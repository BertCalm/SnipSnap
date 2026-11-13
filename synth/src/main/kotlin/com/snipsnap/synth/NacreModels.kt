package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** Saved recipes retain their original synthesis, defaults, events and diagnostic semantics. */
internal object NacreModels {
    /** Opt-in source version; model 3 stays the default for existing and partial recipes. */
    const val CONTACT_MODEL_VERSION = 4
    const val CONTACT_MACRO = "CONTACT"
    fun supports(model: Int): Boolean = model in 1..CONTACT_MODEL_VERSION

    private fun contactControls(voice: NacreVoice, macros: Map<String, Float>): Pair<Map<String, Float>, Float> {
        val known = macrosFor(voice, CONTACT_MODEL_VERSION).map { it.name }.toSet()
        require(macros.keys.all { it in known }) { "NACRE model 4 contains unknown macros: ${macros.keys - known}" }
        val loss = macros[CONTACT_MACRO] ?: 0f
        require(loss.isFinite() && loss in 0f..1f) { "NACRE CONTACT must be finite and in 0..1" }
        // Canonical zero reaches the original cache key and skips all new string arithmetic.
        return (macros - CONTACT_MACRO) to if (loss == 0f) 0f else loss
    }

    private fun requireSupported(model: Int) {
        require(supports(model)) { "unsupported nacre model $model" }
    }

    fun macrosFor(voice: NacreVoice, model: Int): List<MacroSpec> {
        requireSupported(model)
        return when (model) {
            1 -> NacreModel1.macrosFor(voice)
            2 -> NacreModel2.macrosFor(voice)
            3 -> Nacre.macrosFor(voice)
            else -> Nacre.macrosFor(voice).let { it.dropLast(1) + MacroSpec(CONTACT_MACRO, 0f, 0f) + it.last() }
        }
    }

    fun defaults(voice: NacreVoice, model: Int): Map<String, Float> {
        requireSupported(model)
        return when (model) {
            1 -> NacreModel1.defaults(voice)
            2 -> NacreModel2.defaults(voice)
            3 -> Nacre.defaults(voice)
            else -> macrosFor(voice, model).associate { it.name to it.default }
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
            3 -> Nacre.render(voice, macros, midi, velocity, seed)
            else -> contactControls(voice, macros).let { (source, loss) ->
                Nacre.renderContactProfile(voice, source, midi, velocity, seed, loss)
            }
        }
    }

    /**
     * Model-4 render with an explicit [NacreStrikeShape] (a velocity layer's pluck). The patch's own
     * render is this call with [NacreStrikeShape.DEFAULT]; earlier saved models have no strike input.
     */
    fun renderStruck(patch: NacrePatch, strike: NacreStrikeShape): Snip {
        require(patch.model == CONTACT_MODEL_VERSION) { "NACRE strike shapes apply only to saved model $CONTACT_MODEL_VERSION" }
        return contactControls(patch.voice, patch.macros).let { (source, loss) ->
            Nacre.renderContactProfile(patch.voice, source, patch.midi, patch.velocity, patch.seed, loss, strike)
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
            3 -> Nacre.inspect(voice, macros, midi, velocity, seed, options)
            else -> contactControls(voice, macros).let { (source, loss) ->
                val probe = Nacre.inspectContactStudy(voice, source, midi, velocity, seed, options, loss)
                probe.copy(diagnostics = (probe.diagnostics - "contactStudyRegisteredOrSaved") + linkedMapOf(
                    "modelVersion" to CONTACT_MODEL_VERSION.toDouble(),
                    "savedContactProfileVersion" to NacreContactStudy.PROFILE_VERSION.toDouble(),
                    "savedContactAdditionalLoss" to loss.toDouble(),
                    "savedContactLossT60Seconds" to NacreContactStudy.LOSS_T60_SECONDS,
                ))
            }
        }
    }
}
