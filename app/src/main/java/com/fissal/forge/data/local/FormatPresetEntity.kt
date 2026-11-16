package com.fissal.forge.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * User-saved pipeline preset scoped by media kind (`VIDEO`, `AUDIO`, `IMAGE`, `CAMERA_RAW`, `REMOTE_URL`).
 */
@Entity(
    tableName = "format_presets",
    indices = [Index(value = ["mediaKind"])]
)
data class FormatPresetEntity(
    @PrimaryKey val id: String,
    val emoji: String,
    val name: String,
    val mediaKind: String,
    val specJson: String,
    val isDefaultForKind: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
