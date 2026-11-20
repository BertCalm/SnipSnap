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
 * (a jet across one Helmholtz cavity). Five for four places: one is the spare. Nothing here is heard. The kept renders are pinned
 * below against the build before this round.
 *
 * Touch is the held keys' law ([BoreKeys.SOFT_BREATH_SHARE]): BREATH scaled from 0.45 of itself at the softest to all of it.
 */
class BoreCandidateVoicesTest {
    private val M = CandidateVoiceMeasures
    private val candidates = BoreVoice.entries.filter { it !in Bore.KEPT_VOICES }

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
    fun `candidates are appended and offered nowhere`() {
        assertEquals(listOf(BoreVoice.HOLLOW, BoreVoice.SHAWM, BoreVoice.SHAKU, BoreVoice.PANPIPE, BoreVoice.VESSEL), candidates)
        assertEquals(BoreVoice.entries.take(2), Bore.KEPT_VOICES)
        assertTrue(BorePresets.all().all { it.voice in Bore.KEPT_VOICES }, "a candidate has a preset")
        assertEquals(listOf(45, 55, 62, 60, 60), candidates.map { low(it) })
        for (voice in candidates) {
            assertTrue(BorePresets.forVoice(voice).isEmpty(), "$voice has presets")
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

    @Test
    fun `soft, medium and hard differ in tone at the same loudness`() {
        for (voice in candidates) {
            val shapes = listOf(.35f, .7f, 1f).map { M.bands(render(voice, touch = it)) }
            val steps = listOf(M.distance(shapes[0], shapes[1]), M.distance(shapes[1], shapes[2]))
            println("BORE $voice level-matched touch steps: %.2f dB, %.2f dB".format(steps[0], steps[1]))
            assertTrue(steps.all { it > TOUCH_DB }, "$voice touch barely changes the tone: $steps")
        }
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
        for (v in Bore.KEPT_VOICES) {
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
        const val TOUCH_DB = .5
        /** Notes every pair of voices that share a range meets on: HOLLOW and PANPIPE meet from C4 to A4, SAX and SHAKU from D4. */
        val PROBE_NOTES = listOf(48, 55, 60, 64, 67, 72, 79, 84)
    }
}
