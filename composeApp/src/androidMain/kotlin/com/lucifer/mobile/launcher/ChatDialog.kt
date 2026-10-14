package com.lucifer.mobile.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ChatMessage(
    val text: String,
    val fromLucifer: Boolean
)

/**
 * ChatDialog — the C chip's half-screen dialogue with Lucifer.
 * Bottom-anchored, 50% screen height, messages on top, input at the bottom.
 */
@Composable
fun ChatDialog(
    messages: List<ChatMessage>,
    targetLabel: String,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    thinking: Boolean = false,
    voiceMuted: Boolean = false,
    onToggleVoice: () -> Unit = {}
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    // Auto-focus the input when the dialog opens — keyboard rises immediately,
    // and taps never need to find the field.
    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    // Entrance: slide up from the bottom (interruptible, 280ms ease-out)
    val slideIn = remember { Animatable(260f) }
    LaunchedEffect(Unit) {
        slideIn.animateTo(0f, tween(280, easing = EaseOut))
    }

    // Auto-scroll to the newest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val halfScreen = (LocalConfiguration.current.screenHeightDp.dp / 2)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(halfScreen)
            .navigationBarsPadding()
            .imePadding()
            .graphicsLayer { translationY = slideIn.value }
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Color(0xFF0A030A))
            .border(1.dp, Color(0xFF8B0000).copy(alpha = 0.35f), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF160A16))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LUCIFER · CHAP",
                    color = Color(0xFFFFD700),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "exec: $targetLabel",
                    color = Color(0xFF8B7D8B),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF251525))
                        .clickable { onToggleVoice() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (voiceMuted) "🔇" else "🔊",
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF251525))
                        .clickable { onClose() }
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text("✕", color = Color(0xFFFFD700), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Thinking shimmer — his mind is working
            if (thinking) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF12060F))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val shimmer by rememberInfiniteTransition(label = "think").animateFloat(
                        initialValue = 0f, targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
                        label = "shimmer"
                    )
                    Text(
                        "λ ",
                        color = Color(0xFFFFD700).copy(alpha = 0.4f + shimmer * 0.6f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "reasoning from the axioms…",
                        color = Color(0xFF8B7D8B).copy(alpha = 0.5f + shimmer * 0.5f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages) { msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.fromLucifer) Arrangement.Start else Arrangement.End
                    ) {
                        Box(
                            modifier = Modifier
                                .width(290.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (msg.fromLucifer) Color(0xFF1A0A1A) else Color(0xFF3A0A0A)
                                )
                                .border(
                                    1.dp,
                                    if (msg.fromLucifer) Color(0xFF8B0000).copy(alpha = 0.3f) else Color(0xFFFFD700).copy(alpha = 0.3f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = (if (msg.fromLucifer) "λ " else "» ") + msg.text,
                                color = if (msg.fromLucifer) Color(0xFFE0E0E0) else Color(0xFFFFE9E9),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Input bar (bottom)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D050D))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "λ ",
                    color = Color(0xFFFFD700),
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
                Box(Modifier.weight(1f)) {
                    if (input.isEmpty()) {
                        Text(
                            "speak to him…",
                            color = Color(0xFF665566),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color(0xFFE0E0E0),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            val trimmed = input.trim()
                            if (trimmed.isNotBlank()) {
                                onSend(trimmed)
                                input = ""
                            }
                        }),
                        cursorBrush = Brush.verticalGradient(listOf(Color(0xFFFFD700), Color(0xFF8B0000))),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF8B0000))
                        .clickable {
                            val trimmed = input.trim()
                            if (trimmed.isNotBlank()) {
                                onSend(trimmed)
                                input = ""
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("SEND", color = Color(0xFFFFD700), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
