package com.lucifer.mobile.ui.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Dialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Row
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferTextField
import com.lucifer.mobile.ui.theme.LuciferColors
import com.lucifer.mobile.ui.theme.LuciferTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton

@Composable
fun WalletScreen(
    onNavigateBack: () -> Unit
) {
    var wallets by remember { mutableStateOf(listOf<Wallet>()) }
    var isLoading by remember { mutableStateOf(false) }
    var showGenerateDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        WalletHeader(
            onNavigateBack = onNavigateBack
        )
        
        // Wallet list
        Box(modifier = Modifier
            .fillMaxSize()
            .weight(1f)
            .padding(16.dp)
        ) {
            if (wallets.isEmpty()) {
                EmptyWalletState(
                    onGenerateClick = { showGenerateDialog = true }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(wallets) { wallet ->
                        WalletCard(
                            wallet = wallet,
                            onClick = { /* show wallet details */ },
                            onSendClick = { /* show send dialog */ },
                            onReceiveClick = { /* show receive dialog */ }
                        )
                    }
                }
            }
        }
        
        // Floating action button
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            LuciferButton.FloatingActionButton(
                onClick = { showGenerateDialog = true },
                icon = { LuciferIcon.Add() }
            )
        }
    }
    
    if (showGenerateDialog) {
        GenerateWalletDialog(
            onDismiss = { showGenerateDialog = false },
            onGenerate = { chain ->
                // Handle wallet generation
                showGenerateDialog = false
            }
        )
    }
}

@Composable
fun WalletHeader(
    onNavigateBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                LuciferIcon.ChevronLeft(tint = LuciferColors.Gold)
            }
            Text(
                text = "😈 Lucifer Wallet",
                style = androidx.compose.material3.Typography.titleMedium,
                color = LuciferColors.Red,
                fontWeight = FontWeight.Bold
            )
            LuciferIcon.Wallet(tint = LuciferColors.Gold)
        }
        
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Chain selector
            Text(text = "ARBITRUM", color = LuciferColors.OnSurface)
        }
    }
}

@Composable
fun EmptyWalletState(
    onGenerateClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            LuciferIcon.Lucifer(
                modifier = Modifier.size(96.dp),
                tint = LuciferColors.Red.copy(alpha = 0.5f)
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "No Wallets Yet",
                    style = androidx.compose.material3.Typography.headlineMedium,
                    color = LuciferColors.OnSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Create your first Lucifer wallet to\naccess Arbitrum, Ethereum, and more",
                    style = androidx.compose.material3.Typography.bodyLarge,
                    color = LuciferColors.OnSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            LuciferButton(
                onClick = onGenerateClick,
                icon = { LuciferIcon.Add() },
                text = "Generate Wallet"
            )
        }
    )
}

@Composable
fun WalletCard(
    wallet: Wallet,
    onClick: () -> Unit,
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = LuciferColors.SurfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LuciferIcon.Wallet(tint = LuciferColors.Gold, modifier = Modifier.size(24.dp))
                        Text(
                            text = "${wallet.chain.uppercase()} Wallet",
                            style = androidx.compose.material3.Typography.titleMedium,
                            color = LuciferColors.OnSurface,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = wallet.address,
                        style = androidx.compose.material3.Typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = LuciferColors.OnSurfaceVariant
                    )
                }
                
                // Balance
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${wallet.balance} ${wallet.symbol}",
                        style = androidx.compose.material3.Typography.headlineSmall,
                        color = LuciferColors.Gold,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "\$${wallet.usdValue}",
                        style = androidx.compose.material3.Typography.bodySmall,
                        color = LuciferColors.OnSurfaceVariant
                    )
                }
            }
            
            // Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LuciferButton(
                    onClick = onReceiveClick,
                    icon = { LuciferIcon.ArrowDown() },
                    text = "Receive",
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = LuciferColors.Gold,
                        border = BorderStroke(1.dp, LuciferColors.Gold)
                    )
                )
                LuciferButton(
                    onClick = onSendClick,
                    icon = { LuciferIcon.ArrowUp() },
                    text = "Send",
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LuciferColors.Red,
                        contentColor = Color.White
                    )
                )
            }
        }
    }
}

data class Wallet(
    val address: String,
    val chain: String,
    val balance: String,
    val symbol: String,
    val usdValue: String
)

@Composable
fun GenerateWalletDialog(
    onDismiss: () -> Unit,
    onGenerate: (String) -> Unit
) {
    var selectedChain by remember { mutableStateOf("arbitrum") }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.padding(32.dp),
            shape = Shapes.large
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                LuciferIcon.Lucifer(
                    modifier = Modifier.size(64.dp),
                    tint = LuciferColors.Red
                )
                Text(
                    text = "Generate New Wallet",
                    style = androidx.compose.material3.Typography.headlineMedium,
                    color = LuciferColors.OnSurface
                )
                
                // Chain selector
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "Chain:", style = androidx.compose.material3.Typography.bodyLarge, color = LuciferColors.OnSurface)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf("arbitrum", "ethereum", "optimism", "base", "polygon").forEach { chain ->
                            Text(
                                text = chain.uppercase(),
                                color = if (selectedChain == chain) LuciferColors.Red else LuciferColors.OnSurfaceVariant,
                                fontWeight = if (selectedChain == chain) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                                    .clickable { selectedChain = chain }
                            )
                        }
                    }
                }
                
                LuciferButton(
                    onClick = {
                        onGenerate(selectedChain)
                        onDismiss()
                    },
                    icon = { LuciferIcon.Add() },
                    text = "Generate"
                )
                
                LuciferButton(
                    onClick = onDismiss,
                    text = "Cancel",
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = LuciferColors.OnSurfaceVariant
                    )
                )
            }
        }
    }
}