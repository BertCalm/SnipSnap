package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/** The exciter and feedback brightness of the original string, before fragmentation. */
enum class OrphicaMaterial { NYLON, STEEL, CRYSTAL }

/** Ways of revisiting a note's own recent sound; CLEAR is an exact dry bypass. */
enum class OrphicaMode { CLEAR, STUTTER, SCATTER, REVERSE, FREEZE }

/** A complete, deterministic offline note. Times and rates retain their physical units. */
data class OrphicaConfig(
    val material: OrphicaMaterial = OrphicaMaterial.NYLON,
    val mode: OrphicaMode = OrphicaMode.SCATTER,
    val midi: Int = 60,
    val velocity: Float = 1f,
    val pluck: Float = .6f,
    val fracture: Float = .5f,
    val grainSizeMs: Float = 60f,
    val grainRateHz: Float = 30f,
    val scatter: Float = .5f,
    val damping: Float = .25f,
    val seconds: Float = 4f,
    val seed: Long = 1L,
    val captureSeconds: Float = .15f,
) {
    init {
        require(midi in Orphica.MIDI_MIN..Orphica.MIDI_MAX) { "ORPHICA midi out of ${Orphica.MIDI_MIN}..${Orphica.MIDI_MAX}: $midi" }
        for ((name, value) in listOf("velocity" to velocity, "pluck" to pluck, "fracture" to fracture,
            "scatter" to scatter, "damping" to damping)) {
            require(value.isFinite() && value in 0f..1f) { "ORPHICA $name out of 0..1: $value" }
        }
        require(grainSizeMs.isFinite() && grainSizeMs in 8f..120f) { "ORPHICA grainSizeMs out of 8..120: $grainSizeMs" }
        require(grainRateHz.isFinite() && grainRateHz in 2f..100f) { "ORPHICA grainRateHz out of 2..100: $grainRateHz" }
        require(seconds.isFinite() && seconds in .2f..12f) { "ORPHICA seconds out of .2..12: $seconds" }
        require(captureSeconds.isFinite() && captureSeconds in .03f..1f) { "ORPHICA captureSeconds out of .03..1: $captureSeconds" }
        require(captureSeconds < seconds) { "ORPHICA captureSeconds must precede the end of the note" }
    }
}

/**
 * ORPHICA — a tuned pluck whose sustain breaks into grains of that same note.
 *
 * Each note owns a quarter-second history. Grains copy only samples already
 * written at their onset, including reverse grains: rendering a source buffer
 * in advance never grants the cloud permission to read its future. Hann windows
 * and their actual summed overlap keep density from multiplying output gain.
 * The first 25 ms stay exactly dry, then FRACTURE crossfades into the cloud.
 * There is no room or shimmer stage in this model.
 */
object Orphica {
    const val MODEL_VERSION = 1
    const val DEFAULT_MIDI = 60
    const val MIDI_MIN = 21
    const val MIDI_MAX = 108
    internal const val HISTORY_SECONDS = .25f
    internal const val ATTACK_SECONDS = .025f
    private const val CROSSFADE_SECONDS = .07f

    fun macrosFor(material: OrphicaMaterial = OrphicaMaterial.NYLON): List<MacroSpec> = listOf(
        MacroSpec("TUNE", .5f, .5f), MacroSpec("PLUCK", .6f), MacroSpec("FRACTURE", .5f),
        MacroSpec("SIZE", sizeMacro(60f)), MacroSpec("DAMP", .25f),
    )

    fun defaults(material: OrphicaMaterial = OrphicaMaterial.NYLON): Map<String, Float> =
        macrosFor(material).associate { it.name to it.default }

    /** Missing macros preserve the complete saved config rather than resetting it. */
    fun configWithMacros(config: OrphicaConfig, macros: Map<String, Float>): OrphicaConfig {
        val known = macrosFor(config.material).map { it.name }.toSet()
        for ((name, value) in macros) {
            require(name in known) { "unknown ORPHICA macro $name" }
            require(value.isFinite() && value in 0f..1f) { "ORPHICA macro $name out of 0..1: $value" }
        }
        return config.copy(
            midi = macros["TUNE"]?.let { (config.midi + ((it - .5f) * 24f).roundToInt()).coerceIn(MIDI_MIN, MIDI_MAX) } ?: config.midi,
            pluck = macros["PLUCK"] ?: config.pluck,
            fracture = macros["FRACTURE"] ?: config.fracture,
            grainSizeMs = macros["SIZE"]?.let { 8f + it * 112f } ?: config.grainSizeMs,
            damping = macros["DAMP"] ?: config.damping,
        )
    }

    private fun sizeMacro(ms: Float): Float = (ms - 8f) / 112f

    /** The exact same seeded string used by [render], duplicated to two channels. */
    fun dry(config: OrphicaConfig): Snip = stereo(source(config))

    fun render(config: OrphicaConfig): Snip = fragment(source(config), config)

    private data class Material(val loopHz: Float, val pickLow: Float, val pickHigh: Float)

    private fun source(config: OrphicaConfig): FloatArray {
        val character = when (config.material) {
            OrphicaMaterial.NYLON -> Material(3400f, 1000f, 5600f)
            OrphicaMaterial.STEEL -> Material(7000f, 2200f, 10500f)
            OrphicaMaterial.CRYSTAL -> Material(11000f, 4000f, 16000f)
        }
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val excitation = config.pluck * (.35f + .65f * config.velocity)
        // Material changes brightness, never the requested equal-temperament pitch.
        // DAMP's usable range favors harp sustain; its top still damps the loop.
        val raw = Pluck.ks(
            freq = Keys.midiHz(config.midi), seconds = config.seconds,
            damp = .04f + .36f * config.damping,
            bodyLoopHz = character.loopHz,
            pickHz = Dsp.expMap(excitation, character.pickLow, character.pickHigh),
            seed = (config.seed xor (config.seed ushr 32)).toInt(), rate = rate,
            position = Dsp.lin(excitation, .42f, .08f),
        )
        val frames = (config.seconds * Dsp.RATE).toInt()
        val mono = Dsp.decimate(raw, Dsp.RATE).copyOf(frames)
        // Reserve headroom once, on the string. Cloud mixing never rescales its attack.
        Dsp.normalize(mono, .72f * config.velocity)
        return mono
    }

    private fun stereo(source: FloatArray): Snip {
        val samples = FloatArray(source.size * 2) { source[it / 2] }
        Dsp.fadeTail(samples, ms = 80f, channels = 2)
        return Snip(samples, channels = 2, sampleRate = Dsp.RATE)
    }

    /** The source bounds used by an actual grain, exposed internally for causal checks. */
    internal data class GrainEvent(
        val onsetFrame: Int,
        val sourceFromFrame: Int,
        val sourceUntilFrame: Int,
        val frozen: Boolean,
        val reversed: Boolean,
    )

    private data class Slice(val samples: FloatArray, val from: Int)
    private data class Grain(val samples: FloatArray, val left: Float, val right: Float, var age: Int = 0)

    /** The reusable history stage also accepts captured mono sources, without future reads. */
    internal fun fragment(source: FloatArray, config: OrphicaConfig, onGrain: ((GrainEvent) -> Unit)? = null): Snip {
        if (config.fracture == 0f || config.mode == OrphicaMode.CLEAR) return stereo(source)
        val historyFrames = (HISTORY_SECONDS * Dsp.RATE).toInt()
        val attackFrames = (ATTACK_SECONDS * Dsp.RATE).toInt()
        val crossfadeFrames = (CROSSFADE_SECONDS * Dsp.RATE).toInt()
        val requestedSize = (config.grainSizeMs * .001f * Dsp.RATE).roundToInt()
        val captureFrame = (config.captureSeconds * Dsp.RATE).roundToInt()
        val stutterBlock = (.08f * Dsp.RATE).roundToInt()
        val history = FloatArray(historyFrames)
        val grains = mutableListOf<Grain>()
        val random = Random(config.seed)
        val out = FloatArray(source.size * 2)
        var nextOnset = attackFrames.toDouble()
        val onsetStep = Dsp.RATE.toDouble() / config.grainRateHz
        var frozen: Slice? = null
        var stutter: Slice? = null
        var stutterBucket = -1

        fun recent(from: Int, until: Int): Slice =
            Slice(FloatArray(until - from) { history[(from + it) % historyFrames] }, from)

        for (frame in source.indices) {
            // The current source sample is written at the END of this iteration.
            val earliest = max(0, frame - historyFrames)
            if (config.mode == OrphicaMode.FREEZE && frozen == null && frame >= captureFrame) {
                frozen = recent(earliest, frame)
            }
            if (frame >= nextOnset && frame >= attackFrames) {
                val capture = frozen
                val available = capture?.samples?.size ?: (frame - earliest)
                val size = minOf(requestedSize, available)
                if (size >= 2) {
                    val slice = when {
                        capture != null -> {
                            val maxOffset = capture.samples.size - size
                            val offset = (maxOffset * random.nextFloat() * config.scatter).roundToInt()
                            Slice(capture.samples.copyOfRange(offset, offset + size), capture.from + offset)
                        }
                        config.mode == OrphicaMode.STUTTER -> {
                            val bucket = frame / stutterBlock
                            if (bucket != stutterBucket || stutter == null) {
                                stutter = recent(frame - size, frame)
                                stutterBucket = bucket
                            }
                            stutter!!
                        }
                        else -> {
                            val newest = frame - size
                            val span = newest - earliest
                            val from = newest - (span * random.nextFloat() * config.scatter).roundToInt()
                            recent(from, from + size)
                        }
                    }
                    val reversed = config.mode == OrphicaMode.REVERSE
                    val samples = if (reversed) slice.samples.reversedArray() else slice.samples
                    // A gentle balance pan never amplifies either channel beyond the mono source.
                    val pan = (random.nextFloat() * 2f - 1f) * .4f
                    grains += Grain(samples, 1f - max(pan, 0f), 1f + minOf(pan, 0f))
                    onGrain?.invoke(GrainEvent(frame, slice.from, slice.from + samples.size, capture != null, reversed))
                }
                // Fractional spacing keeps the requested rate exact over long notes.
                nextOnset += onsetStep
            }

            var left = 0f
            var right = 0f
            var overlap = 0f
            val iterator = grains.iterator()
            while (iterator.hasNext()) {
                val grain = iterator.next()
                val window = (.5 - .5 * cos(2.0 * PI * grain.age / (grain.samples.size - 1))).toFloat()
                val sample = grain.samples[grain.age] * window
                left += sample * grain.left
                right += sample * grain.right
                overlap += window
                grain.age++
                if (grain.age == grain.samples.size) iterator.remove()
            }
            val divisor = max(1f, overlap)
            val fraction = config.fracture * ((frame - attackFrames).toFloat() / crossfadeFrames).coerceIn(0f, 1f)
            out[frame * 2] = source[frame] * (1f - fraction) + left / divisor * fraction
            out[frame * 2 + 1] = source[frame] * (1f - fraction) + right / divisor * fraction
            history[frame % historyFrames] = source[frame]
        }
        // A finite captured sustain needs an explicit release at the file boundary.
        Dsp.fadeTail(out, ms = 80f, channels = 2)
        return Snip(out, channels = 2, sampleRate = Dsp.RATE)
    }
}
