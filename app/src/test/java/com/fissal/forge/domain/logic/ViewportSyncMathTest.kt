package com.fissal.forge.domain.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class ViewportSyncMathTest {

    @Test
    fun computeTransformIsPixelIdenticalAcross480p720pAnd1080pAtAllZoomLevels() {
        val stateBase = NormalizedViewportState(
            focusNormX = 0.72f,
            focusNormY = 0.31f,
            zoomScale = 1.0f,
            referenceAspectRatio = 1920f / 1080f
        )

        val viewports = listOf(
            1080f to 2340f, // Portrait phone viewport
            2340f to 1080f  // Landscape phone viewport
        )
        val zooms = listOf(1.0f, 2.5f, 4.0f)

        for ((vpW, vpH) in viewports) {
            for (zoom in zooms) {
                val state = stateBase.copy(zoomScale = zoom)
                val t480 = ViewportSyncMath.computeTransform(state, vpW, vpH, 854, 480)
                val t720 = ViewportSyncMath.computeTransform(state, vpW, vpH, 1280, 720)
                val t1080 = ViewportSyncMath.computeTransform(state, vpW, vpH, 1920, 1080)

                assertEquals(t1080.fittedWidthPx, t720.fittedWidthPx, 0f)
                assertEquals(t1080.fittedHeightPx, t720.fittedHeightPx, 0f)
                assertEquals(t1080.scaledWidthPx, t720.scaledWidthPx, 0f)
                assertEquals(t1080.scaledHeightPx, t720.scaledHeightPx, 0f)
                assertEquals(t1080.translationXPx, t720.translationXPx, 0f)
                assertEquals(t1080.translationYPx, t720.translationYPx, 0f)
                assertEquals(t1080.effectiveZoom, t720.effectiveZoom, 0f)

                assertEquals(t1080.fittedWidthPx, t480.fittedWidthPx, 0f)
                assertEquals(t1080.fittedHeightPx, t480.fittedHeightPx, 0f)
                assertEquals(t1080.scaledWidthPx, t480.scaledWidthPx, 0f)
                assertEquals(t1080.scaledHeightPx, t480.scaledHeightPx, 0f)
                assertEquals(t1080.translationXPx, t480.translationXPx, 0f)
                assertEquals(t1080.translationYPx, t480.translationYPx, 0f)
                assertEquals(t1080.effectiveZoom, t480.effectiveZoom, 0f)
            }
        }
    }

    @Test
    fun applyGestureClampsCornersWithoutOverscrollSlackAndAnchorsPinchCentroid() {
        val vpW = 1080f
        val vpH = 1920f
        val imgW = 1920
        val imgH = 1080

        // Pinch zoom around off-center centroid (right & slightly above center)
        var state = NormalizedViewportState(referenceAspectRatio = imgW.toFloat() / imgH.toFloat())
        val centroidX = 810f // +270px from viewport center
        val centroidY = 860f // -100px from viewport center
        val beforePinch = ViewportSyncMath.computeTransform(state, vpW, vpH, imgW, imgH)
        val imgXBefore = 0.5f + ((centroidX - vpW * 0.5f) - beforePinch.translationXPx) / beforePinch.scaledWidthPx

        repeat(4) {
            state = ViewportSyncMath.applyGesture(
                state = state,
                panDeltaXPx = 0f,
                panDeltaYPx = 0f,
                zoomChange = 1.25f,
                centroidXPx = centroidX,
                centroidYPx = centroidY,
                viewportWidthPx = vpW,
                viewportHeightPx = vpH,
                imageWidthPx = imgW,
                imageHeightPx = imgH
            )
        }
        assertEquals(1.25f * 1.25f * 1.25f * 1.25f, state.zoomScale, 1e-4f)
        val afterPinch = ViewportSyncMath.computeTransform(state, vpW, vpH, imgW, imgH)
        val imgXAfter = 0.5f + ((centroidX - vpW * 0.5f) - afterPinch.translationXPx) / afterPinch.scaledWidthPx
        assertEquals(imgXBefore, imgXAfter, 1e-4f)

        // Drag aggressively toward all four corners and confirm zero black-gap overscroll & zero dead-zone slack
        val cornerDrags = listOf(
            50_000f to 50_000f,
            -50_000f to 50_000f,
            50_000f to -50_000f,
            -50_000f to -50_000f
        )
        for ((dragX, dragY) in cornerDrags) {
            state = ViewportSyncMath.applyGesture(
                state = state,
                panDeltaXPx = dragX,
                panDeltaYPx = dragY,
                zoomChange = 1f,
                centroidXPx = vpW * 0.5f,
                centroidYPx = vpH * 0.5f,
                viewportWidthPx = vpW,
                viewportHeightPx = vpH,
                imageWidthPx = imgW,
                imageHeightPx = imgH
            )
            val t = ViewportSyncMath.computeTransform(state, vpW, vpH, imgW, imgH)
            val maxTransX = max(0f, (t.scaledWidthPx - vpW) * 0.5f)
            val maxTransY = max(0f, (t.scaledHeightPx - vpH) * 0.5f)
            assertTrue(abs(t.translationXPx) <= maxTransX + 1e-4f)
            assertTrue(abs(t.translationYPx) <= maxTransY + 1e-4f)

            val halfVisX = (vpW * 0.5f) / t.scaledWidthPx
            if (t.scaledWidthPx > vpW) {
                assertTrue(state.focusNormX in (halfVisX - 1e-4f)..(1f - halfVisX + 1e-4f))
            } else {
                assertEquals(0.5f, state.focusNormX, 1e-5f)
            }
        }

        // Immediately panning 100px back from clamped right edge must move translation right away (no overscroll slack)
        val atLeftClamped = ViewportSyncMath.applyGesture(
            state = state,
            panDeltaXPx = 50_000f,
            panDeltaYPx = 0f,
            zoomChange = 1f,
            centroidXPx = vpW * 0.5f,
            centroidYPx = vpH * 0.5f,
            viewportWidthPx = vpW,
            viewportHeightPx = vpH,
            imageWidthPx = imgW,
            imageHeightPx = imgH
        )
        val tClamped = ViewportSyncMath.computeTransform(atLeftClamped, vpW, vpH, imgW, imgH)
        val nudgedBack = ViewportSyncMath.applyGesture(
            state = atLeftClamped,
            panDeltaXPx = -120f,
            panDeltaYPx = 0f,
            zoomChange = 1f,
            centroidXPx = vpW * 0.5f,
            centroidYPx = vpH * 0.5f,
            viewportWidthPx = vpW,
            viewportHeightPx = vpH,
            imageWidthPx = imgW,
            imageHeightPx = imgH
        )
        val tNudged = ViewportSyncMath.computeTransform(nudgedBack, vpW, vpH, imgW, imgH)
        assertEquals(tClamped.translationXPx - 120f, tNudged.translationXPx, 1e-2f)

        // Zoom bounds clamp cleanly to [1f..8f]
        val zoomedOut = ViewportSyncMath.applyGesture(
            state = nudgedBack,
            panDeltaXPx = 0f,
            panDeltaYPx = 0f,
            zoomChange = 0.05f,
            centroidXPx = vpW * 0.5f,
            centroidYPx = vpH * 0.5f,
            viewportWidthPx = vpW,
            viewportHeightPx = vpH,
            imageWidthPx = imgW,
            imageHeightPx = imgH
        )
        assertEquals(1f, zoomedOut.zoomScale, 1e-5f)
        assertEquals(0.5f, zoomedOut.focusNormX, 1e-5f)
        assertEquals(0.5f, zoomedOut.focusNormY, 1e-5f)

        val zoomedMax = ViewportSyncMath.applyGesture(
            state = zoomedOut,
            panDeltaXPx = 0f,
            panDeltaYPx = 0f,
            zoomChange = 20f,
            centroidXPx = vpW * 0.5f,
            centroidYPx = vpH * 0.5f,
            viewportWidthPx = vpW,
            viewportHeightPx = vpH,
            imageWidthPx = imgW,
            imageHeightPx = imgH
        )
        assertEquals(8f, zoomedMax.zoomScale, 1e-5f)
    }

    @Test
    fun differingAspectRatiosKeepSubjectAlignedAndStrictlyWithinBounds() {
        val vpW = 1080f
        val vpH = 1920f

        // 16:9 (1920x1080) subject +240px right of center -> focusNormX = 1200 / 1920 = 0.625f
        val state16x9 = NormalizedViewportState(
            focusNormX = 0.625f,
            focusNormY = 0.40f,
            zoomScale = 3.0f,
            referenceAspectRatio = 1920f / 1080f
        )

        val t16x9 = ViewportSyncMath.computeTransform(state16x9, vpW, vpH, 1920, 1080)
        val t4x3 = ViewportSyncMath.computeTransform(state16x9, vpW, vpH, 1440, 1080)
        val t1x1 = ViewportSyncMath.computeTransform(state16x9, vpW, vpH, 1080, 1080)

        // In 1440x1080 (same 1080 height), +240px right of center is 960/1440 = 0.66667f.
        // Since fittedW = 1080 in all 3 cases, scaledW = 3240:
        // 16:9 transX = (0.5 - 0.625) * 3240 = -405px
        // 4:3  transX = (0.5 - 0.66667) * 3240 = -540px
        // 1:1  transX = (0.5 - 0.72222) * 3240 = -720px
        assertEquals(-405f, t16x9.translationXPx, 0.5f)
        assertEquals(-540f, t4x3.translationXPx, 0.5f)
        assertEquals(-720f, t1x1.translationXPx, 0.5f)

        // Inverse: panning in 4:3 and flipping back to 16:9 preserves alignment symmetrically
        val state4x3 = NormalizedViewportState(
            focusNormX = 2f / 3f,
            focusNormY = 0.40f,
            zoomScale = 3.0f,
            referenceAspectRatio = 1440f / 1080f
        )
        val tBackTo16x9 = ViewportSyncMath.computeTransform(state4x3, vpW, vpH, 1920, 1080)
        assertEquals(-405f, tBackTo16x9.translationXPx, 0.5f)

        // Extreme edge focus in 16:9 -> narrower 1:1 & 9:16 must clamp strictly inside visible bounds (zero black gap)
        val edgeState = NormalizedViewportState(
            focusNormX = 0.98f,
            focusNormY = 0.90f,
            zoomScale = 2.0f,
            referenceAspectRatio = 16f / 9f
        )
        for ((w, h) in listOf(1440 to 1080, 1080 to 1080, 1080 to 1920)) {
            val t = ViewportSyncMath.computeTransform(edgeState, vpW, vpH, w, h)
            val maxTransX = max(0f, (t.scaledWidthPx - vpW) * 0.5f)
            val maxTransY = max(0f, (t.scaledHeightPx - vpH) * 0.5f)
            assertTrue(abs(t.translationXPx) <= maxTransX + 1e-4f)
            assertTrue(abs(t.translationYPx) <= maxTransY + 1e-4f)
        }
    }

    @Test
    fun toggleDoubleTapZoomZoomsOntoTappedPointAndResetsOnSecondDoubleTap() {
        val vpW = 1080f
        val vpH = 1920f
        val imgW = 1920
        val imgH = 1080

        val initial = NormalizedViewportState(referenceAspectRatio = imgW.toFloat() / imgH.toFloat())
        // Tap on right-side detail at x = 810 (75% across fitted 1080px image)
        val zoomed = ViewportSyncMath.toggleDoubleTapZoom(
            state = initial,
            tapXPx = 810f,
            tapYPx = 960f,
            viewportWidthPx = vpW,
            viewportHeightPx = vpH,
            imageWidthPx = imgW,
            imageHeightPx = imgH,
            targetZoom = 2.5f
        )
        assertEquals(2.5f, zoomed.zoomScale, 1e-5f)
        assertEquals(0.75f, zoomed.focusNormX, 1e-4f)
        assertEquals(0.5f, zoomed.focusNormY, 1e-4f) // scaledH (1518.75) <= vpH (1920), stays centered

        // Tapping right near the corner clamps cleanly without overscroll
        val cornerZoomed = ViewportSyncMath.toggleDoubleTapZoom(
            state = initial,
            tapXPx = 1075f,
            tapYPx = 960f,
            viewportWidthPx = vpW,
            viewportHeightPx = vpH,
            imageWidthPx = imgW,
            imageHeightPx = imgH,
            targetZoom = 2.5f
        )
        val cornerTransform = ViewportSyncMath.computeTransform(cornerZoomed, vpW, vpH, imgW, imgH)
        val maxTransX = max(0f, (cornerTransform.scaledWidthPx - vpW) * 0.5f)
        assertTrue(abs(cornerTransform.translationXPx) <= maxTransX + 1e-4f)
        assertEquals(0.8f, cornerZoomed.focusNormX, 1e-4f)

        // Second double-tap resets smoothly to centered 1.0x
        val reset = ViewportSyncMath.toggleDoubleTapZoom(
            state = zoomed,
            tapXPx = 540f,
            tapYPx = 960f,
            viewportWidthPx = vpW,
            viewportHeightPx = vpH,
            imageWidthPx = imgW,
            imageHeightPx = imgH,
            targetZoom = 2.5f
        )
        assertEquals(1.0f, reset.zoomScale, 1e-5f)
        assertEquals(0.5f, reset.focusNormX, 1e-5f)
        assertEquals(0.5f, reset.focusNormY, 1e-5f)
    }

    @Test
    fun classifyTapZoneRespectsExactLeftCenterRightBoundaries() {
        val width = 1000f
        assertEquals(TapZone.LEFT, ViewportSyncMath.classifyTapZone(0f, width))
        assertEquals(TapZone.LEFT, ViewportSyncMath.classifyTapZone(299.9f, width))
        assertEquals(TapZone.CENTER, ViewportSyncMath.classifyTapZone(300f, width))
        assertEquals(TapZone.CENTER, ViewportSyncMath.classifyTapZone(500f, width))
        assertEquals(TapZone.CENTER, ViewportSyncMath.classifyTapZone(700f, width))
        assertEquals(TapZone.RIGHT, ViewportSyncMath.classifyTapZone(700.1f, width))
        assertEquals(TapZone.RIGHT, ViewportSyncMath.classifyTapZone(1000f, width))

        // Invalid viewport width safely defaults to CENTER
        assertEquals(TapZone.CENTER, ViewportSyncMath.classifyTapZone(10f, 0f))
        assertEquals(TapZone.CENTER, ViewportSyncMath.classifyTapZone(10f, -100f))
        assertEquals(TapZone.CENTER, ViewportSyncMath.classifyTapZone(Float.NaN, width))
    }

    @Test
    fun clampActiveIndexAfterRemovalGuardsAllDeletionPositions() {
        assertEquals(0, ViewportSyncMath.clampActiveIndexAfterRemoval(0, 0))
        assertEquals(0, ViewportSyncMath.clampActiveIndexAfterRemoval(2, -1))
        assertEquals(0, ViewportSyncMath.clampActiveIndexAfterRemoval(0, 3))
        assertEquals(1, ViewportSyncMath.clampActiveIndexAfterRemoval(1, 3))
        assertEquals(2, ViewportSyncMath.clampActiveIndexAfterRemoval(3, 3))
        assertEquals(0, ViewportSyncMath.clampActiveIndexAfterRemoval(-2, 3))
    }

    @Test
    fun invalidOrZeroBoundsProduceFiniteNonCrashingTransformsAndStates() {
        val state = NormalizedViewportState(
            focusNormX = Float.NaN,
            focusNormY = Float.POSITIVE_INFINITY,
            zoomScale = Float.NaN
        )
        val tZeroVp = ViewportSyncMath.computeTransform(state, 0f, 1080f, 1920, 1080)
        assertEquals(0f, tZeroVp.fittedWidthPx, 0f)
        assertEquals(0f, tZeroVp.translationXPx, 0f)
        assertEquals(1f, tZeroVp.effectiveZoom, 0f)

        val tZeroImg = ViewportSyncMath.computeTransform(state, 1080f, 1920f, 0, 1080)
        assertEquals(0f, tZeroImg.fittedWidthPx, 0f)
        assertEquals(0f, tZeroImg.scaledHeightPx, 0f)

        val unchangedGesture = ViewportSyncMath.applyGesture(
            state = state,
            panDeltaXPx = 10f,
            panDeltaYPx = 10f,
            zoomChange = 2f,
            centroidXPx = 500f,
            centroidYPx = 500f,
            viewportWidthPx = 0f,
            viewportHeightPx = 1000f,
            imageWidthPx = 1920,
            imageHeightPx = 1080
        )
        assertEquals(state, unchangedGesture)
    }
}
