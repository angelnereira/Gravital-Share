package io.gravital.share.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.gravital.share.R
import io.gravital.share.domain.SessionManager
import io.gravital.share.domain.SessionMode
import io.gravital.share.ffi.EngineBridge
import io.gravital.share.telemetry.GravitalLog
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * VPN client service (Modo Cliente).
 * Inherits from VpnService to gain access to protect() and TUN builder.
 */
@AndroidEntryPoint
class GravitalVpnService : VpnService() {

    companion object {
        const val ACTION_START = "io.gravital.share.VPN_START"
        const val ACTION_STOP  = "io.gravital.share.VPN_STOP"
        const val EXTRA_PROXY  = "proxy_addr"
        const val EXTRA_MTU    = "mtu"
        const val EXTRA_DNS    = "dns_server"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "gravital_vpn"

        const val DEFAULT_MTU = 1280
        const val DEFAULT_DNS = "1.1.1.1"
        const val DEFAULT_VPN_IP = "10.42.0.2"
    }

    @Inject lateinit var sessionManager: SessionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunFd: Int = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn(intent)
            ACTION_STOP  -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(intent: Intent) {
        val proxyAddr = intent.getStringExtra(EXTRA_PROXY) ?: "192.168.43.1:1080"
        val mtu = intent.getIntExtra(EXTRA_MTU, DEFAULT_MTU)
        val dns = intent.getStringExtra(EXTRA_DNS) ?: DEFAULT_DNS

        startForeground(NOTIFICATION_ID, buildNotification("Conectando…"))

        GravitalLog.info(
            kind = "vpn_service.starting",
            payload = mapOf("proxy" to proxyAddr, "mtu" to mtu, "dns" to dns)
        )

        val tunParcel = Builder()
            .setSession("Gravital Share")
            .addAddress(DEFAULT_VPN_IP, 32)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(dns)
            .setMtu(mtu)
            .setBlocking(false)
            // Anti-loop: exclude our own app from the VPN
            .addDisallowedApplication(packageName)
            .establish()

        if (tunParcel == null) {
            GravitalLog.error(kind = "vpn_service.establish_failed")
            stopSelf()
            return
        }

        tunFd = tunParcel.detachFd()

        scope.launch {
            sessionManager.startClient(tunFd, proxyAddr, mtu, dns)
        }

        updateNotification("Conectado")
    }

    private fun stopVpn() {
        GravitalLog.info(kind = "vpn_service.stopping")
        scope.launch { sessionManager.stop() }
        if (tunFd >= 0) {
            // fd is now owned by the engine — do not close here
            tunFd = -1
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onRevoke() {
        GravitalLog.warn(kind = "vpn_service.revoked")
        stopVpn()
    }

    private fun buildNotification(status: String): Notification {
        createChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gravital Share")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gravital Share VPN",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
