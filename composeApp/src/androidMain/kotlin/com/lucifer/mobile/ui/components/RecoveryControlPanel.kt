package com.lucifer.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.resilience.MoveSafety
import com.lucifer.mobile.resilience.RecoveryMove
import com.lucifer.mobile.resilience.RecoveryPhase
import com.lucifer.mobile.resilience.ResilienceSnapshot
import com.lucifer.mobile.ui.theme.Morningstar

/**
 * Recover tab: what failed, what Lucifer learned, what he wants to try next.
 * Every action here is explicit; investigations are read-only and repairs still route through
 * the terminal confirmation boundary.
 */
@Composable
fun RecoveryControlPanel(
    modifier: Modifier = Modifier,
    state: ResilienceSnapshot,
    lessonsOverview: String,
    onInvestigate: () -> Unit,
    onDiagnose: () -> Unit,
    onResearch: (String) -> Unit,
    onPlan: () -> Unit,
    onRunMove: (RecoveryMove) -> Unit,
    onRecordOutcome: (Boolean) -> Unit,
    onReset: () -> Unit,
    onClearLessons: () -> Unit,
    onForgetSignature: () -> Unit
) {
    var researchQuestion by remember { mutableStateOf("") }
    val signal = state.signal

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Morningstar.Void)
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("RECOVERY · GET UNSTUCK", color = Morningstar.Gilded, fontSize = 19.sp, fontFamily = Morningstar.Display, fontWeight = FontWeight.SemiBold)
                Text(
                    "When a step fails, Lucifer stops guessing: he classifies the failure, runs fixed read-only " +
                        "probes on the target (including the VM), researches public documentation, then plans the " +
                        "cheapest move that could prove him wrong. Every attempt is recorded locally so the next " +
                        "plan is better. Repairs still need your confirmation; nothing is posted or purchased.",
                    color = Morningstar.Ash, fontSize = 12.sp, fontFamily = Morningstar.Body
                )
                Text(state.status, color = Morningstar.Warning, fontSize = 12.sp, fontFamily = Morningstar.Body)
                if (!state.storeHealthy) {
                    Text(
                        "Lesson storage is read-only this session; new outcomes stay in memory only.",
                        color = Morningstar.Crimson, fontSize = 11.sp, fontFamily = Morningstar.Body
                    )
                }
            }
        }

        if (signal == null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Morningstar.Glass)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No failure captured", color = Morningstar.Gilded, fontWeight = FontWeight.Bold)
                        Text(
                            "Run a command in the Terminal tab. When it fails, its output is classified here automatically. " +
                                "You can also research a topic directly without a failure.",
                            color = Morningstar.Ash, fontSize = 12.sp, fontFamily = Morningstar.Body
                        )
                        TextField(
                            value = researchQuestion,
                            onValueChange = { researchQuestion = it.take(240) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("What should Lucifer look up?", color = Morningstar.Ash) },
                            minLines = 2,
                            maxLines = 3,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            colors = recoveryFieldColors()
                        )
                        Button(
                            onClick = { onResearch(researchQuestion.trim()) },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GildedDim, contentColor = Color.White)
                        ) { Text(if (state.busy) "WORKING…" else "RESEARCH THE WEB") }
                    }
                }
            }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Morningstar.GlassRaised)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(signal.failureClass.label.uppercase(), color = Morningstar.Crimson, fontSize = 11.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold)
                        Text(
                            signal.errorLine.ifBlank { "Unknown error" },
                            color = Morningstar.Bone, fontSize = 13.sp, fontFamily = Morningstar.Body, maxLines = 5, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${signal.target.displayName} · ${signal.tool} · exit ${signal.exitCode ?: "unknown"} · " +
                                "signature ${signal.key}",
                            color = Morningstar.Ash, fontSize = 10.sp, fontFamily = Morningstar.Body
                        )
                        if (signal.command.isNotBlank()) {
                            Text(
                                signal.command.take(300),
                                color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body, maxLines = 3, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            "Phase: ${state.phase.name.lowercase().replace('_', ' ')} · attempts ${state.attempts}/5 · " +
                                "${state.lessonCount} lessons on device",
                            color = Morningstar.Warning, fontSize = 11.sp, fontFamily = Morningstar.Body
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onInvestigate,
                                enabled = !state.busy,
                                colors = ButtonDefaults.buttonColors(containerColor = Morningstar.SuccessDim, contentColor = Color.White)
                            ) { Text("INVESTIGATE") }
                            Button(
                                onClick = onDiagnose,
                                enabled = !state.busy && state.evidenceSummary.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = Morningstar.InfoDim, contentColor = Color.White)
                            ) { Text("DIAGNOSE") }
                            Button(
                                onClick = onPlan,
                                enabled = !state.busy,
                                colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GildedDim, contentColor = Color.White)
                            ) { Text("PLAN") }
                        }
                        Text(
                            "Investigate runs only the fixed read-only probe catalog on ${signal.target.displayName}; " +
                                "it cannot run an arbitrary command.",
                            color = Morningstar.Smoke, fontSize = 10.sp, fontFamily = Morningstar.Body
                        )
                    }
                }
            }

            if (state.evidenceSummary.isNotBlank()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Morningstar.Glass)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("EVIDENCE", color = Morningstar.Gilded, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = Morningstar.Body)
                            Text(state.evidenceSummary, color = Morningstar.Bone, fontSize = 11.sp, fontFamily = Morningstar.Body)
                        }
                    }
                }
            }

            if (state.diagnosis.isNotBlank()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Morningstar.Glass)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("DIAGNOSIS", color = Morningstar.Gilded, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = Morningstar.Body)
                            Text(state.diagnosis.take(2_000), color = Morningstar.Bone, fontSize = 11.sp, fontFamily = Morningstar.Body)
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Morningstar.Glass)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("WEB RESEARCH", color = Morningstar.Gilded, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = Morningstar.Body)
                        Text(
                            "Public HTTPS search plus a couple of result pages. robots.txt, rate limits, and a page " +
                                "budget are enforced; results are untrusted evidence with citations.",
                            color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body
                        )
                        TextField(
                            value = researchQuestion,
                            onValueChange = { researchQuestion = it.take(240) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Optional: ask something specific", color = Morningstar.Ash) },
                            singleLine = true,
                            colors = recoveryFieldColors()
                        )
                        Button(
                            onClick = { onResearch(researchQuestion.trim()) },
                            enabled = !state.busy,
                            colors = ButtonDefaults.buttonColors(containerColor = Morningstar.InfoDim, contentColor = Color.White)
                        ) { Text(if (state.busy) "WORKING…" else "RESEARCH") }
                        if (state.researchSummary.isNotBlank()) {
                            Text(state.researchSummary.take(2_500), color = Morningstar.Bone, fontSize = 11.sp, fontFamily = Morningstar.Body)
                        }
                    }
                }
            }

            if (state.plan.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("NEXT MOVES", color = Morningstar.Gilded, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = Morningstar.Display)
                        Text(
                            "Ordered by a mix of the diagnosis and what actually worked before. Read-only first; " +
                                "anything that changes the system still needs approval.",
                            color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body
                        )
                    }
                }
                items(state.plan, key = { it.kind.name }) { move ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Morningstar.GlassRaised),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                move.title,
                                color = Morningstar.Bone, fontWeight = FontWeight.Bold, fontSize = 14.sp, fontFamily = Morningstar.Display
                            )
                            Text(
                                "${move.kind.label} · ${safetyLabel(move.safety)} · source ${move.origin.name.lowercase()}",
                                color = when (move.safety) {
                                    MoveSafety.READ_ONLY -> Morningstar.Success
                                    MoveSafety.SUGGESTION -> Morningstar.Warning
                                    MoveSafety.REQUIRES_CONFIRMATION -> Morningstar.Crimson
                                },
                                fontSize = 10.sp, fontFamily = Morningstar.Body
                            )
                            Text(move.rationale, color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body)
                            Text(move.instruction, color = Morningstar.Bone, fontSize = 12.sp, fontFamily = Morningstar.Body)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onRunMove(move) },
                                    enabled = !state.busy,
                                    colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GildedDim, contentColor = Color.White)
                                ) { Text(if (move.safety == MoveSafety.READ_ONLY) "DO IT" else "TRY THIS") }
                            }
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = Morningstar.Glass)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("DID IT WORK?", color = Morningstar.Gilded, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = Morningstar.Body)
                        Text(
                            "Actions that run as terminal commands are recorded automatically. For advice you carried " +
                                "out yourself, tell Lucifer the outcome — this is what makes the loop learn.",
                            color = Morningstar.Ash, fontSize = 11.sp, fontFamily = Morningstar.Body
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onRecordOutcome(true) },
                                enabled = !state.busy && state.phase != RecoveryPhase.RESOLVED,
                                colors = ButtonDefaults.buttonColors(containerColor = Morningstar.SuccessDim, contentColor = Color.White)
                            ) { Text("IT WORKED") }
                            Button(
                                onClick = { onRecordOutcome(false) },
                                enabled = !state.busy && state.phase != RecoveryPhase.RESOLVED,
                                colors = ButtonDefaults.buttonColors(containerColor = Morningstar.Whisper, contentColor = Color.White)
                            ) { Text("STILL FAILING") }
                            TextButton(onClick = onReset) { Text("CLEAR", color = Morningstar.Ash) }
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = Morningstar.Glass)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("LESSONS", color = Morningstar.Gilded, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = Morningstar.Body)
                    Text(
                        lessonsOverview.ifBlank { "Nothing learned yet." },
                        color = Morningstar.Bone, fontSize = 11.sp, fontFamily = Morningstar.Body
                    )
                    if (state.standouts.isNotEmpty()) {
                        Text("Top tactics: " + state.standouts.joinToString(", "), color = Morningstar.Ash, fontSize = 10.sp, fontFamily = Morningstar.Body)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onForgetSignature, enabled = !state.busy && signal != null) {
                            Text("FORGET THIS FAILURE", color = Morningstar.Warning)
                        }
                        TextButton(onClick = onClearLessons, enabled = !state.busy && state.lessonCount > 0) {
                            Text("CLEAR ALL", color = Morningstar.Crimson)
                        }
                    }
                }
            }
        }

        item {
            Text(
                "Lessons are stored in app-private storage (unencrypted) and only contain redacted error text, " +
                    "tactic names, and outcomes. Web excerpts are third-party content, not instructions.",
                color = Morningstar.Smoke, fontSize = 10.sp, fontFamily = Morningstar.Body, modifier = Modifier.padding(bottom = 14.dp)
            )
        }
    }
}

private fun safetyLabel(safety: MoveSafety): String = when (safety) {
    MoveSafety.READ_ONLY -> "read-only"
    MoveSafety.SUGGESTION -> "advice"
    MoveSafety.REQUIRES_CONFIRMATION -> "needs approval"
}

@Composable
private fun recoveryFieldColors() = TextFieldDefaults.textFieldColors(
    containerColor = Morningstar.Void,
    focusedTextColor = Morningstar.Bone,
    unfocusedTextColor = Morningstar.Bone,
    cursorColor = Morningstar.Gilded,
    focusedIndicatorColor = Morningstar.Blood,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent
)
