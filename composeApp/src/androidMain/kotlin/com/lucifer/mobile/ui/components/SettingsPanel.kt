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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import com.lucifer.mobile.diagnostics.AndroidPlatformDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AnimatedSettingsPanel(
    modifier: Modifier = Modifier,
    onSoulMdEdit: (String) -> Boolean = { true },
    onAgentPanelOpen: () -> Unit = {},
    onManageWallets: () -> Unit = {},
    onHeartbeatOpen: () -> Unit = {},
    onShizukuRequest: () -> Unit = {},
    onOpenShizuku: () -> Unit = {},
    onOpenPermissionManagerX: () -> Unit = {},
    onShizukuTest: () -> Unit = {},
    onShizukuAudit: (String) -> Unit = {},
    shizukuAuditOutput: String = "",
    shizukuAuditBusy: Boolean = false,
    shizukuStatus: String = "Checking Shizuku…",
    onKaiHealthCheck: () -> Unit = {},
    onVmPasswordSave: (String) -> Unit = {},
    onVmHostSave: (String) -> Unit = {},
    onVmUserSave: (String) -> Unit = {},
    onVmPortSave: (String) -> Unit = {},
    onVmKnownHostsSave: (String) -> Unit = {},
    onNvidiaKeySave: (String) -> Unit = {},
    onVoiceToggle: (Boolean) -> Unit = {},
    onKnowledgeSync: () -> Unit = {},
    onKnowledgeUrlSave: (String) -> Unit = {},
    savedMemoryMcpUrl: String = "",
    memoryMcpStatus: String = "DISABLED",
    memoryMcpTokenConfigured: Boolean = false,
    onMemoryMcpSave: (String, String) -> Unit = { _, _ -> },
    onMemoryMcpTest: () -> Unit = {},
    onMemoryMcpRemove: () -> Unit = {},
    localMemoryEnabled: Boolean = true,
    localMemoryCount: Int = 0,
    localMemoryBytes: Long = 0L,
    onLocalMemoryToggle: (Boolean) -> Unit = {},
    onLocalMemoryClear: () -> Unit = {},
    voiceEnabled: Boolean = true,
    knowledgeStatus: String = "…",
    corpusInfo: String = "124 files · 54 volumes",
    savedKnowledgeUrl: String = "",
    llmStatus: String = "…",
    savedVmHost: String = "",
    savedVmUser: String = "droid",
    savedVmPort: Int = 8022,
    savedVmKnownHosts: String = "",
    leanVerificationState: String = "NOT RUN",
    leanVerificationOutput: String = "",
    leanVerificationCommit: String? = null,
    leanVerificationToolchain: String? = null,
    leanVerificationBusy: Boolean = false,
    onLeanVerificationPrepare: () -> Unit = {},
    onLeanVerificationStart: () -> Unit = {},
    onLeanVerificationRefresh: () -> Unit = {},
    onLeanVerificationStop: () -> Unit = {},
    statusMessage: String? = null
) {
    val context = LocalContext.current
    val diagnosticsScope = rememberCoroutineScope()
    var androidDiagnosticsText by remember { mutableStateOf("Collecting a local runtime snapshot…") }
    var androidDiagnosticsBusy by remember { mutableStateOf(true) }
    LaunchedEffect(context) {
        androidDiagnosticsBusy = true
        androidDiagnosticsText = try {
            withContext(Dispatchers.IO) { AndroidPlatformDiagnostics.snapshot(context) }
        } catch (error: Exception) {
            "[ERROR] ${error.message ?: "Runtime diagnostics unavailable."}"
        }
        androidDiagnosticsBusy = false
    }
    var shizukuPackageText by remember { mutableStateOf("com.mirfatif.permissionmanagerx") }
    var soulMdText by remember { mutableStateOf("") }
    var soulSaved by remember { mutableStateOf(0) }
    // Show the soul that is actually in force, not an empty box that would overwrite it.
    LaunchedEffect(context) {
        soulMdText = withContext(Dispatchers.IO) {
            com.lucifer.mobile.agents.android.PromptStore(context).soul().body
        }
    }
    var vmPasswordText by remember { mutableStateOf("") }
    var knowledgeUrlText by remember { mutableStateOf(savedKnowledgeUrl) }
    var memoryMcpUrlText by remember { mutableStateOf(savedMemoryMcpUrl) }
    var memoryMcpTokenText by remember { mutableStateOf("") }
    var vmHostText by remember { mutableStateOf(savedVmHost) }
    var vmUserText by remember { mutableStateOf(savedVmUser) }
    var vmPortText by remember { mutableStateOf(savedVmPort.toString()) }
    var vmKnownHostsText by remember { mutableStateOf(savedVmKnownHosts) }
    var nvidiaKeyText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D050D))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        statusMessage?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Color(0xFFFFD700), fontSize = 12.sp)
        }

        // Soul.md Editor
        SettingsCard(title = "SOUL.MD + HEARTBEAT.MD", icon = { Icon(Icons.Filled.Info, contentDescription = "Soul", tint = Color(0xFF8B0000), modifier = Modifier.size(24.dp)) }) {
            Text(
                "His identity. This overrides the copy shipped in the APK for chat, heartbeat sessions, and the cognitive router. " +
                    "Saved explicitly, not on every keystroke, so a half-typed edit cannot reach the model. heartbeat.md and each agent's " +
                    "agent-class.md are edited in the Agents tab.",
                color = Color(0xFFB8AEB8), fontSize = 11.sp
            )
            TextField(
                value = soulMdText,
                onValueChange = { soulMdText = it.take(16_000) },
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (onSoulMdEdit(soulMdText)) soulSaved = soulMdText.length },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000), contentColor = Color.White)
                ) { Text("SAVE SOUL.MD", fontSize = 11.sp) }
                Button(
                    onClick = { onAgentPanelOpen() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A1533), contentColor = Color(0xFFE2D6FF))
                ) { Text("AGENTS · HEARTBEAT", fontSize = 11.sp) }
            }
            Text(
                if (soulSaved > 0) "Saved · $soulSaved characters in files/soul.md." else "Not saved yet in this session.",
                color = if (soulSaved > 0) Color(0xFF00FF88) else Color(0xFF8B7D8B), fontSize = 9.sp
            )
        }

        SettingsCard(title = "AUTONOMOUS HEARTBEAT", icon = { Icon(Icons.Filled.Psychology, contentDescription = "Heartbeat", tint = Color(0xFFFFAA00), modifier = Modifier.size(24.dp)) }) {
            Text(
                "Opt-in foreground sessions: one idea/project plan and local Markdown draft now, then about every 30 minutes while Android allows. Camera permission enables one still per session, sent to NVIDIA for analysis; it is not saved locally. Public web research is read-only. No shell commands or external publishing.",
                color = Color(0xFFFFCC88),
                fontSize = 12.sp
            )
            Button(onClick = onHeartbeatOpen, modifier = Modifier.fillMaxWidth()) { Text("OPEN HEARTBEAT CONTROLS") }
        }

        SettingsCard(title = "SHIZUKU SYSTEM ACCESS", icon = { Icon(Icons.Filled.Build, contentDescription = "Shizuku", tint = Color(0xFF00E5FF), modifier = Modifier.size(24.dp)) }) {
            SystemStatusRow("Shizuku", shizukuStatus, if (shizukuStatus.startsWith("Connected")) Color(0xFF00FF88) else Color(0xFFFFAA00))
            Text(
                "Shizuku is a user-started Binder broker, not a blanket root grant. Lucifer checks pingBinder(), its Shizuku grant, server version, and UID (ADB shell 2000 vs root 0), then binds its custom AIDL UserService and runs the confirmed command with output/time limits. Lucifer currently uses UserService + sh -c, not ShizukuBinderWrapper direct PackageManager/AppOps calls; ADB-shell abilities vary by Android version and SELinux.",
                color = Color(0xFFB8AEB8), fontSize = 12.sp
            )
            Text(
                "Start Shizuku yourself through Wireless debugging and grant Lucifer access there. Lucifer will not grant/revoke/change Permission Manager X permissions; recognizable direct PM/AppOps permission mutations targeting PMX are blocked. Shizuku alone does not expose device traffic—network telemetry/filtering needs a separate Android VPN consent flow.",
                color = Color(0xFFFFCC88), fontSize = 12.sp
            )
            Button(onClick = onShizukuRequest, modifier = Modifier.fillMaxWidth()) { Text("REQUEST / REFRESH SHIZUKU ACCESS") }
            Button(onClick = onOpenShizuku, modifier = Modifier.fillMaxWidth()) { Text("OPEN SHIZUKU SETUP") }
            Button(onClick = onOpenPermissionManagerX, modifier = Modifier.fillMaxWidth()) { Text("OPEN PERMISSION MANAGER X") }
            Button(onClick = onShizukuTest, modifier = Modifier.fillMaxWidth()) { Text("TEST SHIZUKU IDENTITY") }
            Text(
                "Read-only package report: dumpsys package plus cmd appops get. Enter one package; results stay in this screen's memory and are not sent to Lucifer's model. AppOps state and Android runtime permission grants are separate.",
                color = Color(0xFFB8AEB8), fontSize = 12.sp
            )
            TextField(
                value = shizukuPackageText,
                onValueChange = { value ->
                    if (value.length <= 255 && value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }) {
                        shizukuPackageText = value
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
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
                placeholder = { Text("com.example.app", color = Color(0xFF443344)) }
            )
            Button(
                onClick = { onShizukuAudit(shizukuPackageText.trim()) },
                enabled = !shizukuAuditBusy && shizukuStatus.startsWith("Connected") &&
                    shizukuPackageText.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+$")),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (shizukuAuditBusy) "READ-ONLY AUDIT RUNNING…" else "INSPECT PACKAGE PERMISSIONS + APP-OPS") }
            if (shizukuAuditOutput.isNotBlank()) {
                Text(
                    shizukuAuditOutput,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                    color = Color(0xFFD8D1D8), fontFamily = FontFamily.Monospace, fontSize = 10.sp
                )
            }
        }

        SettingsCard(title = "PIXEL / ANDROID PROCESS DIAGNOSTICS", icon = { Icon(Icons.Filled.Info, contentDescription = "Android diagnostics", tint = Color(0xFF00E5FF), modifier = Modifier.size(24.dp)) }) {
            Text(
                "Local, read-only runtime snapshot for Pixel 8a (akita) or the detected Android device. Reports the actual build/API, power and process state, Usage Access status for Lucifer, and whether a system VPN is active. It does not inspect other-app usage history or capture traffic.",
                color = Color(0xFFB8AEB8), fontSize = 12.sp
            )
            Button(
                onClick = {
                    diagnosticsScope.launch {
                        androidDiagnosticsBusy = true
                        androidDiagnosticsText = try {
                            withContext(Dispatchers.IO) { AndroidPlatformDiagnostics.snapshot(context) }
                        } catch (error: Exception) {
                            "[ERROR] ${error.message ?: "Runtime diagnostics unavailable."}"
                        }
                        androidDiagnosticsBusy = false
                    }
                },
                enabled = !androidDiagnosticsBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (androidDiagnosticsBusy) "REFRESHING DEVICE STATUS…" else "REFRESH DEVICE / PROCESS STATUS")
            }
            Text(
                androidDiagnosticsText,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                color = Color(0xFFD8D1D8), fontFamily = FontFamily.Monospace, fontSize = 10.sp
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
                    placeholder = { Text("SSH password for your configured VM host", color = Color(0xFF443344)) }
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
                    placeholder = { Text("VM hostname/IP (127.0.0.1 for forwarded Linux VM)", color = Color(0xFF443344)) }
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

        SettingsCard(title = "VM SSH ACCOUNT AND PORT", icon = { Icon(Icons.Filled.Wifi, contentDescription = "VM SSH account", tint = Color(0xFF00CCFF), modifier = Modifier.size(24.dp)) }) {
            Text("For Android's built-in Linux VM, enable SSH in the Debian guest and configure the Terminal app's port forwarding first. Use 127.0.0.1 plus the forwarded host port here. Lucifer still requires a verified host key.", color = Color(0xFFB8AEB8), fontSize = 12.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = vmUserText,
                    onValueChange = { vmUserText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                    colors = TextFieldDefaults.textFieldColors(containerColor = Color(0xFF0D050D), focusedTextColor = Color(0xFFE0E0E0), unfocusedTextColor = Color(0xFFE0E0E0), cursorColor = Color(0xFF00CCFF), focusedIndicatorColor = Color(0xFF0066AA), unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                    placeholder = { Text("SSH user", color = Color(0xFF443344)) }
                )
                Button(onClick = { onVmUserSave(vmUserText) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000), contentColor = Color.White)) { Text("SAVE USER", fontSize = 11.sp) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = vmPortText,
                    onValueChange = { vmPortText = it.filter { character -> character.isDigit() }.take(5) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = TextFieldDefaults.textFieldColors(containerColor = Color(0xFF0D050D), focusedTextColor = Color(0xFFE0E0E0), unfocusedTextColor = Color(0xFFE0E0E0), cursorColor = Color(0xFF00CCFF), focusedIndicatorColor = Color(0xFF0066AA), unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                    placeholder = { Text("Forwarded SSH port", color = Color(0xFF443344)) }
                )
                Button(onClick = { onVmPortSave(vmPortText) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000), contentColor = Color.White)) { Text("SAVE PORT", fontSize = 11.sp) }
            }
        }

        SettingsCard(title = "VM SSH HOST KEY", icon = { Icon(Icons.Filled.Wifi, contentDescription = "SSH host key", tint = Color(0xFFFFAA00), modifier = Modifier.size(24.dp)) }) {
            Text(
                "Paste the matching known_hosts line. Verify its SHA-256 fingerprint on the VM console or another trusted channel first; ssh-keyscan by itself is not verification.",
                color = Color(0xFFB8AEB8),
                fontSize = 12.sp
            )
            TextField(
                value = vmKnownHostsText,
                onValueChange = { vmKnownHostsText = it },
                modifier = Modifier.fillMaxWidth().height(90.dp),
                singleLine = false,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Color(0xFF0D050D),
                    focusedTextColor = Color(0xFFE0E0E0),
                    unfocusedTextColor = Color(0xFFE0E0E0),
                    cursorColor = Color(0xFFFFD700),
                    focusedIndicatorColor = Color(0xFFFFAA00),
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("[host]:port ssh-ed25519 AAAA…", color = Color(0xFF443344)) }
            )
            Button(
                onClick = { onVmKnownHostsSave(vmKnownHostsText) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000), contentColor = Color.White)
            ) { Text("SAVE VERIFIED HOST KEY") }
        }

        SettingsCard(title = "LOCAL LEAN 4 VERIFICATION", icon = { Icon(Icons.Filled.Build, contentDescription = "Lean verification", tint = Color(0xFF00CCFF), modifier = Modifier.size(24.dp)) }) {
            Text(
                "Prepare fetches the public Omega Theory main source into the Debian VM without running its audit scripts. Review the exact SHA and pinned Lean toolchain below; starting verification then runs the Lean/source audit gates and Lake build in that VM. Preparation times out after 10 minutes; verification has a two-hour deadline and timeout is never a pass. Requires Git, tmux, coreutils timeout, Python 3, elan/Lean/Lake, and network access. After explicit confirmation, elan/Lake may download the pinned Lean toolchain/dependencies; Lucifer does not run apt or install system packages. Lean checks formal declarations; they do not validate physical reality.",
                color = Color(0xFFB8AEB8), fontSize = 12.sp
            )
            Text("Status: $leanVerificationState", color = if (leanVerificationState == "PASS") Color(0xFF00FF88) else Color(0xFFFFCC88), fontWeight = FontWeight.Bold)
            leanVerificationCommit?.let { Text("Source SHA: $it", color = Color(0xFFE0E0E0), fontSize = 12.sp) }
            leanVerificationToolchain?.let { Text("Lean pin: $it", color = Color(0xFFE0E0E0), fontSize = 12.sp) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLeanVerificationPrepare, enabled = !leanVerificationBusy) { Text("PREPARE SOURCE") }
                Button(onClick = onLeanVerificationRefresh, enabled = !leanVerificationBusy) { Text("REFRESH") }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onLeanVerificationStart,
                    enabled = !leanVerificationBusy && leanVerificationState == "PREPARED" &&
                        leanVerificationCommit != null && leanVerificationToolchain != null
                ) { Text("CONFIRM + RUN") }
                Button(onClick = onLeanVerificationStop, enabled = !leanVerificationBusy) { Text("STOP JOB") }
            }
            if (leanVerificationOutput.isNotBlank()) {
                Text(
                    leanVerificationOutput.takeLast(2_500),
                    color = Color(0xFFB8AEB8), fontSize = 10.sp, maxLines = 16
                )
            }
        }

        // NVIDIA API key — user-provided and encrypted with Android Keystore.
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
                        placeholder = { Text("Paste NVIDIA API key (empty clears it)", color = Color(0xFF443344)) }
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

        // Wallet manager (EVM-only; there is no Solana connector in this build)
        SettingsCard(title = "WALLET & NETWORK", icon = { Icon(Icons.Filled.CloudDownload, contentDescription = "Wallet", tint = Color(0xFF00FF88), modifier = Modifier.size(24.dp)) }) {
            Text(
                "Manage locally generated EVM wallets, encrypted at rest. No external wallet connection or fund transfers are enabled.",
                color = Color(0xFFB8AEB8),
                fontSize = 12.sp
            )
            Button(
                onClick = onManageWallets,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8B0000),
                    contentColor = Color(0xFFFFFFFF)
                )
            ) { Text("Manage EVM Wallets") }
        }

        // Kai Bridge is not deployable from this build; expose the real ping instead.
        SettingsCard(title = "KAI BRIDGE SANDBOX", icon = { Icon(Icons.Filled.Build, contentDescription = "Kai Bridge", tint = Color(0xFFFFAA00), modifier = Modifier.size(24.dp)) }) {
            Text(
                "Checks whether the Kai sandbox is installed and responds. This does not deploy or install it.",
                color = Color(0xFFB8AEB8),
                fontSize = 12.sp
            )
            Button(
                onClick = onKaiHealthCheck,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFAA00),
                    contentColor = Color(0xFF000000)
                )
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Check", modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Check Kai Sandbox", fontWeight = FontWeight.Bold)
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

        SettingsCard(title = "LOCAL EPISODIC MEMORY · OFFLINE", icon = { Icon(Icons.Filled.Memory, contentDescription = "Local episodic memory", tint = Color(0xFF00CCFF), modifier = Modifier.size(24.dp)) }) {
            Text(
                "Stores compact, redacted extractive memories of conversations and completed findings/actions—no raw transcript or image archive. There is no age-based expiry; explicit decisions and durable findings rank above routine exchanges. Automatic low-value compaction/offload to the selected summary-only long-term server is not yet enabled, so local entries remain until you clear them and writes may fail at the 2 GiB app-private disk cap (not reserved RAM).",
                color = Color(0xFFB8AEB8), fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            SystemStatusRow("Local memory", if (localMemoryEnabled) "ON · $localMemoryCount entries" else "PAUSED", if (localMemoryEnabled) Color(0xFF00FF88) else Color(0xFFFFAA00))
            SystemStatusRow("Disk usage", "${(localMemoryBytes / (1024 * 1024)).coerceAtLeast(0)} MiB / 2048 MiB", Color(0xFF00CCFF))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onLocalMemoryToggle(!localMemoryEnabled) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (localMemoryEnabled) Color(0xFF332233) else Color(0xFF145A32), contentColor = Color.White)
                ) { Text(if (localMemoryEnabled) "PAUSE SAVING" else "RESUME SAVING") }
                Button(
                    onClick = onLocalMemoryClear,
                    enabled = localMemoryCount > 0,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A2020), contentColor = Color.White)
                ) { Text("DELETE LOCAL MEMORY") }
            }
        }

        // Read-only local MCP retrieval from the user's private Drive memory file.
        SettingsCard(title = "DRIVE MEMORY · LOCAL MCP", icon = { Icon(Icons.Filled.Psychology, contentDescription = "Drive memory MCP", tint = Color(0xFFB99DFF), modifier = Modifier.size(24.dp)) }) {
            Text(
                "Before sending a prompt to the main model, Lucifer asks the same-device Termux MCP server for a few relevant passages from the private Drive memory. Retrieval is read-only; retrieved text is untrusted context, not policy or permission. If Termux is stopped, the original prompt is sent unchanged.",
                color = Color(0xFFB8AEB8), fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            SystemStatusRow("Memory MCP", memoryMcpStatus, when {
                memoryMcpStatus.startsWith("CONNECTED") -> Color(0xFF00FF88)
                memoryMcpStatus.startsWith("CONFIGURED") -> Color(0xFFFFAA00)
                memoryMcpStatus.startsWith("NOT CONNECTED") -> Color(0xFFFF6677)
                else -> Color(0xFF8B7D8B)
            })
            SystemStatusRow("Bearer token", if (memoryMcpTokenConfigured) "ENCRYPTED IN ANDROID KEYSTORE" else "NOT SAVED", if (memoryMcpTokenConfigured) Color(0xFF00FF88) else Color(0xFFFFAA00))
            TextField(
                value = memoryMcpUrlText,
                onValueChange = { memoryMcpUrlText = it.take(160) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Color(0xFF0D050D), focusedTextColor = Color(0xFFE0E0E0),
                    unfocusedTextColor = Color(0xFFE0E0E0), cursorColor = Color(0xFFB99DFF),
                    focusedIndicatorColor = Color(0xFF8066B3), unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("http://127.0.0.1:8031/mcp", color = Color(0xFF443344)) }
            )
            TextField(
                value = memoryMcpTokenText,
                onValueChange = { memoryMcpTokenText = it.take(256) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Color(0xFF0D050D), focusedTextColor = Color(0xFFE0E0E0),
                    unfocusedTextColor = Color(0xFFE0E0E0), cursorColor = Color(0xFFB99DFF),
                    focusedIndicatorColor = Color(0xFF8066B3), unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("Paste the Termux bearer token (leave blank to keep saved token)", color = Color(0xFF443344)) }
            )
            Text("The endpoint is restricted to localhost / 127.0.0.1 on port 8031. The token is never stored in plaintext by Lucifer.", color = Color(0xFF8B7D8B), fontSize = 10.sp)
            Button(
                onClick = onMemoryMcpTest,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF332244), contentColor = Color(0xFFE2D6FF))
            ) { Text("TEST READ-ONLY MCP CONNECTION") }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onMemoryMcpSave(memoryMcpUrlText, memoryMcpTokenText); memoryMcpTokenText = "" },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8066B3), contentColor = Color.White)
                ) { Text("SAVE LOCAL MCP") }
                Button(
                    onClick = { onMemoryMcpRemove(); memoryMcpUrlText = ""; memoryMcpTokenText = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF332233), contentColor = Color(0xFFFFAA00))
                ) { Text("REMOVE") }
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
                SystemStatusRow("SSH target", if (savedVmHost.isBlank()) "not set — configure above" else "$savedVmUser@$savedVmHost:$savedVmPort", if (savedVmHost.isBlank()) Color(0xFFFFAA00) else Color(0xFF00FF88))
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
