package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class RivetTest {
    private val controls = listOf("TUNE", "STRIKE", "METAL", "DRIVE", "SIZE", "WALLS")

    private fun energy(x: FloatArray, from: Int = 0, to: Int = x.size): Double =
        (from until minOf(to, x.size)).sumOf { x[it].toDouble() * x[it] }

    private fun difference(a: FloatArray, b: FloatArray): Double =
        sqrt((0 until maxOf(a.size, b.size)).sumOf {
            (a.getOrElse(it) { 0f } - b.getOrElse(it) { 0f }).toDouble().pow(2)
        } / maxOf(energy(a), 1e-20))

    @Test fun `all 128 drum corners have finite bounded raw sources and non-looping output`() {
        var largestRaw = 0f
        var largestMean = 0.0
        for (voice in RivetVoice.entries) for (bits in 0 until 64) {
            val m = controls.mapIndexed { i, key -> key to if ((bits and (1 shl i)) == 0) 0f else 1f }.toMap()
            val p = Rivet.inspect(voice, m)
            largestRaw = maxOf(largestRaw, p.rawPeak)
            largestMean = maxOf(largestMean, abs(p.snip.samples.average()))
            assertTrue(p.rawPeak in .001f..3f, "$voice $bits source peak ${p.rawPeak}")
            assertTrue(p.snip.samples.all { it.isFinite() && abs(it) <= .951f }, "$voice $bits unsafe")
            assertEquals(.95f, p.snip.peak(), .0001f)
            assertTrue(abs(p.snip.samples.average()) < .005, "$voice $bits DC")
            assertEquals(1, p.snip.channels)
            assertEquals(44100, p.snip.sampleRate)
            assertTrue(p.snip.durationSeconds < 1.5f, "$voice $bits became a loop")
            assertTrue(abs(p.snip.samples.last()) < 1e-6f)
        }
        println("RIVET corners: max raw peak=$largestRaw, max absolute mean=$largestMean")
    }

    @Test fun `every control moves at low and middle settings`() {
        for (voice in RivetVoice.entries) for (key in controls) for (start in listOf(0f, .4f)) {
            val a = Rivet.render(voice, mapOf(key to start)).samples
            val b = Rivet.render(voice, mapOf(key to start + .2f)).samples
            assertTrue(difference(a, b) > .0001, "$voice $key inert at $start")
        }
    }

    @Test fun `strike drive metal and room pairs interact`() {
        for (voice in RivetVoice.entries) for ((a, b) in listOf("STRIKE" to "DRIVE", "METAL" to "DRIVE", "SIZE" to "WALLS")) {
            val x = listOf(.2f to .2f, .2f to .8f, .8f to .2f, .8f to .8f).map { (lo, hi) ->
                Rivet.render(voice, mapOf(a to lo, b to hi)).samples
            }
            val residual = FloatArray(x.maxOf { it.size }) { i ->
                x[3].getOrElse(i) { 0f } - x[2].getOrElse(i) { 0f } -
                    x[1].getOrElse(i) { 0f } + x[0].getOrElse(i) { 0f }
            }
            assertTrue(energy(residual) / energy(x[0]) > 1e-6, "$voice $a x $b has no interaction")
        }
    }

    @Test fun `warehouse changes the room while retaining an identical close hit`() {
        for (voice in RivetVoice.entries) {
            val small = Rivet.inspect(voice, mapOf("SIZE" to 0f, "WALLS" to .7f))
            val big = Rivet.inspect(voice, mapOf("SIZE" to 1f, "WALLS" to .7f))
            val soft = Rivet.inspect(voice, mapOf("SIZE" to 1f, "WALLS" to 0f))
            assertContentEquals(small.close.samples, big.close.samples)
            assertContentEquals(big.close.samples, soft.close.samples)
            assertTrue(difference(small.snip.samples, big.snip.samples) > .01)
            val end = big.close.frameCount
            assertTrue(energy(big.snip.samples, end) > energy(small.snip.samples, end) * 1.5, "$voice size lost tail")
            val window = 4410
            assertTrue(energy(big.snip.samples, big.snip.frameCount - window) <
                energy(big.snip.samples, end, end + window) * .1, "$voice warehouse did not settle")
        }
    }

    @Test fun `defaults keep drum identity in every space`() {
        for (voice in RivetVoice.entries) for (size in listOf(0f, .5f, 1f)) {
            val m = mapOf("SIZE" to size)
            val snip = Rivet.render(voice, m)
            val expected = if (voice == RivetVoice.KICK) DrumClass.KICK else DrumClass.SNARE
            assertEquals(expected, Rivet.drumClassFor(voice, m))
            assertEquals(expected, Classifier.classify(snip).drumClass, "$voice SIZE=$size")
        }
    }

    @Test fun `kick low-band fundamental survives metal and room endpoints`() {
        for (tune in listOf(0f, .5f, 1f)) for (metal in listOf(0f, 1f)) {
            val m = mapOf("TUNE" to tune, "METAL" to metal, "SIZE" to 1f, "DRIVE" to .2f)
            val expected = Rivet.frequencyFor(RivetVoice.KICK, m)
            val x = Rivet.render(RivetVoice.KICK, m).samples
            // Independent spectral search includes the membrane's next partial. A metal bank
            // stealing the low-register root therefore fails instead of returning a requested Hz.
            fun power(hz: Double): Double {
                var re = 0.0; var im = 0.0
                val n = 11025
                for (i in 0 until n) {
                    val w = .5 - .5 * cos(2 * PI * i / (n - 1))
                    val phase = 2 * PI * hz * i / 44100
                    re += x[i] * w * cos(phase); im += x[i] * w * sin(phase)
                }
                return re * re + im * im
            }
            val found = (0..220).map { expected * (.75 + it * .005) }.maxBy { power(it) }
            val cents = 1200 * ln(found / expected) / ln(2.0)
            println("RIVET kick root tune=$tune metal=$metal: $found Hz, $cents cents")
            assertTrue(abs(cents) < 50, "root was $found Hz, expected $expected")
        }
    }

    @Test fun `softer native strikes change timbre without editing the patch`() {
        for (voice in RivetVoice.entries) {
            val p = RivetPatch("Touch", voice)
            val before = p.toJsonText()
            val soft = Velocity.atVelocity(p, .25f)
            val hard = Velocity.atVelocity(p, 1f)
            assertTrue(difference(hard.samples, soft.samples) > .02)
            assertEquals(before, p.toJsonText())
            assertContentEquals(soft.samples, Velocity.atVelocity(p, .25f).samples)
        }
    }

    @Test fun `settling scramble HOLD and immutable JSON are explicit`() {
        for (voice in RivetVoice.entries) {
            val base = Rivet.render(voice).samples
            assertContentEquals(base, Rivet.render(voice, mapOf("TYPO" to 1f)).samples)
            for (key in controls) for (v in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
                assertContentEquals(base, Rivet.render(voice, mapOf(key to v)).samples)
            assertContentEquals(Rivet.render(voice, mapOf("METAL" to 1f)).samples,
                Rivet.render(voice, mapOf("METAL" to 20f)).samples)
            val holdError = assertFailsWith<IllegalArgumentException> { Rivet.render(voice, mapOf("HOLD" to .1f)) }
            assertTrue(holdError.message.orEmpty().contains("HOLD is unsupported"))
            val versionError = assertFailsWith<IllegalArgumentException> { Rivet.render(voice, modelVersion = 2) }
            assertTrue(versionError.message.orEmpty().contains("unsupported RIVET model version"))
            for (t in listOf(0f, .5f, 1f, Float.NaN)) {
                val m = Rivet.scramble(voice, Random(5), t)
                assertEquals(0f, m["HOLD"])
                assertTrue(m.values.all { it.isFinite() && it in 0f..1f })
            }
            val mutable = Rivet.defaults(voice).toMutableMap()
            val p = RivetPatch("Saved", voice, mutable)
            mutable["SIZE"] = .99f
            assertEquals(.30f, p.macros["SIZE"])
            val replay = Patches.fromJsonText(p.toJsonText())
            assertEquals(p, replay)
            assertContentEquals(p.render().samples, replay.render().samples)
            val badVersion = JsonValue.Obj(p.toJsonValue().entries + ("modelVersion" to JsonValue.Num(9.0)))
            val savedVersionError = assertFailsWith<JsonException> { Patches.fromJsonValue(badVersion) }
            assertTrue(savedVersionError.message.orEmpty().contains("unsupported RIVET model version"))
        }
    }

    @Test fun `4x distortion suppresses foldback against a 16x reference and naive 1x`() {
        fun render(os: Int): FloatArray {
            val rate = 44100 * os
            val x = FloatArray(rate / 5) { i -> Rivet.saturateSample(sin(2 * PI * 3000 * i / rate).toFloat(), 12f) }
            if (os == 1) return x
            Tide.bandLimit(x, rate)
            return if (os == 4) Dsp.decimate(x, 44100) else Resampler.resample(Snip(x, 1, rate), 44100).samples
        }
        fun alias(x: FloatArray): Double {
            var re = 0.0; var im = 0.0
            for (i in 4410 until 8820) {
                val phase = 2 * PI * 17100 * i / 44100
                re += x[i] * cos(phase); im += x[i] * sin(phase)
            }
            return 2 * hypot(re, im) / 4410
        }
        val one = alias(render(1)); val four = alias(render(4)); val reference = alias(render(16))
        println("RIVET drive 12, 3 kHz sine, 17.1 kHz alias amplitude: 1x=$one 4x=$four 16x=$reference")
        assertTrue(one > .02, "naive ablation did not expose foldback")
        assertTrue(four < one * .1, "oversampling did not suppress the alias")
        assertTrue(four < reference + .005, "4x is too far from the higher-rate reference")
    }
}
