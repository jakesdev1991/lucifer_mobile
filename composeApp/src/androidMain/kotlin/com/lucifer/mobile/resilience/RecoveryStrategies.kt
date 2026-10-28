package com.lucifer.mobile.resilience

/**
 * The recovery playbook: what Lucifer is allowed to try after a failure, ordered from cheap and
 * read-only to disruptive. Nothing here executes anything - the Android layer decides how a
 * move is carried out, and non-read-only work still passes through the existing confirmation
 * boundary.
 */

/** How much trust a move needs before Lucifer may act on it. */
enum class MoveSafety {
    /** Fixed, vetted read-only probe; may run without extra confirmation. */
    READ_ONLY,

    /** A planning/suggestion step: no side effect until a human or the router turns it into action. */
    SUGGESTION,

    /** Needs the user's explicit approval before anything touches the system. */
    REQUIRES_CONFIRMATION
}

/** Reusable recovery tactics. The enum is stable because lessons are persisted by name. */
enum class RecoveryKind(val label: String, val safety: MoveSafety, val shortCode: String) {
    DIAGNOSE_ENVIRONMENT("inspect the environment", MoveSafety.READ_ONLY, "diag"),
    READ_LOGS("read recent logs", MoveSafety.READ_ONLY, "logs"),
    VERIFY_PREREQUISITES("verify prerequisites", MoveSafety.READ_ONLY, "prereq"),
    WEB_RESEARCH("research the web", MoveSafety.READ_ONLY, "web"),
    WAIT_AND_RETRY("wait, then retry", MoveSafety.SUGGESTION, "retry"),
    ALTERNATE_TOOL("use a different tool", MoveSafety.SUGGESTION, "alt-tool"),
    ALTERNATE_TARGET("run it on another target", MoveSafety.SUGGESTION, "alt-target"),
    SIMPLIFY_SCOPE("shrink the problem", MoveSafety.SUGGESTION, "simplify"),
    DECOMPOSE("split into smaller checks", MoveSafety.SUGGESTION, "decompose"),
    QUESTION_ASSUMPTION("challenge an assumption", MoveSafety.SUGGESTION, "assume"),
    FIX_DEPENDENCY("repair the dependency", MoveSafety.REQUIRES_CONFIRMATION, "dep"),
    FREE_RESOURCE("free the exhausted resource", MoveSafety.REQUIRES_CONFIRMATION, "resource"),
    RECONFIGURE("reconfigure the connection or account", MoveSafety.REQUIRES_CONFIRMATION, "config"),
    ASK_USER("ask the user", MoveSafety.SUGGESTION, "ask");

    companion object {
        fun fromName(raw: String?): RecoveryKind? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * One concrete recovery option. [instruction] is natural language (the existing router turns it
 * into a command if appropriate); [probeIds] names the diagnostic probes that support it.
 */
data class RecoveryMove(
    val kind: RecoveryKind,
    val title: String,
    val rationale: String,
    val instruction: String,
    val safety: MoveSafety = kind.safety,
    val probeIds: List<String> = emptyList(),
    val origin: MoveOrigin = MoveOrigin.PLAYBOOK
) {
    val isReadOnly: Boolean get() = safety == MoveSafety.READ_ONLY
}

enum class MoveOrigin { PLAYBOOK, LEARNED, DIAGNOSIS, MODEL, USER }

/** Where the "outside the box" prompts come from - shown to the model and in the panel. */
object LateralThinking {
    fun assumptionChallenges(failureClass: FailureClass, tool: String): List<String> = when (failureClass) {
        FailureClass.COMMAND_NOT_FOUND -> listOf(
            "Is the command missing, or is the shell looking in the wrong PATH for this user?",
            "Should this run somewhere else entirely - Termux instead of the app sandbox?",
            "Is there an equivalent tool already installed that does the same job?"
        )
        FailureClass.MISSING_DEPENDENCY -> listOf(
            "Is the dependency actually absent, or built for a different architecture/version?",
            "Can the same goal be reached with a tool that is already installed?",
            "Is this a runtime-only need that could be avoided by changing the approach?"
        )
        FailureClass.PERMISSION_DENIED -> listOf(
            "Is this an Android sandbox barrier rather than a Unix permission problem?",
            "Does this belong on Termux/Shizuku/the VM instead of the app process?",
            "Is the file owned by another user while a copy in workspace would work?"
        )
        FailureClass.NETWORK, FailureClass.DNS -> listOf(
            "Is DNS failing, or is only this one host blocked by the network?",
            "Would a cached copy, a mirror, or a different endpoint answer the question?",
            "Is the device actually online, or is the failure inside the sandbox?"
        )
        FailureClass.TIMEOUT -> listOf(
            "Is the timeout too aggressive, or is the work genuinely too big for one step?",
            "Can the task be split so progress is visible between chunks?",
            "Is a lock or prompt silently waiting for input that will never come?"
        )
        FailureClass.DISK_FULL, FailureClass.RESOURCE_LIMIT -> listOf(
            "What is actually consuming the space or memory - and is it needed?",
            "Could the work happen in a smaller temp area or on another target?",
            "Is an old artifact or log being retained that could be archived first?"
        )
        FailureClass.PORT_IN_USE -> listOf(
            "Is the old process still running, or is a different service on that port?",
            "Can the preview move to a free loopback port instead of killing something?",
            "Is this the right host - the app cannot see the VM's ports directly?"
        )
        FailureClass.BUILD_FAILED, FailureClass.SYNTAX -> listOf(
            "Is the failure in the code, in the toolchain version, or in stale build state?",
            "Does the smallest reproduction fail the same way?",
            "Was the command copied from a different platform's shell?"
        )
        FailureClass.TESTS_FAILED -> listOf(
            "Is the test wrong, the environment different, or the code actually broken?",
            "What is the smallest failing case that isolates the behavior?",
            "Do the tests need data, network, or a device that is not present?"
        )
        FailureClass.AUTH -> listOf(
            "Is the credential absent, expired, or simply not visible to this process?",
            "Is the failure a network gate in front of the API rather than the key itself?",
            "Would a local, offline path answer the question without credentials?"
        )
        FailureClass.MODEL_UNAVAILABLE -> listOf(
            "Is this a missing key, a rate limit, or a removed model name?",
            "Can Lucifer answer from local knowledge, embedded corpus, or memory instead?",
            "Would a smaller request or a different route succeed?"
        )
        FailureClass.TERMINAL_SETUP -> listOf(
            "Which prerequisite is missing: the app, the permission, or the configuration?",
            "Can the same check run on a target that is already configured?",
            "Is the user expected to take one visible step that Lucifer cannot take for them?"
        )
        FailureClass.PATH_MISSING -> listOf(
            "Is the path wrong, or does it exist in a different home directory on this target?",
            "Was the file produced somewhere else (a previous session or another target)?",
            "Would listing the parent directory answer this faster than guessing?"
        )
        FailureClass.NONE, FailureClass.UNKNOWN -> listOf(
            "What assumption would make this failure impossible if it were false?",
            "Which single observation would distinguish the top two explanations?",
            "Could this be a different problem than the error text suggests?"
        )
    }

    /** Short, class-specific nudge appended to planning prompts. */
    fun curiosityPrompt(failureClass: FailureClass, tool: String): String =
        "Lateral questions to consider: " + assumptionChallenges(failureClass, tool).joinToString(" ")
}

/** Ordered, class-specific candidates. The ledger then re-ranks them using real outcomes. */
object RecoveryPlaybook {

    fun candidates(signal: FailureSignal): List<RecoveryMove> {
        val t = signal.target
        val tool = signal.tool
        return when (signal.failureClass) {
            FailureClass.COMMAND_NOT_FOUND -> listOf(
                move(RecoveryKind.DIAGNOSE_ENVIRONMENT, "Check what is actually installed",
                    "'$tool' was not found. Before installing anything, see which tools exist on ${t.displayName}.",
                    "Inspect ${t.displayName} for whether '$tool' or an equivalent exists, and report the PATH.", probeIds = listOf("tools", "path")),
                move(RecoveryKind.ALTERNATE_TARGET, "Try the target that owns that tool",
                    "The app sandbox has a very small userland; Termux or the VM usually has '$tool'.",
                    "Run the same request on the target where '$tool' is installed, if one is available."),
                move(RecoveryKind.WEB_RESEARCH, "Find the correct invocation or package",
                    "The command may have been renamed or live in a package with a different name.",
                    "Research which package provides '$tool' on this platform and the exact install command."),
                move(RecoveryKind.ALTERNATE_TOOL, "Use an equivalent already-installed tool",
                    "Another tool often produces the same evidence with less setup.",
                    "Identify an installed alternative for '$tool' and use it instead of installing anything.")
            )
            FailureClass.MISSING_DEPENDENCY -> listOf(
                move(RecoveryKind.VERIFY_PREREQUISITES, "Confirm the dependency and its version",
                    "An import/install error can mean absent, wrong architecture, or wrong version.",
                    "Check the installed version and architecture of the missing dependency on ${t.displayName}.", probeIds = listOf("tools", "os")),
                move(RecoveryKind.FIX_DEPENDENCY, "Install or repair the dependency",
                    "If the package is genuinely missing, installing it is the direct fix - with your approval.",
                    "Install the missing dependency on ${t.displayName} using its package manager."),
                move(RecoveryKind.ALTERNATE_TOOL, "Avoid the dependency",
                    "Rewriting the step to use the standard library or an installed tool removes the install entirely.",
                    "Rewrite the failing step so it needs no new dependency."),
                move(RecoveryKind.WEB_RESEARCH, "Look up this exact error",
                    "Dependency errors are frequently version-specific; the fix is in the release notes.",
                    "Research this exact dependency error, including the version, and summarize the confirmed fix.")
            )
            FailureClass.PERMISSION_DENIED -> listOf(
                move(RecoveryKind.DIAGNOSE_ENVIRONMENT, "Identify which permission is missing",
                    "Android sandbox denials and Unix permissions fail the same way but need different fixes.",
                    "Check the user, uid, and file ownership on ${t.displayName}.", probeIds = listOf("identity", "path")),
                move(RecoveryKind.ALTERNATE_TARGET, "Move the work where it is allowed",
                    "Termux or the VM may have the permissions the app sandbox does not.",
                    "Run this step on a target with the required permissions instead of requesting new ones."),
                move(RecoveryKind.SIMPLIFY_SCOPE, "Write somewhere the app actually owns",
                    "App-private storage is often enough; shared paths need scoped access.",
                    "Redo the step using app-private or home storage rather than a protected path."),
                move(RecoveryKind.RECONFIGURE, "Change only the specific grant you own",
                    "If a human must grant it, Lucifer should ask - never silently escalate.",
                    "Ask the user which permission to grant, and open the relevant Android settings.")
            )
            FailureClass.AUTH -> listOf(
                move(RecoveryKind.VERIFY_PREREQUISITES, "Check whether a credential is even configured",
                    "A missing key and a rejected key need different fixes.",
                    "Check whether the credential is configured and non-empty, without printing it.", probeIds = listOf("env")),
                move(RecoveryKind.RECONFIGURE, "Re-enter the credential",
                    "The saved key may be expired or truncated.",
                    "Ask the user to re-enter the credential in Lucifer Settings."),
                move(RecoveryKind.ALTERNATE_TOOL, "Answer without the remote service",
                    "Local reasoning, memory, and the embedded corpus still work offline.",
                    "Answer from local knowledge and memory instead of the rejected remote route."),
                move(RecoveryKind.WEB_RESEARCH, "Confirm the service is healthy",
                    "A provider outage looks identical to a bad key.",
                    "Check whether the provider is reporting an outage before changing any credential.")
            )
            FailureClass.NETWORK, FailureClass.DNS -> listOf(
                move(RecoveryKind.DIAGNOSE_ENVIRONMENT, "Separate DNS from connectivity",
                    "Resolution failures and blocked routes have different fixes.",
                    "Check DNS resolution and outbound HTTPS from ${t.displayName}.", probeIds = listOf("dns", "egress")),
                move(RecoveryKind.WAIT_AND_RETRY, "Retry once after a short wait",
                    "Transient network faults are the most common cause and clear quickly.",
                    "Wait briefly, then retry the same step exactly once."),
                move(RecoveryKind.ALTERNATE_TOOL, "Use a mirror or cached copy",
                    "The host may be blocked while the content is available elsewhere.",
                    "Look for a mirror, cache, or offline copy of the same resource."),
                move(RecoveryKind.WEB_RESEARCH, "Confirm whether the host is reachable at all",
                    "Lucifer's fetch path can distinguish a dead host from a restricted sandbox.",
                    "Research whether this service is reachable from a normal network, and note any outage.")
            )
            FailureClass.TIMEOUT -> listOf(
                move(RecoveryKind.DECOMPOSE, "Split the work into observable chunks",
                    "A timeout hides which part is slow; smaller steps make progress measurable.",
                    "Break the failing step into smaller checks and run the first one only."),
                move(RecoveryKind.DIAGNOSE_ENVIRONMENT, "Look for a blocked prompt or a busy host",
                    "Interactive prompts and CPU contention both present as timeouts.",
                    "Inspect running processes and load on ${t.displayName}.", probeIds = listOf("processes", "load")),
                move(RecoveryKind.WAIT_AND_RETRY, "Retry when the host is quieter",
                    "If the host is overloaded, retrying later is cheaper than changing the approach.",
                    "Retry after the load drops, and report the timing.")
            )
            FailureClass.DISK_FULL, FailureClass.RESOURCE_LIMIT -> listOf(
                move(RecoveryKind.DIAGNOSE_ENVIRONMENT, "Find what is consuming the resource",
                    "Freeing the wrong thing wastes the one chance you get.",
                    "Measure disk, memory, and the biggest consumers on ${t.displayName}.", probeIds = listOf("disk", "memory")),
                move(RecoveryKind.FREE_RESOURCE, "Reclaim space deliberately",
                    "Caches and old artifacts are usually safe to clear - after review.",
                    "List candidate caches or artifacts to clear, then clear only what the user approves."),
                move(RecoveryKind.SIMPLIFY_SCOPE, "Produce a smaller artifact",
                    "A partial result that fits is better than a complete one that cannot be written.",
                    "Reduce the output size - fewer rows, no intermediate copies - and retry."),
                move(RecoveryKind.ALTERNATE_TARGET, "Run where there is room",
                    "The VM or Termux may have capacity the phone's app sandbox lacks.",
                    "Run this step on a target with more free space and report the result.")
            )
            FailureClass.PORT_IN_USE -> listOf(
                move(RecoveryKind.DIAGNOSE_ENVIRONMENT, "See which process owns the port",
                    "Killing the wrong process breaks the session you are working in.",
                    "List listening sockets and the owning processes on ${t.displayName}.", probeIds = listOf("ports")),
                move(RecoveryKind.ALTERNATE_TOOL, "Move to a free loopback port",
                    "A different port avoids disrupting anything already running.",
                    "Start the preview on the next free loopback port instead."),
                move(RecoveryKind.FREE_RESOURCE, "Stop the stale preview",
                    "A leftover preview from an earlier session is the usual culprit.",
                    "Stop the specific stale preview the user identifies, then restart.")
            )
            FailureClass.BUILD_FAILED, FailureClass.SYNTAX -> listOf(
                move(RecoveryKind.DIAGNOSE_ENVIRONMENT, "Check the toolchain before the code",
                    "Wrong compiler or stale build state produces convincing 'code' errors.",
                    "Report the compiler/runtime versions and workspace state on ${t.displayName}.", probeIds = listOf("tools", "os", "disk")),
                move(RecoveryKind.READ_LOGS, "Read the first error, not the last",
                    "The earliest error is usually the cause; later ones are cascades.",
                    "Show the first error and the smallest reproduction of it."),
                move(RecoveryKind.SIMPLIFY_SCOPE, "Reproduce minimally",
                    "A minimal reproduction separates environment problems from code problems.",
                    "Reduce the failing case to the smallest command that still fails."),
                move(RecoveryKind.WEB_RESEARCH, "Look up the exact compiler message",
                    "Version-specific toolchain errors have documented fixes.",
                    "Research the exact compiler error with the toolchain version.")
            )
            FailureClass.TESTS_FAILED -> listOf(
                move(RecoveryKind.READ_LOGS, "Read the failing assertion",
                    "The assertion text usually names the wrong assumption directly.",
                    "Show the failing test name, expected value, and actual value."),
                move(RecoveryKind.DIAGNOSE_ENVIRONMENT, "Check inputs the test depends on",
                    "Missing fixtures, network, or device capabilities masquerade as logic bugs.",
                    "Verify the test environment and required fixtures on ${t.displayName}.", probeIds = listOf("os", "tools", "egress")),
                move(RecoveryKind.QUESTION_ASSUMPTION, "Question whether the code or the test is wrong",
                    "When tests and code disagree, one of them encodes the wrong expectation.",
                    "State which behavior the requirement actually demands, then decide what to change.")
            )
            FailureClass.MODEL_UNAVAILABLE -> listOf(
                move(RecoveryKind.VERIFY_PREREQUISITES, "Check the key and the model name",
                    "A blank key, a removed model, and a rate limit all surface as route failure.",
                    "Verify a key is saved and check the recorded model preference.", probeIds = listOf("env")),
                move(RecoveryKind.ALTERNATE_TOOL, "Continue locally",
                    "Embedded knowledge and memory can answer many questions without the remote model.",
                    "Answer from the embedded corpus and local memory, and label it as offline reasoning."),
                move(RecoveryKind.WAIT_AND_RETRY, "Retry after the limit window",
                    "Rate limits are temporary and retrying immediately makes them worse.",
                    "Wait for the rate-limit window to pass, then retry once.")
            )
            FailureClass.TERMINAL_SETUP -> listOf(
                move(RecoveryKind.VERIFY_PREREQUISITES, "Identify the missing prerequisite",
                    "Termux permission, host key, and SSH credentials are separate prerequisites.",
                    "Report exactly which setup step is missing for ${t.displayName}.", probeIds = listOf("identity", "path")),
                move(RecoveryKind.ALTERNATE_TARGET, "Use a configured target meanwhile",
                    "Lucifer can keep making progress on a target that is already working.",
                    "Run the equivalent check on a target that is already configured."),
                move(RecoveryKind.ASK_USER, "Ask for the one visible step needed",
                    "Some setup steps are intentionally human-only.",
                    "Tell the user the single step they must take, then wait.")
            )
            FailureClass.PATH_MISSING -> listOf(
                move(RecoveryKind.DIAGNOSE_ENVIRONMENT, "List the parent directory",
                    "One listing usually settles which path is real.",
                    "List the parent directory of the missing path on ${t.displayName}.", probeIds = listOf("path")),
                move(RecoveryKind.ALTERNATE_TARGET, "Look where the file was actually produced",
                    "Each target has its own filesystem; the artifact may be on another one.",
                    "Check the same relative path on the other targets."),
                move(RecoveryKind.VERIFY_PREREQUISITES, "Confirm the producing step ran",
                    "A path error is often a downstream symptom of an earlier failure.",
                    "Confirm the step that should have created this file actually succeeded.")
            )
            FailureClass.NONE, FailureClass.UNKNOWN -> listOf(
                move(RecoveryKind.DIAGNOSE_ENVIRONMENT, "Gather environment evidence",
                    "When the error is unclear, environment facts narrow the space quickly.",
                    "Run the read-only environment probe set on ${t.displayName}.", probeIds = listOf("os", "tools", "disk", "processes")),
                move(RecoveryKind.WEB_RESEARCH, "Research the exact error text",
                    "An unfamiliar error is often a known issue with a documented workaround.",
                    "Research this exact error message and summarize the confirmed cause."),
                move(RecoveryKind.SIMPLIFY_SCOPE, "Reduce to the smallest failing case",
                    "A smaller case either reproduces with a clear cause or disappears, which is also information.",
                    "Reduce the failing request to its smallest form and retry that."),
                move(RecoveryKind.QUESTION_ASSUMPTION, "Test the assumption behind the step",
                    "If the premise is wrong, every follow-up fix will also be wrong.",
                    "Name the assumption this step depends on and design one check that could falsify it.")
            )
        }
    }

    /** Always-available lateral moves used when the playbook runs out. */
    fun lateralFallback(signal: FailureSignal): List<RecoveryMove> = listOf(
        move(RecoveryKind.QUESTION_ASSUMPTION, "Challenge the framing",
            "When nothing works, the problem statement itself is the most likely error.",
            "List three assumptions in the current approach and design one cheap check for each."),
        move(RecoveryKind.DECOMPOSE, "Change the shape of the task",
            "A different decomposition often sidesteps a blocker nobody can name.",
            "Rewrite the task as three smaller outcomes and attempt only the first."),
        move(RecoveryKind.ASK_USER, "Ask for the missing context",
            "Some context only the user has - intent, constraints, prior attempts.",
            "Ask the user one specific question that would change the plan.")
    )

    private fun move(
        kind: RecoveryKind,
        title: String,
        rationale: String,
        instruction: String,
        probeIds: List<String> = emptyList()
    ) = RecoveryMove(kind = kind, title = title, rationale = rationale, instruction = instruction, probeIds = probeIds)

    /** Human-readable list of everything the playbook can do for a failure class. */
    fun describe(signal: FailureSignal): String =
        candidates(signal).joinToString("\n") { "- ${it.kind.shortCode}: ${it.title}" }
}
