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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import com.lucifer.mobile.ui.theme.Morningstar

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
    selectedTarget: CommandTarget,
    onTargetSelected: (CommandTarget) -> Unit,
    pendingAction: TerminalAction?,
    pendingDestinationDetails: String? = null,
    onConfirmAction: () -> Unit,
    onCancelAction: () -> Unit,
    onSend: (String) -> Unit,
    onClose: () -> Unit,
    thinking: Boolean = false,
    /** What the loop is doing right now: "reading the corpus", "fetching a page". Empty when idle. */
    progress: String = "",
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
            .background(Morningstar.Void)
            .border(1.dp, Morningstar.Blood.copy(alpha = 0.35f), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Morningstar.Glass)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LUCIFER · CHAT",
                    color = Morningstar.Gilded,
                    fontSize = 12.sp,
                    fontFamily = Morningstar.MonoTail,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "default: ${selectedTarget.displayName}",
                    color = Morningstar.Ash,
                    fontSize = 9.sp,
                    fontFamily = Morningstar.MonoTail
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Morningstar.GlassRaised)
                        .clickable { onToggleVoice() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        if (voiceMuted) "🔇" else "🔊",
                        fontSize = 11.sp, fontFamily = Morningstar.Body
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Morningstar.GlassRaised)
                        .clickable { onClose() }
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text("✕", color = Morningstar.Gilded, fontSize = 12.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold)
                }
            }

            // Explicit default destination, with natural-language target overrides supported too.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .background(Morningstar.Void)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CommandTarget.entries.forEach { destination ->
                    val selected = destination == selectedTarget
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) Morningstar.Blood else Morningstar.GlassRaised)
                            .clickable { onTargetSelected(destination) }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            destination.displayName,
                            color = if (selected) Morningstar.Gilded else Morningstar.Ash,
                            fontSize = 9.sp,
                            fontFamily = Morningstar.MonoTail,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Thinking shimmer — his mind is working
            if (thinking) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Morningstar.Void)
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
                        color = Morningstar.Gilded.copy(alpha = 0.4f + shimmer * 0.6f),
                        fontSize = 11.sp,
                        fontFamily = Morningstar.MonoTail
                    )
                    Text(
                        // A forty-second run that says nothing reads as a hang. When the loop reports a
                        // step, say what it is doing instead of the generic phrase.
                        if (progress.isBlank()) "understanding · routing · responding…" else progress,
                        color = Morningstar.Ash.copy(alpha = 0.5f + shimmer * 0.5f),
                        fontSize = 11.sp,
                        fontFamily = Morningstar.MonoTail,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
                                    if (msg.fromLucifer) Morningstar.Glass else Morningstar.CrimsonDim
                                )
                                .border(
                                    1.dp,
                                    if (msg.fromLucifer) Morningstar.Blood.copy(alpha = 0.3f) else Morningstar.Gilded.copy(alpha = 0.3f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = (if (msg.fromLucifer) "λ " else "» ") + msg.text,
                                color = if (msg.fromLucifer) Morningstar.Bone else Morningstar.Gilded,
                                fontSize = 12.sp,
                                fontFamily = Morningstar.MonoTail,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (pendingAction != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Morningstar.Whisper)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "CONFIRM · ${pendingAction.target.displayName}",
                        color = Morningstar.Gilded,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = Morningstar.MonoTail
                    )
                    pendingDestinationDetails?.let {
                        Text(it, color = Morningstar.Ash, fontSize = 9.sp, fontFamily = Morningstar.MonoTail)
                    }
                    Text(
                        pendingAction.command,
                        color = Morningstar.Bone,
                        fontSize = 11.sp,
                        fontFamily = Morningstar.MonoTail,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morningstar.Blood)
                                .clickable { onConfirmAction() }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text("RUN COMMAND", color = Morningstar.Gilded, fontSize = 10.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morningstar.GlassRaised)
                                .clickable { onCancelAction() }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text("CANCEL", color = Morningstar.Bone, fontSize = 10.sp, fontFamily = Morningstar.Body)
                        }
                    }
                }
            }

            // Input bar (bottom)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Morningstar.Void)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "λ ",
                    color = Morningstar.Gilded,
                    fontSize = 16.sp,
                    fontFamily = Morningstar.MonoTail
                )
                Box(Modifier.weight(1f)) {
                    if (input.isEmpty()) {
                        Text(
                            "speak to him…",
                            color = Morningstar.Ash,
                            fontSize = 12.sp,
                            fontFamily = Morningstar.MonoTail
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        enabled = !thinking && pendingAction == null,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Morningstar.Bone,
                            fontSize = 12.sp,
                            fontFamily = Morningstar.MonoTail
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            val trimmed = input.trim()
                            if (trimmed.isNotBlank()) {
                                onSend(trimmed)
                                input = ""
                            }
                        }),
                        cursorBrush = Brush.verticalGradient(listOf(Morningstar.Gilded, Morningstar.Blood)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morningstar.Blood)
                        .clickable(enabled = !thinking && pendingAction == null) {
                            val trimmed = input.trim()
                            if (trimmed.isNotBlank()) {
                                onSend(trimmed)
                                input = ""
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("SEND", color = Morningstar.Gilded, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Morningstar.MonoTail)
                }
            }
        }
    }
}
