package com.lucifer.mobile.terminal

/** Target-independent owner boundaries for commands sent to any Lucifer terminal. */
object TerminalCommandPolicy {
    private val pmxPackage = Regex("(?i)\\bcom\\.mirfatif\\.permissionmanagerx\\b")
    private val permissionMutation = Regex(
        "(?i)\\b(?:pm\\s+(?:grant|revoke|set-permission-flags|clear-permission-flags)|cmd\\s+package\\s+(?:grant|revoke|set-permission-flags|clear-permission-flags)|(?:cmd\\s+)?appops\\s+(?:set|reset))\\b"
    )

    /** Returns a refusal message for recognizable direct edits of PMX permission/app-op state. */
    fun refusalReason(command: String): String? {
        if (!pmxPackage.containsMatchIn(command) || !permissionMutation.containsMatchIn(command)) return null
        return "Lucifer will not grant, revoke, or change Permission Manager X permissions. Manage those grants yourself in Shizuku/Permission Manager X."
    }
}
