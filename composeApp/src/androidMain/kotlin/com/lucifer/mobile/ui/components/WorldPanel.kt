package com.lucifer.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lucifer.mobile.privilege.LedgerEntry
import com.lucifer.mobile.privilege.PrivilegeCommands
import com.lucifer.mobile.privilege.PrivilegeOp
import com.lucifer.mobile.privilege.Provider
import com.lucifer.mobile.privilege.ProviderState
import com.lucifer.mobile.ui.effects.SigilRule
import com.lucifer.mobile.ui.effects.glassSurface
import com.lucifer.mobile.ui.effects.shard
import com.lucifer.mobile.ui.theme.Morningstar
import com.lucifer.mobile.verification.VerifierUiCommand
import com.lucifer.mobile.verification.VerifierUiState
import com.lucifer.mobile.world.WorldAction
import com.lucifer.mobile.world.WorldCharter
import com.lucifer.mobile.world.WorldCommands
import com.lucifer.mobile.world.WorldSnapshot

/** What the panel draws. Assembled by MainActivity from PrivilegeRouter and WorldManager. */
data class WorldUiState(
    val providers: List<ProviderState> = emptyList(),
    val summary: String = "Checking privilege backends…",
    val snapshot: WorldSnapshot = WorldSnapshot(),
    val ledger: List<LedgerEntry> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val commandPreview: String? = null
)

/**
 * The World tab: what Lucifer can reach, what he is allowed to do with it, and what he has already
 * done. Every button that changes state shows the exact command first — this panel is the one place
 * where "he has powers" and "he uses them" are separated by the owner's hand.
 */
@Composable
fun WorldPanel(
    modifier: Modifier = Modifier,
    state: WorldUiState,
    onRefresh: () -> Unit,
    onConfirmAction: (WorldAction) -> Unit,
    onConnectDhizuku: () -> Unit,
    onConnectShizuku: () -> Unit,
    onLaunchInWorld: (String) -> Unit,
    onOwnerOp: (PrivilegeOp) -> Unit,
    verifier: VerifierUiState = VerifierUiState(),
    onVerifier: (VerifierUiCommand) -> Unit = {}
) {
    var pending by remember { mutableStateOf<WorldAction?>(null) }
    var pendingOwner by remember { mutableStateOf<PrivilegeOp?>(null) }
    var packageInput by remember { mutableStateOf("") }
    var ownerPackage by remember { mutableStateOf("") }
    var ownerPermission by remember { mutableStateOf("android.permission.CAMERA") }
    val ownerPackageValid = PrivilegeCommands.validPackage(ownerPackage.trim()) &&
        !PrivilegeCommands.isProtected(ownerPackage.trim())

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("THE WORLD", style = Morningstar.title(19).copy(color = Morningstar.Gilded))
        Text(
            WorldCharter.describe(),
            style = Morningstar.caption(11).copy(color = Morningstar.Ash)
        )
        SigilRule()

        // ── backends ─────────────────────────────────────────────────────────────
        state.providers.forEach { provider ->
            ProviderRow(provider)
        }
        Text(state.summary, style = Morningstar.caption(10).copy(color = Morningstar.Smoke))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onConnectDhizuku,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Morningstar.Crimson,
                    contentColor = Color.White
                ),
                modifier = Modifier.weight(1f)
            ) { Text("DHIZUKU", style = Morningstar.label(9).copy(color = Color.White)) }
            Button(
                onClick = onConnectShizuku,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Morningstar.GlassRaised,
                    contentColor = Morningstar.Bone
                ),
                modifier = Modifier.weight(1f)
            ) { Text("SHIZUKU", style = Morningstar.label(9).copy(color = Morningstar.Bone)) }
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Morningstar.GlassRaised,
                    contentColor = Morningstar.Bone
                ),
                modifier = Modifier.weight(1f)
            ) { Text(if (state.busy) "…" else "REFRESH", style = Morningstar.label(9).copy(color = Morningstar.Bone)) }
        }

        // ── the profile ──────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(shard(13.dp), Morningstar.Edge)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("MANAGED PROFILE", style = Morningstar.overline(9).copy(color = Morningstar.Gilded))
            state.snapshot.lines().forEach { line ->
                Text(line, style = Morningstar.caption(10).copy(color = Morningstar.Bone))
            }
            if (!state.snapshot.hasWorld) {
                WorldButton(
                    label = "CREATE LUCIFER'S WORLD",
                    action = WorldAction.CREATE_WORLD,
                    enabled = !state.busy,
                    onRequest = { pending = it }
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    WorldButton(
                        label = "WAKE",
                        action = WorldAction.OPEN_WORLD,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                        onRequest = { pending = it }
                    )
                    WorldButton(
                        label = "SLEEP",
                        action = WorldAction.CLOSE_WORLD,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                        onRequest = { pending = it }
                    )
                }
                WorldButton(
                    label = "START THE VM INSIDE THE WORLD",
                    action = WorldAction.START_VM,
                    enabled = !state.busy,
                    onRequest = { pending = it }
                )
            }
        }

        // ── launching inside the world ───────────────────────────────────────────
        if (state.snapshot.hasWorld) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(shard(13.dp), Morningstar.EdgeQuiet)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("APPS IN THE WORLD", style = Morningstar.overline(9).copy(color = Morningstar.Gilded))
                state.snapshot.worldPackages.take(20).forEach { pkg ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            pkg,
                            style = Morningstar.body(11).copy(color = Morningstar.Bone),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onLaunchInWorld(pkg) }) {
                            Text("LAUNCH", style = Morningstar.label(9).copy(color = Morningstar.Crimson))
                        }
                    }
                }
                if (state.snapshot.worldPackages.isEmpty()) {
                    Text(
                        "The world has no launchable apps yet. Install one into the profile first.",
                        style = Morningstar.caption(10).copy(color = Morningstar.Smoke)
                    )
                }
                OutlinedTextField(
                    value = packageInput,
                    onValueChange = { packageInput = it },
                    label = { Text("package to launch in the world", style = Morningstar.caption(10)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onLaunchInWorld(packageInput.trim()) },
                    enabled = PrivilegeCommands.validPackage(packageInput.trim()),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Morningstar.Crimson,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("LAUNCH IN WORLD", style = Morningstar.label(9).copy(color = Color.White)) }
            }
        }

        // ── the owner's power, stated plainly ────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(shard(13.dp), Morningstar.EdgeQuiet)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("OWNER ACTIONS", style = Morningstar.overline(9).copy(color = Morningstar.Gilded))
            Text(
                "These change another app's device state and always need an owner backend. Lucifer will " +
                    "not hide, suspend or uninstall Dhizuku, Shizuku, Permission Manager X, or himself — " +
                    "those refusals happen before any confirmation appears.",
                style = Morningstar.caption(10).copy(color = Morningstar.Ash)
            )
            Text(
                "Permission Manager X stays the owner's tool: Lucifer inspects grants read-only and " +
                    "refuses to edit PMX's own state.",
                style = Morningstar.caption(10).copy(color = Morningstar.Smoke)
            )
            OutlinedTextField(
                value = ownerPackage,
                onValueChange = { ownerPackage = it },
                label = { Text("target package", style = Morningstar.caption(10)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = ownerPermission,
                onValueChange = { ownerPermission = it },
                label = { Text("runtime permission (for grant/deny)", style = Morningstar.caption(10)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OwnerButton("HIDE", ownerPackageValid, Modifier.weight(1f)) {
                    pendingOwner = PrivilegeOp.OwnerHidden(ownerPackage.trim(), hidden = true)
                }
                OwnerButton("UNHIDE", ownerPackageValid, Modifier.weight(1f)) {
                    pendingOwner = PrivilegeOp.OwnerHidden(ownerPackage.trim(), hidden = false)
                }
                OwnerButton("SUSPEND", ownerPackageValid, Modifier.weight(1f)) {
                    pendingOwner = PrivilegeOp.OwnerSuspended(ownerPackage.trim(), suspended = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OwnerButton(
                    "GRANT",
                    ownerPackageValid && PrivilegeCommands.validPermission(ownerPermission),
                    Modifier.weight(1f)
                ) {
                    pendingOwner = PrivilegeOp.OwnerPermission(ownerPackage.trim(), ownerPermission.trim(), granted = true)
                }
                OwnerButton(
                    "DENY",
                    ownerPackageValid && PrivilegeCommands.validPermission(ownerPermission),
                    Modifier.weight(1f)
                ) {
                    pendingOwner = PrivilegeOp.OwnerPermission(ownerPackage.trim(), ownerPermission.trim(), granted = false)
                }
            }
        }

        // ── the Lean verifier Lucifer hosts in the world ─────────────────────────
        VerifierCard(state = verifier, onCommand = onVerifier)

        // ── audit trail ──────────────────────────────────────────────────────────
        if (state.ledger.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassSurface(shard(13.dp), Morningstar.EdgeQuiet)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("AUDIT TRAIL", style = Morningstar.overline(9).copy(color = Morningstar.Gilded))
                state.ledger.takeLast(12).reversed().forEach { entry ->
                    Text(
                        "${if (entry.ok) "·" else "×"} ${entry.label} — ${entry.provider.label}",
                        style = Morningstar.mono(9).copy(
                            color = if (entry.ok) Morningstar.Bone else Morningstar.Crimson
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        state.message?.let { message ->
            Text(message, style = Morningstar.caption(11).copy(color = Morningstar.Gilded))
        }
        Spacer(Modifier.height(18.dp))
    }

    pending?.let { action ->
        val preview = previewFor(action, state.snapshot.world?.id)
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(action.label, style = Morningstar.title(15).copy(color = Morningstar.Bone)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Lucifer will do exactly this, once:",
                        style = Morningstar.caption(11).copy(color = Morningstar.Ash)
                    )
                    Text(
                        preview,
                        style = Morningstar.mono(11).copy(color = Morningstar.Gilded)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onConfirmAction(action)
                }) { Text("DO IT", style = Morningstar.label(10).copy(color = Morningstar.Crimson)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text("CANCEL", style = Morningstar.label(10).copy(color = Morningstar.Ash))
                }
            },
            containerColor = Morningstar.Obsidian
        )
    }

    pendingOwner?.let { op ->
        val plan = PrivilegeCommands.plan(op)
        AlertDialog(
            onDismissRequest = { pendingOwner = null },
            title = { Text(op.label, style = Morningstar.title(14).copy(color = Morningstar.Bone)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "This is a device-owner call, carried over Binder as the owner. One call, once:",
                        style = Morningstar.caption(11).copy(color = Morningstar.Ash)
                    )
                    Text(
                        plan.refusal ?: "DevicePolicyManager.${op.methodHint()}",
                        style = Morningstar.mono(11).copy(
                            color = if (plan.refusal != null) Morningstar.Crimson else Morningstar.Gilded
                        )
                    )
                    if (plan.refusal != null) {
                        Text(
                            "That request is refused before it reaches any backend.",
                            style = Morningstar.caption(10).copy(color = Morningstar.Smoke)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingOwner = null
                        onOwnerOp(op)
                    },
                    enabled = plan.refusal == null
                ) { Text("DO IT", style = Morningstar.label(10).copy(color = Morningstar.Crimson)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingOwner = null }) {
                    Text("CANCEL", style = Morningstar.label(10).copy(color = Morningstar.Ash))
                }
            },
            containerColor = Morningstar.Obsidian
        )
    }
}

private fun PrivilegeOp.methodHint(): String = when (this) {
    is PrivilegeOp.OwnerHidden -> "setApplicationHidden(pkg, ${if (hidden) "true" else "false"}, user $userId)"
    is PrivilegeOp.OwnerSuspended -> "setPackagesSuspended(pkg, ${if (suspended) "true" else "false"}, user $userId)"
    is PrivilegeOp.OwnerPermission -> "setPermissionGrantState(pkg, $permission, user $userId)"
    else -> "call"
}

@Composable
private fun OwnerButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = Morningstar.GlassRaised,
            contentColor = Morningstar.Bone,
            disabledContainerColor = Morningstar.Glass,
            disabledContentColor = Morningstar.Smoke
        )
    ) { Text(label, style = Morningstar.label(9).copy(color = if (enabled) Morningstar.Bone else Morningstar.Smoke)) }
}

@Composable
private fun ProviderRow(provider: ProviderState) {
    val color = when {
        provider.available -> Morningstar.Success
        provider.provider == Provider.NONE -> Morningstar.Smoke
        else -> Morningstar.Warning
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(end = 8.dp)
                .width(4.dp)
                .height(4.dp)
                .glassSurface(shard(2.dp), color)
        )
        Text(
            provider.provider.label,
            style = Morningstar.label(10).copy(color = Morningstar.Bone),
            modifier = Modifier.weight(1f)
        )
        Text(
            if (provider.available) "READY" else "OFF",
            style = Morningstar.overline(8).copy(color = color)
        )
    }
    Text(
        provider.detail,
        style = Morningstar.caption(9).copy(color = Morningstar.Smoke),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun WorldButton(
    label: String,
    action: WorldAction,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onRequest: (WorldAction) -> Unit
) {
    val destructive = action == WorldAction.CLOSE_WORLD
    Button(
        onClick = { onRequest(action) },
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) Morningstar.Blood else Morningstar.GlassRaised,
            contentColor = if (destructive) Color.White else Morningstar.Bone,
            disabledContainerColor = Morningstar.Glass,
            disabledContentColor = Morningstar.Smoke
        )
    ) {
        Text(
            label,
            style = Morningstar.label(9).copy(
                color = if (destructive) Color.White else Morningstar.Bone
            )
        )
    }
}

/** The literal command behind a button, so the dialog cannot promise something different. */
private fun previewFor(action: WorldAction, worldId: Int?): String = when (action) {
    WorldAction.CREATE_WORLD -> WorldCommands.createManagedProfile().command ?: "pm create-user (unavailable)"
    WorldAction.OPEN_WORLD -> worldId?.let { WorldCommands.startUser(it).command } ?: "am start-user (no world yet)"
    WorldAction.CLOSE_WORLD -> worldId?.let { WorldCommands.stopUser(it).command } ?: "am stop-user (no world yet)"
    WorldAction.START_VM -> "CrossProfileApps.startMainActivity(com.android.virtualization.terminal)"
    WorldAction.LAUNCH_APP -> "CrossProfileApps.startMainActivity(<app>) — no shell involved"
    WorldAction.INSPECT -> "pm list users"
    WorldAction.OPEN_SETTINGS -> "Settings.ACTION_MANAGED_PROFILE_SETTINGS"
}

/** Owner-side actions exposed to the panel, each one already validated by PrivilegeCommands. */
object WorldOwnerActions {
    fun hide(pkg: String): PrivilegeOp = PrivilegeOp.OwnerHidden(pkg, hidden = true)
    fun unhide(pkg: String): PrivilegeOp = PrivilegeOp.OwnerHidden(pkg, hidden = false)
    fun suspend(pkg: String): PrivilegeOp = PrivilegeOp.OwnerSuspended(pkg, suspended = true)
    fun granted(pkg: String, permission: String): PrivilegeOp =
        PrivilegeOp.OwnerPermission(pkg, permission, granted = true)
}
