package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** Saved Relay recipes keep their pulse law; new recipes use the periodic model. */
internal object RelayModels {
    fun supports(model: Int): Boolean = model == RelayPatch.LEGACY_MODEL_VERSION || model == RelayPatch.MODEL_VERSION

    /** Model 2's sounding pulse. The serialized accent roster is inactive in this model. */
    internal data class Pulse(
        val depthDb: Double,
        val minimumGain: Double,
        val rateHz: Double,
        /** Whole cycles in the actual fitted held phrase, null for a finite note. */
        val cycles: Int?,
    )

    internal data class Report(
        /** The unmodified legacy report, or the PULSE-0 source report for model 2. */
        val base: Relay.Report,
        val snip: Snip,
        val previousCycle: FloatArray,
        val seamError: Double,
        val pulse: Pulse?,
    ) {
        val loop get() = base.loop
        val requestedHz get() = base.requestedHz
        val actualHz get() = base.actualHz
        val phraseSeconds get() = base.phraseSeconds
        val gateSeconds get() = base.gateSeconds
        /** Actual 4x source peak before the optional finished-sample pulse stage. */
        val rawPeak get() = base.rawPeak
        val weights get() = base.weights
        val accents get() = if (pulse == null) base.accents else emptyList()
    }

    fun render(voice: RelayVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
               normalize: Boolean = true, phrase: RelayPhrase = Relay.phraseFor(voice),
               model: Int = RelayPatch.MODEL_VERSION): Snip =
        inspect(voice, macros, velocity, normalize, phrase, model).snip

    internal fun inspect(voice: RelayVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
                         normalize: Boolean = true, phrase: RelayPhrase = Relay.phraseFor(voice),
                         model: Int = RelayPatch.MODEL_VERSION): Report {
        require(supports(model)) { "unsupported relay model $model" }
        return if (model == RelayPatch.LEGACY_MODEL_VERSION) {
            val legacy = Relay.inspect(voice, macros, velocity, normalize, phrase)
            Report(legacy, legacy.snip, legacy.previousCycle, legacy.seamError, null)
        } else RelayPulse.inspect(voice, macros, velocity, normalize, phrase)
    }
}
