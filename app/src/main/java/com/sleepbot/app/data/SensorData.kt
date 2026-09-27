package com.sleepbot.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Movement samples for one session, one float per [intervalMs] bucket (legacy accelRecord). */
@Entity(tableName = "accel_record", indices = [Index("punchToken")])
data class AccelRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val punchToken: Long,
    val startTime: Long,
    val endTime: Long,
    val intervalMs: Int,
    val values: FloatArray,
)

/** Sound amplitude samples for one session (legacy soundRecord). */
@Entity(tableName = "sound_record", indices = [Index("punchToken")])
data class SoundRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val punchToken: Long,
    val startTime: Long,
    val endTime: Long,
    val intervalMs: Int,
    val values: FloatArray,
    /** Optional recorded clip of the loudest events. */
    val filePath: String? = null,
)
