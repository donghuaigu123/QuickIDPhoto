package cn.quickidphoto.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceCropCalculatorTest {
    @Test
    fun noFace_usesLargestCenteredCrop() {
        val crop = FaceCropCalculator.calculate(1200, 900, 4f / 3f, null)
        assertEquals(1200f, crop.width, 0.01f)
        assertEquals(900f, crop.height, 0.01f)
        assertEquals(0f, crop.left, 0.01f)
    }

    @Test
    fun face_controlsHeightAndTopMargin() {
        val face = FaceBox(450f, 300f, 550f, 500f)
        val crop = FaceCropCalculator.calculate(1000, 1200, 0.7f, face,
            faceScale = 0.5f, headTopMargin = 0.1f)
        assertEquals(400f, crop.height, 0.01f)
        assertEquals(280f, crop.width, 0.01f)
        assertEquals(260f, crop.top, 0.01f)
        assertEquals(360f, crop.left, 0.01f)
    }

    @Test
    fun crop_neverLeavesImageBounds() {
        val face = FaceBox(0f, 0f, 180f, 220f)
        val crop = FaceCropCalculator.calculate(800, 1000, 0.75f, face)
        assertTrue(crop.left >= 0f)
        assertTrue(crop.top >= 0f)
        assertTrue(crop.right <= 800f)
        assertTrue(crop.bottom <= 1000f)
    }

    @Test
    fun extremeFaceSize_isSafelyFitted() {
        val face = FaceBox(100f, 100f, 900f, 1100f)
        val crop = FaceCropCalculator.calculate(1000, 1200, 1f, face)
        assertTrue(crop.width <= 1000f)
        assertTrue(crop.height <= 1200f)
    }

    @Test
    fun horizontalAdjustment_movesCrop() {
        val face = FaceBox(400f, 300f, 600f, 600f)
        val normal = FaceCropCalculator.calculate(1200, 1400, 0.75f, face, horizontalOffset = 0f)
        val shifted = FaceCropCalculator.calculate(1200, 1400, 0.75f, face, horizontalOffset = 0.1f)
        assertTrue(shifted.left > normal.left)
    }
}
