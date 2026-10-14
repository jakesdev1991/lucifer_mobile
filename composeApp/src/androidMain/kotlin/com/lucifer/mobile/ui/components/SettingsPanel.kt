package com.lucifer.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AnimatedSettingsPanel(
    modifier: Modifier = Modifier,
    onSoulMdEdit: (String) -> Unit = {},
    onHeartbeatEdit: (String) -> Unit = {},
    onWalletConnect: () -> Unit = {},
    onDeployKaiBridge: () -> Unit = {},
    onVmPasswordSave: (String) -> Unit = {},
    onVmHostSave: (String) -> Unit = {},
    onNvidiaKeySave: (String) -> Unit = {},
    onVoiceToggle: (Boolean) -> Unit = {},
    onKnowledgeSync: () -> Unit = {},
    onKnowledgeUrlSave: (String) -> Unit = {},
    voiceEnabled: Boolean = true,
    knowledgeStatus: String = "…",
    corpusInfo: String = "124 files · 54 volumes",
    savedKnowledgeUrl: String = "",
    llmStatus: String = "…",
    savedVmHost: String = ""
) {
    var soulMdText by remember { mutableStateOf("") }
    var heartbeatPromptText by remember { mutableStateOf("") }
    var vmPasswordText by remember { mutableStateOf("") }
    var knowledgeUrlText by remember { mutableStateOf(savedKnowledgeUrl) }
    var vmHostText by remember { mutableStateOf(savedVmHost) }
    var nvidiaKeyText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D050D))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Soul.md Editor
        SettingsCard(title = "SOUL.MD", icon = { Icon(Icons.Filled.Info, contentDescription = "Soul", tint = Color(0xFF8B0000), modifier = Modifier.size(24.dp)) }) {
            TextField(
                value = soulMdText,
                onValueChange = { soulMdText = it; onSoulMdEdit(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                singleLine = false,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Color(0xFF0D050D),
                    focusedTextColor = Color(0xFFE0E0E0),
                    unfocusedTextColor = Color(0xFFE0E0E0),
                    cursorColor = Color(0xFFFFD700),
                    focusedIndicatorColor = Color(0xFF8B0000),
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("Edit your Soul.md identity...", color = Color(0xFF443344)) }
            )
        }

        // Heartbeat Prompt Editor
        SettingsCard(title = "HEARTBEAT PROMPT", icon = { Icon(Icons.Filled.Psychology, contentDescription = "Heartbeat", tint = Color(0xFF00AAFF), modifier = Modifier.size(24.dp)) }) {
            TextField(
                value = heartbeatPromptText,
                onValueChange = { heartbeatPromptText = it; onHeartbeatEdit(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                singleLine = false,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Color(0xFF0D050D),
                    focusedTextColor = Color(0xFFE0E0E0),
                    unfocusedTextColor = Color(0xFFE0E0E0),
                    cursorColor = Color(0xFF00CCFF),
                    focusedIndicatorColor = Color(0xFF0066AA),
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("Edit heartbeat system prompt...", color = Color(0xFF443344)) }
            )
        }

        // VM SSH Password
        SettingsCard(title = "VM SSH PASSWORD", icon = { Icon(Icons.Filled.Wifi, contentDescription = "VM SSH", tint = Color(0xFF00FF88), modifier = Modifier.size(24.dp)) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = vmPasswordText,
                    onValueChange = { vmPasswordText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                    colors = TextFieldDefaults.textFieldColors(
                        containerColor = Color(0xFF0D050D),
                        focusedTextColor = Color(0xFFE0E0E0),
                        unfocusedTextColor = Color(0xFFE0E0E0),
                        cursorColor = Color(0xFF00CCFF),
                        focusedIndicatorColor = Color(0xFF0066AA),
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    placeholder = { Text("SSH password for droid@10.13.135.192:8022", color = Color(0xFF443344)) }
                )
                Button(
                    onClick = { onVmPasswordSave(vmPasswordText) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8B0000),
                        contentColor = Color(0xFFFFFFFF)
                    )
                ) { Text("SAVE", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }

        // VM SSH Host (the AVF bridge IP changes per VM reinstall — configurable)
        SettingsCard(title = "VM SSH HOST", icon = { Icon(Icons.Filled.Wifi, contentDescription = "VM Host", tint = Color(0xFF00CCFF), modifier = Modifier.size(24.dp)) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = vmHostText,
                    onValueChange = { vmHostText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = TextFieldDefaults.textFieldColors(
                        containerColor = Color(0xFF0D050D),
                        focusedTextColor = Color(0xFFE0E0E0),
                        unfocusedTextColor = Color(0xFFE0E0E0),
                        cursorColor = Color(0xFF00CCFF),
                        focusedIndicatorColor = Color(0xFF0066AA),
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    placeholder = { Text("VM bridge IP (e.g. 172.20.10.X)", color = Color(0xFF443344)) }
                )
                Button(
                    onClick = { onVmHostSave(vmHostText) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8B0000),
                        contentColor = Color(0xFFFFFFFF)
                    )
                ) { Text("SAVE", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }

        // NVIDIA API key (his mind — rotate/override the embedded pool)
        SettingsCard(title = "NVIDIA API KEY", icon = { Icon(Icons.Filled.Psychology, contentDescription = "LLM Key", tint = Color(0xFF00FF88), modifier = Modifier.size(24.dp)) }) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = nvidiaKeyText,
                        onValueChange = { nvidiaKeyText = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = TextFieldDefaults.textFieldColors(
                            containerColor = Color(0xFF0D050D),
                            focusedTextColor = Color(0xFFE0E0E0),
                            unfocusedTextColor = Color(0xFFE0E0E0),
                            cursorColor = Color(0xFF00CCFF),
                            focusedIndicatorColor = Color(0xFF0066AA),
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        placeholder = { Text("nvapi-… (empty = embedded key pool)", color = Color(0xFF443344)) }
                    )
                    Button(
                        onClick = { onNvidiaKeySave(nvidiaKeyText) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8B0000),
                            contentColor = Color(0xFFFFFFFF)
                        )
                    ) { Text("SAVE", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
                Spacer(modifier = Modifier.height(6.dp))
                SystemStatusRow("LLM", llmStatus, if (llmStatus == "LIVE") Color(0xFF00FF88) else Color(0xFFFFAA00))
            }
        }

        // Wallet Connection
        SettingsCard(title = "WALLET & NETWORK", icon = { Icon(Icons.Filled.CloudDownload, contentDescription = "Wallet", tint = Color(0xFF00FF88), modifier = Modifier.size(24.dp)) }) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onWalletConnect,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8B0000),
                        contentColor = Color(0xFFFFFFFF)
                    )
                ) { Text("Connect Solana") }
                Button(
                    onClick = onWalletConnect,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0066AA),
                        contentColor = Color(0xFFFFFFFF)
                    )
                ) { Text("Connect ETH") }
            }
        }

        // Kai Bridge Deployment
        SettingsCard(title = "KAI BRIDGE SANDBOX", icon = { Icon(Icons.Filled.Build, contentDescription = "Kai Bridge", tint = Color(0xFFFFAA00), modifier = Modifier.size(24.dp)) }) {
            Button(
                onClick = onDeployKaiBridge,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFAA00),
                    contentColor = Color(0xFF000000)
                )
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Deploy", modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Deploy Kai Sandbox to Pixel 8a", fontWeight = FontWeight.Bold)
            }
        }

        // Knowledge Link (his ToE corpus + GitHub source of truth)
        SettingsCard(title = "KNOWLEDGE LINK", icon = { Icon(Icons.Filled.Psychology, contentDescription = "Knowledge", tint = Color(0xFF00E5FF), modifier = Modifier.size(24.dp)) }) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SystemStatusRow("ToE Server", knowledgeStatus, if (knowledgeStatus == "LINKED") Color(0xFF00FF88) else Color(0xFFFFAA00))
                SystemStatusRow("Corpus", corpusInfo, Color(0xFF00E5FF))
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onKnowledgeSync,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0066AA),
                        contentColor = Color(0xFFFFFFFF)
                    )
                ) { Text("SYNC FROM GITHUB", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = knowledgeUrlText,
                    onValueChange = { knowledgeUrlText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = TextFieldDefaults.textFieldColors(
                        containerColor = Color(0xFF0D050D),
                        focusedTextColor = Color(0xFFE0E0E0),
                        unfocusedTextColor = Color(0xFFE0E0E0),
                        cursorColor = Color(0xFF00CCFF),
                        focusedIndicatorColor = Color(0xFF0066AA),
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    placeholder = { Text("omega-knowledge-mcp URL (http://IP:8030)", color = Color(0xFF443344)) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onKnowledgeUrlSave(knowledgeUrlText) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF8B0000),
                        contentColor = Color(0xFFFFFFFF)
                    )
                ) { Text("SAVE URL", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }

        // Voice
        SettingsCard(title = "VOICE", icon = { Icon(Icons.Filled.PlayArrow, contentDescription = "Voice", tint = Color(0xFF00FF88), modifier = Modifier.size(24.dp)) }) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("He speaks results & replies", fontSize = 13.sp, color = Color(0xFFCCCCCC))
                Text(
                    if (voiceEnabled) "ON" else "OFF",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (voiceEnabled) Color(0xFF00FF88) else Color(0xFF8B7D8B),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (voiceEnabled) Color(0xFF0A3A1A) else Color(0xFF332233))
                        .clickable { onVoiceToggle(!voiceEnabled) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // System Info
        SettingsCard(title = "SYSTEM STATUS", icon = { Icon(Icons.Filled.Wifi, contentDescription = "Network", tint = Color(0xFF8B7D8B), modifier = Modifier.size(24.dp)) }) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SystemStatusRow("SSH Cluster", "Active (port 8023)", Color(0xFF00FF88))
                SystemStatusRow("Debian VM", if (savedVmHost.isBlank()) "not set — configure above" else "$savedVmHost:8022", if (savedVmHost.isBlank()) Color(0xFFFFAA00) else Color(0xFF00FF88))
                SystemStatusRow("Heartbeat", LuciferPhase(), Color(0xFF00CCFF))
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0A1A)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD700), modifier = Modifier.padding(start = 12.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SystemStatusRow(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 12.sp, color = Color(0xFFCCCCCC))
        Text(text = value, fontSize = 12.sp, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

private fun LuciferPhase(): String {
    return try {
        val phase = com.lucifer.mobile.launcher.LuciferState.phase
        val cycle = com.lucifer.mobile.launcher.LuciferState.cycle
        "$phase · cycle $cycle"
    } catch (e: Exception) {
        "DORMANT"
    }
}
