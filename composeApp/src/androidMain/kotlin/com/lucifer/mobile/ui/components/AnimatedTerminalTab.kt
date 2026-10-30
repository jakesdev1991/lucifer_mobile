package com.lucifer.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.ui.theme.Morningstar

@Composable
fun AnimatedTerminal(
    modifier: Modifier = Modifier,
    outputLines: List<String> = listOf(),
    isProcessing: Boolean = false,
    target: Int = 0, // 0=LOCAL, 1=TERMUX, 2=VM, 3=SHIZUKU
    onTargetSelected: (Int) -> Unit = {},
    onCommand: (String) -> Unit = {},
    onOpenInteractiveTermux: () -> Unit = {},
    onInstallTermuxTools: () -> Unit = {},
    onOpenAndroidLinux: () -> Unit = {},
    onStageStaticSite: () -> Unit = {},
    onStageApiSite: () -> Unit = {},
    onStagePaymentDemo: () -> Unit = {},
    onStopLocalSites: () -> Unit = {},
    /** Cancels whatever is running — a deep reasoning run, a command, a workflow step. */
    onStop: () -> Unit = {}
) {
    var cmdText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Morningstar.Void)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("LOCAL", "TERMUX", "VM", "SHIZUKU").forEachIndexed { idx, label ->
                val isSelected = target == idx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(
                            if (isSelected) Morningstar.Blood else Morningstar.Glass,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onTargetSelected(idx) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = Morningstar.MonoTail,
                        color = if (isSelected) Morningstar.Gilded else Morningstar.Ash
                    )
                }
            }
        }

        if (target == 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier.weight(1f).height(34.dp).background(Morningstar.SuccessDim, RoundedCornerShape(8.dp)).clickable { onOpenInteractiveTermux() },
                    contentAlignment = Alignment.Center
                ) { Text("OPEN PTY + CHAT", color = Morningstar.Success, fontSize = 10.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold) }
                Box(
                    modifier = Modifier.weight(1f).height(34.dp).background(Morningstar.InfoDim, RoundedCornerShape(8.dp)).clickable { onInstallTermuxTools() },
                    contentAlignment = Alignment.Center
                ) { Text("INSTALL TOOLKIT", color = Morningstar.Info, fontSize = 10.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier.weight(1f).height(32.dp).background(Morningstar.Whisper, RoundedCornerShape(8.dp)).clickable { onStageStaticSite() },
                    contentAlignment = Alignment.Center
                ) { Text("STAGE STATIC", color = Morningstar.Info, fontSize = 9.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold) }
                Box(
                    modifier = Modifier.weight(1f).height(32.dp).background(Morningstar.Whisper, RoundedCornerShape(8.dp)).clickable { onStageApiSite() },
                    contentAlignment = Alignment.Center
                ) { Text("STAGE API", color = Morningstar.Info, fontSize = 9.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier.weight(1f).height(32.dp).background(Morningstar.Whisper, RoundedCornerShape(8.dp)).clickable { onStagePaymentDemo() },
                    contentAlignment = Alignment.Center
                ) { Text("PAYMENT DEMO", color = Morningstar.Gilded, fontSize = 9.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold) }
                Box(
                    modifier = Modifier.weight(1f).height(32.dp).background(Morningstar.Whisper, RoundedCornerShape(8.dp)).clickable { onStopLocalSites() },
                    contentAlignment = Alignment.Center
                ) { Text("STOP PREVIEWS", color = Morningstar.Crimson, fontSize = 9.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold) }
            }
        } else if (target == 2) {
            Box(
                modifier = Modifier.fillMaxWidth().height(34.dp).background(Morningstar.SuccessDim, RoundedCornerShape(8.dp)).clickable { onOpenAndroidLinux() },
                contentAlignment = Alignment.Center
            ) { Text("OPEN ANDROID LINUX + CHAT", color = Morningstar.Success, fontSize = 10.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold) }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            items(outputLines) { line ->
                Text(
                    text = line,
                    fontFamily = Morningstar.MonoTail,
                    fontSize = 12.sp,
                    color = when {
                        line.startsWith("[ERROR]") -> Morningstar.Crimson
                        line.startsWith("[WARN]") -> Morningstar.Warning
                        line.startsWith("[SUCCESS]") -> Morningstar.Success
                        line.startsWith("[INFO]") -> Morningstar.Info
                        line.startsWith("$") -> Morningstar.Gilded
                        else -> Morningstar.Bone
                    },
                    modifier = Modifier.fillMaxWidth(),
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (isProcessing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "…executing",
                    color = Morningstar.Gilded,
                    fontSize = 11.sp,
                    fontFamily = Morningstar.MonoTail
                )
                Text(
                    text = "STOP",
                    color = Morningstar.Crimson,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Morningstar.MonoTail,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .background(Morningstar.GlassRaised, RoundedCornerShape(4.dp))
                        .clickable { onStop() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$ ",
                fontFamily = Morningstar.MonoTail,
                fontSize = 14.sp,
                color = Morningstar.Gilded
            )
            TextField(
                value = cmdText,
                onValueChange = { cmdText = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    val trimmed = cmdText.trim()
                    if (trimmed.isNotBlank()) {
                        onCommand(trimmed)
                        cmdText = ""
                    }
                }),
                visualTransformation = VisualTransformation.None,
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Color.Transparent,
                    focusedTextColor = Morningstar.Bone,
                    unfocusedTextColor = Morningstar.Bone,
                    cursorColor = Morningstar.Gilded,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("Ask naturally or enter a command…", color = Morningstar.Ash) }
            )
        }
    }
}
