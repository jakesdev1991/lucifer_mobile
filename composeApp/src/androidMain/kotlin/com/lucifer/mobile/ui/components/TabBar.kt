package com.lucifer.mobile.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lucifer.mobile.ui.effects.rememberBreathingAmount
import com.lucifer.mobile.ui.theme.Morningstar

data class TabItem(
    val label: String,
    val icon: ImageVector
)

/**
 * The navigation bar as a strip of smoked glass.
 *
 * Ten destinations do not fit as equal slabs, so the bar scrolls once it is full. The selected
 * destination gets a crimson needle above the icon and a Cinzel label in bone; everything else
 * recedes to ash. No coloured pills, no bouncing — a slow settle instead.
 */
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
    val scrollable = tabs.size > 6
    val scrollState = rememberScrollState()
    val breath = rememberBreathingAmount(min = 0.55f, max = 1f, periodMillis = 3_400)

    Column(modifier = modifier.fillMaxWidth()) {
        // Hairline ceiling: the bar is a shelf, not a bubble.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.15f to Morningstar.Edge,
                        0.6f to Morningstar.EdgeQuiet,
                        1f to Color.Transparent
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Morningstar.Obsidian.copy(alpha = 0.92f),
                        1f to Morningstar.Void
                    )
                )
                .then(if (scrollable) Modifier.horizontalScroll(scrollState) else Modifier),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = index == selectedTab
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1f else if (pressed) 0.94f else 0.97f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "tab-scale"
                )
                val iconAlpha by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0.55f,
                    animationSpec = spring(stiffness = Spring.StiffnessLow),
                    label = "tab-alpha"
                )
                Box(
                    modifier = Modifier
                        .then(if (scrollable) Modifier.width(72.dp).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight())
                        .clickable(interactionSource = interaction, indication = null) { onTabSelected(index) }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        // The needle: a single crimson thread that marks the living destination.
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(9.dp)
                                .background(
                                    if (isSelected) {
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.4f to Morningstar.Crimson,
                                            1f to Morningstar.Ember.copy(alpha = 0.6f)
                                        )
                                    } else {
                                        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                                    }
                                )
                        )
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = if (isSelected) {
                                Morningstar.Bone
                            } else {
                                Morningstar.Ash.copy(alpha = iconAlpha)
                            },
                            modifier = Modifier
                                .size(19.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                        )
                        Text(
                            text = tab.label.uppercase(),
                            style = Morningstar.overline(8).copy(
                                color = if (isSelected) Morningstar.Bone else Morningstar.Smoke
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Box(
                            modifier = Modifier
                                .width(if (isSelected) 14.dp else 0.dp)
                                .height(1.dp)
                                .background(Morningstar.Crimson.copy(alpha = 0.9f * breath))
                        )
                    }
                }
            }
        }
    }
}
