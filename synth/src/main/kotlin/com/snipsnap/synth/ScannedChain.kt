package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.random.Random

enum class ScannedChainVoice { PLUCK, METAL, BLOOM }

/** Saved non-macro settings. Numerical resolution is part of model 1, not a patch control. */
data class ScannedChainConfig(
    val mechanicalHz: Float = 1.15f,
    val restFraction: Float = .16f,
    val driveHz: Float = 1.5f,
    val seconds: Float = 3f,
    val attackSeconds: Float = .008f,
    val releaseSeconds: Float = .25f,
    val seed: Int = 1729,
) {
    init {
        require(mechanicalHz.isFinite() && mechanicalHz in .1f..4f) { "SCANNED CHAIN mechanicalHz must be .1..4" }
        require(restFraction.isFinite() && restFraction in 0f.. .35f) { "SCANNED CHAIN restFraction must be 0...35" }
        require(driveHz.isFinite() && driveHz in .1f..8f) { "SCANNED CHAIN driveHz must be .1..8" }
        require(seconds.isFinite() && seconds in .25f..4f) { "SCANNED CHAIN duration must be .25..4 seconds" }
        require(attackSeconds.isFinite() && attackSeconds in 0f..1f) { "SCANNED CHAIN attack must be 0..1 second" }
        require(releaseSeconds.isFinite() && releaseSeconds in 0f..2f) { "SCANNED CHAIN release must be 0..2 seconds" }
    }

    internal fun validateForVoice(voice: ScannedChainVoice) {
        val fixed = forVoice(voice)
        require(mechanicalHz == fixed.mechanicalHz && driveHz == fixed.driveHz) {
            "SCANNED CHAIN model 1 keeps mechanicalHz and driveHz fixed for ${voice.name}"
        }
    }

    companion object {
        fun forVoice(voice: ScannedChainVoice): ScannedChainConfig = when (voice) {
            ScannedChainVoice.PLUCK -> ScannedChainConfig()
            ScannedChainVoice.METAL -> ScannedChainConfig(mechanicalHz = 3.1f, restFraction = .12f)
            // The quiet bright initial shape leaves room for this existing bounded driver.
            ScannedChainVoice.BLOOM -> ScannedChainConfig(mechanicalHz = .62f, restFraction = .05f,
                driveHz = .67f, attackSeconds = .035f, releaseSeconds = .4f)
        }
    }
}

/** A finite scanned waveform whose timbre evolves under a slow mass/spring simulation. */
object ScannedChain {
    const val MODEL_VERSION = 1
    const val MIDI_MIN = 36
    const val MIDI_MAX = 96
    const val MIN_MIDI = MIDI_MIN
    const val MAX_MIDI = MIDI_MAX
    const val SHAPE_SIZE = 128
    const val DAMP_MAX = 6f
    const val BEND_MAX = 8f

    fun macrosFor(voice: ScannedChainVoice): List<MacroSpec> {
        val damping = when (voice) {
            ScannedChainVoice.PLUCK, ScannedChainVoice.BLOOM -> 1.4f
            ScannedChainVoice.METAL -> .8f
        }
        val bend = when (voice) {
            ScannedChainVoice.PLUCK -> 2f
            ScannedChainVoice.METAL -> 5f
            ScannedChainVoice.BLOOM -> 3f
        }
        return listOf(MacroSpec("MOTION", 1f), MacroSpec("DAMP", damping / DAMP_MAX),
            MacroSpec("BEND", bend / BEND_MAX), MacroSpec("BLOOM", if (voice == ScannedChainVoice.BLOOM) .8f else 0f))
    }

    fun defaults(voice: ScannedChainVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    fun scramble(voice: ScannedChainVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice) + if (near is ScannedChainPatch && near.voice == voice) near.macros else emptyMap()
        return Dsp.scrambleNear(base, temperature, random)
    }

    fun drumClassFor(voice: ScannedChainVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        settled(voice, macros)
        return DrumClass.TONAL
    }

    fun render(
        voice: ScannedChainVoice, macros: Map<String, Float> = emptyMap(), midi: Int = 60, velocity: Float = .8f,
        shape: ScannedChainShape = ScannedChainShape.factory(voice), config: ScannedChainConfig = ScannedChainConfig.forVoice(voice),
    ): Snip {
        val result = probe(voice, macros, midi, velocity, shape, config)
        val samples = result.raw.samples
        // One constant finishing gain. Velocity retains level as well as physical excitation.
        Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity, ceiling = .9f * velocity)
        checkCancellation()
        return Snip(samples, 1, Dsp.RATE)
    }

    internal fun probe(
        voice: ScannedChainVoice, macros: Map<String, Float> = emptyMap(), midi: Int = 60, velocity: Float = .8f,
        shape: ScannedChainShape = ScannedChainShape.factory(voice), config: ScannedChainConfig = ScannedChainConfig.forVoice(voice),
    ): ScannedChainModelV1.Result {
        checkCancellation()
        require(midi in MIDI_MIN..MIDI_MAX) { "SCANNED CHAIN MIDI must be $MIDI_MIN..$MIDI_MAX" }
        require(velocity.isFinite() && velocity in 0f..1f) { "SCANNED CHAIN velocity must be 0..1" }
        config.validateForVoice(voice)
        val m = settled(voice, macros)
        return ScannedChainModelV1.render(ScannedChainModelV1.Config(
            name = "SCANNED CHAIN ${voice.name}", voice = ScannedChainModelV1.Voice.valueOf(voice.name),
            midi = midi, velocity = velocity, seconds = config.seconds, motion = m.getValue("MOTION"),
            mechanicalHz = config.mechanicalHz, dampingPerSecond = m.getValue("DAMP") * DAMP_MAX,
            nonlinear = m.getValue("BEND") * BEND_MAX, restFraction = config.restFraction,
            drive = m.getValue("BLOOM"), driveHz = config.driveHz, seed = config.seed,
            attackSeconds = config.attackSeconds, releaseSeconds = config.releaseSeconds,
        ), shape.points)
    }

    private fun settled(voice: ScannedChainVoice, macros: Map<String, Float>): Map<String, Float> {
        val values = defaults(voice).toMutableMap()
        for ((key, value) in macros) {
            require(key in values) { "unknown SCANNED CHAIN macro $key" }
            require(value.isFinite() && value in 0f..1f) { "SCANNED CHAIN $key must be 0..1" }
            values[key] = value
        }
        return values
    }

    internal fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("scanned-chain render cancelled")
    }
}
