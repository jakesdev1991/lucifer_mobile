package com.lucifer.mobile.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lucifer.mobile.data.wallet.LuciferWallet
import com.lucifer.mobile.data.wallet.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val walletChains = listOf("ethereum", "arbitrum", "optimism", "base", "polygon")

/** Native, read-only wallet manager; key creation is always explicitly confirmed. */
@Composable
fun WalletControlPanel(
    context: Context,
    modifier: Modifier = Modifier
) {
    val walletService = remember(context) { LuciferWallet(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var wallets by remember { mutableStateOf(emptyList<Wallet>()) }
    val balances = remember { mutableStateMapOf<String, String>() }
    var selectedChain by remember { mutableStateOf("arbitrum") }
    var chainMenuExpanded by remember { mutableStateOf(false) }
    var confirmGeneration by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Wallets are stored locally; signing keys are encrypted with Android Keystore.") }

    fun refreshWallets() {
        wallets = walletService.listWallets()
    }

    LaunchedEffect(walletService) { refreshWallets() }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("EVM WALLETS", color = Color(0xFFFFD700))
        Text(
            "Generate an address or check its native-token balance. This screen cannot send funds or export private keys.",
            color = Color(0xFFB8AEB8)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { chainMenuExpanded = true }, enabled = !busy) {
                Text("Network: $selectedChain ▾", color = Color(0xFFFFD700))
            }
            DropdownMenu(expanded = chainMenuExpanded, onDismissRequest = { chainMenuExpanded = false }) {
                walletChains.forEach { chain ->
                    DropdownMenuItem(
                        text = { Text(chain) },
                        onClick = { selectedChain = chain; chainMenuExpanded = false }
                    )
                }
            }
            Button(onClick = { confirmGeneration = true }, enabled = !busy) {
                Text("Create wallet")
            }
        }
        Text(status, color = Color(0xFF8B7D8B))

        if (wallets.isEmpty()) {
            Text("No local wallets yet.", color = Color(0xFFB8AEB8))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(wallets, key = { "${it.chain}:${it.address}" }) { wallet ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF160A16))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("${wallet.chain} · ${wallet.symbol}", color = Color(0xFFFFD700))
                            Text(wallet.address, color = Color(0xFFE0E0E0))
                            Text(
                                balances["${wallet.chain}:${wallet.address}"]?.let { "Balance: $it ${wallet.symbol}" }
                                    ?: "Balance not checked",
                                color = Color(0xFFB8AEB8)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Wallet address", wallet.address))
                                    status = "Address copied to clipboard."
                                }) { Text("Copy address") }
                                TextButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    status = "Checking public RPC…"
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            walletService.getBalance(wallet.address, wallet.chain)
                                        }
                                        balances["${wallet.chain}:${wallet.address}"] = result ?: "Unavailable"
                                        status = if (result == null) "Balance lookup failed; check network access and try again." else "Balance updated."
                                        busy = false
                                    }
                                }
                            ) { Text("Refresh balance") }
                        }
                    }
                }
            }
        }
    }

    if (confirmGeneration) {
        AlertDialog(
            onDismissRequest = { confirmGeneration = false },
            title = { Text("Create a local wallet?") },
            text = {
                Text("This creates a ${selectedChain} address and stores its signing key encrypted on this device. This build has no recovery phrase or key export: if this device or its encrypted app data is lost, the wallet cannot be recovered. Do not fund it until recovery and signing have been implemented and tested.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmGeneration = false
                    busy = true
                    status = "Generating and encrypting wallet…"
                    scope.launch {
                        val created = withContext(Dispatchers.IO) { walletService.generateWallet(selectedChain) }
                        refreshWallets()
                        status = if (created == null) "Wallet creation failed; no wallet was added." else "Created ${created.address} on ${created.chain}. Keep this device secure."
                        busy = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { confirmGeneration = false }) { Text("Cancel") }
            }
        )
    }
}
