package com.sleepbot.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One sleep session; mirrors the legacy `hours` table (soft-deleted rows stay for sync/backup). */
@Entity(tableName = "hours", indices = [Index("awake"), Index("punch_token")])
data class SleepEntry(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "_id") val id: Long = 0,
    val sleep: Long,
    val awake: Long,
    val note: String = "",
    /** 1..5, or -1 when unrated. */
    val rating: Int = -1,
    @ColumnInfo(name = "utc_offset") val utcOffsetSec: Long = 0,
    @ColumnInfo(name = "punch_token") val punchToken: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "createdDate") val created: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "modifiedDate") val modified: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "movement_id") val hasMovement: Boolean = false,
    @ColumnInfo(name = "voice_id") val hasSound: Boolean = false,
    val deleted: Boolean = false,
) {
    /** Duration in hours, or -1 when the entry is invalid (legacy Record.getDuration). */
    val durationHours: Double
        get() = if (sleep == 0L || awake == 0L || awake < sleep) -1.0 else (awake - sleep) / 3_600_000.0
}
