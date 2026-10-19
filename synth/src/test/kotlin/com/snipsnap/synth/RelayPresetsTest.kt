package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Curated playing recipes must retain their authored identity and survive saving. */
class RelayPresetsTest {
    private val seeds = linkedMapOf(
        RelayVoice.KEY to "FIRST HANDOFF",
        RelayVoice.BASS to "POCKET RELAY",
        RelayVoice.PLUCK to "MUTED PASSAGE",
        RelayVoice.ORGAN to "ORGAN AFTERGLOW",
        RelayVoice.CHIME to "BELL EXIT",
        RelayVoice.FLOW to "SOFT EXCHANGE",
    )

    @Test
    fun `the bank keeps six unchanged starting points within its curated voice families`() {
        val expected = listOf(8, 6, 6, 6, 4, 6)
        val patches = RelayPresets.all()
        assertEquals(36, patches.size)
        assertEquals(36, patches.map { it.name }.distinct().size, "factory names must be unambiguous")
        assertEquals(expected, RelayVoice.entries.map { RelayPresets.forVoice(it).size })
        for ((voice, name) in seeds) {
            val first = RelayPresets.forVoice(voice).first()
            assertEquals(RelayPatch(name, voice, Relay.defaults(voice)), first,
                "$voice keeps the original macros and complete phrase as its first recipe")
            assertContentEquals(Relay.render(voice).samples, first.render().samples,
                "$name must retain the earlier dry sound")
        }
        assertEquals(RelayVoice.entries.toSet(), patches.filter { Relay.isLoop(it.macros.getValue("HOLD")) }
            .map { it.voice }.toSet(), "each family has one purposeful held recipe")
        assertEquals(6, patches.count { Relay.isLoop(it.macros.getValue("HOLD")) })
    }

    @Test
    fun `every factory recipe renders bounded pitched material with a finished finite tail`() {
        val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
            DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
        val rendered = mutableMapOf<RelayVoice, MutableList<Pair<String, FloatArray>>>()
        for (patch in RelayPresets.all()) {
            val report = Relay.inspect(patch.voice, patch.macros, patch.velocity, phrase = patch.phrase)
            val samples = report.snip.samples
            val family = rendered.getOrPut(patch.voice) { mutableListOf() }
            for ((otherName, otherSamples) in family) {
                assertFalse(samples.contentEquals(otherSamples), "${patch.name} duplicates $otherName at the same native register")
            }
            family += patch.name to samples
            assertTrue(samples.isNotEmpty() && samples.all { it.isFinite() }, patch.name)
            assertTrue(report.rawPeak in 0.0..0.850501, "${patch.name} pre-level peak ${report.rawPeak}")
            assertTrue(report.snip.peak() in .001f..1f, "${patch.name} must produce usable dry audio")
            assertTrue(abs(samples.average()) < .005, "${patch.name} DC offset")
            val classified = Classifier.classify(report.snip)
            assertTrue(classified.drumClass !in drums, "${patch.name} classified as $classified")
            if (!report.loop) {
                assertTrue(samples.size.toDouble() / Dsp.RATE in 1.5..6.5,
                    "${patch.name} finite playing duration")
                assertTrue(abs(samples.last()) < 1e-5f, "${patch.name} release must finish")
            }
        }
    }

    @Test
    fun `saved custom factory scores replay exactly after playing edits and native velocity`() {
        val names = listOf("AFTERIMAGE KEY", "WOODEN STEP", "COPPER THREAD",
            "WARM REGISTER", "DUSK METAL", "FOUR CORNERS")
        for (name in names) {
            val source = RelayPresets.all().first { it.name == name }
            val played = source.copy(macros = source.macros + mapOf(
                "TUNE" to .25f, "ORDER" to .45f, "PULSE" to .55f,
            ), velocity = .45f)
            val saved = RelayPatch.fromJsonText(played.toJsonText())
            assertEquals(source.phrase, saved.phrase, "$name retains its authored source history and clock")
            assertEquals(played, saved, "$name retains playing edits and native velocity")
            assertContentEquals(played.render().samples, saved.render().samples,
                "$name replay must keep more than a display name and macro settings")
            assertContentEquals(saved.copy(velocity = .25f).render().samples, Velocity.atVelocity(saved, .25f).samples,
                "$name host velocity preserves the complete saved score")
        }
    }
}
