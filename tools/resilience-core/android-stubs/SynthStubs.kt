// Type-check-only stand-in. See AndroidStubs.kt.
//
// The real SkillSynthRuntime lives in `skills/` and pulls in the whole synthesizer (state file,
// codec, prompt rendering). The recovery loop only needs to know that recording an outcome exists,
// so this stub keeps the signature honest without dragging the synth package into this harness.
@file:Suppress("unused", "UNUSED_PARAMETER")

package com.lucifer.mobile.skills

object SkillSynthRuntime {
    fun learnProcedure(
        context: android.content.Context?,
        failureClass: String,
        target: String,
        tactic: String,
        worked: Boolean
    ) = Unit
}
