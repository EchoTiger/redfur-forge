package com.fissal.forge.domain.logic

import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.ChapterTrackItem
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressSpec
import com.fissal.forge.domain.model.CompressionTier
import com.fissal.forge.domain.model.ConvertSpec
import com.fissal.forge.domain.model.CropRectNormalized
import com.fissal.forge.domain.model.CropSpec
import com.fissal.forge.domain.model.DetectedLinkKind
import com.fissal.forge.domain.model.DownloadMode
import com.fissal.forge.domain.model.LinkItemSettings
import com.fissal.forge.domain.model.LinkResolveState
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.PlaylistTrackItem
import com.fissal.forge.domain.model.ResolvedLinkChipItem
import com.fissal.forge.domain.model.SilenceSkipSpec
import com.fissal.forge.domain.model.TrimSpec
import com.fissal.forge.domain.model.VideoCodecChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesizeEstimatorTest {

    private val sampleVideo = MediaProbeResult(
        uriString = "file:///sample.mp4",
        displayName = "sample.mp4",
        mimeType = "video/mp4",
        extension = "mp4",
        mediaKind = MediaKind.VIDEO,
        fileSizeBytes = 60L * 1024L * 1024L,
        rawWidth = 1920,
        rawHeight = 1080,
        rotationDegrees = 0,
        durationMs = 60_000L,
        hasAudio = true
    )

    private val sampleAudio = MediaProbeResult(
        uriString = "file:///track.wav",
        displayName = "track.wav",
        mimeType = "audio/wav",
        extension = "wav",
        mediaKind = MediaKind.AUDIO,
        fileSizeBytes = 34_560_000L,
        durationMs = 180_000L,
        hasAudio = true
    )

    private val sampleImage = MediaProbeResult(
        uriString = "file:///photo.png",
        displayName = "photo.png",
        mimeType = "image/png",
        extension = "png",
        mediaKind = MediaKind.IMAGE,
        fileSizeBytes = 12_000_000L,
        rawWidth = 4000,
        rawHeight = 3000
    )

    private val sampleRaw = MediaProbeResult(
        uriString = "file:///shot.dng",
        displayName = "shot.dng",
        mimeType = "image/x-adobe-dng",
        extension = "dng",
        mediaKind = MediaKind.CAMERA_RAW,
        fileSizeBytes = 32_000_000L,
        rawWidth = 6000,
        rawHeight = 4000
    )

    @Test
    fun formatsBytesAndDeltaLabelsDeterministically() {
        assertEquals("0 B", FilesizeEstimator.formatBytes(0L))
        assertEquals("420 KB", FilesizeEstimator.formatBytes(420L * 1024L))
        assertEquals("14.2 MB", FilesizeEstimator.formatBytes((14.2 * 1024.0 * 1024.0).toLong()))
        assertEquals("1.25 GB", FilesizeEstimator.formatBytes((1.25 * 1024.0 * 1024.0 * 1024.0).toLong()))

        val smaller = EstimatedSizeResult(
            estimatedBytes = 3_800_000L,
            minBytes = 3_400_000L,
            maxBytes = 4_200_000L,
            sourceBytes = 10_000_000L,
            deltaPercent = -62
        )
        assertTrue(smaller.formattedWithDelta().endsWith("(-62%)"))

        val larger = smaller.copy(deltaPercent = 8)
        assertTrue(larger.formattedWithDelta().endsWith("(+8%)"))

        val unchanged = smaller.copy(deltaPercent = 0)
        assertEquals(unchanged.formattedEstimate(), unchanged.formattedWithDelta())

        val noSource = smaller.copy(sourceBytes = null, deltaPercent = null)
        assertEquals(noSource.formattedEstimate(), noSource.formattedWithDelta())
    }

    @Test
    fun videoCrfCurveIsMonotonicallyDecreasing() {
        val crfs = listOf(16, 18, 23, 24, 32, 40)
        val estimates = crfs.map { crf ->
            FilesizeEstimator.estimateLocalPipeline(
                sampleVideo,
                PipelineSpec(
                    compress = CompressSpec(mode = CompressMode.QUALITY, videoCrf = crf)
                )
            ).estimatedBytes
        }

        for (i in 0 until estimates.lastIndex) {
            assertTrue(
                "Expected CRF ${crfs[i]} (${estimates[i]}) > CRF ${crfs[i + 1]} (${estimates[i + 1]})",
                estimates[i] > estimates[i + 1]
            )
        }
    }

    @Test
    fun videoCodecOrderingFollowsAv1LessThanHevcLteVp9LessThanH264() {
        fun estimateFor(ext: String, codec: VideoCodecChoice): Long {
            return FilesizeEstimator.estimateLocalPipeline(
                sampleVideo,
                PipelineSpec(
                    convert = ConvertSpec(targetExtension = ext, videoCodec = codec, audioCodec = AudioCodecChoice.AAC),
                    compress = CompressSpec(mode = CompressMode.QUALITY, videoCrf = 23)
                )
            ).estimatedBytes
        }

        val av1 = estimateFor("mp4", VideoCodecChoice.AV1)
        val hevc = estimateFor("mp4", VideoCodecChoice.HEVC)
        val vp9 = estimateFor("webm", VideoCodecChoice.VP9)
        val h264 = estimateFor("mp4", VideoCodecChoice.H264)

        assertTrue("Expected AV1 ($av1) < HEVC ($hevc)", av1 < hevc)
        assertTrue("Expected HEVC ($hevc) <= VP9 ($vp9)", hevc <= vp9)
        assertTrue("Expected VP9 ($vp9) < H264 ($h264)", vp9 < h264)
    }

    @Test
    fun videoCropTrimAndDownscaleReduceEstimatedBytes() {
        val baseline = FilesizeEstimator.estimateLocalPipeline(
            sampleVideo,
            PipelineSpec(compress = CompressSpec(mode = CompressMode.QUALITY, videoCrf = 23))
        ).estimatedBytes

        val croppedSquare = FilesizeEstimator.estimateLocalPipeline(
            sampleVideo,
            PipelineSpec(
                compress = CompressSpec(mode = CompressMode.QUALITY, videoCrf = 23),
                crop = CropSpec(
                    preset = AspectPreset.RATIO_1_1,
                    rect = CropRectNormalized(left = 0.21875f, top = 0f, right = 0.78125f, bottom = 1f)
                )
            )
        ).estimatedBytes
        assertTrue("Expected 1:1 crop ($croppedSquare) < 16:9 baseline ($baseline)", croppedSquare < baseline)

        val halvedDuration = FilesizeEstimator.estimateLocalPipeline(
            sampleVideo,
            PipelineSpec(
                compress = CompressSpec(mode = CompressMode.QUALITY, videoCrf = 23),
                trim = TrimSpec(startMs = 0L, endMs = 30_000L)
            )
        ).estimatedBytes
        assertTrue("Expected half trim ($halvedDuration) near half baseline ($baseline)", halvedDuration < (baseline * 0.58).toLong())

        val downscaled720 = FilesizeEstimator.estimateLocalPipeline(
            sampleVideo,
            PipelineSpec(
                compress = CompressSpec(mode = CompressMode.QUALITY, videoCrf = 23, maxDimensionPx = 1280)
            )
        ).estimatedBytes
        assertTrue("Expected 720p maxDim ($downscaled720) < 1080p baseline ($baseline)", downscaled720 < baseline)
    }

    @Test
    fun fastKeyframeStreamCopyScalesProportionallyWithTrimDuration() {
        val fastHalf = FilesizeEstimator.estimateLocalPipeline(
            sampleVideo,
            PipelineSpec(
                trim = TrimSpec(startMs = 10_000L, endMs = 40_000L, fastKeyframeCopy = true)
            )
        )
        val expectedHalf = sampleVideo.fileSizeBytes / 2L
        assertTrue(fastHalf.estimatedBytes in (expectedHalf - 1024L)..(expectedHalf + 1024L))
        assertEquals(-50, fastHalf.deltaPercent)
    }

    @Test
    fun targetBytesClampsUnderCapAndSupportsZeroLossPassthrough() {
        val smallTarget = 8L * 1024L * 1024L
        val compressed = FilesizeEstimator.estimateLocalPipeline(
            sampleVideo,
            PipelineSpec(
                compress = CompressSpec(
                    mode = CompressMode.TARGET_BYTES,
                    targetBytes = smallTarget,
                    tier = CompressionTier.PERCEPTUAL_AV1
                )
            )
        )
        assertTrue(compressed.estimatedBytes <= smallTarget)
        assertTrue(compressed.maxBytes <= smallTarget)
        assertTrue((compressed.deltaPercent ?: 0) < 0)

        val generousTarget = 100L * 1024L * 1024L
        val passthrough = FilesizeEstimator.estimateLocalPipeline(
            sampleVideo,
            PipelineSpec(
                compress = CompressSpec(mode = CompressMode.TARGET_BYTES, targetBytes = generousTarget)
            )
        )
        assertEquals(sampleVideo.fileSizeBytes, passthrough.estimatedBytes)
        assertEquals(0, passthrough.deltaPercent)
    }

    @Test
    fun audioCodecOrderingAndTargetBytesWorkCleanly() {
        fun estimateAudio(ext: String, codec: AudioCodecChoice): Long {
            return FilesizeEstimator.estimateLocalPipeline(
                sampleAudio,
                PipelineSpec(convert = ConvertSpec(targetExtension = ext, audioCodec = codec))
            ).estimatedBytes
        }

        val opus = estimateAudio("opus", AudioCodecChoice.OPUS)
        val mp3 = estimateAudio("mp3", AudioCodecChoice.MP3)
        val aac = estimateAudio("m4a", AudioCodecChoice.AAC)
        val flac = estimateAudio("flac", AudioCodecChoice.FLAC)
        val wav = estimateAudio("wav", AudioCodecChoice.WAV)

        assertTrue("OPUS ($opus) < MP3 ($mp3)", opus < mp3)
        assertEquals(mp3, aac)
        assertTrue("MP3 ($mp3) < FLAC ($flac)", mp3 < flac)
        assertTrue("FLAC ($flac) < WAV ($wav)", flac < wav)

        val targetBytes = 2L * 1024L * 1024L
        val targetAudio = FilesizeEstimator.estimateLocalPipeline(
            sampleAudio,
            PipelineSpec(
                convert = ConvertSpec("mp3", audioCodec = AudioCodecChoice.MP3),
                compress = CompressSpec(mode = CompressMode.TARGET_BYTES, targetBytes = targetBytes),
                silenceSkip = SilenceSkipSpec(enabled = true)
            )
        )
        assertTrue(targetAudio.estimatedBytes <= targetBytes)
    }

    @Test
    fun imageAndRawRespectFormatQualityHalfSizeCropAndTargetBytes() {
        val webp = FilesizeEstimator.estimateLocalPipeline(
            sampleImage,
            PipelineSpec(
                convert = ConvertSpec("webp"),
                compress = CompressSpec(mode = CompressMode.QUALITY, imageQuality = 85)
            )
        ).estimatedBytes
        val jpg = FilesizeEstimator.estimateLocalPipeline(
            sampleImage,
            PipelineSpec(
                convert = ConvertSpec("jpg"),
                compress = CompressSpec(mode = CompressMode.QUALITY, imageQuality = 85)
            )
        ).estimatedBytes
        val png = FilesizeEstimator.estimateLocalPipeline(
            sampleImage,
            PipelineSpec(
                convert = ConvertSpec("png"),
                compress = CompressSpec(mode = CompressMode.QUALITY, imageQuality = 85)
            )
        ).estimatedBytes

        assertTrue("WEBP ($webp) < JPG ($jpg)", webp < jpg)
        assertTrue("JPG ($jpg) << PNG ($png)", jpg * 2L < png)

        val q35 = FilesizeEstimator.estimateLocalPipeline(
            sampleImage,
            PipelineSpec(
                convert = ConvertSpec("jpg"),
                compress = CompressSpec(mode = CompressMode.QUALITY, imageQuality = 35)
            )
        ).estimatedBytes
        val q75 = FilesizeEstimator.estimateLocalPipeline(
            sampleImage,
            PipelineSpec(
                convert = ConvertSpec("jpg"),
                compress = CompressSpec(mode = CompressMode.QUALITY, imageQuality = 75)
            )
        ).estimatedBytes
        val q95 = FilesizeEstimator.estimateLocalPipeline(
            sampleImage,
            PipelineSpec(
                convert = ConvertSpec("jpg"),
                compress = CompressSpec(mode = CompressMode.QUALITY, imageQuality = 95)
            )
        ).estimatedBytes
        assertTrue("Q35 ($q35) < Q75 ($q75) < Q95 ($q95)", q35 < q75 && q75 < q95)

        val rawFull = FilesizeEstimator.estimateLocalPipeline(
            sampleRaw,
            PipelineSpec(
                convert = ConvertSpec("jpg"),
                compress = CompressSpec(mode = CompressMode.QUALITY, imageQuality = 90, rawHalfSizeFast = false)
            )
        ).estimatedBytes
        val rawHalf = FilesizeEstimator.estimateLocalPipeline(
            sampleRaw,
            PipelineSpec(
                convert = ConvertSpec("jpg"),
                compress = CompressSpec(mode = CompressMode.QUALITY, imageQuality = 90, rawHalfSizeFast = true)
            )
        ).estimatedBytes
        assertTrue("RAW half-size ($rawHalf) should be ~0.25x of full ($rawFull)", rawHalf < rawFull / 3L)

        // When capped below half-size (e.g. 1200px vs 6000/2=3000px), maxDimensionPx governs final pixel count
        val rawHalfCapped = FilesizeEstimator.estimateLocalPipeline(
            sampleRaw,
            PipelineSpec(
                convert = ConvertSpec("jpg"),
                compress = CompressSpec(mode = CompressMode.QUALITY, imageQuality = 90, rawHalfSizeFast = true, maxDimensionPx = 1200)
            )
        ).estimatedBytes
        val rawFullCapped = FilesizeEstimator.estimateLocalPipeline(
            sampleRaw,
            PipelineSpec(
                convert = ConvertSpec("jpg"),
                compress = CompressSpec(mode = CompressMode.QUALITY, imageQuality = 90, rawHalfSizeFast = false, maxDimensionPx = 1200)
            )
        ).estimatedBytes
        assertEquals(rawFullCapped, rawHalfCapped)

        val cap = 500L * 1024L
        val cappedJpg = FilesizeEstimator.estimateLocalPipeline(
            sampleImage,
            PipelineSpec(
                convert = ConvertSpec("jpg"),
                compress = CompressSpec(mode = CompressMode.TARGET_BYTES, targetBytes = cap)
            )
        )
        assertTrue(cappedJpg.estimatedBytes <= cap)
    }

    @Test
    fun sampleCalibrationMarksFlagAndScalesProportionally() {
        val uncalibrated = FilesizeEstimator.estimateLocalPipeline(
            sampleImage,
            PipelineSpec(convert = ConvertSpec("jpg"), compress = CompressSpec(imageQuality = 85))
        )
        assertFalse(uncalibrated.isCalibratedFromSample)

        val cal1x = FilesizeEstimator.estimateLocalPipeline(
            sampleImage,
            PipelineSpec(convert = ConvertSpec("jpg"), compress = CompressSpec(imageQuality = 85)),
            sampleCalibration = SampleCalibrationRatio(referenceBytes = 120_000L, compressedBytes = 50_000L, areaScaleToFinal = 1.0)
        )
        val cal4x = FilesizeEstimator.estimateLocalPipeline(
            sampleImage,
            PipelineSpec(convert = ConvertSpec("jpg"), compress = CompressSpec(imageQuality = 85)),
            sampleCalibration = SampleCalibrationRatio(referenceBytes = 120_000L, compressedBytes = 50_000L, areaScaleToFinal = 4.0)
        )
        assertTrue(cal1x.isCalibratedFromSample)
        assertTrue(cal4x.isCalibratedFromSample)
        assertTrue("4x areaScale (${cal4x.estimatedBytes}) > 1x (${cal1x.estimatedBytes})", cal4x.estimatedBytes > cal1x.estimatedBytes * 3L)

        val videoCal1x = FilesizeEstimator.estimateLocalPipeline(
            sampleVideo,
            PipelineSpec(compress = CompressSpec(mode = CompressMode.QUALITY, videoCrf = 23)),
            sampleCalibration = SampleCalibrationRatio(referenceBytes = 320_000L, compressedBytes = 180_000L, areaScaleToFinal = 1.0)
        )
        val videoCal2x = FilesizeEstimator.estimateLocalPipeline(
            sampleVideo,
            PipelineSpec(compress = CompressSpec(mode = CompressMode.QUALITY, videoCrf = 23)),
            sampleCalibration = SampleCalibrationRatio(referenceBytes = 320_000L, compressedBytes = 180_000L, areaScaleToFinal = 2.25)
        )
        assertTrue(videoCal1x.isCalibratedFromSample)
        assertTrue(videoCal2x.estimatedBytes > videoCal1x.estimatedBytes)
    }

    @Test
    fun downloaderLinkChipsPlaylistsChaptersAndBatchesEstimateAccurately() {
        val tracks = listOf(
            PlaylistTrackItem(1, "t1", "Track 1", durationSec = 180L, selected = true),
            PlaylistTrackItem(2, "t2", "Track 2", durationSec = 240L, selected = true),
            PlaylistTrackItem(3, "t3", "Track 3", durationSec = 180L, selected = false)
        )
        val playlistChip = ResolvedLinkChipItem(
            cleanUrl = "https://youtube.com/playlist?list=PL123",
            host = "youtube.com",
            resolveState = LinkResolveState.RESOLVED,
            detectedKind = DetectedLinkKind.PLAYLIST,
            durationSec = 600L,
            tracks = tracks,
            settings = LinkItemSettings(
                mode = DownloadMode.AUDIO_ONLY,
                audioFormat = "mp3",
                playlistSingleOnly = false
            )
        )

        val audioEst = FilesizeEstimator.estimateLinkChip(playlistChip)
        assertNotNull(audioEst)
        // Selected duration = 180 + 240 = 420s @ 192 kbps = ~10.08 MB
        val expectedMp3 = (420.0 * (192_000.0 / 8.0)).toLong()
        assertTrue(audioEst!!.estimatedBytes in (expectedMp3 - 25_000L)..(expectedMp3 + 25_000L))

        val opusEst = FilesizeEstimator.estimateLinkChip(
            playlistChip.copy(settings = playlistChip.settings.copy(audioFormat = "opus"))
        )!!
        val flacEst = FilesizeEstimator.estimateLinkChip(
            playlistChip.copy(settings = playlistChip.settings.copy(audioFormat = "flac"))
        )!!
        assertTrue(opusEst.estimatedBytes < audioEst.estimatedBytes)
        assertTrue(audioEst.estimatedBytes < flacEst.estimatedBytes)

        // Track estimation
        val singleTrackEst = FilesizeEstimator.estimatePlaylistTrack(playlistChip, tracks[0])
        assertNotNull(singleTrackEst)
        assertTrue(singleTrackEst!!.estimatedBytes < audioEst.estimatedBytes)

        // Target MB multiplied by selectedTrackCount (2)
        val targetPlaylistChip = playlistChip.copy(
            settings = LinkItemSettings(mode = DownloadMode.VIDEO_MP4, playlistSingleOnly = false, targetSizeMb = 10.0)
        )
        val targetPlaylistEst = FilesizeEstimator.estimateLinkChip(targetPlaylistChip)!!
        assertEquals(2L * 10L * 1024L * 1024L, targetPlaylistEst.estimatedBytes)

        // Chapter splitting + approxBytes scaling
        val chapters = listOf(
            ChapterTrackItem(1, "Intro", startMs = 0L, endMs = 60_000L, selected = true),
            ChapterTrackItem(2, "Main", startMs = 60_000L, endMs = 180_000L, selected = false),
            ChapterTrackItem(3, "Outro", startMs = 180_000L, endMs = 240_000L, selected = true)
        )
        val chapterChip = ResolvedLinkChipItem(
            cleanUrl = "https://youtube.com/watch?v=abc",
            host = "youtube.com",
            resolveState = LinkResolveState.RESOLVED,
            durationSec = 240L,
            approxBytes = 100_000_000L,
            chapters = chapters,
            settings = LinkItemSettings(mode = DownloadMode.VIDEO_MP4, maxHeight = 1080, splitByChapters = true)
        )
        val chapter1Est = FilesizeEstimator.estimateChapterTrack(chapterChip, chapters[0])!!
        assertEquals(25_000_000L, chapter1Est.estimatedBytes)

        val splitEst1080 = FilesizeEstimator.estimateLinkChip(chapterChip)!!
        val splitEst720 = FilesizeEstimator.estimateLinkChip(
            chapterChip.copy(settings = chapterChip.settings.copy(maxHeight = 720))
        )!!
        // Selected chapters = 60s + 60s = 120s out of 240s -> 50 MB at 1080p
        assertEquals(50_000_000L, splitEst1080.estimatedBytes)
        assertTrue(splitEst720.estimatedBytes < splitEst1080.estimatedBytes)

        // Unresolved link without duration/approxBytes/targetSize returns null
        val unresolved = ResolvedLinkChipItem(cleanUrl = "https://youtube.com/watch?v=xyz", host = "youtube.com")
        assertNull(FilesizeEstimator.estimateLinkChip(unresolved))

        // Batch aggregation sums all valid chips
        val batch = FilesizeEstimator.estimateBatchLinks(listOf(playlistChip, chapterChip, unresolved))!!
        assertEquals(audioEst.estimatedBytes + splitEst1080.estimatedBytes, batch.estimatedBytes)
    }
}
