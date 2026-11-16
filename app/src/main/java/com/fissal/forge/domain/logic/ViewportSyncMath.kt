package com.fissal.forge.domain.logic

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

enum class TapZone {
    LEFT,
    CENTER,
    RIGHT
}

data class NormalizedViewportState(
    val focusNormX: Float = 0.5f,
    val focusNormY: Float = 0.5f,
    val zoomScale: Float = 1f,
    val referenceAspectRatio: Float? = null
)

data class ViewportTransform(
    val fittedWidthPx: Float,
    val fittedHeightPx: Float,
    val scaledWidthPx: Float,
    val scaledHeightPx: Float,
    val translationXPx: Float,
    val translationYPx: Float,
    val effectiveZoom: Float
)

/**
 * Pure JVM viewport geometry and touch-synchronization math for fullscreen A/B sample
 * inspection. Keeps `(X, Y, Zoom)` pixel-locked across differing resolutions (`480p`/`720p`/`1080p`)
 * and ratio-compensated across differing aspect crops (`16:9` <-> `4:3` <-> `1:1`) with zero
 * black-margin overscroll slack.
 */
object ViewportSyncMath {

    private const val ASPECT_MATCH_LN_TOLERANCE = 0.015f

    fun computeTransform(
        state: NormalizedViewportState,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        imageWidthPx: Int,
        imageHeightPx: Int
    ): ViewportTransform {
        val safeZoom = sanitizeZoom(state.zoomScale, minZoom = 1f, maxZoom = Float.MAX_VALUE)
        if (!viewportWidthPx.isFinite() ||
            !viewportHeightPx.isFinite() ||
            viewportWidthPx <= 0f ||
            viewportHeightPx <= 0f ||
            imageWidthPx <= 0 ||
            imageHeightPx <= 0
        ) {
            return ViewportTransform(
                fittedWidthPx = 0f,
                fittedHeightPx = 0f,
                scaledWidthPx = 0f,
                scaledHeightPx = 0f,
                translationXPx = 0f,
                translationYPx = 0f,
                effectiveZoom = safeZoom
            )
        }

        val imgAspect = imageWidthPx.toFloat() / imageHeightPx.toFloat()
        val refAspect = state.referenceAspectRatio?.takeIf { it.isFinite() && it > 0f }
        val isMatchingAspect = refAspect == null ||
            abs(ln(imgAspect / refAspect)) <= ASPECT_MATCH_LN_TOLERANCE

        val fitAspect = if (isMatchingAspect && refAspect != null) refAspect else imgAspect
        val viewportAspect = viewportWidthPx / viewportHeightPx
        val fittedWidthPx: Float
        val fittedHeightPx: Float
        if (fitAspect >= viewportAspect) {
            fittedWidthPx = viewportWidthPx
            fittedHeightPx = viewportWidthPx / fitAspect
        } else {
            fittedHeightPx = viewportHeightPx
            fittedWidthPx = viewportHeightPx * fitAspect
        }

        val safeFocusX = sanitizeNorm(state.focusNormX)
        val safeFocusY = sanitizeNorm(state.focusNormY)
        val (mappedFocusX, mappedFocusY) = if (isMatchingAspect) {
            safeFocusX to safeFocusY
        } else {
            mapNormalizedFocusAcrossAspects(
                focusNormX = safeFocusX,
                focusNormY = safeFocusY,
                refAspect = refAspect!!,
                imgAspect = imgAspect
            )
        }

        val scaledWidthPx = fittedWidthPx * safeZoom
        val scaledHeightPx = fittedHeightPx * safeZoom

        val maxTransX = max(0f, (scaledWidthPx - viewportWidthPx) * 0.5f)
        val maxTransY = max(0f, (scaledHeightPx - viewportHeightPx) * 0.5f)

        val rawTransX = (0.5f - mappedFocusX) * scaledWidthPx
        val rawTransY = (0.5f - mappedFocusY) * scaledHeightPx

        return ViewportTransform(
            fittedWidthPx = fittedWidthPx,
            fittedHeightPx = fittedHeightPx,
            scaledWidthPx = scaledWidthPx,
            scaledHeightPx = scaledHeightPx,
            translationXPx = rawTransX.coerceIn(-maxTransX, maxTransX),
            translationYPx = rawTransY.coerceIn(-maxTransY, maxTransY),
            effectiveZoom = safeZoom
        )
    }

    fun applyGesture(
        state: NormalizedViewportState,
        panDeltaXPx: Float,
        panDeltaYPx: Float,
        zoomChange: Float,
        centroidXPx: Float,
        centroidYPx: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        imageWidthPx: Int,
        imageHeightPx: Int,
        minZoom: Float = 1f,
        maxZoom: Float = 8f
    ): NormalizedViewportState {
        if (!viewportWidthPx.isFinite() ||
            !viewportHeightPx.isFinite() ||
            viewportWidthPx <= 0f ||
            viewportHeightPx <= 0f ||
            imageWidthPx <= 0 ||
            imageHeightPx <= 0
        ) {
            return state
        }

        val current = computeTransform(
            state = state,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            imageWidthPx = imageWidthPx,
            imageHeightPx = imageHeightPx
        )
        if (current.fittedWidthPx <= 0f || current.fittedHeightPx <= 0f) {
            return state
        }

        val safeMinZoom = if (minZoom.isFinite() && minZoom >= 1f) minZoom else 1f
        val safeMaxZoom = if (maxZoom.isFinite() && maxZoom >= safeMinZoom) maxZoom else safeMinZoom
        val safeZoomChange = if (zoomChange.isFinite() && zoomChange > 0f) zoomChange else 1f
        val safePanX = if (panDeltaXPx.isFinite()) panDeltaXPx else 0f
        val safePanY = if (panDeltaYPx.isFinite()) panDeltaYPx else 0f
        val safeCentroidX = if (centroidXPx.isFinite()) centroidXPx else viewportWidthPx * 0.5f
        val safeCentroidY = if (centroidYPx.isFinite()) centroidYPx else viewportHeightPx * 0.5f

        val curFocusX = if (current.scaledWidthPx > 0f) {
            0.5f - (current.translationXPx / current.scaledWidthPx)
        } else {
            0.5f
        }
        val curFocusY = if (current.scaledHeightPx > 0f) {
            0.5f - (current.translationYPx / current.scaledHeightPx)
        } else {
            0.5f
        }

        val nextZoom = (current.effectiveZoom * safeZoomChange).coerceIn(safeMinZoom, safeMaxZoom)
        val nextScaledW = current.fittedWidthPx * nextZoom
        val nextScaledH = current.fittedHeightPx * nextZoom

        val cRelX = safeCentroidX - viewportWidthPx * 0.5f
        val cRelY = safeCentroidY - viewportHeightPx * 0.5f

        val rawNextFocusX = curFocusX +
            cRelX * (1f / current.scaledWidthPx - 1f / nextScaledW) -
            (safePanX / nextScaledW)
        val rawNextFocusY = curFocusY +
            cRelY * (1f / current.scaledHeightPx - 1f / nextScaledH) -
            (safePanY / nextScaledH)

        return NormalizedViewportState(
            focusNormX = clampFocusNorm(rawNextFocusX, viewportWidthPx, nextScaledW),
            focusNormY = clampFocusNorm(rawNextFocusY, viewportHeightPx, nextScaledH),
            zoomScale = nextZoom,
            referenceAspectRatio = imageWidthPx.toFloat() / imageHeightPx.toFloat()
        )
    }

    fun toggleDoubleTapZoom(
        state: NormalizedViewportState,
        tapXPx: Float,
        tapYPx: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        imageWidthPx: Int,
        imageHeightPx: Int,
        targetZoom: Float = 2.5f
    ): NormalizedViewportState {
        if (!viewportWidthPx.isFinite() ||
            !viewportHeightPx.isFinite() ||
            viewportWidthPx <= 0f ||
            viewportHeightPx <= 0f ||
            imageWidthPx <= 0 ||
            imageHeightPx <= 0
        ) {
            return state
        }

        val imgAspect = imageWidthPx.toFloat() / imageHeightPx.toFloat()
        val safeCurZoom = sanitizeZoom(state.zoomScale, minZoom = 1f, maxZoom = Float.MAX_VALUE)
        if (safeCurZoom > 1.05f) {
            return NormalizedViewportState(
                focusNormX = 0.5f,
                focusNormY = 0.5f,
                zoomScale = 1f,
                referenceAspectRatio = imgAspect
            )
        }

        val current = computeTransform(
            state = state,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            imageWidthPx = imageWidthPx,
            imageHeightPx = imageHeightPx
        )
        if (current.scaledWidthPx <= 0f || current.scaledHeightPx <= 0f) {
            return state
        }

        val safeTapX = if (tapXPx.isFinite()) tapXPx else viewportWidthPx * 0.5f
        val safeTapY = if (tapYPx.isFinite()) tapYPx else viewportHeightPx * 0.5f
        val tapRelX = safeTapX - viewportWidthPx * 0.5f
        val tapRelY = safeTapY - viewportHeightPx * 0.5f

        val tappedNormX = (0.5f + (tapRelX - current.translationXPx) / current.scaledWidthPx).coerceIn(0f, 1f)
        val tappedNormY = (0.5f + (tapRelY - current.translationYPx) / current.scaledHeightPx).coerceIn(0f, 1f)

        val safeTargetZoom = sanitizeZoom(targetZoom, minZoom = 1f, maxZoom = Float.MAX_VALUE)
        val nextScaledW = current.fittedWidthPx * safeTargetZoom
        val nextScaledH = current.fittedHeightPx * safeTargetZoom

        return NormalizedViewportState(
            focusNormX = clampFocusNorm(tappedNormX, viewportWidthPx, nextScaledW),
            focusNormY = clampFocusNorm(tappedNormY, viewportHeightPx, nextScaledH),
            zoomScale = safeTargetZoom,
            referenceAspectRatio = imgAspect
        )
    }

    fun classifyTapZone(
        tapXPx: Float,
        viewportWidthPx: Float,
        edgeFraction: Float = 0.30f
    ): TapZone {
        if (!viewportWidthPx.isFinite() || viewportWidthPx <= 0f || !tapXPx.isFinite()) {
            return TapZone.CENTER
        }
        val safeFraction = if (edgeFraction.isFinite()) edgeFraction.coerceIn(0f, 0.49f) else 0.30f
        val leftEdge = viewportWidthPx * safeFraction
        val rightEdge = viewportWidthPx * (1f - safeFraction)
        return when {
            tapXPx < leftEdge -> TapZone.LEFT
            tapXPx > rightEdge -> TapZone.RIGHT
            else -> TapZone.CENTER
        }
    }

    fun clampActiveIndexAfterRemoval(previousActiveIndex: Int, newTabCount: Int): Int {
        if (newTabCount <= 0) return 0
        return previousActiveIndex.coerceIn(0, newTabCount - 1)
    }

    private fun mapNormalizedFocusAcrossAspects(
        focusNormX: Float,
        focusNormY: Float,
        refAspect: Float,
        imgAspect: Float
    ): Pair<Float, Float> {
        val dx = focusNormX - 0.5f
        val dy = focusNormY - 0.5f
        val (dxPrime, dyPrime) = when {
            min(refAspect, imgAspect) >= 1f -> {
                // Landscape / square crops preserve height and adjust horizontal span.
                dx * (refAspect / imgAspect) to dy
            }
            max(refAspect, imgAspect) <= 1f -> {
                // Portrait / square crops preserve width and adjust vertical span.
                dx to dy * (imgAspect / refAspect)
            }
            refAspect > imgAspect -> {
                // Wider reference -> narrower portrait crop preserving vertical span.
                dx * (refAspect / imgAspect) to dy
            }
            else -> {
                // Portrait reference -> wider landscape crop preserving horizontal span.
                dx to dy * (imgAspect / refAspect)
            }
        }
        return (0.5f + dxPrime).coerceIn(0f, 1f) to (0.5f + dyPrime).coerceIn(0f, 1f)
    }

    private fun clampFocusNorm(
        focusNorm: Float,
        viewportSpanPx: Float,
        scaledSpanPx: Float
    ): Float {
        if (!focusNorm.isFinite() || scaledSpanPx <= viewportSpanPx || scaledSpanPx <= 0f) {
            return 0.5f
        }
        val halfVisible = (viewportSpanPx * 0.5f) / scaledSpanPx
        val minFocus = halfVisible.coerceIn(0f, 0.5f)
        val maxFocus = (1f - halfVisible).coerceIn(0.5f, 1f)
        return focusNorm.coerceIn(minFocus, maxFocus)
    }

    private fun sanitizeNorm(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 0.5f

    private fun sanitizeZoom(zoom: Float, minZoom: Float, maxZoom: Float): Float =
        if (zoom.isFinite()) zoom.coerceIn(minZoom, maxZoom) else minZoom
}
