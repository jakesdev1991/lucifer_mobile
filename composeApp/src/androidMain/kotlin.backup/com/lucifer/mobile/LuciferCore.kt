package com.lucifer.mobile

import android.content.Context
import com.lucifer.mobile.data.wallet.LuciferWallet
import com.lucifer.mobile.data.browser.LuciferBrowser
import com.lucifer.mobile.data.llm.LuciferLLM
import com.lucifer.mobile.data.sandbox.LuciferSandbox
import com.lucifer.mobile.omni_bridge.OmniBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

object LuciferCore {

    @Volatile
    private var _initialized = false

    val wallet: LuciferWallet by lazy { LuciferWallet() }
    val browser: LuciferBrowser by lazy { LuciferBrowser() }
    val llm: LuciferLLM by lazy { LuciferLLM() }
    val sandbox: LuciferSandbox by lazy { LuciferSandbox() }
    val omniBridge: OmniBridge by lazy { OmniBridge() }

    val scope: CoroutineScope by lazy { CoroutineScope(kotlinx.coroutines.Dispatchers.IO) }

    fun initialize(context: Context) {
        if (_initialized) return
        
        // Initialize core systems
        wallet.initialize(context)
        browser.initialize(context)
        llm.initialize(context)
        sandbox.initialize(context)
        omniBridge.initialize()
        
        _initialized = true
    }

    val isInitialized: Boolean
        get() = _initialized

    fun shutdown() {
        scope.cancel()
        _initialized = false
    }
}