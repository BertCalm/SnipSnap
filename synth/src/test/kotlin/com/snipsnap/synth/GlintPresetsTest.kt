package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GLINT's factory roster (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.3): eight per voice,
 * held to the contract every `<Engine>PresetsTest` holds (names, blocklist, every macro set, round trip, a clean render)
 * plus the roster's own claims: every preset is its numbered candidate of the roster audition, to the bit, and every
 * one plays as a pad at the lowest, the middle and the top key. No spread test: this roster is picked, and good picks
 * cluster (PLUCK, TINES and THUMP have none either).
 *
 * These prove the roster is sound and is what the owner was sent. They cannot prove it is good: only his ear can,
 * at the final listen.
 */
class GlintPresetsTest {

    private companion object {
        /** The frozen roster: names in order, per voice. */
        val names = mapOf(
            GlintVoice.SWEEP to listOf("WARM DRIFT", "DEEP BLOOM", "OPEN SLIDE", "HOLLOW MID", "AMBER", "DARK RISE", "WARM RISE", "BRIGHT FALL"),
            GlintVoice.STEP to listOf("SOFT LADDER", "WARM BUZZ", "LONG CLIMB", "FULL BUZZ", "BRIGHT CLIMB", "RISING STEPS", "TWIN STEPS", "GLASS LADDER"),
            GlintVoice.BRASS to listOf("MELLOW HORN", "PLUSH HORN", "DARK TUBA", "SWAY HORN", "BRIGHT HORN", "BREATHY HORN", "STEADY HORN", "SLOW ROLL"),
            GlintVoice.VOWEL to listOf("STILL AH", "BUZZY EH", "SOFT OH", "RISING OO", "PLAIN OO", "FALLING EH", "RISING OH", "OPEN AH"),
        )

        /** Each preset's number among the roster audition's sixteen candidates (its clip `<VOICE>-NN`), in roster order. */
        val candidates = mapOf(
            GlintVoice.SWEEP to listOf(15, 9, 1, 10, 3, 13, 8, 14),
            GlintVoice.STEP to listOf(7, 14, 4, 15, 9, 5, 3, 16),
            GlintVoice.BRASS to listOf(11, 10, 1, 2, 6, 5, 16, 13),
            GlintVoice.VOWEL to listOf(10, 14, 1, 6, 3, 4, 11, 16),
        )
    }

    @Test
    fun `eight per voice, 32 in all, and all() is the four voices in order`() {
        for (voice in GlintVoice.entries) {
            val presets = GlintPresets.forVoice(voice)
            assertEquals(8, presets.size, "$voice should ship 8 presets, has ${presets.size}")
            assertTrue(presets.all { it.voice == voice }, "$voice holds another voice's preset")
        }
        assertEquals(32, GlintPresets.all().size)
        assertEquals(GlintVoice.entries.flatMap { GlintPresets.forVoice(it) }, GlintPresets.all())
    }

    @Test
    fun `names are uppercase, at most 14 characters, one or two words, unique per voice, and the frozen roster in order`() {
        for (voice in GlintVoice.entries) {
            val got = GlintPresets.forVoice(voice).map { it.name }
            assertEquals(got.toSet().size, got.size, "$voice has duplicate preset names: $got")
            for (name in got) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
                val words = name.split(" ")
                assertTrue(words.size in 1..2 && words.all { it.isNotEmpty() }, "$voice/$name is not one or two words")
            }
            assertEquals(names.getValue(voice), got, "$voice's roster")
        }
    }

    @Test
    fun `no preset name is an engine's name, a voice's or a rack section's`() {
        val taken = listOf(
            ThumpPatch.ENGINE, SkinPatch.ENGINE, TinesPatch.ENGINE, PluckPatch.ENGINE, TonewheelPatch.ENGINE, VelvetPatch.ENGINE,
            FathomPatch.ENGINE, ResinPatch.ENGINE, TidePatch.ENGINE, VoxPatch.ENGINE, SnapPatch.ENGINE, GlintPatch.ENGINE,
            SirenPatch.ENGINE, ForkPatch.ENGINE, TerraPatch.ENGINE, SilkPatch.ENGINE, BorePatch.ENGINE, ArcoPatch.ENGINE,
            MercuryPatch.ENGINE, GyrePatch.ENGINE,
        ) + FxChain.SECTION_NAMES.map { it.uppercase() } + GlintVoice.entries.map { it.name }
        assertTrue(taken.size > 30, "the list of taken names lost its rack sections")
        for (preset in GlintPresets.all()) {
            val words = preset.name.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
            val clash = words.filter { it in taken }
            assertTrue(clash.isEmpty(), "${preset.name} names $clash, which is an engine, a voice or a rack section")
        }
    }

    @Test
    fun `no preset name references a real instrument or its maker`() {
        val offenders = GlintPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real maker: ${offenders.map { it.name }}")
    }

    @Test
    fun `every preset names every macro, in range, and DEPTH stays at 0_9 or under`() {
        for (preset in GlintPresets.all()) {
            val wanted = Glint.macrosFor(preset.voice).map { it.name }.toSet()
            assertEquals(wanted, preset.macros.keys, "${preset.voice} ${preset.name} leaves a macro at its default")
            for ((macro, value) in preset.macros) assertTrue(value in 0f..1f, "${preset.name}: $macro is $value")
            assertTrue(preset.macros.getValue("DEPTH") <= 0.9f, "${preset.name}: DEPTH ${preset.macros["DEPTH"]} is past 0.9, where every voice is the same sine")
        }
    }

    @Test
    fun `no two presets of a voice are the same sound`() {
        for (voice in GlintVoice.entries) {
            val presets = GlintPresets.forVoice(voice)
            assertEquals(presets.size, presets.map { it.macros }.toSet().size, "$voice has two presets with the same macros")
        }
    }

    @Test
    fun `every preset is its numbered candidate of the roster audition, to the bit`() {
        for (voice in GlintVoice.entries) {
            val spread = GlintRosterCandidates.forVoice(voice)
            for ((i, preset) in GlintPresets.forVoice(voice).withIndex()) {
                val number = candidates.getValue(voice)[i]
                for (macro in GlintRosterCandidates.varied(voice)) {
                    assertEquals(
                        spread[number - 1].macros.getValue(macro), preset.macros.getValue(macro),
                        "$voice ${preset.name}: $macro is not candidate $number's",
                    )
                }
            }
        }
    }

    @Test
    fun `TUNE, DECAY and FOLLOW ship at their defaults, which the final listen may move`() {
        for (preset in GlintPresets.all()) {
            val defaults = Glint.defaults(preset.voice)
            for (macro in listOf("TUNE", "DECAY", "FOLLOW")) {
                if (macro in defaults) {
                    assertEquals(defaults.getValue(macro), preset.macros.getValue(macro), "${preset.voice} ${preset.name}: $macro left its default")
                }
            }
        }
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in GlintPresets.all()) {
            val restored = Patches.fromJsonText(preset.toJsonText()) as GlintPatch
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertContentEquals(preset.render().samples, restored.render().samples, "${preset.name} rendered differently after a round-trip")
        }
    }

    @Test
    fun `every preset renders clean audio, finite and at or under 0_99`() {
        for (preset in GlintPresets.all()) {
            val snip = preset.render()
            val what = "${preset.voice} ${preset.name}"
            assertTrue(snip.frameCount > 0, "$what rendered nothing")
            assertTrue(snip.samples.all { it.isFinite() }, "$what produced non-finite samples")
            assertTrue(snip.peak() <= 0.99f + 1e-6f, "$what peaks at ${snip.peak()}, over the 0.99 ceiling")
            assertTrue(snip.peak() > 0.05f, "$what is nearly silent: peak ${snip.peak()}")
        }
    }

    @Test
    fun `every preset plays as a pad at the lowest, the middle and the top key, and the loop closes`() {
        for (preset in GlintPresets.all()) {
            val midis = Keys.glintPadMidis(preset.voice)
            for (midi in listOf(midis.first(), midis[midis.size / 2], midis.last())) {
                // glintPad itself refuses a loop that does not close (requireSeam), so rendering is the check.
                val note = Keys.glintPad(preset.voice, preset.macros, midi)
                val what = "${preset.voice} ${preset.name} at MIDI $midi"
                assertTrue(note.snip.samples.all { it.isFinite() }, "$what: a sample is not finite")
                assertTrue(note.loopStartFrame in 1L until note.snip.frameCount.toLong(), "$what: the loop marker ${note.loopStartFrame} is outside the note")
            }
        }
    }
}
