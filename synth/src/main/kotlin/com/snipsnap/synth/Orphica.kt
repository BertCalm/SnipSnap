package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.Fft
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.abs
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
    val hold: Float = 0f,
    val sustainModel: Int = Orphica.SUSTAIN_MODEL_VERSION,
) {
    init {
        require(midi in Orphica.MIDI_MIN..Orphica.MIDI_MAX) { "ORPHICA midi out of ${Orphica.MIDI_MIN}..${Orphica.MIDI_MAX}: $midi" }
        for ((name, value) in listOf("velocity" to velocity, "pluck" to pluck, "fracture" to fracture,
            "scatter" to scatter, "damping" to damping, "hold" to hold)) {
            require(value.isFinite() && value in 0f..1f) { "ORPHICA $name out of 0..1: $value" }
        }
        require(grainSizeMs.isFinite() && grainSizeMs in 8f..120f) { "ORPHICA grainSizeMs out of 8..120: $grainSizeMs" }
        require(grainRateHz.isFinite() && grainRateHz in 2f..100f) { "ORPHICA grainRateHz out of 2..100: $grainRateHz" }
        require(seconds.isFinite() && seconds in .2f..12f) { "ORPHICA seconds out of .2..12: $seconds" }
        require(captureSeconds.isFinite() && captureSeconds in .03f..1f) { "ORPHICA captureSeconds out of .03..1: $captureSeconds" }
        require(captureSeconds < seconds) { "ORPHICA captureSeconds must precede the end of the note" }
        require(sustainModel == Orphica.SUSTAIN_MODEL_VERSION) { "unsupported orphica sustain model $sustainModel" }
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
    const val SUSTAIN_MODEL_VERSION = 1
    const val DEFAULT_MIDI = 60
    const val MIDI_MIN = 21
    const val MIDI_MAX = 108
    internal const val HISTORY_SECONDS = .25f
    internal const val ATTACK_SECONDS = .025f
    private const val CROSSFADE_SECONDS = .07f

    fun macrosFor(material: OrphicaMaterial = OrphicaMaterial.NYLON): List<MacroSpec> = listOf(
        MacroSpec("TUNE", .5f, .5f), MacroSpec("PLUCK", .6f), MacroSpec("FRACTURE", .5f),
        MacroSpec("SIZE", sizeMacro(60f)), MacroSpec("DAMP", .25f), MacroSpec("HOLD", 0f),
    )

    fun defaults(material: OrphicaMaterial = OrphicaMaterial.NYLON): Map<String, Float> =
        macrosFor(material).associate { it.name to it.default }

    /** Panel defaults from the saved sound, so loading a recipe does not change its identity. */
    fun macrosFrom(config: OrphicaConfig): Map<String, Float> = linkedMapOf(
        "TUNE" to .5f, "PLUCK" to config.pluck, "FRACTURE" to config.fracture,
        "SIZE" to sizeMacro(config.grainSizeMs), "DAMP" to config.damping, "HOLD" to config.hold,
    )

    fun isLoop(hold: Float): Boolean = hold >= .99f

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
            hold = macros["HOLD"] ?: config.hold,
        )
    }

    private fun sizeMacro(ms: Float): Float = (ms - 8f) / 112f

    /** The exact same seeded string used by [render], duplicated to two channels. */
    fun dry(config: OrphicaConfig): Snip = stereo(source(config))

    fun render(config: OrphicaConfig, cancelled: () -> Boolean = { false }): Snip {
        checkCancelled(cancelled)
        if (config.hold == 0f) return fragment(source(config, cancelled), config, cancelled = cancelled)
        val held = heldMaterial(config, cancelled)
        if (isLoop(config.hold)) return Snip(held.cycle, channels = 2, sampleRate = Dsp.RATE)
        val source = held.source
        val finite = fragment(source, config.copy(hold = 0f), cancelled = cancelled, release = false).samples
        val frames = (config.seconds * Dsp.RATE).toInt()
        val out = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            if (frame % 1024 == 0) checkCancelled(cancelled)
            val amount = config.hold * captureBlend(frame, held.captureFrame)
            val phase = Math.floorMod(frame - held.captureFrame, held.frameCount)
            for (channel in 0..1) out[frame * 2 + channel] =
                finite[frame * 2 + channel] * (1f - amount) + held.cycle[phase * 2 + channel] * amount
        }
        Dsp.fadeTail(out, ms = 80f, channels = 2)
        return Snip(out, channels = 2, sampleRate = Dsp.RATE)
    }

    private data class Material(val loopHz: Float, val pickLow: Float, val pickHigh: Float)

    private fun source(config: OrphicaConfig, cancelled: () -> Boolean = { false }): FloatArray {
        checkCancelled(cancelled)
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
        checkCancelled(cancelled)
        val frames = (config.seconds * Dsp.RATE).toInt()
        val mono = Dsp.decimate(raw, Dsp.RATE).copyOf(frames)
        checkCancelled(cancelled)
        // Reserve headroom once, on the string. Cloud mixing never rescales its attack.
        Dsp.normalize(mono, .72f * config.velocity)
        return mono
    }

    private fun stereo(source: FloatArray, release: Boolean = true): Snip {
        val samples = FloatArray(source.size * 2) { source[it / 2] }
        if (release) Dsp.fadeTail(samples, ms = 80f, channels = 2)
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
    internal fun fragment(source: FloatArray, config: OrphicaConfig, onGrain: ((GrainEvent) -> Unit)? = null,
        cancelled: () -> Boolean = { false }, release: Boolean = true): Snip {
        checkCancelled(cancelled)
        if (config.fracture == 0f || config.mode == OrphicaMode.CLEAR) return stereo(source, release)
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
            if (frame % 1024 == 0) checkCancelled(cancelled)
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
        if (release) Dsp.fadeTail(out, ms = 80f, channels = 2)
        return Snip(out, channels = 2, sampleRate = Dsp.RATE)
    }

    /**
     * A keyboard note keeps its attack. Its marker follows two traversals of the
     * periodic sustain, after the capture crossfade has completely settled.
     * Pads use [render]'s cycle alone because they loop the whole file.
     */
    fun renderHeld(config: OrphicaConfig, cancelled: () -> Boolean = { false }): KeyNote {
        if (!isLoop(config.hold)) return KeyNote(render(config, cancelled))
        val held = heldMaterial(config, cancelled)
        val prefix = fragment(held.source, config.copy(hold = 0f), cancelled = cancelled, release = false).samples
        val loopStart = held.captureFrame + held.frameCount * 2
        val out = FloatArray((loopStart + held.frameCount) * 2)
        for (frame in 0 until out.size / 2) {
            if (frame % 1024 == 0) checkCancelled(cancelled)
            val amount = captureBlend(frame, held.captureFrame)
            val phase = Math.floorMod(frame - held.captureFrame, held.frameCount)
            for (channel in 0..1) {
                val original = if (frame * 2 + channel < prefix.size) prefix[frame * 2 + channel] else 0f
                out[frame * 2 + channel] = original * (1f - amount) + held.cycle[phase * 2 + channel] * amount
            }
        }
        return KeyNote(Snip(out, 2, Dsp.RATE), loopStart.toLong())
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("ORPHICA render cancelled")
    }

    private fun captureBlend(frame: Int, captureFrame: Int): Float {
        val position = ((frame - captureFrame) / (.1f * Dsp.RATE)).coerceIn(0f, 1f)
        return (.5 - .5 * cos(PI * position)).toFloat()
    }

    internal data class HeldMaterial(
        val source: FloatArray,
        val cycle: FloatArray,
        val nextCycle: FloatArray,
        val captureFrame: Int,
        val musicalCycles: Int,
        val grainCount: Int,
    ) {
        val frameCount: Int get() = cycle.size / 2
        val grainRateHz: Double get() = grainCount * Dsp.RATE.toDouble() / frameCount
    }

    /**
     * Sustain model 1 closes the captured carrier and the grain schedule themselves.
     * It waits for three written periods at very low notes. If a late capture has
     * already decayed below numerical usefulness, it retains an earlier populated
     * history of the same note. This is an offline note capture, not a live chord latch.
     */
    internal fun heldMaterial(config: OrphicaConfig, cancelled: () -> Boolean = { false }): HeldMaterial {
        checkCancelled(cancelled)
        val hz = Keys.midiHz(config.midi).toDouble()
        val period = Dsp.RATE / hz
        val source = source(config.copy(seconds = max(config.seconds, config.captureSeconds + .3f)), cancelled)
        var capture = max((config.captureSeconds * Dsp.RATE).roundToInt(), (period * 3).roundToInt() + 2)
        val history = (HISTORY_SECONDS * Dsp.RATE).toInt()
        fun capturedLevel(until: Int): Double {
            val from = max(0, until - history)
            var energy = 0.0
            for (i in from until until) energy += source[i].toDouble() * source[i]
            return sqrt(energy / (until - from).coerceAtLeast(1))
        }
        if (config.velocity > 0f && capturedLevel(capture) < config.velocity * 1e-6) {
            capture = max((.05f * Dsp.RATE).roundToInt(), (period * 3).roundToInt() + 2)
        }
        val from = max(0, capture - history)
        val cycles = minOf(8, ((capture - from - 2) / period).toInt()).coerceAtLeast(1)
        val tableSize = 512
        val table = FloatArray(tableSize)
        // Phase-fold only samples at or before the capture cursor. Averaging actual
        // string periods retains its material and seeded excitation, without a new oscillator.
        for (phase in table.indices) {
            if (phase % 128 == 0) checkCancelled(cancelled)
            var sum = 0.0
            for (cycle in 1..cycles) {
                val position = capture - 2.0 - cycle * period + phase.toDouble() / tableSize * period
                val index = floor(position).toInt().coerceIn(from, capture - 2)
                val fraction = (position - index).toFloat().coerceIn(0f, 1f)
                sum += source[index] * (1f - fraction) + source[index + 1] * fraction
            }
            table[phase] = (sum / cycles).toFloat()
        }
        // The circular table is projected onto musical harmonics below Nyquist.
        // Its periodic interpolation closes both value and slope at the phase boundary.
        val imaginary = FloatArray(tableSize)
        Fft.forward(table, imaginary)
        val harmonicLimit = minOf(tableSize / 2 - 1, (Dsp.RATE * .43 / hz).toInt())
        table[0] = 0f
        imaginary[0] = 0f
        for (bin in 1 until tableSize) {
            val harmonic = minOf(bin, tableSize - bin)
            if (harmonic > harmonicLimit) { table[bin] = 0f; imaginary[bin] = 0f }
        }
        Fft.inverse(table, imaginary)
        Dsp.normalize(table, .22f * config.velocity)

        var musicalCycles = max(1, (hz * .5).roundToInt())
        var length = (musicalCycles * period).roundToInt()
        var smallestError = Double.MAX_VALUE
        val desiredSeconds = max(.5, minOf(2.0, 4.0 / config.grainRateHz))
        val low = max(1, kotlin.math.ceil(hz * .35).toInt())
        val high = max(low, kotlin.math.floor(hz * 2.0).toInt())
        for (count in low..high) {
            val exact = count * period
            val frames = exact.roundToInt()
            val events = max(1, (config.grainRateHz * frames / Dsp.RATE).roundToInt())
            if (events < 4) continue
            val rateError = abs(events * Dsp.RATE.toDouble() / frames - config.grainRateHz) / config.grainRateHz
            // Fit the musical and grain clocks together. The nearest integer-frame
            // musical period is already far inside a cent; favor a faithful event rate.
            val error = max(.01, rateError) + abs(exact - frames) / exact * .1 +
                abs(frames.toDouble() / Dsp.RATE - desiredSeconds) * .0001
            if (error < smallestError) { smallestError = error; musicalCycles = count; length = exact.roundToInt() }
        }
        val random = Random(config.seed xor 0x4F525048494341L)
        val grainCount = max(1, (config.grainRateHz * length / Dsp.RATE).roundToInt())
        val size = (config.grainSizeMs * .001f * Dsp.RATE).roundToInt()
        val frozenOffset = random.nextDouble() * config.scatter * .2
        data class Scheduled(val onset: Int, val pan: Float, val offset: Double)
        val scheduled = (0 until grainCount).map { grain ->
            Scheduled((grain.toDouble() * length / grainCount).roundToInt(),
                (random.nextFloat() * 2f - 1f) * .4f,
                when (config.mode) {
                    OrphicaMode.STUTTER -> 0.0
                    OrphicaMode.FREEZE -> frozenOffset
                    else -> random.nextDouble() * config.scatter * .2
                })
        }
        val panPhase = random.nextDouble() * 2.0 * PI
        fun carrier(phase: Double): Float {
            val wrapped = phase - floor(phase)
            val position = wrapped * tableSize
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            val p0 = table[(index + tableSize - 1) % tableSize]
            val p1 = table[index]
            val p2 = table[(index + 1) % tableSize]
            val p3 = table[(index + 2) % tableSize]
            return p1 + .5f * fraction * (p2 - p0 + fraction *
                (2f * p0 - 5f * p1 + 4f * p2 - p3 + fraction * (3f * (p1 - p2) + p3 - p0)))
        }
        // Evaluate two periods of the actual circular process. Grains that began
        // before either boundary contribute their remaining window across it.
        fun evaluate(turn: Int): FloatArray {
            val left = FloatArray(length)
            val right = FloatArray(length)
            val overlap = FloatArray(length)
            for (grain in scheduled) {
                checkCancelled(cancelled)
                for (age in 0 until size) {
                    val frame = Math.floorMod(grain.onset + age, length)
                    val absolute = frame + turn * length
                    val phase = absolute.toDouble() * musicalCycles / length
                    val readPhase = if (config.mode == OrphicaMode.REVERSE) -phase + grain.offset else phase + grain.offset
                    val window = (.5 - .5 * cos(2.0 * PI * age / (size - 1))).toFloat()
                    val sample = carrier(readPhase) * window
                    left[frame] += sample * (1f - max(grain.pan, 0f))
                    right[frame] += sample * (1f + minOf(grain.pan, 0f))
                    overlap[frame] += window
                }
            }
            val amount = if (config.mode == OrphicaMode.CLEAR) 0f else config.fracture * .65f
            return FloatArray(length * 2) { sampleIndex ->
                val frame = sampleIndex / 2
                if (sampleIndex % 2048 == 0) checkCancelled(cancelled)
                val phase = (frame + turn * length).toDouble() * musicalCycles / length
                val wave = carrier(phase)
                val pan = (.06 * sin(2.0 * PI * frame / length + panPhase)).toFloat()
                val original = wave * if (sampleIndex % 2 == 0) 1f + pan else 1f - pan
                val cloud = if (sampleIndex % 2 == 0) left[frame] else right[frame]
                original * (1f - amount) + cloud / max(1f, overlap[frame]) * amount
            }
        }
        return HeldMaterial(source, evaluate(0), evaluate(1), capture, musicalCycles, grainCount)
    }
}
