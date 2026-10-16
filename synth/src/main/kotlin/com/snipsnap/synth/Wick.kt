package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class WickVoice { PLUCK, BUBBLE }

/**
 * First WICK listening prototype: one continuously powered complex source feeds three parallel
 * low-pass gates. Charge opens each gate; a separate reserve recovers after admitted pulses.
 * An armed gate's real closing edge can schedule a later pulse into its neighbour. There is no
 * audio feedback and no prewritten three-step envelope. The small reserve describes articulation,
 * not conservation of the oscillator's electrical or acoustic energy.
 *
 * HOLD first searches for recurrence with the causal network running freely. A difficult network
 * may instead replay one measured causal event roster, while the gates, admission, depletion and
 * recovery continue evolving. That approximation is named in [LoopDiagnostics.strategy]. Both
 * paths must converge independently synthesized audio and complete control/filter state; copying
 * a loop is never the evidence used to accept it.
 */
object Wick {
    const val MIN_MIDI = 24
    const val MAX_MIDI = 84
    const val LOOP_THRESHOLD = .99f
    const val MAX_QUEUE = 64
    const val MAX_EVENTS_PER_SECOND = 160
    const val MAX_FINITE_EVENTS = 512
    private const val MAX_LOOP_ATTEMPTS = 10
    private const val STAGES = 3
    private const val OPEN_THRESHOLD = .20
    private const val CLOSE_THRESHOLD = .075
    private const val LOOP_SECONDS = 2.4
    private val names = listOf("SPARK", "FEED", "CASCADE", "TIMBRE", "RECOVERY", "HOLD")

    fun rootMidi(voice: WickVoice): Int = if (voice == WickVoice.PLUCK) 48 else 60
    fun macrosFor(voice: WickVoice): List<MacroSpec> {
        val d = if (voice == WickVoice.PLUCK) floatArrayOf(.45f, .05f, .20f, .25f, .30f, 0f)
        else floatArrayOf(.40f, .65f, .70f, .45f, .50f, 0f)
        return names.mapIndexed { i, name ->
            // Clearing supply, transmission and source complexity has a real no-effect value.
            // SPARK and RECOVERY use the voice's useful articulation as their central baseline.
            MacroSpec(name, d[i], if (name in listOf("FEED", "CASCADE", "TIMBRE", "HOLD")) 0f else d[i])
        }
    }
    fun defaults(voice: WickVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= LOOP_THRESHOLD
    fun settled(macros: Map<String, Float>, voice: WickVoice): Map<String, Float> =
        defaults(voice).toMutableMap().apply {
            // Match the forgiving sibling-engine entry point: unknown and nonfinite values are
            // ignored, while finite values are bounded. Saved patches validate their own schema.
            for ((key, value) in macros) if (key in this && value.isFinite()) this[key] = value.coerceIn(0f, 1f)
        }
    fun drumClassFor(voice: WickVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if (isLoop(settled(macros, voice).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL
    fun scramble(voice: WickVoice, random: Random = Random.Default, temperature: Float = .35f,
                 near: Patch? = null): Map<String, Float> {
        val d = defaults(voice)
        return Dsp.scrambleNear(d + (near?.macros?.filterKeys { it in d } ?: emptyMap()), temperature, random)
            .toMutableMap().apply { this["HOLD"] = getValue("HOLD").coerceAtMost(.95f) }
    }

    data class Options(
        val disableCascade: Boolean = false,
        val constantAvailability: Boolean = false,
        val gainOnly: Boolean = false,
        val oversample: Int = Dsp.OVERSAMPLE,
    )
    data class Event(
        val timeSeconds: Double,
        val parentStage: Int,
        val releaseSeconds: Double,
        val destination: Int,
        val requestedStrength: Double,
        val admittedStrength: Double,
        val availabilityBefore: Double,
        val availabilityAfter: Double,
        val kind: String,
        val id: Int = -1,
        val parentEventId: Int = -1,
        /** Replay events cite a separate measured causal trace, not a newly detected edge. */
        val derivation: String = "causal",
        val sourceEventId: Int = -1,
    )
    data class LoopDiagnostics(
        val periodFrames: Int,
        val periodSeconds: Double,
        val attempts: Int,
        val stateError: Double,
        val seam: Double,
        val converged: Boolean,
        val strategy: String,
        val audioError: Double = 0.0,
        val requestedHz: Double = 0.0,
        val actualHz: Double = 0.0,
    )
    data class Diagnostics(
        val rawPeak: Double,
        val rawRms: Double,
        val rawDc: Double,
        val events: List<Event>,
        val maxQueueDepth: Int,
        val rejectedEvents: Int,
        val minAvailability: Double,
        val maxAvailability: Double,
        val maxCharge: Double,
        val maxOpening: Double,
        val maxFilterState: Double,
        val loop: LoopDiagnostics?,
        val endedAdmissions: Int = 0,
        val replaySourceEvents: List<Event> = emptyList(),
        val replaySourcePeriodStartSeconds: Double? = null,
    )
    data class Probe(
        val raw: FloatArray,
        val samples: FloatArray,
        val diagnostics: Diagnostics,
        /** Genuine preceding period plus the returned period, at the same output gain. */
        val seamContext: FloatArray? = null,
    )

    fun render(voice: WickVoice, macros: Map<String, Float> = emptyMap(), midi: Int = rootMidi(voice),
               velocity: Float = 1f): Snip =
        Snip(inspect(voice, macros, midi, velocity).samples, channels = 1, sampleRate = Dsp.RATE)

    fun inspect(voice: WickVoice, macros: Map<String, Float> = emptyMap(), midi: Int = rootMidi(voice),
                velocity: Float = 1f, options: Options = Options()): Probe {
        require(midi in MIN_MIDI..MAX_MIDI) { "WICK midi must be $MIN_MIDI..$MAX_MIDI" }
        require(velocity.isFinite() && velocity in 0f..1f) { "WICK velocity must be finite and in 0..1" }
        require(options.oversample in listOf(1, 2, 4, 8)) { "WICK oversample must be 1, 2, 4 or 8" }
        val m = settled(macros, voice)
        if (isLoop(m.getValue("HOLD"))) return held(voice, m, midi, velocity, options)
        val engine = Engine(voice, m, midi, velocity, options, null)
        val up = engine.block(engine.finiteSamples)
        val raw = condition(up, options.oversample)
        Dsp.fadeTail(raw, ms = 12f)
        val samples = raw.copyOf()
        Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        return Probe(raw, samples, diagnostics(raw, engine, null))
    }

    private data class Plan(val frames: Int, val cycles: Int, val hz: Double) {
        val seconds get() = frames.toDouble() / Dsp.RATE
    }
    private fun plan(midi: Int): Plan {
        val requested = Keys.midiHz(midi).toDouble()
        // Even root-cycle counts also close the quiet 3:2 component. Fit cycles first and frames
        // second, keeping pitch within a fractional output frame rather than rounding it to Hz.
        val cycles = ((LOOP_SECONDS * requested / 2.0).roundToInt().coerceAtLeast(2)) * 2
        val frames = (cycles * Dsp.RATE / requested).roundToInt()
        return Plan(frames, cycles, cycles * Dsp.RATE.toDouble() / frames)
    }

    /** Two nonresonant poles with exact bounded coefficients, owned here so every state is visible. */
    private class LowPass(private val rate: Int) {
        var first = 0.0
        var second = 0.0
        fun next(x: Double, cutoff: Double): Double {
            val a = 1.0 - exp(-2.0 * PI * cutoff.coerceIn(10.0, rate * .45) / rate)
            first += a * (x - first)
            second += a * (first - second)
            return second
        }
    }
    private class Gate(rate: Int) {
        var available = 1.0
        var charge = 0.0
        var opening = 0.0
        var armed = false
        var peak = 0.0
        var lastRelease = Long.MIN_VALUE / 2
        val filter = LowPass(rate)
    }
    private data class Pending(val sample: Long, val destination: Int, val strength: Double,
                               val parent: Int, val release: Long, val cause: Int, val kind: String,
                               val sourceEventId: Int = -1)
    private data class Replay(val sample: Int, val destination: Int, val strength: Double,
                              val parent: Int, val releaseOffset: Long, val kind: String,
                              val sourceEventId: Int)

    private class Engine(
        val voice: WickVoice, val m: Map<String, Float>, val midi: Int, val velocity: Float,
        val options: Options, val plan: Plan?, val replay: List<Replay>? = null,
    ) {
        val rate = Dsp.RATE * options.oversample
        val periodSamples = (plan?.frames ?: 0) * options.oversample
        val hz = plan?.hz ?: Keys.midiHz(midi).toDouble()
        val spark = m.getValue("SPARK").toDouble()
        val feed = m.getValue("FEED").toDouble()
        val cascade = if (options.disableCascade) 0.0 else m.getValue("CASCADE").toDouble()
        val timbre = m.getValue("TIMBRE").toDouble()
        val recovery = m.getValue("RECOVERY").toDouble()
        val held = plan != null
        val gates = Array(STAGES) { Gate(rate) }
        val queue = ArrayList<Pending>()
        val events = ArrayList<Event>()
        val admittedTimes = java.util.ArrayDeque<Long>()
        val feedRate = .5 + 7.5 * feed.pow(1.45)
        val feedCount = if (held) max(1, (plan!!.seconds * (.65 + 7.35 * feed.pow(1.45))).roundToInt()) else 0
        // A 200 ms sub-bass envelope is heard/classified as a kick even when its oscillator is
        // perfectly pitched. The bass gates really ring longer: three times the root charge
        // lifetime through C3, smoothly returning to the compact articulation at C4. Later
        // stages stretch less, so a bass note still completes an answering chain in a few seconds.
        val registerRelease = run {
            val x = ((midi - 48) / 12.0).coerceIn(0.0, 1.0)
            1.0 + 2.0 * (1.0 - x * x * (3.0 - 2.0 * x))
        }
        val bassTime = .40 * (registerRelease - 1.0)
        val finiteSeconds = (if (voice == WickVoice.PLUCK) 2.1 + 1.1 * recovery + .5 * feed
            else 3.0 + 1.3 * recovery + .8 * feed) + bassTime
        val finiteSamples = (finiteSeconds * rate).roundToInt()
        // Stop all *admission*, including late cascade events, before an actual decaying tail.
        val admissionEnd = ((finiteSeconds - (1.0 + .7 * recovery)) * rate).roundToInt().toLong()
        val chargeDecay = DoubleArray(STAGES) { j ->
            val base = if (voice == WickVoice.PLUCK) doubleArrayOf(.19, .14, .115)[j]
                else doubleArrayOf(.092, .075, .11)[j]
            val stretch = 1.0 + (registerRelease - 1.0) / (1.0 + 2.0 * j)
            exp(-1.0 / (rate * base * (.62 + 1.05 * recovery) * stretch))
        }
        val recoveryPole = DoubleArray(STAGES) { j ->
            val tau = (.055 * (32.0.pow(recovery))) * (1.0 + .18 * j)
            exp(-1.0 / (rate * tau))
        }
        val attack = 1.0 - exp(-1.0 / (rate * (.017 - .014 * spark)))
        val closing = 1.0 - exp(-1.0 / (rate * .007))
        val ratio = if (voice == WickVoice.PLUCK) 2.0 else 3.0
        val reach: Double = run {
            val a = 3.2 * 1.8 * ratio
            val b = 3.2 + 1.8 * ratio
            ((-b + sqrt(b * b - 4.0 * a * (1.0 - 20_000.0 / hz))) / (2.0 * a)).coerceIn(0.0, 1.0)
        }
        val index = 1.8 * timbre.pow(1.4) * reach * (.8 + .2 * velocity)
        val drive = 1.0 + 3.2 * timbre.pow(1.2) * reach
        val phaseOffset = Dsp.phases(2, Dsp.seedFor("WICK", voice.name, midi))
        var sample = 0L
        var pulseIndex = 0L
        var delivered = 0
        var maxQueue = 0
        var rejected = 0
        var endedAdmissions = 0
        var minAvailable = 1.0
        var maxAvailable = 1.0
        var maxCharge = 0.0
        var maxOpening = 0.0
        var maxFilter = 0.0
        var dcX = 0.0
        var dcY = 0.0
        val dcPole = exp(-2.0 * PI * 8.0 / rate)

        private fun trace(p: Pending, admitted: Double, before: Double, after: Double): Event {
            val event = Event(sample.toDouble() / rate, p.parent,
                if (p.release >= 0) p.release.toDouble() / rate else -1.0, p.destination,
                p.strength, admitted, before, after, p.kind, events.size, p.cause,
                if (p.sourceEventId >= 0) "causal-schedule-replay" else "causal", p.sourceEventId)
            events.add(event)
            return event
        }
        private fun admit(p: Pending) {
            if (!held && sample >= admissionEnd) { endedAdmissions++; return }
            while (admittedTimes.isNotEmpty() && admittedTimes.first <= sample - rate) admittedTimes.removeFirst()
            if (admittedTimes.size >= MAX_EVENTS_PER_SECOND || !held && delivered >= MAX_FINITE_EVENTS) {
                rejected++; return
            }
            val gate = gates[p.destination]
            val before = gate.available
            val admitted = min(1.0 - gate.charge, p.strength.coerceIn(0.0, 1.0) * before)
                .coerceIn(0.0, 1.0)
            gate.charge = (gate.charge + admitted).coerceIn(0.0, 1.0)
            gate.available = if (options.constantAvailability) 1.0 else (before - admitted * .92).coerceIn(0.0, 1.0)
            minAvailable = min(minAvailable, gate.available)
            maxCharge = max(maxCharge, gate.charge)
            admittedTimes.addLast(sample)
            delivered++
            trace(p, admitted, before, gate.available)
        }
        private fun schedule(p: Pending) {
            if (!held && p.sample >= admissionEnd) { endedAdmissions++; return }
            if (queue.size >= MAX_QUEUE) { rejected++; return } // Reject newest; keep existing causal order.
            queue.add(p)
            maxQueue = max(maxQueue, queue.size)
        }
        private fun pulse() {
            if (velocity <= 0f) return
            // Quiet gates accept the pulse. An already-open root is allowed to finish rather
            // than being held forever above its closing threshold by a fast feed clock.
            if (gates[0].opening > CLOSE_THRESHOLD) return
            // HOLD declares a .54 recurrent supply floor: at FEED 0 the default SPARK must
            // still open far enough to arm a real release and its answering cascade. Finite
            // notes keep the initial-only FEED 0 gesture; FEED still changes held pulse count
            // and supply strongly. Velocity zero never reaches this point.
            val supply = if (held) .54 + .46 * feed else .42 + .58 * feed
            val strength = (.40 + .48 * spark) * supply * velocity
            admit(Pending(sample, 0, strength, -1, -1, -1, "FEED"))
        }
        private fun initial() {
            if (velocity <= 0f) return
            val strength = (.40 + .58 * spark) * velocity
            admit(Pending(sample, 0, strength, -1, -1, -1, "INITIAL"))
            // A hard spark may reach later stages directly, but the audible answering chain
            // still depends on real prior closures. Soft/neutral spark starts on the first gate.
            if (spark > .65) for (j in 1 until STAGES) {
                admit(Pending(sample, j, strength * (spark - .65) * .45 / j, -1, -1, -1, "INITIAL"))
            }
        }
        fun block(frames: Int): FloatArray {
            val out = FloatArray(frames)
            for (i in 0 until frames) {
                if ((sample and 4095L) == 0L && Thread.currentThread().isInterrupted)
                    throw java.util.concurrent.CancellationException("WICK render cancelled")
                val phaseSample = if (held) (sample % periodSamples).toInt() else 0
                if (replay != null) {
                    for (event in replay) if (event.sample == phaseSample) {
                        admit(Pending(sample, event.destination, event.strength, event.parent,
                            if (event.releaseOffset < 0) sample + event.releaseOffset else -1,
                            -1, event.kind, event.sourceEventId))
                    }
                } else if (held) {
                    val nowPulse = sample / periodSamples * feedCount + phaseSample.toLong() * feedCount / periodSamples
                    if (nowPulse >= pulseIndex) { pulse(); pulseIndex = nowPulse + 1 }
                } else {
                    if (sample == 0L) initial()
                    if (feed > 0.0 && sample >= ((pulseIndex + 1) * rate / feedRate).roundToLong()) {
                        if (sample < admissionEnd) pulse()
                        pulseIndex++
                    }
                }
                // Stable tie order is due-time, destination, then insertion order. Deliveries
                // run before new edge detection, so a release can never recursively act now.
                while (true) {
                    var due = -1
                    for (j in queue.indices) if (queue[j].sample <= sample &&
                        (due < 0 || queue[j].sample < queue[due].sample ||
                            queue[j].sample == queue[due].sample && queue[j].destination < queue[due].destination)) due = j
                    if (due < 0) break
                    admit(queue.removeAt(due))
                }
                val phase = if (held) phaseSample.toDouble() * plan!!.cycles / periodSamples
                    else sample.toDouble() * hz / rate
                val rootPhase = 2.0 * PI * (phase + phaseOffset[0])
                val root = sin(rootPhase)
                val mod = sin(2.0 * PI * (phase * ratio + phaseOffset[1]))
                val complex = Tide.fold(sin(rootPhase + index * mod).toFloat(), drive.toFloat(),
                    (.11 * timbre).toFloat()).toDouble()
                val blend = .25 + .48 * timbre
                val source = (1.0 - blend) * root + blend * complex
                var mixed = 0.0
                for (j in gates.indices) {
                    val gate = gates[j]
                    gate.available = if (options.constantAvailability) 1.0 else
                        (1.0 - (1.0 - gate.available) * recoveryPole[j]).coerceIn(0.0, 1.0)
                    gate.charge = (gate.charge * chargeDecay[j]).coerceIn(0.0, 1.0)
                    val smooth = if (gate.charge > gate.opening) attack else closing
                    gate.opening = (gate.opening + smooth * (gate.charge - gate.opening)).coerceIn(0.0, 1.0)
                    minAvailable = min(minAvailable, gate.available)
                    maxAvailable = max(maxAvailable, gate.available)
                    maxOpening = max(maxOpening, gate.opening)
                    val sourceMix = when (j) {
                        1 -> (1.0 - .20 * timbre) * source + .20 * timbre * sin(2.0 * rootPhase)
                        2 -> (1.0 - .14 * timbre) * source + .14 * timbre * sin(1.5 * rootPhase)
                        else -> source
                    }
                    val low = max(15.0, hz * .60)
                    val high = max(5_500.0, hz * (18.0 - 3.0 * j)).coerceAtMost(14_000.0)
                    val cutoff = low * exp(ln(high / low) * gate.opening.pow(.64 + .07 * j))
                    val filtered = gate.filter.next(sourceMix, cutoff)
                    maxFilter = max(maxFilter, max(abs(gate.filter.first), abs(gate.filter.second)))
                    val vca = gate.opening.pow(1.22)
                    val weight = if (j == 0) 1.0 else (.60 + .42 * cascade) / (1.0 + .15 * j)
                    mixed += (if (options.gainOnly) sourceMix else filtered) * vca * weight
                    if (gate.opening >= OPEN_THRESHOLD) {
                        gate.armed = true
                        gate.peak = max(gate.peak, gate.opening)
                    }
                    if (gate.armed && gate.opening <= CLOSE_THRESHOLD) {
                        gate.armed = false
                        val transmission = (.18 + .82 * cascade) * (.55 + .45 * gate.peak)
                        val destination = (j + 1) % STAGES
                        val release = Pending(sample, destination, transmission, j, sample, -1, "RELEASE")
                        val cause = trace(release, 0.0, gates[destination].available, gates[destination].available)
                        if (replay == null && cascade > 0.0 && (j < STAGES - 1 || cascade > .78) &&
                            sample - gate.lastRelease >= (.025 * rate).roundToLong()) {
                            val delay = ((if (voice == WickVoice.PLUCK) .065 else .028) +
                                .025 * j + .07 * recovery) * rate
                            val strength = transmission * (if (j == STAGES - 1) .24 else 1.0)
                            schedule(Pending(sample + delay.roundToLong().coerceAtLeast(1), destination,
                                strength, j, sample, cause.id, "CASCADE"))
                        }
                        gate.lastRelease = sample
                        gate.peak = 0.0
                    }
                    check(gate.charge.isFinite() && gate.available.isFinite() && gate.opening.isFinite() &&
                        gate.filter.first.isFinite() && gate.filter.second.isFinite()) { "WICK nonfinite gate state" }
                }
                val x = .48 * mixed
                dcY = x - dcX + dcPole * dcY
                dcX = x
                check(dcY.isFinite() && abs(dcY) < 4.0) { "WICK raw source exceeded its bounds" }
                out[i] = if (velocity == 0f) 0f else dcY.toFloat()
                sample++
            }
            return out
        }
        fun state(): DoubleArray {
            val s = ArrayList<Double>()
            s.add(dcX); s.add(dcY)
            s.add(if (held) (sample % periodSamples).toDouble() / periodSamples else sample.toDouble() / rate)
            // The periodic oscillator phases and pulser clock are functions of this phase.
            for (gate in gates) {
                s.add(gate.available); s.add(gate.charge); s.add(gate.opening)
                s.add(if (gate.armed) 1.0 else 0.0); s.add(gate.peak)
                s.add(min(.025, (sample - gate.lastRelease).toDouble() / rate))
                s.add(gate.filter.first); s.add(gate.filter.second)
            }
            val ordered = queue.sortedWith(compareBy<Pending> { it.sample }.thenBy { it.destination })
            s.add(ordered.size.toDouble())
            for (p in ordered) {
                s.add((p.sample - sample).toDouble() / rate); s.add(p.destination.toDouble())
                s.add(p.strength); s.add(p.parent.toDouble()); s.add((p.release - sample).toDouble() / rate)
            }
            // The rolling admission budget affects future decisions too.
            val recent = admittedTimes.filter { it > sample - rate }
            s.add(recent.size.toDouble())
            for (time in recent) s.add((time - sample).toDouble() / rate)
            return s.toDoubleArray()
        }
    }

    private fun held(voice: WickVoice, m: Map<String, Float>, midi: Int, velocity: Float, options: Options): Probe {
        val p = plan(midi)
        var engine = Engine(voice, m, midi, velocity, options, p)
        var previous = FloatArray(0)
        var current = FloatArray(0)
        var oldState = engine.state()
        var stateError = Double.POSITIVE_INFINITY
        var audioError = Double.POSITIVE_INFINITY
        var attempts = 0
        var converged = false
        var strategy = "causal"
        var replaySourceEvents = emptyList<Event>()
        var replaySourcePeriodStartSeconds: Double? = null
        fun search(): Boolean {
            var confirmations = 0
            previous = FloatArray(0)
            current = FloatArray(0)
            oldState = engine.state()
            repeat(MAX_LOOP_ATTEMPTS) {
                previous = current
                current = engine.block(engine.periodSamples)
                val state = engine.state()
                attempts++
                stateError = difference(oldState, state)
                audioError = if (previous.size == current.size) relativeDifference(previous, current) else Double.POSITIVE_INFINITY
                oldState = state
                if (stateError < 2e-6 && audioError < 1e-7) confirmations++ else confirmations = 0
                if (confirmations >= 2) return true
            }
            return false
        }
        converged = search()
        if (!converged) {
            // Capture a real causal period (including secondary deliveries) before replacing
            // only the scheduler. Replay re-admits requested strengths against live reserves.
            val end = engine.sample
            val from = end - engine.periodSamples
            // Keep the complete causal source trace, including parents just before the selected
            // period. Replayed deliveries refer to this namespace via sourceEventId; they do not
            // pretend that the converged gates necessarily close at the captured source times.
            replaySourceEvents = engine.events.toList()
            replaySourcePeriodStartSeconds = from.toDouble() / engine.rate
            val replay = engine.events.filter { it.kind != "RELEASE" &&
                it.timeSeconds >= from.toDouble() / engine.rate - 1e-10 &&
                it.timeSeconds < end.toDouble() / engine.rate - 1e-10 }.map {
                Replay((it.timeSeconds * engine.rate).roundToLong().minus(from).toInt().coerceIn(0, engine.periodSamples - 1),
                    it.destination, it.requestedStrength, it.parentStage,
                    if (it.releaseSeconds >= 0) ((it.releaseSeconds - it.timeSeconds) * engine.rate).roundToLong() else 0,
                    it.kind, it.id)
            }
            require(replay.isNotEmpty() || velocity == 0f) { "WICK HOLD has no causal events to replay" }
            strategy = "causal-schedule-replay"
            engine = Engine(voice, m, midi, velocity, options, p, replay)
            converged = search()
        }
        require(converged) { "WICK HOLD did not converge: $voice MIDI $midi strategy=$strategy state=$stateError audio=$audioError" }
        // Verify another independent period, then preserve the preceding one and a following
        // guard while bandlimiting/decimating. The resampler never sees a synthetic cut boundary.
        previous = current
        current = engine.block(engine.periodSamples)
        stateError = difference(oldState, engine.state())
        audioError = relativeDifference(previous, current)
        require(stateError < 2e-6 && audioError < 1e-7) { "WICK HOLD recurrence did not persist" }
        val guard = engine.block(min(engine.periodSamples, 4096 * options.oversample))
        val down = condition(previous + current + guard, options.oversample)
        val before = down.copyOfRange(0, p.frames)
        val raw = down.copyOfRange(p.frames, 2 * p.frames)
        val context = before + raw
        val seam = if (velocity == 0f) 0.0 else Keys.seamError(context, p.frames)
        require(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR) { "WICK HOLD seam $seam exceeds ${Keys.MAX_SEAM_ERROR}" }
        val samples = raw.copyOf()
        Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        val rawPeak = raw.maxOfOrNull { abs(it) } ?: 0f
        val gain = if (rawPeak > 1e-12f) (samples.maxOfOrNull { abs(it) } ?: 0f) / rawPeak else 1f
        for (i in context.indices) context[i] *= gain
        val report = LoopDiagnostics(p.frames, p.seconds, attempts, stateError, seam, true, strategy,
            audioError, Keys.midiHz(midi).toDouble(), p.hz)
        return Probe(raw, samples, diagnostics(raw, engine, report).copy(
            replaySourceEvents = replaySourceEvents,
            replaySourcePeriodStartSeconds = replaySourcePeriodStartSeconds,
        ), context)
    }

    private fun difference(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return Double.POSITIVE_INFINITY
        var error = 0.0
        for (i in a.indices) error = max(error, abs(a[i] - b[i]))
        return error
    }
    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return Double.POSITIVE_INFINITY
        var error = 0.0
        var energy = 0.0
        for (i in a.indices) { val d = a[i].toDouble() - b[i]; error += d * d; energy += a[i].toDouble() * a[i] }
        return if (energy < 1e-20) error else error / energy
    }
    private fun condition(up: FloatArray, oversample: Int): FloatArray {
        val buf = up.copyOf()
        Tide.bandLimit(buf, Dsp.RATE * oversample)
        if (oversample == 4) return Dsp.decimate(buf, Dsp.RATE)
        if (oversample == 1) return buf
        var snip = Snip(buf, channels = 1, sampleRate = Dsp.RATE * oversample)
        while (snip.sampleRate > Dsp.RATE) snip = Resampler.resample(snip, snip.sampleRate / 2)
        return snip.samples
    }
    private fun diagnostics(raw: FloatArray, engine: Engine, loop: LoopDiagnostics?): Diagnostics {
        var peak = 0.0
        var energy = 0.0
        var sum = 0.0
        for (value in raw) { val x = value.toDouble(); peak = max(peak, abs(x)); energy += x * x; sum += x }
        return Diagnostics(peak, sqrt(energy / raw.size.coerceAtLeast(1)), sum / raw.size.coerceAtLeast(1),
            engine.events.toList(), engine.maxQueue, engine.rejected, engine.minAvailable, engine.maxAvailable,
            engine.maxCharge, engine.maxOpening, engine.maxFilter, loop, engine.endedAdmissions)
    }
}
