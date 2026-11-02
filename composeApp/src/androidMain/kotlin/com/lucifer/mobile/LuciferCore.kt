package com.lucifer.mobile

import android.content.Context
import com.lucifer.mobile.data.wallet.LuciferWallet
import com.lucifer.mobile.data.browser.LuciferBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/**
 * Process-wide handles to Lucifer's real capabilities.
 *
 * Only the paths that have a live consumer are kept: the egress-gated browser and the wallet.
 * The old `llm` / `sandbox` / `omniBridge` members were removed — nothing in the app called them,
 * and each one constructed a whole Kai bridge chain for no reason. LLM access goes through
 * `omni_bridge/llm/NemotronClient` directly; sandbox access goes through `kai_bridge/KaiBridge`.
 */
object LuciferCore {

    @Volatile
    private var _initialized = false

    private var _job: Job? = null

    // We need to store context for lazy initialization
    private var context: Context? = null

    val wallet: LuciferWallet by lazy { LuciferWallet(context!!) }
    val browser: LuciferBrowser by lazy { LuciferBrowser(context!!) }

    val scope: CoroutineScope by lazy {
        _job = Job()
        CoroutineScope(Dispatchers.IO + _job!!)
    }

    fun initialize(ctx: Context) {
        if (_initialized) return

        context = ctx

        wallet.initialize()
        browser.initialize()

        _initialized = true
    }

    val isInitialized: Boolean
        get() = _initialized

    fun shutdown() {
        _job?.cancel()
        _initialized = false
    }
}
