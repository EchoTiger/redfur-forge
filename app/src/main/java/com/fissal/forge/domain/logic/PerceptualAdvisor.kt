package com.fissal.forge.domain.logic

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure JVM planar 8-bit-range luminance frame (`[0f..255f]`) for perceptual quality analysis.
 */
class LumaFrame(
    val width: Int,
    val height: Int,
    val luma: FloatArray
) {
    init {
        require(width > 0) { "LumaFrame width must be > 0 (got $width)" }
        require(height > 0) { "LumaFrame height must be > 0 (got $height)" }
        require(luma.size >= width * height) {
            "LumaFrame buffer size (${luma.size}) is smaller than width * height (${width * height})"
        }
    }
}

data class CandidateEvaluationInput(
    val candidateId: String,
    val projectedTotalBytes: Long,
    val perceptualScore: Double,
    val isOriginalBaseline: Boolean = false
)

data class ParetoRecommendation(
    val bestCandidateId: String,
    val summaryByCandidateId: Map<String, String>,
    val frontierIds: Set<String>
)

/**
 * Pure JVM perceptual quality & Pareto-knee optimizer for Sample Lab candidates.
 * Evaluates spatial sharpness + denoising tolerance via edge-weighted 8x8 SSIM
 * and picks the optimal quality-per-byte knee on the Pareto frontier.
 */
object PerceptualAdvisor {

    private const val SSIM_C1 = 6.5025 // (0.01 * 255)^2
    private const val SSIM_C2 = 58.5225 // (0.03 * 255)^2

    /**
     * Resamples [source] to ([targetWidth], [targetHeight]) using bilinear interpolation,
     * mapping `[0..target-1]` linearly across `[0..source-1]`.
     */
    fun resampleBilinear(
        source: LumaFrame,
        targetWidth: Int,
        targetHeight: Int
    ): LumaFrame {
        require(targetWidth > 0) { "targetWidth must be > 0 (got $targetWidth)" }
        require(targetHeight > 0) { "targetHeight must be > 0 (got $targetHeight)" }
        if (source.width == targetWidth && source.height == targetHeight) {
            return source
        }

        val srcW = source.width
        val srcH = source.height
        val src = source.luma
        val out = FloatArray(targetWidth * targetHeight)

        val scaleX = if (targetWidth > 1 && srcW > 1) (srcW - 1).toDouble() / (targetWidth - 1).toDouble() else 0.0
        val scaleY = if (targetHeight > 1 && srcH > 1) (srcH - 1).toDouble() / (targetHeight - 1).toDouble() else 0.0

        for (ty in 0 until targetHeight) {
            val sy = if (targetHeight == 1 && srcH > 1) (srcH - 1) * 0.5 else ty * scaleY
            val y0 = sy.toInt().coerceIn(0, srcH - 1)
            val y1 = min(y0 + 1, srcH - 1)
            val fy = (sy - y0).toFloat()
            val row0 = y0 * srcW
            val row1 = y1 * srcW
            val dstRow = ty * targetWidth

            for (tx in 0 until targetWidth) {
                val sx = if (targetWidth == 1 && srcW > 1) (srcW - 1) * 0.5 else tx * scaleX
                val x0 = sx.toInt().coerceIn(0, srcW - 1)
                val x1 = min(x0 + 1, srcW - 1)
                val fx = (sx - x0).toFloat()

                val v00 = src[row0 + x0]
                val v10 = src[row0 + x1]
                val v01 = src[row1 + x0]
                val v11 = src[row1 + x1]

                val top = v00 + (v10 - v00) * fx
                val bottom = v01 + (v11 - v01) * fx
                out[dstRow + tx] = top + (bottom - top) * fy
            }
        }

        return LumaFrame(targetWidth, targetHeight, out)
    }

    /**
     * Detects black/dark fade frames (`mean < 12` and `stdDev < 6.5` luma levels).
     */
    fun isNearBlackFrame(frame: LumaFrame): Boolean {
        val total = frame.width * frame.height
        if (total <= 0) return true
        val stride = if (total > 32_768) max(1, total / 16_384) else 1

        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        var idx = 0
        while (idx < total) {
            val v = frame.luma[idx].toDouble()
            sum += v
            sumSq += v * v
            count++
            idx += stride
        }
        if (count == 0) return true
        val mean = sum / count.toDouble()
        val variance = ((sumSq / count.toDouble()) - mean * mean).coerceAtLeast(0.0)
        val stdDev = sqrt(variance)
        return mean < 12.0 && stdDev < 6.5
    }

    /**
     * Computes edge-salience weighted 8x8 block SSIM between [reference] and [candidate],
     * bilinearly aligning [candidate] to [reference]'s resolution when dimensions differ so
     * downscaling naturally reflects spatial detail loss.
     */
    fun computePerceptualScore(reference: LumaFrame, candidate: LumaFrame): Double {
        val aligned = if (candidate.width == reference.width && candidate.height == reference.height) {
            candidate
        } else {
            resampleBilinear(candidate, reference.width, reference.height)
        }

        val w = reference.width
        val h = reference.height
        val refLuma = reference.luma
        val candLuma = aligned.luma

        var weightedScoreSum = 0.0
        var totalWeight = 0.0

        var y = 0
        while (y < h) {
            val blockH = min(8, h - y)
            var x = 0
            while (x < w) {
                val blockW = min(8, w - x)
                val n = (blockW * blockH).toDouble()

                var sumX = 0.0
                var sumY = 0.0
                var sumX2 = 0.0
                var sumY2 = 0.0
                var sumXY = 0.0
                var sumAbsDiff = 0.0

                for (by in 0 until blockH) {
                    val rowOffset = (y + by) * w + x
                    for (bx in 0 until blockW) {
                        val idx = rowOffset + bx
                        val vx = refLuma[idx].toDouble().coerceIn(0.0, 255.0)
                        val vy = candLuma[idx].toDouble().coerceIn(0.0, 255.0)
                        sumX += vx
                        sumY += vy
                        sumX2 += vx * vx
                        sumY2 += vy * vy
                        sumXY += vx * vy
                        sumAbsDiff += abs(vx - vy)
                    }
                }

                val mad = sumAbsDiff / n
                val meanX = sumX / n
                val meanY = sumY / n
                val varX = (sumX2 / n - meanX * meanX).coerceAtLeast(0.0)
                val varY = (sumY2 / n - meanY * meanY).coerceAtLeast(0.0)
                val covXY = sumXY / n - meanX * meanY

                val blockSsim = if (mad == 0.0) {
                    1.0
                } else {
                    val num = (2.0 * meanX * meanY + SSIM_C1) * (2.0 * covXY + SSIM_C2)
                    val den = (meanX * meanX + meanY * meanY + SSIM_C1) * (varX + varY + SSIM_C2)
                    val rawSsim = (num / den).coerceIn(0.0, 1.0)
                    if (mad <= 1.25) {
                        // Gentle micro-noise tolerance so clean AV1 denoising doesn't read as blur
                        val microFloor = 1.0 - (mad / 1.25) * 0.015
                        max(rawSsim, microFloor)
                    } else {
                        rawSsim
                    }
                }

                val edgeWeight = 1.0 + 0.6 * min(1.0, sqrt(varX) / 28.0)
                weightedScoreSum += blockSsim * edgeWeight
                totalWeight += edgeWeight

                x += 8
            }
            y += 8
        }

        if (totalWeight <= 0.0) return 1.0
        return (weightedScoreSum / totalWeight).coerceIn(0.0, 1.0)
    }

    /**
     * Evaluates non-baseline candidates, builds per-candidate summaries, identifies the
     * Pareto-optimal frontier, and picks the perceptual quality-per-byte knee winner.
     */
    fun recommendBestCandidate(
        originalBytes: Long,
        candidates: List<CandidateEvaluationInput>
    ): ParetoRecommendation? {
        val valid = candidates
            .filter { !it.isOriginalBaseline && it.projectedTotalBytes > 0L }
            .map { c ->
                val clampedScore = c.perceptualScore.let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.0 }
                val sizeRatio = if (originalBytes > 0L) {
                    c.projectedTotalBytes.toDouble() / originalBytes.toDouble()
                } else {
                    1.0
                }
                EvaluatedCandidate(
                    input = c,
                    score = clampedScore,
                    sizeRatio = sizeRatio
                )
            }

        if (valid.isEmpty()) return null

        val summaries = LinkedHashMap<String, String>(valid.size)
        for (item in valid) {
            val ssimText = String.format(Locale.US, "SSIM %.2f", item.score)
            val deltaPct = ((item.sizeRatio - 1.0) * 100.0).roundToInt()
            val deltaText = when {
                originalBytes <= 0L || deltaPct == 0 -> "same size"
                deltaPct < 0 -> "${deltaPct}% smaller"
                else -> "+${deltaPct}% larger"
            }
            summaries[item.input.candidateId] = "$ssimText • $deltaText"
        }

        val frontierIds = LinkedHashSet<String>()
        for (i in valid.indices) {
            val a = valid[i]
            var dominated = false
            for (j in valid.indices) {
                if (i == j) continue
                val b = valid[j]
                val bNotWorse = b.sizeRatio <= a.sizeRatio && b.score >= a.score
                val bStrictlyBetter = b.sizeRatio < a.sizeRatio || b.score > a.score
                if (bNotWorse && bStrictlyBetter) {
                    dominated = true
                    break
                }
            }
            if (!dominated) {
                frontierIds += a.input.candidateId
            }
        }

        val viable = valid.filter { it.score >= 0.85 && it.sizeRatio <= 1.05 }
        val pool = if (viable.isNotEmpty()) {
            viable.filter { it.input.candidateId in frontierIds }.ifEmpty { viable }
        } else {
            valid.filter { it.input.candidateId in frontierIds }.ifEmpty { valid }
        }

        val best = pool.maxWithOrNull(
            compareBy<EvaluatedCandidate> { kneeUtility(it.score, it.sizeRatio) }
                .thenBy { it.score }
                .thenBy { -it.sizeRatio }
                .thenBy { -it.input.projectedTotalBytes }
        ) ?: return null

        return ParetoRecommendation(
            bestCandidateId = best.input.candidateId,
            summaryByCandidateId = summaries,
            frontierIds = frontierIds
        )
    }

    /**
     * Concave quality utility with diminishing marginal returns above SSIM 0.955 and steep
     * fidelity protection below 0.90, balanced against byte savings.
     */
    private fun kneeUtility(score: Double, sizeRatio: Double): Double {
        val s = score.coerceIn(0.0, 1.0)
        val qualityUtility = when {
            s >= 0.955 -> 0.6475 + (s - 0.955) * 0.8
            s >= 0.90 -> 0.40 + (s - 0.90) * 4.5
            s >= 0.85 -> (s - 0.85) * 8.0
            else -> (s - 0.85) * 12.0
        }
        val savingsTerm = 0.55 * (1.0 - sizeRatio)
        val bloatPenalty = if (sizeRatio > 1.0) -0.8 * (sizeRatio - 1.0) else 0.0
        return qualityUtility + savingsTerm + bloatPenalty
    }

    private data class EvaluatedCandidate(
        val input: CandidateEvaluationInput,
        val score: Double,
        val sizeRatio: Double
    )
}
