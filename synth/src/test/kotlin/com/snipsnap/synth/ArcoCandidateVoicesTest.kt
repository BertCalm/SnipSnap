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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Candidate voices for listening round arco-voices-1 ([ArcoCandidates]): WHEEL (a wheel and a period-locked chien), BAR (a banded
 * bow on a tuned bar), GLASS (the banded bow on a glass's modes) and VIOL, the spare (a string bowed further from the bridge into a
 * flat box). Nothing here is heard. The kept renders are pinned below against the build before this round.
 *
 * Touch is the held keys' law ([ArcoKeys.SOFT_SHARE]): BOW and GRIP scaled from 0.28 of themselves at the softest to all of
 * themselves at the hardest, the same two macros the soft layer moves.
 */
class ArcoCandidateVoicesTest {
    private val M = CandidateVoiceMeasures
    private val candidates = ArcoVoice.entries.filter { it !in Arco.KEPT_VOICES }

    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun rms(x: FloatArray, from: Double, to: Double): Double {
        val a = (from * Dsp.RATE).toInt()
        val b = minOf((to * Dsp.RATE).toInt(), x.size)
        return sqrt((a until b).sumOf { x[it].toDouble() * x[it] } / (b - a))
    }
    private fun low(voice: ArcoVoice) = Arco.rootMidi(voice)
    private fun top(voice: ArcoVoice) = Arco.rootMidi(voice) + Arco.tuneSemitones(voice)
    private fun plays(voice: ArcoVoice, midi: Int) = midi in low(voice)..top(voice)
    private fun macros(voice: ArcoVoice, midi: Int, touch: Float, extra: Map<String, Float> = emptyMap()): Map<String, Float> {
        val m = Arco.defaults(voice) + ("TUNE" to (midi - low(voice)) / Arco.tuneSemitones(voice).toFloat()) + extra
        val share = Dsp.lin(touch, ArcoKeys.SOFT_SHARE, 1f)
        return m + ("BOW" to m.getValue("BOW") * share) + ("GRIP" to m.getValue("GRIP") * share)
    }
    private fun render(voice: ArcoVoice, midi: Int = low(voice) + 12, touch: Float = 1f, extra: Map<String, Float> = emptyMap()) =
        Arco.render(voice, macros(voice, midi, touch, extra)).samples

    @Test
    fun `candidates are appended and offered nowhere`() {
        assertEquals(listOf(ArcoVoice.WHEEL, ArcoVoice.BAR, ArcoVoice.GLASS, ArcoVoice.VIOL), candidates)
        assertEquals(ArcoVoice.entries.take(3), Arco.KEPT_VOICES)
        assertTrue(ArcoPresets.all().all { it.voice in Arco.KEPT_VOICES }, "a candidate has a preset")
        assertEquals(listOf(50, 53, 60, 48), candidates.map { low(it) })
        for (voice in candidates) {
            assertTrue(ArcoPresets.forVoice(voice).isEmpty(), "$voice has presets")
            assertEquals(Arco.macrosFor(ArcoVoice.CELLO).map { it.name }, Arco.macrosFor(voice).map { it.name })
            val patch = ArcoPatch("Candidate", voice, Arco.defaults(voice) + ("GRIP" to .3f))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()), "$voice does not survive saving")
            assertContentEquals(patch.render().samples, Patches.fromJsonText(patch.toJsonText()).render().samples)
            assertEquals(Arco.renderFrames(voice, patch.macros), patch.render().frameCount, "$voice: the predicted length is not the render's")
        }
    }

    @Test
    fun `candidates render finite, bounded, without a click or a cut, and the same twice`() {
        val allowed = setOf(DrumClass.TONAL, DrumClass.PERC, DrumClass.LOOP)
        fun heard(x: FloatArray) = Classifier.classify(Snip(x, channels = 1, sampleRate = Dsp.RATE)).drumClass
        // The classifier files a low bowed note whose loudest moment comes late as a KICK (its kick rule reads a dark note that falls
        // soon after its peak): the kept voices do it too, CELLO's C2 and SARANGI's D3 at every touch. So a candidate is held to the
        // kept voices' own record on the same grid, not to none.
        val keptStrays = Arco.KEPT_VOICES.associateWith { v ->
            listOf(low(v), low(v) + 12, top(v)).sumOf { midi -> listOf(.3f, .7f, 1f).count { heard(render(v, midi, it)) !in allowed } }
        }
        val bar = keptStrays.values.max()
        println("ARCO kept voices off the melodic classes on the grid: $keptStrays")
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
                val heard = heard(x)
                if (heard !in allowed) strays += "$name became $heard"
            }
            assertContentEquals(Arco.render(voice).samples, Arco.render(voice).samples, "$voice is not deterministic")
        }
        println("ARCO candidates off the melodic classes:\n" + strays.joinToString("\n"))
        for (voice in candidates) {
            val own = strays.count { it.startsWith("$voice ") }
            assertTrue(own <= bar, "$voice left the melodic classes $own times on the grid, the kept voices at most $bar")
        }
    }

    @Test
    fun `candidates sound the played note`() {
        val wrong = ArrayList<String>()
        for (voice in candidates) for (midi in listOf(low(voice), low(voice) + 12, top(voice))) {
            val x = render(voice, midi)
            val root = M.rootCents(x, hz(midi), .15, .4)
            val share = M.rootShare(x, hz(midi), .15, .4)
            println("ARCO $voice MIDI$midi root %+.0f cents (share %.2f)".format(root, share))
            if (abs(root) > 15.0) wrong += "$voice MIDI$midi root is $root cents off"
            if (share <= ROOT_SHARE) wrong += "$voice MIDI$midi has a weak root: $share"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `every candidate is spectrally distinct from every other voice wherever their ranges meet`() {
        val close = ArrayList<String>()
        val met = HashSet<Pair<ArcoVoice, ArcoVoice>>()
        for (midi in PROBE_NOTES) {
            val voices = ArcoVoice.entries.filter { plays(it, midi) }
            val shapes = voices.associateWith { M.bands(render(it, midi)) }
            for (a in candidates.filter { it in voices }) for (b in voices) if (a != b) {
                val d = M.distance(shapes.getValue(a), shapes.getValue(b))
                met += a to b
                println("ARCO third-octave distance MIDI$midi $a/$b = %.1f dB".format(d))
                if (d < M.DISTINCT_DB) close += "$a and $b are only $d dB apart at MIDI $midi"
            }
        }
        assertTrue(close.isEmpty(), close.joinToString("\n"))
        // Every candidate met every voice whose range it shares (ERHU and CELLO share none with each other, and neither is a candidate).
        for (a in candidates) for (b in ArcoVoice.entries) if (a != b && (low(a)..top(a)).intersect(low(b)..top(b)).isNotEmpty()) {
            assertTrue(a to b in met, "$a was never compared with $b")
        }
    }

    /**
     * The owner on every soft, medium, hard clip of round voices-arco-1: "All sound the same" (level-matched, soft to hard was 1.2
     * to 2.7 dB by third-octave shape, each step 0.6 to 1.8 dB, at one level). Touch now leans the note ([CandidateTouch]): a softer
     * touch is darker, quieter and slower to speak, and the hardest touch (the default BOW) is the kept sound. The bars are under
     * what this build measures at the clips' touches 0.3, 0.7, 1 (soft to hard 9.4 to 13.3 dB of shape, each step at least 4.4 dB, soft 8.5 to 10.6 dB and medium 4.2 to
     * 6.1 dB quieter than hard, soft reaching half its level 80 to 150 ms later), so a return to "sounds
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
        val held = mapOf("HOLD" to Arco.holdFor(2.5f))
        for (voice in candidates) {
            val x = listOf(.3f, .7f, 1f).map { render(voice, touch = it, extra = held) }
            val shapes = x.map { M.bands(it) }
            val steps = listOf(M.distance(shapes[0], shapes[1]), M.distance(shapes[1], shapes[2]))
            val span = M.distance(shapes[0], shapes[2])
            val levels = x.map { 20 * log10(rms(it, .3, 2.0)) }
            val onsets = x.map { onset(it) }
            println("ARCO $voice touch: shape steps %.2f, %.2f dB, soft to hard %.2f dB; level %.1f, %.1f, %.1f dB; half-level onset %.0f, %.0f, %.0f ms"
                .format(steps[0], steps[1], span, levels[0], levels[1], levels[2], 1000 * onsets[0], 1000 * onsets[1], 1000 * onsets[2]))
            assertTrue(steps.all { it > TOUCH_STEP_DB } && span > TOUCH_SPAN_DB, "$voice touch barely changes the tone: $steps, $span")
            assertTrue(levels[2] - levels[0] > TOUCH_SOFT_QUIETER_DB && levels[2] - levels[1] > TOUCH_MEDIUM_QUIETER_DB, "$voice touch levels $levels")
            assertTrue(onsets[0] - onsets[2] > TOUCH_SLOWER_SECONDS, "$voice soft speaks no slower than hard: $onsets")
        }
    }

    /** The hardest touch is the default BOW: what was heard and kept in the range clips. Under it, the lean starts at once. */
    @Test
    fun `the default touch is the kept sound`() {
        val lines = candidates.map { v -> "$v " + M.hash(render(v)) }
        lines.forEach(::println)
        assertEquals(KEPT_CANDIDATES, M.hash(lines))
        val x = FloatArray(1000) { kotlin.math.sin(it * .1f) }
        assertContentEquals(x.copyOf(), CandidateTouch.touched(x, 440f, CandidateTouch.MIN_DEPTH / 2, ArcoCandidates.TOUCH))
    }

    @Test
    fun `a held candidate moves like a player and its held key and LOOP close`() {
        for (voice in candidates) {
            val m = macros(voice, low(voice) + 12, 1f, mapOf("HOLD" to Arco.holdFor(3f)))
            val held = Arco.render(voice, m).samples
            val still = Arco.render(voice, m, Arco.BUZZ_LAW, human = Humanize.OFF).samples
            assertNotEquals(M.hash(held), M.hash(still), "$voice: the player moved nothing")
            val levels = (0 until 15).map { 20 * log10(rms(held, 1.0 + .1 * it, 1.1 + .1 * it)) }
            val swing = levels.max() - levels.min()
            println("ARCO $voice held level moves %.2f dB over 1.0 to 2.5 s".format(swing))
            assertTrue(swing > .3, "$voice: a held note is static ($swing dB)")
            for (midi in ArcoKeys.zoneMidis(voice).let { listOf(it.first(), it.last()) }) {
                val key = Arco.renderHeldMeasured(voice, ArcoKeys.macrosFor(voice, Arco.defaults(voice), midi, ArcoKeys.Layer.HARD))
                assertTrue(key.seam < Keys.MAX_SEAM_ERROR, "$voice held key at $midi does not close: ${key.seam}")
            }
            assertTrue(Arco.renderLoopMeasured(voice, Arco.defaults(voice) + ("HOLD" to 1f)).seam < Keys.MAX_SEAM_ERROR, "$voice LOOP")
        }
    }

    @Test
    fun `WHEEL knocks once a period, BAR rings two octaves up, GLASS is the purest`() {
        // The chien: WHEEL's energy near its knock (1.6 to 2.6 kHz) against SARANGI's on the same note, the same bow.
        fun knock(x: FloatArray) = M.bands(x).let { b -> (16..17).sumOf { 10.0.pow(b[it] / 10) } }
        val wheel = knock(render(ArcoVoice.WHEEL, 62))
        val sarangi = knock(Arco.render(ArcoVoice.SARANGI, Arco.defaults(ArcoVoice.SARANGI) + ("TUNE" to 0.5f)).samples)
        println("ARCO WHEEL knock band share %.4f against SARANGI %.4f".format(wheel, sarangi))
        assertTrue(wheel > 1.5 * sarangi, "WHEEL's chien is not heard")
        // The tuned bar's second mode is at 4.02 times the first: the fourth harmonic stands over the third and the fifth.
        val f = hz(65)
        val bar = render(ArcoVoice.BAR, 65)
        val h = (1..5).map { M.component(bar, it * f, .3, .4) }
        println("ARCO BAR harmonics 1 to 5: " + h.joinToString { "%.4f".format(it) })
        assertTrue(h[3] > 5 * h[2] && h[3] > 5 * h[4], "BAR's second mode does not ring")
        // GLASS: the upper harmonics together are under a tenth of a percent of the root.
        val g = render(ArcoVoice.GLASS, 72)
        val gf = hz(72)
        val above = (2..8).sumOf { M.component(g, it * gf, .3, .4) } / M.component(g, gf, .3, .4)
        println("ARCO GLASS harmonics 2 to 8 against the root: %.5f".format(above))
        assertTrue(above < .02, "GLASS is not pure")
    }

    @Test
    fun `the kept voices render exactly what they rendered before the candidates`() {
        val lines = ArrayList<String>()
        for (v in Arco.KEPT_VOICES) {
            for (tune in listOf(0f, .5f, 1f)) for (hold in listOf(Arco.DEFAULT_HOLD, Arco.holdFor(3f))) lines +=
                "ARCO $v TUNE $tune HOLD $hold " + M.hash(Arco.render(v, Arco.defaults(v) + mapOf("TUNE" to tune, "HOLD" to hold)).samples)
            val root = ArcoKeys.zoneMidis(v).first()
            for (layer in ArcoKeys.Layer.entries) lines +=
                "ARCO $v held $root $layer " + M.hash(ArcoKeys.pad(v, Arco.defaults(v), root, layer).snip.samples)
        }
        assertEquals(24, lines.size)
        assertEquals(KEPT_RENDERS, M.hash(lines), lines.joinToString("\n"))
        assertFalse(candidates.any { it in Arco.KEPT_VOICES })
    }

    private companion object {
        /**
         * SHA-256 over the 24 lines above, taken from the build before this round (claude/arco-bore-humanize, 6a1c248a7). Every preset,
         * default note, LOOP and three second note of the kept voices is pinned as well, by HoldHumanizePinsTest.
         */
        const val KEPT_RENDERS = "137490ca590795006d6103f0a351a48a44f5cd34448dc01cf9e8045dafc85ddc"
        const val ROOT_SHARE = .3
        /** Touch bars, each under what this build measures (the test's KDoc) and far over the 0.6 to 1.8 dB steps the owner rejected. */
        const val TOUCH_STEP_DB = 3.5
        const val TOUCH_SPAN_DB = 8.0
        const val TOUCH_SOFT_QUIETER_DB = 7.5
        const val TOUCH_MEDIUM_QUIETER_DB = 3.5
        const val TOUCH_SLOWER_SECONDS = .06
        /** SHA-256 over the candidates' default renders an octave over the root (default macros): the hardest touch. */
        const val KEPT_CANDIDATES = "6093fd58ec718adc705ad127f7ee5bdde2b41c343c908bc5009601a71ec7f4fb"
        /** Notes every pair of voices that share a range meets on: CELLO and GLASS only at C4, ERHU and VIOL from D4 to C5. */
        val PROBE_NOTES = listOf(50, 55, 60, 64, 69, 74, 79)
    }
}
