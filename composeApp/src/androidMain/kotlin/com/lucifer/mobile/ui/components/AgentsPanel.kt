package com.lucifer.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.agents.AgentPack
import com.lucifer.mobile.agents.Author
import com.lucifer.mobile.agents.PromptDefaults
import com.lucifer.mobile.agents.PromptRules
import com.lucifer.mobile.agents.android.PromptStore
import com.lucifer.mobile.agents.android.SignalCollector
import com.lucifer.mobile.agents.android.TacticianRunner
import com.lucifer.mobile.agents.android.TacticianService
import com.lucifer.mobile.tactician.Opportunity
import com.lucifer.mobile.tactician.OpportunityCategory
import com.lucifer.mobile.tactician.TacticianEngine
import com.lucifer.mobile.ui.effects.Disintegration
import com.lucifer.mobile.ui.effects.SigilRule
import com.lucifer.mobile.ui.effects.glassSurface
import com.lucifer.mobile.ui.effects.shard
import com.lucifer.mobile.ui.theme.Morningstar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AgentsSection(val label: String) {
    GENERAL("GENERAL"),
    PROPOSALS("PROPOSALS"),
    HEARTBEAT("HEARTBEAT.MD"),
    SOUL("SOUL.MD"),
    AGENTS("AGENTS")
}

/**
 * The ecosystem surface: the General's state and proposals, the two editable prompt files that
 * shape every session, and one editor per agent class.
 *
 * Everything here is explicit. Starting the watcher is a user action with a visible notification,
 * approving a proposal is a user action, and no control in this panel executes a command.
 */
@Composable
fun AgentsPanel(
    modifier: Modifier = Modifier,
    statusMessage: String? = null,
    onStartGeneral: () -> Unit = {},
    onStopGeneral: () -> Unit = {},
    onNotify: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { PromptStore(context) }
    val runner = remember(context) { TacticianRunner(context) }
    val collector = remember(context) { SignalCollector(context) }

    var section by remember { mutableStateOf(AgentsSection.GENERAL) }
    var refreshTick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var purge by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }

    var heartbeatText by remember { mutableStateOf("") }
    var soulText by remember { mutableStateOf("") }
    var agents by remember { mutableStateOf<List<AgentPack>>(emptyList()) }
    var brokenAgents by remember { mutableStateOf<List<AgentPack>>(emptyList()) }
    var proposals by remember { mutableStateOf<List<Opportunity>>(emptyList()) }
    var latestReport by remember { mutableStateOf<String?>(null) }
    var observationLine by remember { mutableStateOf("—") }
    var budgetLine by remember { mutableStateOf("—") }
    var generalEnabled by remember { mutableStateOf(TacticianService.isEnabled(context)) }
    var generalRunning by remember { mutableStateOf(TacticianService.isRunning()) }
    var heartbeatCustom by remember { mutableStateOf(false) }
    var selectedAgentId by remember { mutableStateOf<String?>(null) }
    var agentEditText by remember { mutableStateOf("") }
    var newAgentName by remember { mutableStateOf("") }
    var newAgentGoal by remember { mutableStateOf("") }
    var newAgentByLucifer by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTick) {
        withContext(Dispatchers.IO) {
            val registry = runCatching { collector.settleRegistry(store) }.getOrNull()
            val soul = store.soul()
            val heartbeat = store.heartbeat()
            val ledger = runCatching { runner.ledger() }.getOrNull()
            val observations = com.lucifer.mobile.agents.android.ObservationBus.ledger(context).all()
            val budget = runCatching { collector.defaultBudget() }.getOrDefault(com.lucifer.mobile.tactician.AutonomyBudget())
            val calls = TacticianEngine.callCountToday(observations, System.currentTimeMillis())
            val counts = TacticianEngine.summarizeCounts(observations)
            val lastRun = runCatching { collector.lastRunAt() }.getOrDefault(0L)
            val report = runCatching { runner.latestReport() }.getOrNull()
            val customized = runCatching { store.heartbeatIsCustomized() }.getOrDefault(false)
            val openAgentId = selectedAgentId
            val openAgentText = openAgentId?.let { id ->
                val file = store.agentFile(id)
                if (file.isFile) file.readText().take(PromptRules.MAX_AGENT_CHARS) else store.agent(id)?.mandate.orEmpty()
            }.orEmpty()
            withContext(Dispatchers.Main) {
                soulText = soul.body
                heartbeatText = heartbeat.body
                heartbeatCustom = customized
                agents = registry?.packs.orEmpty()
                brokenAgents = registry?.problems.orEmpty()
                proposals = ledger?.pending().orEmpty()
                latestReport = report
                observationLine = "${observations.size} recorded · $counts"
                budgetLine = TacticianEngine.budgetLine(budget, calls, lastRun, System.currentTimeMillis())
                generalEnabled = TacticianService.isEnabled(context)
                generalRunning = TacticianService.isRunning()
                if (openAgentId != null) agentEditText = openAgentText
            }
        }
    }

    fun reload(message: String? = null) {
        note = message
        refreshTick += 1
    }

    fun runPass() {
        if (busy) return
        busy = true
        scope.launch {
            val outcome = runCatching { runner.runOnce("panel") }
                .getOrElse { com.lucifer.mobile.agents.android.TacticianOutcome(false, "Pass failed: ${it.message ?: "unknown"}") }
            busy = false
            note = outcome.message
            onNotify(outcome.message)
            refreshTick += 1
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Morningstar.Void)
    ) {
    // Erasure: when the ledger is cleared, the data burns away instead of blinking out.
    Disintegration(trigger = purge, embers = 64)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AGENT ECOSYSTEM", color = Morningstar.Gilded, fontSize = 19.sp, fontFamily = Morningstar.Display, fontWeight = FontWeight.SemiBold)
        Text(
            "heartbeat.md and soul.md are yours to edit, and every agent has its own agent-class.md. " +
                "The General watches what Lucifer already records — app installs, commands and failures, recovery " +
                "lessons, workflows, heartbeat sessions, camera use, model calls, agent runs — and proposes structural " +
                "fixes. It cannot edit prompts, run commands, or spend budget on its own: you approve each proposal here.",
            color = Morningstar.Bone, fontSize = 12.sp, fontFamily = Morningstar.Body
        )

        (note ?: statusMessage)?.let { message ->
            Card(colors = CardDefaults.cardColors(containerColor = Morningstar.Glass)) {
                Text(message, color = Morningstar.Gilded, fontSize = 12.sp, fontFamily = Morningstar.Body, modifier = Modifier.padding(10.dp))
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            AgentsSection.entries.forEach { entry ->
                Button(
                    onClick = { section = entry },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (entry == section) Morningstar.Crimson else Morningstar.GlassRaised,
                        contentColor = if (entry == section) Color.White else Morningstar.Ash
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text(entry.label, fontSize = 8.sp, fontFamily = Morningstar.Body, maxLines = 1) }
            }
        }

        when (section) {
            AgentsSection.GENERAL -> GeneralSection(
                generalEnabled = generalEnabled,
                generalRunning = generalRunning,
                observationLine = observationLine,
                budgetLine = budgetLine,
                latestReport = latestReport,
                busy = busy,
                onStart = {
                    onStartGeneral()
                    reload("Watcher start requested. Android shows the ongoing notification with a Stop control.")
                },
                onStop = {
                    onStopGeneral()
                    reload("Watcher stopped. No background pass will run until you start it again.")
                },
                onRunPass = ::runPass,
                onClearObservations = {
                    purge += 1
                    scope.launch {
                        withContext(Dispatchers.IO) { com.lucifer.mobile.agents.android.ObservationBus.clear(context) }
                        reload("Observation ledger cleared. Existing lessons and workflow history are untouched.")
                    }
                }
            )
            AgentsSection.PROPOSALS -> ProposalsSection(
                proposals = proposals,
                busy = busy,
                onApprove = { target ->
                    if (busy) return@ProposalsSection
                    busy = true
                    scope.launch {
                        val result = runCatching { runner.approve(target.fingerprint) }
                            .getOrElse { com.lucifer.mobile.agents.android.PromptSaveResult(false, "Approve failed: ${it.message}") }
                        busy = false
                        onNotify(result.message)
                        refreshTick += 1
                        note = result.message
                    }
                },
                onReject = { target ->
                    val result = runner.reject(target.fingerprint)
                    reload(result.message)
                },
                onClearResolved = {
                    val result = runner.clearResolved()
                    reload(result.message)
                }
            )
            AgentsSection.HEARTBEAT -> EditorSection(
                title = "HEARTBEAT.MD",
                hint = if (heartbeatCustom) {
                    "Custom directive active. This text is prepended to every heartbeat session, which is why the session produces what you actually need."
                } else {
                    "The shipped directive is active. Edit this to name your real projects and what counts as high value here — the heartbeat follows this file, not a hard-coded prompt."
                },
                value = heartbeatText,
                maxChars = PromptRules.MAX_HEARTBEAT_CHARS,
                onChange = { heartbeatText = it },
                onSave = {
                    val result = store.saveHeartbeat(heartbeatText)
                    reload(result.message)
                },
                onResetDefault = {
                    heartbeatText = PromptDefaults.heartbeat
                    note = "Default restored in the editor. Press SAVE to write it."
                },
                accent = Morningstar.Info
            )
            AgentsSection.SOUL -> EditorSection(
                title = "SOUL.MD",
                hint = "His identity and standing rules. Saved here as files/soul.md, which overrides the copy shipped in the APK for chat, heartbeat, and the cognitive router. Credentials are rejected.",
                value = soulText,
                maxChars = PromptRules.MAX_SOUL_CHARS,
                onChange = { soulText = it },
                onSave = {
                    val result = store.saveSoul(soulText)
                    reload(result.message)
                },
                onResetDefault = {
                    note = "soul.md is never auto-reset; edit it deliberately. Clearing it is not allowed."
                },
                accent = Morningstar.Gilded
            )
            AgentsSection.AGENTS -> AgentsSectionContent(
                agents = agents,
                broken = brokenAgents,
                selectedAgentId = selectedAgentId,
                editorText = agentEditText,
                busy = busy,
                newAgentName = newAgentName,
                newAgentGoal = newAgentGoal,
                newAgentByLucifer = newAgentByLucifer,
                onSelect = { id ->
                    selectedAgentId = id
                    val file = store.agentFile(id)
                    agentEditText = if (file.isFile) file.readText() else store.agent(id)?.mandate.orEmpty()
                },
                onEditorChange = { agentEditText = it },
                onSaveAgent = { id ->
                    val result = store.saveAgentFile(id, agentEditText)
                    reload(result.message)
                },
                onToggleAgent = { id, enabled ->
                    val result = store.setAgentEnabled(id, enabled)
                    reload(result.message)
                },
                onDeleteAgent = { id ->
                    val result = store.deleteAgent(id)
                    if (result.saved) {
                        selectedAgentId = null
                        agentEditText = ""
                    }
                    reload(result.message)
                },
                onNewNameChange = { newAgentName = it },
                onNewGoalChange = { newAgentGoal = it },
                onAuthorChange = { newAgentByLucifer = it },
                onCreateAgent = {
                    if (busy) return@AgentsSectionContent
                    val goal = newAgentGoal.trim()
                    if (goal.length < PromptRules.MIN_BODY_CHARS) {
                        reload("Describe what this agent should own (at least ${PromptRules.MIN_BODY_CHARS} characters).")
                        return@AgentsSectionContent
                    }
                    busy = true
                    scope.launch {
                        val author = if (newAgentByLucifer) Author.LUCIFER else Author.USER
                        val pack = runCatching { runner.composeAgent(newAgentName, goal, author) }
                            .getOrElse { null }
                        if (pack == null) {
                            busy = false
                            reload("Could not compose an agent from that description. Try wording the goal as an outcome.")
                            return@launch
                        }
                        val result = runCatching { store.createAgent(pack) }
                            .getOrElse { com.lucifer.mobile.agents.android.PromptSaveResult(false, "Create failed: ${it.message}") }
                        busy = false
                        if (result.saved) {
                            newAgentName = ""
                            newAgentGoal = ""
                            selectedAgentId = pack.id
                            agentEditText = store.agentFile(pack.id).let { if (it.isFile) it.readText() else pack.mandate }
                        }
                        reload(result.message)
                        onNotify(result.message)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Limits, stated plainly: the General sees event summaries and metadata Lucifer already stores — never " +
                "message bodies, browsing history, or another app's private data. It has no execution path, no " +
                "permission changes, and no way to spend money or contact anyone. Background passes need the " +
                "ongoing notification; Android may delay them.",
            color = Morningstar.Ash, fontSize = 10.sp, fontFamily = Morningstar.Body
        )
    }
    }
}

@Composable
private fun GeneralSection(
    generalEnabled: Boolean,
    generalRunning: Boolean,
    observationLine: String,
    budgetLine: String,
    latestReport: String?,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRunPass: () -> Unit,
    onClearObservations: () -> Unit
) {
    PanelCard("THE GENERAL · TACTICIAN", Morningstar.Gilded) {
        SystemStatusRow("Watcher", if (generalRunning) "ACTIVE" else if (generalEnabled) "ARMED · NOT RUNNING" else "OFF",
            if (generalRunning) Morningstar.Success else if (generalEnabled) Morningstar.Warning else Morningstar.Ash)
        SystemStatusRow("Senses", observationLine.take(120), Morningstar.Gilded)
        SystemStatusRow("Budget", budgetLine.take(120), Morningstar.Info)
        Text(
            "One pass every ~60 minutes while the notification is visible: ingest new events, score what is " +
                "actually costing you time, and write a brief plus proposals. Approve them in the Proposals section.",
            color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = if (generalRunning || generalEnabled) onStop else onStart,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (generalRunning || generalEnabled) Morningstar.GlassRaised else Morningstar.Crimson,
                    contentColor = Morningstar.Gilded
                )
            ) { Text(if (generalRunning || generalEnabled) "STOP WATCHER" else "START WATCHER", fontSize = 10.sp, fontFamily = Morningstar.Body) }
            Button(
                onClick = onRunPass,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Bone)
            ) { Text(if (busy) "RUNNING…" else "RUN ONE PASS", fontSize = 10.sp, fontFamily = Morningstar.Body) }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = onClearObservations,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Morningstar.CrimsonDim, contentColor = Morningstar.Crimson)
        ) { Text("CLEAR OBSERVATION LEDGER", fontSize = 10.sp, fontFamily = Morningstar.Body) }
    }

    PanelCard("LATEST BRIEF", Morningstar.Info) {
        if (latestReport.isNullOrBlank()) {
            Text(
                "No tactician pass yet. Run one pass to see what the General concludes from the events Lucifer already recorded.",
                color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body
            )
        } else {
            Text(latestReport.take(2_400), color = Morningstar.Bone, fontSize = 11.sp, fontFamily = Morningstar.MonoTail)
            if (latestReport.length > 2_400) {
                Text("… truncated in the panel; the full brief is in files/tactician/latest.md.", color = Morningstar.Ash, fontSize = 9.sp, fontFamily = Morningstar.Body)
            }
        }
    }
}

@Composable
private fun ProposalsSection(
    proposals: List<Opportunity>,
    busy: Boolean,
    onApprove: (Opportunity) -> Unit,
    onReject: (Opportunity) -> Unit,
    onClearResolved: () -> Unit
) {
    PanelCard("PROPOSALS · PENDING ${proposals.size}", Morningstar.Gilded) {
        if (proposals.isEmpty()) {
            Text(
                "Nothing pending. That is a valid result — the General is instructed to say so rather than invent work.",
                color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body
            )
        }
        proposals.forEach { proposal ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .background(Morningstar.Glass, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    proposal.title,
                    color = Morningstar.Gilded, fontSize = 12.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold
                )
                Text(
                    "${proposal.category.label} · score ${proposal.score}/100 · ${proposal.autonomy.label}",
                    color = Morningstar.Ash, fontSize = 9.sp, fontFamily = Morningstar.Body
                )
                Text(proposal.why, color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body)
                if (proposal.evidence.isNotEmpty()) {
                    Text("evidence: ${proposal.evidence.joinToString(", ")}", color = Morningstar.Info, fontSize = 9.sp, fontFamily = Morningstar.Body)
                }
                Text("action: ${proposal.action}", color = Morningstar.Gilded, fontSize = 11.sp, fontFamily = Morningstar.Body)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onApprove(proposal) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Morningstar.Crimson, contentColor = Color.White)
                    ) { Text("APPROVE", fontSize = 10.sp, fontFamily = Morningstar.Body) }
                    Button(
                        onClick = { onReject(proposal) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Warning)
                    ) { Text("DISMISS", fontSize = 10.sp, fontFamily = Morningstar.Body) }
                }
                if (proposal.category == OpportunityCategory.AGENT || proposal.category == OpportunityCategory.SKILL) {
                    Text(
                        "Approving composes a new agent class written by Lucifer, with the same boundaries as every other agent.",
                        color = Morningstar.Ash, fontSize = 9.sp, fontFamily = Morningstar.Body
                    )
                }
                if (proposal.category == OpportunityCategory.MCP_TOOL) {
                    Text(
                        "Approving writes a read-only tool spec and a Termux checklist to files/tactician/mcp-drafts/. Nothing is installed.",
                        color = Morningstar.Ash, fontSize = 9.sp, fontFamily = Morningstar.Body
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = onClearResolved,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Gilded)
        ) { Text("CLEAR DISMISSED / EXPIRED", fontSize = 10.sp, fontFamily = Morningstar.Body) }
    }
}

@Composable
private fun EditorSection(
    title: String,
    hint: String,
    value: String,
    maxChars: Int,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onResetDefault: () -> Unit,
    accent: Color
) {
    PanelCard(title, accent) {
        Text(hint, color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body)
        Spacer(modifier = Modifier.height(6.dp))
        TextField(
            value = value,
            onValueChange = { if (it.length <= maxChars) onChange(it) },
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = Morningstar.MonoTail),
            colors = panelFieldColors()
        )
        Text("${value.length} / $maxChars characters", color = Morningstar.Ash, fontSize = 9.sp, fontFamily = Morningstar.Body)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Morningstar.Crimson, contentColor = Color.White)
            ) { Text("SAVE", fontSize = 10.sp, fontFamily = Morningstar.Body) }
            Button(
                onClick = onResetDefault,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Gilded)
            ) { Text(if (title.startsWith("HEART")) "LOAD DEFAULT" else "WHAT HAPPENS?", fontSize = 10.sp, fontFamily = Morningstar.Body) }
        }
    }
}

@Composable
private fun AgentsSectionContent(
    agents: List<AgentPack>,
    broken: List<AgentPack>,
    selectedAgentId: String?,
    editorText: String,
    busy: Boolean,
    newAgentName: String,
    newAgentGoal: String,
    newAgentByLucifer: Boolean,
    onSelect: (String) -> Unit,
    onEditorChange: (String) -> Unit,
    onSaveAgent: (String) -> Unit,
    onToggleAgent: (String, Boolean) -> Unit,
    onDeleteAgent: (String) -> Unit,
    onNewNameChange: (String) -> Unit,
    onNewGoalChange: (String) -> Unit,
    onAuthorChange: (Boolean) -> Unit,
    onCreateAgent: () -> Unit
) {
    PanelCard("ROSTER · ${agents.count { it.enabled }} ENABLED / ${agents.size}", Morningstar.Success) {
        if (agents.isEmpty()) {
            Text("No agent files yet. The General is seeded automatically on first open.", color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body)
        }
        agents.forEach { agent ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${agent.name} · ${agent.id}",
                        color = if (agent.enabled) Morningstar.Bone else Morningstar.Ash,
                        fontSize = 12.sp, fontFamily = Morningstar.Body,
                        fontWeight = FontWeight.Bold
                    )
                    Text(agent.oneLine, color = Morningstar.Ash, fontSize = 9.sp, fontFamily = Morningstar.Body)
                    if (agent.skills.isNotEmpty()) {
                        Text("skills: ${agent.skills.joinToString("/")}", color = Morningstar.Gilded, fontSize = 9.sp, fontFamily = Morningstar.Body)
                    }
                    if (agent.triggers.isNotEmpty()) {
                        Text("triggers: ${agent.triggers.joinToString("/")}", color = Morningstar.Ash, fontSize = 9.sp, fontFamily = Morningstar.Body)
                    }
                }
                Button(
                    onClick = { onSelect(agent.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Bone)
                ) { Text(if (selectedAgentId == agent.id) "EDITING" else "EDIT", fontSize = 9.sp, fontFamily = Morningstar.Body) }
                Button(
                    onClick = { onToggleAgent(agent.id, !agent.enabled) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (agent.enabled) Morningstar.SuccessDim else Morningstar.GlassRaised,
                        contentColor = if (agent.enabled) Morningstar.Success else Morningstar.Warning
                    )
                ) { Text(if (agent.enabled) "ON" else "OFF", fontSize = 9.sp, fontFamily = Morningstar.Body) }
            }
        }
        if (broken.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Files that cannot be used:", color = Morningstar.Crimson, fontSize = 10.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold)
            broken.forEach { pack ->
                Text("${pack.id}: ${pack.problem}", color = Morningstar.Crimson, fontSize = 9.sp, fontFamily = Morningstar.Body)
            }
        }
    }

    if (selectedAgentId != null) {
        PanelCard("EDIT · $selectedAgentId", Morningstar.Gilded) {
            TextField(
                value = editorText,
                onValueChange = { if (it.length <= PromptRules.MAX_AGENT_CHARS) onEditorChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontFamily = Morningstar.MonoTail),
                colors = panelFieldColors()
            )
            Text(
                "Front matter keys: id, name, role, created_by, enabled, skills, triggers. The body is the mandate. " +
                    "A file that fails validation is never written.",
                color = Morningstar.Ash, fontSize = 9.sp, fontFamily = Morningstar.Body
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSaveAgent(selectedAgentId) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Morningstar.Crimson, contentColor = Color.White)
                ) { Text("SAVE AGENT", fontSize = 10.sp, fontFamily = Morningstar.Body) }
                Button(
                    onClick = { onDeleteAgent(selectedAgentId) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Morningstar.CrimsonDim, contentColor = Morningstar.Crimson)
                ) { Text("DELETE", fontSize = 10.sp, fontFamily = Morningstar.Body) }
            }
        }
    }

    PanelCard("NEW AGENT", Morningstar.Gilded) {
        Text(
            "Compose a specialist from a goal. Written by you or by Lucifer — either way it becomes a file you can edit, " +
                "and it cannot grant new tools or permissions.",
            color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body
        )
        TextField(
            value = newAgentName,
            onValueChange = { onNewNameChange(it.take(60)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Name, e.g. Disk Medic", color = Morningstar.Whisper) },
            colors = panelFieldColors()
        )
        TextField(
            value = newAgentGoal,
            onValueChange = { onNewGoalChange(it.take(600)) },
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
            placeholder = { Text("What it owns, and what a good result looks like", color = Morningstar.Whisper) },
            colors = panelFieldColors()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onAuthorChange(false) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!newAgentByLucifer) Morningstar.Crimson else Morningstar.GlassRaised,
                    contentColor = Morningstar.Bone
                )
            ) { Text("I WRITE IT", fontSize = 10.sp, fontFamily = Morningstar.Body) }
            Button(
                onClick = { onAuthorChange(true) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (newAgentByLucifer) Morningstar.Crimson else Morningstar.GlassRaised,
                    contentColor = Morningstar.Bone
                )
            ) { Text("LUCIFER WRITES IT", fontSize = 10.sp, fontFamily = Morningstar.Body) }
        }
        Button(
            onClick = onCreateAgent,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Gilded)
        ) { Text(if (busy) "COMPOSING…" else "CREATE AGENT CLASS", fontSize = 10.sp, fontFamily = Morningstar.Body) }
    }
}

@Composable
private fun PanelCard(title: String, accent: Color, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shard(13.dp), accent.copy(alpha = 0.35f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = Morningstar.overline(10).copy(color = accent))
        SigilRule()
        content()
    }
}

@Composable
private fun panelFieldColors() = TextFieldDefaults.textFieldColors(
    containerColor = Morningstar.Void,
    focusedTextColor = Morningstar.Bone,
    unfocusedTextColor = Morningstar.Bone,
    cursorColor = Morningstar.Gilded,
    focusedIndicatorColor = Morningstar.Crimson,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent
)
