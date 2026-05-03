package io.gravital.share.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import io.gravital.share.domain.SessionManager
import io.gravital.share.domain.SettingsRepository
import io.gravital.share.telemetry.GravitalLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.InetSocketAddress
import java.net.Socket
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

    // Keep WiFi radio alive while tunnel is up.
    private val wifiManager by lazy { getSystemService(WifiManager::class.java) }
    private var wifiLock: WifiManager.WifiLock? = null

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

        acquireWifiLock()

        scope.launch {
            val settings = settingsRepository.settings.first()

            val tunParcel = Builder()
                .setSession("Gravital Share")
                .addAddress(DEFAULT_VPN_IP, 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(settings.dnsServer)
                .addDnsServer(settings.dnsServerSecondary)
                .setMtu(settings.mtu)
                .setBlocking(false)
                .addDisallowedApplication(packageName)
                .establish()

            if (tunParcel == null) {
                GravitalLog.error(kind = "vpn_service.establish_failed")
                releaseWifiLock()
                stopSelf()
                return@launch
            }

            tunFd = tunParcel.detachFd()
            sessionManager.startClient(tunFd, proxyAddr, settings.mtu, settings.dnsServer, settings.dnsServerSecondary)
            updateNotification("Túnel activo · verificando internet…")

            // Give the engine ~2 s to wire up the SOCKS relay, then probe.
            delay(2_000)
            val ok = probeInternetViaSocks(proxyAddr)
            if (ok) {
                GravitalLog.info(kind = "vpn_service.internet_verified")
                updateNotification("Conectado · Internet ✓")
            } else {
                GravitalLog.warn(kind = "vpn_service.internet_unreachable")
                updateNotification("⚠ Sin internet — verifica el servidor")
            }
        }
    }

    /**
     * Verify the full chain Device B → SOCKS5 on Device A → 8.8.8.8:53.
     * Runs from the Gravital app process (excluded from its own VPN), so the
     * socket goes through the hotspot directly to the server.  A successful
     * SOCKS5 CONNECT response means Device A can reach the internet.
     */
    private suspend fun probeInternetViaSocks(proxyAddr: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val lastColon = proxyAddr.lastIndexOf(':')
                val host = proxyAddr.substring(0, lastColon)
                val port = proxyAddr.substring(lastColon + 1).toInt()

                withTimeoutOrNull(6_000L) {
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress(host, port), 3_000)
                        val out = sock.getOutputStream()
                        val inp = sock.getInputStream()

                        // SOCKS5 auth negotiation — no auth
                        out.write(byteArrayOf(0x05, 0x01, 0x00))
                        out.flush()
                        val auth = ByteArray(2)
                        inp.read(auth)
                        if (auth[0] != 0x05.toByte() || auth[1] != 0x00.toByte()) {
                            return@use false
                        }

                        // CONNECT 8.8.8.8:53  (Google DNS — almost always open)
                        out.write(byteArrayOf(
                            0x05, 0x01, 0x00, 0x01,   // VER CMD RSV ATYP=IPv4
                            8, 8, 8, 8,                // 8.8.8.8
                            0x00, 53                   // port 53
                        ))
                        out.flush()
                        val reply = ByteArray(10)
                        val n = inp.read(reply)
                        n >= 2 && reply[1] == 0x00.toByte()
                    }
                } ?: false
            } catch (e: Exception) {
                GravitalLog.warn(
                    kind = "vpn_service.probe_failed",
                    payload = mapOf("err" to e.message.orEmpty())
                )
                false
            }
        }

    private fun stopVpn() {
        GravitalLog.info(kind = "vpn_service.stopping")
        scope.launch { sessionManager.stop() }
        if (tunFd >= 0) tunFd = -1
        releaseWifiLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWifiLock()
        scope.cancel()
    }

    override fun onRevoke() {
        GravitalLog.warn(kind = "vpn_service.revoked")
        stopVpn()
    }

    // ── WiFi lock ─────────────────────────────────────────────────────────────

    private fun acquireWifiLock() {
        if (wifiLock == null) {
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GravitalShare:VPN"
            )
        }
        wifiLock?.takeIf { !it.isHeld }?.acquire()
    }

    private fun releaseWifiLock() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    // ── Notifications ─────────────────────────────────────────────────────────

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
