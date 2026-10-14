package com.lucifer.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MemoryItem(
    val id: String,
    val content: String,
    val timestamp: Long,
    val tags: List<String>,
    val relevance: Float = 1.0f
)

@Composable
fun AnimatedMemoryPanel(
    modifier: Modifier = Modifier,
    memories: List<MemoryItem> = listOf(),
    onSearch: (String) -> Unit = {},
    isSearching: Boolean = false
) {
    var searchText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D050D))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color(0xFF8B7D8B))
            TextField(
                value = searchText,
                onValueChange = { searchText = it; onSearch(it) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Color(0xFF1A0A1A),
                    focusedTextColor = Color(0xFFE0E0E0),
                    unfocusedTextColor = Color(0xFFE0E0E0),
                    cursorColor = Color(0xFFFFD700),
                    focusedIndicatorColor = Color(0xFF8B0000),
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("Semantic search memories...", color = Color(0xFF665566)) }
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(memories) { memory ->
                MemoryCard(memory = memory)
            }
        }

        if (memories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Memory, contentDescription = "No memories", tint = Color(0xFF443344), modifier = Modifier.size(48.dp))
                    Text("No memories found", color = Color(0xFF665566), fontSize = 16.sp)
                    Text("Search or sync to populate", color = Color(0xFF443344), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun MemoryCard(
    memory: MemoryItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A0A1A),
            contentColor = Color(0xFFE0E0E0)
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF8B0000).copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = java.text.SimpleDateFormat("MMM dd, HH:mm").format(java.util.Date(memory.timestamp)),
                    fontSize = 11.sp,
                    color = Color(0xFF8B7D8B)
                )
                Text(
                    text = String.format("%.0f%%", memory.relevance * 100),
                    fontSize = 11.sp,
                    color = Color(0xFFFFD700),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = memory.content,
                fontSize = 13.sp,
                color = Color(0xFFE0E0E0),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

data class SkillItem(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val isEnabled: Boolean,
    val icon: ImageVector,
    val complexity: Int = 1
)

@Composable
fun AnimatedSkillsPanel(
    modifier: Modifier = Modifier,
    skills: List<SkillItem> = listOf(),
    onSkillToggle: (String, Boolean) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D050D))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "SKILLS & CAPABILITIES",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFD700),
            letterSpacing = 1.5.sp
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(skills) { skill ->
                SkillCard(skill = skill, onToggle = onSkillToggle)
            }
        }
    }
}

@Composable
fun SkillCard(
    skill: SkillItem,
    onToggle: (String, Boolean) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val toggleAnim by animateFloatAsState(
        targetValue = if (skill.isEnabled) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A0A1A),
            contentColor = Color(0xFFE0E0E0)
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF8B0000).copy(alpha = 0.2f + toggleAnim * 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = skill.icon,
                        contentDescription = skill.name,
                        tint = Color(0xFF8B0000).copy(alpha = 0.5f + toggleAnim * 0.5f),
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer { scaleX = 0.8f + toggleAnim * 0.2f; scaleY = 0.8f + toggleAnim * 0.2f }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(text = skill.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (skill.isEnabled) Color(0xFFFFD700) else Color(0xFFE0E0E0))
                        Text(text = skill.category, fontSize = 11.sp, color = Color(0xFF8B7D8B))
                    }
                }

                Box(
                    modifier = Modifier
                        .size(52.dp, 28.dp)
                        .background(if (skill.isEnabled) Color(0xFF8B0000) else Color(0xFF332233), RoundedCornerShape(14.dp))
                        .clickable { onToggle(skill.id, !skill.isEnabled) }
                        .graphicsLayer { scaleX = 0.9f + toggleAnim * 0.1f; scaleY = 0.9f + toggleAnim * 0.1f }
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color(0xFFFFD700), CircleShape)
                            .offset { IntOffset(((if (skill.isEnabled) 26.dp.toPx() else 2.dp.toPx()) * toggleAnim).toInt(), 0) }
                            .graphicsLayer { scaleX = 0.8f + toggleAnim * 0.2f; scaleY = 0.8f + toggleAnim * 0.2f }
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(300, easing = EaseOut)
                ),
                exit = shrinkVertically(animationSpec = tween(200, easing = EaseIn))
            ) {
                Column(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = skill.description, fontSize = 12.sp, color = Color(0xFFCCCCCC), lineHeight = 20.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Complexity: ${skill.complexity}/5", fontSize = 10.sp, color = Color(0xFF8B7D8B))
                }
            }

            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = Color(0xFF8B7D8B),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable { isExpanded = !isExpanded }
                    .graphicsLayer { rotationZ = if (isExpanded) 180f else 0f }
            )
        }
    }
}
