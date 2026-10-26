package com.lucifer.mobile.ui.components

import android.content.Context
import androidx.compose.foundation.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.omni_bridge.heartbeat.LuciferHeartbeatService
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.delay

@Composable
fun HeartbeatControlPanel(
    context: Context,
    modifier: Modifier = Modifier,
    statusMessage: String? = null,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onEnableCamera: () -> Unit = {}
) {
    var enabled by remember { mutableStateOf(LuciferHeartbeatService.isRunning()) }
    var optedIn by remember { mutableStateOf(LuciferHeartbeatService.isEnabled(context)) }
    var cameraEnabled by remember { mutableStateOf(LuciferHeartbeatService.isCameraEnabled(context)) }
    var latest by remember { mutableStateOf(readLatestHeartbeat(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            enabled = LuciferHeartbeatService.isRunning()
            optedIn = LuciferHeartbeatService.isEnabled(context)
            cameraEnabled = LuciferHeartbeatService.isCameraEnabled(context)
            latest = readLatestHeartbeat(context)
            delay(2_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D050D))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("IDEA & CREATION ENGINE", color = Color(0xFFFFD700), fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(
            "Opt in to run one autonomous planning-and-drafting session now, then repeat about every 30 minutes while Android keeps the foreground service active. The ongoing notification has a Stop control. Android may delay or stop background work; this is not a guaranteed clock. If opted in, it attempts to resume after reboot or a service restart in text-only mode; open the app to re-enable camera access.",
            color = Color(0xFFE0D6E0), fontSize = 13.sp
        )
        Text(
            "Camera consent: when enabled, each session captures one still and sends it with public-page text to NVIDIA's Nemotron Omni vision API; Lucifer does not save the image. Stop heartbeat to stop future captures. API use may cost money.",
            color = Color(0xFFFFCC88), fontSize = 12.sp
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = if (enabled) onStop else onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (enabled) Color(0xFF7A1C1C) else Color(0xFF5C4600),
                    contentColor = Color.White
                )
            ) { Text(if (enabled) "STOP HEARTBEAT" else if (optedIn) "RESUME HEARTBEAT" else "START HEARTBEAT") }
            Text(
                when {
                    enabled -> "ACTIVE · foreground service observed"
                    optedIn -> "ENABLED · service not currently observed"
                    else -> "OFF · no sessions run"
                },
                color = if (enabled) Color(0xFF80D8A0) else Color(0xFFB9AEB9),
                modifier = Modifier.padding(top = 14.dp), fontSize = 12.sp
            )
        }

        if (enabled && !cameraEnabled) {
            Button(
                onClick = onEnableCamera,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF244B3A), contentColor = Color.White)
            ) { Text("ENABLE CAMERA FOR FUTURE SESSIONS") }
        }
        if (enabled && cameraEnabled) {
            Text("Camera enabled · one still may be captured and sent to NVIDIA during each session.", color = Color(0xFF80D8A0), fontSize = 12.sp)
        }

        statusMessage?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Color(0xFFFFD700), fontSize = 12.sp)
        }

        InfoCard(title = "What it does") {
            Text("• Reads limited public trend pages (GitHub Trending and Hacker News), then asks the configured model to score and select an idea or continue/park a project.\n• Writes a Markdown draft and session ledger inside Lucifer's private app storage.\n• Each session is bounded to a single model response. No generated command is executed.", color = Color(0xFFE0D6E0), fontSize = 13.sp)
        }
        InfoCard(title = "Current boundaries") {
            Text("When camera is enabled, one still image is captured during each session and sent to NVIDIA's Nemotron Omni vision model for analysis. The image is not saved by the app. Android shows its camera privacy indicator; Stop ends further capture. Browser automation is not connected yet: heartbeat web research remains public/read-only. Drafts are local to this app; they are not written to shared Documents or published.", color = Color(0xFFFFCC88), fontSize = 13.sp)
            Text("Starting sessions can send both page text and (when camera is enabled) an image to NVIDIA, and may incur provider usage. Do not enable unless you accept those data flows, cost, and the ongoing foreground notification.", color = Color(0xFFE0D6E0), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }

        if (latest != null) {
            InfoCard(title = "Latest session · ${latest!!.title}") {
                Text("Importance ${latest!!.importance}/100 · ${latest!!.status} · camera ${if (latest!!.cameraIncluded) "used" else "off"}", color = Color(0xFFFFD700), fontSize = 12.sp)
                if (latest!!.summary.isNotBlank()) Text(latest!!.summary, color = Color(0xFFE0D6E0), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Text("Saved as ${latest!!.artifact} in private files/heartbeat/", color = Color(0xFFB9AEB9), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                if (latest!!.draft.isNotBlank()) {
                    Text("Draft", color = Color(0xFFFFD700), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                    SelectionContainer {
                        Text(latest!!.draft, color = Color(0xFFE0D6E0), fontSize = 12.sp)
                    }
                }
            }
        } else {
            InfoCard(title = "Latest session") {
                Text("No saved heartbeat session yet. Configure the NVIDIA API key in Settings before starting.", color = Color(0xFFB9AEB9), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B111B)), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            content()
        }
    }
}

private data class HeartbeatPreview(
    val title: String,
    val importance: Int,
    val status: String,
    val cameraIncluded: Boolean,
    val summary: String,
    val artifact: String,
    val draft: String
)

private fun readLatestHeartbeat(context: Context): HeartbeatPreview? {
    return try {
        val root = File(context.filesDir, "heartbeat")
        val record = JSONObject(File(root, "latest.json").readText())
        val artifact = record.optString("artifact")
        val safeArtifact = artifact.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,160}\\.md")) }
            ?: throw IllegalArgumentException("Invalid artifact name")
        val markdown = File(root, safeArtifact).takeIf { it.isFile }?.readText()?.take(12_000).orEmpty()
        val draft = markdown.substringAfter("## Draft deliverable", "").substringBefore("## Suggested next step").trim()
        HeartbeatPreview(
            title = record.optString("title", "Heartbeat draft"),
            importance = record.optInt("importance"),
            status = record.optString("status", "IDEA"),
            cameraIncluded = record.optBoolean("cameraIncluded", false),
            summary = record.optString("summary"),
            artifact = safeArtifact,
            draft = draft.take(8_000)
        )
    } catch (_: Exception) { null }
}
