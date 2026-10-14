package com.lucifer.mobile

import android.app.Application
import android.content.Context
import com.lucifer.mobile.LuciferCore

class LuciferApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Initialize native library
        System.loadLibrary("lucifer_native")
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize Lucifer core systems
        LuciferCore.initialize(this)
    }

    companion object {
        @JvmStatic
        lateinit var instance: LuciferApplication
            private set
    }
}