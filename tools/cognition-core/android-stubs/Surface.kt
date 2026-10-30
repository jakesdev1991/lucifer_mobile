// Type-check-only stand-in with a fixture, so `device_facts` rendering can be exercised for real.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.surface

enum class Surface { UNKNOWN }

object SurfaceFixture {
    @Volatile var lines: List<String> = listOf("build: Android 14", "apps: 120 installed", "network: wifi")

    fun reset() {
        lines = listOf("build: Android 14", "apps: 120 installed", "network: wifi")
    }
}

class SurfaceFacts(private val lines: List<String> = SurfaceFixture.lines) {
    fun snapshot(): List<String> = lines
}

object SurfaceKnowledge {
    fun renderForPrompt(surface: Surface, facts: SurfaceFacts): String = "surface: ${surface.name}"
}

object SurfaceRuntime {
    fun facts(context: android.content.Context): SurfaceFacts = SurfaceFacts()
}
