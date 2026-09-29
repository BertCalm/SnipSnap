package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BORE's factory roster: eight per voice, the same identity/sanity/round-trip/names/
 * blocklist/spread contract every `<Engine>PresetsTest` holds (`ForkPresetsTest`'s own
 * shape). These prove the roster is *sound* - renders clean, files honestly, round-trips.
 * They cannot prove it is *good*: nothing in it was listened to, and the audition
 * page is where that is decided.
 */
class BorePresetsTest {

    @Test
    fun `every preset renders clean audio at full level`() {
        for (preset in BorePresets.all()) {
            val snip = preset.render()
            assertTrue(snip.frameCount > 0, "${preset.name} rendered nothing")
            assertTrue(snip.samples.all { it.isFinite() }, "${preset.name} produced non-finite samples")
            assertTrue(snip.samples.all { it in -1f..1f }, "${preset.name} clipped")
            val loud = Loudness.of(snip)
            assertTrue(
                loud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() >= 0.95f,
                "${preset.name} is too quiet: loudness $loud, peak ${snip.peak()}",
            )
            val dc = snip.samples.average().toFloat()
            assertTrue(abs(dc) < 0.05f, "${preset.name} has DC offset $dc")
        }
    }

    @Test
    fun `every preset classifies as a pitched note, never a drum with a choke group`() {
        // What the classifier makes of a sustained wind note is measured, not wished for: a
        // note over 1.5 s reads LOOP (the length rule, exact and predictable from HOLD), a
        // shorter one PERC - or TONAL, when the head window's share under 200 Hz passes 0.55 and
        // it rings past 500 ms, which two low SAX presets do from the skirts of their own
        // fundamental (R1's roster). All three are honest non-drum readings. What matters for a
        // kit is that a BORE pad never lands in a real drum's choke group (the first roster did,
        // 8 of 16: the mouth pressure's onset swell read as a snare or a clap - see Bore's
        // output tap), and that the filed class (what SynthScreen reads before a render exists)
        // is exact where the rule is exact, the LOOP line, and PERC-or-TONAL below it.
        val choking = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.CLAP, DrumClass.TOM)
        val heard = BorePresets.all().associate { it.name to Classifier.classify(it.render()).drumClass }
        println("BORE presets by the classifier: $heard")
        for (preset in BorePresets.all()) {
            val drumClass = heard.getValue(preset.name)
            assertTrue(drumClass !in choking, "${preset.name} classified as $drumClass, a real drum's own choke group")
            val filed = Bore.drumClassFor(preset.voice, preset.macros)
            if (drumClass == DrumClass.LOOP || filed == DrumClass.LOOP) {
                assertEquals(filed, drumClass, "${preset.name}: filed class disagrees with the classifier over the LOOP line")
            } else {
                assertTrue(drumClass in setOf(DrumClass.PERC, DrumClass.TONAL), "${preset.name}: a one-shot read as $drumClass")
                assertEquals(DrumClass.PERC, filed, "${preset.name}: a one-shot is filed PERC")
            }
        }
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in BorePresets.all()) {
            val restored = BorePatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertContentEquals(preset.render().samples, restored.render().samples, "${preset.name} rendered differently after a round-trip")
        }
    }

    @Test
    fun `preset names are uppercase, short, and unique per voice`() {
        for (voice in BoreVoice.entries) {
            val names = BorePresets.forVoice(voice).map { it.name }
            assertEquals(8, names.size, "$voice should ship 8 presets, has ${names.size}")
            assertEquals(names.toSet().size, names.size, "$voice has duplicate preset names: $names")
            for (name in names) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
            }
        }
    }

    @Test
    fun `no preset name references a real instrument or its maker`() {
        val offenders = BorePresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real maker: ${offenders.map { it.name }}")
    }

    @Test
    fun `the blocklist catches the tape keyboard and the reed makers, and lets mellow through`() {
        // The lookahead is the point: "mello" alone would refuse a plain adjective.
        for (nearMiss in listOf("MELLOTRON", "Mellotron M400", "mello-ish", "CHAMBERLIN", "HECKELPHONE", "SELMER MK6", "YANAGISAWA", "selmer-ish")) {
            assertTrue(PresetTestSupport.trademarkBlocklist.containsMatchIn(nearMiss), "blocklist let '$nearMiss' through")
        }
        for (clean in listOf("MELLOW REED", "MELLOWTONE", "SOFT SWELL", "BREATHY", "AIRY REED", "DINNER JAZZ", "GLASS TINE")) {
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(clean), "blocklist wrongly flagged '$clean'")
        }
    }

    @Test
    fun `presets spread out rather than cluster`() {
        for (voice in BoreVoice.entries) {
            val presets = BorePresets.forVoice(voice)
            for ((i, a) in presets.withIndex()) for (b in presets.drop(i + 1)) {
                val d = PresetTestSupport.rmsDistance(a.macros, b.macros)
                assertTrue(d > 0.05f, "$voice: ${a.name} and ${b.name} are nearly the same sound (${"%.3f".format(d)})")
            }
        }
    }

    @Test
    fun `every preset names every macro, so the roster is the full knob and not a default in disguise`() {
        for (preset in BorePresets.all()) {
            assertEquals(Bore.macrosFor(preset.voice).map { it.name }.toSet(), preset.macros.keys, "${preset.name} leaves a macro at its default")
        }
    }

    @Test
    fun `the roster's HOLD 1 presets are loops and the rest are not`() {
        for (preset in BorePresets.all()) {
            val loop = preset.macros.getValue("HOLD") >= Bore.LOOP_THRESHOLD
            assertEquals(loop, Bore.isLoop(preset.macros.getValue("HOLD")))
            if (loop) assertTrue(preset.render().frameCount >= 1.5f * Dsp.RATE, "${preset.name}: a LOOP under the classifier's line")
        }
    }

    @Test
    fun `the dispatcher knows BORE`() {
        assertEquals(BorePresets.forVoice(BoreVoice.SAX), Presets.forVoice("BORE", "SAX"))
        assertEquals(BorePresets.forVoice(BoreVoice.FLUTE).first(), Presets.byName("BORE", "FLUTE", "BREATHY"))
        assertTrue(Presets.all().containsAll(BorePresets.all()))
    }
}
