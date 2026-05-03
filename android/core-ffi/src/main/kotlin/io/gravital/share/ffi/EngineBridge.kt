package io.gravital.share.ffi

/**
 * Bridge to libgravital_engine.so — JNI bindings.
 * All calls must be dispatched from Dispatchers.IO.
 * The callback can arrive on any native thread; emit to a SharedFlow.
 */
object EngineBridge {

    init {
        System.loadLibrary("gravital_engine")
    }

    // Lifecycle

    external fun init(configJson: String): Int
    external fun startClient(tunFd: Int, configJson: String): Int
    external fun startServer(configJson: String): Int
    external fun stop(): Int
    external fun shutdown(): Int

    // Information

    external fun getState(): String
    external fun getStats(): String

    // Telemetry callback
    // Kotlin stores the instance; nativeSetEventCallback passes a GlobalRef to
    // the native engine so Tokio threads can call onEvent() directly.

    @Volatile private var _eventCallback: EventCallback? = null

    fun setEventCallback(callback: EventCallback) {
        _eventCallback = callback
        nativeSetEventCallback(callback)
    }

    private external fun nativeSetEventCallback(callback: EventCallback)

    fun interface EventCallback {
        fun onEvent(jsonEvent: String)
    }

    // Contract version

    const val FFI_VERSION_EXPECTED = 1
    external fun ffiVersion(): Int
}
