package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

enum class WickVoice { PLUCK, BUBBLE, WOOD, BREATH, FLICKER, RATTLE }

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

    fun rootMidi(voice: WickVoice): Int = when (voice) {
        WickVoice.PLUCK, WickVoice.WOOD -> 48
        WickVoice.BUBBLE, WickVoice.BREATH, WickVoice.FLICKER, WickVoice.RATTLE -> 60
    }
    fun macrosFor(voice: WickVoice): List<MacroSpec> {
        val d = when (voice) {
            WickVoice.PLUCK -> floatArrayOf(.45f, .05f, .20f, .25f, .30f, 0f)
            // Listening revision: separate the bubbles; Busy Bubbles retains the original feed.
            WickVoice.BUBBLE -> floatArrayOf(.40f, .25f, .70f, .45f, .50f, 0f)
            WickVoice.WOOD -> floatArrayOf(.60f, .15f, .40f, .40f, .40f, 0f)
            WickVoice.BREATH -> floatArrayOf(.20f, .60f, .35f, .20f, .70f, 0f)
            WickVoice.FLICKER -> floatArrayOf(.55f, .45f, .65f, .80f, .35f, 0f)
            // Keep the first rattle readable; the supplied dense setting is a separate recipe.
            WickVoice.RATTLE -> floatArrayOf(.65f, .50f, .65f, .45f, .65f, 0f)
        }
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
        /** Diagnostic reference: reproduce BUBBLE's original compact envelope and stage mix. */
        val legacyBubbleEnvelope: Boolean = false,
        /** Diagnostic reference: reproduce RATTLE's earlier separate, repeated openings. */
        val legacyRattleEnvelope: Boolean = false,
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
        val roundedBubble = voice == WickVoice.BUBBLE && !options.legacyBubbleEnvelope
        val cohesiveRattle = voice == WickVoice.RATTLE && !options.legacyRattleEnvelope
        val gates = Array(STAGES) { Gate(rate) }
        val queue = ArrayList<Pending>()
        val events = ArrayList<Event>()
        val admittedTimes = java.util.ArrayDeque<Long>()
        val feedRate = .5 + 7.5 * feed.pow(1.45)
        val feedCount = if (held) max(1, (plan!!.seconds * (.65 + 7.35 * feed.pow(1.45))).roundToInt()) else 0
        // A 200 ms sub-bass envelope is heard/classified as a kick even when its oscillator is
        // perfectly pitched. The original bass gates stretch the root lifetime three times
        // through C3, returning to their compact C4 articulation. Rounded BUBBLE instead starts
        // with a longer primary ring and applies only 1..1.4x primary tracking, retaining a
        // pitched midrange tail without making the bass chain outlast its excitation window.
        val registerRelease = run {
            val x = ((midi - 48) / 12.0).coerceIn(0.0, 1.0)
            1.0 + 2.0 * (1.0 - x * x * (3.0 - 2.0 * x))
        }
        val bassTime = .40 * (registerRelease - 1.0)
        val legacyFiniteSeconds = when (voice) {
            WickVoice.PLUCK -> 2.1 + 1.1 * recovery + .5 * feed
            WickVoice.BUBBLE -> 3.0 + 1.3 * recovery + .8 * feed
            WickVoice.WOOD -> 2.3 + .9 * recovery + .35 * feed
            WickVoice.BREATH -> 3.5 + 1.0 * recovery + .5 * feed
            WickVoice.FLICKER -> 2.1 + .9 * recovery + .4 * feed
            WickVoice.RATTLE -> 3.2 + 1.0 * recovery + .7 * feed
        } + bassTime
        val finiteSeconds = when {
            roundedBubble -> legacyFiniteSeconds + .4
            // Soft secondary openings need a longer real ring-out, not a longer FEED window.
            voice == WickVoice.BREATH -> legacyFiniteSeconds + .9
            // The gentler RATTLE body remains audible farther below its closing edge,
            // especially in the bass. Let that real tail decay before the final wrap fade;
            // admissionEnd still uses the earlier lifetime and admits no extra pulses.
            cohesiveRattle -> legacyFiniteSeconds + .20 + .20 * recovery + .50 * (registerRelease - 1.0)
            else -> legacyFiniteSeconds
        }
        val finiteSamples = (finiteSeconds * rate).roundToInt()
        // Stop all *admission*, including late cascade events, before an actual decaying tail.
        // The added BUBBLE tail lets its actual gates ring; it admits no extra feed or cascades.
        val admissionEnd = ((legacyFiniteSeconds - (1.0 + .7 * recovery)) * rate).roundToInt().toLong()
        val chargeDecay = DoubleArray(STAGES) { j ->
            val base = when (voice) {
                WickVoice.PLUCK -> doubleArrayOf(.19, .14, .115)[j]
                WickVoice.BUBBLE -> if (roundedBubble && j == 0) .24 else doubleArrayOf(.092, .075, .11)[j]
                WickVoice.WOOD -> doubleArrayOf(.15, .09, .065)[j]
                // The first gate must still close. Longer answering gates bridge its later
                // soft re-openings rather than holding it permanently above the release edge.
                WickVoice.BREATH -> doubleArrayOf(.24, .35, .45)[j]
                // Retain a pitched first body; the answering gates expose briefer bright flashes.
                WickVoice.FLICKER -> doubleArrayOf(.205, .065, .045)[j]
                // Shorter primary/answer charges expose clusters and allow real reserve
                // depletion between openings, while a gentle VCA leaves the root audible.
                WickVoice.RATTLE -> doubleArrayOf(.11, .067, .047)[j]
            }
            val stretch = when {
                voice == WickVoice.BREATH -> 1.0 + .08 * (registerRelease - 1.0)
                voice == WickVoice.RATTLE -> 1.0 + .4 * (registerRelease - 1.0) / (1.0 + j)
                roundedBubble && j == 0 -> 1.0 + .2 * (registerRelease - 1.0)
                else -> 1.0 + (registerRelease - 1.0) / (1.0 + 2.0 * j)
            }
            exp(-1.0 / (rate * base * (.62 + 1.05 * recovery) * stretch))
        }
        val recoveryPole = DoubleArray(STAGES) { j ->
            val tau = (.055 * (32.0.pow(recovery))) * (1.0 + .18 * j)
            exp(-1.0 / (rate * tau))
        }
        val attackSeconds = when (voice) {
            WickVoice.PLUCK -> .017 - .014 * spark
            WickVoice.BUBBLE -> if (roundedBubble) .034 - .022 * spark else .017 - .014 * spark
            WickVoice.WOOD -> .015 - .010 * spark
            WickVoice.BREATH -> .070 - .050 * spark
            WickVoice.FLICKER -> .004 - .002 * spark
            // The earlier short attack reads as another hit every time FEED opens the root.
            // Let the same admitted charge swell into its still-audible residual tail.
            WickVoice.RATTLE -> if (cohesiveRattle) .060 - .035 * spark else .020 - .012 * spark
        }
        val attack = 1.0 - exp(-1.0 / (rate * attackSeconds))
        val closing = 1.0 - exp(-1.0 / (rate * when (voice) {
            WickVoice.BREATH -> .025
            WickVoice.FLICKER -> .0045
            WickVoice.RATTLE -> if (cohesiveRattle) .025 else .010
            else -> .007
        }))
        val ratio = when (voice) {
            WickVoice.PLUCK -> 2.0
            WickVoice.BUBBLE -> 3.0
            WickVoice.WOOD -> 1.0
            WickVoice.BREATH -> 2.0
            // Half-order sidebands provide a metallic color. The even root-cycle HOLD plan
            // closes this 3:2 modulator exactly without snapping it to another pitch.
            WickVoice.FLICKER -> 1.5
            // Integer color reinforces the requested note during the recurring openings.
            WickVoice.RATTLE -> 2.0
        }
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
            val supply = if (held) {
                // A soft attack loses peak opening while charge decays. BREATH's recurrent
                // floor lets its default SPARK arm a real release even at FEED zero.
                if (voice == WickVoice.BREATH) .70 + .30 * feed else .54 + .46 * feed
            } else .42 + .58 * feed
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
                val blend = when (voice) {
                    WickVoice.PLUCK, WickVoice.BUBBLE -> .25 + .48 * timbre
                    WickVoice.WOOD -> .20 + .26 * timbre
                    // Keep a plainly pitched carrier under the soft articulation. BREATH is
                    // a gate gesture, with no noise bed, formant model or separate air layer.
                    WickVoice.BREATH -> .12 + .30 * timbre
                    // Half-order color must stay below the root-period cue. A larger blend
                    // made the 3:2 source read an octave low despite a strong root projection.
                    WickVoice.FLICKER -> .10 + .12 * timbre
                    WickVoice.RATTLE -> .14 + .28 * timbre
                }
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
                    val sourceMix = when (voice) {
                        WickVoice.PLUCK, WickVoice.BUBBLE -> when (j) {
                            1 -> (1.0 - .20 * timbre) * source + .20 * timbre * sin(2.0 * rootPhase)
                            2 -> (1.0 - .14 * timbre) * source + .14 * timbre * sin(1.5 * rootPhase)
                            else -> source
                        }
                        WickVoice.BREATH -> {
                            val partial = if (j == 2) 3.0 else 2.0
                            val color = (if (j == 0) .06 else .10) * timbre
                            (1.0 - color) * source + color * sin(partial * rootPhase)
                        }
                        WickVoice.FLICKER -> {
                            // The later gates reveal quiet half-order glints; their amplitudes
                            // follow the same admitted charge and recovery as the root gate.
                            val color = when (j) { 1 -> .12 * timbre; 2 -> .09 * timbre; else -> 0.0 }
                            val partial = if (j == 1) 2.5 else 3.5
                            (1.0 - color) * source + color * sin(partial * rootPhase)
                        }
                        WickVoice.RATTLE -> {
                            val color = when (j) { 1 -> .10 * timbre; 2 -> .08 * timbre; else -> 0.0 }
                            val partial = if (j == 1) 2.0 else 3.0
                            (1.0 - color) * source + color * sin(partial * rootPhase)
                        }
                        WickVoice.WOOD -> {
                            // A quiet generated body partial follows this same gate's opening.
                            // It is a perceptual wooden knock, not a sampled or measured body.
                            val body = (when (j) { 0 -> .32; 1 -> .22; else -> .16 }) *
                                timbre * gate.opening.pow(.35)
                            val partial = if (j == 1) 2.0 else 3.0
                            (1.0 - body) * source + body * sin(partial * rootPhase)
                        }
                    }
                    val low = when (voice) {
                        WickVoice.PLUCK, WickVoice.BUBBLE -> max(15.0, hz * .60)
                        WickVoice.WOOD -> max(15.0, hz * .72)
                        WickVoice.BREATH -> max(15.0, hz * .95)
                        WickVoice.FLICKER -> max(15.0, hz * .80)
                        WickVoice.RATTLE -> max(15.0, hz * .90)
                    }
                    val high = when (voice) {
                        WickVoice.PLUCK, WickVoice.BUBBLE -> max(5_500.0, hz * (18.0 - 3.0 * j)).coerceAtMost(14_000.0)
                        WickVoice.WOOD -> max(when (j) { 0 -> 4_400.0; 1 -> 3_400.0; else -> 2_600.0 },
                            hz * (12.0 - 2.0 * j)).coerceAtMost(11_000.0)
                        WickVoice.BREATH -> max(3_200.0 - 450.0 * j,
                            hz * (14.0 - 2.0 * j)).coerceAtMost(10_000.0)
                        WickVoice.FLICKER -> max(when (j) { 0 -> 9_000.0; 1 -> 11_000.0; else -> 10_000.0 },
                            hz * (24.0 - 2.0 * j)).coerceAtMost(15_000.0)
                        WickVoice.RATTLE -> max(when (j) { 0 -> 5_500.0; 1 -> 6_200.0; else -> 4_800.0 },
                            hz * (14.0 - 2.0 * j)).coerceAtMost(12_000.0)
                    }
                    val toneExponent = when (voice) {
                        WickVoice.PLUCK, WickVoice.BUBBLE -> .64 + .07 * j
                        WickVoice.WOOD -> .58 + .09 * j
                        WickVoice.BREATH -> .50 + .08 * j
                        WickVoice.FLICKER -> .42 + .05 * j
                        WickVoice.RATTLE -> if (cohesiveRattle) .42 + .05 * j else .62 + .04 * j
                    }
                    val cutoff = low * exp(ln(high / low) * gate.opening.pow(toneExponent))
                    val filtered = gate.filter.next(sourceMix, cutoff)
                    maxFilter = max(maxFilter, max(abs(gate.filter.first), abs(gate.filter.second)))
                    // A closing BREATH gate stays gently audible below the causal closing
                    // threshold. Its actual residual tail meets the next opening; no bypass
                    // drone is mixed in, and CASCADE still comes from the real closing edge.
                    val vca = gate.opening.pow(when {
                        voice == WickVoice.BREATH -> .78
                        // A slightly gentler primary power keeps the note's body present;
                        // steeper secondary powers close the audible metallic flashes quickly.
                        voice == WickVoice.FLICKER -> if (j == 0) 1.10 else 1.55
                        // A closing root retains a continuous pitched body below the real
                        // release edge. Answers are quieter motion inside that same body.
                        voice == WickVoice.RATTLE -> if (cohesiveRattle) {
                            if (j == 0) .45 else .85
                        } else if (j == 0) 1.02 else 1.35
                        else -> 1.22
                    })
                    val weight = if (j == 0) 1.0 else (.60 + .42 * cascade) / (1.0 + .15 * j)
                    val voicedWeight = when {
                        roundedBubble && j > 0 -> weight * (if (j == 1) .55 else .42)
                        voice == WickVoice.WOOD && j > 0 -> weight * (if (j == 1) .50 else .34)
                        voice == WickVoice.FLICKER && j > 0 -> weight * (if (j == 1) .80 else .65)
                        voice == WickVoice.RATTLE && j > 0 -> weight * (if (cohesiveRattle) {
                            if (j == 1) .42 else .30
                        } else if (j == 1) .75 else .60)
                        else -> weight
                    }
                    mixed += (if (options.gainOnly) sourceMix else filtered) * vca * voicedWeight
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
                            val delayBase = when (voice) {
                                WickVoice.PLUCK -> .065
                                WickVoice.BUBBLE -> .028
                                WickVoice.WOOD -> .045
                                WickVoice.BREATH -> .025
                                WickVoice.FLICKER -> .018
                                WickVoice.RATTLE -> .020
                            }
                            val stageDelay = when (voice) {
                                WickVoice.PLUCK, WickVoice.BUBBLE -> .025
                                WickVoice.WOOD -> .020
                                WickVoice.BREATH -> .020
                                WickVoice.FLICKER -> .012
                                WickVoice.RATTLE -> .014
                            }
                            val recoveryDelay = when (voice) {
                                WickVoice.PLUCK, WickVoice.BUBBLE -> .07
                                WickVoice.WOOD -> .055
                                WickVoice.BREATH -> .040
                                WickVoice.FLICKER -> .035
                                WickVoice.RATTLE -> .040
                            }
                            val delay = (delayBase + stageDelay * j + recoveryDelay * recovery) * rate
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
