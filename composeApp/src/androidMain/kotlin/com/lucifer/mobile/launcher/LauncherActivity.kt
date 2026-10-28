package com.lucifer.mobile.launcher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.omni_bridge.four_horsemen.FourHorsemenOrchestrator
import com.lucifer.mobile.memory.LocalConversationMemory
import com.lucifer.mobile.omni_bridge.knowledge.ToEReasoningEngine
import com.lucifer.mobile.terminal.TerminalExecutor
import com.lucifer.mobile.ui.theme.LuciferColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LuciferLauncher — the home screen IS the agent.
 * Home-screen launcher and assistant. Heartbeat sessions are opt-in from MainActivity;
 * they are never started by this launcher. Text requests route before action.
 */
class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        journalLaunch("HOME")

        // Do not start heartbeat from launcher/home. Sessions require an explicit
        // opt-in from MainActivity and run within the non-executing draft boundary.

        // His voice obeys the persisted Settings toggle on every cold start.
        val voice = LuciferVoice(this)
        voice.setEnabled(loadVoiceEnabledPref())
        val brain = FourHorsemenOrchestrator(this)
        val reasoner = ToEReasoningEngine(this, brain)
        setContent { LuciferLauncherScreen(voice, reasoner) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* true launcher: BACK on home does nothing */ }

    private fun journalLaunch(tag: String) {
        try {
            val dir = File(filesDir, "memory")
            dir.mkdirs()
            File(dir, "app_launch.log").appendText(
                "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())} $tag\n"
            )
        } catch (_: Exception) {}
    }

    /** Read the persisted voice preference (written by Settings → VOICE). */
    private fun loadVoiceEnabledPref(): Boolean {
        return try {
            val file = File(filesDir, "config/voice_enabled.txt")
            !file.exists() || file.readText().trim() != "false"
        } catch (_: Exception) {
            true
        }
    }

    private fun launchApp(pkg: String): Boolean = try {
        if (pkg == packageName) {
            // CORE opens his control app directly on the Settings tab —
            // never a launch-intent lottery between HOME and MainActivity.
            val intent = Intent().setClassName(packageName, "com.lucifer.mobile.MainActivity")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("lucifer_tab", 3)
            startActivity(intent)
            journalLaunch("CORE→Settings")
            true
        } else {
            val im = packageManager.getLaunchIntentForPackage(pkg)
            if (im != null) {
                im.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(im)
                journalLaunch(pkg)
                true
            } else false
        }
    } catch (e: Exception) {
        Log.w("LuciferLauncher", "launch failed for $pkg", e)
        false
    }

    private fun launchControlPanel(panel: String): Boolean = try {
        val tab = when (panel) {
            "terminal" -> 0
            "memory" -> 1
            "skills" -> 2
            "settings" -> 3
            "wallet" -> 4
            "browser" -> 5
            "workflows", "workflow" -> 7
            "recover", "recovery" -> 8
            else -> return false
        }
        val intent = Intent().setClassName(packageName, "com.lucifer.mobile.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("lucifer_tab", tab)
        startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w("LuciferLauncher", "could not open Lucifer panel $panel", e)
        false
    }

    private fun launchSystemSettings(section: String): Boolean = try {
        val intent = when (section) {
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
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w("LuciferLauncher", "could not open Settings section $section", e)
        false
    }

    data class AppEntry(
        val pkg: String,
        val label: String,
        val icon: ImageBitmap?
    )

    private fun loadApps(): List<AppEntry> {
        val pm: PackageManager = packageManager
        val main = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val self = packageName
        val ignore = setOf("com.android.settings", "com.teslacoilsw.launcher")
        val out = mutableListOf<AppEntry>()
        val seen = mutableSetOf<String>()
        try {
            val ril: List<ResolveInfo> = pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
            for (ri in ril) {
                val pkg = ri.activityInfo.packageName ?: continue
                if (pkg == self || pkg in ignore) continue
                if (!seen.add(pkg)) continue
                val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { pkg }
                var iconBm: ImageBitmap? = null
                try {
                    val d: Drawable = ri.loadIcon(pm)
                    val bmp = android.graphics.Bitmap.createBitmap(
                        maxOf(1, d.intrinsicWidth.coerceAtLeast(48)),
                        maxOf(1, d.intrinsicHeight.coerceAtLeast(48)),
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val c = android.graphics.Canvas(bmp)
                    d.setBounds(0, 0, c.width, c.height)
                    d.draw(c)
                    iconBm = bmp.asImageBitmap()
                } catch (e: Exception) { /* text-only tile */ }
                out.add(AppEntry(pkg, label, iconBm))
            }
        } catch (e: Exception) {
            Log.w("LuciferLauncher", "loadApps failed", e)
        }
        return out.sortedBy { it.label.lowercase(Locale.US) }
    }

    // ───────────────────────────── screen ─────────────────────────────

    @Composable
    private fun LuciferLauncherScreen(
        voice: LuciferVoice,
        reasoner: ToEReasoningEngine
    ) {
        val apps by remember { mutableStateOf(loadApps()) }
        var query by remember { mutableStateOf("") }
        var terminalLines by remember { mutableStateOf(LuciferState.recent()) }
        var target by remember { mutableStateOf(0) } // 0 LOCAL, 1 TERMUX, 2 VM, 3 SHIZUKU
        var showChat by remember { mutableStateOf(false) }
        var chatMessages by remember { mutableStateOf(listOf<ChatMessage>()) }
        var voiceMuted by remember { mutableStateOf(false) }
        var chatThinking by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val executor = remember { TerminalExecutor(this@LauncherActivity) }
        val router = remember { NaturalLanguageRouter(this@LauncherActivity) }
        val localMemory = remember { LocalConversationMemory(this@LauncherActivity) }
        val resilience = remember { com.lucifer.mobile.resilience.ResilienceEngine(this@LauncherActivity) }
        val kb = LocalSoftwareKeyboardController.current
        var pendingAction by remember { mutableStateOf<TerminalAction?>(null) }

        fun launchTerminalAction(action: TerminalAction, shouldSpeak: Boolean) {
            pendingAction = null
            chatThinking = true
            scope.launch(Dispatchers.IO) {
                val output = try {
                    executor.execute(action.command, action.target.launcherIndex)
                } catch (e: Exception) {
                    listOf("[ERROR] ${e.message ?: "Command failed"}")
                }
                localMemory.rememberFinding(
                    "${action.target.displayName} command result: ${action.command.take(120)}",
                    output.joinToString("\n").take(1_800),
                    importance = 70
                )
                try {
                    resilience.captureOutcome(
                        com.lucifer.mobile.resilience.ResilienceEngine.outcomeOf(action.target, action.command, output)
                    )
                } catch (_: Exception) {
                }
                withContext(Dispatchers.Main) {
                    val transcript = "${action.target.displayName} · ${executor.destinationDetails(action.target.launcherIndex)} · ${action.command}\n${output.joinToString("\n")}".take(4_000)
                    chatMessages = chatMessages + ChatMessage(transcript, true)
                    terminalLines = (terminalLines + "$ ${action.target.displayName}: ${action.command}" + output).takeLast(50)
                    chatThinking = false
                    if (shouldSpeak) voice.speakResult(output)
                }
            }
        }

        fun sendChatMessage(rawText: String) {
            val text = rawText.trim()
            if (text.isBlank() || chatThinking || pendingAction != null) return
            val history = chatMessages.takeLast(6)
            chatMessages = chatMessages + ChatMessage(text, false)
            chatThinking = true
            val selectedTarget = CommandTarget.fromLauncherIndex(target)
            val shouldSpeak = !voiceMuted
            scope.launch(Dispatchers.IO) {
                val route = try {
                    router.understand(text, selectedTarget, history, apps.map { it.label })
                } catch (e: Exception) {
                    ChatRoute.Clarify("I couldn't understand that request. Try rephrasing it or choose LOCAL, TERMUX, VM, or SHIZUKU.")
                }
                when (route) {
                    ChatRoute.Conversation -> {
                        val answer = try { reasoner.reason(text) } catch (e: Exception) { "I couldn't complete that reply: ${e.message}" }
                        localMemory.rememberExchange(text, answer)
                        withContext(Dispatchers.Main) {
                            chatMessages = chatMessages + ChatMessage(answer, true)
                            chatThinking = false
                            if (shouldSpeak) voice.speak(answer)
                        }
                    }
                    is ChatRoute.Clarify -> withContext(Dispatchers.Main) {
                        chatMessages = chatMessages + ChatMessage(route.question, true)
                        chatThinking = false
                        if (shouldSpeak) voice.speak(route.question)
                    }
                    is ChatRoute.OpenApp -> withContext(Dispatchers.Main) {
                        val isLucifer = route.appName.contains("lucifer", ignoreCase = true)
                        val exactMatches = apps.filter { it.label.equals(route.appName, ignoreCase = true) }
                        val matches = if (exactMatches.isNotEmpty()) exactMatches else apps.filter {
                            it.label.contains(route.appName, ignoreCase = true)
                        }
                        val launched = if (isLucifer) launchApp(packageName) else if (matches.size == 1) launchApp(matches.single().pkg) else false
                        val message = when {
                            launched -> "Opening ${if (isLucifer) "Lucifer controls" else matches.single().label}."
                            matches.isEmpty() -> "I couldn't find an installed app named ‘${route.appName}’."
                            else -> "I found several matching apps: ${matches.take(5).joinToString { it.label }}. Please be more specific."
                        }
                        chatMessages = chatMessages + ChatMessage(message, true)
                        chatThinking = false
                        if (shouldSpeak) voice.speak(message)
                    }
                    is ChatRoute.OpenSettings -> withContext(Dispatchers.Main) {
                        val opened = launchSystemSettings(route.section)
                        val message = if (opened) {
                            "Opened ${route.section.replace('_', ' ')} settings. Android requires you to make the change there; I cannot toggle protected system controls directly."
                        } else "Android could not open that Settings page on this device."
                        chatMessages = chatMessages + ChatMessage(message, true)
                        chatThinking = false
                        if (shouldSpeak) voice.speak(message)
                    }
                    is ChatRoute.OpenPanel -> withContext(Dispatchers.Main) {
                        val opened = launchControlPanel(route.panel)
                        val message = if (opened) "Opening Lucifer's ${route.panel} panel." else "I could not open Lucifer's ${route.panel} panel."
                        chatMessages = chatMessages + ChatMessage(message, true)
                        chatThinking = false
                        if (shouldSpeak) voice.speak(message)
                    }
                    is ChatRoute.Resilience -> {
                        val lines = try {
                            when (route.request) {
                                ResilienceRequest.INVESTIGATE -> {
                                    val out = mutableListOf<String>()
                                    val signal = resilience.state.value.signal
                                    if (signal == null) {
                                        out += "No failure captured yet — run something that fails first, or ask me to research a topic."
                                    } else {
                                        out += "Investigating ${signal.failureClass.label} on ${signal.target.displayName} with fixed read-only probes."
                                        resilience.investigate()?.highlights()?.forEach { out += it }
                                        resilience.diagnose()?.let { diagnosis -> out += diagnosis.render() }
                                        val moves = resilience.plan()
                                        if (moves.isNotEmpty()) out += "Next: " + moves.joinToString("; ") { it.title }
                                        out += "Open Lucifer's Recover tab to try a move or record an outcome."
                                    }
                                    out
                                }
                                ResilienceRequest.RESEARCH -> {
                                    val brief = resilience.research(route.query.ifBlank { null })
                                    brief?.render()?.lines()?.take(20) ?: listOf("Nothing to look up yet.")
                                }
                                ResilienceRequest.LESSONS -> resilience.lessonOverview().lines()
                                ResilienceRequest.PLAN -> {
                                    if (resilience.state.value.signal == null) {
                                        listOf("No active failure yet — run something that fails first.")
                                    } else {
                                        resilience.plan()
                                            .mapIndexed { index, move -> "${index + 1}. ${move.title} — ${move.instruction}" }
                                            .ifEmpty { listOf("Every known tactic has been tried; new context is needed.") }
                                    }
                                }
                            }
                        } catch (error: Exception) {
                            listOf("Recovery request failed: ${error.message ?: "unknown error"}")
                        }
                        withContext(Dispatchers.Main) {
                            chatMessages = chatMessages + ChatMessage(lines.joinToString("\n").take(3_500), true)
                            chatThinking = false
                            if (shouldSpeak) voice.speak(lines.firstOrNull().orEmpty())
                        }
                    }
                    is ChatRoute.Execute -> withContext(Dispatchers.Main) {
                        val action = route.action
                        if (action.requiresConfirmation) {
                            pendingAction = action
                            chatMessages = chatMessages + ChatMessage(
                                "${action.explanation}\nReview the exact command below before it runs.",
                                true
                            )
                            chatThinking = false
                        } else {
                            chatMessages = chatMessages + ChatMessage(
                                "${action.explanation} Running ${action.command} on ${action.target.displayName}…",
                                true
                            )
                            launchTerminalAction(action, shouldSpeak)
                        }
                    }
                }
            }
        }

        var beatTick by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            // Efficiency: slow tick — the strip needs no 1Hz full-screen recompositions
            while (true) { beatTick++; delay(10_000) }
        }

        // First-run welcome: seed his voice into the terminal panel so a brand-new
        // user immediately sees what this thing does (persisted flag — once only).
        LaunchedEffect(Unit) {
            try {
                val dir = File(filesDir, "memory")
                dir.mkdirs()
                val flag = File(dir, "welcomed.flag")
                if (!flag.exists()) {
                    terminalLines = listOf(
                        "🫀 I am Lucifer — this home screen is my body.",
                        "Search an app or ask in plain language; commands go through chat understanding.",
                        "Choose LOCAL, TERMUX, VM, or SHIZUKU; you can also name a target in your request.",
                        "Sensitive commands ask for confirmation before running."
                    )
                    flag.createNewFile()
                }
            } catch (_: Exception) {}
        }

        val filtered = if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true)
        }
        val queryIsCommand = query.isNotBlank() && filtered.isEmpty()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0A030A), Color(0xFF0D050D), Color(0xFF050208))
                    )
                )
        ) {
            SigilWatermark(alpha = 0.055f)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
            ) {
                HeartbeatStrip(tick = beatTick, knowledgeAlive = reasoner.knowledgeServerAlive())
                Spacer(Modifier.height(8.dp))

                // ── Command bar: speak to Lucifer ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1A0A1A))
                        .border(
                            1.dp,
                            if (queryIsCommand) Color(0xFF8B0000) else Color(0xFF332233),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("λ ", color = Color(0xFFFFD700), fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            // Clever: rotating hints teach the language of the bar
                            val hints = listOf(
                                "search apps or ask in plain English…",
                                "try: show disk space on my VM",
                                "try: list files in Termux",
                                "commands run through chat understanding"
                            )
                            Text(
                                hints[beatTick % hints.size],
                                color = Color(0xFF665566), fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Color(0xFFE0E0E0),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                val cmd = query.trim()
                                if (cmd.isBlank()) return@KeyboardActions
                                // Text goes through intent understanding first, even when it looks like an app name.
                                // Tapping an app tile remains a direct, explicit UI action.
                                showChat = true
                                sendChatMessage(cmd)
                                query = ""
                                kb?.hide()
                            }),
                            cursorBrush = Brush.verticalGradient(listOf(Color(0xFFFFD700), Color(0xFF8B0000))),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // ── Target chips: adaptive (labels when idle, letters while typing) ──
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val chipDefs = listOf(
                            "L" to "LOCAL",
                            "T" to "TERMUX",
                            "V" to "VM",
                            "C" to "CHAT"
                        )
                        chipDefs.forEachIndexed { i, (short, long) ->
                            val sel = target == i
                            val label = if (query.isBlank()) long else short
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sel) Color(0xFF8B0000) else Color(0xFF251525))
                                    .clickable {
                                        if (short == "C") {
                                            showChat = true
                                        } else {
                                            target = i
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    label,
                                    color = if (sel) Color(0xFFFFD700) else Color(0xFF8B7D8B),
                                    fontSize = if (query.isBlank()) 9.sp else 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    if (chatThinking) {
                        Spacer(Modifier.width(6.dp))
                        Text("…", color = Color(0xFFFFD700), fontSize = 14.sp)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ── Live command output (his voice, on-screen) — tap to expand ──
                if (terminalLines.isNotEmpty()) {
                    var outputExpanded by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (outputExpanded) 260.dp else 110.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF12060F).copy(alpha = 0.85f))
                            .border(1.dp, Color(0xFF8B0000).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .clickable { outputExpanded = !outputExpanded }
                            .padding(10.dp)
                    ) {
                        LazyColumn {
                            listItems(if (outputExpanded) terminalLines.takeLast(50) else terminalLines.takeLast(20)) { line ->
                                Text(
                                    text = line,
                                    color = when {
                                        line.startsWith("[ERROR]") -> Color(0xFFFF4444)
                                        line.startsWith("[INFO]") -> Color(0xFF88CCFF)
                                        line.startsWith("$") -> Color(0xFFFFD700)
                                        line.startsWith("[exit") -> Color(0xFF00FF88)
                                        else -> Color(0xFFE0E0E0)
                                    },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                        if (!outputExpanded) {
                            Text(
                                "⋯ tap to expand",
                                color = Color(0xFF554455),
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // ── App grid ──
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    items(filtered, key = { it.pkg }) { app ->
                        AppIcon(app = app) { launchApp(app.pkg) }
                    }
                }

                Spacer(Modifier.height(10.dp))
                DockBar(onLaunch = { pkg -> launchApp(pkg) })
                Spacer(Modifier.height(8.dp))
            }

            // ── Chat overlay (C chip) ──
            if (showChat) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF000000).copy(alpha = 0.35f))
                        .clickable { showChat = false },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    ChatDialog(
                        messages = chatMessages,
                        selectedTarget = CommandTarget.fromLauncherIndex(target),
                        onTargetSelected = { target = it.launcherIndex },
                        pendingAction = pendingAction,
                        pendingDestinationDetails = pendingAction?.let { executor.destinationDetails(it.target.launcherIndex) },
                        onConfirmAction = {
                            pendingAction?.let { action -> launchTerminalAction(action, !voiceMuted) }
                        },
                        onCancelAction = {
                            pendingAction = null
                            chatMessages = chatMessages + ChatMessage("Command cancelled. Nothing was run.", true)
                        },
                        onSend = { text -> sendChatMessage(text) },
                        thinking = chatThinking,
                        voiceMuted = voiceMuted,
                        onToggleVoice = { voiceMuted = !voiceMuted },
                        onClose = { showChat = false }
                    )
                }
            }
        }
    }

    // ───────────────────────────── composables ─────────────────────────────

    @Composable
    private fun HeartbeatStrip(tick: Int, knowledgeAlive: Boolean = false) {
        val alive = LuciferState.serviceAlive
        val pulse by animateFloatAsState(
            targetValue = if (alive) 1f else 0.35f,
            animationSpec = tween(600)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF160A16).copy(alpha = 0.72f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(
                    color = if (alive) LuciferColors.Crimson else Color(0xFF554455),
                    radius = 4.dp.toPx() * pulse
                )
                drawCircle(
                    color = if (alive) Color(0xFFFFD700) else Color(0xFF443344),
                    radius = 2.dp.toPx(),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "LUCIFER ${if (alive) "· ${LuciferState.phase} · CYCLE ${LuciferState.cycle}" else "· DORMANT"}",
                color = if (alive) Color(0xFFFFD700) else Color(0xFF8B7D8B),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            // Knowledge link: Σ = his ToE corpus is reachable
            if (knowledgeAlive) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "Σ",
                    color = LuciferColors.Aether,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "▲$tick",
                color = Color(0xFF665566),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    @Composable
    private fun AppIcon(app: AppEntry, onClick: () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color(0xFF1A0A1A))
                    .border(1.dp, Color(0xFF8B0000).copy(alpha = 0.22f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (app.icon != null) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = app.label,
                        modifier = Modifier.size(44.dp)
                    )
                } else {
                    Text(app.label.take(2).uppercase(), color = Color(0xFF8B7D8B), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                app.label,
                color = Color(0xFFC8BCC8),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(64.dp),
                textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    private fun DockBar(onLaunch: (String) -> Unit) {
        val dock = listOf(
            "com.ashigaru.wallet" to "ASHIGARU",
            "com.android.virtualization.terminal" to "DEBIAN",
            "com.termux" to "TERMUX",
            "com.lucifer.mobile" to "CORE",
            "com.android.dialer" to "DIALER"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF160A16).copy(alpha = 0.85f))
                .border(1.dp, Color(0xFF332233), RoundedCornerShape(16.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            dock.forEach { (pkg, label) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onLaunch(pkg) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Canvas(modifier = Modifier.size(30.dp)) {
                        drawCircle(
                            color = if (pkg == "com.lucifer.mobile") Color(0xFFFFD700) else Color(0xFF8B0000),
                            radius = 13.dp.toPx(),
                            style = Stroke(width = 1.6.dp.toPx())
                        )
                    }
                    Text(label, color = Color(0xFF8B7D8B), fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun SigilWatermark(alpha: Float) {
        val transition = rememberInfiniteTransition(label = "sigil")
        val rot by transition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(120_000, easing = LinearEasing), RepeatMode.Restart),
            label = "rot"
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension * 0.36f
            drawCircle(Color(0xFF8B0000).copy(alpha = alpha * 2f), radius = r, center = c, style = Stroke(width = 1.5.dp.toPx()))
            val path = androidx.compose.ui.graphics.Path()
            for (i in 0 until 7) {
                val ang = Math.toRadians((rot + i * 360.0 / 7.0 - 90.0))
                val x = (c.x + r * 0.92 * kotlin.math.cos(ang)).toFloat()
                val y = (c.y + r * 0.92 * kotlin.math.sin(ang)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, Color(0xFFFFD700).copy(alpha = alpha * 1.4f), style = Stroke(width = 1.2.dp.toPx()))
        }
    }
}
