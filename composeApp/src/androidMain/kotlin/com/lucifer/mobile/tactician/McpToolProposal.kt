package com.lucifer.mobile.tactician

import com.lucifer.mobile.resilience.JsonLite

/**
 * A draft tool for the existing Google-Drive memory MCP server.
 *
 * The General can propose a new read-only tool for that server; it cannot install one. This file
 * produces a reviewable spec (name, JSON Schema, read-only annotation, and the Termux steps the user
 * would run). Nothing here executes, connects, or writes to Drive.
 */
data class McpToolDraft(
    val name: String,
    val title: String,
    val purpose: String,
    val inputSchema: Map<String, Any?>,
    val returns: String,
    val notes: List<String>,
    val termuxChecklist: List<String>,
    val origin: String = "tactician"
) {
    val isReadOnly: Boolean = true

    /** Full spec, including the non-executable next steps, as one JSON document. */
    fun toJson(): String = JsonLite.stringify(
        linkedMapOf<String, Any?>(
            "schema" to SCHEMA,
            "server" to "drive-memory (same-device Termux, read-only)",
            "tool" to linkedMapOf<String, Any?>(
                "name" to name,
                "title" to title,
                "description" to purpose,
                "inputSchema" to inputSchema,
                "annotations" to linkedMapOf<String, Any?>(
                    "readOnlyHint" to true,
                    "destructiveHint" to false,
                    "openWorldHint" to false
                )
            ),
            "returns" to returns,
            "notes" to notes,
            "user_steps" to termuxChecklist,
            "executed" to false,
            "origin" to origin
        )
    )

    companion object {
        const val SCHEMA = 1
    }
}

object McpToolProposals {

    private val namePattern = Regex("^[a-z][a-z0-9_]{2,40}$")

    /**
     * Build a draft from a proposal. Returns null when the proposal is not about the memory server,
     * because a generic tool name would be noise.
     */
    fun from(opportunity: Opportunity): McpToolDraft? {
        if (opportunity.category != OpportunityCategory.MCP_TOOL) return null
        val slug = slugFor(opportunity.title)
        return build(
            name = slug,
            title = opportunity.title.take(80),
            purpose = "${opportunity.why.take(300)} ${opportunity.action.take(200)}".trim()
        )
    }

    fun build(name: String, title: String, purpose: String): McpToolDraft {
        val safeName = if (namePattern.matches(name)) name else slugFor(name)
        return McpToolDraft(
            name = safeName,
            title = title.take(80).ifBlank { safeName.replace('_', ' ') },
            purpose = purpose.take(600).ifBlank { "Retrieve the passages most relevant to a query from the private Drive memory." },
            inputSchema = linkedMapOf(
                "type" to "object",
                "properties" to linkedMapOf(
                    "query" to linkedMapOf(
                        "type" to "string",
                        "description" to "What to look for. Natural language; the server embeds it.",
                        "maxLength" to 512
                    ),
                    "limit" to linkedMapOf(
                        "type" to "integer",
                        "description" to "Maximum passages to return.",
                        "minimum" to 1,
                        "maximum" to 8,
                        "default" to 3
                    )
                ),
                "required" to listOf("query"),
                "additionalProperties" to false
            ),
            returns = "An array of {path, heading, excerpt, score} objects, read-only. No file writes, no listing of unrelated paths.",
            notes = listOf(
                "Read-only by construction: retrieval only, no mutation tool is proposed.",
                "The server stays on the same device over loopback; nothing is uploaded to a third party by this tool.",
                "Lucifer will not install, restart, or grant anything: these are steps for you to run in Termux."
            ),
            termuxChecklist = listOf(
                "1. Review the spec in this file before changing the server.",
                "2. Add the tool to the existing memory MCP server's read-only tool list; keep its bearer token in the mode-600 config.",
                "3. Restart the Termux service and re-run the Settings 'TEST READ-ONLY MCP CONNECTION' check.",
                "4. If retrieval fails, Lucifer prompts unchanged; the tool must fail open."
            )
        )
    }

    /** Validation used before a draft is written, so a garbage proposal cannot produce a bad spec. */
    fun problems(draft: McpToolDraft): List<String> = buildList {
        if (!namePattern.matches(draft.name)) add("tool name must be snake_case, 3-41 chars: '${draft.name}'")
        if (draft.purpose.isBlank()) add("purpose is empty")
        if (draft.inputSchema["type"] != "object") add("inputSchema must be an object schema")
        if (!draft.isReadOnly) add("draft must be read-only")
        val required = (draft.inputSchema["required"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        if ("query" !in required) add("query must be a required input")
    }

    fun slugFor(text: String): String {
        val base = text.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(32)
            .trim('_')
        val prefixed = when {
            base.isEmpty() -> "memory_lookup"
            base.first().isLetter() -> base
            else -> "memory_$base"
        }
        return prefixed.take(40)
    }
}
