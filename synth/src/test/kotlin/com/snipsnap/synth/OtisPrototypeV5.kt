package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** New air character, accepted Shared pressure, and the unchanged model-4 Organ/output stage. */
internal object OtisPrototypeV5 {
    fun render(request: OtisPrototypeRequest): Snip {
        require(request.model == 5)
        if (request.voice == OtisPrototypeVoice.ORGAN) return OtisPrototypeV4.render(request.copy(model = 4))
        return OtisPrototypeV4.condition(OtisAirCharacterV5.synthesize(request, Dsp.RATE * Dsp.OVERSAMPLE))
    }
}
