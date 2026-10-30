package com.lucifer.mobile.cognition.harness

import com.lucifer.mobile.cognition.AndroidCognitionTools
import com.lucifer.mobile.cognition.Calculator
import com.lucifer.mobile.cognition.ToolCall
import com.lucifer.mobile.cognition.ToolCatalogue
import com.lucifer.mobile.data.browser.BrowserFixture
import com.lucifer.mobile.data.browser.BrowserResult
import com.lucifer.mobile.memory.MemoryFixture
import com.lucifer.mobile.omni_bridge.knowledge.CorpusFixture
import com.lucifer.mobile.surface.SurfaceFixture
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The tools, executed.
 *
 * The runner harness proves what the loop does with a toolbox. This proves the toolbox the app actually
 * ships: `AndroidCognitionTools.toolbox(context)` is constructed for real and its `run` is called with
 * arguments a model could send. The six collaborating services behind it are fixtures, so what is
 * asserted is the tool layer's own judgement — which argument is a URL, which question is too short to
 * search, what "the fetch failed" says to the loop, and that no tool invents a success.
 */

private var failures = 0
private var checks = 0

private fun test(name: String, body: () -> Unit) {
    checks++
    try {
        body()
        println("  ok    $name")
    } catch (error: Throwable) {
        failures++
        println("  FAIL  $name")
        println("        ${error.message ?: error.javaClass.simpleName}")
    }
}

private fun expect(condition: Boolean, what: String) {
    if (!condition) throw AssertionError(what)
}

private fun expectEq(actual: Any?, expected: Any?, what: String) {
    if (actual != expected) throw AssertionError("$what: expected <$expected> got <$actual>")
}

private fun expectContains(text: String, fragment: String, what: String) {
    if (!text.contains(fragment, ignoreCase = true)) {
        throw AssertionError("$what: <$fragment> not found in <${text.take(300)}>")
    }
}

private class TempContext(private val dir: File) : android.content.Context() {
    override val applicationContext: android.content.Context get() = this
    override val filesDir: File get() = dir
}

private fun context(): android.content.Context {
    val dir = File.createTempFile("lucifer-tools", "").let { file ->
        file.delete()
        file.mkdirs()
        file
    }
    dir.deleteOnExit()
    return TempContext(dir)
}

private fun reset() {
    BrowserFixture.reset()
    CorpusFixture.reset()
    MemoryFixture.reset()
    SurfaceFixture.reset()
}

fun main() {
    println("Lucifer cognition tools — what a model can actually run")

    val toolbox = AndroidCognitionTools.toolbox(context())

    fun run(id: String, argument: String) = runBlocking { toolbox.run(ToolCall(id, argument)) }

    test("the toolbox offers exactly the catalogue, and nothing else") {
        expectEq(toolbox.specs().map { it.id }, ToolCatalogue.specs().map { it.id }, "specs")
        reset()
        val unknown = run("rm_rf", "/")
        expectEq(unknown.ok, false, "an unknown tool is refused")
        expectContains(unknown.detail, "no tool named", "and says so")
    }

    test("arithmetic is computed, not guessed") {
        reset()
        val result = run(ToolCatalogue.CALCULATE, "17 * 23 + 4")
        expectEq(result.ok, true, "it ran")
        expectEq(result.output, "395", "exactly")
        expectEq(run(ToolCatalogue.CALCULATE, "nonsense(").ok, false, "and a bad expression fails instead of throwing")
    }

    test("a page read refuses anything that is not http(s)") {
        reset()
        for (argument in listOf("file:///etc/passwd", "javascript:alert(1)", "example.com", " content://x")) {
            val result = run(ToolCatalogue.PAGE, argument)
            expectEq(result.ok, false, "refused: $argument")
            expectContains(result.detail, "http(s)", "with a reason: $argument")
        }
        expectEq(BrowserFixture.calls.size, 0, "the browser was never asked")
    }

    test("a failed fetch is reported as the browser reported it") {
        reset()
        BrowserFixture.result = BrowserResult(success = false, error = "the host did not answer")
        val result = run(ToolCatalogue.PAGE, "https://example.com/x")
        expectEq(result.ok, false, "not a success")
        expectContains(result.detail, "did not answer", "the real reason survives")
        expectEq(BrowserFixture.calls, listOf("https://example.com/x"), "exactly one request")
    }

    test("a successful fetch returns the page, bounded") {
        reset()
        BrowserFixture.result = BrowserResult(
            success = true,
            url = "https://example.com/x",
            title = "Example",
            text = "t".repeat(9_000)
        )
        val result = run(ToolCatalogue.PAGE, "https://example.com/x")
        expectEq(result.ok, true, "it ran")
        expectContains(result.output, "Example", "the title is kept")
        expect(result.output.length <= 4_000, "the body is bounded: ${result.output.length}")
    }

    test("a research question needs more than a word") {
        reset()
        val short = run(ToolCatalogue.RESEARCH, "why")
        expectEq(short.ok, false, "refused")
        expectContains(short.detail, "a few words", "with a reason")
        expectEq(BrowserFixture.calls.size, 0, "nothing was fetched")
    }

    test("research with no sources says so instead of answering from nothing") {
        reset()
        val result = run(ToolCatalogue.RESEARCH, "how does a tor circuit get built")
        expectEq(result.ok, false, "it failed honestly")
        expect(result.detail.isNotBlank(), "with a reason: ${result.detail}")
    }

    test("corpus search reports an empty match, and the passages when there are some") {
        reset()
        val empty = run(ToolCatalogue.CORPUS, "entropy")
        expectEq(empty.ok, true, "an empty index is not an error")
        expectContains(empty.output, "no corpus passage matched", "and it says so")
        CorpusFixture.hits = listOf("passage one", "passage two")
        val hits = run(ToolCatalogue.CORPUS, "entropy")
        expectContains(hits.output, "passage one", "the passages are returned")
        expectContains(hits.detail, "2 passages", "and counted")
    }

    test("an unreadable index is a failure with a reason, not a crash") {
        reset()
        CorpusFixture.failure = IllegalStateException("index missing")
        val result = run(ToolCatalogue.CORPUS, "entropy")
        expectEq(result.ok, false, "failed")
        expectContains(result.detail, "not ready", "the loop is told why")
    }

    test("memory search is bounded and honest when it is unreadable") {
        reset()
        MemoryFixture.found = ""
        expectContains(run(ToolCatalogue.MEMORY, "archive").output, "nothing remembered", "empty is stated")
        MemoryFixture.found = "you asked about the archive on Tuesday"
        expectContains(run(ToolCatalogue.MEMORY, "archive").output, "Tuesday", "the memory is returned")
        MemoryFixture.failure = IllegalStateException("db locked")
        val failed = run(ToolCatalogue.MEMORY, "archive")
        expectEq(failed.ok, false, "an unreadable memory is a failure")
        expectContains(failed.detail, "unreadable", "named")
    }

    test("device facts are filtered by topic, and fall back to everything") {
        reset()
        val network = run(ToolCatalogue.DEVICE, "network")
        expectContains(network.output, "network: wifi", "the asked-for topic")
        expect(!network.output.contains("apps: 120"), "and not the rest")
        val unknownTopic = run(ToolCatalogue.DEVICE, "battery")
        expectContains(unknownTopic.output, "apps: 120", "an unmatched topic falls back to the whole snapshot")
        expectContains(network.output, "read at", "and it is stamped with when it was read")
    }

    test("the clock reports a real date, formatted once") {
        reset()
        val result = run(ToolCatalogue.CLOCK, "ignored")
        expectEq(result.ok, true, "it ran")
        expect(Regex("""\d{4}""").containsMatchIn(result.output), "it contains a year: ${result.output}")
    }

    test("the calculator is total for the toolbox too") {
        reset()
        val empty = run(ToolCatalogue.CALCULATE, "")
        expectEq(empty.ok, false, "an empty expression fails")
        expect(Calculator.evaluate("").let { it is Calculator.Result.Err }, "the calculator agrees")
    }

    println()
    if (failures == 0) {
        println("PASS · $checks checks")
    } else {
        println("FAIL · ${checks - failures} passed, $failures failed")
        kotlin.system.exitProcess(1)
    }
}
