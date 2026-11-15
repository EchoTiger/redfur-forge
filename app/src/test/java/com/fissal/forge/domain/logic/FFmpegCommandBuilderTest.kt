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
import com.fissal.forge.domain.model.TrimSpec
import com.fissal.forge.domain.model.VideoCodecChoice
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
}
