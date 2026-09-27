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
 * FORK's factory roster: eight per voice, the same identity/sanity/
 * round-trip/names/blocklist/spread contract every `<Engine>PresetsTest`
 * holds (`SirenPresetsTest`'s own shape, most recently).
 */
class ForkPresetsTest {

    @Test
    fun `every preset renders clean audio at full level`() {
        for (preset in ForkPresets.all()) {
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
        // Measured, not wished for: DECAY alone decides the bucket
        // (Fork.drumClassFor, verified against the real classifier in
        // ForkTest), and there is no gap where FORK reads strict TONAL - a
        // low DECAY reads PERC, a high one LOOP (the same length rule
        // SIREN's own presets hit). Both are honest, non-drum readings for
        // a struck, decaying tone; what actually matters for a kit is that
        // a FORK pad never lands in an actual drum's choke group, and that
        // the preset's own filed class (what SynthScreen reads before a
        // render even exists) agrees with what a render is actually heard as.
        val choking = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.CLAP, DrumClass.TOM)
        val heard = ForkPresets.all().associate { it.name to Classifier.classify(it.render()).drumClass }
        println("FORK presets by the classifier: $heard")
        for (preset in ForkPresets.all()) {
            val drumClass = heard.getValue(preset.name)
            assertTrue(drumClass !in choking, "${preset.name} classified as $drumClass, a real drum's own choke group")
            assertEquals(Fork.drumClassFor(preset.voice, preset.macros), drumClass, "${preset.name}: filed class disagrees with the classifier")
        }
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in ForkPresets.all()) {
            val restored = ForkPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertContentEquals(preset.render().samples, restored.render().samples, "${preset.name} rendered differently after a round-trip")
        }
    }

    @Test
    fun `preset names are uppercase, short, and unique per voice`() {
        for (voice in ForkVoice.entries) {
            val names = ForkPresets.forVoice(voice).map { it.name }
            assertEquals(8, names.size, "$voice should ship 8 presets, has ${names.size}")
            assertEquals(names.toSet().size, names.size, "$voice has duplicate preset names: $names")
            for (name in names) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
            }
        }
    }

    @Test
    fun `no preset name references a real instrument maker`() {
        val offenders = ForkPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real maker: ${offenders.map { it.name }}")
    }

    @Test
    fun `the blocklist catches the electric piano makers and their near-misses`() {
        for (nearMiss in listOf("RHODES 73", "WURLITZER", "FENDER KEYS", "rhodes-ish")) {
            assertTrue(PresetTestSupport.trademarkBlocklist.containsMatchIn(nearMiss), "blocklist let '$nearMiss' through")
        }
        for (clean in listOf("DINNER JAZZ", "GLASS TINE", "VIBE BELL", "COLD METAL")) {
            assertTrue(!PresetTestSupport.trademarkBlocklist.containsMatchIn(clean), "blocklist wrongly flagged '$clean'")
        }
    }

    @Test
    fun `presets spread out rather than cluster`() {
        for (voice in ForkVoice.entries) {
            val presets = ForkPresets.forVoice(voice)
            for ((i, a) in presets.withIndex()) for (b in presets.drop(i + 1)) {
                val d = PresetTestSupport.rmsDistance(a.macros, b.macros)
                assertTrue(d > 0.05f, "$voice: ${a.name} and ${b.name} are nearly the same sound (${"%.3f".format(d)})")
            }
        }
    }

    @Test
    fun `the dispatcher knows FORK`() {
        assertEquals(ForkPresets.forVoice(ForkVoice.TINE), Presets.forVoice("FORK", "TINE"))
        assertEquals(ForkPresets.forVoice(ForkVoice.TINE).first(), Presets.byName("FORK", "TINE", "DINNER JAZZ"))
        assertTrue(Presets.all().containsAll(ForkPresets.all()))
    }
}
