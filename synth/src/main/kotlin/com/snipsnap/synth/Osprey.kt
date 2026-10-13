package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.random.Random

enum class OspreyVoice { SWELL }

/** Versioned finite coastal instrument. Saved model-1 recipes use their frozen renderer. */
object Osprey {
    const val MODEL_VERSION = 2
    const val DEFAULT_MIDI = 60
    const val MIDI_MIN = 36
    const val MIDI_MAX = 84
    const val MAX_SECONDS = 12f
    private val DEFAULTS = linkedMapOf("SHORE" to 0f, "SEA" to .35f, "MOTION" to .45f,
        "DEPTH" to .45f, "SPACE" to .20f, "HOLD" to .35f)

    fun supportsModel(modelVersion: Int) = modelVersion in 1..MODEL_VERSION
    fun defaults(voice: OspreyVoice, modelVersion: Int = MODEL_VERSION): Map<String, Float> {
        require(supportsModel(modelVersion)) { "unsupported OSPREY model $modelVersion" }
        return if (modelVersion == 1) OspreyV1.defaults(voice) else DEFAULTS.toMap()
    }
    fun macrosFor(voice: OspreyVoice, modelVersion: Int = MODEL_VERSION): List<MacroSpec> =
        defaults(voice, modelVersion).map { (name, value) -> MacroSpec(name, value, value) }
    fun scramble(voice: OspreyVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(defaults(voice) + (near?.macros?.filterKeys { it in DEFAULTS } ?: emptyMap()), temperature, random)
    fun frequencyFor(midi: Int): Float = Keys.midiHz(midi)
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: OspreyVoice,
        @Suppress("UNUSED_PARAMETER") macros: Map<String, Float> = emptyMap()): DrumClass = DrumClass.TONAL
    internal fun settled(voice: OspreyVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (name, default) -> (macros[name]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f) }

    fun render(voice: OspreyVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, seed: Int = 0, durationSeconds: Float = 8f, modelVersion: Int = MODEL_VERSION,
        cancelled: () -> Boolean = { false }): Snip {
        require(supportsModel(modelVersion)) { "unsupported OSPREY model $modelVersion" }
        return if (modelVersion == 1) OspreyV1.render(voice, macros, midi, velocity, seed, durationSeconds, cancelled)
        else Snip(probe(voice, macros, midi, velocity, seed, durationSeconds, modelVersion, cancelled).samples, 2, Dsp.RATE)
    }

    internal enum class CreatureKind { BIRD, DEEP, AMBIENT }
    internal enum class ContactKind { SWELL, CHOP, BREAKER }
    internal data class CallEvent(val timeSeconds: Float, val kind: CreatureKind, val shore: Float,
        val activity: Float, val strength: Float)
    /** Energy is the weighted exciter budget, not a claim about net work against moving material. */
    internal data class ContactEvent(val timeSeconds: Float, val kind: ContactKind, val material: Int,
        val activity: Float, val energy: Float, val widthMs: Float)
    internal data class Metrics(val rawPeak: Float, val finalModalEnergy: Double,
        val peakModalEnergy: Double, val nonFiniteRecoveries: Int, val excitationStopSeconds: Float,
        val injectedEnergy: Double = 0.0, val pressureFrames: Int = 0)
    internal data class Probe(val samples: FloatArray, val ocean: FloatArray, val primary: FloatArray,
        val sympathy: FloatArray, val creatures: FloatArray, val dry: FloatArray,
        val callEvents: List<CallEvent>, val metrics: Metrics, val rootHz: Float, val modeHz: FloatArray,
        val contactEvents: List<ContactEvent> = emptyList(),
        val weatherWeights: FloatArray = floatArrayOf(1f, 0f, 0f),
        val rootIndices: IntArray = intArrayOf(0))

    internal fun probe(voice: OspreyVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, seed: Int = 0, durationSeconds: Float = 8f, modelVersion: Int = MODEL_VERSION,
        cancelled: () -> Boolean = { false }): Probe {
        require(supportsModel(modelVersion)) { "unsupported OSPREY model $modelVersion" }
        if (modelVersion == 2) return OspreyV2.probe(voice, macros, midi, velocity, seed, durationSeconds, cancelled)
        val p = OspreyV1.probe(voice, macros, midi, velocity, seed, durationSeconds, cancelled)
        return Probe(p.samples, p.ocean, p.primary, p.sympathy, p.creatures, p.dry,
            p.callEvents.map { CallEvent(it.timeSeconds, CreatureKind.valueOf(it.kind.name), it.shore, it.activity, it.strength) },
            Metrics(p.metrics.rawPeak, p.metrics.finalModalEnergy, p.metrics.peakModalEnergy,
                p.metrics.nonFiniteRecoveries, p.metrics.excitationStopSeconds), p.rootHz, p.modeHz,
            rootIndices = intArrayOf(0, 4, 8))
    }
}
