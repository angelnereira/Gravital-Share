package io.gravital.share

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.gravital.share.ffi.EngineBridge
import io.gravital.share.telemetry.GravitalLog

@HiltAndroidApp
class GravitalShareApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Verify FFI contract before proceeding
        val ffiVersion = EngineBridge.ffiVersion()
        if (ffiVersion != EngineBridge.FFI_VERSION_EXPECTED) {
            GravitalLog.error(
                kind = "app.ffi_version_mismatch",
                payload = mapOf("expected" to EngineBridge.FFI_VERSION_EXPECTED, "got" to ffiVersion)
            )
            // UI will show error screen — do not crash here
            return
        }

        GravitalLog.info(
            kind = "app.started",
            payload = mapOf(
                "version" to BuildConfig.VERSION_NAME,
                "ffi_version" to ffiVersion
            )
        )
    }
}
