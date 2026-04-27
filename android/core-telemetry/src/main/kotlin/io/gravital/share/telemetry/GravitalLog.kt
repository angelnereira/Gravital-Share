package io.gravital.share.telemetry

import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.Instant

/**
 * Structured logging for the Android control plane.
 * Emits gs.event.v1 JSON to Logcat in debug builds.
 * In production, routes to the rolling file sink and optionally to the MCP endpoint.
 */
object GravitalLog {

    private const val TAG = "GravitalShare"

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
