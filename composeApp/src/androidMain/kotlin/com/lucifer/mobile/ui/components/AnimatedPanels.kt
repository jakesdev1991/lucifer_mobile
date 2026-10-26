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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.skills.ReferenceSkill
import com.lucifer.mobile.skills.SkillLibrary

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
    val status: String = if (isEnabled) "AVAILABLE" else "LIMITED",
    val complexity: Int = 1
)

@Composable
fun AnimatedSkillsPanel(
    modifier: Modifier = Modifier,
    skills: List<SkillItem> = listOf(),
    library: SkillLibrary? = null
) {
    var query by remember { mutableStateOf("") }
    val operationalMatches = skills.filter { skill ->
        query.isBlank() || listOf(skill.name, skill.description, skill.category, skill.status)
            .any { it.contains(query, ignoreCase = true) }
    }
    val referenceMatches = remember(query, library) {
        if (library == null) emptyList() else if (query.isBlank()) library.skills else library.search(query, limit = 50)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D050D))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "SKILLS & CAPABILITIES",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFD700),
            letterSpacing = 1.5.sp
        )
        Text(
            text = "Operational status is separate from reference guidance. Guidance entries do not add tools, authorization, or executable capability.",
            fontSize = 11.sp,
            color = Color(0xFF8B7D8B)
        )
        TextField(
            value = query,
            onValueChange = { query = it.take(100) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search skills", tint = Color(0xFF8B7D8B)) },
            colors = TextFieldDefaults.textFieldColors(
                containerColor = Color(0xFF1A0A1A),
                focusedTextColor = Color(0xFFE0E0E0),
                unfocusedTextColor = Color(0xFFE0E0E0),
                cursorColor = Color(0xFFFFD700),
                focusedIndicatorColor = Color(0xFF8B0000),
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            placeholder = { Text("Search operational skills and reference guidance…", color = Color(0xFF665566), fontSize = 12.sp) }
        )
        Text(
            text = library?.statusSummary ?: "Reference skill library not loaded",
            fontSize = 10.sp,
            color = if (library?.isAvailable == true) Color(0xFF8B7D8B) else Color(0xFFFFAA00)
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (operationalMatches.isNotEmpty()) {
                item {
                    Text("IMPLEMENTED / OPERATIONAL REGISTRY", color = Color(0xFFFFD700), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                items(operationalMatches) { skill -> SkillCard(skill = skill) }
            }
            if (referenceMatches.isNotEmpty()) {
                item {
                    Text("REFERENCE LIBRARY · GUIDANCE ONLY", color = Color(0xFFB99DFF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                items(referenceMatches) { skill -> ReferenceSkillCard(skill = skill, library = library) }
            }
            if (operationalMatches.isEmpty() && referenceMatches.isEmpty()) {
                item {
                    Text("No skills match this search.", color = Color(0xFF8B7D8B), modifier = Modifier.padding(vertical = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun ReferenceSkillCard(skill: ReferenceSkill, library: SkillLibrary?) {
    var expanded by remember(skill.id) { mutableStateOf(false) }
    val pack = library?.packFor(skill)
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171222), contentColor = Color(0xFFE0E0E0)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF8066B3).copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(skill.name, color = Color(0xFFE2D6FF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("${pack?.title ?: skill.packId} · ${skill.readiness} · NOT RUNNABLE", color = Color(0xFFB99DFF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(skill.summary, color = Color(0xFFD0C9D8), fontSize = 12.sp, lineHeight = 18.sp)
            if (expanded && pack != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Prerequisites", color = Color(0xFFFFD700), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(pack.prerequisites, color = Color(0xFFCCCCCC), fontSize = 11.sp, lineHeight = 17.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Safety boundary", color = Color(0xFFFFD700), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(pack.safetyBoundary, color = Color(0xFFCCCCCC), fontSize = 11.sp, lineHeight = 17.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Verification / evidence", color = Color(0xFFFFD700), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(pack.verification, color = Color(0xFFCCCCCC), fontSize = 11.sp, lineHeight = 17.sp)
                if (pack.references.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Sources: ${pack.references.joinToString()}", color = Color(0xFF8B7D8B), fontSize = 9.sp, lineHeight = 14.sp)
                }
            }
            Text(if (expanded) "Tap to collapse" else "Tap for prerequisites, boundaries and evidence", color = Color(0xFF8B7D8B), fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun SkillCard(
    skill: SkillItem
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
                        .background(if (skill.isEnabled) Color(0xFF173B2D) else Color(0xFF332233), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = skill.status,
                        color = if (skill.isEnabled) Color(0xFF00FF88) else Color(0xFFFFAA00),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
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
