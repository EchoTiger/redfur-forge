package com.fissal.forge.pipeline

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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.fissal.forge.ForgeApplication
import com.fissal.forge.MainActivity

/**
 * Foreground service keeping multi-lane downloads, FFmpeg encodes, and RAW demosaics alive
 * while displaying live progress and a one-tap Cancel All action.
 */
class ForgeForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ALL -> {
                (application as? ForgeApplication)?.orchestrator?.cancelAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Redfur Forge Active"
                val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Running media pipeline..."
                val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0) ?: 0
                val indeterminate = intent?.getBooleanExtra(EXTRA_INDETERMINATE, true) ?: true

                val notification = buildNotification(title, text, progress, indeterminate)
                startForegroundCompat(notification)
                return START_NOT_STICKY
            }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout reached (type=$fgsType); stopping cleanly.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun startForegroundCompat(notification: Notification) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, ForgeForegroundService::class.java).apply {
            action = ACTION_CANCEL_ALL
        }
        val cancelPending = PendingIntent.getService(
            this,
            101,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPending)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel All", cancelPending)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Redfur Forge Pipeline",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live progress for batch downloads, transcodes, and RAW processing"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "ForgeForegroundService"
        private const val CHANNEL_ID = "forge_pipeline"
        private const val NOTIFICATION_ID = 4109

        const val ACTION_UPDATE = "com.fissal.forge.ACTION_UPDATE"
        const val ACTION_STOP = "com.fissal.forge.ACTION_STOP"
        const val ACTION_CANCEL_ALL = "com.fissal.forge.ACTION_CANCEL_ALL"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_INDETERMINATE = "extra_indeterminate"

        fun pushUpdate(
            context: Context,
            title: String,
            text: String,
            progress: Int,
            indeterminate: Boolean
        ) {
            val intent = Intent(context, ForgeForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_INDETERMINATE, indeterminate)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { err ->
                Log.w(TAG, "Unable to promote/update foreground service (app may be backgrounded): ${err.message}")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ForgeForegroundService::class.java))
            }
        }
    }
}
