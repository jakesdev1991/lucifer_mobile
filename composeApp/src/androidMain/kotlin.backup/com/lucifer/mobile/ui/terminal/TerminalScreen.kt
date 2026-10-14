package com.lucifer.mobile.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.tint
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.ui.components.LuciferButton
import com.lucifer.mobile.ui.components.LuciferIcon
import com.lucifer.mobile.ui.components.LuciferTextField
import com.lucifer.mobile.ui.theme.LuciferColors
import com.lucifer.mobile.ui.theme.LuciferTheme
import androidx.compose.runtime.MutableState
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
fun TerminalScreen(
    onNavigateBack: () -> Unit
) {
    var commandInput by remember { mutableStateOf("") }
    var output by remember { mutableStateOf(listOf<TerminalLine>()) }
    var isExecuting by remember { mutableStateOf(false) }
    var currentPath by remember { mutableStateOf("/data/data/com.inspiredandroid.kai/files") }
    
    // Initialize with welcome message
    if (output.isEmpty()) {
        output = listOf(
            TerminalLine("😈 Lucifer Terminal v1.0", TerminalType.INFO),
            TerminalLine("Connected to Kai9000 sandbox (Linux rootfs)", TerminalType.SUCCESS),
            TerminalLine("Python 3.12, Node.js 22, Full Linux rootfs available", TerminalType.INFO),
            TerminalLine("Type 'help' for available commands", TerminalType.INFO),
            TerminalLine("$ ", TerminalType.PROMPT)
        )
    }
    
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        TerminalHeader(
            currentPath = currentPath,
            onNavigateBack = onNavigateBack
        )
        
        // Output area
        Box(modifier = Modifier
            .fillMaxSize()
            .weight(1f)
            .padding(16.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = LuciferColors.Black,
                border = androidx.compose.ui.graphics.BorderStroke(1.dp, LuciferColors.Outline)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(output.reversed()) { line ->
                        TerminalLineView(line = line)
                    }
                }
            }
        }
        
        // Input area
        TerminalInput(
            commandInput = commandInput,
            onCommandChange = { commandInput = it },
            onExecute = { executeCommand() },
            currentPath = currentPath,
            isExecuting = isExecuting
        )
    }
    
    fun executeCommand() {
        if (commandInput.isNotBlank() && !isExecuting) {
            val cmd = commandInput
            output = output + TerminalLine("$ $cmd", TerminalType.COMMAND)
            commandInput = ""
            isExecuting = true
            
            // Execute in Kai sandbox via OmniBridge
            // For now, simulate common commands
            val result = when (cmd.trim().lowercase()) {
                "help" -> listOf(
                    TerminalLine("Available commands:", TerminalType.INFO),
                    TerminalLine("  help          - Show this help", TerminalType.INFO),
                    TerminalLine("  ls [path]     - List directory", TerminalType.INFO),
                    TerminalLine("  cat <file>    - Show file contents", TerminalType.INFO),
                    TerminalLine("  pwd           - Show current directory", TerminalType.INFO),
                    TerminalLine("  cd <dir>      - Change directory", TerminalType.INFO),
                    TerminalLine("  python <file> - Run Python script", TerminalType.INFO),
                    TerminalLine("  node <file>   - Run Node.js script", TerminalType.INFO),
                    TerminalLine("  wallet        - Wallet operations", TerminalType.INFO),
                    TerminalLine("  browser       - Browser automation", TerminalType.INFO),
                    TerminalLine("  llm <prompt>  - Query LLM", TerminalType.INFO),
                    TerminalLine("  clear         - Clear terminal", TerminalType.INFO),
                    TerminalLine("  exit          - Close terminal", TerminalType.INFO)
                )
                "ls" -> executeLs()
                "pwd" -> listOf(TerminalLine(currentPath, TerminalType.OUTPUT))
                "clear" -> {
                    output = listOf(TerminalLine("$ ", TerminalType.PROMPT))
                    isExecuting = false
                    return
                }
                "exit" -> {
                    // Navigate back
                    isExecuting = false
                    return
                }
                else -> {
                    if (cmd.startsWith("ls ")) {
                        val path = cmd.substring(3).trim()
                        executeLs(path)
                    } else if (cmd.startsWith("cd ")) {
                        val path = cmd.substring(3).trim()
                        executeCd(path)
                    } else if (cmd.startsWith("cat ")) {
                        val file = cmd.substring(4).trim()
                        executeCat(file)
                    } else if (cmd.startsWith("python ")) {
                        val script = cmd.substring(7).trim()
                        executePython(script)
                    } else if (cmd.startsWith("node ")) {
                        val script = cmd.substring(5).trim()
                        executeNode(script)
                    } else if (cmd.startsWith("wallet ")) {
                        executeWallet(cmd.substring(7).trim())
                    } else if (cmd.startsWith("browser ")) {
                        executeBrowser(cmd.substring(8).trim())
                    } else if (cmd.startsWith("llm ")) {
                        executeLLM(cmd.substring(4).trim())
                    } else {
                        listOf(TerminalLine("Unknown command: $cmd. Type 'help' for commands.", TerminalType.ERROR))
                    }
                }
            
            output = output + result + TerminalLine("$ ", TerminalType.PROMPT)
            isExecuting = false
        }
    }
    
    fun executeLs(path: String = currentPath): List<TerminalLine> {
        // Execute in Kai sandbox
        // For now, simulate
        return listOf(
            TerminalLine("drwxr-xr-x 2 user user 4096 Jan 1 00:00 linux-sandbox", TerminalType.OUTPUT),
            TerminalLine("drwxr-xr-x 2 user user 4096 Jan 1 00:00 litert_models", TerminalType.OUTPUT),
            TerminalLine("-rw------- 1 user user 12 Jul 20 03:48 profileInstalled", TerminalType.OUTPUT),
            TerminalLine("-rw------- 1 user user 24 Sep 9 03:04 eidetic_core.bin", TerminalType.OUTPUT)
        )
    }
    
    fun executeCd(path: String) {
        // Update current path
        currentPath = if (path.startsWith("/")) path else "$currentPath/$path"
    }
    
    fun executeCat(file: String) {
        output = output + TerminalLine("[File contents would be shown here]", TerminalType.INFO)
    }
    
    fun executePython(script: String) {
        output = output + TerminalLine("[Python execution: $script]", TerminalType.INFO)
    }
    
    fun executeNode(script: String) {
        output = output + TerminalLine("[Node.js execution: $script]", TerminalType.INFO)
    }
    
    fun executeWallet(args: String) {
        output = output + TerminalLine("[Wallet: $args]", TerminalType.INFO)
    }
    
    fun executeBrowser(args: String) {
        output = output + TerminalLine("[Browser: $args]", TerminalType.INFO)
    }
    
    fun executeLLM(prompt: String) {
        output = output + TerminalLine("[LLM Query: $prompt]", TerminalType.INFO)
    }
}

data class TerminalLine(
    val text: String,
    val type: TerminalType = TerminalType.OUTPUT
)

enum class TerminalType {
    OUTPUT, COMMAND, ERROR, SUCCESS, INFO, PROMPT
}

@Composable
fun TerminalHeader(
    currentPath: String,
    onNavigateBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { /* navigate back */ }) {
                LuciferIcon.ChevronLeft(tint = LuciferColors.Gold)
            }
            Text(
                text = "😈 Lucifer Terminal",
                style = androidx.compose.material3.Typography.titleMedium,
                color = LuciferColors.Red,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = currentPath,
                style = androidx.compose.material3.Typography.bodySmall,
                color = LuciferColors.OnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { /* minimize */ }) {
                LuciferIcon.Minimize(tint = LuciferColors.Gold)
            }
            IconButton(onClick = { /* maximize */ }) {
                LuciferIcon.Maximize(tint = LuciferColors.Gold)
            }
            IconButton(onClick = { /* close */ }) {
                LuciferIcon.Close(tint = LuciferColors.Error)
            }
        }
    }
}

@Composable
fun TerminalLineView(line: TerminalLine) {
    val (textColor, prefix) = when (line.type) {
        TerminalType.COMMAND -> LuciferColors.Gold to ""
        TerminalType.ERROR -> LuciferColors.Error to "✗ "
        TerminalType.SUCCESS -> LuciferColors.Gold to "✓ "
        TerminalType.INFO -> LuciferColors.GoldLight to "ℹ "
        TerminalType.PROMPT -> LuciferColors.Red to ""
        TerminalType.OUTPUT -> LuciferColors.OnSurface to ""
        TerminalType.COMMAND -> LuciferColors.Gold to ""
    }
    
    Text(
        text = "${prefix}${line.text}",
        style = androidx.compose.material3.Typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        ),
        color = textColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
fun TerminalInput(
    commandInput: String,
    onCommandChange: (String) -> Unit,
    onExecute: () -> Unit,
    currentPath: String,
    isExecuting: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = LuciferColors.Obsidian
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$currentPath $ ",
                style = androidx.compose.material3.Typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                color = LuciferColors.Red
            )
            LuciferTextField(
                value = commandInput,
                onValueChange = onCommandChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = "Enter command...",
                singleLine = true,
                onKeyEvent = { keyEvent ->
                    if (keyEvent.key == androidx.compose.ui.platform.Key.Enter && keyEvent.type == androidx.compose.ui.input.key.KeyEventType.Down) {
                        onExecute()
                        true
                    } else false
                }
            )
        }
    }
}