package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.random.Random

enum class ScannedChainVoice { PLUCK, METAL, BLOOM }

/** Saved non-macro settings. Numerical resolution belongs to the saved render model. */
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
            "SCANNED CHAIN keeps mechanicalHz and driveHz fixed for ${voice.name}"
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
    const val MODEL_VERSION = 2
    const val LEGACY_MODEL = 1
    const val MIDI_MIN = 36
    const val MIDI_MAX = 96
    const val MIN_MIDI = MIDI_MIN
    const val MAX_MIDI = MIDI_MAX
    const val SHAPE_SIZE = 128
    const val DAMP_MAX = 6f
    const val BEND_MAX = 8f

    fun supportsModel(model: Int): Boolean = model in LEGACY_MODEL..MODEL_VERSION

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
        model: Int = MODEL_VERSION,
    ): Snip {
        val resolved = settled(voice, macros)
        val physical = configuration(voice, resolved, midi, velocity, config, model)
        return ScannedChainRenderCache.render(model, voice, resolved, midi, velocity, shape, config) {
            val samples = renderModel(physical, shape.points, model).raw.samples
            // One constant finishing gain preserves the gesture inside each finite sound.
            Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity, ceiling = .9f * velocity)
            checkCancellation()
            Snip(samples, 1, Dsp.RATE)
        }
    }

    internal fun probe(
        voice: ScannedChainVoice, macros: Map<String, Float> = emptyMap(), midi: Int = 60, velocity: Float = .8f,
        shape: ScannedChainShape = ScannedChainShape.factory(voice), config: ScannedChainConfig = ScannedChainConfig.forVoice(voice),
        model: Int = MODEL_VERSION,
    ): ScannedChainModelV1.Result {
        val physical = configuration(voice, settled(voice, macros), midi, velocity, config, model)
        return renderModel(physical, shape.points, model)
    }

    /** Model-two diagnostics bypass the completed-audio cache. */
    internal fun probeV2(
        voice: ScannedChainVoice, macros: Map<String, Float> = emptyMap(), midi: Int = 60, velocity: Float = .8f,
        shape: ScannedChainShape = ScannedChainShape.factory(voice), config: ScannedChainConfig = ScannedChainConfig.forVoice(voice),
    ): ScannedChainModelV2.Result = ScannedChainModelV2.render(
        configuration(voice, settled(voice, macros), midi, velocity, config, MODEL_VERSION), shape.points,
    )

    private fun configuration(
        voice: ScannedChainVoice, m: Map<String, Float>, midi: Int, velocity: Float,
        config: ScannedChainConfig, model: Int,
    ): ScannedChainModelV1.Config {
        checkCancellation()
        require(supportsModel(model)) { "unsupported scanned-chain model $model" }
        require(midi in MIDI_MIN..MIDI_MAX) { "SCANNED CHAIN MIDI must be $MIDI_MIN..$MIDI_MAX" }
        require(velocity.isFinite() && velocity in 0f..1f) { "SCANNED CHAIN velocity must be 0..1" }
        config.validateForVoice(voice)
        return ScannedChainModelV1.Config(
            name = "SCANNED CHAIN ${voice.name}", voice = ScannedChainModelV1.Voice.valueOf(voice.name),
            midi = midi, velocity = velocity, seconds = config.seconds, motion = m.getValue("MOTION"),
            mechanicalHz = config.mechanicalHz, dampingPerSecond = m.getValue("DAMP") * DAMP_MAX,
            nonlinear = m.getValue("BEND") * BEND_MAX, restFraction = config.restFraction,
            drive = m.getValue("BLOOM"), driveHz = config.driveHz, seed = config.seed,
            attackSeconds = config.attackSeconds, releaseSeconds = config.releaseSeconds,
        )
    }

    private fun renderModel(
        config: ScannedChainModelV1.Config, points: FloatArray, model: Int,
    ): ScannedChainModelV1.Result {
        if (model == MODEL_VERSION) return ScannedChainModelV2.render(config, points).compatibility
        try {
            return ScannedChainModelV1.render(config, points)
        } catch (failure: IllegalStateException) {
            // Successful legacy renders never take this path and retain their exact PCM.
            // A previously refused source can use conservative initial excitation headroom;
            // the physical state is never clipped and unrelated failures still propagate.
            val message = failure.message.orEmpty()
            val headroomFailure = message.startsWith("scanned-chain displacement exceeded finite experiment bound") ||
                message.startsWith("scanned-chain conservative Verlet stability bound failed") ||
                message.startsWith("snapshot rate has insufficient headroom for the chain") ||
                message.startsWith("scanned-chain output exceeded finite audition bound")
            if (!headroomFailure) throw failure
            checkCancellation()
            return ScannedChainModelV2.renderLegacyFallback(config, points)
        }
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
