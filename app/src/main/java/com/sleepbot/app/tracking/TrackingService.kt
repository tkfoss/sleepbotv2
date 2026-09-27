package com.sleepbot.app.tracking

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sleepbot.app.R
import com.sleepbot.app.alarm.AlarmScheduler
import com.sleepbot.app.app
import com.sleepbot.app.data.AccelRecord
import com.sleepbot.app.data.Prefs
import com.sleepbot.app.util.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Public API for sensor tracking (legacy RecordMovementService + RecordVoiceService). The actual
 * work happens in [SensorService], a foreground service (types health and/or microphone).
 */
object TrackingService {
    internal val _movement = MutableStateFlow(LiveMovement.EMPTY)
    internal val _amplitude = MutableStateFlow(0)
    internal val _running = MutableStateFlow(false)

    /** Live 100-point movement ring buffer (unscaled window maxima), in draw order. */
    val movement: StateFlow<LiveMovement> = _movement.asStateFlow()
    /** Live microphone peak amplitude 0..32767 (VU bar). */
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()
    /** True while the tracking service runs. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Runtime permissions needed before punching in with the current toggles. */
    fun requiredPermissions(prefs: Prefs): Array<String> =
        if (prefs.recordSound) arrayOf(Manifest.permission.RECORD_AUDIO) else emptyArray()

    /** Called by SleepSession at punch-in. */
    fun onPunchIn(context: Context) {
        val prefs = context.app.prefs
        _movement.value = LiveMovement.EMPTY
        if (prefs.trackMotion || prefs.recordSound) {
            start(context, motion = prefs.trackMotion, sound = prefs.recordSound)
        }
        if (prefs.trackMotion) {
            try {
                context.startActivity(NightActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w("TrackingService", "cannot open night screen", e)
            }
        }
    }

    /** Called by SleepSession at punch-out (and on "No Record"). Saves and stops everything. */
    fun onPunchOut(context: Context) {
        context.stopService(Intent(context, SensorService::class.java))
    }

    /**
     * Smart-window start (scheduled by the alarm module at nextAlarm − window): start motion
     * tracking if Track Motion is off but Smart Alarm is on.
     */
    fun startSmartWindow(context: Context) {
        val prefs = context.app.prefs
        if (prefs.isAwake || !prefs.smartAlarm) return
        start(context, motion = true, sound = false)
    }

    private fun start(context: Context, motion: Boolean, sound: Boolean) {
        val i = Intent(context, SensorService::class.java)
            .setAction(SensorService.ACTION_START)
            .putExtra(SensorService.EXTRA_MOTION, motion)
            .putExtra(SensorService.EXTRA_SOUND, sound)
        try {
            ContextCompat.startForegroundService(context, i)
        } catch (e: Exception) {
            Log.w("TrackingService", "cannot start tracking service", e)
        }
    }
}

/** Foreground service running the accelerometer and microphone trackers for one punch token. */
class SensorService : Service() {
    private var token = 0L
    private var motion: MotionTracker? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var sound: SoundTracker? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastSave = 0L
    private var triggerAlarm = false
    private var hasTriggered = false
    private var screenReceiver: BroadcastReceiver? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = app.prefs
        if (prefs.isAwake) {
            // Must still satisfy the startForeground contract before stopping.
            goForeground(motion = true, sound = false)
            stopSelf()
            return START_NOT_STICKY
        }
        token = prefs.punchToken
        // After a process restart (START_STICKY, null intent) resume what the toggles say.
        val wantMotion = intent?.getBooleanExtra(EXTRA_MOTION, false) ?: (prefs.trackMotion)
        val wantSound = intent?.getBooleanExtra(EXTRA_SOUND, false) ?: (prefs.recordSound)
        val motionOn = motion != null || wantMotion
        val soundOn = (sound != null || wantSound) && hasMic()
        if (!goForeground(motionOn, soundOn)) { stopSelf(); return START_NOT_STICKY }
        TrackingService._running.value = true
        acquireLock()
        if (wantMotion && motion == null) startMotion()
        if (soundOn && sound == null) startSound()
        if (motion == null && sound == null) stopSelf()
        return START_STICKY
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun goForeground(motion: Boolean, sound: Boolean): Boolean {
        var type = 0
        if (motion) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        if (sound) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (type == 0) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        return try {
            ServiceCompat.startForeground(this, Notifications.ID_TRACKING, buildNotification(motion, sound), type)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            if (sound && motion) goForeground(true, false) else false
        }
    }

    private fun buildNotification(motion: Boolean, sound: Boolean): Notification {
        val target = if (motion) NightActivity.intent(this) else packageManager.getLaunchIntentForPackage(packageName)!!
        val pi = PendingIntent.getActivity(this, 7, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when {
            motion && sound -> R.string.trk_notification_both
            sound -> R.string.trk_notification_sound
            else -> R.string.trk_notification_motion
        }
        return NotificationCompat.Builder(this, Notifications.CH_TRACKING)
            .setSmallIcon(R.drawable.ic_stat_asleep)
            .setContentTitle(getString(R.string.trk_notification_title))
            .setContentText(getString(text))
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pi)
            .build()
    }

    private fun acquireLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepBot:tracking").apply {
            setReferenceCounted(false)
            acquire(20 * 3_600_000L)
        }
    }

    private fun startMotion() {
        val prefs = app.prefs
        val sm = getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) { Log.w(TAG, "no accelerometer"); return }
        triggerAlarm = prefs.smartAlarm
        val base = if (prefs.sleepState > 0) prefs.sleepState else System.currentTimeMillis()
        lastSave = System.currentTimeMillis()
        val tracker = MotionTracker(
            sensitivityIndex = prefs.motionSensitivity,
            use24h = prefs.is24h(),
            series = BucketSeries(base),
            onWindow = { v, init, now -> onWindow(v, init, now) },
            onLive = { TrackingService._movement.value = it },
        )
        val th = HandlerThread("SleepBotMotion").apply { start() }
        sensorThread = th
        sensorHandler = Handler(th.looper)
        if (!sm.registerListener(tracker, sensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)) {
            Log.w(TAG, "accelerometer registration failed"); th.quitSafely(); sensorThread = null; return
        }
        motion = tracker
        // Legacy workaround: some devices stop delivering sensor events after screen-off; re-register.
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                main.postDelayed({
                    val t = motion ?: return@postDelayed
                    sm.unregisterListener(t)
                    sm.registerListener(t, sensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler)
                }, 1000)
            }
        }
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Runs on the sensor thread for each ~4 s window. */
    private fun onWindow(newValue: Int, initialized: Boolean, now: Long) {
        val t = motion ?: return
        if (now - lastSave > MotionTracker.AUTO_SAVE_INTERVAL) {
            lastSave = now
            saveAccel(t)
        }
        if (initialized && triggerAlarm && !hasTriggered && newValue > t.threshold) {
            val next = app.alarms.nextAlarm.value ?: return
            val windowMs = app.prefs.smartWindowMin * 60_000L
            if (next - now < windowMs) {
                hasTriggered = true
                main.post { AlarmScheduler.smartTrigger(applicationContext) }
            }
        }
    }

    private fun startSound() {
        val tracker = SoundTracker(applicationContext, token, app.prefs.soundSensitivity) { TrackingService._amplitude.value = it }
        if (tracker.start()) sound = tracker
    }

    /** Snapshot + merge into the single AccelRecord row of this token (async, app scope). */
    private fun saveAccel(t: MotionTracker) {
        val series = t.series
        val values = series.snapshot()
        val tok = token
        app.appScope.launch(Dispatchers.IO) { writeAccel(applicationContext, tok, series.base, values) }
    }

    override fun onDestroy() {
        val t = motion
        motion = null
        sensorThread?.let { th ->
            t?.let { getSystemService(SensorManager::class.java)?.unregisterListener(it) }
            th.quitSafely()
        }
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        val s = sound
        sound = null
        val ctx = applicationContext
        val tok = token
        val snapshot = t?.let { m -> m.series.base to m.series.snapshot() }
        val lock = wakeLock
        app.appScope.launch(Dispatchers.IO) {
            try {
                s?.stop()
                snapshot?.let { (base, v) -> writeAccel(ctx, tok, base, v) }
            } finally {
                runCatching { if (lock?.isHeld == true) lock.release() }
            }
        }
        TrackingService._running.value = false
        TrackingService._amplitude.value = 0
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SensorService"
        const val ACTION_START = "com.sleepbot.app.tracking.START"
        const val EXTRA_MOTION = "motion"
        const val EXTRA_SOUND = "sound"

        private val writeLock = Mutex()

        /** Merge [values] (buckets from [base]) into the token's AccelRecord (max per bucket). */
        internal suspend fun writeAccel(context: Context, token: Long, base: Long, values: FloatArray) = writeLock.withLock {
            val dao = context.app.db.sensors()
            val interval = 30_000
            val now = System.currentTimeMillis()
            val existing = dao.accel(token)
            if (existing == null) {
                dao.insertAccel(AccelRecord(punchToken = token, startTime = base, endTime = now, intervalMs = interval, values = values))
                return@withLock
            }
            val start = minOf(existing.startTime, base)
            val offOld = ((existing.startTime - start) / existing.intervalMs).toInt()
            val offNew = ((base - start) / interval).toInt()
            val size = maxOf(offOld + existing.values.size, offNew + values.size)
            val merged = FloatArray(size)
            existing.values.forEachIndexed { i, v -> merged[offOld + i] = v }
            values.forEachIndexed { i, v -> if (v > merged[offNew + i]) merged[offNew + i] = v }
            dao.updateAccel(existing.copy(startTime = start, endTime = maxOf(now, existing.endTime), values = merged))
        }
    }
}
