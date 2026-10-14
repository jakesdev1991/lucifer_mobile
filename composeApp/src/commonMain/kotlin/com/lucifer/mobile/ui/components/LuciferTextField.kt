package com.lucifer.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucifer.mobile.ui.theme.LuciferColors

@Composable
fun LuciferTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String = "",
    leadingIcon: (@Composable (Modifier, Color) -> Unit)? = null,
    trailingIcon: (@Composable (Modifier, Color) -> Unit)? = null,
    isError: Boolean = false,
    errorMessage: String = "",
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = androidx.compose.material3.Typography.bodyMedium,
    colors: TextFieldDefaults.TextFieldColors = TextFieldDefaults.outlinedTextFieldColors(
        containerColor = com.lucifer.mobile.ui.theme.LuciferColors.Surface,
        focusedContainerColor = com.lucifer.mobile.ui.theme.LuciferColors.Surface,
        disabledContainerColor = com.lucifer.mobile.ui.theme.LuciferColors.Surface,
        unfocusedContainerColor = com.lucifer.mobile.ui.theme.LuciferColors.Surface,
        textColor = com.lucifer.mobile.ui.theme.LuciferColors.OnSurface,
        disabledTextColor = com.lucifer.mobile.ui.theme.LuciferColors.OnSurfaceVariant,
        placeholderTextColor = com.lucifer.mobile.ui.theme.LuciferColors.OnSurfaceVariant.copy(alpha = 0.6f),
        labelTextColor = com.lucifer.mobile.ui.theme.LuciferColors.OnSurfaceVariant,
        focusedLabelTextColor = com.lucifer.mobile.ui.theme.LuciferColors.Red,
        errorTextColor = com.lucifer.mobile.ui.theme.LuciferColors.Error,
        focusedIndicatorColor = com.lucifer.mobile.ui.theme.LuciferColors.Red,
        unfocusedIndicatorColor = com.lucifer.mobile.ui.theme.LuciferColors.Outline,
        disabledIndicatorColor = com.lucifer.mobile.ui.theme.LuciferColors.Outline,
        disabledLabelTextColor = com.lucifer.mobile.ui.theme.LuciferColors.OnSurfaceVariant,
        trailingIconColor = com.lucifer.mobile.ui.theme.LuciferColors.OnSurfaceVariant,
        focusedTrailingIconColor = com.lucifer.mobile.ui.theme.LuciferColors.Red,
        leadingIconColor = com.lucifer.mobile.ui.theme.LuciferColors.OnSurfaceVariant,
        focusedLeadingIconColor = com.lucifer.mobile.ui.theme.LuciferColors.Red,
        cursorColor = com.lucifer.mobile.ui.theme.LuciferColors.Red,
        selectionHandleColor = com.lucifer.mobile.ui.theme.LuciferColors.Red,
        selectionHandleBorderColor = com.lucifer.mobile.ui.theme.LuciferColors.Red
    )
) {
    var text by remember { mutableStateOf(value) }
    
    OutlinedTextField(
        value = text,
        onValueChange = { 
            text = it
            onValueChange(it)
        },
        modifier = modifier
            .fillMaxWidth(),
        label = if (label.isNotEmpty()) { { Text(text = label) } } else null,
        placeholder = { Text(text = placeholder, color = com.lucifer.mobile.ui.theme.LuciferColors.OnSurfaceVariant.copy(alpha = 0.6f)) },
        leadingIcon = leadingIcon?.let { { it(Modifier.size(24.dp), com.lucifer.mobile.ui.theme.LuciferColors.OnSurfaceVariant) } },
        trailingIcon = trailingIcon?.let { { it(Modifier.size(24.dp), com.lucifer.mobile.ui.theme.LuciferColors.OnSurfaceVariant) } },
        isError = isError,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardType = keyboardType,
        visualTransformation = visualTransformation,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        colors = colors
    )
}

@Composable
fun LuciferPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Password",
    placeholder: String = "Enter password",
    enabled: Boolean = true,
    onToggleVisibility: (() -> Unit)? = null
) {
    var visualTransformation by remember { mutableStateOf(androidx.compose.ui.text.input.PasswordVisualTransformation()) }
    var trailingIcon by remember { mutableStateOf<(@Composable (Modifier, Color) -> Unit)?>(LuciferIcon.VisibilityOff()) }
    
    if (onToggleVisibility != null) {
        trailingIcon = remember {
            { modifier, color ->
                IconButton(
                    onClick = {
                        visualTransformation = if (visualTransformation is androidx.compose.ui.text.input.PasswordVisualTransformation) 
                            VisualTransformation.None 
                            else androidx.compose.ui.text.input.PasswordVisualTransformation()
                        trailingIcon = if (visualTransformation is androidx.compose.ui.text.input.PasswordVisualTransformation)
                            LuciferIcon.VisibilityOff() 
                            else LuciferIcon.Visibility()
                        onToggleVisibility?.invoke()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    trailingIcon?.invoke(Modifier.size(24.dp), com.lucifer.mobile.ui.theme.LuciferColors.OnSurfaceVariant)
                }
            }
        }
    }
    
    LuciferTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        trailingIcon = trailingIcon,
        singleLine = true,
        visualTransformation = visualTransformation,
        enabled = enabled,
        keyboardType = KeyboardType.Password
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
    val trailingIcon = if (value.isNotEmpty() && onClear != null) {
        LuciferIcon.Delete()
    } else {
        LuciferIcon.Search()
    }
    
    LuciferTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        leadingIcon = { LuciferIcon.Search() },
        trailingIcon = if (value.isNotEmpty()) {
            { trailingIcon = { LuciferIcon.Delete(
                onClick = { onClear?.invoke() }
            ) } }
        } else {
            { LuciferIcon.Search() }
        },
        singleLine = true,
        keyboardType = KeyboardType.Text
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
    var text by remember { mutableStateOf(value) }
    
    androidx.compose.material3.TextField(
        value = text,
        onValueChange = { text = it; onValueChange(it) },
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(16.dp),
        label = if (label.isNotEmpty()) { { Text(text = label) } } else null,
        placeholder = { Text(text = placeholder, color = LuciferColors.OnSurfaceVariant.copy(alpha = 0.6f)) },
        singleLine = false,
        maxLines = maxLines,
        minLines = minLines,
        textStyle = androidx.compose.material3.Typography.bodyMedium,
        colors = androidx.compose.material3.TextFieldDefaults.textFieldColors(
            containerColor = LuciferColors.Surface,
            textColor = LuciferColors.OnSurface,
            cursorColor = LuciferColors.Red,
            selectionHandleColor = LuciferColors.Red
        ),
        enabled = enabled
    )
}