package dev.lumensync.app.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.lumensync.app.model.SessionMode
import dev.lumensync.app.model.SyncPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SyncForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Synchronization", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a manual Lumen Sync session is running"
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession("Stopped")
            else -> startSession()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val graph = AndroidAppGraph.get(this)
        val needsCleanup = session?.isActive == true
        graph.serviceRunning = false
        session?.cancel()
        scope.cancel()
        if (needsCleanup) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                graph.engine.stop("Android ended the sync session")
            }
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSession("Android paused this long-running sync. Open Lumen Sync to resume.")
    }

    private fun startSession() {
        if (session?.isActive == true) return
        AndroidAppGraph.get(this).serviceRunning = true
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Starting Syncthing…", indeterminate = true),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        session = scope.launch {
            val graph = AndroidAppGraph.get(this@SyncForegroundService)
            graph.initialize()
            graph.engine.start(SessionMode.UNTIL_UP_TO_DATE)
            val startedAt = SystemClock.elapsedRealtime()
            var settledAt: Long? = null
            var resultMessage = "Sync stopped"
            while (isActive) {
                val status = graph.engine.status.value
                updateNotification(status.message, status.progressVisible)
                val now = SystemClock.elapsedRealtime()
                when {
                    status.phase == SyncPhase.FAILED || status.errors.isNotEmpty() -> {
                        resultMessage = status.message
                        break
                    }
                    status.connectedDevices == 0 && now - startedAt >= NO_PEER_TIMEOUT_MS -> {
                        resultMessage = "No devices reachable"
                        break
                    }
                    status.isSettled && status.totalDevices > 0 -> {
                        settledAt = settledAt ?: now
                        if (now - settledAt >= SETTLE_TIME_MS) {
                            resultMessage = status.message
                            break
                        }
                    }
                    else -> settledAt = null
                }
                delay(1_000)
            }
            graph.engine.stop(resultMessage)
            graph.serviceRunning = false
            session = null
            ServiceCompat.stopForeground(this@SyncForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopSession(message: String) {
        session?.cancel()
        session = null
        scope.launch {
            val graph = AndroidAppGraph.get(this@SyncForegroundService)
            graph.engine.stop(message)
            graph.serviceRunning = false
            updateNotification(message, false)
            ServiceCompat.stopForeground(this@SyncForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification(message: String, indeterminate: Boolean) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            notification(message, indeterminate),
        )
    }

    private fun notification(message: String, indeterminate: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SyncForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Lumen Sync")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, indeterminate)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.lumensync.action.START_SYNC"
        const val ACTION_STOP = "dev.lumensync.action.STOP_SYNC"
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1001
        private const val NO_PEER_TIMEOUT_MS = 60_000L
        private const val SETTLE_TIME_MS = 15_000L
    }
}
