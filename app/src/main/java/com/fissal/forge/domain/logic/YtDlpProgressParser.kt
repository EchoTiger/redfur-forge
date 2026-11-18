package com.fissal.forge.domain.logic

import kotlin.math.max
import kotlin.math.roundToInt

data class DownloadProgressSnapshot(
    val percent: Float,
    val statusText: String
)

/**
 * Stateful line parser for yt-dlp, aria2c, and FFmpeg post-processor stdout lines.
 * Distinguishes indeterminate extraction/handshake stages (percent = -1f) from active byte
 * transfers and enforces monotonic progress across dual-stream video+audio fetches.
 */
class YtDlpProgressTracker(
    private val expectsSeparateAudioStream: Boolean
) {
    private var destinationCount = 0
    private var hasByteTransferStarted = false
    private var lastDeterminatePct = -1f

    fun onLine(rawLine: String): DownloadProgressSnapshot? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null

        val lower = line.lowercase()

        // 1. Track stream boundaries ([download] Destination: ...)
        if (lower.startsWith("[download] destination:")) {
            destinationCount++
            if (!hasByteTransferStarted) {
                return DownloadProgressSnapshot(-1f, "Starting transfer...")
            }
            val label = if (expectsSeparateAudioStream && destinationCount >= 2) {
                "Fetching audio track..."
            } else {
                "Fetching next stream..."
            }
            return DownloadProgressSnapshot(lastDeterminatePct.coerceAtLeast(1f), label)
        }

        // 2. Active yt-dlp download progress line
        YTDLP_PROGRESS_REGEX.find(line)?.let { match ->
            val rawPct = match.groupValues[1].toFloatOrNull()?.coerceIn(0f, 100f) ?: return null
            if (!hasByteTransferStarted && rawPct <= 0f) {
                return DownloadProgressSnapshot(-1f, "Connecting to media stream...")
            }
            hasByteTransferStarted = true
            val speed = YTDLP_SPEED_REGEX.find(line)?.groupValues?.getOrNull(1)
                ?.trim()?.takeUnless { it.equals("unknown", ignoreCase = true) }
            val eta = YTDLP_ETA_REGEX.find(line)?.groupValues?.getOrNull(1)
                ?.trim()?.takeUnless { it.equals("unknown", ignoreCase = true) }

            val mappedPct = mapStreamPercent(rawPct)
            lastDeterminatePct = max(lastDeterminatePct, mappedPct)

            val streamPrefix = when {
                !expectsSeparateAudioStream -> "Downloading"
                destinationCount >= 2 -> "Downloading audio"
                else -> "Downloading video"
            }
            val msg = buildString {
                append("$streamPrefix ${rawPct.roundToInt()}%")
                if (!speed.isNullOrBlank()) append(" • $speed")
                if (!eta.isNullOrBlank()) append(" • ETA $eta")
            }
            return DownloadProgressSnapshot(lastDeterminatePct, msg)
        }

        // 3. Active aria2c summary progress line
        ARIA2C_PROGRESS_REGEX.find(line)?.let { match ->
            val rawPct = match.groupValues[1].toFloatOrNull()?.coerceIn(0f, 100f) ?: return null
            if (!hasByteTransferStarted && rawPct <= 0f) {
                return DownloadProgressSnapshot(-1f, "Opening multi-stream connections...")
            }
            hasByteTransferStarted = true
            val speed = match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("0B", ignoreCase = true) }
            val eta = match.groupValues.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }

            val mappedPct = mapStreamPercent(rawPct)
            lastDeterminatePct = max(lastDeterminatePct, mappedPct)

            val streamPrefix = if (expectsSeparateAudioStream && destinationCount >= 2) {
                "Downloading audio"
            } else {
                "Downloading"
            }
            val msg = buildString {
                append("$streamPrefix ${rawPct.roundToInt()}%")
                if (!speed.isNullOrBlank()) append(" • $speed/s")
                if (!eta.isNullOrBlank()) append(" • ETA $eta")
            }
            return DownloadProgressSnapshot(lastDeterminatePct, msg)
        }

        // 4. Post-processing stages
        if (lower.startsWith("[merger]") || lower.contains("merging formats")) {
            val pct = max(lastDeterminatePct, 97f)
            lastDeterminatePct = pct
            return DownloadProgressSnapshot(pct, "Merging video & audio...")
        }
        if (lower.startsWith("[extractaudio]") || lower.startsWith("[videoconvertor]")) {
            val pct = max(lastDeterminatePct, 97f)
            lastDeterminatePct = pct
            return DownloadProgressSnapshot(pct, "Converting media format...")
        }
        if (lower.startsWith("[metadata]") || lower.startsWith("[embedthumbnail]") || lower.startsWith("[modifychapters]")) {
            val pct = max(lastDeterminatePct, 98.5f)
            lastDeterminatePct = pct
            return DownloadProgressSnapshot(pct, "Writing tags & artwork...")
        }
        if (lower.startsWith("[fixup")) {
            val pct = max(lastDeterminatePct, 98f)
            lastDeterminatePct = pct
            return DownloadProgressSnapshot(pct, "Finalizing container...")
        }

        // 5. Handshake & extractor phases before byte transfer begins
        if (!hasByteTransferStarted) {
            val status = when {
                lower.contains("sponsorblock") -> "Checking SponsorBlock segments..."
                lower.contains("downloading webpage") || lower.contains("downloading player") ||
                    lower.contains("downloading tv") || lower.contains("downloading ios") ||
                    lower.contains("downloading android") || lower.contains("downloading api") ->
                    "Negotiating video extractor..."
                lower.contains("downloading m3u8") || lower.contains("downloading manifest") ||
                    lower.startsWith("[info]") ->
                    "Selecting best stream..."
                lower.contains("extracting url") || (line.startsWith("[") && lower.contains("] ")) ->
                    "Resolving media link..."
                lower.contains("already been downloaded") ->
                    "Preparing local file..."
                else -> null
            }
            if (status != null) {
                return DownloadProgressSnapshot(-1f, status)
            }
        }

        return null
    }

    private fun mapStreamPercent(rawPct: Float): Float {
        return if (!expectsSeparateAudioStream) {
            (1f + rawPct * 0.95f).coerceIn(1f, 96f)
        } else if (destinationCount >= 2) {
            (86f + rawPct * 0.11f).coerceIn(86f, 97f)
        } else {
            (1f + rawPct * 0.85f).coerceIn(1f, 86f)
        }
    }

    companion object {
        private val YTDLP_PROGRESS_REGEX = Regex("""\[download\]\s+(\d+(?:\.\d+)?)%""")
        private val YTDLP_SPEED_REGEX = Regex("""\bat\s+([0-9.]+\s*[KMGTP]?i?B/s)\b""", RegexOption.IGNORE_CASE)
        private val YTDLP_ETA_REGEX = Regex("""\bETA\s+((?:\d+:)?\d+:\d+|Unknown)\b""", RegexOption.IGNORE_CASE)
        private val ARIA2C_PROGRESS_REGEX = Regex("""\[#[A-Za-z0-9]+.*?\((\d+(?:\.\d+)?)%\)(?:.*?DL:([^\s\]]+))?(?:.*?ETA:([^\s\]]+))?\]""")
    }
}
