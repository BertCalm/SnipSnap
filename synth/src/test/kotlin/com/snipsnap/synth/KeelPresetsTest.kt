package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Features
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KEEL's factory roster: six per voice (REDUCE, UPRIGHT, BELLY), held to the identity, sanity, round-trip, names, blocklist and spread contract
 * every `<Engine>PresetsTest` holds (`MercuryPresetsTest`'s own shape), plus the roster's own claims: each preset sits
 * on the note its comment names, names all seven knobs and keeps SLIDE at or under .5; the fifteen one-shots are short and
 * filed TONAL; the three loops are about three seconds long, filed LOOP and sit at C2; and the classifier never hears a
 * drum with a choke group in any of the eighteen.
 *
 * These prove the roster is *sound*. They cannot prove it is *good*: nothing in it was listened to yet, and the kit on
 * the phone ([SynthKits.keel]) is where that is decided.
 */
class KeelPresetsTest {

    private class Reading(val preset: KeelPatch) {
        val voice = preset.voice
        val snip: Snip = preset.render()
        val features: Features = FeatureExtractor.extract(snip)
        val heard: DrumClass = Classifier.classify(features).drumClass
        val filed: DrumClass = Keel.drumClassFor(voice, preset.macros)
        val midi: Int = Keel.midiFor(voice, preset.macros.getValue("TUNE"), preset.midi)
        val seconds: Float get() = snip.frameCount.toFloat() / Dsp.RATE
        val label: String get() = "$voice ${preset.name}"
    }

    private companion object {
        val choking = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.CLAP, DrumClass.TOM)

        val readings: List<Reading> by lazy { KeelPresets.all().parallelStream().map { Reading(it) }.toList() }

        private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun noteName(midi: Int): String = noteNames[midi % 12] + (midi / 12 - 1)

        fun f2(x: Number): String = "%.2f".format(java.util.Locale.ROOT, x.toDouble())

        /** The seven knobs every KEEL voice has, written out so the roster is held to the engine's own list and not to a copy of it. */
        val knobs = setOf("TUNE", "SETTLE", "WEIGHT", "GRIT", "SLIDE", "LENGTH", "HOLD")

        /** The frozen roster: names in order, per voice (the kit and the phone ask for these by name). */
        val names = mapOf(
            KeelVoice.REDUCE to listOf("ROUND FLOOR", "QUICK SINK", "LOW HULL", "GROWL LINE", "SCOOP UP", "SETTLED HUM"),
            KeelVoice.UPRIGHT to listOf("WARM GUT", "MUTED THUMB", "LONG NECK", "BRIGHT PICK", "HOT WOOD", "BOWED NOTE"),
            KeelVoice.BELLY to listOf("SOFT PILLOW", "DEEP CUSHION", "TOOTH MARK", "SLOW MELT", "CLIMB IN", "WARM HUSH"),
        )

        /** The note each preset's comment in [KeelPresets] names. */
        val notes = mapOf(
            "ROUND FLOOR" to "C2", "QUICK SINK" to "G1", "LOW HULL" to "C1", "GROWL LINE" to "F1",
            "SCOOP UP" to "D#2", "SETTLED HUM" to "C2",
            "WARM GUT" to "C2", "MUTED THUMB" to "A#1", "LONG NECK" to "D#1", "BRIGHT PICK" to "G2",
            "HOT WOOD" to "C2", "BOWED NOTE" to "C2",
            "SOFT PILLOW" to "C2", "DEEP CUSHION" to "C1", "TOOTH MARK" to "G1", "SLOW MELT" to "D#2",
            "CLIMB IN" to "A#1", "WARM HUSH" to "C2",
        )

        /** The three held loops; every other preset is a one-shot. */
        val loops = setOf("SETTLED HUM", "BOWED NOTE", "WARM HUSH")

        /** C2, the only pitch where the held bow was heard. */
        const val LOOP_MIDI = 36
    }

    @Test
    fun `preset names are uppercase, short, unique per voice, and the frozen roster in order`() {
        for (voice in KeelVoice.entries) {
            val got = KeelPresets.forVoice(voice).map { it.name }
            assertEquals(6, got.size, "$voice should ship 6 presets, has ${got.size}")
            assertEquals(got.toSet().size, got.size, "$voice has duplicate preset names: $got")
            for (name in got) {
                assertTrue(name.length <= 14, "$voice/$name is longer than 14 chars")
                assertEquals(name.uppercase(), name, "$voice/$name is not uppercase")
            }
            assertEquals(names.getValue(voice), got, "$voice's roster")
            for (preset in KeelPresets.forVoice(voice)) assertEquals(voice, preset.voice, "${preset.name} is filed under the wrong voice")
        }
        val every = KeelPresets.all().map { it.name }
        assertEquals(every.toSet().size, every.size, "a name is used by two voices: $every")
    }

    @Test
    fun `every preset names every knob, each finite and in 0 to 1, and TUNE sits on a whole semitone`() {
        for (preset in KeelPresets.all()) {
            assertEquals(knobs, preset.macros.keys, "${preset.name} leaves a knob at its default or names one KEEL does not have")
            assertEquals(Keel.macrosFor(preset.voice).map { it.name }.toSet(), preset.macros.keys, "${preset.name} disagrees with the voice's own knobs")
            for ((knob, value) in preset.macros) {
                assertTrue(value.isFinite() && value in 0f..1f, "${preset.name}: $knob is $value")
            }
            val semitones = preset.macros.getValue("TUNE") * Keel.TUNE_SEMITONES
            assertTrue(abs(semitones - semitones.roundToInt()) < 0.01f, "${preset.name}: TUNE ${preset.macros["TUNE"]} is ${f2(semitones)} semitones, not a whole step")
        }
    }

    @Test
    fun `every preset round-trips through json unchanged`() {
        for (preset in KeelPresets.all()) {
            val restored = KeelPatch.fromJsonValue(preset.toJsonValue())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
        }
    }

    @Test
    fun `every preset lands on the note its comment names`() {
        for (r in readings) {
            assertEquals(notes.getValue(r.preset.name), noteName(r.midi), "${r.label} sounds ${noteName(r.midi)}")
        }
        assertEquals(KeelPresets.all().map { it.name }.toSet(), notes.keys, "a preset has no note written down, or a note has no preset")
    }

    @Test
    fun `the fifteen one-shots are filed TONAL and the three loops LOOP`() {
        for (r in readings) {
            val want = if (r.preset.name in loops) DrumClass.LOOP else DrumClass.TONAL
            assertEquals(want, r.filed, "${r.label} is filed ${r.filed}")
            assertEquals(want == DrumClass.LOOP, r.preset.macros.getValue("HOLD") >= 0.85f, "${r.label}: HOLD and the loop disagree")
        }
        assertEquals(15, readings.count { it.filed == DrumClass.TONAL })
        assertEquals(3, readings.count { it.filed == DrumClass.LOOP })
    }

    @Test
    fun `one-shots render under 1_5 s and loops about 3 s, every render finite with a peak between 0_01 and 1`() {
        val period = Dsp.RATE / Keys.midiHz(LOOP_MIDI)
        for (r in readings) {
            val snip = r.snip
            assertTrue(snip.frameCount > 0, "${r.label} rendered nothing")
            assertTrue(snip.samples.all { it.isFinite() }, "${r.label} produced non-finite samples")
            val peak = snip.peak()
            assertTrue(peak in 0.01f..1f, "${r.label} has peak $peak")
            if (r.preset.name in loops) {
                val wanted = Keel.LOOP_SECONDS * Dsp.RATE
                assertTrue(abs(snip.frameCount - wanted) <= period, "${r.label} is ${f2(r.seconds)} s (${snip.frameCount} frames), not 3.0 s to within one period of C2 (${f2(period)} frames)")
            } else {
                // 1.5 s is the classifier's line between a one-shot and a loop.
                assertTrue(r.seconds < 1.5f, "${r.label} is ${f2(r.seconds)} s, past the one-shot line")
            }
        }
    }

    @Test
    fun `the classifier never hears a drum with a choke group`() {
        for (r in readings) {
            println(
                "KEEL PRESET ${r.label} ${noteName(r.midi)} len=${f2(r.seconds)}s filed=${r.filed} heard=${r.heard} " +
                    "low=${f2(r.features.lowRatio)} high=${f2(r.features.highRatio)} decay=${r.features.decayMs.roundToInt()}ms",
            )
            assertTrue(r.heard !in choking, "${r.label} classified as ${r.heard}, a real drum's own choke group")
        }
    }

    @Test
    fun `SLIDE stays at 0_5 or under, because a slide from above was never heard`() {
        for (preset in KeelPresets.all()) {
            val slide = preset.macros.getValue("SLIDE")
            assertTrue(slide <= 0.5f, "${preset.name}: SLIDE $slide starts above the note and slides down into it")
        }
    }

    @Test
    fun `all three loops sit at C2, the only pitch where the held bow was heard`() {
        val held = readings.filter { it.preset.name in loops }
        assertEquals(loops, held.map { it.preset.name }.toSet())
        for (r in held) assertEquals(LOOP_MIDI, r.midi, "${r.label} is at ${noteName(r.midi)}, not C2")
    }

    @Test
    fun `no preset name is an engine tag, a voice's or a rack section's`() {
        // Every engine tag comes from the Patch registry, so an engine added later cannot be forgotten here.
        val taken = PatchTypes.engines + FxChain.SECTION_NAMES.map { it.uppercase() } + KeelVoice.entries.map { it.name }
        assertTrue(taken.size > 30, "the list of taken names lost its engines or its rack sections")
        assertTrue(KeelPatch.ENGINE in taken, "KEEL's own tag is not in the taken list")
        for (preset in KeelPresets.all()) {
            val words = preset.name.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
            val clash = words.filter { it in taken }.toSet()
            assertTrue(clash.isEmpty(), "${preset.name} names $clash, which is an engine, a voice or a rack section")
        }
    }

    @Test
    fun `no preset name references a real instrument or its maker`() {
        val offenders = KeelPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real maker: ${offenders.map { it.name }}")
    }

    @Test
    fun `every preset lands dry`() {
        for (preset in KeelPresets.all()) {
            assertNull(Presets.landingFor(preset.engine, preset.voiceName, preset.macros), "${preset.voice} ${preset.name} lands with a rack chain")
        }
    }

    @Test
    fun `presets spread out rather than cluster`() {
        for (voice in KeelVoice.entries) {
            val presets = KeelPresets.forVoice(voice)
            for ((i, a) in presets.withIndex()) for (b in presets.drop(i + 1)) {
                val d = PresetTestSupport.rmsDistance(a.macros, b.macros)
                assertTrue(d > 0.05f, "$voice: ${a.name} and ${b.name} are nearly the same sound (${"%.3f".format(java.util.Locale.ROOT, d)})")
            }
        }
    }

    @Test
    fun `the dispatcher knows KEEL`() {
        for (voice in KeelVoice.entries) assertEquals(KeelPresets.forVoice(voice), Presets.forVoice("KEEL", voice.name))
        assertEquals(KeelPresets.forVoice(KeelVoice.UPRIGHT).first(), Presets.byName("KEEL", "UPRIGHT", "WARM GUT"))
        assertTrue(Presets.all().containsAll(KeelPresets.all()))
    }
}
