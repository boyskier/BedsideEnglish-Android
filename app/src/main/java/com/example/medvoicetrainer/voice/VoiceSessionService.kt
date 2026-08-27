package com.example.medvoicetrainer.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.medvoicetrainer.MainActivity
import com.example.medvoicetrainer.R

/**
 * Keeps a live voice session's microphone and socket running once the app leaves the
 * foreground (screen lock, switching apps). Android silently revokes background microphone
 * access — and Doze eventually suspends the process — unless a foreground service of type
 * "microphone" is running; the persistent notification this requires doubles as the "a mic is
 * live" cue that previously only existed on-screen. Owns no session state itself: VoiceManager
 * and the transcript live in MainViewModel exactly as before, so process death still ends
 * everything, same as it always did.
 */
class VoiceSessionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val caseName = intent?.getStringExtra(EXTRA_CASE_NAME).orEmpty()
        ensureChannel()
        // Explicit ServiceCompat call (rather than the bare Service.startForeground overload) so the
        // microphone type is asserted at start time too, not only via the manifest's
        // android:foregroundServiceType — this is what Play's foreground-service-type review checks
        // for and what keeps API 34+ from throwing MissingForegroundServiceTypeException.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(caseName),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Explicit removal (rather than relying on stopService() alone) guards against OEM battery
        // managers that have been seen to leave a foreground notification stuck after the service
        // process is torn down — this is the one thing standing between "session ended" and a fake
        // ongoing mic notification with nothing behind it.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MedVoiceTrainer:voiceSession")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        if (!lock.isHeld) lock.acquire(MAX_WAKE_LOCK_MILLIS)
    }

    private fun buildNotification(caseName: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("AI patient conversation active")
            .setContentText("Microphone is active while the conversation continues.")
            .setSubText(caseName.ifBlank { null })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Voice session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a practice session's microphone is live in the background."
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 4201
        private const val EXTRA_CASE_NAME = "case_name"

        // Failsafe only — MainViewModel always calls stop() when a session actually ends. This
        // just bounds how long a leaked wake lock could survive a missed stop call.
        private const val MAX_WAKE_LOCK_MILLIS = 6 * 60 * 60 * 1000L

        fun start(context: Context, caseName: String) {
            val intent = Intent(context, VoiceSessionService::class.java)
                .putExtra(EXTRA_CASE_NAME, caseName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceSessionService::class.java))
        }
    }
}
