package com.lucifer.mobile.resilience

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import com.lucifer.mobile.config.AppConfig
import java.io.File

/**
 * The Android half of the opt-in lesson exchange: the Settings toggle, and the SAF-backed
 * export/import entry points.
 *
 * Rules that hold by construction:
 *
 *  - **Never auto-export.** [exportLessons] is only reachable from a button behind the
 *    "allow lesson export" toggle; there is no timer, worker, or boot path that calls it.
 *  - **No network.** Export writes to a URI the system picker returned; import reads from a URI
 *    the file picker returned. Neither opens a socket.
 *  - **Credential rejection and dedupe** live in the pure core ([LessonExchange]), so they are
 *    host-tested and cannot be bypassed by a different caller.
 */
object LessonTransfer {

    private const val TAG = "LessonTransfer"
    private const val LESSON_FILE = "resilience/lessons.json"

    /** Settings toggle. Default OFF: exporting requires an explicit user decision each install.
     *  The path is named once in [AppConfig], as every `files/config/` path must be. */
    fun allowExport(context: Context): Boolean =
        AppConfig.readFlag(context, AppConfig.ALLOW_LESSON_EXPORT, default = false)

    fun setAllowExport(context: Context, on: Boolean) {
        AppConfig.writeFlag(context, AppConfig.ALLOW_LESSON_EXPORT, on)
    }

    private fun lessonFile(context: Context): File = File(context.filesDir, LESSON_FILE)

    private fun readLedger(context: Context): LearningLedger = try {
        val file = lessonFile(context.applicationContext)
        if (!file.isFile) LearningLedger() else LearningLedger.decode(file.readText()) ?: LearningLedger()
    } catch (error: Exception) {
        Log.w(TAG, "could not read lessons for export", error)
        LearningLedger()
    }

    private fun writeLedger(context: Context, ledger: LearningLedger) {
        try {
            val file = lessonFile(context.applicationContext)
            file.parentFile?.mkdirs()
            AtomicFile(file).let { atomic ->
                val stream = atomic.startWrite()
                try {
                    stream.write(ledger.encode().toByteArray(Charsets.UTF_8))
                    atomic.finishWrite(stream)
                } catch (error: Exception) {
                    atomic.failWrite(stream)
                    throw error
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "could not persist imported lessons", error)
        }
    }

    /**
     * Build the export document and write it to the SAF-chosen [uri]. Refuses when the toggle is
     * off, so an export can never happen without the user having allowed it in Settings.
     */
    fun exportLessons(context: Context, uri: Uri): Result<String> {
        val app = context.applicationContext
        if (!allowExport(app)) {
            return Result.failure(IllegalStateException("Lesson export is off in Settings. Turn on 'allow lesson export' first."))
        }
        return try {
            val result = LessonExchange.export(readLedger(app))
            val stream = app.contentResolver.openOutputStream(uri)
                ?: return Result.failure(IllegalStateException("The picker returned a location Lucifer cannot write to."))
            stream.use { it.write(result.json.toByteArray(Charsets.UTF_8)) }
            Log.i(TAG, "exported ${result.exported} lessons (${result.droppedForSecret} dropped for credential match)")
            val note = if (result.droppedForSecret > 0) {
                "Exported ${result.exported} lessons; ${result.droppedForSecret} were withheld because they looked like credentials. Nothing was uploaded."
            } else {
                "Exported ${result.exported} lessons. Nothing was uploaded."
            }
            Result.success(note)
        } catch (error: Exception) {
            Log.w(TAG, "lesson export failed", error)
            Result.failure(error)
        }
    }

    /**
     * Read a lesson bundle from the file-picker [uri] and merge it into the local ledger.
     * Dedupe by fingerprint; malformed or credential-bearing lessons are skipped and counted.
     */
    fun importLessons(context: Context, uri: Uri): Result<String> {
        val app = context.applicationContext
        return try {
            val raw = app.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: return Result.failure(IllegalStateException("Could not read the chosen file."))
            val ledger = readLedger(app)
            val result = LessonExchange.importInto(raw, ledger)
            if (result.rejected) {
                return Result.failure(IllegalStateException("Import refused: ${result.reason.ifBlank { "not a valid lesson bundle" }}."))
            }
            writeLedger(app, ledger)
            Log.i(TAG, "imported ${result.added} lessons (${result.duplicates} duplicates skipped)")
            val note = buildString {
                append("Imported ${result.added} lesson(s); ${result.duplicates} duplicate(s) skipped.")
                if (result.reason.isNotBlank()) append(" ${result.reason}.")
                append(" Nothing was sent anywhere.")
            }
            Result.success(note)
        } catch (error: Exception) {
            Log.w(TAG, "lesson import failed", error)
            Result.failure(error)
        }
    }
}
