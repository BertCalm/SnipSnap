package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Features
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MERCURY's factory roster: eight per voice, held to the identity, sanity, round-trip, names, blocklist and spread
 * contract every `<Engine>PresetsTest` holds (`ArcoPresetsTest`'s own shape), plus the roster's own claims: each preset
 * sits on the note its comment names, and the classifier never hears a drum in it.
 *
 * These prove the roster is *sound*. They cannot prove it is *good*: nothing in it was listened to, and the audition
 * page is where that is decided.
 */
class MercuryPresetsTest {

    private class Reading(val preset: MercuryPatch) {
        val voice = preset.voice
        val snip: Snip = preset.render()
        val features: Features = FeatureExtractor.extract(snip)
        val heard: DrumClass = Classifier.classify(features).drumClass
        val filed: DrumClass = Mercury.drumClassFor(voice, preset.macros)
        val midi: Int = Mercury.midiFor(voice, preset.macros.getValue("TUNE"))
        val label: String get() = "$voice ${preset.name}"
    }

    private companion object {
        val choking = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.CLAP, DrumClass.TOM)

        val readings: List<Reading> by lazy { MercuryPresets.all().parallelStream().map { Reading(it) }.toList() }

        private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun noteName(midi: Int): String = noteNames[midi % 12] + (midi / 12 - 1)

        fun f2(x: Number): String = "%.2f".format(java.util.Locale.ROOT, x.toDouble())

        /** The frozen roster: names in order, per voice (the kit and the audition page ask for these by name). */
        val names = mapOf(
            MercuryVoice.PING to listOf("CLEAR RIM", "SOFT MALLET", "STILL WATER", "COLD GLASS", "WARPED PLATE", "FLOATING MASS", "PAIRED MODES", "LOW BELL"),
            MercuryVoice.SING to listOf("LONG RUB", "SINGING EDGE", "GLASS CURRENT", "SLOW CURRENT", "HUSHED GLASS", "WET FINGER", "UPPER RIM", "LOW HUM"),
            MercuryVoice.BLADE to listOf("BENT RIBBON", "BOWED STEEL", "WHISTLE BEND", "SLOW GLIDE", "DOWN BEND", "WOBBLE STEEL", "LOW STEEL", "TAPPED STEEL"),
        )

        /** The note each preset's comment in [MercuryPresets] names. */
        val notes = mapOf(
            "CLEAR RIM" to "C5", "SOFT MALLET" to "G4", "STILL WATER" to "C5", "COLD GLASS" to "G5",
            "WARPED PLATE" to "A#4", "FLOATING MASS" to "C5", "PAIRED MODES" to "A4", "LOW BELL" to "C4",
            "LONG RUB" to "G4", "SINGING EDGE" to "A4", "GLASS CURRENT" to "G4", "SLOW CURRENT" to "E4",
            "HUSHED GLASS" to "F#4", "WET FINGER" to "G#4", "UPPER RIM" to "E5", "LOW HUM" to "A3",
            "BENT RIBBON" to "G4", "BOWED STEEL" to "A4", "WHISTLE BEND" to "D5", "SLOW GLIDE" to "E4",
            "DOWN BEND" to "G4", "WOBBLE STEEL" to "G4", "LOW STEEL" to "A3", "TAPPED STEEL" to "A4",
        )
    }

    @Test
    fun `every preset renders clean audio at full level`() {
        for (r in readings) {
            val snip = r.snip
            assertTrue(snip.frameCount > 0, "${r.label} rendered nothing")
            assertTrue(snip.samples.all { it.isFinite() }, "${r.label} produced non-finite samples")
            assertTrue(snip.samples.all { it in -1f..1f }, "${r.label} clipped")
            val loud = Loudness.of(snip)
            assertTrue(loud >= Dsp.MELODIC_LOUDNESS_TARGET * 0.9f || snip.peak() >= 0.95f, "${r.label} is too quiet: loudness $loud, peak ${snip.peak()}")
            val dc = snip.samples.average().toFloat()
            assertTrue(abs(dc) < 0.05f, "${r.label} has DC offset $dc")
            assertEquals(Mercury.renderFrames(Mercury.settled(r.preset.macros, r.voice)), snip.frameCount, "${r.label}: the filed length is not the rendered length")
        }
    }

    @Test
    fun `every preset classifies as a pitched note, never a drum with a choke group`() {
        for (r in readings) {
            println(
                "MERCURY PRESET ${r.label} ${noteName(r.midi)} len=${f2(r.snip.frameCount.toFloat() / Dsp.RATE)}s filed=${r.filed} heard=${r.heard} " +
                    "low=${f2(r.features.lowRatio)} high=${f2(r.features.highRatio)} decay=${r.features.decayMs.roundToInt()}ms",
            )
            assertTrue(r.heard !in choking, "${r.label} classified as ${r.heard}, a real drum's own choke group")
            if (r.heard == DrumClass.LOOP || r.filed == DrumClass.LOOP) {
                assertEquals(r.filed, r.heard, "${r.label}: filed class disagrees with the classifier over the LOOP line")
            } else {
                assertTrue(r.heard in setOf(DrumClass.PERC, DrumClass.TONAL), "${r.label}: a one-shot read as ${r.heard}")
            }
        }
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in MercuryPresets.all()) {
            val restored = MercuryPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertContentEquals(preset.render().samples, restored.render().samples, "${preset.name} rendered differently after a round-trip")
        }
    }

    @Test
    fun `preset names are uppercase, short, unique per voice, and the frozen roster in order`() {
        for (voice in MercuryVoice.entries) {
            val got = MercuryPresets.forVoice(voice).map { it.name }
            assertEquals(8, got.size, "$voice should ship 8 presets, has ${got.size}")
            assertEquals(got.toSet().size, got.size, "$voice has duplicate preset names: $got")
            for (name in got) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
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
            MercuryPatch.ENGINE,
            // R2's three voices, named in the design, so a preset does not take a name a voice is about to have.
            "EDDY", "VESSEL", "SHARD",
        ) + FxChain.SECTION_NAMES.map { it.uppercase() } + MercuryVoice.entries.map { it.name }
        assertTrue(taken.size > 30, "the list of taken names lost its rack sections")
        for (preset in MercuryPresets.all()) {
            val words = preset.name.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
            val clash = words.filter { it in taken }
            assertTrue(clash.isEmpty(), "${preset.name} names $clash, which is an engine, a voice or a rack section")
        }
    }

    @Test
    fun `no preset name references a real instrument or its maker`() {
        val offenders = MercuryPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real maker: ${offenders.map { it.name }}")
    }

    @Test
    fun `presets spread out rather than cluster`() {
        for (voice in MercuryVoice.entries) {
            val presets = MercuryPresets.forVoice(voice)
            for ((i, a) in presets.withIndex()) for (b in presets.drop(i + 1)) {
                val d = PresetTestSupport.rmsDistance(a.macros, b.macros)
                assertTrue(d > 0.05f, "$voice: ${a.name} and ${b.name} are nearly the same sound (${"%.3f".format(java.util.Locale.ROOT, d)})")
            }
        }
    }

    @Test
    fun `every preset names every macro, so the roster is the full knob and not a default in disguise`() {
        for (preset in MercuryPresets.all()) {
            assertEquals(Mercury.macrosFor(preset.voice).map { it.name }.toSet(), preset.macros.keys, "${preset.name} leaves a macro at its default")
        }
    }

    @Test
    fun `every preset lands on the note its comment names`() {
        for (r in readings) {
            val exact = r.preset.macros.getValue("TUNE") * Mercury.TUNE_SEMITONES
            assertTrue(abs(exact - exact.roundToInt()) < 0.05f, "${r.label}: TUNE ${r.preset.macros["TUNE"]} is ${f2(exact)} semitones, not on a note")
            assertEquals(notes.getValue(r.preset.name), noteName(r.midi), "${r.label} sounds ${noteName(r.midi)}")
        }
    }

    @Test
    fun `the dispatcher knows MERCURY`() {
        for (voice in MercuryVoice.entries) assertEquals(MercuryPresets.forVoice(voice), Presets.forVoice("MERCURY", voice.name))
        assertEquals(MercuryPresets.forVoice(MercuryVoice.SING).first(), Presets.byName("MERCURY", "SING", "LONG RUB"))
        assertTrue(Presets.all().containsAll(MercuryPresets.all()))
    }
}
