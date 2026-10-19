package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Model 3 keeps the first audition's warm ocean instrument. Its three root-bearing
 * bodies, initiating contact, continuous pressure, random scene, callers, coupling,
 * decay and harbor are the model-1 equations. Only the bodies' output participation
 * receives a small, continuously interpolated coast-specific shade.
 *
 * This is a conservative voicing of the original instrument, not a new exciter or
 * material model. In particular, it adds no noise, oscillator, contact or transient.
 */
internal object OspreyV3 {
    const val MODEL_VERSION = 3
    const val DEFAULT_MIDI = 60
    const val MIDI_MIN = 36
    const val MIDI_MAX = 84
    const val MAX_SECONDS = 12f
    private const val RATE = Dsp.RATE
    private const val CONTROL = 64
    private const val OUTPUT_GAIN = .84
    private val NAMES = listOf("SHORE", "SEA", "MOTION", "DEPTH", "SPACE", "HOLD")
    private val DEFAULTS = linkedMapOf("SHORE" to 0f, "SEA" to .25f, "MOTION" to .45f,
        "DEPTH" to .55f, "SPACE" to .20f, "HOLD" to .35f)

    fun defaults(@Suppress("UNUSED_PARAMETER") voice: OspreyVoice): Map<String, Float> = DEFAULTS.toMap()
    fun macrosFor(voice: OspreyVoice): List<MacroSpec> = defaults(voice).map { (name, value) -> MacroSpec(name, value, value) }
    fun scramble(voice: OspreyVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(defaults(voice) + (near?.macros?.filterKeys { it in NAMES } ?: emptyMap()), temperature, random)
    fun frequencyFor(midi: Int): Float = Keys.midiHz(midi)
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: OspreyVoice,
        @Suppress("UNUSED_PARAMETER") macros: Map<String, Float> = emptyMap()): DrumClass = DrumClass.TONAL

    internal fun settled(voice: OspreyVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).mapValues { (name, default) -> (macros[name]?.takeIf { it.isFinite() } ?: default).coerceIn(0f, 1f) }

    fun render(voice: OspreyVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, seed: Int = 0, durationSeconds: Float = 8f,
        cancelled: () -> Boolean = { false }): Snip =
        Snip(probe(voice, macros, midi, velocity, seed, durationSeconds, cancelled).samples, 2, RATE)

    /** All taps retain the complete mix's fixed gain. Ocean is an excitation monitor, not a layer in dry. */
    internal fun probe(voice: OspreyVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, seed: Int = 0, durationSeconds: Float = 8f,
        cancelled: () -> Boolean = { false }): Osprey.Probe {
        require(midi in MIDI_MIN..MIDI_MAX) { "OSPREY midi out of $MIDI_MIN..$MIDI_MAX: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OSPREY velocity out of 0..1: $velocity" }
        require(durationSeconds.isFinite() && durationSeconds in .25f..MAX_SECONDS) { "OSPREY duration out of .25..$MAX_SECONDS: $durationSeconds" }
        val m = settled(voice, macros)
        val shore = m.getValue("SHORE")
        val sea = m.getValue("SEA").toDouble()
        val motion = m.getValue("MOTION").toDouble()
        val depth = m.getValue("DEPTH").toDouble()
        val space = m.getValue("SPACE").toDouble()
        val hold = m.getValue("HOLD").toDouble()
        val profile = OspreyShore.profile(shore)
        val radiation = radiation(shore, profile)
        val root = frequencyFor(midi).toDouble()
        val frames = (durationSeconds * RATE).roundToInt()
        val stop = min(durationSeconds * (.42 + .32 * hold), durationSeconds - min(1.4, durationSeconds * .25))
        val out = FloatArray(frames * 2)
        val ocean = FloatArray(out.size)
        val primary = FloatArray(out.size)
        val sympathy = FloatArray(out.size)
        val creatures = FloatArray(out.size)
        val dry = FloatArray(out.size)
        val events = ArrayList<Osprey.CallEvent>()
        val sceneSeed = Dsp.seedFor("OSPREY", OspreyV1.MODEL_VERSION, seed)
        val noise = Dsp.Noise(sceneSeed)
        val initial = Dsp.Noise(Dsp.seedFor(sceneSeed, "PLUCK"))
        val modeHz = FloatArray(16)
        val modes = Array(16) { i ->
            val frequency: Double
            val t60: Double
            if (i < 12) {
                val body = profile.instruments[i / 4]
                val band = i % 4
                // Each of the three bodies bears the requested root. Upper formant ratios keep
                // the source spectral fingerprints, not an arbitrary missing 'base instrument Hz'.
                frequency = if (band == 0) root else root * body.centers[band] / body.centers[0]
                t60 = (.42 + sqrt(body.decayMs / 1000.0) * .95) / (1.0 + band * .18 + sea * .32)
            } else {
                frequency = root * doubleArrayOf(2.0, 3.0, 5.0, 8.0)[i - 12]
                t60 = 1.2 + .8 * depth
            }
            val hz = frequency.coerceIn(20.0, RATE * .43)
            modeHz[i] = hz.toFloat()
            Mode(hz, t60)
        }
        val gains = DoubleArray(12) { i ->
            val band = i % 4
            val body = profile.instruments[i / 4]
            body.gains[band] * (if (band == 0) 1.0 else .63 + body.brightness * .45) * exp(-depth * band * .30) * radiation[i]
        }
        // One finite initiating contact. Its shape uses a stable scene across all shore sweeps.
        // Upper participation must survive the three coherent fundamentals: the first dry
        // spectral measurement put Atlantic/Nordic redistribution at only 1.24%.
        // This contact exposes their different modal bodies while retaining the root anchor.
        for (i in 0 until 12) {
            modes[i].v = velocity * (if (i % 4 == 0) .36 else (.16 + .08 * initial.next()))
        }
        val pans = DoubleArray(16) { i -> if (i < 12) (i / 4 - 1) * .48 + (i % 4 - 1.5) * .065 else (i - 13.5) * .32 }
        val left = DoubleArray(16) { sqrt((1.0 - pans[it]) * .5) }
        val right = DoubleArray(16) { sqrt((1.0 + pans[it]) * .5) }
        val fluid = Ocean(profile.fluid, sea, motion, Dsp.seedFor(sceneSeed, "FLUID"))
        val callers = Array(3) { kind -> Caller(Osprey.CreatureKind.entries[kind], profile.creatures[kind],
            Dsp.seedFor(sceneSeed, "CALL", kind), sea, motion, depth) }
        val callPans = doubleArrayOf(-.52, .10, .56)
        val callLeft = DoubleArray(3) { sqrt((1 - callPans[it]) * .5) }
        val callRight = DoubleArray(3) { sqrt((1 + callPans[it]) * .5) }
        val attackSeconds = DoubleArray(3) { max(.004, profile.instruments[it].attackMs / 1000.0) }
        val reverbs = Array(2) { channel -> Harbor(channel) }
        val carrier = Oscillator(root)
        val coupling = .0018 * (.30 + .70 * motion)
        val cc = cos(coupling)
        val ss = sin(coupling)
        val coupled = intArrayOf(1, 5, 9, 10)
        var activity = 0.0
        var excitationGate = 0.0
        var peakEnergy = 0.0
        var recoveries = 0
        var dcOceanIn = 0.0
        var dcOceanOut = 0.0
        val dcPole = exp(-2 * PI * 8 / RATE)
        for (frame in 0 until frames) {
            if (frame % 4096 == 0 && (cancelled() || Thread.currentThread().isInterrupted)) {
                throw CancellationException("OSPREY render no longer wanted")
            }
            val time = frame.toDouble() / RATE
            if (frame % CONTROL == 0) {
                val rise = smooth(time / .055)
                val fall = smooth((stop - time) / min(.45, durationSeconds * .18))
                excitationGate = rise * fall * velocity
                activity = fluid.next(CONTROL.toDouble() / RATE)
                for (caller in callers) {
                    caller.control(time, CONTROL.toDouble() / RATE, activity, excitationGate, stop)?.let {
                        events.add(Osprey.CallEvent(caller.eventTime.toFloat(), caller.kind, shore, activity.toFloat(), it.toFloat()))
                    }
                }
            }
            val n = noise.next().toDouble()
            val pitched = carrier.next()
            val forcing = excitationGate * (.32 + .68 * activity)
            val turbulence = (.12 + .88 * sea) * (.35 + .65 * activity)
            // White excitation is spectrally broad enough to ring all modes. The ocean's
            // low-rate energy contour shapes it; a root-period pressure component gives calm
            // water a heard pitch. Neither source is mixed around the resonating bodies.
            val pressure = forcing * (.075 * n * turbulence + .09 * pitched * (1.0 - .35 * sea))
            val dc = pressure - dcOceanIn + dcPole * dcOceanOut
            dcOceanIn = pressure; dcOceanOut = dc
            ocean[frame * 2] = (dc * OUTPUT_GAIN).toFloat()
            ocean[frame * 2 + 1] = ocean[frame * 2]
            for (i in 0 until 12) {
                val band = i % 4
                // Ocean excitation keeps the coloured upper body present through the swell,
                // instead of allowing three coherent roots to mask it after the contact.
                val noiseParticipation = if (band == 0) .010 + sea * .080 else .024 + sea * .160
                val input = forcing * (
                    n * modes[i].noiseScale * noiseParticipation * (.7 + band * .20) +
                        if (band == 0) pitched * modes[i].loss * .20 * (1.0 - sea * .25) else 0.0)
                modes[i].v += input
                if (!modes[i].step()) recoveries++
            }
            for (j in 0 until 4) {
                // A reciprocal orthogonal junction redistributes existing modal energy.
                // The fundamental is deliberately outside this coupling, so the played root
                // cannot move with MOTION or an upper sympathetic response.
                val a = modes[coupled[j]]
                val b = modes[12 + j]
                val x = a.x; val v = a.v
                a.x = cc * x - ss * b.x; b.x = ss * x + cc * b.x
                a.v = cc * v - ss * b.v; b.v = ss * v + cc * b.v
                if (!b.step()) recoveries++
            }
            var pl = 0.0; var pr = 0.0; var sl = 0.0; var sr = 0.0
            for (i in 0 until 12) {
                val attack = smooth(time / attackSeconds[i / 4])
                val value = modes[i].v * gains[i] * .28 * attack
                pl += value * left[i]; pr += value * right[i]
            }
            for (i in 12 until 16) {
                val value = modes[i].v * .20
                sl += value * left[i]; sr += value * right[i]
            }
            var cl = 0.0; var cr = 0.0
            for (i in 0..2) {
                val value = callers[i].next(time)
                cl += value * callLeft[i]; cr += value * callRight[i]
            }
            val at = frame * 2
            primary[at] = (pl * OUTPUT_GAIN).toFloat(); primary[at + 1] = (pr * OUTPUT_GAIN).toFloat()
            sympathy[at] = (sl * OUTPUT_GAIN).toFloat(); sympathy[at + 1] = (sr * OUTPUT_GAIN).toFloat()
            creatures[at] = (cl * OUTPUT_GAIN).toFloat(); creatures[at + 1] = (cr * OUTPUT_GAIN).toFloat()
            for (channel in 0..1) {
                val mono = primary[at + channel] + sympathy[at + channel] + creatures[at + channel]
                dry[at + channel] = mono
                out[at + channel] = if (space == 0.0) mono else (mono + space * .42 * reverbs[channel].next(mono.toDouble())).toFloat()
            }
            if (frame % CONTROL == 0) peakEnergy = max(peakEnergy, modes.sumOf { it.energy() })
        }
        // Finite export boundary only; this fade neither claims nor constructs a sustain loop.
        for (tap in listOf(out, ocean, primary, sympathy, creatures, dry)) Dsp.fadeTail(tap, 12f, RATE, 2)
        val peak = out.maxOfOrNull { abs(it) } ?: 0f
        check(recoveries == 0 && out.all { it.isFinite() }) { "OSPREY recovered an invalid modal state" }
        return Osprey.Probe(out, ocean, primary, sympathy, creatures, dry, events,
            Osprey.Metrics(peak, modes.sumOf { it.energy() }, peakEnergy, recoveries, stop.toFloat()), root.toFloat(), modeHz, rootIndices = intArrayOf(0, 4, 8))
    }

    // Small relative body balances, in source-table order. The root normalization
    // below preserves their summed fundamental participation rather than thinning
    // the common warm carrier to make the other modes conspicuous.
    private val BODY_BALANCE = arrayOf(
        doubleArrayOf(1.12, 1.00, .88), // Atlantic: a little more guitarra
        doubleArrayOf(.88, 1.24, .88), // Nordic: the softer langspil body
        doubleArrayOf(.96, 1.18, .86), // Mediterranean: oud body
        doubleArrayOf(.86, .92, 1.22), // Pacific: the existing bowl body
        doubleArrayOf(1.20, .88, .92), // Southern: cavaquinho body
    )
    // Combined first-upper radiation is authored directly so overlapping body and
    // colour boosts cannot compound. The 1.28 table maximum leaves room for the
    // tiny root normalization variation while staying below 1.30 at every shore.
    private val FIRST_UPPER_RADIATION = arrayOf(
        doubleArrayOf(1.28, 1.00, .93),
        doubleArrayOf(.85, 1.28, .94),
        doubleArrayOf(1.14, 1.25, .85),
        doubleArrayOf(.86, .88, 1.28),
        doubleArrayOf(1.28, .88, 1.10),
    )

    private fun radiation(shore: Float, profile: OspreyShore.Profile): DoubleArray {
        val position = shore.coerceIn(0f, 1f).toDouble() * 4.0
        val lower = position.toInt().coerceAtMost(3)
        val t = position - lower
        fun blend(rows: Array<DoubleArray>, body: Int): Double =
            rows[lower][body] + (rows[lower + 1][body] - rows[lower][body]) * t
        val weights = DoubleArray(3) { blend(BODY_BALANCE, it) }
        val rootBefore = profile.instruments.sumOf { it.gains[0] }
        val rootAfter = profile.instruments.indices.sumOf { profile.instruments[it].gains[0] * weights[it] }
        val rootCompensation = rootBefore / rootAfter
        return DoubleArray(12) { i ->
            val body = i / 4
            val band = i % 4
            // The first existing upper formant receives a modest shade. The higher
            // formants have less radiation, never new high-frequency excitation.
            when (band) {
                0 -> weights[body] * rootCompensation
                1 -> blend(FIRST_UPPER_RADIATION, body) * rootCompensation
                2 -> .96
                else -> .88
            }
        }
    }

    private fun smooth(value: Double): Double { val x = value.coerceIn(0.0, 1.0); return x * x * (3.0 - 2.0 * x) }

    /** State coordinates (omega*q, velocity) make retuning a rotation, not added energy. */
    private class Mode(hz: Double, t60: Double) {
        var x = 0.0; var v = 0.0
        private val radius = exp(-6.907755278982137 / (t60 * RATE))
        val loss = 1.0 - radius
        val noiseScale = sqrt(1.0 - radius * radius)
        private val c = radius * cos(2 * PI * hz / RATE)
        private val s = radius * sin(2 * PI * hz / RATE)
        fun step(): Boolean {
            val a = x
            x = c * a + s * v; v = c * v - s * a
            if (!x.isFinite() || !v.isFinite()) { x = 0.0; v = 0.0; return false }
            if (abs(x) + abs(v) < 1e-25) { x = 0.0; v = 0.0 }
            return true
        }
        fun energy() = .5 * (x * x + v * v)
    }

    private class Oscillator(hz: Double) {
        private var x = 0.0; private var y = 1.0
        private var c = cos(2 * PI * hz / RATE); private var s = sin(2 * PI * hz / RATE)
        fun phase(radians: Double) { x = sin(radians); y = cos(radians) }
        fun tune(hz: Double) { c = cos(2 * PI * hz.coerceIn(20.0, RATE * .40) / RATE); s = sin(2 * PI * hz.coerceIn(20.0, RATE * .40) / RATE) }
        fun next(): Double { val a = x; x = c * x + s * y; y = c * y - s * a; return x }
    }

    private class SmoothNoise(seed: Int) {
        private val noise = Dsp.Noise(seed)
        private var from = noise.next().toDouble(); private var to = noise.next().toDouble()
        private var phase = 0.0
        fun next(step: Double): Double {
            phase += step
            while (phase >= 1.0) { phase -= 1.0; from = to; to = noise.next().toDouble() }
            return from + (to - from) * smooth(phase)
        }
    }

    private class Ocean(private val fluid: OspreyShore.Fluid, private val sea: Double,
        private val motion: Double, seed: Int) {
        private var swell = -.5
        private var chop = .7
        private val octaves = Array(4) { SmoothNoise(Dsp.seedFor(seed, it)) }
        fun next(dt: Double): Double {
            // Proof gestures compress the original 5-25 s ocean periods without discarding
            // their shore-dependent ordering; one or more swells fit the finite audition.
            val period = fluid.period * .24 / (.45 + motion * 1.7)
            swell += 2 * PI * dt / period
            chop += 2 * PI * dt * fluid.chopHz * (1.0 + motion * 3.5)
            val swellShape = .5 + .5 * sin(swell)
            val chopShape = sin(chop)
            var turbulence = 0.0
            for (i in 0..3) turbulence += octaves[i].next(dt * (.8 + motion * 2.8) * (1 shl i)) / (1 shl i)
            val storm = smooth((sea - fluid.turbulenceOnset) / (1.0 - fluid.turbulenceOnset))
            return (.22 + swellShape * (.40 + .20 * fluid.swellDepth) + sea * abs(chopShape) * fluid.chopAmount * .20 +
                storm * abs(turbulence) * .22).coerceIn(.05, 1.0)
        }
    }

    /** Three moving, state-preserving TPT bands with control interpolation. */
    private class Formant {
        private var ic1 = 0.0; private var ic2 = 0.0
        private var g = .01; private var k = .5; private var dg = 0.0; private var dk = 0.0
        fun reset() { ic1 = 0.0; ic2 = 0.0 }
        fun tune(center: Double, width: Double, immediate: Boolean) {
            val nextG = tan(PI * center.coerceIn(30.0, RATE * .40) / RATE)
            val nextK = (width / center).coerceIn(.07, 2.0)
            if (immediate) { g = nextG; k = nextK; dg = 0.0; dk = 0.0 }
            else { dg = (nextG - g) / CONTROL; dk = (nextK - k) / CONTROL }
        }
        fun next(input: Double): Double {
            g += dg; k += dk
            val a1 = 1.0 / (1.0 + g * (g + k))
            val v1 = a1 * (ic1 + g * (input - ic2))
            val v2 = ic2 + g * v1
            ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2
            if (abs(ic1) < 1e-25) ic1 = 0.0
            if (abs(ic2) < 1e-25) ic2 = 0.0
            return k * v1
        }
    }

    private class Caller(val kind: Osprey.CreatureKind, private val shape: OspreyShore.Creature,
        seed: Int, private val sea: Double, private val motion: Double, private val depth: Double) {
        private val noise = Dsp.Noise(seed)
        private val sourceNoise = Dsp.Noise(Dsp.seedFor(seed, "SOURCE"))
        private val filters = Array(3) { Formant() }
        private val source = Oscillator(shape.start[0])
        private val harmonic = Oscillator(shape.start[0] * 2)
        private var started = -100.0
        val eventTime get() = started
        private var sourceStarted = false
        private var duration = .1
        private var strength = 0.0
        private var cooldown = 0.0
        private var accumulated = 0.0
        // Small first opportunity makes the three roles inspectable in an eight-second proof;
        // subsequent calls wait their shore-specific gaps and independent seeded thresholds.
        private var threshold = doubleArrayOf(.12, .055, .035)[kind.ordinal] * (1.0 + .30 * noise.next())
        fun control(time: Double, dt: Double, activity: Double, gate: Double, stop: Double): Double? {
            val age = time - started
            if (age < duration) {
                val trajectory = smooth(age / duration)
                for (i in 0..2) filters[i].tune(shape.start[i] + (shape.end[i] - shape.start[i]) * trajectory, shape.widths[i], false)
                val hz = shape.start[0] + (shape.end[0] - shape.start[0]) * trajectory
                source.tune(hz); harmonic.tune(hz * 2)
            }
            if (time >= stop - .04 || gate <= .001) return null
            // A cooldown can expire inside this control interval. Integrate only its eligible
            // fraction, then locate the threshold crossing inside it; rounding every expiry
            // and call to the control grid caused cumulative whole-cycle jumps under SHORE.
            val eligibleFrom = max(time, cooldown)
            val eligibleUntil = min(time + dt, stop - .04)
            if (eligibleUntil <= eligibleFrom) return null
            val opportunities = (.24 + sea * .85 + motion * .25) * 2000.0 / shape.gapMs.coerceAtLeast(700.0)
            val hazardRate = opportunities * activity * activity * gate
            val remaining = threshold - accumulated
            val increment = hazardRate * (eligibleUntil - eligibleFrom)
            if (increment < remaining) { accumulated += increment; return null }
            started = eligibleFrom + remaining / hazardRate
            accumulated = 0.0
            duration = (shape.sweepMs / 1000.0).coerceIn(.055, 3.4) / (.80 + motion * .65)
            duration = min(duration, max(.04, stop - started + .20))
            strength = gate * (.25 + .75 * activity)
            cooldown = started + duration + shape.gapMs / 1000.0 * (.30 + .25 * (1.0 - sea))
            threshold = .45 + .65 * (noise.next() + 1.0) * .5
            for (i in 0..2) { filters[i].reset(); filters[i].tune(shape.start[i], shape.widths[i], true) }
            source.tune(shape.start[0]); harmonic.tune(shape.start[0] * 2)
            sourceStarted = false
            return strength
        }
        fun next(time: Double): Double {
            // The independent noise stream follows world time even in gaps. A fractional
            // onset changes the envelope, not which random waveform starts underneath it.
            val n = sourceNoise.next().toDouble()
            val age = time - started
            if (age < 0 || age >= duration) return 0.0
            if (!sourceStarted) {
                // Anchor onset phase to the shared render clock. A tiny smooth event-time
                // shift must not reset a kilohertz carrier to an unrelated world-time phase.
                source.phase(2 * PI * shape.start[0] * time)
                harmonic.phase(4 * PI * shape.start[0] * time)
                sourceStarted = true
            }
            val rise = smooth(age / min(.035, duration * .20))
            val fall = smooth((duration - age) / min(.12, duration * .30))
            val tone = source.next() + .25 * harmonic.next()
            val input = when (kind) {
                Osprey.CreatureKind.BIRD -> tone * .75 + n * .18
                Osprey.CreatureKind.DEEP -> tone * .9 + n * .05
                Osprey.CreatureKind.AMBIENT -> tone * .25 + n * .70
            }
            var value = 0.0
            for (i in 0..2) value += filters[i].next(input) / (i + 1.0)
            return value * rise * fall * strength * depth * shape.amplitude * .12 * (.45 + .55 * sea)
        }
    }

    private class Harbor(channel: Int) {
        private val stages = Array(4) { i -> Allpass(intArrayOf(1499, 2203, 3067, 4001)[i] + channel * 137) }
        private var low = 0.0
        fun next(input: Double): Double {
            var value = input
            for (stage in stages) value = stage.next(value)
            low += .12 * (value - low)
            return low
        }
    }
    private class Allpass(length: Int) {
        private val buffer = DoubleArray(length)
        private var at = 0
        fun next(input: Double): Double {
            val delayed = buffer[at]
            val out = delayed - .55 * input
            buffer[at] = input + .55 * out
            at++; if (at == buffer.size) at = 0
            return out
        }
    }
}
