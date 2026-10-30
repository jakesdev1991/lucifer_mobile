package com.lucifer.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lucifer.mobile.omni_bridge.kai_bridge.KaiBridge
import com.lucifer.mobile.omni_bridge.heartbeat.LuciferHeartbeatService
import com.lucifer.mobile.omni_bridge.knowledge.ToEReasoningEngine
import com.lucifer.mobile.omni_bridge.knowledge.Lean4VerificationRunner
import com.lucifer.mobile.terminal.TerminalExecutor
import com.lucifer.mobile.terminal.FloatingChatService
import com.lucifer.mobile.terminal.TermuxInteractiveSession
import com.lucifer.mobile.terminal.LocalWebsiteManager
import com.lucifer.mobile.launcher.ChatRoute
import com.lucifer.mobile.launcher.CommandTarget
import com.lucifer.mobile.launcher.NaturalLanguageRouter
import com.lucifer.mobile.launcher.ResilienceRequest
import com.lucifer.mobile.launcher.TerminalAction
import com.lucifer.mobile.resilience.MoveSafety
import com.lucifer.mobile.resilience.RecoveryKind
import com.lucifer.mobile.resilience.RecoveryMove
import com.lucifer.mobile.resilience.ResilienceEngine
import com.lucifer.mobile.ui.components.RecoveryControlPanel
import com.lucifer.mobile.security.SecretStore
import com.lucifer.mobile.security.BiometricGate
import com.lucifer.mobile.data.export.ExportBundle
import com.lucifer.mobile.memory.DriveMemoryMcpClient
import com.lucifer.mobile.memory.LocalConversationMemory
import com.lucifer.mobile.skills.SkillLibrary
import com.lucifer.mobile.skills.SkillSynthRuntime
import com.lucifer.mobile.shizuku.ShizukuAccess
import com.lucifer.mobile.shizuku.ShizukuTerminal
import rikka.shizuku.Shizuku
import com.lucifer.mobile.ui.components.AnimatedMemoryPanel
import com.lucifer.mobile.ui.components.AnimatedSettingsPanel
import com.lucifer.mobile.ui.components.AnimatedSkillsPanel
import com.lucifer.mobile.ui.components.AnimatedTabBar
import com.lucifer.mobile.ui.components.AnimatedTerminal
import com.lucifer.mobile.ui.components.BrowserControlPanel
import com.lucifer.mobile.agents.android.ObservationBus
import com.lucifer.mobile.agents.android.PromptStore
import com.lucifer.mobile.surface.Surface
import com.lucifer.mobile.agents.android.TacticianService
import com.lucifer.mobile.tactician.ObservationKind
import com.lucifer.mobile.privilege.DhizukuBridge
import com.lucifer.mobile.privilege.PrivilegeCommands
import com.lucifer.mobile.privilege.PrivilegeLedger
import com.lucifer.mobile.privilege.PrivilegeOp
import com.lucifer.mobile.privilege.PrivilegeOutcome
import com.lucifer.mobile.privilege.PrivilegeRouter
import com.lucifer.mobile.privilege.Provider
import com.lucifer.mobile.privilege.ProviderPlan
import com.lucifer.mobile.world.WorldAction
import com.lucifer.mobile.world.WorldManager
import com.lucifer.mobile.world.WorldCharter
import com.lucifer.mobile.ui.components.AgentsPanel
import com.lucifer.mobile.ui.components.WorldPanel
import com.lucifer.mobile.ui.components.WorldUiState
import com.lucifer.mobile.verification.VerifierController
import com.lucifer.mobile.verification.VerifierUiCommand
import com.lucifer.mobile.verification.VerifierUiState
import com.lucifer.mobile.ui.effects.FallIn
import com.lucifer.mobile.ui.components.HeartbeatControlPanel
import com.lucifer.mobile.ui.components.WalletControlPanel
import com.lucifer.mobile.ui.components.WorkflowControlPanel
import com.lucifer.mobile.workflows.WorkflowAgent
import com.lucifer.mobile.workflows.WorkflowBlueprints
import com.lucifer.mobile.workflows.WorkflowReflection
import com.lucifer.mobile.workflows.WorkflowStepStatus
import com.lucifer.mobile.workflows.WorkflowTask
import com.lucifer.mobile.workflows.WorkflowTaskStatus
import com.lucifer.mobile.workflows.WorkflowTaskStore
import com.lucifer.mobile.workflows.WorkflowTransitions
import com.lucifer.mobile.ui.components.TabItem
import com.lucifer.mobile.ui.theme.LuciferColors
import com.lucifer.mobile.ui.theme.LuciferTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.AtomicFile
import com.lucifer.mobile.config.AppConfig
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LuciferTheme {
                val scope = remember { CoroutineScope(Dispatchers.Main) }

                var terminalLines by remember { mutableStateOf(loadTerminalHistory()) }
                var isProcessing by remember { mutableStateOf(false) }
                // The in-flight turn, so the STOP control has something to cancel. A deep run can last
                // ninety seconds; a UI that offers no way out of that is a UI that traps you.
                var activeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                var terminalTarget by remember { mutableStateOf(0) } // 0=LOCAL, 1=TERMUX, 2=VM, 3=SHIZUKU
                val terminalExecutor = remember { TerminalExecutor(this@MainActivity) }
                val router = remember { NaturalLanguageRouter(this@MainActivity) }
                val reasoner = remember { ToEReasoningEngine(this@MainActivity) }
                val resilience = remember { ResilienceEngine(this@MainActivity) }
                val resilienceState by resilience.state.collectAsState()
                val workflowStore = remember { WorkflowTaskStore(this@MainActivity) }
                val workflowAgent = remember { WorkflowAgent(this@MainActivity) }
                val workflowLoad = remember { runCatching { workflowStore.load() } }
                val recoveredWorkflowTasks = remember { workflowLoad.map(workflowStore::recoverInterrupted) }
                var workflowTasks by remember { mutableStateOf(recoveredWorkflowTasks.getOrDefault(emptyList())) }
                var selectedWorkflowTaskId by remember { mutableStateOf(workflowTasks.lastOrNull()?.id) }
                var workflowBusy by remember { mutableStateOf(false) }
                var workflowStoreHealthy by remember { mutableStateOf(workflowLoad.isSuccess) }
                var workflowMessage by remember { mutableStateOf(workflowLoad.exceptionOrNull()?.message ?: "One step at a time; review output before continuing.") }
                var pendingWorkflowStep by remember { mutableStateOf<Pair<String, String>?>(null) }
                var pendingTerminalAction by remember { mutableStateOf<TerminalAction?>(null) }
                var pendingRecoveryMove by remember { mutableStateOf<RecoveryMove?>(null) }
                var voiceEnabled by remember { mutableStateOf(loadVoiceEnabled()) }
                var settingsToast by remember { mutableStateOf<String?>(null) }
                // The last deep reasoning run, kept so the Skills tab can show how an answer was reached
                // rather than only what it said.
                var cognitionSummary by remember { mutableStateOf("") }
                var cognitionTrace by remember { mutableStateOf("") }
                var cognitionHistory by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    cognitionHistory = withContext(Dispatchers.IO) {
                        com.lucifer.mobile.cognition.CognitionLedger.summary(this@MainActivity)
                    }
                }
                var showTermuxToolkitDialog by remember { mutableStateOf(false) }
                var showLeanVerificationConfirmation by remember { mutableStateOf(false) }
                var leanVerificationBusy by remember { mutableStateOf(false) }
                var leanVerificationState by remember { mutableStateOf("NOT RUN") }
                var leanVerificationOutput by remember { mutableStateOf("") }
                var leanVerificationCommit by remember { mutableStateOf<String?>(null) }
                var leanVerificationToolchain by remember { mutableStateOf<String?>(null) }
                var shizukuStatus by remember { mutableStateOf(ShizukuAccess.status()) }
                var shizukuAuditOutput by remember { mutableStateOf("") }
                var shizukuAuditBusy by remember { mutableStateOf(false) }

                fun runLeanVerification(operation: suspend () -> Lean4VerificationRunner.Snapshot) {
                    if (leanVerificationBusy) return
                    leanVerificationBusy = true
                    scope.launch {
                        val result = try {
                            operation()
                        } catch (e: Exception) {
                            Lean4VerificationRunner.Snapshot(
                                "UNAVAILABLE",
                                "[ERROR] ${e.message ?: "Lean verification request failed."}"
                            )
                        }
                        leanVerificationState = result.state
                        leanVerificationOutput = result.output
                        if (result.commit != null) leanVerificationCommit = result.commit
                        if (result.toolchain != null) leanVerificationToolchain = result.toolchain
                        leanVerificationBusy = false
                    }
                }

                fun saveWorkflowTasks(updated: List<WorkflowTask>) {
                    if (!workflowStoreHealthy) return
                    try {
                        workflowStore.save(updated)
                        workflowTasks = updated
                        if (selectedWorkflowTaskId == null || updated.none { it.id == selectedWorkflowTaskId }) {
                            selectedWorkflowTaskId = updated.lastOrNull()?.id
                        }
                    } catch (e: Exception) {
                        workflowStoreHealthy = false
                        workflowMessage = "Workflow storage failed; changes are disabled to avoid overwriting data. ${e.message ?: "Storage error"}"
                    }
                }

                fun updateWorkflowTask(taskId: String, transform: (WorkflowTask) -> WorkflowTask) {
                    val updated = workflowTasks.map { task -> if (task.id == taskId) transform(task) else task }
                    if (updated != workflowTasks) saveWorkflowTasks(updated)
                }

                fun createWorkflowTask(blueprintId: String, goal: String, target: CommandTarget) {
                    if (workflowBusy || !workflowStoreHealthy || workflowTasks.size >= WorkflowTaskStore.MAX_TASKS) return
                    val blueprint = WorkflowBlueprints.get(blueprintId) ?: return
                    workflowBusy = true
                    workflowMessage = "Planning the task. The goal is sent to the configured model when available."
                    scope.launch(Dispatchers.IO) {
                        val plan = try {
                            workflowAgent.plan(blueprint, goal)
                        } catch (_: Exception) {
                            com.lucifer.mobile.workflows.WorkflowPlan(
                                "Using the static blueprint because model planning failed.", blueprint.steps, "TEMPLATE"
                            )
                        }
                        val task = try { workflowStore.newTask(blueprint, goal, target, plan) } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                workflowBusy = false
                                workflowMessage = "Could not create workflow: ${e.message ?: "invalid plan"}"
                            }
                            return@launch
                        }
                        withContext(Dispatchers.Main) {
                            saveWorkflowTasks((workflowTasks + task).takeLast(WorkflowTaskStore.MAX_TASKS))
                            if (workflowStoreHealthy) {
                                selectedWorkflowTaskId = task.id
                                workflowMessage = "${plan.source} plan created. Run one step, inspect the result, then reflect before continuing."
                            } else {
                                workflowMessage = "Task could not be saved; storage is now read-only to protect existing workflow data."
                            }
                            workflowBusy = false
                        }
                    }
                }

                fun reflectWorkflowTask(taskId: String) {
                    if (workflowBusy || !workflowStoreHealthy) return
                    val task = workflowTasks.firstOrNull { it.id == taskId } ?: return
                    if (task.status != WorkflowTaskStatus.REVIEW || task.currentStep?.status != WorkflowStepStatus.REVIEW) return
                    workflowBusy = true
                    workflowMessage = "Reviewing the recorded step output. This sends bounded task evidence to the configured model."
                    scope.launch(Dispatchers.IO) {
                        val reflection = try {
                            workflowAgent.reflect(task)
                        } catch (_: Exception) {
                            WorkflowReflection(
                                "Model reflection failed; inspect the result and retry or use the template fallback.",
                                null, false, "ERROR"
                            )
                        }
                        withContext(Dispatchers.Main) {
                            if (reflection.source == "ERROR") {
                                workflowMessage = reflection.assessment
                            } else {
                                updateWorkflowTask(taskId) { WorkflowTransitions.applyReflection(it, reflection) }
                                workflowMessage = if (workflowStoreHealthy) {
                                    "${reflection.source} review ready. Lucifer has not marked the task complete."
                                } else "Review could not be saved; workflow actions are disabled to protect existing data."
                            }
                            workflowBusy = false
                        }
                    }
                }

                fun runLocalSiteOperation(label: String, operation: suspend () -> List<String>) {
                    if (isProcessing) return
                    if (terminalTarget != CommandTarget.TERMUX.launcherIndex) {
                        appendTerminalLines(listOf("[SETUP] Select TERMUX to stage a local website."))
                        return
                    }
                    isProcessing = true
                    appendTerminalLines(listOf("[INFO] $label"))
                    scope.launch(Dispatchers.IO) {
                        val result = try { operation() } catch (e: Exception) {
                            listOf("[ERROR] ${e.message ?: "Local site operation failed."}")
                        }
                        withContext(Dispatchers.Main) {
                            appendTerminalLines(result)
                            isProcessing = false
                        }
                    }
                }

                DisposableEffect(Unit) {
                    val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                        if (requestCode == ShizukuAccess.REQUEST_CODE) {
                            runOnUiThread {
                                shizukuStatus = ShizukuAccess.status()
                                settingsToast = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                    "Shizuku access granted to Lucifer."
                                } else {
                                    "Shizuku access denied. You can change it in Shizuku."
                                }
                            }
                        }
                    }
                    Shizuku.addRequestPermissionResultListener(permissionListener)
                    onDispose { Shizuku.removeRequestPermissionResultListener(permissionListener) }
                }
                var heartbeatPermissionAction by remember { mutableStateOf("start") }
                // Shared launcher: the tactician asks only for POST_NOTIFICATIONS, and this flag
                // decides which foreground service to start once the grant returns.
                var tacticianPermissionRequested by remember { mutableStateOf(false) }
                // ── getting the data out, through the system picker ──
                var biometricStatus by remember { mutableStateOf(BiometricGate.capability(this@MainActivity).detail) }
                LaunchedEffect(Unit) {
                    biometricStatus = BiometricGate.capability(this@MainActivity).detail
                }
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json")
                ) { uri ->
                    if (uri == null) {
                        settingsToast = "Export cancelled — nothing was written."
                    } else {
                        scope.launch(Dispatchers.IO) {
                            val result = ExportBundle.write(this@MainActivity, uri)
                            withContext(Dispatchers.Main) {
                                settingsToast = result.getOrElse { "Export failed: ${it.message ?: "unknown error"}" }
                            }
                        }
                    }
                }

                val heartbeatPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { _ ->
                    val cameraGranted = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    val notificationsGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    val cameraRequired = heartbeatPermissionAction == "camera" && !tacticianPermissionRequested
                    if (tacticianPermissionRequested) {
                        tacticianPermissionRequested = false
                        settingsToast = if (!notificationsGranted) {
                            "The General was not started: allow notifications so its foreground status stays visible."
                        } else {
                            try {
                                TacticianService.start(this@MainActivity)
                                "The General is watching. One bounded pass every ~60 minutes; stop it from the notification or the Agents panel."
                            } catch (e: Exception) {
                                "Could not start the tactician: ${e.message ?: "system denied foreground service"}"
                            }
                        }
                    } else if (notificationsGranted && (!cameraRequired || cameraGranted)) {
                        try {
                            if (heartbeatPermissionAction == "camera") LuciferHeartbeatService.enableCamera(this@MainActivity)
                            else LuciferHeartbeatService.start(this@MainActivity)
                            settingsToast = if (heartbeatPermissionAction == "camera") "Camera enabled for future heartbeat sessions." else "Heartbeat enabled. The first session is starting now."
                        } catch (e: Exception) {
                            settingsToast = "Could not start heartbeat: ${e.message ?: "system denied foreground service"}"
                        }
                    } else {
                        settingsToast = if (cameraRequired && !cameraGranted) {
                            "Camera not enabled: grant CAMERA permission to opt into image context."
                        } else {
                            "Heartbeat not started: allow notifications for its persistent foreground status."
                        }
                    }
                }
                // Settings used to be read inside remember {}, which runs on the main thread during the
                // first composition of this screen: six files plus a Keystore decrypt before the first
                // frame. The screen now paints its defaults immediately and the values arrive from IO.
                var savedKnowledgeUrl by remember { mutableStateOf("") }
                var savedMemoryMcpUrl by remember { mutableStateOf("") }
                var memoryMcpTokenConfigured by remember { mutableStateOf(false) }
                var memoryMcpStatus by remember { mutableStateOf("DISABLED") }
                var savedVmHost by remember { mutableStateOf("") }
                var savedVmUser by remember { mutableStateOf(DEFAULT_VM_USER) }
                var savedVmPort by remember { mutableStateOf(DEFAULT_VM_PORT) }
                var savedVmKnownHosts by remember { mutableStateOf("") }

                LaunchedEffect(Unit) {
                    val settings = withContext(Dispatchers.IO) {
                        SettingsSnapshot(
                            knowledgeUrl = loadKnowledgeUrl(),
                            memoryMcpUrl = loadMemoryMcpUrl(),
                            memoryMcpTokenSet = !SecretStore.read(this@MainActivity, AppConfig.MEMORY_MCP_TOKEN).isNullOrBlank(),
                            vmHost = loadVmHost(),
                            vmUser = loadVmUser(),
                            vmPort = loadVmPort(),
                            vmKnownHosts = loadVmKnownHosts()
                        )
                    }
                    savedKnowledgeUrl = settings.knowledgeUrl
                    savedMemoryMcpUrl = settings.memoryMcpUrl
                    memoryMcpTokenConfigured = settings.memoryMcpTokenSet
                    savedVmHost = settings.vmHost
                    savedVmUser = settings.vmUser
                    savedVmPort = settings.vmPort
                    savedVmKnownHosts = settings.vmKnownHosts
                    memoryMcpStatus = when {
                        settings.memoryMcpUrl.isBlank() -> "DISABLED"
                        settings.memoryMcpTokenSet -> "CONFIGURED · NOT TESTED"
                        else -> "TOKEN REQUIRED"
                    }
                }

                var llmStatus by remember { mutableStateOf("…") }
                val llmClient = remember { com.lucifer.mobile.omni_bridge.llm.NemotronClient(this@MainActivity) }
                val localMemoryStore = remember { LocalConversationMemory(this@MainActivity) }
                // A SQLiteOpenHelper that is never closed keeps its connection for the life of the
                // process; without this, every recreation of this screen leaves another one open.
                DisposableEffect(localMemoryStore) {
                    onDispose { localMemoryStore.close() }
                }
                var localMemoryEnabled by remember {
                    mutableStateOf(getSharedPreferences(LocalConversationMemory.PREFS, MODE_PRIVATE)
                        .getBoolean(LocalConversationMemory.KEY_ENABLED, true))
                }
                var localMemoryCount by remember { mutableStateOf(0) }
                var localMemoryBytes by remember { mutableStateOf(0L) }
                // Reading three log files and opening the conversation store is not a composition job.
                var memoryItems by remember { mutableStateOf<List<com.lucifer.mobile.ui.components.MemoryItem>>(emptyList()) }
                var memorySearching by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    memoryItems = withContext(Dispatchers.IO) { loadMemoryItems() }
                }
                val skills = remember { buildSkillRegistry() }
                val referenceSkillLibrary = remember { SkillLibrary.load(this@MainActivity.assets) }
                // The learned list is read on demand: it changes from turns taken anywhere in the app.
                var synthRevision by remember { mutableStateOf(0) }
                var synthSummary by remember { mutableStateOf("") }
                var synthEnabled by remember { mutableStateOf(true) }
                LaunchedEffect(synthRevision) {
                    val (line, on) = withContext(Dispatchers.IO) {
                        SkillSynthRuntime.summary(this@MainActivity) to SkillSynthRuntime.enabled(this@MainActivity)
                    }
                    synthSummary = line
                    synthEnabled = on
                }

                LaunchedEffect(savedKnowledgeUrl) {
                    if (savedKnowledgeUrl.isNotBlank()) reasoner.updateBaseUrl(savedKnowledgeUrl)
                }

                LaunchedEffect(localMemoryStore) {
                    val localStats = withContext(Dispatchers.IO) {
                        localMemoryStore.count() to localMemoryStore.storageBytes()
                    }
                    localMemoryCount = localStats.first
                    localMemoryBytes = localStats.second
                }

                // One-shot LLM health probe for the Settings status row.
                LaunchedEffect(Unit) {
                    try {
                        val ok = llmClient.healthCheck()
                        llmStatus = if (ok) "LIVE" else "DARK"
                    } catch (e: Exception) {
                        llmStatus = "DARK"
                    }
                }

                fun appendTerminalLines(lines: List<String>) {
                    terminalLines = (terminalLines + lines).takeLast(200)
                    val historySnapshot = terminalLines
                    scope.launch(Dispatchers.IO) { saveTerminalHistory(historySnapshot) }
                }

                fun runTerminalAction(action: TerminalAction) {
                    pendingTerminalAction = null
                    val workflowRef = pendingWorkflowStep
                    pendingWorkflowStep = null
                    val recoveryMove = pendingRecoveryMove
                    pendingRecoveryMove = null
                    isProcessing = true
                    scope.launch(Dispatchers.IO) {
                        val result = try {
                            terminalExecutor.execute(action.command, action.target.launcherIndex)
                        } catch (e: Exception) {
                            listOf("[ERROR] ${e.message ?: "Command failed"}")
                        }
                        // Every finished command is classified. Failures become a recovery episode
                        // so Lucifer can stop, investigate, research, and learn from the outcome.
                        val failure = try {
                            resilience.captureOutcome(ResilienceEngine.outcomeOf(action.target, action.command, result))
                        } catch (e: Exception) {
                            null
                        }
                        // The General's view of what actually ran. Summary only; output stays in the terminal.
                        ObservationBus.record(
                            this@MainActivity,
                            ObservationKind.TERMINAL,
                            "ran on ${action.target.displayName}: ${action.command.take(160)}",
                            detail = result.takeLast(6).joinToString("\n"),
                            source = "terminal",
                            severity = if (failure != null) 2 else 0,
                            tags = listOf(action.target.name.lowercase())
                        )
                        if (recoveryMove != null) {
                            resilience.recordAttempt(
                                recoveryMove,
                                succeeded = failure == null,
                                note = result.takeLast(12).joinToString("\n")
                            )
                        }
                        withContext(Dispatchers.Main) {
                            appendTerminalLines(listOf("${action.target.displayName} · ${terminalExecutor.destinationDetails(action.target.launcherIndex)} · ${action.command}") + result)
                            if (failure != null) {
                                appendTerminalLines(
                                    listOf(
                                        "[RECOVERY] ${failure.failureClass.label} captured on ${failure.target.displayName}.",
                                        "[RECOVERY] Open the Recover tab, or say \"figure out what's going on\" to run read-only probes and research."
                                    )
                                )
                            }
                            if (workflowRef != null) {
                                updateWorkflowTask(workflowRef.first) {
                                    WorkflowTransitions.recordOutcome(it, workflowRef.second, result.joinToString("\n"), executed = true)
                                }
                                workflowMessage = when {
                                    !workflowStoreHealthy -> "Command finished, but its result could not be saved; workflow actions are disabled. Check the terminal output."
                                    failure != null -> "Step output recorded with a failure. Open the Recover tab to investigate it, or reflect as-is."
                                    else -> "Step output recorded. Review it and request a reflection before continuing."
                                }
                            }
                            isProcessing = false
                        }
                    }
                }

                fun runWorkflowStep(taskId: String, stepId: String, instruction: String) {
                    if (workflowBusy || isProcessing || pendingTerminalAction != null || !workflowStoreHealthy) return
                    val task = workflowTasks.firstOrNull { it.id == taskId } ?: return
                    if (task.status == WorkflowTaskStatus.PAUSED || task.status == WorkflowTaskStatus.COMPLETE) return
                    val active = WorkflowTransitions.activate(task, stepId)
                    if (active == task) return
                    updateWorkflowTask(taskId) { active }
                    if (!workflowStoreHealthy) return
                    workflowBusy = true
                    workflowMessage = "Routing one step to ${task.target.displayName}. Any non-read-only terminal action will wait for confirmation."
                    scope.launch(Dispatchers.IO) {
                        val route = try {
                            router.understand(instruction, task.target, installedAppNames = installedAppNames())
                        } catch (_: Exception) {
                            ChatRoute.Clarify("I could not route this workflow step. Add context and retry; nothing was executed.")
                        }
                        when (route) {
                            ChatRoute.Conversation -> {
                                val answer = try { reasoner.reason(instruction, Surface.WORKFLOW) } catch (e: Exception) {
                                    "[WORKFLOW ERROR] ${e.message ?: "Could not reason about this step."}"
                                }
                                withContext(Dispatchers.Main) {
                                    updateWorkflowTask(taskId) { WorkflowTransitions.recordOutcome(it, stepId, answer, executed = true) }
                                    workflowBusy = false
                                    workflowMessage = if (workflowStoreHealthy) {
                                        "Step result recorded. Inspect it, then request a reflection before continuing."
                                    } else "Step result could not be saved; workflow actions are disabled."
                                }
                            }
                            is ChatRoute.Execute -> withContext(Dispatchers.Main) {
                                val action = route.action
                                if (action.target != task.target) {
                                    updateWorkflowTask(taskId) {
                                        WorkflowTransitions.recordOutcome(it, stepId, "Blocked: router selected ${action.target.displayName}, but this workflow is locked to ${task.target.displayName}. No command ran.", executed = false)
                                    }
                                    workflowBusy = false
                                    workflowMessage = "Blocked a target change; no terminal command ran. Edit the task plan or create a task for the intended target."
                                } else {
                                    pendingWorkflowStep = taskId to stepId
                                    workflowBusy = false
                                    if (action.requiresConfirmation) {
                                        pendingTerminalAction = action
                                        appendTerminalLines(listOf("[WORKFLOW CONFIRM] ${action.explanation}", "[${action.target.displayName}] ${action.command}", "This action came from one workflow step. Review the exact target and command."))
                                    } else {
                                        runTerminalAction(action)
                                    }
                                }
                            }
                            is ChatRoute.Clarify -> withContext(Dispatchers.Main) {
                                updateWorkflowTask(taskId) { WorkflowTransitions.recordOutcome(it, stepId, "Needs clarification: ${route.question}", executed = false) }
                                workflowBusy = false
                                workflowMessage = "Nothing ran. Add the missing context to the step and retry."
                            }
                            is ChatRoute.Resilience -> {
                                val output = try {
                                    when (route.request) {
                                        ResilienceRequest.INVESTIGATE -> {
                                            val signal = resilience.state.value.signal
                                            if (signal == null) {
                                                "No failure captured yet. Run the failing step first so there is something to investigate."
                                            } else {
                                                val bundle = resilience.investigate()
                                                resilience.diagnose()
                                                resilience.plan()
                                                buildString {
                                                    append("Read-only investigation of ${signal.failureClass.label} on ${signal.target.displayName}.\n")
                                                    bundle?.highlights()?.forEach { append("- ").append(it).append('\n') }
                                                    val diagnosis = resilience.state.value.diagnosis
                                                    if (diagnosis.isNotBlank()) append(diagnosis)
                                                }.trim()
                                            }
                                        }
                                        ResilienceRequest.RESEARCH ->
                                            resilience.research(route.query.ifBlank { null })?.render()
                                                ?: "No public sources were retrievable for this step."
                                        ResilienceRequest.LESSONS -> resilience.lessonOverview()
                                        ResilienceRequest.PLAN ->
                                            resilience.plan().joinToString("\n") { "- ${it.title}: ${it.instruction}" }
                                                .ifBlank { "No untried tactic remains; new context is needed." }
                                    }
                                } catch (e: Exception) {
                                    "Recovery step failed: ${e.message ?: "unknown error"}"
                                }
                                withContext(Dispatchers.Main) {
                                    updateWorkflowTask(taskId) { WorkflowTransitions.recordOutcome(it, stepId, output, executed = true) }
                                    workflowBusy = false
                                    workflowMessage = "Recovery step recorded. Inspect the evidence, then reflect before continuing."
                                }
                            }
                            else -> withContext(Dispatchers.Main) {
                                updateWorkflowTask(taskId) {
                                    WorkflowTransitions.recordOutcome(it, stepId, "This step resolved to a panel/app/settings action, which the workflow runner does not launch. Use the corresponding Lucifer UI, then add its observed result and retry/review.", executed = false)
                                }
                                workflowBusy = false
                                workflowMessage = "Workflow steps can route to chat or a terminal command only; other controls remain manual."
                            }
                        }
                    }
                }

                fun cancelPendingTerminalAction() {
                    pendingTerminalAction = null
                    pendingRecoveryMove = null
                    val workflowRef = pendingWorkflowStep
                    pendingWorkflowStep = null
                    if (workflowRef != null) {
                        updateWorkflowTask(workflowRef.first) {
                            WorkflowTransitions.recordOutcome(it, workflowRef.second, "User cancelled confirmation; command was not run.", executed = false)
                        }
                        workflowMessage = "Cancelled before execution. No terminal command was run."
                    }
                    appendTerminalLines(listOf("[CANCELLED] Command was not run."))
                }


                /**
                 * Recovery, research, and lesson requests from chat. Read-only by construction:
                 * probes come from a fixed catalog and web research only performs GET requests.
                 */
                suspend fun resilienceLines(request: ResilienceRequest, query: String): List<String> {
                    val out = mutableListOf<String>()
                    val current = resilience.state.value.signal
                    when (request) {
                        ResilienceRequest.INVESTIGATE -> {
                            if (current == null) {
                                out += "[RECOVER] No failure captured yet. Run a failing command first, or ask me to research something."
                                return out
                            }
                            out += "[RECOVER] ${current.failureClass.label} on ${current.target.displayName}. Running the fixed read-only probe catalog only."
                            val bundle = resilience.investigate()
                            bundle?.highlights()?.forEach { out += "[EVIDENCE] $it" }
                            if (bundle != null && bundle.probeCount > 0) {
                                val diagnosis = resilience.diagnose()
                                if (diagnosis != null) {
                                    out += "[DIAGNOSIS · ${diagnosis.source.name.lowercase()} · ${diagnosis.confidence}] ${diagnosis.rootCause}"
                                    diagnosis.hypotheses.take(3).forEach { hypothesis ->
                                        out += "  ? ${hypothesis.statement} (${(hypothesis.confidence * 100).toInt()}%)"
                                    }
                                }
                            }
                            val moves = resilience.plan()
                            moves.forEachIndexed { index, move ->
                                val approval = if (move.safety == MoveSafety.REQUIRES_CONFIRMATION) ", needs approval" else ""
                                out += "[MOVE ${index + 1}] ${move.title} (${move.kind.label}$approval)"
                            }
                            out += "[RECOVER] Open the Recover tab to run a move, research the web, or record the outcome."
                        }
                        ResilienceRequest.RESEARCH -> {
                            out += "[RESEARCH] Read-only public web search; robots.txt, rate limits, and a page budget are enforced."
                            val brief = resilience.research(query.ifBlank { null })
                            if (brief == null) out += "[RESEARCH] Nothing to look up yet."
                            else out += brief.render().lines().take(24)
                        }
                        ResilienceRequest.LESSONS -> out += resilience.lessonOverview().lines()
                        ResilienceRequest.PLAN -> {
                            if (current == null) {
                                out += "[RECOVER] No active failure. Run something that fails first."
                                return out
                            }
                            val moves = resilience.plan()
                            if (moves.isEmpty()) {
                                out += "[RECOVER] Every known tactic has been tried. New context is needed."
                            } else {
                                out += "[PLAN] ${moves.size} move(s), cheapest first:"
                                moves.forEachIndexed { index, move ->
                                    out += "  ${index + 1}. ${move.title} — ${move.instruction}"
                                }
                            }
                        }
                    }
                    return out
                }

                fun requestHeartbeatPermissions(action: String) {
                    heartbeatPermissionAction = action
                    val permissions = mutableListOf<String>()
                    if (action == "camera") permissions.add(Manifest.permission.CAMERA)
                    if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    val missing = permissions.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        heartbeatPermissionLauncher.launch(missing.toTypedArray())
                    } else {
                        try {
                            if (action == "camera") LuciferHeartbeatService.enableCamera(this@MainActivity)
                            else LuciferHeartbeatService.start(this@MainActivity)
                            settingsToast = if (action == "camera") "Camera enabled for future heartbeat sessions." else "Heartbeat enabled. The first session is starting now."
                        } catch (e: Exception) {
                            settingsToast = "Could not start heartbeat: ${e.message ?: "system denied foreground service"}"
                        }
                    }
                }

                fun requestHeartbeatStart() = requestHeartbeatPermissions("start")
                fun requestHeartbeatCamera() = requestHeartbeatPermissions("camera")

                fun stopHeartbeat() {
                    LuciferHeartbeatService.stop(this@MainActivity)
                    settingsToast = "Heartbeat stopped."
                }

                // The General runs as a foreground service, so it needs the same notification grant.
                fun startTactician() {
                    try {
                        TacticianService.start(this@MainActivity)
                        settingsToast = "The General is watching. One bounded pass every ~60 minutes; stop it from the notification or this panel."
                    } catch (e: Exception) {
                        settingsToast = "Could not start the tactician: ${e.message ?: "system denied foreground service"}"
                    }
                }

                fun requestTacticianStart() {
                    val missing = if (Build.VERSION.SDK_INT >= 33) {
                        listOf(Manifest.permission.POST_NOTIFICATIONS).filter {
                            ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                        }
                    } else emptyList()
                    if (missing.isNotEmpty()) {
                        tacticianPermissionRequested = true
                        heartbeatPermissionLauncher.launch(missing.toTypedArray())
                    } else {
                        startTactician()
                    }
                }

                // ── Lucifer's world: privilege backends, the managed profile, and the audit trail ──
                val worldManager = remember { WorldManager(this@MainActivity) }
                val privilegeRouter = remember { PrivilegeRouter(this@MainActivity) }
                var worldState by remember { mutableStateOf(WorldUiState()) }

                fun publishWorld(message: String?, busy: Boolean = false) {
                    worldState = worldState.copy(
                        providers = privilegeRouter.states(),
                        summary = privilegeRouter.summary(),
                        ledger = PrivilegeLedger.snapshot(),
                        busy = busy,
                        message = message
                    )
                }

                fun refreshWorld(message: String? = worldState.message) {
                    worldState = worldState.copy(busy = true, message = message)
                    scope.launch(Dispatchers.IO) {
                        val providers = privilegeRouter.states()
                        val summary = ProviderPlan.summary(providers)
                        val snapshot = worldManager.snapshot()
                        withContext(Dispatchers.Main) {
                            worldState = WorldUiState(
                                providers = providers,
                                summary = summary,
                                snapshot = snapshot,
                                ledger = PrivilegeLedger.snapshot(),
                                busy = false,
                                message = message
                            )
                        }
                    }
                }

                fun performWorldAction(action: WorldAction) {
                    worldState = worldState.copy(busy = true)
                    scope.launch(Dispatchers.IO) {
                        val world = worldManager.snapshot().world
                        val outcome = when (action) {
                            WorldAction.CREATE_WORLD -> worldManager.createWorld()
                            WorldAction.OPEN_WORLD -> world?.let { worldManager.wakeWorld(it.id) }
                                ?: PrivilegeOutcome(false, Provider.NONE, "There is no world to wake yet.")
                            WorldAction.CLOSE_WORLD -> world?.let { worldManager.sleepWorld(it.id) }
                                ?: PrivilegeOutcome(false, Provider.NONE, "There is no world to put to sleep yet.")
                            WorldAction.START_VM -> worldManager.startVm(world?.id)
                            WorldAction.OPEN_SETTINGS -> PrivilegeOutcome(
                                worldManager.openWorkProfileSettings(),
                                Provider.NONE,
                                "Opened the managed-profile settings."
                            )
                            else -> PrivilegeOutcome(false, Provider.NONE, "${action.label} needs no backend action.")
                        }
                        val providers = privilegeRouter.states()
                        val snapshot = worldManager.snapshot()
                        withContext(Dispatchers.Main) {
                            worldState = WorldUiState(
                                providers = providers,
                                summary = ProviderPlan.summary(providers),
                                snapshot = snapshot,
                                ledger = PrivilegeLedger.snapshot(),
                                busy = false,
                                message = outcome.headline
                            )
                            settingsToast = outcome.headline
                        }
                    }
                }

                fun runWorldAction(action: WorldAction) {
                    if (WorldCharter.requiresConfirmation(action) || action == WorldAction.CREATE_WORLD) {
                        val shown = BiometricGate.require(
                            this@MainActivity,
                            title = "Confirm ${action.label.lowercase()}",
                            subtitle = "This changes Lucifer's world on this device.",
                            onDenied = { reason -> settingsToast = "${action.label} cancelled — $reason" },
                            onConfirmed = { performWorldAction(action) }
                        )
                        if (shown) return
                    }
                    performWorldAction(action)
                }

                fun launchInWorld(pkg: String) {
                    if (!PrivilegeCommands.validPackage(pkg)) {
                        settingsToast = "Enter a valid Android package name to launch inside the world."
                        return
                    }
                    scope.launch(Dispatchers.IO) {
                        val world = worldManager.snapshot().world
                        val outcome = if (world == null) {
                            PrivilegeOutcome(false, Provider.NONE, "Create or find a managed profile first.")
                        } else {
                            worldManager.launchInWorld(pkg, world.id)
                        }
                        withContext(Dispatchers.Main) { settingsToast = outcome.headline }
                    }
                }

                fun runOwnerOp(op: PrivilegeOp) {
                    // An owner call changes another app on this device. The dialog asked "do it?"; this
                    // asks "are you the owner?". Both, or neither.
                    val proceed = {
                        scope.launch(Dispatchers.IO) {
                            val outcome = privilegeRouter.run(op, confirmed = true)
                            withContext(Dispatchers.Main) {
                                publishWorld(outcome.headline)
                                settingsToast = outcome.headline
                            }
                        }
                    }
                    val shown = BiometricGate.require(
                        this@MainActivity,
                        title = "Confirm owner action",
                        subtitle = op.label,
                        onDenied = { reason ->
                            settingsToast = "Owner action cancelled — $reason"
                            publishWorld("Owner action cancelled: $reason")
                        },
                        onConfirmed = { proceed() }
                    )
                    if (!shown) {
                        // No sensor and no device lock: say so once, then let the confirmation stand.
                        settingsToast = "No biometric gate on this device · the confirmation dialog was the only check."
                        proceed()
                    }
                }

                // ── the Lean verifier Lucifer hosts in the world ──
                var verifierState by remember { mutableStateOf(VerifierUiState()) }

                fun refreshVerifier(
                    message: String? = verifierState.message,
                    issuedToken: String? = verifierState.issuedToken,
                    vaultSecret: String? = verifierState.vaultSecret,
                    busy: Boolean = false
                ) {
                    verifierState = verifierState.copy(busy = true)
                    scope.launch(Dispatchers.IO) {
                        val state = VerifierController.state(
                            context = this@MainActivity,
                            message = message,
                            busy = busy,
                            issuedToken = issuedToken,
                            vaultSecret = vaultSecret
                        )
                        withContext(Dispatchers.Main) { verifierState = state }
                    }
                }

                fun runVerifier(command: VerifierUiCommand) {
                    when (command) {
                        VerifierUiCommand.Start -> {
                            runCatching { VerifierController.start(this@MainActivity) }
                                .onFailure {
                                    settingsToast = "Could not start the verifier: ${it.message ?: "foreground service denied"}"
                                }
                            refreshVerifier(
                                message = "Starting the endpoint on 127.0.0.1:${VerifierController.PORT}…",
                                issuedToken = null
                            )
                        }
                        VerifierUiCommand.Stop -> {
                            VerifierController.stop(this@MainActivity)
                            refreshVerifier("The verifier stopped listening.", issuedToken = null)
                        }
                        VerifierUiCommand.Refresh -> refreshVerifier()
                        VerifierUiCommand.IssueInternalKey -> {
                            val issued = runCatching {
                                VerifierController.issueInternalKey(this@MainActivity)
                            }.getOrNull()
                            refreshVerifier(
                                message = if (issued == null) "An internal key is already active."
                                else "Internal key issued. It is shown once and never again.",
                                issuedToken = issued?.token
                            )
                        }
                        is VerifierUiCommand.IssueKey -> {
                            val issued = VerifierController.issueKey(this@MainActivity, command.plan)
                            refreshVerifier(
                                message = "${command.plan.label} key issued: " +
                                    "${command.plan.creditsPerPeriod} credits per 30 days.",
                                issuedToken = issued.token
                            )
                        }
                        is VerifierUiCommand.RevokeKey -> {
                            val revoked = VerifierController.revokeKey(this@MainActivity, command.keyId)
                            refreshVerifier(
                                message = if (revoked) "Key ${command.keyId} revoked."
                                else "That key was already revoked.",
                                issuedToken = null
                            )
                        }
                        is VerifierUiCommand.LinkVault -> {
                            scope.launch(Dispatchers.IO) {
                                val linked = VerifierController.linkVault(this@MainActivity, command.url)
                                withContext(Dispatchers.Main) {
                                    refreshVerifier(
                                        message = if (linked == null) {
                                            "That is not a vault URL this phone will sign for. Use https, or http on loopback/private."
                                        } else {
                                            "Vault linked. Paste the secret below into audit-vault once; it is shown once."
                                        },
                                        vaultSecret = linked?.secret,
                                        issuedToken = null
                                    )
                                }
                            }
                        }
                        VerifierUiCommand.UnlinkVault -> {
                            VerifierController.unlinkVault(this@MainActivity)
                            refreshVerifier(
                                message = "Vault unlinked. Jobs run on the Debian VM again.",
                                vaultSecret = null,
                                issuedToken = null
                            )
                        }
                        is VerifierUiCommand.SetProjectRoot -> {
                            val updated = VerifierController.setProjectRoot(this@MainActivity, command.root)
                            refreshVerifier(
                                message = if (updated) "Project root updated."
                                else "That is not an absolute checkout path this server can use.",
                                issuedToken = null
                            )
                        }
                    }
                }

                var selectedTab by remember { mutableStateOf(intent?.getIntExtra("lucifer_tab", 0) ?: 0) }

                LaunchedEffect(selectedTab) {
                    if (selectedTab == 10) {
                        refreshWorld(null)
                        refreshVerifier(message = null, issuedToken = null, vaultSecret = null)
                    }
                }

                LaunchedEffect(selectedTab) {
                    if (selectedTab == 3) {
                        while (true) {
                            shizukuStatus = withContext(Dispatchers.IO) { ShizukuAccess.status() }
                            delay(1_500)
                        }
                    }
                }

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
                                    TabItem("Settings", Icons.Filled.Settings),
                                    TabItem("Wallet", Icons.Filled.AccountBalanceWallet),
                                    TabItem("Browser", Icons.Filled.Language),
                                    TabItem("Heartbeat", Icons.Filled.Psychology),
                                    TabItem("Work", Icons.Filled.Build),
                                    TabItem("Recover", Icons.Filled.Autorenew),
                                    TabItem("Agents", Icons.Filled.Face),
                                    TabItem("World", Icons.Filled.Public)
                                )
                            )

                            // The Fall: every panel switch enters from above with weight, never a cut.
                            androidx.compose.runtime.key(selectedTab) {
                                FallIn(modifier = Modifier.fillMaxSize()) {
                                    when (selectedTab) {
                                        0 -> AnimatedTerminal(
                                            modifier = Modifier.fillMaxSize(),
                                            outputLines = terminalLines,
                                            isProcessing = isProcessing,
                                            onStop = {
                                                activeJob?.cancel()
                                                activeJob = null
                                                isProcessing = false
                                                appendTerminalLines(
                                                    listOf("[STOPPED] Cancelled. Whatever it had already done stands; nothing further will run.")
                                                )
                                            },
                                            target = terminalTarget,
                                            onTargetSelected = { terminalTarget = it },
                                            onOpenInteractiveTermux = {
                                                val message = launchTermuxInteractive(installToolkit = false)
                                                settingsToast = message
                                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                            },
                                            onInstallTermuxTools = { showTermuxToolkitDialog = true },
                                            onOpenAndroidLinux = {
                                                val message = openAndroidLinuxWithOverlay()
                                                settingsToast = message
                                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                            },
                                            onStageStaticSite = {
                                                runLocalSiteOperation("Creating the local static-site starter…") {
                                                    LocalWebsiteManager.stageStatic(this@MainActivity)
                                                }
                                            },
                                            onStageApiSite = {
                                                runLocalSiteOperation("Creating the local website + API starter…") {
                                                    LocalWebsiteManager.stageApi(this@MainActivity)
                                                }
                                            },
                                            onStagePaymentDemo = {
                                                runLocalSiteOperation("Creating the no-money USDC checkout demo…") {
                                                    LocalWebsiteManager.stagePaymentDemo(this@MainActivity)
                                                }
                                            },
                                            onStopLocalSites = {
                                                runLocalSiteOperation("Stopping local preview servers…") {
                                                    LocalWebsiteManager.stop(this@MainActivity)
                                                }
                                            },
                                            onCommand = { cmd ->
                                                if (!isProcessing && pendingTerminalAction == null) {
                                                    isProcessing = true
                                                    appendTerminalLines(listOf("λ $cmd"))
                                                    val selectedTarget = CommandTarget.fromLauncherIndex(terminalTarget)
                                                    activeJob = scope.launch(Dispatchers.IO) {
                                                        // Judge this message against the last answer before
                                                        // anything new is produced, off the main thread.
                                                        SkillSynthRuntime.observeIncoming(this@MainActivity, cmd, Surface.CORE_CHAT.name)
                                                        val route = try {
                                                            router.understand(cmd, selectedTarget, installedAppNames = installedAppNames())
                                                        } catch (_: Exception) {
                                                            ChatRoute.Clarify("I couldn't route that request. Rephrase it or choose LOCAL, TERMUX, VM, or SHIZUKU.")
                                                        }
                                                        when (route) {
                                                            ChatRoute.Conversation -> {
                                                                val answer = try {
                                                                    reasoner.reason(
                                                                        cmd,
                                                                        Surface.CORE_CHAT,
                                                                        // One line per tool call, as it happens: a ninety-second
                                                                        // silence reads as a hang, and the trace is the honest
                                                                        // answer to "what is it doing".
                                                                        onStep = { record ->
                                                                            if (record.call != null) {
                                                                                scope.launch(Dispatchers.Main) {
                                                                                    appendTerminalLines(listOf("[THINK] " + record.brief()))
                                                                                }
                                                                            }
                                                                        }
                                                                    )
                                                                } catch (e: Exception) {
                                                                    "[CHAT ERROR] ${e.message ?: "Could not answer"}"
                                                                }
                                                                SkillSynthRuntime.recordAnswer(this@MainActivity, cmd, answer, Surface.CORE_CHAT.name)
                                                                val run = reasoner.lastCognition
                                                                withContext(Dispatchers.Main) {
                                                                    appendTerminalLines(listOf("[CHAT] $answer"))
                                                                    if (run != null) {
                                                                        cognitionSummary = run.summaryLine()
                                                                        cognitionTrace = run.traceText()
                                                                    }
                                                                    // Read the ledger off the main thread: it
                                                                    // is a file, and this runs per answer.
                                                                    scope.launch(Dispatchers.IO) {
                                                                        val line = com.lucifer.mobile.cognition.CognitionLedger
                                                                            .summary(this@MainActivity)
                                                                        withContext(Dispatchers.Main) { cognitionHistory = line }
                                                                    }
                                                                    isProcessing = false
                                                                }
                                                            }
                                                            is ChatRoute.Clarify -> withContext(Dispatchers.Main) {
                                                                appendTerminalLines(listOf("[NEED CLARIFICATION] ${route.question}"))
                                                                isProcessing = false
                                                            }
                                                            is ChatRoute.OpenApp -> withContext(Dispatchers.Main) {
                                                                appendTerminalLines(listOf(launchRequestedApp(route.appName)))
                                                                isProcessing = false
                                                            }
                                                            is ChatRoute.OpenSettings -> withContext(Dispatchers.Main) {
                                                                val opened = launchSystemSettings(route.section)
                                                                appendTerminalLines(listOf(if (opened) "[SETTINGS] Opened ${route.section}; make protected changes in Android Settings." else "[SETTINGS] Could not open that Settings page."))
                                                                isProcessing = false
                                                            }
                                                            is ChatRoute.OpenPanel -> withContext(Dispatchers.Main) {
                                                                selectedTab = when (route.panel) {
                                                                    "terminal" -> 0
                                                                    "memory" -> 1
                                                                    "skills" -> 2
                                                                    "settings" -> 3
                                                                    "wallet" -> 4
                                                                    "browser" -> 5
                                                                    "heartbeat" -> 6
                                                                    "agents", "general", "tactician" -> 9
                                                                    "world", "dhizuku", "privilege" -> 10
                                                                    "workflow", "workflows" -> 7
                                                                    "recover", "recovery" -> 8
                                                                    else -> 0
                                                                }
                                                                appendTerminalLines(listOf("[PANEL] Opening Lucifer's ${route.panel} panel."))
                                                                isProcessing = false
                                                            }
                                                            is ChatRoute.Execute -> withContext(Dispatchers.Main) {
                                                                val action = route.action
                                                                if (action.requiresConfirmation) {
                                                                    pendingTerminalAction = action
                                                                    appendTerminalLines(listOf(
                                                                        "[CONFIRM] ${action.explanation}",
                                                                        "[${action.target.displayName}] ${action.command}",
                                                                        "Review and confirm before running."
                                                                    ))
                                                                    isProcessing = false
                                                                } else {
                                                                    appendTerminalLines(listOf("[UNDERSTOOD] ${action.explanation}"))
                                                                    runTerminalAction(action)
                                                                }
                                                            }
                                                            is ChatRoute.Resilience -> {
                                                                val lines = try {
                                                                    resilienceLines(route.request, route.query)
                                                                } catch (e: Exception) {
                                                                    listOf("[RECOVER ERROR] ${e.message ?: "could not complete the recovery request"}")
                                                                }
                                                                withContext(Dispatchers.Main) {
                                                                    appendTerminalLines(lines)
                                                                    isProcessing = false
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                        1 -> AnimatedMemoryPanel(
                                            modifier = Modifier.fillMaxSize(),
                                            memories = memoryItems,
                                            onSearch = { q ->
                                                if (!memorySearching) {
                                                    memorySearching = true
                                                    scope.launch(Dispatchers.IO) {
                                                        val hits = searchMemories(q)
                                                        withContext(Dispatchers.Main) {
                                                            memoryItems = hits
                                                            memorySearching = false
                                                        }
                                                    }
                                                }
                                            },
                                            isSearching = memorySearching
                                        )
                                        2 -> AnimatedSkillsPanel(
                                            modifier = Modifier.fillMaxSize(),
                                            skills = skills,
                                            library = referenceSkillLibrary,
                                            cognitionSummary = cognitionSummary,
                                            cognitionTrace = cognitionTrace,
                                            cognitionHistory = cognitionHistory,
                                            synthSummary = synthSummary,
                                            synthEnabled = synthEnabled,
                                            onToggleSynth = {
                                                val turnOn = !synthEnabled
                                                scope.launch(Dispatchers.IO) {
                                                    SkillSynthRuntime.setEnabled(this@MainActivity, turnOn)
                                                    withContext(Dispatchers.Main) { synthRevision++ }
                                                }
                                            },
                                            onForgetLearned = {
                                                scope.launch(Dispatchers.IO) {
                                                    SkillSynthRuntime.forgetAll(this@MainActivity)
                                                    withContext(Dispatchers.Main) { synthRevision++ }
                                                }
                                            }
                                        )
                                        3 -> AnimatedSettingsPanel(
                                            modifier = Modifier.fillMaxSize(),
                                            onSoulMdEdit = { saveSoulPrompt(it) },
                                            onAgentPanelOpen = { selectedTab = 9 },
                                            onWorldPanelOpen = { selectedTab = 10 },
                                            onManageWallets = { selectedTab = 4 },
                                            onHeartbeatOpen = { selectedTab = 6 },
                                            biometricStatus = biometricStatus,
                                            onExportBundle = {
                                                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                                                    .format(java.util.Date())
                                                exportLauncher.launch("lucifer-export-$stamp.json")
                                            },
                                            shizukuStatus = shizukuStatus.message,
                                            onShizukuRequest = {
                                                settingsToast = if (ShizukuAccess.requestPermission()) {
                                                    "Approve Lucifer in the Shizuku permission prompt."
                                                } else {
                                                    "Start the Shizuku server first, then retry."
                                                }
                                            },
                                            onOpenShizuku = {
                                                settingsToast = if (ShizukuAccess.openShizuku(this@MainActivity)) "Opened Shizuku. Start its Wireless debugging service, then return here." else "Shizuku app not found. Install and configure it first."
                                            },
                                            onOpenPermissionManagerX = {
                                                settingsToast = if (ShizukuAccess.openPermissionManagerX(this@MainActivity)) "Opened Permission Manager X. Manage grants there; Lucifer will not change them." else "Permission Manager X is not installed or has no launch activity."
                                            },
                                            onShizukuTest = {
                                                scope.launch(Dispatchers.IO) {
                                                    val result = ShizukuTerminal.execute(this@MainActivity, "id")
                                                    withContext(Dispatchers.Main) { settingsToast = result.joinToString(" ").take(300) }
                                                }
                                            },
                                            onShizukuAudit = { packageName ->
                                                if (!shizukuAuditBusy) {
                                                    shizukuAuditBusy = true
                                                    shizukuAuditOutput = "Running read-only package and AppOps inspection…"
                                                    scope.launch(Dispatchers.IO) {
                                                        val result = try {
                                                            ShizukuTerminal.inspectPackageReadOnly(this@MainActivity, packageName)
                                                        } catch (e: Exception) {
                                                            listOf("[ERROR] ${e.message ?: "Read-only package inspection failed."}")
                                                        }
                                                        withContext(Dispatchers.Main) {
                                                            shizukuAuditOutput = result.joinToString("\n").take(12_000)
                                                            shizukuAuditBusy = false
                                                            settingsToast = "Read-only audit finished for $packageName; results remain local to this screen."
                                                        }
                                                    }
                                                }
                                            },
                                            shizukuAuditOutput = shizukuAuditOutput,
                                            shizukuAuditBusy = shizukuAuditBusy,
                                            onKaiHealthCheck = {
                                                scope.launch(Dispatchers.IO) {
                                                    val connected = KaiBridge(this@MainActivity).connect()
                                                    withContext(Dispatchers.Main) {
                                                        settingsToast = if (connected) "Kai sandbox responds to ping." else "Kai sandbox unavailable — install the Kai package and its sandbox first."
                                                    }
                                                }
                                            },
                                            onVmPasswordSave = { saveVmPassword(it) },
                                            onVmUserSave = { user ->
                                                if (saveVmUser(user)) { savedVmUser = user.trim(); settingsToast = "VM SSH user saved." }
                                                else settingsToast = "Invalid SSH username."
                                            },
                                            onVmPortSave = { port ->
                                                if (saveVmPort(port)) { savedVmPort = port.trim().toInt(); settingsToast = "VM SSH port saved." }
                                                else settingsToast = "Port must be between 1 and 65535."
                                            },
                                            onVmHostSave = { host ->
                                                if (saveVmHost(host)) {
                                                    savedVmHost = host.trim()
                                                    settingsToast = "VM host saved."
                                                } else {
                                                    settingsToast = "Invalid VM hostname/IP; use a host only, without a URL or port."
                                                }
                                            },
                                            onVmKnownHostsSave = { knownHosts ->
                                                val saved = saveVmKnownHosts(knownHosts)
                                                if (saved) savedVmKnownHosts = knownHosts.trim()
                                                settingsToast = when {
                                                    !saved -> "Invalid known_hosts entry; expected a host, key type, and base64 public key."
                                                    knownHosts.isBlank() -> "SSH host key cleared; VM execution is now disabled."
                                                    else -> "SSH known_hosts saved. Lucifer will require an exact host-key match."
                                                }
                                            },
                                            onNvidiaKeySave = { key ->
                                                scope.launch(Dispatchers.IO) {
                                                    val saved = llmClient.saveRuntimeKey(key)
                                                    val ok = if (saved && key.isNotBlank()) llmClient.healthCheck() else false
                                                    withContext(Dispatchers.Main) {
                                                        llmStatus = when {
                                                            !saved -> "INVALID KEY"
                                                            key.isBlank() -> "NO KEY"
                                                            ok -> "LIVE"
                                                            else -> "DARK"
                                                        }
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
                                            savedMemoryMcpUrl = savedMemoryMcpUrl,
                                            memoryMcpStatus = memoryMcpStatus,
                                            onMemoryMcpTest = {
                                                scope.launch(Dispatchers.IO) {
                                                    val status = DriveMemoryMcpClient(this@MainActivity).testConnection()
                                                    withContext(Dispatchers.Main) {
                                                        memoryMcpStatus = if (status.startsWith("No valid") || status.startsWith("MCP check failed") || status.startsWith("MCP URL") || status.startsWith("MCP bearer")) "NOT CONNECTED · $status" else "CONNECTED · READ ONLY"
                                                        settingsToast = status.take(500)
                                                    }
                                                }
                                            },
                                            memoryMcpTokenConfigured = memoryMcpTokenConfigured,
                                            onMemoryMcpSave = { url, token ->
                                                val client = DriveMemoryMcpClient(this@MainActivity)
                                                val normalized = client.validateLoopbackEndpoint(url.trim())
                                                when {
                                                    url.isBlank() -> settingsToast = "Enter the local Termux MCP URL, or use Remove to disable memory retrieval."
                                                    normalized == null -> settingsToast = "MCP URL must be http://127.0.0.1:8031/mcp or http://localhost:8031/mcp."
                                                    token.isNotBlank() && token.length < 24 -> settingsToast = "MCP token must contain at least 24 characters."
                                                    token.isNotBlank() && !SecretStore.write(this@MainActivity, AppConfig.MEMORY_MCP_TOKEN, token.trim()) -> settingsToast = "Could not encrypt the MCP token in Android Keystore storage."
                                                    !token.isNotBlank() && !memoryMcpTokenConfigured -> settingsToast = "Paste the bearer token printed once when the Termux server was configured."
                                                    else -> {
                                                        if (!saveMemoryMcpUrl(normalized)) {
                                                            settingsToast = "Could not save the MCP endpoint in app-private storage."
                                                        } else {
                                                            savedMemoryMcpUrl = normalized
                                                            if (token.isNotBlank()) memoryMcpTokenConfigured = true
                                                            memoryMcpStatus = "CONFIGURED · NOT TESTED"
                                                            settingsToast = "Drive-memory retrieval configured for the same-device Termux MCP server. Termux:Boot/runit can restart it after setup; retrieval fails open if unavailable."
                                                        }
                                                    }
                                                }
                                            },
                                            onMemoryMcpRemove = {
                                                saveMemoryMcpUrl("")
                                                SecretStore.delete(this@MainActivity, AppConfig.MEMORY_MCP_TOKEN)
                                                savedMemoryMcpUrl = ""
                                                memoryMcpTokenConfigured = false
                                                memoryMcpStatus = "DISABLED"
                                                settingsToast = "Drive-memory retrieval disabled and its local bearer token removed."
                                            },
                                            localMemoryEnabled = localMemoryEnabled,
                                            localMemoryCount = localMemoryCount,
                                            localMemoryBytes = localMemoryBytes,
                                            onLocalMemoryToggle = { enabled ->
                                                getSharedPreferences(LocalConversationMemory.PREFS, MODE_PRIVATE).edit()
                                                    .putBoolean(LocalConversationMemory.KEY_ENABLED, enabled).apply()
                                                localMemoryEnabled = enabled
                                                settingsToast = if (enabled) "Local episodic memory saving resumed." else "Local memory saving paused; existing entries remain until expiry or deletion."
                                            },
                                            onLocalMemoryClear = {
                                                scope.launch(Dispatchers.IO) {
                                                    localMemoryStore.clearAll()
                                                    val count = localMemoryStore.count()
                                                    val bytes = localMemoryStore.storageBytes()
                                                    withContext(Dispatchers.Main) {
                                                        localMemoryCount = count
                                                        localMemoryBytes = bytes
                                                        settingsToast = "Local episodic memories deleted. This does not change the private Drive file."
                                                    }
                                                }
                                            },
                                            voiceEnabled = voiceEnabled,
                                            knowledgeStatus = if (reasoner.knowledgeServerAlive()) "EMBEDDED" else "…",
                                            corpusInfo = "54 Lean volumes · axioms · book (in-APK)",
                                            savedKnowledgeUrl = savedKnowledgeUrl,
                                            llmStatus = llmStatus,
                                            savedVmHost = savedVmHost,
                                            savedVmUser = savedVmUser,
                                            savedVmPort = savedVmPort,
                                            savedVmKnownHosts = savedVmKnownHosts,
                                            leanVerificationState = leanVerificationState,
                                            leanVerificationOutput = leanVerificationOutput,
                                            leanVerificationCommit = leanVerificationCommit,
                                            leanVerificationToolchain = leanVerificationToolchain,
                                            leanVerificationBusy = leanVerificationBusy,
                                            onLeanVerificationPrepare = {
                                                runLeanVerification { Lean4VerificationRunner.prepare(this@MainActivity) }
                                            },
                                            onLeanVerificationStart = {
                                                if (leanVerificationState == "PREPARED" && leanVerificationCommit != null && leanVerificationToolchain != null) {
                                                    showLeanVerificationConfirmation = true
                                                }
                                            },
                                            onLeanVerificationRefresh = {
                                                runLeanVerification { Lean4VerificationRunner.refresh(this@MainActivity) }
                                            },
                                            onLeanVerificationStop = {
                                                runLeanVerification { Lean4VerificationRunner.stop(this@MainActivity) }
                                            },
                                            statusMessage = settingsToast
                                        )
                                        4 -> WalletControlPanel(this@MainActivity, Modifier.fillMaxSize())
                                        5 -> BrowserControlPanel(this@MainActivity, Modifier.fillMaxSize())
                                        6 -> HeartbeatControlPanel(
                                            context = this@MainActivity,
                                            modifier = Modifier.fillMaxSize(),
                                            statusMessage = settingsToast,
                                            onStart = { requestHeartbeatStart() },
                                            onStop = { stopHeartbeat() },
                                            onEnableCamera = { requestHeartbeatCamera() }
                                        )
                                        7 -> WorkflowControlPanel(
                                            modifier = Modifier.fillMaxSize(),
                                            tasks = workflowTasks,
                                            selectedTaskId = selectedWorkflowTaskId,
                                            busy = workflowBusy || isProcessing || pendingTerminalAction != null || !workflowStoreHealthy,
                                            statusMessage = workflowMessage,
                                            onSelectTask = { selectedWorkflowTaskId = it },
                                            onCreateTask = { blueprintId, goal, target -> createWorkflowTask(blueprintId, goal, target) },
                                            onRunStep = { taskId, stepId, instruction -> runWorkflowStep(taskId, stepId, instruction) },
                                            onRecordObservation = { taskId, stepId, observation ->
                                                updateWorkflowTask(taskId) { WorkflowTransitions.recordObservation(it, stepId, observation) }
                                                workflowMessage = if (workflowStoreHealthy) "User observation recorded. Reflect on the updated evidence before continuing."
                                                else "Observation could not be saved; workflow actions are disabled."
                                            },
                                            onReflect = { reflectWorkflowTask(it) },
                                            onAcceptReview = { taskId ->
                                                updateWorkflowTask(taskId) { WorkflowTransitions.acceptReview(it) }
                                                workflowMessage = "Review accepted. Inspect the next suggested step, or explicitly complete the task."
                                            },
                                            onRetryStep = { taskId, stepId ->
                                                updateWorkflowTask(taskId) { WorkflowTransitions.retry(it, stepId) }
                                                workflowMessage = "Step reset for another user-started attempt."
                                            },
                                            onPauseResume = { taskId ->
                                                updateWorkflowTask(taskId) { WorkflowTransitions.pauseResume(it) }
                                                workflowMessage = "Workflow state updated. No background work is running."
                                            },
                                            onComplete = { taskId ->
                                                updateWorkflowTask(taskId) { WorkflowTransitions.complete(it) }
                                                workflowMessage = "Task completion recorded after user review."
                                            },
                                            onDelete = { taskId ->
                                                saveWorkflowTasks(workflowTasks.filterNot { it.id == taskId })
                                                workflowMessage = "Workflow task deleted from local app storage."
                                            }
                                        )
                                        8 -> {
                                            val lessonsText = remember(resilienceState.lessonCount, resilienceState.lastUpdated) {
                                                resilience.lessonOverview()
                                            }
                                            RecoveryControlPanel(
                                                modifier = Modifier.fillMaxSize(),
                                                state = resilienceState,
                                                lessonsOverview = lessonsText,
                                                onInvestigate = {
                                                    scope.launch(Dispatchers.IO) {
                                                        try {
                                                            resilience.investigate()
                                                            resilience.diagnose()
                                                            resilience.plan()
                                                        } catch (_: Exception) {
                                                            // The panel surfaces the engine's own status text.
                                                        }
                                                    }
                                                },
                                                onDiagnose = {
                                                    scope.launch(Dispatchers.IO) {
                                                        try {
                                                            resilience.diagnose()
                                                            resilience.plan()
                                                        } catch (_: Exception) {
                                                        }
                                                    }
                                                },
                                                onResearch = { query ->
                                                    scope.launch(Dispatchers.IO) {
                                                        try {
                                                            resilience.research(query.ifBlank { null })
                                                        } catch (_: Exception) {
                                                        }
                                                    }
                                                },
                                                onPlan = {
                                                    scope.launch(Dispatchers.IO) {
                                                        try {
                                                            resilience.plan()
                                                        } catch (_: Exception) {
                                                        }
                                                    }
                                                },
                                                onRunMove = { move ->
                                                    if (!isProcessing && pendingTerminalAction == null) {
                                                        isProcessing = true
                                                        appendTerminalLines(listOf("[RECOVER] Working out how to try: ${move.title}"))
                                                        scope.launch(Dispatchers.IO) {
                                                            // Read-only moves are carried out directly: probes from the
                                                            // fixed catalog, or a robots-aware public web search.
                                                            val handled = when (move.kind) {
                                                                RecoveryKind.DIAGNOSE_ENVIRONMENT,
                                                                RecoveryKind.READ_LOGS,
                                                                RecoveryKind.VERIFY_PREREQUISITES -> {
                                                                    val lines = mutableListOf("[RECOVER] ${move.title}")
                                                                    val bundle = resilience.investigate()
                                                                    bundle?.highlights()?.forEach { lines += "[EVIDENCE] $it" }
                                                                    resilience.diagnose()?.let { diagnosis ->
                                                                        lines += "[DIAGNOSIS · ${diagnosis.source.name.lowercase()}] ${diagnosis.rootCause}"
                                                                    }
                                                                    resilience.plan()
                                                                    lines += "[RECOVER] Evidence refreshed — record the outcome only after a change you can observe."
                                                                    lines
                                                                }
                                                                RecoveryKind.WEB_RESEARCH -> {
                                                                    val brief = resilience.research()
                                                                    listOf("[RESEARCH] ${move.title}") + (
                                                                        brief?.render()?.lines()?.take(18)
                                                                            ?: listOf("No sources were retrievable.")
                                                                        )
                                                                }
                                                                else -> null
                                                            }
                                                            if (handled != null) {
                                                                withContext(Dispatchers.Main) {
                                                                    appendTerminalLines(handled)
                                                                    isProcessing = false
                                                                }
                                                                return@launch
                                                            }
                                                            val route = try {
                                                                router.understand(
                                                                    move.instruction,
                                                                    CommandTarget.fromLauncherIndex(terminalTarget),
                                                                    installedAppNames = installedAppNames()
                                                                )
                                                            } catch (_: Exception) {
                                                                ChatRoute.Clarify("I could not work out how to carry out that move. Add detail and replan.")
                                                            }
                                                            when (route) {
                                                                is ChatRoute.Execute -> withContext(Dispatchers.Main) {
                                                                    pendingRecoveryMove = move
                                                                    isProcessing = false
                                                                    val action = route.action
                                                                    if (action.requiresConfirmation) {
                                                                        pendingTerminalAction = action
                                                                        appendTerminalLines(
                                                                            listOf(
                                                                                "[RECOVER CONFIRM] ${action.explanation}",
                                                                                "[${action.target.displayName}] ${action.command}",
                                                                                "This move changes the system, so it needs your approval before it runs."
                                                                            )
                                                                        )
                                                                    } else {
                                                                        runTerminalAction(action)
                                                                    }
                                                                }
                                                                ChatRoute.Conversation -> {
                                                                    val advice = try {
                                                                        reasoner.reason(move.instruction, Surface.RECOVER)
                                                                    } catch (e: Exception) {
                                                                        "[CHAT ERROR] ${e.message ?: "could not produce advice"}"
                                                                    }
                                                                    withContext(Dispatchers.Main) {
                                                                        appendTerminalLines(
                                                                            listOf(
                                                                                "[RECOVER · ADVICE] ${move.title}",
                                                                                advice,
                                                                                "Carry it out yourself if it applies, then record the outcome in the Recover tab."
                                                                            )
                                                                        )
                                                                        isProcessing = false
                                                                    }
                                                                }
                                                                is ChatRoute.Clarify -> withContext(Dispatchers.Main) {
                                                                    appendTerminalLines(listOf("[RECOVER] ${route.question}"))
                                                                    isProcessing = false
                                                                }
                                                                else -> withContext(Dispatchers.Main) {
                                                                    appendTerminalLines(
                                                                        listOf("[RECOVER] That move needs a Lucifer panel action. Do it there, then record the outcome in Recover.")
                                                                    )
                                                                    isProcessing = false
                                                                }
                                                            }
                                                        }
                                                    }
                                                },
                                                onRecordOutcome = { succeeded -> resilience.recordManualOutcome(succeeded) },
                                                onReset = { resilience.reset() },
                                                onClearLessons = { resilience.clearLessons() },
                                                onForgetSignature = { resilience.forgetCurrentSignature() }
                                            )
                                        }
                                        9 -> AgentsPanel(
                                            modifier = Modifier.fillMaxSize(),
                                            statusMessage = settingsToast,
                                            onStartGeneral = { requestTacticianStart() },
                                            onStopGeneral = {
                                                TacticianService.stop(this@MainActivity)
                                                settingsToast = "The General stopped watching."
                                            },
                                            onNotify = { message -> settingsToast = message }
                                        )
                                        10 -> WorldPanel(
                                            modifier = Modifier.fillMaxSize(),
                                            state = worldState,
                                            onRefresh = { refreshWorld() },
                                            onConfirmAction = { action -> runWorldAction(action) },
                                            onConnectDhizuku = {
                                                val requested = DhizukuBridge.requestPermission(this@MainActivity)
                                                settingsToast = if (requested) {
                                                    "Approve Lucifer in the Dhizuku permission prompt, then refresh."
                                                } else if (DhizukuBridge.openApp(this@MainActivity)) {
                                                    "Approve Lucifer inside Dhizuku, then refresh this panel."
                                                } else {
                                                    "Dhizuku is not installed. Its device-owner powers are unavailable."
                                                }
                                            },
                                            onConnectShizuku = {
                                                settingsToast = if (ShizukuAccess.requestPermission()) {
                                                    "Approve Lucifer in the Shizuku permission prompt, then refresh."
                                                } else {
                                                    "Start Shizuku through Wireless debugging first, then retry."
                                                }
                                            },
                                            onLaunchInWorld = { pkg -> launchInWorld(pkg) },
                                            onOwnerOp = { op -> runOwnerOp(op) },
                                            verifier = verifierState,
                                            onVerifier = { command -> runVerifier(command) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (showLeanVerificationConfirmation) {
                        val confirmedCommit = leanVerificationCommit
                        val confirmedToolchain = leanVerificationToolchain
                        AlertDialog(
                            onDismissRequest = { showLeanVerificationConfirmation = false },
                            title = { Text("Run local Lean verification?") },
                            text = {
                                Text(
                                    "This will execute the repository's Python audit scripts and Lean/Lake build inside your Debian VM. Source SHA: ${confirmedCommit ?: "missing"}. Toolchain: ${confirmedToolchain ?: "missing"}. Network access may be used to obtain pinned dependencies. This checks formal artifacts, not whether their physical interpretation is true."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showLeanVerificationConfirmation = false
                                    val commit = confirmedCommit
                                    val toolchain = confirmedToolchain
                                    if (commit != null && toolchain != null) {
                                        runLeanVerification { Lean4VerificationRunner.start(this@MainActivity, commit, toolchain) }
                                    }
                                }) { Text("Run in Debian VM") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showLeanVerificationConfirmation = false }) { Text("Cancel") }
                            }
                        )
                    }
                    if (showTermuxToolkitDialog) {
                        AlertDialog(
                            onDismissRequest = { showTermuxToolkitDialog = false },
                            title = { Text("Install Termux toolkit?") },
                            text = {
                                Text("This downloads and installs a curated CLI toolkit in Termux (shell and tmux, build runtimes, Git, Python/Node/Rust/Go, search/edit tools, network diagnostics, media utilities, and proot-distro). Size and download time vary by repository and device. Lucifer will open a visible Termux session; no packages are installed silently.")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showTermuxToolkitDialog = false
                                    val message = launchTermuxInteractive(installToolkit = true)
                                    settingsToast = message
                                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                }) { Text("Install and open") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showTermuxToolkitDialog = false }) { Text("Cancel") }
                            }
                        )
                    }
                    pendingTerminalAction?.let { action ->
                        AlertDialog(
                            onDismissRequest = { cancelPendingTerminalAction() },
                            title = { Text("Confirm terminal command") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(action.explanation)
                                    Text(terminalExecutor.destinationDetails(action.target.launcherIndex))
                                    Text("${action.target.displayName} · ${action.command}")
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { runTerminalAction(action) }) { Text("Run") }
                            },
                            dismissButton = {
                                TextButton(onClick = { cancelPendingTerminalAction() }) { Text("Cancel") }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun launchTermuxInteractive(installToolkit: Boolean): String {
        if (!Settings.canDrawOverlays(this)) {
            return try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                "Allow Lucifer to display over other apps, return here, then tap OPEN PTY + CHAT again."
            } catch (error: Exception) {
                "Overlay permission is required: ${error.message ?: "Android denied the settings page"}"
            }
        }
        return try {
            FloatingChatService.start(this, CommandTarget.TERMUX.launcherIndex)
            if (TermuxInteractiveSession.launch(this, installToolkit)) {
                if (installToolkit) "Termux toolkit installation started in a visible interactive session. The Lucifer chat bubble stays on top."
                else "Opened persistent Termux tmux shell with Lucifer's floating chat."
            } else {
                stopService(Intent(this, FloatingChatService::class.java))
                "Could not start Termux. Install Termux, enable allow-external-apps, and grant Lucifer Run commands in Termux."
            }
        } catch (error: Exception) {
            stopService(Intent(this, FloatingChatService::class.java))
            "Could not launch the interactive terminal companion: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun openAndroidLinuxWithOverlay(): String {
        if (!Settings.canDrawOverlays(this)) {
            return try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                "Allow Lucifer to display over other apps, return here, then open Android Linux again."
            } catch (error: Exception) {
                "Overlay permission is required: ${error.message ?: "Android denied the settings page"}"
            }
        }
        val launchIntent = packageManager.getLaunchIntentForPackage("com.android.virtualization.terminal")
        if (launchIntent == null) {
            return try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                "Enable Android's Linux development environment in Developer options, finish its setup, then return."
            } catch (_: Exception) {
                "Android Linux Terminal is not enabled or supported on this device."
            }
        }
        return try {
            FloatingChatService.start(this, CommandTarget.VM.launcherIndex)
            startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened Android Linux Terminal with chat overlay. To share its live shell, enable guest SSH, forward a host port to guest port 22 in Terminal settings, configure the SSH user/port/password and verified host key here, then start `tmux new-session -A -s lucifer` inside Debian."
        } catch (error: Exception) {
            stopService(Intent(this, FloatingChatService::class.java))
            "Could not open Android Linux Terminal: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun installedAppNames(): List<String> = try {
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.MATCH_ALL
        ).mapNotNull { info ->
            try { info.loadLabel(packageManager).toString() } catch (_: Exception) { null }
        }.distinct()
    } catch (_: Exception) {
        emptyList()
    }

    private fun launchRequestedApp(query: String): String {
        return try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                .distinctBy { it.activityInfo.packageName }
                .mapNotNull { info ->
                    val label = try { info.loadLabel(packageManager).toString() } catch (_: Exception) { null }
                    label?.let { it to info.activityInfo.packageName }
                }
            val exact = apps.filter { it.first.equals(query, ignoreCase = true) }
            val matches = if (exact.isNotEmpty()) exact else apps.filter { it.first.contains(query, ignoreCase = true) }
            when {
                matches.isEmpty() -> "[APP] No installed app matched ‘$query’."
                matches.size > 1 -> "[APP] Multiple apps match: ${matches.take(5).joinToString { it.first }}. Be more specific."
                else -> {
                    val (label, pkg) = matches.single()
                    val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                        ?: return "[APP] $label has no launchable activity."
                    startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "[APP] Opening $label."
                }
            }
        } catch (e: Exception) {
            "[APP] Could not open ‘$query’: ${e.message ?: "launch failed"}"
        }
    }

    private fun launchSystemSettings(section: String): Boolean = try {
        val settingsIntent = when (section) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "app_permissions" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        startActivity(settingsIntent)
        true
    } catch (_: Exception) {
        false
    }

    private fun loadTerminalHistory(): List<String> =
        tailLines(File(filesDir, TERMINAL_HISTORY), MEMORY_TAIL_BYTES).ifEmpty { PLACEHOLDER_TERMINAL_LINES }

    /**
     * Last [maxBytes] of a text file, as lines.
     *
     * The panels only ever show the last few lines, but the old code read every byte of every file into
     * memory before taking them — and the memory search did it again on every query. A log is the largest
     * thing this app writes, so it is also the one worth reading backwards from the end.
     */
    private fun tailLines(file: File, maxBytes: Int): List<String> = try {
        if (!file.isFile) {
            emptyList()
        } else {
            java.io.RandomAccessFile(file, "r").use { handle ->
                val length = handle.length()
                val start = (length - maxBytes).coerceAtLeast(0L)
                val buffer = ByteArray((length - start).toInt())
                handle.seek(start)
                handle.readFully(buffer)
                val text = buffer.toString(Charsets.UTF_8)
                // A clipped read can start mid-character; the partial first line is dropped, not shown.
                val whole = if (start > 0) text.substringAfter('\n', "") else text
                whole.lines().filter { it.isNotBlank() }
            }
        }
    } catch (_: Exception) {
        emptyList()
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
                val lines = tailLines(src, MEMORY_TAIL_BYTES).takeLast(40)
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
        try {
            val store = LocalConversationMemory(this)
            try {
                store.recent(40).filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) }
                    .forEach { entry ->
                        items.add(
                            com.lucifer.mobile.ui.components.MemoryItem(
                                id = "local-${entry.createdAt}-${entry.title.hashCode()}",
                                content = "${entry.title}: ${entry.body}".take(240),
                                timestamp = entry.createdAt,
                                tags = listOf("local episodic", entry.horizon),
                                relevance = entry.importance / 100f
                            )
                        )
                    }
            } finally {
                store.close()
            }
        } catch (_: Exception) {}
        return items.sortedByDescending { it.timestamp }.take(30)
    }

    /** A truthful catalog of implemented controls and their current limitations. */
    private fun buildSkillRegistry(): List<com.lucifer.mobile.ui.components.SkillItem> {
        return listOf(
            com.lucifer.mobile.ui.components.SkillItem(
                id = "natural_language", name = "Natural-language request routing",
                description = "Understands requests before routing to chat, shell, apps, or Android Settings; non-read-only shell commands require confirmation.",
                category = "Control", isEnabled = true, status = "READY",
                icon = Icons.Filled.Psychology
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "recovery_loop", name = "Failure recovery + self-learning loop",
                description = "Classifies a failed command, runs a fixed read-only probe catalog on LOCAL/TERMUX/VM/SHIZUKU, optionally diagnoses with the model, researches public HTTPS docs (robots.txt + page budget), and ranks the next tactic from locally recorded outcomes. Repairs remain confirmation-gated; source is implemented and host-verified but not device-tested.",
                category = "Autonomy", isEnabled = true, status = "IMPLEMENTED · UNVALIDATED",
                icon = Icons.Filled.Autorenew
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "workflow_runner", name = "User-led iterative workflows",
                description = "Seven reusable blueprints (including recover-from-a-failure) with bounded persisted plans, user-started terminal/chat steps, manual observations, model reflection, pause/retry, and explicit completion. No background loop; only terminal/chat actions are routed here. Source is implemented but not yet compiled or device-tested.",
                category = "Automation", isEnabled = true, status = "USER-LED · UNVALIDATED",
                icon = Icons.Filled.Build
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "app_control", name = "Installed-app launcher",
                description = "Launch by natural-language app name or by explicitly tapping an app tile.",
                category = "Device", isEnabled = true, status = "READY",
                icon = Icons.Filled.PlayArrow
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "system_settings", name = "Android Settings shortcuts",
                description = "Opens Wi-Fi, Bluetooth, location, display, sound, notifications, battery, app, and permission settings. Android keeps protected toggles user-controlled.",
                category = "Device", isEnabled = true, status = "USER ACTION REQUIRED",
                icon = Icons.Filled.Settings
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "multi_target_terminal", name = "Interactive Termux · Linux VM · Shizuku terminal",
                description = "Natural-language-routed commands across LOCAL, Termux, pinned SSH VM, and Shizuku. Termux and SSH can share a tmux PTY with a floating chat overlay; Android's AVF guest needs user-configured guest SSH and port forwarding.",
                category = "Terminal", isEnabled = false, status = "LIMITED",
                icon = Icons.Filled.Terminal
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "shizuku_user_service", name = "Shizuku AIDL UserService bridge",
                description = "Checks Shizuku Binder/grant/version and UID, supports bounded terminal actions plus a validated read-only package/AppOps report, and blocks recognizable PMX permission mutations. It uses UserService shell execution, not direct PackageManager/AppOps Binder wrappers.",
                category = "Device access", isEnabled = true, status = "USER-GRANTED · LIMITED",
                icon = Icons.Filled.Build
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "pixel_android_diagnostics", name = "Pixel 8a / Android process diagnostics",
                description = "Locally reports runtime build/API, process/power state, Usage Access for Lucifer, and active VPN status. Detects Pixel 8a by akita/model; does not capture traffic or promise process survival.",
                category = "Device access", isEnabled = true, status = "LOCAL · READ ONLY",
                icon = Icons.Filled.Build
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "evm_wallet", name = "EVM wallet manager",
                description = "Creates EVM addresses, encrypts keys at rest, and checks native-token balances. No recovery phrase/export or transaction sending is implemented; do not fund wallets yet.",
                category = "Wallet", isEnabled = false, status = "LIMITED",
                icon = Icons.Filled.AccountBalanceWallet
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "public_browser", name = "Public web reader",
                description = "Fetches HTTPS pages and extracts text. Read-only: no JS, clicks, forms, sign-in, or browser session automation.",
                category = "Browser", isEnabled = false, status = "READ ONLY",
                icon = Icons.Filled.Language
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "drive_memory_mcp", name = "Private Google Drive memory retrieval (MCP)",
                description = "Optional pre-prompt retrieval through a token-protected, loopback-only Termux MCP service. Reads the user's private Lucifer_Memory.md through rclone; local llama.cpp embeddings are fused with BM25, with lexical fallback. Retrieved passages are untrusted context, not policy or authorization.",
                category = "Memory", isEnabled = false, status = "OPTIONAL · USER CONFIGURED",
                icon = Icons.Filled.Memory
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "memory", name = "Local activity memory",
                description = "Searches recent app-launch events and terminal history; this is a lightweight local log viewer, separate from optional Drive-backed retrieval.",
                category = "Memory", isEnabled = true, status = "READY",
                icon = Icons.Filled.Memory
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "voice", name = "Voice output",
                description = "Text-to-speech for chat and terminal results when enabled in Settings.",
                category = "Interface", isEnabled = true, status = "READY",
                icon = Icons.Filled.PlayArrow
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "kai_sandbox", name = "Kai sandbox bridge",
                description = "Can ping the installed Kai package. Deployment and several bridge operations remain incomplete or depend on external sandbox files.",
                category = "Sandbox", isEnabled = false, status = "INCOMPLETE",
                icon = Icons.Filled.Build
            ),
            com.lucifer.mobile.ui.components.SkillItem(
                id = "heartbeat", name = "Opt-in idea and creation heartbeat",
                description = "Runs one model-planned session immediately and repeats about every 30 minutes while its visible foreground service is active. With runtime consent it sends one camera still per session to NVIDIA; writes local Markdown drafts. No shell or external publishing. Browser interaction remains unavailable.",
                category = "Automation", isEnabled = true, status = "OPT-IN · LIMITED",
                icon = Icons.Filled.PlayArrow
            )
        )
    }

    // ───────────── VM host and pinned SSH host keys ─────────────
    //
    // Storage lives in config/AppConfig (atomic writes, one spelling per file). Validation stays here,
    // because a hostname rule is a settings decision, not a storage one.

    private fun loadVmHost(): String = AppConfig.readText(this, AppConfig.VM_HOST).orEmpty()

    private fun saveVmHost(host: String): Boolean {
        val normalized = host.trim()
        // Blank clears the setting; anything else must be a plausible hostname.
        if (normalized.isNotEmpty() && !normalized.matches(VM_HOST_RE)) return false
        return AppConfig.write(this, AppConfig.VM_HOST, normalized)
    }

    private fun loadVmUser(): String =
        AppConfig.readText(this, AppConfig.VM_USER)?.takeIf { it.matches(VM_USER_RE) } ?: DEFAULT_VM_USER

    private fun saveVmUser(user: String): Boolean {
        val normalized = user.trim()
        if (!normalized.matches(VM_USER_RE)) return false
        return AppConfig.write(this, AppConfig.VM_USER, normalized)
    }

    private fun loadVmPort(): Int = AppConfig.readInt(this, AppConfig.VM_PORT, DEFAULT_VM_PORT, 1..65535)

    private fun saveVmPort(port: String): Boolean {
        val value = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return false
        return AppConfig.write(this, AppConfig.VM_PORT, value.toString())
    }

    /** Pinned SSH host keys. Absent means "nothing pinned yet", which the SSH path treats as unverified. */
    private fun loadVmKnownHosts(): String =
        AppConfig.read(this, AppConfig.KNOWN_HOSTS, MAX_KNOWN_HOSTS_CHARS).orEmpty()

    private fun saveVmKnownHosts(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isNotEmpty()) {
            if (normalized.length > MAX_KNOWN_HOSTS_CHARS) return false
            val keyLines = normalized.lines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            val wellFormed = keyLines.isNotEmpty() && keyLines.all { line ->
                val fields = line.split(WHITESPACE_RE)
                fields.size >= 3 &&
                    KNOWN_HOST_KEY_TYPES.any { fields[1].startsWith(it) } &&
                    try { java.util.Base64.getDecoder().decode(fields[2]); true } catch (_: Exception) { false }
            }
            if (!wellFormed) return false
        }
        // A blank value clears the pin list instead of leaving a stale key behind.
        return AppConfig.write(this, AppConfig.KNOWN_HOSTS, normalized)
    }

    private fun saveTerminalHistory(lines: List<String>) {
        // Best effort: a terminal transcript that cannot be saved must not interrupt the session.
        AtomicFile(File(filesDir, TERMINAL_HISTORY)).let { atomic ->
            val stream = try { atomic.startWrite() } catch (_: Exception) { return }
            try {
                stream.write(lines.joinToString("\n").toByteArray())
                atomic.finishWrite(stream)
            } catch (e: Exception) {
                atomic.failWrite(stream)
            }
        }
    }

    fun saveSoulPrompt(prompt: String): Boolean {
        // Settings edits use the same validation as the Agents editor: minimum length, no
        // credentials, atomic write. A rejected edit leaves the previous soul.md intact.
        val result = PromptStore(this).saveSoul(prompt)
        if (!result.saved) Log.w("MainActivity", "saveSoulPrompt rejected: ${result.message}")
        return result.saved
    }

    fun saveVmPassword(password: String) {
        if (password.isEmpty()) {
            SecretStore.delete(this, AppConfig.VM_SSH_PASSWORD)
        } else if (!SecretStore.write(this, AppConfig.VM_SSH_PASSWORD, password)) {
            Log.e("MainActivity", "saveVmPassword failed: secure storage unavailable")
        }
    }

    private fun loadVoiceEnabled(): Boolean =
        AppConfig.readFlag(this, AppConfig.VOICE_ENABLED, default = true)

    fun saveVoiceEnabled(enabled: Boolean) {
        AppConfig.writeFlag(this, AppConfig.VOICE_ENABLED, enabled)
    }

    private fun loadKnowledgeUrl(): String = AppConfig.readText(this, AppConfig.KNOWLEDGE_URL).orEmpty()

    fun saveKnowledgeUrl(url: String) {
        AppConfig.write(this, AppConfig.KNOWLEDGE_URL, url)
    }

    private fun loadMemoryMcpUrl(): String = AppConfig.readText(this, AppConfig.MEMORY_MCP_URL).orEmpty()

    private fun saveMemoryMcpUrl(url: String): Boolean = AppConfig.write(this, AppConfig.MEMORY_MCP_URL, url)

    /** One IO pass for the settings screen, so it builds state once instead of seven times. */
    private data class SettingsSnapshot(
        val knowledgeUrl: String,
        val memoryMcpUrl: String,
        val memoryMcpTokenSet: Boolean,
        val vmHost: String,
        val vmUser: String,
        val vmPort: Int,
        val vmKnownHosts: String
    )

    private companion object {
        const val DEFAULT_VM_USER = "droid"
        const val DEFAULT_VM_PORT = 8022
        const val MAX_KNOWN_HOSTS_CHARS = 32_000
        const val TERMINAL_HISTORY = "terminal_history.log"

        /** Whole-token hostname rule: a leading alphanumeric, then letters/digits/./_/:/%/-. */
        val VM_HOST_RE = Regex("^[A-Za-z0-9][A-Za-z0-9._:%-]*$")
        val VM_USER_RE = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,31}$")
        val WHITESPACE_RE = Regex("\\s+")
        val KNOWN_HOST_KEY_TYPES = listOf("ssh-", "ecdsa-", "sk-")

        /** The panels show the last few lines; reading a whole log to find them is waste. */
        const val MEMORY_TAIL_BYTES = 64 * 1024

        val PLACEHOLDER_TERMINAL_LINES = listOf(
            "Lucifer controls ready. Heartbeat is opt-in from its control panel.",
            "Type a plain-language request; Lucifer routes before action.",
            "Wallet and read-only Browser controls are available in their tabs."
        )
    }
}
