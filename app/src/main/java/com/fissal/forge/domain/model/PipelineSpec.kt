package com.fissal.forge.domain.model

import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val presetNormalizationJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
enum class AspectPreset(val label: String, val ratioW: Int?, val ratioH: Int?) {
    AUTO_CLOSEST("Closest (16:9 / 4:3)", null, null),
    RATIO_16_9("16:9", 16, 9),
    RATIO_4_3("4:3", 4, 3),
    RATIO_9_16("9:16", 9, 16),
    RATIO_3_4("3:4", 3, 4),
    RATIO_1_1("1:1", 1, 1),
    RATIO_3_2("3:2", 3, 2),
    RATIO_2_3("2:3", 2, 3),
    FREEFORM("Free", null, null)
}

@Serializable
data class CropRectNormalized(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
)

data class PixelCropRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

@Serializable
enum class DownloadMode(val label: String) {
    VIDEO_MP4("MP4 Universal"),
    VIDEO_BEST_MKV("Best Quality MKV"),
    AUDIO_ONLY("Audio Only")
}

@Serializable
enum class VideoCodecChoice(val label: String) {
    H264("H.264"),
    HEVC("HEVC / H.265"),
    AV1("AV1 (SVT-AV1)"),
    VP9("VP9"),
    COPY("Stream Copy")
}

@Serializable
enum class CompressionTier(val label: String, val subtitle: String) {
    PERCEPTUAL_AV1("Smart AV1 + Opus", "Highest compression efficiency (ideal for long clips -> 25 MB)"),
    TWO_PASS_HEVC("2-Pass HEVC", "Dual-pass x265 psychovisual AQ"),
    COMPATIBLE_H264("Universal H.264", "2-pass H.264 + AAC for older devices")
}

@Serializable
enum class AudioCodecChoice(val label: String) {
    AAC("AAC"),
    MP3("MP3"),
    OPUS("Opus"),
    FLAC("FLAC"),
    WAV("WAV"),
    MUTE("Mute"),
    COPY("Stream Copy")
}

@Serializable
enum class CompressMode {
    QUALITY,
    TARGET_BYTES
}

/**
 * Segment categories supported by yt-dlp `--sponsorblock-remove`.
 */
@Serializable
enum class SponsorBlockCategory(
    val ytDlpKey: String,
    val label: String,
    val description: String
) {
    SPONSOR("sponsor", "Sponsors", "Paid promotion, sponsor reads, and direct advertisements"),
    SELFPROMO("selfpromo", "Self-Promo", "Unpaid merch, Patreon plugs, and creator self-promotion"),
    INTERACTION("interaction", "Interaction Reminders", "Like, subscribe, and notification bell reminders"),
    INTRO("intro", "Intros / Intermissions", "Recurring intro animations or intermission bumpers"),
    OUTRO("outro", "Endcards / Credits", "Closing credits and endcard overlays"),
    PREVIEW("preview", "Previews / Recaps", "Recaps or upcoming teaser segments"),
    MUSIC_OFFTOPIC("music_offtopic", "Non-Music Sections", "Sketches or non-music dialogue in music videos")
}

/**
 * Configuration for FFmpeg silence detection / dead-air removal (`silenceremove`).
 */
@Serializable
data class SilenceSkipSpec(
    val enabled: Boolean = false,
    val thresholdDb: Int = -38,
    val minSilenceMs: Int = 600
)

@Serializable
data class DownloadSpec(
    val url: String,
    val playlistSingleOnly: Boolean = true,
    val playlistItemIndex: Int? = null,
    val mode: DownloadMode = DownloadMode.VIDEO_MP4,
    val maxHeight: Int? = 1080,
    val audioFormat: String = "mp3",
    val embedMetadata: Boolean = true,
    val embedThumbnail: Boolean = true,
    val sponsorBlock: Boolean = false,
    val useAria2c: Boolean = true,
    val sponsorBlockCategories: Set<SponsorBlockCategory> = emptySet(),
    val siteConnectionId: String? = null,
    val splitByChapters: Boolean = false
) {
    /**
     * Resolves active SponsorBlock categories, preserving legacy boolean [sponsorBlock] behavior
     * when explicit [sponsorBlockCategories] have not been chosen.
     */
    val effectiveSponsorBlockCategories: Set<SponsorBlockCategory>
        get() = when {
            sponsorBlockCategories.isNotEmpty() -> sponsorBlockCategories
            sponsorBlock -> setOf(SponsorBlockCategory.SPONSOR)
            else -> emptySet()
        }
}

@Serializable
data class ConvertSpec(
    val targetExtension: String,
    val videoCodec: VideoCodecChoice = VideoCodecChoice.H264,
    val audioCodec: AudioCodecChoice = AudioCodecChoice.AAC
)

@Serializable
data class CompressSpec(
    val mode: CompressMode = CompressMode.QUALITY,
    val videoCrf: Int = 23,
    val imageQuality: Int = 88,
    val targetBytes: Long? = null,
    val maxDimensionPx: Int? = null,
    val rawHalfSizeFast: Boolean = false,
    val tier: CompressionTier = CompressionTier.PERCEPTUAL_AV1,
    val twoPass: Boolean = true
)

@Serializable
data class TrimSpec(
    val startMs: Long = 0L,
    val endMs: Long? = null,
    val fastKeyframeCopy: Boolean = false,
    val fadeInOut: Boolean = false,
    val muteAudio: Boolean = false,
    val silenceSkip: SilenceSkipSpec? = null
)

@Serializable
data class CropSpec(
    val preset: AspectPreset = AspectPreset.AUTO_CLOSEST,
    val rect: CropRectNormalized = CropRectNormalized()
)

@Serializable
data class DeliverySpec(
    val saveToDevice: Boolean = true,
    val copyToClipboard: Boolean = false,
    val stripMetadata: Boolean = false,
    val filenameSuffix: String = "_forge",
    val customOutputBaseName: String? = null,
    val metadataTitle: String? = null,
    val metadataArtist: String? = null,
    val metadataAlbum: String? = null,
    val metadataTrack: String? = null
)

@Serializable
data class PipelineSpec(
    val download: DownloadSpec? = null,
    val convert: ConvertSpec? = null,
    val compress: CompressSpec? = null,
    val trim: TrimSpec? = null,
    val crop: CropSpec? = null,
    val delivery: DeliverySpec = DeliverySpec(),
    val silenceSkip: SilenceSkipSpec? = null
) {
    val hasTransformStage: Boolean
        get() = convert != null ||
            compress != null ||
            trim != null ||
            crop != null ||
            (silenceSkip?.enabled == true) ||
            (trim?.silenceSkip?.enabled == true)

    fun summaryLabel(): String {
        val parts = mutableListOf<String>()
        if (download != null) parts += download.mode.label
        if (convert != null) parts += "→ ${convert.targetExtension.uppercase()}"
        if (compress != null) {
            parts += if (compress.mode == CompressMode.TARGET_BYTES && compress.targetBytes != null) {
                val mb = compress.targetBytes / (1024.0 * 1024.0)
                if (mb >= 1.0) String.format(Locale.US, "≤ %.1f MB", mb) else "${compress.targetBytes / 1024} KB"
            } else {
                "Compress Q${compress.videoCrf}"
            }
        }
        if (trim != null) parts += "Trim"
        if (crop != null) parts += "Crop ${crop.preset.label}"
        if (silenceSkip?.enabled == true || trim?.silenceSkip?.enabled == true) {
            parts += "Silence Skip"
        }
        if (delivery.copyToClipboard && !delivery.saveToDevice) parts += "Copy Only"
        else if (delivery.copyToClipboard) parts += "+Clipboard"
        return if (parts.isEmpty()) "Passthrough" else parts.joinToString(" • ")
    }

    /**
     * Returns a reusable format-preset copy of this pipeline with source-specific URL, crop,
     * trim timestamps, and per-file delivery filenames stripped, while preserving codec, target,
     * filter flags, and metadata-strip preferences.
     */
    fun sanitizeForPreset(): PipelineSpec {
        val cleanTrim = trim?.let { tr ->
            if (tr.fastKeyframeCopy || tr.fadeInOut || tr.muteAudio || tr.silenceSkip?.enabled == true) {
                tr.copy(startMs = 0L, endMs = null)
            } else {
                null
            }
        }
        return copy(
            download = null,
            trim = cleanTrim,
            crop = null,
            delivery = DeliverySpec(stripMetadata = delivery.stripMetadata)
        )
    }

    /**
     * Serializes the sanitized preset form of this pipeline into deterministic JSON for Room
     * persistence and cross-session comparison.
     */
    fun toNormalizedJson(): String = presetNormalizationJson.encodeToString(sanitizeForPreset())
}
