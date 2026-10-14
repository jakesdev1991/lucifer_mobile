package com.lucifer.mobile.data.wallet

import android.content.Context
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiBridge
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiWalletBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

class LuciferWallet {

    private val kaiBridge: KaiWalletBridge
    private val scope = CoroutineScope(Dispatchers.IO)

    constructor(context: Context) {
        val bridge = KaiBridge(context)
        this.kaiBridge = KaiWalletBridge(bridge)
    }

    private val _wallets = mutableStateOf<List<Wallet>>(emptyList())
    val wallets: List<Wallet> get() = _wallets.value

    private val _selectedWallet = mutableStateOf<Wallet?>(null)
    val selectedWallet: Wallet? get() = _selectedWallet.value

    fun initialize() {
        scope.launch {
            kaiBridge.initialize()
            loadWallets()
        }
    }

    suspend fun generateWallet(chain: String = "arbitrum"): Wallet? {
        return kaiBridge.generateWallet(chain)?.let { keypair ->
            val wallet = Wallet(
                address = keypair.address,
                chain = chain,
                balance = "0",
                symbol = when (chain) {
                    "arbitrum" -> "ETH"
                    "ethereum" -> "ETH"
                    "optimism" -> "ETH"
                    "base" -> "ETH"
                    "polygon" -> "MATIC"
                    else -> "ETH"
                },
                usdValue = "0.00"
            )
            _wallets.value = _wallets.value + wallet
            _selectedWallet.value = wallet
            saveWallet(wallet)
            wallet
        }
    }

    suspend fun loadWallet(address: String): Wallet? {
        return kaiBridge.loadWallet(address)?.let { keypair ->
            Wallet(
                address = keypair.address,
                chain = keypair.chain,
                balance = "0",
                symbol = when (keypair.chain) {
                    "arbitrum" -> "ETH"
                    "ethereum" -> "ETH"
                    "optimism" -> "ETH"
                    "base" -> "ETH"
                    "polygon" -> "MATIC"
                    else -> "ETH"
                },
                usdValue = "0.00"
            )
        }
    }

    suspend fun signTransaction(txParams: Map<String, String>): String? {
        return kaiBridge.signTransaction(txParams)
    }

    suspend fun getBalance(address: String, token: String = "ETH"): String? {
        return kaiBridge.getBalance(address, token)
    }

    private fun loadWallets() {
        // Load from secure storage
    }

    private fun saveWallet(wallet: Wallet) {
        // Save to secure storage
    }

    fun shutdown() {
        kaiBridge.shutdown()
    }
}

data class Wallet(
    val address: String,
    val chain: String,
    val balance: String,
    val symbol: String,
    val usdValue: String
)