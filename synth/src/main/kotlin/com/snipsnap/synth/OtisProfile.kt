package com.snipsnap.synth

import java.util.Collections

/** Stable source identities; HARP is the blues-harmonica family. */
enum class OtisVoice { ORGAN, STEAM, HARP, SQUEEZE }

/** One finite event relative to the recipe's saved MIDI anchor. */
data class OtisEvent(val semitones: Int = 0, val startSeconds: Double = 0.0,
    val gateSeconds: Double = 1.5, val velocity: Float = .8f) {
    init {
        require(semitones in -12..12) { "OTIS event interval must be within one octave" }
        require(startSeconds.isFinite() && startSeconds in 0.0..6.0) { "OTIS event start must be 0..6 seconds" }
        require(gateSeconds.isFinite() && gateSeconds in .04..4.0) { "OTIS event gate must be .04..4 seconds" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OTIS event velocity must be finite and normalized" }
    }
}

/** Internal absolute-pitch input at the accepted synthesis clock. */
internal data class OtisNote(val midi: Int, val startSeconds: Double, val gateSeconds: Double, val velocity: Float)

/** Complete finite score and source settings, stored instead of reconstructed from a preset name. */
data class OtisProfile(val events: List<OtisEvent> = listOf(OtisEvent()),
    val air: OtisAirProfile = OtisAirProfile(), val organ: OtisOrganProfile = OtisOrganProfile(),
    val sharedPressure: Float = .85f) {
    init {
        require(events.isNotEmpty() && events.size <= 32) { "OTIS requires 1..32 events" }
        require(sharedPressure.isFinite() && sharedPressure in 0f..1f) { "OTIS Shared pressure must be normalized" }
    }
    internal fun snapshot() = copy(events = Collections.unmodifiableList(events.toList()),
        organ = organ.copy(drawbars = Collections.unmodifiableList(organ.drawbars.toList())))
}

data class OtisAirProfile(
    val steamInstability: Float = .55f,
    val harpBend: Float = .65f,
    val breath: Float = .35f,
    val harpVibrato: Float = .30f,
    val musette: Float = .60f,
    val bellowsPulse: Float = .50f,
    val pressure: Float = .60f,
    val release: Float = .45f,
) {
    init {
        for ((name, value) in asMap()) require(value.isFinite() && value in 0f..1f) {
            "OTIS air $name must be finite and normalized, got $value"
        }
    }

    fun asMap(): Map<String, Float> = linkedMapOf(
        "steamInstability" to steamInstability, "harpBend" to harpBend,
        "breath" to breath, "harpVibrato" to harpVibrato, "musette" to musette,
        "bellowsPulse" to bellowsPulse, "pressure" to pressure, "release" to release,
    )
}

enum class OtisPercussion { SECOND, THIRD }
enum class OtisRotorSpeed { BRAKE, SLOW, FAST }

/** Saved source settings for the accepted finite organ. */
data class OtisOrganProfile(
    /** Drawbar order: 16', 5 1/3', 8', 4', 2 2/3', 2', 1 3/5', 1 1/3', 1'. */
    val drawbars: List<Float> = listOf(1f, .85f, 1f, .42f, .22f, .30f, .10f, .12f, .06f),
    val click: Float = .45f,
    val percussionLevel: Float = .32f,
    val percussionHarmonic: OtisPercussion = OtisPercussion.THIRD,
    /** Time for percussion to fall 60 dB, rather than an exponential time constant. */
    val percussionDecaySeconds: Double = .30,
    val leakage: Float = .38f,
    val drive: Float = .20f,
    val attackSeconds: Double = .004,
    val releaseSeconds: Double = .15,
    val cabinetHz: Double = 5600.0,
    val rotary: Boolean = false,
    val rotaryStart: OtisRotorSpeed = OtisRotorSpeed.SLOW,
    val rotaryTarget: OtisRotorSpeed = OtisRotorSpeed.FAST,
    val rotaryChangeSeconds: Double = .75,
    val rotaryDepth: Float = .82f,
) {
    init {
        require(drawbars.size == 9 && drawbars.all { it.isFinite() && it in 0f..1f }) {
            "drawbars must contain nine finite values in 0..1"
        }
        for ((name, value) in listOf("click" to click, "percussionLevel" to percussionLevel,
            "leakage" to leakage, "drive" to drive, "rotaryDepth" to rotaryDepth)) {
            require(value.isFinite() && value in 0f..1f) { "$name must be finite and in 0..1" }
        }
        require(attackSeconds.isFinite() && attackSeconds in 0.0..2.0) { "attackSeconds must be finite and in 0..2" }
        require(releaseSeconds.isFinite() && releaseSeconds in .005..3.0) { "releaseSeconds must be finite and in .005..3" }
        require(percussionDecaySeconds.isFinite() && percussionDecaySeconds in .02..3.0) {
            "percussionDecaySeconds must be finite and in .02..3"
        }
        require(cabinetHz.isFinite() && cabinetHz in 200.0..20_000.0) { "cabinetHz must be finite and in 200..20000" }
        require(rotaryChangeSeconds.isFinite() && rotaryChangeSeconds in 0.0..20.0) {
            "rotaryChangeSeconds must be finite and in 0..20"
        }
    }
}

