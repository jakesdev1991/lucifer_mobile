package com.lucifer.mobile.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lucifer.mobile.data.browser.LuciferBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read-only public-web reader. It does not execute JavaScript or submit forms. */
@Composable
fun BrowserControlPanel(
    context: Context,
    modifier: Modifier = Modifier
) {
    val browser = remember(context) { LuciferBrowser(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var pageText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Enter a public HTTPS URL to fetch readable page text.") }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("READ-ONLY WEB", color = Color(0xFFFFD700))
        Text(
            "Lucifer can retrieve public pages and extract text. No JavaScript, clicks, form submissions, sign-ins, or purchases.",
            color = Color(0xFFB8AEB8)
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("https://example.com") },
            enabled = !loading
        )
        Button(
            onClick = {
                val requested = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
                loading = true
                message = "Fetching page…"
                pageText = ""
                scope.launch {
                    val result = withContext(Dispatchers.IO) { browser.navigate(requested) }
                    loading = false
                    if (result.success) {
                        url = result.url
                        title = result.title
                        pageText = result.text
                        message = "Fetched ${result.url}"
                    } else {
                        title = ""
                        message = result.error.ifBlank { "Could not fetch this page." }
                    }
                }
            },
            enabled = !loading && url.isNotBlank()
        ) { Text(if (loading) "Loading…" else "Open page") }
        Text(message, color = Color(0xFF8B7D8B))
        if (title.isNotBlank()) Text(title, color = Color(0xFFFFD700))
        if (pageText.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF160A16))
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    item {
                        Text(
                            pageText,
                            color = Color(0xFFE0E0E0),
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                }
            }
        }
    }
}
