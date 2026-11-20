package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Candidate voices for listening round bore-voices-1 ([BoreCandidates]): HOLLOW (a reed on a closed cylinder), SHAWM (a double reed
 * and two formants), SHAKU (a jet with its edge noise heard and a bend into the note), PANPIPE (a jet on a closed pipe) and VESSEL
 * (a jet across one Helmholtz cavity). Five for four places: one was the spare. The owner kept all five on 2026-11-20 (bore-voices-1,
 * then touch-bore-1 for the touch), so they are offered ([Bore.KEPT_VOICES]); FLUTE's and SAX's renders are pinned below against the build
 * before this round.
 *
 * Touch is the held keys' law ([BoreKeys.SOFT_BREATH_SHARE]): BREATH scaled from 0.45 of itself at the softest to all of it.
 */
class BoreCandidateVoicesTest {
    private val M = CandidateVoiceMeasures
    /** The five built for bore-voices-1 and kept on 2026-11-20. */
    private val candidates = listOf(BoreVoice.HOLLOW, BoreVoice.SHAWM, BoreVoice.SHAKU, BoreVoice.PANPIPE, BoreVoice.VESSEL)

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * Dsp.RATE).toInt()
        val b = minOf((to * Dsp.RATE).toInt(), x.size)
        return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
    }
    private fun low(voice: BoreVoice) = Bore.rootMidi(voice)
    private fun top(voice: BoreVoice) = Bore.rootMidi(voice) + Bore.TUNE_SEMITONES
    private fun plays(voice: BoreVoice, midi: Int) = midi in low(voice)..top(voice)
    private fun macros(voice: BoreVoice, midi: Int, touch: Float, extra: Map<String, Float> = emptyMap()): Map<String, Float> {
        val m = Bore.defaults(voice) + ("TUNE" to (midi - low(voice)) / Bore.TUNE_SEMITONES.toFloat()) + extra
        return m + ("BREATH" to m.getValue("BREATH") * Dsp.lin(touch, BoreKeys.SOFT_BREATH_SHARE, 1f))
    }
    private fun render(voice: BoreVoice, midi: Int = low(voice) + 12, touch: Float = 1f, extra: Map<String, Float> = emptyMap()) =
        Bore.render(voice, macros(voice, midi, touch, extra)).samples

    @Test
    fun `the new voices are appended after the first two, offered, and each has its one heard preset`() {
        assertEquals(BoreMeasure.FIRST_VOICES + candidates, BoreVoice.entries, "the new voices are appended: saved names and ordinals do not move")
        assertEquals(listOf(0, 1), BoreMeasure.FIRST_VOICES.map { it.ordinal })
        assertEquals(BoreVoice.entries, Bore.KEPT_VOICES, "every voice is offered")
        assertEquals(listOf(45, 55, 62, 60, 60), candidates.map { low(it) })
        val names = listOf("LOW WOOD REED", "PINCHED REED", "BAMBOO BREATH", "CANE PIPES", "ROUND CLAY")
        for ((voice, name) in candidates.zip(names)) {
            // The one preset is the note the owner heard as touch-bore-1's hard note: the defaults an octave over the root, held 2.5 s.
            val preset = BorePresets.forVoice(voice).single()
            assertEquals(name, preset.name)
            assertEquals(preset, Presets.byName("BORE", voice.name, name))
            assertContentEquals(render(voice, extra = mapOf("HOLD" to preset.macros.getValue("HOLD"))), preset.render().samples, "$voice: the preset is not the heard note")
            assertEquals(2.5, Bore.renderFrames(Bore.settled(preset.macros, voice), voice).toDouble() / Dsp.RATE, .35, "$voice: the preset is not the 2.5 s note")
            assertEquals(Bore.macrosFor(BoreVoice.SAX).map { it.name }, Bore.macrosFor(voice).map { it.name })
            val patch = BorePatch("Candidate", voice, Bore.defaults(voice) + ("LIP" to .3f))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()), "$voice does not survive saving")
            assertContentEquals(patch.render().samples, Patches.fromJsonText(patch.toJsonText()).render().samples)
            assertEquals(Bore.renderFrames(patch.macros, voice), patch.render().frameCount, "$voice: the predicted length is not the render's")
        }
    }

    @Test
    fun `candidates render finite, bounded, without a click or a cut, and the same twice`() {
        val allowed = setOf(DrumClass.TONAL, DrumClass.PERC, DrumClass.LOOP)
        val strays = ArrayList<String>()
        for (voice in candidates) {
            for (midi in listOf(low(voice), low(voice) + 12, top(voice))) for (touch in listOf(.3f, .7f, 1f)) {
                val x = render(voice, midi, touch)
                val name = "$voice MIDI$midi touch $touch"
                assertTrue(x.all { it.isFinite() } && x.maxOf { abs(it) } <= .991f, name)
                assertTrue(abs(x[0]) < 5e-3f, "$name starts open: ${x[0]}")
                assertTrue((0 until 11).maxOf { abs(x[it]) } < .05f * x.maxOf { abs(it) }, "$name starts with a click")
                val end = x.size / 44100.0
                assertTrue(rms(x, end - .02, end) < .05 * rms(x, .03, .3), "$name ends abruptly")
                val heard = Classifier.classify(Snip(x, channels = 1, sampleRate = Dsp.RATE)).drumClass
                if (heard !in allowed) strays += "$name became $heard"
            }
            assertContentEquals(Bore.render(voice).samples, Bore.render(voice).samples, "$voice is not deterministic")
        }
        assertTrue(strays.isEmpty(), "candidates left the melodic classes:\n" + strays.joinToString("\n"))
    }

    @Test
    fun `candidates sound the played note`() {
        val wrong = ArrayList<String>()
        for (voice in candidates) for (midi in listOf(low(voice), low(voice) + 12, top(voice))) {
            val x = render(voice, midi)
            val root = M.rootCents(x, hz(midi), .15, .4)
            val share = M.rootShare(x, hz(midi), .15, .4)
            println("BORE $voice MIDI$midi root %+.0f cents (share %.2f)".format(root, share))
            if (abs(root) > 15.0) wrong += "$voice MIDI$midi root is $root cents off"
            if (share <= ROOT_SHARE) wrong += "$voice MIDI$midi has a weak root: $share"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `every candidate is spectrally distinct from every other voice wherever their ranges meet`() {
        val close = ArrayList<String>()
        val met = HashSet<Pair<BoreVoice, BoreVoice>>()
        for (midi in PROBE_NOTES) {
            val voices = BoreVoice.entries.filter { plays(it, midi) }
            val shapes = voices.associateWith { M.bands(render(it, midi)) }
            for (a in candidates.filter { it in voices }) for (b in voices) if (a != b) {
                val d = M.distance(shapes.getValue(a), shapes.getValue(b))
                met += a to b
                println("BORE third-octave distance MIDI$midi $a/$b = %.1f dB".format(d))
                if (d < M.DISTINCT_DB) close += "$a and $b are only $d dB apart at MIDI $midi"
            }
        }
        assertTrue(close.isEmpty(), close.joinToString("\n"))
        for (a in candidates) for (b in BoreVoice.entries) if (a != b && (low(a)..top(a)).intersect(low(b)..top(b)).isNotEmpty()) {
            assertTrue(a to b in met, "$a was never compared with $b")
        }
    }

    /**
     * The owner on every soft, medium, hard clip of round voices-bore-1: "All sound the same" (level-matched, soft to hard was 1.2
     * to 2.7 dB by third-octave shape, each step 0.6 to 1.8 dB, at one level). Touch now leans the note ([CandidateTouch]): a softer
     * touch is darker, quieter and slower to speak, and the hardest touch (the default BREATH) is the kept sound. The bars are under
     * what this build measures at the clips' touches 0.3, 0.7, 1 (soft to hard 8.6 to 13.3 dB of shape, each step at least 4.2 dB, soft 8.6 to 9.4 dB and medium 4.2 to
     * 4.9 dB quieter than hard, soft reaching half its level 80 to 100 ms later), so a return to "sounds
     * the same" fails.
     */
    @Test
    fun `soft, medium and hard differ clearly in tone, level and onset`() {
        fun onset(x: FloatArray): Double {
            val w = Dsp.RATE / 100
            val env = DoubleArray(x.size / w) { k -> rms(x, k * w / 44100.0, (k + 1) * w / 44100.0) }
            val steady = env.copyOfRange(50, 200).sorted()[75]
            return env.indexOfFirst { it >= .5 * steady } / 100.0
        }
        // Held two and a half seconds, as the soft, medium, hard clips were.
        val held = mapOf("HOLD" to (Bore.LOOP_THRESHOLD * kotlin.math.ln(2.5 / Bore.HOLD_MIN_SECONDS) / kotlin.math.ln(Bore.HOLD_MAX_SECONDS / Bore.HOLD_MIN_SECONDS)).toFloat())
        for (voice in candidates) {
            val x = listOf(.3f, .7f, 1f).map { render(voice, touch = it, extra = held) }
            val shapes = x.map { M.bands(it) }
            val steps = listOf(M.distance(shapes[0], shapes[1]), M.distance(shapes[1], shapes[2]))
            val span = M.distance(shapes[0], shapes[2])
            val levels = x.map { 20 * log10(rms(it, .3, 2.0)) }
            val onsets = x.map { onset(it) }
            println("BORE $voice touch: shape steps %.2f, %.2f dB, soft to hard %.2f dB; level %.1f, %.1f, %.1f dB; half-level onset %.0f, %.0f, %.0f ms"
                .format(steps[0], steps[1], span, levels[0], levels[1], levels[2], 1000 * onsets[0], 1000 * onsets[1], 1000 * onsets[2]))
            assertTrue(steps.all { it > TOUCH_STEP_DB } && span > TOUCH_SPAN_DB, "$voice touch barely changes the tone: $steps, $span")
            assertTrue(levels[2] - levels[0] > TOUCH_SOFT_QUIETER_DB && levels[2] - levels[1] > TOUCH_MEDIUM_QUIETER_DB, "$voice touch levels $levels")
            assertTrue(onsets[0] - onsets[2] > TOUCH_SLOWER_SECONDS, "$voice soft speaks no slower than hard: $onsets")
        }
    }

    /** The hardest touch is the default BREATH: what was heard and kept in the range clips. Under it, the lean starts at once. */
    @Test
    fun `the default touch is the kept sound`() {
        val lines = candidates.map { v -> "$v " + M.hash(render(v)) }
        lines.forEach(::println)
        assertEquals(KEPT_CANDIDATES, M.hash(lines))
        val x = FloatArray(1000) { kotlin.math.sin(it * .1f) }
        assertContentEquals(x.copyOf(), CandidateTouch.touched(x, 440f, CandidateTouch.MIN_DEPTH / 2, BoreCandidates.TOUCH))
    }

    @Test
    fun `a held candidate moves like a player and its held key and LOOP join`() {
        for (voice in candidates) {
            val m = macros(voice, low(voice) + 12, 1f, mapOf("HOLD" to .8f))
            val held = Bore.render(voice, m).samples
            val still = Bore.render(voice, m, Humanize.OFF).samples
            assertNotEquals(M.hash(held), M.hash(still), "$voice: the player moved nothing")
            val levels = (0 until 15).map { 20 * log10(rms(held, 1.0 + .1 * it, 1.1 + .1 * it)) }
            val swing = levels.max() - levels.min()
            println("BORE $voice held level moves %.2f dB over 1.0 to 2.5 s".format(swing))
            assertTrue(swing > .2, "$voice: a held note is static ($swing dB)")
            for (midi in BoreKeys.padMidis(voice).let { listOf(it.first(), it.last()) }) {
                val key = BoreKeys.padMeasured(voice, Bore.defaults(voice), midi, Bore.defaults(voice).getValue("BREATH"))
                Bore.checkJoin("BORE $voice held key $midi", key.join, maxDipDb = Bore.maxDipDb(Bore.HUMAN))
            }
            Bore.renderLoop(voice, Bore.defaults(voice) + ("HOLD" to 1f))
        }
    }

    @Test
    fun `HOLLOW and PANPIPE are odd, VESSEL is one resonance, SHAKU's breath is heard`() {
        fun h(x: FloatArray, f: Double, k: Int) = M.component(x, k * f, .3, .4)
        for (voice in listOf(BoreVoice.HOLLOW, BoreVoice.PANPIPE)) {
            val midi = low(voice) + 12
            val x = render(voice, midi)
            val odd = h(x, hz(midi), 3)
            val even = h(x, hz(midi), 2) + h(x, hz(midi), 4)
            println("BORE $voice third harmonic against the second and fourth: %.4f / %.4f".format(odd, even))
            assertTrue(odd > 3 * even, "$voice is not a closed pipe")
        }
        val v = render(BoreVoice.VESSEL, 72)
        val above = (2..6).sumOf { h(v, hz(72), it) } / h(v, hz(72), 1)
        println("BORE VESSEL harmonics 2 to 6 against the root: %.5f".format(above))
        assertTrue(above < .01, "VESSEL is more than one resonance")
        // SHAKU's edge noise fills the gap between its third and fourth harmonics (1.9 to 2.25 kHz at D5) that a FLUTE leaves nearly
        // empty, measured against each note's own root.
        fun between(x: FloatArray) = (1_900..2_250 step 10).sumOf { M.component(x, it.toDouble(), .4, .5) } / M.component(x, hz(74), .4, .5)
        val shaku = between(render(BoreVoice.SHAKU, 74))
        val flute = between(Bore.render(BoreVoice.FLUTE, Bore.defaults(BoreVoice.FLUTE) + ("TUNE" to 14 / 24f)).samples)
        println("BORE SHAKU breath between harmonics %.5f against FLUTE %.5f".format(shaku, flute))
        assertTrue(shaku > 1.8 * flute, "SHAKU's breath is not heard")
    }

    @Test
    fun `the kept voices render exactly what they rendered before the candidates`() {
        val lines = ArrayList<String>()
        for (v in BoreMeasure.FIRST_VOICES) {
            for (tune in listOf(0f, .5f, 1f)) for (hold in listOf(Bore.DEFAULT_HOLD, .8f)) lines +=
                "BORE $v TUNE $tune HOLD $hold " + M.hash(Bore.render(v, mapOf("TUNE" to tune, "HOLD" to hold)).samples)
            val root = BoreKeys.padMidis(v).first()
            for ((i, note) in BoreKeys.pad(v, Bore.defaults(v), root).withIndex()) lines +=
                "BORE $v held $root layer $i " + M.hash(note.snip.samples)
        }
        assertEquals(16, lines.size)
        assertEquals(KEPT_RENDERS, M.hash(lines), lines.joinToString("\n"))
    }

    private companion object {
        /**
         * SHA-256 over the 16 lines above, taken from the build before this round (claude/arco-bore-humanize, 6a1c248a7). Every preset,
         * default note and LOOP of the kept voices is pinned as well, by HoldHumanizePinsTest.
         */
        const val KEPT_RENDERS = "31d7f07fc3ffac4c58235b733bd2ee68dd363e38412cf8aa30384598f3723ecf"
        const val ROOT_SHARE = .3
        /** Touch bars, each under what this build measures (the test's KDoc) and far over the 0.6 to 1.8 dB steps the owner rejected. */
        const val TOUCH_STEP_DB = 3.5
        const val TOUCH_SPAN_DB = 8.0
        const val TOUCH_SOFT_QUIETER_DB = 7.5
        const val TOUCH_MEDIUM_QUIETER_DB = 3.5
        const val TOUCH_SLOWER_SECONDS = .06
        /** SHA-256 over the candidates' default renders an octave over the root (default macros): the hardest touch. */
        const val KEPT_CANDIDATES = "cb428f79c90fbcd974fd4365d8222ca6336a0bab19da0b1857cedcac49bf1b89"
        /** Notes every pair of voices that share a range meets on: HOLLOW and PANPIPE meet from C4 to A4, SAX and SHAKU from D4. */
        val PROBE_NOTES = listOf(48, 55, 60, 64, 67, 72, 79, 84)
    }
}
