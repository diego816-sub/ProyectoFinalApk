package com.diego.pasoapaso.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.LocaleList
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.diego.pasoapaso.R
import com.diego.pasoapaso.field.FieldActivity
import com.diego.pasoapaso.ui.WalkActivity
import kotlinx.coroutines.*

/** Android mantiene el seguimiento aunque la Activity deje de estar visible. */
class WalkTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var recorder: WalkRecorder
    private var foreground = false
    private var updates: Job? = null
    private var lastUpdate = 0L
    private var lastState: SessionState? = null

    override fun onCreate() {
        super.onCreate()
        recorder = WalkRecorder.get(application)
        val context = languageContext()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.walk_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            recorder.pause()
            if (!foreground) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || recorder.ui.value.detail != null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(recorder.ui.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            foreground = true
            recorder.start()
            if (updates == null) updates = scope.launch {
                recorder.ui.collect { state ->
                    if (state.detail != null || state.state == SessionState.IDLE) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val now = SystemClock.elapsedRealtime()
                        if (lastState != state.state || now - lastUpdate >= 5000L) {
                            lastState = state.state
                            lastUpdate = now
                            // Con POST_NOTIFICATIONS denegado, Android mantiene el aviso en su gestor de tareas.
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
                        }
                    }
                }
            }
        } catch (_: RuntimeException) {
            recorder.serviceFailed()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // No reiniciar a escondidas ni inventar el tramo perdido si Android mata el proceso.
        return START_NOT_STICKY
    }

    private fun notification(state: WalkUiState): Notification {
        val context = languageContext()
        val open = PendingIntent.getActivities(this, 0, arrayOf(
            Intent(this, FieldActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            Intent(this, WalkActivity::class.java)
        ), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1,
            Intent(this, WalkTrackingService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle(context.getString(if (state.state == SessionState.PAUSED) R.string.paused else R.string.recording))
            .setContentText(context.getString(R.string.walk_notification_body))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state.state == SessionState.RECORDING) builder.addAction(0, context.getString(R.string.pause), pause)
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        recorder.pause()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun languageContext(): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return this
        val configuration = Configuration(resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
        return createConfigurationContext(configuration)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.diego.pasoapaso.START_WALK"
        const val ACTION_PAUSE = "com.diego.pasoapaso.PAUSE_WALK"
        private const val CHANNEL = "walk_recording"
        private const val NOTIFICATION_ID = 1001
    }
}
