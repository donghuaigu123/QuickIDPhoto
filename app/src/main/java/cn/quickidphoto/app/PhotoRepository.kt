package cn.quickidphoto.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

object PhotoRepository {
    fun load(context: Context, uri: Uri, maxDimension: Int = 2600): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("无法读取照片")
        val orientation = resolver.openInputStream(uri).use { input ->
            if (input == null) ExifInterface.ORIENTATION_NORMAL
            else ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        return if (matrix.isIdentity) decoded else Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, matrix, true
        )
    }

    fun save(
        context: Context,
        bitmap: Bitmap,
        spec: PhotoSpec,
        settings: EditorSettings,
        batchSuffix: String? = null
    ): Uri {
        val png = settings.png || settings.transparentBackground
        val extension = if (png) "png" else "jpg"
        val mime = if (png) "image/png" else "image/jpeg"
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = batchSuffix?.let { "_$it" }.orEmpty()
        val name = "快捷证件照_${spec.name}_${time}$suffix.$extension"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/快捷证件照")
            } else {
                val directory = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "快捷证件照")
                if (!directory.exists()) directory.mkdirs()
                put(MediaStore.Images.Media.DATA, File(directory, name).absolutePath)
            }
        }
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: error("无法创建输出文件")
        try {
            context.contentResolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output)
                if (png) {
                    val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    if (!ok) error("照片编码失败")
                } else {
                    encodeJpeg(bitmap, settings.targetKb, settings.jpegQuality, output)
                }
            }
            if (png) writePngDensity(context, uri, spec.dpi) else writeJpegDensity(context, uri, spec.dpi)
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
        return uri
    }

    /**
     * Encodes JPEG, honoring an optional target file size (KB). When [targetKb] > 0,
     * quality is bisected between 15 and [maxQuality] to fit the size cap, matching
     * the behavior of HivisionIDPhotos' `-k` parameter.
     */
    private fun encodeJpeg(bitmap: Bitmap, targetKb: Int, maxQuality: Int, output: java.io.OutputStream) {
        if (targetKb <= 0) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, maxQuality, output)) error("照片编码失败")
            return
        }
        val targetBytes = targetKb * 1024L
        var best: ByteArray? = null
        var low = 15
        var high = maxQuality.coerceIn(15, 100)
        repeat(7) {
            val quality = (low + high) / 2
            val bytes = ByteArrayOutputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) error("照片编码失败")
                stream.toByteArray()
            }
            if (bytes.size <= targetBytes) {
                best = bytes
                low = quality + 1
            } else {
                high = quality - 1
            }
        }
        val fallback = best ?: ByteArrayOutputStream().use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 15, stream)) error("照片编码失败")
            stream.toByteArray()
        }
        output.write(fallback)
    }

    private fun writeJpegDensity(context: Context, uri: Uri, dpi: Int) {
        context.contentResolver.openFileDescriptor(uri, "rw").use { descriptor ->
            requireNotNull(descriptor)
            val exif = ExifInterface(descriptor.fileDescriptor)
            exif.setAttribute(ExifInterface.TAG_X_RESOLUTION, "$dpi/1")
            exif.setAttribute(ExifInterface.TAG_Y_RESOLUTION, "$dpi/1")
            exif.setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, "2")
            exif.saveAttributes()
        }
    }

    /** Inserts a standards-compliant PNG pHYs chunk immediately after IHDR. */
    private fun writePngDensity(context: Context, uri: Uri, dpi: Int) {
        val original = context.contentResolver.openInputStream(uri).use { it?.readBytes() }
            ?: error("无法读取 PNG 输出")
        require(original.size > 33 && original.copyOfRange(12, 16).decodeToString() == "IHDR") {
            "PNG 文件结构无效"
        }
        val pixelsPerMeter = (dpi / 0.0254).toInt()
        val data = ByteBuffer.allocate(9).putInt(pixelsPerMeter).putInt(pixelsPerMeter).put(1).array()
        val type = "pHYs".encodeToByteArray()
        val crc = CRC32().apply { update(type); update(data) }.value
        val chunk = ByteBuffer.allocate(4 + 4 + data.size + 4)
            .putInt(data.size).put(type).put(data).putInt(crc.toInt()).array()
        val output = ByteArrayOutputStream(original.size + chunk.size)
        output.write(original, 0, 33)
        output.write(chunk)
        output.write(original, 33, original.size - 33)
        context.contentResolver.openOutputStream(uri, "wt").use {
            requireNotNull(it)
            it.write(output.toByteArray())
        }
    }
}
