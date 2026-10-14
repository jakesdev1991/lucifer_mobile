package com.lucifer.mobile

import android.app.Application
import android.content.Context
import androidx.multidex.MultiDex

class LuciferApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
        // Native library loading deferred - will be implemented when JNI bridge is ready
        // System.loadLibrary("lucifer_native")
    }
}