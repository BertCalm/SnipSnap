package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** NACRE's new sound sources, each on its own saved model. PEARL keeps models 1 to 4. */
internal enum class NacreSource(val model: Int) { HARMONICS(5), SHELL(6) }

/**
 * NACRE's second and third sound sources, built for listening round `nacre-voices-1` (14 Nov 2026).
 * Engine only: no picker entry, preset or starter. A saved [NacrePatch] reaches one by its model
 * (5 or 6); the patch's `voice` stays [NacreVoice.PEARL], the only voice entry, until the sources are
 * registered as voices of their own.
 *
 * Both keep what the owner asked every NACRE voice to keep: the paired bass and upper performers and
 * their strum ([schedule], the same law as PEARL's), the two harp bodies, the eight sympathetic harp
 * strings tuned to the note, and the water: PEARL's own coral and seaweed returns, VENUE, CURRENT and
 * BLOOM ([Nacre.returns]), applied after the source exactly as model 3 applies them. Only the played
 * source changes.
 *
 * - **HARMONICS** plucks each harp string as a harmonic. The string is tuned an octave below the
 *   note; for a few of its periods after the pluck a light finger rests at its midpoint. Inside the
 *   loop that touch averages the returning wave with itself one sounding period earlier, which
 *   cancels the string's odd modes (the ones without a node there) and, through the finger's own
 *   low-pass, its upper partials. The finger then lifts and the even modes ring on: the note at the
 *   requested pitch, purer and rounder than PEARL's pluck. The average is a convex combination of
 *   past returns and the low-pass has unity gain at DC, so the touch can only remove loop energy.
 * - **SHELL** strikes small modal shells instead of strings: five slightly stretched modes per
 *   shell, struck by a mallet whose contact gets longer (duller) as TOUCH and velocity fall. Each
 *   mode is a damped two-pole resonator with its own decay; the lowest mode, at the note, decays
 *   slowest, so the ring-out carries the pitch.
 *
 * Every renderer here is new; nothing in models 1 to 4 is called or changed, so their audio stays
 * byte-identical. Output uses one fixed reference gain per source and a scalar [PEAK_CEILING] guard.
 * There is no per-patch loudness matching, so velocity and TOUCH keep their effect on level.
 */
internal object NacreVoices {
    const val HARMONICS_MODEL = 5
    const val SHELL_MODEL = 6
    const val PEAK_CEILING = .94f

    /** Fixed reference output gains: hard C3 at the default controls sits near PEARL's hard C3. */
    const val HARMONICS_OUTPUT_GAIN = 4.3
    const val SHELL_OUTPUT_GAIN = 1.935

    private const val DURATION_SECONDS = 6f
    private const val CORE_CURRENT = .20
    private const val CORE_BLOOM = .35
    private const val STRINGS_PER_HARP = 4
    private const val CONTROL_BLOCK = 32
    private const val TWO_PI = 2.0 * PI
    private val RATIOS = doubleArrayOf(1.0, 2.0, 4.0, 8.0)

    /** SHELL's modes: slightly stretched, so the shell reads as a struck object with a clear note. */
    private val SHELL_RATIOS = doubleArrayOf(1.0, 2.41, 4.13, 6.20, 8.57)
    private val SHELL_WEIGHTS = doubleArrayOf(1.0, .70, .45, .28, .16)

    fun supports(model: Int): Boolean = model == HARMONICS_MODEL || model == SHELL_MODEL

    fun sourceFor(model: Int): NacreSource = NacreSource.entries.firstOrNull { it.model == model }
        ?: throw IllegalArgumentException("unsupported nacre source model $model")

    /** The same six controls and defaults as PEARL's model 3, so A/B listening compares like with like. */
    fun macrosFor(): List<MacroSpec> = Nacre.macrosFor(NacreVoice.PEARL)

    fun defaults(): Map<String, Float> = macrosFor().associate { it.name to it.default }

    fun render(source: NacreSource, macros: Map<String, Float> = emptyMap(), midi: Int = Nacre.DEFAULT_MIDI,
               velocity: Float = 1f, seed: Long = Nacre.DEFAULT_SEED): Snip =
        play(source, macros, midi, velocity, seed, record = false).snip

    fun inspect(source: NacreSource, macros: Map<String, Float> = emptyMap(), midi: Int = Nacre.DEFAULT_MIDI,
                velocity: Float = 1f, seed: Long = Nacre.DEFAULT_SEED): Nacre.Probe =
        play(source, macros, midi, velocity, seed, record = true)

    private fun stopIfInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("NACRE render cancelled")
    }

    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    private fun settled(macros: Map<String, Float>): Map<String, Float> {
        val defaults = defaults()
        require(macros.keys.all { it in defaults }) { "NACRE contains unknown macros: ${macros.keys - defaults.keys}" }
        val m = defaults.mapValues { (name, default) -> (macros[name] ?: default).also {
            require(it.isFinite() && it in 0f..1f) { "NACRE $name must be finite and in 0..1, got $it" }
        } }
        require(m.getValue("HOLD") == 0f) { "NACRE's sources are finite; HOLD must be 0. Held gestures are not implemented." }
        return m
    }

    /** Stable ID-derived variations, in each source's own seed space. */
    private fun variation(source: NacreSource, seed: Long, performer: String, id: Int, purpose: String): Double =
        ((Dsp.seedFor("NACRE", source.model, seed, performer, id, purpose).toLong() and 0xffffffffL) /
            4294967295.0) * 2.0 - 1.0

    /** PEARL's internal water at its core CURRENT .20: related slow components at fixed positions. */
    private class Field(val depth: Double, val hz: Double, val phase: Double) {
        fun at(seconds: Double, position: Double): Double {
            val p = TWO_PI * hz * seconds + phase
            return (.78 * sin(p + position) + .22 * sin(2.0 * p - .63 * position + .4)).coerceIn(-1.0, 1.0)
        }
    }

    private fun field(source: NacreSource, seed: Long) = Field(
        depth = 1.0 - exp(-3.2 * CORE_CURRENT),
        hz = .28 + 2.1 * CORE_CURRENT.pow(1.3),
        phase = .4 + .5 * variation(source, seed, "water", 0, "phase"),
    )

    /** PEARL's paired strum: a continuous answering offset, one direction per complete gesture. */
    private fun schedule(source: NacreSource, touch: Double, duet: Double, f0: Double, velocity: Float,
                         seed: Long, water: Field): List<Nacre.Event> {
        val offset = .006 + .22 * duet.pow(1.45) - .42 * smooth((duet - .72) / .28)
        val bassOrigin = .018 + if (offset < 0.0) -offset else 0.0
        val upperOrigin = .018 + if (offset > 0.0) offset else 0.0
        val spacing = (.008 + .052 * duet) * (1.0 - .14 * touch)
        val events = ArrayList<Nacre.Event>(8)
        for (harp in 0..1) {
            val performer = if (harp == 0) "bass" else "upper"
            val descending = harp == 1 && duet >= .65
            for (order in 0 until STRINGS_PER_HARP) {
                val id = if (descending) STRINGS_PER_HARP - 1 - order else order
                val time = (if (harp == 0) bassOrigin else upperOrigin) + order * spacing *
                    (if (harp == 0) 1.04 else .94) + .0008 * variation(source, seed, performer, id, "timing")
                // PEARL's TOUCH law, plus the strike: a softer hit sets fewer of the higher strings or
                // shells ringing, so it is darker as well as quieter (PEARL's own v1 layers were not).
                val participation = if (id == 0) 1.0 else
                    (.12 + .88 * touch.pow(.65)) * (if (harp == 0) .48 else .58).pow(id) * (.35 + .65 * velocity).pow(id)
                val effort = (.22 + .78 * touch.pow(.65)) * velocity *
                    (.95 + .05 * variation(source, seed, performer, id, "effort"))
                val wet = 1.0 - .14 * water.depth * (.5 + .5 * water.at(time, harp * 1.7))
                events += Nacre.Event(performer, id, time, f0 * (if (harp == 0) 1.0 else 2.0) * RATIOS[id],
                    effort * participation * wet * (if (harp == 0) 1.0 else .72),
                    if (descending) "descending" else "ascending")
            }
        }
        return events.sortedWith(compareBy<Nacre.Event> { it.timeSeconds }.thenBy { it.performer }.thenBy { it.stringId })
    }

    /** Static constant-peak bandpass, as PEARL's bodies and sympathetic strings use. */
    private class Mode(hz: Double, q: Double, rate: Int) {
        private val w = TWO_PI * hz / rate
        private val alpha = sin(w) / (2.0 * q)
        private val b = alpha / (1.0 + alpha)
        private val a1 = -2.0 * cos(w) / (1.0 + alpha)
        private val a2 = (1.0 - alpha) / (1.0 + alpha)
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0
        fun next(x: Double): Double {
            val y = b * (x - x2) - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    private class LowPass(hz: Double, rate: Int) {
        private val a = 1.0 - exp(-TWO_PI * hz / rate)
        private var state = 0.0
        fun next(x: Double): Double { state += a * (x - state); return state }
    }

    /** A damped two-pole resonator whose impulse response starts at unit amplitude. Poles inside the circle. */
    private class Resonator(hz: Double, t60: Double, rate: Int) {
        private val w = TWO_PI * hz / rate
        private val r = exp(-ln(1000.0) / (t60 * rate))
        private val a1 = 2.0 * r * cos(w)
        private val a2 = -r * r
        private val norm = sin(w)
        private var y1 = 0.0
        private var y2 = 0.0
        fun next(x: Double): Double {
            val y = a1 * y1 + a2 * y2 + x
            y2 = y1; y1 = y
            return y * norm
        }
    }

    /** One harmonic string: PEARL's loop, tuned an octave down, with a timed midpoint touch. */
    private class HarmonicString(
        val event: Nacre.Event,
        val start: Int,
        val burst: FloatArray,
        val loop: Strings.Loop,
        val t60: Double,
        val stringHz: Double,
        private val touchFrames: Int,
        private val releaseFrames: Int,
        private val sounding: Double,
        fingerHz: Double,
        rate: Int,
    ) {
        private val ring = DoubleArray(ceil(sounding).toInt() + 3)
        private var head = 0
        private val fingerA = 1.0 - exp(-TWO_PI * fingerHz / rate)
        private var finger = 0.0
        var peakTouch = 0.0; private set

        private fun touch(age: Int): Double = when {
            age < touchFrames -> 1.0
            age >= touchFrames + releaseFrames -> 0.0
            else -> 1.0 - smoothStep((age - touchFrames).toDouble() / releaseFrames)
        }

        private fun smoothStep(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

        /** The returning wave [sounding] samples ago, linearly interpolated from the ring. */
        private fun delayed(): Double {
            val whole = sounding.toInt()
            val frac = sounding - whole
            val size = ring.size
            val a = ring[((head - 1 - whole) % size + size) % size]
            val b = ring[((head - 2 - whole) % size + size) % size]
            return a + frac * (b - a)
        }

        fun next(age: Int, excitation: Float): Double {
            val r = loop.reflected().toDouble()
            ring[head] = r
            head = (head + 1) % ring.size
            val d = touch(age)
            val returned = if (d == 0.0) r else {
                // The midpoint node: averaging with the wave one sounding period back cancels every
                // odd string mode. The finger's width low-passes what passes. Both only remove energy.
                val node = .5 * (r + delayed())
                finger += fingerA * (node - finger)
                peakTouch = maxOf(peakTouch, d)
                (1.0 - d) * r + d * finger
            }
            val y = excitation + returned
            loop.inject(y.toFloat())
            return y
        }
    }

    private class Shell(val event: Nacre.Event, val start: Int, val pulse: FloatArray,
                        val modes: Array<Resonator>, val weights: DoubleArray)

    /** Identical linear conditioning for the source and sympathetic streams, as PEARL's core uses. */
    private fun condition(oversampled: FloatArray): FloatArray {
        stopIfInterrupted()
        Tide.bandLimit(oversampled, Dsp.RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(oversampled, Dsp.RATE)
        val dc = LowPass(3.0, Dsp.RATE)
        for (i in out.indices) out[i] = (out[i] - dc.next(out[i].toDouble())).toFloat()
        Dsp.fadeTail(out, ms = 50f)
        return out
    }

    private class Streams(val bass: FloatArray, val upper: FloatArray, val sympathetic: FloatArray,
                          val diagnostics: Map<String, Double>)

    private fun harmonics(events: List<Nacre.Event>, touch: Double, velocity: Float, seed: Long,
                          frames: Int, rate: Int): Pair<List<HarmonicString>, Map<String, Double>> {
        val strings = events.map { event ->
            val bass = event.performer == "bass"
            val hz = event.frequencyHz
            val stringHz = hz / 2.0
            // A harmonic is far purer than a pluck: the loop keeps only a few partials of the sounding
            // note (PEARL's keeps six to twenty-one), brighter for a firmer TOUCH and a harder strike.
            val brightness = (2.2 + 2.8 * touch) * (.8 + .2 * velocity)
            val loopHz = (hz * brightness).coerceAtMost(if (bass) 7000.0 else 10500.0).toFloat()
            val t60 = 1.25 * (if (bass) 3.1 + 1.1 * CORE_BLOOM else 2.2 + .9 * CORE_BLOOM) * .76.pow(event.stringId)
            val feedback = 10.0.pow(-3.0 / (t60 * stringHz)).coerceAtMost(.9995)
            val tuning = Strings.tune(stringHz.toFloat(), loopHz, rate)
            val loop = Strings.Loop(tuning.n, tuning.a, feedback.toFloat(), loopHz, rate)
            val pickHz = (if (bass) 2200.0 + 5700.0 * touch else 4300.0 + 8200.0 * touch) * (.7 + .3 * velocity)
            // Plucked near the touched midpoint's quarter, the way a harpist plays a harmonic.
            val pickPosition = .25 + .02 * variation(NacreSource.HARMONICS, seed, event.performer, event.stringId, "pick")
            val burst = Strings.pluckExciter(tuning.n, stringHz.toFloat(), pickHz.toFloat(), pickPosition.toFloat(),
                Dsp.seedFor("NACRE", NacreSource.HARMONICS.model, seed, event.performer, event.stringId, "pluck"), rate, frames)
            var peak = 0f
            for (sample in burst) peak = maxOf(peak, abs(sample))
            // A softer strike has less pick noise against the pitched tone.
            val foundation = 1.0 - (if (event.stringId == 0) .25 else .35) * (.3 + .7 * velocity)
            val pickNoise = LowPass(minOf(pickHz, 3.0 * hz), rate)
            val edgeFrames = minOf((.00035 * rate).roundToInt(), (burst.size - 1) / 3).coerceAtLeast(1)
            for (i in burst.indices) {
                val noise = if (peak > 1e-9f) burst[i] / peak else 0f
                val displacement = sin(TWO_PI * hz * i / rate)
                val envelope = smooth(i.toDouble() / edgeFrames) * smooth((burst.lastIndex - i).toDouble() / edgeFrames)
                burst[i] = (.28 * event.strength * envelope *
                    ((1.0 - foundation) * pickNoise.next(noise.toDouble()) + foundation * displacement)).toFloat()
            }
            val periodFrames = rate / stringHz
            // A softer touch rests longer and with a broader finger: a purer, duller harmonic.
            val touchFrames = ((3.0 + 3.0 * (1.0 - touch)) * periodFrames).roundToInt()
            val releaseFrames = periodFrames.roundToInt().coerceAtLeast(1)
            val fingerHz = hz * (1.5 + 4.5 * touch) * (.35 + .65 * velocity)
            HarmonicString(event, (event.timeSeconds * rate).roundToInt(), burst, loop, t60, stringHz,
                touchFrames, releaseFrames, rate / hz, fingerHz, rate)
        }
        return strings to linkedMapOf("harmonicTouchPoint" to .5)
    }

    private fun shells(events: List<Nacre.Event>, touch: Double, velocity: Float, rate: Int): List<Shell> = events.map { event ->
        val bass = event.performer == "bass"
        val hz = event.frequencyHz
        // A soft mallet stays on the shell longer: a raised-cosine contact whose first null is 2 / width.
        val contactMs = (.12 + .9 * (1.0 - touch)) * (1.8 - .8 * velocity)
        val width = (contactMs / 1000.0 * rate).roundToInt().coerceAtLeast(3)
        val kernel = DoubleArray(width) { .5 - .5 * cos(TWO_PI * (it + 1) / (width + 1)) }
        val sum = kernel.sum()
        val pulse = FloatArray(width) { (.28 * event.strength * kernel[it] / sum).toFloat() }
        val base = (if (bass) 2.6 else 1.9) * .80.pow(event.stringId)
        val ceiling = .36 * Dsp.RATE
        val kept = SHELL_RATIOS.indices.filter { hz * SHELL_RATIOS[it] < ceiling }
        Shell(event, (event.timeSeconds * rate).roundToInt(), pulse,
            kept.map { Resonator(hz * SHELL_RATIOS[it], base / SHELL_RATIOS[it].pow(.45), rate) }.toTypedArray(),
            kept.map { SHELL_WEIGHTS[it] }.toDoubleArray())
    }

    private fun core(source: NacreSource, touch: Double, duet: Double, midi: Int, velocity: Float,
                     seed: Long): Pair<Streams, List<Nacre.Event>> {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val frames = (DURATION_SECONDS * rate).roundToInt()
        val f0 = Keys.midiHz(midi).toDouble()
        val water = field(source, seed)
        val events = schedule(source, touch, duet, f0, velocity, seed, water)
        val (strings, extra) = if (source == NacreSource.HARMONICS)
            harmonics(events, touch, velocity, seed, frames, rate) else emptyList<HarmonicString>() to emptyMap<String, Double>()
        val shellList = if (source == NacreSource.SHELL) shells(events, touch, velocity, rate) else emptyList()
        val bassBody = arrayOf(138.0, 261.0, 427.0).mapIndexed { i, hz -> Mode(hz, 3.0 + i, rate) }
        val upperBody = arrayOf(397.0, 743.0, 1261.0).mapIndexed { i, hz -> Mode(hz, 2.8 + i, rate) }
        val sympathetic = Array(2) { harp -> Array(4) { id ->
            val hz = f0 * (if (harp == 0) 1.0 else 2.0) * RATIOS[id]
            val t60 = (.85 + 2.25 * CORE_BLOOM) * .85.pow(id)
            Mode(hz, PI * hz * t60 / ln(1000.0), rate)
        } }
        val bassOut = FloatArray(frames)
        val upperOut = FloatArray(frames)
        val symOut = FloatArray(frames)
        var previousBassBody = 0.0
        var previousUpperBody = 0.0
        var crossBass = 1.0
        var crossUpper = 1.0
        var internalPeak = 0.0
        val sympatheticGain = .10 + 2.0 * CORE_BLOOM.pow(.72)
        val cross = .20 + .48 * CORE_BLOOM
        for (i in 0 until frames) {
            if (i % 32768 == 0) stopIfInterrupted()
            if (i % CONTROL_BLOCK == 0) {
                val time = i.toDouble() / rate
                val bassFlow = water.at(time, .2)
                val upperFlow = water.at(time, 1.9)
                crossBass = 1.0 - .72 * water.depth * (.5 + .5 * bassFlow)
                crossUpper = 1.0 - .72 * water.depth * (.5 + .5 * upperFlow)
                for (s in strings) {
                    val flow = if (s.event.performer == "bass") bassFlow else upperFlow
                    val loading = .35 + .65 * (.5 + .5 * flow)
                    val amount = water.depth * (if (s.event.stringId == 0) .28 else .90)
                    // PEARL's water loading: a nonnegative per-period loss, fixed phase-bearing coefficients.
                    s.loop.gain(exp(-amount * loading * ln(1000.0) / (s.t60 * s.stringHz)).toFloat().coerceIn(0f, 1f))
                }
            }
            var bass = 0.0
            var upper = 0.0
            for (s in strings) {
                val age = i - s.start
                val excitation = if (age in s.burst.indices) s.burst[age] else 0f
                val sample = s.next(age, excitation)
                internalPeak = maxOf(internalPeak, abs(sample))
                if (s.event.performer == "bass") bass += sample else upper += sample
            }
            for (shell in shellList) {
                val age = i - shell.start
                if (age < 0) continue
                val x = if (age < shell.pulse.size) shell.pulse[age].toDouble() else 0.0
                var y = 0.0
                for (k in shell.modes.indices) y += shell.weights[k] * shell.modes[k].next(x)
                internalPeak = maxOf(internalPeak, abs(y))
                if (shell.event.performer == "bass") bass += y else upper += y
            }
            var bBody = 0.0
            var uBody = 0.0
            for (mode in bassBody) bBody += mode.next(bass) / bassBody.size
            for (mode in upperBody) uBody += mode.next(upper) / upperBody.size
            val bDrive = .62 * previousBassBody + cross * crossBass * previousUpperBody
            val uDrive = .62 * previousUpperBody + cross * crossUpper * previousBassBody
            var sym = 0.0
            for (id in 0 until 4) {
                val weight = .65.pow(id) / 2.35
                sym += weight * sympathetic[0][id].next(bDrive)
                sym += .82 * weight * sympathetic[1][id].next(uDrive)
            }
            previousBassBody = bBody
            previousUpperBody = uBody
            val b = .92 * bass + .26 * bBody
            val u = .92 * upper + .22 * uBody
            check(b.isFinite() && u.isFinite() && sym.isFinite()) { "NACRE ${source.name} nonfinite state at frame $i" }
            bassOut[i] = b.toFloat()
            upperOut[i] = u.toFloat()
            symOut[i] = (sym * sympatheticGain).toFloat()
        }
        val diagnostics = LinkedHashMap<String, Double>(extra)
        diagnostics["internalSourcePeak"] = internalPeak
        diagnostics["maxTouchApplied"] = strings.maxOfOrNull { it.peakTouch } ?: 0.0
        return Streams(condition(bassOut), condition(upperOut), condition(symOut), diagnostics) to events
    }

    private fun play(source: NacreSource, macros: Map<String, Float>, midi: Int, velocity: Float, seed: Long,
                     record: Boolean): Nacre.Probe {
        require(midi in Nacre.MIN_MIDI..Nacre.MAX_MIDI) { "NACRE supports MIDI ${Nacre.MIN_MIDI}..${Nacre.MAX_MIDI}, got $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "NACRE velocity must be finite and in 0..1, got $velocity" }
        val m = settled(macros)
        stopIfInterrupted()
        val (c, events) = core(source, m.getValue("TOUCH").toDouble(), m.getValue("DUET").toDouble(),
            midi, velocity, seed)
        val sourceMix = FloatArray(c.bass.size) { c.bass[it] + c.upper[it] }
        val venue = m.getValue("VENUE").toDouble()
        val current = m.getValue("CURRENT")
        val bloom = m.getValue("BLOOM")
        val gain = Nacre.REFERENCE_RETURN_GAIN * Nacre.returnAmount(bloom)
        val symGain = Nacre.sympatheticAmount(bloom)
        val coral = if (venue < 1.0) Nacre.returns(sourceMix, current, false) else null
        val weed = if (venue > 0.0) Nacre.returns(sourceMix, current, true) else null
        val wet = FloatArray(sourceMix.size) { i ->
            val blend = when (venue) {
                0.0 -> coral!![i].toDouble()
                1.0 -> weed!![i].toDouble()
                else -> (1.0 - venue) * coral!![i] + venue * weed!![i]
            }
            (gain * blend).toFloat()
        }
        val sympathetic = FloatArray(c.sympathetic.size) { (c.sympathetic[it] * symGain).toFloat() }
        val raw = FloatArray(sourceMix.size) { sourceMix[it] + sympathetic[it] + wet[it] }
        val outputGain = if (source == NacreSource.HARMONICS) HARMONICS_OUTPUT_GAIN else SHELL_OUTPUT_GAIN
        val native = FloatArray(raw.size) { (raw[it] * outputGain).toFloat() }
        val preGuardPeak = native.maxOf { abs(it) }
        val peakSafetyGain = if (preGuardPeak > PEAK_CEILING) (PEAK_CEILING / preGuardPeak).toDouble() else 1.0
        // A hair under the ceiling, so the scaled peak cannot round up past it.
        Dsp.limitPeak(native, PEAK_CEILING - 1e-5f)
        stopIfInterrupted()
        var energy = 0.0
        for (v in raw) energy += v.toDouble() * v
        val diagnostics = LinkedHashMap(c.diagnostics).also {
            it["modelVersion"] = source.model.toDouble()
            it["rootHz"] = Keys.midiHz(midi).toDouble()
            it["returnGain"] = gain
            it["sympatheticGain"] = symGain
            it["referenceOutputGain"] = outputGain
            it["peakSafetyGain"] = peakSafetyGain
            it["venueFeedbackGain"] = 0.0
            it["rawRms"] = sqrt(energy / raw.size)
            it["rawPeak"] = raw.maxOf { v -> abs(v).toDouble() }
        }
        val stems = if (!record) emptyMap() else linkedMapOf("bass" to c.bass, "upper" to c.upper,
            "sympathetic" to sympathetic, "venue" to wet)
        return Nacre.Probe(Snip(native, channels = 1, sampleRate = Dsp.RATE), raw, stems, events, diagnostics)
    }
}
