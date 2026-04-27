package io.gravital.share.domain

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

data class ProxyInfo(val host: String, val port: Int) {
    override fun toString() = "$host:$port"
}

@Singleton
class NetworkDiscovery @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    suspend fun findServer(): ProxyInfo? = withContext(Dispatchers.IO) {
        val gw = getGatewayIp() ?: return@withContext null
        PROBE_PORTS.firstNotNullOfOrNull { port ->
            if (probePort(gw, port)) ProxyInfo(gw, port) else null
        }
    }

    private fun getGatewayIp(): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(network) ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.dhcpServerAddress?.hostAddress?.let { return it }
        }

        lp.routes
            .firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway?.hostAddress
            ?.let { return it }

        @Suppress("DEPRECATION")
        val dhcp = ctx.getSystemService(WifiManager::class.java)?.dhcpInfo
        if (dhcp != null && dhcp.gateway != 0) return intToIp(dhcp.gateway)
        return null
    }

    // DhcpInfo.gateway is little-endian on Android
    private fun intToIp(i: Int) =
        "${i and 0xFF}.${(i shr 8) and 0xFF}.${(i shr 16) and 0xFF}.${(i shr 24) and 0xFF}"

    private suspend fun probePort(host: String, port: Int): Boolean =
        withTimeoutOrNull(1_500L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { s -> s.connect(InetSocketAddress(host, port), 1_000); true }
                }.getOrDefault(false)
            }
        } ?: false

    fun getServerAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.filter { !it.isLoopbackAddress }
            ?.mapNotNull { it.hostAddress }
            ?: emptyList()
    }.getOrDefault(emptyList())

    companion object {
        private val PROBE_PORTS = listOf(1080, 8080)
    }
}
