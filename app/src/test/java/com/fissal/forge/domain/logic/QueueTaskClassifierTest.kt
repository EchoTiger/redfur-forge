package com.fissal.forge.domain.logic

import com.fissal.forge.data.local.JobLane
import com.fissal.forge.domain.model.CompressMode
import com.fissal.forge.domain.model.CompressSpec
import com.fissal.forge.domain.model.ConvertSpec
import com.fissal.forge.domain.model.CropSpec
import com.fissal.forge.domain.model.DownloadSpec
import com.fissal.forge.domain.model.PipelineSpec
import com.fissal.forge.domain.model.TrimSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class QueueTaskClassifierTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun classifiesDownloadSingleEditAndMultiStageForgeTasks() {
        val pureDownload = PipelineSpec(download = DownloadSpec(url = "https://example.com/v"))
        assertEquals(QueueTaskType.DOWNLOAD, QueueTaskClassifier.classify(pureDownload))

        val downloadAndCompress = PipelineSpec(
            download = DownloadSpec(url = "https://example.com/v"),
            compress = CompressSpec(mode = CompressMode.TARGET_BYTES, targetBytes = 10_000_000L)
        )
        assertEquals(QueueTaskType.FORGE, QueueTaskClassifier.classify(downloadAndCompress))

        val pureConvert = PipelineSpec(convert = ConvertSpec(targetExtension = "mkv"))
        assertEquals(QueueTaskType.CONVERT, QueueTaskClassifier.classify(pureConvert))

        val targetCompress = PipelineSpec(
            convert = ConvertSpec(targetExtension = "mp4"),
            compress = CompressSpec(mode = CompressMode.TARGET_BYTES, targetBytes = 8_000_000L)
        )
        assertEquals(QueueTaskType.COMPRESS, QueueTaskClassifier.classify(targetCompress))

        val pureTrim = PipelineSpec(trim = TrimSpec(startMs = 1000L, endMs = 5000L))
        assertEquals(QueueTaskType.TRIM, QueueTaskClassifier.classify(pureTrim))

        val pureCrop = PipelineSpec(crop = CropSpec())
        assertEquals(QueueTaskType.CROP, QueueTaskClassifier.classify(pureCrop))

        val combinedForge = PipelineSpec(
            trim = TrimSpec(startMs = 0L, endMs = 8000L),
            crop = CropSpec()
        )
        assertEquals(QueueTaskType.FORGE, QueueTaskClassifier.classify(combinedForge))

        val rawJson = json.encodeToString(pureConvert)
        assertEquals(QueueTaskType.CONVERT, QueueTaskClassifier.classify(rawJson, JobLane.COMPUTE.name))
        assertEquals(QueueTaskType.DOWNLOAD, QueueTaskClassifier.classify("{invalid", JobLane.NETWORK.name))
    }

    @Test
    fun formatsAddedTimestampsAcrossTodayYesterdayAndOlderDates() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 11, 17, 22, 30, 0, 0, zone).toInstant().toEpochMilli()
        val sameDay = ZonedDateTime.of(2026, 11, 17, 14, 15, 0, 0, zone).toInstant().toEpochMilli()
        val yesterday = ZonedDateTime.of(2026, 11, 16, 9, 5, 0, 0, zone).toInstant().toEpochMilli()
        val lastMonth = ZonedDateTime.of(2026, 10, 4, 19, 45, 0, 0, zone).toInstant().toEpochMilli()
        val priorYear = ZonedDateTime.of(2025, 8, 12, 8, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertTrue(QueueTaskClassifier.formatAddedTimestamp(sameDay, now, zone).startsWith("Added Today • "))
        assertTrue(QueueTaskClassifier.formatAddedTimestamp(yesterday, now, zone).startsWith("Added Yesterday • "))
        assertTrue(QueueTaskClassifier.formatAddedTimestamp(lastMonth, now, zone).startsWith("Added Oct 4 • "))
        assertTrue(QueueTaskClassifier.formatAddedTimestamp(priorYear, now, zone).startsWith("Added Aug 12, 2025 • "))
    }
}
