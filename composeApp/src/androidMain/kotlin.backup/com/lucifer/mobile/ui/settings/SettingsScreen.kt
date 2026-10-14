package com.lucifer.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferTextField
import com.lucifer.mobile.ui.theme.LuciferColors
import com.lucifer.mobile.ui.theme.LuciferTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferTextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    var apiKeys by remember { mutableStateOf(mapOf<String, String>()) }
    var themeMode by remember { mutableStateOf("Dark") }
    var autoConnect by remember { mutableStateOf(true) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var biometricAuth by remember { mutableStateOf(false) }
    var debugMode by remember { mutableStateOf(false) }
    var sshPort by remember { mutableStateOf("8022") }
    var omnibridgePort by remember { mutableStateOf("8080") }
    
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsHeader(
            onNavigateBack = onNavigateBack
        )
        
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { SettingsSection("Appearance", LuciferIcon.Brightness()) {
                SettingsRow(
                    label = "Theme",
                    trailingContent = {
                        DropdownMenu(
                            expanded = false,
                            onDismissRequest = {}
                        ) {
                            listOf("Dark", "Light", "System").forEach { mode ->
                                DropdownMenuItem(onClick = { themeMode = mode }) {
                                    Text(text = mode, color = LuciferColors.OnSurface)
                                }
                            }
                        }
                    }
                )
            }
            
            item { SettingsSection("Security", LuciferIcon.Lock()) {
                SettingsSwitch(
                    label = "Biometric Authentication",
                    description = "Require fingerprint/face ID to unlock",
                    checked = biometricAuth,
                    onCheckedChange = { biometricAuth = it }
                )
                SettingsSwitch(
                    label = "Debug Mode",
                    description = "Enable debug logging and developer options",
                    checked = debugMode,
                    onCheckedChange = { debugMode = it }
                )
            }
            
            item { SettingsSection("Network", LuciferIcon.Cloud()) {
                SettingsSwitch(
                    label = "Auto-connect to Host",
                    description = "Automatically establish SSH tunnel on launch",
                    checked = autoConnect,
                    onCheckedChange = { autoConnect = it }
                )
                SettingsRow(
                    label = "SSH Port",
                    trailingContent = {
                        LuciferTextField(
                            value = sshPort,
                            onValueChange = { sshPort = it },
                            singleLine = true,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.width(100.dp)
                        )
                    }
                )
                SettingsRow(
                    label = "OmniBridge Port",
                    trailingContent = {
                        LuciferTextField(
                            value = omnibridgePort,
                            onValueChange = { omnibridgePort = it },
                            singleLine = true,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.width(100.dp)
                        )
                    }
                )
            }
            
            item { SettingsSection("API Keys", LuciferIcon.Key()) {
                SettingsRow(
                    label = "NVIDIA API Keys",
                    description = "Manage API keys for LLM providers",
                    trailingContent = {
                        LuciferButton(
                            onClick = { /* open key manager */ },
                            icon = { LuciferIcon.Edit() },
                            text = "Manage"
                        )
                    }
                )
                SettingsRow(
                    label = "Arbitrum RPC",
                    description = "Custom RPC endpoint for Arbitrum",
                    trailingContent = {
                        LuciferButton(
                            onClick = { /* edit RPC */ },
                            icon = { LuciferIcon.Edit() },
                            text = "Edit"
                        )
                    }
                )
            }
            
            item { SettingsSection("Advanced", LuciferIcon.Terminal()) {
                SettingsSwitch(
                    label = "Enable Kai Sandbox",
                    description = "Access Kai Linux sandbox (Python, Node.js, etc.)",
                    checked = true,
                    onCheckedChange = {}
                )
                SettingsSwitch(
                    label = "Enable Lucid Mode",
                    description = "Enable experimental features",
                    checked = false,
                    onCheckedChange = {}
                )
                SettingsRow(
                    label = "Reset App Data",
                    trailingContent = {
                        LuciferButton(
                            onClick = { /* show reset confirmation */ },
                            icon = { LuciferIcon.Warning() },
                            text = "Reset",
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = LuciferColors.Error,
                                border = androidx.compose.foundation.BorderStroke(1.dp, LuciferColors.Error)
                            )
                        )
                    }
                )
            }
            
            item { SettingsSection("About", LuciferIcon.Info()) {
                SettingsRow(
                    label = "Version",
                    trailingContent = { Text(text = "1.0.0", color = LuciferColors.OnSurfaceVariant) }
                )
                SettingsRow(
                    label = "Build",
                    trailingContent = { Text(text = "Lucifer Mobile 1.0.0", color = LuciferColors.OnSurfaceVariant) }
                )
                SettingsRow(
                    label = "License",
                    trailingContent = {
                        LuciferButton(
                            onClick = { /* show license */ },
                            text = "View"
                        )
                    }
                )
                SettingsRow(
                    label = "Privacy Policy",
                    trailingContent = {
                        LuciferButton(
                            onClick = { /* show privacy */ },
                            text = "View"
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun SettingsHeader(
    onNavigateBack: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = LuciferColors.Surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    text = "⚙️ Settings",
                    style = androidx.compose.material3.Typography.titleMedium,
                    color = LuciferColors.Red,
                    fontWeight = FontWeight.Bold
                )
                LuciferIcon.Settings(tint = LuciferColors.Gold)
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: @Composable (Modifier, Color) -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = LuciferColors.Surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    icon(Modifier.size(24.dp), LuciferColors.Gold)
                    Text(
                        text = title,
                        style = androidx.compose.material3.Typography.titleMedium,
                        color = LuciferColors.Red,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            content()
        }
    }
}

@Composable
fun SettingsRow(
    label: String,
    description: String = "",
    trailingContent: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = label,
                style = androidx.compose.material3.Typography.bodyLarge,
                color = LuciferColors.OnSurface
            )
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = androidx.compose.material3.Typography.bodySmall,
                    color = LuciferColors.OnSurfaceVariant
                )
            }
        }
        trailingContent()
    }
}

@Composable
fun SettingsSwitch(
    label: String,
    description: String = "",
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsRow(
        label = label,
        description = description
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = LuciferColors.Red,
                checkedTrackColor = LuciferColors.Red.copy(alpha = 0.5f),
                uncheckedThumbColor = LuciferColors.OnSurfaceVariant,
                uncheckedTrackColor = LuciferColors.Outline
            )
        )
    }
}