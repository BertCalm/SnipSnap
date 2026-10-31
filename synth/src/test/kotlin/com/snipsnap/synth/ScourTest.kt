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

/** Mechanics, plate and house audio evidence for SCOUR R2, the impact; the audition supplies the listening verdict. */
class ScourTest {
    private val drums = setOf(
        DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM,
    )
    private val timbral = listOf("BUILD", "RPM", "SHOT", "HARDEN", "HOLD")

    /** The control tick the slam lands on. */
    private fun slamTick(trace: Scour.Trace) = (trace.slamFrame / Scour.CTRL).coerceAtMost(trace.smoothed.lastIndex)

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var e = 0.0
        for (i in from until to) e += a[i].toDouble() * a[i]
        return if (to > from) sqrt(e / (to - from)) else 0.0
    }

    private fun db(x: Double) = 20 * log10(x + 1e-12)

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
            for (shot in listOf(0f, 1f)) for (build in listOf(0f, 1f)) for (velocity in listOf(.2f, 1f)) {
                val trace = Scour.mechanics(voice, mapOf("SHOT" to shot, "BUILD" to build), velocity)
                assertTrue(trace.coverage.all { it in 0.0..1.0 && it < 1.0 }, "$voice SHOT $shot BUILD $build: coverage left [0, 1)")
                assertTrue(trace.smoothed.all { it in 0.0..1.0 }, "$voice: smoothed coverage left [0, 1]")
            }
            val atSlam = listOf(0f, .25f, .5f, .75f, 1f).map { shot ->
                val t = Scour.mechanics(voice, mapOf("SHOT" to shot))
                t.coverage[slamTick(t)]
            }
            assertTrue(atSlam.zipWithNext().all { (a, b) -> b > a }, "$voice: coverage at the slam is not monotonic in SHOT: $atSlam")
            assertTrue(atSlam.last() > .5, "$voice: a full blast leaves little coverage: ${atSlam.last()}")
            val longer = Scour.mechanics(voice, mapOf("BUILD" to 1f))
            assertTrue(longer.coverage[slamTick(longer)] > atSlam[3], "$voice: a longer build does not harden more")
            val soft = Scour.mechanics(voice, emptyMap(), .3f)
            val hard = Scour.mechanics(voice, emptyMap(), 1f)
            assertTrue(soft.coverage[slamTick(soft)] < hard.coverage[slamTick(hard)], "$voice: velocity does not reach the coverage")
        }
    }

    @Test
    fun `the rain accelerates, and turning SHOT or BUILD stretches one schedule with the energy law`() {
        for (voice in ScourVoice.entries) {
            val a = Scour.mechanics(voice, mapOf("SHOT" to .5f, "BUILD" to .5f))
            val b = Scour.mechanics(voice, mapOf("SHOT" to .9f, "BUILD" to .7f))
            assertTrue(b.shotFrames.size > 3 * a.shotFrames.size, "$voice: SHOT and BUILD did not raise the shot count")
            // Expected ratio of the last quarter's shots to the first's is (20 − 20^¾)/(20^¼ − 1), about 9.5.
            for ((trace, label) in listOf(b to "dense")) {
                val quarter = trace.slamFrame / 4
                val first = trace.shotFrames.count { it < quarter }
                val last = trace.shotFrames.count { it >= 3 * quarter }
                assertTrue(last > 4 * first.coerceAtLeast(1), "$voice $label: the rain does not accelerate ($first then $last)")
            }
            for (i in a.shotFrames.indices) {
                // Shot i keeps its own unit-rate arrival: each schedule's expected count reaches the same value
                // at its i-th shot, to within the two frames of quantisation.
                val ta = a.shotFrames[i] / Scour.INTERNAL_RATE.toDouble()
                val tb = b.shotFrames[i] / Scour.INTERNAL_RATE.toDouble()
                val ua = Scour.arrivalsBy(a.peakRate, a.slamSeconds, ta)
                val ub = Scour.arrivalsBy(b.peakRate, b.slamSeconds, tb)
                val frame = 2.0 / Scour.INTERNAL_RATE
                val slack = frame * (Scour.rateAt(a.peakRate, a.slamSeconds, ta) + Scour.rateAt(b.peakRate, b.slamSeconds, tb))
                assertTrue(abs(ua - ub) <= slack, "$voice shot $i was reshuffled, not stretched ($ua vs $ub)")
                assertEquals(a.shotAim[i], b.shotAim[i], "$voice shot $i changed its aim")
                assertEquals(a.shotCoverage[i], b.shotCoverage[i], "$voice shot $i changed its dent")
                // λ(t)·amp² / swell² is the same for shot i under any SHOT and BUILD: the swell is the only loudness ramp.
                val ea = a.shotAmplitude[i].let { it * it } * Scour.rateAt(a.peakRate, a.slamSeconds, ta) / Scour.swell(a.slamSeconds, ta).let { it * it }
                val eb = b.shotAmplitude[i].let { it * it } * Scour.rateAt(b.peakRate, b.slamSeconds, tb) / Scour.swell(b.slamSeconds, tb).let { it * it }
                assertTrue(abs(ea - eb) <= 1e-3 * ea, "$voice shot $i: λ·amp²/swell² is not constant ($ea vs $eb)")
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
    fun `the root never moves while the upper modes climb through the build and stay under the ceiling`() {
        for (voice in ScourVoice.entries) {
            val f0 = Scour.frequencyFor(voice, .5f)
            val tops = listOf(0f, .5f, 1f).map { harden ->
                val played = Scour.play(voice, mapOf("HARDEN" to harden, "SHOT" to .8f), record = true)
                val modes = requireNotNull(played.modeHz)
                assertTrue(modes[0].all { it == f0 }, "$voice HARDEN $harden moved the root")
                modes.last()[slamTick(played.trace)]
            }
            assertTrue(tops.zipWithNext().all { (a, b) -> b > a }, "$voice: the top mode does not climb with HARDEN: $tops")
            // The climb is the riser: the top mode at the slam stands above where it was halfway through the build.
            val played = Scour.play(voice, mapOf("HARDEN" to 1f, "SHOT" to .8f), record = true)
            val top = requireNotNull(played.modeHz).last()
            assertTrue(top[slamTick(played.trace)] > top[slamTick(played.trace) / 2], "$voice: the build does not climb")
            // SHOT × HARDEN: without shots there is little to harden.
            val bare = Scour.play(voice, mapOf("HARDEN" to 1f, "SHOT" to 0f), record = true)
            val bareTop = requireNotNull(bare.modeHz).last()[slamTick(bare.trace)]
            assertTrue(bareTop - tops[0] < .2 * (tops[2] - tops[0]), "$voice: HARDEN moved the plate without shots")
            val bright = Scour.play(voice, mapOf("TUNE" to 1f, "HARDEN" to 1f, "SHOT" to 1f, "BUILD" to 1f, "HOLD" to 1f), record = true)
            assertTrue(requireNotNull(bright.modeHz).all { m -> m.all { it < 18_000f } }, "$voice: a mode passed the ceiling")
        }
    }

    @Test
    fun `the build rises, the slam lands on time and stands over it`() {
        for (voice in ScourVoice.entries) {
            for (seconds in listOf(.5, 2.0, 2.667, 4.0)) {
                val build = Scour.buildFor(seconds)
                assertTrue(abs(Scour.buildSeconds(build) - seconds) < 1e-5 * seconds, "$voice: buildFor($seconds) does not invert buildSeconds")
                val trace = Scour.mechanics(voice, mapOf("BUILD" to build))
                assertEquals((Scour.buildSeconds(build) * Scour.INTERNAL_RATE).roundToInt(), trace.slamFrame, "$voice: the slam is not at the end of the build")
                assertTrue(trace.shotFrames.all { it < trace.slamFrame }, "$voice: a shot fell after the slam")
            }
            val played = Scour.play(voice, emptyMap())
            val out = Scour.finish(played.raw, normalize = false)
            val slam = (played.trace.slamSeconds * Dsp.RATE).roundToInt()
            val window = Dsp.RATE / 5
            val quarters = (0 until 4).map { db(rms(out, it * slam / 4, (it + 1) * slam / 4)) }
            assertTrue(quarters.last() - quarters.first() > 6.0, "$voice: the build does not rise: $quarters")
            var loudest = 0.0
            var i = 0
            while (i + window <= slam) { loudest = maxOf(loudest, rms(out, i, i + window)); i += window / 4 }
            val hit = rms(out, slam, slam + window)
            assertTrue(db(hit) - db(loudest) > 6.0, "$voice: the slam stands only ${db(hit) - db(loudest)} dB over the build")
        }
    }

    @Test
    fun `the plate rings down once the slam has stopped the roller, and the check can fail`() {
        for (voice in ScourVoice.entries) for (macros in listOf(emptyMap(), mapOf("SHOT" to 1f, "RPM" to 1f, "HARDEN" to 1f), mapOf("BUILD" to 0f, "HOLD" to 1f))) {
            val trace = Scour.mechanics(voice, macros)
            val out = Scour.render(voice, macros).samples
            val quiet = ((trace.slamSeconds + .08) * Dsp.RATE).toInt()
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
                mapOf("TUNE" to 1f, "BUILD" to 1f, "RPM" to 1f, "SHOT" to 1f, "HARDEN" to 1f),
                mapOf("TUNE" to 0f, "BUILD" to 0f, "RPM" to 0f, "SHOT" to 0f, "HARDEN" to 0f),
                mapOf("HOLD" to 1f, "SHOT" to 1f),
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
            // Velocity moves the hit, never the length, so a soft hit files the same.
            val soft = Scour.render(voice, macros, .2f)
            assertEquals(snip.samples.size, soft.samples.size, "$voice $macros: velocity changed the length")
            assertEquals(filed, Classifier.classify(soft).drumClass, "$voice $macros: a soft hit changes the class")
        }
    }

    @Test
    fun `the requested note is the detected root in the hit's ring and in the tail at every corner`() {
        val corners = listOf(0f, 1f)
        for (voice in ScourVoice.entries) {
            val cases = corners.flatMap { tune -> corners.flatMap { build -> corners.flatMap { rpm -> corners.flatMap { shot ->
                corners.map { harden -> mapOf("TUNE" to tune, "BUILD" to build, "RPM" to rpm, "SHOT" to shot, "HARDEN" to harden) }
            } } } } + listOf(0f, .25f, .5f, .75f, 1f).map { mapOf("TUNE" to it) }
            for (macros in cases) {
                val snip = Scour.render(voice, macros)
                val expected = Scour.frequencyFor(voice, macros.getValue("TUNE")).toDouble()
                val slam = Scour.buildSeconds(Scour.settled(voice, macros).getValue("BUILD")).toFloat()
                // The hit's ring starts once the slam's transient has passed: in the first 0.15 s after the slam the
                // fully hardened PLATE reads like a struck gong (68 cents flat at the extreme corner), as the spec records.
                for ((window, pitch) in listOf("hit" to Pitch.detect(snip, fromSec = slam + .2f), "tail" to Pitch.detect(snip, fromSec = slam + .6f))) {
                    requireNotNull(pitch) { "$voice $macros: no root in the $window" }
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
                val high = Scour.render(voice, mapOf(macro to 1f)).samples
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
    fun `HOLD lengthens the ring all the way to its top, never loops, and never moves the build`() {
        for (voice in ScourVoice.entries) {
            val holds = listOf(0f, .5f, .99f, 1f)
            assertTrue(holds.none { Scour.isLoop(it) }, "$voice: a HOLD setting loops")
            val lengths = holds.map { Scour.lengthSeconds(voice, mapOf("HOLD" to it)) }
            assertTrue(lengths.zipWithNext().all { (a, b) -> b > a }, "$voice: HOLD does not lengthen the ring to its top: $lengths")
            assertContentEquals(Scour.mechanics(voice, mapOf("HOLD" to 0f)).shotFrames, Scour.mechanics(voice, mapOf("HOLD" to 1f)).shotFrames,
                "$voice: HOLD moved the build")
        }
    }

    @Test
    fun `the bright corner leaves almost nothing above the output band before the band limit`() {
        val played = Scour.play(ScourVoice.PLATE, mapOf("TUNE" to 1f, "HARDEN" to 1f, "SHOT" to 1f, "RPM" to 1f))
        val raw = played.raw
        val size = 1 shl 16
        val start = played.trace.slamFrame - size / 2
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

    /**
     * Power at each ratio of the note, in dB under the root, over the last 0.74 s of the build at [tune] with HARDEN 0:
     * where the shot rain keeps each body sounding. After the slam the ring is mostly the root, as a struck gong's is
     * (each body's partials sit 39 to 69 dB under it 50 ms after the hit, measured), so that is not where to look.
     */
    private fun partials(voice: ScourVoice, tune: Float, ratios: List<Double>): List<Double> {
        val m = mapOf("HARDEN" to 0f, "TUNE" to tune)
        val out = Scour.render(voice, m).samples
        val size = 1 shl 15
        val start = (Scour.mechanics(voice, m).slamSeconds * Dsp.RATE).toInt() - size
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
    fun `each voice rings with its own body, heard, and in no other voice`() {
        // Three partials of each body, as ratios to its root, none within 3 % of another body's partial (the
        // closest, PANE's 8.659 and SHEET's 8.947, sit 28 bins apart at SHEET's C3). PLATE's 1.729 and PANE's
        // 1.692 (2 % apart) would leak into each other's bins, as would PLATE's 3.906 and 6.301 and SHEET's
        // 3.881 and 6.328 (under 1 %), and PANE's 9.413 and SHEET's 9.331. The narrowest margin is SHEET's at C3,
        // 15.6 dB over the floor at PLATE's ratios: its louder roller raises the floor (measured).
        val bodies = mapOf(
            ScourVoice.PLATE to listOf(2.328, 4.112, 6.711),
            ScourVoice.PANE to listOf(2.549, 4.650, 8.659),
            ScourVoice.SHEET to listOf(2.778, 10.222, 13.821),
        )
        for (tune in listOf(.5f, 1f)) {
            for ((voice, own) in bodies) {
                val mine = partials(voice, tune, own)
                assertTrue(mine.max() > -35.0, "$voice TUNE $tune: its own body is not heard: $mine")
                for ((other, ratios) in bodies) if (other != voice) {
                    // The other body's ratios read the build's noise floor (the roller and the shots), not a partial.
                    val theirs = partials(voice, tune, ratios)
                    assertTrue(theirs.max() < mine.max() - 15.0, "$voice TUNE $tune: rings at $other's partials: $theirs against $mine")
                }
            }
        }
    }

    @Test
    fun `patch editing recipes scramble and the provisional roster hold together`() {
        for (voice in ScourVoice.entries) {
            assertEquals(listOf("TUNE", "BUILD", "RPM", "SHOT", "HARDEN", "HOLD"), Scour.macrosFor(voice).map { it.name })
            val patch = ScourPatch("Test ${voice.name}", voice, Scour.defaults(voice))
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            val edited = Patches.edited(patch, "Edited", patch.macros + ("SHOT" to .73f))
            assertEquals(patch.copy(name = "Edited", macros = patch.macros + ("SHOT" to .73f)), edited)
            assertEquals(edited, PadRecipe.fromJsonText(PadRecipe(edited).toJsonText()).patch)
            val random = Random(7)
            repeat(20) {
                val scrambled = Scour.scramble(voice, random)
                assertTrue(scrambled.values.all { it in 0f..1f })
                assertFalse(Scour.isLoop(scrambled.getValue("HOLD")))
                ScourPatch("Scrambled", voice, scrambled)
            }
        }
        assertFailsWith<IllegalArgumentException> { ScourPatch("Bad", ScourVoice.PLATE, mapOf("SHOT" to Float.NaN)) }
        assertFailsWith<IllegalArgumentException> { ScourPatch("Bad", ScourVoice.PLATE, mapOf("UNKNOWN" to .5f)) }
        assertFailsWith<JsonException> {
            ScourPatch.fromJsonText("""{"engine":"SCOUR","version":99,"name":"Bad","voice":"PLATE","macros":{}}""")
        }
    }
}
