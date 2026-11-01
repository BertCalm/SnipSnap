package com.snipsnap.synth

import com.snipsnap.audio.Snip

/** Finite-note research harness. It does not register an engine or alter saved TONEWHEEL recipes. */
enum class OtisPrototypeVoice { ORGAN, STEAM, HARP, SQUEEZE }

data class OtisPrototypeNote(
    val midi: Int = 60,
    val startSeconds: Double = 0.0,
    val gateSeconds: Double = 1.5,
    val velocity: Float = .8f,
) {
    init {
        require(midi in 36..84) { "prototype MIDI must be 36..84: $midi" }
        require(startSeconds.isFinite() && startSeconds in 0.0..12.0) { "prototype start must be 0..12 seconds" }
        require(gateSeconds.isFinite() && gateSeconds in .04..8.0) { "prototype gate must be .04..8 seconds" }
        require(velocity.isFinite() && velocity in 0f..1f) { "prototype velocity must be 0..1" }
    }
}

data class OtisPrototypeRequest(
    val voice: OtisPrototypeVoice,
    val notes: List<OtisPrototypeNote> = listOf(OtisPrototypeNote()),
    val seed: Int = 20261014,
    val organ: OtisOrganSettings = OtisOrganSettings(),
    val air: OtisAirSettings = OtisAirSettings(),
    val model: Int = OtisPrototype.MODEL_VERSION,
    /** Model 5+ player budget; earlier models retain their independent source envelopes. */
    val sharedPressure: Float = .85f,
) {
    init {
        require(notes.isNotEmpty() && notes.size <= 32) { "prototype requires 1..32 notes" }
        require(model in 1..OtisPrototype.MODEL_VERSION) { "unsupported Otis prototype model $model" }
        require(sharedPressure.isFinite() && sharedPressure in 0f..1f) { "sharedPressure must be finite and normalized" }
    }

    fun render(): Snip = OtisPrototype.render(this)
}

object OtisPrototype {
    const val MODEL_VERSION = 6

    /** Conditioned source audio before loudness matching; no FX rack or peak normalization. */
    fun render(request: OtisPrototypeRequest): Snip {
        return when (request.model) {
            in 1..3 -> OtisPrototypeLegacy.render(request)
            4 -> OtisPrototypeV4.render(request)
            5 -> OtisPrototypeV5.render(request)
            else -> OtisPrototypeV6.render(request)
        }
    }
}
