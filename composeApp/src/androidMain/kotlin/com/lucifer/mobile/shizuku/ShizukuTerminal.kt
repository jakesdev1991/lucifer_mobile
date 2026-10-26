package com.lucifer.mobile.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.lucifer.mobile.BuildConfig
import com.lucifer.mobile.terminal.TerminalCommandPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** Binds a Shizuku UserService for one terminal invocation. The privileged process
 * uses the identity configured by the phone owner (ADB shell or root); command text
 * still passes through Lucifer's natural-language router and confirmation UI. */
object ShizukuTerminal {
    private const val BIND_TIMEOUT_MS = 10_000L
    private const val COMMAND_TIMEOUT_MS = 65_000L
    private const val COMMAND_TIMEOUT_SECONDS = 60

    private fun args(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuCommandUserService::class.java.name)
    )
        .tag("lucifer-shizuku-terminal-v1")
        .daemon(false)
        .processNameSuffix("shizuku_terminal")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+$")

    /** Explicit, read-only package state inspection; package input is strictly validated. */
    suspend fun inspectPackageReadOnly(context: Context, rawPackageName: String): List<String> = withContext(Dispatchers.IO) {
        val packageName = rawPackageName.trim()
        if (packageName.length > 255 || !PACKAGE_NAME_PATTERN.matches(packageName)) {
            return@withContext listOf("[ERROR] Enter a valid Android package name (for example, com.example.app).")
        }
        val status = ShizukuAccess.status()
        if (!status.ready) return@withContext listOf("[SETUP] ${status.message}. Start Shizuku and grant Lucifer access first.")

        val packageState = execute(context, "dumpsys package $packageName")
        val appOpsState = execute(context, "cmd appops get $packageName")
        buildList {
            add("[READ-ONLY] dumpsys package $packageName")
            addAll(packageState.take(90))
            add("[READ-ONLY] cmd appops get $packageName")
            addAll(appOpsState.take(90))
            add("[INFO] Read-only result is held in Settings memory only; no permission/AppOps changes were made.")
        }.take(190)
    }

    suspend fun execute(context: Context, rawCommand: String): List<String> = withContext(Dispatchers.IO) {
        val command = rawCommand.trim()
        if (command.isBlank() || command.length > 4_000 || command.contains('\u0000')) {
            return@withContext listOf("[ERROR] Shizuku command is empty or exceeds 4,000 characters.")
        }
        TerminalCommandPolicy.refusalReason(command)?.let { reason ->
            return@withContext listOf("[BLOCKED] $reason")
        }
        val access = ShizukuAccess.status()
        if (!access.ready) {
            return@withContext listOf("[SETUP] ${access.message}. Start Shizuku via Wireless debugging and grant Lucifer access in Shizuku.")
        }
        val apiVersion = runCatching { Shizuku.getVersion() }.getOrDefault(0)
        if (apiVersion < 11) {
            return@withContext listOf("[SETUP] Shizuku UserService requires Shizuku server API 11 or newer.")
        }

        val serviceArgs = args(context.applicationContext)
        val connected = CompletableDeferred<IShizukuCommandService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val remote = binder?.let { IShizukuCommandService.Stub.asInterface(it) }
                if (remote == null) connected.completeExceptionally(IllegalStateException("Invalid Shizuku UserService binder"))
                else connected.complete(remote)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException("Shizuku UserService disconnected"))
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException("Shizuku binding died"))
            }

            override fun onNullBinding(name: ComponentName?) {
                if (!connected.isCompleted) connected.completeExceptionally(IllegalStateException("Shizuku returned an empty UserService binding"))
            }
        }

        var bound = false
        try {
            Shizuku.bindUserService(serviceArgs, connection)
            bound = true
            val remote = withTimeout(BIND_TIMEOUT_MS) { connected.await() }
            val output = withTimeout(COMMAND_TIMEOUT_MS) {
                remote.execute(command, COMMAND_TIMEOUT_SECONDS)
            }
            output.lineSequence().take(400).toList().ifEmpty { listOf("[INFO] Shizuku command completed with no output.") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            listOf("[ERROR] Shizuku operation failed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            if (bound) {
                try { Shizuku.unbindUserService(serviceArgs, connection, true) } catch (_: Exception) { }
            }
        }
    }

}
