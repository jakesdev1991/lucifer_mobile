package com.lucifer.mobile

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import com.lucifer.mobile.privacy.NetworkGuard
import com.lucifer.mobile.privacy.TorRuntime

class LuciferApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
        // Load the JNI bridge if the native build produced a .so for this ABI.
        // A missing or incompatible .so must never crash app startup.
        nativeLibraryLoaded = try {
            System.loadLibrary("lucifer_native")
            true
        } catch (t: Throwable) {
            android.util.Log.w("LuciferApplication", "lucifer_native not loaded: ${t.message}")
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (nativeLibraryLoaded) {
            try {
                nativeInit(this)
            } catch (t: Throwable) {
                android.util.Log.w("LuciferApplication", "nativeInit failed: ${t.message}")
            }
        }
        // Every outbound request asks this guard first. Installing it here is what makes
        // Settings → Privacy's NETWORK OFF switch mean something; see privacy/NetworkGuard.kt.
        NetworkGuard.install(this)
        // Device-held HMAC key for signed lesson-export bundles (schema 2). Idempotent.
        com.lucifer.mobile.security.LessonSigner.install()
        // The transport (direct or Tor) rides alongside the switch: one decides *whether* a request
        // may leave, the other *how*. Both are installed here so neither depends on a screen having
        // been opened first. See privacy/TorRuntime.kt.
        TorRuntime.install(this)
    }

    companion object {
        /** True when `liblucifer_native.so` loaded successfully in [attachBaseContext]. */
        @Volatile
        var nativeLibraryLoaded: Boolean = false
            private set

        // JNI bridge exported by native/src/lucifer_native.cpp (liblucifer_native.so).
        // Declared here so the library can be absent without crashing startup.
        private external fun nativeInit(context: Context)
        external fun nativeShutdown()
    }
}