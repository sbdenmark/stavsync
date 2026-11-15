package dk.sbdenmark.stavsync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Baggrundstjeneste, der holder appen i live, så stavene bliver styret,
 * selv når du skifter til YouTube eller slukker skærmen. Viser en fast notifikation.
 */
class SyncService : Service() {

    companion object {
        const val ACTION_KEEP = "keep"
        const val ACTION_MIC = "mic"
        const val ACTION_PHONE = "phone"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "stavsync"

        fun send(c: Context, action: String, extras: Intent.() -> Unit = {}) {
            val i = Intent(c, SyncService::class.java).setAction(action).apply(extras)
            ContextCompat.startForegroundService(c, i)
        }
    }

    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this); BleHub.init(this); Engine.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Stav-synk kører", NotificationManager.IMPORTANCE_LOW))
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stavsync:run").apply { setReferenceCounted(false); acquire() }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Stav-synk")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun foreground(mic: Boolean, phone: Boolean, text: String) {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (mic && hasMic()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (phone) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        try {
            startForeground(1, notification(text), type)
        } catch (e: Exception) {
            // fx hvis en tilladelse mangler: kør videre uden de ekstra typer
            try {
                startForeground(1, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                Engine.mStatus = "Kunne ikke starte i baggrunden: ${e.message}"
            } catch (e2: Exception) {
                Engine.mStatus = "Appen mangler tilladelse til Bluetooth og kan ikke køre i baggrunden."
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Engine.off()
                Handler(Looper.getMainLooper()).postDelayed({ BleHub.disconnectAll() }, 400)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MIC -> {
                foreground(mic = true, phone = false, text = "Lytter på mikrofonen")
                Engine.startMusic(null)
            }
            ACTION_PHONE -> {
                foreground(mic = true, phone = true, text = "Lytter på telefonens lyd")
                val code = intent.getIntExtra("code", 0)
                val data: Intent? = readData(intent)
                val mp: MediaProjection? = try {
                    data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }
                } catch (e: Exception) { Engine.mStatus = "Kunne ikke få adgang til telefonens lyd: ${e.message}"; null }
                if (mp != null) {
                    mp.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() { if (Engine.running == Run.MUSIC) Engine.stopAll() }
                    }, Handler(Looper.getMainLooper()))
                    Engine.startMusic(mp)
                }
            }
            else -> foreground(mic = false, phone = false, text = "Styrer lysstavene")
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readData(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("data", Intent::class.java)
        else i.getParcelableExtra<Intent>("data")

    override fun onDestroy() {
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
