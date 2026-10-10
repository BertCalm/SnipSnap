package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class RevelVoice { CIRCLE, CLOSE, CROSSING, SPIRO, FRICTION, PROCESSION }
enum class RevelTrajectory { CIRCLE, ECCENTRIC, SPIRO }

/** Stored musical geometry; empty rosters use the selected voice's reproducible roster. */
data class RevelConfig(
    val micCount: Int? = null,
    val phraseTempo: Float = 104f,
    val phraseBeats: Int = 4,
    val trajectories: List<RevelTrajectory> = emptyList(),
    val phaseOffsets: List<Float> = emptyList(),
    val directions: List<Int> = emptyList(),
    val speedRatios: List<Float> = emptyList(),
    val seed: Long = 0L,
) {
    init {
        require(micCount == null || micCount in 1..3) { "REVEL has one to three microphones" }
        require(phraseTempo.isFinite() && phraseTempo in 60f..200f)
        require(phraseBeats in 1..8)
        require(listOf(trajectories.size, phaseOffsets.size, directions.size, speedRatios.size).all { it <= 3 })
        require(phaseOffsets.all { it.isFinite() && abs(it) <= 32f * PI.toFloat() })
        require(directions.all { it == -1 || it == 1 })
        require(speedRatios.all { it in listOf(.5f, 1f, 1.5f, 2f) })
    }
}

/**
 * Six tuned modal skins share four weak floor modes. Acoustic coordinates are
 * (omega*q, velocity): exact damped rotations followed by reciprocal velocity
 * exchanges. Every exchange contracts total energy. Performer contacts have
 * explicit work budgets; moving microphones never enter the source equations.
 *
 * Pickup uses reception-time geometry and shared circular source histories.
 * Moving delay excursions are compressed to at most 16% of their physical excursion:
 * distance still sets the mean arrival, gain and spectral detail, while this
 * musical approximation restrains Doppler to .4%. A quiet central pickup of
 * actual head radiation and fundamentals preserves body and root in mono.
 * These are invented mechanisms and musical calibrations, not measured models.
 */
object Revel {
    const val RATE = Dsp.RATE
    const val ROOT_MIDI = 48
    const val TUNE_SEMITONES = 24
    const val LOOP_THRESHOLD = .99f
    const val HOLD_LOOP = LOOP_THRESHOLD
    const val RING_RADIUS = 2.0
    const val MAX_PATH_RADIUS = 1.55
    const val MIN_CLEARANCE = .35
    const val MIC_HEIGHT = .30
    const val MAX_DELAY_RATE = .004
    private const val INTERNAL_RATE = RATE * Dsp.OVERSAMPLE
    private const val HEADS = 6
    private const val MODES = 5
    private const val FLOOR = HEADS * MODES
    private const val COUNT = FLOOR + 4
    private const val STRIDE = 64
    private const val HISTORY = 4096
    private const val DT = 1.0 / INTERNAL_RATE
    private const val SOUND_SPEED = 343.0
    private const val LN1000 = 6.907755278982137

    private val starting = arrayOf(
        floatArrayOf(.55f, .50f, .35f, .10f, .55f),
        floatArrayOf(.50f, .55f, .40f, .25f, .85f),
        floatArrayOf(.60f, .50f, .50f, .45f, .70f),
        floatArrayOf(.70f, .60f, .55f, .85f, .75f),
        floatArrayOf(.45f, .40f, .35f, .55f, .70f),
        floatArrayOf(.75f, .35f, .25f, .30f, .60f),
    )
    fun macrosFor(voice: RevelVoice): List<MacroSpec> {
        val d = starting[voice.ordinal]
        return listOf(MacroSpec("TUNE", .5f, .5f), MacroSpec("PLAY", d[0], .5f),
            MacroSpec("SKIN", d[1], .5f), MacroSpec("ORBIT", d[2], .3f),
            MacroSpec("WEAVE", d[3], .25f), MacroSpec("REACH", d[4], .6f), MacroSpec("HOLD", 0f, 0f))
    }
    fun defaults(voice: RevelVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    internal fun settled(macros: Map<String, Float>, voice: RevelVoice): Map<String, Float> =
        defaults(voice).mapValues { (name, d) -> macros[name]?.let { if (it.isFinite()) it.coerceIn(0f, 1f) else d } ?: d }
    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: RevelVoice): Int = ROOT_MIDI
    fun midiFor(voice: RevelVoice, tune: Float): Int = rootMidi(voice) +
        ((if (tune.isFinite()) tune.coerceIn(0f, 1f) else .5f) * TUNE_SEMITONES).roundToInt()
    fun midiFor(tune: Float): Int = midiFor(RevelVoice.CIRCLE, tune)
    fun frequencyFor(voice: RevelVoice, tune: Float): Float = Keys.midiHz(midiFor(voice, tune))
    fun frequencyFor(tune: Float): Float = frequencyFor(RevelVoice.CIRCLE, tune)
    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= LOOP_THRESHOLD
    fun defaultMicCount(voice: RevelVoice): Int = when (voice) {
        RevelVoice.CROSSING, RevelVoice.FRICTION -> 2
        RevelVoice.SPIRO -> 3
        else -> 1
    }
    fun resolvedConfiguration(voice: RevelVoice, configuration: RevelConfig = RevelConfig()): RevelConfig {
        val c = configuration
        val count = c.micCount ?: defaultMicCount(voice)
        require(listOf(c.trajectories.size, c.phaseOffsets.size, c.directions.size, c.speedRatios.size)
            .all { it == 0 || it == count }) { "REVEL rosters must match resolved microphone count $count" }
        return c.copy(micCount = count,
            trajectories = c.trajectories.ifEmpty { List(count) { i ->
                if (voice == RevelVoice.SPIRO || voice == RevelVoice.FRICTION) RevelTrajectory.SPIRO
                else if (i > 0 || voice == RevelVoice.CLOSE) RevelTrajectory.ECCENTRIC else RevelTrajectory.CIRCLE } },
            phaseOffsets = c.phaseOffsets.ifEmpty { List(count) { (.37 + it * 2.0 * PI / count).toFloat() } },
            directions = c.directions.ifEmpty { List(count) { if (it % 2 == 0) 1 else -1 } },
            speedRatios = c.speedRatios.ifEmpty { List(count) { 1f } })
    }
    fun drumClassFor(voice: RevelVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(settled(macros, voice).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL
    fun scramble(voice: RevelVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(defaults(voice) + (near?.macros?.filterKeys { it in defaults(voice) } ?: emptyMap()),
            temperature, random).toMutableMap().also { it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f) }

    /** Solo suppresses other performers, not their passive sympathetic membranes. */
    data class Probe(val recordSources: Boolean = false, val recordPaths: Boolean = false,
        val recordMics: Boolean = false, val floor: Boolean = true, val soloHead: Int? = null,
        val inputOffSeconds: Double? = null, val coincidentMics: Boolean = false, val seconds: Float? = null) {
        init {
            require(soloHead == null || soloHead in 0 until HEADS)
            require(inputOffSeconds == null || (inputOffSeconds.isFinite() && inputOffSeconds >= 0))
            require(seconds == null || (seconds.isFinite() && seconds in .01f..20f))
        }
    }
    data class Event(val head: Int, val family: String, val kind: String, val timeSeconds: Double,
        val durationSeconds: Double, val energy: Double)
    data class PathFrame(val timeSeconds: Double, val mic: Int, val head: Int, val x: Double,
        val y: Double, val distanceMeters: Double, val gain: Double, val delaySamples: Double)
    data class Rates(val requestedOrbitHz: Double, val orbitHz: Double,
        val requestedPhraseSeconds: Double, val phraseSeconds: Double, val loopSeconds: Double)
    data class LoopEvidence(val seamError: Double, val convergenceError: Double,
        val stateConvergenceError: Double, val prerollCycles: Int)
    data class Report(val snip: Snip, val raw: FloatArray, val sources: Array<FloatArray>?,
        val floorSignal: FloatArray?, val microphones: Array<FloatArray>?, val events: List<Event>,
        val paths: List<PathFrame>, val rates: Rates, val maxEnergy: Double, val maxFloorEnergy: Double,
        val poweredWork: Double, val passiveLoss: Double, val recoveries: Int,
        val maxMicGain: Double, val maxDelayRate: Double, val loop: LoopEvidence?,
        val energy: FloatArray? = null, val floorEnergy: FloatArray? = null)

    private data class Plan(val frames: Int, val phraseFrames: Int, val beats: Int,
        val cycles: Int, val rates: Rates, val held: Boolean)
    private fun plan(m: Map<String, Float>, c: RevelConfig): Plan {
        val requestedPhrase = 60.0 * c.phraseBeats / c.phraseTempo
        val requestedOrbit = m.getValue("ORBIT").toDouble().let { if (it == 0.0) 0.0 else .10 + .42 * it.pow(.75) }
        val held = isLoop(m.getValue("HOLD"))
        val cycles = if (held || m.getValue("HOLD") >= .5f) 2 else 1
        var beats = c.phraseBeats
        // A compatible shorter whole-beat phrase is selected before any strokes are made.
        while (60.0 * beats / c.phraseTempo * cycles > (if (held) 8.0 else 4.35) && beats > 1)
            beats = max(1, beats / 2)
        val phraseFrames = (60.0 * beats * RATE / c.phraseTempo).roundToInt()
        val phrase = phraseFrames.toDouble() / RATE
        val frames = if (held) cycles * phraseFrames else ((cycles * phrase + 2.65) * RATE).roundToInt()
        val loopSeconds = if (held) frames.toDouble() / RATE else 0.0
        // Half-integer ratios close after an even number of base revolutions.
        val quantum = if (c.speedRatios.any { it == .5f || it == 1.5f }) 2 else 1
        val orbit = if (!held || requestedOrbit == 0.0) requestedOrbit
            else max(quantum, (requestedOrbit * loopSeconds / quantum).roundToInt() * quantum) / loopSeconds
        return Plan(frames, phraseFrames, beats, cycles,
            Rates(requestedOrbit, orbit, requestedPhrase, phrase, loopSeconds), held)
    }
    fun renderFrames(voice: RevelVoice, macros: Map<String, Float> = emptyMap(),
        configuration: RevelConfig = RevelConfig()): Int = plan(settled(macros, voice), configuration).frames

    private fun family(head: Int): String = when (head) {
        0 -> "friction"; 1, 2 -> "articulated"; 3, 4 -> "rolling"; else -> "pulse"
    }
    private fun schedule(voice: RevelVoice, m: Map<String, Float>, c: RevelConfig, p: Plan,
        velocity: Double, probe: Probe): List<Event> {
        val random = Random(Dsp.seedFor("REVEL", voice.name, midiFor(voice, m.getValue("TUNE")), c.seed))
        val play = m.getValue("PLAY").toDouble()
        val beat = p.rates.phraseSeconds / p.beats
        val out = ArrayList<Event>()
        fun add(head: Int, at: Double, kind: String, strength: Double, duration: Double) {
            if (probe.soloHead != null && probe.soloHead != head) return
            val jitter = if (at == 0.0) 0.0 else (random.nextDouble() - .5) * .028 * beat
            val time = (at * beat + jitter).coerceAtLeast(0.0)
            val bias = when {
                voice == RevelVoice.FRICTION && head == 0 -> 1.65
                voice == RevelVoice.PROCESSION && head >= 3 -> 1.3
                voice == RevelVoice.CLOSE && head in 1..2 -> 1.2
                else -> 1.0
            }
            val fittedDuration = min(duration, max(.0001, p.rates.phraseSeconds - time - .002))
            out.add(Event(head, family(head), kind, time, fittedDuration,
                strength * bias * (.40 + .60 * velocity) * velocity * (.88 + .24 * random.nextDouble())))
        }
        for (b in 0 until p.beats) {
            add(5, b.toDouble(), "broad", if (b == 0) .85 else .46 + .25 * play, .012 + .014 * (1 - m.getValue("SKIN")))
            add(if (b % 2 == 0) 3 else 4, b + .25, "palm", .25 + .35 * play, .0018)
            if (play > .25 || b == 0) add(if (b % 2 == 0) 4 else 3, b + .72, "finger", .18 + .28 * play, .0008)
            add(if (b % 2 == 0) 2 else 1, b + .49, if (b % 3 == 2) "touch" else if (b % 2 == 0) "edge" else "center",
                .23 + .38 * play, if (b % 3 == 2) .004 else .0012)
            if (b % 2 == 0 || play > .75) add(0, b + .56, "rub", .26 + .30 * play, beat * (.34 + .16 * (1 - play)))
            if (play > .70 && b % 2 == 1) add(1, b + .88, "center", .20 * play, .0018)
            if (play > .88 && b == 0) add(4, .25, "emphasis", .20, .0013)
        }
        // One-beat fallback still contains both rolling projections and a bass response.
        if (p.beats == 1) add(1, .83, "center", .25, .0012)
        return out.sortedWith(compareBy<Event> { it.timeSeconds }.thenBy { it.head })
    }

    private val stationX = doubleArrayOf(2.0, -.08, .08, -2.0, -2.0, 0.0)
    private val stationY = doubleArrayOf(0.0, 2.0, 2.0, -.08, .08, -2.0)
    private class Geometry(voice: RevelVoice, val m: Map<String, Float>, configuration: RevelConfig, val p: Plan, coincident: Boolean) {
        private val c = resolvedConfiguration(voice, configuration)
        val count = c.micCount!!
        val radius = .15 + 1.40 * m.getValue("REACH")
        val weave = m.getValue("WEAVE").toDouble()
        val trajectories: List<RevelTrajectory>
        val phases: DoubleArray
        val directions: IntArray
        val ratios: DoubleArray
        val delayMotionScale: DoubleArray
        init {
            require(listOf(c.trajectories.size, c.phaseOffsets.size, c.directions.size, c.speedRatios.size)
                .all { it == 0 || it == count }) { "REVEL rosters must match resolved microphone count $count" }
            val selectedTrajectories = c.trajectories
            phases = DoubleArray(count) { i -> c.phaseOffsets[if (coincident) 0 else i].toDouble() }
            directions = IntArray(count) { i -> c.directions[if (coincident) 0 else i] }
            ratios = DoubleArray(count) { i -> c.speedRatios[if (coincident) 0 else i].toDouble() }
            trajectories = if (coincident) List(count) { selectedTrajectories.first() } else selectedTrajectories
            delayMotionScale = DoubleArray(count) { mic ->
                val k = when (trajectories[mic]) { RevelTrajectory.CIRCLE -> 2; RevelTrajectory.ECCENTRIC -> 3; RevelTrajectory.SPIRO -> 4 }
                val intricate = (2 * weave - 1).coerceAtLeast(0.0)
                val speedBound = radius * 2 * PI * p.rates.orbitHz * ratios[mic] *
                    (1 + intricate * (.68 + .32 * k - 1))
                min(.16, MAX_DELAY_RATE * SOUND_SPEED / max(speedBound, 1e-9))
            }
        }
        fun position(mic: Int, time: Double, out: DoubleArray) {
            val theta = phases[mic] + 2 * PI * p.rates.orbitHz * time * directions[mic] * ratios[mic]
            val eccentric = (2 * weave).coerceAtMost(1.0)
            val intricate = (2 * weave - 1).coerceAtLeast(0.0)
            val k = when (trajectories[mic]) { RevelTrajectory.CIRCLE -> 2; RevelTrajectory.ECCENTRIC -> 3; RevelTrajectory.SPIRO -> 4 }
            val phi = when (trajectories[mic]) { RevelTrajectory.CIRCLE -> .2; RevelTrajectory.ECCENTRIC -> 1.1; RevelTrajectory.SPIRO -> 1.8 }
            out[0] = radius * ((1 - intricate) * cos(theta) + intricate * (.68 * cos(theta) + .32 * cos(k * theta + phi)))
            out[1] = radius * ((1 - intricate) * (1 - .32 * eccentric) * sin(theta) +
                intricate * (.68 * sin(theta) - .32 * sin(k * theta + phi)))
        }
    }

    private class Ensemble(val voice: RevelVoice, val m: Map<String, Float>, val probe: Probe, val hz: Double) {
        val re = DoubleArray(COUNT)
        val im = DoubleArray(COUNT)
        val cr = DoubleArray(COUNT)
        val sr = DoubleArray(COUNT)
        val omega = DoubleArray(COUNT)
        val radiation = DoubleArray(COUNT)
        val contact = DoubleArray(MODES)
        val started = IntArray(HEADS) { -1 }
        val duration = IntArray(HEADS)
        val strength = DoubleArray(HEADS)
        val kinds = IntArray(HEADS)
        val budgets = DoubleArray(HEADS)
        val ratios = arrayOf(doubleArrayOf(1.0, 1.63, 2.37, 3.81, 5.14),
            doubleArrayOf(1.0, 1.51, 2.26, 3.49, 4.72), doubleArrayOf(1.0, 1.94, 2.79, 4.08, 5.37),
            doubleArrayOf(1.0, 1.69, 2.57, 3.67, 4.96), doubleArrayOf(1.0, 1.87, 2.92, 4.31, 5.86),
            doubleArrayOf(1.0, 2.01, 3.03, 4.19, 5.31))
        var pressure = 0.0
        var bristle = 0.0
        var poweredWork = 0.0
        var passiveLoss = 0.0
        var maxEnergy = 0.0
        var maxFloorEnergy = 0.0
        val skin = m.getValue("SKIN").toDouble()
        val floorExchange = .5 * (1 - exp(-2 * (3.8 + 2.0 * skin) * DT))
        val sources = DoubleArray(HEADS)
        var floorSample = 0.0
        var rootSample = 0.0
        var energy = 0.0
        var floorEnergy = 0.0
        init {
            for (head in 0 until HEADS) for (mode in 0 until MODES) {
                val i = head * MODES + mode
                // The broad pulse's strong lower body modes remain close to
                // root harmonics; its higher pair and the other skins remain
                // inharmonic. Large spacing changes here pulled virtual pitch
                // below C3 despite an accurately tuned fundamental.
                val spacing = if (head == 5) {
                    if (mode <= 2) .995 + .01 * skin else .98 + .04 * skin
                } else .94 + .12 * skin
                val r = if (mode == 0) 1.0 else ratios[head][mode] * spacing
                omega[i] = 2 * PI * hz * r
                val upper = (if (head == 5) doubleArrayOf(1.0, 2.55, 3.975, 4.23, 4.41)
                    else doubleArrayOf(1.0, .45, .32, .21, .12))[mode]
                val skinRadiation = if (head == 5) .85 + .55 * skin else .55 + .9 * skin
                radiation[i] = upper * (if (mode == 0) 1.0 else skinRadiation) *
                    ((16_000 - hz * r) / 4000).coerceIn(0.0, 1.0)
            }
            for (mode in 0..3) {
                omega[FLOOR + mode] = 2 * PI * hz * doubleArrayOf(1.0, 1.73, 2.63, 3.97)[mode]
                radiation[FLOOR + mode] = .11 / (1 + mode)
            }
            coefficients()
        }
        fun coefficients() {
            for (i in 0 until COUNT) {
                val head = i / MODES
                val mode = i % MODES
                val tail = if (i >= FLOOR) 1.15 else {
                    // Pulse body modes radiate promptly, then yield to its long
                    // root-bearing tail rather than sustaining a virtual note.
                    val base = when (head) { 0 -> 1.35; 1 -> 1.65; 2 -> 1.45; 3 -> 1.3; 4 -> 1.1
                        else -> if (mode == 0) 2.5 else .90 }
                    base * (1.12 - .32 * skin) / (1 + .42 * mode)
                }
                val load = if (head == 1) pressure else 0.0
                val bend = if (head == 1) 2.0.pow(-(.018 + .014 * (1 - skin)) * load) else 1.0
                val loss = exp(-LN1000 * DT * (1 + .28 * load) / tail)
                val phase = omega[i] * bend * DT
                cr[i] = loss * cos(phase)
                sr[i] = loss * sin(phase)
            }
        }
        fun start(e: Event, sample: Int) {
            val h = e.head
            started[h] = sample
            duration[h] = (e.durationSeconds * INTERNAL_RATE).roundToInt().coerceAtLeast(1)
            strength[h] = e.energy
            kinds[h] = when (e.kind) { "edge" -> 1; "touch" -> 2; "finger", "emphasis" -> 3; else -> 0 }
            budgets[h] = if (h == 0) e.energy * .30 else e.energy * e.energy * .85
        }
        private fun exchange(i: Int, j: Int, a: Double) {
            val d = im[j] - im[i]
            im[i] += a * d
            im[j] -= a * d
            passiveLoss += a * (1 - a) * d * d
        }
        private fun stroke(h: Int, sample: Int) {
            val at = sample - started[h]
            if (started[h] < 0 || at !in 0 until duration[h]) return
            val u = (at + .5) / duration[h]
            val broad = h == 5
            val envelope = if (broad) sin(PI * u).pow(2) else sin(PI * u)
            val width = duration[h] * DT
            var dot = 0.0
            var norm = 0.0
            for (j in 0 until MODES) {
                val root = if (kinds[h] == 1) .88 else if (kinds[h] == 2) .72 else 1.0
                val upper = when (kinds[h]) { 1 -> .80; 2 -> .10; 3 -> .90; else -> .42 }
                val shape = if (j == 0) root else (if (broad) .90 else upper) / sqrt(j.toDouble())
                contact[j] = shape
                dot += im[h * MODES + j] * shape
                norm += shape * shape
            }
            // A mallet first compresses its skin, then remains in broad compliant
            // contact. The finite compression lobe excites real body modes that
            // a long smooth force otherwise cancels. Both spend one work budget.
            val carrier = if (broad) .40 + .60 * cos(2 * PI * hz * at * DT) else 1.0
            val edgeSeconds = min(width, .0012 + .0008 * (1 - skin))
            val edgeU = at * DT / edgeSeconds
            val compression = if (broad && edgeU < 1) sin(PI * edgeU) / edgeSeconds else 0.0
            val contactForce = if (broad) .45 * envelope * carrier / width + .65 * compression else envelope / width
            val requested = .85 * strength[h] * contactForce * DT
            val proposedWork = requested * dot + .5 * requested * requested * norm
            val discriminant = sqrt(dot * dot + 2 * budgets[h] * norm)
            val lower = (-dot - discriminant) / norm
            val upper = (-dot + discriminant) / norm
            val impulse = if (proposedWork > budgets[h] && proposedWork > 0) requested.coerceIn(lower, upper) else requested
            val work = impulse * dot + .5 * impulse * impulse * norm
            for (j in 0 until MODES) im[h * MODES + j] += impulse * contact[j]
            if (work >= 0) { budgets[h] = max(0.0, budgets[h] - work); poweredWork += work }
            else passiveLoss -= work
            if (kinds[h] == 2) for (j in 0 until MODES) {
                val i = h * MODES + j
                val damping = exp(-140 * DT * envelope)
                val before = .5 * (re[i] * re[i] + im[i] * im[i])
                re[i] *= damping; im[i] *= damping
                passiveLoss += before * (1 - damping * damping)
            }
        }
        private fun friction(sample: Int, time: Double) {
            val at = sample - started[0]
            if (started[0] < 0 || at !in 0 until duration[0]) {
                bristle *= exp(-300 * DT)
                return
            }
            val u = (at + .5) / duration[0]
            val pressureEnvelope = sin(PI * u).pow(.75)
            // Seed-independent microscopic texture closes with the phrase in HOLD.
            val grain = sin(2 * PI * hz * time) + .16 * sin(2 * PI * hz * 3 * time + .71)
            val surface = .20 * strength[0] * pressureEnvelope * (.36 + .64 * grain)
            val b0 = 1.0; val b1 = .30 + .12 * skin; val b2 = .16
            val contactVelocity = im[0] * b0 + im[1] * b1 + im[2] * b2
            val slip = surface - contactVelocity
            val stribeck = .30 + .70 * exp(-(slip / (.025 + .02 * (1 - skin))).pow(2))
            bristle += (slip - bristle) * (1 - exp(-(.9 + 2 * skin) * 900 * DT))
            val rate = (700 + 3600 * skin) * pressureEnvelope * stribeck *
                (1 + .25 * tanh(bristle * 12)).coerceIn(.75, 1.25)
            val norm = b0 * b0 + b1 * b1 + b2 * b2
            val alpha = rate * DT / (1 + rate * DT * norm)
            var impulse = alpha * slip
            val input = impulse * surface
            if (input > 0) impulse *= min(1.0, budgets[0] / input)
            val change = impulse * contactVelocity + .5 * impulse * impulse * norm
            val supplied = impulse * surface
            if (supplied >= 0) { budgets[0] = max(0.0, budgets[0] - supplied); poweredWork += supplied }
            else passiveLoss -= supplied
            passiveLoss += max(0.0, supplied - change)
            im[0] += impulse * b0; im[1] += impulse * b1; im[2] += impulse * b2
        }
        fun tick(sample: Int, time: Double, input: Boolean) {
            val age = sample - started[1]
            val pressureTarget = if (input && started[1] >= 0 && age in 0 until duration[1] + (INTERNAL_RATE * .12).toInt())
                strength[1] * exp(-max(0.0, age * DT) / .09) else 0.0
            pressure += (pressureTarget.coerceIn(0.0, 1.0) - pressure) * (1 - exp(-65 * DT))
            for (i in 0 until COUNT) {
                val q = re[i]; val v = im[i]
                val nq = cr[i] * q + sr[i] * v
                val nv = cr[i] * v - sr[i] * q
                re[i] = if (abs(nq) < 1e-25) 0.0 else nq
                im[i] = if (abs(nv) < 1e-25) 0.0 else nv
                passiveLoss += max(0.0, .5 * (q * q + v * v - nq * nq - nv * nv))
            }
            if (probe.floor) for (h in 0 until HEADS) for (j in 0..3)
                exchange(h * MODES + j, FLOOR + j, floorExchange * (if (j == 0) .25 else .50 / j))
            if (input) { for (h in 1 until HEADS) stroke(h, sample); friction(sample, time) }
            else bristle *= exp(-300 * DT)
            energy = 0.0; floorEnergy = 0.0; floorSample = 0.0; rootSample = 0.0
            for (h in 0 until HEADS) {
                var sound = 0.0
                for (j in 0 until MODES) {
                    val i = h * MODES + j
                    sound += re[i] * radiation[i]
                    energy += .5 * (re[i] * re[i] + im[i] * im[i])
                }
                sources[h] = sound
                rootSample += re[h * MODES]
            }
            for (j in 0..3) {
                val i = FLOOR + j
                floorSample += re[i] * radiation[i]
                floorEnergy += .5 * (re[i] * re[i] + im[i] * im[i])
            }
            energy += floorEnergy
            check(energy.isFinite() && energy < 100.0) { "REVEL internal acoustic energy escaped its contact budget" }
            maxEnergy = max(maxEnergy, energy); maxFloorEnergy = max(maxFloorEnergy, floorEnergy)
        }
        fun state(): DoubleArray = re + im + doubleArrayOf(pressure, bristle) + budgets
    }

    fun render(voice: RevelVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        configuration: RevelConfig = RevelConfig(), normalize: Boolean = true): Snip =
        perform(voice, macros, velocity, configuration, Probe(), normalize).snip
    fun renderLoop(voice: RevelVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        configuration: RevelConfig = RevelConfig(), normalize: Boolean = true): FloatArray =
        perform(voice, macros + ("HOLD" to 1f), velocity, configuration, Probe(), normalize).snip.samples
    fun inspect(voice: RevelVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
        configuration: RevelConfig = RevelConfig(), probe: Probe = Probe()): Report =
        perform(voice, macros, velocity, configuration, probe, true)

    private fun relativeError(a: DoubleArray, b: DoubleArray): Double {
        var difference = 0.0; var energy = 0.0
        for (i in a.indices) { val d = a[i] - b[i]; difference += d * d; energy += b[i] * b[i] }
        return difference / max(energy, 1e-24)
    }
    private fun cyclicDecimate(two: FloatArray): FloatArray {
        val pad = 128 * Dsp.OVERSAMPLE
        val extended = FloatArray(two.size + 2 * pad) { two[Math.floorMod(it - pad, two.size)] }
        Tide.bandLimit(extended, INTERNAL_RATE)
        val result = Dsp.decimate(extended, RATE)
        return result.copyOfRange(pad / Dsp.OVERSAMPLE, pad / Dsp.OVERSAMPLE + two.size / Dsp.OVERSAMPLE)
    }
    private fun finish(raw: FloatArray): FloatArray {
        Tide.bandLimit(raw, INTERNAL_RATE)
        return Dsp.decimate(raw, RATE)
    }
    private fun perform(voice: RevelVoice, macros: Map<String, Float>, velocity: Float,
        c: RevelConfig, probe: Probe, normalize: Boolean): Report {
        val m = settled(macros, voice)
        val p = plan(m, c)
        val geometry = Geometry(voice, m, c, p, probe.coincidentMics)
        val v = if (velocity.isFinite()) velocity.coerceIn(0f, 1f).toDouble() else 1.0
        val phraseEvents = schedule(voice, m, c, p, v, probe)
        val periodN = p.frames * Dsp.OVERSAMPLE
        val phraseN = p.phraseFrames * Dsp.OVERSAMPLE
        // Four complete candidate periods include pressure, contact and real history.
        val preroll = if (p.held) 3 else 0
        val n = probe.seconds?.let { (it * INTERNAL_RATE).roundToInt() }
            ?: if (p.held) periodN * (preroll + 2) else periodN
        val recordedStart = if (p.held && probe.seconds == null) preroll * periodN else 0
        val recordedN = n - recordedStart
        val rawInternal = FloatArray(recordedN)
        val sourceInternal = if (probe.recordSources) Array(HEADS) { FloatArray(recordedN) } else null
        val floorInternal = if (probe.recordSources) FloatArray(recordedN) else null
        val micInternal = if (probe.recordMics) Array(geometry.count) { FloatArray(recordedN) } else null
        val energyTrace = if (probe.recordSources) FloatArray((recordedN + Dsp.OVERSAMPLE - 1) / Dsp.OVERSAMPLE) else null
        val floorTrace = energyTrace?.let { FloatArray(it.size) }
        val histories = Array(HEADS + 2) { FloatArray(HISTORY) }
        var write = 0
        val paths = ArrayList<PathFrame>()
        val count = geometry.count * HEADS
        val gain = DoubleArray(count)
        val gainStep = DoubleArray(count)
        val delay = DoubleArray(count)
        val delayStep = DoubleArray(count)
        val coefficient = DoubleArray(count)
        val coefficientStep = DoubleArray(count)
        val low = DoubleArray(count)
        val reflectedLow = DoubleArray(count)
        val pos = DoubleArray(2)
        val endPos = DoubleArray(2)
        val micSamples = DoubleArray(geometry.count)
        val ensemble = Ensemble(voice, m, probe, frequencyFor(voice, m.getValue("TUNE")).toDouble())
        var maxGain = 0.0; var maxDelayRate = 0.0
        var eventIndex = 0
        var nextEvent = if (phraseEvents.isEmpty()) Int.MAX_VALUE else (phraseEvents[0].timeSeconds * INTERNAL_RATE).roundToInt()
        var previousState: DoubleArray? = null
        var stateError = 0.0
        val centralDelay = sqrt(RING_RADIUS * RING_RADIUS + MIC_HEIGHT * MIC_HEIGHT) * INTERNAL_RATE / SOUND_SPEED
        val dcCoefficient = exp(-2 * PI * 12 * DT)
        var dcInput = 0.0; var dcOutput = 0.0
        fun snapshot(): DoubleArray {
            val sourceState = ensemble.state()
            val out = DoubleArray(sourceState.size + low.size * 2 + histories.size * HISTORY + 2)
            sourceState.copyInto(out)
            var at = sourceState.size
            for (value in low) out[at++] = value
            for (value in reflectedLow) out[at++] = value
            // Chronological buffer order compares histories independent of ring ownership.
            for (line in histories) for (i in 0 until HISTORY) out[at++] = line[(write + i) % HISTORY].toDouble()
            out[at++] = dcInput; out[at] = dcOutput
            return out
        }
        for (sample in 0 until n) {
            val local = if (p.held) sample % periodN else sample
            val phraseLocal = if (p.held || sample < p.cycles * phraseN) sample % phraseN else -1
            if (phraseLocal == 0) { eventIndex = 0; nextEvent = phraseEvents.firstOrNull()?.let { (it.timeSeconds * INTERNAL_RATE).roundToInt() } ?: Int.MAX_VALUE }
            val input = (p.held || sample < p.cycles * phraseN) &&
                (probe.inputOffSeconds == null || sample * DT < probe.inputOffSeconds)
            if (input && phraseLocal >= 0) while (eventIndex < phraseEvents.size && phraseLocal >= nextEvent) {
                ensemble.start(phraseEvents[eventIndex++], sample)
                nextEvent = if (eventIndex < phraseEvents.size) (phraseEvents[eventIndex].timeSeconds * INTERNAL_RATE).roundToInt() else Int.MAX_VALUE
            }
            if (local % STRIDE == 0) {
                ensemble.coefficients()
                val block = if (p.held) min(STRIDE, periodN - local) else STRIDE
                val time = local * DT
                val endTime = (local + block) * DT
                for (mic in 0 until geometry.count) {
                    geometry.position(mic, time, pos); geometry.position(mic, endTime, endPos)
                    for (h in 0 until HEADS) {
                        val i = mic * HEADS + h
                        val d = sqrt((pos[0] - stationX[h]).pow(2) + (pos[1] - stationY[h]).pow(2) + MIC_HEIGHT * MIC_HEIGHT)
                        val endD = sqrt((endPos[0] - stationX[h]).pow(2) + (endPos[1] - stationY[h]).pow(2) + MIC_HEIGHT * MIC_HEIGHT)
                        check(d >= MIN_CLEARANCE)
                        val g = (.30 + 1.65 / (.55 + d)).coerceIn(.55, 1.95)
                        val endG = (.30 + 1.65 / (.55 + endD)).coerceIn(.55, 1.95)
                        gain[i] = g; gainStep[i] = (endG - g) / block
                        // Reception-time mean propagation plus compressed moving excursion.
                        val compression = geometry.delayMotionScale[mic]
                        val d0 = centralDelay + compression * (d * INTERNAL_RATE / SOUND_SPEED - centralDelay)
                        val d1 = centralDelay + compression * (endD * INTERNAL_RATE / SOUND_SPEED - centralDelay)
                        delay[i] = d0; delayStep[i] = ((d1 - d0) / block).coerceIn(-MAX_DELAY_RATE, MAX_DELAY_RATE)
                        val cutoff = (10500 / (1 + .80 * d)) * (.78 + .44 * m.getValue("SKIN"))
                        val endCutoff = (10500 / (1 + .80 * endD)) * (.78 + .44 * m.getValue("SKIN"))
                        coefficient[i] = 1 - exp(-2 * PI * cutoff * DT)
                        coefficientStep[i] = ((1 - exp(-2 * PI * endCutoff * DT)) - coefficient[i]) / block
                        maxGain = max(maxGain, g); maxDelayRate = max(maxDelayRate, abs(delayStep[i]))
                        if (probe.recordPaths && sample >= recordedStart && local % (STRIDE * 24) == 0)
                            paths.add(PathFrame((sample - recordedStart) * DT, mic, h, pos[0], pos[1], d, g, d0))
                    }
                }
            }
            // Periodic microtexture is restarted by phase, never a new random draw.
            ensemble.tick(sample, (if (p.held) local else sample) * DT, input)
            for (h in 0 until HEADS) histories[h][write] = ensemble.sources[h].toFloat()
            histories[HEADS][write] = ensemble.floorSample.toFloat()
            histories[HEADS + 1][write] = ensemble.rootSample.toFloat()
            var diffuse = 0.0
            for (h in 0 until HEADS) diffuse += Dsp.tap(histories[h], write, centralDelay.toFloat())
            // Substituting .20 of the existing .50 root anchor with actual head
            // radiation preserves exactly the root gain and a restrained common
            // body. Independently phased microphones still hear moving details.
            val anchor = .30 * Dsp.tap(histories[HEADS + 1], write, centralDelay.toFloat()) + .20 * diffuse
            val floorPickup = if (probe.floor) Dsp.tap(histories[HEADS], write, (centralDelay + 330).toFloat()).toDouble() else 0.0
            for (mic in 0 until geometry.count) {
                var sound = anchor + .18 * floorPickup
                for (h in 0 until HEADS) {
                    val i = mic * HEADS + h
                    val arriving = Dsp.tap(histories[h], write, delay[i].toFloat()).toDouble()
                    low[i] += coefficient[i] * (arriving - low[i])
                    val reflected = Dsp.tap(histories[h], write, (delay[i] + 710 + 47 * h).toFloat()).toDouble()
                    reflectedLow[i] += .22 * coefficient[i] * (reflected - reflectedLow[i])
                    sound += .50 * gain[i] * low[i] + .045 * reflectedLow[i]
                    gain[i] += gainStep[i]; delay[i] += delayStep[i]; coefficient[i] += coefficientStep[i]
                }
                micSamples[mic] = sound * .35
            }
            // Arithmetic mean is coherent-safe: coincident microphones reproduce one.
            var pickup = 0.0
            for (sound in micSamples) pickup += sound / geometry.count
            val filtered = pickup - dcInput + dcCoefficient * dcOutput
            dcInput = pickup; dcOutput = filtered
            if (sample >= recordedStart) {
                val at = sample - recordedStart
                rawInternal[at] = filtered.toFloat()
                if (sourceInternal != null) {
                    for (h in 0 until HEADS) sourceInternal[h][at] = ensemble.sources[h].toFloat()
                    floorInternal!![at] = ensemble.floorSample.toFloat()
                    if (at % Dsp.OVERSAMPLE == 0) {
                        energyTrace!![at / Dsp.OVERSAMPLE] = ensemble.energy.toFloat()
                        floorTrace!![at / Dsp.OVERSAMPLE] = ensemble.floorEnergy.toFloat()
                    }
                }
                if (micInternal != null) for (mic in 0 until geometry.count) micInternal[mic][at] = micSamples[mic].toFloat()
            }
            write = (write + 1) % HISTORY
            if (p.held && (sample + 1) % periodN == 0) {
                val state = snapshot()
                if (previousState != null) stateError = relativeError(previousState!!, state)
                previousState = state
            }
        }
        var loopEvidence: LoopEvidence? = null
        val raw: FloatArray
        if (p.held && probe.seconds == null) {
            val two = cyclicDecimate(rawInternal)
            var difference = 0.0; var signal = 0.0
            for (i in 0 until p.frames) {
                val d = two[i + p.frames] - two[i].toDouble()
                difference += d * d; signal += two[i + p.frames].toDouble() * two[i + p.frames]
            }
            val convergence = difference / max(signal, 1e-24)
            val seam = if (signal < 1e-24) 0.0 else Keys.seamError(two, p.frames)
            loopEvidence = LoopEvidence(seam, convergence, stateError, preroll)
            check(seam < 1e-3 && convergence < 1e-3 && stateError < 1e-3) { "REVEL complete held state did not settle: $loopEvidence" }
            // The standard unity-sum wrap corrects only the final tiny residual.
            wrap(two, p.frames)
            raw = two.copyOfRange(p.frames, two.size)
        } else { raw = finish(rawInternal); if (!p.held) Dsp.fadeTail(raw) }
        fun tapFinish(data: FloatArray): FloatArray = if (p.held && probe.seconds == null)
            cyclicDecimate(data).copyOfRange(p.frames, p.frames * 2) else finish(data)
        val sources = sourceInternal?.let { Array(HEADS) { h -> tapFinish(it[h]) } }
        val floorSignal = floorInternal?.let { tapFinish(it) }
        val microphones = micInternal?.let { Array(geometry.count) { mic -> tapFinish(it[mic]) } }
        val offset = if (p.held && probe.seconds == null) p.frames else 0
        val energy = energyTrace?.copyOfRange(offset, energyTrace.size)
        val floorEnergy = floorTrace?.copyOfRange(offset, floorTrace.size)
        val out = raw.copyOf()
        if (normalize) Dsp.levelTo(out, RATE, Dsp.MELODIC_LOUDNESS_TARGET, .99f)
        val events = ArrayList<Event>()
        val eventCycles = if (p.held) p.cycles else p.cycles
        for (cycle in 0 until eventCycles) for (event in phraseEvents) {
            val e = event.copy(timeSeconds = event.timeSeconds + cycle * p.rates.phraseSeconds)
            if ((probe.inputOffSeconds == null || e.timeSeconds < probe.inputOffSeconds) &&
                (probe.seconds == null || e.timeSeconds < probe.seconds)) events.add(e)
        }
        return Report(Snip(out, channels = 1, sampleRate = RATE), raw, sources, floorSignal, microphones,
            events, paths, p.rates, ensemble.maxEnergy, ensemble.maxFloorEnergy, ensemble.poweredWork,
            ensemble.passiveLoss, 0, maxGain, maxDelayRate, loopEvidence, energy, floorEnergy)
    }
    private fun wrap(two: FloatArray, start: Int) {
        val fade = 1024; val tail = 256; val need = fade + tail
        for (i in 0 until need) {
            val t = (i / fade.toDouble()).coerceAtMost(1.0)
            val w = t * t * (3 - 2 * t)
            val from = start - need + i; val to = two.size - need + i
            two[to] = ((1 - w) * two[to] + w * two[from]).toFloat()
        }
    }
}
