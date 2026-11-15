package com.fissal.forge.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.fissal.forge.domain.logic.AspectRatioSnapper
import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.CropRectNormalized
import com.fissal.forge.ui.theme.MasserCrimson
import com.fissal.forge.ui.theme.ObsidianBg
import com.fissal.forge.ui.theme.SecundaSilver
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private enum class DragMode {
    NONE,
    MOVE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

@Composable
fun CropCanvasOverlay(
    bitmap: Bitmap?,
    effectiveWidth: Int,
    effectiveHeight: Int,
    preset: AspectPreset,
    rect: CropRectNormalized,
    onRectChange: (CropRectNormalized) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeW = effectiveWidth.takeIf { it > 0 } ?: bitmap?.width ?: 1920
    val safeH = effectiveHeight.takeIf { it > 0 } ?: bitmap?.height ?: 1080
    val resolvedRatio = remember(preset, safeW, safeH) {
        if (preset == AspectPreset.AUTO_CLOSEST) {
            AspectRatioSnapper.closestDefaultPreset(safeW, safeH)
        } else {
            preset
        }
    }
    val pixelPreview = remember(rect, safeW, safeH) {
        AspectRatioSnapper.toEvenPixelCrop(rect, safeW, safeH)
    }

    val currentRect by rememberUpdatedState(rect)
    val currentRatio by rememberUpdatedState(resolvedRatio)
    val density = LocalDensity.current
    val touchSlopPx = with(density) { 28.dp.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .background(ObsidianBg, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        val boxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val boxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val imgAspect = safeW.toFloat() / safeH.toFloat()
        val boxAspect = boxW / boxH

        val renderW: Float
        val renderH: Float
        if (imgAspect > boxAspect) {
            renderW = boxW
            renderH = boxW / imgAspect
        } else {
            renderH = boxH
            renderW = boxH * imgAspect
        }
        val offsetX = (boxW - renderW) / 2f
        val offsetY = (boxH - renderH) / 2f

        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Crop preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        var dragMode by remember { mutableStateOf(DragMode.NONE) }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(renderW, renderH, offsetX, offsetY) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val r = currentRect
                            val lPx = offsetX + r.left * renderW
                            val tPx = offsetY + r.top * renderH
                            val rPx = offsetX + r.right * renderW
                            val bPx = offsetY + r.bottom * renderH

                            val dTL = hypot(pos.x - lPx, pos.y - tPx)
                            val dTR = hypot(pos.x - rPx, pos.y - tPx)
                            val dBL = hypot(pos.x - lPx, pos.y - bPx)
                            val dBR = hypot(pos.x - rPx, pos.y - bPx)

                            dragMode = when {
                                dTL <= touchSlopPx -> DragMode.TOP_LEFT
                                dTR <= touchSlopPx -> DragMode.TOP_RIGHT
                                dBL <= touchSlopPx -> DragMode.BOTTOM_LEFT
                                dBR <= touchSlopPx -> DragMode.BOTTOM_RIGHT
                                pos.x in lPx..rPx && pos.y in tPx..bPx -> DragMode.MOVE
                                else -> DragMode.NONE
                            }
                        },
                        onDragEnd = { dragMode = DragMode.NONE },
                        onDragCancel = { dragMode = DragMode.NONE },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val dx = dragAmount.x / renderW
                            val dy = dragAmount.y / renderH
                            val r = currentRect
                            val minSpan = 0.08f

                            val ratioW = currentRatio.ratioW
                            val ratioH = currentRatio.ratioH
                            val lockedNormRatio = if (ratioW != null && ratioH != null) {
                                // Normalized height / normalized width relation in image coordinates
                                (ratioW.toFloat() / ratioH.toFloat()) / (safeW.toFloat() / safeH.toFloat())
                            } else {
                                null
                            }

                            val next = when (dragMode) {
                                DragMode.MOVE -> {
                                    val w = r.right - r.left
                                    val h = r.bottom - r.top
                                    val nl = (r.left + dx).coerceIn(0f, 1f - w)
                                    val nt = (r.top + dy).coerceIn(0f, 1f - h)
                                    CropRectNormalized(nl, nt, nl + w, nt + h)
                                }
                                DragMode.TOP_LEFT -> resizeCorner(
                                    r = r,
                                    anchorX = r.right,
                                    anchorY = r.bottom,
                                    newX = (r.left + dx).coerceIn(0f, r.right - minSpan),
                                    newY = (r.top + dy).coerceIn(0f, r.bottom - minSpan),
                                    isLeft = true,
                                    isTop = true,
                                    lockedNormRatio = lockedNormRatio,
                                    minSpan = minSpan
                                )
                                DragMode.TOP_RIGHT -> resizeCorner(
                                    r = r,
                                    anchorX = r.left,
                                    anchorY = r.bottom,
                                    newX = (r.right + dx).coerceIn(r.left + minSpan, 1f),
                                    newY = (r.top + dy).coerceIn(0f, r.bottom - minSpan),
                                    isLeft = false,
                                    isTop = true,
                                    lockedNormRatio = lockedNormRatio,
                                    minSpan = minSpan
                                )
                                DragMode.BOTTOM_LEFT -> resizeCorner(
                                    r = r,
                                    anchorX = r.right,
                                    anchorY = r.top,
                                    newX = (r.left + dx).coerceIn(0f, r.right - minSpan),
                                    newY = (r.bottom + dy).coerceIn(r.top + minSpan, 1f),
                                    isLeft = true,
                                    isTop = false,
                                    lockedNormRatio = lockedNormRatio,
                                    minSpan = minSpan
                                )
                                DragMode.BOTTOM_RIGHT -> resizeCorner(
                                    r = r,
                                    anchorX = r.left,
                                    anchorY = r.top,
                                    newX = (r.right + dx).coerceIn(r.left + minSpan, 1f),
                                    newY = (r.bottom + dy).coerceIn(r.top + minSpan, 1f),
                                    isLeft = false,
                                    isTop = false,
                                    lockedNormRatio = lockedNormRatio,
                                    minSpan = minSpan
                                )
                                DragMode.NONE -> r
                            }
                            onRectChange(next)
                        }
                    )
                }
        ) {
            val lPx = offsetX + rect.left * renderW
            val tPx = offsetY + rect.top * renderH
            val rPx = offsetX + rect.right * renderW
            val bPx = offsetY + rect.bottom * renderH
            val cropW = (rPx - lPx).coerceAtLeast(1f)
            val cropH = (bPx - tPx).coerceAtLeast(1f)

            val scrim = Color.Black.copy(alpha = 0.60f)
            // Scrim outside crop box
            drawRect(scrim, Offset(offsetX, offsetY), Size(renderW, (tPx - offsetY).coerceAtLeast(0f)))
            drawRect(scrim, Offset(offsetX, bPx), Size(renderW, (offsetY + renderH - bPx).coerceAtLeast(0f)))
            drawRect(scrim, Offset(offsetX, tPx), Size((lPx - offsetX).coerceAtLeast(0f), cropH))
            drawRect(scrim, Offset(rPx, tPx), Size((offsetX + renderW - rPx).coerceAtLeast(0f), cropH))

            // Crop outline
            drawRect(
                color = SecundaSilver,
                topLeft = Offset(lPx, tPx),
                size = Size(cropW, cropH),
                style = Stroke(width = 2.dp.toPx())
            )

            // Rule-of-thirds grid
            val gridColor = Color.White.copy(alpha = 0.28f)
            val stroke1 = 1.dp.toPx()
            for (i in 1..2) {
                val x = lPx + cropW * (i / 3f)
                val y = tPx + cropH * (i / 3f)
                drawLine(gridColor, Offset(x, tPx), Offset(x, bPx), strokeWidth = stroke1)
                drawLine(gridColor, Offset(lPx, y), Offset(rPx, y), strokeWidth = stroke1)
            }

            // Corner handles
            val handleRadius = 8.dp.toPx()
            listOf(
                Offset(lPx, tPx),
                Offset(rPx, tPx),
                Offset(lPx, bPx),
                Offset(rPx, bPx)
            ).forEach { corner ->
                drawCircle(MasserCrimson, radius = handleRadius, center = corner)
                drawCircle(Color.White, radius = handleRadius * 0.45f, center = corner)
            }
        }

        Surface(
            color = ObsidianBg.copy(alpha = 0.85f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        ) {
            Text(
                text = "${pixelPreview.width} × ${pixelPreview.height} • ${resolvedRatio.label}",
                style = MaterialTheme.typography.labelMedium,
                color = SecundaSilver,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

private fun resizeCorner(
    r: CropRectNormalized,
    anchorX: Float,
    anchorY: Float,
    newX: Float,
    newY: Float,
    isLeft: Boolean,
    isTop: Boolean,
    lockedNormRatio: Float?,
    minSpan: Float
): CropRectNormalized {
    if (lockedNormRatio == null || lockedNormRatio <= 0f) {
        val left = if (isLeft) newX else anchorX
        val right = if (isLeft) anchorX else newX
        val top = if (isTop) newY else anchorY
        val bottom = if (isTop) anchorY else newY
        return CropRectNormalized(left, top, right, bottom)
    }

    val maxAvailW = if (isLeft) anchorX else 1f - anchorX
    val maxAvailH = if (isTop) anchorY else 1f - anchorY

    var w = abs(anchorX - newX).coerceIn(minSpan, maxAvailW)
    var h = w / lockedNormRatio
    if (h > maxAvailH) {
        h = maxAvailH
        w = h * lockedNormRatio
    }
    if (h < minSpan) {
        h = min(minSpan, maxAvailH)
        w = min(h * lockedNormRatio, maxAvailW)
    }

    val left = if (isLeft) max(0f, anchorX - w) else anchorX
    val right = if (isLeft) anchorX else min(1f, anchorX + w)
    val top = if (isTop) max(0f, anchorY - h) else anchorY
    val bottom = if (isTop) anchorY else min(1f, anchorY + h)

    return CropRectNormalized(left, top, right, bottom)
}
