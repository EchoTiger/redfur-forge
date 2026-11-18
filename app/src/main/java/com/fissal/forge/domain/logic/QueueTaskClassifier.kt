package com.fissal.forge.domain.logic

import com.fissal.forge.data.local.JobLane
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.PipelineSpec
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class QueueTaskType(val label: String) {
    DOWNLOAD("Download"),
    CONVERT("Convert"),
    COMPRESS("Compress"),
    TRIM("Trim"),
    CROP("Crop"),
    FORGE("Forge")
}

/**
 * Classifies queued/historical jobs into user-facing task types for Queue color coding and
 * renders consistent local-timezone timestamps.
 */
object QueueTaskClassifier {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val MONTH_DAY_TIME_FMT = DateTimeFormatter.ofPattern("MMM d • h:mm a", Locale.US)
    private val FULL_DATE_TIME_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy • h:mm a", Locale.US)
    private val SHORT_DAY_TIME_FMT = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

    fun classify(pipelineSpecJson: String, fallbackLane: String): QueueTaskType {
        val spec = runCatching { json.decodeFromString<PipelineSpec>(pipelineSpecJson) }.getOrNull()
            ?: return if (fallbackLane == JobLane.NETWORK.name) QueueTaskType.DOWNLOAD else QueueTaskType.FORGE
        return classify(spec)
    }

    fun classify(spec: PipelineSpec): QueueTaskType {
        if (spec.download != null) {
            return if (spec.hasTransformStage) QueueTaskType.FORGE else QueueTaskType.DOWNLOAD
        }

        val hasConvert = spec.convert != null
        val hasCompress = spec.compress != null
        val hasTrim = spec.trim != null || (spec.silenceSkip?.enabled == true)
        val hasCrop = spec.crop != null

        // Target-bytes video compression attaches an internal codec convert helper automatically.
        if (hasCompress && spec.compress?.mode == CompressMode.TARGET_BYTES && !hasTrim && !hasCrop) {
            return QueueTaskType.COMPRESS
        }

        val activeStages = (if (hasConvert) 1 else 0) +
            (if (hasCompress) 1 else 0) +
            (if (hasTrim) 1 else 0) +
            (if (hasCrop) 1 else 0)

        return when {
            activeStages >= 2 -> QueueTaskType.FORGE
            hasCompress -> QueueTaskType.COMPRESS
            hasConvert -> QueueTaskType.CONVERT
            hasTrim -> QueueTaskType.TRIM
            hasCrop -> QueueTaskType.CROP
            else -> QueueTaskType.FORGE
        }
    }

    fun formatAddedTimestamp(
        createdAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        if (createdAtMs <= 0L) return "Added recently"
        val createdZdt = Instant.ofEpochMilli(createdAtMs).atZone(zoneId)
        val nowZdt = Instant.ofEpochMilli(nowMs).atZone(zoneId)
        val createdDate = createdZdt.toLocalDate()
        val today = nowZdt.toLocalDate()
        val yesterday = today.minusDays(1)

        return when {
            createdDate == today -> "Added Today • ${TIME_FMT.format(createdZdt)}"
            createdDate == yesterday -> "Added Yesterday • ${TIME_FMT.format(createdZdt)}"
            createdDate.year == today.year -> "Added ${MONTH_DAY_TIME_FMT.format(createdZdt)}"
            else -> "Added ${FULL_DATE_TIME_FMT.format(createdZdt)}"
        }
    }

    fun formatShortTimestamp(
        createdAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        if (createdAtMs <= 0L) return ""
        val createdZdt = Instant.ofEpochMilli(createdAtMs).atZone(zoneId)
        val nowZdt = Instant.ofEpochMilli(nowMs).atZone(zoneId)
        return if (createdZdt.toLocalDate() == nowZdt.toLocalDate()) {
            TIME_FMT.format(createdZdt)
        } else {
            SHORT_DAY_TIME_FMT.format(createdZdt)
        }
    }
}
