package com.lucifer.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.lucifer.mobile.ui.effects.glassSurface
import com.lucifer.mobile.ui.effects.shard
import com.lucifer.mobile.ui.theme.Morningstar
import com.lucifer.mobile.verification.Plan
import com.lucifer.mobile.verification.VerifierCharter
import com.lucifer.mobile.verification.VerifierUiCommand
import com.lucifer.mobile.verification.VerifierUiState

/**
 * The verifier, inside the World tab. Starting the server is a button because it opens a socket; the
 * tunnel is *not* a button, because publishing a port needs the owner to run the exact command and see
 * it happen. Keys are shown once, revoked in place, and the meter is always visible.
 */
@Composable
fun VerifierCard(
    state: VerifierUiState,
    onCommand: (VerifierUiCommand) -> Unit
) {
    var rootInput by remember(state.projects) {
        mutableStateOf(state.projects.firstOrNull()?.substringAfter("→ ")?.trim().orEmpty())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shard(13.dp), Morningstar.Edge)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("LEAN VERIFICATION", style = Morningstar.overline(9).copy(color = Morningstar.Gilded))
            Text(
                if (state.running) "LIVE" else "STOPPED",
                style = Morningstar.overline(9).copy(
                    color = if (state.running) Morningstar.Gilded else Morningstar.Smoke
                )
            )
        }
        Text(
            VerifierCharter.describe(),
            style = Morningstar.caption(10).copy(color = Morningstar.Ash)
        )

        SelectionContainer {
            Text(
                state.endpoint,
                style = Morningstar.mono(10).copy(color = Morningstar.Bone)
            )
        }
        Text(
            "exposure · ${state.exposure}",
            style = Morningstar.caption(9).copy(color = Morningstar.Smoke),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    onCommand(if (state.running) VerifierUiCommand.Stop else VerifierUiCommand.Start)
                },
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.running) Morningstar.Blood else Morningstar.Crimson,
                    contentColor = Color.White
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (state.running) "STOP" else "START SERVER",
                    style = Morningstar.label(9).copy(color = Color.White)
                )
            }
            Button(
                onClick = { onCommand(VerifierUiCommand.Refresh) },
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Morningstar.GlassRaised,
                    contentColor = Morningstar.Bone
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (state.busy) "…" else "REFRESH",
                    style = Morningstar.label(9).copy(color = Morningstar.Bone)
                )
            }
        }

        // ── projects ─────────────────────────────────────────────────────────────
        state.projects.forEach { project ->
            Text(project, style = Morningstar.mono(9).copy(color = Morningstar.Smoke))
        }
        OutlinedTextField(
            value = rootInput,
            onValueChange = { rootInput = it },
            label = { Text("Lean checkout on the remote host", style = Morningstar.caption(10)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(
            onClick = { onCommand(VerifierUiCommand.SetProjectRoot(rootInput.trim())) },
            enabled = rootInput.trim().startsWith("/")
        ) {
            Text("SET PROJECT ROOT", style = Morningstar.label(9).copy(color = Morningstar.Crimson))
        }

        // ── keys and money ───────────────────────────────────────────────────────
        Text(
            "${state.usedCredits} credits metered · $${"%.2f".format(state.billableUsd)} billable · " +
                "${state.keys.count { !it.revoked }} active keys",
            style = Morningstar.caption(10).copy(color = Morningstar.Bone)
        )
        state.keys.forEach { key ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${key.label} · ${key.plan}",
                        style = Morningstar.caption(10).copy(
                            color = if (key.revoked) Morningstar.Smoke else Morningstar.Bone
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${key.scopes} · used ${key.used} · " +
                            (key.remaining?.let { "left $it" } ?: "unbounded"),
                        style = Morningstar.caption(9).copy(color = Morningstar.Smoke),
                        maxLines = 1
                    )
                }
                if (!key.revoked) {
                    TextButton(onClick = { onCommand(VerifierUiCommand.RevokeKey(key.id)) }) {
                        Text("REVOKE", style = Morningstar.label(8).copy(color = Morningstar.Crimson))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onCommand(VerifierUiCommand.IssueInternalKey) },
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Morningstar.GlassRaised,
                    contentColor = Morningstar.Bone
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("MY KEY", style = Morningstar.label(9).copy(color = Morningstar.Bone))
            }
            Button(
                onClick = { onCommand(VerifierUiCommand.IssueKey(Plan.FREE)) },
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Morningstar.GlassRaised,
                    contentColor = Morningstar.Bone
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("FREE KEY", style = Morningstar.label(9).copy(color = Morningstar.Bone))
            }
            Button(
                onClick = { onCommand(VerifierUiCommand.IssueKey(Plan.METERED)) },
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Morningstar.GlassRaised,
                    contentColor = Morningstar.Bone
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("PAID KEY", style = Morningstar.label(9).copy(color = Morningstar.Bone))
            }
        }

        state.issuedToken?.let { token ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "THIS KEY IS SHOWN ONCE",
                    style = Morningstar.overline(9).copy(color = Morningstar.Crimson)
                )
                SelectionContainer {
                    Text(token, style = Morningstar.mono(9).copy(color = Morningstar.Bone))
                }
            }
        }

        state.message?.let { message ->
            Text(message, style = Morningstar.caption(10).copy(color = Morningstar.Ash))
        }
    }
}
