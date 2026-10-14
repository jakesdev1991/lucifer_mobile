package com.lucifer.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.ui.theme.LuciferColors

@Composable
fun LuciferButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable (Modifier, Color) -> Unit)? = null,
    text: String = "",
    colors: ButtonDefaults.ButtonColors = ButtonDefaults.buttonColors(
        containerColor = LuciferColors.Red,
        contentColor = Color.White,
        disabledContainerColor = LuciferColors.SurfaceVariant,
        disabledContentColor = LuciferColors.OnSurfaceVariant
    ),
    shape = androidx.compose.material3.Shapes.medium
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        enabled = enabled,
        colors = colors,
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = if (icon != null) Arrangement.SpaceBetween else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    style = androidx.compose.material3.Typography.labelLarge,
                    color = colors.contentColor(enabled),
                    fontWeight = FontWeight.Medium
                )
            }
            if (icon != null) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    icon(Modifier, colors.contentColor(enabled))
                }
            }
        }
    )
}

@Composable
fun LuciferOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable (Modifier, Color) -> Unit)? = null,
    text: String = "",
    colors: ButtonDefaults.ButtonColors = ButtonDefaults.outlinedButtonColors(
        containerColor = Color.Transparent,
        contentColor = LuciferColors.Red,
        disabledContentColor = LuciferColors.OnSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, LuciferColors.Red)
    ),
    shape = androidx.compose.material3.Shapes.medium
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        enabled = enabled,
        colors = colors,
        shape = shape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = if (icon != null) Arrangement.SpaceBetween else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    style = androidx.compose.material3.Typography.labelLarge,
                    color = colors.contentColor(enabled),
                    fontWeight = FontWeight.Medium
                )
            }
            if (icon != null) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    icon(Modifier, colors.contentColor(enabled))
                }
            }
        }
    )
}

@Composable
fun LuciferTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: String = "",
    colors: ButtonDefaults.ButtonColors = ButtonDefaults.textButtonColors(
        contentColor = LuciferColors.Red,
        disabledContentColor = LuciferColors.OnSurfaceVariant
    )
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        enabled = enabled,
        colors = colors
    ) {
        Text(
            text = text,
            style = androidx.compose.material3.Typography.labelLarge,
            color = colors.contentColor(enabled),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun LuciferFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (Modifier, Color) -> Unit)? = null,
    colors: androidx.compose.material3.FloatingActionButtonDefaults.FloatingActionButtonColors = 
        androidx.compose.material3.FloatingActionButtonDefaults.floatingActionButtonColors(
            containerColor = LuciferColors.Red,
            contentColor = Color.White
        )
) {
    androidx.compose.material3.FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = colors.containerColor,
        contentColor = colors.contentColor
    ) {
        icon?.invoke(Modifier.size(24.dp), colors.contentColor)
    }
}