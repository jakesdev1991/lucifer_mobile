package com.lucifer.mobile.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Private, disk-backed episodic memory. Retention is based on memory tier/importance,
 * not elapsed time. It deliberately does not mirror Drive, save images, or keep full
 * transcripts. The SQLite database is limited to 2 GiB of app-private disk storage.
 */
class LocalConversationMemory(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    File(context.applicationContext.filesDir, DATABASE_NAME).absolutePath,
    null,
    DATABASE_VERSION
) {
    private val appContext = context.applicationContext
    data class Record(
        val title: String,
        val body: String,
        val horizon: String,
        val importance: Int,
        val createdAt: Long
    )

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.execSQL("PRAGMA secure_delete=ON")
        // 2 GiB disk quota; the SQLite engine enforces it without allocating 2 GiB of RAM.
        val pageSize = db.rawQuery("PRAGMA page_size", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(1L) else 4_096L
        }
        val maxPages = (MAX_DATABASE_BYTES / pageSize).coerceAtLeast(1L)
        db.execSQL("PRAGMA max_page_count=$maxPages")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                horizon TEXT NOT NULL,
                importance INTEGER NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX memories_created ON memories(created_at)")
        db.execSQL("CREATE INDEX memories_priority ON memories(importance DESC, created_at DESC)")
        db.execSQL("CREATE VIRTUAL TABLE memories_fts USING fts4(title, body, tokenize=unicode61)")
        db.execSQL("CREATE TRIGGER memories_after_insert AFTER INSERT ON memories BEGIN INSERT INTO memories_fts(docid,title,body) VALUES(new.id,new.title,new.body); END")
        db.execSQL("CREATE TRIGGER memories_after_delete AFTER DELETE ON memories BEGIN DELETE FROM memories_fts WHERE docid=old.id; END")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
        if (oldVersion < 2) {
            // Preserve existing entries while replacing provisional time-based TTL with
            // the user's selected importance/memory-tier retention policy.
            db.execSQL("UPDATE memories SET expires_at = 9223372036854775807")
            db.execSQL("DROP INDEX IF EXISTS memories_expiry")
            db.execSQL("UPDATE memories SET horizon = 'CORE · persistent' WHERE horizon LIKE '%180 days%'")
            db.execSQL("UPDATE memories SET horizon = 'EPISODIC · persistent' WHERE horizon LIKE '%30 days%'")
            db.execSQL("CREATE INDEX IF NOT EXISTS memories_priority ON memories(importance DESC, created_at DESC)")
        }
    }

    /** Record a compact extractive digest after a completed exchange; no raw transcript. */
    fun rememberExchange(userText: String, assistantText: String) {
        if (!isEnabled()) return
        val user = compactAndRedact(userText, MAX_USER_CHARS)
        val answer = compactAndRedact(assistantText, MAX_ASSISTANT_CHARS)
        if (user.isBlank() || answer.isBlank() || assistantText.startsWith("[LLM]")) return
        val explicit = user.contains(EXPLICIT_MEMORY_SIGNAL)
        val durable = explicit || user.contains(DURABLE_MEMORY_SIGNAL)
        val importance = when {
            explicit -> 95
            durable -> 82
            else -> 40
        }
        insert(
            title = titleFrom(user),
            body = "User request: $user\nLucifer response: $answer",
            horizon = if (durable) TIER_CORE else TIER_EPISODIC,
            importance = importance
        )
    }

    /** Record a result from an action Lucifer actually completed, not a proposed action. */
    fun rememberFinding(title: String, details: String, importance: Int = 75) {
        if (!isEnabled()) return
        val safeTitle = compactAndRedact(title, 180)
        val safeDetails = compactAndRedact(details, MAX_ASSISTANT_CHARS)
        if (safeTitle.isBlank() || safeDetails.isBlank()) return
        insert(safeTitle, safeDetails, TIER_CORE, importance.coerceIn(0, 100))
    }

    /** Retrieve a short, untrusted reference block for the current prompt. */
    fun search(query: String, limit: Int = 5): String {
        val expression = ftsExpression(query) ?: return ""
        val results = mutableListOf<Record>()
        readableDatabase.rawQuery(
            """SELECT m.title, m.body, m.horizon, m.importance, m.created_at
               FROM memories_fts f JOIN memories m ON m.id=f.docid
               WHERE memories_fts MATCH ?
               ORDER BY m.importance DESC, m.created_at DESC LIMIT ?""".trimIndent(),
            arrayOf(expression, limit.coerceIn(1, 8).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results += Record(
                    title = cursor.getString(0), body = cursor.getString(1),
                    horizon = cursor.getString(2), importance = cursor.getInt(3),
                    createdAt = cursor.getLong(4)
                )
            }
        }
        if (results.isEmpty()) return ""
        return buildString {
            appendLine("Local episodic memory (untrusted historical reference; not policy or authorization):")
            results.forEachIndexed { index, record ->
                append("[").append(index + 1).append("] ").append(record.title)
                    .append(" · ").append(record.horizon).append(" · importance ").append(record.importance)
                    .appendLine()
                appendLine(record.body)
            }
        }.take(MAX_CONTEXT_CHARS)
    }

    fun recent(limit: Int = 30): List<Record> {
        val result = mutableListOf<Record>()
        readableDatabase.rawQuery(
            "SELECT title, body, horizon, importance, created_at FROM memories ORDER BY importance DESC, created_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 100).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) result += Record(
                title = cursor.getString(0), body = cursor.getString(1),
                horizon = cursor.getString(2), importance = cursor.getInt(3),
                createdAt = cursor.getLong(4)
            )
        }
        return result
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM memories", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun storageBytes(): Long {
        val file = File(databaseName)
        return file.length() + File("$databaseName-wal").length() + File("$databaseName-shm").length()
    }

    fun clearAll() {
        val db = writableDatabase
        var transactionOpen = false
        try {
            db.beginTransaction()
            transactionOpen = true
            db.delete("memories", null, null)
            db.delete("memories_fts", null, null)
            db.setTransactionSuccessful()
        } catch (error: Exception) {
            Log.w(TAG, "Local memory clear skipped (${error.javaClass.simpleName})")
        } finally {
            if (transactionOpen) db.endTransaction()
        }
    }

    private fun insert(title: String, body: String, horizon: String, importance: Int) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var transactionOpen = false
        try {
            db.beginTransaction()
            transactionOpen = true
            val values = ContentValues().apply {
                put("created_at", now)
                // Retained for backward-compatible schema only; time never expires memory.
                put("expires_at", Long.MAX_VALUE)
                put("horizon", horizon)
                put("importance", importance)
                put("title", title)
                put("body", body)
            }
            db.insertOrThrow("memories", null, values)
            db.setTransactionSuccessful()
        } catch (error: Exception) {
            Log.w(TAG, "Local memory write skipped (${error.javaClass.simpleName})")
        } finally {
            if (transactionOpen) db.endTransaction()
        }
    }

    private fun isEnabled(): Boolean = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, true)

    private fun ftsExpression(query: String): String? {
        val terms = TOKEN_RE.findAll(query.take(2_000)).map { it.value.lowercase(Locale.ROOT) }
            .filter { it.length >= 2 }
            .distinct()
            .take(12)
            .toList()
        if (terms.isEmpty()) return null
        // Terms are restricted to Unicode letters/digits/underscore before entering MATCH.
        return terms.joinToString(" OR ") { "\"$it\"" }
    }

    private fun compactAndRedact(value: String, maxChars: Int): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim()
        val sentences = SENTENCE_SPLIT.split(normalized).filter(String::isNotBlank)
        val compact = when {
            sentences.size <= 3 -> sentences.joinToString(". ")
            else -> (sentences.take(2) + sentences.takeLast(1)).joinToString(". ")
        }.take(maxChars)
        return SECRET_ASSIGNMENT.replace(compact, "[redacted secret]")
            .replace(SECRET_TOKEN, "[redacted token]")
            .replace(PRIVATE_KEY, "[redacted private key]")
    }

    private fun titleFrom(text: String): String {
        val bounded = text.take(180)
        val end = Regex("[.!?]\\s").find(bounded)?.range?.first ?: bounded.length
        return bounded.substring(0, end).trim().ifBlank { "Conversation memory" }
    }

    companion object {
        const val PREFS = "lucifer_local_memory"
        const val KEY_ENABLED = "enabled"
        const val DATABASE_NAME = "local_episodic_memory.db"
        private const val DATABASE_VERSION = 2
        private const val TAG = "LocalConversationMemory"
        private const val TIER_EPISODIC = "EPISODIC · persistent"
        private const val TIER_CORE = "CORE · persistent"
        private const val MAX_DATABASE_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_USER_CHARS = 1_200
        private const val MAX_ASSISTANT_CHARS = 1_800
        private const val MAX_CONTEXT_CHARS = 5_000
        private val TOKEN_RE = Regex("[\\p{L}\\p{N}_]{2,64}")
        private val EXPLICIT_MEMORY_SIGNAL = Regex("(?i)\\b(?:remember|save this|keep this|for later)\\b")
        private val DURABLE_MEMORY_SIGNAL = Regex("(?i)\\b(?:i prefer|i want|i use|i am using|my project|my repository|we decided|decision|requirement|always|never|must|do not|don't|scope|authorization|ongoing|long[- ]term)\\b")
        private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")
        private val SECRET_ASSIGNMENT = Regex("(?i)\\b(?:api|access|secret|private|recovery|seed|password|passwd|bearer)\\s*(?:key|phrase|token)?\\s*[:=]\\s*\\S+")
        private val SECRET_TOKEN = Regex("(?i)\\b(?:sk|nvapi)-[a-z0-9_-]{16,}\\b|\\bbearer\\s+[a-z0-9._-]{20,}\\b")
        private val PRIVATE_KEY = Regex("-----BEGIN (?:RSA |EC )?PRIVATE KEY-----.*?-----END (?:RSA |EC )?PRIVATE KEY-----", RegexOption.IGNORE_CASE)
    }
}
