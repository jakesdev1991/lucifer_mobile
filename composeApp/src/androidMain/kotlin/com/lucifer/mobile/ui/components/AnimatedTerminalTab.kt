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
    onStopLocalSites: () -> Unit = {}
) {
    var cmdText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
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
                            if (isSelected) Color(0xFF8B0000) else Color(0xFF1A0A1A),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onTargetSelected(idx) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (isSelected) Color(0xFFFFD700) else Color(0xFF8B7D8B)
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
                    modifier = Modifier.weight(1f).height(34.dp).background(Color(0xFF164A36), RoundedCornerShape(8.dp)).clickable { onOpenInteractiveTermux() },
                    contentAlignment = Alignment.Center
                ) { Text("OPEN PTY + CHAT", color = Color(0xFFB8FFD8), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                Box(
                    modifier = Modifier.weight(1f).height(34.dp).background(Color(0xFF2A2440), RoundedCornerShape(8.dp)).clickable { onInstallTermuxTools() },
                    contentAlignment = Alignment.Center
                ) { Text("INSTALL TOOLKIT", color = Color(0xFFD8C8FF), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier.weight(1f).height(32.dp).background(Color(0xFF182A31), RoundedCornerShape(8.dp)).clickable { onStageStaticSite() },
                    contentAlignment = Alignment.Center
                ) { Text("STAGE STATIC", color = Color(0xFFB8E8FF), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                Box(
                    modifier = Modifier.weight(1f).height(32.dp).background(Color(0xFF182A31), RoundedCornerShape(8.dp)).clickable { onStageApiSite() },
                    contentAlignment = Alignment.Center
                ) { Text("STAGE API", color = Color(0xFFB8E8FF), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier.weight(1f).height(32.dp).background(Color(0xFF3A3020), RoundedCornerShape(8.dp)).clickable { onStagePaymentDemo() },
                    contentAlignment = Alignment.Center
                ) { Text("PAYMENT DEMO", color = Color(0xFFFFD98A), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                Box(
                    modifier = Modifier.weight(1f).height(32.dp).background(Color(0xFF3A2020), RoundedCornerShape(8.dp)).clickable { onStopLocalSites() },
                    contentAlignment = Alignment.Center
                ) { Text("STOP PREVIEWS", color = Color(0xFFFFCACA), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
        } else if (target == 2) {
            Box(
                modifier = Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF164A36), RoundedCornerShape(8.dp)).clickable { onOpenAndroidLinux() },
                contentAlignment = Alignment.Center
            ) { Text("OPEN ANDROID LINUX + CHAT", color = Color(0xFFB8FFD8), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
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
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = when {
                        line.startsWith("[ERROR]") -> Color(0xFFFF4444)
                        line.startsWith("[WARN]") -> Color(0xFFFFAA00)
                        line.startsWith("[SUCCESS]") -> Color(0xFF00FF88)
                        line.startsWith("[INFO]") -> Color(0xFF88CCFF)
                        line.startsWith("$") -> Color(0xFFFFD700)
                        else -> Color(0xFFE0E0E0)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (isProcessing) {
            Text(
                text = "…executing",
                color = Color(0xFFFFD700),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$ ",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = Color(0xFFFFD700)
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
                    focusedTextColor = Color(0xFFE0E0E0),
                    unfocusedTextColor = Color(0xFFE0E0E0),
                    cursorColor = Color(0xFFFFD700),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("Ask naturally or enter a command…", color = Color(0xFF665566)) }
            )
        }
    }
}
