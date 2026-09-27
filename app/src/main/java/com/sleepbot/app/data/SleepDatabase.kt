package com.sleepbot.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Converters {
    @TypeConverter
    fun fromFloats(v: FloatArray): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(v) }.array()

    @TypeConverter
    fun toFloats(b: ByteArray): FloatArray {
        val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }
}

@Database(entities = [SleepEntry::class, AccelRecord::class, SoundRecord::class], version = 1)
@TypeConverters(Converters::class)
abstract class SleepDatabase : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun sensors(): SensorDao

    companion object {
        const val NAME = "sleepbot.db"
        @Volatile private var instance: SleepDatabase? = null

        fun get(context: Context): SleepDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, SleepDatabase::class.java, NAME)
                .build().also { instance = it }
        }
    }
}
