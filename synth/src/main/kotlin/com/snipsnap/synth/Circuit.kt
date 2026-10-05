package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class CircuitVoice { ROOT, PROCESSION, ANSWER, VOICED, EXPANSE, CONFLUENCE }

/**
 * CIRCUIT: three pressure-driven, tuned breath resonators inside a moving pitched ensemble.
 * The instruments and performance rules are invented, not a model of a real tradition.
 *
 * The mono observer is off centre; two unequal wall images give every source its own moving
 * arrival times and losses. Tagged accents also travel to the performers themselves and may
 * cue one finite reply. ORBIT never schedules base strikes, and PACE never sets walking speed.
 *
 * TUNE stores C2–C4. Velocity is performer energy, as a render argument. HOLD below .99 extends
 * the finite breath phrase; at .99 it returns a settled loop alone (Snip has no loop marker).
 * Held clocks are rounded independently to compatible cycles of an approximately sixteen-second
 * loop. Slow nonzero ORBIT becomes one turn per loop; diagnostics report this approximation.
 * All nonlinear sources run at 4x, followed by the shared band-limited decimator and loudness.
 */
object Circuit {
    const val ROOT_MIDI = 36
    const val TUNE_SEMITONES = 24
    const val LOOP_THRESHOLD = .99f
    const val SCRAMBLE_HOLD_CEILING = .95f
    const val MAX_REPLIES = 12
    const val MAX_REPLY_ENERGY = 2.4
    private const val SPEED_OF_SOUND = 343.0
    private const val CONTROL_BLOCK = 64
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE

    private val starting = arrayOf(
        floatArrayOf(.55f, .30f, .15f, .20f, .35f),
        floatArrayOf(.50f, .45f, .55f, .55f, .45f),
        floatArrayOf(.45f, .55f, .25f, .30f, .75f),
        floatArrayOf(.75f, .40f, .30f, .40f, .50f),
        floatArrayOf(.50f, .85f, .20f, .25f, .85f),
        floatArrayOf(.70f, .55f, .75f, .80f, .65f),
    )

    fun macrosFor(voice: CircuitVoice): List<MacroSpec> {
        val d = starting[voice.ordinal]
        return listOf(MacroSpec("TUNE", .5f, neutral = .5f),
            MacroSpec("BREATH", d[0], neutral = .45f), MacroSpec("DIAMETER", d[1], neutral = .40f),
            MacroSpec("ORBIT", d[2], neutral = 0f), MacroSpec("PACE", d[3], neutral = .35f),
            MacroSpec("CANYON", d[4], neutral = .40f), MacroSpec("HOLD", 0f, neutral = 0f))
    }

    fun defaults(voice: CircuitVoice) = macrosFor(voice).associate { it.name to it.default }
    fun midiFor(tune: Float) = ROOT_MIDI + (tune.coerceIn(0f, 1f) * TUNE_SEMITONES).roundToInt()
    fun frequencyFor(tune: Float): Float = Keys.midiHz(midiFor(tune))
    fun isLoop(hold: Float) = hold >= LOOP_THRESHOLD
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: CircuitVoice,
                     @Suppress("UNUSED_PARAMETER") macros: Map<String, Float> = emptyMap()) = DrumClass.LOOP

    fun scramble(voice: CircuitVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = base + (near?.macros ?: if (temperature < 1f) CircuitPresets.forVoice(voice).random(random).macros else emptyMap())
        return Dsp.scrambleNear(seed.filterKeys { it in base }, temperature, random).toMutableMap().apply {
            this["HOLD"] = getValue("HOLD").coerceAtMost(SCRAMBLE_HOLD_CEILING)
        }
    }

    /** Engineering surfaces; none adds a product macro. Solo still runs the complete source system. */
    data class Probe(val solo: Int? = null, val responses: Boolean = true, val coupling: Double = 1.0,
                     val inputOffSeconds: Double? = null, val recordPaths: Boolean = false) {
        init {
            require(solo == null || solo in 0..6)
            require(coupling.isFinite() && coupling in 0.0..1.0)
            require(inputOffSeconds == null || (inputOffSeconds.isFinite() && inputOffSeconds >= 0))
        }
    }
    internal data class Event(val source: Int, val kind: String, val timeSeconds: Double, val energy: Double,
                              val reply: Boolean = false, val cueSeconds: Double? = null,
                              val cueSource: Int? = null, val reflection: Int? = null, val depth: Int = 0,
                              val emittedSeconds: Double? = null, val observerCueSeconds: Double? = null,
                              val reason: String = "pattern")
    internal data class PathFrame(val timeSeconds: Double, val source: Int, val x: Double, val y: Double,
                                  val directMeters: Double, val reflectedMeters: List<Double>)
    internal data class Rates(val orbitHz: Double, val paceHz: Double, val requestedOrbitHz: Double,
                              val requestedPaceHz: Double, val loopSeconds: Double)
    internal data class LoopEvidence(val seamError: Double, val convergenceError: Double, val prerollCycles: Int)
    internal data class Report(val snip: Snip, val raw: FloatArray, val events: List<Event>, val paths: List<PathFrame>,
                               val rates: Rates, val rawPeak: Double, val recoveries: Int, val loop: LoopEvidence?)
    private data class Plan(val frames: Int, val phraseSeconds: Double, val rates: Rates, val frequency: Double,
                            val held: Boolean)

    private fun settled(voice: CircuitVoice, macros: Map<String, Float>): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) {
            require(k in m) { "unknown CIRCUIT macro: $k" }
            require(v.isFinite() && v in 0f..1f) { "CIRCUIT $k must be finite and in 0..1" }
            m[k] = v
        }
        return m
    }

    private fun plan(m: Map<String, Float>): Plan {
        val f = frequencyFor(m.getValue("TUNE")).toDouble()
        val orbit = m.getValue("ORBIT").toDouble().let { if (it == 0.0) 0.0 else .025 + .10 * it.pow(.7) }
        val pace = .5 + 5.5 * m.getValue("PACE").toDouble().pow(1.2)
        if (isLoop(m.getValue("HOLD"))) {
            // Whole requested-root cycles to much better than one cent, with an integer frame count.
            val cycles = 2 * (8.0 * f).roundToInt()
            val frames = (cycles * Dsp.RATE / f).roundToInt()
            val seconds = frames.toDouble() / Dsp.RATE
            val motion = if (orbit == 0.0) 0.0 else max(1, (orbit * seconds).roundToInt()) / seconds
            // A whole eight-step pattern closes, independently of the movement clock.
            val playing = max(8, (pace * seconds / 8.0).roundToInt() * 8) / seconds
            return Plan(frames, seconds, Rates(motion, playing, orbit, pace, seconds),
                cycles * Dsp.RATE.toDouble() / frames, true)
        }
        val phrase = 3.2 + 1.8 * m.getValue("HOLD") / LOOP_THRESHOLD
        val total = phrase + 1.8 + .9 * m.getValue("CANYON")
        return Plan((total * Dsp.RATE).roundToInt(), phrase, Rates(orbit, pace, orbit, pace, 0.0), f, false)
    }

    fun renderFrames(macros: Map<String, Float>, voice: CircuitVoice): Int = plan(settled(voice, macros)).frames
    fun render(voice: CircuitVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
               normalize: Boolean = true, probe: Probe = Probe()): Snip =
        perform(voice, macros, velocity, normalize, probe).snip

    internal fun inspect(voice: CircuitVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f,
                         probe: Probe = Probe()): Report = perform(voice, macros, velocity, true, probe)

    /** Per-player refractory interval. Replies may never become cues for more replies. */
    internal fun refractorySeconds(paceHz: Double) = .35 + .45 / paceHz

    private class Geometry(m: Map<String, Float>, private val voice: CircuitVoice, private val rates: Rates) {
        val radius = 1.2 + 2.8 * m.getValue("DIAMETER").toDouble().pow(.8)
        private val canyon = m.getValue("CANYON").toDouble()
        private val walls = doubleArrayOf(radius + 5.0 + 16.0 * canyon,
            radius * 1.35 + 8.0 + 24.0 * canyon + voice.ordinal * .25)
        private val nx = doubleArrayOf(.94, -.40)
        private val ny = DoubleArray(2) { sqrt(1.0 - nx[it] * nx[it]) }
        private val offset = doubleArrayOf(.13, 1.64, 3.31, 4.92)
        fun position(source: Int, time: Double): Pair<Double, Double> {
            if (source < 3) return Pair((source - 1) * .16, if (source == 1) .13 else -.08)
            val angle = offset[source - 3] + 2.0 * PI * rates.orbitHz * time
            return Pair(radius * cos(angle), radius * sin(angle))
        }
        private fun reflected(source: Pair<Double, Double>, wall: Int): Pair<Double, Double> {
            val distance = walls[wall] - (source.first * nx[wall] + source.second * ny[wall])
            return Pair(source.first + 2 * distance * nx[wall], source.second + 2 * distance * ny[wall])
        }
        fun paths(source: Int, time: Double): DoubleArray {
            val p = position(source, time)
            val out = DoubleArray(3)
            out[0] = hypot(p.first - .43, p.second + .28)
            for (wall in 0..1) {
                val image = reflected(p, wall)
                out[wall + 1] = hypot(image.first - .43, image.second + .28)
            }
            return out
        }
        fun cueArrival(source: Int, player: Int, emitted: Double, wall: Int): Double {
            val image = reflected(position(source, emitted), wall)
            var arrival = emitted
            repeat(3) {
                val listener = position(player, arrival)
                arrival = emitted + hypot(image.first - listener.first, image.second - listener.second) / SPEED_OF_SOUND
            }
            return arrival
        }
        fun facing(source: Int, time: Double): Double {
            if (source < 3) return 1.0
            // Changes radiation BEFORE propagation, including its high-frequency loss.
            val p = position(source, time)
            val toward = atan2(-.28 - p.second, .43 - p.first)
            val direction = atan2(p.second, p.first) + .7
            val depth = (.18 + 5 * rates.requestedOrbitHz).coerceAtMost(1.0)
            return .5 + .5 * depth * cos(toward - direction + 2 * PI * rates.orbitHz * time)
        }
    }

    private fun events(voice: CircuitVoice, m: Map<String, Float>, p: Plan, g: Geometry,
                       velocity: Double, probe: Probe): List<Event> {
        val base = ArrayList<Event>()
        val cutoff = min(p.phraseSeconds - if (p.held) 0.0 else .62, probe.inputOffSeconds ?: Double.POSITIVE_INFINITY)
        val count = if (p.held) (p.rates.paceHz * p.rates.loopSeconds).roundToInt() else ceil(cutoff * p.rates.paceHz).toInt()
        fun add(source: Int, kind: String, time: Double, energy: Double, step: Int) {
            val r = Random(Dsp.seedFor("CIRCUIT-EVENT", voice, midiFor(m.getValue("TUNE")), source, step))
            val at = time + if (step == 0) 0.0 else (r.nextDouble() - .5) * .022
            if (at >= 0 && at < cutoff) base.add(Event(source, kind, at, velocity * energy * (.92 + .16 * r.nextDouble())))
        }
        for (step in 0 until count) {
            val time = .09 + step / p.rates.paceHz
            add(3, "RATTLE", time, .56, step)
            if (step % 4 == 0 || step % 4 == 2) add(4, "CLAPPER", time + .035, .72, step)
            if (step % 4 == 0) add(5, "CLAY", time + .075, .66, step)
            if (step % 4 == 1) add(6, if ((step / 4) % 2 == 0) "UH_HUH" else "GRUNT", time + .04, .64, step)
            if (step % 2 == 0) add(1, "TUBE_ACCENT", time, .55 + .25 * m.getValue("BREATH"), step)
        }
        // Even a sparse short gesture contains a coordination voice.
        if (base.none { it.source == 6 }) add(6, "UH_HUH", .39, .55, 0)
        base.sortWith(compareBy<Event> { it.timeSeconds }.thenBy { it.source })
        if (!probe.responses || velocity == 0.0) return base
        val canyon = m.getValue("CANYON").toDouble()
        if (canyon < .015) return base
        val replies = ArrayList<Event>()
        val refractory = DoubleArray(4) { -10.0 }
        var energy = 0.0
        val cap = min(MAX_REPLIES, 4 + (8 * canyon).roundToInt())
        val cues = base.filter { it.source == 1 || it.source == 4 }
        for ((index, cue) in cues.withIndex()) {
            if (replies.size >= cap) break
            val player = 3 + (index + voice.ordinal) % 4
            if (cue.source == player) continue
            val wall = index % 2
            val arrived = g.cueArrival(cue.source, player, cue.timeSeconds, wall)
            val time = arrived + .08 + .10 * canyon
            val travel = (arrived - cue.timeSeconds) * SPEED_OF_SOUND
            val strength = cue.energy * (.035 + .43 * canyon) / (1 + .035 * travel)
            if (strength < (if (voice == CircuitVoice.ANSWER) .018 else .025) * velocity) continue
            val e = velocity * (.45 + .30 * canyon) * (if (voice == CircuitVoice.ANSWER) 1.2 else 1.0) * (.60 + strength)
            if (time >= cutoff || time - refractory[player - 3] < refractorySeconds(p.rates.paceHz)) continue
            if (base.any { it.source == player && abs(it.timeSeconds - time) < .18 }) continue
            if (energy + e > MAX_REPLY_ENERGY * velocity) continue
            val kind = when (player) { 3 -> "RATTLE"; 4 -> "CLAPPER"; 5 -> "CLAY"; else -> if (index % 2 == 0) "UH_HUH" else "GRUNT" }
            val observer = cue.timeSeconds + g.paths(cue.source, cue.timeSeconds)[wall + 1] / SPEED_OF_SOUND
            replies.add(Event(player, kind, time, e, true, arrived, cue.source, wall, 1,
                cue.timeSeconds, observer, if (cue.source == 1) "reflected-tube-accent" else "reflected-clapper"))
            energy += e
            refractory[player - 3] = time
        }
        return (base + replies).sortedWith(compareBy<Event> { it.timeSeconds }.thenBy { it.source }.thenBy { it.reply })
    }

    /** Stable complex resonator: pole radius below one, fixed tuned angle, bounded pressure forcing. */
    private class Tube(root: Double, role: Int, breath: Double, voice: CircuitVoice) {
        private val ratios = when (role) { 0 -> intArrayOf(1, 3, 5, 7); 1 -> intArrayOf(1, 2, 3, 5); else -> intArrayOf(1, 2, 4, 6) }
        private val gains = when (role) { 0 -> doubleArrayOf(1.0, .23, .11, .05); 1 -> doubleArrayOf(.66, .34, .17, .06); else -> doubleArrayOf(.55, .30, .14, .06) }
        private val re = DoubleArray(4)
        private val im = DoubleArray(4)
        private val c = DoubleArray(4) { cos(2 * PI * root * ratios[it] / INTERNAL_RATE) }
        private val s = DoubleArray(4) { sin(2 * PI * root * ratios[it] / INTERNAL_RATE) }
        private val a = DoubleArray(4) { exp(-1.0 / (INTERNAL_RATE * (.028 + .011 * role) / (1 + it * .65))) }
        private val phaseC = cos(2 * PI * root / INTERNAL_RATE)
        private val phaseS = sin(2 * PI * root / INTERNAL_RATE)
        private var x = 1.0
        private var y = 0.0
        private val stiff = 1.0 + 2.6 * breath + if (voice == CircuitVoice.VOICED) .5 else 0.0
        private val colour = .07 + .23 * breath
        private val throatC = cos(PI * root / INTERNAL_RATE)
        private val throatS = sin(PI * root / INTERNAL_RATE)
        private var throatX = 1.0
        private var throatY = 0.0
        private val growl = if (role == 2) .06 + .16 * breath + if (voice == CircuitVoice.VOICED) .06 else 0.0 else 0.0
        var recoveries = 0
            private set
        fun tick(pressure: Double, loading: Double): Double {
            val xx = x * phaseC - y * phaseS
            y = x * phaseS + y * phaseC
            x = xx
            val tx = throatX * throatC - throatY * throatS
            throatY = throatX * throatS + throatY * throatC
            throatX = tx
            // An abstract periodic lip drive; loading changes stiffness/partials, never the phase clock.
            // A subordinate band-limited throat source changes the upper tube's pressure, producing
            // controlled growl sidebands. It has no independent radiation or energy after release.
            val boundedLoad = loading.coerceIn(-.08, .08)
            val stiffness = stiff * (1 + 2 * boundedLoad)
            val lip = tanh(stiffness * (pressure * (y * (1 + growl * throatY) +
                colour * (1 + 3 * boundedLoad) * 2 * x * y) + boundedLoad * pressure))
            var out = 0.0
            for (h in 0..3) {
                val rr = a[h] * (c[h] * re[h] - s[h] * im[h]) + (1 - a[h]) * lip
                val ii = a[h] * (s[h] * re[h] + c[h] * im[h])
                if (!rr.isFinite() || !ii.isFinite()) { re[h] = 0.0; im[h] = 0.0; recoveries++ }
                else { re[h] = rr; im[h] = ii }
                out += 2 * gains[h] * re[h]
            }
            return out * .23
        }
    }

    private fun perform(voice: CircuitVoice, macros: Map<String, Float>, velocity: Float,
                        normalize: Boolean, probe: Probe): Report {
        require(velocity.isFinite() && velocity in 0f..1f) { "CIRCUIT velocity must be finite and in 0..1" }
        val m = settled(voice, macros)
        val p = plan(m)
        val geometry = Geometry(m, voice, p.rates)
        val schedule = events(voice, m, p, geometry, velocity.toDouble(), probe)
        val n = p.frames * Dsp.OVERSAMPLE
        val sources = Array(7) { FloatArray(n) }
        val breath = m.getValue("BREATH").toDouble()
        val canyon = m.getValue("CANYON").toDouble()
        val tubes = Array(3) { Tube(p.frequency, it, breath, voice) }
        val radiation = DoubleArray(3)
        var convergenceDifference = 0.0
        var convergenceEnergy = 0.0
        val cycles = if (p.held) 3 else 1
        val returnDelay = IntArray(3) { (geometry.cueArrival(it, it, 0.0, 0) * INTERNAL_RATE).roundToInt() }
        val accents = schedule.filter { it.source == 1 }
        val prior = DoubleArray(3)
        for (cycle in 0 until cycles) {
            var accentIndex = 0
            for (i in 0 until n) {
                val t = i.toDouble() / INTERNAL_RATE
                val release = min(p.phraseSeconds, probe.inputOffSeconds ?: Double.POSITIVE_INFINITY)
                val envelope = if (p.held && probe.inputOffSeconds == null) 1.0 else {
                    val attack = (t / .055).coerceIn(0.0, 1.0)
                    val end = ((release - t) / .26).coerceIn(0.0, 1.0)
                    attack * end
                }
                val pressure = velocity * envelope * (.20 + .60 * breath)
                val slow = 2 * PI * t / if (p.held) p.rates.loopSeconds else 3.2
                val cadence = 2 * PI * p.rates.paceHz * (t - .09)
                for (role in 0..2) prior[role] = radiation[role]
                while (accentIndex + 1 < accents.size && accents[accentIndex + 1].timeSeconds <= t) accentIndex++
                val accent = if (accents.isEmpty()) 0.0 else {
                    val event = if (t < accents.first().timeSeconds && p.held) accents.last() else accents[accentIndex]
                    val age = t - event.timeSeconds + if (t < event.timeSeconds && p.held) p.rates.loopSeconds else 0.0
                    if (age < 0 || age > .28 || velocity == 0f) 0.0
                    else event.energy / velocity * (1 - exp(-age / .005)) * exp(-age / .080)
                }
                for (role in 0..2) {
                    val gesture = when (role) {
                        0 -> .91 + .09 * sin(slow)
                        1 -> .30 + .95 * accent
                        else -> .78 + .12 * sin(slow + 1.8) + .10 * cos(cadence * .5)
                    }
                    val local = probe.coupling * (.30 * prior[(role + 1) % 3] - .12 * prior[(role + 2) % 3])
                    val returning = read(sources[role], i.toDouble() - returnDelay[role], p.held) * canyon * .025
                    radiation[role] = tubes[role].tick(pressure * gesture, local + returning)
                    val value = radiation[role].toFloat()
                    if (p.held && cycle == cycles - 1) {
                        val difference = value.toDouble() - sources[role][i]
                        convergenceDifference += difference * difference
                        convergenceEnergy += value.toDouble() * value
                    }
                    sources[role][i] = value
                }
            }
        }

        // Event seed excludes motion/diameter: a player's gesture survives changes to its path.
        for (event in schedule) {
            if (event.source < 3 || event.energy == 0.0) continue
            val wave = CircuitInstruments.gesture(CircuitInstruments.Kind.valueOf(event.kind), p.frequency,
                INTERNAL_RATE, event.energy, Dsp.seedFor("CIRCUIT-GESTURE", voice,
                    midiFor(m.getValue("TUNE")), event.source, event.timeSeconds, event.kind, event.reply).toLong(), breath,
                poweredSeconds = probe.inputOffSeconds?.let { max(0.0, it - event.timeSeconds) })
            val start = (event.timeSeconds * INTERNAL_RATE).roundToInt()
            for (k in wave.indices) {
                val at = start + k
                if (p.held) sources[event.source][at % n] += wave[k]
                else if (at < n) sources[event.source][at] += wave[k]
            }
        }

        val weights = when (voice) {
            CircuitVoice.ROOT -> doubleArrayOf(1.0, .70, .60, .42, .50, .55, .42)
            CircuitVoice.PROCESSION -> doubleArrayOf(.95, .85, .65, .85, .95, .80, .65)
            CircuitVoice.ANSWER -> doubleArrayOf(1.0, .72, .65, .60, .90, .85, .75)
            CircuitVoice.VOICED -> doubleArrayOf(.92, .65, 1.15, .50, .65, .65, 1.05)
            CircuitVoice.EXPANSE -> doubleArrayOf(1.0, .72, .85, .55, .65, .70, .55)
            CircuitVoice.CONFLUENCE -> doubleArrayOf(.98, 1.0, .90, .85, 1.0, .85, .80)
        }
        // Preroll acoustic filters once, then measure/export two real adjacent cycles.
        val acousticCycles = if (p.held) 3 else 1
        val rawInternal = FloatArray(n * acousticCycles)
        val pathFrames = ArrayList<PathFrame>()
        for (source in 0..6) {
            if (probe.solo != null && probe.solo != source) continue
            val low = DoubleArray(3)
            var delays = geometry.paths(source, 0.0).map { it * INTERNAL_RATE / SPEED_OF_SOUND }.toDoubleArray()
            var distances = geometry.paths(source, 0.0)
            var delaySteps = DoubleArray(3)
            var distanceSteps = DoubleArray(3)
            var facing = geometry.facing(source, 0.0)
            var facingStep = 0.0
            var coefficients = DoubleArray(3)
            for (i in rawInternal.indices) {
                val at = i % n
                val t = at.toDouble() / INTERNAL_RATE
                if (i % CONTROL_BLOCK == 0) {
                    val future = geometry.paths(source, t + CONTROL_BLOCK.toDouble() / INTERNAL_RATE)
                    delaySteps = DoubleArray(3) { (future[it] * INTERNAL_RATE / SPEED_OF_SOUND - delays[it]) / CONTROL_BLOCK }
                    distanceSteps = DoubleArray(3) { (future[it] - distances[it]) / CONTROL_BLOCK }
                    facingStep = (geometry.facing(source, t + CONTROL_BLOCK.toDouble() / INTERNAL_RATE) - facing) / CONTROL_BLOCK
                    coefficients = DoubleArray(3) { path ->
                        val cutoff = if (path == 0) 1700 + 4000 * facing else 3400 / (1 + distances[path] * .028)
                        1 - exp(-2 * PI * cutoff / INTERNAL_RATE)
                    }
                    if (probe.recordPaths && i < n && at % (CONTROL_BLOCK * 32) == 0) {
                        val pos = geometry.position(source, t)
                        pathFrames.add(PathFrame(t, source, pos.first, pos.second, distances[0], distances.drop(1)))
                    }
                }
                var sample = 0.0
                for (path in 0..2) {
                    val incoming = read(sources[source], at.toDouble() - delays[path], p.held)
                    low[path] += coefficients[path] * (incoming - low[path])
                    val gain = if (path == 0) (.70 + .30 * facing) / (1 + .18 * distances[path])
                        else (.035 + .43 * canyon) / (1 + .035 * distances[path])
                    sample += low[path] * gain
                    delays[path] += delaySteps[path]
                    distances[path] += distanceSteps[path]
                }
                rawInternal[i] += (sample * weights[source]).toFloat()
                facing += facingStep
            }
        }

        val canyonField = Canyon(canyon)
        val dcA = exp(-2 * PI * 18.0 / INTERNAL_RATE)
        var previousInput = 0.0
        var previousOutput = 0.0
        var peak = 0.0
        for (i in rawInternal.indices) {
            val x = rawInternal[i].toDouble()
            val sound = x + canyonField.tick(x)
            val filtered = sound - previousInput + dcA * previousOutput
            previousInput = sound
            previousOutput = filtered
            rawInternal[i] = filtered.toFloat()
            peak = max(peak, abs(filtered))
        }
        val recoveries = tubes.sumOf { it.recoveries } + canyonField.recoveries
        var evidence: LoopEvidence? = null
        val raw: FloatArray
        if (p.held) {
            val two = rawInternal.copyOfRange(n, rawInternal.size)
            val decimated = cyclicDecimate(two)
            val seam = Keys.seamError(decimated, p.frames)
            var difference = 0.0
            var energy = 0.0
            for (i in 0 until p.frames) {
                val a = decimated[i].toDouble()
                val b = decimated[i + p.frames].toDouble()
                difference += (a - b) * (a - b)
                energy += b * b
            }
            val convergence = max(difference / max(energy, 1e-30), convergenceDifference / max(convergenceEnergy, 1e-30))
            evidence = LoopEvidence(if (energy < 1e-20) 0.0 else seam, convergence, cycles)
            check(evidence.seamError < 1e-3 && convergence < 1e-3) { "CIRCUIT held state did not converge: $evidence" }
            raw = decimated.copyOfRange(p.frames, 2 * p.frames)
        } else {
            raw = Dsp.decimate(rawInternal, Dsp.RATE)
            Dsp.fadeTail(raw)
        }
        val out = raw.copyOf()
        if (normalize) Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET, ceiling = .99f)
        return Report(Snip(out, channels = 1, sampleRate = Dsp.RATE), raw, schedule, pathFrames,
            p.rates, peak, recoveries, evidence)
    }

    private fun read(source: FloatArray, position: Double, cyclic: Boolean): Double {
        val lower = floor(position).toInt()
        val fraction = position - lower
        fun sample(index: Int): Double = if (cyclic) source[Math.floorMod(index, source.size)].toDouble()
            else if (index in source.indices) source[index].toDouble() else 0.0
        return sample(lower) * (1 - fraction) + sample(lower + 1) * fraction
    }

    private fun cyclicDecimate(two: FloatArray): FloatArray {
        // Feed real wrapped context to the FIR; zero padding would notch every loop boundary.
        val pad = 128 * Dsp.OVERSAMPLE
        val padded = FloatArray(two.size + 2 * pad) { two[Math.floorMod(it - pad, two.size)] }
        val down = Dsp.decimate(padded, Dsp.RATE)
        return down.copyOfRange(pad / Dsp.OVERSAMPLE, pad / Dsp.OVERSAMPLE + two.size / Dsp.OVERSAMPLE)
    }

    /** Four damped branches with an orthogonal scattering matrix and gain strictly below one. */
    private class Canyon(private val amount: Double) {
        private val rings = Array(4) { i -> FloatArray(((.053 + .040 * i + (.03 + .02 * i) * amount) * INTERNAL_RATE).roundToInt()) }
        private val position = IntArray(4)
        private val low = DoubleArray(4)
        private val coefficient = 1 - exp(-2 * PI * (1700 - 850 * amount) / INTERNAL_RATE)
        private val feedback = .20 + .16 * amount
        var recoveries = 0
            private set
        fun tick(input: Double): Double {
            for (j in 0..3) low[j] += coefficient * (rings[j][position[j]] - low[j])
            val sum = low.sum()
            // Householder reflection I - 2vv^T, v=(1,1,1,1)/2; spectral norm exactly one.
            for (j in 0..3) {
                val value = input * .13 + feedback * (low[j] - .5 * sum)
                if (!value.isFinite()) { rings[j][position[j]] = 0f; low[j] = 0.0; recoveries++ }
                else rings[j][position[j]] = value.toFloat()
                position[j]++
                if (position[j] == rings[j].size) position[j] = 0
            }
            return (.10 + .45 * amount) * sum
        }
    }
}
