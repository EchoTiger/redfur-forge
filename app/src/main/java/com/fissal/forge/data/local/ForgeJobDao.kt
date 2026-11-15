package com.fissal.forge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ForgeJobDao {

    @Query("SELECT * FROM forge_jobs ORDER BY createdAtMs DESC")
    fun observeAllJobs(): Flow<List<ForgeJobEntity>>

    @Query("SELECT * FROM forge_jobs WHERE id = :id")
    suspend fun getById(id: String): ForgeJobEntity?

    @Query("SELECT * FROM forge_jobs WHERE groupId = :groupId ORDER BY playlistIndex ASC, createdAtMs ASC")
    suspend fun getByGroupId(groupId: String): List<ForgeJobEntity>

    @Query("SELECT * FROM forge_jobs WHERE status IN ('QUEUED', 'DOWNLOADING', 'PROCESSING') ORDER BY createdAtMs ASC")
    suspend fun getPendingOrActiveJobs(): List<ForgeJobEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: ForgeJobEntity)

    @Query("UPDATE forge_jobs SET status = :status, statusText = :statusText, progress = :progress, lane = :lane, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun updateProgress(
        id: String,
        status: String,
        statusText: String,
        progress: Float,
        lane: String,
        updatedAtMs: Long = System.currentTimeMillis()
    )

    @Query("UPDATE forge_jobs SET status = 'COMPLETED', progress = 100.0, statusText = :statusText, inputBytes = :inputBytes, outputBytes = :outputBytes, outputPath = :outputPath, outputUriString = :outputUriString, outputMimeType = :outputMimeType, errorMessage = NULL, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun markCompleted(
        id: String,
        statusText: String,
        inputBytes: Long,
        outputBytes: Long,
        outputPath: String?,
        outputUriString: String?,
        outputMimeType: String?,
        updatedAtMs: Long = System.currentTimeMillis()
    )

    @Query("UPDATE forge_jobs SET status = :status, statusText = :statusText, errorMessage = :errorMessage, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun markTerminalState(
        id: String,
        status: String,
        statusText: String,
        errorMessage: String?,
        updatedAtMs: Long = System.currentTimeMillis()
    )

    @Query("UPDATE forge_jobs SET status = 'CANCELED', statusText = 'Interrupted (tap retry)', updatedAtMs = :updatedAtMs WHERE status IN ('DOWNLOADING', 'PROCESSING', 'QUEUED')")
    suspend fun resetInterruptedJobs(updatedAtMs: Long = System.currentTimeMillis())

    @Query("DELETE FROM forge_jobs WHERE status IN ('COMPLETED', 'FAILED', 'CANCELED')")
    suspend fun clearFinishedJobs()

    @Query("DELETE FROM forge_jobs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM forge_jobs WHERE groupId = :groupId")
    suspend fun deleteByGroupId(groupId: String)
}
