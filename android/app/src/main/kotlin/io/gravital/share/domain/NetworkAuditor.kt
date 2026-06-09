package io.gravital.share.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gravital.share.telemetry.GravitalLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Continuously audits WiFi, mobile data, hotspot, traffic stats, and proxy
 * latency — emitting structured log entries into GravitalLog every poll cycle.
 *
 * Started by GravitalVpnService / GravitalServerService during an active session.
 * DiagnosticViewModel also starts it when no session is running so the screen
 * always shows fresh data.
 */
@Singleton
class NetworkAuditor @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    data class NetworkInfo(
        val transport: String         = "UNKNOWN",  // WIFI | MOBILE | ETHERNET | NONE
        val isInternetValidated: Boolean = false,
        val downstreamKbps: Int       = 0,
        val upstreamKbps: Int         = 0,
        // WiFi
        val wifiSsid: String?         = null,
        val wifiRssiDbm: Int?         = null,
        val wifiLinkSpeedMbps: Int?   = null,
        val wifiFreqMhz: Int?         = null,
        val wifiSignalBars: Int       = 0,  // 0–4
        // Hotspot
        val isHotspotActive: Boolean  = false,
        // App cumulative traffic since boot
        val appTxBytes: Long          = 0L,
        val appRxBytes: Long          = 0L,
        // Proxy TCP connect latency (-1 = not measured / unreachable)
        val proxyLatencyMs: Long      = -1L,
        val proxyAddr: String?        = null,
    )

    private val _info = MutableStateFlow(NetworkInfo())
    val info: StateFlow<NetworkInfo> = _info

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var currentProxyAddr: String? = null

    val isPolling: Boolean get() = pollingJob?.isActive == true

    fun startPolling(proxyAddr: String? = null, intervalMs: Long = 10_000L) {
        currentProxyAddr = proxyAddr
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                snapshot()
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        currentProxyAddr = null
    }

    /** Take one network snapshot immediately (blocking; must be called from IO thread). */
    fun snapshot() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wm = ctx.getSystemService(WifiManager::class.java)

        val network = cm.activeNetwork
        val caps    = network?.let { cm.getNetworkCapabilities(it) }

        val transport = when {
            caps == null -> "NONE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "NONE"
        }
        val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val downKbps    = caps?.linkDownstreamBandwidthKbps ?: 0
        val upKbps      = caps?.linkUpstreamBandwidthKbps   ?: 0

        @Suppress("DEPRECATION")
        val wifiConn = if (transport == "WIFI") wm?.connectionInfo else null
        val ssid     = wifiConn?.ssid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" }
        val rssi     = wifiConn?.rssi?.takeIf { it != 0 && it > -120 }
        val bars     = rssi?.let { WifiManager.calculateSignalLevel(it, 5) } ?: 0

        val isHotspot = runCatching {
            val m = wm?.javaClass?.getDeclaredMethod("isWifiApEnabled")
            m?.isAccessible = true
            m?.invoke(wm) as? Boolean
        }.getOrNull() ?: false

        val uid   = Process.myUid()
        val appTx = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0 } ?: 0L
        val appRx = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0 } ?: 0L

        val latencyMs = currentProxyAddr?.let { measureLatency(it) } ?: -1L

        val info = NetworkInfo(
            transport           = transport,
            isInternetValidated = isValidated,
            downstreamKbps      = downKbps,
            upstreamKbps        = upKbps,
            wifiSsid            = ssid,
            wifiRssiDbm         = rssi,
            wifiLinkSpeedMbps   = wifiConn?.linkSpeed,
            wifiFreqMhz         = wifiConn?.frequency,
            wifiSignalBars      = bars,
            isHotspotActive     = isHotspot,
            appTxBytes          = appTx,
            appRxBytes          = appRx,
            proxyLatencyMs      = latencyMs,
            proxyAddr           = currentProxyAddr,
        )
        _info.value = info

        GravitalLog.info(
            kind = "network.audit.snapshot",
            payload = buildMap {
                put("transport",       transport)
                put("internet_ok",     isValidated)
                if (ssid != null)                  put("wifi_ssid",       ssid)
                if (rssi != null)                  put("wifi_rssi_dbm",   rssi)
                if (wifiConn?.linkSpeed != null)   put("wifi_speed_mbps", wifiConn.linkSpeed)
                if (wifiConn?.frequency != null)   put("wifi_freq_mhz",   wifiConn.frequency)
                if (isHotspot)                     put("hotspot_active",  true)
                if (downKbps > 0)                  put("est_down_kbps",   downKbps)
                if (upKbps   > 0)                  put("est_up_kbps",     upKbps)
                put("app_tx_kb", appTx / 1024)
                put("app_rx_kb", appRx / 1024)
                if (latencyMs >= 0)                put("proxy_latency_ms", latencyMs)
            }
        )
    }

    private fun measureLatency(proxyAddr: String): Long = try {
        val lastColon = proxyAddr.lastIndexOf(':')
        val host = proxyAddr.substring(0, lastColon)
        val port = proxyAddr.substring(lastColon + 1).toInt()
        val t0 = System.currentTimeMillis()
        Socket().use { s -> s.connect(InetSocketAddress(host, port), 2_000) }
        System.currentTimeMillis() - t0
    } catch (_: Exception) { -1L }
}
