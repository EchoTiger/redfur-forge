package com.fissal.forge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for persisting and querying saved [FormatPresetEntity] profiles.
 */
@Dao
interface FormatPresetDao {

    @Query("SELECT * FROM format_presets WHERE mediaKind = :mediaKind ORDER BY isDefaultForKind DESC, createdAt ASC")
    fun observeByMediaKind(mediaKind: String): Flow<List<FormatPresetEntity>>

    @Query("SELECT * FROM format_presets ORDER BY mediaKind ASC, isDefaultForKind DESC, createdAt ASC")
    fun observeAllPresets(): Flow<List<FormatPresetEntity>>

    @Query("SELECT * FROM format_presets WHERE id = :id")
    suspend fun getById(id: String): FormatPresetEntity?

    @Query("SELECT * FROM format_presets WHERE mediaKind = :mediaKind AND isDefaultForKind = 1 LIMIT 1")
    suspend fun getDefaultForKind(mediaKind: String): FormatPresetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: FormatPresetEntity)

    @Query("UPDATE format_presets SET isDefaultForKind = 0 WHERE mediaKind = :mediaKind")
    suspend fun clearDefaultForKind(mediaKind: String)

    @Query("UPDATE format_presets SET isDefaultForKind = 1 WHERE id = :presetId AND mediaKind = :mediaKind")
    suspend fun markDefaultForKind(mediaKind: String, presetId: String)

    @Transaction
    suspend fun setDefaultForKind(mediaKind: String, presetId: String) {
        clearDefaultForKind(mediaKind)
        markDefaultForKind(mediaKind, presetId)
    }

    @Query("DELETE FROM format_presets WHERE id = :id")
    suspend fun deleteById(id: String)
}
