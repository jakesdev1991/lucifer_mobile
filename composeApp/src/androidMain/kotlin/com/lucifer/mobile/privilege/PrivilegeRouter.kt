package com.lucifer.mobile.privilege

import android.content.Context
import com.lucifer.mobile.shizuku.ShizukuAccess
import com.lucifer.mobile.shizuku.ShizukuTerminal
import com.lucifer.mobile.world.AndroidUser
import com.lucifer.mobile.world.UserListParser

/**
 * Chooses the backend and executes exactly one confirmed plan.
 *
 * Order of operations, in both directions:
 *
 *  1. [PrivilegeCommands] turns the request into a command or a refusal. A refusal never executes.
 *  2. if the op changes state and the caller has not confirmed it, nothing happens.
 *  3. only then is a provider picked, and only a provider that actually holds the needed capability.
 *
 * Step 2 is deliberately here as well as in the UI: the panel asking for confirmation is a courtesy,
 * this is the rule.
 */
class PrivilegeRouter(private val context: Context) {

    fun states(): List<ProviderState> {
        val dhizuku = DhizukuBridge.status(context)
        val shizuku = ShizukuAccess.status()
        val localOwner = LocalOwnerOps.isOwner(context)
        return listOf(
            ProviderState(Provider.DHIZUKU, dhizuku.ready, dhizuku.message),
            ProviderState(
                Provider.SHIZUKU_ROOT,
                shizuku.ready && shizuku.uid == 0,
                if (shizuku.ready && shizuku.uid == 0) shizuku.message else "Shizuku is not running as root"
            ),
            ProviderState(
                Provider.SHIZUKU_SHELL,
                shizuku.ready,
                shizuku.message
            ),
            ProviderState(
                Provider.LUCIFER_OWNER,
                localOwner,
                when {
                    localOwner && LocalOwnerOps.isDeviceOwner(context) -> "Lucifer is the device owner"
                    localOwner -> "Lucifer is a profile owner"
                    else -> "Lucifer is not an owner app"
                }
            )
        )
    }

    fun summary(): String = ProviderPlan.summary(states())

    fun shellProvider(): Provider? = ProviderPlan.select(Capability.SHELL, states())

    fun ownerProvider(): Provider? = ProviderPlan.select(Capability.DEVICE_OWNER, states())

    /** Everything the panel needs to explain why a button is or is not available. */
    fun readiness(): Readiness {
        val states = states()
        return Readiness(
            shell = ProviderPlan.select(Capability.SHELL, states),
            owner = ProviderPlan.select(Capability.DEVICE_OWNER, states),
            summary = ProviderPlan.summary(states)
        )
    }

    data class Readiness(val shell: Provider?, val owner: Provider?, val summary: String) {
        val canRunCommands: Boolean get() = shell != null
        val canDoOwnerWork: Boolean get() = owner != null
    }

    /**
     * Runs one op. [confirmed] must be true for anything above read-only; the caller passes it only
     * after the owner has seen the exact command.
     */
    suspend fun run(op: PrivilegeOp, confirmed: Boolean): PrivilegeOutcome {
        if (op.risk != Risk.LOW && !confirmed) {
            return PrivilegeOutcome(
                ok = false,
                provider = Provider.NONE,
                headline = "Not run: \"${op.label}\" was not confirmed."
            )
        }
        val plan = PrivilegeCommands.plan(op)
        plan.refusal?.let { reason ->
            record(op, Provider.NONE, false, reason)
            return PrivilegeOutcome(false, Provider.NONE, reason)
        }
        return when (plan.capability) {
            Capability.SHELL -> runShell(op, plan.command.orEmpty())
            Capability.DEVICE_OWNER -> runOwner(op)
            Capability.CROSS_PROFILE -> PrivilegeOutcome(
                ok = false,
                provider = Provider.NONE,
                headline = "Cross-profile launches do not use a privilege backend."
            )
        }
    }

    private suspend fun runShell(op: PrivilegeOp, command: String): PrivilegeOutcome {
        val provider = shellProvider()
        if (provider == null) {
            val message = "No shell backend. Start Shizuku (Wireless debugging) and grant Lucifer access, " +
                "or provision Dhizuku and use the owner actions instead."
            record(op, Provider.NONE, false, message)
            return PrivilegeOutcome(false, Provider.NONE, message)
        }
        val output = runCatching { ShizukuTerminal.execute(context, command) }
            .getOrElse { listOf("[ERROR] ${it.message ?: it.javaClass.simpleName}") }
        val failed = output.firstOrNull()?.let { first ->
            first.startsWith("[ERROR]") || first.startsWith("[SETUP]") || first.startsWith("[BLOCKED]")
        } ?: false
        val headline = if (failed) output.first() else "Ran as ${provider.label}."
        record(op, provider, !failed, command)
        return PrivilegeOutcome(ok = !failed, provider = provider, headline = headline, detail = output.take(40))
    }

    private fun runOwner(op: PrivilegeOp): PrivilegeOutcome {
        val provider = ownerProvider()
        if (provider == null) {
            val message = "No device-owner backend. Install and activate Dhizuku, approve Lucifer inside it, " +
                "or set Lucifer as device owner."
            record(op, Provider.NONE, false, message)
            return PrivilegeOutcome(false, Provider.NONE, message)
        }
        val result: Result<*>
        val detail: List<String>
        when (op) {
            is PrivilegeOp.OwnerHidden -> {
                result = ownerHide(op, provider)
                detail = listOf("setApplicationHidden(${op.pkg}, hidden=${op.hidden}, user=${op.userId})")
            }
            is PrivilegeOp.OwnerSuspended -> {
                result = ownerSuspend(op, provider)
                detail = listOf("setPackagesSuspended(${op.pkg}, suspended=${op.suspended}, user=${op.userId})")
            }
            is PrivilegeOp.OwnerPermission -> {
                result = ownerPermission(op, provider)
                detail = listOf("setPermissionGrantState(${op.pkg}, ${op.permission}, user=${op.userId})")
            }
            else -> {
                val message = "That request is not a device-owner call."
                record(op, provider, false, message)
                return PrivilegeOutcome(false, provider, message)
            }
        }
        val ok = result.isSuccess
        val message = result.fold(
            onSuccess = { "${op.label} completed." },
            onFailure = { "${op.label} refused: ${it.message ?: it.javaClass.simpleName}" }
        )
        record(op, provider, ok, message)
        return PrivilegeOutcome(ok, provider, message, detail)
    }

    private fun ownerHide(op: PrivilegeOp.OwnerHidden, provider: Provider): Result<*> =
        if (provider == Provider.LUCIFER_OWNER) {
            val local = LocalOwnerOps.setApplicationHidden(context, op.pkg, op.hidden, op.userId)
            if (local.ok) Result.success(Unit) else Result.failure(IllegalStateException(local.message))
        } else {
            DhizukuBridge.setApplicationHidden(LocalOwnerOps.admin(context), op.pkg.trim(), op.hidden, op.userId)
        }

    private fun ownerSuspend(op: PrivilegeOp.OwnerSuspended, provider: Provider): Result<*> =
        if (provider == Provider.LUCIFER_OWNER) {
            val local = LocalOwnerOps.setPackagesSuspended(context, op.pkg, op.suspended, op.userId)
            if (local.ok) Result.success(Unit) else Result.failure(IllegalStateException(local.message))
        } else {
            DhizukuBridge.setPackagesSuspended(LocalOwnerOps.admin(context), op.pkg.trim(), op.suspended, op.userId)
        }

    private fun ownerPermission(op: PrivilegeOp.OwnerPermission, provider: Provider): Result<*> =
        if (provider == Provider.LUCIFER_OWNER) {
            val local = LocalOwnerOps.setPermissionGrantState(context, op.pkg, op.permission, op.granted)
            if (local.ok) Result.success(Unit) else Result.failure(IllegalStateException(local.message))
        } else {
            val state = if (op.granted) {
                android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                android.content.pm.PackageManager.PERMISSION_DENIED
            }
            DhizukuBridge.setPermissionGrantState(
                LocalOwnerOps.admin(context),
                op.pkg.trim(),
                op.permission.trim(),
                state,
                op.userId
            )
        }

    /** Read-only user list, used to find or verify Lucifer's world. */
    suspend fun listUsers(): Pair<List<AndroidUser>, PrivilegeOutcome> {
        val outcome = run(PrivilegeOp.ReadOnly("pm list users"), confirmed = true)
        val users = UserListParser.parse(outcome.detail.joinToString("\n"))
        return users to outcome
    }

    private fun record(op: PrivilegeOp, provider: Provider, ok: Boolean, note: String) {
        PrivilegeLedger.record(
            LedgerEntry(
                at = System.currentTimeMillis(),
                label = op.label,
                provider = provider,
                ok = ok,
                note = note.take(220)
            )
        )
    }
}
