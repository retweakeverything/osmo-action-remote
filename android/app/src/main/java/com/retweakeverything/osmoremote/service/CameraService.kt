package com.retweakeverything.osmoremote.service

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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.retweakeverything.osmoremote.OsmoApp
import com.retweakeverything.osmoremote.R
import com.retweakeverything.osmoremote.core.CameraConnectionManager
import com.retweakeverything.osmoremote.core.ConnectionState
import com.retweakeverything.osmoremote.core.workflow.RunState
import com.retweakeverything.osmoremote.core.workflow.WorkflowRunner
import com.retweakeverything.osmoremote.data.Settings
import com.retweakeverything.osmoremote.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and therefore the BLE link and
 * any running workflow) alive while the screen is off. It holds no logic of
 * its own; the connection manager and runner live in the app container.
 */
class CameraService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Connected to camera"
        val notification = buildNotification(this, text)
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL_ID = "camera"
        const val NOTIFICATION_ID = 1
        const val EXTRA_TEXT = "text"

        fun buildNotification(context: Context, text: String): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }
    }
}

/** Starts, updates, and stops [CameraService] from connection and workflow state. */
class CameraServiceController(
    private val context: Context,
    private val connection: CameraConnectionManager,
    private val runner: WorkflowRunner,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private var running = false

    fun start() {
        createChannel()
        scope.launch {
            combine(connection.state, runner.state) { c, r -> describe(c, r) }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
    }

    /** null = service should not run. */
    private fun describe(c: ConnectionState, r: RunState): String? {
        val workflow = (r as? RunState.Active)?.let {
            val loop = it.loops.lastOrNull()?.let { l -> " · loop $l" }.orEmpty()
            "${it.workflow.name}: ${it.current}$loop" + if (it.paused) " (paused)" else ""
        }
        val link = when (c) {
            is ConnectionState.Ready -> "Connected to ${c.device.name}"
            is ConnectionState.Reconnecting -> "Reconnecting to ${c.device.name}…"
            is ConnectionState.Connecting, is ConnectionState.AwaitingConfirmation -> "Connecting…"
            else -> null
        }
        return when {
            workflow != null -> workflow
            link != null && settings.keepAliveInBackground -> link
            else -> null
        }
    }

    private fun apply(text: String?) {
        if (text == null) {
            if (running) context.stopService(Intent(context, CameraService::class.java))
            running = false
            return
        }
        if (running) {
            // Already in the foreground: just refresh the notification text.
            context.getSystemService(NotificationManager::class.java)
                .notify(CameraService.NOTIFICATION_ID, CameraService.buildNotification(context, text))
            return
        }
        // Android 14+ requires BLUETOOTH_CONNECT to be granted for a connectedDevice service.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(context, CameraService::class.java).putExtra(CameraService.EXTRA_TEXT, text)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onSuccess { running = true }
            .onFailure { (context.applicationContext as? OsmoApp)?.container?.logger?.log("service start failed: ${it.message}") }
    }

    private fun createChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CameraService.CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = context.getString(R.string.channel_description)
        nm.createNotificationChannel(channel)
    }
}
