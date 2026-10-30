package com.lucifer.mobile.omni_bridge.llm

/** Pure validation shared by Settings writes and runtime reads. No credential values in errors. */
object RuntimeKeys {
    const val MAX_INPUT = 8192
    const val MAX_KEYS = 16

    /** null means invalid; an empty list is an intentional clear. Order survives deduplication. */
    fun parse(value: String): List<String>? {
        if (value.length > MAX_INPUT) return null
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return emptyList()
        val keys = trimmed.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.distinct()
        if (keys.isEmpty() || keys.size > MAX_KEYS) return null
        if (keys.any { key -> key.length !in 16..512 || key.any {
                it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '-' && it != '_'
            } }) return null
        return keys
    }
}
