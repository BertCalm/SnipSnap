package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** Squeeze-only chord motion revision; the other families retain model5 samples exactly. */
internal object OtisPrototypeV6 {
    fun render(request: OtisPrototypeRequest): Snip {
        require(request.model == 6)
        if (request.voice != OtisPrototypeVoice.SQUEEZE)
            return OtisPrototypeV5.render(request.copy(model = 5))
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val frames = OtisAirCharacterV5.frameCount(request.voice, request.notes, request.air, rate)
        val pressure = OtisAirCharacterV5.trace(request.voice, request.notes, request.air, frames, request.sharedPressure, rate)
        return OtisPrototypeV4.condition(
            OtisSqueezeCharacterV6.synthesize(request.notes, request.air, request.seed, rate, pressure))
    }
}
