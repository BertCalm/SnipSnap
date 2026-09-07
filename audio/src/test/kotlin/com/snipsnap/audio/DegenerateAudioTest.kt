package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The hardening matrix (SS2): every engine added since the DD3
 * invariants meets every degenerate shape a capture can arrive in.
 * The contract is binary and named: an engine either returns audio
 * whose every sample is finite, or refuses with an
 * IllegalArgumentException that says why. No NaN, no Inf, no other
 * throwable, no silence turned into invented noise.
 */
class DegenerateAudioTest {

    private val rate = 44_100

    private fun fixtures(): List<Pair<String, Snip>> {
        val square = FloatArray(rate / 2) { i -> if ((i / 100) % 2 == 0) 1f else -1f }
        return listOf(
            "one sample" to Snip(floatArrayOf(0.5f), 1, rate),
            "tiny" to Snip(FloatArray(44) { i -> (0.5 * Math.sin(2.0 * Math.PI * 440 * i / rate)).toFloat() }, 1, rate),
            "silence" to Snip(FloatArray(rate / 2), 1, rate),
            "pure DC" to Snip(FloatArray(rate / 2) { 0.5f }, 1, rate),
            "full-scale square" to Snip(square, 1, rate),
            "low rate" to Snip(FloatArray(2400) { i -> (0.5 * Math.sin(2.0 * Math.PI * 200 * i / 8000)).toFloat() }, 1, 8000),
            "stereo" to Snip(
                FloatArray(rate) { i -> (0.4 * Math.sin(2.0 * Math.PI * (if (i % 2 == 0) 300.0 else 500.0) * (i / 2) / rate)).toFloat() },
                2, rate,
            ),
        )
    }

    private fun engines(): List<Pair<String, (Snip) -> Snip?>> = listOf(
        "Granular.render" to { s -> Granular.render(s, 1f, 1) },
        "Stretch.stretch" to { s -> Stretch.stretch(s, 4f, 1) },
        "Stretch.freeze" to { s -> Stretch.freeze(s, 1f, 1) },
        "Pghi.stretch" to { s -> Pghi.stretch(s, 2f, 1) },
        "Separate.hpss" to { s -> Separate.hpss(s).let { split -> checkFinite("hpss.percussive", split.percussive); split.harmonic } },
        "Separate.stn" to { s -> Separate.stn(s).let { stn -> checkFinite("stn.transients", stn.transients); checkFinite("stn.noise", stn.noise); stn.sines } },
        "Retime.retime" to { s -> Retime.retime(s, 1.5f, 1) },
        "CaptureDoctor.clean(all legs)" to { s -> CaptureDoctor.clean(s, denoise = true, declip = true, deverb = true).snip },
        "CaptureDoctor.trimRoomTail" to { s -> CaptureDoctor.trimRoomTail(s)?.snip },
        "CaptureDoctor.deverb" to { s -> CaptureDoctor.deverb(s) },
    )

    private fun checkFinite(label: String, snip: Snip) {
        for (v in snip.samples) {
            assertTrue(v.isFinite(), "$label produced a non-finite sample")
        }
    }

    @Test
    fun `every engine returns finite audio or refuses by name - across every degenerate shape`() {
        for ((fixtureName, snip) in fixtures()) {
            for ((engineName, engine) in engines()) {
                val result = runCatching { engine(snip) }
                result.fold(
                    { out ->
                        if (out != null) checkFinite("$engineName on $fixtureName", out)
                    },
                    { e ->
                        assertTrue(
                            e is IllegalArgumentException,
                            "$engineName on $fixtureName threw ${e::class.simpleName}: ${e.message}",
                        )
                        assertTrue(!e.message.isNullOrBlank(), "$engineName on $fixtureName refused without saying why")
                    },
                )
            }
        }
    }

    @Test
    fun `silence in means silence or refusal out - never invented sound`() {
        val silence = Snip(FloatArray(rate / 2), 1, rate)
        for ((engineName, engine) in engines()) {
            val out = runCatching { engine(silence) }.getOrNull() ?: continue
            val peak = out.samples.maxOfOrNull { Math.abs(it) } ?: 0f
            assertTrue(peak < 1e-4f, "$engineName invented sound from silence: peak $peak")
        }
    }
}
