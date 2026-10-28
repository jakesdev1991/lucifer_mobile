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
import com.lucifer.mobile.ui.theme.Morningstar

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
            .background(Morningstar.Void)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, contentDescription = "Search", tint = Morningstar.Ash)
            TextField(
                value = searchText,
                onValueChange = { searchText = it; onSearch(it) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                colors = TextFieldDefaults.textFieldColors(
                    containerColor = Morningstar.Glass,
                    focusedTextColor = Morningstar.Bone,
                    unfocusedTextColor = Morningstar.Bone,
                    cursorColor = Morningstar.Gilded,
                    focusedIndicatorColor = Morningstar.Blood,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                placeholder = { Text("Semantic search memories...", color = Morningstar.Ash) }
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
                    Icon(Icons.Filled.Memory, contentDescription = "No memories", tint = Morningstar.Whisper, modifier = Modifier.size(48.dp))
                    Text("No memories found", color = Morningstar.Ash, fontSize = 16.sp, fontFamily = Morningstar.Body)
                    Text("Search or sync to populate", color = Morningstar.Whisper, fontSize = 12.sp, fontFamily = Morningstar.Body)
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
            containerColor = Morningstar.Glass,
            contentColor = Morningstar.Bone
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Morningstar.Blood.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = java.text.SimpleDateFormat("MMM dd, HH:mm").format(java.util.Date(memory.timestamp)),
                    fontSize = 11.sp, fontFamily = Morningstar.Body,
                    color = Morningstar.Ash
                )
                Text(
                    text = String.format("%.0f%%", memory.relevance * 100),
                    fontSize = 11.sp, fontFamily = Morningstar.Body,
                    color = Morningstar.Gilded,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = memory.content,
                fontSize = 13.sp, fontFamily = Morningstar.Body,
                color = Morningstar.Bone,
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
            .background(Morningstar.Void)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "SKILLS & CAPABILITIES",
            fontSize = 14.sp, fontFamily = Morningstar.Display,
            fontWeight = FontWeight.Bold,
            color = Morningstar.Gilded,
            letterSpacing = 1.5.sp
        )
        Text(
            text = "Operational status is separate from reference guidance. Guidance entries do not add tools, authorization, or executable capability.",
            fontSize = 11.sp, fontFamily = Morningstar.Body,
            color = Morningstar.Ash
        )
        TextField(
            value = query,
            onValueChange = { query = it.take(100) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search skills", tint = Morningstar.Ash) },
            colors = TextFieldDefaults.textFieldColors(
                containerColor = Morningstar.Glass,
                focusedTextColor = Morningstar.Bone,
                unfocusedTextColor = Morningstar.Bone,
                cursorColor = Morningstar.Gilded,
                focusedIndicatorColor = Morningstar.Blood,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            ),
            placeholder = { Text("Search operational skills and reference guidance…", color = Morningstar.Ash, fontSize = 12.sp, fontFamily = Morningstar.Body) }
        )
        Text(
            text = library?.statusSummary ?: "Reference skill library not loaded",
            fontSize = 10.sp, fontFamily = Morningstar.Body,
            color = if (library?.isAvailable == true) Morningstar.Ash else Morningstar.Warning
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (operationalMatches.isNotEmpty()) {
                item {
                    Text("IMPLEMENTED / OPERATIONAL REGISTRY", color = Morningstar.Gilded, fontSize = 11.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold)
                }
                items(operationalMatches) { skill -> SkillCard(skill = skill) }
            }
            if (referenceMatches.isNotEmpty()) {
                item {
                    Text("REFERENCE LIBRARY · GUIDANCE ONLY", color = Morningstar.Gilded, fontSize = 11.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold)
                }
                items(referenceMatches) { skill -> ReferenceSkillCard(skill = skill, library = library) }
            }
            if (operationalMatches.isEmpty() && referenceMatches.isEmpty()) {
                item {
                    Text("No skills match this search.", color = Morningstar.Ash, modifier = Modifier.padding(vertical = 16.dp))
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
        colors = CardDefaults.cardColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Bone),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Morningstar.Crimson.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(skill.name, color = Morningstar.Bone, fontSize = 14.sp, fontFamily = Morningstar.Display, fontWeight = FontWeight.SemiBold)
            Text("${pack?.title ?: skill.packId} · ${skill.readiness} · NOT RUNNABLE", color = Morningstar.Gilded, fontSize = 9.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(skill.summary, color = Morningstar.Bone, fontSize = 12.sp, fontFamily = Morningstar.Body, lineHeight = 18.sp)
            if (expanded && pack != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Prerequisites", color = Morningstar.Gilded, fontSize = 11.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold)
                Text(pack.prerequisites, color = Morningstar.Bone, fontSize = 11.sp, fontFamily = Morningstar.Body, lineHeight = 17.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Safety boundary", color = Morningstar.Gilded, fontSize = 11.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold)
                Text(pack.safetyBoundary, color = Morningstar.Bone, fontSize = 11.sp, fontFamily = Morningstar.Body, lineHeight = 17.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Verification / evidence", color = Morningstar.Gilded, fontSize = 11.sp, fontFamily = Morningstar.Body, fontWeight = FontWeight.Bold)
                Text(pack.verification, color = Morningstar.Bone, fontSize = 11.sp, fontFamily = Morningstar.Body, lineHeight = 17.sp)
                if (pack.references.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Sources: ${pack.references.joinToString()}", color = Morningstar.Ash, fontSize = 9.sp, fontFamily = Morningstar.Body, lineHeight = 14.sp)
                }
            }
            Text(if (expanded) "Tap to collapse" else "Tap for prerequisites, boundaries and evidence", color = Morningstar.Ash, fontSize = 9.sp, fontFamily = Morningstar.Body, modifier = Modifier.padding(top = 8.dp))
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
            containerColor = Morningstar.Glass,
            contentColor = Morningstar.Bone
        ),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Morningstar.Blood.copy(alpha = 0.2f + toggleAnim * 0.3f))
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
                        tint = Morningstar.Blood.copy(alpha = 0.5f + toggleAnim * 0.5f),
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer { scaleX = 0.8f + toggleAnim * 0.2f; scaleY = 0.8f + toggleAnim * 0.2f }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(text = skill.name, fontSize = 16.sp, fontFamily = Morningstar.Display, fontWeight = FontWeight.SemiBold, color = if (skill.isEnabled) Morningstar.Gilded else Morningstar.Bone)
                        Text(text = skill.category, fontSize = 11.sp, fontFamily = Morningstar.Body, color = Morningstar.Ash)
                    }
                }

                Box(
                    modifier = Modifier
                        .background(if (skill.isEnabled) Morningstar.Ash else Morningstar.GlassRaised, RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = skill.status,
                        color = if (skill.isEnabled) Morningstar.Success else Morningstar.Warning,
                        fontSize = 9.sp, fontFamily = Morningstar.Body,
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
                    Text(text = skill.description, fontSize = 12.sp, fontFamily = Morningstar.Body, color = Morningstar.Bone, lineHeight = 20.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Complexity: ${skill.complexity}/5", fontSize = 10.sp, fontFamily = Morningstar.Body, color = Morningstar.Ash)
                }
            }

            Icon(
                imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = Morningstar.Ash,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable { isExpanded = !isExpanded }
                    .graphicsLayer { rotationZ = if (isExpanded) 180f else 0f }
            )
        }
    }
}
