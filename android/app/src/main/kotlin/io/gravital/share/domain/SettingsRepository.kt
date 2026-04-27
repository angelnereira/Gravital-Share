package io.gravital.share.domain

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("app_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    private val ds = ctx.dataStore

    val settings: Flow<AppSettings> = ds.data.map { p ->
        AppSettings(
            dnsServer          = p[KEY_DNS] ?: "1.1.1.1",
            mtu                = p[KEY_MTU] ?: 1280,
            mcpEndpointEnabled = p[KEY_MCP] ?: false,
        )
    }

    suspend fun save(s: AppSettings) {
        ds.edit { p ->
            p[KEY_DNS] = s.dnsServer
            p[KEY_MTU] = s.mtu
            p[KEY_MCP] = s.mcpEndpointEnabled
        }
    }

    companion object {
        private val KEY_DNS = stringPreferencesKey("dns_server")
        private val KEY_MTU = intPreferencesKey("mtu")
        private val KEY_MCP = booleanPreferencesKey("mcp_endpoint")
    }
}
