package com.fissal.forge.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.fissal.forge.domain.model.MediaKind

enum class JobLane {
    NETWORK,
    COMPUTE
}

enum class JobStatus {
    QUEUED,
    DOWNLOADING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELED
}

@Entity(tableName = "forge_jobs")
data class ForgeJobEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sourceLabel: String,
    val sourceUriString: String?,
    val mediaKind: String = MediaKind.UNKNOWN.name,
    val lane: String = JobLane.COMPUTE.name,
    val status: String = JobStatus.QUEUED.name,
    val progress: Float = 0f,
    val statusText: String = "Queued",
    val pipelineSpecJson: String,
    val inputBytes: Long = 0L,
    val outputBytes: Long = 0L,
    val outputPath: String? = null,
    val outputUriString: String? = null,
    val outputMimeType: String? = null,
    val errorMessage: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val statusEnum: JobStatus
        get() = runCatching { JobStatus.valueOf(status) }.getOrDefault(JobStatus.FAILED)

    val mediaKindEnum: MediaKind
        get() = runCatching { MediaKind.valueOf(mediaKind) }.getOrDefault(MediaKind.UNKNOWN)

    val isActive: Boolean
        get() = statusEnum == JobStatus.QUEUED ||
            statusEnum == JobStatus.DOWNLOADING ||
            statusEnum == JobStatus.PROCESSING

    val savingsPercent: Int?
        get() = if (inputBytes > 0L && outputBytes > 0L && outputBytes < inputBytes) {
            (((inputBytes - outputBytes).toDouble() / inputBytes.toDouble()) * 100.0).toInt()
        } else {
            null
        }
}
