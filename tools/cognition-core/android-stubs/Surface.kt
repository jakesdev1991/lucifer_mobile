@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.surface

enum class Surface { UNKNOWN }

class SurfaceFacts(private val lines: List<String> = emptyList()) {
    fun snapshot(): List<String> = lines
}

object SurfaceKnowledge {
    fun renderForPrompt(surface: Surface, facts: SurfaceFacts): String = "surface: ${surface.name}"
}

object SurfaceRuntime {
    fun facts(context: android.content.Context): SurfaceFacts = SurfaceFacts()
}
