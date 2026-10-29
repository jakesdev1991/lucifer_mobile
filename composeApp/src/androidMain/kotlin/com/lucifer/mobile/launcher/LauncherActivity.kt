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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
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
import com.lucifer.mobile.config.AppConfig
import com.lucifer.mobile.surface.Surface
import com.lucifer.mobile.skills.SkillSynthRuntime
import com.lucifer.mobile.terminal.TerminalExecutor
import com.lucifer.mobile.ui.components.SigilClock
import com.lucifer.mobile.ui.effects.SigilBackdrop
import com.lucifer.mobile.ui.effects.SigilNeedle
import com.lucifer.mobile.ui.effects.SigilRule
import com.lucifer.mobile.ui.effects.glassSurface
import com.lucifer.mobile.ui.effects.glowShadow
import com.lucifer.mobile.ui.effects.pressPulse
import com.lucifer.mobile.ui.effects.rememberBreathingAmount
import com.lucifer.mobile.ui.theme.LuciferColors
import com.lucifer.mobile.ui.theme.Morningstar
import com.lucifer.mobile.ui.theme.shard
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

    /** Read the persisted voice preference (written by Settings → VOICE). Default is on. */
    private fun loadVoiceEnabledPref(): Boolean =
        AppConfig.readFlag(this, AppConfig.VOICE_ENABLED, default = true)

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
            "agents", "general", "tactician" -> 9
            "world", "dhizuku", "privilege" -> 10
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

    private val dockPackages = setOf(
        "com.termux",
        "com.android.virtualization.terminal",
        "com.ashigaru.wallet",
        "com.lucifer.mobile",
        "com.android.dialer"
    )

    // ───────────────────────────── screen ─────────────────────────────

    @Composable
    private fun LuciferLauncherScreen(
        voice: LuciferVoice,
        reasoner: ToEReasoningEngine
    ) {
        // loadApps() queries every launcher activity and draws an icon bitmap for each one. Doing that
        // inside remember{} means the home screen cannot paint until it finishes — on a phone with a
        // hundred apps that is the whole cold start. The grid fills in a frame later instead.
        var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
        LaunchedEffect(Unit) {
            apps = withContext(Dispatchers.IO) { loadApps() }
        }
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
                // Judge this message against the last answer before anything new is produced: this is
                // the only moment where "no, that's wrong" can still be tied to the turn it is about.
                // Runs on IO because it may load and rewrite the learned-skill file.
                SkillSynthRuntime.observeIncoming(this@LauncherActivity, text, Surface.LAUNCHER_CHAT.name)
                val route = try {
                    router.understand(text, selectedTarget, history, apps.map { it.label })
                } catch (e: Exception) {
                    ChatRoute.Clarify("I couldn't understand that request. Try rephrasing it or choose LOCAL, TERMUX, VM, or SHIZUKU.")
                }
                when (route) {
                    ChatRoute.Conversation -> {
                        val answer = try { reasoner.reason(text, Surface.LAUNCHER_CHAT) } catch (e: Exception) { "I couldn't complete that reply: ${e.message}" }
                        localMemory.rememberExchange(text, answer)
                        // Attributed to the learned skills that were in this turn's prompt: a
                        // correction of this answer becomes their loss, praise becomes their win.
                        SkillSynthRuntime.recordAnswer(this, text, answer, Surface.LAUNCHER_CHAT.name)
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

        Box(modifier = Modifier.fillMaxSize()) {
            // True black canvas: vignette, compass rings, drifting dust. No wallpaper.
            SigilBackdrop()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp)
            ) {
                Spacer(Modifier.height(12.dp))

                // ── masthead: the sigil on the left, his name and state on the right ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SigilClock(size = 146.dp, onClick = { showChat = true })
                    Spacer(Modifier.width(18.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            "LUCIFER",
                            style = Morningstar.title(23).copy(color = Morningstar.Bone),
                            maxLines = 1
                        )
                        Text(
                            "MORNINGSTAR SHELL",
                            style = Morningstar.overline(8),
                            maxLines = 1
                        )
                        SigilRule(Modifier.padding(vertical = 3.dp), Morningstar.Edge)
                        StatusLine(
                            alive = LuciferState.serviceAlive,
                            knowledgeAlive = reasoner.knowledgeServerAlive(),
                            phase = LuciferState.phase,
                            cycle = LuciferState.cycle
                        )
                        Text(
                            "HOLD THE SIGIL TO SPEAK",
                            style = Morningstar.caption(8).copy(color = Morningstar.Smoke, letterSpacing = 1.1.sp),
                            maxLines = 1
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── command bar: a glass shard with a crimson caret ──
                val barShape = remember { shard(12.dp) }
                val hint = rememberRotatingHint()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(barShape, if (queryIsCommand) Morningstar.Crimson else Morningstar.CrimsonLine)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "λ",
                        style = Morningstar.title(17).copy(color = Morningstar.Crimson),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(hint, style = Morningstar.caption(12).copy(color = Morningstar.Smoke), maxLines = 1)
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = Morningstar.body(13).copy(color = Morningstar.Bone),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                val cmd = query.trim()
                                if (cmd.isBlank()) return@KeyboardActions
                                showChat = true
                                sendChatMessage(cmd)
                                query = ""
                                kb?.hide()
                            }),
                            cursorBrush = Brush.verticalGradient(
                                listOf(Morningstar.Crimson, Morningstar.Ember)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val chipDefs = listOf("L" to "LOCAL", "T" to "TERMUX", "V" to "VM", "C" to "CHAT")
                        chipDefs.forEachIndexed { i, (short, long) ->
                            val sel = target == i
                            val label = if (query.isBlank()) long else short
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        if (short == "C") showChat = true else target = i
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    label,
                                    style = Morningstar.overline(if (query.isBlank()) 7 else 9).copy(
                                        color = if (sel) Morningstar.Bone else Morningstar.Smoke
                                    ),
                                    maxLines = 1
                                )
                                Box(
                                    modifier = Modifier
                                        .width(if (sel) 18.dp else 0.dp)
                                        .height(1.dp)
                                        .background(Morningstar.Crimson)
                                )
                            }
                        }
                    }
                    if (chatThinking) {
                        Spacer(Modifier.width(6.dp))
                        Text("…", style = Morningstar.body(13).copy(color = Morningstar.Gilded))
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ── his voice: live output, collapsed by default ──
                if (terminalLines.isNotEmpty()) {
                    var outputExpanded by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (outputExpanded) 250.dp else 96.dp)
                            .glassSurface(shard(10.dp), Morningstar.CrimsonLine)
                            .clickable { outputExpanded = !outputExpanded }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        LazyColumn {
                            listItems(
                                if (outputExpanded) terminalLines.takeLast(50) else terminalLines.takeLast(16)
                            ) { line ->
                                Text(
                                    text = line,
                                    style = Morningstar.mono(10).copy(
                                        color = when {
                                            line.startsWith("[ERROR]") -> Morningstar.Crimson
                                            line.startsWith("[INFO]") -> Morningstar.Info
                                            line.startsWith("$") -> Morningstar.Gilded
                                            line.startsWith("[exit") -> Morningstar.Success
                                            else -> Morningstar.Bone.copy(alpha = 0.82f)
                                        }
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                        if (!outputExpanded) {
                            Text(
                                "▲ EXPAND",
                                style = Morningstar.overline(7).copy(color = Morningstar.Smoke),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // ── the drawer: typography, not tiles. A wide empty gutter keeps it asymmetric. ──
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    Column(
                        modifier = Modifier
                            .width(30.dp)
                            .fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(34.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Morningstar.Crimson.copy(alpha = 0.5f))
                                    )
                                )
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            filtered.size.toString().padStart(2, '0'),
                            style = Morningstar.overline(8).copy(color = Morningstar.Smoke)
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .weight(1f)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Morningstar.EdgeQuiet, Color.Transparent)
                                    )
                                )
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                    ) {
                        listItemsIndexed(filtered, key = { _, app -> app.pkg }) { position, app ->
                            AppRow(
                                app = app,
                                highlighted = app.pkg in dockPackages,
                                index = position + 1
                            ) { launchApp(app.pkg) }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                SovereignDock(
                    onLaunch = { pkg -> launchApp(pkg) },
                    onOpenChat = { showChat = true }
                )
                Spacer(Modifier.height(8.dp))
            }

            // ── Chat overlay (C chip) ──
            if (showChat) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Morningstar.Void.copy(alpha = 0.35f))
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

    /**
     * The command bar's hint line.
     *
     * This used to be driven by a screen-level `beatTick`, which recomposed the masthead, drawer and
     * dock every ten seconds to change one string. The ticker now lives in the composable that reads
     * it, so only this text recomposes.
     */
    @Composable
    private fun rememberRotatingHint(): String {
        val hints = remember {
            listOf(
                "search apps or ask in plain English",
                "try: show disk space on my VM",
                "try: list files in Termux",
                "commands run through chat understanding"
            )
        }
        var index by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(10_000)
                index = (index + 1) % hints.size
            }
        }
        return hints[index]
    }

    /** Name, pulse, and cycle: the launcher's only status readout. */
    @Composable
    private fun StatusLine(
        alive: Boolean,
        knowledgeAlive: Boolean,
        phase: String,
        cycle: Int
    ) {
        val breath = rememberBreathingAmount(min = 0.55f, max = 1f, periodMillis = 3_200)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(
                    color = (if (alive) Morningstar.Crimson else Morningstar.Smoke).copy(alpha = 0.25f + 0.5f * breath),
                    radius = 3.2.dp.toPx()
                )
                drawCircle(
                    color = if (alive) Morningstar.Ember else Morningstar.Smoke,
                    radius = 1.2.dp.toPx()
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                if (alive) "AWAKE · $phase · CYCLE $cycle" else "DORMANT",
                style = Morningstar.overline(8).copy(
                    color = if (alive) Morningstar.Ash else Morningstar.Smoke
                ),
                maxLines = 1
            )
            if (knowledgeAlive) {
                Spacer(Modifier.width(7.dp))
                Text("Σ", style = Morningstar.title(10).copy(color = Morningstar.Info))
            }
        }
    }

    /** One app, as a line of type. Favourites carry a faint bloom; nothing else competes. */
    @Composable
    private fun AppRow(
        app: AppEntry,
        highlighted: Boolean,
        index: Int,
        onClick: () -> Unit
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressPulse(
                    pressed = pressed,
                    shape = shard(8.dp),
                    color = Morningstar.CrimsonDim,
                    maxSpread = 0.03f
                )
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .background(if (pressed) Morningstar.GlassRaised else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                index.toString().padStart(2, '0'),
                style = Morningstar.overline(8).copy(color = Morningstar.Whisper),
                modifier = Modifier.width(22.dp)
            )
            Text(
                app.label,
                style = Morningstar.body(14).copy(
                    color = if (highlighted) Morningstar.Bone else Morningstar.Bone.copy(alpha = 0.78f),
                    shadow = if (highlighted) glowShadow(Morningstar.Ember, 12f) else null
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (highlighted) {
                SigilNeedle(Modifier.padding(start = 8.dp), Morningstar.Crimson, 10.dp)
            }
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(1.dp)
                    .background(if (pressed) Morningstar.Crimson else Color.Transparent)
            )
        }
    }

    /** The dock: five circles of glass, no labels shouting. */
    @Composable
    private fun SovereignDock(onLaunch: (String) -> Unit, onOpenChat: () -> Unit) {
        val dock = listOf(
            "com.termux" to "TERMUX",
            "com.android.virtualization.terminal" to "VM",
            "com.ashigaru.wallet" to "VAULT",
            "com.lucifer.mobile" to "CORE",
            "com.android.dialer" to "DIAL"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(shard(16.dp), Morningstar.EdgeQuiet)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            dock.forEach { (pkg, label) ->
                val core = pkg == "com.lucifer.mobile"
                val dockSource = remember { MutableInteractionSource() }
                val dockPressed by dockSource.collectIsPressedAsState()
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .pressPulse(
                            pressed = dockPressed,
                            shape = shard(10.dp),
                            color = if (core) Morningstar.Gilded else Morningstar.CrimsonDim,
                            maxSpread = 0.05f
                        )
                        .clickable(interactionSource = dockSource, indication = null) { onLaunch(pkg) }
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Canvas(modifier = Modifier.size(28.dp)) {
                        drawCircle(
                            color = if (core) Morningstar.Gilded else Morningstar.Crimson,
                            radius = 12.dp.toPx(),
                            style = Stroke(width = 1.dp.toPx())
                        )
                        drawCircle(
                            color = (if (core) Morningstar.Gilded else Morningstar.Crimson).copy(alpha = 0.12f),
                            radius = 12.dp.toPx()
                        )
                        drawCircle(
                            color = if (core) Morningstar.Gilded else Morningstar.Crimson.copy(alpha = 0.8f),
                            radius = 1.6.dp.toPx()
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(label, style = Morningstar.overline(7).copy(color = Morningstar.Smoke))
                }
            }
            val speakSource = remember { MutableInteractionSource() }
            val speakPressed by speakSource.collectIsPressedAsState()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .pressPulse(
                        pressed = speakPressed,
                        shape = shard(10.dp),
                        color = Morningstar.Crimson,
                        maxSpread = 0.05f
                    )
                    .clickable(interactionSource = speakSource, indication = null) { onOpenChat() }
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text("λ", style = Morningstar.title(17).copy(color = Morningstar.Crimson))
                Text("SPEAK", style = Morningstar.overline(7).copy(color = Morningstar.Ash))
            }
        }
    }
}
