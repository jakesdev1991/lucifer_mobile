package com.lucifer.mobile

import android.content.Context
import com.lucifer.mobile.data.wallet.LuciferWallet
import com.lucifer.mobile.data.browser.LuciferBrowser
import com.lucifer.mobile.data.llm.LuciferLLM
import com.lucifer.mobile.data.sandbox.LuciferSandbox
import com.lucifer.mobile.omni_bridge.OmniBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

object LuciferCore {

    @Volatile
    private var _initialized = false

    private var _job: Job? = null

    // We need to store context for lazy initialization
    private var context: Context? = null

    val wallet: LuciferWallet by lazy { LuciferWallet(context!!) }
    val browser: LuciferBrowser by lazy { LuciferBrowser(context!!) }
    val llm: LuciferLLM by lazy { LuciferLLM(context!!) }
    val sandbox: LuciferSandbox by lazy { LuciferSandbox(context!!) }
    val omniBridge: OmniBridge by lazy { OmniBridge(context!!) }

    val scope: CoroutineScope by lazy {
        _job = Job()
        CoroutineScope(Dispatchers.IO + _job!!)
    }

    fun initialize(ctx: Context) {
        if (_initialized) return

        context = ctx

        // Initialize core systems
        wallet.initialize()
        browser.initialize()
        llm.initialize()
        sandbox.initialize()
        omniBridge.initialize()

        _initialized = true
    }

    val isInitialized: Boolean
        get() = _initialized

    fun shutdown() {
        _job?.cancel()
        _initialized = false
    }
}