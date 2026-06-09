package io.gravital.share.domain

data class AppSettings(
    val dnsServer: String           = "1.1.1.1",
    val dnsServerSecondary: String  = "8.8.8.8",
    val mtu: Int                    = 1280,
    val mcpEndpointEnabled: Boolean = false,
)
