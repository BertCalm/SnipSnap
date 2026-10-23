package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** Dispatch saved recipes without changing the renderer or defaults their model originally used. */
internal object NacreModels {
    fun supports(model: Int): Boolean = model == NacreModel1.MODEL_VERSION || model == Nacre.MODEL_VERSION

    private fun requireSupported(model: Int) {
        require(supports(model)) { "unsupported nacre model $model" }
    }

    fun macrosFor(voice: NacreVoice, model: Int): List<MacroSpec> {
        requireSupported(model)
        return if (model == NacreModel1.MODEL_VERSION) NacreModel1.macrosFor(voice) else Nacre.macrosFor(voice)
    }

    fun defaults(voice: NacreVoice, model: Int): Map<String, Float> {
        requireSupported(model)
        return if (model == NacreModel1.MODEL_VERSION) NacreModel1.defaults(voice) else Nacre.defaults(voice)
    }

    fun render(
        voice: NacreVoice = NacreVoice.PEARL,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = Nacre.DEFAULT_MIDI,
        velocity: Float = 1f,
        seed: Long = Nacre.DEFAULT_SEED,
        model: Int = Nacre.MODEL_VERSION,
    ): Snip {
        requireSupported(model)
        return if (model == NacreModel1.MODEL_VERSION) NacreModel1.render(voice, macros, midi, velocity, seed)
            else Nacre.render(voice, macros, midi, velocity, seed)
    }

    fun inspect(
        voice: NacreVoice = NacreVoice.PEARL,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = Nacre.DEFAULT_MIDI,
        velocity: Float = 1f,
        seed: Long = Nacre.DEFAULT_SEED,
        model: Int = Nacre.MODEL_VERSION,
        options: Nacre.Options = Nacre.Options(),
    ): Nacre.Probe {
        requireSupported(model)
        if (model != NacreModel1.MODEL_VERSION) return Nacre.inspect(voice, macros, midi, velocity, seed, options)
        require(!options.lockStrumTiming) { "NACRE model 1 does not support the locked strum timing diagnostic" }
        val legacy = NacreModel1.inspect(voice, macros, midi, velocity, seed, NacreModel1.Options(
            muteBassPlucks = options.muteBassPlucks,
            muteUpperPlucks = options.muteUpperPlucks,
            disableCrossHarp = options.disableCrossHarp,
            postCurrentOnly = options.postCurrentOnly,
            bypassVenue = options.bypassVenue,
        ))
        return Nacre.Probe(legacy.snip, legacy.raw, legacy.stems, legacy.events.map { event ->
            Nacre.Event(event.performer, event.stringId, event.timeSeconds, event.frequencyHz,
                event.strength, event.direction)
        }, legacy.diagnostics)
    }
}
