package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Snip

/** Frozen models 1–3 include their output processing, not just their oscillator code. */
internal object OtisPrototypeLegacy {
    fun render(request: OtisPrototypeRequest): Snip {
        require(request.model in 1..3)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val internal = when {
            request.model == 1 && request.voice == OtisPrototypeVoice.ORGAN ->
                OtisOrganPrototypeV1.synthesize(request.notes, request.organ, request.seed, rate)
            request.model == 1 ->
                OtisAirPrototypeV1.synthesize(request.voice, request.notes, request.air, request.seed, rate)
            request.model == 2 && request.voice != OtisPrototypeVoice.ORGAN ->
                OtisAirPrototypeV2.synthesize(request.voice, request.notes, request.air, request.seed, rate)
            request.voice == OtisPrototypeVoice.ORGAN ->
                OtisOrganPrototype.synthesize(request.notes, request.organ, request.seed, rate)
            else -> OtisAirPrototype.synthesize(request.voice, request.notes, request.air, request.seed, rate)
        }
        check(internal.isNotEmpty() && internal.all { it.isFinite() }) { "nonfinite or empty prototype render" }
        val source = Snip(Dsp.decimate(internal, Dsp.RATE), channels = 1, sampleRate = Dsp.RATE)
        val out = Cleanup.removeDcOffset(source)
        val head = minOf(6, out.frameCount)
        for (i in 0 until head) out.samples[i] *= i.toFloat() / head
        Dsp.fadeTail(out.samples)
        return out
    }
}
