package com.lucifer.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.diagnostics.AndroidPlatformDiagnostics
import com.lucifer.mobile.privacy.NetworkGuard
import com.lucifer.mobile.privacy.PlayClassifier
import com.lucifer.mobile.privacy.PlayEnvironmentProbe
import com.lucifer.mobile.privacy.TorRuntime
import com.lucifer.mobile.net.EgressTransport
import com.lucifer.mobile.skills.SkillSynthRuntime
import com.lucifer.mobile.ui.effects.SigilRule
import com.lucifer.mobile.ui.effects.glassSurface
import com.lucifer.mobile.ui.effects.shard
import com.lucifer.mobile.ui.theme.Morningstar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    onWorldPanelOpen: () -> Unit = {},
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
    onExportBundle: () -> Unit = {},
    biometricStatus: String = "…",
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
    // Privacy: the switch and the egress ledger are read from NetworkGuard, not passed in, so the
    // panel shows the real file-backed state instead of a copy that could drift.
    var networkOnline by remember { mutableStateOf(true) }
    var privacyLedger by remember { mutableStateOf("Reading the egress ledger…") }
    var guardInstalled by remember { mutableStateOf(false) }
    var playReport by remember { mutableStateOf("Reading the Play stack…") }
    suspend fun refreshPrivacy() {
        networkOnline = withContext(Dispatchers.IO) { NetworkGuard.isOnline(context) }
        privacyLedger = withContext(Dispatchers.IO) { NetworkGuard.recent(context, 20) }
        guardInstalled = NetworkGuard.installed
        // Which Play stack is under him changes push, location and integrity — not Lucifer's own
        // behaviour, since this APK ships zero Google dependencies.
        playReport = withContext(Dispatchers.IO) {
            PlayClassifier.report(PlayEnvironmentProbe(context).read())
        }
    }
    LaunchedEffect(context) { refreshPrivacy() }
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
            .background(Morningstar.Void)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        statusMessage?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Morningstar.Gilded, fontSize = 12.sp, fontFamily = Morningstar.Body)
        }

        // ── PRIVACY · EGRESS — the one switch that makes the app silent ──
        SettingsCard(title = "PRIVACY · EGRESS", icon = {
            Icon(Icons.Filled.Lock, contentDescription = "Privacy", tint = Morningstar.Crimson, modifier = Modifier.size(24.dp))
        }) {
            Text(
                "Every outbound request asks this switch first and is written to a local ledger: the host and the " +
                    "path, never the query string. Device-local traffic (Termux, the VM bridge, the memory MCP) keeps " +
                    "working while the network is off. This hides nothing from your carrier or your network — for that " +
                    "you need a VPN, and Lucifer should not be your anonymity layer.",
                color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body
            )
            Text(
                if (guardInstalled) "GUARD INSTALLED · ${if (networkOnline) "NETWORK ON" else "NETWORK OFF"}"
                else "GUARD NOT INSTALLED — egress is unguarded until LuciferApplication.onCreate runs",
                color = if (guardInstalled && !networkOnline) Morningstar.Crimson else Morningstar.Ash,
                fontSize = 10.sp, fontFamily = Morningstar.MonoTail
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val next = !networkOnline
                        NetworkGuard.setOnline(context, next)
                        networkOnline = next
                        diagnosticsScope.launch { refreshPrivacy() }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (networkOnline) Morningstar.GlassRaised else Morningstar.Blood,
                        contentColor = Morningstar.Bone
                    )
                ) {
                    Text(
                        if (networkOnline) "GO DARK · NETWORK OFF" else "BACK ONLINE",
                        fontSize = 10.sp, fontFamily = Morningstar.Body
                    )
                }
                Button(
                    onClick = {
                        NetworkGuard.clear(context)
                        diagnosticsScope.launch { refreshPrivacy() }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Bone)
                ) { Text("CLEAR LEDGER", fontSize = 10.sp, fontFamily = Morningstar.Body) }
            }
            // ── TRANSPORT · Tor for Lucifer's own traffic ──
            SigilRule()
            var transportLabel by remember { mutableStateOf("DIRECT") }
            var torStatus by remember { mutableStateOf("…") }
            var circuitBusy by remember { mutableStateOf(false) }
            var transportRevision by remember { mutableStateOf(0) }
            LaunchedEffect(transportRevision) {
                withContext(Dispatchers.IO) {
                    transportLabel = TorRuntime.transport().label
                    torStatus = TorRuntime.status(context)
                }
            }
            Text(
                "TRANSPORT · where Lucifer's own requests go. VIA TOR sends every remote request through a " +
                    "SOCKS5 proxy (Orbot's 127.0.0.1:9050 by default) with the hostname handed to the proxy, " +
                    "so this phone never resolves a name it meant to keep private. If the proxy stops answering, " +
                    "requests are refused — never quietly sent directly.",
                color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body
            )
            Text(torStatus, color = Morningstar.Gilded, fontSize = 10.sp, fontFamily = Morningstar.MonoTail)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        diagnosticsScope.launch {
                            val next = if (transportLabel == "TOR") EgressTransport.DIRECT else EgressTransport.TOR
                            withContext(Dispatchers.IO) { TorRuntime.setTransport(context, next) }
                            withContext(Dispatchers.IO) { torStatus = TorRuntime.status(context) }
                            transportLabel = TorRuntime.transport().label
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (transportLabel == "TOR") Morningstar.SuccessDim else Morningstar.GlassRaised,
                        contentColor = Color.White
                    )
                ) { Text(if (transportLabel == "TOR") "USE DIRECT" else "VIA TOR", fontSize = 10.sp, fontFamily = Morningstar.Body) }
                Button(
                    onClick = {
                        circuitBusy = true
                        diagnosticsScope.launch {
                            val line = TorRuntime.testCircuit(context)
                            torStatus = line
                            circuitBusy = false
                            transportRevision++
                        }
                    },
                    enabled = !circuitBusy && transportLabel == "TOR",
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Bone)
                ) { Text(if (circuitBusy) "CHECKING…" else "TEST CIRCUIT", fontSize = 10.sp, fontFamily = Morningstar.Body) }
            }
            Text(
                TorRuntime.SCOPE_NOTE,
                color = Morningstar.Warning, fontSize = 10.sp, fontFamily = Morningstar.Body
            )

            SigilRule()
            Text(
                playReport,
                color = Morningstar.Ash,
                fontSize = 10.sp,
                fontFamily = Morningstar.MonoTail,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                privacyLedger,
                color = Morningstar.Ash,
                fontSize = 10.sp,
                fontFamily = Morningstar.MonoTail,
                maxLines = 16,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Soul.md Editor
        SettingsCard(title = "SOUL.MD + HEARTBEAT.MD", icon = { Icon(Icons.Filled.Info, contentDescription = "Soul", tint = Morningstar.Blood, modifier = Modifier.size(24.dp)) }) {
            Text(
                "His identity. This overrides the copy shipped in the APK for chat, heartbeat sessions, and the cognitive router. " +
                    "Saved explicitly, not on every keystroke, so a half-typed edit cannot reach the model. heartbeat.md and each agent's " +
                    "agent-class.md are edited in the Agents tab.",
                color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body
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
                    containerColor = Morningstar.Void,
                    focusedTextColor = Morningstar.Bone,
                    unfocusedTextColor = Morningstar.Bone,
                    cursorColor = Morningstar.Gilded,
                    focusedIndicatorColor = Morningstar.Blood,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("Edit your Soul.md identity...", color = Morningstar.Whisper) }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (onSoulMdEdit(soulMdText)) soulSaved = soulMdText.length },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Morningstar.Blood, contentColor = Color.White)
                ) { Text("SAVE SOUL.MD", fontSize = 11.sp, fontFamily = Morningstar.Body) }
                Button(
                    onClick = { onAgentPanelOpen() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Bone)
                ) { Text("AGENTS · HEARTBEAT", fontSize = 11.sp, fontFamily = Morningstar.Body) }
            }
            Text(
                if (soulSaved > 0) "Saved · $soulSaved characters in files/soul.md." else "Not saved yet in this session.",
                color = if (soulSaved > 0) Morningstar.Success else Morningstar.Ash, fontSize = 9.sp, fontFamily = Morningstar.Body
            )
        }

        SettingsCard(title = "AUTONOMOUS HEARTBEAT", icon = { Icon(Icons.Filled.Psychology, contentDescription = "Heartbeat", tint = Morningstar.Warning, modifier = Modifier.size(24.dp)) }) {
            Text(
                "Opt-in foreground sessions: one idea/project plan and local Markdown draft now, then about every 30 minutes while Android allows. Camera permission enables one still per session, sent to NVIDIA for analysis; it is not saved locally. Public web research is read-only. No shell commands or external publishing.",
                color = Morningstar.Warning,
                fontSize = 12.sp, fontFamily = Morningstar.Body
            )
            Button(onClick = onHeartbeatOpen, modifier = Modifier.fillMaxWidth()) { Text("OPEN HEARTBEAT CONTROLS") }
        }

        SettingsCard(title = "SHIZUKU SYSTEM ACCESS", icon = { Icon(Icons.Filled.Build, contentDescription = "Shizuku", tint = Morningstar.Info, modifier = Modifier.size(24.dp)) }) {
            SystemStatusRow("Shizuku", shizukuStatus, if (shizukuStatus.startsWith("Connected")) Morningstar.Success else Morningstar.Warning)
            Text(
                "Shizuku is a user-started Binder broker, not a blanket root grant. Lucifer checks pingBinder(), its Shizuku grant, server version, and UID (ADB shell 2000 vs root 0), then binds its custom AIDL UserService and runs the confirmed command with output/time limits. Lucifer currently uses UserService + sh -c, not ShizukuBinderWrapper direct PackageManager/AppOps calls; ADB-shell abilities vary by Android version and SELinux.",
                color = Morningstar.Ash, fontSize = 12.sp, fontFamily = Morningstar.Body
            )
            Text(
                "Start Shizuku yourself through Wireless debugging and grant Lucifer access there. Lucifer will not grant/revoke/change Permission Manager X permissions; recognizable direct PM/AppOps permission mutations targeting PMX are blocked. Shizuku alone does not expose device traffic—network telemetry/filtering needs a separate Android VPN consent flow.",
                color = Morningstar.Warning, fontSize = 12.sp, fontFamily = Morningstar.Body
            )
            Button(onClick = onShizukuRequest, modifier = Modifier.fillMaxWidth()) { Text("REQUEST / REFRESH SHIZUKU ACCESS") }
            Button(onClick = onOpenShizuku, modifier = Modifier.fillMaxWidth()) { Text("OPEN SHIZUKU SETUP") }
            Button(onClick = onOpenPermissionManagerX, modifier = Modifier.fillMaxWidth()) { Text("OPEN PERMISSION MANAGER X") }
            Button(onClick = onWorldPanelOpen, modifier = Modifier.fillMaxWidth()) { Text("OPEN PRIVILEGE & WORLD") }
            Text(
                "The World tab is where Shizuku (shell) and Dhizuku (device owner) turn into actions: create and wake a " +
                    "managed profile, launch apps inside it, start the VM there, and make owner calls such as hide, suspend " +
                    "or grant. Each one shows its exact command and waits for you. Lucifer's own tooling, and Permission " +
                    "Manager X, stay off limits to those calls.",
                color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body
            )
            Button(onClick = onShizukuTest, modifier = Modifier.fillMaxWidth()) { Text("TEST SHIZUKU IDENTITY") }
            Text(
                "Read-only package report: dumpsys package plus cmd appops get. Enter one package; results stay in this screen's memory and are not sent to Lucifer's model. AppOps state and Android runtime permission grants are separate.",
                color = Morningstar.Ash, fontSize = 12.sp, fontFamily = Morningstar.Body
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
                    containerColor = Morningstar.Void,
                    focusedTextColor = Morningstar.Bone,
                    unfocusedTextColor = Morningstar.Bone,
                    cursorColor = Morningstar.Info,
                    focusedIndicatorColor = Morningstar.Crimson,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("com.example.app", color = Morningstar.Whisper) }
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
                    color = Morningstar.Bone, fontFamily = Morningstar.MonoTail, fontSize = 10.sp
                )
            }
        }

        SettingsCard(title = "PIXEL / ANDROID PROCESS DIAGNOSTICS", icon = { Icon(Icons.Filled.Info, contentDescription = "Android diagnostics", tint = Morningstar.Info, modifier = Modifier.size(24.dp)) }) {
            Text(
                "Local, read-only runtime snapshot for Pixel 8a (akita) or the detected Android device. Reports the actual build/API, power and process state, Usage Access status for Lucifer, and whether a system VPN is active. It does not inspect other-app usage history or capture traffic.",
                color = Morningstar.Ash, fontSize = 12.sp, fontFamily = Morningstar.Body
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
                color = Morningstar.Bone, fontFamily = Morningstar.MonoTail, fontSize = 10.sp
            )
        }

        // VM SSH Password
        SettingsCard(title = "VM SSH PASSWORD", icon = { Icon(Icons.Filled.Wifi, contentDescription = "VM SSH", tint = Morningstar.Success, modifier = Modifier.size(24.dp)) }) {
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
                        containerColor = Morningstar.Void,
                        focusedTextColor = Morningstar.Bone,
                        unfocusedTextColor = Morningstar.Bone,
                        cursorColor = Morningstar.Info,
                        focusedIndicatorColor = Morningstar.Crimson,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    placeholder = { Text("SSH password for your configured VM host", color = Morningstar.Whisper) }
                )
                Button(
                    onClick = { onVmPasswordSave(vmPasswordText) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Morningstar.Blood,
                        contentColor = Morningstar.Bone
                    )
                ) { Text("SAVE", fontSize = 12.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold) }
            }
        }

        // VM SSH Host (the AVF bridge IP changes per VM reinstall — configurable)
        SettingsCard(title = "VM SSH HOST", icon = { Icon(Icons.Filled.Wifi, contentDescription = "VM Host", tint = Morningstar.Info, modifier = Modifier.size(24.dp)) }) {
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
                        containerColor = Morningstar.Void,
                        focusedTextColor = Morningstar.Bone,
                        unfocusedTextColor = Morningstar.Bone,
                        cursorColor = Morningstar.Info,
                        focusedIndicatorColor = Morningstar.Crimson,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    placeholder = { Text("VM hostname/IP (127.0.0.1 for forwarded Linux VM)", color = Morningstar.Whisper) }
                )
                Button(
                    onClick = { onVmHostSave(vmHostText) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Morningstar.Blood,
                        contentColor = Morningstar.Bone
                    )
                ) { Text("SAVE", fontSize = 12.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold) }
            }
        }

        SettingsCard(title = "VM SSH ACCOUNT AND PORT", icon = { Icon(Icons.Filled.Wifi, contentDescription = "VM SSH account", tint = Morningstar.Info, modifier = Modifier.size(24.dp)) }) {
            Text("For Android's built-in Linux VM, enable SSH in the Debian guest and configure the Terminal app's port forwarding first. Use 127.0.0.1 plus the forwarded host port here. Lucifer still requires a verified host key.", color = Morningstar.Ash, fontSize = 12.sp, fontFamily = Morningstar.Body)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = vmUserText,
                    onValueChange = { vmUserText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                    colors = TextFieldDefaults.textFieldColors(containerColor = Morningstar.Void, focusedTextColor = Morningstar.Bone, unfocusedTextColor = Morningstar.Bone, cursorColor = Morningstar.Info, focusedIndicatorColor = Morningstar.Crimson, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                    placeholder = { Text("SSH user", color = Morningstar.Whisper) }
                )
                Button(onClick = { onVmUserSave(vmUserText) }, colors = ButtonDefaults.buttonColors(containerColor = Morningstar.Blood, contentColor = Color.White)) { Text("SAVE USER", fontSize = 11.sp, fontFamily = Morningstar.Body) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = vmPortText,
                    onValueChange = { vmPortText = it.filter { character -> character.isDigit() }.take(5) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = TextFieldDefaults.textFieldColors(containerColor = Morningstar.Void, focusedTextColor = Morningstar.Bone, unfocusedTextColor = Morningstar.Bone, cursorColor = Morningstar.Info, focusedIndicatorColor = Morningstar.Crimson, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                    placeholder = { Text("Forwarded SSH port", color = Morningstar.Whisper) }
                )
                Button(onClick = { onVmPortSave(vmPortText) }, colors = ButtonDefaults.buttonColors(containerColor = Morningstar.Blood, contentColor = Color.White)) { Text("SAVE PORT", fontSize = 11.sp, fontFamily = Morningstar.Body) }
            }
        }

        SettingsCard(title = "VM SSH HOST KEY", icon = { Icon(Icons.Filled.Wifi, contentDescription = "SSH host key", tint = Morningstar.Warning, modifier = Modifier.size(24.dp)) }) {
            Text(
                "Paste the matching known_hosts line. Verify its SHA-256 fingerprint on the VM console or another trusted channel first; ssh-keyscan by itself is not verification.",
                color = Morningstar.Ash,
                fontSize = 12.sp, fontFamily = Morningstar.Body
            )
            TextField(
                value = vmKnownHostsText,
                onValueChange = { vmKnownHostsText = it },
                modifier = Modifier.fillMaxWidth().height(90.dp),
                singleLine = false,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Morningstar.Void,
                    focusedTextColor = Morningstar.Bone,
                    unfocusedTextColor = Morningstar.Bone,
                    cursorColor = Morningstar.Gilded,
                    focusedIndicatorColor = Morningstar.Warning,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("[host]:port ssh-ed25519 AAAA…", color = Morningstar.Whisper) }
            )
            Button(
                onClick = { onVmKnownHostsSave(vmKnownHostsText) },
                colors = ButtonDefaults.buttonColors(containerColor = Morningstar.Blood, contentColor = Color.White)
            ) { Text("SAVE VERIFIED HOST KEY") }
        }

        SettingsCard(title = "LOCAL LEAN 4 VERIFICATION", icon = { Icon(Icons.Filled.Build, contentDescription = "Lean verification", tint = Morningstar.Info, modifier = Modifier.size(24.dp)) }) {
            Text(
                "Prepare fetches the public Omega Theory main source into the Debian VM without running its audit scripts. Review the exact SHA and pinned Lean toolchain below; starting verification then runs the Lean/source audit gates and Lake build in that VM. Preparation times out after 10 minutes; verification has a two-hour deadline and timeout is never a pass. Requires Git, tmux, coreutils timeout, Python 3, elan/Lean/Lake, and network access. After explicit confirmation, elan/Lake may download the pinned Lean toolchain/dependencies; Lucifer does not run apt or install system packages. Lean checks formal declarations; they do not validate physical reality.",
                color = Morningstar.Ash, fontSize = 12.sp, fontFamily = Morningstar.Body
            )
            Text("Status: $leanVerificationState", color = if (leanVerificationState == "PASS") Morningstar.Success else Morningstar.Warning, fontWeight = FontWeight.Bold)
            leanVerificationCommit?.let { Text("Source SHA: $it", color = Morningstar.Bone, fontSize = 12.sp, fontFamily = Morningstar.Body) }
            leanVerificationToolchain?.let { Text("Lean pin: $it", color = Morningstar.Bone, fontSize = 12.sp, fontFamily = Morningstar.Body) }
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
                    color = Morningstar.Ash, fontSize = 10.sp, fontFamily = Morningstar.Body, maxLines = 16
                )
            }
        }

        // NVIDIA API key — user-provided and encrypted with Android Keystore.
        SettingsCard(title = "NVIDIA API KEY", icon = { Icon(Icons.Filled.Psychology, contentDescription = "LLM Key", tint = Morningstar.Success, modifier = Modifier.size(24.dp)) }) {
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
                            containerColor = Morningstar.Void,
                            focusedTextColor = Morningstar.Bone,
                            unfocusedTextColor = Morningstar.Bone,
                            cursorColor = Morningstar.Info,
                            focusedIndicatorColor = Morningstar.Crimson,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        placeholder = { Text("Paste NVIDIA API key (empty clears it)", color = Morningstar.Whisper) }
                    )
                    Button(
                        onClick = { onNvidiaKeySave(nvidiaKeyText) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Morningstar.Blood,
                            contentColor = Morningstar.Bone
                        )
                    ) { Text("SAVE", fontSize = 12.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold) }
                }
                Spacer(modifier = Modifier.height(6.dp))
                SystemStatusRow("LLM", llmStatus, if (llmStatus == "LIVE") Morningstar.Success else Morningstar.Warning)
            }
        }

        // Wallet manager (EVM-only; there is no Solana connector in this build)
        SettingsCard(title = "WALLET & NETWORK", icon = { Icon(Icons.Filled.CloudDownload, contentDescription = "Wallet", tint = Morningstar.Success, modifier = Modifier.size(24.dp)) }) {
            Text(
                "Manage locally generated EVM wallets, encrypted at rest. No external wallet connection or fund transfers are enabled.",
                color = Morningstar.Ash,
                fontSize = 12.sp, fontFamily = Morningstar.Body
            )
            Button(
                onClick = onManageWallets,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Morningstar.Blood,
                    contentColor = Morningstar.Bone
                )
            ) { Text("Manage EVM Wallets") }
        }

        // Kai Bridge is not deployable from this build; expose the real ping instead.
        SettingsCard(title = "KAI BRIDGE SANDBOX", icon = { Icon(Icons.Filled.Build, contentDescription = "Kai Bridge", tint = Morningstar.Warning, modifier = Modifier.size(24.dp)) }) {
            Text(
                "Checks whether the Kai sandbox is installed and responds. This does not deploy or install it.",
                color = Morningstar.Ash,
                fontSize = 12.sp, fontFamily = Morningstar.Body
            )
            Button(
                onClick = onKaiHealthCheck,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Morningstar.Warning,
                    contentColor = Morningstar.Void
                )
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Check", modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Check Kai Sandbox", fontWeight = FontWeight.Bold)
            }
        }

        // Knowledge Link (his ToE corpus + GitHub source of truth)
        SettingsCard(title = "KNOWLEDGE LINK", icon = { Icon(Icons.Filled.Psychology, contentDescription = "Knowledge", tint = Morningstar.Info, modifier = Modifier.size(24.dp)) }) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SystemStatusRow("ToE Server", knowledgeStatus, if (knowledgeStatus == "LINKED") Morningstar.Success else Morningstar.Warning)
                SystemStatusRow("Corpus", corpusInfo, Morningstar.Info)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onKnowledgeSync,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Morningstar.Crimson,
                        contentColor = Morningstar.Bone
                    )
                ) { Text("SYNC FROM GITHUB", fontSize = 12.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold) }
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = knowledgeUrlText,
                    onValueChange = { knowledgeUrlText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = TextFieldDefaults.textFieldColors(
                        containerColor = Morningstar.Void,
                        focusedTextColor = Morningstar.Bone,
                        unfocusedTextColor = Morningstar.Bone,
                        cursorColor = Morningstar.Info,
                        focusedIndicatorColor = Morningstar.Crimson,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    placeholder = { Text("omega-knowledge-mcp URL (http://IP:8030)", color = Morningstar.Whisper) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onKnowledgeUrlSave(knowledgeUrlText) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Morningstar.Blood,
                        contentColor = Morningstar.Bone
                    )
                ) { Text("SAVE URL", fontSize = 12.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold) }
            }
        }

        SettingsCard(title = "LOCAL EPISODIC MEMORY · OFFLINE", icon = { Icon(Icons.Filled.Memory, contentDescription = "Local episodic memory", tint = Morningstar.Info, modifier = Modifier.size(24.dp)) }) {
            Text(
                "Stores compact, redacted extractive memories of conversations and completed findings/actions—no raw transcript or image archive. There is no age-based expiry; explicit decisions and durable findings rank above routine exchanges. Automatic low-value compaction/offload to the selected summary-only long-term server is not yet enabled, so local entries remain until you clear them and writes may fail at the 2 GiB app-private disk cap (not reserved RAM).",
                color = Morningstar.Ash, fontSize = 12.sp, fontFamily = Morningstar.Body
            )
            Spacer(modifier = Modifier.height(6.dp))
            SystemStatusRow("Local memory", if (localMemoryEnabled) "ON · $localMemoryCount entries" else "PAUSED", if (localMemoryEnabled) Morningstar.Success else Morningstar.Warning)
            SystemStatusRow("Disk usage", "${(localMemoryBytes / (1024 * 1024)).coerceAtLeast(0)} MiB / 2048 MiB", Morningstar.Info)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onLocalMemoryToggle(!localMemoryEnabled) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (localMemoryEnabled) Morningstar.GlassRaised else Morningstar.SuccessDim, contentColor = Color.White)
                ) { Text(if (localMemoryEnabled) "PAUSE SAVING" else "RESUME SAVING") }
                Button(
                    onClick = onLocalMemoryClear,
                    enabled = localMemoryCount > 0,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Morningstar.CrimsonDim, contentColor = Color.White)
                ) { Text("DELETE LOCAL MEMORY") }
            }
        }

        // The way out: everything he knows, written where you point, uploaded nowhere.
        SettingsCard(title = "EXPORT · YOUR DATA, YOUR FILE", icon = { Icon(Icons.Filled.Share, contentDescription = "Export your data", tint = Morningstar.Info, modifier = Modifier.size(24.dp)) }) {
            Text(
                "Writes one JSON document through the system file picker: the settings that are set, every " +
                    "learned skill, the egress ledger, and record counts. Secrets are excluded by name — the " +
                    "file records only whether each one exists. Nothing is uploaded; there is no code path " +
                    "from the export to a socket, and no storage permission is held because the picker grants " +
                    "the single file you choose.",
                color = Morningstar.Ash, fontSize = 12.sp, fontFamily = Morningstar.Body
            )
            Spacer(modifier = Modifier.height(6.dp))
            SystemStatusRow("Biometric gate", biometricStatus, Morningstar.Info)
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onExportBundle,
                colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Color.White)
            ) { Text("EXPORT BUNDLE", fontSize = 12.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold) }
        }

        // Always-on skill synthesis: local, bounded, and reversible from here.
        SettingsCard(title = "LEARNED SKILLS · ALWAYS ON", icon = { Icon(Icons.Filled.Psychology, contentDescription = "Learned skills", tint = Morningstar.Gilded, modifier = Modifier.size(24.dp)) }) {
            val synthContext = LocalContext.current
            val synthScope = rememberCoroutineScope()
            var synthOn by remember { mutableStateOf(true) }
            var synthLine by remember { mutableStateOf("…") }
            var synthRevision by remember { mutableStateOf(0) }
            LaunchedEffect(synthRevision) {
                withContext(Dispatchers.IO) {
                    synthOn = SkillSynthRuntime.enabled(synthContext)
                    synthLine = SkillSynthRuntime.summary(synthContext)
                }
            }
            Text(
                "Lucifer keeps a short list of habits learned from his own turns: a correction you made, a question you had to repeat, a recovery step that fixed a failed command. It is local, capped, and never grants permission — confirmations, privacy limits and surface boundaries still apply. Nothing here is sent anywhere.",
                color = Morningstar.Ash, fontSize = 12.sp, fontFamily = Morningstar.Body
            )
            Spacer(modifier = Modifier.height(6.dp))
            SystemStatusRow("Learning", if (synthOn) "ON" else "OFF", if (synthOn) Morningstar.Success else Morningstar.Warning)
            SystemStatusRow("Learned", synthLine, Morningstar.Info)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val turnOn = !synthOn
                        synthScope.launch {
                            withContext(Dispatchers.IO) { SkillSynthRuntime.setEnabled(synthContext, turnOn) }
                            synthRevision++
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (synthOn) Morningstar.GlassRaised else Morningstar.SuccessDim, contentColor = Color.White)
                ) { Text(if (synthOn) "PAUSE LEARNING" else "RESUME LEARNING") }
                Button(
                    onClick = {
                        synthScope.launch {
                            withContext(Dispatchers.IO) { SkillSynthRuntime.forgetAll(synthContext) }
                            synthRevision++
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Morningstar.CrimsonDim, contentColor = Color.White)
                ) { Text("FORGET EVERYTHING") }
            }
        }

        // Read-only local MCP retrieval from the user's private Drive memory file.
        SettingsCard(title = "DRIVE MEMORY · LOCAL MCP", icon = { Icon(Icons.Filled.Psychology, contentDescription = "Drive memory MCP", tint = Morningstar.Gilded, modifier = Modifier.size(24.dp)) }) {
            Text(
                "Before sending a prompt to the main model, Lucifer asks the same-device Termux MCP server for a few relevant passages from the private Drive memory. Retrieval is read-only; retrieved text is untrusted context, not policy or permission. If Termux is stopped, the original prompt is sent unchanged.",
                color = Morningstar.Ash, fontSize = 12.sp, fontFamily = Morningstar.Body
            )
            Spacer(modifier = Modifier.height(6.dp))
            SystemStatusRow("Memory MCP", memoryMcpStatus, when {
                memoryMcpStatus.startsWith("CONNECTED") -> Morningstar.Success
                memoryMcpStatus.startsWith("CONFIGURED") -> Morningstar.Warning
                memoryMcpStatus.startsWith("NOT CONNECTED") -> Morningstar.Crimson
                else -> Morningstar.Ash
            })
            SystemStatusRow("Bearer token", if (memoryMcpTokenConfigured) "ENCRYPTED IN ANDROID KEYSTORE" else "NOT SAVED", if (memoryMcpTokenConfigured) Morningstar.Success else Morningstar.Warning)
            TextField(
                value = memoryMcpUrlText,
                onValueChange = { memoryMcpUrlText = it.take(160) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Morningstar.Void, focusedTextColor = Morningstar.Bone,
                    unfocusedTextColor = Morningstar.Bone, cursorColor = Morningstar.Gilded,
                    focusedIndicatorColor = Morningstar.Crimson, unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("http://127.0.0.1:8031/mcp", color = Morningstar.Whisper) }
            )
            TextField(
                value = memoryMcpTokenText,
                onValueChange = { memoryMcpTokenText = it.take(256) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Morningstar.Void, focusedTextColor = Morningstar.Bone,
                    unfocusedTextColor = Morningstar.Bone, cursorColor = Morningstar.Gilded,
                    focusedIndicatorColor = Morningstar.Crimson, unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("Paste the Termux bearer token (leave blank to keep saved token)", color = Morningstar.Whisper) }
            )
            Text("The endpoint is restricted to localhost / 127.0.0.1 on port 8031. The token is never stored in plaintext by Lucifer.", color = Morningstar.Ash, fontSize = 10.sp, fontFamily = Morningstar.Body)
            Button(
                onClick = onMemoryMcpTest,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Bone)
            ) { Text("TEST READ-ONLY MCP CONNECTION") }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onMemoryMcpSave(memoryMcpUrlText, memoryMcpTokenText); memoryMcpTokenText = "" },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Morningstar.Crimson, contentColor = Color.White)
                ) { Text("SAVE LOCAL MCP") }
                Button(
                    onClick = { onMemoryMcpRemove(); memoryMcpUrlText = ""; memoryMcpTokenText = "" },
                    colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Warning)
                ) { Text("REMOVE") }
            }
        }

        // Voice
        SettingsCard(title = "VOICE", icon = { Icon(Icons.Filled.PlayArrow, contentDescription = "Voice", tint = Morningstar.Success, modifier = Modifier.size(24.dp)) }) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("He speaks results & replies", fontSize = 13.sp, fontFamily = Morningstar.Body, color = Morningstar.Bone)
                Text(
                    if (voiceEnabled) "ON" else "OFF",
                    fontSize = 14.sp, fontFamily = Morningstar.Display,
                    fontWeight = FontWeight.Bold,
                    color = if (voiceEnabled) Morningstar.Success else Morningstar.Ash,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (voiceEnabled) Morningstar.SuccessDim else Morningstar.GlassRaised)
                        .clickable { onVoiceToggle(!voiceEnabled) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // System Info
        SettingsCard(title = "SYSTEM STATUS", icon = { Icon(Icons.Filled.Wifi, contentDescription = "Network", tint = Morningstar.Ash, modifier = Modifier.size(24.dp)) }) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SystemStatusRow("SSH Cluster", "Active (port 8023)", Morningstar.Success)
                SystemStatusRow("SSH target", if (savedVmHost.isBlank()) "not set — configure above" else "$savedVmUser@$savedVmHost:$savedVmPort", if (savedVmHost.isBlank()) Morningstar.Warning else Morningstar.Success)
                SystemStatusRow("Heartbeat", LuciferPhase(), Morningstar.Info)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shard(13.dp), Morningstar.Edge)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
            Text(
                title,
                style = Morningstar.overline(10).copy(color = Morningstar.Bone),
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        SigilRule()
        Spacer(modifier = Modifier.height(10.dp))
        content()
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
        Text(text = label, fontSize = 12.sp, fontFamily = Morningstar.Body, color = Morningstar.Bone)
        Text(text = value, fontSize = 12.sp, fontFamily = Morningstar.Body, color = valueColor, fontWeight = FontWeight.Medium)
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
