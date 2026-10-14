package com.lucifer.mobile.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import com.lucifer.mobile.ui.theme.LuciferColors
import com.lucifer.mobile.ui.theme.LuciferTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import com.lucifer.mobile.ui.theme.LuciferTheme
import com.lucifer.mobile.ui.theme.LuciferColors
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferTextField
import com.lucifer.mobile.ui.components.LuciferButton
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.IconButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import com.lucifer.mobile.ui.theme.LuciferTheme
import com.lucifer.mobile.ui.theme.LuciferColors
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferTextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferTextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferTextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferTextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferTextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferTextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferTextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferTextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferTextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

@Composable
fun ChatScreen(
    onNavigateToTerminal: () -> Unit,
    onNavigateToWallet: () -> Unit,
    onNavigateToBrowser: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    // Add welcome message
    if (messages.isEmpty()) {
        messages = listOf(
            ChatMessage(
                role = "assistant",
                content = "😈 **Lucifer Online** — The Four Horsemen await your command.\n\n**Conquest** — Planning, architecture, synthesis\n**War** — Red teaming, threat modeling, contradiction search\n**Famine** — Low-budget, latency-constrained execution\n**Death** — Invalid assumption detection, branch pruning\n\nWhat shall we conquer today?",
                horseman = Horseman.CONQUEST
            )
        )
    }
    
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header with Horseman selector
        HorsemanSelector()
        
        // Messages
        Box(modifier = Modifier
            .fillMaxSize()
            .weight(1f)
            .padding(16.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages.reversed()) { message ->
                    MessageBubble(message = message)
                }
            }
        }
        
        // Input area
        ChatInput(
            text = inputText,
            onTextChange = { inputText = it },
            onSend = { sendMessage() },
            isLoading = isLoading
        )
    }
    
    fun sendMessage() {
        if (inputText.isNotBlank() && !isLoading) {
            val userMessage = ChatMessage(
                role = "user",
                content = inputText,
                horseman = Horseman.CONQUEST
            )
            messages = messages + userMessage
            val currentInput = inputText
            inputText = ""
            isLoading = true
            
            // TODO: Call LLM via OmniBridge
            // For now, simulate response
            Thread.sleep(1000)
            val response = ChatMessage(
                role = "assistant",
                content = "Lucifer acknowledges: $currentInput\n\n[Four Horsemen processing...]",
                horseman = Horseman.CONQUEST
            )
            messages = messages + response
            isLoading = false
        }
}

data class ChatMessage(
    val role: String, // "user" | "assistant"
    val content: String,
    val horseman: Horseman = Horseman.CONQUEST
)

enum class Horseman {
    CONQUEST,
    WAR,
    FAMINE,
    DEATH
}

@Composable
fun HorsemanSelector() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Horseman.values().forEach { horseman ->
            HorsemanChip(horseman = horseman, selected = false, onClick = {})
        }
    }
}

@Composable
fun HorsemanChip(
    horseman: Horseman,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) LuciferColors.Red else LuciferColors.SurfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp, 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(id = when (horseman) {
                    Horseman.CONQUEST -> R.drawable.ic_conquest
                    Horseman.WAR -> R.drawable.ic_war
                    Horseman.FAMINE -> R.drawable.ic_famine
                    Horseman.DEATH -> R.drawable.ic_death
                }),
                contentDescription = horseman.name,
                tint = if (selected) Color.White else LuciferColors.Gold
            )
            Text(
                text = horseman.name,
                style = androidx.compose.material3.Typography.labelMedium,
                color = if (selected) Color.White else LuciferColors.OnSurfaceVariant
            )
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val horsemanColor = when (message.horseman) {
        Horseman.CONQUEST -> LuciferColors.Red
        Horseman.WAR -> LuciferColors.Gold
        Horseman.FAMINE -> LuciferColors.GoldLight
        Horseman.DEATH -> LuciferColors.Error
    }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .wrapContentSize()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) LuciferColors.Red else LuciferColors.SurfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = message.horseman.name,
                        style = androidx.compose.material3.Typography.labelSmall,
                        color = horsemanColor,
                        fontWeight = FontWeight.Bold
                    )
                    if (!isUser) {
                        Icon(
                            painter = painterResource(id = when (message.horseman) {
                                Horseman.CONQUEST -> R.drawable.ic_conquest
                                Horseman.WAR -> R.drawable.ic_war
                                Horseman.FAMINE -> R.drawable.ic_famine
                                Horseman.DEATH -> R.drawable.ic_death
                            }),
                            contentDescription = message.horseman.name,
                            tint = horsemanColor
                        )
                    }
                }
                Text(
                    text = message.content,
                    style = androidx.compose.material3.Typography.bodyMedium,
                    color = if (isUser) Color.White else LuciferColors.OnSurface
                )
            }
        }
    }
}

@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = LuciferColors.Surface
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LuciferTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = "Command the Four Horsemen...",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            if (isLoading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = LuciferColors.Gold
                )
            } else {
                LuciferButton(
                    onClick = { onSend() },
                    icon = { LuciferIcon.Send() },
                    text = "Send"
                )
            }
        }
    }
}