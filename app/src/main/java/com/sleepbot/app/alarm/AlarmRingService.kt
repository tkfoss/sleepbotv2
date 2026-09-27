package com.sleepbot.app.alarm

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.util.Notifications
import com.sleepbot.app.util.TimeFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays firing alarms (legacy NotificationService): FIFO queue, the head one plays on the alarm
 * stream with a linear volume fade, a fallback system ringtone, optional vibration and an
 * auto-timeout. Runs as a mediaPlayback foreground service whose notification carries the
 * full-screen intent that opens [AlarmRingingActivity].
 */
class AlarmRingService : Service() {

    data class Ring(val id: Long, val time: Long, val label: String, val snooze: Int)

    companion object {
        private const val ACTION_FIRE = "fire"
        private const val ACTION_SNOOZE = "snooze"
        private const val ACTION_DISMISS = "dismiss"
        private const val EXTRA_ID = "id"
        private const val EXTRA_MIN = "min"

        @Volatile var running = false
            private set

        private val _current = MutableStateFlow<Ring?>(null)
        /** Alarm currently ringing (queue head), or null. */
        val current: StateFlow<Ring?> = _current.asStateFlow()

        /** Set when the head alarm timed out (legacy "Alarm time out" dialog). */
        val timedOut = MutableStateFlow<Ring?>(null)

        fun fire(context: Context, id: Long) {
            context.startForegroundService(Intent(context, AlarmRingService::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id))
        }

        fun snooze(context: Context, id: Long, minutes: Int) {
            if (!running) { context.app.alarms.snooze(id, minutes); return }
            context.startService(Intent(context, AlarmRingService::class.java).setAction(ACTION_SNOOZE).putExtra(EXTRA_ID, id).putExtra(EXTRA_MIN, minutes))
        }

        fun dismiss(context: Context, id: Long) {
            if (!running) { context.app.alarms.acknowledge(id); return }
            context.startService(Intent(context, AlarmRingService::class.java).setAction(ACTION_DISMISS).putExtra(EXTRA_ID, id))
        }
    }

    private val queue = ArrayDeque<Ring>()
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var fallback: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedVolume = -1
    private var playStart = 0L
    private var playing: Ring? = null
    private var settings = AlarmSettings()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepBot:alarm").apply { acquire(60 * 60_000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        val repo = app.alarms
        when (intent?.action) {
            ACTION_FIRE -> {
                val alarm = repo.get(id)
                if (alarm != null && queue.none { it.id == id }) {
                    val time = repo.pending.value[id] ?: System.currentTimeMillis()
                    val s = repo.settingsFor(id)
                    repo.markRinging(id)
                    queue.addLast(Ring(id, minOf(time, System.currentTimeMillis()), alarm.name, s.snooze))
                }
                if (queue.isEmpty()) { goForeground(Ring(id, System.currentTimeMillis(), "", 10)); finish(); return START_NOT_STICKY }
                goForeground(queue.first())
                if (playing == null) play(queue.first())
            }
            ACTION_SNOOZE -> { repo.snooze(id, intent.getIntExtra(EXTRA_MIN, 10)); remove(id) }
            ACTION_DISMISS -> { repo.acknowledge(id); remove(id) }
            else -> if (queue.isEmpty()) finish()
        }
        return START_NOT_STICKY
    }

    private fun remove(id: Long) {
        val wasHead = queue.firstOrNull()?.id == id
        queue.removeAll { it.id == id }
        if (!wasHead && queue.isNotEmpty()) return
        stopPlayback()
        if (queue.isEmpty()) finish() else { goForeground(queue.first()); play(queue.first()) }
    }

    private fun finish() {
        stopPlayback()
        _current.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(r: Ring) {
        val open = PendingIntent.getActivity(
            this, 10,
            Intent(this, AlarmRingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = r.label.ifEmpty { TimeFormat.time(this, r.time) }
        val n = NotificationCompat.Builder(this, Notifications.CH_ALARM)
            .setSmallIcon(R.drawable.alarmclock_notification)
            .setContentTitle(title)
            .setContentText(getString(R.string.dismiss_slider))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
        ServiceCompat.startForeground(
            this, Notifications.ID_ALARM_RINGING, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        // The full-screen intent only takes over the screen when the device is locked/idle; like the
        // original, bring the ringing screen up directly whenever the system allows it.
        runCatching {
            startActivity(Intent(this, AlarmRingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        }
    }

    private val alarmAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun play(r: Ring) {
        playing = r
        _current.value = r
        timedOut.value = null
        settings = app.alarms.settingsFor(r.id)
        playStart = System.currentTimeMillis()

        val am = getSystemService(AudioManager::class.java)
        if (savedVolume < 0) {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        }
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(alarmAttrs)
                setDataSource(this@AlarmRingService, settings.toneUriOrDefault())
                isLooping = true
                setVolume(settings.volStart / 100f, settings.volStart / 100f)
                prepare()
                start()
            }
        }.getOrNull()

        if (settings.vibrate) {
            val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            val effect = VibrationEffect.createWaveform(longArrayOf(500, 500), 0)
            if (Build.VERSION.SDK_INT >= 33) v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            else @Suppress("DEPRECATION") v.vibrate(effect, alarmAttrs)
            vibrator = v
        }
        handler.post(tick)
        handler.postDelayed(timeout, app.prefs.alarmTimeoutMin * 60_000L)
    }

    /** Once per second: linear fade volStart→volEnd over volTime, fallback ringtone if silent. */
    private val tick = object : Runnable {
        override fun run() {
            val p = player
            val elapsed = (System.currentTimeMillis() - playStart) / 1000f
            val frac = (elapsed / settings.volTime).coerceIn(0f, 1f)
            val vol = (settings.volStart + (settings.volEnd - settings.volStart) * frac) / 100f
            val ok = runCatching { p != null && p.isPlaying }.getOrDefault(false)
            if (ok) {
                p!!.setVolume(vol, vol)
            } else if (fallback?.isPlaying != true) {
                fallback = runCatching {
                    RingtoneManager.getRingtone(this@AlarmRingService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                        ?.apply {
                            audioAttributes = alarmAttrs
                            if (Build.VERSION.SDK_INT >= 28) isLooping = true
                            play()
                        }
                }.getOrNull()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val timeout = Runnable {
        val r = playing ?: return@Runnable
        timedOut.value = r
        app.alarms.acknowledge(r.id)
        remove(r.id)
        runCatching {
            startActivity(Intent(this, AlarmRingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun stopPlayback() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(timeout)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { fallback?.stop() }
        fallback = null
        vibrator?.cancel()
        vibrator = null
        playing = null
    }

    override fun onDestroy() {
        stopPlayback()
        if (savedVolume >= 0) {
            runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
            savedVolume = -1
        }
        _current.value = null
        running = false
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
