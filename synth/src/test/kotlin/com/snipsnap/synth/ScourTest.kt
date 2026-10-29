package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.json.JsonException
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mechanics, plate and house audio evidence for SCOUR R1.1; the audition supplies the listening verdict. */
class ScourTest {
    private val drums = setOf(
        DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM,
    )
    private val timbral = listOf("RPM", "SHOT", "HARDEN", "RING", "HOLD")

    private fun closeTick(trace: Scour.Trace) =
        ((trace.closeSeconds * Scour.INTERNAL_RATE).toInt() / Scour.CTRL).coerceAtMost(trace.smoothed.lastIndex)

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until to) e += a[i].toDouble() * a[i]
        return if (to > from) sqrt(e / (to - from)) else 0.0
    }

    /** Timbre at matched RMS, so a macro that only moves level cannot pass. */
    private fun normalizedDifference(a: FloatArray, b: FloatArray): Double {
        val end = minOf(a.size, b.size)
        val first = (.05 * Dsp.RATE).toInt()
        val ra = rms(a, first, end)
        val rb = rms(b, first, end)
        if (ra < 1e-10 || rb < 1e-10) return 0.0
        var d = 0.0
        for (i in first until end) {
            val x = a[i] / ra - b[i] / rb
            d += x * x
        }
        return sqrt(d / (end - first))
    }

    /** Once nothing drives the plate, every 50 ms window is quieter than the first one after the drive. */
    private fun ringsDown(out: FloatArray, from: Int): Boolean {
        val window = Dsp.RATE / 20
        val reference = rms(out, from, from + window)
        var i = from + window
        while (i + window <= out.size - Dsp.RATE / 100) {
            if (rms(out, i, i + window) > reference * 1.05) return false
            i += window
        }
        return true
    }

    @Test
    fun `coverage stays inside zero to one without a clamp and rises with SHOT and velocity`() {
        for (voice in ScourVoice.entries) {
            for (shot in listOf(0f, 1f)) for (hold in listOf(0f, .98f)) for (velocity in listOf(.2f, 1f)) {
                val trace = Scour.mechanics(voice, mapOf("SHOT" to shot, "HOLD" to hold), velocity)
                assertTrue(trace.coverage.all { it in 0.0..1.0 && it < 1.0 }, "$voice SHOT $shot HOLD $hold: coverage left [0, 1)")
                assertTrue(trace.smoothed.all { it in 0.0..1.0 }, "$voice: smoothed coverage left [0, 1]")
            }
            val atClose = listOf(0f, .25f, .5f, .75f, 1f).map { shot ->
                val t = Scour.mechanics(voice, mapOf("SHOT" to shot))
                t.coverage[closeTick(t)]
            }
            assertTrue(atClose.zipWithNext().all { (a, b) -> b > a }, "$voice: coverage at close is not monotonic in SHOT: $atClose")
            assertTrue(atClose.last() > .5, "$voice: a full blast leaves little coverage: ${atClose.last()}")
            val soft = Scour.mechanics(voice, emptyMap(), .3f)
            val hard = Scour.mechanics(voice, emptyMap(), 1f)
            assertTrue(soft.coverage[closeTick(soft)] < hard.coverage[closeTick(hard)], "$voice: velocity does not reach the coverage")
        }
    }

    @Test
    fun `turning SHOT stretches one schedule and keeps mean shot power by the law`() {
        for (voice in ScourVoice.entries) {
            val sparse = Scour.mechanics(voice, mapOf("SHOT" to .4f))
            val dense = Scour.mechanics(voice, mapOf("SHOT" to .9f))
            assertTrue(dense.shotFrames.size > 3 * sparse.shotFrames.size, "$voice: SHOT did not raise the shot count")
            for (i in sparse.shotFrames.indices) {
                // The i-th shot keeps its own draws: its distance from the first shot scales by the rate ratio,
                // to within the two frames of quantisation.
                val a0 = (sparse.shotFrames[i] - sparse.shotFrames[0]) * sparse.shotRate
                val b0 = (dense.shotFrames[i] - dense.shotFrames[0]) * dense.shotRate
                assertTrue(abs(a0 - b0) <= 2 * dense.shotRate, "$voice shot $i was reshuffled, not stretched")
                assertEquals(sparse.shotAim[i], dense.shotAim[i], "$voice shot $i changed its aim")
                assertEquals(sparse.shotCoverage[i], dense.shotCoverage[i], "$voice shot $i changed its dent")
                val a = sparse.shotAmplitude[i] * sparse.shotAmplitude[i] * sparse.shotRate
                val b = dense.shotAmplitude[i] * dense.shotAmplitude[i] * dense.shotRate
                assertTrue(abs(a - b) <= 1e-9 * a, "$voice shot $i: λ·amp² is not constant ($a vs $b)")
            }
        }
    }

    @Test
    fun `HARDEN never reaches the mechanics, only the plate`() {
        for (voice in ScourVoice.entries) {
            val soft = Scour.mechanics(voice, mapOf("HARDEN" to 0f, "SHOT" to .8f))
            val hard = Scour.mechanics(voice, mapOf("HARDEN" to 1f, "SHOT" to .8f))
            assertContentEquals(soft.shotFrames, hard.shotFrames, "$voice: HARDEN moved the shots")
            val ticks = minOf(soft.coverage.size, hard.coverage.size)
            assertContentEquals(soft.coverage.copyOf(ticks), hard.coverage.copyOf(ticks), "$voice: HARDEN moved the coverage")
        }
    }

    @Test
    fun `the root never moves while the upper modes stretch with HARDEN and stay under the ceiling`() {
        for (voice in ScourVoice.entries) {
            val f0 = Scour.frequencyFor(voice, .5f)
            val tops = listOf(0f, .5f, 1f).map { harden ->
                val played = Scour.play(voice, mapOf("HARDEN" to harden, "SHOT" to .8f), record = true)
                val modes = requireNotNull(played.modeHz)
                assertTrue(modes[0].all { it == f0 }, "$voice HARDEN $harden moved the root")
                modes.last()[closeTick(played.trace)]
            }
            assertTrue(tops.zipWithNext().all { (a, b) -> b > a }, "$voice: the top mode does not climb with HARDEN: $tops")
            // SHOT × HARDEN: without shots there is little to harden.
            val bare = Scour.play(voice, mapOf("HARDEN" to 1f, "SHOT" to 0f), record = true)
            val bareTop = requireNotNull(bare.modeHz).last()[closeTick(bare.trace)]
            assertTrue(bareTop - tops[0] < .2 * (tops[2] - tops[0]), "$voice: HARDEN moved the plate without shots")
            val bright = Scour.play(voice, mapOf("TUNE" to 1f, "HARDEN" to 1f, "SHOT" to 1f, "HOLD" to .98f), record = true)
            assertTrue(requireNotNull(bright.modeHz).all { m -> m.all { it < 18_000f } }, "$voice: a mode passed the ceiling")
        }
    }

    @Test
    fun `the plate rings down once the blast and the roller stop, and the check can fail`() {
        val cases = listOf(emptyMap(), mapOf("SHOT" to 1f, "RPM" to 1f, "HARDEN" to 1f), mapOf("SHOT" to 1f, "HARDEN" to 1f, "RING" to 1f))
        for (voice in ScourVoice.entries) for (macros in cases) {
            val trace = Scour.mechanics(voice, macros)
            val out = Scour.render(voice, macros).samples
            val quiet = ((maxOf(trace.closeSeconds, trace.stopSeconds) + .01) * Dsp.RATE).toInt()
            assertTrue(ringsDown(out, quiet), "$voice $macros: the passive plate gained energy")
            // The same check over the decay played backwards (a swell) must fail, or it proves nothing.
            assertFalse(ringsDown(out.copyOfRange(quiet, out.size).reversedArray(), 0), "$voice: the ring-down check cannot fail")
        }
    }

    @Test
    fun `renders are mono finite levelled DC free outside the drum set and as long as filed`() {
        val cases = ScourVoice.entries.flatMap { voice ->
            listOf(
                emptyMap(), mapOf("TUNE" to 0f), mapOf("TUNE" to 1f),
                mapOf("TUNE" to 1f, "RPM" to 1f, "SHOT" to 1f, "HARDEN" to 1f, "RING" to 1f),
                mapOf("TUNE" to 0f, "RPM" to 0f, "SHOT" to 0f, "HARDEN" to 0f, "RING" to 0f),
                // The shortest ring there is: RING 0 at full hardening, which the floor holds at 1.6 s.
                mapOf("TUNE" to 1f, "RPM" to 0f, "SHOT" to 1f, "HARDEN" to 1f, "RING" to 0f),
                mapOf("HOLD" to .98f, "SHOT" to 1f),
            ).map { voice to it }
        }
        for ((voice, macros) in cases) {
            val snip = Scour.render(voice, macros)
            assertEquals(1, snip.channels)
            assertEquals(Dsp.RATE, snip.sampleRate)
            assertTrue(snip.samples.all { it.isFinite() }, "$voice $macros")
            assertTrue(snip.peak() in 0.01f..0.991f, "$voice $macros peak ${snip.peak()}")
            assertTrue(abs(snip.samples.average()) < 0.005, "$voice $macros DC ${snip.samples.average()}")
            assertEquals(Scour.renderFrames(voice, macros), snip.samples.size, "$voice $macros length")
            val filed = Scour.drumClassFor(voice, macros)
            val heard = Classifier.classify(snip).drumClass
            assertTrue(heard !in drums && filed !in drums, "$voice $macros heard $heard, filed $filed")
            assertEquals(filed, heard, "$voice $macros: the classifier and drumClassFor disagree")
            assertEquals(DrumClass.LOOP, filed, "$voice $macros: every SCOUR render passes 1.5 s")
            // The class is filed from velocity 1 and 0; a soft hit's own render must agree with it.
            val soft = Scour.render(voice, macros, .2f)
            assertEquals(filed, Classifier.classify(soft).drumClass, "$voice $macros: a soft hit changes the class")
            assertTrue(soft.samples.size >= Dsp.RATE * Scour.MIN_RENDER_SECONDS - 1, "$voice $macros: a soft hit falls under the floor")
        }
    }

    @Test
    fun `the render length moves with velocity one way only and RING decides which way`() {
        for (voice in ScourVoice.entries) for (ring in listOf(0f, .25f, .75f, 1f)) {
            val m = mapOf("SHOT" to .8f, "HARDEN" to 1f, "RING" to ring)
            val frames = listOf(0f, .2f, .4f, .6f, .8f, 1f).map { Scour.renderFrames(voice, m, it) }
            val steps = frames.zipWithNext { a, b -> b - a }
            if (ring < .5f) assertTrue(steps.all { it <= 0 } && frames.first() > frames.last(), "$voice RING $ring: a harder hit is not shorter: $frames")
            else assertTrue(steps.all { it >= 0 } && frames.last() > frames.first(), "$voice RING $ring: a harder hit is not longer: $frames")
        }
    }

    @Test
    fun `the requested note is the detected root at the onset and in the tail at every corner`() {
        val corners = listOf(0f, 1f)
        for (voice in ScourVoice.entries) {
            val cases = corners.flatMap { tune -> corners.flatMap { rpm -> corners.flatMap { shot -> corners.flatMap { harden ->
                corners.map { ring -> mapOf("TUNE" to tune, "RPM" to rpm, "SHOT" to shot, "HARDEN" to harden, "RING" to ring) }
            } } } } + listOf(0f, .25f, .5f, .75f, 1f).map { mapOf("TUNE" to it) }
            for (macros in cases) {
                val snip = Scour.render(voice, macros)
                val expected = Scour.frequencyFor(voice, macros.getValue("TUNE")).toDouble()
                val close = Scour.mechanics(voice, macros).closeSeconds.toFloat()
                for ((window, pitch) in listOf("onset" to Pitch.detect(snip), "tail" to Pitch.detect(snip, fromSec = close + .05f))) {
                    requireNotNull(pitch) { "$voice $macros: no root at the $window" }
                    val cents = FineTuning.cents(pitch.hz.toDouble(), expected)
                    assertTrue(abs(cents) < 50.0, "$voice $macros: the $window root is $cents cents off")
                    assertTrue(pitch.confidence >= .3f, "$voice $macros: $window confidence ${pitch.confidence}")
                }
            }
        }
    }

    @Test
    fun `every timbral macro changes the sound, velocity changes more than level, and an unknown key changes nothing`() {
        for (voice in ScourVoice.entries) {
            for (macro in timbral) {
                val low = Scour.render(voice, mapOf(macro to 0f)).samples
                val high = Scour.render(voice, mapOf(macro to if (macro == "HOLD") .98f else 1f)).samples
                val d = normalizedDifference(low, high)
                assertTrue(d > .02, "$voice $macro changes only level or nothing: $d")
            }
            val soft = Scour.render(voice, emptyMap(), .3f).samples
            val hard = Scour.render(voice, emptyMap(), 1f).samples
            assertTrue(normalizedDifference(soft, hard) > .02, "$voice: velocity is only a gain")
            assertContentEquals(Scour.render(voice).samples, Scour.render(voice, mapOf("UNKNOWN" to .7f)).samples)
            assertContentEquals(Scour.render(voice).samples, Scour.render(voice, mapOf("SHOT" to Float.NaN)).samples,
                "$voice: a non-finite macro must fall back to its default")
        }
    }

    @Test
    fun `RING needs hardening, moves only the ring, and lengthens it in order`() {
        for (voice in ScourVoice.entries) {
            val bare = mapOf("SHOT" to .8f, "HARDEN" to 0f)
            assertContentEquals(Scour.render(voice, bare + ("RING" to 0f)).samples, Scour.render(voice, bare + ("RING" to 1f)).samples,
                "$voice: with nothing hardened, RING must change nothing")
            val hard = mapOf("SHOT" to .8f, "HARDEN" to 1f)
            val rings = listOf(0f, .25f, .5f, .75f, 1f)
            val lengths = rings.map { Scour.lengthSeconds(voice, hard + ("RING" to it)) }
            assertTrue(lengths.zipWithNext().all { (a, b) -> b > a || a == Scour.MIN_RENDER_SECONDS }, "$voice: RING does not lengthen the ring in order: $lengths")
            assertEquals(Scour.lengthSeconds(voice, bare), Scour.lengthSeconds(voice, hard + ("RING" to .5f)), "$voice: RING .5 must leave the ring alone")
            assertContentEquals(Scour.mechanics(voice, hard + ("RING" to 0f)).shotFrames, Scour.mechanics(voice, hard + ("RING" to 1f)).shotFrames,
                "$voice: RING moved the shots")
            val shortest = Scour.play(voice, hard + ("RING" to 0f), record = true)
            val longest = Scour.play(voice, hard + ("RING" to 1f), record = true)
            val tick = closeTick(shortest.trace)
            assertEquals(requireNotNull(shortest.modeHz).map { it[tick] }, requireNotNull(longest.modeHz).map { it[tick] }, "$voice: RING moved the tuning")
        }
    }

    /** Power at each ratio of the note, in dB under the root, in the tail of a render at [tune] with HARDEN 0. */
    private fun partials(voice: ScourVoice, tune: Float, ratios: List<Double>): List<Double> {
        val m = mapOf("HARDEN" to 0f, "TUNE" to tune)
        val out = Scour.render(voice, m).samples
        val size = 1 shl 15
        val start = ((Scour.mechanics(voice, m).closeSeconds + .05) * Dsp.RATE).toInt()
        val re = FloatArray(size) { if (start + it < out.size) out[start + it] * (0.5f - 0.5f * kotlin.math.cos(2 * Math.PI * it / (size - 1)).toFloat()) else 0f }
        val im = FloatArray(size)
        Fft.forward(re, im)
        val f0 = Scour.frequencyFor(voice, tune).toDouble()
        fun power(ratio: Double): Double {
            val bin = (f0 * ratio * size / Dsp.RATE).roundToInt()
            return (bin - 2..bin + 2).maxOf { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        }
        val root = power(1.0)
        return ratios.map { 10 * log10(power(it) / root) }
    }

    @Test
    fun `PLATE and PANE ring with different bodies, each heard and neither in the other`() {
        // Three partials of each body, as ratios to its root. The two bodies' nearest pair, PLATE's 1.729 and
        // PANE's 1.692 (2 % apart), is left out: one would leak into the other's bins.
        val round = listOf(2.328, 3.906, 4.112)
        val square = listOf(2.549, 4.650, 8.659)
        for (tune in listOf(.5f, 1f)) {
            for ((voice, own, other) in listOf(Triple(ScourVoice.PLATE, round, square), Triple(ScourVoice.PANE, square, round))) {
                val mine = partials(voice, tune, own)
                val theirs = partials(voice, tune, other)
                assertTrue(mine.max() > -45.0, "$voice TUNE $tune: its own body is not heard: $mine")
                assertTrue(theirs.max() < mine.max() - 20.0, "$voice TUNE $tune: rings at the other voice's partials: $theirs against $mine")
            }
        }
    }

    @Test
    fun `HOLD's top step is reserved and renders as the step below it`() {
        for (voice in ScourVoice.entries) {
            assertFalse(Scour.isLoop(1f))
            assertContentEquals(
                Scour.render(voice, mapOf("HOLD" to Scour.LOOP_THRESHOLD)).samples,
                Scour.render(voice, mapOf("HOLD" to 1f)).samples,
                "$voice: HOLD's top step must render as the step below it in R1",
            )
            assertTrue(Scour.renderFrames(voice, mapOf("HOLD" to .5f)) > Scour.renderFrames(voice),
                "$voice: HOLD below its top must keep the blast open longer")
        }
    }

    @Test
    fun `the bright corner leaves almost nothing above the output band before the band limit`() {
        val played = Scour.play(ScourVoice.PLATE, mapOf("TUNE" to 1f, "HARDEN" to 1f, "SHOT" to 1f, "RPM" to 1f))
        val raw = played.raw
        val size = 1 shl 16
        val start = ((played.trace.closeSeconds - .2) * Scour.INTERNAL_RATE).toInt()
        val re = FloatArray(size) { raw[start + it] * (0.5f - 0.5f * kotlin.math.cos(2 * Math.PI * it / (size - 1)).toFloat()) }
        val im = FloatArray(size)
        Fft.forward(re, im)
        var total = 0.0
        var above = 0.0
        for (k in 1 until size / 2) {
            val p = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
            total += p
            if (k.toDouble() * Scour.INTERNAL_RATE / size > Dsp.RATE / 2.0) above += p
        }
        assertTrue(above / total < 1e-3, "energy above output Nyquist: ${above / total}")
    }

    @Test
    fun `patch editing recipes scramble and the provisional roster hold together`() {
        for (voice in ScourVoice.entries) {
            assertEquals(listOf("TUNE", "RPM", "SHOT", "HARDEN", "RING", "HOLD"), Scour.macrosFor(voice).map { it.name })
            val patch = ScourPatch("Test ${voice.name}", voice, Scour.defaults(voice))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val edited = Patches.edited(patch, "Edited", patch.macros + ("SHOT" to .73f))
            assertEquals(patch.copy(name = "Edited", macros = patch.macros + ("SHOT" to .73f)), edited)
            assertEquals(edited, PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).patch)
            val random = Random(7)
            repeat(20) {
                val scrambled = Scour.scramble(voice, random)
                assertEquals(0f, scrambled.getValue("HOLD"))
                assertTrue(scrambled.values.all { it in 0f..1f })
                ScourPatch("Scrambled", voice, scrambled)
            }
            assertEquals(1, ScourPresets.forVoice(voice).size)
            assertTrue(Presets.forVoice(ScourPatch.ENGINE, voice.name).isEmpty(), "$voice is served before its listen")
        }
        assertTrue(Presets.all().none { it.engine == ScourPatch.ENGINE })
        assertFailsWith<IllegalArgumentException> { ScourPatch("Bad", ScourVoice.PLATE, mapOf("SHOT" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { ScourPatch("Bad", ScourVoice.PLATE, mapOf("UNKNOWN" to .5f)) }
        assertFailsWith<JsonException> {
            ScourPatch.fromJsonText("""{"engine":"SCOUR","version":99,"name":"Bad","voice":"PLATE","macros":{}}""")
        }
    }
}
