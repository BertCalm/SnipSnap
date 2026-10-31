package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class CairnVoice { KNOCK, ANSWER }

/**
 * Two-voice reconstruction, not the source of the earlier recorded six-voice audition.
 * One bead is launched, four pocket coordinates exchange motion through a fifth,
 * shared mount coordinate, and steep groove edges withdraw kinetic energy. The
 * return faces are smooth. KNOCK has a stiff, lossy mounting; ANSWER's mobile mount
 * wakes octave, twelfth and double-octave contacts without scheduling their times.
 *
 * Mechanics are a literal, normalised linear mass/spring/damper network, advanced
 * with passive implicit midpoint steps at 2 kHz. Geometry and material constants
 * are design shapes, not measurements of stone. Acoustic modes are a one-way
 * readout of withdrawn work, not a coupled elastic collision simulation. A common
 * fixed calibration maps that work to impulses; late contacts are never levelled
 * separately. No limiter participates in the mechanical stability argument.
 */
object Cairn {
    const val MODEL_VERSION = 1
    const val DEFAULT_MIDI = 60
    // R1 proves the source audition's C3..C5 span. The short C2 KNOCK is
    // classified as a tom; lower registers need a separate voicing decision.
    const val MIN_MIDI = 48
    const val MAX_MIDI = 72
    const val RATE = Dsp.RATE
    const val HOLD_RESERVED = .99f
    private const val MECHANICAL_RATE = 2000
    private const val MECHANICAL_SECONDS = 3.5
    private const val MAX_EVENTS = 256
    private const val CACHE_SIZE = 24
    private const val N = 5
    private val controls = listOf("TUNE", "WEIGHT", "SLOPE", "GROOVE", "FRICTION", "BODY", "HOLD")

    fun macrosFor(voice: CairnVoice): List<MacroSpec> {
        val d = when (voice) {
            CairnVoice.KNOCK -> floatArrayOf(.5f, .45f, .30f, .25f, .15f, .35f, 0f)
            CairnVoice.ANSWER -> floatArrayOf(.5f, .60f, .45f, .45f, .40f, .75f, 0f)
        }
        return controls.mapIndexed { i, key -> MacroSpec(key, d[i], if (key == "HOLD") 0f else .5f) }
    }
    fun defaults(voice: CairnVoice) = macrosFor(voice).associate { it.name to it.default }
    fun settled(voice: CairnVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(voice).toMutableMap().apply {
            for ((key, value) in macros) if (key in this && value.isFinite()) this[key] = value.coerceIn(0f, 1f)
        }
    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: CairnVoice) = DEFAULT_MIDI
    fun midiFor(@Suppress("UNUSED_PARAMETER") voice: CairnVoice, tune: Float, midi: Int = DEFAULT_MIDI): Int =
        (midi + ((tune.coerceIn(0f, 1f) - .5f) * 24).roundToInt()).coerceIn(MIN_MIDI, MAX_MIDI)
    fun frequencyFor(midi: Int) = Keys.midiHz(midi)
    fun scramble(voice: CairnVoice, random: Random = Random.Default, temperature: Float = .35f,
                 near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(settled(voice, if (near is CairnPatch) near.macros else emptyMap()), temperature, random)
            .toMutableMap().apply { this["HOLD"] = getValue("HOLD").coerceAtMost(.95f) }

    data class Event(val seconds: Double, val stone: Int, val groove: Int, val work: Double,
                     val speedBefore: Double, val speedAfter: Double)
    data class State(val seconds: Double, val energy: Double, val withdrawnWork: Double,
                     val positions: List<Double>)
    data class Motion(val events: List<Event>, val states: List<State>, val initialEnergy: Double,
                      val finalEnergy: Double, val withdrawnWork: Double, val dissipatedWork: Double,
                      val largestEnergyIncrease: Double, val terminalBrakeWork: Double)
    data class Diagnostics(val motion: Motion, val rawPeak: Double, val rawRms: Double,
                           val maxModalEnergy: Double, val tailEnergyIncrease: Double,
                           val firstContactFrame: Int, val outputStartFrame: Int,
                           val mechanicsCacheHit: Boolean, val mechanicsMs: Double, val audioMs: Double,
                           val requestedHz: Double, val internalRate: Int)
    data class Render(val raw: FloatArray, val full: FloatArray, val samples: FloatArray,
                      val diagnostics: Diagnostics)
    data class Options(val disableCoupling: Boolean = false, val oversample: Int = Dsp.OVERSAMPLE,
                       val bypassCache: Boolean = false)

    // Motion has no note or HOLD dependency. Save every quantity that does affect
    // it in the key; fresh acoustic states are never cached or shared across notes.
    private data class MotionKey(val model: Int, val voice: CairnVoice, val weight: Float,
                                 val slope: Float, val groove: Float, val friction: Float,
                                 val body: Float, val velocity: Float, val uncoupled: Boolean)
    private val cache = object : LinkedHashMap<MotionKey, Motion>(CACHE_SIZE, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MotionKey, Motion>?) = size > CACHE_SIZE
    }
    internal fun clearMotionCache() = synchronized(cache) { cache.clear() }
    internal fun motionCacheSize() = synchronized(cache) { cache.size }

    private fun checked(voice: CairnVoice, macros: Map<String, Float>): Map<String, Float> =
        settled(voice, macros).also {
            require(it.getValue("HOLD") < HOLD_RESERVED) {
                "CAIRN's first two-voice reconstruction reserves HOLD's top step; no loop has been accepted"
            }
        }

    fun motion(voice: CairnVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
               disableCoupling: Boolean = false): Motion {
        require(velocity.isFinite() && velocity in 0f..1f)
        val m = checked(voice, macros)
        return calculateMotion(key(voice, m, velocity, disableCoupling))
    }
    private fun key(voice: CairnVoice, m: Map<String, Float>, velocity: Float, uncoupled: Boolean) =
        MotionKey(MODEL_VERSION, voice, m.getValue("WEIGHT"), m.getValue("SLOPE"), m.getValue("GROOVE"),
            m.getValue("FRICTION"), m.getValue("BODY"), velocity, uncoupled)

    /** Tiny dense solve at setup only. No geometry or iterative solve runs on the audio clock. */
    private fun inverse(a: Array<DoubleArray>): Array<DoubleArray> {
        val x = Array(N) { i -> DoubleArray(2 * N) { j -> if (j < N) a[i][j] else if (j - N == i) 1.0 else 0.0 } }
        for (c in 0 until N) {
            val pivot = (c until N).maxBy { abs(x[it][c]) }
            val tmp = x[c]; x[c] = x[pivot]; x[pivot] = tmp
            check(abs(x[c][c]) > 1e-12) { "singular CAIRN mechanics" }
            val scale = x[c][c]
            for (j in c until 2 * N) x[c][j] /= scale
            for (i in 0 until N) if (i != c) {
                val f = x[i][c]
                for (j in c until 2 * N) x[i][j] -= f * x[c][j]
            }
        }
        return Array(N) { i -> DoubleArray(N) { j -> x[i][j + N] } }
    }

    private fun calculateMotion(key: MotionKey): Motion {
        val dt = 1.0 / MECHANICAL_RATE
        val answer = key.voice == CairnVoice.ANSWER
        val mass = .55 + 1.3 * key.weight
        val masses = DoubleArray(N) { if (it == 4) .65 + key.body else mass }
        val k = Array(N) { DoubleArray(N) }
        for (i in 0 until N) k[i][i] = if (i == 4) 8.0 else 2.2 + 3 * key.slope
        val coupling = if (key.uncoupled) 0.0 else if (answer) 2.0 + 9 * key.body else .12 + .35 * key.body
        for (i in 0..3) {
            k[i][i] += coupling; k[4][4] += coupling
            k[i][4] -= coupling; k[4][i] -= coupling
        }
        val damping = DoubleArray(N) {
            if (it == 4) .45 else if (answer) .17 + .55 * key.friction else 2.4 + 2.4 * key.friction
        }
        val a = Array(N) { i -> DoubleArray(N) { j ->
            dt * dt / 4 * k[i][j] + if (i == j) masses[i] + dt / 2 * damping[i] else 0.0
        } }
        val inv = inverse(a)
        val b = Array(N) { DoubleArray(N) }
        val c = Array(N) { DoubleArray(N) }
        for (i in 0 until N) for (j in 0 until N) for (z in 0 until N) {
            b[i][j] += inv[i][z] * (-dt * dt / 4 * k[z][j] +
                if (z == j) masses[z] - dt / 2 * damping[z] else 0.0)
            c[i][j] -= dt * inv[i][z] * k[z][j]
        }
        var q = DoubleArray(N)
        var v = DoubleArray(N).also { it[0] = (1.15 + 1.3 * key.slope) * key.velocity }
        fun energy(): Double {
            var e = 0.0
            for (i in 0 until N) {
                e += .5 * masses[i] * v[i] * v[i]
                for (j in 0 until N) e += .5 * q[i] * k[i][j] * q[j]
            }
            return e
        }
        val seed = Dsp.seedFor("CAIRN", MODEL_VERSION, key.voice.name, "terrain")
        val noise = Dsp.Noise(seed)
        val count = 2 + (4 * key.groove).roundToInt()
        val edges = Array(4) { stone -> DoubleArray(count) { g ->
            val jitter = noise.next().toDouble() * .0008
            if (stone == 0) .12 + g * (.026 + .020 * (1 - key.groove)) + jitter
            else .018 + .007 * (stone - 1) + g * (.013 + .005 * (stone - 1)) + jitter
        } }
        val initial = energy()
        var lastEnergy = initial
        var withdrawn = 0.0
        var brakeWork = 0.0
        var largestIncrease = 0.0
        val events = mutableListOf<Event>()
        val states = mutableListOf(State(0.0, initial, 0.0, q.toList()))
        val lastContact = DoubleArray(4) { -1.0 }
        repeat((MECHANICAL_SECONDS * MECHANICAL_RATE).toInt()) { n ->
            val nv = DoubleArray(N)
            val nq = DoubleArray(N)
            for (i in 0 until N) {
                for (j in 0 until N) nv[i] += b[i][j] * v[j] + c[i][j] * q[j]
                nq[i] = q[i] + dt / 2 * (v[i] + nv[i])
            }
            val seconds = n * dt
            for (stone in 0..3) if (seconds - lastContact[stone] >= .018 && nv[stone] > .015) {
                for (g in edges[stone].indices) {
                    val edge = edges[stone][g]
                    // Only the steep outward face makes a knock. The same position
                    // coordinate drives both motion and contact detection.
                    if (q[stone] < edge && nq[stone] >= edge) {
                        val fraction = if (stone == 0) .16 + .10 * key.groove else .28
                        val before = nv[stone]
                        val work = .5 * masses[stone] * before * before * fraction
                        nv[stone] *= sqrt(1.0 - fraction)
                        withdrawn += work
                        val t = (edge - q[stone]) / (nq[stone] - q[stone])
                        events += Event(seconds + t * dt, stone, g, work, before, nv[stone])
                        lastContact[stone] = seconds
                        check(events.size <= MAX_EVENTS) { "CAIRN contact cap exceeded" }
                        break
                    }
                }
            }
            q = nq; v = nv
            // A finite cradle closes into a padded pocket after 2.5 s. Scaling
            // both coordinates and velocities is a disclosed dissipative gesture
            // approximation, not gravity or a solver correction. It can only
            // remove energy from this positive-definite quadratic network.
            if (seconds > 2.5) {
                val before = energy()
                val gain = exp(-12.0 * (seconds - 2.5) * dt)
                for (i in 0 until N) { q[i] *= gain; v[i] *= gain }
                brakeWork += before - energy()
            }
            val e = energy()
            largestIncrease = max(largestIncrease, e - lastEnergy)
            lastEnergy = e
            if ((n + 1) % 20 == 0) states += State((n + 1) * dt, e, withdrawn, q.toList())
        }
        return Motion(events.sortedWith(compareBy<Event> { it.seconds }.thenBy { it.stone }).toList(), states.toList(),
            initial, lastEnergy, withdrawn, initial - lastEnergy - withdrawn, largestIncrease, brakeWork)
    }

    private fun ringSeconds(voice: CairnVoice, m: Map<String, Float>): Double =
        (if (voice == CairnVoice.KNOCK) .32 + .48 * m.getValue("BODY")
        else .85 + 1.1 * m.getValue("BODY")) * (1.0 + .6 * m.getValue("HOLD"))

    private fun frameCount(voice: CairnVoice, m: Map<String, Float>, motion: Motion, contact: Boolean): Int {
        val last = motion.events.lastOrNull()?.seconds ?: 0.0
        val firstFrame = ((motion.events.firstOrNull()?.seconds ?: 0.0) * RATE).roundToInt()
        val cue = if (contact) (firstFrame - (.002 * RATE).roundToInt()).coerceAtLeast(0) else 0
        return max(1, ceil(max(.12, last + ringSeconds(voice, m)) * RATE).toInt() - cue)
    }
    fun drumClassFor(voice: CairnVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
                     startAtContact: Boolean = true): DrumClass {
        val m = checked(voice, macros)
        val motion = cachedMotion(key(voice, m, velocity, false), false).first
        return if (frameCount(voice, m, motion, startAtContact) > (1.5 * RATE).toInt()) DrumClass.LOOP else DrumClass.TONAL
    }
    private fun cachedMotion(key: MotionKey, bypass: Boolean): Pair<Motion, Boolean> {
        if (!bypass) synchronized(cache) { cache[key]?.let { return it to true } }
        val result = calculateMotion(key)
        if (!bypass) synchronized(cache) { cache[key] = result }
        return result to false
    }

    fun render(voice: CairnVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
               velocity: Float = 1f, startAtContact: Boolean = true): Snip =
        Snip(inspect(voice, macros, midi, velocity, startAtContact).samples, 1, RATE)

    fun inspect(voice: CairnVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
                velocity: Float = 1f, startAtContact: Boolean = true, options: Options = Options()): Render {
        require(midi in MIN_MIDI..MAX_MIDI && velocity.isFinite() && velocity in 0f..1f)
        require(options.oversample == 4 || options.oversample == 8)
        val m = checked(voice, macros)
        val start = System.nanoTime()
        val (motion, hit) = cachedMotion(key(voice, m, velocity, options.disableCoupling), options.bypassCache)
        val audioStart = System.nanoTime()
        val internalRate = RATE * options.oversample
        val hz = frequencyFor(midiFor(voice, m.getValue("TUNE"), midi)).toDouble()
        val frames = frameCount(voice, m, motion, false)
        val raw = FloatArray(frames * options.oversample)
        val bank = Modes.Bank(8, internalRate)
        val ring = ringSeconds(voice, m)
        val ratios = if (voice == CairnVoice.KNOCK) doubleArrayOf(1.0, 2.756, 5.404, 2.0, 3.0, 4.0, 1.0, 1.59)
                     else doubleArrayOf(1.0, 2.4, 4.1, 2.0, 3.0, 4.0, 1.0, 1.59)
        for (i in ratios.indices) bank.tune(i, min(12_000.0, hz * ratios[i]), ring * when (i) {
            0 -> 1.0; 1 -> .42; 2 -> .22; in 3..5 -> .70; else -> .50
        })
        val pickup = doubleArrayOf(1.0, .60, .30, 1.0, .90, .80, .32, .16)
        val contact = Array(4) { stone -> DoubleArray(8).also { b ->
            if (stone == 0) { b[0] = 1.0; b[1] = .45; b[2] = .22 }
            else b[stone + 2] = 1.0
            b[6] = .12 + .23 * m.getValue("BODY"); b[7] = .08
            for (i in b.indices) if (hz * ratios[i] >= 12_000) b[i] = 0.0
        } }
        // A unit-area raised-cosine force has no dependence on sample rate.
        // Force width changes with mass and velocity. Its fixed work calibration
        // is shared by all stones; quiet answers cannot be inflated to root level.
        val width = (.00030 + .0010 * m.getValue("WEIGHT")) *
            (1.35 - .65 * velocity) * if (voice == CairnVoice.KNOCK) .60 else 1.0
        val pulseFrames = max(8, (width * internalRate).roundToInt())
        val kernel = DoubleArray(pulseFrames) { .5 - .5 * cos(2 * PI * (it + .5) / pulseFrames) }
        val area = kernel.sum() / internalRate
        val drives = Array(4) { FloatArray(raw.size) }
        for (event in motion.events) {
            val first = (event.seconds * internalRate).roundToInt()
            val impulse = .12 * sqrt(event.work)
            for (j in kernel.indices) if (first + j < raw.size) drives[event.stone][first + j] += (impulse * kernel[j] / area).toFloat()
        }
        val lastForce = (motion.events.lastOrNull()?.seconds?.times(internalRate)?.roundToInt() ?: 0) + pulseFrames + 2
        var maxModalEnergy = 0.0
        var tailIncrease = 0.0
        var previousEnergy = 0.0
        for (i in raw.indices) {
            for (stone in 0..3) if (drives[stone][i] != 0f) bank.drive(contact[stone], drives[stone][i].toDouble())
            bank.step()
            raw[i] = bank.velocityAlong(pickup).toFloat()
            // Probe the actual state independently of output conditioning.
            if (i % 32 == 0) {
                val e = bank.energy()
                maxModalEnergy = max(maxModalEnergy, e)
                if (i > lastForce + 32) tailIncrease = max(tailIncrease, e - previousEnergy)
                previousEnergy = e
            }
        }
        val rawPeak = raw.maxOf { abs(it.toDouble()) }
        val rawRms = sqrt(raw.sumOf { it.toDouble() * it } / raw.size)
        val limited = raw.copyOf()
        Tide.bandLimit(limited, internalRate)
        val full = if (options.oversample == Dsp.OVERSAMPLE) Dsp.decimate(limited, RATE)
            else Resampler.resample(Snip(limited, 1, internalRate), RATE).samples
        val mean = full.average().toFloat()
        val hp = Dsp.OnePole(RATE)
        for (i in full.indices) { val x = full[i] - mean; full[i] = x - hp.lp(x, 20f) }
        Dsp.levelTo(full, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(full, rate = RATE)
        val firstContact = ((motion.events.firstOrNull()?.seconds ?: 0.0) * RATE).roundToInt()
        val cue = if (startAtContact) (firstContact - (.002 * RATE).roundToInt()).coerceAtLeast(0) else 0
        val samples = if (cue == 0) full else full.copyOfRange(cue, full.size)
        return Render(raw, full, samples, Diagnostics(motion, rawPeak, rawRms, maxModalEnergy,
            tailIncrease, firstContact, cue, hit, (audioStart - start) / 1e6,
            (System.nanoTime() - audioStart) / 1e6, hz, internalRate))
    }
}
