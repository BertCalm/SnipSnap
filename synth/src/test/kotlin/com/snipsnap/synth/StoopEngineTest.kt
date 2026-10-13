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

    @Test fun `an interrupted render stops instead of finishing beside its replacement`() {
        // SynthScreen's preview runs inside runInterruptible: a slider or engine change interrupts the thread.
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<java.util.concurrent.CancellationException> { Stoop.render(StoopVoice.entries.first()) }
        } finally {
            Thread.interrupted()
        }
        assertTrue(Stoop.render(StoopVoice.entries.first()).samples.any { it != 0f }, "an uninterrupted render still completes")
    }

    @Test fun `an interrupted held render stops instead of finishing beside its replacement`() {
        // The held path warms its loop before the passes; it must stop on the flag as the finite one does.
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<java.util.concurrent.CancellationException> { Stoop.render(StoopVoice.entries.first(), mapOf("HOLD" to 1f)) }
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun `the conditioning pass looks again after its band limit when the render is no longer wanted`() {
        // The pass looks on entry, so a flag set before it is caught there and says nothing about the look
        // between its band limit and its decimation; an interrupt that lands after that first look must still stop it.
        val late = LateInterrupt(clearLooks = 1)
        assertFailsWith<java.util.concurrent.CancellationException> {
            late.run { Stoop.condition(FloatArray(Dsp.OVERSAMPLE * 4096)) }
        }
        assertEquals(2, late.looks, "the pass stopped at its look after the band limit")
        assertEquals(4096, Stoop.condition(FloatArray(Dsp.OVERSAMPLE * 4096)).size, "an uninterrupted pass still conditions")
    }

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

    @Test fun `held contact and companion articulation change spectrum after gain matching`() {
        fun distance(a: FloatArray, b: FloatArray): Double {
            assertEquals(a.size, b.size)
            val ra = rms(a)
            val rb = rms(b)
            assertTrue(ra > 1e-6 && rb > 1e-6)
            var sum = 0.0
            for (i in a.indices) {
                val delta = a[i] / ra - b[i] / rb
                sum += delta * delta
            }
            return sqrt(sum / a.size)
        }
        fun spectralDistance(a: FloatArray, b: FloatArray): Double {
            fun magnitude(x: FloatArray): DoubleArray {
                val n = 16_384
                val re = FloatArray(n) { i -> x[i] * (.5 - .5 * cos(2.0 * PI * i / n)).toFloat() }
                val im = FloatArray(n)
                Fft.forward(re, im)
                val bins = DoubleArray(n / 2) { i -> hypot(re[i].toDouble(), im[i].toDouble()) }
                val norm = sqrt(bins.sumOf { it * it })
                return DoubleArray(bins.size) { bins[it] / norm }
            }
            val ma = magnitude(a)
            val mb = magnitude(b)
            return sqrt(ma.indices.sumOf { (ma[it] - mb[it]).pow(2) })
        }
        for (voice in StoopVoice.entries) {
            val p = patch(voice, mapOf("HOLD" to 1f, "COLOR" to .5f, "LENGTH" to .5f,
                "WEAR" to 0f, "SHADOW" to 1f, "TOUCH" to .5f)).copy(midi = 60)
            val rounded = Stoop.renderProbe(p.copy(macros = p.macros + ("TOUCH" to 0f)))
            val accented = Stoop.renderProbe(p.copy(macros = p.macros + ("TOUCH" to 1f)))
            val softReply = Stoop.renderProbe(p.copy(shadowArticulation = 0f))
            val hardReply = Stoop.renderProbe(p.copy(shadowArticulation = 1f))
            val contact = distance(rounded.main, accented.main)
            val companion = distance(softReply.shadow, hardReply.shadow)
            val contactSpectrum = spectralDistance(rounded.main, accented.main)
            val companionSpectrum = spectralDistance(softReply.shadow, hardReply.shadow)
            println("STOOP held $voice gain-matched TOUCH distance=$contact spectrum=$contactSpectrum, companion articulation distance=$companion spectrum=$companionSpectrum")
            // RMS matching removes a scalar level change; the kept region
            // begins after bounded preroll, so attack changes cannot pass.
            assertTrue(contact > .01, "$voice held TOUCH only changed level: $contact")
            assertTrue(companion > .01, "$voice held companion articulation only changed level: $companion")
            // Magnitude spectra also differ, excluding a pure phase shift.
            assertTrue(contactSpectrum > .005, "$voice held TOUCH only moved phase: $contactSpectrum")
            assertTrue(companionSpectrum > .005, "$voice held companion articulation only moved phase: $companionSpectrum")
            assertContentEquals(softReply.main, hardReply.main, "$voice companion articulation leaked into main")
            for (r in listOf(rounded, accented, softReply, hardReply)) {
                assertTrue(Keys.seamError(r.previousCycle + r.samples, 256) < Keys.MAX_SEAM_ERROR)
                assertEquals(256, r.previousMain.size)
                assertEquals(256, r.previousShadow.size)
                assertTrue(Keys.seamError(r.previousMain + r.main, 256) < Keys.MAX_SEAM_ERROR)
                assertTrue(Keys.seamError(r.previousShadow + r.shadow, 256) < Keys.MAX_SEAM_ERROR)
            }
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
