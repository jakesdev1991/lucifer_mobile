package com.lucifer.mobile

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex
import com.lucifer.mobile.privacy.NetworkGuard

class LuciferApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
        // Native library loading deferred - will be implemented when JNI bridge is ready
        // System.loadLibrary("lucifer_native")
    }

    override fun onCreate() {
        super.onCreate()
        // Every outbound request asks this guard first. Installing it here is what makes
        // Settings → Privacy's NETWORK OFF switch mean something; see privacy/NetworkGuard.kt.
        NetworkGuard.install(this)
    }
}