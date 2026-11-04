package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The six first-generation [RosterAudition] specs say something about every
 * voice and every knob the page will play. [RosterAudition] falls back to
 * "KNOB at 0, the rest at their defaults" for a macro with no note and an
 * empty line for a voice with none, so a macro added to an engine later would
 * otherwise reach the page silently. Reads the specs only: nothing renders.
 */
class FirstGenAuditionTest {

    private val specs = listOf(
        VelvetRosterAuditionGenerator.SPEC,
        TonewheelRosterAuditionGenerator.SPEC,
        SkinRosterAuditionGenerator.SPEC,
        TideRosterAuditionGenerator.SPEC,
        VoxRosterAuditionGenerator.SPEC,
        ResinRosterAuditionGenerator.SPEC,
    )

    @Test
    fun `the six specs name six different engines`() {
        assertEquals(
            listOf(VelvetPatch.ENGINE, TonewheelPatch.ENGINE, SkinPatch.ENGINE, TidePatch.ENGINE, VoxPatch.ENGINE, ResinPatch.ENGINE),
            specs.map { it.engine },
        )
    }

    @Test
    fun `every voice the presets name has a voice note`() {
        for (spec in specs) {
            val voices = Presets.all().filter { it.engine == spec.engine }.map { it.voiceName }.distinct()
            assertTrue(voices.isNotEmpty(), "${spec.engine} has no factory presets")
            val missing = voices.filter { spec.voiceNotes[it].isNullOrBlank() }
            assertTrue(missing.isEmpty(), "${spec.engine}'s spec has no voice note for $missing")
            val stray = spec.voiceNotes.keys - voices.toSet()
            assertTrue(stray.isEmpty(), "${spec.engine}'s spec has voice notes for voices no preset names: $stray")
        }
    }

    @Test
    fun `every macro but TUNE of every voice the presets name has a knob note at both ends`() {
        for (spec in specs) {
            val byVoice = Presets.all().filter { it.engine == spec.engine }.groupBy { it.voiceName }
            val knobs = byVoice.values.flatMap { presets -> Velocity.macroSpecsFor(presets.first()).map { it.name } }
                .filter { it != "TUNE" }.toSet()
            val missing = knobs.filter { spec.knobNotes[it] == null }
            assertTrue(missing.isEmpty(), "${spec.engine}'s spec has no knob note for $missing")
            val blank = spec.knobNotes.filterValues { (low, high) -> low.isBlank() || high.isBlank() }.keys
            assertTrue(blank.isEmpty(), "${spec.engine}'s spec has a blank end for $blank")
            val stray = spec.knobNotes.keys - knobs
            assertTrue(stray.isEmpty(), "${spec.engine}'s spec has knob notes for macros no voice has: $stray")
        }
    }

    @Test
    fun `every spec has three guide paragraphs and three questions`() {
        for (spec in specs) {
            assertEquals(3, spec.guide.size, "${spec.engine}'s guide")
            assertEquals(3, spec.questions.size, "${spec.engine}'s questions")
        }
    }
}
