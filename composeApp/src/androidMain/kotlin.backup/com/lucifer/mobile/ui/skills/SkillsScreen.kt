package com.lucifer.mobile.ui.skills

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

@Composable
fun SkillsScreen(
    onNavigateBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var installedOnly by remember { mutableStateOf(false) }
    
    // Sample skills data (in real app, would come from Hermes skill registry)
    val allSkills = remember { 
        listOf(
            Skill("Capability Evolver", "Evolves new capabilities through genetic algorithms", "AI/ML", true, Horseman.CONQUEST),
            Skill("Council of Minds", "Multi-agent deliberation for complex decisions", "Governance", true, Horseman.CONQUEST),
            Skill("Create Skill", "Generates new skills from requirements", "Meta", true, Horseman.CONQUEST),
            Skill("Creative Storyteller", "Generates narratives and creative content", "Creative", false, Horseman.CONQUEST),
            Skill("Cyber Sec Ops", "Offensive and defensive security operations", "Security", true, Horseman.WAR),
            Skill("Data Scientist", "Statistical analysis and ML pipelines", "Data", true, Horseman.CONQUEST),
            Skill("Deep Research Pro", "Comprehensive research with citations", "Research", true, Horseman.CONQUEST),
            Skill("Financial Analyst", "Market analysis and trading strategies", "Finance", true, Horseman.FAMINE),
            Skill("Frontend Design", "UI/UX design and implementation", "Design", false, Horseman.CONQUEST),
            Skill("Legal Scholar", "Legal research and contract analysis", "Legal", false, Horseman.DEATH),
            Skill("Marketing Director", "Campaign strategy and content generation", "Marketing", false, Horseman.CONQUEST),
            Skill("Master Coder", "Expert-level code generation and review", "Engineering", true, Horseman.CONQUEST),
            Skill("Medical Researcher", "Medical literature analysis", "Medical", false, Horseman.CONQUEST),
            Skill("Ontology Memory", "Knowledge graph construction and query", "Knowledge", true, Horseman.CONQUEST),
            Skill("Polymath", "Cross-domain synthesis and integration", "Meta", true, Horseman.CONQUEST),
            Skill("Self-Improving Agent", "Recursive self-improvement loops", "Meta", true, Horseman.DEATH),
            Skill("Skill Vetter", "Validates and tests skill implementations", "Quality", true, Horseman.DEATH),
            Skill("Summarize Pro", "Intelligent summarization and extraction", "NLP", true, Horseman.FAMINE),
            Skill("System Architect", "Distributed systems design and review", "Architecture", true, Horseman.CONQUEST),
        )
    }
    
    val filteredSkills = allSkills.filter { skill ->
        (selectedCategory == "All" || skill.category == selectedCategory) &&
        (!installedOnly || skill.installed) &&
        (searchQuery.isBlank() || 
         skill.name.lowercase().contains(searchQuery.lowercase()) ||
         skill.description.lowercase().contains(searchQuery.lowercase()))
    }
    
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SkillsHeader(
            onNavigateBack = onNavigateBack,
            searchQuery = searchQuery,
            onSearchChange = { searchQuery = it },
            selectedCategory = selectedCategory,
            onCategoryChange = { selectedCategory = it },
            installedOnly = installedOnly,
            onInstalledToggle = { installedOnly = it }
        )
        
        // Skills list
        Box(modifier = Modifier
            .fillMaxSize()
            .weight(1f)
            .padding(16.dp)
        ) {
            if (filteredSkills.isEmpty()) {
                EmptySkillsState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredSkills) { skill ->
                        SkillCard(
                            skill = skill,
                            onInstallClick = { /* install skill */ },
                            onRunClick = { /* run skill */ },
                            onInfoClick = { /* show details */ }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SkillsHeader(
    onNavigateBack: () -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    installedOnly: Boolean,
    onInstalledToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = LuciferColors.Surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onNavigateBack) {
                        LuciferIcon.ChevronLeft(tint = LuciferColors.Gold)
                    }
                    Text(
                        text = "⚡ Lucifer Skills",
                        style = androidx.compose.material3.Typography.titleMedium,
                        color = LuciferColors.Red,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Category filter
                    DropdownMenu(
                        expanded = false,
                        onDismissRequest = {}
                    ) {
                        listOf("All", "AI/ML", "Governance", "Meta", "Creative", "Security", "Data", "Research", "Finance", "Design", "Legal", "Marketing", "Engineering", "Medical", "Knowledge", "Quality", "NLP", "Architecture")
                            .forEach { cat ->
                                DropdownMenuItem(onClick = { selectedCategory = cat }) {
                                    Text(text = cat, color = LuciferColors.OnSurface)
                                }
                            }
                    }
                }
            }
            
            // Search and filters
            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LuciferTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = "Search skills...",
                    leadingIcon = { LuciferIcon.Search() }
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LuciferButton(
                        onClick = { onInstalledToggle(!installedOnly) },
                        icon = { if (installedOnly) LuciferIcon.CheckCircle() else LuciferIcon.Unlock() },
                        text = if (installedOnly) "Installed Only" else "All Skills",
                        colors = if (installedOnly) 
                            ButtonDefaults.buttonColors(containerColor = LuciferColors.Red)
                            else ButtonDefaults.outlinedButtonColors(
                                contentColor = LuciferColors.Gold,
                                border = androidx.compose.foundation.BorderStroke(1.dp, LuciferColors.Gold)
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun EmptySkillsState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LuciferIcon.Search(
                modifier = Modifier.size(64.dp),
                tint = LuciferColors.Gold.copy(alpha = 0.5f)
            )
            Text(
                text = "No Skills Found",
                style = androidx.compose.material3.Typography.headlineMedium,
                color = LuciferColors.OnSurface
            )
            Text(
                text = "Try adjusting your search or filters",
                style = androidx.compose.material3.Typography.bodyLarge,
                color = LuciferColors.OnSurfaceVariant
            )
        }
    )
}

@Composable
fun SkillCard(
    skill: Skill,
    onInstallClick: () -> Unit,
    onRunClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    val horsemanColor = when (skill.horseman) {
        Horseman.CONQUEST -> LuciferColors.Red
        Horseman.WAR -> LuciferColors.Gold
        Horseman.FAMINE -> LuciferColors.GoldLight
        Horseman.DEATH -> LuciferColors.Error
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { /* onInfoClick() */ },
        colors = CardDefaults.cardColors(
            containerColor = LuciferColors.SurfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Horseman badge
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                color = horsemanColor,
                                shape = androidx.compose.material3.Shapes.small
                            )
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (skill.horseman) {
                                Horseman.CONQUEST -> "C"
                                Horseman.WAR -> "W"
                                Horseman.FAMINE -> "F"
                                Horseman.DEATH -> "D"
                            },
                            style = androidx.compose.material3.Typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column {
                        Text(
                            text = skill.name,
                            style = androidx.compose.material3.Typography.titleMedium,
                            color = LuciferColors.OnSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = skill.category,
                            style = androidx.compose.material3.Typography.labelSmall,
                            color = LuciferColors.OnSurfaceVariant
                        )
                    }
                }
                
                if (skill.installed) {
                    LuciferIcon.CheckCircle(
                        modifier = Modifier.size(20.dp),
                        tint = LuciferColors.Gold
                    )
                }
            }
            
            Text(
                text = skill.description,
                style = androidx.compose.material3.Typography.bodyMedium,
                color = LuciferColors.OnSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            // Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!skill.installed) {
                    LuciferButton(
                        onClick = { /* install */ },
                        icon = { LuciferIcon.Download() },
                        text = "Install",
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LuciferColors.Red,
                            contentColor = Color.White
                        )
                    )
                } else {
                    LuciferButton(
                        onClick = { /* run */ },
                        icon = { LuciferIcon.Terminal() },
                        text = "Run",
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LuciferColors.Red,
                            contentColor = Color.White
                        )
                    )
                    LuciferButton(
                        onClick = { /* info */ },
                        icon = { LuciferIcon.Info() },
                        text = "Details",
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = LuciferColors.Gold,
                            border = androidx.compose.foundation.BorderStroke(1.dp, LuciferColors.Gold)
                        )
                    )
                }
            }
        }
    )
}

data class Skill(
    val name: String,
    val description: String,
    val category: String,
    val installed: Boolean,
    val horseman: Horseman
)

enum class Horseman {
    CONQUEST,
    WAR,
    FAMINE,
    DEATH
}

@Composable
fun EmptySkillsState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LuciferIcon.Skills(
                modifier = Modifier.size(64.dp),
                tint = LuciferColors.Gold.copy(alpha = 0.5f)
            )
            Text(
                text = "No Skills Match",
                style = androidx.compose.material3.Typography.headlineMedium,
                color = LuciferColors.OnSurface
            )
            Text(
                text = "Try adjusting your search or filters",
                style = androidx.compose.material3.Typography.bodyLarge,
                color = LuciferColors.OnSurfaceVariant
            )
        }
    }
}