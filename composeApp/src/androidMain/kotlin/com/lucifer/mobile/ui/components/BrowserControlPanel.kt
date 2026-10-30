package com.lucifer.mobile.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lucifer.mobile.browser.BrowserAction
import com.lucifer.mobile.browser.BrowserPolicy
import com.lucifer.mobile.browser.InteractiveBrowser
import com.lucifer.mobile.data.browser.LuciferBrowser
import com.lucifer.mobile.privacy.TorRuntime
import com.lucifer.mobile.ui.theme.Morningstar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Two browsers, deliberately separated.
 *
 * **READ-ONLY** is `LuciferBrowser`: a text fetcher that goes through the egress gate and can ride the
 * Tor tunnel. It cannot click, sign in, or submit anything.
 *
 * **INTERACTIVE** is a WebView: JavaScript, cookies, sign-in, clicks. It is the honest way to give a
 * real browser, and it comes with three costs this panel states rather than hides — its traffic is not
 * in the ledger, it cannot use Lucifer's tunnel, and its actions can submit things to the world. So it
 * is refused under Tor without a system VPN, and every mutating action waits for a confirmation that
 * names exactly what is about to happen.
 */
@Composable
fun BrowserControlPanel(
    context: Context,
    modifier: Modifier = Modifier
) {
    val browser = remember(context) { LuciferBrowser(context.applicationContext) }
    val interactive = remember(context) { InteractiveBrowser(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var interactiveOn by remember { mutableStateOf(false) }
    var availability by remember { mutableStateOf(BrowserPolicy.availability("DIRECT", false)) }
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var pageText by remember { mutableStateOf("") }
    var statusLine by remember { mutableStateOf("Enter a public HTTPS URL to fetch readable page text.") }
    var loading by remember { mutableStateOf(false) }
    var selector by remember { mutableStateOf("") }
    var typedText by remember { mutableStateOf("") }
    var scriptText by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<BrowserAction?>(null) }
    var snapshot by remember { mutableStateOf(InteractiveBrowser.Snapshot()) }
    var cookieLine by remember { mutableStateOf("") }

    // The transport can change in Settings while this panel is alive; re-read it whenever the mode does.
    LaunchedEffect(interactiveOn) {
        availability = withContext(Dispatchers.IO) {
            BrowserPolicy.availability(
                TorRuntime.transport().label,
                TorRuntime.vpnPresent(context.applicationContext)
            )
        }
        if (interactiveOn && availability.refused) {
            interactiveOn = false
            statusLine = availability.reason
        }
    }

    // Nothing survives the panel: the WebView is destroyed when it leaves the screen.
    DisposableEffect(Unit) { onDispose { interactive.destroy() } }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (interactiveOn) "INTERACTIVE WEB" else "READ-ONLY WEB", color = Morningstar.Gilded)
            Text(
                text = if (interactiveOn) "READ-ONLY" else "INTERACTIVE",
                color = Morningstar.Ash,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Morningstar.GlassRaised, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clickable(interactionSource = MutableInteractionSource(), indication = null) {
                        if (!interactiveOn && availability.refused) {
                            statusLine = availability.reason
                        } else {
                            interactiveOn = !interactiveOn
                            pending = null
                        }
                    }
            )
        }

        if (interactiveOn) {
            InteractiveBrowserPane(
                interactive = interactive,
                url = url,
                onUrlChange = { url = it },
                selector = selector,
                onSelectorChange = { selector = it },
                typedText = typedText,
                onTypedTextChange = { typedText = it },
                scriptText = scriptText,
                onScriptTextChange = { scriptText = it },
                pending = pending,
                onPendingChange = { pending = it },
                snapshot = snapshot,
                onSnapshot = { snapshot = it },
                cookieLine = cookieLine,
                onCookieLine = { cookieLine = it },
                pageText = pageText,
                onPageText = { pageText = it },
                statusLine = statusLine,
                onStatusLine = { statusLine = it },
                scope = scope
            )
        } else {
            Text(
                "Lucifer can retrieve public pages and extract text. No JavaScript, clicks, form " +
                    "submissions, sign-ins or purchases. This mode goes through the egress gate and rides " +
                    "the Tor tunnel when the transport is TOR.",
                color = Morningstar.Ash, fontSize = 11.sp
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
                    val requested = url.trim().let {
                        if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
                    }
                    loading = true
                    statusLine = "Fetching page…"
                    pageText = ""
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { browser.navigate(requested) }
                        loading = false
                        if (result.success) {
                            url = result.url
                            title = result.title
                            pageText = result.text
                            statusLine = "Fetched ${result.url}"
                        } else {
                            title = ""
                            statusLine = result.error.ifBlank { "Could not fetch this page." }
                        }
                    }
                },
                enabled = !loading && url.isNotBlank()
            ) { Text(if (loading) "Loading…" else "Open page") }
            Text(statusLine, color = Morningstar.Ash, fontSize = 11.sp)
            if (title.isNotBlank()) Text(title, color = Morningstar.Gilded)
            if (pageText.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Morningstar.Glass)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        item { Text(pageText, color = Morningstar.Bone, style = Morningstar.body(12)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.InteractiveBrowserPane(
    interactive: InteractiveBrowser,
    url: String,
    onUrlChange: (String) -> Unit,
    selector: String,
    onSelectorChange: (String) -> Unit,
    typedText: String,
    onTypedTextChange: (String) -> Unit,
    scriptText: String,
    onScriptTextChange: (String) -> Unit,
    pending: BrowserAction?,
    onPendingChange: (BrowserAction?) -> Unit,
    snapshot: InteractiveBrowser.Snapshot,
    onSnapshot: (InteractiveBrowser.Snapshot) -> Unit,
    cookieLine: String,
    onCookieLine: (String) -> Unit,
    pageText: String,
    onPageText: (String) -> Unit,
    statusLine: String,
    onStatusLine: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    Text(
        "JavaScript, cookies and sign-in, in a real WebView. It opens its own sockets: it is NOT in the " +
            "egress ledger and cannot use Lucifer's Tor tunnel. Clicks, typed text and scripts always " +
            "wait for your confirmation, because they can post, message, buy or delete.",
        color = Morningstar.Warning, fontSize = 11.sp
    )
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("https://example.com") }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                val target = url.trim()
                val decision = BrowserPolicy.decide(BrowserAction.NAVIGATE, target, available = true)
                if (decision.refused) {
                    onStatusLine(decision.reason)
                } else if (interactive.navigate(target, available = true)) {
                    onStatusLine("Loading $target")
                } else {
                    onStatusLine("The browser refused that address.")
                }
            },
            enabled = url.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Morningstar.Blood, contentColor = Morningstar.Bone)
        ) { Text("GO", fontSize = 11.sp) }
        Button(
            onClick = { if (!interactive.goBack()) onStatusLine("No page to go back to.") },
            colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Bone)
        ) { Text("BACK", fontSize = 11.sp) }
        Button(
            onClick = { interactive.reload() },
            colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Bone)
        ) { Text("RELOAD", fontSize = 11.sp) }
        Button(
            onClick = {
                interactive.clearCookies()
                onCookieLine(interactive.cookieSummary())
                onStatusLine("Cookies, cache and form data cleared.")
            },
            colors = ButtonDefaults.buttonColors(containerColor = Morningstar.CrimsonDim, contentColor = Color.White)
        ) { Text("CLEAR", fontSize = 11.sp) }
    }

    Card(
        modifier = Modifier.fillMaxWidth().height(260.dp),
        colors = CardDefaults.cardColors(containerColor = Morningstar.Glass)
    ) {
        AndroidView(
            // Built with the application context: a WebView must never hold this Activity.
            factory = { _ ->
                interactive.attach().also { view ->
                    interactive.installClient(
                        onState = {
                            onSnapshot(it)
                            if (it.url.isNotBlank()) onUrlChange(it.url)
                            onCookieLine(interactive.cookieSummary())
                        },
                        onBlocked = { onStatusLine(it) }
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    Text(
        if (snapshot.title.isBlank()) statusLine else snapshot.title,
        color = Morningstar.Ash, fontSize = 10.sp
    )
    Text(cookieLine.ifBlank { "no cookies for this site" }, color = Morningstar.Ash, fontSize = 10.sp)

    OutlinedTextField(
        value = selector,
        onValueChange = onSelectorChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("CSS selector · #submit, .btn.primary") }
    )
    OutlinedTextField(
        value = typedText,
        onValueChange = onTypedTextChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("text to type into that selector") }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onPendingChange(BrowserAction.CLICK) },
            colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Bone)
        ) { Text("CLICK", fontSize = 11.sp) }
        Button(
            onClick = { onPendingChange(BrowserAction.TYPE) },
            colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Bone)
        ) { Text("TYPE", fontSize = 11.sp) }
        Button(
            onClick = {
                scope.launch {
                    val text = interactive.pageText()
                    onPageText(text)
                    onStatusLine(
                        if (text.isBlank()) "The page has no visible text yet."
                        else "Read ${text.length} characters from the page."
                    )
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Bone)
        ) { Text("READ", fontSize = 11.sp) }
    }

    // The escape hatch, kept behind the same sheet. A script can do anything the page can do, which
    // is why it is not a text field that runs on Enter.
    OutlinedTextField(
        value = scriptText,
        onValueChange = onScriptTextChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("script · runs in the page, ${BrowserPolicy.MAX_SCRIPT_CHARS} characters max") }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { onPendingChange(BrowserAction.EVALUATE) },
            enabled = scriptText.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Morningstar.GlassRaised, contentColor = Morningstar.Bone)
        ) { Text("RUN SCRIPT", fontSize = 11.sp) }
        Text(
            "${scriptText.length} / ${BrowserPolicy.MAX_SCRIPT_CHARS}",
            color = Morningstar.Ash, fontSize = 10.sp
        )
    }

    val action = pending
    if (action != null) {
        val payload = when (action) {
            BrowserAction.CLICK -> selector
            BrowserAction.TYPE -> typedText
            BrowserAction.EVALUATE -> scriptText
            else -> ""
        }
        val decision = BrowserPolicy.decide(action, payload)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Morningstar.GlassRaised)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CONFIRM ACTION", color = Morningstar.Crimson, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(BrowserPolicy.describe(action, payload), color = Morningstar.Bone, fontSize = 11.sp)
                Text(
                    decision.reason.ifBlank {
                        when {
                            action == BrowserAction.EVALUATE -> "This script runs with the page's own permissions."
                            !BrowserPolicy.isValidSelector(selector) -> "That is not a selector this browser will run."
                            else -> "This will be sent to the page."
                        }
                    },
                    color = Morningstar.Warning, fontSize = 10.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onPendingChange(null)
                            scope.launch {
                                val check = BrowserPolicy.decide(action, payload)
                                if (check.refused) {
                                    onStatusLine(check.reason)
                                    return@launch
                                }
                                if (action == BrowserAction.CLICK && !BrowserPolicy.isValidSelector(selector)) {
                                    onStatusLine("That is not a selector this browser will run.")
                                    return@launch
                                }
                                val outcome = when (action) {
                                    BrowserAction.CLICK -> interactive.click(selector)
                                    BrowserAction.TYPE -> interactive.type(selector, typedText)
                                    BrowserAction.EVALUATE -> interactive.evaluate(scriptText)
                                    else -> ""
                                }
                                onStatusLine(
                                    when (outcome.trim('"')) {
                                        "NO_MATCH" -> "No element matched that selector."
                                        "CLICKED" -> "Clicked. Whatever the page does next is the page's choice."
                                        "TYPED" -> "Typed into the field. Nothing was submitted."
                                        else -> "The page answered: $outcome"
                                    }
                                )
                                onCookieLine(interactive.cookieSummary())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Morningstar.Crimson, contentColor = Morningstar.Bone)
                    ) { Text("DO IT", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = { onPendingChange(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = Morningstar.Glass, contentColor = Morningstar.Ash)
                    ) { Text("CANCEL", fontSize = 11.sp) }
                }
            }
        }
    }

    if (pageText.isNotBlank()) {
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = Morningstar.Glass)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                item { Text(pageText, color = Morningstar.Bone, style = Morningstar.body(12)) }
            }
        }
    }
}
