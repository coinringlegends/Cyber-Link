package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.CyberLinkApp
import com.example.MainActivity
import com.example.R
import com.example.map.LocationSharingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Location Sharing Service (V5.0 Section J).
 * - Starts ONLY upon explicit user action.
 * - Shows a persistent visible notification with active sharing status and a Stoppen button.
 * - Uses NO WAKE_LOCK.
 * - Marks background status PAUSED/OFFLINE if destroyed by Android.
 */
class LocationSharingService : Service() {

    companion object {
        const val CHANNEL_ID = "cyber_map_location_channel"
        const val NOTIFICATION_ID = 5102
        const val ACTION_STOP_SHARING = "com.example.action.STOP_LOCATION_SHARING"

        fun start(context: Context) {
            val intent = Intent(context, LocationSharingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationSharingService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? CyberLinkApp
        if (intent?.action == ACTION_STOP_SHARING) {
            serviceScope.launch {
                app?.container?.locationSharingManager?.triggerEmergencyStop()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Standortfreigabe für bestätigte Kontakte aktiv"))
        app?.container?.locationSharingManager?.setBackgroundState(
            LocationSharingManager.BackgroundSharingState.ACTIVE
        )
        return START_STICKY
    }

    override fun onDestroy() {
        val app = application as? CyberLinkApp
        app?.container?.locationSharingManager?.setBackgroundState(
            LocationSharingManager.BackgroundSharingState.PAUSED_BY_SYSTEM
        )
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, LocationSharingService::class.java).apply { action = ACTION_STOP_SHARING },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.notif_location_title))
            .setContentText(statusText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_location),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }
}
