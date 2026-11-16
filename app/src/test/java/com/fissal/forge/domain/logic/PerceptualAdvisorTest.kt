package com.fissal.forge.domain.logic

import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressSpec
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.ConvertSpec
import com.fissal.forge.domain.model.CropRectNormalized
import com.fissal.forge.domain.model.CropSpec
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.SampleEntryDraft
import com.fissal.forge.domain.model.TrimSpec
import com.fissal.forge.domain.model.VideoCodecChoice
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class PerceptualAdvisorTest {

    private val videoProbe1080p = MediaProbeResult(
        uriString = "file:///clip.mp4",
        displayName = "clip.mp4",
        mimeType = "video/mp4",
        extension = "mp4",
        mediaKind = MediaKind.VIDEO,
        fileSizeBytes = 100L * 1024L * 1024L,
        rawWidth = 1920,
        rawHeight = 1080,
        durationMs = 40_000L,
        hasAudio = true
    )

    private val imageProbe4K = MediaProbeResult(
        uriString = "file:///photo.png",
        displayName = "photo.png",
        mimeType = "image/png",
        extension = "png",
        mediaKind = MediaKind.IMAGE,
        fileSizeBytes = 14L * 1024L * 1024L,
        rawWidth = 4000,
        rawHeight = 3000
    )

    private val rawProbe = MediaProbeResult(
        uriString = "file:///shot.dng",
        displayName = "shot.dng",
        mimeType = "image/x-adobe-dng",
        extension = "dng",
        mediaKind = MediaKind.CAMERA_RAW,
        fileSizeBytes = 36L * 1024L * 1024L,
        rawWidth = 6000,
        rawHeight = 4000
    )

    @Test
    fun identicalFramesReturnExactOneAcrossRegularAndOddDimensions() {
        val textured = makeTexturedFrame(64, 64)
        assertEquals(1.0, PerceptualAdvisor.computePerceptualScore(textured, textured), 1e-9)

        val oddFrame = makeTexturedFrame(27, 19)
        val oddCopy = LumaFrame(27, 19, oddFrame.luma.copyOf())
        assertEquals(1.0, PerceptualAdvisor.computePerceptualScore(oddFrame, oddCopy), 1e-9)

        val tinyFrame = makeTexturedFrame(5, 3)
        val tinyCopy = LumaFrame(5, 3, tinyFrame.luma.copyOf())
        assertEquals(1.0, PerceptualAdvisor.computePerceptualScore(tinyFrame, tinyCopy), 1e-9)
    }

    @Test
    fun bilinearResamplingPreservesCornersUniformFieldsAndLinearGradients() {
        val src = makeTexturedFrame(16, 16)
        assertSame(src, PerceptualAdvisor.resampleBilinear(src, 16, 16))

        val constant = LumaFrame(8, 8, FloatArray(64) { 142.5f })
        val upscaledConst = PerceptualAdvisor.resampleBilinear(constant, 25, 17)
        assertEquals(25, upscaledConst.width)
        assertEquals(17, upscaledConst.height)
        for (v in upscaledConst.luma) {
            assertEquals(142.5f, v, 1e-4f)
        }

        // Linear gradient L(x, y) = 20*x + 10*y over 8x8 -> corners [0..210]
        val gradData = FloatArray(64) { idx ->
            val x = idx % 8
            val y = idx / 8
            20f * x + 10f * y
        }
        val grad = LumaFrame(8, 8, gradData)
        val resampled = PerceptualAdvisor.resampleBilinear(grad, 29, 29)
        assertEquals(0f, resampled.luma[0], 1e-4f)
        assertEquals(140f, resampled.luma[28], 1e-4f)
        assertEquals(70f, resampled.luma[28 * 29], 1e-4f)
        assertEquals(210f, resampled.luma[28 * 29 + 28], 1e-4f)
        assertEquals(105f, resampled.luma[14 * 29 + 14], 1e-3f)
    }

    @Test
    fun isNearBlackFrameDistinguishesBlackFadesFromHighContrastOrLitScenes() {
        val pureBlack = LumaFrame(32, 32, FloatArray(32 * 32) { 0f })
        assertTrue(PerceptualAdvisor.isNearBlackFrame(pureBlack))

        val dimFade = LumaFrame(64, 64, FloatArray(64 * 64) { idx -> if (idx % 2 == 0) 6f else 9f })
        assertTrue(PerceptualAdvisor.isNearBlackFrame(dimFade))

        val hugeDark = LumaFrame(512, 512, FloatArray(512 * 512) { 4f })
        assertTrue(PerceptualAdvisor.isNearBlackFrame(hugeDark))

        // Low mean luma (~10) but bright pinpoint highlights -> high stdDev -> NOT a blank black fade
        val starfield = FloatArray(64 * 64) { idx -> if (idx % 31 == 0) 240f else 2f }
        assertFalse(PerceptualAdvisor.isNearBlackFrame(LumaFrame(64, 64, starfield)))

        val litScene = makeTexturedFrame(64, 64)
        assertFalse(PerceptualAdvisor.isNearBlackFrame(litScene))
    }

    @Test
    fun perceptualScoreDropsMonotonicallyWithBlurAndDownscaleWhileToleratingMicroDenoising() {
        val crisp = makeTexturedFrame(64, 64)
        val slightBlur = boxBlur3x3(crisp)
        val heavyBlur = boxBlur3x3(boxBlur3x3(boxBlur3x3(slightBlur)))
        val lowRes = downsampleAverage(crisp, factor = 4) // 16x16

        val sIdentical = PerceptualAdvisor.computePerceptualScore(crisp, crisp)
        val sSlightBlur = PerceptualAdvisor.computePerceptualScore(crisp, slightBlur)
        val sHeavyBlur = PerceptualAdvisor.computePerceptualScore(crisp, heavyBlur)
        val sLowRes = PerceptualAdvisor.computePerceptualScore(crisp, lowRes)

        assertEquals(1.0, sIdentical, 1e-9)
        assertTrue("Expected slightBlur ($sSlightBlur) < 0.995", sSlightBlur < 0.995)
        assertTrue("Expected heavyBlur ($sHeavyBlur) < slightBlur ($sSlightBlur)", sHeavyBlur < sSlightBlur)
        assertTrue("Expected lowRes ($sLowRes) < slightBlur ($sSlightBlur)", sLowRes < sSlightBlur)

        // Subtle sensor grain (+/- 1.0 luma) denoised cleanly (MAD <= 1.25) should score >= 0.98
        val cleanBase = FloatArray(64 * 64) { idx ->
            val x = idx % 64
            val y = idx / 64
            (40f + x * 2f + y * 1.2f).coerceIn(0f, 255f)
        }
        val grainyRef = LumaFrame(
            64,
            64,
            FloatArray(64 * 64) { idx ->
                val grain = if (((idx * 17 + idx / 64) and 1) == 0) 1.0f else -1.0f
                (cleanBase[idx] + grain).coerceIn(0f, 255f)
            }
        )
        val denoisedCand = LumaFrame(64, 64, cleanBase)
        val denoiseScore = PerceptualAdvisor.computePerceptualScore(grainyRef, denoisedCand)
        assertTrue("Expected micro-denoising score ($denoiseScore) >= 0.98", denoiseScore >= 0.98)
    }

    @Test
    fun recommendBestCandidateFindsParetoFrontierAndSelectsPerceptualKnee() {
        val originalBytes = 100_000_000L

        assertNull(PerceptualAdvisor.recommendBestCandidate(originalBytes, emptyList()))
        assertNull(
            PerceptualAdvisor.recommendBestCandidate(
                originalBytes,
                listOf(CandidateEvaluationInput("orig", originalBytes, 1.0, isOriginalBaseline = true))
            )
        )
        assertNull(
            PerceptualAdvisor.recommendBestCandidate(
                originalBytes,
                listOf(CandidateEvaluationInput("zero", 0L, 0.99))
            )
        )

        val candidates = listOf(
            CandidateEvaluationInput("orig", originalBytes, 1.0, isOriginalBaseline = true),
            CandidateEvaluationInput("hi", 85_000_000L, 0.985),
            CandidateEvaluationInput("knee", 32_000_000L, 0.96),
            CandidateEvaluationInput("blurry", 15_000_000L, 0.72),
            CandidateEvaluationInput("bloated", 105_000_000L, 0.99),
            CandidateEvaluationInput("dominated", 50_000_000L, 0.95)
        )

        val rec = PerceptualAdvisor.recommendBestCandidate(originalBytes, candidates)
        assertNotNull(rec)
        rec!!

        assertEquals("knee", rec.bestCandidateId)
        assertFalse("orig" in rec.summaryByCandidateId)
        assertFalse("orig" in rec.frontierIds)
        assertFalse("dominated" in rec.frontierIds)
        assertEquals(setOf("blurry", "knee", "hi", "bloated"), rec.frontierIds)

        assertEquals("SSIM 0.96 • -68% smaller", rec.summaryByCandidateId["knee"])
        assertEquals("SSIM 0.99 • +5% larger", rec.summaryByCandidateId["bloated"])
        assertEquals("SSIM 0.72 • -85% smaller", rec.summaryByCandidateId["blurry"])
    }

    @Test
    fun recommendBestCandidateFallbackHandlesNonViablePoolsAndZeroOriginalBytes() {
        val fallbackOnly = listOf(
            CandidateEvaluationInput("very_blurry", 15_000_000L, 0.65),
            CandidateEvaluationInput("passable", 50_000_000L, 0.84)
        )
        val rec = PerceptualAdvisor.recommendBestCandidate(100_000_000L, fallbackOnly)
        assertNotNull(rec)
        assertEquals("passable", rec!!.bestCandidateId)

        val zeroOrigRec = PerceptualAdvisor.recommendBestCandidate(
            0L,
            listOf(
                CandidateEvaluationInput("a", 5_000_000L, 0.96),
                CandidateEvaluationInput("b", 12_000_000L, 0.97)
            )
        )
        assertNotNull(zeroOrigRec)
        assertEquals("b", zeroOrigRec!!.bestCandidateId)
        assertEquals("SSIM 0.96 • same size", zeroOrigRec.summaryByCandidateId["a"])

        val tieBreakRec = PerceptualAdvisor.recommendBestCandidate(
            0L,
            listOf(
                CandidateEvaluationInput("bigger", 12_000_000L, 0.96),
                CandidateEvaluationInput("smaller", 5_000_000L, 0.96)
            )
        )
        assertNotNull(tieBreakRec)
        assertEquals("smaller", tieBreakRec!!.bestCandidateId)
    }

    @Test
    fun sampleEntryDraftHarmonizationLabelsSpecConversionAndStartersWorkCleanly() {
        val webmDraft = SampleEntryDraft(targetExtension = "mp4", videoCodec = VideoCodecChoice.H264)
            .harmonizedForContainer("webm", VideoCodecChoice.H264, MediaKind.VIDEO)
        assertEquals("webm", webmDraft.targetExtension)
        assertEquals(VideoCodecChoice.VP9, webmDraft.videoCodec)

        val webmAv1 = webmDraft.harmonizedForContainer("webm", VideoCodecChoice.AV1, MediaKind.VIDEO)
        assertEquals(VideoCodecChoice.AV1, webmAv1.videoCodec)

        val mp4FromVp9 = webmDraft.harmonizedForContainer("mp4", VideoCodecChoice.VP9, MediaKind.VIDEO)
        assertEquals("mp4", mp4FromVp9.targetExtension)
        assertEquals(VideoCodecChoice.H264, mp4FromVp9.videoCodec)

        val normalizedCopyDraft = SampleEntryDraft(
            targetExtension = "mkv",
            videoCodec = VideoCodecChoice.COPY,
            compressionTier = CompressionTier.TWO_PASS_HEVC
        ).harmonizedForContainer("mkv", VideoCodecChoice.COPY, MediaKind.VIDEO)
        assertEquals(VideoCodecChoice.HEVC, normalizedCopyDraft.videoCodec)

        val gifDraft = SampleEntryDraft(targetExtension = "gif", maxDimensionPx = null)
            .harmonizedForContainer("gif", VideoCodecChoice.AV1, MediaKind.VIDEO)
        assertEquals("gif", gifDraft.targetExtension)
        assertEquals(VideoCodecChoice.COPY, gifDraft.videoCodec)
        assertEquals("GIF • 540px", gifDraft.autoLabel(MediaKind.VIDEO))

        val av1Draft = SampleEntryDraft(
            targetExtension = "mp4",
            videoCodec = VideoCodecChoice.AV1,
            compressMode = CompressMode.QUALITY,
            videoCrf = 28,
            maxDimensionPx = 1920
        )
        assertEquals("AV1 • 1080p • CRF 28", av1Draft.autoLabel(MediaKind.VIDEO))

        val hevcTargetDraft = SampleEntryDraft(
            targetExtension = "mp4",
            videoCodec = VideoCodecChoice.HEVC,
            compressMode = CompressMode.TARGET_BYTES,
            targetMbText = "10",
            maxDimensionPx = null,
            compressionTier = CompressionTier.TWO_PASS_HEVC
        )
        assertEquals("HEVC • Orig • ≤ 10 MB", hevcTargetDraft.autoLabel(MediaKind.VIDEO))

        val webpLabel = SampleEntryDraft(
            targetExtension = "webp",
            compressMode = CompressMode.QUALITY,
            imageQuality = 82,
            maxDimensionPx = 1920
        ).autoLabel(MediaKind.IMAGE)
        assertEquals("WEBP • 1080p • Q82", webpLabel)

        val jpgTargetDraft = SampleEntryDraft(
            targetExtension = "jpeg",
            compressMode = CompressMode.TARGET_BYTES,
            targetMbText = "2.5",
            maxDimensionPx = null
        )
        assertEquals("JPG • Orig • ≤ 2.5 MB", jpgTargetDraft.autoLabel(MediaKind.IMAGE))
        assertEquals(
            "PNG • 720p",
            SampleEntryDraft(targetExtension = "png", maxDimensionPx = 1280).autoLabel(MediaKind.IMAGE)
        )

        val trim = TrimSpec(startMs = 1_000L, endMs = 15_000L, muteAudio = false)
        val crop = CropSpec(AspectPreset.RATIO_1_1, CropRectNormalized(0.2f, 0f, 0.8f, 1f))
        val av1Spec = av1Draft.toPipelineSpec(videoProbe1080p, inheritedTrim = trim, inheritedCrop = crop, stripMetadata = true)
        assertEquals(AudioCodecChoice.OPUS, av1Spec.convert?.audioCodec)
        assertEquals(VideoCodecChoice.AV1, av1Spec.convert?.videoCodec)
        assertEquals(trim, av1Spec.trim)
        assertEquals(crop, av1Spec.crop)
        assertTrue(av1Spec.delivery.stripMetadata)

        val hevcSpec = hevcTargetDraft.toPipelineSpec(videoProbe1080p, inheritedTrim = trim.copy(muteAudio = true))
        assertEquals(AudioCodecChoice.MUTE, hevcSpec.convert?.audioCodec)
        assertEquals((10.0 * 1024.0 * 1024.0).roundToLong(), hevcSpec.compress?.targetBytes)
        assertTrue(hevcSpec.compress?.twoPass == true)

        val jpgSpec = jpgTargetDraft.toPipelineSpec(imageProbe4K)
        assertEquals("jpg", jpgSpec.convert?.targetExtension)
        assertEquals((2.5 * 1024.0 * 1024.0).roundToLong(), jpgSpec.compress?.targetBytes)

        val roundTrippedDraft = SampleEntryDraft.fromPipelineSpec(videoProbe1080p, av1Spec)
        assertEquals(av1Draft.targetExtension, roundTrippedDraft.targetExtension)
        assertEquals(av1Draft.videoCodec, roundTrippedDraft.videoCodec)
        assertEquals(av1Draft.videoCrf, roundTrippedDraft.videoCrf)
        assertEquals(av1Draft.maxDimensionPx, roundTrippedDraft.maxDimensionPx)

        val videoStarters = SampleEntryDraft.defaultStarterDrafts(videoProbe1080p)
        assertEquals(3, videoStarters.size)

        val customStudio = PipelineSpec(
            convert = ConvertSpec("mkv", VideoCodecChoice.VP9, AudioCodecChoice.OPUS),
            compress = CompressSpec(mode = CompressMode.QUALITY, videoCrf = 22)
        )
        val withStudio = SampleEntryDraft.defaultStarterDrafts(videoProbe1080p, customStudio)
        assertEquals(4, withStudio.size)
        assertEquals("mkv", withStudio.first().targetExtension)

        val rawStarters = SampleEntryDraft.defaultStarterDrafts(rawProbe)
        assertEquals(3, rawStarters.size)
        assertTrue(SampleEntryDraft.bestKneeSeedDrafts(videoProbe1080p).size >= 4)
        assertTrue(SampleEntryDraft.bestKneeSeedDrafts(imageProbe4K).size >= 4)

        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val encoded = json.encodeToString(av1Draft)
        assertEquals(av1Draft, json.decodeFromString<SampleEntryDraft>(encoded))
    }

    private fun makeTexturedFrame(width: Int, height: Int): LumaFrame {
        val data = FloatArray(width * height) { idx ->
            val x = idx % width
            val y = idx / width
            val checker = if (((x / 4) + (y / 4)) % 2 == 0) 90f else 180f
            val ramp = ((x * 3 + y * 5) % 45).toFloat()
            val edge = if (x == width / 2 || y == height / 2) 250f else checker
            (edge * 0.7f + ramp).coerceIn(0f, 255f)
        }
        return LumaFrame(width, height, data)
    }

    private fun boxBlur3x3(src: LumaFrame): LumaFrame {
        val w = src.width
        val h = src.height
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var count = 0
                for (dy in -1..1) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        sum += src.luma[ny * w + nx]
                        count++
                    }
                }
                out[y * w + x] = sum / count.toFloat()
            }
        }
        return LumaFrame(w, h, out)
    }

    private fun downsampleAverage(src: LumaFrame, factor: Int): LumaFrame {
        val dw = (src.width / factor).coerceAtLeast(1)
        val dh = (src.height / factor).coerceAtLeast(1)
        val out = FloatArray(dw * dh)
        for (y in 0 until dh) {
            for (x in 0 until dw) {
                var sum = 0f
                var count = 0
                for (sy in y * factor until (y + 1) * factor) {
                    for (sx in x * factor until (x + 1) * factor) {
                        sum += src.luma[sy * src.width + sx]
                        count++
                    }
                }
                out[y * dw + x] = sum / count.toFloat()
            }
        }
        return LumaFrame(dw, dh, out)
    }
}
