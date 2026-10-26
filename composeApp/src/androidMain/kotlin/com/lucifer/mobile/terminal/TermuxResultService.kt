package com.lucifer.mobile.terminal

import android.app.IntentService
import android.content.Intent
import kotlinx.coroutines.CancellableContinuation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Receives the explicit PendingIntent result callback from Termux RUN_COMMAND. */
class TermuxResultService : IntentService("TermuxResultService") {
    override fun onHandleIntent(intent: Intent?) {
        val executionId = intent?.getIntExtra(EXTRA_EXECUTION_ID, -1) ?: return
        val bundle = intent.getBundleExtra(RESULT_BUNDLE_KEY)
        if (bundle == null) {
            TermuxResultRegistry.complete(executionId, listOf("[ERROR] Termux returned no result bundle"))
            return
        }

        val stdout = bundle.getString("stdout").orEmpty().trimEnd()
        val stderr = bundle.getString("stderr").orEmpty().trimEnd()
        val exitCode = bundle.getInt("exitCode", -1)
        val internalError = bundle.getString("errmsg").orEmpty()
        val internalErrorCode = bundle.getInt("err", -1)
        val lines = buildList {
            if (stdout.isNotBlank()) addAll(stdout.lines())
            if (stderr.isNotBlank()) addAll(stderr.lines().map { "[stderr] $it" })
            if (internalError.isNotBlank()) add("[Termux] $internalError")
            else if (internalErrorCode != -1) add("[Termux] internal error code $internalErrorCode")
            add("[exit $exitCode]")
        }
        TermuxResultRegistry.complete(executionId, lines)
    }

    companion object {
        const val EXTRA_EXECUTION_ID = "lucifer.termux.execution_id"
        private const val RESULT_BUNDLE_KEY = "result"
    }
}

internal object TermuxResultRegistry {
    private val nextExecutionId = AtomicInteger(1_000)
    private val pending = ConcurrentHashMap<Int, CancellableContinuation<List<String>>>()

    fun nextId(): Int = nextExecutionId.incrementAndGet()

    fun register(id: Int, continuation: CancellableContinuation<List<String>>) {
        pending[id] = continuation
    }

    fun remove(id: Int) {
        pending.remove(id)
    }

    fun complete(id: Int, result: List<String>) {
        val continuation = pending.remove(id) ?: return
        if (continuation.isActive) {
            try {
                continuation.resume(result)
            } catch (_: IllegalStateException) {
                // The awaiting command timed out/cancelled while Termux was returning its result.
            }
        }
    }
}
