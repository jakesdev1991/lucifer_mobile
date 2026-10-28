package com.lucifer.mobile.privilege

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log

/**
 * Dhizuku: borrow the device-owner identity from the app the owner already provisioned.
 *
 * Dhizuku runs as device owner and, once the owner approves Lucifer inside it, hands back a Binder
 * for a system service that answers as the owner. That is a different kind of power from Shizuku: no
 * shell, no `pm` text, only `IDevicePolicyManager` calls.
 *
 * The client library (`io.github.iamr0s:Dhizuku-API`) is reached by reflection rather than linked,
 * for two reasons: its permission callback signature has changed across releases, and a build that
 * cannot resolve the artifact should still compile and still report "Dhizuku not installed" instead
 * of failing.
 *
 * Honest limit, stated in the panel too: the deeper `IDevicePolicyManager` methods live behind
 * Android's hidden-API policy. On many builds they resolve; where they do not, every call returns a
 * named failure and nothing is silently skipped. Lucifer never assumes a policy change succeeded.
 */
object DhizukuBridge {

    private const val TAG = "LuciferDhizuku"
    private const val API_CLASS = "com.rosan.dhizuku.api.Dhizuku"
    const val PACKAGE = "com.rosan.dhizuku"
    const val PERMISSION = "com.rosan.dhizuku.permission.API"
    const val REQUEST_CODE = 0x4448

    data class Status(
        val installed: Boolean,
        val initialized: Boolean,
        val permissionGranted: Boolean,
        val version: String,
        val message: String
    ) {
        val ready: Boolean get() = installed && initialized && permissionGranted
    }

    fun status(context: Context): Status {
        val installed = isInstalled(context)
        if (!installed) {
            return Status(false, false, false, "—", "Dhizuku is not installed. Its device-owner powers are unavailable.")
        }
        val api = apiClass()
            ?: return Status(true, false, false, "—", "Dhizuku is installed but its API is not on the classpath; add io.github.iamr0s:Dhizuku-API.")
        val initialized = runCatching {
            api.getMethod("init", Context::class.java).invoke(null, context.applicationContext) as? Boolean ?: false
        }.getOrElse { error ->
            Log.w(TAG, "init failed: ${error.message}")
            false
        }
        if (!initialized) {
            return Status(true, false, false, "—", "Dhizuku is installed but not active. Open it and complete setup.")
        }
        val granted = runCatching {
            api.getMethod("isPermissionGranted").invoke(null) as? Boolean ?: false
        }.getOrDefault(false)
        val version = runCatching {
            (api.getMethod("getVersion").invoke(null) as? Int)?.toString() ?: "—"
        }.getOrDefault("—")
        val message = when {
            granted -> "Connected · API $version · device-owner binder available"
            else -> "Dhizuku is running; Lucifer still needs owner approval in the Dhizuku app."
        }
        return Status(true, true, granted, version, message)
    }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun openApp(context: Context): Boolean = try {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Asks Dhizuku for the delegated permission. Recent builds only expose the listener form, which
     * cannot be constructed reflectively (it is an abstract class, not an interface), so the honest
     * answer there is: approve it in the Dhizuku app.
     */
    fun requestPermission(context: Context): Boolean = try {
        val api = apiClass() ?: return false
        if (status(context).permissionGranted) return true
        api.getMethod("requestPermission", Integer.TYPE).invoke(null, REQUEST_CODE)
        true
    } catch (_: NoSuchMethodException) {
        false
    } catch (_: Exception) {
        false
    }

    private fun apiClass(): Class<*>? = try {
        Class.forName(API_CLASS)
    } catch (_: Throwable) {
        null
    }

    /** Wraps a system-service Binder so calls arrive as the device owner. */
    fun binderWrapper(original: IBinder): IBinder? = try {
        val api = apiClass() ?: return null
        api.getMethod("binderWrapper", IBinder::class.java).invoke(null, original) as? IBinder
    } catch (error: Throwable) {
        Log.w(TAG, "binderWrapper failed: ${error.message}")
        null
    }

    /** Some Dhizuku builds can resolve a service by name directly; used when ServiceManager is closed to us. */
    fun binderWrapperByName(name: String): IBinder? = try {
        val api = apiClass() ?: return null
        api.getMethod("binderWrapper", String::class.java).invoke(null, name) as? IBinder
    } catch (_: Throwable) {
        null
    }

    // ── hidden system services ────────────────────────────────────────────────────────────────

    private fun serviceManagerBinder(name: String): IBinder? = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        serviceManager.getMethod("getService", String::class.java).invoke(null, name) as? IBinder
    } catch (error: Throwable) {
        Log.w(TAG, "ServiceManager.$name unreachable: ${error.message}")
        null
    }

    /**
     * A Binder for `device_policy`, wrapped so DevicePolicyManager answers as Lucifer's delegator.
     * Returns null — with a reason — when neither route is available.
     */
    fun devicePolicyChannel(): Result<IBinder> {
        val direct = serviceManagerBinder("device_policy")
        if (direct != null) {
            val wrapped = binderWrapper(direct)
            if (wrapped != null) return Result.success(wrapped)
        }
        val byName = binderWrapperByName("device_policy")
            ?: binderWrapperByName("DevicePolicyManager")
        return if (byName != null) Result.success(byName)
        else Result.failure(IllegalStateException("Dhizuku could not expose the device_policy service on this build."))
    }

    /**
     * Calls `IDevicePolicyManager` through the owner binder.
     *
     * Methods are matched by name and argument arity; every failure is reported with the method name
     * and the reason, so the panel can say exactly which call the build refused rather than showing a
     * blanket "failed".
     */
    fun ownerCall(method: String, vararg args: Any?): Result<Any?> {
        if (!OWNER_METHODS.contains(method)) {
            return Result.failure(SecurityException("$method is not on Lucifer's device-owner allowlist."))
        }
        val channel = devicePolicyChannel().getOrElse { return Result.failure(it) }
        val stub = try {
            Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
        } catch (error: Throwable) {
            return Result.failure(IllegalStateException("This build hides IDevicePolicyManager: ${error.message}"))
        }
        val proxy = try {
            stub.getMethod("asInterface", IBinder::class.java).invoke(null, channel)
                ?: return Result.failure(IllegalStateException("DevicePolicyManager binder did not wrap."))
        } catch (error: Throwable) {
            return Result.failure(error)
        }
        var lastError: Throwable? = null
        for (candidate in proxy.javaClass.methods.filter { it.name == method && it.parameterCount == args.size }) {
            try {
                return Result.success(candidate.invoke(proxy, *args))
            } catch (error: Throwable) {
                lastError = error.cause ?: error
            }
        }
        return Result.failure(
            lastError ?: NoSuchMethodException("No $method(${args.size} args) on this Android version.")
        )
    }

    /**
     * The device-owner calls Lucifer will make. Anything not listed here is refused before it reaches
     * Binder, so a bug in the panel cannot turn into an arbitrary DevicePolicyManager call.
     */
    val OWNER_METHODS = setOf(
        "setApplicationHidden",
        "setPackagesSuspended",
        "setPermissionGrantState",
        "installExistingPackage",
        "setUserRestriction",
        "clearUserRestriction",
        "logoutUser",
        "setLockTaskPackages",
        "wipeData"
    )

    /** Owner-side helpers, each returning a readable result instead of throwing. */
    fun setApplicationHidden(admin: ComponentName, pkg: String, hidden: Boolean, userId: Int): Result<Any?> =
        ownerCall("setApplicationHidden", admin, pkg, hidden, userId)

    fun setPackagesSuspended(admin: ComponentName, pkg: String, suspended: Boolean, userId: Int): Result<Any?> =
        ownerCall("setPackagesSuspended", admin, arrayOf(pkg), suspended, userId)

    fun setPermissionGrantState(
        admin: ComponentName,
        pkg: String,
        permission: String,
        state: Int,
        userId: Int
    ): Result<Any?> = ownerCall("setPermissionGrantState", admin, pkg, permission, state, userId)

    /**
     * Android's hidden-API policy can close the door on `IDevicePolicyManager` for apps that merely
     * borrow the owner. This attempts the standard exemption and reports whether it took; it never
     * pretends to have worked.
     */
    fun tryHiddenApiExemption(): Boolean {
        // Preferred: the LSPosed bypass library, which handles the per-API-level details.
        try {
            val bypass = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass")
            val added = bypass
                .getMethod("addHiddenApiExemptions", Array<String>::class.java)
                .invoke(null, arrayOf("Landroid/app/admin/", "Landroid/os/", "Landroid/content/pm/")) as? Boolean
            if (added == true) return true
        } catch (error: Throwable) {
            Log.i(TAG, "HiddenApiBypass unavailable: ${error.message}")
        }
        // Fallback: ask VMRuntime directly. Blocked on many builds; that is reported, not hidden.
        return try {
            val vmRuntime = Class.forName("dalvik.system.VMRuntime")
            val runtime = vmRuntime.getMethod("getRuntime").invoke(null)
            vmRuntime.getMethod("setHiddenApiExemptions", Array<String>::class.java)
                .invoke(runtime, arrayOf("Landroid/app/admin/", "Landroid/os/", "Landroid/content/pm/"))
            true
        } catch (error: Throwable) {
            Log.i(TAG, "hidden-API exemption unavailable: ${error.message}")
            false
        }
    }

    fun permissionStateLabel(state: Int): String = when (state) {
        PackageManager.PERMISSION_GRANTED -> "granted"
        PackageManager.PERMISSION_DENIED -> "denied"
        else -> "default"
    }
}
