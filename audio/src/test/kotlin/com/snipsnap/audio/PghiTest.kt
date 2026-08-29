package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertTrue

class PghiTest {

    private val rate = 44_100

    private fun probe(samples: FloatArray, hz: Float): Float =
        CaptureDoctor.goertzel(samples, samples.size, hz, rate)

    @Test
    fun `magnitudes alone rebuild a tone - and a beat keeps its onsets`() {
        val tone = Snip(
            FloatArray(rate) { i -> (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / rate)).toFloat() },
            1, rate,
        )
        val mags = mutableListOf<FloatArray>()
        Spectral.forEachFrame(tone) { _, _, m -> mags.add(m.copyOf()) }
        val back = Pghi.invert(mags, tone.frameCount, rate)
        val orig = probe(tone.samples, 440f)
        val rebuilt = probe(back.samples, 440f)
        assertTrue(
            Math.abs(rebuilt - orig) < 0.15f * orig,
            "the tone comes back from magnitudes alone: $orig -> $rebuilt",
        )
        fun rms(s: FloatArray): Double = Math.sqrt(s.sumOf { (it * it).toDouble() } / s.size)
        assertTrue(
            Math.abs(rms(back.samples) - rms(tone.samples)) < 0.2 * rms(tone.samples),
            "and at its own level: ${rms(tone.samples)} -> ${rms(back.samples)}",
        )

        // A beat's hits still land where they landed.
        val beat = FloatArray(2 * rate)
        val hitAt = listOf(0.2f, 0.7f, 1.2f, 1.7f)
        for (at in hitAt) {
            val hit = DrumSynth.snare()
            val start = (at * rate).toInt()
            for (i in hit.samples.indices) {
                val idx = start + i
                if (idx < beat.size) beat[idx] += hit.samples[i] * 0.8f
            }
        }
        val beatMags = mutableListOf<FloatArray>()
        Spectral.forEachFrame(Snip(beat, 1, rate)) { _, _, m -> beatMags.add(m.copyOf()) }
        val beatBack = Pghi.invert(beatMags, beat.size, rate)
        val onsets = Transients.detect(beatBack).map { it.frame.toFloat() / rate }
        for (at in hitAt) {
            assertTrue(
                onsets.any { Math.abs(it - at) < 0.03f },
                "the hit at ${at}s survives the round trip: heard $onsets",
            )
        }
    }

    @Test
    fun `the clear stretch caps its output like its sibling - an absurd ask is bounded, not an OOM`() {
        // 10 s at a factor of 100 asks for 1000 s; the ceiling answers 300.
        val long = Snip(FloatArray(10 * 8000), 1, 8000)
        val out = Pghi.stretch(long, factor = 100f, seed = 1)
        assertTrue(
            out.frameCount == (Pghi.MAX_OUT_SEC * 8000).toInt(),
            "capped at the ceiling: ${out.frameCount} frames",
        )
    }

    @Test
    fun `the clear stretch keeps a sine a narrow line - the wash admits it can't`() {
        val tone = Snip(
            FloatArray(rate / 2) { i -> (0.5 * Math.sin(2.0 * Math.PI * 440.0 * i / rate)).toFloat() },
            1, rate,
        )
        val clear = Pghi.stretch(tone, factor = 4f, seed = 3)
        assertTrue(
            Math.abs(clear.frameCount - 2 * rate) < Spectral.FRAME,
            "four times longer: ${clear.frameCount}",
        )
        fun lineRatio(s: FloatArray): Float {
            val inBand = CaptureDoctor.goertzel(s, s.size, 440f, rate)
            val off = CaptureDoctor.goertzel(s, s.size, 415.3f, rate) +
                CaptureDoctor.goertzel(s, s.size, 466.16f, rate)
            return inBand / (off + 1e-9f)
        }
        val clearRatio = lineRatio(clear.samples)
        assertTrue(clearRatio > 20f, "a narrow line: $clearRatio")

        // The paulstretch wash of the same tone is honestly wider.
        val washSnip = Stretch.stretch(tone, factor = 4f, seed = 3)
        val wash = FloatArray(washSnip.frameCount) { f -> (washSnip.samples[f * 2] + washSnip.samples[f * 2 + 1]) / 2f }
        assertTrue(clearRatio > 2 * lineRatio(wash), "clear beats the wash on line width: $clearRatio vs ${lineRatio(wash)}")

        // Deterministic per seed.
        val again = Pghi.stretch(tone, factor = 4f, seed = 3)
        assertTrue(clear.samples.contentEquals(again.samples), "same seed, same phases")
    }
}
