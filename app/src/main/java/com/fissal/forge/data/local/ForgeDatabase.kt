package com.fissal.forge.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ForgeJobEntity::class, FormatPresetEntity::class],
    version = 3,
    exportSchema = false
)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun jobDao(): ForgeJobDao
    abstract fun presetDao(): FormatPresetDao

    companion object {
        @Volatile
        private var INSTANCE: ForgeDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE forge_jobs ADD COLUMN groupId TEXT")
                db.execSQL("ALTER TABLE forge_jobs ADD COLUMN groupTitle TEXT")
                db.execSQL("ALTER TABLE forge_jobs ADD COLUMN playlistIndex INTEGER")
                db.execSQL("ALTER TABLE forge_jobs ADD COLUMN playlistTotal INTEGER")
                db.execSQL("ALTER TABLE forge_jobs ADD COLUMN thumbnailUrl TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS format_presets (" +
                        "id TEXT NOT NULL, " +
                        "emoji TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "mediaKind TEXT NOT NULL, " +
                        "specJson TEXT NOT NULL, " +
                        "isDefaultForKind INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_format_presets_mediaKind " +
                        "ON format_presets (mediaKind)"
                )
            }
        }

        fun getInstance(context: Context): ForgeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ForgeDatabase::class.java,
                    "redfur_forge.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
