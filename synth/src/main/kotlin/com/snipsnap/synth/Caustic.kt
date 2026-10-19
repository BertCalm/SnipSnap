package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * First-listen CAUSTIC: one dry harmonic source illuminates one circular body.
 * Geometry changes the signed, coherent excitation of shared modes. No motion,
 * diffuse rays, nonlinear processing, effects or held-loop implementation is implied.
 *
 * Source envelopes are differences of exponentials (one in model1, two in model2).
 * Each delayed envelope has an exact two-coefficient representation after causal arrival; both its
 * carrier phase and its envelope delay survive compression. Rendering sums these
 * coefficients, rather than tracing paths or approximating their envelope at audio rate.
 */
object Caustic {
    const val MODEL_VERSION = 2
    const val RATE = WavWriter.MPC_SAMPLE_RATE
    private const val INTERNAL_RATE = RATE * 4
    private const val SOURCE_DECAY = 1.10
    private const val SOURCE_ATTACK = .012
    private const val SLOW_RATE = 1.0 / SOURCE_DECAY
    private const val FAST_RATE = SLOW_RATE + 1.0 / SOURCE_ATTACK
    private const val SOURCE_LEVEL = .28
    private const val BODY_FORCE = 80.0
    private const val DIRECT_ROOT = .025
    private const val MODES = 16
    private val harmonic = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 3, 5, 7, 9)

    enum class Character { BODY, REED, ALLOY }

    data class Config(
        val source: Double = .4,
        val focus: Double = .2,
        val midi: Int = 60,
        val velocity: Double = .75,
        val seconds: Double = 5.0,
        val aperture: Double = .05,
        val delayCycles: Double = .65,
        val rayCount: Int = 256,
        val model: Int = 1,
        val character: Character = Character.BODY,
    ) {
        init {
            require(source.isFinite() && source in 0.0..1.0) { "CAUSTIC source must be in 0..1" }
            require(focus.isFinite() && focus in 0.0..1.0) { "CAUSTIC focus must be in 0..1" }
            require(midi in 24..96) { "CAUSTIC MIDI must be in 24..96" }
            require(velocity.isFinite() && velocity in 0.0..1.0) { "CAUSTIC velocity must be in 0..1" }
            require(seconds.isFinite() && seconds in .1..12.0) { "CAUSTIC seconds must be in .1..12" }
            require(aperture.isFinite() && aperture in .015.. .15) { "CAUSTIC aperture must be in .015...15 R" }
            require(delayCycles.isFinite() && delayCycles in .01..2.0) { "CAUSTIC delayCycles must be in .01..2" }
            require(rayCount in 64..4096) { "CAUSTIC rayCount must be in 64..4096" }
            require(model in 1..MODEL_VERSION) { "unsupported CAUSTIC model $model" }
            require(model != 1 || character == Character.BODY) { "CAUSTIC model1 has no selectable character" }
        }
    }

    /** Each diagnostic changes only the named stage of the saved configuration. */
    enum class Mode {
        FULL,
        /** Original twelve-partial pluck, before any geometry, direct-root or modal processing. */
        SOURCE_ONLY,
        /** Exact causal envelope delays and signed routing, with all carrier delays set to zero. */
        PHASE_DISABLED,
        /** Same signed source-band routing and direct root; replace body dynamics with fixed 9x excitation gain. */
        MODES_BYPASSED,
        /** FULL at fixed source=.4/focus=.2, multiplied by current/reference scalar collection. */
        GAIN_ONLY,
    }

    data class Diagnostics(
        val noteHz: Double,
        val rawPeak: Double,
        val rawRms: Double,
        val collection: Double,
        val pathCount: Int,
        val maxDelaySeconds: Double,
        val modeFrequencies: DoubleArray,
        val excitationMethod: String = "exact delayed two-exponential harmonic source; causal coefficient events",
    )

    /** Both sample arrays use identical fixed gain. No per-render normalization or limiter is applied. */
    data class Rendered(
        val raw: FloatArray,
        val samples: FloatArray,
        val densities: DoubleArray,
        /** Maximum of summed body peak and each mode's full phase-space amplitude, before output conditioning. */
        val modalPeak: Double,
        /** Time integral of each mode's energy, in model units. */
        val modalEnergy: DoubleArray,
        val prepareMillis: Double,
        val renderMillis: Double,
        val diagnostics: Diagnostics,
    )

    private fun stopIfUnwanted(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled())
            throw CancellationException("CAUSTIC render no longer wanted")
    }

    private fun noteHz(c: Config): Double = 440.0 * 2.0.pow((c.midi - 69) / 12.0)

    private fun partial(h: Int, hz: Double, velocity: Double): Double {
        val f = h * hz
        val rolloff = when {
            f <= 16_000.0 -> 1.0
            f >= 20_000.0 -> 0.0
            else -> .5 + .5 * cos(PI * (f - 16_000.0) / 4_000.0)
        }
        return SOURCE_LEVEL * velocity * h.toDouble().pow(-(1.30 - .46 * velocity)) * rolloff
    }

    private fun envelope(t: Double): Double = if (t < 0) 0.0 else exp(-t * SLOW_RATE) - exp(-t * FAST_RATE)

    /** Fixed pickup material, independent of SOURCE/FOCUS. Paths change which material bands are driven. */
    private fun bandLoss(pickup: Int, h: Int): Double {
        val p = pickup / 8.0
        return 1.0 / (1.0 + (h * (.045 + .115 * p)).pow(1.65))
    }

    /** Signed body shape: rays can reinforce or cancel a mode across the nine finite pickups. */
    private fun participation(mode: Int, pickup: Int): Double {
        val y = (pickup - 4) / 4.0
        if (mode == 0) return .84 + .16 * cos(PI * y)
        val h = harmonic[mode]
        val shape = cos(PI * (.42 + .21 * (h % 5)) * (y + .22 * sin(h.toDouble())))
        val companion = if (mode < 12) 1.0 else .32
        return companion * (.18 + .82 * shape)
    }

    private fun modeGain(mode: Int): Double = if (mode == 0) .6 else 2.5

    private data class Event(
        val arrival: Int,
        val delay: Double,
        val pickup: Int,
        val weight: Double,
    )

    private class Transfer(private val size: Int, private val hz: Double, private val harmonics: IntArray, private val velocity: Double) {
        val slowRe = DoubleArray(size)
        val slowIm = DoubleArray(size)
        val fastRe = DoubleArray(size)
        val fastIm = DoubleArray(size)
        fun add(event: Event, phaseDisabled: Boolean) {
            val slow = exp(event.delay * SLOW_RATE)
            val fast = exp(event.delay * FAST_RATE)
            for (m in 0 until size) {
                val h = harmonics[m]
                val angle = if (phaseDisabled) 0.0 else -2.0 * PI * h * hz * event.delay
                val w = event.weight * bandLoss(event.pickup, h) * partial(h, hz, velocity) *
                    participation(m, event.pickup)
                val re = w * cos(angle)
                val im = w * sin(angle)
                slowRe[m] += re * slow
                slowIm[m] += im * slow
                fastRe[m] += re * fast
                fastIm[m] += im * fast
            }
        }
        fun at(m: Int, sine: Double, cosine: Double, slow: Double, fast: Double): Double =
            (slowRe[m] * sine + slowIm[m] * cosine) * slow -
                (fastRe[m] * sine + fastIm[m] * cosine) * fast
    }

    fun render(config: Config = Config(), mode: Mode = Mode.FULL, cancelled: () -> Boolean = { false }): Snip =
        Snip(inspect(config, mode, cancelled).samples, channels = 1, sampleRate = RATE)

    fun inspect(config: Config = Config(), mode: Mode = Mode.FULL, cancelled: () -> Boolean = { false }): Rendered {
        if (config.model == 2) return inspectRich(config, mode, cancelled)
        stopIfUnwanted(cancelled)
        val started = System.nanoTime()
        val hz = noteHz(config)
        val geometry = CausticGeometry.prepare(config.source, config.focus, config.rayCount, config.aperture)
        val collection = geometry.densities.sum()
        val used = if (mode == Mode.GAIN_ONLY)
            CausticGeometry.prepare(.4, .2, config.rayCount, config.aperture) else geometry
        val scalar = if (mode == Mode.GAIN_ONLY) collection / used.densities.sum().coerceAtLeast(1e-30) else 1.0
        val events = used.paths.map {
            val delay = it.delayR * config.delayCycles / hz
            Event(ceil(delay * INTERNAL_RATE).toInt(), delay, it.pickup, it.weight)
        }.sortedBy { it.arrival }
        val freqs = DoubleArray(MODES) { m -> hz * harmonic[m] * (if (m < 12) 1.0 else .9974 + .0003 * (m - 12)) }
        val prepareMillis = (System.nanoTime() - started) / 1e6
        stopIfUnwanted(cancelled)
        val renderStarted = System.nanoTime()
        val frames = (config.seconds * RATE).toInt()
        val internal = FloatArray(frames * 4)
        val bank = Modes.Bank(MODES, INTERNAL_RATE)
        for (m in 0 until MODES) bank.tune(m, freqs[m], 2.65 / (1.0 + .075 * (harmonic[m] - 1)))
        val transfer = Transfer(MODES, hz, harmonic, config.velocity)
        val sourceAmplitudes = DoubleArray(12) { partial(it + 1, hz, config.velocity) }
        val oscillatorSin = DoubleArray(12)
        val oscillatorCos = DoubleArray(12) { 1.0 }
        val rotationSin = DoubleArray(12) { sin(2 * PI * hz * (it + 1) / INTERNAL_RATE) }
        val rotationCos = DoubleArray(12) { cos(2 * PI * hz * (it + 1) / INTERNAL_RATE) }
        val forces = DoubleArray(MODES)
        val energy = DoubleArray(MODES)
        val slowStep = exp(-SLOW_RATE / INTERNAL_RATE)
        val fastStep = exp(-FAST_RATE / INTERNAL_RATE)
        var slow = 1.0
        var fast = 1.0
        var nextEvent = 0
        var modalPeak = 0.0
        for (n in internal.indices) {
            if ((n and 8191) == 0) stopIfUnwanted(cancelled)
            while (nextEvent < events.size && events[nextEvent].arrival <= n) {
                transfer.add(events[nextEvent++], mode == Mode.PHASE_DISABLED)
            }
            val sourceEnvelope = slow - fast
            val out = when (mode) {
                Mode.SOURCE_ONLY -> {
                    var s = 0.0
                    for (h in 1..12) s += sourceAmplitudes[h - 1] * oscillatorSin[h - 1]
                    s * sourceEnvelope
                }
                Mode.MODES_BYPASSED -> {
                    var s = 0.0
                    for (m in 0 until MODES) {
                        val h = harmonic[m] - 1
                        s += modeGain(m) * transfer.at(m, oscillatorSin[h], oscillatorCos[h], slow, fast)
                    }
                    // Geometry's collection budget remains audible; only a fixed instrument gain is applied.
                    s * 9.0 + DIRECT_ROOT * config.velocity * sourceEnvelope * oscillatorSin[0]
                }
                else -> {
                    for (m in 0 until MODES) {
                        val h = harmonic[m] - 1
                        forces[m] = BODY_FORCE * transfer.at(m, oscillatorSin[h], oscillatorCos[h], slow, fast)
                    }
                    bank.step()
                    bank.drive(forces, 1.0)
                    var body = 0.0
                    for (m in 0 until MODES) {
                        val v = bank.velocity(m)
                        body += v * modeGain(m)
                        // Re²+Im² = omega²*q²+v²; integrate exact uncoupled energy at output-rate stride.
                        if ((n and 3) == 0) {
                            val q = bank.displacement(m) * 2 * PI * freqs[m]
                            val amplitude = sqrt(v * v + q * q)
                            check(amplitude.isFinite() && amplitude < 8) {
                                "CAUSTIC unstable mode $m at $n: $amplitude"
                            }
                            modalPeak = maxOf(modalPeak, amplitude * scalar)
                            energy[m] += .5 * amplitude * amplitude / RATE
                        }
                    }
                    modalPeak = maxOf(modalPeak, abs(body * scalar))
                    body + DIRECT_ROOT * config.velocity * sourceEnvelope * oscillatorSin[0]
                }
            } * scalar
            check(out.isFinite() && abs(out) < 8) { "CAUSTIC unstable raw state at $n: $out" }
            internal[n] = out.toFloat()
            slow *= slowStep
            fast *= fastStep
            for (h in 0 until 12) {
                val s = oscillatorSin[h]
                val c = oscillatorCos[h]
                oscillatorSin[h] = s * rotationCos[h] + c * rotationSin[h]
                oscillatorCos[h] = c * rotationCos[h] - s * rotationSin[h]
            }
        }
        stopIfUnwanted(cancelled)
        Tide.bandLimit(internal, INTERNAL_RATE)
        stopIfUnwanted(cancelled)
        val raw = Dsp.decimate(internal, RATE)
        val dc = Dsp.OnePole(RATE)
        for (n in raw.indices) {
            if ((n and 16383) == 0) stopIfUnwanted(cancelled)
            raw[n] -= dc.lp(raw[n], 8f)
        }
        Dsp.fadeTail(raw, ms = 8f, rate = RATE)
        stopIfUnwanted(cancelled)
        var peak = 0.0
        var sum = 0.0
        for (x in raw) { check(x.isFinite()); peak = maxOf(peak, abs(x.toDouble())); sum += x.toDouble() * x }
        for (m in energy.indices) energy[m] *= scalar * scalar
        val diagnostics = Diagnostics(hz, peak, sqrt(sum / raw.size.coerceAtLeast(1)), collection,
            geometry.paths.size, geometry.paths.maxOfOrNull { it.delayR * config.delayCycles / hz } ?: 0.0, freqs)
        return Rendered(raw, raw.copyOf(), geometry.densities.copyOf(), modalPeak, energy,
            prepareMillis, (System.nanoTime() - renderStarted) / 1e6, diagnostics)
    }

    /** Model2 changes the resonant instrument, while preserving the model1 reference above. */
    private data class RichProfile(
        val character: Character,
        val decay: Double,
        val attack: Double,
        val edgeDecay: Double,
        val edgeAttack: Double,
        val force: Double,
        val edgeForce: Double,
        val output: Double,
    ) {
        val slow = 1.0 / decay
        val fast = slow + 1.0 / attack
        val edgeSlow = 1.0 / edgeDecay
        val edgeFast = edgeSlow + 1.0 / edgeAttack
        fun frequency(m: Int): Double {
            val h = m % 12 + 1
            val companion = m >= 12
            return when (character) {
                Character.BODY -> h * if (!companion) 1.0 else 1.0 + .0025 * sin(h * 1.71)
                Character.REED -> h * if (!companion) 1.0 else 1.0 + .0043 * cos(h * 1.37)
                Character.ALLOY -> if (h == 1) (if (companion) 1.0018 else 1.0) else
                    h * (1.0 + (if (companion) .005 + .0022 * sin(h * 1.7) else .0015 * (h - 1.0).pow(1.20)))
            }
        }
        fun t60(m: Int): Double {
            val h = m % 12 + 1
            return when (character) {
                Character.BODY -> (if (m < 12) 4.8 else 4.2) / (1.0 + .095 * (h - 1))
                Character.REED -> (if (h % 2 == 1) 3.7 else 1.3) / (1.0 + .030 * (h - 1))
                Character.ALLOY -> (if (h == 1) 2.7 else 5.0) / (1.0 + .025 * (h - 1))
            }
        }
        fun gain(m: Int): Double {
            val h = m % 12 + 1
            val base = when (character) {
                Character.BODY -> when (h) { 1 -> .68; 2 -> 2.8; 3 -> 3.2; else -> 1.8 / (1 + .10 * (h - 3)) }
                Character.REED -> if (h == 1) .90 else if (h % 2 == 1) 3.7 else .45
                Character.ALLOY -> if (h == 1) .22 else if (h == 2) 2.2 else 3.2
            }
            return base * if (m < 12) 1.0 else when (character) {
                Character.BODY -> if (h == 1) .12 else .33
                Character.REED -> if (h == 1) .18 else .48
                Character.ALLOY -> if (h == 1) .10 else .45
            }
        }
        fun participation(m: Int, pickup: Int): Double {
            val h = m % 12 + 1
            val y = (pickup - 4) / 4.0
            if (h == 1) return .86 + .14 * cos(PI * y)
            val spatial = when (character) {
                Character.BODY -> .24 + .76 * cos(PI * (.30 + .15 * (h % 5)) * (y + .12 * sin(h.toDouble())))
                Character.REED -> .18 + .82 * cos(PI * (.42 + .23 * (h % 4)) * (y + .24 * sin(h * .7)))
                Character.ALLOY -> .12 + .88 * cos(PI * (.55 + .19 * (h % 5)) * (y + .18 * cos(h.toDouble())))
            }
            return if (m < 12) spatial else spatial * (.86 + .14 * sin(PI * y * (h % 3 + 1)))
        }
        fun band(pickup: Int, h: Int): Double {
            val p = pickup / 8.0
            val loss = when (character) {
                Character.BODY -> .045 + .12 * p
                Character.REED -> .025 + .075 * p
                Character.ALLOY -> .018 + .055 * p
            }
            return 1.0 / (1.0 + (h * loss).pow(1.5))
        }
        fun source(h: Int, hz: Double, velocity: Double, edge: Boolean): Double {
            val rolloff = if (h * hz >= 20_000) 0.0 else if (h * hz < 16_000) 1.0 else
                .5 + .5 * cos(PI * (h * hz - 16_000) / 4_000)
            if (edge) {
                val effort = velocity.pow(1.25)
                return when (character) {
                    Character.BODY -> .028 * effort * h.toDouble().pow(-1.10)
                    Character.REED -> .075 * effort * h.toDouble().pow(-.80) * (if (h % 2 == 1) 1.0 else .25)
                    Character.ALLOY -> .20 * effort * h.toDouble().pow(-.60) * (if (h == 1) .10 else 2.4)
                } * rolloff
            }
            val a = when (character) {
                Character.BODY -> .25 * velocity * h.toDouble().pow(-(1.40 - .30 * velocity)) *
                    (if (h == 2) 1.5 else if (h == 3) 1.3 else 1.0)
                Character.REED -> .25 * velocity * h.toDouble().pow(-(1.05 - .30 * velocity)) *
                    (if (h % 2 == 1) 1.0 else .16)
                Character.ALLOY -> .24 * velocity * h.toDouble().pow(-(1.30 - .35 * velocity))
            }
            return a * rolloff
        }
    }

    private fun profile(c: Config): RichProfile = when (c.character) {
        Character.BODY -> RichProfile(c.character, 1.55, .045, .11, .008, 65.0, 45.0, .72)
        Character.REED -> RichProfile(c.character, 1.25, .016, .080, .004, 78.0, 125.0, .78)
        Character.ALLOY -> RichProfile(c.character, .64, .006, .006, .0005, 70.0, 1_500.0, .68)
    }

    /** Two independently delayed source envelopes, each represented exactly by two exponentials. */
    private class RichTransfer(val hz: Double, val velocity: Double, val profile: RichProfile) {
        val re = Array(4) { DoubleArray(24) }
        val im = Array(4) { DoubleArray(24) }
        fun add(e: Event, phaseDisabled: Boolean) {
            val rates = doubleArrayOf(profile.slow, profile.fast, profile.edgeSlow, profile.edgeFast)
            for (m in 0 until 24) {
                val h = m % 12 + 1
                val angle = if (phaseDisabled) 0.0 else -2 * PI * h * hz * e.delay
                val spatial = e.weight * profile.band(e.pickup, h) * profile.participation(m, e.pickup)
                for (j in 0..3) {
                    val w = spatial * profile.source(h, hz, velocity, j >= 2) * exp(e.delay * rates[j])
                    re[j][m] += w * cos(angle)
                    im[j][m] += w * sin(angle)
                }
            }
        }
        fun at(m: Int, s: Double, c: Double, env: DoubleArray, edge: Boolean): Double {
            val j = if (edge) 2 else 0
            return (re[j][m] * s + im[j][m] * c) * env[j] -
                (re[j + 1][m] * s + im[j + 1][m] * c) * env[j + 1]
        }
    }

    private fun inspectRich(config: Config, mode: Mode, cancelled: () -> Boolean): Rendered {
        stopIfUnwanted(cancelled)
        val started = System.nanoTime()
        val hz = noteHz(config)
        val p = profile(config)
        val geometry = CausticGeometry.prepare(config.source, config.focus, config.rayCount, config.aperture)
        val used = if (mode == Mode.GAIN_ONLY) CausticGeometry.prepare(.4, .2, config.rayCount, config.aperture) else geometry
        val scalar = if (mode == Mode.GAIN_ONLY) geometry.densities.sum() / used.densities.sum().coerceAtLeast(1e-30) else 1.0
        val events = used.paths.map {
            val delay = it.delayR * config.delayCycles / hz
            Event(ceil(delay * INTERNAL_RATE).toInt(), delay, it.pickup, it.weight)
        }.sortedBy { it.arrival }
        val frequencies = DoubleArray(24) { hz * p.frequency(it) }
        val bank = Modes.Bank(24, INTERNAL_RATE)
        for (m in 0 until 24) bank.tune(m, frequencies[m], p.t60(m))
        val transfer = RichTransfer(hz, config.velocity, p)
        val gains = DoubleArray(24) { p.gain(it) }
        val amplitudes = Array(2) { edge -> DoubleArray(12) { p.source(it + 1, hz, config.velocity, edge == 1) } }
        val rates = doubleArrayOf(p.slow, p.fast, p.edgeSlow, p.edgeFast)
        val envelope = DoubleArray(4) { 1.0 }
        val steps = DoubleArray(4) { exp(-rates[it] / INTERNAL_RATE) }
        val ss = DoubleArray(12)
        val cc = DoubleArray(12) { 1.0 }
        val rs = DoubleArray(12) { sin(2 * PI * hz * (it + 1) / INTERNAL_RATE) }
        val rc = DoubleArray(12) { cos(2 * PI * hz * (it + 1) / INTERNAL_RATE) }
        val force = DoubleArray(24)
        val energy = DoubleArray(24)
        val prepareMillis = (System.nanoTime() - started) / 1e6
        val renderStarted = System.nanoTime()
        val internal = FloatArray((config.seconds * RATE).toInt() * 4)
        var next = 0
        var modalPeak = 0.0
        for (n in internal.indices) {
            if ((n and 8191) == 0) stopIfUnwanted(cancelled)
            while (next < events.size && events[next].arrival <= n) transfer.add(events[next++], mode == Mode.PHASE_DISABLED)
            val direct = .035 * config.velocity * ss[0] * (envelope[0] - envelope[1])
            val value = when (mode) {
                Mode.SOURCE_ONLY -> {
                    var sum = 0.0
                    for (h in 0..11) sum += ss[h] * (amplitudes[0][h] * (envelope[0] - envelope[1]) +
                        amplitudes[1][h] * (envelope[2] - envelope[3]))
                    sum
                }
                Mode.MODES_BYPASSED -> {
                    var sum = 0.0
                    for (m in 0 until 24) {
                        val h = m % 12
                        sum += gains[m] * (transfer.at(m, ss[h], cc[h], envelope, false) +
                            p.edgeForce / p.force * transfer.at(m, ss[h], cc[h], envelope, true))
                    }
                    sum * 9.0 + direct
                }
                else -> {
                    for (m in 0 until 24) {
                        val h = m % 12
                        force[m] = p.force * transfer.at(m, ss[h], cc[h], envelope, false) +
                            p.edgeForce * transfer.at(m, ss[h], cc[h], envelope, true)
                    }
                    bank.step()
                    bank.drive(force, 1.0)
                    var sum = 0.0
                    for (m in 0 until 24) {
                        val v = bank.velocity(m)
                        sum += v * gains[m]
                        if ((n and 3) == 0) {
                            val q = bank.displacement(m) * 2 * PI * frequencies[m]
                            val a = sqrt(v * v + q * q)
                            check(a.isFinite() && a < 8) { "CAUSTIC model2 unstable mode $m at $n: $a" }
                            modalPeak = maxOf(modalPeak, a * p.output * scalar)
                            energy[m] += .5 * a * a / RATE
                        }
                    }
                    modalPeak = maxOf(modalPeak, abs(sum * p.output * scalar))
                    sum + direct
                }
            } * p.output * scalar
            check(value.isFinite() && abs(value) < 8) { "CAUSTIC model2 unstable output at $n: $value" }
            internal[n] = value.toFloat()
            for (j in 0..3) envelope[j] *= steps[j]
            for (h in 0..11) {
                val s = ss[h]
                ss[h] = s * rc[h] + cc[h] * rs[h]
                cc[h] = cc[h] * rc[h] - s * rs[h]
            }
        }
        stopIfUnwanted(cancelled)
        Tide.bandLimit(internal, INTERNAL_RATE)
        stopIfUnwanted(cancelled)
        val raw = Dsp.decimate(internal, RATE)
        val dc = Dsp.OnePole(RATE)
        for (n in raw.indices) {
            if ((n and 16383) == 0) stopIfUnwanted(cancelled)
            raw[n] -= dc.lp(raw[n], 8f)
        }
        Dsp.fadeTail(raw, ms = 8f, rate = RATE)
        stopIfUnwanted(cancelled)
        var peak = 0.0
        var sum = 0.0
        for (x in raw) { check(x.isFinite()); peak = maxOf(peak, abs(x.toDouble())); sum += x.toDouble() * x }
        for (m in energy.indices) energy[m] *= scalar * scalar
        val d = Diagnostics(hz, peak, sqrt(sum / raw.size.coerceAtLeast(1)), geometry.densities.sum(), geometry.paths.size,
            geometry.paths.maxOfOrNull { it.delayR * config.delayCycles / hz } ?: 0.0, frequencies,
            "model2 ${config.character.name}; exact delayed tonal and transient envelopes; 24 sympathetic modes")
        return Rendered(raw, raw.copyOf(), geometry.densities.copyOf(), modalPeak, energy, prepareMillis,
            (System.nanoTime() - renderStarted) / 1e6, d)
    }

    private fun richTransferProbe(config: Config, times: DoubleArray, mode: Mode): TransferProbe {
        val hz = noteHz(config)
        val p = profile(config)
        val geometry = CausticGeometry.prepare(config.source, config.focus, config.rayCount, config.aperture)
        val events = geometry.paths.map { Event(0, it.delayR * config.delayCycles / hz, it.pickup, it.weight) }
        val exact = DoubleArray(times.size * 24)
        val compressed = DoubleArray(exact.size)
        val rates = doubleArrayOf(p.slow, p.fast, p.edgeSlow, p.edgeFast)
        for (i in times.indices) {
            val t = times[i]
            val transfer = RichTransfer(hz, config.velocity, p)
            for (e in events) {
                if (t < e.delay) continue
                transfer.add(e, mode == Mode.PHASE_DISABLED)
                val dt = t - e.delay
                for (m in 0 until 24) {
                    val h = m % 12 + 1
                    val carrier = if (mode == Mode.PHASE_DISABLED) t else dt
                    val tone = p.source(h, hz, config.velocity, false) * (exp(-dt * p.slow) - exp(-dt * p.fast))
                    val edge = p.source(h, hz, config.velocity, true) * (exp(-dt * p.edgeSlow) - exp(-dt * p.edgeFast))
                    exact[i * 24 + m] += e.weight * p.band(e.pickup, h) * p.participation(m, e.pickup) *
                        (p.force * tone + p.edgeForce * edge) * sin(2 * PI * h * hz * carrier)
                }
            }
            val env = DoubleArray(4) { exp(-t * rates[it]) }
            for (m in 0 until 24) {
                val a = 2 * PI * (m % 12 + 1) * hz * t
                compressed[i * 24 + m] = p.force * transfer.at(m, sin(a), cos(a), env, false) +
                    p.edgeForce * transfer.at(m, sin(a), cos(a), env, true)
            }
        }
        return TransferProbe(exact, compressed)
    }

    /** Independent direct delayed-path reference against the compressed transfer, before body dynamics. */
    internal data class TransferProbe(val exact: DoubleArray, val compressed: DoubleArray)
    internal fun transferProbe(config: Config, times: DoubleArray, mode: Mode = Mode.FULL): TransferProbe {
        require(mode == Mode.FULL || mode == Mode.PHASE_DISABLED)
        if (config.model == 2) return richTransferProbe(config, times, mode)
        val hz = noteHz(config)
        val geometry = CausticGeometry.prepare(config.source, config.focus, config.rayCount, config.aperture)
        val events = geometry.paths.map {
            val delay = it.delayR * config.delayCycles / hz
            Event(0, delay, it.pickup, it.weight)
        }.sortedBy { it.delay }
        val exact = DoubleArray(times.size * MODES)
        val compressed = DoubleArray(exact.size)
        for (i in times.indices) {
            val t = times[i]
            val transfer = Transfer(MODES, hz, harmonic, config.velocity)
            for (e in events) {
                if (t < e.delay) continue
                transfer.add(e, mode == Mode.PHASE_DISABLED)
                for (m in 0 until MODES) {
                    val h = harmonic[m]
                    val carrierTime = if (mode == Mode.PHASE_DISABLED) t else t - e.delay
                    exact[i * MODES + m] += e.weight * bandLoss(e.pickup, h) * partial(h, hz, config.velocity) *
                        participation(m, e.pickup) * envelope(t - e.delay) * sin(2 * PI * h * hz * carrierTime)
                }
            }
            for (m in 0 until MODES) {
                val a = 2 * PI * harmonic[m] * hz * t
                compressed[i * MODES + m] = transfer.at(m, sin(a), cos(a), exp(-t * SLOW_RATE), exp(-t * FAST_RATE))
            }
        }
        return TransferProbe(exact, compressed)
    }
}
