package com.fissal.forge.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ForgeJobEntity::class],
    version = 2,
    exportSchema = false
)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun jobDao(): ForgeJobDao

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

        fun getInstance(context: Context): ForgeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ForgeDatabase::class.java,
                    "redfur_forge.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
