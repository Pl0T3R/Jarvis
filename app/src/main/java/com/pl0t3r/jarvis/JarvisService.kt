package com.pl0t3r.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * Keeps JARVIS listening in the background: a foreground service of the microphone type with a
 * permanent notification. The system restarts it if it gets killed (START_STICKY).
 */
class JarvisService : Service() {
    companion object {
        private const val TAG = "Jarvis"
        private const val CHANNEL = "jarvis"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.pl0t3r.jarvis.STOP"

        @Volatile var instance: JarvisService? = null; private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, JarvisService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, JarvisService::class.java))
        }
    }

    private var assistant: Assistant? = null
    private var worker: Thread? = null
    private val statusListener = Status.Listener { updateNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "JARVIS", NotificationManager.IMPORTANCE_LOW)
        )
        Status.addListener(statusListener)
        Status.foregroundRequest = {
            // Android withheld the microphone after a background restart: show the panel for a moment
            if (android.provider.Settings.canDrawOverlays(this)) {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_AUTOSTART, true))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Settings(this).listening = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no microphone permission")
            Status.set(Status.State.ERROR, "Brak dostępu do mikrofonu. Otwórz JARVIS i zezwól.")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: RuntimeException) {
            // started from the background without an exemption
            Log.w(TAG, "startForeground refused", e)
            Status.set(Status.State.ERROR, "System nie pozwolił włączyć nasłuchu w tle. Otwórz JARVIS.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive != true) {
            val a = Assistant(applicationContext, Settings(this))
            assistant = a
            worker = Thread({ a.run(); assistantEnded() }, "jarvis-assistant").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
        }
        return START_STICKY
    }

    /** The loop only ends on stop or on an error; the error stays visible in the notification and panel. */
    private fun assistantEnded() {
        if (Status.state == Status.State.ERROR) Log.w(TAG, "assistant stopped: ${Status.detail}")
    }

    fun speakTest(text: String) = assistant?.speakTest(text)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) assistant?.trim()
    }

    override fun onDestroy() {
        assistant?.stop()
        worker?.join(1500)
        worker = null
        assistant = null
        Status.removeListener(statusListener)
        Status.foregroundRequest = null
        Status.set(Status.State.OFF, "")
        instance = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("JARVIS: ${Status.state.label}")
            .setContentText(Status.detail.ifBlank { "Powiedz „JARVIS”." })
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Wyłącz nasłuch", stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        if (instance == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }
}
