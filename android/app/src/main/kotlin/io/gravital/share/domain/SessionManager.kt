package io.gravital.share.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gravital.share.ffi.EngineBridge
import io.gravital.share.telemetry.GravitalLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

enum class SessionMode { IDLE, CLIENT, SERVER }

enum class InternetStatus { VERIFYING, OK, UNREACHABLE }

sealed class SessionState {
    object Idle : SessionState()
    data class Preparing(val mode: SessionMode) : SessionState()
    data class Connecting(val mode: SessionMode, val attempt: Int = 1) : SessionState()
    data class Connected(val mode: SessionMode, val proxyAddr: String) : SessionState()
    data class Reconnecting(val mode: SessionMode, val attempt: Int, val lastError: String?) : SessionState()
    data class Stopping(val mode: SessionMode) : SessionState()
    data class Failed(val mode: SessionMode, val error: String, val recoverable: Boolean) : SessionState()
}

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val engineBridge: EngineBridge,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _mode = MutableStateFlow(SessionMode.IDLE)
    val mode: StateFlow<SessionMode> = _mode.asStateFlow()

    private val _throughput = MutableStateFlow(0L)
    val throughput: StateFlow<Long> = _throughput.asStateFlow()

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    private val _internetStatus = MutableStateFlow<InternetStatus?>(null)
    val internetStatus: StateFlow<InternetStatus?> = _internetStatus.asStateFlow()

    private val _engineEvents = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val engineEvents: SharedFlow<String> = _engineEvents.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Register native callback — arrives on a native thread, safe to re-emit
        engineBridge.setEventCallback { jsonEvent ->
            scope.launch { _engineEvents.emit(jsonEvent) }
        }

        // Process events
        scope.launch {
            _engineEvents.collect { handleEngineEvent(it) }
        }
    }

    fun reportInternetStatus(ok: Boolean) {
        _internetStatus.value = if (ok) InternetStatus.OK else InternetStatus.UNREACHABLE
    }

    fun startClient(
        tunFd: Int,
        proxyAddr: String,
        mtu: Int = 1280,
        dnsServer: String = "1.1.1.1",
        dnsServerSecondary: String = "8.8.8.8",
    ) {
        scope.launch {
            _mode.value = SessionMode.CLIENT
            _state.value = SessionState.Preparing(SessionMode.CLIENT)
            _internetStatus.value = InternetStatus.VERIFYING

            val config = """
                {
                  "mode": "Client",
                  "proxy_addr": "$proxyAddr",
                  "mtu": $mtu,
                  "dns_server": "$dnsServer",
                  "dns_server_secondary": "$dnsServerSecondary"
                }
            """.trimIndent()

            val result = engineBridge.startClient(tunFd, config)
            if (result != 0) {
                GravitalLog.error(kind = "session_manager.start_client_failed",
                    payload = mapOf("code" to result))
                _state.value = SessionState.Failed(SessionMode.CLIENT, "Engine error $result", false)
            } else {
                _state.value = SessionState.Connecting(SessionMode.CLIENT)
            }
        }
    }

    fun startServer(socksAddr: String = "0.0.0.0:1080", httpAddr: String = "0.0.0.0:8080") {
        scope.launch {
            _mode.value = SessionMode.SERVER
            _state.value = SessionState.Preparing(SessionMode.SERVER)

            val config = """
                {
                  "mode": "Server",
                  "socks_bind": "$socksAddr",
                  "http_bind": "$httpAddr"
                }
            """.trimIndent()

            val result = engineBridge.startServer(config)
            if (result != 0) {
                GravitalLog.error(kind = "session_manager.start_server_failed",
                    payload = mapOf("code" to result))
                _state.value = SessionState.Failed(SessionMode.SERVER, "Engine error $result", false)
            } else {
                _state.value = SessionState.Connected(SessionMode.SERVER, socksAddr)
            }
        }
    }

    fun stop() {
        scope.launch {
            val current = _state.value
            val m = when (current) {
                is SessionState.Connected -> current.mode
                is SessionState.Connecting -> current.mode
                is SessionState.Reconnecting -> current.mode
                else -> _mode.value
            }
            _state.value = SessionState.Stopping(m)
            engineBridge.stop()
            _state.value = SessionState.Idle
            _mode.value = SessionMode.IDLE
            _internetStatus.value = null
        }
    }

    fun acknowledgeError() {
        _state.value = SessionState.Idle
        _mode.value = SessionMode.IDLE
        _internetStatus.value = null
    }

    private fun handleEngineEvent(json: String) {
        // Parse "kind" field from the gs.event.v1 JSON and update state accordingly
        try {
            val kind = extractJsonField(json, "kind") ?: return
            when {
                kind == "session.transition" -> {
                    val to = extractJsonField(json, "to") ?: return
                    applyTransition(to, json)
                }
                kind == "engine.metrics.snapshot" -> {
                    val bytes = extractJsonLong(json, "bytes_out") ?: 0L
                    _throughput.value = bytes
                }
                kind == "engine.client_count" -> {
                    val count = extractJsonLong(json, "count")?.toInt() ?: 0
                    _clientCount.value = count
                }
            }
        } catch (e: Exception) {
            GravitalLog.warn(kind = "session_manager.event_parse_error",
                payload = mapOf("error" to e.message.orEmpty()))
        }
    }

    private fun applyTransition(toState: String, json: String) {
        val current = _state.value
        val m = current.mode() ?: _mode.value
        _state.value = when (toState) {
            "Connecting" -> SessionState.Connecting(m)
            "Connected"  -> SessionState.Connected(m, proxyFromJson(json))
            "Reconnecting" -> SessionState.Reconnecting(m, 1, null)
            "Stopping"   -> SessionState.Stopping(m)
            "Idle"       -> { _mode.value = SessionMode.IDLE; SessionState.Idle }
            "Failed"     -> SessionState.Failed(m, errorFromJson(json), true)
            else -> current
        }
    }

    private fun SessionState.mode(): SessionMode? = when (this) {
        is SessionState.Connecting -> mode
        is SessionState.Connected -> mode
        is SessionState.Reconnecting -> mode
        is SessionState.Stopping -> mode
        is SessionState.Failed -> mode
        is SessionState.Preparing -> mode
        else -> null
    }

    private fun proxyFromJson(json: String): String =
        extractJsonField(json, "peer") ?: "unknown"

    private fun errorFromJson(json: String): String =
        extractJsonField(json, "error") ?: "unknown"

    private fun extractJsonField(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"([^"]+)"""")
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val pattern = Regex(""""$key"\s*:\s*(\d+)""")
        return pattern.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }
}
