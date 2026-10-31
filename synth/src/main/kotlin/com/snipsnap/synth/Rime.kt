package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.*

enum class RimeVoice { LOOP, GLINT, VESSEL }

/** Contour instrument. Saved models 1–4 retain their finite rendering laws. */
object Rime {
    const val MODEL_VERSION = 5
    const val HOLD_THRESHOLD = .85f
    const val MIN_MIDI = 24
    const val MAX_MIDI = 96
    val TIMBRAL = listOf("DETAIL", "EDGE", "ANGLE", "UNFOLD", "BODY")
    fun rootMidi(voice: RimeVoice): Int = when (voice) { RimeVoice.LOOP -> 36; RimeVoice.GLINT -> 60; RimeVoice.VESSEL -> 48 }
    fun gateSeconds(voice: RimeVoice): Float = when (voice) { RimeVoice.LOOP -> .8f; RimeVoice.GLINT -> 1.2f; RimeVoice.VESSEL -> 2.6f }
    fun defaults(voice: RimeVoice, model: Int = MODEL_VERSION): Map<String, Float> {
        require(model in 1..MODEL_VERSION) { "unsupported RIME model $model" }
        if (model == 1) return RimeV1.defaults(voice)
        val values = when (voice) {
            RimeVoice.LOOP -> listOf(.46f, .22f, .20f, .10f, .25f)
            RimeVoice.GLINT -> listOf(.82f, .72f, if (model >= 3) .15f else .65f, .35f, .55f)
            RimeVoice.VESSEL -> listOf(.85f, .48f, .25f, .85f, .85f)
        }
        return (TIMBRAL.zip(values) + ("HOLD" to 0f)).toMap()
    }
    fun macrosFor(voice: RimeVoice, model: Int = MODEL_VERSION): List<MacroSpec> = defaults(voice, model).map { MacroSpec(it.key, it.value) }
    internal fun settled(voice: RimeVoice, macros: Map<String, Float>, model: Int = MODEL_VERSION): Map<String, Float> {
        val values = defaults(voice, model).toMutableMap()
        for ((name, value) in macros) {
            require(name in values) { "unknown RIME macro $name" }
            require(value.isFinite() && value in 0f..1f) { "RIME $name must be finite in 0..1" }
            values[name] = value
        }
        require(model >= 5 || values.getValue("HOLD") == 0f) { "RIME models 1–4 render finite notes; HOLD is not implemented" }
        return values
    }
    fun isLoop(macros: Map<String, Float>): Boolean = (macros["HOLD"] ?: 0f) >= HOLD_THRESHOLD
    internal fun extendedGate(gate: Float, hold: Float): Float = gate + (4f - gate) * (hold / HOLD_THRESHOLD).coerceIn(0f, 1f)
    fun drumClassFor(voice: RimeVoice, macros: Map<String, Float> = emptyMap(), gateSeconds: Float = gateSeconds(voice), model: Int = MODEL_VERSION): DrumClass {
        val m = settled(voice, macros, model)
        val body = m.getValue("BODY").toDouble()
        if (isLoop(m)) return DrumClass.LOOP
        val seconds = extendedGate(gateSeconds, m.getValue("HOLD")) + .06 + .12 * body + max(.25, 1.25 * RimeBody.decaySeconds(voice, body))
        return if (seconds > 1.5) DrumClass.LOOP else DrumClass.TONAL
    }

    fun scramble(voice: RimeVoice, random: kotlin.random.Random): Map<String, Float> =
        defaults(voice).mapValues { (name, value) -> if (name == "HOLD") 0f else (value + (random.nextFloat() - .5f) * .6f).coerceIn(0f, 1f) }

    /** Explicit diagnostic recipe switches, never hidden patch macro overrides. */
    data class Options(val contact: Boolean = true, val reference: Boolean = true, val body: Boolean = true,
        val oversample: Int = Dsp.OVERSAMPLE, val friction: Boolean = true)
    data class Trace(val seconds: Double, val bandGains: List<Double>, val bodyEnergy: Double)
    data class Probe(val raw: FloatArray, val samples: FloatArray, val internalPeak: Double,
        val maxBodyEnergy: Double, val cutoff: Double, val safeMaximum: Int, val tailDb: Double, val trace: List<Trace>)

    internal fun bandGain(t: Double, band: Int, bands: Int, unfold: Double, gate: Double): Double {
        if (band == 0 || unfold == 0.0) return 1.0
        val scale = band.toDouble() / max(1, bands - 1)
        val reveal = min(1.5, gate * .72) * unfold.pow(1.6)
        val delay = reveal * (.12 + .70 * scale)
        val rise = RimeContour.smooth((t - delay) / (.008 + reveal * .25))
        val recession = gate * (.94 - .28 * scale * unfold)
        val fall = 1 - RimeContour.smooth((t - recession) / max(.025, gate - recession))
        return rise * fall
    }

    fun render(voice: RimeVoice, macros: Map<String, Float> = emptyMap(), midi: Int = rootMidi(voice),
        velocity: Float = .8f, gateSeconds: Float = gateSeconds(voice), seed: Int = 1729, model: Int = MODEL_VERSION): Snip =
        if (model >= 5 && isLoop(macros)) Snip(RimeHeld.render(RimePatch("Held Rime", voice, macros, midi, velocity, gateSeconds, seed, model)).cycle, 1, Dsp.RATE)
        else Snip(inspect(voice, macros, midi, velocity, gateSeconds, seed, model = model).samples, 1, Dsp.RATE)

    internal fun inspect(voice: RimeVoice, macros: Map<String, Float> = emptyMap(), midi: Int = rootMidi(voice),
        velocity: Float = .8f, gateSeconds: Float = gateSeconds(voice), seed: Int = 1729,
        options: Options = Options(), model: Int = MODEL_VERSION): Probe {
        if (model >= 5) {
            val m = settled(voice, macros, model)
            require(gateSeconds.isFinite() && gateSeconds in .15f..4f) { "RIME gateSeconds must be .15..4" }
            require(!isLoop(m)) { "use RimeHeld for periodic HOLD diagnostics" }
            return inspect(voice, m + ("HOLD" to 0f), midi, velocity, extendedGate(gateSeconds, m.getValue("HOLD")), seed, options, 4)
        }
        if (model == 1) {
            val old = RimeV1.inspect(voice, macros, midi, velocity, gateSeconds, seed,
                RimeV1.Options(options.contact, options.reference, options.body, options.oversample, options.friction))
            return Probe(old.raw, old.samples, old.internalPeak, old.maxBodyEnergy, old.cutoff, old.safeMaximum, old.tailDb,
                old.trace.map { Trace(it.seconds, it.bandGains, it.bodyEnergy) })
        }
        val m = settled(voice, macros, model)
        require(midi in MIN_MIDI..MAX_MIDI) { "RIME MIDI must be $MIN_MIDI..$MAX_MIDI" }
        require(velocity.isFinite() && velocity in 0f..1f) { "RIME velocity must be finite in 0..1" }
        require(gateSeconds.isFinite() && gateSeconds in .15f..4f) { "RIME gateSeconds must be .15..4" }
        require(options.oversample in listOf(4, 8))
        val rate = Dsp.RATE * options.oversample
        val hz = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val edge = m.getValue("EDGE").toDouble(); val body = m.getValue("BODY").toDouble()
        val unfold = m.getValue("UNFOLD").toDouble()
        val angle = (m.getValue("ANGLE").toDouble() % 1) * 2 * PI
        val ca = cos(angle); val sa = sin(angle)
        val cr = cos(PI / 6); val sr = sin(PI / 6)
        val source = RimeContour.prepare(hz, m.getValue("DETAIL").toDouble(), edge,
            apertureAngle = if (model >= 3) angle else null, apertureLaw = max(3, model))
        val resonator = RimeBody(voice, hz, body, rate)
        val release = .06 + .12 * body
        val duration = min(8.0, gateSeconds + release + max(.25, resonator.t60 * 1.25))
        val frames = (duration * Dsp.RATE).roundToInt()
        val raw = FloatArray(frames * options.oversample)
        // Model 4 only retunes knob travel. Keep the established friction stream
        // so its accepted anchors and all three starter sounds stay sample-exact.
        val noise = Dsp.Noise(Dsp.seedFor("RIME", min(model, 3), seed, midi, "friction"))
        var rough = 0.0
        val noiseAlpha = 1 - exp(-2 * PI * 12000 / rate)
        val contactGain = (.035 + 1.05 * edge.pow(2)) * (.35 + .65 * velocity)
        var phase = 0.0; var peak = 0.0; var maxEnergy = 0.0
        val trace = mutableListOf<Trace>()
        val gains = DoubleArray(source.bands.size)
        val increments = DoubleArray(gains.size)
        val block = (rate / 1000).coerceAtLeast(1)
        val attack = when (voice) { RimeVoice.LOOP -> .006; RimeVoice.GLINT -> .003; RimeVoice.VESSEL -> .025 } +
            (if (source.bands.size > 1) unfold * .008 else 0.0)
        val decay = when (voice) { RimeVoice.LOOP -> 1.0; RimeVoice.GLINT -> 2.1; RimeVoice.VESSEL -> .3 }
        val step = hz / rate
        for (i in raw.indices) {
            if (i % 4096 == 0 && Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("RIME render cancelled")
            val t = i.toDouble() / rate
            if (i % block == 0) for (b in gains.indices) {
                gains[b] = bandGain(t, b, gains.size, unfold, gateSeconds.toDouble())
                increments[b] = (bandGain((i + block).toDouble() / rate, b, gains.size, unfold, gateSeconds.toDouble()) - gains[b]) / block
            }
            var x = 0.0; var y = 0.0; var dx = 0.0; var dy = 0.0
            var rx = 0.0; var ry = 0.0; var rdx = 0.0; var rdy = 0.0
            // A versioned second-port path length, expressed in root periods.
            // ANGLE rotates the projection and, in model 3, its finite contact aperture.
            val referencePhase = phase - (.035 + .075 * body)
            for (b in source.bands.indices) {
                val band = source.bands[b]; val gain = gains[b]
                x += gain * band.read(band.x, phase); y += gain * band.read(band.y, phase)
                if (options.contact) { dx += gain * band.read(band.dx, phase); dy += gain * band.read(band.dy, phase) }
                if (options.reference) {
                    rx += gain * band.read(band.x, referencePhase); ry += gain * band.read(band.y, referencePhase)
                    if (options.contact) { rdx += gain * band.read(band.dx, referencePhase); rdy += gain * band.read(band.dy, referencePhase) }
                }
                gains[b] += increments[b]
            }
            val p = ca * x + sa * y; val reference = cr * rx + sr * ry
            val slopeA = ca * dx + sa * dy; val slopeB = cr * rdx + sr * rdy
            // Bounded smooth pressure, normalized from the admitted derivative energy.
            val pressureA = slopeA / sqrt(1 + .3 * slopeA * slopeA)
            val pressureB = slopeB / sqrt(1 + .3 * slopeB * slopeB)
            rough += noiseAlpha * (noise.next() - rough)
            val friction = if (options.friction) .018 * rough else 0.0
            val contactA = contactGain * (pressureA + friction * abs(pressureA))
            val contactB = contactGain * (pressureB + friction * abs(pressureB))
            val envelope = RimeContour.smooth(t / attack) * exp(-decay * min(t, gateSeconds.toDouble()) / gateSeconds) *
                (1 - RimeContour.smooth((t - gateSeconds) / release))
            val a = velocity * envelope * (p + .50 * contactA)
            val b = if (options.reference) .36 * velocity * envelope * (reference + .50 * contactB) else 0.0
            // The body needs contour-derived upper-mode force. Feeding it mostly the
            // same sine as the direct path made BODY a level change erased by finishing.
            val bodyContact = if (options.contact) .45 + .90 * edge else 0.0
            val driveA = velocity * envelope * (.18 * p + bodyContact * pressureA)
            val driveB = if (options.reference) .36 * velocity * envelope * (.18 * reference + bodyContact * pressureB) else 0.0
            val resonated = if (options.body) resonator.next(driveA, driveB) else 0.0
            val value = (1 - .45 * body) * a + .18 * b + (3.8 * body) * resonated
            check(value.isFinite() && abs(value) < 16) { "RIME unbounded internal output at $i: $value" }
            raw[i] = value.toFloat(); peak = max(peak, abs(value))
            if (i % (rate / 50) == 0) {
                val energy = resonator.energy()
                check(energy.isFinite() && energy < 1e8) { "RIME modal energy unbounded: $energy" }
                maxEnergy = max(maxEnergy, energy)
                trace += Trace(t, gains.toList(), energy)
            }
            phase += step; if (phase >= 1) phase -= 1
        }
        // Compare higher-rate DSP through the identical house conditioning path.
        // Running its IIR cutoff at 8x changed filter phase near 18 kHz and
        // incorrectly counted that phase difference as nonlinear alias error.
        val houseRate = Dsp.RATE * Dsp.OVERSAMPLE
        val conditioned = if (rate == houseRate) raw else Resampler.resample(Snip(raw, 1, rate), houseRate).samples
        Tide.bandLimit(conditioned, houseRate)
        val down = Dsp.decimate(conditioned, Dsp.RATE)
        val mean = down.sumOf { it.toDouble() } / down.size
        val highpass = Dsp.OnePole(Dsp.RATE)
        for (i in down.indices) { val value = (down[i] - mean).toFloat(); down[i] = value - highpass.lp(value, 8f) }
        val window = min(down.size, Dsp.RATE / 20)
        val headRms = sqrt(down.sumOf { it.toDouble().pow(2) } / down.size).coerceAtLeast(1e-20)
        val endRms = sqrt((down.size - window until down.size).sumOf { down[it].toDouble().pow(2) } / window)
        val tailDb = 20 * log10(endRms.coerceAtLeast(1e-20) / headRms)
        Dsp.fadeTail(down)
        val out = down.copyOf()
        val measured = Loudness.of(out, 1, Dsp.RATE)
        // The shared finish retains velocity and caps cancellation compensation at 12x.
        val target = min(Dsp.MELODIC_LOUDNESS_TARGET * velocity.toDouble().pow(.65), measured * 12.0).toFloat()
        if (velocity > 0 && measured > 1e-9) Dsp.levelTo(out, Dsp.RATE, target, ceiling = .94f)
        return Probe(down, out, peak, maxEnergy, source.cutoff, source.safeMaximum, tailDb, trace)
    }
}

/** Feedforward two-port bank. Each mode decays independently; no source feedback. */
internal class RimeBody(voice: RimeVoice, hz: Double, body: Double, rate: Int) {
    private val bank = Modes.Bank(8, rate)
    private val portA = DoubleArray(8)
    private val portB = DoubleArray(8)
    private val pickup = DoubleArray(8)
    private var previousA = 0.0
    private var previousB = 0.0
    val t60: Double = decaySeconds(voice, body)
    companion object {
        fun decaySeconds(voice: RimeVoice, body: Double): Double = (.09 + 1.20 * body.pow(1.5)) *
            when (voice) { RimeVoice.LOOP -> .65; RimeVoice.GLINT -> .8; RimeVoice.VESSEL -> 1.35 }
    }
    init {
        val ratios = doubleArrayOf(1.0, 2.0, 3.0, 5.0, 7.0, 11.0, 13.0, 17.0)
        val offsets = doubleArrayOf(0.0, -2.0, 3.5, -4.0, 5.0, -6.0, 7.0, -8.0)
        val ringingOffsets = doubleArrayOf(0.0, .20, -.30, .45, -.55, .65, -.75, .85)
        val a = doubleArrayOf(.55, .45, .35, 1.10, .90, .60, .45, .30)
        val b = doubleArrayOf(.25, -.65, .30, -.90, 1.0, -.55, .65, -.40)
        val readout = doubleArrayOf(.30, .22, .20, .65, .55, .36, .28, .20)
        for (i in ratios.indices) {
            // Absolute small detunings keep the upper body modes excitable across
            // registers. Scaling every ratio moved them off all contour harmonics.
            val frequency = (hz * ratios[i] + offsets[i] * (1 - body) + ringingOffsets[i] * body).coerceAtLeast(10.0)
            val modeT60 = (t60 / (1 + .28 * i)).coerceAtLeast(.035)
            bank.tune(i, frequency.coerceAtMost(17000.0), modeT60)
            val safe = RimeContour.smooth((17500 - frequency) / 2000)
            // Modal force includes damping so resonance gain remains bounded at long decay.
            val force = 2 * ln(1000.0) / modeT60
            portA[i] = a[i] * force * safe
            portB[i] = b[i] * force * safe
            pickup[i] = readout[i]
        }
    }
    fun next(a: Double, b: Double): Double {
        // Trapezoidal forcing at actual sample times avoids a rate-dependent
        // half-sample phase advance between the direct and modal paths.
        bank.drive(portA, .5 * previousA); bank.drive(portB, .5 * previousB)
        bank.step()
        bank.drive(portA, .5 * a); bank.drive(portB, .5 * b)
        previousA = a; previousB = b
        return bank.velocityAlong(pickup)
    }
    fun energy(): Double = bank.energy()
}
