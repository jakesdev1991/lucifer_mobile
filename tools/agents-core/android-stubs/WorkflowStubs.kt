// Type-check-only stand-ins. See AndroidContentStubs.kt.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.workflows

enum class WorkflowTaskStatus { READY, ACTIVE, REVIEW, PAUSED, READY_TO_COMPLETE, COMPLETE }
enum class WorkflowStepStatus { READY, RUNNING, REVIEW, COMPLETE }

data class WorkflowStep(
    val id: String,
    val title: String,
    val instruction: String,
    val acceptance: String,
    val status: WorkflowStepStatus = WorkflowStepStatus.READY
)

data class WorkflowTask(
    val id: String,
    val blueprintId: String,
    val goal: String,
    val status: WorkflowTaskStatus,
    val steps: List<WorkflowStep>,
    val updatedAt: Long
)

class WorkflowTaskStore(context: android.content.Context) {
    fun load(): List<WorkflowTask> = emptyList()
}
