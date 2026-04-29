package io.gravital.share.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.gravital.share.domain.SessionManager
import io.gravital.share.domain.SettingsRepository
import io.gravital.share.telemetry.GravitalLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@AndroidEntryPoint
class GravitalVpnService : VpnService() {

    companion object {
        const val ACTION_START = "io.gravital.share.VPN_START"
        const val ACTION_STOP  = "io.gravital.share.VPN_STOP"
        const val EXTRA_PROXY  = "proxy_addr"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "gravital_vpn"
        private const val DEFAULT_VPN_IP  = "10.42.0.2"
    }

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var settingsRepository: SettingsRepository

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
        startForeground(NOTIFICATION_ID, buildNotification("Conectando…"))
        GravitalLog.info(kind = "vpn_service.starting", payload = mapOf("proxy" to proxyAddr))

        scope.launch {
            val settings = settingsRepository.settings.first()

            val tunParcel = Builder()
                .setSession("Gravital Share")
                .addAddress(DEFAULT_VPN_IP, 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(settings.dnsServer)
                .setMtu(settings.mtu)
                .setBlocking(false)
                .addDisallowedApplication(packageName)
                .establish()

            if (tunParcel == null) {
                GravitalLog.error(kind = "vpn_service.establish_failed")
                stopSelf()
                return@launch
            }

            tunFd = tunParcel.detachFd()
            sessionManager.startClient(tunFd, proxyAddr, settings.mtu, settings.dnsServer)
            updateNotification("Activo · $proxyAddr")
        }
    }

    private fun stopVpn() {
        GravitalLog.info(kind = "vpn_service.stopping")
        scope.launch { sessionManager.stop() }
        if (tunFd >= 0) tunFd = -1
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
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }
}
