package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * NACRE CONCH (saved model 9): the pair of blown shells and its held loop. Each held case prints its seams, retune passes
 * and cycle error, so the record carries the numbers and not only the pass.
 */
class NacreConchTest {
    private val held = mapOf("HOLD" to 1f)

    private fun hash(samples: FloatArray): String {
        val b = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) b.putFloat(s)
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var e = 0.0
        for (i in from until to) e += x[i].toDouble() * x[i]
        return sqrt(e / (to - from))
    }

    private fun cents(hz: Double, want: Double) = 1200.0 * ln(hz / want) / ln(2.0)

    private fun steps(loop: FloatArray): Pair<Float, Float> {
        var inside = 0f
        for (i in 1 until loop.size) inside = maxOf(inside, abs(loop[i] - loop[i - 1]))
        return inside to abs(loop[0] - loop[loop.lastIndex])
    }

    @Test
    fun `the held loop closes without a click at C2 to C5, soft and hard, and at the TOUCH x DUET corners`() {
        val cases = listOf(36, 48, 60, 72).flatMap { m -> listOf(Triple(m, .35f, emptyMap<String, Float>()), Triple(m, 1f, emptyMap())) } +
            listOf(0f to 0f, 0f to 1f, 1f to 0f, 1f to 1f).map { (t, d) -> Triple(48, .8f, mapOf("TOUCH" to t, "DUET" to d)) } +
            listOf(Triple(41, .7f, mapOf("DUET" to .7f)), Triple(67, .9f, mapOf("TOUCH" to .7f, "DUET" to .9f)))
        for ((midi, velocity, macros) in cases) {
            val r = NacreConch.played(macros + held, midi, velocity, 7L)
            val c = assertNotNull(r.closure)
            val d = r.probe.diagnostics
            println("CONCH held MIDI $midi velocity $velocity $macros: seam before %.2e after %.2e cycle %.2e passes %d/%d loop %.4f s".format(
                java.util.Locale.ROOT, c.before, c.after, c.cycleError, d.getValue("retunePasses").toInt(),
                d.getValue("upperRetunePasses").toInt(), d.getValue("loopSeconds")))
            assertTrue(c.worstSeam < Keys.MAX_SEAM_ERROR, "MIDI $midi $velocity $macros seam ${c.worstSeam}")
            assertTrue(c.cycleError < Keys.MAX_SEAM_ERROR, "MIDI $midi $velocity $macros cycle ${c.cycleError}")
            val loop = r.probe.snip.samples
            assertEquals(r.loopFrames, loop.size)
            assertEquals(DrumClass.LOOP, Nacre.drumClassFor(NacreVoice.CONCH, macros + held))
            val (inside, wrap) = steps(loop)
            assertTrue(wrap <= inside, "MIDI $midi $velocity $macros: the wrap steps $wrap, the loop's own biggest step is $inside")
            val w = 2205
            val step = 20.0 * log10(rms(loop, 0, w) / rms(loop, loop.size - w, loop.size))
            assertTrue(abs(step) < HeldLoop.MAX_LEVEL_STEP_DB, "MIDI $midi level step $step dB across the wrap")
            assertTrue(loop.all { it.isFinite() } && loop.maxOf { abs(it) } <= NacreConch.PEAK_CEILING)
        }
    }

    @Test
    fun `the loop played eight times end to end is the real next lap`() {
        val r = NacreConch.played(held, 48, .8f, 7L)
        val loop = r.probe.snip.samples
        val next = r.native.copyOfRange(r.loopStart + r.loopFrames, r.loopStart + 2 * r.loopFrames)
        assertTrue(NacreHeld.cycleError(r.native, r.loopStart, r.loopFrames) < Keys.MAX_SEAM_ERROR)
        var diff = 0.0
        for (i in loop.indices) diff = maxOf(diff, abs(next[i] - loop[i]).toDouble())
        assertTrue(diff < .02 * loop.maxOf { abs(it) }, "the next lap departs from the loop by $diff")
        val eight = FloatArray(loop.size * 8)
        for (k in 0 until 8) loop.copyInto(eight, k * loop.size)
        val (inside, _) = steps(loop)
        for (k in 1 until 8) assertTrue(abs(eight[k * loop.size] - eight[k * loop.size - 1]) <= inside)
    }

    @Test
    fun `every note settles exactly in tune, both shells, and is heard at its own octave`() {
        for (midi in NacreConch.MIN_MIDI..NacreConch.MAX_MIDI step 3) for (upper in listOf(false, true)) {
            val (tuning, plan) = NacreConch.heldShell(midi, .3f, .15f, 1f, upper, 7L)
            val loopPlan = if (upper) plan.upper else plan.bass
            val key = Keys.midiHz(midi).toDouble() * (if (upper) 2.0 else 1.0)
            val planned = loopPlan.periods * Dsp.RATE.toDouble() / loopPlan.frames
            assertTrue(abs(cents(planned, key)) < .05, "MIDI $midi upper $upper: the plan is ${cents(planned, key)} cents off")
            // The shell's played periods fill the loop: measured on the retuned shell itself.
            val wanted = loopPlan.frames.toDouble() * Dsp.OVERSAMPLE
            val played = Bore.measureLoopSamples(tuning.raw, plan.loopStart * Dsp.OVERSAMPLE, wanted, loopPlan.periods)
            assertTrue(abs(cents(wanted / played, 1.0)) < .05, "MIDI $midi upper $upper: the shell plays ${cents(wanted / played, 1.0)} cents from the loop")
        }
        for (midi in listOf(36, 48, 60, 72)) for (touch in listOf(.3f, 1f)) {
            val key = Keys.midiHz(midi).toDouble()
            val m = mapOf("TOUCH" to touch)
            val loop = NacreConch.render(m + held, midi, 1f, 7L)
            val heldPitch = assertNotNull(Pitch.detect(loop, 0f, 1f))
            // Within 10 cents of the key, not an octave up: the upper shell answers, the bass shell is the note.
            assertTrue(abs(cents(heldPitch.hz.toDouble(), key)) < 10.0 && heldPitch.confidence >= .3f, "held MIDI $midi TOUCH $touch: $heldPitch")
            val finite = NacreConch.render(m, midi, 1f, 7L)
            val blown = assertNotNull(Pitch.detect(finite, 2.2f, .8f))
            assertTrue(abs(cents(blown.hz.toDouble(), key)) < 10.0 && blown.confidence >= .3f, "finite MIDI $midi TOUCH $touch: $blown")
        }
    }

    @Test
    fun `renders are deterministic across cold caches and the seed is a real input`() {
        for (macros in listOf(emptyMap(), held, held + ("DUET" to .8f))) {
            Nacre.clearCoreCache()
            val a = NacreConch.render(macros, 53, .8f, 7L).samples
            Nacre.clearCoreCache()
            val b = NacreConch.render(macros, 53, .8f, 7L).samples
            assertEquals(hash(a), hash(b), "CONCH $macros is not deterministic")
            assertNotEquals(hash(a), hash(NacreConch.render(macros, 53, .8f, 8L).samples), "CONCH $macros ignores its seed")
            assertTrue(a.all { it.isFinite() })
        }
    }

    @Test
    fun `a finite note is the held note until the breath begins to fade`() {
        for (hold in listOf(0f, .5f)) {
            val finite = NacreConch.played(mapOf("HOLD" to hold), 45, .7f, 7L)
            val loop = NacreConch.played(held, 45, .7f, 7L)
            val fade = (NacreConch.breathSeconds(hold) * Dsp.RATE).toInt()
            assertEquals(NacreConch.renderFrames(mapOf("HOLD" to hold), 45), finite.native.size)
            val until = fade - 64
            for (stem in listOf("bass", "upper", "sympathetic", "venue")) {
                assertContentEquals(loop.probe.stems.getValue(stem).copyOf(until), finite.probe.stems.getValue(stem).copyOf(until), "$stem at HOLD $hold")
            }
            val after = finite.probe.stems.getValue("bass")
            assertFalse(after.copyOfRange(fade + 4410, fade + 8820).contentEquals(
                loop.probe.stems.getValue("bass").copyOfRange(fade + 4410, fade + 8820)), "the breath never faded")
            // The fade lets the shells go: two seconds after it the tone is well down on the held note.
            val ring = rms(finite.native, fade + 2 * 44100, fade + 2 * 44100 + 4410)
            assertTrue(ring < .25 * rms(loop.native, fade + 2 * 44100, fade + 2 * 44100 + 4410), "the shells kept sounding")
            assertEquals(DrumClass.TONAL, Nacre.drumClassFor(NacreVoice.CONCH, mapOf("HOLD" to hold)))
        }
    }

    @Test
    fun `DUET sets when the upper shell answers, and from LEAN_DUET a whole-cycle beat`() {
        val early = NacreConch.planFor(48, .3, .15)
        val late = NacreConch.planFor(48, .3, .55)
        assertTrue(late.upperStart > early.upperStart && early.upperStart > 0 && early.bassStart == 0)
        // PEARL's law reverses near the top: the bass answers the upper.
        val top = NacreConch.planFor(48, .3, 1.0)
        assertTrue(top.bassStart > 0 && top.upperStart == 0)
        for ((duet, beat) in listOf(.15 to 0, .64 to 0, .7 to 1, .84 to 1, .9 to 2, 1.0 to 2)) {
            assertEquals(beat, NacreConch.beatCycles(duet))
            val p = NacreConch.planFor(48, .3, duet)
            assertEquals(2 * p.bass.periods + beat, p.upper.periods)
            assertEquals(p.bass.frames, p.upper.frames)
        }
        // The lean is real on the loop: the upper shell's own played length fills the loop with 2n + 1 periods.
        val (tuning, plan) = NacreConch.heldShell(48, .3f, .7f, .8f, true, 7L)
        val wanted = plan.loopFrames.toDouble() * Dsp.OVERSAMPLE
        val played = Bore.measureLoopSamples(tuning.raw, plan.loopStart * Dsp.OVERSAMPLE, wanted, plan.upper.periods)
        assertTrue(abs(cents(wanted / played, 1.0)) < .05)
        assertTrue(cents(tuning.tunedHz, NacreConch.heldShell(48, .3f, .15f, .8f, true, 7L).first.tunedHz) > .5,
            "the leaning upper shell is not sharper than the octave")
        val r = NacreConch.played(held + ("DUET" to .7f), 48, .8f, 7L)
        assertEquals(1.0, r.probe.diagnostics.getValue("beatCyclesPerLoop"))
        val events = r.probe.events
        assertEquals(listOf("bass", "upper"), events.map { it.performer })
        assertTrue(events.all { it.direction == "breath" })
    }

    @Test
    fun `the harp's sympathetic strings and water are still heard under a held breath`() {
        val r = NacreConch.played(held, 48, 1f, 7L)
        val s = r.loopStart
        val l = r.loopFrames
        val source = rms(FloatArray(l) { r.probe.stems.getValue("bass")[s + it] + r.probe.stems.getValue("upper")[s + it] })
        val sym = 20.0 * log10(rms(r.probe.stems.getValue("sympathetic"), s, s + l) / source)
        val venue = 20.0 * log10(rms(r.probe.stems.getValue("venue"), s, s + l) / source)
        println("CONCH held C3: sympathetic %.1f dB, venue %.1f dB against the blown source".format(java.util.Locale.ROOT, sym, venue))
        assertTrue(sym > -18.0, "sympathetic $sym dB")
        assertTrue(venue > -18.0, "venue $venue dB")
        for ((name, value) in listOf("VENUE" to 0f, "VENUE" to 1f, "BLOOM" to .15f, "BLOOM" to .7f, "CURRENT" to 0f, "CURRENT" to .6f)) {
            val moved = NacreConch.played(held + (name to value), 48, 1f, 7L)
            assertNotEquals(hash(r.probe.snip.samples), hash(moved.probe.snip.samples), "$name $value does not move the loop")
            assertEquals(1.0, moved.probe.diagnostics.getValue("coreCacheHit"), "$name re-rendered the shells")
        }
    }

    /**
     * The tone's colour, level-free: the power above 2.5 times the note against the power below it, in dB, over
     * [fromSec, toSec). The shells are near-sinusoids (the jet's own harmonics sit 19 to 28 dB down), and the upper shell's
     * octave sits below the line, so what moves this is the jet's edge tone, the spiral chamber and the hiss.
     */
    private fun harmonicShareDb(x: FloatArray, f0: Double, fromSec: Double, toSec: Double): Double {
        val p = NacreVoicesMeasure.power(x, fromSec, toSec)
        val bin = Dsp.RATE.toDouble() / 8192
        var low = 0.0
        var high = 0.0
        for (k in 1 until p.size) if (k * bin < 2.5 * f0) low += p[k] else high += p[k]
        return 10.0 * log10(high / low)
    }

    @Test
    fun `velocity changes the breath's colour, not only its level`() {
        val f0 = Keys.midiHz(48).toDouble()
        val soft = NacreConch.render(held, 48, .35f, 7L).samples
        val hard = NacreConch.render(held, 48, 1f, 7L).samples
        val softShare = harmonicShareDb(soft, f0, 0.0, 3.5)
        val hardShare = harmonicShareDb(hard, f0, 0.0, 3.5)
        println("CONCH velocity harmonic share soft %.1f dB hard %.1f dB".format(java.util.Locale.ROOT, softShare, hardShare))
        assertTrue(hardShare > softShare + 3.0, "hard $hardShare dB soft $softShare dB")
        assertTrue(rms(hard) > rms(soft) * 1.4)
        // The finite note's steady breath too.
        val softFinite = NacreConch.render(emptyMap(), 48, .35f, 7L).samples
        val hardFinite = NacreConch.render(emptyMap(), 48, 1f, 7L).samples
        assertTrue(harmonicShareDb(hardFinite, f0, 1.8, 2.5) > harmonicShareDb(softFinite, f0, 1.8, 2.5) + 3.0)
        assertTrue(NacreConch.pressureFor(.3, 1f) > NacreConch.pressureFor(.3, .35f))
    }

    @Test
    fun `TOUCH brightens the breath across five steps`() {
        val f0 = Keys.midiHz(48).toDouble()
        var last = Double.NEGATIVE_INFINITY
        for (touch in listOf(0f, .25f, .5f, .75f, 1f)) {
            val share = harmonicShareDb(NacreConch.render(mapOf("TOUCH" to touch) + held, 48, .8f, 7L).samples, f0, 0.0, 3.5)
            println("CONCH TOUCH $touch harmonic share %.1f dB".format(java.util.Locale.ROOT, share))
            assertTrue(share > last, "TOUCH $touch share $share dB after $last dB")
            last = share
        }
    }

    @Test
    fun `the shells stay bounded and finite, and the jet saturates rather than grows`() {
        for ((midi, touch) in listOf(36 to 1f, 72 to 1f, 48 to 0f)) {
            val r = NacreConch.played(mapOf("TOUCH" to touch) + held, midi, 1f, 7L)
            val d = r.probe.diagnostics
            // The jet's flow is bounded by construction (tanh, then a unity-gain low-pass), and so is the passive bank it drives.
            assertTrue(d.getValue("jetFlowPeak") <= 1.0 + kotlin.math.tanh(NacreConch.JET_BIAS) + 1e-9, "MIDI $midi flow ${d["jetFlowPeak"]}")
            assertTrue(d.getValue("toneRawPeak") < 3.0, "MIDI $midi raw tone peak ${d["toneRawPeak"]}")
            println("CONCH MIDI $midi TOUCH $touch: flow peak %.3f, raw tone peak %.3f, guard %.3f".format(java.util.Locale.ROOT,
                d.getValue("jetFlowPeak"), d.getValue("toneRawPeak"), d.getValue("peakSafetyGain")))
            assertTrue(r.native.all { it.isFinite() })
            // The last lap is no louder than the one before it: the limit cycle holds its level.
            val s = r.loopStart
            val l = r.loopFrames
            assertTrue(rms(r.native, s + l, s + 2 * l) < rms(r.native, s, s + l) * 1.001)
        }
    }

    @Test
    fun `CONCH plays C2 to C5 and its finite length is closed form`() {
        for (midi in listOf(35, 73, 28)) {
            assertFailsWith<IllegalArgumentException> { NacreConch.render(emptyMap(), midi) }
            assertFailsWith<IllegalArgumentException> { NacrePatch("x", NacreVoice.CONCH, emptyMap(), midi = midi) }
        }
        assertEquals(36..72, Nacre.midiRange(NacreVoice.CONCH))
        assertEquals(2.5f, NacreConch.breathSeconds(0f))
        assertEquals(8f, NacreConch.breathSeconds(.98f))
        for (hold in listOf(0f, .3f, .98f)) {
            assertEquals(NacreConch.renderFrames(mapOf("HOLD" to hold), 50), NacreConch.render(mapOf("HOLD" to hold), 50, .6f, 3L).samples.size)
        }
        assertEquals(NacreConch.renderFrames(held, 50), NacreConch.render(held, 50, .6f, 3L).samples.size)
        val (key, start) = NacreConch.renderKey(emptyMap(), 50, .6f, 3L)
        assertContentEquals(NacreConch.render(held, 50, .6f, 3L).samples, key.copyOfRange(start, key.size))
    }

    /**
     * GROTTO (saved model 8, kept by the owner in full) is untouched by CONCH: these are its renders at 416db58fe, the commit
     * the owner kept, hashed before any CONCH line was written. PEARL, HARMONICS, SHELL and BUBBLE are pinned by their own tests.
     */
    @Test
    fun `GROTTO's kept renders are byte-identical`() {
        for ((key, expected) in GROTTO_PINS) {
            val (macros, midi, velocity) = key
            assertEquals(expected, hash(NacreGrotto.render(macros, midi, velocity, 7L).samples), "GROTTO $key moved")
        }
    }

    private companion object {
        val GROTTO_PINS: List<Pair<Triple<Map<String, Float>, Int, Float>, String>> = listOf(
            Triple(emptyMap<String, Float>(), 36, .8f) to "c3556be162227db026ac57a5c2b0883adcfe19e4067eab567f7a89e11a36457f",
            Triple(mapOf("TOUCH" to .7f, "HOLD" to .3f), 48, .6f) to "88ff9df10759d8bbb0dcbcd61217d7456c69ffddba9e27fd37d6f08c183a0618",
            Triple(mapOf("HOLD" to 1f), 36, .8f) to "68e68557de7dd0e3f933d031529a46d0349c5d18359dbe41f5ee1cd2cd9755d1",
            Triple(mapOf("HOLD" to 1f, "DUET" to .55f, "VENUE" to 1f), 60, .35f) to "b3f25c3b7d63fa6172e8708f527b08ae034c968a4f0bdec092ddbc642e03b271",
        )
    }
}
