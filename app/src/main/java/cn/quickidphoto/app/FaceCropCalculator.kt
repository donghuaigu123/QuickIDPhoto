package cn.quickidphoto.app

import kotlin.math.max
import kotlin.math.min

/** Deterministic, independently testable face-aware composition algorithm. */
object FaceCropCalculator {
    fun calculate(
        imageWidth: Int,
        imageHeight: Int,
        aspect: Float,
        face: FaceBox?,
        faceScale: Float = 0.57f,
        headTopMargin: Float = 0.10f,
        horizontalOffset: Float = 0f
    ): CropBox {
        require(imageWidth > 0 && imageHeight > 0 && aspect > 0)
        val iw = imageWidth.toFloat()
        val ih = imageHeight.toFloat()
        if (face == null || face.width <= 0f || face.height <= 0f) {
            return centered(iw, ih, aspect)
        }

        val safeFaceScale = faceScale.coerceIn(0.30f, 0.88f)
        var cropHeight = face.height / safeFaceScale
        var cropWidth = cropHeight * aspect
        val fitScale = min(1f, min(iw / cropWidth, ih / cropHeight))
        cropWidth *= fitScale
        cropHeight *= fitScale

        val centerX = face.centerX + horizontalOffset.coerceIn(-0.35f, 0.35f) * cropWidth
        val top = face.top - cropHeight * headTopMargin.coerceIn(0.01f, 0.35f)
        val left = centerX - cropWidth / 2f
        return clamp(left, top, cropWidth, cropHeight, iw, ih)
    }

    fun centered(imageWidth: Float, imageHeight: Float, aspect: Float): CropBox {
        val width = min(imageWidth, imageHeight * aspect)
        val height = width / aspect
        val left = (imageWidth - width) / 2f
        val top = (imageHeight - height) / 2f
        return CropBox(left, top, left + width, top + height)
    }

    private fun clamp(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        imageWidth: Float,
        imageHeight: Float
    ): CropBox {
        val safeLeft = max(0f, min(left, imageWidth - width))
        val safeTop = max(0f, min(top, imageHeight - height))
        return CropBox(safeLeft, safeTop, safeLeft + width, safeTop + height)
    }
}
