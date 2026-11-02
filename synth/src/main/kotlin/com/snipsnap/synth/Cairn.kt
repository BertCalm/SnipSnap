package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class CairnVoice { KNOCK, ANSWER, TUMBLE }

/**
 * Three-voice reconstruction, not the source of the earlier recorded six-voice audition.
 * One bead is launched, four pocket coordinates exchange motion through a fifth,
 * shared mount coordinate, and steep groove edges withdraw kinetic energy. The
 * return faces are smooth. KNOCK has a stiff, lossy mounting; ANSWER's mobile mount
 * wakes octave, twelfth and double-octave contacts without scheduling their times.
 * TUMBLE instead uses a unilateral gravity/rebound law in CairnTumble: one
 * launched stone returns to a bed in successively shorter flights.
 *
 * KNOCK/ANSWER mechanics are a literal, normalised linear mass/spring/damper network, advanced
 * with passive implicit midpoint steps at 2 kHz. Geometry and material constants
 * are design shapes, not measurements of stone. Acoustic modes are a one-way
 * readout of withdrawn work, not a coupled elastic collision simulation. A common
 * fixed calibration maps that work to impulses; late contacts are never levelled
 * separately. No limiter participates in the mechanical stability argument.
 */
object Cairn {
    const val MODEL_VERSION = 3
    const val DEFAULT_MIDI = 60
    // R1 proves the source audition's C3..C5 span. The short C2 KNOCK is
    // classified as a tom; lower registers need a separate voicing decision.
    const val MIN_MIDI = 48
    const val MAX_MIDI = 72
    const val RATE = Dsp.RATE
    const val HOLD_RESERVED = .99f
    fun isLoop(macros: Map<String, Float>) = (macros["HOLD"] ?: 0f) >= HOLD_RESERVED
    fun loopSeconds(voice: CairnVoice) = if (voice == CairnVoice.ANSWER) 4.0 else 2.0
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
            CairnVoice.TUMBLE -> floatArrayOf(.5f, .35f, .55f, .65f, .20f, .30f, 0f)
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
                      val largestEnergyIncrease: Double, val terminalBrakeWork: Double,
                      val suppliedWork: Double = 0.0, val periodSeconds: Double = 0.0,
                      val settledCycles: Int = 0, val cycleResidual: Double = 0.0,
                      val precedingEvents: List<Event> = emptyList())
    data class Diagnostics(val motion: Motion, val rawPeak: Double, val rawRms: Double,
                           val maxModalEnergy: Double, val tailEnergyIncrease: Double,
                           val firstContactFrame: Int, val outputStartFrame: Int,
                           val mechanicsCacheHit: Boolean, val mechanicsMs: Double, val audioMs: Double,
                           val requestedHz: Double, val internalRate: Int,
                           val loopStartFrame: Int = 0, val seamError: Double? = null)
    data class Render(val raw: FloatArray, val full: FloatArray, val samples: FloatArray,
                      val diagnostics: Diagnostics)
    data class Options(val disableCoupling: Boolean = false, val oversample: Int = Dsp.OVERSAMPLE,
                       val bypassCache: Boolean = false, val fullLoopPreroll: Boolean = false)

    // Finite HOLD changes only ring time. The powered top step changes motion,
    // so its regime is part of the key. Pitch never rebuilds the mechanics.
    private data class MotionKey(val model: Int, val voice: CairnVoice, val weight: Float,
                                 val slope: Float, val groove: Float, val friction: Float,
                                 val body: Float, val velocity: Float, val uncoupled: Boolean,
                                 val powered: Boolean)
    private val cache = object : LinkedHashMap<MotionKey, Motion>(CACHE_SIZE, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MotionKey, Motion>?) = size > CACHE_SIZE
    }
    internal fun clearMotionCache() = synchronized(cache) { cache.clear() }
    internal fun motionCacheSize() = synchronized(cache) { cache.size }

    private fun checked(voice: CairnVoice, macros: Map<String, Float>, model: Int): Map<String, Float> =
        settled(voice, macros).also {
            require(model in 1..MODEL_VERSION) { "unsupported CAIRN model $model" }
            require(voice != CairnVoice.TUMBLE || model >= 3) { "CAIRN TUMBLE requires model 3" }
            require(model >= 2 || !isLoop(it)) {
                "CAIRN model 1 reserves HOLD's top step"
            }
        }

    fun motion(voice: CairnVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
               disableCoupling: Boolean = false, model: Int = MODEL_VERSION): Motion {
        require(velocity.isFinite() && velocity in 0f..1f)
        val m = checked(voice, macros, model)
        return calculateMotion(key(voice, m, velocity, disableCoupling, model))
    }
    private fun key(voice: CairnVoice, m: Map<String, Float>, velocity: Float, uncoupled: Boolean, model: Int) =
        MotionKey(model, voice, m.getValue("WEIGHT"), m.getValue("SLOPE"), m.getValue("GROOVE"),
            m.getValue("FRICTION"), m.getValue("BODY"), velocity, uncoupled, isLoop(m))

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
        if (key.voice == CairnVoice.TUMBLE) return CairnTumble.motion(
            key.weight.toDouble(), key.slope.toDouble(), key.groove.toDouble(), key.friction.toDouble(),
            key.body.toDouble(), key.velocity.toDouble(), key.powered, key.uncoupled)
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
        val launch = (1.15 + 1.3 * key.slope) * key.velocity
        var v = DoubleArray(N).also { if (!key.powered) it[0] = launch }
        fun energy(): Double {
            var e = 0.0
            for (i in 0 until N) {
                e += .5 * masses[i] * v[i] * v[i]
                for (j in 0 until N) e += .5 * q[i] * k[i][j] * q[j]
            }
            return e
        }
        val seed = Dsp.seedFor("CAIRN", 1, key.voice.name, "terrain")
        val noise = Dsp.Noise(seed)
        val count = 2 + (4 * key.groove).roundToInt()
        val edges = Array(4) { stone -> DoubleArray(count) { g ->
            val jitter = noise.next().toDouble() * .0008
            if (stone == 0) .12 + g * (.026 + .020 * (1 - key.groove)) + jitter
            else .018 + .007 * (stone - 1) + g * (.013 + .005 * (stone - 1)) + jitter
        } }
        val duration = if (key.powered) loopSeconds(key.voice) else MECHANICAL_SECONDS
        val brakeStart = if (key.powered && !answer) .75 else 2.5
        val lastContact = DoubleArray(4) { -1.0 }
        var previousEvents = emptyList<Event>()
        repeat(if (key.powered) 8 else 1) { cycle ->
            val beforeQ = q.copyOf(); val beforeV = v.copyOf()
            val initial = energy()
            if (key.powered) v[0] += launch
            val supplied = if (key.powered) energy() - initial else 0.0
            var lastEnergy = energy()
            var withdrawn = 0.0
            var brakeWork = 0.0
            var largestIncrease = 0.0
            val events = mutableListOf<Event>()
            val states = mutableListOf(State(0.0, lastEnergy, 0.0, q.toList()))
            repeat((duration * MECHANICAL_RATE).toInt()) { n ->
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
                // The padded pocket closes late in the gesture. In HOLD this
                // closure is a repeating cam, never a coordinate/state reset.
                // Contracting q and v only removes quadratic network energy.
                if (seconds > brakeStart) {
                    val before = energy()
                    val gain = exp(-12.0 * (seconds - brakeStart) * dt)
                    for (i in 0 until N) { q[i] *= gain; v[i] *= gain }
                    brakeWork += before - energy()
                }
                val e = energy()
                largestIncrease = max(largestIncrease, e - lastEnergy)
                lastEnergy = e
                if ((n + 1) % 20 == 0) states += State((n + 1) * dt, e, withdrawn, q.toList())
            }
            val ordered = events.sortedWith(compareBy<Event> { it.seconds }.thenBy { it.stone }).toList()
            val residual = (0 until N).maxOf { max(abs(q[it] - beforeQ[it]), abs(v[it] - beforeV[it])) }
            val eventsRepeat = ordered.size == previousEvents.size && ordered.zip(previousEvents).all { (a, b) ->
                a.stone == b.stone && a.groove == b.groove && abs(a.seconds - b.seconds) < 1e-7 && abs(a.work - b.work) < 1e-8
            }
            if (!key.powered || (cycle >= 2 && residual < 1e-8 && eventsRepeat)) {
                return Motion(ordered, states.toList(), initial, lastEnergy, withdrawn,
                    initial + supplied - lastEnergy - withdrawn, largestIncrease, brakeWork, supplied,
                    if (key.powered) duration else 0.0, if (key.powered) cycle + 1 else 0,
                    if (key.powered) residual else 0.0, if (key.powered) previousEvents else emptyList())
            }
            previousEvents = ordered
            for (stone in lastContact.indices) lastContact[stone] -= duration
        }
        error("CAIRN HOLD mechanics did not settle within eight continuing cycles")
    }

    private fun ringSeconds(voice: CairnVoice, m: Map<String, Float>): Double =
        (if (voice == CairnVoice.KNOCK) .32 + .48 * m.getValue("BODY")
        else if (voice == CairnVoice.TUMBLE) .15 + .28 * m.getValue("BODY")
        else .85 + 1.1 * m.getValue("BODY")) * (1.0 + .6 * m.getValue("HOLD"))

    private fun frameCount(voice: CairnVoice, m: Map<String, Float>, motion: Motion, contact: Boolean): Int {
        val last = motion.events.lastOrNull()?.seconds ?: 0.0
        val firstFrame = ((motion.events.firstOrNull()?.seconds ?: 0.0) * RATE).roundToInt()
        val cue = if (contact) (firstFrame - (.002 * RATE).roundToInt()).coerceAtLeast(0) else 0
        return max(1, ceil(max(.12, last + ringSeconds(voice, m)) * RATE).toInt() - cue)
    }
    fun drumClassFor(voice: CairnVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
                     startAtContact: Boolean = true, model: Int = MODEL_VERSION): DrumClass {
        val m = checked(voice, macros, model)
        if (isLoop(m)) return DrumClass.LOOP
        val motion = cachedMotion(key(voice, m, velocity, false, model), false).first
        return if (frameCount(voice, m, motion, startAtContact) > (1.5 * RATE).toInt()) DrumClass.LOOP else DrumClass.TONAL
    }
    private fun cachedMotion(key: MotionKey, bypass: Boolean): Pair<Motion, Boolean> {
        if (!bypass) synchronized(cache) { cache[key]?.let { return it to true } }
        val result = calculateMotion(key)
        if (!bypass) synchronized(cache) { cache[key] = result }
        return result to false
    }

    /** The top HOLD step is a free-running 120 BPM phrase: one bar KNOCK/TUMBLE, two bars ANSWER. */
    fun renderLoop(voice: CairnVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
                   velocity: Float = 1f): Render = inspect(voice, macros + ("HOLD" to 1f), midi, velocity)

    /** Matching digital silence has zero error, despite the relative metric's 0/0. */
    internal fun silentBoundarySeam(full: FloatArray, loopStart: Int): Double {
        require(loopStart >= 256 && full.size > loopStart)
        val silent = (0 until 256).all { full[loopStart - 256 + it] == 0f && full[full.size - 256 + it] == 0f }
        return if (silent) 0.0 else Keys.seamError(full, loopStart)
    }

    fun render(voice: CairnVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
               velocity: Float = 1f, startAtContact: Boolean = true, model: Int = MODEL_VERSION): Snip =
        Snip(inspect(voice, macros, midi, velocity, startAtContact, model = model).samples, 1, RATE)

    fun inspect(voice: CairnVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
                velocity: Float = 1f, startAtContact: Boolean = true, options: Options = Options(), model: Int = MODEL_VERSION): Render {
        require(midi in MIN_MIDI..MAX_MIDI && velocity.isFinite() && velocity in 0f..1f)
        require(options.oversample == 4 || options.oversample == 8)
        val m = checked(voice, macros, model)
        val start = System.nanoTime()
        val (motion, hit) = cachedMotion(key(voice, m, velocity, options.disableCoupling, model), options.bypassCache)
        val audioStart = System.nanoTime()
        val internalRate = RATE * options.oversample
        val hz = frequencyFor(midiFor(voice, m.getValue("TUNE"), midi)).toDouble()
        val held = isLoop(m)
        val period = (loopSeconds(voice) * RATE).roundToInt()
        // TUMBLE's short rings leave a nearly silent end. One real second
        // suppresses HP startup below that tiny tail; the older two voices
        // retain their exact quarter-second path and samples.
        val prefix = if (options.fullLoopPreroll) period else if (voice == CairnVoice.TUMBLE) RATE else RATE / 4
        // Extra continuation protects the kept end from finite resampler edge padding.
        val frames = if (held) prefix + period + 1024 else frameCount(voice, m, motion, false)
        val raw = FloatArray(frames * options.oversample)
        val bank = Modes.Bank(8, internalRate)
        val ring = ringSeconds(voice, m)
        val ratios = if (voice == CairnVoice.KNOCK) doubleArrayOf(1.0, 2.756, 5.404, 2.0, 3.0, 4.0, 1.0, 1.59)
                     else if (voice == CairnVoice.TUMBLE) doubleArrayOf(1.0, 2.71, 5.80, 2.0, 3.0, 4.0, 1.0, 1.47)
                     else doubleArrayOf(1.0, 2.4, 4.1, 2.0, 3.0, 4.0, 1.0, 1.59)
        for (i in ratios.indices) bank.tune(i, min(12_000.0, hz * ratios[i]), ring * when (i) {
            0 -> 1.0; 1 -> .42; 2 -> .22; in 3..5 -> .70; else -> .50
        })
        val pickup = doubleArrayOf(1.0, .60, .30, 1.0, .90, .80, .32, .16)
        val contact = Array(4) { stone -> DoubleArray(8).also { b ->
            if (stone == 0) {
                // Light ANSWER contacts can cancel much of the fundamental across
                // the close cluster. Reinforce root participation only below .25;
                // frequencies, contact work and the accepted defaults are unchanged.
                val light = ((.25 - m.getValue("WEIGHT")) / .25).coerceIn(0.0, 1.0)
                b[0] = if (model >= 2 && voice == CairnVoice.ANSWER) 1.0 + 1.5 * light * light else 1.0
                b[1] = .45; b[2] = .22
                if (voice == CairnVoice.TUMBLE) {
                    b[0] = 1.65; b[1] = .34; b[2] = .12
                    // Harmonics of this same impact give a small stone its
                    // attack detail. These are not separately arriving replies.
                    b[3] = 1.10; b[4] = .85; b[5] = .55
                }
            }
            else b[stone + 2] = 1.0
            b[6] = .12 + .23 * m.getValue("BODY"); b[7] = .08
            for (i in b.indices) if (hz * ratios[i] >= 12_000) b[i] = 0.0
        } }
        // A unit-area raised-cosine force has no dependence on sample rate.
        // Force width changes with mass and velocity. Its fixed work calibration
        // is shared by all stones; quiet answers cannot be inflated to root level.
        val width = (.00030 + .0010 * m.getValue("WEIGHT")) *
            (1.35 - .65 * velocity) * when (voice) {
                CairnVoice.KNOCK -> .60; CairnVoice.TUMBLE -> .45; CairnVoice.ANSWER -> 1.0
            }
        val pulseFrames = max(8, (width * internalRate).roundToInt())
        val kernel = DoubleArray(pulseFrames) { .5 - .5 * cos(2 * PI * (it + .5) / pulseFrames) }
        val area = kernel.sum() / internalRate
        val drives = Array(4) { FloatArray(raw.size) }
        fun addEvents(events: List<Event>, offset: Int) {
            for (event in events) {
                val first = (event.seconds * internalRate).roundToInt() + offset
                val impulse = .12 * sqrt(event.work)
                for (j in kernel.indices) if (first + j in raw.indices)
                    drives[event.stone][first + j] += (impulse * kernel[j] / area).toFloat()
            }
        }
        val periodInternal = period * options.oversample
        if (held) {
            // Prime the acoustic bank analytically from sparse forces, rather
            // than paying for many audio-rate warmup cycles on each knob turn.
            var cursor = 0
            val impulses = sortedMapOf<Int, DoubleArray>()
            for (event in motion.precedingEvents) {
                val first = (event.seconds * internalRate).roundToInt()
                val impulse = .12 * sqrt(event.work)
                for (j in kernel.indices) {
                    val frame = (first + j) % periodInternal
                    impulses.getOrPut(frame) { DoubleArray(4) }[event.stone] +=
                        (impulse * kernel[j] / area).toFloat().toDouble()
                }
            }
            for ((frame, forces) in impulses) {
                bank.advanceUncoupled(frame - cursor)
                for (stone in 0..3) if (forces[stone] != 0.0) bank.drive(contact[stone], forces[stone])
                bank.step(); cursor = frame + 1
            }
            bank.advanceUncoupled(periodInternal - cursor)
            bank.closeUncoupledCycle(periodInternal)
            // Advance the periodic state sparsely to a real preceding
            // segment, long enough to settle the output filters. The retained
            // cycle still advances every acoustic sample and every force normally.
            // A full-cycle reference path is kept for independent parity probes.
            val phase = (period - prefix) * options.oversample
            cursor = 0
            for ((frame, forces) in impulses) {
                if (frame >= phase) break
                bank.advanceUncoupled(frame - cursor)
                for (stone in 0..3) if (forces[stone] != 0.0) bank.drive(contact[stone], forces[stone])
                bank.step(); cursor = frame + 1
            }
            bank.advanceUncoupled(phase - cursor)
            val prefixInternal = prefix * options.oversample
            addEvents(motion.precedingEvents, -phase)
            addEvents(motion.events, prefixInternal)
            addEvents(motion.events, prefixInternal + periodInternal)
        } else addEvents(motion.events, 0)
        val lastForce = if (held) drives.maxOf { drive -> drive.indexOfLast { it != 0f } } + 2
            else (motion.events.lastOrNull()?.seconds?.times(internalRate)?.roundToInt() ?: 0) + pulseFrames + 2
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
        if (!held) Dsp.fadeTail(full, rate = RATE)
        val firstContact = ((motion.events.firstOrNull()?.seconds ?: 0.0) * RATE).roundToInt()
        val cue = if (held) prefix else if (startAtContact) (firstContact - (.002 * RATE).roundToInt()).coerceAtLeast(0) else 0
        val kept = if (held) full.copyOf(prefix + period) else full
        val seam = if (held && velocity > 0f) {
            if (voice == CairnVoice.TUMBLE) silentBoundarySeam(kept, prefix) else Keys.seamError(kept, prefix)
        } else null
        if (seam != null) {
            if (voice == CairnVoice.TUMBLE) require(seam < Keys.MAX_SEAM_ERROR) {
                "CAIRN TUMBLE MIDI$midi: the real continuation does not close (seam $seam)"
            } else Keys.requireSeam("CAIRN $voice MIDI$midi", kept, prefix)
        }
        val samples = if (cue == 0) kept else kept.copyOfRange(cue, kept.size)
        return Render(raw, kept, samples, Diagnostics(motion, rawPeak, rawRms, maxModalEnergy,
            tailIncrease, firstContact, cue, hit, (audioStart - start) / 1e6,
            (System.nanoTime() - audioStart) / 1e6, hz, internalRate, if (held) prefix else 0, seam))
    }
}
