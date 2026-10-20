package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class OrigamiVoice { CRANE, FAN, STILL }

/** FOLDED_ONLY is the complete dry + reflected upper bank; add ROOT_ONLY to reconstruct COMBINED. */
enum class OrigamiDiagnostic { COMBINED, SOURCE, ROOT_ONLY, FOLDED_ONLY }

/**
 * A geometry observation before its time envelope. frequencyHz is the full transformed target,
 * independent of FOLD; sourceAmplitude is the unmixed source. The root is a separate oscillator.
 */
data class OrigamiPartial(
    val index: Int,
    val sourceHz: Double,
    val frequencyHz: Double,
    val sourceAmplitude: Double,
    val dryAmplitude: Double,
    val wetAmplitude: Double,
) {
    /** Reflected branch amplitude; retained as a convenient geometry-probe name. */
    val amplitude: Double get() = wetAmplitude
}

/**
 * ORIGAMI's fourth listening model: finite notes made from analytic spectral geometry.
 * Upper partials reflect around a note-relative crease, translate, and change their spacing.
 * FOLD splits each partial's amplitude between its source frequency and transformed destination.
 * The dry and reflected branches are separate oscillators, not crossfaded recordings. This avoids
 * collapsing every reflected partial onto the crease at half depth, as frequency interpolation would.
 * A primary fundamental remains at the played pitch. Soft, restrained harmonic upper banks supply
 * the folding material. STILL organizes its frozen source shape around harmonics with sparse quiet
 * detuned companions; it is neither noise nor a loop.
 * The accepted model-3 source, root, and contours are retained. Centered folds provide a fifth
 * for FAN and a major tenth for STILL; small axis motion leaves unreflected low harmonics stable.
 *
 * Sources and phases depend only on the model, voice, and seed. Geometry comparisons therefore
 * use the same excitation, including at FOLD zero. Frequencies are never wrapped into the bass:
 * a smooth gain taper rejects geometry outside the useful band. The 4x oscillator bank maintains
 * phase while frequency changes; shared linear decimation precedes melodic loudness matching.
 * This deliberately exposes no HOLD control until an independent loop design has been proved.
 */
object Origami {
    const val MODEL_VERSION = 4
    const val MIDI_MIN = 48
    const val MIDI_MAX = 72
    const val ROOT_MIDI = MIDI_MIN
    const val TUNE_SEMITONES = MIDI_MAX - MIDI_MIN
    const val MAX_PARTIALS = 64
    const val USABLE_BANDWIDTH_HZ = 18_000.0
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val BLOCK = 64
    private const val TAU = 2.0 * PI
    private const val LOG_1000 = 6.907755278982137
    private const val SOURCE_SEED_VERSION = 1

    private fun requireModel(model: Int) {
        require(model in 1..MODEL_VERSION) { "unsupported ORIGAMI model $model" }
    }

    @Suppress("UNUSED_PARAMETER")
    fun rootMidi(voice: OrigamiVoice): Int = ROOT_MIDI

    fun midiFor(voice: OrigamiVoice, tune: Float): Int = rootMidi(voice) +
        ((tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()

    fun frequencyFor(voice: OrigamiVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))

    fun macrosFor(voice: OrigamiVoice): List<MacroSpec> {
        val values = when (voice) {
            OrigamiVoice.CRANE -> floatArrayOf(.55f, .72f, .5f, .5f, .18f)
            OrigamiVoice.FAN -> floatArrayOf(.5f, .62f, .5f, .5f, .36f)
            OrigamiVoice.STILL -> floatArrayOf(.5f, .68f, .5f, .5f, .32f)
        }
        return listOf(
            MacroSpec("TUNE", .5f, neutral = .5f),
            MacroSpec("CREASE", values[0], neutral = .5f),
            MacroSpec("FOLD", values[1], neutral = 0f),
            MacroSpec("TURN", values[2], neutral = .5f),
            MacroSpec("WARP", values[3], neutral = .5f),
            MacroSpec("MOTION", values[4], neutral = 0f),
        )
    }

    fun defaults(voice: OrigamiVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    private fun settled(voice: OrigamiVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (key, default) ->
            (macros[key]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f)
        }

    private fun note(voice: OrigamiVoice, m: Map<String, Float>, midi: Int?): Int {
        val played = midi ?: midiFor(voice, m.getValue("TUNE"))
        require(played in MIDI_MIN..MIDI_MAX) { "ORIGAMI supports MIDI $MIDI_MIN..$MIDI_MAX, got $played" }
        return played
    }

    fun durationSeconds(voice: OrigamiVoice): Double = when (voice) {
        OrigamiVoice.CRANE -> 3.2
        OrigamiVoice.FAN -> 3.0
        OrigamiVoice.STILL -> 4.0
    }

    private data class Bank(val ratios: DoubleArray, val amplitudes: DoubleArray, val phases: DoubleArray)

    private fun bank(voice: OrigamiVoice, seed: Long): Bank {
        val random = Random(Dsp.seedFor("ORIGAMI", SOURCE_SEED_VERSION, voice.name, seed))
        if (voice == OrigamiVoice.STILL) return stillBank(random)
        val count = if (voice == OrigamiVoice.CRANE) 40 else 36
        val ratios = DoubleArray(count) { index -> index + 2.0 }
        val amplitudes = DoubleArray(count) { index ->
            val ratio = ratios[index]
            if (voice == OrigamiVoice.CRANE) ratio.pow(-.82) * exp(-.023 * index)
            else ratio.pow(-2.0) * exp(-.12 * index) * if ((index + 2) % 2 == 0) .85 else 1.0
        }
        // An L1 budget bounds even a coincident folded bank without a nonlinear limiter.
        val budget = if (voice == OrigamiVoice.CRANE) .70 else .18
        val gain = budget / amplitudes.sum()
        for (index in amplitudes.indices) amplitudes[index] *= gain
        return Bank(ratios, amplitudes, DoubleArray(count) { random.nextDouble() * TAU })
    }

    private fun stillBank(random: Random): Bank {
        // Consume the same two 64-value seeded blocks as model 1 before obtaining phases.
        // The source changes; the random excitation remains stable for controlled comparisons.
        val ratioJitter = DoubleArray(MAX_PARTIALS) { random.nextDouble() }
        val levelJitter = DoubleArray(MAX_PARTIALS) { random.nextDouble() }
        val phaseSeed = DoubleArray(MAX_PARTIALS) { random.nextDouble() * TAU }
        val companions = intArrayOf(3, 5, 8)
        val carriers = 12 // Exact harmonics 2..13 supply a restrained low and middle body.
        val count = carriers + companions.size * 2
        val ratios = DoubleArray(count) { index ->
            if (index < carriers) index + 2.0 else {
                val companion = index - carriers
                val harmonic = companions[companion / 2].toDouble()
                val direction = if (companion % 2 == 0) -1.0 else 1.0
                val cents = direction * (2.5 + 1.5 * ratioJitter[index])
                harmonic * 2.0.pow(cents / 1200.0)
            }
        }
        val amplitudes = DoubleArray(count) { index ->
            val harmonic = if (index < carriers) index + 2.0 else companions[(index - carriers) / 2].toDouble()
            val body = harmonic.pow(-2.30) * exp(-.18 * (harmonic - 2.0))
            val companionGain = if (index < carriers) 1.0 else .018
            body * companionGain * (.9 + .2 * levelJitter[index])
        }
        val gain = .13 / amplitudes.sum()
        for (index in amplitudes.indices) amplitudes[index] *= gain
        return Bank(ratios, amplitudes, phaseSeed.copyOf(count))
    }

    private fun smooth(value: Double): Double {
        val x = value.coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    private fun bandGain(hz: Double, rootHz: Double): Double {
        // Upper material fades before it can compete with the fundamental or approach Nyquist.
        return smooth((hz - rootHz * .95) / (rootHz * .45)) *
            (1.0 - smooth((hz - 15_500.0) / (USABLE_BANDWIDTH_HZ - 15_500.0)))
    }

    /** Partial pickup weighting keeps transformed upper material gentle without filtering audio. */
    private fun wetVoicing(voice: OrigamiVoice, hz: Double, rootHz: Double): Double {
        val scale = when (voice) {
            OrigamiVoice.CRANE -> return 1.0
            OrigamiVoice.FAN -> 10.0
            OrigamiVoice.STILL -> 7.5
        }
        val ratio = hz.coerceAtLeast(0.0) / rootHz
        return 1.0 / sqrt(1.0 + (ratio / scale).pow(4))
    }

    private fun reflect(ratio: Double, crease: Double): Double =
        if (ratio <= crease) ratio else 2.0 * crease - ratio

    private data class Geometry(val crease: Double, val turn: Double, val spacing: Double)

    /** Continuous exponential travel with an exact center at macro .5 and independent endpoints. */
    private fun centeredCrease(macro: Float, low: Double, center: Double, high: Double): Double =
        if (macro < .5f) low * (center / low).pow(macro.toDouble() * 2.0)
        else center * (high / center).pow((macro.toDouble() - .5) * 2.0)

    private fun geometry(voice: OrigamiVoice, m: Map<String, Float>, time: Double): Geometry {
        val motion = m.getValue("MOTION").toDouble()
        val movement = when (voice) {
            OrigamiVoice.CRANE -> sin(TAU * 1.15 * time) * exp(-time / .8)
            OrigamiVoice.FAN -> sin(TAU * .35 * time)
            OrigamiVoice.STILL -> .75 * sin(TAU * .10 * time) + .25 * sin(TAU * .17 * time)
        }
        val baseCrease = when (voice) {
            OrigamiVoice.CRANE -> 3.0 * 5.0.pow(m.getValue("CREASE").toDouble())
            OrigamiVoice.FAN -> centeredCrease(m.getValue("CREASE"), 1.6, 2.25, 3.4)
            OrigamiVoice.STILL -> centeredCrease(m.getValue("CREASE"), 2.6, 3.25, 4.4)
        }
        val axisTravel = when (voice) {
            OrigamiVoice.CRANE -> .16 * baseCrease
            OrigamiVoice.FAN -> .015
            OrigamiVoice.STILL -> .010
        }
        // Motion moves axes only. The warm unreflected octave is not translated away from the note.
        val crease = baseCrease + axisTravel * motion * movement
        return Geometry(crease, (m.getValue("TURN") - .5) * 4.0,
            2.0.pow((m.getValue("WARP") - .5) * 1.8))
    }

    private fun relocatedRatio(voice: OrigamiVoice, ratio: Double, geometry: Geometry): Double {
        var target = reflect(ratio, geometry.crease)
        // STILL's two axes share their motion and stay half a root apart. At center, source
        // harmonics 3 and 4 meet at 2.5 roots, so they do not form a sustained beating cluster.
        if (voice == OrigamiVoice.STILL) target = reflect(target, geometry.crease - .5)
        return 1.0 + (target + geometry.turn - 1.0) * geometry.spacing
    }

    /** Source and actual target frequencies, including rejected targets whose amplitude is zero. */
    fun partials(
        voice: OrigamiVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int? = null,
        seed: Long = 0,
        timeSeconds: Double = 0.0,
        model: Int = MODEL_VERSION,
    ): List<OrigamiPartial> {
        requireModel(model)
        if (model == 1 || voice == OrigamiVoice.CRANE) {
            return OrigamiV1.partials(voice, macros, midi, seed, timeSeconds)
        }
        if (model == 2) return OrigamiV2.partials(voice, macros, midi, seed, timeSeconds, model)
        if (model == 3) return OrigamiV3.partials(voice, macros, midi, seed, timeSeconds, model)
        require(timeSeconds.isFinite() && timeSeconds >= 0.0) { "ORIGAMI time must be finite and nonnegative" }
        val m = settled(voice, macros)
        val hz = Keys.midiHz(note(voice, m, midi)).toDouble()
        val source = bank(voice, seed)
        val shape = geometry(voice, m, timeSeconds)
        val fold = m.getValue("FOLD").toDouble()
        return source.ratios.indices.map { index ->
            val sourceHz = hz * source.ratios[index]
            val targetHz = hz * relocatedRatio(voice, source.ratios[index], shape)
            val sourceWeight = source.amplitudes[index] * bandGain(sourceHz, hz)
            // Out-of-band source harmonics never become an aliased or folded-in excitation.
            OrigamiPartial(index, sourceHz, targetHz, sourceWeight, (1.0 - fold) * sourceWeight,
                fold * source.amplitudes[index] * minOf(bandGain(sourceHz, hz), bandGain(targetHz, hz)) *
                    wetVoicing(voice, targetHz, hz))
        }
    }

    private fun rootAmplitude(voice: OrigamiVoice): Double = when (voice) {
        OrigamiVoice.CRANE -> .14
        OrigamiVoice.FAN -> .12
        OrigamiVoice.STILL -> .09
    }

    private fun envelope(voice: OrigamiVoice, index: Int, time: Double): Double {
        val duration = durationSeconds(voice)
        if (time <= 0.0 || time >= duration) return 0.0
        return when (voice) {
            OrigamiVoice.CRANE -> {
                val decay = if (index < 0) 3.0 else 3.1 / (1.0 + .085 * index)
                smooth(time / .0025) * exp(-LOG_1000 * time / decay) * smooth((duration - time) / .04)
            }
            OrigamiVoice.FAN -> smooth(time / .035) * smooth((duration - time) / .8)
            OrigamiVoice.STILL -> smooth(time / .48) * smooth((duration - time) / 1.0)
        }
    }

    /**
     * Finite 44.1 kHz evidence before loudness matching. All diagnostic paths have the same
     * linear conditioning and gain, so the root and upper bank remain independently measurable.
     * A nonnull MIDI overrides TUNE; otherwise TUNE chooses a semitone in 48..72.
     */
    fun renderRaw(
        voice: OrigamiVoice,
        macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f,
        midi: Int? = null,
        seed: Long = 0,
        diagnostic: OrigamiDiagnostic = OrigamiDiagnostic.COMBINED,
        model: Int = MODEL_VERSION,
    ): FloatArray {
        requireModel(model)
        if (model == 1 || voice == OrigamiVoice.CRANE) {
            return OrigamiV1.renderRaw(voice, macros, velocity, midi, seed, diagnostic)
        }
        if (model == 2) return OrigamiV2.renderRaw(voice, macros, velocity, midi, seed, diagnostic, model)
        if (model == 3) return OrigamiV3.renderRaw(voice, macros, velocity, midi, seed, diagnostic, model)
        val m = settled(voice, macros)
        if (diagnostic == OrigamiDiagnostic.SOURCE || diagnostic == OrigamiDiagnostic.ROOT_ONLY || m.getValue("FOLD") == 0f) {
            // Accepted references are replayed directly, including every macro, note, and seed.
            return OrigamiV3.renderRaw(voice, macros, velocity, midi, seed, diagnostic, model = 3)
        }
        val rootHz = Keys.midiHz(note(voice, m, midi)).toDouble()
        val frames = (durationSeconds(voice) * RATE).roundToInt()
        val v = (velocity.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f).toDouble()
        if (v == 0.0) return FloatArray(frames)
        val source = bank(voice, seed)
        val sourceOnly = diagnostic == OrigamiDiagnostic.SOURCE || m.getValue("FOLD") == 0f
        val rootOnly = diagnostic == OrigamiDiagnostic.ROOT_ONLY
        val includeRoot = diagnostic != OrigamiDiagnostic.FOLDED_ONLY
        val fold = m.getValue("FOLD").toDouble()
        val dryCount = if (!rootOnly && (sourceOnly || fold < 1.0)) source.ratios.size else 0
        val wetCount = if (!rootOnly && !sourceOnly) source.ratios.size else 0
        val count = 1 + dryCount + wetCount
        val partialIndex = IntArray(count) { index ->
            when {
                index == 0 -> -1
                index <= dryCount -> index - 1
                else -> index - dryCount - 1
            }
        }
        val wet = BooleanArray(count) { index -> index > 0 && index > dryCount }
        val oscillatorSin = DoubleArray(count) { index -> if (index == 0) 0.0 else sin(source.phases[partialIndex[index]]) }
        val oscillatorCos = DoubleArray(count) { index -> if (index == 0) 1.0 else cos(source.phases[partialIndex[index]]) }
        val stepSin = DoubleArray(count)
        val stepCos = DoubleArray(count)
        val chirpSin = DoubleArray(count)
        val chirpCos = DoubleArray(count)
        val gains = DoubleArray(count)
        val gainIncrement = DoubleArray(count)
        val sourceHz = DoubleArray(count) { index -> if (index == 0) rootHz else rootHz * source.ratios[partialIndex[index]] }
        val weights = DoubleArray(count) { index ->
            if (index == 0) { if (includeRoot) rootAmplitude(voice) else 0.0 }
            else source.amplitudes[partialIndex[index]] *
                if (sourceOnly) 1.0 else if (wet[index]) fold else 1.0 - fold
        }
        val raw = FloatArray(frames * Dsp.OVERSAMPLE)
        for (blockStart in raw.indices step BLOCK) {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("ORIGAMI render cancelled")
            val blockLength = minOf(BLOCK, raw.size - blockStart)
            val time = blockStart.toDouble() / INTERNAL_RATE
            val nextTime = (blockStart + blockLength).toDouble() / INTERNAL_RATE
            val shape = if (wetCount > 0) geometry(voice, m, time) else null
            val nextShape = if (wetCount > 0) geometry(voice, m, nextTime) else null
            for (index in 0 until count) {
                val frequency = if (!wet[index]) sourceHz[index]
                    else rootHz * relocatedRatio(voice, source.ratios[partialIndex[index]], shape!!)
                val nextFrequency = if (!wet[index]) sourceHz[index]
                    else rootHz * relocatedRatio(voice, source.ratios[partialIndex[index]], nextShape!!)
                // Rejected oscillators still advance continuously at a bounded frequency. They
                // are silent outside the taper and are never modulo-wrapped back into the bass.
                val f0 = frequency.coerceIn(0.0, USABLE_BANDWIDTH_HZ)
                val f1 = nextFrequency.coerceIn(0.0, USABLE_BANDWIDTH_HZ)
                val delta = TAU * (f1 - f0) / (INTERNAL_RATE * blockLength)
                val step = TAU * f0 / INTERNAL_RATE + .5 * delta
                stepSin[index] = sin(step); stepCos[index] = cos(step)
                chirpSin[index] = sin(delta); chirpCos[index] = cos(delta)
                val sourceTaper = if (index == 0) 1.0 else bandGain(sourceHz[index], rootHz)
                val taper = if (!wet[index]) sourceTaper else
                    minOf(sourceTaper, bandGain(frequency, rootHz)) * wetVoicing(voice, frequency, rootHz)
                val nextTaper = if (!wet[index]) sourceTaper else
                    minOf(sourceTaper, bandGain(nextFrequency, rootHz)) * wetVoicing(voice, nextFrequency, rootHz)
                gains[index] = weights[index] * v * taper * envelope(voice, partialIndex[index], time)
                val nextGain = weights[index] * v * nextTaper * envelope(voice, partialIndex[index], nextTime)
                gainIncrement[index] = (nextGain - gains[index]) / blockLength
                // Normalize recurrence error without resetting phase or replaying any frame.
                val norm = sqrt(oscillatorSin[index] * oscillatorSin[index] + oscillatorCos[index] * oscillatorCos[index])
                oscillatorSin[index] /= norm; oscillatorCos[index] /= norm
            }
            for (offset in 0 until blockLength) {
                var sample = 0.0
                for (index in 0 until count) {
                    sample += oscillatorSin[index] * gains[index]
                    val s = oscillatorSin[index] * stepCos[index] + oscillatorCos[index] * stepSin[index]
                    oscillatorCos[index] = oscillatorCos[index] * stepCos[index] - oscillatorSin[index] * stepSin[index]
                    oscillatorSin[index] = s
                    val ds = stepSin[index] * chirpCos[index] + stepCos[index] * chirpSin[index]
                    stepCos[index] = stepCos[index] * chirpCos[index] - stepSin[index] * chirpSin[index]
                    stepSin[index] = ds
                    gains[index] += gainIncrement[index]
                }
                raw[blockStart + offset] = sample.toFloat()
            }
        }
        val out = Dsp.decimate(raw, RATE)
        // The same linear high-pass and end guard preserve diagnostic reconstruction.
        val hp = Dsp.OnePole(RATE)
        for (index in out.indices) out[index] -= hp.lp(out[index], 8f)
        Dsp.fadeTail(out)
        return out
    }

    fun render(
        voice: OrigamiVoice,
        macros: Map<String, Float> = emptyMap(),
        velocity: Float = 1f,
        midi: Int? = null,
        seed: Long = 0,
        model: Int = MODEL_VERSION,
    ): Snip {
        requireModel(model)
        if (model == 1 || voice == OrigamiVoice.CRANE) {
            return OrigamiV1.render(voice, macros, velocity, midi, seed)
        }
        if (model == 2) return OrigamiV2.render(voice, macros, velocity, midi, seed, model)
        if (model == 3) return OrigamiV3.render(voice, macros, velocity, midi, seed, model)
        val out = renderRaw(voice, macros, velocity, midi, seed, model = model)
        val v = (velocity.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f)
        if (v > 0f) Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET * v.pow(.65f))
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
