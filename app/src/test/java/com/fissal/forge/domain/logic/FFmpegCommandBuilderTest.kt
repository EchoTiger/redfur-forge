package com.fissal.forge.domain.logic

import com.fissal.forge.domain.model.AspectPreset
import com.fissal.forge.domain.model.AudioCodecChoice
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressSpec
import com.fissal.forge.domain.model.ConvertSpec
import com.fissal.forge.domain.model.CropRectNormalized
import com.fissal.forge.domain.model.CropSpec
import com.fissal.forge.domain.model.DeliverySpec
import com.fissal.forge.domain.model.MediaKind
import com.fissal.forge.domain.model.MediaProbeResult
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.SilenceSkipSpec
import com.fissal.forge.domain.model.TrimSpec
import com.fissal.forge.domain.model.VideoCodecChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FFmpegCommandBuilderTest {

    private val sampleVideo = MediaProbeResult(
        uriString = "file:///in.mp4",
        displayName = "in.mp4",
        mimeType = "video/mp4",
        extension = "mp4",
        mediaKind = MediaKind.VIDEO,
        fileSizeBytes = 50_000_000L,
        rawWidth = 1920,
        rawHeight = 1080,
        rotationDegrees = 0,
        durationMs = 45_000L,
        hasAudio = true
    )

    @Test
    fun fastKeyframeCopyUsesStreamCopyWithoutFilters() {
        val spec = PipelineSpec(
            trim = TrimSpec(startMs = 5_000L, endMs = 20_000L, fastKeyframeCopy = true),
            delivery = DeliverySpec(stripMetadata = true)
        )
        val cmd = FFmpegCommandBuilder.buildCommand("/in.mp4", "/out.mp4", sampleVideo, spec)
        assertTrue(cmd.contains("-c") && cmd.contains("copy"))
        assertFalse(cmd.contains("-vf"))
        assertTrue(cmd.contains("-map_metadata") && cmd.contains("-1"))
    }

    @Test
    fun combinedCropTargetSizeAndMetadataStripGeneratesValidFiltergraph() {
        val spec = PipelineSpec(
            convert = ConvertSpec("mp4", VideoCodecChoice.H264, AudioCodecChoice.AAC),
            compress = CompressSpec(mode = CompressMode.TARGET_BYTES, targetBytes = 8L * 1024L * 1024L),
            trim = TrimSpec(startMs = 2_000L, endMs = 32_000L),
            crop = CropSpec(AspectPreset.RATIO_4_3, CropRectNormalized(0.125f, 0f, 0.875f, 1f)),
            delivery = DeliverySpec(stripMetadata = true)
        )
        val cmd = FFmpegCommandBuilder.buildCommand("/in.mp4", "/out.mp4", sampleVideo, spec)
        val vfIdx = cmd.indexOf("-vf")
        assertTrue(vfIdx >= 0)
        val vf = cmd[vfIdx + 1]
        assertTrue(vf.startsWith("crop=1440:1080:240:0"))
        assertTrue(cmd.contains("-b:v"))
        assertTrue(cmd.contains("+faststart"))
        assertTrue(cmd.contains("-map_metadata"))
    }

    @Test
    fun animatedGifUsesPalettegenSplitFilter() {
        val spec = PipelineSpec(
            convert = ConvertSpec("gif")
        )
        val cmd = FFmpegCommandBuilder.buildCommand("/in.mp4", "/out.gif", sampleVideo, spec)
        val fcIdx = cmd.indexOf("-filter_complex")
        assertTrue(fcIdx >= 0)
        assertTrue(cmd[fcIdx + 1].contains("palettegen"))
        assertTrue(cmd[fcIdx + 1].contains("paletteuse"))
    }

    @Test
    fun perceptualAv1BuildsSvtAv1AndOpusForTightTargetBytes() {
        val thirtyMin = sampleVideo.copy(durationMs = 1_800_000L)
        val spec = PipelineSpec(
            compress = com.fissal.forge.domain.model.CompressSpec(
                mode = com.fissal.forge.domain.model.CompressMode.TARGET_BYTES,
                targetBytes = 25L * 1024L * 1024L,
                tier = com.fissal.forge.domain.model.CompressionTier.PERCEPTUAL_AV1,
                twoPass = true
            )
        )
        assertTrue(!FFmpegCommandBuilder.shouldUseTwoPass(thirtyMin, spec))
        val cmd = FFmpegCommandBuilder.buildCommand("/in.mp4", "/out.mp4", thirtyMin, spec)
        assertTrue(cmd.contains("libsvtav1"))
        assertTrue(cmd.contains("-svtav1-params"))
        assertTrue(cmd.contains("libopus"))
        val afIdx = cmd.indexOf("-af")
        assertTrue(afIdx >= 0)
        assertTrue(cmd[afIdx + 1].contains("aresample=48000:resampler=soxr"))
    }

    @Test
    fun twoPassHevcAndH264EmitPass1AndPass2Commands() {
        val specHevc = PipelineSpec(
            compress = com.fissal.forge.domain.model.CompressSpec(
                mode = com.fissal.forge.domain.model.CompressMode.TARGET_BYTES,
                targetBytes = 15L * 1024L * 1024L,
                tier = com.fissal.forge.domain.model.CompressionTier.TWO_PASS_HEVC,
                twoPass = true
            )
        )
        assertTrue(FFmpegCommandBuilder.shouldUseTwoPass(sampleVideo, specHevc))
        val p1 = FFmpegCommandBuilder.buildCommand(
            "/in.mp4", "/out.mp4", sampleVideo, specHevc,
            passMode = EncodePassMode.PASS_1, passLogPrefix = "/tmp/p"
        )
        val p2 = FFmpegCommandBuilder.buildCommand(
            "/in.mp4", "/out.mp4", sampleVideo, specHevc,
            passMode = EncodePassMode.PASS_2, passLogPrefix = "/tmp/p"
        )
        assertTrue(p1.contains("-an"))
        assertTrue(p1.contains("/dev/null"))
        val p1Idx = p1.indexOf("-x265-params")
        val p2Idx = p2.indexOf("-x265-params")
        assertTrue(p1Idx >= 0 && p1[p1Idx + 1].contains("pass=1"))
        assertTrue(p2Idx >= 0 && p2[p2Idx + 1].contains("pass=2"))

        val specH264 = PipelineSpec(
            compress = com.fissal.forge.domain.model.CompressSpec(
                mode = com.fissal.forge.domain.model.CompressMode.TARGET_BYTES,
                targetBytes = 15L * 1024L * 1024L,
                tier = com.fissal.forge.domain.model.CompressionTier.COMPATIBLE_H264,
                twoPass = true
            )
        )
        assertTrue(FFmpegCommandBuilder.shouldUseTwoPass(sampleVideo, specH264))
        val h1 = FFmpegCommandBuilder.buildCommand(
            "/in.mp4", "/out.mp4", sampleVideo, specH264,
            passMode = EncodePassMode.PASS_1, passLogPrefix = "/tmp/h"
        )
        val h2 = FFmpegCommandBuilder.buildCommand(
            "/in.mp4", "/out.mp4", sampleVideo, specH264,
            passMode = EncodePassMode.PASS_2, passLogPrefix = "/tmp/h"
        )
        assertTrue(h1.contains("-pass") && h1.contains("1"))
        assertTrue(h2.contains("-pass") && h2.contains("2"))
    }

    @Test
    fun silenceSkipInjectsSilenceremoveAndDisablesFastStreamCopyOnAudio() {
        val sampleAudio = MediaProbeResult(
            uriString = "file:///podcast.mp3",
            displayName = "podcast.mp3",
            mimeType = "audio/mpeg",
            extension = "mp3",
            mediaKind = MediaKind.AUDIO,
            fileSizeBytes = 12_000_000L,
            durationMs = 600_000L,
            hasAudio = true
        )

        val specTopLevel = PipelineSpec(
            convert = ConvertSpec("opus"),
            trim = TrimSpec(startMs = 5_000L, endMs = 125_000L, fastKeyframeCopy = true, fadeInOut = true),
            silenceSkip = SilenceSkipSpec(enabled = true, thresholdDb = -42, minSilenceMs = 750)
        )
        val cmdTop = FFmpegCommandBuilder.buildCommand("/podcast.mp3", "/out.opus", sampleAudio, specTopLevel)
        assertFalse(cmdTop.contains("-c") && cmdTop.contains("copy"))
        val afIdxTop = cmdTop.indexOf("-af")
        assertTrue(afIdxTop >= 0)
        val afTop = cmdTop[afIdxTop + 1]
        assertTrue(afTop.contains("aresample=48000:resampler=soxr"))
        assertTrue(
            afTop.contains("silenceremove=start_periods=1:start_threshold=-42dB:stop_periods=-1:stop_duration=0.750:stop_threshold=-42dB:window=0.02")
        )
        assertFalse(afTop.contains(":timestamp=copy"))
        assertTrue(afTop.contains("afade=t=in:st=0:d=0.4"))

        val specTrimNested = PipelineSpec(
            trim = TrimSpec(
                startMs = 1_000L,
                endMs = 60_000L,
                fastKeyframeCopy = true,
                silenceSkip = SilenceSkipSpec(enabled = true, thresholdDb = -38, minSilenceMs = 600)
            )
        )
        val cmdNested = FFmpegCommandBuilder.buildCommand("/podcast.mp3", "/out.mp3", sampleAudio, specTrimNested)
        assertFalse(cmdNested.contains("-c") && cmdNested.contains("copy"))
        val afIdxNested = cmdNested.indexOf("-af")
        assertTrue(afIdxNested >= 0)
        assertTrue(
            cmdNested[afIdxNested + 1].contains(
                "silenceremove=start_periods=1:start_threshold=-38dB:stop_periods=-1:stop_duration=0.600:stop_threshold=-38dB:window=0.02"
            )
        )

        val specDisabled = PipelineSpec(
            trim = TrimSpec(
                startMs = 1_000L,
                endMs = 60_000L,
                fastKeyframeCopy = true,
                silenceSkip = SilenceSkipSpec(enabled = false)
            ),
            silenceSkip = SilenceSkipSpec(enabled = false)
        )
        val cmdDisabled = FFmpegCommandBuilder.buildCommand("/podcast.mp3", "/out.mp3", sampleAudio, specDisabled)
        assertTrue(cmdDisabled.contains("-c") && cmdDisabled.contains("copy"))
        assertFalse(cmdDisabled.contains("-af"))
    }

    @Test
    fun videoWithKeepSegmentsEmitsSyncedJumpcutsAndForcesReencode() {
        val spec = PipelineSpec(
            convert = ConvertSpec("mp4", VideoCodecChoice.COPY, AudioCodecChoice.COPY),
            trim = TrimSpec(startMs = 0L, endMs = 15_000L, fastKeyframeCopy = true, fadeInOut = true),
            silenceSkip = SilenceSkipSpec(enabled = true, thresholdDb = -36, minSilenceMs = 500)
        )
        val keepSegments = listOf(1_000L to 4_000L, 6_000L to 10_000L)
        val cmd = FFmpegCommandBuilder.buildCommand(
            inputPath = "/in.mp4",
            outputPath = "/out.mp4",
            probe = sampleVideo,
            spec = spec,
            keepSegmentsMs = keepSegments
        )

        assertFalse(cmd.contains("copy"))
        assertTrue(cmd.contains("libx264"))
        assertTrue(cmd.contains("aac"))

        val vfIdx = cmd.indexOf("-vf")
        assertTrue(vfIdx >= 0)
        val vf = cmd[vfIdx + 1]
        assertTrue(vf.startsWith("select='between(t,1.000,4.000)+between(t,6.000,10.000)',setpts='"))
        assertTrue(vf.contains("if(between(T,1.000,4.000),(T-1.000)/TB,(T-3.000)/TB)"))
        // Post-cut duration = 3000 + 4000 = 7000 ms -> fade out at 6.600
        assertTrue(vf.contains("fade=t=out:st=6.600:d=0.4"))

        val afIdx = cmd.indexOf("-af")
        assertTrue(afIdx >= 0)
        val af = cmd[afIdx + 1]
        assertTrue(af.startsWith("aselect='between(t,1.000,4.000)+between(t,6.000,10.000)',asetpts=N/SR/TB"))
        assertTrue(af.contains("afade=t=out:st=6.600:d=0.4"))
        assertFalse(af.contains("silenceremove"))
    }

    @Test
    fun videoWithoutKeepSegmentsEmitsSilenceremoveTimestampCopyAndForcesReencode() {
        val spec = PipelineSpec(
            convert = ConvertSpec("mp4", VideoCodecChoice.COPY, AudioCodecChoice.COPY),
            trim = TrimSpec(startMs = 2_000L, endMs = 20_000L, fastKeyframeCopy = true),
            silenceSkip = SilenceSkipSpec(enabled = true, thresholdDb = -40, minSilenceMs = 650)
        )
        val cmd = FFmpegCommandBuilder.buildCommand(
            inputPath = "/in.mp4",
            outputPath = "/out.mp4",
            probe = sampleVideo,
            spec = spec,
            keepSegmentsMs = null
        )

        assertFalse(cmd.contains("copy"))
        assertTrue(cmd.contains("libx264"))
        assertTrue(cmd.contains("aac"))

        val afIdx = cmd.indexOf("-af")
        assertTrue(afIdx >= 0)
        val af = cmd[afIdx + 1]
        assertTrue(
            af.contains(
                "silenceremove=start_periods=1:start_threshold=-40dB:stop_periods=-1:stop_duration=0.650:stop_threshold=-40dB:window=0.02:timestamp=copy"
            )
        )
    }

    @Test
    fun mutedOrAudioFreeVideoOmitsAudioAndSilenceFilters() {
        val keepSegments = listOf(0L to 3_000L, 5_000L to 8_000L)

        val mutedTrimSpec = PipelineSpec(
            trim = TrimSpec(muteAudio = true),
            silenceSkip = SilenceSkipSpec(enabled = true)
        )
        val cmdMutedTrim = FFmpegCommandBuilder.buildCommand(
            "/in.mp4", "/out.mp4", sampleVideo, mutedTrimSpec, keepSegmentsMs = keepSegments
        )
        assertTrue(cmdMutedTrim.contains("-an"))
        assertFalse(cmdMutedTrim.contains("-af"))
        assertFalse(cmdMutedTrim.any { it.contains("silenceremove") || it.contains("aselect") || it.contains("select=") })

        val mutedCodecSpec = PipelineSpec(
            convert = ConvertSpec("mp4", VideoCodecChoice.H264, AudioCodecChoice.MUTE),
            silenceSkip = SilenceSkipSpec(enabled = true)
        )
        val cmdMutedCodec = FFmpegCommandBuilder.buildCommand(
            "/in.mp4", "/out.mp4", sampleVideo, mutedCodecSpec, keepSegmentsMs = keepSegments
        )
        assertTrue(cmdMutedCodec.contains("-an"))
        assertFalse(cmdMutedCodec.contains("-af"))
        assertFalse(cmdMutedCodec.any { it.contains("silenceremove") || it.contains("aselect") || it.contains("select=") })

        val silentSource = sampleVideo.copy(hasAudio = false)
        val cmdNoAudio = FFmpegCommandBuilder.buildCommand(
            "/in.mp4", "/out.mp4", silentSource,
            PipelineSpec(silenceSkip = SilenceSkipSpec(enabled = true)),
            keepSegmentsMs = keepSegments
        )
        assertTrue(cmdNoAudio.contains("-an"))
        assertFalse(cmdNoAudio.contains("-af"))
        assertFalse(cmdNoAudio.any { it.contains("silenceremove") || it.contains("aselect") || it.contains("select=") })

        val cmdGif = FFmpegCommandBuilder.buildCommand(
            "/in.mp4", "/out.gif", sampleVideo,
            PipelineSpec(convert = ConvertSpec("gif"), silenceSkip = SilenceSkipSpec(enabled = true)),
            keepSegmentsMs = keepSegments
        )
        assertFalse(cmdGif.contains("-af"))
        assertFalse(cmdGif.any { it.contains("silenceremove") || it.contains("aselect") || it.contains("select=") })
    }

    @Test
    fun parseSilenceKeepSegmentsPadsBoundariesHandlesEofAndCoalescesOverflow() {
        val log = """
            [silencedetect @ 0x7a1000] silence_start: 2.000
            [silencedetect @ 0x7a1000] silence_end: 5.000 | silence_duration: 3.000
            [silencedetect @ 0x7a1000] silence_start: 8.500
        """.trimIndent()

        val segs = FFmpegCommandBuilder.parseSilenceKeepSegments(log, clipDurationMs = 10_000L)
        assertEquals(listOf(0L to 2_080L, 4_920L to 8_580L), segs)

        // Empty log returns empty list
        assertTrue(FFmpegCommandBuilder.parseSilenceKeepSegments("", clipDurationMs = 10_000L).isEmpty())

        // Short gap absorbed by speechPadMs merges cleanly into full range
        val tinyGapLog = """
            [silencedetect @ 0x7a1000] silence_start: 2.000
            [silencedetect @ 0x7a1000] silence_end: 2.100 | silence_duration: 0.100
        """.trimIndent()
        assertEquals(
            listOf(0L to 10_000L),
            FFmpegCommandBuilder.parseSilenceKeepSegments(tinyGapLog, clipDurationMs = 10_000L)
        )

        // Coalesce shortest silence gap first when exceeding maxSegments
        val multiLog = """
            [silencedetect @ 0x1] silence_start: 1.000
            [silencedetect @ 0x1] silence_end: 1.500 | silence_duration: 0.500
            [silencedetect @ 0x1] silence_start: 4.000
            [silencedetect @ 0x1] silence_end: 6.000 | silence_duration: 2.000
        """.trimIndent()
        val coalesced = FFmpegCommandBuilder.parseSilenceKeepSegments(
            ffmpegLog = multiLog,
            clipDurationMs = 10_000L,
            speechPadMs = 80L,
            maxSegments = 2
        )
        assertEquals(listOf(0L to 4_080L, 5_920L to 10_000L), coalesced)
    }

    @Test
    fun emitsDeliveryMetadataTagsAcrossTrimVideoAndAudioUnlessStrippedOrPass1() {
        val delivery = DeliverySpec(
            stripMetadata = false,
            metadataTitle = "Night Relay",
            metadataArtist = "Echo",
            metadataAlbum = "Clockwork Stars",
            metadataTrack = "3/12"
        )

        // 1. Fast keyframe copy trim
        val copyCmd = FFmpegCommandBuilder.buildCommand(
            "/in.mp4", "/out.mp4", sampleVideo,
            PipelineSpec(trim = TrimSpec(startMs = 1_000L, endMs = 10_000L, fastKeyframeCopy = true), delivery = delivery)
        )
        assertMetadataPresent(copyCmd)

        // 2. Video + silence + trim + metadata
        val videoCmd = FFmpegCommandBuilder.buildCommand(
            "/in.mp4", "/out.mp4", sampleVideo,
            PipelineSpec(
                trim = TrimSpec(startMs = 1_000L, endMs = 12_000L),
                silenceSkip = SilenceSkipSpec(enabled = true),
                delivery = delivery
            ),
            keepSegmentsMs = listOf(0L to 4_000L, 6_000L to 9_000L)
        )
        assertMetadataPresent(videoCmd)

        // 3. PASS_1 omits delivery metadata; PASS_2 emits it
        val twoPassSpec = PipelineSpec(
            compress = CompressSpec(
                mode = CompressMode.TARGET_BYTES,
                targetBytes = 10L * 1024L * 1024L,
                tier = com.fissal.forge.domain.model.CompressionTier.TWO_PASS_HEVC,
                twoPass = true
            ),
            delivery = delivery
        )
        val p1 = FFmpegCommandBuilder.buildCommand(
            "/in.mp4", "/out.mp4", sampleVideo, twoPassSpec,
            passMode = EncodePassMode.PASS_1, passLogPrefix = "/tmp/meta"
        )
        val p2 = FFmpegCommandBuilder.buildCommand(
            "/in.mp4", "/out.mp4", sampleVideo, twoPassSpec,
            passMode = EncodePassMode.PASS_2, passLogPrefix = "/tmp/meta"
        )
        assertFalse(p1.contains("-metadata"))
        assertMetadataPresent(p2)

        // 4. Audio-only + silence + metadata
        val sampleAudio = sampleVideo.copy(
            uriString = "file:///track.mp3",
            displayName = "track.mp3",
            mimeType = "audio/mpeg",
            extension = "mp3",
            mediaKind = MediaKind.AUDIO
        )
        val audioCmd = FFmpegCommandBuilder.buildCommand(
            "/track.mp3", "/out.opus", sampleAudio,
            PipelineSpec(
                convert = ConvertSpec("opus"),
                silenceSkip = SilenceSkipSpec(enabled = true),
                delivery = delivery
            )
        )
        assertMetadataPresent(audioCmd)

        // 5. stripMetadata = true suppresses metadata tags and emits -map_metadata -1
        val strippedCmd = FFmpegCommandBuilder.buildCommand(
            "/track.mp3", "/out.mp3", sampleAudio,
            PipelineSpec(delivery = delivery.copy(stripMetadata = true))
        )
        assertTrue(strippedCmd.contains("-map_metadata") && strippedCmd.contains("-1"))
        assertFalse(strippedCmd.contains("-metadata"))
    }

    @Test
    fun skipAudioFallbackSilenceRemoveSuppressesUncutVideoSilenceremoveWhenConfirmedClean() {
        val silenceOnlySpec = PipelineSpec(
            silenceSkip = SilenceSkipSpec(enabled = true, thresholdDb = -38, minSilenceMs = 600),
            delivery = DeliverySpec()
        )
        val fallbackCmd = FFmpegCommandBuilder.buildCommand(
            "/in.mp4",
            "/out.mp4",
            sampleVideo,
            silenceOnlySpec,
            keepSegmentsMs = null,
            skipAudioFallbackSilenceRemove = false
        )
        val afFallback = fallbackCmd[fallbackCmd.indexOf("-af") + 1]
        assertTrue(afFallback.contains("silenceremove"))
        assertTrue(afFallback.contains("timestamp=copy"))

        val confirmedNoPauseCmd = FFmpegCommandBuilder.buildCommand(
            "/in.mp4",
            "/out.mp4",
            sampleVideo,
            silenceOnlySpec,
            keepSegmentsMs = null,
            skipAudioFallbackSilenceRemove = true
        )
        val afIdx = confirmedNoPauseCmd.indexOf("-af")
        if (afIdx >= 0) {
            assertFalse(confirmedNoPauseCmd[afIdx + 1].contains("silenceremove"))
        }
    }

    private fun assertMetadataPresent(cmd: List<String>) {
        assertTrue(cmd.contains("title=Night Relay"))
        assertTrue(cmd.contains("artist=Echo"))
        assertTrue(cmd.contains("album=Clockwork Stars"))
        assertTrue(cmd.contains("track=3/12"))
    }
}
