package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/** Original synthesized recordings; these names do not imply historical or acoustic tapes. */
enum class SpoolVoice { STRING, CHOIR, FLUTE, BRASS, PALIMPSEST, CUT }

/**
 * Three finite recordings share a powered motor, but carry their own load, slip and return travel.
 * The pickup reads moving coordinates in the recordings, including overlapping reordered windows.
 * A delayed return pickup writes only the locations traversed by a neighboring write head.
 *
 * A one-shot has one forward pass, its return, and at most one quieter forward encounter with the
 * inherited trace. HOLD deliberately uses the specification's frozen-snapshot fallback: one finite
 * deterministic preroll makes the overlay; exported cycles never write. Each cycle includes forward
 * drive and an audible return. There is no persistent memory between independent renders.
 */
object Spool {
    const val MODEL_VERSION = 1
    const val ROOT_MIDI = 48
    const val TUNE_SEMITONES = 24
    const val MIDI_MIN = ROOT_MIDI
    const val MIDI_MAX = ROOT_MIDI + TUNE_SEMITONES
    const val DEFAULT_MIDI = 60
    const val LOOP_THRESHOLD = .999f
    const val MAX_REVERSE_RATE = 3.2f
    const val MAX_FORWARD_RATE = 1.65f
    const val FEEDBACK_CEILING = .55f
    const val OVERLAY_CEILING = .65f
    const val GENERATION_RETENTION = .40f
    const val MAX_DURATION_SECONDS = 8f
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val CONTROL_STRIDE = 88
    private const val SOURCE_BANDWIDTH = 3_400f
    private const val CONTACT_RAMP = .035
    private const val COAST_SECONDS = .025
    private const val RETURN_ONSET_SECONDS = .035
    private const val PHASE_HANDOFF_SECONDS = .060
    private const val GEOMETRY_FADE_SECONDS = .200
    private const val REENCOUNTER_RAMP_SECONDS = .060
    private const val LOOP_REST_SECONDS = .025
    private const val REST_COORDINATE_SECONDS = .012
    private const val SPLICE_OVERLAP = .032
    private data class ReadKernel(val radius: Int, val weights: FloatArray)
    // Positive normalized Gaussian kernels: l-infinity gain <=1, no box-filter rebound
    // lobes. Sigma scales with complete coordinate speed, not just motor speed. The
    // half-power output bandwidth stays near 15.5kHz; source components able to alias
    // from internal Nyquist into the output band receive strong exponential rejection.
    private val overlayKernels: Array<ReadKernel> = Array(81) { bin ->
        val sigma = max(.70, bin * .125 * 1.50)
        val radius = kotlin.math.ceil(4 * sigma).toInt()
        val w = FloatArray(2 * radius + 1) { j -> exp(-.5 * ((j - radius) / sigma).pow(2)).toFloat() }
        val sum = w.sum()
        for (j in w.indices) w[j] /= sum
        ReadKernel(radius, w)
    }

    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: SpoolVoice): Int = ROOT_MIDI
    fun midiFor(voice: SpoolVoice, tune: Float): Int = rootMidi(voice) + Math.round(finite(tune, .5f) * TUNE_SEMITONES)
    fun frequencyFor(voice: SpoolVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))
    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= LOOP_THRESHOLD

    fun macrosFor(voice: SpoolVoice): List<MacroSpec> {
        val d = when (voice) {
            SpoolVoice.STRING -> floatArrayOf(.15f, .10f, .20f, .15f, .05f)
            SpoolVoice.CHOIR -> floatArrayOf(.25f, .15f, .30f, .20f, .05f)
            SpoolVoice.FLUTE -> floatArrayOf(.20f, .10f, .25f, .15f, .10f)
            SpoolVoice.BRASS -> floatArrayOf(.20f, .15f, .20f, .15f, .10f)
            SpoolVoice.PALIMPSEST -> floatArrayOf(.65f, .40f, .65f, .70f, .25f)
            SpoolVoice.CUT -> floatArrayOf(.55f, .35f, .50f, .45f, .75f)
        }
        return listOf(MacroSpec("TUNE", .5f, neutral = .5f),
            MacroSpec("HEAD", d[0], neutral = .20f), MacroSpec("STRAIN", d[1], neutral = .10f),
            MacroSpec("RETURN", d[2], neutral = .20f), MacroSpec("IMPRINT", d[3], neutral = 0f),
            MacroSpec("SPLICE", d[4], neutral = 0f), MacroSpec("HOLD", 0f, neutral = 0f))
    }

    fun defaults(voice: SpoolVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    private fun finite(x: Float, fallback: Float): Float = if (x.isFinite()) x.coerceIn(0f, 1f) else fallback
    internal fun settled(macros: Map<String, Float>, voice: SpoolVoice): Map<String, Float> =
        defaults(voice).mapValues { (name, default) -> finite(macros[name] ?: default, default) }

    fun scramble(voice: SpoolVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val d = defaults(voice)
        return Dsp.scrambleNear(d + (near?.macros?.filterKeys { it in d } ?: emptyMap()), finite(temperature, .35f), random)
            .toMutableMap().also { it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f) }
    }

    // Explicit melodic recipe classification; a finite long WAV can separately classify LOOP.
    fun drumClassFor(voice: SpoolVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(settled(macros, voice).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    data class TransportFrame(
        val timeSeconds: Float, val motorRate: Float, val positions: List<Float>, val rates: List<Float>,
        val headCoordinates: List<Float>, val springEnergy: Float, val motorWork: Float, val frictionLoss: Float,
    )
    data class ImprintEvent(val source: Int, val destination: Int, val timeSeconds: Float,
                            val coordinateSeconds: Float, val gain: Float)
    data class Diagnostics(
        val rate: Int, val gateSeconds: Float, val durationSeconds: Float, val returnEndSeconds: Float,
        val reengageSeconds: Float, val sourceHashes: List<Int>, val sourcePeak: Float, val overlayPeak: Float,
        val feedbackBound: Float, val writeCount: Long, val firstWriteSeconds: Float?,
        val firstOverlayReadSeconds: Float?, val maxForwardRate: Float, val maxReverseRate: Float,
        val returnTravel: List<Float>, val sourceFrames: Int, val allocatedBytes: Long,
        val writesFrozen: Boolean, val spliceCount: Int, val trace: List<TransportFrame>,
        val imprintEvents: List<ImprintEvent>, val writePassLimit: Int = 1,
        val completeGenerationBound: Float = GENERATION_RETENTION,
    )
    data class ProbeResult(val samples: FloatArray, val diagnostics: Diagnostics,
                           val previousCycle: FloatArray? = null, val seamError: Double? = null)
    internal data class Played(val raw: FloatArray, val diagnostics: Diagnostics)

    private class Material(val originals: Array<FloatArray>, val overlays: Array<FloatArray>,
                           val strips: List<SpoolStrip>, val hashes: List<Int>, val peak: Float)

    /** A finite performance with an evolving articulation, rather than a repeated static waveform. */
    // SynthScreen previews run in runInterruptible: a newer request interrupts this thread,
    // and every long stage polls it so a stale render stops instead of finishing unwanted.
    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("SPOOL render no longer wanted")
    }

    private fun material(voice: SpoolVoice, m: Map<String, Float>, machine: SpoolMachine, velocity: Float): Material {
        val strips = if (machine.roster.isEmpty()) SpoolMachine.rosterFor(voice) else machine.roster
        val n = (machine.sourceSeconds * INTERNAL_RATE).toInt().coerceAtLeast(64)
        val hz = frequencyFor(voice, m.getValue("TUNE")).toDouble()
        var peak = 0f
        val originals = Array(3) { strip ->
            val s = strips[strip]
            val random = Random(Dsp.seedFor("SPOOL", voice.name, midiFor(voice, m.getValue("TUNE")), machine.seed, strip))
            val harmonics = min(24, floor(SOURCE_BANDWIDTH / hz).toInt()).coerceAtLeast(1)
            val phase = DoubleArray(harmonics) { if (it == 0) .13 * strip else random.nextDouble() * 2 * PI }
            val oscSin = DoubleArray(harmonics) { sin(phase[it]) }
            val oscCos = DoubleArray(harmonics) { cos(phase[it]) }
            val stepSin = DoubleArray(harmonics)
            val stepCos = DoubleArray(harmonics)
            val gain = DoubleArray(harmonics)
            val family = s.family
            val attack = when (family) {
                SpoolSourceFamily.STRING -> .085; SpoolSourceFamily.CHOIR -> .145
                SpoolSourceFamily.FLUTE -> .065; SpoolSourceFamily.BRASS -> .035
            }
            var doubleSin = sin(.8 + strip)
            var doubleCos = cos(.8 + strip)
            val doubleStep = 2 * PI * hz * 2.0.pow((if (strip % 2 == 0) 1.7 else -1.7) / 1200) / INTERNAL_RATE
            val ds = sin(doubleStep); val dc = cos(doubleStep)
            val out = FloatArray(n)
            var breath = 0.0
            for (i in out.indices) {
                if (i % 8192 == 0) stopIfUnwanted()
                val t = i.toDouble() / INTERNAL_RATE
                if (i % 64 == 0) {
                    // Tiny recorded ensemble movement: below a cent at the ordinary setting.
                    val vibrato = 1 + .00038 * sin(2 * PI * (4.3 + strip * .17) * t + strip)
                    val evolution = .68 + .18 * sin(2 * PI * .43 * t + strip * .7) +
                        .14 * exp(-t / .24) * (.45 + .55 * velocity)
                    for (h in 1..harmonics) {
                        val step = 2 * PI * hz * h * vibrato / INTERNAL_RATE
                        stepSin[h - 1] = sin(step); stepCos[h - 1] = cos(step)
                        val f = hz * h
                        val a = when (family) {
                            SpoolSourceFamily.STRING -> h.toDouble().pow(-1.30) * exp(-f / (1550 + 1500 * evolution))
                            SpoolSourceFamily.CHOIR -> if (h == 1) .72 else (.12 +
                                .90 * exp(-((f - 740) / 260).pow(2)) +
                                .55 * exp(-((f - 1160 - 100 * sin(t * 1.2)) / 340).pow(2)) +
                                .20 * exp(-((f - 2480) / 400).pow(2))) / h.toDouble().pow(.80)
                            SpoolSourceFamily.FLUTE -> when (h) { 1 -> 1.0; 2 -> .16 + .09 * evolution; 3 -> .055; else -> .025 / h }
                            SpoolSourceFamily.BRASS -> h.toDouble().pow(-.98) * exp(-f / (1100 + 1700 * evolution))
                        }
                        gain[h - 1] = a * if (h == 1) 1.0 else (.65 + .35 * velocity)
                    }
                }
                var y = 0.0
                for (h in 0 until harmonics) {
                    val old = oscSin[h]
                    oscSin[h] = old * stepCos[h] + oscCos[h] * stepSin[h]
                    oscCos[h] = oscCos[h] * stepCos[h] - old * stepSin[h]
                    y += oscSin[h] * gain[h]
                }
                val old = doubleSin
                doubleSin = old * dc + doubleCos * ds
                doubleCos = doubleCos * dc - old * ds
                y = y * (1 - .18 * s.doubling) + .18 * s.doubling * doubleSin
                // Original airflow is restrained, bandwidth-limited and never the voice's identity.
                breath += .021 * (random.nextDouble(-1.0, 1.0) - breath)
                if (family == SpoolSourceFamily.FLUTE) y += .025 * breath
                val env = smooth(t / attack) * smooth((machine.sourceSeconds - t) / .16)
                val gesture = (.90 + .07 * sin(2 * PI * .75 * t + strip * .45)) *
                    (1 - .09 * exp(-((t - .38 - strip * .09) / .13).pow(2)))
                out[i] = (.36 * y * env * gesture * (.60 + .40 * velocity)).toFloat()
            }
            val sourceFilters = Array(4) { Dsp.OnePole(INTERNAL_RATE) }
            for (j in out.indices) {
                if (j % 8192 == 0) stopIfUnwanted()
                var value = out[j]
                for (filter in sourceFilters) value = filter.lp(value, SOURCE_BANDWIDTH)
                out[j] = value
                peak = max(peak, abs(out[j]))
            }
            out
        }
        return Material(originals, Array(3) { FloatArray(n) }, strips, originals.map { it.contentHashCode() }, peak)
    }

    private fun smooth(x: Double): Double { val u = x.coerceIn(0.0, 1.0); return u * u * (3 - 2 * u) }

    /**
     * A convex interpolator (l-infinity norm exactly <=1), including reverse and stationary reads.
     * Original synthesis is limited to 3.4kHz. Its positive three-tap smoothing varies
     * continuously with read rate; above rate 4.0 it blends into the rate-dependent Gaussian
     * pickup, fully active by 4.4. Mutable overlays always use that Gaussian path. The full head
     * derivative includes the geometry taper and is bounded below 8.01, within the rate-10
     * kernel bank. Interpolation and these positive kernels have no norm gain; the overlay
     * projection and saturation are 1-Lipschitz. Thus the inherited-generation operator is bounded
     * by the largest routing row sum times sqrt(IMPRINT), <= .55 in l-infinity. Retaining an existing
     * cell is storage, not a new feedback generation; one-shots permit one finite write pass only. Causal descendants are bounded by
     * passFrames/delay and each inherited path contracts; a generation-start .40 retention
     * bounds the complete pass map (including retained cells), as shown in MachinePass.run.
     */
    private fun read(buffer: FloatArray, coordinate: Double, speed: Double, overlay: Boolean = false): Float {
        val at = (coordinate * INTERNAL_RATE).coerceIn(0.0, buffer.size - 2.000001)
        val i = at.toInt(); val f = (at - i).toFloat()
        fun linear(j: Int): Float {
            val k = j.coerceIn(0, buffer.size - 2)
            return buffer[k] * (1 - f) + buffer[k + 1] * f
        }
        fun gaussian(): Float {
            val binPosition = (abs(speed) / .125).coerceIn(0.0, overlayKernels.lastIndex.toDouble())
            val low = binPosition.toInt()
            val high = min(low + 1, overlayKernels.lastIndex)
            val blend = (binPosition - low).toFloat()
            fun kernelValue(kernel: ReadKernel): Float {
                var y = 0f
                for (j in kernel.weights.indices) y += kernel.weights[j] * linear(i + j - kernel.radius)
                return y
            }
            val first = kernelValue(overlayKernels[low])
            return if (blend <= 0) first else first * (1 - blend) + kernelValue(overlayKernels[high]) * blend
        }
        if (overlay || abs(speed) >= 4.4) return gaussian()
        val a = linear(i)
        if (abs(speed) <= 1.0) return a
        val radiusPosition = abs(speed).coerceIn(1.0, 4.0)
        val radius = radiusPosition.toInt()
        val blend = (radiusPosition - radius).toFloat()
        fun triple(r: Int) = .50f * a + .25f * linear(i - r) + .25f * linear(i + r)
        val first = triple(radius)
        val value = if (blend <= 0) first else first * (1 - blend) + triple(radius + 1) * blend
        return if (abs(speed) <= 4.0) value else {
            val g = smooth((abs(speed) - 4.0) / .4).toFloat()
            value * (1 - g) + gaussian() * g
        }
    }

    private data class Pickup(val first: Double, val second: Double, val blend: Double)

    /** Two matched read windows, not a pointer jump, across each of at most four cuts. */
    private fun pickup(coordinate: Double, support: Double, amount: Double, schedule: List<SpoolSplice>, sourceLength: Double): Pickup {
        fun folded(x: Double): Double {
            // Smooth reflection near ends preserves position and gain without hard pointer clipping.
            if (support <= 0) return 0.0
            val period = 2 * support
            var p = x % period
            if (p < 0) p += period
            return if (p <= support) p else period - p
        }
        if (amount <= 0 || schedule.isEmpty()) return Pickup(folded(coordinate), folded(coordinate), 0.0)
        val active = schedule.take((1 + 3 * amount).toInt().coerceIn(1, 4))
        var previous = 0.0
        for (cut in active) {
            // The serialized schedule always refers to immutable recorded time, on both
            // journeys. Only valid read support shrinks on return. Source >=2s and
            // boundary spacing >=.06 guarantee >=120ms between cuts, greater than
            // the two 32ms overlap windows even for the shortest gate.
            val boundary = cut.boundary * sourceLength
            val displaced = cut.displacement * sourceLength * amount
            if (coordinate < boundary - SPLICE_OVERLAP) break
            if (coordinate <= boundary + SPLICE_OVERLAP) {
                val b = smooth((coordinate - boundary + SPLICE_OVERLAP) / (2 * SPLICE_OVERLAP))
                return Pickup(folded(coordinate + previous), folded(coordinate + displaced), b)
            }
            previous = displaced
        }
        val p = folded(coordinate + previous)
        return Pickup(p, p, 0.0)
    }

    private fun tap(buffer: FloatArray, pickup: Pickup, speed: Double, overlay: Boolean = false): Float =
        if (pickup.blend <= 0) read(buffer, pickup.first, speed, overlay)
        else if (pickup.blend >= 1) read(buffer, pickup.second, speed, overlay)
        else (read(buffer, pickup.first, speed, overlay) * (1 - pickup.blend) + read(buffer, pickup.second, speed, overlay) * pickup.blend).toFloat()

    private class MachinePass(
        private val voice: SpoolVoice, private val m: Map<String, Float>, private val machine: SpoolMachine,
        private val velocity: Float, private val material: Material, private val writes: Boolean,
        private val frozenHead: Boolean, private val record: Boolean, private val cycle: Boolean,
    ) {
        private val strain = m.getValue("STRAIN").toDouble()
        private val head = m.getValue("HEAD").toDouble()
        private val splice = m.getValue("SPLICE").toDouble()
        private val imprint = sqrt(m.getValue("IMPRINT").toDouble())
        private val answer = m.getValue("RETURN").toDouble()
        private val sourceLength = (material.originals[0].size - 3.0) / INTERNAL_RATE
        private val hold = if (cycle) 0.0 else m.getValue("HOLD").coerceAtMost(.998f).toDouble()
        val gate = min(machine.gateSeconds * (1 + .45 * hold), sourceLength - .08).coerceAtLeast(.10)
        private val positions = DoubleArray(3)
        private val rates = DoubleArray(3) { 1.0 }
        private val driveRates = DoubleArray(3) { 1.0 }
        private val travel = DoubleArray(3)
        private val returnDurations = DoubleArray(3)
        private val returnStops = DoubleArray(3)
        private val coords = DoubleArray(3)
        private var motor = 1.0
        private var motorWork = 0.0
        private var frictionLoss = 0.0
        private var maxForward = 0f
        private var maxReverse = 0f
        private var writeCount = 0L
        private var firstWrite: Float? = null
        private var firstOverlayRead: Float? = null
        private val trace = ArrayList<TransportFrame>()
        private val events = ArrayList<ImprintEvent>()
        private val delay = (machine.writeDelaySamples * Dsp.OVERSAMPLE).coerceAtLeast(Dsp.OVERSAMPLE)
        private val pending = Array(3) { FloatArray(delay) }
        private val pendingPositions = Array(3) { DoubleArray(delay) { -1.0 } }
        private val writeFilter = DoubleArray(3)
        private var returnEnd = gate + machine.returnSeconds
        private var rereadStart = returnEnd
        private val rereadLength = if (!cycle && machine.reengage) min(.90, gate * .70) else 0.0
        private val contact = DoubleArray(3) { material.strips[it].contact * (.52 + .48 * velocity) }
        private val nominalDrag = (0..2).sumOf { contact[it] * .045 * material.strips[it].drag }
        private var overlayAvailable = material.overlays.any { b -> b.any { it != 0f } }
        private val phase = DoubleArray(3) { Random(Dsp.seedFor("SPOOL-TRANSPORT", machine.seed, it)).nextDouble() * 2 * PI }
        private var released = false

        private fun forward(t: Double, dt: Double) {
            var dragLoad = 0.0
            var springLoad = 0.0
            for (k in 0..2) {
                val s = material.strips[k]
                dragLoad += contact[k] * .045 * s.drag
                springLoad += contact[k] * .055 * strain * s.tension * positions[k]
            }
            val load = dragLoad + springLoad
            // Nominal rest drag is calibrated once. Travel-dependent spring work is an actual
            // shared load: compensation is NOT instantaneous, so a loaded neighbor changes the
            // motor and hence every strip even before finite torque clips. Regulation supplies
            // recovery work; release friction and spring decay never create powered work.
            val disturbance = strain.pow(2) * .11 * sin(2 * PI * 1.15 * t)
            val torque = (nominalDrag + (1 + disturbance - motor) * (9 - 5 * strain))
                .coerceIn(-machine.motorTorque.toDouble(), machine.motorTorque.toDouble())
            motor = (motor + (torque - load) * dt).coerceIn(.58, 1.22)
            motorWork += max(0.0, torque * motor) * dt
            frictionLoss += max(0.0, dragLoad * motor) * dt
            for (k in 0..2) {
                val s = material.strips[k]
                val differential = (s.tension - 1) * .11 + (s.drag - 1) * .08
                val slip = strain.pow(2) * (.19 * sin(2 * PI * (1.4 + .21 * k) * t + phase[k]) - differential)
                val target = (motor + slip).coerceIn(.40, MAX_FORWARD_RATE.toDouble())
                driveRates[k] += (target - driveRates[k]) * min(1.0, dt * (35 - 24 * strain))
            }
        }

        private fun release() {
            released = true
            for (k in 0..2) {
                // A gentle Hermite return p/travel=1-1.5u+.5u^3 starts around the
                // forward pitch, then slows. Integrating a 35ms launch ramp keeps velocity
                // continuous at the physical turnaround; its normalization preserves travel.
                val shaped = machine.returnSeconds * (.28 + .72 * sqrt(travel[k] / 1.35))
                returnDurations[k] = max(shaped, 1.5 * travel[k] / MAX_REVERSE_RATE + RETURN_ONSET_SECONDS / 2)
                // Brake only when <=1% of actual travel and <=12ms of recording remain.
                // Residual spring energy is <=1e-4 of gate energy and is dissipated at rest.
                val rest = min(REST_COORDINATE_SECONDS, .01 * travel[k])
                var lo = 0.0
                var hi = 1.0
                repeat(40) {
                    val u = (lo + hi) / 2
                    val p = travel[k] * (1 - 1.5 * u + .5 * u * u * u)
                    if (p > rest) lo = u else hi = u
                }
                val effective = returnDurations[k] - RETURN_ONSET_SECONDS / 2
                returnStops[k] = ((lo + hi) / 2) * effective + RETURN_ONSET_SECONDS / 2
            }
            returnEnd = gate + (returnStops.maxOrNull() ?: 0.0)
            rereadStart = returnEnd
        }

        private fun returning(t: Double) {
            for (k in 0..2) {
                val elapsed = (t - gate).coerceAtLeast(0.0)
                if (elapsed >= returnStops[k]) { positions[k] = 0.0; rates[k] = 0.0; continue }
                val z = (elapsed / RETURN_ONSET_SECONDS).coerceIn(0.0, 1.0)
                val integrated = if (elapsed < RETURN_ONSET_SECONDS)
                    RETURN_ONSET_SECONDS * (z * z * z - .5 * z * z * z * z)
                    else elapsed - RETURN_ONSET_SECONDS / 2
                val effective = returnDurations[k] - RETURN_ONSET_SECONDS / 2
                val u = (integrated / effective).coerceIn(0.0, 1.0)
                positions[k] = travel[k] * (1 - 1.5 * u + .5 * u * u * u)
                rates[k] = -1.5 * travel[k] / effective * (1 - u * u) * smooth(z)
                maxReverse = max(maxReverse, abs(rates[k]).toFloat())
            }
        }

        private fun headWeights(t: Double): DoubleArray {
            val phase = if (frozenHead) 0.0 else 2 * PI * t / max(.4, gate + machine.returnSeconds)
            val q = .5 + .5 * sin(phase)
            val w = DoubleArray(3) { k -> material.strips[k].gain.toDouble() *
                if (k == 0) 1 - .46 * head else 1 + head * (1.2 + 1.8 * (if (k == 1) q else 1 - q)) }
            val sum = w.sum().coerceAtLeast(1e-12)
            for (k in w.indices) w[k] /= sum
            return w
        }

        fun run(): Played {
            val dt = 1.0 / INTERNAL_RATE
            // One O(N) retention step per inherited generation makes the COMPLETE map a
            // strict contraction, including untouched cells. For two initial tapes, delta starts
            // <= .40 ||deltaOld||_infinity. Each delayed local update is a convex combination
            // of its current cell and a routed donor with norm <= .55 of the current bound;
            // induction over commits keeps the bound <= .40 throughout. IMPRINT=0 bypasses
            // this step and all writes exactly. Snapshot loops do not invoke it while playing.
            if (writes && imprint > 0) for (overlay in material.overlays)
                for (j in overlay.indices) overlay[j] *= GENERATION_RETENTION
            // Allocate to a conservative finite upper bound, trim once after return travel is known.
            val upperReturn = max(machine.returnSeconds * (.28 + .72 * sqrt(sourceLength / 1.35)), 3 * sourceLength / MAX_REVERSE_RATE)
            val upperDuration = min(MAX_DURATION_SECONDS.toDouble(), gate + upperReturn + .06 + rereadLength + .23)
            val raw = FloatArray((upperDuration * INTERNAL_RATE).toInt())
            var used = raw.size
            var color = 0.0
            var dc = 0.0
            var passEntered = false
            val overlayReads = FloatArray(3)
            val heard = FloatArray(3)
            for (i in raw.indices) {
                if (i % 8192 == 0 && Thread.currentThread().isInterrupted)
                    throw java.util.concurrent.CancellationException("SPOOL render no longer wanted")
                val t = i.toDouble() / INTERNAL_RATE
                if (t >= gate && !released) release()
                val inReturn = released && t < returnEnd
                val inReread = !cycle && rereadLength > 0 && t >= rereadStart && t < rereadStart + rereadLength
                if (i % CONTROL_STRIDE == 0) {
                    if (t < gate) forward(t, CONTROL_STRIDE * dt)
                    else if (inReturn) returning(t)
                    else if (inReread) {
                        if (!passEntered) { positions.fill(0.0); rates.fill(1.0); driveRates.fill(1.0); motor = 1.0; passEntered = true }
                        forward(t - rereadStart, CONTROL_STRIDE * dt)
                    } else { positions.fill(0.0); rates.fill(0.0) }
                }
                // Rate regulation runs at the control rate; tape travel advances exactly once
                // per audio frame. The return law is analytic at every sample.
                if (inReturn) returning(t)
                else for (k in 0..2) {
                    rates[k] = if (t < gate) driveRates[k] * smooth((gate - t) / COAST_SECONDS)
                        else if (inReread && passEntered) driveRates[k] else 0.0
                    positions[k] = (positions[k] + rates[k] * dt).coerceIn(0.0, sourceLength)
                    if (t < gate) travel[k] = positions[k]
                    maxForward = max(maxForward, rates[k].toFloat())
                }
                val end = if (cycle) returnEnd + LOOP_REST_SECONDS else rereadStart + rereadLength + .20
                if (released && t >= end) { used = i; break }
                val weights = headWeights(t)
                val handoff = smooth((t - gate + PHASE_HANDOFF_SECONDS) / (2 * PHASE_HANDOFF_SECONDS))
                val fluidContact = 1 + (answer * .85 - 1) * handoff
                val muteContact = smooth((gate - t) / CONTACT_RAMP)
                // RETURN approaches exact mute continuously. Normal settings >=.1 retain
                // the complete fluid handoff; near zero it joins the original mute envelope.
                val returnContactMix = smooth(answer / .10)
                val turnContact = muteContact * (1 - returnContactMix) + fluidContact * returnContactMix
                val forwardContact = if (t < gate) smooth(t / CONTACT_RAMP) * turnContact
                    else if (inReread) .38 * smooth((t - rereadStart) / REENCOUNTER_RAMP_SECONDS) *
                        smooth((rereadStart + rereadLength - t) / REENCOUNTER_RAMP_SECONDS) else 0.0
                val geometryX = if (t < gate) (gate - t) / GEOMETRY_FADE_SECONDS
                    else if (inReturn) (t - gate) / GEOMETRY_FADE_SECONDS
                    else if (inReread) (t - rereadStart) / GEOMETRY_FADE_SECONDS else 0.0
                val geometry = smooth(geometryX)
                val geometryPrime = if (geometryX > 0 && geometryX < 1)
                    6 * geometryX * (1 - geometryX) / GEOMETRY_FADE_SECONDS * (if (t < gate) -1 else 1) else 0.0
                var y = 0.0
                for (k in 0..2) {
                    // A stopped pickup cannot send its masked reset-coordinate read into a
                    // neighbor's recording. The causal write filter receives zero and decays.
                    if (inReturn && t >= gate + returnStops[k]) {
                        coords[k] = 0.0
                        overlayReads[k] = 0f
                        heard[k] = 0f
                        continue
                    }
                    val support = if (inReturn) travel[k] else sourceLength
                    // Fixed-support geometry reaches neutral before the turnaround, so a new
                    // return support cannot jump the pickup. Its full derivative includes the
                    // 200ms taper, rather than pretending this virtual head motion is transport.
                    // Gaussian pickup filtering also protects serialized maximum-offset corners.
                    val motion = if (frozenHead) 0.0 else sin(PI * positions[k] / max(.12, support))
                    val offsetAmplitude = machine.headOffsets[k].toDouble().coerceIn(-.12 * support, .12 * support)
                    val offset = head * offsetAmplitude * motion * geometry
                    val coordinate = (positions[k] + offset).coerceIn(0.0, support)
                    coords[k] = coordinate
                    val p = pickup(coordinate, support, splice, machine.spliceSchedule, sourceLength)
                    val theta = PI * positions[k] / max(.12, support)
                    val speed = if (frozenHead) rates[k] else rates[k] *
                        (1 + head * offsetAmplitude * cos(theta) * PI / max(.12, support) * geometry) +
                        head * offsetAmplitude * motion * geometryPrime
                    // Convex dry/reordered pickup avoids deep low-SPLICE phase cancellation.
                    // Tapering it to dry at the turn also keeps support changes inaudible.
                    val wet = smooth(splice) * geometry
                    val originalDry = read(material.originals[k], coordinate, speed)
                    val original = if (wet > 0) (originalDry * (1 - wet) + tap(material.originals[k], p, speed) * wet).toFloat() else originalDry
                    val overlayDry = if (overlayAvailable) read(material.overlays[k], coordinate, speed, overlay = true) else 0f
                    val overlay = if (overlayAvailable && wet > 0) (overlayDry * (1 - wet) + tap(material.overlays[k], p, speed, overlay = true) * wet).toFloat() else overlayDry
                    overlayReads[k] = overlay
                    heard[k] = original + overlay
                    val returnContact = if (inReturn && answer > 0) turnContact *
                        smooth((gate + returnStops[k] - t) / .015) else 0.0
                    y += weights[k] * heard[k] * contact[k] * (forwardContact + returnContact)
                }
                if (firstOverlayRead == null && (forwardContact > 1e-4 || inReturn && answer > 0) &&
                    overlayReads.any { abs(it) > 1e-7f }) firstOverlayRead = t.toFloat()
                // All pickups happen before commits. The ring imposes a nonzero delay in physical
                // time, independent of coordinate direction, interpolation or inter-strip ordering.
                val slot = i % delay
                if (writes && imprint > 0) {
                    for (destination in 0..2) {
                        val wp = pendingPositions[destination][slot]
                        if (wp >= 0) {
                            val at = (wp * INTERNAL_RATE).coerceIn(0.0, material.overlays[destination].size - 2.0001)
                            val index = at.toInt(); val f = (at - index).toFloat()
                            val e = .26f // per-cell exposure is bounded; no normalization in this path
                            val value = pending[destination][slot].coerceIn(-OVERLAY_CEILING, OVERLAY_CEILING)
                            val overlay = material.overlays[destination]
                            for (j in 0..1) {
                                val exposure = e * if (j == 0) 1 - f else f
                                overlay[index + j] = ((1 - exposure) * overlay[index + j] + exposure * value)
                                    .coerceIn(-OVERLAY_CEILING, OVERLAY_CEILING)
                            }
                            writeCount++
                            overlayAvailable = true
                            if (firstWrite == null) firstWrite = t.toFloat()
                        }
                        pendingPositions[destination][slot] = -1.0
                        pending[destination][slot] = 0f
                    }
                    if (inReturn) {
                        for (source in 0..2) writeFilter[source] += .012 * (heard[source] - writeFilter[source])
                        for (route in machine.routes) {
                            val destination = route.destination
                            if (t >= gate + returnStops[destination]) continue
                            val gain = route.gain * imprint * (.24 + .76 * answer)
                            pending[destination][slot] += (gain * tanh(writeFilter[route.source])).toFloat()
                            // Write geometry is exempt from SPLICE by design: the physical write
                            // head deposits at its strip coordinate; spliced playback reorders it.
                            pendingPositions[destination][slot] = positions[destination]
                            if (record && events.size < 192 && i % (CONTROL_STRIDE * 30) == 0)
                                events.add(ImprintEvent(route.source, destination, (t + delay * dt).toFloat(),
                                    positions[destination].toFloat(), gain.toFloat()))
                        }
                    }
                }
                // Restrained fixed contact color. tanh is bounded and 1-Lipschitz; DC cannot linger
                // when near-stationary return contact has already faded out.
                color += .16 * (tanh(y * 1.12) / 1.12 - color)
                dc += .00042 * (color - dc)
                raw[i] = if (velocity == 0f) 0f else (color - dc).toFloat()
                if (record && i % CONTROL_STRIDE == 0) {
                    var spring = 0.0
                    for (k in 0..2) spring += .5 * contact[k] * .055 * strain * material.strips[k].tension * positions[k].pow(2)
                    trace.add(TransportFrame(t.toFloat(), motor.toFloat(), positions.map { it.toFloat() },
                        rates.map { it.toFloat() }, coords.map { it.toFloat() }, spring.toFloat(), motorWork.toFloat(), frictionLoss.toFloat()))
                }
            }
            val output = raw.copyOf(used - used % Dsp.OVERSAMPLE)
            val peak = material.overlays.maxOf { b -> b.maxOf { abs(it) } }
            val feedback = machine.routes.groupBy { it.destination }.values.maxOfOrNull { routes -> routes.sumOf { it.gain.toDouble() } } ?: 0.0
            val bytes = 6L * material.originals[0].size * 4 + raw.size * 4L + output.size * 4L + delay * 3L * 12
            return Played(output, Diagnostics(INTERNAL_RATE / CONTROL_STRIDE, gate.toFloat(), output.size.toFloat() / INTERNAL_RATE,
                returnEnd.toFloat(), rereadLength.toFloat(), material.hashes, material.peak, peak,
                (if (writes && imprint > 0) max(GENERATION_RETENTION.toDouble(), feedback * imprint) else 0.0).toFloat(), writeCount, firstWrite, firstOverlayRead, maxForward, maxReverse,
                travel.map { it.toFloat() }, material.originals[0].size, bytes, !writes,
                if (splice > 0 && machine.spliceSchedule.isNotEmpty()) (1 + 3 * splice).toInt().coerceIn(1, machine.spliceSchedule.size) else 0,
                trace, events))
        }
    }

    internal fun play(voice: SpoolVoice, macros: Map<String, Float> = emptyMap(), machine: SpoolMachine = SpoolMachine(),
                      velocity: Float = 1f, writes: Boolean = true, frozenHead: Boolean = false, record: Boolean = false): Played {
        val m = settled(macros, voice)
        val v = finite(velocity, 1f)
        val material = material(voice, m, machine, v)
        return MachinePass(voice, m, machine, v, material, writes, frozenHead, record, cycle = false).run()
    }

    internal fun finish(raw: FloatArray, normalize: Boolean = true, loop: Boolean = false): FloatArray {
        if (raw.isEmpty()) return raw
        stopIfUnwanted()
        val guard = if (loop) min(4096, raw.size) else 0
        val work = if (loop) raw.copyOfRange(raw.size - guard, raw.size) + raw + raw.copyOfRange(0, guard) else raw.copyOf()
        Tide.bandLimit(work, INTERNAL_RATE)
        val decimated = Dsp.decimate(work, Dsp.RATE)
        val out = if (loop) decimated.copyOfRange(guard / Dsp.OVERSAMPLE, guard / Dsp.OVERSAMPLE + raw.size / Dsp.OVERSAMPLE) else decimated
        var mean = 0.0
        for (x in out) mean += x
        val center = (mean / out.size.coerceAtLeast(1)).toFloat()
        for (i in out.indices) out[i] -= center
        if (normalize) Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        if (!loop) Dsp.fadeTail(out, ms = 12f)
        return out
    }

    private fun loopPlayed(voice: SpoolVoice, macros: Map<String, Float>, machine: SpoolMachine, velocity: Float,
                           writes: Boolean, frozenHead: Boolean, record: Boolean): Played {
        val m = settled(macros, voice) + ("HOLD" to 0f)
        val v = finite(velocity, 1f)
        val material = material(voice, m, machine, v)
        // One finite write generation establishes memory. Freeze the full overlay, not only its
        // endpoints. Every exported traversal uses this same snapshot, deterministic motor/head
        // phases, matched windows and rest coordinate; no claim of continuous rewriting is made.
        val snapshot = MachinePass(voice, m, machine, v, material, writes, frozenHead, false, cycle = false).run()
        val cycle = MachinePass(voice, m, machine, v, material, false, frozenHead, record, cycle = true).run()
        return cycle.copy(diagnostics = cycle.diagnostics.copy(feedbackBound = snapshot.diagnostics.feedbackBound, writeCount = snapshot.diagnostics.writeCount,
            firstWriteSeconds = snapshot.diagnostics.firstWriteSeconds, writesFrozen = true,
            imprintEvents = snapshot.diagnostics.imprintEvents, allocatedBytes = max(cycle.diagnostics.allocatedBytes, snapshot.diagnostics.allocatedBytes)))
    }

    fun renderLoop(voice: SpoolVoice, macros: Map<String, Float> = emptyMap(), machine: SpoolMachine = SpoolMachine(),
                   velocity: Float = 1f): FloatArray = finish(loopPlayed(voice, macros, machine, velocity, true, false, false).raw, loop = true)

    fun render(voice: SpoolVoice, macros: Map<String, Float> = emptyMap(), machine: SpoolMachine = SpoolMachine(),
               velocity: Float = 1f): Snip {
        val m = settled(macros, voice)
        val loop = isLoop(m.getValue("HOLD"))
        val played = if (loop) loopPlayed(voice, m, machine, velocity, true, false, false) else play(voice, m, machine, velocity)
        return Snip(finish(played.raw, loop = loop), channels = 1, sampleRate = Dsp.RATE)
    }

    internal data class ImprintContraction(val initialDistance: Float, val finalDistance: Float,
                                           val unvisitedDistance: Float, val bound: Float)

    /** Empirical full-tape norm probe, including cells the physical write heads never visit. */
    internal fun imprintContractionProbe(voice: SpoolVoice, macros: Map<String, Float>,
                                         machine: SpoolMachine = SpoolMachine(), delta: Float = .01f): ImprintContraction {
        require(delta.isFinite() && delta > 0 && delta <= .1f)
        val m = settled(macros, voice)
        require(m.getValue("IMPRINT") > 0) { "disabled imprint has no generation operator" }
        val a = material(voice, m, machine, 1f)
        val b = Material(a.originals, Array(3) { FloatArray(a.originals[0].size) }, a.strips, a.hashes, a.peak)
        val random = Random(Dsp.seedFor("SPOOL-NORM", machine.seed))
        var initial = 0f
        for (strip in 0..2) for (i in a.overlays[strip].indices) {
            val common = random.nextFloat() * .06f - .03f
            a.overlays[strip][i] = common
            b.overlays[strip][i] = common + delta // constant perturbation survives every positive pickup/filter
            initial = max(initial, abs(a.overlays[strip][i] - b.overlays[strip][i]))
        }
        MachinePass(voice, m, machine, 1f, a, true, false, false, false).run()
        MachinePass(voice, m, machine, 1f, b, true, false, false, false).run()
        var final = 0f
        var unvisited = 0f
        for (strip in 0..2) for (i in a.overlays[strip].indices) {
            val difference = abs(a.overlays[strip][i] - b.overlays[strip][i])
            final = max(final, difference)
            if (i >= a.overlays[strip].size - 64) unvisited = max(unvisited, difference)
        }
        return ImprintContraction(initial, final, unvisited, GENERATION_RETENTION)
    }

    /** Tests broadband inherited material against the actual rate-dependent pickup kernel. */
    internal fun resampleOverlayProbe(input: FloatArray, speed: Float, filtered: Boolean = true): FloatArray {
        require(input.size > 256 && speed.isFinite() && abs(speed) > 0 && abs(speed) <= 5f)
        val length = ((input.size - 128) / abs(speed)).toInt()
        val start = if (speed > 0) 64.0 else input.size - 65.0
        return FloatArray(length) { i -> read(input, (start + i * speed) / INTERNAL_RATE, speed.toDouble(), filtered) }
    }

    /** Diagnostic output uses the identical signal path; level matching is the only optional step. */
    fun renderProbe(voice: SpoolVoice, macros: Map<String, Float> = emptyMap(), machine: SpoolMachine = SpoolMachine(),
                    velocity: Float = 1f, normalize: Boolean = false, frozenHead: Boolean = false, writes: Boolean = true): ProbeResult {
        val m = settled(macros, voice)
        val loop = isLoop(m.getValue("HOLD"))
        val p = if (loop) loopPlayed(voice, m, machine, velocity, writes, frozenHead, true)
            else play(voice, m, machine, velocity, writes, frozenHead, true)
        val samples = finish(p.raw, normalize, loop)
        // Render a preceding traversal independently from the same frozen snapshot. Compare
        // real machine output before extracting the cycle; this is not a copied finished loop.
        val previous = if (loop) finish(loopPlayed(voice, m, machine, velocity, writes, frozenHead, false).raw, normalize, true) else null
        val seam = if (previous != null && previous.size >= 256) Keys.seamError(previous + samples, previous.size) else null
        return ProbeResult(samples, p.diagnostics, previous, seam)
    }
}
