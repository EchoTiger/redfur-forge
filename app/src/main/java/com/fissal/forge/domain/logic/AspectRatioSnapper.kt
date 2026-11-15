package com.fissal.forge.domain.logic

import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.CropRectNormalized
import com.fissal.forge.domain.model.PixelCropRect
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Pure geometry helpers for aspect-ratio snapping and encoder-safe even-pixel crop clamping.
 */
object AspectRatioSnapper {

    /**
     * Picks the closest canonical aspect preset based on the source orientation:
     * - Landscape or square: closest of 16:9 vs 4:3
     * - Portrait: closest of 9:16 vs 3:4
     */
    fun closestDefaultPreset(effectiveWidth: Int, effectiveHeight: Int): AspectPreset {
        if (effectiveWidth <= 0 || effectiveHeight <= 0) return AspectPreset.RATIO_16_9
        val aspect = effectiveWidth.toDouble() / effectiveHeight.toDouble()
        return if (effectiveWidth >= effectiveHeight) {
            val d16x9 = abs(ln(aspect / (16.0 / 9.0)))
            val d4x3 = abs(ln(aspect / (4.0 / 3.0)))
            if (d16x9 <= d4x3) AspectPreset.RATIO_16_9 else AspectPreset.RATIO_4_3
        } else {
            val d9x16 = abs(ln(aspect / (9.0 / 16.0)))
            val d3x4 = abs(ln(aspect / (3.0 / 4.0)))
            if (d9x16 <= d3x4) AspectPreset.RATIO_9_16 else AspectPreset.RATIO_3_4
        }
    }

    /**
     * Computes the largest centered normalized [0..1] crop rectangle matching [ratioW]:[ratioH].
     */
    fun centeredRectForAspect(
        effectiveWidth: Int,
        effectiveHeight: Int,
        ratioW: Int,
        ratioH: Int
    ): CropRectNormalized {
        if (effectiveWidth <= 0 || effectiveHeight <= 0 || ratioW <= 0 || ratioH <= 0) {
            return CropRectNormalized()
        }
        val sourceRatio = effectiveWidth.toFloat() / effectiveHeight.toFloat()
        val targetRatio = ratioW.toFloat() / ratioH.toFloat()
        return if (sourceRatio > targetRatio) {
            val normW = (targetRatio / sourceRatio).coerceIn(0.05f, 1f)
            val left = (1f - normW) / 2f
            CropRectNormalized(left = left, top = 0f, right = left + normW, bottom = 1f)
        } else {
            val normH = (sourceRatio / targetRatio).coerceIn(0.05f, 1f)
            val top = (1f - normH) / 2f
            CropRectNormalized(left = 0f, top = top, right = 1f, bottom = top + normH)
        }
    }

    /**
     * Resolves [AspectPreset.AUTO_CLOSEST] or any explicit preset into its concrete preset and centered crop box.
     */
    fun resolvePresetRect(
        preset: AspectPreset,
        effectiveWidth: Int,
        effectiveHeight: Int
    ): Pair<AspectPreset, CropRectNormalized> {
        val concrete = if (preset == AspectPreset.AUTO_CLOSEST) {
            closestDefaultPreset(effectiveWidth, effectiveHeight)
        } else {
            preset
        }
        val rect = if (concrete.ratioW != null && concrete.ratioH != null) {
            centeredRectForAspect(effectiveWidth, effectiveHeight, concrete.ratioW, concrete.ratioH)
        } else {
            CropRectNormalized(0f, 0f, 1f, 1f)
        }
        return concrete to rect
    }

    /**
     * Converts normalized [0..1] coordinates into strictly even-width, even-height pixel bounds
     * guaranteed not to exceed [effectiveWidth] or [effectiveHeight] (even on odd source dimensions).
     */
    fun toEvenPixelCrop(
        rect: CropRectNormalized,
        effectiveWidth: Int,
        effectiveHeight: Int
    ): PixelCropRect {
        if (effectiveWidth < 2 || effectiveHeight < 2) {
            return PixelCropRect(0, 0, effectiveWidth.coerceAtLeast(1), effectiveHeight.coerceAtLeast(1))
        }
        val left = rect.left.coerceIn(0f, 0.98f)
        val right = rect.right.coerceIn(left + 0.02f, 1f)
        val top = rect.top.coerceIn(0f, 0.98f)
        val bottom = rect.bottom.coerceIn(top + 0.02f, 1f)

        val maxEvenW = (effectiveWidth and 1.inv()).coerceAtLeast(2)
        val maxEvenH = (effectiveHeight and 1.inv()).coerceAtLeast(2)

        val rawW = ((right - left) * effectiveWidth).roundToInt()
        val rawH = ((bottom - top) * effectiveHeight).roundToInt()

        val evenW = (rawW and 1.inv()).coerceIn(2, maxEvenW)
        val evenH = (rawH and 1.inv()).coerceIn(2, maxEvenH)

        val x = (left * effectiveWidth).roundToInt().coerceIn(0, (effectiveWidth - evenW).coerceAtLeast(0))
        val y = (top * effectiveHeight).roundToInt().coerceIn(0, (effectiveHeight - evenH).coerceAtLeast(0))

        return PixelCropRect(x = x, y = y, width = evenW, height = evenH)
    }
}
