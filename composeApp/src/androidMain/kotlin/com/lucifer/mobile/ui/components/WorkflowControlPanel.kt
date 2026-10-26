package com.lucifer.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.lucifer.mobile.launcher.CommandTarget
import com.lucifer.mobile.workflows.WorkflowBlueprints
import com.lucifer.mobile.workflows.WorkflowStepStatus
import com.lucifer.mobile.workflows.WorkflowTask
import com.lucifer.mobile.workflows.WorkflowTaskStatus

@Composable
fun WorkflowControlPanel(
    modifier: Modifier = Modifier,
    tasks: List<WorkflowTask>,
    selectedTaskId: String?,
    busy: Boolean,
    statusMessage: String,
    onSelectTask: (String) -> Unit,
    onCreateTask: (blueprintId: String, goal: String, target: CommandTarget) -> Unit,
    onRunStep: (taskId: String, stepId: String, instruction: String) -> Unit,
    onRecordObservation: (taskId: String, stepId: String, observation: String) -> Unit,
    onReflect: (taskId: String) -> Unit,
    onAcceptReview: (taskId: String) -> Unit,
    onRetryStep: (taskId: String, stepId: String) -> Unit,
    onPauseResume: (taskId: String) -> Unit,
    onComplete: (taskId: String) -> Unit,
    onDelete: (taskId: String) -> Unit
) {
    var goal by remember { mutableStateOf("") }
    var blueprintId by remember { mutableStateOf(WorkflowBlueprints.all.first().id) }
    var selectedTarget by remember { mutableStateOf(CommandTarget.TERMUX) }
    var blueprintMenu by remember { mutableStateOf(false) }
    val selected = tasks.firstOrNull { it.id == selectedTaskId }
    val blueprint = WorkflowBlueprints.get(blueprintId)
    val currentStep = selected?.currentStep
    var extraContext by remember(selectedTaskId, currentStep?.id) { mutableStateOf("") }
    var observation by remember(selectedTaskId, currentStep?.id) { mutableStateOf("") }
    val queuedSteps = selected?.let { task ->
        task.steps.filter { it.status == WorkflowStepStatus.READY && it.id != task.currentStepId }
    }.orEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Color(0xFF0D050D)).padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ITERATIVE WORKFLOWS", color = Color(0xFFFFD700), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Create a bounded task, run one step, inspect its evidence, then reflect and adapt. Task goals, step outputs, and manual observations may be sent to the configured NVIDIA model for planning/review. Saved task text is private to this app but not encrypted; common token/password patterns are redacted from captured output, not every possible secret. No background task loop; terminal actions remain on the selected target and writes still require Lucifer's existing confirmation.",
                    color = Color(0xFFB8AEB8), fontSize = 12.sp
                )
                Text(statusMessage, color = Color(0xFFFFCC88), fontSize = 12.sp)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B111B))) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("NEW WORKFLOW", color = Color(0xFFFFD700), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { blueprintMenu = true }, enabled = !busy) {
                        Text("${blueprint?.title ?: "Choose a blueprint"} ▾", color = Color(0xFFE0D6E0))
                    }
                    DropdownMenu(expanded = blueprintMenu, onDismissRequest = { blueprintMenu = false }) {
                        WorkflowBlueprints.all.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.title) },
                                onClick = { blueprintId = item.id; blueprintMenu = false }
                            )
                        }
                    }
                    Text(blueprint?.summary.orEmpty(), color = Color(0xFFB8AEB8), fontSize = 11.sp)
                    TextField(
                        value = goal,
                        onValueChange = { goal = it.take(1_000) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("What outcome should Lucifer work toward?", color = Color(0xFF746574)) },
                        minLines = 2,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        colors = workflowFieldColors()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        CommandTarget.entries.forEach { target ->
                            TextButton(
                                onClick = { selectedTarget = target },
                                enabled = !busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    target.displayName,
                                    color = if (selectedTarget == target) Color(0xFFFFD700) else Color(0xFFB8AEB8),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                    Text("New task target: ${selectedTarget.displayName}", color = Color(0xFFB8AEB8), fontSize = 11.sp)
                    Button(
                        onClick = {
                            val text = goal.trim()
                            if (text.isNotBlank() && blueprint != null) {
                                onCreateTask(blueprint.id, text, selectedTarget)
                                goal = ""
                            }
                        },
                        enabled = !busy && goal.isNotBlank() && tasks.size < 20,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C4600), contentColor = Color.White)
                    ) { Text(if (busy) "WORKING…" else "PLAN TASK") }
                    if (tasks.size >= 20) Text("Task limit reached (20). Delete an old task to create another.", color = Color(0xFFFFAA88), fontSize = 11.sp)
                }
            }
        }
        if (selected != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF211621))) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(selected.goal, color = Color(0xFFEDE3ED), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "${WorkflowBlueprints.get(selected.blueprintId)?.title ?: selected.blueprintId} · ${selected.status.name.replace('_', ' ')} · ${selected.target.displayName} · plan ${selected.planSource}",
                            color = Color(0xFFFFCC88), fontSize = 11.sp
                        )
                        Text("${selected.summary}  Iteration ${selected.iteration + 1}.", color = Color(0xFFB8AEB8), fontSize = 12.sp)
                        selected.steps.filter { it.status == WorkflowStepStatus.COMPLETE }.forEach { finished ->
                            Text("✓ ${finished.title} · complete", color = Color(0xFF91D2A5), fontSize = 11.sp)
                            if (finished.result.isNotBlank()) Text(finished.result.takeLast(700), color = Color(0xFF9B929B), fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (queuedSteps.isNotEmpty()) {
                            Text(if (currentStep == null) "UNRUN STEPS" else "QUEUED STEPS", color = Color(0xFFB8AEB8), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            queuedSteps.forEach { queued -> Text("○ ${queued.title}", color = Color(0xFF9B929B), fontSize = 10.sp) }
                        }
                        currentStep?.let { step ->
                            Text("STEP ${selected.steps.indexOf(step) + 1}: ${step.title}", color = Color(0xFFFFD700), fontWeight = FontWeight.Bold)
                            Text(step.instruction, color = Color(0xFFE0D6E0), fontSize = 13.sp)
                            if (step.acceptance.isNotBlank()) Text("Evidence sought: ${step.acceptance}", color = Color(0xFFB8AEB8), fontSize = 11.sp)
                            if (step.result.isNotBlank()) {
                                Text("Latest result (${step.status.name})", color = Color(0xFFFFCC88), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(step.result.takeLast(1_800), color = Color(0xFFD6CDD6), fontSize = 11.sp, maxLines = 12, overflow = TextOverflow.Ellipsis)
                            }
                            if (step.status in setOf(WorkflowStepStatus.READY, WorkflowStepStatus.REVIEW) && selected.status != WorkflowTaskStatus.PAUSED && selected.status != WorkflowTaskStatus.COMPLETE) {
                                Text("Add an observation from another Lucifer panel or work you performed manually. It will be included in reflection.", color = Color(0xFFB8AEB8), fontSize = 10.sp)
                                TextField(
                                    value = observation,
                                    onValueChange = { observation = it.take(1_000) },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("What did you observe?", color = Color(0xFF746574)) },
                                    minLines = 2,
                                    maxLines = 4,
                                    colors = workflowFieldColors()
                                )
                                TextButton(
                                    onClick = {
                                        val note = observation.trim()
                                        onRecordObservation(selected.id, step.id, note)
                                        observation = ""
                                    },
                                    enabled = !busy && observation.isNotBlank()
                                ) { Text("RECORD OBSERVATION", color = Color(0xFFFFCC88)) }
                            }
                            if (step.status == WorkflowStepStatus.READY) {
                                TextField(
                                    value = extraContext,
                                    onValueChange = { extraContext = it.take(500) },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("Optional context for this step", color = Color(0xFF746574)) },
                                    singleLine = true,
                                    colors = workflowFieldColors()
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        val instruction = if (extraContext.isBlank()) step.instruction else "${step.instruction}\nAdditional user context: ${extraContext.trim()}"
                                        onRunStep(selected.id, step.id, instruction)
                                        extraContext = ""
                                    },
                                    enabled = !busy && step.status == WorkflowStepStatus.READY && selected.status != WorkflowTaskStatus.PAUSED && selected.status != WorkflowTaskStatus.COMPLETE
                                ) { Text(if (busy) "RUNNING…" else "RUN STEP") }
                                Button(
                                    onClick = { onReflect(selected.id) },
                                    enabled = !busy && step.status == WorkflowStepStatus.REVIEW
                                ) { Text("REFLECT") }
                            }
                            if (selected.review.isNotBlank()) {
                                Text("REVIEW · ${selected.reviewSource}", color = Color(0xFFFFD700), fontWeight = FontWeight.Bold)
                                Text(selected.review, color = Color(0xFFE0D6E0), fontSize = 12.sp)
                                selected.proposedStep?.let { next ->
                                    Text("Proposed next: ${next.title} — ${next.instruction}", color = Color(0xFFB8AEB8), fontSize = 11.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onAcceptReview(selected.id) }, enabled = !busy) { Text("ACCEPT & CONTINUE") }
                                    TextButton(onClick = { onRetryStep(selected.id, step.id) }, enabled = !busy) { Text("RETRY STEP", color = Color(0xFFFFCC88)) }
                                }
                            }
                        } ?: Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("No pending step. Review the task or complete it.", color = Color(0xFFB8AEB8))
                            if (selected.review.isNotBlank()) {
                                Text("FINAL REVIEW · ${selected.reviewSource}", color = Color(0xFFFFD700), fontWeight = FontWeight.Bold)
                                Text(selected.review, color = Color(0xFFE0D6E0), fontSize = 12.sp)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (selected.status == WorkflowTaskStatus.PAUSED) {
                                TextButton(onClick = { onPauseResume(selected.id) }, enabled = !busy) { Text("RESUME") }
                            } else if (selected.status != WorkflowTaskStatus.COMPLETE) {
                                TextButton(onClick = { onPauseResume(selected.id) }, enabled = !busy) { Text("PAUSE") }
                            }
                            Button(
                                onClick = { onComplete(selected.id) },
                                enabled = !busy && (selected.status == WorkflowTaskStatus.READY_TO_COMPLETE || selected.steps.all { it.status == WorkflowStepStatus.COMPLETE })
                            ) { Text("COMPLETE TASK") }
                            TextButton(onClick = { onDelete(selected.id) }, enabled = !busy) { Text("DELETE", color = Color(0xFFFF8888)) }
                        }
                    }
                }
            }
        }
        item {
            Text("SAVED TASKS", color = Color(0xFFFFD700), fontWeight = FontWeight.Bold)
        }
        items(tasks, key = { it.id }) { task ->
            val isSelected = task.id == selectedTaskId
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelectTask(task.id) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF332733) else Color(0xFF1B111B))
            ) {
                Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.goal, color = Color(0xFFE0D6E0), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${task.status.name.replace('_', ' ')} · ${task.target.displayName} · ${task.steps.count { it.status == WorkflowStepStatus.COMPLETE }}/${task.steps.size} steps", color = Color(0xFFB8AEB8), fontSize = 10.sp)
                }
            }
        }
        item { Text("Tasks persist in app-private, unencrypted files. Avoid sensitive notes and delete tasks when finished.", color = Color(0xFF807480), fontSize = 10.sp, modifier = Modifier.padding(bottom = 12.dp)) }
    }
}

@Composable
private fun workflowFieldColors() = TextFieldDefaults.textFieldColors(
    containerColor = Color(0xFF100810),
    focusedTextColor = Color(0xFFE0D6E0),
    unfocusedTextColor = Color(0xFFE0D6E0),
    cursorColor = Color(0xFFFFD700),
    focusedIndicatorColor = Color(0xFF8B0000),
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent
)
