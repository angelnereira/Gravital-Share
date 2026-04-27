package io.gravital.share.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import io.gravital.share.domain.SessionManager
import io.gravital.share.telemetry.GravitalLog
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Proxy server service (Modo Servidor).
 * Starts the SOCKS5 + HTTP CONNECT proxy on the hotspot interface.
 */
@AndroidEntryPoint
class GravitalServerService : LifecycleService() {

    companion object {
        const val ACTION_START  = "io.gravital.share.SERVER_START"
        const val ACTION_STOP   = "io.gravital.share.SERVER_STOP"
        const val EXTRA_SOCKS   = "socks_addr"
        const val EXTRA_HTTP    = "http_addr"

        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "gravital_server"

        const val DEFAULT_SOCKS = "0.0.0.0:1080"
        const val DEFAULT_HTTP  = "0.0.0.0:8080"
    }

    @Inject lateinit var sessionManager: SessionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startServer(intent)
            ACTION_STOP  -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer(intent: Intent) {
        val socks = intent.getStringExtra(EXTRA_SOCKS) ?: DEFAULT_SOCKS
        val http  = intent.getStringExtra(EXTRA_HTTP)  ?: DEFAULT_HTTP

        startForeground(NOTIFICATION_ID, buildNotification(
            "Compartiendo — SOCKS5 :1080 · HTTP :8080"
        ))

        GravitalLog.info(
            kind = "server_service.starting",
            payload = mapOf("socks" to socks, "http" to http)
        )

        scope.launch { sessionManager.startServer(socks, http) }
    }

    private fun stopServer() {
        GravitalLog.info(kind = "server_service.stopping")
        scope.launch { sessionManager.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun buildNotification(text: String): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "Gravital Share Server", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)

        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Gravital Share — Servidor activo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
