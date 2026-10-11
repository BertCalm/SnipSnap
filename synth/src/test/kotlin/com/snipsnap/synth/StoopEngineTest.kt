package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class StoopEngineTest {
    private fun patch(voice: StoopVoice, macros: Map<String, Float> = emptyMap()) =
        StoopPatch("Test", voice, Stoop.defaults(voice) + macros)
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))

    @Test fun `source rejects invalid independent configurations`() {
        assertFailsWith<IllegalArgumentException> { StoopSource(fmRatio = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { StoopSource(sub = 1.01f) }
        assertFailsWith<IllegalArgumentException> { StoopSource(detuneCents = 21f) }
        assertFailsWith<IllegalArgumentException> { StoopSource(fmIndex = -1f) }
        for (voice in StoopVoice.entries) {
            assertEquals(listOf("TOUCH", "COLOR", "SHADOW", "WEAR", "LENGTH", "HOLD"), Stoop.macrosFor(voice).map { it.name })
            val rolled = Stoop.scramble(voice, Random(5), .4f)
            assertEquals(Stoop.defaults(voice).keys, rolled.keys)
            assertTrue(rolled.values.all { it in 0f..1f })
        }
    }

    @Test fun `all sources and seeded character reproduce identical audio`() {
        for (voice in StoopVoice.entries) {
            val p = patch(voice, mapOf("WEAR" to .88f, "SHADOW" to .75f))
            assertContentEquals(Stoop.renderRaw(p).samples, Stoop.renderRaw(p).samples, voice.name)
            assertContentEquals(Stoop.render(p).samples, Stoop.render(p).samples, voice.name)
        }
    }

    @Test fun `raw velocity retains energy before shared leveling`() {
        for (voice in StoopVoice.entries) {
            val p = patch(voice, mapOf("WEAR" to 0f, "SHADOW" to 0f))
            val soft = rms(Stoop.renderRaw(p.copy(velocity = .2f)).samples)
            val firm = rms(Stoop.renderRaw(p.copy(velocity = .9f)).samples)
            assertTrue(firm > 2.5 * soft, "$voice soft=$soft firm=$firm")
            assertEquals(0.0, rms(Stoop.renderRaw(p.copy(velocity = 0f)).samples))
        }
    }

    @Test fun `shadow is freshly articulated at each explicit schedule`() {
        val p = patch(StoopVoice.TINE, mapOf("WEAR" to 0f, "LENGTH" to 0f, "SHADOW" to 1f))
            .copy(shadowDelayMs = 250f, shadowInterval = 12, shadowReplies = 2, shadowArticulation = .12f)
        val r = Stoop.renderProbe(p)
        assertEquals(listOf(11_025, 22_050), r.events.map { it.frame })
        assertEquals(listOf(p.midi + 12, p.midi + 12), r.events.map { it.midi })
        assertTrue(r.events.all { it.articulation < p.macros.getValue("TOUCH") })
        for (event in r.events) assertTrue(r.samples.size >= event.frame + event.durationFrames)
        assertTrue(r.shadow.take(r.events.first().frame - 64).all { abs(it) < 1e-7f })
        val direct = Stoop.renderProbe(p.copy(macros = p.macros + ("SHADOW" to 0f)))
        assertTrue(direct.events.isEmpty())
        assertTrue(direct.shadow.all { it == 0f })
        // The output filters remain linear with WEAR=0; the sum is measurable.
        val from = 128
        val to = r.samples.size - 512
        var error = 0.0
        for (i in from until to) error = max(error, abs(r.samples[i] - r.main[i] - r.shadow[i]).toDouble())
        assertTrue(error < 1e-5, "clean main+shadow error $error")
    }

    @Test fun `finite length shapes the gesture and never exceeds host bounds`() {
        for (voice in StoopVoice.entries) {
            val tight = Stoop.renderRaw(patch(voice, mapOf("LENGTH" to 0f, "SHADOW" to 0f)))
            val long = Stoop.renderRaw(patch(voice, mapOf("LENGTH" to 1f, "SHADOW" to 1f, "HOLD" to .98f))
                .copy(shadowReplies = 2, shadowDelayMs = 250f))
            assertTrue(tight.samples.size < long.samples.size / 3, "$voice duration curve")
            assertTrue(long.samples.size <= Stoop.RATE * Stoop.MAX_SECONDS)
            assertTrue(long.samples.all { it.isFinite() && abs(it) < 2f })
        }
    }

    @Test fun `held output closes against continuous history with active character and companions`() {
        for (voice in StoopVoice.entries) {
            val p = patch(voice, mapOf("HOLD" to 1f, "WEAR" to 1f, "SHADOW" to 1f, "LENGTH" to .9f))
            val r = Stoop.renderProbe(p)
            assertEquals(256, r.previousCycle.size)
            assertTrue(r.prerollFrames > Stoop.RATE)
            assertTrue(r.samples.size <= Stoop.RATE * Stoop.MAX_SECONDS)
            val measured = Keys.seamError(r.previousCycle + r.samples, 256)
            assertEquals(r.seamError, measured, 1e-12)
            assertTrue(measured < Keys.MAX_SEAM_ERROR, "$voice held seam $measured")
            assertTrue(r.maxPitchAdjustmentCents < 5.0, "$voice fitted ${r.maxPitchAdjustmentCents} cents")
            assertTrue(r.events.size == 1)
        }
    }

    @Test fun `zero velocity held audio and normalized probes remain exact silence`() {
        for (voice in StoopVoice.entries) {
            val p = patch(voice, mapOf("HOLD" to 1f, "SHADOW" to 1f, "WEAR" to 1f)).copy(velocity = 0f)
            assertTrue(Stoop.renderRaw(p).samples.all { it == 0f }, "$voice raw held silence")
            assertTrue(Stoop.render(p).samples.all { it == 0f }, "$voice normalized held silence")
            val probe = Stoop.renderProbe(p, normalize = true)
            assertEquals(0.0, probe.seamError, "$voice silent seam")
            assertEquals(256, probe.previousCycle.size)
            assertTrue(probe.samples.all { it == 0f } && probe.previousCycle.all { it == 0f })
            assertTrue(probe.main.all { it == 0f } && probe.shadow.all { it == 0f })
        }
    }

    @Test fun `explicit intervals remain safe at both root boundaries`() {
        for (voice in StoopVoice.entries) for (midi in listOf(Stoop.MIDI_MIN, Stoop.MIDI_MAX)) {
            val p = patch(voice, mapOf("COLOR" to 1f, "TOUCH" to 1f, "LENGTH" to 0f, "SHADOW" to 1f, "WEAR" to 1f))
                .copy(midi = midi, shadowInterval = if (midi == Stoop.MIDI_MIN) -24 else 24,
                    chordIntervals = if (midi == Stoop.MIDI_MIN) listOf(0, -24, -12) else listOf(0, 12, 24))
            val r = Stoop.renderRaw(p)
            assertTrue(r.samples.all { it.isFinite() && abs(it) < 2f }, "$voice at $midi")
            assertTrue(r.samples.size <= Stoop.RATE * Stoop.MAX_SECONDS)
        }
    }

    @Test fun `zero wear bypass is exact and coarse folded energy stays bounded`() {
        val untouched = FloatArray(200) { sin(it * .27).toFloat() }
        val original = untouched.copyOf()
        Stoop.recordingCharacter(untouched, 0.0, StoopVoice.TINE, .6)
        assertContentEquals(original, untouched)
        // Coherent sine: true harmonics occupy known bins. Energy elsewhere
        // after the 4x quantizer/anti-alias chain measures folded sidebands.
        // A 16x reference distinguishes that energy from the intended grain.
        val n = 65_536
        val bin = 997
        val hz = bin.toDouble() * Stoop.RATE / n
        val warm = Stoop.RATE / 3
        val frames = warm + n + 256
        fun renderAt(over: Int): FloatArray {
            val rate = Stoop.RATE * over
            val x = FloatArray(frames * over) { i -> (.36 * sin(2.0 * PI * hz * i / rate)).toFloat() }
            Stoop.recordingCharacter(x, 1.0, StoopVoice.TINE, .6, rate)
            Tide.bandLimit(x, rate)
            var snip = Snip(x, channels = 1, sampleRate = rate)
            var current = rate
            while (current > Stoop.RATE) { current /= 2; snip = Resampler.resample(snip, current) }
            return snip.samples.copyOfRange(warm, warm + n)
        }
        fun offHarmonic(x: FloatArray): Pair<Double, Double> {
            val re = FloatArray(n) { i -> x[i] * (.5 - .5 * cos(2.0 * PI * i / n)).toFloat() }
            val im = FloatArray(n)
            Fft.forward(re, im)
            var other = 0.0
            var total = 0.0
            for (k in 1 until n / 2) {
                val energy = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                total += energy
                val h = (k.toDouble() / bin).roundToInt()
                if (h == 0 || abs(k - h * bin) > 3) other += energy
            }
            return other to total
        }
        val at4 = offHarmonic(renderAt(4))
        val at16 = offHarmonic(renderAt(16))
        val folded = max(0.0, at4.first - at16.first) / at4.second
        val db = 10.0 * log10(max(folded, 1e-15))
        println("STOOP full-WEAR quantization folded energy vs 16x reference: $db dB")
        assertTrue(db < -35.0, "folded quantizer energy $db dB")
    }
}
