package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class LorentzVoice { FUSE, LORENTZ }

/**
 * Falling sweep -> finite collision -> irreversible capture -> steady sub.
 *
 * Model 3 ports the owner-approved third Python audition. Welding is an analogy,
 * not an electromagnetic/contact solver. Fixed phases are intentional: there is
 * no random source or cross-render state. Every blend is convex and each source
 * is bounded before output conditioning. HOLD extends a finite decay, never loops.
 */
object Lorentz {
    const val MODEL_VERSION = 3
    const val TUNE_SEMITONES = 24
    const val MIN_MIDI = 24
    const val MAX_MIDI = MIN_MIDI + TUNE_SEMITONES
    const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val CAPTURE_SECONDS = .0015

    @Suppress("UNUSED_PARAMETER")
    fun macrosFor(voice: LorentzVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", .5f), MacroSpec("CAPACITOR", .7f), MacroSpec("GAP", .2f),
        MacroSpec("IMPACT", .6f), MacroSpec("WELD", .5f), MacroSpec("HOLD", 0f),
    )
    fun defaults(voice: LorentzVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(voice: LorentzVoice, macros: Map<String, Float>) = defaults(voice).mapValues { (k, v) ->
        (macros[k]?.takeIf { it.isFinite() } ?: v).coerceIn(0f, 1f)
    }
    @Suppress("UNUSED_PARAMETER")
    fun rootMidi(voice: LorentzVoice) = MIN_MIDI
    fun midiFor(voice: LorentzVoice, tune: Float): Int = rootMidi(voice) +
        (TUNE_SEMITONES * (tune.takeIf { it.isFinite() } ?: .5f).coerceIn(0f, 1f).toDouble()).roundToInt()
    fun frequencyFor(voice: LorentzVoice, tune: Float): Double = 440.0 * 2.0.pow((midiFor(voice, tune) - 69) / 12.0)

    /** Actual rendered classification, including bright impacts and long finite tails. */
    fun drumClassFor(voice: LorentzVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        Classifier.classify(render(voice, macros)).drumClass

    fun scramble(voice: LorentzVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val t = (temperature.takeIf { it.isFinite() } ?: .35f).coerceIn(0f, 1f)
        val base = settled(voice, if (near is LorentzPatch && near.voice == voice) near.macros else emptyMap())
        return base.mapValues { (k, v) ->
            if (k == "HOLD") v else (v + (2f * random.nextFloat() - 1f) * t * .35f).coerceIn(0f, 1f)
        }
    }

    internal data class Plan(
        val hz: Double, val ratio: Double, val fallRate: Double, val collisionSeconds: Double,
        val contactSeconds: Double, val t60: Double, val seconds: Double,
        val impact: Double, val gap: Double, val drive: Double,
    )

    internal fun plan(voice: LorentzVoice, macros: Map<String, Float>, velocity: Float = 1f): Plan {
        require(velocity.isFinite() && velocity in 0f..1f) { "LORENTZ velocity must be finite in 0..1" }
        val m = settled(voice, macros)
        fun value(k: String) = m.getValue(k).toDouble()
        val gap = value("GAP")
        val ratio = 40 * 2.0.pow(2 * (gap - .2))
        val k = 35 * 10.0.pow(value("CAPACITOR")) / 2.0.pow(1.5 * (gap - .2))
        val tc = ln(ratio) / k
        val t60 = .12 * 10.0.pow(value("WELD")) * (1 - value("HOLD")) + 1.2 * value("HOLD")
        // Full velocity preserves the approved source. Softer hits also soften
        // contact; level alone is applied after the shared conditioning stage.
        val impact = value("IMPACT") * (.25 + .75 * velocity)
        val contact = if (voice == LorentzVoice.FUSE) .010 + .044 * impact else .012 + .066 * impact
        return Plan(frequencyFor(voice, m.getValue("TUNE")), ratio, k, tc, contact, t60,
            min(1.45, tc + 1.1 * t60 + .010), impact, gap, 1 + 3 * value("WELD"))
    }

    private fun smooth(value: Double): Double {
        val x = value.coerceIn(0.0, 1.0)
        return x * x * x * (10 + x * (-15 + 6 * x))
    }
    private fun blep(p: Double, dt: Double): Double = when {
        p < dt -> { val a = p / dt; 2 * a - a * a - 1 }
        p > 1 - dt -> { val b = (p - 1) / dt; b * b + 2 * b + 1 }
        else -> 0.0
    }
    private fun flyer(p: Plan, voice: LorentzVoice, time: Double, rate: Int): Double {
        val u = min(time, p.collisionSeconds)
        val phase = p.hz * p.ratio / p.fallRate * -expm1(-p.fallRate * u) + p.hz * max(time - p.collisionSeconds, 0.0)
        val wrapped = phase - floor(phase)
        if (voice == LorentzVoice.FUSE) return sin(2 * PI * wrapped)
        val dt = p.hz * p.ratio * exp(-p.fallRate * u) / rate
        return (if (wrapped < .5) 1.0 else -1.0) + blep(wrapped, dt) - blep((wrapped + .5) % 1, dt)
    }
    private fun settlingPhase(age: Double, seconds: Double, hz: Double, start: Double, end: Double): Double {
        val u = min(age, seconds)
        // Integrate the moving ratio; ratio(t)*t would add an unwanted frequency term.
        return hz * (end * age + (start - end) * (u - u * u / seconds + u * u * u / (3 * seconds * seconds)))
    }
    private fun contact(p: Plan, voice: LorentzVoice, time: Double, target: Double, base: Double): Double {
        val age = max(0.0, time - p.collisionSeconds)
        val shape = smooth(age / CAPTURE_SECONDS) * (1 - smooth((age - .003) / (p.contactSeconds - .003)))
        val primary = sin(2 * PI * settlingPhase(age, p.contactSeconds, p.hz, 2.37, 1.0))
        val secondary = sin(2 * PI * settlingPhase(age, p.contactSeconds, p.hz, 5.13, 2.0))
        val modulator: Double; val index: Double; val rootShare: Double; val drive: Double; val mix: Double
        if (voice == LorentzVoice.FUSE) {
            modulator = primary + .12 * p.impact * secondary
            index = (1 + 5 * p.impact) * shape
            rootShare = .65; drive = 1 + 4 * p.impact; mix = .85 * p.impact.pow(.75) * shape
        } else {
            val progress = (age / p.contactSeconds).coerceIn(0.0, 1.0)
            val ripple = 1 - .6 * p.impact * p.impact * sin(PI * (1 + 4 * p.impact) * progress).pow(2)
            modulator = primary + .55 * p.impact * secondary
            index = (1 + 9 * p.impact) * shape * ripple
            rootShare = .52; drive = 1 + 7 * p.impact
            mix = .9 * p.impact.pow(.75) * shape * (.65 + .35 * ripple)
        }
        val pm = sin(2 * PI * p.hz * time + index * modulator)
        val collision = rootShare * target + (1 - rootShare) * tanh(drive * pm) / tanh(drive)
        return (1 - mix) * base + mix * collision
    }

    /** Unconditioned reference core, with arbitrary rate for cross-rate probes. */
    internal fun synthesize(voice: LorentzVoice, macros: Map<String, Float> = emptyMap(), rate: Int = INTERNAL_RATE,
        velocity: Float = 1f): FloatArray {
        require(rate >= Dsp.RATE) { "LORENTZ internal rate must be at least ${Dsp.RATE}" }
        val p = plan(voice, macros, velocity)
        val out = FloatArray(ceil(p.seconds * rate).toInt())
        if (velocity == 0f) return out
        val driveScale = tanh(p.drive)
        for (i in out.indices) {
            val time = i.toDouble() / rate
            val age = max(0.0, time - p.collisionSeconds)
            val target = sin(2 * PI * p.hz * time)
            val base = .7 * target + .3 * tanh(p.drive * target) / driveScale
            val captured = if (age < p.contactSeconds) contact(p, voice, time, target, base) else base
            val blend = smooth((time - p.collisionSeconds) / CAPTURE_SECONDS)
            val source = if (blend >= 1) captured else
                (1 - blend) * (.75 * flyer(p, voice, time, rate) + .25 * target) + blend * captured
            val flightGain = 1 - .3 * p.gap * (1 - smooth(min(time / p.collisionSeconds, 1.0)))
            out[i] = (source * smooth(time / .0003) * flightGain * exp(-ln(1000.0) * age / p.t60)).toFloat()
        }
        return out
    }

    fun render(voice: LorentzVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip {
        val raw = synthesize(voice, macros, velocity = velocity)
        Tide.bandLimit(raw, INTERNAL_RATE)
        val out = Dsp.decimate(raw, Dsp.RATE)
        val mean = out.average().toFloat()
        val dc = Dsp.FixedOnePole(20f)
        for (i in out.indices) { val x = out[i] - mean; out[i] = x - dc.lp(x) }
        Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        for (i in out.indices) out[i] *= velocity
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }
}
