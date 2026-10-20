package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The first listening prototype deliberately has one performer profile. */
enum class NacreVoice { PEARL }

/**
 * NACRE model 1: two finite, separately excited octave harp gestures in moving water.
 *
 * The eight active waveguides have fixed tuning/filter coefficients. CURRENT only reduces their
 * loop gains; it never moves delay lengths or raises a gain above its built value. Each harp
 * drives a separate, fixed three-mode body. A one-sample-delayed mixture of the two bodies enters
 * each harp's four sympathetic resonators, with bounded current-dependent transmission. This is
 * an acyclic body-to-sympathetic path: neither the bodies, sympathetic strings, nor venues feed
 * back into an active string. Each active string's round trip is contractive (feedback < 1 and
 * fixed allpass/averaging/low-pass stages); every other resonator has fixed poles inside the unit
 * circle. Bounded modulation on this feed-forward graph cannot create a new feedback loop.
 *
 * This intentionally reduces the proposed Nacre spec for an audition: VENUE is a convex output
 * blend from short coral reflections/cavities at 0 to soft open seaweed transmission paths at 1.
 * There is no conch, cave, venue loading return, chord mode, or HOLD. The final 50 ms use the
 * project's capped-tail treatment; this finite sample must never be presented as a held loop.
 * All sound is synthesized at 4x, then antialiased, decimated and DC-blocked before leveling.
 */
object Nacre {
    const val MODEL_VERSION = 1
    const val DEFAULT_MIDI = 48
    const val MIN_MIDI = 28
    const val MAX_MIDI = 72
    const val DEFAULT_SEED = 7L
    const val DURATION_SECONDS = 6f
    private const val STRINGS_PER_HARP = 4
    private const val CONTROL_BLOCK = 32
    private const val TWO_PI = 2.0 * PI
    private val RATIOS = doubleArrayOf(1.0, 2.0, 4.0, 8.0)

    fun macrosFor(voice: NacreVoice): List<MacroSpec> = when (voice) {
        NacreVoice.PEARL -> listOf(
            MacroSpec("TOUCH", .30f, neutral = .30f),
            MacroSpec("DUET", .15f, neutral = .15f),
            MacroSpec("CURRENT", .20f, neutral = .20f),
            MacroSpec("VENUE", .15f, neutral = .15f),
            MacroSpec("BLOOM", .35f, neutral = .35f),
            MacroSpec("HOLD", 0f),
        )
    }

    fun defaults(voice: NacreVoice = NacreVoice.PEARL): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    private fun settled(voice: NacreVoice, macros: Map<String, Float>): Map<String, Float> {
        val defaults = defaults(voice)
        require(macros.keys.all { it in defaults }) { "NACRE contains unknown macros: ${macros.keys - defaults.keys}" }
        val m = defaults.mapValues { (name, default) ->
            (macros[name] ?: default).also {
                require(it.isFinite() && it in 0f..1f) { "NACRE $name must be finite and in 0..1, got $it" }
            }
        }
        require(m.getValue("HOLD") == 0f) {
            "NACRE model 1 is a finite paired-strum prototype; HOLD must be 0. Seamless held gestures are not implemented."
        }
        return m
    }

    @Suppress("UNUSED_PARAMETER")
    fun drumClassFor(voice: NacreVoice, macros: Map<String, Float> = emptyMap()): DrumClass = DrumClass.TONAL

    internal data class Options(
        val muteBassPlucks: Boolean = false,
        val muteUpperPlucks: Boolean = false,
        val disableCrossHarp: Boolean = false,
        val postCurrentOnly: Boolean = false,
        val bypassVenue: Boolean = false,
    )

    internal data class Event(
        val performer: String,
        val stringId: Int,
        val timeSeconds: Double,
        val frequencyHz: Double,
        val strength: Double,
        val direction: String,
    )

    /** Stems are output-rate, antialiased/DC-blocked, pre-level contributions to [raw]. */
    internal data class Probe(
        val snip: Snip,
        val raw: FloatArray,
        val stems: Map<String, FloatArray>,
        val events: List<Event>,
        val diagnostics: Map<String, Double>,
    )

    fun render(
        voice: NacreVoice = NacreVoice.PEARL,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
        seed: Long = DEFAULT_SEED,
    ): Snip = play(voice, macros, midi, velocity, seed, Options(), record = false).snip

    internal fun inspect(
        voice: NacreVoice = NacreVoice.PEARL,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
        seed: Long = DEFAULT_SEED,
        options: Options = Options(),
    ): Probe = play(voice, macros, midi, velocity, seed, options, record = true)

    private fun stopIfInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("NACRE render cancelled")
    }

    private fun smooth(x: Double): Double = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }

    /** Stable ID-derived variations: muting/editing one harp never advances another harp's PRNG. */
    private fun variation(seed: Long, performer: String, id: Int, purpose: String): Double =
        ((Dsp.seedFor("NACRE", MODEL_VERSION, seed, performer, id, purpose).toLong() and 0xffffffffL) /
            4294967295.0) * 2.0 - 1.0

    private data class Field(val depth: Double, val hz: Double, val phase: Double) {
        /** Related components at fixed positions; no independent, per-sample randomness. */
        fun at(seconds: Double, position: Double): Double {
            val p = TWO_PI * hz * seconds + phase
            return (.78 * sin(p + position) + .22 * sin(2.0 * p - .63 * position + .4)).coerceIn(-1.0, 1.0)
        }
    }

    private fun field(current: Double, seed: Long): Field = Field(
        depth = 1.0 - exp(-3.2 * current),
        hz = if (current == 0.0) 0.0 else .28 + 2.1 * current.pow(1.3),
        phase = .4 + .5 * variation(seed, "water", 0, "phase"),
    )

    private fun schedule(m: Map<String, Float>, f0: Double, velocity: Float, seed: Long,
                         field: Field, options: Options): List<Event> {
        val touch = m.getValue("TOUCH").toDouble()
        val duet = m.getValue("DUET").toDouble()
        // A continuous answering offset changes leader near the high end. Direction is chosen
        // once for a whole gesture; no in-progress sequence is ever reversed.
        val offset = .006 + .22 * duet.pow(1.45) - .42 * smooth((duet - .72) / .28)
        val bassOrigin = .018 + if (offset < 0.0) -offset else 0.0
        val upperOrigin = .018 + if (offset > 0.0) offset else 0.0
        val spacing = (.008 + .052 * duet) * (1.0 - .14 * touch)
        val events = ArrayList<Event>(8)
        for (harp in 0..1) {
            val performer = if (harp == 0) "bass" else "upper"
            val descending = harp == 1 && duet >= .65
            for (order in 0 until STRINGS_PER_HARP) {
                val id = if (descending) STRINGS_PER_HARP - 1 - order else order
                val time = (if (harp == 0) bassOrigin else upperOrigin) + order * spacing *
                    (if (harp == 0) 1.04 else .94) +
                    .0008 * variation(seed, performer, id, "timing")
                val participation = if (id == 0) 1.0 else
                    (.12 + .88 * touch.pow(.65)) * (if (harp == 0) .48 else .58).pow(id)
                val effort = (.22 + .78 * touch.pow(.65)) * velocity *
                    (.95 + .05 * variation(seed, performer, id, "effort"))
                val water = 1.0 - .14 * field.depth * (.5 + .5 * field.at(time, harp * 1.7))
                val muted = if (harp == 0) options.muteBassPlucks else options.muteUpperPlucks
                events += Event(performer, id, time, f0 * (if (harp == 0) 1.0 else 2.0) * RATIOS[id],
                    if (muted) 0.0 else effort * participation * water * (if (harp == 0) 1.0 else .72),
                    if (descending) "descending" else "ascending")
            }
        }
        return events.sortedWith(compareBy<Event> { it.timeSeconds }.thenBy { it.performer }.thenBy { it.stringId })
    }

    /** Static RBJ constant-peak bandpass in Double: even long sympathetic modes keep precision. */
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

    /** Fixed one-pole absorption; no coefficient recalculation or moving poles in the sample loop. */
    private class LowPass(hz: Double, rate: Int) {
        private val a = 1.0 - exp(-TWO_PI * hz / rate)
        private var state = 0.0
        fun next(x: Double): Double { state += a * (x - state); return state }
    }

    private class Delay(seconds: Double, rate: Int) {
        private val line = DoubleArray((seconds * rate).roundToInt().coerceAtLeast(1))
        private var head = 0
        fun next(x: Double): Double {
            val y = line[head]; line[head] = x
            head++; if (head == line.size) head = 0
            return y
        }
    }

    private class Venue(rate: Int) {
        private val coralDelays = arrayOf(.011, .023, .037, .061).map { Delay(it, rate) }
        private val coralAbsorption = arrayOf(4200.0, 3400.0, 2700.0, 2100.0).map { LowPass(it, rate) }
        private val cavities = arrayOf(510.0, 910.0, 1510.0).mapIndexed { i, hz -> Mode(hz, 5.0 + i, rate) }
        private val weedDelays = arrayOf(.007, .031, .079, .143).map { Delay(it, rate) }
        private val weedAbsorption = arrayOf(2400.0, 3200.0, 1300.0, 1900.0).map { LowPass(it, rate) }
        private val bassWeights = doubleArrayOf(.72, .30, .58, .22)
        private val upperWeights = doubleArrayOf(.25, .66, .26, .58)
        var coral = 0.0; private set
        var seaweed = 0.0; private set

        fun next(bass: Double, upper: Double, sympathetic: Double, flow: DoubleArray, depth: Double) {
            var close = 0.0
            var open = 0.0
            for (i in 0..3) {
                // Nonnegative injection weights sum to at most one, and the final path weights
                // sum to one. No delay writes contain any returning venue output.
                val injection = bassWeights[i] * bass + upperWeights[i] * upper + .03 * sympathetic
                close += .25 * coralAbsorption[i].next(coralDelays[i].next(injection)) *
                    (1.0 - .20 * depth * (.5 + .5 * flow[i]))
                val aperture = 1.0 - depth * (.18 + .72 * (.5 + .5 * flow[(i + 1) % 4]))
                open += .25 * weedAbsorption[i].next(weedDelays[i].next(injection)) * aperture
            }
            var cavity = 0.0
            for (mode in cavities) cavity += mode.next(close) / cavities.size
            coral = .70 * close + .30 * cavity
            // There is intentionally no diffuse late tail in the open field.
            seaweed = open
        }
    }

    private data class StringState(val event: Event, val start: Int, val burst: FloatArray,
                                   val loop: Strings.Loop, val baseFeedback: Double, val t60: Double)

    private fun play(voice: NacreVoice, macros: Map<String, Float>, midi: Int, velocity: Float,
                     seed: Long, options: Options, record: Boolean): Probe {
        require(midi in MIN_MIDI..MAX_MIDI) { "NACRE model 1 supports MIDI $MIN_MIDI..$MAX_MIDI, got $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "NACRE velocity must be finite and in 0..1, got $velocity" }
        val m = settled(voice, macros)
        stopIfInterrupted()
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val frames = (DURATION_SECONDS * rate).roundToInt()
        val f0 = Keys.midiHz(midi).toDouble()
        val touch = m.getValue("TOUCH").toDouble()
        val bloom = m.getValue("BLOOM").toDouble()
        val venueBlend = m.getValue("VENUE").toDouble()
        val requestedField = field(m.getValue("CURRENT").toDouble(), seed)
        val water = if (options.postCurrentOnly) field(0.0, seed) else requestedField
        val events = schedule(m, f0, velocity, seed, water, options)
        val states = events.map { event ->
            val bass = event.performer == "bass"
            val hz = event.frequencyHz
            val brightness = if (bass) 6.0 + 7.0 * touch else 10.0 + 11.0 * touch
            val loopHz = (hz * brightness).coerceAtMost(if (bass) 7000.0 else 10500.0).toFloat()
            val t60 = (if (bass) 3.1 + 1.1 * bloom else 2.2 + .9 * bloom) * .76.pow(event.stringId)
            val feedback = 10.0.pow(-3.0 / (t60 * hz)).coerceAtMost(.9995)
            val tuning = Strings.tune(hz.toFloat(), loopHz, rate)
            val loop = Strings.Loop(tuning.n, tuning.a, feedback.toFloat(), loopHz, rate)
            val pickHz = (if (bass) 2200.0 + 5700.0 * touch else 4300.0 + 8200.0 * touch) * (.7 + .3 * velocity)
            val pickPosition = (if (bass) .18 else .115) + .025 * event.stringId +
                .012 * variation(seed, event.performer, event.stringId, "pick")
            val burst = Strings.pluckExciter(tuning.n, hz.toFloat(), pickHz.toFloat(), pickPosition.toFloat(),
                Dsp.seedFor("NACRE", MODEL_VERSION, seed, event.performer, event.stringId, "pluck"), rate, frames)
            var peak = 0f
            for (sample in burst) peak = maxOf(peak, abs(sample))
            // A finite displacement component protects f0 through the intentionally overlapping
            // octave voicings. It is part of each exciter, never a sustained oscillator layer.
            // Upper high strings can lead a descending gesture before either root speaks. Give
            // those strings a clear pitched onset rather than an isolated broadband noise click;
            // the brighter loop/pick spectra still distinguish their touch from the bass harp.
            val foundation = when {
                event.stringId == 0 -> .72
                !bass -> .80 - .12 * touch
                else -> .48
            }
            val pickNoise = LowPass(if (bass) pickHz else minOf(pickHz, 2.6 * hz), rate)
            val edgeFrames = minOf((.00035 * rate).roundToInt(), (burst.size - 1) / 3).coerceAtLeast(1)
            for (i in burst.indices) {
                val noise = if (peak > 1e-9f) burst[i] / peak else 0f
                val displacement = sin(TWO_PI * hz * i / rate)
                // Both endpoints and their first derivatives reach zero. Window the finite
                // exciter itself, not the later string output, so the pitched waveguide tail is
                // retained and an excitation edge cannot become a false percussive transient.
                val envelope = smooth(i.toDouble() / edgeFrames) *
                    smooth((burst.lastIndex - i).toDouble() / edgeFrames)
                burst[i] = (.28 * event.strength * envelope *
                    ((1.0 - foundation) * pickNoise.next(noise.toDouble()) + foundation * displacement)).toFloat()
            }
            StringState(event, (event.timeSeconds * rate).roundToInt(), burst, loop, feedback, t60)
        }
        val bassBody = arrayOf(138.0, 261.0, 427.0).mapIndexed { i, hz -> Mode(hz, 3.0 + i, rate) }
        val upperBody = arrayOf(397.0, 743.0, 1261.0).mapIndexed { i, hz -> Mode(hz, 2.8 + i, rate) }
        val sympathetic = Array(2) { harp -> Array(4) { id ->
            val hz = f0 * (if (harp == 0) 1.0 else 2.0) * RATIOS[id]
            val t60 = (.85 + 2.25 * bloom) * .85.pow(id)
            Mode(hz, PI * hz * t60 / ln(1000.0), rate)
        } }
        val venue = Venue(rate)
        val out = FloatArray(frames)
        val stems = if (record) Array(4) { FloatArray(frames) } else null
        val flows = DoubleArray(4)
        var bassFlow = 0.0
        var upperFlow = 0.0
        var previousBassBody = 0.0
        var previousUpperBody = 0.0
        var crossBass = 1.0
        var crossUpper = 1.0
        var internalPeak = 0.0
        var symmetricPeak = 0.0
        var venuePeak = 0.0
        val sympatheticGain = .10 + 2.0 * bloom.pow(.72)
        val venueGain = .10 + .76 * bloom.pow(.70)
        // The post-only ablation adds moving pickup sound after all generation. It cannot alter
        // any string/sympathetic state, and is deliberately distinct from the production model.
        val postDelay = if (options.postCurrentOnly) Delay(.009, rate) else null
        for (i in 0 until frames) {
            if (i % 32768 == 0) stopIfInterrupted()
            if (i % CONTROL_BLOCK == 0) {
                val time = i.toDouble() / rate
                bassFlow = water.at(time, .2)
                upperFlow = water.at(time, 1.9)
                for (k in flows.indices) flows[k] = water.at(time, .7 + 1.25 * k)
                crossBass = 1.0 - .72 * water.depth * (.5 + .5 * bassFlow)
                crossUpper = 1.0 - .72 * water.depth * (.5 + .5 * upperFlow)
                for (state in states) {
                    val flow = if (state.event.performer == "bass") bassFlow else upperFlow
                    val loading = .35 + .65 * (.5 + .5 * flow)
                    val amount = water.depth * (if (state.event.stringId == 0) .28 else .90)
                    // The extra per-period loss is always nonnegative. Keeping all phase-bearing
                    // coefficients fixed avoids detuning the existing waveguide tuning budget.
                    val scale = exp(-amount * loading * ln(1000.0) / (state.t60 * state.event.frequencyHz))
                    state.loop.gain(scale.toFloat().coerceIn(0f, 1f))
                }
            }
            var bass = 0.0
            var upper = 0.0
            for (state in states) {
                val age = i - state.start
                val excitation = if (age in state.burst.indices) state.burst[age] else 0f
                val sample = state.loop.next(excitation).toDouble()
                internalPeak = maxOf(internalPeak, abs(sample))
                if (state.event.performer == "bass") bass += sample else upper += sample
            }
            var bBody = 0.0
            var uBody = 0.0
            for (mode in bassBody) bBody += mode.next(bass) / bassBody.size
            for (mode in upperBody) uBody += mode.next(upper) / upperBody.size
            val cross = if (options.disableCrossHarp) 0.0 else .20 + .48 * bloom
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
            symmetricPeak = maxOf(symmetricPeak, abs(sym))
            bass = .92 * bass + .26 * bBody
            upper = .92 * upper + .22 * uBody
            sym *= sympatheticGain
            venue.next(bass, upper, sym, flows, water.depth)
            var wet = if (options.bypassVenue) 0.0 else
                venueGain * ((1.0 - venueBlend) * venue.coral + venueBlend * venue.seaweed)
            if (postDelay != null) {
                val delayed = postDelay.next(bass + upper + sym + wet)
                wet += .32 * requestedField.depth *
                    (.5 + .5 * requestedField.at(i.toDouble() / rate, .9)) * delayed
            }
            venuePeak = maxOf(venuePeak, abs(wet))
            val y = bass + upper + sym + wet
            check(y.isFinite() && internalPeak.isFinite() && symmetricPeak.isFinite()) { "NACRE nonfinite synthesis state at frame $i" }
            out[i] = y.toFloat()
            if (stems != null) { stems[0][i] = bass.toFloat(); stems[1][i] = upper.toFloat();
                stems[2][i] = sym.toFloat(); stems[3][i] = wet.toFloat() }
        }
        stopIfInterrupted()
        val raw = condition(out)
        val leveled = raw.copyOf()
        Dsp.levelTo(leveled, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        val names = listOf("bass", "upper", "sympathetic", "venue")
        val conditionedStems = if (stems == null) emptyMap() else names.indices.associate { names[it] to condition(stems[it]) }
        var tailEnergy = 0.0
        val tailFrames = Dsp.RATE / 2
        for (i in raw.size - tailFrames until raw.size) tailEnergy += raw[i].toDouble() * raw[i]
        val diagnostics = linkedMapOf(
            "modelVersion" to MODEL_VERSION.toDouble(),
            "durationSeconds" to DURATION_SECONDS.toDouble(),
            "rootHz" to f0,
            "maxBaseLoopFeedback" to states.maxOf { it.baseFeedback },
            "maxCurrentLoopGainScale" to 1.0,
            "maxCrossHarpSend" to (if (options.disableCrossHarp) 0.0 else .20 + .48 * bloom),
            "venueFeedbackGain" to 0.0,
            "venueBlendWeightSum" to 1.0,
            "currentDepth" to water.depth,
            "currentRateHz" to water.hz,
            "internalStringPeak" to internalPeak,
            "statePeak" to maxOf(internalPeak, symmetricPeak, venuePeak),
            "loopGainMax" to states.maxOf { it.baseFeedback },
            "tailRms" to sqrt(tailEnergy / tailFrames),
            "sympatheticPeakBeforeMix" to symmetricPeak,
            "venuePeakBeforeLevel" to venuePeak,
            "rawPeak" to raw.maxOf { abs(it).toDouble() },
        )
        return Probe(Snip(leveled, channels = 1, sampleRate = Dsp.RATE), raw, conditionedStems, events, diagnostics)
    }

    /** Identical linear conditioning for the mix and stems; leveling is exclusively after it. */
    private fun condition(oversampled: FloatArray): FloatArray {
        stopIfInterrupted()
        Tide.bandLimit(oversampled, Dsp.RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(oversampled, Dsp.RATE)
        val dc = LowPass(3.0, Dsp.RATE)
        for (i in out.indices) out[i] = (out[i] - dc.next(out[i].toDouble())).toFloat()
        Dsp.fadeTail(out, ms = 50f)
        return out
    }
}
