package com.lucifer.mobile.config

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File

/**
 * Every small on-device setting, in one place.
 *
 * Before this, `files/config/*` was reached by eleven hand-rolled readers and writers: four separate
 * spellings of `vm_host.txt`, four of the memory-MCP token, three of the SSH password. Most of them
 * wrote the file directly with `FileOutputStream`, so a process death mid-write left a truncated value
 * behind — a half-written host or port that the SSH path would then trust. Some created `config/`
 * first, some did not.
 *
 * Two rules now hold:
 *
 *  1. **The names live here.** A caller asks for [VM_HOST], not for the string `"config/vm_host.txt"`,
 *     so a typo cannot silently create a second file that nothing reads.
 *  2. **A write is atomic.** [AtomicFile] writes a sibling temp file and renames over the original, so
 *     a reader sees the old value or the new one, never half of either.
 *
 * Secrets (the NVIDIA keys, the MCP token, the SSH password) are *named* here but stored through
 * [com.lucifer.mobile.security.SecretStore], which encrypts them with an Android Keystore key. Naming
 * and storing are separate jobs; this object only guarantees both agree on the path.
 */
object AppConfig {

    private const val TAG = "AppConfig"
    private const val DIR = "config"

    /** Longest value read back. Config here is a URL, a hostname or a flag; anything larger is junk. */
    private const val MAX_READ_CHARS = 64_000

    // ── plain settings ────────────────────────────────────────────────
    const val VM_HOST = "config/vm_host.txt"
    const val VM_USER = "config/vm_user.txt"
    const val VM_PORT = "config/vm_port.txt"
    const val KNOWN_HOSTS = "config/known_hosts"
    const val VOICE_ENABLED = "config/voice_enabled.txt"
    const val KNOWLEDGE_URL = "config/knowledge_url.txt"
    const val MEMORY_MCP_URL = "config/drive_memory_mcp_url.txt"
    const val LLM_MODEL = "config/llm_model.txt"

    // ── secret settings (paths only; written via SecretStore) ────────
    const val MEMORY_MCP_TOKEN = "config/drive_memory_mcp_token.txt"
    const val VM_SSH_PASSWORD = "config/vm_ssh_password.txt"
    const val NVIDIA_KEY = "config/nvidia_key.txt"

    fun file(context: Context, key: String): File = File(context.filesDir, key)

    /**
     * Read a setting, or null when it is absent or unreadable.
     *
     * Returns the raw text (callers trim what they care about) and never throws: a broken config file
     * must degrade to the default, not crash the screen that reads it.
     */
    fun read(context: Context, key: String, maxChars: Int = MAX_READ_CHARS): String? = try {
        val target = file(context, key)
        if (!target.isFile) null else AtomicFile(target).readFully().toString(Charsets.UTF_8).take(maxChars)
    } catch (error: Exception) {
        Log.w(TAG, "could not read $key: ${error.javaClass.simpleName}")
        null
    }

    /** Read and trim. Blank or missing is null, which is what every caller actually wants. */
    fun readText(context: Context, key: String, maxChars: Int = MAX_READ_CHARS): String? =
        read(context, key, maxChars)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Write a setting atomically. A blank value deletes the file, because "unset" and "empty string"
     * are the same thing to every reader and keeping an empty file only invites two behaviours.
     */
    fun write(context: Context, key: String, value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return delete(context, key)
        return try {
            val target = file(context, key)
            target.parentFile?.mkdirs()
            val atomic = AtomicFile(target)
            val stream = atomic.startWrite()
            try {
                stream.write(trimmed.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
                true
            } catch (error: Exception) {
                atomic.failWrite(stream)
                Log.w(TAG, "atomic write failed for $key", error)
                false
            }
        } catch (error: Exception) {
            Log.w(TAG, "could not write $key: ${error.javaClass.simpleName}")
            false
        }
    }

    fun delete(context: Context, key: String): Boolean = try {
        // AtomicFile keeps a .bak beside the file; its delete() removes both, so a cleared setting
        // cannot be resurrected from the backup on the next read.
        AtomicFile(file(context, key)).delete()
        true
    } catch (error: Exception) {
        Log.w(TAG, "could not delete $key: ${error.javaClass.simpleName}")
        false
    }

    fun isSet(context: Context, key: String): Boolean = readText(context, key) != null

    /** Boolean flags, defaulting to [default] when the file is missing. */
    fun readFlag(context: Context, key: String, default: Boolean): Boolean =
        readText(context, key)?.let { it.equals("true", ignoreCase = true) } ?: default

    fun writeFlag(context: Context, key: String, value: Boolean): Boolean =
        write(context, key, if (value) "true" else "false")

    /** Ints, clamped to [range]; an unparsable or out-of-range file falls back to [default]. */
    fun readInt(context: Context, key: String, default: Int, range: IntRange): Int =
        readText(context, key)?.toIntOrNull()?.takeIf { it in range } ?: default
}
