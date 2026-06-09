package io.gravital.share.telemetry

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import java.time.Instant

object GravitalLog {

    private const val TAG = "GravitalShare"
    private const val MAX_ENTRIES = 2_000

    // ── In-memory ring buffer — read by DiagnosticScreen ─────────────────────

    private val _logBuffer = MutableStateFlow<List<String>>(emptyList())
    val logBuffer: StateFlow<List<String>> = _logBuffer

    /** Push any raw JSON line (Android-side or Rust engine event) into the buffer. */
    fun addRaw(json: String) {
        _logBuffer.update { (it + json).takeLast(MAX_ENTRIES) }
    }

    fun clearBuffer() {
        _logBuffer.value = emptyList()
    }

    // ── Public logging API ────────────────────────────────────────────────────

    fun info(kind: String, traceId: String = "", payload: Map<String, Any?> = emptyMap()) =
        emit(Level.INFO, kind, traceId, payload)

    fun warn(kind: String, traceId: String = "", payload: Map<String, Any?> = emptyMap()) =
        emit(Level.WARN, kind, traceId, payload)

    fun error(kind: String, traceId: String = "", payload: Map<String, Any?> = emptyMap()) =
        emit(Level.ERROR, kind, traceId, payload)

    fun debug(kind: String, traceId: String = "", payload: Map<String, Any?> = emptyMap()) =
        emit(Level.DEBUG, kind, traceId, payload)

    private fun emit(lvl: Level, kind: String, traceId: String, payload: Map<String, Any?>) {
        val event = buildJsonObject {
            put("ts", Instant.now().toString())
            put("schema", "gs.event.v1")
            put("lvl", lvl.label)
            put("kind", kind)
            put("trace_id", traceId.ifEmpty { generateId() })
            put("module", currentCallerModule())
            put("payload", buildJsonObject {
                payload.forEach { (k, v) ->
                    when (v) {
                        is String  -> put(k, v)
                        is Int     -> put(k, v)
                        is Long    -> put(k, v)
                        is Boolean -> put(k, v)
                        is Double  -> put(k, v)
                        null       -> put(k, JsonNull)
                        else       -> put(k, v.toString())
                    }
                }
            })
        }

        val json = event.toString()
        addRaw(json)

        when (lvl) {
            Level.TRACE, Level.DEBUG -> Log.d(TAG, json)
            Level.INFO               -> Log.i(TAG, json)
            Level.WARN               -> Log.w(TAG, json)
            Level.ERROR              -> Log.e(TAG, json)
        }
    }

    private fun currentCallerModule(): String {
        return Thread.currentThread().stackTrace
            .firstOrNull { it.className.startsWith("io.gravital") && it.className != GravitalLog::class.java.name }
            ?.className?.substringAfterLast(".")
            ?: "unknown"
    }

    private fun generateId(): String =
        (System.nanoTime() xor System.currentTimeMillis()).toString(16)

    enum class Level(val label: String) {
        TRACE("trace"), DEBUG("debug"), INFO("info"), WARN("warn"), ERROR("error")
    }
}
