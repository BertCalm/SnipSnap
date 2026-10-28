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
 * BALLAST's factory roster: eight per voice, held to the identity, sanity, round-trip, names, blocklist and spread
 * contract every `<Engine>PresetsTest` holds (`ArcoPresetsTest`'s own shape), plus the roster's own claims: each preset
 * sits on the note its comment names, and the classifier never hears a drum in it.
 *
 * These prove the roster is *sound*. They cannot prove it is *good*: nothing in it was listened to, and the audition
 * page is where that is decided.
 */
class BallastPresetsTest {

    private class Reading(val preset: BallastPatch) {
        val voice = preset.voice
        val snip: Snip = preset.render()
        val features: Features = FeatureExtractor.extract(snip)
        val heard: DrumClass = Classifier.classify(features).drumClass
        val filed: DrumClass = Ballast.drumClassFor(voice, preset.macros)
        val midi: Int = Ballast.midiFor(voice, preset.macros.getValue("TUNE"))
        val label: String get() = "$voice ${preset.name}"
    }

    private companion object {
        val choking = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.CLAP, DrumClass.TOM)

        val readings: List<Reading> by lazy { BallastPresets.all().parallelStream().map { Reading(it) }.toList() }

        private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun noteName(midi: Int): String = noteNames[midi % 12] + (midi / 12 - 1)

        fun f2(x: Number): String = "%.2f".format(java.util.Locale.ROOT, x.toDouble())

        /** The frozen roster: names in order, per voice (the kit and the audition page ask for these by name). */
        val names = mapOf(
            BallastVoice.ROOT to listOf("TIGHT BASE", "LOW FRAME", "STONE FLOOR", "SHORT DRAW", "HEAVY MOUNT", "QUIET GLASS", "PLAIN OCTAVES", "NIGHT DRIVE"),
            BallastVoice.WIRE to listOf("LONG STRING", "WIDE OCTAVES", "HELD FRAME", "TAUT WIRES", "UPPER HALO", "BRIDGE HUM", "THIN CABLE", "RESONANT BODY"),
            BallastVoice.GLINT to listOf("GLASS WAKE", "TILE SHIVER", "BRIGHT TAPS", "CLEAR TOPS", "SPARK ROW", "FROST LINE", "SMALL BELLS", "DENSE TILES"),
            BallastVoice.DEEP to listOf("LOWER REPLY", "SUB WEIGHT", "LOOSE MOUNT", "CAVE FLOOR", "FALLING AWAY", "HEAVY FRAME", "LOW WELL", "GREAT HALL"),
            BallastVoice.BLOOM to listOf("DELAYED OPEN", "SLOW CLIMB", "LATE ARRIVAL", "RISING HALO", "SOFT CLOUD", "OPEN FIELD", "WIDE DAWN", "WARM LIFT"),
            BallastVoice.SWARM to listOf("DENSE RATTLE", "CROWD GLASS", "BUSY TILES", "TILE STORM", "LOUD ROOM", "LOOSE PILE", "NOISY FRAME", "SHAKEN BOX"),
        )

        /** The note each preset's comment in [BallastPresets] names. */
        val notes = mapOf(
            "TIGHT BASE" to "C1", "LOW FRAME" to "G1", "STONE FLOOR" to "C2", "SHORT DRAW" to "C2",
            "HEAVY MOUNT" to "F1", "QUIET GLASS" to "C2", "PLAIN OCTAVES" to "G2", "NIGHT DRIVE" to "C1",
            "LONG STRING" to "C2", "WIDE OCTAVES" to "C2", "HELD FRAME" to "G1", "TAUT WIRES" to "G2",
            "UPPER HALO" to "C2", "BRIDGE HUM" to "C1", "THIN CABLE" to "C3", "RESONANT BODY" to "D2",
            "GLASS WAKE" to "C2", "TILE SHIVER" to "G2", "BRIGHT TAPS" to "C3", "CLEAR TOPS" to "C2",
            "SPARK ROW" to "G1", "FROST LINE" to "D2", "SMALL BELLS" to "A2", "DENSE TILES" to "C2",
            "LOWER REPLY" to "C1", "SUB WEIGHT" to "C1", "LOOSE MOUNT" to "F1", "CAVE FLOOR" to "G1",
            "FALLING AWAY" to "C2", "HEAVY FRAME" to "C1", "LOW WELL" to "D#1", "GREAT HALL" to "C2",
            "DELAYED OPEN" to "C2", "SLOW CLIMB" to "G1", "LATE ARRIVAL" to "D2", "RISING HALO" to "G2",
            "SOFT CLOUD" to "D2", "OPEN FIELD" to "C2", "WIDE DAWN" to "F1", "WARM LIFT" to "C1",
            "DENSE RATTLE" to "C2", "CROWD GLASS" to "G2", "BUSY TILES" to "C2", "TILE STORM" to "G1",
            "LOUD ROOM" to "C1", "LOOSE PILE" to "C2", "NOISY FRAME" to "F1", "SHAKEN BOX" to "C3",
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
            assertEquals(Ballast.renderFrames(r.voice, r.preset.macros), snip.frameCount, "${r.label}: the filed length is not the rendered length")
        }
    }

    @Test
    fun `every preset classifies as a pitched note, never a drum with a choke group`() {
        for (r in readings) {
            println(
                "BALLAST PRESET ${r.label} ${noteName(r.midi)} len=${f2(r.snip.frameCount.toFloat() / Dsp.RATE)}s filed=${r.filed} heard=${r.heard} " +
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
        for (preset in BallastPresets.all()) {
            val restored = BallastPatch.fromJsonText(preset.toJsonText())
            assertEquals(preset, restored, "${preset.name} did not round-trip")
            assertContentEquals(preset.render().samples, restored.render().samples, "${preset.name} rendered differently after a round-trip")
        }
    }

    @Test
    fun `preset names are uppercase, short, unique per voice, and the frozen roster in order`() {
        for (voice in BallastVoice.entries) {
            val got = BallastPresets.forVoice(voice).map { it.name }
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
            BallastPatch.ENGINE, MercuryPatch.ENGINE, GyrePatch.ENGINE, MagnetPatch.ENGINE,
        ) + FxChain.SECTION_NAMES.map { it.uppercase() } + BallastVoice.entries.map { it.name }
        assertTrue(taken.size > 30, "the list of taken names lost its rack sections")
        for (preset in BallastPresets.all()) {
            val words = preset.name.split(Regex("[^A-Z0-9]+")).filter { it.isNotEmpty() }
            val clash = words.filter { it in taken }
            assertTrue(clash.isEmpty(), "${preset.name} names $clash, which is an engine, a voice or a rack section")
        }
    }

    @Test
    fun `no preset name references a real instrument or its maker`() {
        val offenders = BallastPresets.all().filter { PresetTestSupport.trademarkBlocklist.containsMatchIn(it.name) }
        assertTrue(offenders.isEmpty(), "names that read as a real maker: ${offenders.map { it.name }}")
    }

    @Test
    fun `presets spread out rather than cluster`() {
        for (voice in BallastVoice.entries) {
            val presets = BallastPresets.forVoice(voice)
            for ((i, a) in presets.withIndex()) for (b in presets.drop(i + 1)) {
                val d = PresetTestSupport.rmsDistance(a.macros, b.macros)
                assertTrue(d > 0.05f, "$voice: ${a.name} and ${b.name} are nearly the same sound (${"%.3f".format(java.util.Locale.ROOT, d)})")
            }
        }
    }

    @Test
    fun `every preset names every macro, so the roster is the full knob and not a default in disguise`() {
        for (preset in BallastPresets.all()) {
            assertEquals(Ballast.macrosFor(preset.voice).map { it.name }.toSet(), preset.macros.keys, "${preset.name} leaves a macro at its default")
        }
    }

    @Test
    fun `every preset lands on the note its comment names`() {
        for (r in readings) {
            val exact = r.preset.macros.getValue("TUNE") * Ballast.TUNE_SEMITONES
            assertTrue(abs(exact - exact.roundToInt()) < 0.05f, "${r.label}: TUNE ${r.preset.macros["TUNE"]} is ${f2(exact)} semitones, not on a note")
            assertEquals(notes.getValue(r.preset.name), noteName(r.midi), "${r.label} sounds ${noteName(r.midi)}")
        }
    }

    @Test
    fun `the dispatcher knows BALLAST`() {
        for (voice in BallastVoice.entries) assertEquals(BallastPresets.forVoice(voice), Presets.forVoice("BALLAST", voice.name))
        assertEquals(BallastPresets.forVoice(BallastVoice.WIRE).first(), Presets.byName("BALLAST", "WIRE", "LONG STRING"))
        assertTrue(Presets.all().containsAll(BallastPresets.all()))
    }
}
