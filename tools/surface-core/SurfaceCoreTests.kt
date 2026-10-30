// Host-side verification for surface awareness.
//
// `Surfaces.kt` and `SurfaceAnswers.kt` are pure Kotlin — no Android — because Lucifer's claims about
// himself have to be testable. The two properties that matter:
//
//   1. recall    — a question about the interface is recognised however it is phrased
//   2. precision — a question about the user's task is NOT hijacked by the classifier
//
// A false positive is the worse failure: the user asks about a build and gets a lecture about his own
// overlay. `MUST_NOT_MATCH` below is therefore as important as the positive cases.
//
// Not part of the Android build. Run with scripts/verify_surface_core.sh.
package com.lucifer.mobile.surface.harness

import com.lucifer.mobile.surface.Surface
import com.lucifer.mobile.surface.SurfaceAnswers
import com.lucifer.mobile.surface.SurfaceFacts
import com.lucifer.mobile.surface.SurfaceKnowledge
import com.lucifer.mobile.surface.SurfaceQuestion
import com.lucifer.mobile.surface.SurfaceQuestions

private var passed = 0
private val failures = mutableListOf<String>()

private fun test(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("  ok  $name")
    } catch (error: Throwable) {
        failures.add("$name: ${error.message ?: error.javaClass.simpleName}")
        println("FAIL  $name -> ${error.message ?: error.javaClass.simpleName}")
    }
}

private fun expect(condition: Boolean, message: String) {
    if (!condition) throw AssertionError(message)
}

private fun expectEq(actual: Any?, expected: Any?, what: String) {
    if (actual != expected) throw AssertionError("$what: expected <$expected> but was <$actual>")
}

private val ONLINE = SurfaceFacts(
    networkOnline = true,
    modelKeyConfigured = true,
    termuxInstalled = true,
    termuxRunCommandGranted = true,
    vmConfigured = true,
    vmHostKeyPinned = true
)

private val OFFLINE = SurfaceFacts(networkOnline = false, modelKeyConfigured = false)

fun main() {
    println("Surface awareness · verification harness")

    test("recall: interface questions are recognised") {
        val cases = mapOf(
            SurfaceQuestion.WHERE to listOf(
                "where am I talking to you from?",
                "which interface is this?",
                "what surface am I on?",
                "what am I talking to you through?"
            ),
            SurfaceQuestion.CAPABILITIES to listOf(
                "what can you do here?",
                "what can you actually do from here",
                "what are your capabilities here?",
                "what does this panel let you do?",
                "can you run commands here?",
                "can you run a shell?",
                "what can you do"
            ),
            SurfaceQuestion.LIMITS to listOf(
                "what can't you do here?",
                "what are the limits here?",
                "why can't you see the terminal?",
                "what are you not allowed to do?"
            ),
            SurfaceQuestion.DATA to listOf(
                "what do you send when I ask you something?",
                "what leaves the device?",
                "are you spying on me?",
                "does this leak anything?"
            ),
            SurfaceQuestion.CONFIRMATION to listOf(
                "will you ask me before running it?",
                "do I need to confirm?",
                "is it automatic?",
                "can you run it without asking me?"
            ),
            SurfaceQuestion.LIST to listOf(
                "what interfaces can I talk to you from?",
                "where can I reach you?",
                "list your interfaces"
            )
        )
        for ((expected, messages) in cases) {
            for (message in messages) {
                expectEq(SurfaceQuestions.classify(message), expected, message)
            }
        }
    }

    test("precision: ordinary requests are not hijacked") {
        val mustNotMatch = listOf(
            "what can you do about this failing build?",
            "can you open chrome for me",
            "what does this error mean?",
            "why can't you connect to the VM?",
            "run git status on the VM",
            "explain entropy",
            "the terminal output says permission denied, what now?",
            "what is the weather in Charlotte",
            "can you write a python script that reads a CSV",
            "what do you send in the HTTP headers"
        )
        for (message in mustNotMatch) {
            expectEq(SurfaceQuestions.classify(message), SurfaceQuestion.NONE, message)
        }
    }

    test("guards: length, emptiness and statements") {
        expectEq(SurfaceQuestions.classify(""), SurfaceQuestion.NONE, "empty")
        expectEq(
            SurfaceQuestions.classify("what can you do here? " + "x".repeat(300)),
            SurfaceQuestion.NONE,
            "over-long"
        )
        expectEq(SurfaceQuestions.classify("the overlay is nice"), SurfaceQuestion.NONE, "statement")
    }

    test("every surface can describe itself") {
        for (surface in Surface.entries) {
            val spec = SurfaceKnowledge.spec(surface)
            expect(spec.whatItIs.length > 40, "${surface.id}: whatItIs too thin")
            expect(spec.canDo.isNotEmpty(), "${surface.id}: no capabilities")
            expect(spec.cannotDo.isNotEmpty(), "${surface.id}: no limits")
            expect(spec.confirmation.isNotBlank(), "${surface.id}: no confirmation rule")
            expect(spec.leavesDevice.isNotBlank(), "${surface.id}: does not say what leaves")
            expect(spec.limits.isNotBlank(), "${surface.id}: no limits text")
        }
    }

    test("unknown surface is honest, not optimistic") {
        val spec = SurfaceKnowledge.spec(Surface.UNKNOWN)
        expect(spec.canDo.size == 1, "UNKNOWN should offer almost nothing")
        expect(
            spec.cannotDo.any { it.contains("Assume", ignoreCase = true) },
            "UNKNOWN must refuse to assume capabilities"
        )
    }

    test("answers are built from the spec, not invented") {
        val answer = SurfaceAnswers.answer("what can you do here?", Surface.FLOATING_TERMUX, ONLINE)
            ?: throw AssertionError("no answer")
        val spec = SurfaceKnowledge.spec(Surface.FLOATING_TERMUX)
        for (capability in spec.canDo) {
            expect(answer.contains(capability.trimEnd('.')), "missing capability: $capability")
        }
        for (limit in spec.cannotDo) {
            expect(answer.contains(limit.trimEnd('.')), "missing limit: $limit")
        }
        expect(answer.contains("Floating") || answer.contains("floating"), "names the surface")
    }

    test("answers differ per surface — a launcher claim never leaks into the overlay") {
        val launcher = SurfaceAnswers.answer("what can you do here?", Surface.LAUNCHER_CHAT, ONLINE)
            ?: throw AssertionError("no launcher answer")
        val overlay = SurfaceAnswers.answer("what can you do here?", Surface.FLOATING_TERMUX, ONLINE)
            ?: throw AssertionError("no overlay answer")
        expect(launcher != overlay, "identical answers for different surfaces")
        expect(
            launcher.contains("Open an installed app", ignoreCase = true),
            "launcher should mention opening apps"
        )
        expect(
            !overlay.contains("Open an installed app", ignoreCase = true),
            "overlay must not claim it can open apps"
        )
    }

    test("live state is reported instead of assumed") {
        val online = SurfaceAnswers.answer("what can you do here?", Surface.CORE_CHAT, ONLINE)
            ?: throw AssertionError("no answer")
        val offline = SurfaceAnswers.answer("what can you do here?", Surface.CORE_CHAT, OFFLINE)
            ?: throw AssertionError("no answer")
        expect(online.contains("network switch: ON"), "online state")
        expect(offline.contains("network switch: OFF"), "offline state")
        expect(offline.contains("No model key", ignoreCase = true), "offline should mention the missing key")
    }

    test("awareness never claims sight") {
        val answer = SurfaceAnswers.answer("what can you do here?", Surface.LAUNCHER_CHAT, ONLINE)
            ?: throw AssertionError("no answer")
        expect(
            answer.contains("cannot see your screen", ignoreCase = true),
            "must state that it does not see the screen"
        )
    }

    test("unregistered questions return null so normal chat continues") {
        expect(
            SurfaceAnswers.answer("explain entropy", Surface.CORE_CHAT, ONLINE) == null,
            "ordinary question should fall through"
        )
        expect(
            SurfaceAnswers.answer("run git status on the VM", Surface.TERMINAL_TAB, ONLINE) == null,
            "a command should fall through to the router"
        )
    }

    test("prompt block carries the surface, the rules and the live state") {
        val block = SurfaceKnowledge.renderForPrompt(Surface.TERMINAL_TAB, ONLINE)
        expect(block.contains("terminal tab", ignoreCase = true), "names the surface")
        expect(block.contains("Cannot do here"), "states limits")
        expect(block.contains("Live state"), "carries live state")
        expect(block.contains("cannot see the screen", ignoreCase = true), "states the boundary")
        expect(block.length < 2_000, "prompt block stays bounded: ${block.length}")
    }

    test("catalogue lists every usable surface and skips UNKNOWN") {
        val catalogue = SurfaceKnowledge.catalogue()
        for (surface in Surface.entries) {
            if (surface == Surface.UNKNOWN) continue
            expect(catalogue.contains(surface.displayName), "missing from catalogue: ${surface.id}")
        }
        expect(!catalogue.contains("unnamed surface"), "UNKNOWN must not be offered as an interface")
    }

    test("confirmation: an unseen queue is reported as unseen") {
        val unknown = SurfaceAnswers.answer("will you ask before running things?", Surface.CORE_CHAT, ONLINE)
            ?: throw AssertionError("no answer")
        expect(unknown.contains("cannot see the confirmation queue"), "unknown case missing: $unknown")
        expect(!unknown.contains("Nothing is waiting"), "asserted nothing pending without looking: $unknown")

        val pending = SurfaceAnswers.answer(
            "will you ask before running things?",
            Surface.CORE_CHAT,
            ONLINE.copy(pendingConfirmation = true)
        ) ?: throw AssertionError("no answer")
        expect(pending.contains("waiting for your confirmation"), "true case missing")

        val clear = SurfaceAnswers.answer(
            "will you ask before running things?",
            Surface.CORE_CHAT,
            ONLINE.copy(pendingConfirmation = false)
        ) ?: throw AssertionError("no answer")
        expect(clear.contains("Nothing is waiting"), "false case missing")
    }

    test("data answers quote the surface's own egress claim") {
        val answer = SurfaceAnswers.answer("what leaves the device?", Surface.FLOATING_TERMUX, ONLINE)
            ?: throw AssertionError("no answer")
        val spec = SurfaceKnowledge.spec(Surface.FLOATING_TERMUX)
        expect(answer.contains(spec.leavesDevice), "egress claim missing")
        expect(answer.contains("confirmation is not a sandbox", ignoreCase = true), "invariants missing")
    }

    println()
    if (failures.isEmpty()) {
        println("PASS · $passed checks")
    } else {
        println("FAILED · $passed passed, ${failures.size} failed")
        failures.forEach { println(" - $it") }
        kotlin.system.exitProcess(1)
    }
}
