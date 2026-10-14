package com.lucifer.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.ui.theme.LuciferColors
import com.lucifer.mobile.ui.theme.Typography

@Composable
fun LuciferTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String = "",
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String = "",
    singleLine: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = Typography.bodyMedium
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = if (label.isNotEmpty()) { { Text(text = label) } } else null,
        placeholder = { Text(text = placeholder, color = LuciferColors.OnSurfaceVariant.copy(alpha = 0.6f)) },
        leadingIcon = leadingIcon?.let { { it() } },
        trailingIcon = trailingIcon?.let { { it() } },
        isError = isError,
        singleLine = singleLine,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle
    )
}

@Composable
fun LuciferPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Password",
    placeholder: String = "Enter password",
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = if (label.isNotEmpty()) { { Text(text = label) } } else null,
        placeholder = { Text(text = placeholder, color = LuciferColors.OnSurfaceVariant.copy(alpha = 0.6f)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        textStyle = Typography.bodyMedium
    )
}

@Composable
fun LuciferSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search...",
    onClear: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(text = placeholder, color = LuciferColors.OnSurfaceVariant.copy(alpha = 0.6f)) },
        leadingIcon = { Text(text = "🔍", color = LuciferColors.OnSurfaceVariant, fontSize = 24.sp) },
        trailingIcon = if (value.isNotEmpty() && onClear != null) {
            { 
                androidx.compose.material3.IconButton(onClick = { onClear?.invoke() }, modifier = Modifier.size(24.dp)) {
                    Text(text = "✕", color = LuciferColors.OnSurfaceVariant, fontSize = 24.sp)
                }
            }
        } else {
            { Text(text = "🔍", color = LuciferColors.OnSurfaceVariant, fontSize = 24.sp) }
        },
        singleLine = true,
        enabled = true
    )
}

@Composable
fun LuciferMultilineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String = "",
    minLines: Int = 4,
    maxLines: Int = 10,
    enabled: Boolean = true
) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        label = if (label.isNotEmpty()) { { Text(text = label) } } else null,
        placeholder = { Text(text = placeholder, color = LuciferColors.OnSurfaceVariant.copy(alpha = 0.6f)) },
        singleLine = false,
        maxLines = maxLines,
        minLines = minLines,
        textStyle = Typography.bodyMedium,
        colors = androidx.compose.material3.TextFieldDefaults.textFieldColors(
            containerColor = LuciferColors.Surface,
            cursorColor = LuciferColors.Red
        ),
        enabled = enabled
    )
}