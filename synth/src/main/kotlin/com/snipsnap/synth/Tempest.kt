package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class TempestVoice { KICK, METAL, SNARE, CLAP, TOM, HAT }

/**
 * Digital strike -> driven resonant bite -> darkening short repeats -> finite landing.
 * Model 3 adds four candidate drum voices; KICK/METAL default to their accepted model 2.
 * Model 2 revises the first listening prototype, not a circuit emulation. All voicing constants are shape
 * values accepted in the model-2 owner audition. HOLD is reserved: it never claims a loop from a dying delay.
 *
 * Unlike FATHOM/THUMP, the continuous source morph feeds an integrated lossy memory stage.
 * Drive follows the resonant filter, and both stay OUTSIDE its feedback path. The fixed integer delay
 * has gain one and its convex lowpass has sup-norm gain <=1, so feedback < .45 contracts.
 */
object Tempest {
    const val MODEL_VERSION = 3
    val APPROVED_VOICES: List<TempestVoice> = listOf(TempestVoice.KICK, TempestVoice.METAL)
    val EXPANSION_VOICES: List<TempestVoice> = listOf(TempestVoice.SNARE, TempestVoice.CLAP, TempestVoice.TOM, TempestVoice.HAT)

    fun defaultModelFor(voice: TempestVoice): Int = if (voice in APPROVED_VOICES) 2 else 3
    fun supports(voice: TempestVoice, model: Int): Boolean = model in 1..MODEL_VERSION &&
        (voice in APPROVED_VOICES || model == 3)
    const val OUTPUT_FRAMES = 52_920
    const val TUNE_SEMITONES = 36

    fun macrosFor(voice: TempestVoice): List<MacroSpec> {
        if (voice in EXPANSION_VOICES) return TempestPercussion.macrosFor(voice)
        val v = if (voice == TempestVoice.KICK) floatArrayOf(.22f, .10f, .65f, .35f, .15f)
            else floatArrayOf(.70f, .65f, .55f, .45f, .35f)
        return listOf("TUNE", "SOURCE", "FILTER", "DRIVE", "SPACE").mapIndexed { i, name -> MacroSpec(name, v[i]) } +
            MacroSpec("HOLD", 0f)
    }

    fun defaults(voice: TempestVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }

    fun settled(voice: TempestVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (name, default) -> macros[name]?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: default }

    fun midiFor(tune: Float): Int = 24 + ((tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(tune: Float): Float = (440.0 * 2.0.pow((midiFor(tune) - 69) / 12.0)).toFloat()

    // TEMPEST is percussion: file the actual render, including low kicks and toms.
    // Kit construction reuses its already rendered snip to avoid a second synthesis pass.
    fun drumClassFor(snip: Snip): DrumClass = Classifier.classify(snip).drumClass
    fun drumClassFor(voice: TempestVoice, macros: Map<String, Float> = emptyMap(),
                     model: Int = defaultModelFor(voice)): DrumClass = drumClassFor(render(voice, macros, model))

    fun scramble(voice: TempestVoice, random: Random = Random.Default): Map<String, Float> =
        defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else random.nextFloat() }

    /** Continuous harmonic-to-inharmonic color; no discrete algorithm boundaries.
     * Fixed convex weights keep the root present while PM depth and noise develop over the dial. */
    internal class Source(private val source: Float, seed: Int) {
        private val noise = Dsp.Noise(seed)
        fun step(phase: Double, indexPm: Double): Float {
            val s = source.toDouble()
            val harmonics = .58 * sin(2 * phase) + .42 * sin(3 * phase)
            val metal = .58 * sin(sqrt(2.0) * phase) + .42 * sin(E * phase)
            val pm = sin(phase + (.1 + 2.4 * s) * (.65 + .35 * indexPm / 3.0) * sin(2 * phase))
            val tone = .42 * sin(phase) + .38 * ((1-s)*harmonics + s*metal) + .20*pm
            val grit = .12*s
            return ((1-grit)*tone + grit*noise.next()).toFloat()
        }
    }

    /** No drive or resonant gain inside this loop. Test-only feedback override proves decay guards. */
    internal class Memory(voice: TempestVoice, space: Float, rate: Int, feedbackOverride: Float? = null) {
        private val seconds = (if (voice == TempestVoice.KICK) .022 + .100*space else .034 + .140*space)
        private val line = FloatArray((seconds * rate).roundToInt())
        private val loss = Dsp.FixedOnePole(3500f, rate)
        // A unity send makes the first repeat available at low SPACE; it no longer
        // gets attenuated by both send and wet. Fixed time per render avoids pitch sweeps.
        private val feedback = feedbackOverride ?: exp(-ln(1000.0)*line.size/rate/(.18+.42*space)).toFloat()
        private val wet = .9f*sqrt(space)
        private var head = 0
        fun step(input: Float): Float {
            val delayed = line[head]
            line[head] = input + feedback * loss.lp(delayed)
            head = (head + 1) % line.size
            return input + wet * delayed
        }
    }

    /** Bounded dry-to-folded drive, so low settings change shape rather than only gain.
     * The folded term stays in [-1,1]; the convex mix is bounded by max(abs(input),1).
     * Square-root wet travel exposes the first fold early. */
    internal fun drive(input: Float, amount: Double, voltage: Double = 2.0): Float {
        val wet = sqrt(amount)
        val bias = .15*amount
        val folded = (sin((1+7*amount)*voltage*input+bias)-sin(bias))/(1+abs(sin(bias)))
        return ((1-wet)*input+wet*folded).toFloat()
    }

    /** Rate-independent reference path also supports higher-rate alias comparisons. */
    internal fun synthesize(voice: TempestVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        require(rate >= Dsp.RATE && rate % Dsp.RATE == 0)
        if (voice in EXPANSION_VOICES) return TempestPercussion.synthesize(voice, macros, rate)
        val m = settled(voice, macros)
        val root = frequencyFor(m.getValue("TUNE")).toDouble()
        // HOLD and unknown keys cannot change finite audio, including its noise seed.
        val source = Source(m.getValue("SOURCE"), Dsp.seedFor("TEMPEST", 2, voice.name, midiFor(m.getValue("TUNE"))))
        val memory = Memory(voice, m.getValue("SPACE"), rate)
        val svf = Dsp.TptSvf(rate)
        val dc = Dsp.FixedOnePole(10f, rate)
        val drive = m.getValue("DRIVE").toDouble()
        val cutoff = root * .5 * 8.0.pow(m.getValue("FILTER").toDouble())
        val damping = if (voice == TempestVoice.KICK) (1f / 1.1f) else (1f / 1.5f)
        val tau = if (voice == TempestVoice.KICK) .035 * 4.0.pow(m.getValue("SOURCE").toDouble())
            else .055 * 3.0.pow(m.getValue("SOURCE").toDouble())
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
            // Source color also changes its native decay. A shared smooth release gives
            // every source a definite end before the memory's last repeats, without truncation.
            val release = ((i.toDouble()/rate-.35)/.25).coerceIn(0.0,1.0)
            val terminal = 1-release*release*(3-2*release)
            val envelope = min(i / (rate * .0007), 1.0) * amp * terminal
            val excited = source.step(phase, .3 + 2.7 * pm) * envelope.toFloat()
            svf.process(excited, min(15000.0, cutoff * (1 + 1.2 * contour)).toFloat(), damping)
            // k*band gives unity response at resonance, rather than Q-dependent makeup.
            val filtered = if (voice == TempestVoice.KICK) svf.low else damping * svf.band
            // Post-filter folding keeps DRIVE's new harmonics from being erased by FILTER.
            // The asymmetric stage is DC blocked before any signal enters the memory loop.
            val driven = drive(filtered, drive, if (voice == TempestVoice.KICK) 2.0 else 4.0)
            val centered = driven-dc.lp(driven)
            amp *= ampMultiplier; bend *= pitchMultiplier; contour *= filterMultiplier; pm *= pmMultiplier
            memory.step(centered)
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

    fun render(voice: TempestVoice, macros: Map<String, Float> = emptyMap(), model: Int = defaultModelFor(voice)): Snip {
        require(supports(voice, model)) { "unsupported TEMPEST $voice model $model" }
        if (model == 1) return TempestLegacy.render(voice, macros)
        return Snip(finish(synthesize(voice, macros, Dsp.RATE * Dsp.OVERSAMPLE)), 1, Dsp.RATE)
    }
}
