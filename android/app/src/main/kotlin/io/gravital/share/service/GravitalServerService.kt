package io.gravital.share.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.wifi.WifiManager
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import io.gravital.share.domain.SessionManager
import io.gravital.share.telemetry.GravitalLog
import kotlinx.coroutines.*
import java.net.ServerSocket
import javax.inject.Inject

/**
 * Proxy server service (Modo Servidor).
 * Starts the SOCKS5 + HTTP CONNECT proxy on the hotspot interface AND
 * the embedded file-sharing HTTP server on port 7878.
 * Both start and stop together — no user configuration required.
 */
@AndroidEntryPoint
class GravitalServerService : LifecycleService() {

    companion object {
        const val ACTION_START  = "io.gravital.share.SERVER_START"
        const val ACTION_STOP   = "io.gravital.share.SERVER_STOP"

        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "gravital_server"

        private const val PREFERRED_SOCKS_PORT = 1080
        private const val PREFERRED_HTTP_PORT  = 8080
    }

    @Inject lateinit var sessionManager: SessionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fileShareServer: FileShareServer? = null

    private val wifiManager by lazy { getSystemService(WifiManager::class.java) }
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP  -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        val socksPort = findFreePort(PREFERRED_SOCKS_PORT)
        val httpPort  = findFreePort(PREFERRED_HTTP_PORT)
        val socks = "0.0.0.0:$socksPort"
        val http  = "0.0.0.0:$httpPort"

        startForeground(NOTIFICATION_ID, buildNotification(
            "SOCKS5 :$socksPort · HTTP :$httpPort · Archivos :${FileShareServer.PORT}"
        ))

        GravitalLog.info(
            kind = "server_service.starting",
            payload = mapOf("socks" to socks, "http" to http, "files" to FileShareServer.PORT)
        )

        acquireWifiLock()

        fileShareServer = FileShareServer(applicationContext).also { it.start() }

        scope.launch { sessionManager.startServer(socks, http) }
    }

    private fun stopServer() {
        GravitalLog.info(kind = "server_service.stopping")
        fileShareServer?.stop()
        fileShareServer = null
        releaseWifiLock()
        scope.launch { sessionManager.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        fileShareServer?.stop()
        releaseWifiLock()
        scope.cancel()
    }

    // ── WiFi lock ─────────────────────────────────────────────────────────────

    private fun acquireWifiLock() {
        if (wifiLock == null) {
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GravitalShare:Server"
            )
        }
        wifiLock?.takeIf { !it.isHeld }?.acquire()
    }

    private fun releaseWifiLock() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    // ── Port selection ────────────────────────────────────────────────────────

    private fun findFreePort(preferred: Int): Int =
        try { ServerSocket(preferred).use { preferred } }
        catch (_: Exception) { ServerSocket(0).use { it.localPort } }

    // ── Notification ──────────────────────────────────────────────────────────

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
