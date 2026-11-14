package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

/** Frozen model 1 for exact replay of the first audition recipes. */
/**
 * Digital strike -> driven resonant bite -> darkening short repeats -> finite landing.
 * R1 is a listening prototype, not a circuit emulation. All voicing constants are shape
 * values awaiting audition. HOLD is reserved: it never claims a loop from a dying delay.
 *
 * Unlike FATHOM/THUMP, the adjacent source morph feeds an integrated lossy memory stage.
 * Drive and the resonant filter stay OUTSIDE its feedback path. The fixed integer delay
 * has gain one and its convex lowpass has sup-norm gain <=1, so feedback <=.75 contracts.
 */
internal object TempestLegacy {
    const val MODEL_VERSION = 1
    const val OUTPUT_FRAMES = 52_920
    const val TUNE_SEMITONES = 36

    fun macrosFor(voice: TempestVoice): List<MacroSpec> {
        val v = if (voice == TempestVoice.KICK) floatArrayOf(.22f, .10f, .45f, .55f, .15f)
            else floatArrayOf(.70f, .65f, .75f, .65f, .45f)
        return listOf("TUNE", "SOURCE", "FILTER", "DRIVE", "SPACE").mapIndexed { i, name -> MacroSpec(name, v[i]) } +
            MacroSpec("HOLD", 0f)
    }

    fun defaults(voice: TempestVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    fun settled(voice: TempestVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (name, default) -> macros[name]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: default }

    fun midiFor(tune: Float): Int = 24 + ((tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(tune: Float): Float = (440.0 * 2.0.pow((midiFor(tune) - 69) / 12.0)).toFloat()

    // Intentional synthesis metadata. Capture-side classification is measured in the audition,
    // not used as an audio filter; low pitched strikes may naturally be identified as drums.
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: TempestVoice,
                     @Suppress("UNUSED_PARAMETER") macros: Map<String, Float> = emptyMap()): DrumClass = DrumClass.PERC

    fun scramble(voice: TempestVoice, random: Random = Random.Default): Map<String, Float> =
        defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else random.nextFloat() }

    /** Linear source weights preserve the anchor bound, unlike equal-power crossfades. */
    internal class Source(source: Float, seed: Int) {
        private val position = source * 3f
        private val first = position.toInt().coerceAtMost(3)
        private val second = (first + 1).coerceAtMost(3)
        private val mix = position - first
        private val noise = Dsp.Noise(seed)
        private fun anchor(index: Int, phase: Double, indexPm: Double): Double = when (index) {
            0 -> sin(phase)
            1 -> sin(phase + indexPm * sin(2 * phase))
            2 -> .55 * sin(phase) + .30 * sin(sqrt(2.0) * phase) + .15 * sin(E * phase)
            else -> .35 * sin(phase) + .65 * noise.next()
        }
        fun step(phase: Double, indexPm: Double): Float {
            val a = anchor(first, phase, indexPm)
            val b = if (second == first || mix == 0f) a else anchor(second, phase, indexPm)
            return (a * (1 - mix) + b * mix).toFloat()
        }
    }

    /** No drive or resonant gain inside this loop. Test-only feedback override proves decay guards. */
    internal class Memory(voice: TempestVoice, space: Float, rate: Int, feedbackOverride: Float? = null) {
        private val line = FloatArray(((if (voice == TempestVoice.KICK) .012 else .035) * rate).roundToInt())
        private val loss = Dsp.FixedOnePole(3500f, rate)
        private val send = .25f * space
        private val feedback = feedbackOverride ?: (.2f + .55f * space)
        private val wet = .6f * space
        private var head = 0
        fun step(input: Float): Float {
            val delayed = line[head]
            line[head] = send * input + feedback * loss.lp(delayed)
            head = (head + 1) % line.size
            return input + wet * delayed
        }
    }

    internal fun drive(input: Float, gain: Double): Float = (tanh(gain * input + .2) - tanh(.2)).toFloat()

    /** Rate-independent reference path also supports higher-rate alias comparisons. */
    internal fun synthesize(voice: TempestVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        require(rate >= Dsp.RATE && rate % Dsp.RATE == 0)
        val m = settled(voice, macros)
        val root = frequencyFor(m.getValue("TUNE")).toDouble()
        // HOLD and unknown keys cannot change finite R1 audio, including its noise seed.
        val source = Source(m.getValue("SOURCE"), Dsp.seedFor("TEMPEST", MODEL_VERSION, voice.name, midiFor(m.getValue("TUNE"))))
        val memory = Memory(voice, m.getValue("SPACE"), rate)
        val svf = Dsp.TptSvf(rate)
        val dc = Dsp.FixedOnePole(10f, rate)
        val gain = 10.0.pow(m.getValue("DRIVE").toDouble())
        val cutoff = 80.0 * 125.0.pow(m.getValue("FILTER").toDouble())
        val damping = if (voice == TempestVoice.KICK) 1.25f else (1f / 1.2f)
        val tau = if (voice == TempestVoice.KICK) .055 else .12
        val ampMultiplier = exp(-1.0 / (rate * tau))
        val pitchMultiplier = exp(-1.0 / (rate * .012))
        val filterMultiplier = exp(-1.0 / (rate * .025))
        val pmMultiplier = exp(-1.0 / (rate * .035))
        var amp = 1.0; var bend = 1.0; var contour = 1.0; var pm = 1.0
        var phase = 0.0
        return FloatArray(OUTPUT_FRAMES * (rate / Dsp.RATE)) { i ->
            // Keep the unwrapped master phase: wrapping it would discontinuously reset the
            // irrational-ratio oscillators. Double precision is ample over this finite note.
            phase += 2 * PI * root * (if (voice == TempestVoice.KICK) 1 + 1.5 * bend else 1.0) / rate
            val envelope = min(i / (rate * .0007), 1.0) * amp
            val excited = source.step(phase, .3 + 2.7 * pm) * envelope.toFloat()
            val driven = drive(excited, gain)
            val centered = driven - dc.lp(driven)
            svf.process(centered, min(15000.0, cutoff * (1 + 2 * contour)).toFloat(), damping)
            // k*band gives unity response at resonance, rather than Q-dependent makeup.
            val filtered = if (voice == TempestVoice.KICK) svf.low else damping * svf.band
            amp *= ampMultiplier; bend *= pitchMultiplier; contour *= filterMultiplier; pm *= pmMultiplier
            memory.step(filtered)
        }
    }

    internal fun finish(raw: FloatArray): FloatArray {
        val filtered = raw.copyOf()
        Tide.bandLimit(filtered, Dsp.RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(filtered, Dsp.RATE)
        val mean = out.sumOf { it.toDouble() } / out.size
        val dc = Dsp.FixedOnePole(20f, Dsp.RATE)
        for (i in out.indices) {
            val x = (out[i] - mean).toFloat()
            out[i] = x - dc.lp(x)
        }
        Dsp.levelTo(out, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return out
    }

    fun render(voice: TempestVoice, macros: Map<String, Float> = emptyMap()): Snip =
        Snip(finish(synthesize(voice, macros, Dsp.RATE * Dsp.OVERSAMPLE)), 1, Dsp.RATE)
}
