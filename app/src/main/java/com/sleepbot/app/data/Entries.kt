package com.sleepbot.app.data

import android.content.Context
import com.sleepbot.app.app
import java.io.File

object Entries {
    /** Soft-delete an entry and drop its movement/sound data and clip files (legacy HoursProvider.delete). */
    suspend fun delete(context: Context, id: Long) {
        val db = context.app.db
        val e = db.entries().get(id) ?: return
        db.entries().softDelete(id)
        if (e.punchToken != 0L) {
            db.sensors().deleteAccel(e.punchToken)
            val sounds = db.sensors().sound(e.punchToken)
            sounds.forEach { r -> r.filePath?.let { File(it).delete() } }
            db.sensors().deleteSound(sounds)
        }
    }
}
