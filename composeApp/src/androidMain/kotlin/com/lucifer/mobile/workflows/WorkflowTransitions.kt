package com.lucifer.mobile.workflows

import java.util.UUID

private fun sanitizeResult(raw: String): String = raw
    .replace(Regex("(?i)(authorization\\s*:\\s*bearer\\s+)\\S+"), "${'$'}1<redacted>")
    .replace(Regex("(?i)\\b(api[_-]?key|access[_-]?token|password|secret)\\b\\s*[:=]\\s*[^\\s,;]+"), "${'$'}1=<redacted>")
    .replace(Regex("\\b[0-9a-fA-F]{64}\\b"), "<redacted-64-hex>")

/** Pure task-state transitions; all actions are recorded before the next loop iteration. */
object WorkflowTransitions {
    fun activate(task: WorkflowTask, stepId: String): WorkflowTask {
        if (task.status == WorkflowTaskStatus.PAUSED || task.status == WorkflowTaskStatus.COMPLETE) return task
        val index = task.steps.indexOfFirst { it.id == stepId }
        if (index < 0 || task.currentStepId != stepId || task.steps[index].status != WorkflowStepStatus.READY) return task
        val updated = task.steps.toMutableList()
        updated[index] = updated[index].copy(status = WorkflowStepStatus.RUNNING, attempts = (updated[index].attempts + 1).coerceAtMost(20))
        return task.copy(steps = updated, status = WorkflowTaskStatus.ACTIVE, review = "", reviewSource = "", proposedStep = null, updatedAt = System.currentTimeMillis())
    }

    fun recordOutcome(task: WorkflowTask, stepId: String, result: String, executed: Boolean = true): WorkflowTask {
        val index = task.steps.indexOfFirst { it.id == stepId }
        if (index < 0 || task.currentStepId != stepId) return task
        val updated = task.steps.toMutableList()
        updated[index] = updated[index].copy(
            status = if (executed) WorkflowStepStatus.REVIEW else WorkflowStepStatus.READY,
            result = sanitizeResult(result).takeLast(WorkflowTaskStore.MAX_RESULT_CHARS)
        )
        return task.copy(
            steps = updated,
            status = if (executed) WorkflowTaskStatus.REVIEW else WorkflowTaskStatus.READY,
            review = "", reviewSource = "", proposedStep = null, modelSuggestsComplete = false,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun recordObservation(task: WorkflowTask, stepId: String, observation: String): WorkflowTask {
        val index = task.steps.indexOfFirst { it.id == stepId }
        if (index < 0 || task.currentStepId != stepId || task.status == WorkflowTaskStatus.PAUSED || task.status == WorkflowTaskStatus.COMPLETE) return task
        if (task.steps[index].status !in setOf(WorkflowStepStatus.READY, WorkflowStepStatus.REVIEW)) return task
        val clean = sanitizeResult(observation.trim()).takeLast(WorkflowTaskStore.MAX_RESULT_CHARS)
        if (clean.isBlank()) return task
        val prior = task.steps[index].result
        val combined = if (prior.isBlank()) "User observation: $clean" else "$prior\\nUser observation: $clean"
        val updated = task.steps.toMutableList()
        updated[index] = updated[index].copy(status = WorkflowStepStatus.REVIEW, result = combined.takeLast(WorkflowTaskStore.MAX_RESULT_CHARS))
        return task.copy(
            steps = updated, status = WorkflowTaskStatus.REVIEW, review = "", reviewSource = "",
            proposedStep = null, modelSuggestsComplete = false, updatedAt = System.currentTimeMillis()
        )
    }

    fun applyReflection(task: WorkflowTask, reflection: WorkflowReflection): WorkflowTask = task.copy(
        status = WorkflowTaskStatus.REVIEW,
        review = reflection.assessment.take(1_000),
        reviewSource = reflection.source,
        proposedStep = reflection.nextStep,
        modelSuggestsComplete = reflection.taskComplete,
        updatedAt = System.currentTimeMillis()
    )

    fun acceptReview(task: WorkflowTask): WorkflowTask {
        val currentId = task.currentStepId ?: return task.copy(status = WorkflowTaskStatus.READY_TO_COMPLETE)
        val index = task.steps.indexOfFirst { it.id == currentId }
        if (index < 0 || task.steps[index].status != WorkflowStepStatus.REVIEW) return task

        // Preserve the rest of the original plan. A model proposal adapts the next queued stage
        // rather than accidentally dropping every later starter step.
        val updatedSteps = task.steps.toMutableList()
        updatedSteps[index] = updatedSteps[index].copy(status = WorkflowStepStatus.COMPLETE)
        val completed = updatedSteps.filter { it.status == WorkflowStepStatus.COMPLETE }
        val spec = task.proposedStep
        val repeated = spec != null && completed.any {
            it.title.equals(spec.title, ignoreCase = true) && it.instruction.equals(spec.instruction, ignoreCase = true)
        }
        val limitReached = completed.size >= WorkflowTaskStore.MAX_STEPS || task.iteration >= WorkflowTaskStore.MAX_ITERATIONS
        if (task.modelSuggestsComplete || spec == null || repeated || limitReached) {
            val stopReason = when {
                repeated -> "The proposed step repeats completed work."
                limitReached -> "The workflow iteration limit was reached."
                task.modelSuggestsComplete -> "The review suggests the goal is complete."
                else -> "No additional step is queued."
            }
            return task.copy(
                steps = updatedSteps, currentStepId = null, status = WorkflowTaskStatus.READY_TO_COMPLETE,
                review = "${task.review}\n$stopReason Inspect completed and unrun steps, then choose Complete task or create follow-up work.".take(WorkflowTaskStore.MAX_TEXT_CHARS),
                proposedStep = null, updatedAt = System.currentTimeMillis()
            )
        }

        val next = WorkflowStep(UUID.randomUUID().toString(), spec.title, spec.instruction, spec.acceptance)
        val nextIndex = index + 1
        if (nextIndex < updatedSteps.size) updatedSteps[nextIndex] = next else updatedSteps += next
        return task.copy(
            steps = updatedSteps, currentStepId = next.id, status = WorkflowTaskStatus.READY,
            iteration = task.iteration + 1, review = "", reviewSource = "", proposedStep = null,
            modelSuggestsComplete = false, updatedAt = System.currentTimeMillis()
        )
    }

    fun retry(task: WorkflowTask, stepId: String): WorkflowTask {
        val index = task.steps.indexOfFirst { it.id == stepId }
        if (index < 0 || task.currentStepId != stepId || task.steps[index].status != WorkflowStepStatus.REVIEW) return task
        val updated = task.steps.toMutableList()
        updated[index] = updated[index].copy(status = WorkflowStepStatus.READY, result = "")
        return task.copy(steps = updated, status = WorkflowTaskStatus.READY, review = "", reviewSource = "", proposedStep = null, updatedAt = System.currentTimeMillis())
    }

    fun pauseResume(task: WorkflowTask): WorkflowTask {
        val next = if (task.status == WorkflowTaskStatus.PAUSED) {
            when {
                task.currentStep?.status == WorkflowStepStatus.REVIEW -> WorkflowTaskStatus.REVIEW
                task.currentStepId == null -> WorkflowTaskStatus.READY_TO_COMPLETE
                else -> WorkflowTaskStatus.READY
            }
        } else WorkflowTaskStatus.PAUSED
        return task.copy(status = next, updatedAt = System.currentTimeMillis())
    }

    fun complete(task: WorkflowTask): WorkflowTask {
        val canComplete = task.status == WorkflowTaskStatus.READY_TO_COMPLETE ||
            (task.steps.isNotEmpty() && task.steps.all { it.status == WorkflowStepStatus.COMPLETE })
        return if (canComplete) task.copy(status = WorkflowTaskStatus.COMPLETE, currentStepId = null, updatedAt = System.currentTimeMillis()) else task
    }
}
