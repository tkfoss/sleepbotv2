package com.sleepbot.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Query("SELECT * FROM hours WHERE deleted = 0 AND awake >= :from AND awake < :to ORDER BY awake DESC")
    fun observeRange(from: Long, to: Long): Flow<List<SleepEntry>>

    @Query("SELECT * FROM hours WHERE deleted = 0 AND awake >= :from AND awake < :to ORDER BY awake DESC")
    suspend fun range(from: Long, to: Long): List<SleepEntry>

    @Query("SELECT * FROM hours WHERE deleted = 0 ORDER BY awake DESC")
    fun observeAll(): Flow<List<SleepEntry>>

    @Query("SELECT * FROM hours WHERE deleted = 0 ORDER BY awake ASC")
    suspend fun all(): List<SleepEntry>

    @Query("SELECT MIN(awake) FROM hours WHERE deleted = 0")
    suspend fun firstAwake(): Long?

    @Query("SELECT * FROM hours WHERE deleted = 0 AND awake < :before ORDER BY awake DESC LIMIT 1")
    suspend fun lastBefore(before: Long): SleepEntry?

    @Query("SELECT * FROM hours WHERE _id = :id")
    suspend fun get(id: Long): SleepEntry?

    @Insert
    suspend fun insert(entry: SleepEntry): Long

    @Insert
    suspend fun insertAll(entries: List<SleepEntry>)

    @Update
    suspend fun update(entry: SleepEntry)

    @Query("UPDATE hours SET deleted = 1, modifiedDate = :now WHERE _id = :id")
    suspend fun softDelete(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM hours")
    suspend fun wipe()
}

@Dao
interface SensorDao {
    @Insert
    suspend fun insertAccel(r: AccelRecord): Long

    @Insert
    suspend fun insertSound(r: SoundRecord): Long

    @Update
    suspend fun updateAccel(r: AccelRecord)

    @Update
    suspend fun updateSound(r: SoundRecord)

    @Query("SELECT * FROM accel_record WHERE punchToken = :token ORDER BY (endTime - startTime) DESC LIMIT 1")
    suspend fun accel(token: Long): AccelRecord?

    @Query("SELECT * FROM sound_record WHERE punchToken = :token ORDER BY startTime")
    suspend fun sound(token: Long): List<SoundRecord>

    @Query("DELETE FROM accel_record WHERE punchToken = :token")
    suspend fun deleteAccel(token: Long)

    @Delete
    suspend fun deleteSound(records: List<SoundRecord>)
}
