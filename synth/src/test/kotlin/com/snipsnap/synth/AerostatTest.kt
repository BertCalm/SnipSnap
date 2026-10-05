package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.json.JsonException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The mechanical and musical claims the Aerostat spec makes of a render.
 * Listening — whether it is the instrument — is the audition page, not this file.
 */
class AerostatTest {

    private val voice = AerostatVoice.FLOAT
    private val defaults = Aerostat.defaults(voice)
    private val drums = setOf(
        DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM,
    )

    @Test
    fun `the torque pulse is finite and gone before the note is`() {
        val m = Aerostat.mechanics(defaults)
        val dt = 1.0 / m.rate
        val energy = m.torqueQuick.sum() * dt + m.torqueHeavy.sum() * dt
        assertTrue(energy > 0.0 && energy.isFinite(), "impulse energy $energy")
        val late = (0.08 * m.rate).roundToInt()
        assertTrue(m.torqueQuick.drop(late).all { it == 0.0 }, "quick torque still running after 80 ms")
        assertTrue(m.torqueHeavy.drop(late).all { it == 0.0 }, "heavy torque still running after 80 ms")
        assertTrue(m.omegaQuick.all { it >= 0.0 && it.isFinite() })
        assertTrue(m.omegaHeavy.all { it >= 0.0 && it.isFinite() })
    }

    @Test
    fun `a gentle strike leaves both valves shut and the tube audible`() {
        val soft = defaults + ("STRIKE" to 0.08f)
        val m = Aerostat.mechanics(soft)
        assertTrue(m.valveQuick.max() < 0.05, "quick valve ${m.valveQuick.max()} on a gentle strike")
        assertTrue(m.valveHeavy.max() < 0.05, "heavy valve ${m.valveHeavy.max()} on a gentle strike")
        val tube = Aerostat.render(voice, soft, tap = AerostatTap.TUBE, normalize = false)
        assertTrue(peak(tube.samples) > 1e-3, "the tube went silent on a gentle strike")
    }

    @Test
    fun `the default strike catches both banks and heavy catches later`() {
        val m = Aerostat.mechanics(defaults)
        val q = catchAt(m.valveQuick, m.seconds)
        val h = catchAt(m.valveHeavy, m.seconds)
        assertTrue(q != null, "quick never caught, peak valve ${m.valveQuick.max()}")
        assertTrue(h != null, "heavy never caught, peak valve ${m.valveHeavy.max()}")
        assertTrue(h!! > q!! + 0.008, "heavy catch $h s, quick $q s")
    }

    @Test
    fun `more inertia catches later and release changes how long the valve stays open`() {
        val early = Aerostat.mechanics(defaults + mapOf("INERTIA" to 0f, "STRIKE" to 0.85f))
        val late = Aerostat.mechanics(defaults + mapOf("INERTIA" to 1f, "STRIKE" to 0.85f))
        val a = catchAt(early.valveQuick, early.seconds)
        val b = catchAt(late.valveQuick, late.seconds)
        assertTrue(a != null && b != null, "inertia sweep did not catch ($a, $b)")
        assertTrue(b!! > a!! + 0.008, "inertia 1 caught at $b, inertia 0 at $a")
        val short = openSeconds(Aerostat.mechanics(defaults + ("RELEASE" to 0f)))
        val long = openSeconds(Aerostat.mechanics(defaults + ("RELEASE" to 1f)))
        assertTrue(long > short + 0.05, "release 1 stayed open $long s, release 0 $short s")
    }

    @Test
    fun `zero pressure silences airflow and leaves the tube`() {
        val silent = Aerostat.render(voice, defaults, tap = AerostatTap.FLOW, forcedPressure = 0.0, normalize = false)
        val tube = Aerostat.render(voice, defaults, tap = AerostatTap.TUBE, forcedPressure = 0.0, normalize = false)
        assertTrue(peak(silent.samples) < 1e-4, "flow peak ${peak(silent.samples)} at zero pressure")
        assertTrue(peak(tube.samples) > 1e-3, "tube peak ${peak(tube.samples)} at zero pressure")
    }

    @Test
    fun `two open banks draw the shared reservoir down further than either alone`() {
        val hard = defaults + ("STRIKE" to 1f) + ("INERTIA" to 0.2f)
        val shared = Aerostat.mechanics(hard)
        val apart = Aerostat.mechanics(hard, isolate = true)
        val sharedMin = shared.pressure.min()
        val apartMin = minOf(apart.pressureQuick.min(), apart.pressureHeavy.min())
        assertTrue(shared.valveQuick.max() > 0.4 && shared.valveHeavy.max() > 0.4, "a hard strike did not open both valves")
        assertTrue(sharedMin < apartMin - 0.02, "shared min $sharedMin, isolated min $apartMin")
    }

    @Test
    fun `no exhaust does not heat, and height stays bounded and lags the flow`() {
        val quiet = Aerostat.mechanics(defaults + ("STRIKE" to 0f) + ("VELOCITY_IGNORED" to 0f), velocity = 0f)
        assertTrue(quiet.temp.max() < 1e-6, "temp rose to ${quiet.temp.max()} with no strike")
        assertTrue(quiet.height.max() < 1e-6, "height rose with no exhaust")
        val m = Aerostat.mechanics(defaults + ("STRIKE" to 1f) + ("LIFT" to 1f))
        assertTrue(m.height.all { it in 0.0..1.0 })
        assertTrue(m.temp.all { it >= 0.0 && it.isFinite() })
        val flowAt = m.flowQuick.indices.maxBy { m.flowQuick[it] + m.flowHeavy[it] }
        val heightAt = m.height.indices.maxBy { m.height[it] }
        assertTrue(m.height.max() > 0.02, "the vessel did not rise, height ${m.height.max()}")
        assertTrue(m.seconds[heightAt] > m.seconds[flowAt] + 0.02, "height peaked at ${m.seconds[heightAt]}, flow at ${m.seconds[flowAt]}")
    }

    @Test
    fun `a faster control step agrees on the catch`() {
        val a = Aerostat.mechanics(defaults, controlHz = Aerostat.CONTROL_RATE)
        val b = Aerostat.mechanics(defaults, controlHz = Aerostat.CONTROL_RATE_FAST)
        val qa = catchAt(a.valveQuick, a.seconds)
        val qb = catchAt(b.valveQuick, b.seconds)
        assertTrue(qa != null && qb != null)
        assertTrue(abs(qa!! - qb!!) < 0.008, "catch $qa s at ${a.rate} Hz, $qb s at ${b.rate} Hz")
    }

    @Test
    fun `boundary states stay finite`() {
        val corners = listOf(0f, 1f)
        for (strike in corners) for (pressure in corners) for (inertia in corners) {
            for (release in corners) for (lift in corners) for (hold in listOf(0f, 0.5f)) {
                val m = Aerostat.mechanics(
                    mapOf(
                        "STRIKE" to strike, "PRESSURE" to pressure, "INERTIA" to inertia,
                        "RELEASE" to release, "LIFT" to lift, "HOLD" to hold, "TUNE" to 0.5f,
                    ),
                )
                assertTrue(m.pressure.all { it.isFinite() && it in 0.0..1.3 })
                assertTrue(m.height.all { it.isFinite() && it in 0.0..1.0 })
                assertTrue(m.valveQuick.all { it.isFinite() && it in 0.0..1.0 })
                assertTrue(m.omegaQuick.all { it >= 0.0 && it.isFinite() })
            }
        }
    }

    @Test
    fun `ordinary notes stay within 10 cents and lift 1 stays within 25`() {
        for (tune in listOf(0f, 0.5f, 1f)) {
            val macros = defaults + mapOf("TUNE" to tune, "LIFT" to 0f)
            val snip = Aerostat.render(voice, macros)
            val want = Aerostat.frequencyFor(tune).toDouble()
            val got = pitchHz(snip.samples, snip.sampleRate, want)
            val cents = cents(got, want)
            assertTrue(abs(cents) < 10.0, "tune $tune measured $got Hz, ${"%.1f".format(cents)} cents from $want")
        }
        val lifted = Aerostat.render(voice, defaults + ("LIFT" to 1f) + ("STRIKE" to 0.9f))
        val want = Aerostat.frequencyFor(defaults.getValue("TUNE")).toDouble()
        val cents = cents(pitchHz(lifted.samples, lifted.sampleRate, want), want)
        assertTrue(abs(cents) < 25.0, "lift 1 moved the note ${"%.1f".format(cents)} cents")
    }

    @Test
    fun `heavy sits about five cents sharp of quick and the mix stays near the note`() {
        val quick = Aerostat.render(voice, defaults + ("LIFT" to 0f), tap = AerostatTap.QUICK, normalize = false)
        val heavy = Aerostat.render(voice, defaults + ("LIFT" to 0f), tap = AerostatTap.HEAVY, normalize = false)
        val full = Aerostat.render(voice, defaults + ("LIFT" to 0f), normalize = false)
        val want = Aerostat.frequencyFor(0.5f).toDouble()
        val q = cents(pitchHz(quick.samples, quick.sampleRate, want), want)
        val h = cents(pitchHz(heavy.samples, heavy.sampleRate, want), want)
        val f = cents(pitchHz(full.samples, full.sampleRate, want), want)
        assertTrue(abs(q) < 10.0, "quick ${"%.1f".format(q)} cents")
        assertTrue(h > q + 1.0 && h < Aerostat.HEAVY_DETUNE_CENTS + 8.0, "heavy ${"%.1f".format(h)} cents, quick ${"%.1f".format(q)}")
        assertTrue(abs(f) < 10.0, "mix ${"%.1f".format(f)} cents")
    }

    @Test
    fun `the whistle arrives after the tube and a missing bank is visible before levelling`() {
        val tube = Aerostat.render(voice, defaults, tap = AerostatTap.TUBE, normalize = false)
        val flow = Aerostat.render(voice, defaults, tap = AerostatTap.FLOW, normalize = false)
        val full = Aerostat.render(voice, defaults, tap = AerostatTap.FULL, normalize = false)
        val early = 0.04
        assertTrue(rms(tube.samples, tube.sampleRate, 0.0, early) > rms(flow.samples, flow.sampleRate, 0.0, early) * 1.5, "the flow spoke before the tube")
        val lateStart = 0.25
        assertTrue(rms(flow.samples, flow.sampleRate, lateStart, 0.5) > rms(tube.samples, tube.sampleRate, lateStart, 0.5), "the tube outlasted the whistle")
        assertTrue(rms(full.samples, full.sampleRate, lateStart, 0.5) > rms(tube.samples, tube.sampleRate, lateStart, 0.5))
    }

    @Test
    fun `lift 0 and lift 1 are both pitched and the tails differ`() {
        val flat = Aerostat.render(voice, defaults + ("LIFT" to 0f), normalize = false)
        val risen = Aerostat.render(voice, defaults + ("LIFT" to 1f) + ("STRIKE" to 0.9f), normalize = false)
        val want = Aerostat.frequencyFor(0.5f).toDouble()
        assertTrue(abs(cents(pitchHz(flat.samples, flat.sampleRate, want), want)) < 10.0)
        assertTrue(abs(cents(pitchHz(risen.samples, risen.sampleRate, want), want)) < 25.0)
        val n = minOf(flat.samples.size, risen.samples.size)
        val from = (n * 0.55).toInt()
        var diff = 0.0
        var level = 0.0
        for (i in from until n) {
            val d = flat.samples[i] - risen.samples[i]
            diff += d * d
            level += flat.samples[i] * flat.samples[i]
        }
        assertTrue(diff > level * 0.01, "lift did not move the tail")
    }

    @Test
    fun `hold sustains without a second attack and the loop closes`() {
        val held = defaults + ("HOLD" to 1f)
        val measured = Aerostat.renderLoopMeasured(held, 1f)
        assertTrue(measured.seam < Keys.MAX_SEAM_ERROR, "seam ${measured.seam}")
        val loop = measured.loop
        val head = peak(loop.copyOfRange(0, minOf(400, loop.size)))
        val body = peak(loop.copyOfRange(loop.size / 2, loop.size / 2 + 400))
        assertTrue(head < body * 4f, "the loop opens with an attack (head $head, body $body)")
        val snip = Aerostat.render(voice, held)
        assertTrue(snip.samples.contentEquals(Aerostat.render(voice, held).samples))
        assertTrue(snip.samples.all { it.isFinite() })
    }

    @Test
    fun `the same recipe renders the same samples`() {
        val a = Aerostat.render(voice, defaults + ("STRIKE" to 0.8f) + ("LIFT" to 0.7f))
        val b = Aerostat.render(voice, defaults + ("STRIKE" to 0.8f) + ("LIFT" to 0.7f))
        assertTrue(a.samples.contentEquals(b.samples))
    }

    @Test
    fun `velocity is event energy and full velocity matches the patch`() {
        val patch = AerostatPatch("Canary", voice, defaults)
        assertTrue(Velocity.atVelocity(patch, 1f).samples.contentEquals(patch.render().samples))
        val soft = Aerostat.mechanics(defaults, velocity = 0.15f)
        val hard = Aerostat.mechanics(defaults, velocity = 1f)
        assertTrue(hard.omegaQuick.max() > soft.omegaQuick.max() * 1.5, "velocity did not change the rotor")
    }

    @Test
    fun `a phrase keeps reservoir state and the second strike is not a copy of the first`() {
        val phrase = Aerostat.phrase(voice, listOf(60, 64, 67), defaults + ("STRIKE" to 0.9f))
        val alone = Aerostat.render(voice, defaults + ("STRIKE" to 0.9f) + ("TUNE" to 12 / 24f), normalize = false)
        assertTrue(phrase.samples.size > alone.samples.size)
        assertTrue(phrase.samples.all { it.isFinite() })
        val carry = AerostatCarry()
        Aerostat.render(voice, defaults + ("STRIKE" to 1f), normalize = false, carry = carry)
        val after = carry.pressure
        val fresh = AerostatCarry()
        Aerostat.render(voice, defaults + ("STRIKE" to 0f), velocity = 0f, normalize = false, carry = fresh)
        assertTrue(after.isFinite() && fresh.pressure.isFinite())
        assertTrue(after < fresh.pressure - 0.01, "shared phrase pressure $after, untouched $fresh")
    }

    @Test
    fun `the default and the presets are not filed as drums`() {
        // The stronger pitched knock can resemble a tom to the generic audio
        // classifier. SynthKits uses the engine's explicit filing contract.
        val patches = listOf(AerostatPatch("Default", voice, defaults)) + AerostatPresets.all()
        for (patch in patches) {
            val cls = Aerostat.drumClassFor(patch.voice, patch.macros)
            assertTrue(cls !in drums, "filed as $cls")
        }
    }

    @Test
    fun `a patch round-trips and rejects a stranger`() {
        val patch = AerostatPatch("Round Trip", voice, defaults + ("STRIKE" to 0.4f) + ("LIFT" to 0.8f))
        val back = AerostatPatch.fromJsonText(patch.toJsonText())
        assertEquals(patch, back)
        assertTrue(Patches.fromJsonText(patch.toJsonText()) is AerostatPatch)
        assertFailsWith<IllegalArgumentException> { AerostatPatch(" ", voice, emptyMap()) }
        assertFailsWith<IllegalArgumentException> { AerostatPatch("x", voice, mapOf("BEND" to 0.2f)) }
        assertFailsWith<IllegalArgumentException> { AerostatPatch("x", voice, mapOf("STRIKE" to 1.2f)) }
        assertFailsWith<JsonException> { AerostatPatch.fromJsonText("""{"engine":"AEROSTAT","version":1,"name":"x","voice":"NOPE","macros":{}}""") }
    }

    @Test
    fun `the kit is sixteen pitched pads and none of them is a drum class`() {
        val kit = SynthKits.aerostat()
        assertEquals(16, kit.size)
        for ((i, pad) in kit.withIndex()) {
            val arranged = requireNotNull(pad)
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe))
            assertTrue(recipe.patch is AerostatPatch, "pad $i")
            assertEquals(null, recipe.fx, "pad $i lands dry")
            assertTrue(arranged.drumClass !in drums, "pad $i filed ${arranged.drumClass}")
            assertTrue(arranged.snip.samples.any { it != 0f })
        }
    }

    private fun catchAt(valve: DoubleArray, seconds: DoubleArray): Double? {
        val i = valve.indexOfFirst { it > 0.25 }
        return if (i < 0) null else seconds[i]
    }

    private fun openSeconds(m: Aerostat.Mechanics): Double {
        val dt = 1.0 / m.rate
        return (m.valveQuick.count { it > 0.2 } + m.valveHeavy.count { it > 0.2 }) * dt
    }

    private fun peak(s: FloatArray): Float {
        var p = 0f
        for (v in s) {
            val a = abs(v)
            if (a > p) p = a
        }
        return p
    }

    private fun rms(s: FloatArray, rate: Int, from: Double, until: Double): Double {
        val a = (from * rate).toInt().coerceIn(0, s.size)
        val b = (until * rate).toInt().coerceIn(a, s.size)
        if (b <= a) return 0.0
        var e = 0.0
        for (i in a until b) e += s[i] * s[i]
        return kotlin.math.sqrt(e / (b - a))
    }

    private fun cents(hz: Double, ref: Double): Double = 1200.0 * ln(hz / ref) / ln(2.0)

    /**
     * Parabolic peak of an 8192-point Hann FFT, searched around [expected] so an
     * octave cannot win. The window is the loudest stretch after the knock:
     * the tail is where a dying tube used to outvote the whistle.
     */
    private fun pitchHz(samples: FloatArray, rate: Int, expected: Double): Double {
        val n = 8192
        if (samples.size < n) return 0.0
        val earliest = (0.08 * rate).toInt()
        var start = earliest.coerceAtMost(samples.size - n)
        var bestEnergy = Double.NEGATIVE_INFINITY
        var at = start
        while (at + n <= samples.size) {
            var energy = 0.0
            for (i in at until at + n) energy += samples[i].toDouble() * samples[i]
            if (energy > bestEnergy) {
                bestEnergy = energy
                start = at
            }
            at += 512
        }
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            re[i] = (samples[start + i] * w).toFloat()
        }
        Fft.forward(re, im)
        fun mag(k: Int): Double = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
        val bin = rate.toDouble() / n
        val lo = (expected * 0.75 / bin).toInt().coerceAtLeast(1)
        val hi = (expected * 1.35 / bin).toInt().coerceAtMost(n / 2 - 2)
        var best = lo
        var bestMag = -1.0
        for (k in lo..hi) {
            val m = mag(k)
            if (m > bestMag) {
                bestMag = m
                best = k
            }
        }
        // Log magnitude interpolation avoids the systematic flat bias of
        // interpolating Hann-window power (about 25 cents at C3).
        val left = ln(mag(best - 1).coerceAtLeast(1e-30))
        val center = ln(mag(best).coerceAtLeast(1e-30))
        val right = ln(mag(best + 1).coerceAtLeast(1e-30))
        val denom = left - 2.0 * center + right
        val delta = if (abs(denom) < 1e-18) 0.0 else 0.5 * (left - right) / denom
        return (best + delta) * bin
    }
}
