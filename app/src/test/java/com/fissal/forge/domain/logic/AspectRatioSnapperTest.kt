package com.fissal.forge.domain.logic

import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.CropRectNormalized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AspectRatioSnapperTest {

    @Test
    fun landscapeMediaSnapsToClosestCanonical16x9Or4x3() {
        assertEquals(AspectPreset.RATIO_16_9, AspectRatioSnapper.closestDefaultPreset(3840, 2160))
        assertEquals(AspectPreset.RATIO_16_9, AspectRatioSnapper.closestDefaultPreset(1920, 1200))
        assertEquals(AspectPreset.RATIO_4_3, AspectRatioSnapper.closestDefaultPreset(4000, 3000))
        assertEquals(AspectPreset.RATIO_4_3, AspectRatioSnapper.closestDefaultPreset(6000, 4000)) // 3:2 is closer to 4:3 than 16:9
    }

    @Test
    fun portraitMediaSnapsToClosestCanonical9x16Or3x4() {
        assertEquals(AspectPreset.RATIO_9_16, AspectRatioSnapper.closestDefaultPreset(1080, 1920))
        assertEquals(AspectPreset.RATIO_3_4, AspectRatioSnapper.closestDefaultPreset(3024, 4032))
    }

    @Test
    fun evenPixelClampingGuaranteesEvenDimensionsEvenOnOddSources() {
        val crop = AspectRatioSnapper.toEvenPixelCrop(
            rect = CropRectNormalized(0f, 0f, 1f, 1f),
            effectiveWidth = 1081,
            effectiveHeight = 1351
        )
        assertEquals(0, crop.width % 2)
        assertEquals(0, crop.height % 2)
        assertTrue(crop.x >= 0 && crop.y >= 0)
        assertTrue(crop.x + crop.width <= 1081)
        assertTrue(crop.y + crop.height <= 1351)
    }

    @Test
    fun resolveAutoClosestProducesValidCenteredBox() {
        val (preset, rect) = AspectRatioSnapper.resolvePresetRect(AspectPreset.AUTO_CLOSEST, 6000, 4000)
        assertEquals(AspectPreset.RATIO_4_3, preset)
        val px = AspectRatioSnapper.toEvenPixelCrop(rect, 6000, 4000)
        assertEquals(4000, px.height)
        assertEquals(0, px.width % 2)
        // 4000 * 4 / 3 = 5333 -> clamped even 5332
        assertTrue(px.width in 5330..5334)
    }
}
