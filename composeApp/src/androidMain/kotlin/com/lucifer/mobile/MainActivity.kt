package com.lucifer.mobile

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiBridge
import com.lucifer.mobile.omni_bridge.knowledge.ToEReasoningEngine
import com.lucifer.mobile.terminal.TerminalExecutor
import com.lucifer.mobile.ui.components.AnimatedMemoryPanel
import com.lucifer.mobile.ui.components.AnimatedSettingsPanel
import com.lucifer.mobile.ui.components.AnimatedSkillsPanel
import com.lucifer.mobile.ui.components.AnimatedTabBar
import com.lucifer.mobile.ui.components.AnimatedTerminal
import com.lucifer.mobile.ui.components.TabItem
import com.lucifer.mobile.ui.theme.LuciferColors
import com.lucifer.mobile.ui.theme.LuciferTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start the foreground heartbeat service OUTSIDE of Compose (prevents process freeze)
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, com.lucifer.mobile.omni_bridge.heartbeat.LuciferHeartbeatService::class.java)
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "heartbeat service start failed", e)
        }

        setContent {
            LuciferTheme {
                val scope = remember { CoroutineScope(Dispatchers.Main) }

                var terminalLines by remember { mutableStateOf(loadTerminalHistory()) }
                var isProcessing by remember { mutableStateOf(false) }
                var terminalTarget by remember { mutableStateOf(0) } // 0=LOCAL, 1=TERMUX, 2=VM
                val terminalExecutor = remember { TerminalExecutor(this@MainActivity, scope) }
                val reasoner = remember { ToEReasoningEngine(this@MainActivity) }
                var voiceEnabled by remember { mutableStateOf(loadVoiceEnabled()) }
                var settingsToast by remember { mutableStateOf<String?>(null) }
                val savedKnowledgeUrl by remember { mutableStateOf(loadKnowledgeUrl()) }

                var kaiConnected by remember { mutableStateOf(false) }
                var connectionError by remember { mutableStateOf<String?>(null) }
                var llmStatus by remember { mutableStateOf("…") }
                var savedVmHost by remember { mutableStateOf(loadVmHost()) }
                val llmClient = remember { com.lucifer.mobile.omni_bridge.llm.NemotronClient(this@MainActivity) }
                var memoryItems by remember { mutableStateOf(loadMemoryItems()) }
                var skills by remember { mutableStateOf(buildSkillRegistry()) }

                // One-shot LLM health probe for the Settings status row.
                LaunchedEffect(Unit) {
                    try {
                        val ok = llmClient.healthCheck()
                        llmStatus = if (ok) "LIVE" else "DARK"
                    } catch (e: Exception) {
                        llmStatus = "DARK"
                    }
                }

                scope.launch {
                    try {
                        val connected = KaiBridge(this@MainActivity).connect()
                        kaiConnected = connected
                    } catch (e: Exception) {
                        connectionError = e.message
                    }
                }

                var selectedTab by remember { mutableStateOf(intent?.getIntExtra("lucifer_tab", 0) ?: 0) }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = LuciferColors.Obsidian
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            AnimatedTabBar(
                                selectedTab = selectedTab,
                                onTabSelected = { selectedTab = it },
                                tabs = listOf(
                                    TabItem("Terminal", Icons.Filled.Terminal),
                                    TabItem("Memory", Icons.Filled.Memory),
                                    TabItem("Skills", Icons.Filled.Psychology),
                                    TabItem("Settings", Icons.Filled.Settings)
                                )
                            )

                            when (selectedTab) {
                                0 -> AnimatedTerminal(
                                    modifier = Modifier.fillMaxSize(),
                                    outputLines = terminalLines,
                                    isProcessing = isProcessing,
                                    target = terminalTarget,
                                    onTargetSelected = { terminalTarget = it },
                                    onCommand = { cmd ->
                                        isProcessing = true
                                        terminalLines = terminalLines + "$ $cmd"
                                        scope.launch(Dispatchers.IO) {
                                            val result = terminalExecutor.execute(cmd, terminalTarget)
                                            val newLines = terminalLines + result
                                            terminalLines = if (newLines.size > 200) newLines.takeLast(200) else newLines
                                            saveTerminalHistory(terminalLines)
                                            isProcessing = false
                                        }
                                    }
                                )
                                1 -> AnimatedMemoryPanel(
                                    modifier = Modifier.fillMaxSize(),
                                    memories = memoryItems,
                                    onSearch = { q ->
                                        scope.launch(Dispatchers.IO) {
                                            val hits = searchMemories(q)
                                            withContext(Dispatchers.Main) { memoryItems = hits }
                                        }
                                    },
                                    isSearching = false
                                )
                                2 -> AnimatedSkillsPanel(
                                    modifier = Modifier.fillMaxSize(),
                                    skills = skills,
                                    onSkillToggle = { id, enabled ->
                                        skills = skills.map { if (it.id == id) it.copy(isEnabled = enabled) else it }
                                    }
                                )
                                3 -> AnimatedSettingsPanel(
                                    modifier = Modifier.fillMaxSize(),
                                    onSoulMdEdit = { saveSoulPrompt(it) },
                                    onHeartbeatEdit = { saveHeartbeatPrompt(it) },
                                    onWalletConnect = { },
                                    onDeployKaiBridge = { },
                                    onVmPasswordSave = { saveVmPassword(it) },
                                    onVmHostSave = { host ->
                                        saveVmHost(host)
                                        savedVmHost = host
                                    },
                                    onNvidiaKeySave = { key ->
                                        scope.launch(Dispatchers.IO) {
                                            llmClient.saveRuntimeKey(key)
                                            val ok = llmClient.healthCheck()
                                            withContext(Dispatchers.Main) {
                                                llmStatus = if (ok) "LIVE" else "DARK"
                                            }
                                        }
                                    },
                                    onVoiceToggle = { enabled ->
                                        voiceEnabled = enabled
                                        saveVoiceEnabled(enabled)
                                    },
                                    onKnowledgeSync = {
                                        scope.launch(Dispatchers.IO) {
                                            val result = reasoner.syncFromGithub()
                                            withContext(Dispatchers.Main) {
                                                settingsToast = result.take(160)
                                            }
                                        }
                                    },
                                    onKnowledgeUrlSave = { url ->
                                        saveKnowledgeUrl(url)
                                        reasoner.updateBaseUrl(url)
                                    },
                                    voiceEnabled = voiceEnabled,
                                    knowledgeStatus = if (reasoner.knowledgeServerAlive()) "EMBEDDED" else "…",
                                    corpusInfo = "54 Lean volumes · axioms · book (in-APK)",
                                    savedKnowledgeUrl = savedKnowledgeUrl,
                                    llmStatus = llmStatus,
                                    savedVmHost = savedVmHost
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadTerminalHistory(): List<String> {
        return try {
            val file = File(filesDir, "terminal_history.log")
            if (file.exists()) {
                FileInputStream(file).use { input ->
                    input.readBytes().decodeToString().split("\n").filter { it.isNotBlank() }
                }
            } else {
                listOf(
                    "🫀 Heartbeat service started",
                    "🔗 Kai Bridge: Connecting...",
                    "💰 Wallet: Ready"
                )
            }
        } catch (e: Exception) {
            listOf("🫀 Heartbeat service started")
        }
    }

    // ───────────── Memory panel (real data from his files + logs) ─────────────

    /** Load memory items: app_launch.log, app_events.log, heartbeat log lines. */
    private fun loadMemoryItems(): List<com.lucifer.mobile.ui.components.MemoryItem> {
        return searchMemories("")
    }

    /** Substring search across his memory files; blank query = everything. */
    private fun searchMemories(query: String): List<com.lucifer.mobile.ui.components.MemoryItem> {
        val items = mutableListOf<com.lucifer.mobile.ui.components.MemoryItem>()
        val memoryDir = File(filesDir, "memory")
        val sources = listOf(
            File(memoryDir, "app_launch.log"),
            File(memoryDir, "app_events.log"),
            File(filesDir, "terminal_history.log")
        )
        for (src in sources) {
            try {
                if (!src.exists()) continue
                val lines = src.readLines().filter { it.isNotBlank() }.takeLast(40)
                for ((i, line) in lines.withIndex()) {
                    if (query.isBlank() || line.contains(query, ignoreCase = true)) {
                        items.add(
                            com.lucifer.mobile.ui.components.MemoryItem(
                                id = "${src.name}-$i-${line.hashCode()}",
                                content = line.take(200),
                                timestamp = src.lastModified(),
                                tags = listOf(src.nameWithoutExtension),
                                relevance = 1.0f
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }
        return items.takeLast(30)
    }

    /** The framework's real capabilities, wired from his own engines. */
    private fun buildSkillRegistry(): List<com.lucifer.mobile.ui.components.SkillItem> {
        return listOf(
            com.lucifer.mobile.ui.components.SkillItem(
                id = "four_horsemen", name = "Four Horsemen Council",
                description = "CONQUEST plans, WAR red-teams, FAMINE compresses, DEATH prunes — real Nemotron passes",
                category = "Cognition", isEnabled = true,
                icon = Icons.Filled.Psychology, complexity = 3
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "toe_reasoning", name = "Omega ToE Reasoning",
                description = "PERCEIVE→UNDERSTAND→DECIDE→ACT grounded in the 54-volume embedded corpus",
                category = "Cognition", isEnabled = true,
                icon = Icons.Filled.Psychology, complexity = 3
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "heartbeat", name = "Autonomous Heartbeat",
                description = "25-min Ideation↔Execution cycle with project ledger and auto-deploy",
                category = "Autonomy", isEnabled = true,
                icon = Icons.Filled.PlayArrow, complexity = 3
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "rcod", name = "RCOD State Estimator",
                description = "Uncertainty/contradiction/complexity tracking for his own reasoning",
                category = "Metacognition", isEnabled = true,
                icon = Icons.Filled.Memory, complexity = 2
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "equi_router", name = "EquiRouter Topology Ranking",
                description = "Instance-wise Pareto-optimal horseman topology via Plackett-Luce ranking",
                category = "Metacognition", isEnabled = true,
                icon = Icons.Filled.Memory, complexity = 3
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "context_compression", name = "Context Compressor",
                description = "8K-budget context curation with token accounting",
                category = "Metacognition", isEnabled = true,
                icon = Icons.Filled.Memory, complexity = 2
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "evidence_ledger", name = "Evidence Ledger",
                description = "Cryptographic provenance records for every decision",
                category = "Governance", isEnabled = true,
                icon = Icons.Filled.Build, complexity = 3
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "appa_policy", name = "APPA Policy Engine",
                description = "Prospective acquisition enforcement — prevents the usability wall",
                category = "Governance", isEnabled = true,
                icon = Icons.Filled.Build, complexity = 3
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "voice", name = "Voice (TTS)",
                description = "He speaks results and replies — rate 0.95, pitch 0.85",
                category = "Interface", isEnabled = true,
                icon = Icons.Filled.PlayArrow, complexity = 1
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "multi_target_terminal", name = "Multi-Target Terminal",
                description = "L=app sandbox · T=Termux · V=Debian VM — with history persistence",
                category = "Interface", isEnabled = true,
                icon = Icons.Filled.Terminal, complexity = 2
            )
        )
    }

    // ───────────── VM host config (bridge IP changes per reinstall) ─────────────

    private fun loadVmHost(): String {
        return try {
            val file = File(filesDir, "config/vm_host.txt")
            if (file.exists()) file.readText().trim() else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun saveVmHost(host: String) {
        try {
            val configDir = File(filesDir, "config")
            configDir.mkdirs()
            val file = File(configDir, "vm_host.txt")
            FileOutputStream(file).use { output -> output.write(host.trim().toByteArray()) }
        } catch (e: Exception) {
            Log.e("MainActivity", "saveVmHost failed", e)
        }
    }

    private fun saveTerminalHistory(lines: List<String>) {
        try {
            val file = File(filesDir, "terminal_history.log")
            FileOutputStream(file).use { output ->
                output.write(lines.joinToString("\n").toByteArray())
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun saveSoulPrompt(prompt: String) {
        try {
            val file = File(filesDir, "soul.md")
            FileOutputStream(file).use { output -> output.write(prompt.toByteArray()) }
        } catch (e: Exception) {
            Log.e("MainActivity", "saveSoulPrompt failed", e)
        }
    }

    fun saveHeartbeatPrompt(prompt: String) {
        try {
            val file = File(filesDir, "heartbeat_prompt.md")
            FileOutputStream(file).use { output -> output.write(prompt.toByteArray()) }
        } catch (e: Exception) {
            Log.e("MainActivity", "saveHeartbeatPrompt failed", e)
        }
    }

    fun saveVmPassword(password: String) {
        try {
            val configDir = File(filesDir, "config")
            configDir.mkdirs()
            val file = File(configDir, "vm_ssh_password.txt")
            FileOutputStream(file).use { output -> output.write(password.toByteArray()) }
        } catch (e: Exception) {
            Log.e("MainActivity", "saveVmPassword failed", e)
        }
    }

    private fun loadVoiceEnabled(): Boolean {
        return try {
            val file = File(filesDir, "config/voice_enabled.txt")
            !file.exists() || file.readText().trim() != "false"
        } catch (e: Exception) {
            true
        }
    }

    fun saveVoiceEnabled(enabled: Boolean) {
        try {
            val configDir = File(filesDir, "config")
            configDir.mkdirs()
            val file = File(configDir, "voice_enabled.txt")
            FileOutputStream(file).use { output ->
                val state = if (enabled) "true" else "false"
                output.write(state.toByteArray())
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "saveVoiceEnabled failed", e)
        }
    }

    private fun loadKnowledgeUrl(): String {
        return try {
            val file = File(filesDir, "config/knowledge_url.txt")
            if (file.exists()) file.readText().trim() else ""
        } catch (e: Exception) {
            ""
        }
    }

    fun saveKnowledgeUrl(url: String) {
        try {
            val configDir = File(filesDir, "config")
            configDir.mkdirs()
            val file = File(configDir, "knowledge_url.txt")
            FileOutputStream(file).use { output -> output.write(url.toByteArray()) }
        } catch (e: Exception) {
            Log.e("MainActivity", "saveKnowledgeUrl failed", e)
        }
    }
}
