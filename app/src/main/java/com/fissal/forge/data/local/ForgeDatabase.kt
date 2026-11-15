package com.fissal.forge.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ForgeJobEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ForgeDatabase : RoomDatabase() {
    abstract fun jobDao(): ForgeJobDao

    companion object {
        @Volatile
        private var INSTANCE: ForgeDatabase? = null

        fun getInstance(context: Context): ForgeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ForgeDatabase::class.java,
                    "redfur_forge.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
