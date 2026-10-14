package com.lucifer.mobile.launcher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
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
import androidx.core.content.ContextCompat
import com.lucifer.mobile.omni_bridge.equirouter.Horseman
import com.lucifer.mobile.omni_bridge.four_horsemen.FourHorsemenOrchestrator
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
 * Same APK/process as HeartbeatService; his state renders live on your wallpaper.
 * Command bar: app search + command execution (L/T/V) + C (chat with talk-back).
 */
class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        journalLaunch("HOME")

        // Start his heartbeat — without this the strip shows DORMANT and
        // Android freezes the process ~19s after launch.
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, com.lucifer.mobile.omni_bridge.heartbeat.LuciferHeartbeatService::class.java)
            )
        } catch (e: Exception) {
            Log.e("LuciferLauncher", "heartbeat service start failed", e)
        }

        // His voice obeys the persisted Settings toggle on every cold start.
        val voice = LuciferVoice(this)
        voice.setEnabled(loadVoiceEnabledPref())
        val brain = FourHorsemenOrchestrator(this)
        val reasoner = ToEReasoningEngine(this, brain)
        setContent { LuciferLauncherScreen(voice, brain, reasoner) }
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
        brain: FourHorsemenOrchestrator,
        reasoner: ToEReasoningEngine
    ) {
        val apps by remember { mutableStateOf(loadApps()) }
        var query by remember { mutableStateOf("") }
        var terminalLines by remember { mutableStateOf(LuciferState.recent()) }
        var executing by remember { mutableStateOf(false) }
        var target by remember { mutableStateOf(0) } // 0 LOCAL, 1 TERMUX, 2 VM
        var showChat by remember { mutableStateOf(false) }
        var chatMessages by remember { mutableStateOf(listOf<ChatMessage>()) }
        var voiceMuted by remember { mutableStateOf(false) }
        var chatThinking by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val executor = remember { TerminalExecutor(this@LauncherActivity, scope) }
        val kb = LocalSoftwareKeyboardController.current

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
                        "λ bar: type an app to launch, a command to run, or tap C to talk.",
                        "L=LOCAL · T=TERMUX · V=VM · C=CHAT",
                        "Ask me anything. I reason from the Omega axioms."
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
                                "type an app name…",
                                "or a command: df /data",
                                "or ask: what is entropy",
                                "chips: L local · T termux · V vm · C chat"
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
                                if (queryIsCommand) {
                                    executing = true
                                    terminalLines = terminalLines + "$ $cmd"
                                    query = ""
                                    scope.launch(Dispatchers.IO) {
                                        val out = executor.execute(cmd, target)
                                        val reply = brain.execute(cmd, Horseman.FAMINE)
                                        withContext(Dispatchers.Main) {
                                            terminalLines = (terminalLines + out).takeLast(50)
                                            executing = false
                                            if (!voiceMuted) {
                                                voice.speakResult(out)
                                                voice.speak(reply)
                                            }
                                        }
                                    }
                                } else {
                                    filtered.firstOrNull()?.let { launchApp(it.pkg) }
                                }
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

                    if (executing) {
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
                        targetLabel = listOf("LOCAL", "TERMUX", "VM")[target.coerceIn(0, 2)],
                        onSend = { text ->
                            chatMessages = chatMessages + ChatMessage(text, false)
                            chatThinking = true
                            val first = text.trim().split(" ").firstOrNull()?.lowercase() ?: ""
                            val looksLikeCommand = text.contains(Regex("[;&|>]")) ||
                                    first in setOf(
                                        "ls", "cd", "cat", "echo", "pwd", "uname", "df", "ps", "grep",
                                        "whoami", "hostname", "id", "uptime", "free", "top", "curl",
                                        "wget", "ssh", "python", "sh", "bash", "env", "which", "man",
                                        "mkdir", "touch", "rm", "cp", "mv", "chmod", "sudo", "apt",
                                        "git", "nano", "vim", "date", "cal", "ifconfig", "ip", "ping",
                                        "netstat", "kill", "sleep", "head", "tail", "sort", "find"
                                    )
                            scope.launch(Dispatchers.IO) {
                                if (looksLikeCommand) {
                                    val out = executor.execute(text, target)
                                    val reply = brain.execute(text, Horseman.FAMINE)
                                    withContext(Dispatchers.Main) {
                                        chatMessages = chatMessages +
                                                ChatMessage(out.joinToString("\n"), true) +
                                                ChatMessage(reply, true)
                                        if (!voiceMuted) voice.speak(reply)
                                        chatThinking = false
                                        terminalLines = (terminalLines + out).takeLast(50)
                                    }
                                } else {
                                    // Plain conversation — super-reasoning pass:
                                    // PERCEIVE → UNDERSTAND (ToE corpus) → DECIDE → ACT
                                    val reply = reasoner.reason(text)
                                    withContext(Dispatchers.Main) {
                                        chatMessages = chatMessages + ChatMessage(reply, true)
                                        if (!voiceMuted) voice.speak(reply)
                                        chatThinking = false
                                    }
                                }
                            }
                        },
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
