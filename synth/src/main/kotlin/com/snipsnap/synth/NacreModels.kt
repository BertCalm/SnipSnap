package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** Saved recipes retain their original synthesis, defaults, events and diagnostic semantics. */
internal object NacreModels {
    fun supports(model: Int): Boolean = model in 1..3

    private fun requireSupported(model: Int) {
        require(supports(model)) { "unsupported nacre model $model" }
    }

    fun macrosFor(voice: NacreVoice, model: Int): List<MacroSpec> {
        requireSupported(model)
        return when (model) {
            1 -> NacreModel1.macrosFor(voice)
            2 -> NacreModel2.macrosFor(voice)
            else -> Nacre.macrosFor(voice)
        }
    }

    fun defaults(voice: NacreVoice, model: Int): Map<String, Float> {
        requireSupported(model)
        return when (model) {
            1 -> NacreModel1.defaults(voice)
            2 -> NacreModel2.defaults(voice)
            else -> Nacre.defaults(voice)
        }
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
        return when (model) {
            1 -> NacreModel1.render(voice, macros, midi, velocity, seed)
            2 -> NacreModel2.render(voice, macros, midi, velocity, seed)
            else -> Nacre.render(voice, macros, midi, velocity, seed)
        }
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
        return when (model) {
            1 -> {
                require(!options.lockStrumTiming) { "NACRE model 1 does not support the locked strum timing diagnostic" }
                val legacy = NacreModel1.inspect(voice, macros, midi, velocity, seed, NacreModel1.Options(
                    muteBassPlucks = options.muteBassPlucks,
                    muteUpperPlucks = options.muteUpperPlucks,
                    disableCrossHarp = options.disableCrossHarp,
                    postCurrentOnly = options.postCurrentOnly,
                    bypassVenue = options.bypassVenue,
                ))
                Nacre.Probe(legacy.snip, legacy.raw, legacy.stems, legacy.events.map {
                    Nacre.Event(it.performer, it.stringId, it.timeSeconds, it.frequencyHz, it.strength, it.direction)
                }, legacy.diagnostics)
            }
            2 -> {
                val legacy = NacreModel2.inspect(voice, macros, midi, velocity, seed, NacreModel2.Options(
                    muteBassPlucks = options.muteBassPlucks,
                    muteUpperPlucks = options.muteUpperPlucks,
                    disableCrossHarp = options.disableCrossHarp,
                    postCurrentOnly = options.postCurrentOnly,
                    bypassVenue = options.bypassVenue,
                    lockStrumTiming = options.lockStrumTiming,
                ))
                Nacre.Probe(legacy.snip, legacy.raw, legacy.stems, legacy.events.map {
                    Nacre.Event(it.performer, it.stringId, it.timeSeconds, it.frequencyHz, it.strength, it.direction)
                }, legacy.diagnostics)
            }
            else -> Nacre.inspect(voice, macros, midi, velocity, seed, options)
        }
    }
}
