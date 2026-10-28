package com.lucifer.mobile.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class TabItem(
    val label: String,
    val icon: ImageVector
)

@Composable
fun AnimatedTabBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<TabItem> = listOf(
        TabItem("Terminal", Icons.Filled.Terminal),
        TabItem("Memory", Icons.Filled.Memory),
        TabItem("Skills", Icons.Filled.Psychology),
        TabItem("Settings", Icons.Filled.Settings)
    )
) {
    // Nine destinations no longer fit as equal slabs on a phone, so scroll once the bar is full.
    val scrollable = tabs.size > 6
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0xFF0D050D))
            .then(if (scrollable) Modifier.horizontalScroll(scrollState) else Modifier),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selectedTab
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1.12f else 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            Box(
                modifier = Modifier
                    .then(if (scrollable) Modifier.width(70.dp).fillMaxHeight() else Modifier.weight(1f).fillMaxSize())
                    .clickable { onTabSelected(index) }
                    .padding(horizontal = if (scrollable) 2.dp else 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (isSelected) Color(0xFFFFD700) else Color(0xFF8B7D8B),
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                    )
                    Text(
                        text = tab.label,
                        fontSize = 10.sp,
                        color = if (isSelected) Color(0xFFFFD700) else Color(0xFF8B7D8B),
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .height(4.dp)
                                .background(Color(0xFFFFD700))
                        )
                    }
                }
            }
        }
    }
}
