package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.round
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
 * Independent forward/backward pickups overlap, with pitch-preserving recorded windows at clean
 * settings. The forward sustain continues through the physical return. Default one-shots do not
 * restart an attacked forward pass; explicitly saved re-engagement remains supported.
 * HOLD deliberately uses the specification's frozen-snapshot fallback: one finite
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
    const val MAX_TAPE_CENTS = 16f
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val CONTROL_STRIDE = 88
    private const val SOURCE_BANDWIDTH = 3_400f
    private const val CONTACT_RAMP = .035
    private const val COAST_SECONDS = .025
    private const val RETURN_ONSET_SECONDS = .035
    private const val PHASE_HANDOFF_SECONDS = .060
    private const val GEOMETRY_FADE_SECONDS = .200
    private const val REENCOUNTER_RAMP_SECONDS = .060
    private const val IMPRINT_DEPTH_RAMP_SECONDS = .035
    private const val LOOP_REST_SECONDS = .025
    private const val REST_COORDINATE_SECONDS = .012
    private const val REVERSE_PREVIEW_GATE = .45
    private const val FORWARD_RELEASE_SECONDS = .40
    private const val GRAIN_HOP_SECONDS = .030
    private const val GRAIN_GUARD_FRAMES = 64
    private const val SPLICE_OVERLAP = .032
    private val maxTapeRate = 2.0.pow(MAX_TAPE_CENTS / 1200.0)
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
            SpoolVoice.STRING -> floatArrayOf(.15f, .10f, .40f, .15f, .05f)
            SpoolVoice.CHOIR -> floatArrayOf(.25f, .15f, .45f, .20f, .05f)
            SpoolVoice.FLUTE -> floatArrayOf(.20f, .10f, .40f, .15f, .10f)
            SpoolVoice.BRASS -> floatArrayOf(.20f, .15f, .40f, .15f, .10f)
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
    data class PickupFrame(
        val timeSeconds: Float, val forwardCoordinates: List<Float>, val reverseCoordinates: List<Float>,
        val forwardReadRates: List<Float>, val reverseReadRates: List<Float>,
        val forwardGains: List<Float>, val reverseGains: List<Float>,
        val forwardMinCoordinates: List<Float>, val forwardMaxCoordinates: List<Float>,
        val reverseMinCoordinates: List<Float>, val reverseMaxCoordinates: List<Float>,
        val reverseSupports: List<Float>,
        // Read rates describe actual grain branches; physical varispeed is a separate blend.
        val physicalRates: List<Float> = emptyList(), val varispeedBlend: Float = 0f,
        val tapeRate: Float = 1f,
    )
    data class Diagnostics(
        val rate: Int, val gateSeconds: Float, val durationSeconds: Float, val returnEndSeconds: Float,
        val reengageSeconds: Float, val sourceHashes: List<Int>, val sourcePeak: Float, val overlayPeak: Float,
        val feedbackBound: Float, val writeCount: Long, val firstWriteSeconds: Float?,
        val firstOverlayReadSeconds: Float?, val maxForwardRate: Float, val maxReverseRate: Float,
        val returnTravel: List<Float>, val sourceFrames: Int, val allocatedBytes: Long,
        val writesFrozen: Boolean, val spliceCount: Int, val trace: List<TransportFrame>,
        val imprintEvents: List<ImprintEvent>, val writePassLimit: Int = 1,
        val completeGenerationBound: Float = GENERATION_RETENTION,
        val pickupTrace: List<PickupFrame> = emptyList(), val overlapSeconds: Float = 0f,
        val pitchMode: String = "PITCH_PRESERVING",
    )
    data class ProbeResult(val samples: FloatArray, val diagnostics: Diagnostics,
                           val previousCycle: FloatArray? = null, val seamError: Double? = null,
                           val forwardStem: FloatArray? = null, val reverseStem: FloatArray? = null)
    internal data class Played(val raw: FloatArray, val diagnostics: Diagnostics,
                               val forwardRaw: FloatArray? = null, val reverseRaw: FloatArray? = null)

    private class Material(val originals: Array<FloatArray>, val overlays: Array<FloatArray>,
                           val strips: List<SpoolStrip>, val hashes: List<Int>, val peak: Float,
                           val rootPhases: DoubleArray,
                           // History records actual delayed commits, including silent writes.
                           // It is independent of overlay amplitudes and survives a frozen snapshot.
                           val recordedStrips: BooleanArray = BooleanArray(3))

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
            val family = s.family
            val random = Random(Dsp.seedFor("SPOOL", voice.name, midiFor(voice, m.getValue("TUNE")), machine.seed, strip))
            val harmonics = min(24, floor(SOURCE_BANDWIDTH / hz).toInt()).coerceAtLeast(1)
            val filterA = (1.0 - exp(-2 * PI * SOURCE_BANDWIDTH / INTERNAL_RATE)).toFloat().toDouble()
            val filterMemory = 1 - filterA
            val phase = DoubleArray(harmonics) { index ->
                val h = index + 1
                val angle = 2 * PI * hz * h / INTERNAL_RATE
                val filterPhase = atan2(-filterMemory * sin(angle), 1 - filterMemory * cos(angle))
                val sign = if (family == SpoolSourceFamily.BRASS && h > 1 && h % 2 == 1) PI else 0.0
                // An even carrier profile survives time reversal in EVERY partial. Compensate
                // the four causal source filters so their phase does not undo this relation.
                // Recorded envelopes, vowel movement and airflow still play backwards.
                PI / 2 + h * .13 * strip + sign - 4 * filterPhase
            }
            val oscSin = DoubleArray(harmonics) { sin(phase[it]) }
            val oscCos = DoubleArray(harmonics) { cos(phase[it]) }
            val stepSin = DoubleArray(harmonics)
            val stepCos = DoubleArray(harmonics)
            val gain = DoubleArray(harmonics)
            val attack = when (family) {
                SpoolSourceFamily.STRING -> .085; SpoolSourceFamily.CHOIR -> .190
                SpoolSourceFamily.FLUTE -> .050; SpoolSourceFamily.BRASS -> .022
            }
            var doubleSin = sin(.8 + strip)
            var doubleCos = cos(.8 + strip)
            val doubleStep = 2 * PI * hz * 2.0.pow((if (strip % 2 == 0) 1.7 else -1.7) / 1200) / INTERNAL_RATE
            val ds = sin(doubleStep); val dc = cos(doubleStep)
            val out = FloatArray(n)
            var breath = 0.0
            var fastBreath = 0.0
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
                            SpoolSourceFamily.STRING -> (if (h == 1) .80 else 1.20 / h.toDouble().pow(.78)) *
                                exp(-f / (3400 + 1600 * evolution))
                            SpoolSourceFamily.CHOIR -> if (h == 1) .42 else (.020 +
                                1.35 * exp(-((f - 730 - 35 * sin(t * 1.6)) / 145).pow(2)) +
                                1.10 * exp(-((f - 1260 - 45 * sin(t * 1.1)) / 300).pow(2)) +
                                .70 * exp(-((f - 2500) / 260).pow(2))) / h.toDouble().pow(.18)
                            SpoolSourceFamily.FLUTE -> when (h) { 1 -> 1.0; 2 -> .045 + .035 * evolution; 3 -> .018; else -> .008 / h }
                            SpoolSourceFamily.BRASS -> (when {
                                h == 1 -> .72
                                h % 2 == 0 -> 1.40 / h.toDouble().pow(.28)
                                else -> .60 / h.toDouble().pow(.58)
                            }) * exp(-f / (3100 + 2100 * evolution))
                        }
                        // The recording has a bright, decaying attack. Reading this same
                        // immutable articulation backwards produces a growing breath/bow swell.
                        val bloom = when (family) {
                            SpoolSourceFamily.STRING -> .68 + .32 * exp(-t / .24)
                            SpoolSourceFamily.CHOIR -> .96 + .04 * exp(-t / .35)
                            SpoolSourceFamily.FLUTE -> .74 + .26 * exp(-t / .08)
                            SpoolSourceFamily.BRASS -> .90 + .10 * exp(-t / .12)
                        }
                        gain[h - 1] = a * if (h == 1) 1.0 else (.65 + .35 * velocity) * bloom
                    }
                    // Bound every recorded family by the same partial-amplitude budget;
                    // audition leveling then compares color instead of oscillator loudness.
                    val budget = gain.sum()
                    if (budget > 2.6) for (h in gain.indices) gain[h] *= 2.6 / budget
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
                val air = random.nextDouble(-1.0, 1.0)
                breath += .021 * (air - breath)
                fastBreath += .15 * (air - fastBreath)
                val airflow = fastBreath - breath
                y += when (family) {
                    SpoolSourceFamily.FLUTE -> .38 * airflow * (.65 + .35 * exp(-t / .10))
                    SpoolSourceFamily.CHOIR -> .06 * airflow
                    SpoolSourceFamily.STRING -> .025 * airflow
                    SpoolSourceFamily.BRASS -> .04 * airflow * exp(-t / .08)
                }
                val env = smooth(t / attack) * smooth((machine.sourceSeconds - t) / .16)
                val gesture = when (family) {
                    SpoolSourceFamily.STRING -> (.90 + .07 * sin(2 * PI * .75 * t + strip * .45)) *
                        (1 - .09 * exp(-((t - .38 - strip * .09) / .13).pow(2)))
                    SpoolSourceFamily.CHOIR -> .88 + .10 * sin(2 * PI * .32 * t + strip * .6)
                    SpoolSourceFamily.FLUTE -> .97 + .02 * sin(2 * PI * t + strip * .4)
                    SpoolSourceFamily.BRASS -> (.82 + .16 * exp(-t / .12) + .04 * sin(2 * PI * .55 * t)).coerceAtMost(1.0)
                }
                val articulation = when (family) {
                    SpoolSourceFamily.STRING -> .55 + .45 * exp(-t / .32)
                    SpoolSourceFamily.CHOIR -> .74 + .26 * exp(-t / .60)
                    SpoolSourceFamily.FLUTE -> .92 + .08 * exp(-t / .22)
                    SpoolSourceFamily.BRASS -> .44 + .56 * exp(-t / .17)
                }
                out[i] = (.36 * y * env * gesture * articulation * (.60 + .40 * velocity)).toFloat()
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
        // Demodulate ONLY immutable originals. For x=sin(omega*t+phi), dot(x,cos)
        // gives sin(phi), dot(x,sin) gives cos(phi), so this atan2 is SIN phase.
        // Feedback overlays never influence grain geometry or its contraction proof.
        val phases = DoubleArray(3) { strip ->
            val from = (.22 * INTERNAL_RATE).toInt()
            val until = min(n, (1.12 * INTERNAL_RATE).toInt())
            val stride = Dsp.OVERSAMPLE
            val step = 2 * PI * hz * stride / INTERNAL_RATE
            val ss = sin(step); val cc = cos(step)
            var sn = sin(2 * PI * hz * from / INTERNAL_RATE)
            var cs = cos(2 * PI * hz * from / INTERNAL_RATE)
            var inPhase = 0.0; var quadrature = 0.0
            var i = from
            while (i < until) {
                val x = originals[strip][i]
                inPhase += x * cs; quadrature += x * sn
                val old = sn; sn = sn * cc + cs * ss; cs = cs * cc - old * ss
                i += stride
            }
            atan2(inPhase, quadrature)
        }
        return Material(originals, Array(3) { FloatArray(n) }, strips, originals.map { it.contentHashCode() }, peak, phases)
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
        private val imprint = m.getValue("IMPRINT").toDouble()
        private val imprintFilterAlpha = 1.0 - exp(-2 * PI * (120.0 + 2280.0 * imprint.pow(1.6)) / INTERNAL_RATE)
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
        private val imprintDepthStart = DoubleArray(3) {
            if (material.recordedStrips[it]) -IMPRINT_DEPTH_RAMP_SECONDS else Double.POSITIVE_INFINITY
        }
        private val pickupDepth = DoubleArray(3)
        private val pitchBlend = smooth((strain - .35) / .55)
        private val period = 1.0 / frequencyFor(voice, m.getValue("TUNE"))
        private val hopFrames = (max(3.0, round(GRAIN_HOP_SECONDS / period)) * period * INTERNAL_RATE).toInt().coerceAtLeast(16)
        private val previewStart = gate * REVERSE_PREVIEW_GATE
        private val previewTravel = DoubleArray(3)
        private var previewCaptured = false
        private val forwardHeads = Array(3) { PitchHead(it, 1) }
        private val reverseHeads = Array(3) { PitchHead(it, -1) }
        private val pickupTrace = ArrayList<PickupFrame>()
        private var overlapFrames = 0L
        private val phase = DoubleArray(3) { Random(Dsp.seedFor("SPOOL-TRANSPORT", machine.seed, it)).nextDouble() * 2 * PI }
        private val tapePhases = Random(Dsp.seedFor("SPOOL-TAPE", machine.seed)).let { random ->
            DoubleArray(3) { random.nextDouble() * 2 * PI }
        }
        private var carrierTime = 0.0
        private var tapeRate = 1.0
        private var released = false

        private data class Grain(
            val startFrame: Int, val carrierStart: Double, val length: Int, val dryStart: Double, val firstStart: Double,
            val secondStart: Double, val blend: Double, val wet: Double, val support: Double,
        )

        /** Independent anchors; both pickup carriers follow the same integrated tape clock. */
        private inner class PitchHead(private val strip: Int, private val direction: Int) {
            private val grains = arrayOfNulls<Grain>(3)
            private var slot = 0
            private var nextLaunch = -1
            var overlayRead = 0f
            var coordinate = 0.0
            var minCoordinate = 0.0
            var maxCoordinate = 0.0
            var support = 0.0
            var readRate = 0f
            private val guard = GRAIN_GUARD_FRAMES.toDouble() / INTERNAL_RATE
            private val phaseOffset = if (direction > 0) 0.0 else (PI - 2 * material.rootPhases[strip]) * period / (2 * PI)

            private fun launch(frame: Int, center: Double, canvas: Double, geometry: Double) {
                val usable = canvas - 2 * guard - 2 * period
                val length = min(2 * hopFrames, (usable / maxTapeRate * INTERNAL_RATE).toInt())
                if (length < (2 * period * INTERNAL_RATE).toInt()) return
                val duration = length.toDouble() / INTERNAL_RATE * maxTapeRate
                val low = if (direction > 0) guard else duration + guard
                val high = if (direction > 0) canvas - duration - guard else canvas - guard
                // Both directions use the SAME integrated playback clock. Its gentle wow
                // and flutter move their fundamental together, rather than detuning layers.
                val origin = direction * carrierTime + phaseOffset
                val loCycle = ceil((low - origin) / period)
                val hiCycle = floor((high - origin) / period)
                if (loCycle > hiCycle) return
                fun anchor(c: Double): Double {
                    val desired = c - direction * duration / 2
                    val cycle = round((desired - origin) / period).coerceIn(loCycle, hiCycle)
                    return origin + cycle * period
                }
                val desiredCenter = center.coerceIn(duration / 2 + guard, canvas - duration / 2 - guard)
                val p = pickup(desiredCenter, canvas, splice, machine.spliceSchedule, sourceLength)
                grains[slot] = Grain(frame, carrierTime, length, anchor(desiredCenter), anchor(p.first), anchor(p.second),
                    p.blend, smooth(splice) * geometry, canvas)
                slot = (slot + 1) % grains.size
            }

            fun step(frame: Int, center: Double, canvas: Double, active: Boolean, geometry: Double): Float {
                overlayRead = 0f; readRate = 0f; coordinate = 0.0; minCoordinate = Double.POSITIVE_INFINITY
                maxCoordinate = 0.0; support = 0.0
                if (!active) { minCoordinate = 0.0; return 0f }
                if (nextLaunch < 0 || frame >= nextLaunch) {
                    launch(frame, center, canvas, geometry)
                    nextLaunch = frame + hopFrames
                }
                var original = 0.0; var inherited = 0.0; var weightSum = 0.0
                for (grain in grains) {
                    if (grain == null) continue
                    val age = frame - grain.startFrame
                    if (age < 0 || age >= grain.length) continue
                    val w = sin(PI * age / grain.length).pow(2)
                    val offset = direction * (carrierTime - grain.carrierStart)
                    val dry = grain.dryStart + offset
                    val first = grain.firstStart + offset
                    val second = grain.secondStart + offset
                    fun encounter(buffer: FloatArray, overlay: Boolean): Float {
                        val rate = direction * tapeRate
                        val a = read(buffer, dry, rate, overlay)
                        if (grain.wet <= 0 || first == dry && second == dry) return a
                        val b = read(buffer, first, rate, overlay) * (1 - grain.blend) +
                            read(buffer, second, rate, overlay) * grain.blend
                        return (a * (1 - grain.wet) + b * grain.wet).toFloat()
                    }
                    original += w * encounter(material.originals[strip], false)
                    if (overlayAvailable) inherited += w * encounter(material.overlays[strip], true)
                    weightSum += w
                    coordinate += w * dry
                    minCoordinate = min(minCoordinate, min(dry, min(first, second)) - guard)
                    maxCoordinate = max(maxCoordinate, max(dry, max(first, second)) + guard)
                    support = max(support, grain.support)
                }
                // Positive Hann overlaps are normalized only downwards. A newly opened or very
                // short canvas fades in; quiet material is never normalized to a loud new note.
                val normalization = max(1.0, weightSum)
                if (weightSum > 1e-9) readRate = (direction * tapeRate).toFloat()
                overlayRead = (inherited / normalization).toFloat()
                coordinate /= max(weightSum, 1e-12)
                if (!minCoordinate.isFinite()) minCoordinate = 0.0
                // Expose inherited texture by reducing only the dry recording. The overlay
                // coefficient stays exactly one; write history, never its amplitude, enables depth.
                return ((original * (1 - pickupDepth[strip]) + inherited) / normalization).toFloat()
            }
        }

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
            // For fixed write history, dry pickup depth is identical for both tapes and does
            // not depend on audio samples. Its original term cancels in their difference;
            // inherited pickup gain remains <=1, as does the convex write filter at every setting.
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
            val forwardRaw = if (record) FloatArray(raw.size) else null
            val reverseRaw = if (record) FloatArray(raw.size) else null
            val forwardGains = DoubleArray(3)
            val reverseGains = DoubleArray(3)
            for (i in raw.indices) {
                if (i % 8192 == 0 && Thread.currentThread().isInterrupted)
                    throw java.util.concurrent.CancellationException("SPOOL render no longer wanted")
                val t = i.toDouble() / INTERNAL_RATE
                for (k in 0..2) pickupDepth[k] = if (imprint > 0 && material.recordedStrips[k])
                    .85 * imprint * smooth((t - imprintDepthStart[k]) / IMPRINT_DEPTH_RAMP_SECONDS) else 0.0
                if (t >= gate && !released) release()
                val inReturn = released && t < returnEnd
                val inReread = !cycle && rereadLength > 0 && t >= rereadStart && t < rereadStart + rereadLength
                if (i % CONTROL_STRIDE == 0) {
                    // Common capstan motion remains audible at clean STRAIN: a slow wow
                    // plus two faster flutter components. No strip gets independent detuning.
                    val cents = (5 * sin(2 * PI * .73 * t + tapePhases[0]) +
                        3 * sin(2 * PI * 6.7 * t + tapePhases[1]) +
                        sin(2 * PI * 12.1 * t + tapePhases[2])) * (1 + .75 * strain)
                    tapeRate = 2.0.pow(cents / 1200)
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
                if (!previewCaptured && t >= previewStart) {
                    for (k in 0..2) previewTravel[k] = positions[k]
                    previewCaptured = true
                }
                val end = if (cycle) returnEnd + LOOP_REST_SECONDS else rereadStart + rereadLength + .20
                if (released && t >= end) { used = i; break }
                val weights = headWeights(t)
                val geometryX = if (t < gate) (gate - t) / GEOMETRY_FADE_SECONDS
                    else if (inReturn) (t - gate) / GEOMETRY_FADE_SECONDS
                    else if (inReread) (t - rereadStart) / GEOMETRY_FADE_SECONDS else 0.0
                val geometry = smooth(geometryX)
                val geometryPrime = if (geometryX > 0 && geometryX < 1)
                    6 * geometryX * (1 - geometryX) / GEOMETRY_FADE_SECONDS * (if (t < gate) -1 else 1) else 0.0
                val reverseOpen = previewCaptured && t < returnEnd
                val forwardOpen = t < returnEnd || inReread
                val forwardEnvelope = if (t < gate) smooth(t / CONTACT_RAMP)
                    else if (t < returnEnd) smooth((returnEnd - t) / max(.035, returnEnd - gate))
                    else if (inReread) .30 * smooth((t - rereadStart) / REENCOUNTER_RAMP_SECONDS) *
                        smooth((rereadStart + rereadLength - t) / REENCOUNTER_RAMP_SECONDS) else 0.0
                val reverseEnvelope = if (reverseOpen) smooth((t - previewStart) / .080) else 0.0
                // The audible two-head mix is also convex. RETURN=0 removes the backward stem
                // exactly, while the internal delayed return pickup may still leave an imprint.
                val forwardMix = 1.0 / (1 + .85 * answer)
                val reverseMix = .85 * answer * forwardMix
                var forwardSum = 0.0
                var reverseSum = 0.0
                for (k in 0..2) {
                    val stopped = inReturn && t >= gate + returnStops[k]
                    val returnCanvas = if (t < gate) previewTravel[k] else travel[k]
                    val previewProgress = ((t - previewStart) / max(.01, gate - previewStart)).coerceIn(0.0, 1.0)
                    // Traverse the captured attack as well as its sustain. The reversed
                    // recorded decay can now swell towards the original onset before release.
                    val returnCenter = if (t < gate) previewTravel[k] * (.94 - .86 * previewProgress) else positions[k]
                    val forwardCanvas = if (t < gate || inReread) sourceLength else travel[k]
                    val forwardCenter = if (t < gate || inReread) positions[k] + hopFrames.toDouble() / INTERNAL_RATE
                        else travel[k] - hopFrames.toDouble() / INTERNAL_RATE - period
                    fun shifted(center: Double, canvas: Double): Double {
                        if (frozenHead) return center
                        val a = machine.headOffsets[k].toDouble().coerceIn(-.12 * canvas, .12 * canvas)
                        return center + head * a * sin(PI * center / max(.12, canvas)) * geometry
                    }
                    var forwardPickup = forwardHeads[k].step(i, shifted(forwardCenter, forwardCanvas), forwardCanvas,
                        forwardOpen, geometry)
                    var backwardPickup = reverseHeads[k].step(i, shifted(returnCenter, returnCanvas), returnCanvas,
                        reverseOpen && !stopped, geometry)
                    var forwardOverlay = forwardHeads[k].overlayRead
                    var returnOverlay = reverseHeads[k].overlayRead
                    if (pitchBlend > 0 && (t < gate || inReturn)) {
                        // Only strong STRAIN restores competing varispeed. Locked and physical
                        // pickups use a convex blend before the recording matrix, preserving norms.
                        val support = if (inReturn) travel[k] else sourceLength
                        val theta = PI * positions[k] / max(.12, support)
                        val a = machine.headOffsets[k].toDouble().coerceIn(-.12 * support, .12 * support)
                        val motion = if (frozenHead) 0.0 else sin(theta)
                        val kernelGuard = GRAIN_GUARD_FRAMES.toDouble() / INTERNAL_RATE
                        val coordinate = (positions[k] + head * a * motion * geometry).coerceIn(kernelGuard, support - kernelGuard)
                        val speed = if (frozenHead) rates[k] else rates[k] *
                            (1 + head * a * cos(theta) * PI / max(.12, support) * geometry) + head * a * motion * geometryPrime
                        val windows = pickup(coordinate, support, splice, machine.spliceSchedule, sourceLength)
                        val p = Pickup(windows.first.coerceIn(kernelGuard, support - kernelGuard),
                            windows.second.coerceIn(kernelGuard, support - kernelGuard), windows.blend)
                        val wet = smooth(splice) * geometry
                        fun physical(buffer: FloatArray, overlay: Boolean): Float {
                            val dry = read(buffer, coordinate, speed, overlay)
                            return if (wet > 0) (dry * (1 - wet) + tap(buffer, p, speed, overlay) * wet).toFloat() else dry
                        }
                        val base = if (!stopped) physical(material.originals[k], false) else 0f
                        val inherited = if (!stopped && overlayAvailable) physical(material.overlays[k], true) else 0f
                        if (t < gate) {
                            forwardPickup = (forwardPickup * (1 - pitchBlend) +
                                (base * (1 - pickupDepth[k]) + inherited) * pitchBlend).toFloat()
                            forwardOverlay = (forwardOverlay * (1 - pitchBlend) + inherited * pitchBlend).toFloat()
                        } else {
                            backwardPickup = (backwardPickup * (1 - pitchBlend) +
                                (base * (1 - pickupDepth[k]) + inherited) * pitchBlend).toFloat()
                            returnOverlay = (returnOverlay * (1 - pitchBlend) + inherited * pitchBlend).toFloat()
                        }
                    }
                    coords[k] = if (inReturn) reverseHeads[k].coordinate else forwardHeads[k].coordinate
                    val returnFade = if (inReturn) smooth((gate + returnStops[k] - t) / .035) else 1.0
                    forwardGains[k] = weights[k] * contact[k] * forwardEnvelope * forwardMix
                    reverseGains[k] = weights[k] * contact[k] * reverseEnvelope * reverseMix * returnFade
                    forwardSum += forwardPickup * forwardGains[k]
                    reverseSum += backwardPickup * reverseGains[k]
                    // Recording hears one normalized backward pickup, never an unbounded sum
                    // of the simultaneously audible forward and backward layers.
                    heard[k] = if (reverseOpen && !stopped) backwardPickup else 0f
                    overlayReads[k] = (abs(forwardOverlay) * forwardGains[k] + abs(returnOverlay) * reverseGains[k]).toFloat()
                }
                if (forwardGains.indices.any { forwardGains[it] > .01 && forwardHeads[it].readRate != 0f } &&
                    reverseGains.indices.any { reverseGains[it] > .01 && reverseHeads[it].readRate != 0f }) overlapFrames++
                if (firstOverlayRead == null && overlayReads.any { it > 1e-7f }) firstOverlayRead = t.toFloat()
                forwardRaw?.set(i, if (velocity == 0f) 0f else forwardSum.toFloat())
                reverseRaw?.set(i, if (velocity == 0f) 0f else reverseSum.toFloat())
                val y = forwardSum + reverseSum
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
                            if (!material.recordedStrips[destination]) {
                                material.recordedStrips[destination] = true
                                imprintDepthStart[destination] = t
                            }
                            if (firstWrite == null) firstWrite = t.toFloat()
                        }
                        pendingPositions[destination][slot] = -1.0
                        pending[destination][slot] = 0f
                    }
                    if (inReturn) {
                        for (source in 0..2) writeFilter[source] += imprintFilterAlpha * (heard[source] - writeFilter[source])
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
                    pickupTrace.add(PickupFrame(t.toFloat(), forwardHeads.map { it.coordinate.toFloat() },
                        reverseHeads.map { it.coordinate.toFloat() }, forwardHeads.map { it.readRate },
                        reverseHeads.map { it.readRate }, forwardGains.map { it.toFloat() }, reverseGains.map { it.toFloat() },
                        forwardHeads.map { it.minCoordinate.toFloat() }, forwardHeads.map { it.maxCoordinate.toFloat() },
                        reverseHeads.map { it.minCoordinate.toFloat() }, reverseHeads.map { it.maxCoordinate.toFloat() },
                        reverseHeads.map { it.support.toFloat() }, rates.map { it.toFloat() }, pitchBlend.toFloat(), tapeRate.toFloat()))
                    trace.add(TransportFrame(t.toFloat(), motor.toFloat(), positions.map { it.toFloat() },
                        rates.map { it.toFloat() }, coords.map { it.toFloat() }, spring.toFloat(), motorWork.toFloat(), frictionLoss.toFloat()))
                }
                carrierTime += tapeRate * dt
            }
            val output = raw.copyOf(used - used % Dsp.OVERSAMPLE)
            val peak = material.overlays.maxOf { b -> b.maxOf { abs(it) } }
            val feedback = machine.routes.groupBy { it.destination }.values.maxOfOrNull { routes -> routes.sumOf { it.gain.toDouble() } } ?: 0.0
            val bytes = 6L * material.originals[0].size * 4 + raw.size * 4L + output.size * 4L + delay * 3L * 12 +
                (if (record) raw.size * 8L + output.size * 8L else 0L)
            return Played(output, Diagnostics(INTERNAL_RATE / CONTROL_STRIDE, gate.toFloat(), output.size.toFloat() / INTERNAL_RATE,
                returnEnd.toFloat(), rereadLength.toFloat(), material.hashes, material.peak, peak,
                (if (writes && imprint > 0) max(GENERATION_RETENTION.toDouble(), feedback * imprint) else 0.0).toFloat(), writeCount, firstWrite, firstOverlayRead, maxForward, maxReverse,
                travel.map { it.toFloat() }, material.originals[0].size, bytes, !writes,
                if (splice > 0 && machine.spliceSchedule.isNotEmpty()) (1 + 3 * splice).toInt().coerceIn(1, machine.spliceSchedule.size) else 0,
                trace, events, pickupTrace = pickupTrace, overlapSeconds = overlapFrames.toFloat() / INTERNAL_RATE,
                pitchMode = if (pitchBlend <= 0) "COHERENT_TAPE" else "TAPE_TRANSPORT_BLEND"),
                forwardRaw?.copyOf(output.size), reverseRaw?.copyOf(output.size))
        }
    }

    internal fun play(voice: SpoolVoice, macros: Map<String, Float> = emptyMap(), machine: SpoolMachine = SpoolMachine.forVoice(voice),
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

    fun renderLoop(voice: SpoolVoice, macros: Map<String, Float> = emptyMap(), machine: SpoolMachine = SpoolMachine.forVoice(voice),
                   velocity: Float = 1f): FloatArray = finish(loopPlayed(voice, macros, machine, velocity, true, false, false).raw, loop = true)

    fun render(voice: SpoolVoice, macros: Map<String, Float> = emptyMap(), machine: SpoolMachine = SpoolMachine.forVoice(voice),
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
                                         machine: SpoolMachine = SpoolMachine.forVoice(voice), delta: Float = .01f): ImprintContraction {
        require(delta.isFinite() && delta > 0 && delta <= .1f)
        val m = settled(macros, voice)
        require(m.getValue("IMPRINT") > 0) { "disabled imprint has no generation operator" }
        val a = material(voice, m, machine, 1f)
        val b = Material(a.originals, Array(3) { FloatArray(a.originals[0].size) }, a.strips, a.hashes, a.peak, a.rootPhases)
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
    fun renderProbe(voice: SpoolVoice, macros: Map<String, Float> = emptyMap(), machine: SpoolMachine = SpoolMachine.forVoice(voice),
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
        return ProbeResult(samples, p.diagnostics, previous, seam,
            p.forwardRaw?.let { finish(it, normalize = false, loop = loop) },
            p.reverseRaw?.let { finish(it, normalize = false, loop = loop) })
    }
}
