package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class CisternVoice { FIRST, DRIP, CASCADE, POOL, RIPPLE, RECOVERY }

/**
 * A struck modal surface releases a finite suspended inventory. Landings excite
 * that same surface, add mass, and change its losses and contact compliance.
 * Distances, masses and gravity are normalized musical units, not measurements
 * of a real suspended-liquid instrument. All acoustic integration and rounded
 * contact pulses run at 4x; only the slow material/release clock runs at ~1 kHz.
 *
 * Modal state is an energy-normalized pair: its free update is a rotation and
 * a contraction. Frame exchange is an orthogonal rotation, not positive audio
 * feedback. Adding modal mass removes energy; removing mass never restores it.
 * This avoids the hidden excitation caused by interpolating resonator poles.
 *
 * HOLD powers a finite-capacity, delayed drain return. Once per cycle a slot
 * can respond to its local release field, provided it has actually refilled.
 * These constrained opportunities let the complete reservoir state settle to a
 * repeatable cycle. The public sample API has no attack/loop region: a HOLD
 * buffer is two repetitions of the settled cycle, without the initiating hit.
 */
object Cistern {
    const val MODEL_VERSION = 1
    const val MIDI_MIN = 36
    const val MIDI_MAX = 84
    const val DEFAULT_MIDI = 60
    const val HOLD_LOOP = 0.85f
    const val REGIONS = 6
    const val MIN_TRAVEL = 0.04
    const val MAX_TRAVEL = 0.50
    const val REGION_CAPACITY = 1.25
    const val MAX_ONESHOT_SECONDS = 8f
    const val MAX_HOLD_CYCLES = 10

    private const val MODES = 10
    private const val FRAME_MODES = 3
    private const val UP_RATE = RATE * Dsp.OVERSAMPLE
    // Fixed radiation trim, including raw auditions. The five-step neutral
    // sweep exposed a 1.217 raw peak at the earlier .18 setting (RIPPLE,
    // SKIN=1); .04 reserves headroom for interacting dense contacts without
    // limiting their waveform or changing the internal energy accounting.
    private const val OUTPUT_GAIN = .04
    private const val CONTROL_SAMPLES = 176
    private const val CONTROL_DT = CONTROL_SAMPLES.toDouble() / UP_RATE
    private const val HOLD_SECONDS = 2.4
    private val NAMES = listOf("STRIKE", "SUSPENSION", "DROP", "SKIN", "DRAIN", "HOLD")
    private val RATIOS = doubleArrayOf(1.0, 1.60, 2.20, 2.90, 3.80, 4.85, 5.95, 7.05, 8.25, 9.45)
    private val DEFAULTS = mapOf(
        CisternVoice.FIRST to values(.55f, .25f, .30f, .65f, .65f),
        CisternVoice.DRIP to values(.35f, .40f, .45f, .55f, .50f),
        CisternVoice.CASCADE to values(.60f, .75f, .50f, .60f, .45f),
        CisternVoice.POOL to values(.50f, .60f, .80f, .35f, .20f),
        CisternVoice.RIPPLE to values(.45f, .70f, .25f, .80f, .65f),
        CisternVoice.RECOVERY to values(.55f, .60f, .65f, .55f, .80f),
    )

    private fun values(strike: Float, suspension: Float, drop: Float, skin: Float, drain: Float) = linkedMapOf(
        "STRIKE" to strike, "SUSPENSION" to suspension, "DROP" to drop,
        "SKIN" to skin, "DRAIN" to drain, "HOLD" to 0f,
    )

    fun defaults(voice: CisternVoice): Map<String, Float> = DEFAULTS.getValue(voice)
    fun macrosFor(voice: CisternVoice): List<MacroSpec> = defaults(voice).map { (name, value) -> MacroSpec(name, value, value) }
    fun scramble(voice: CisternVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> =
        Dsp.scrambleNear(if (near is CisternPatch) settledMacros(voice, near.macros) else defaults(voice), temperature, random)
    fun frequencyFor(midi: Int): Float = Keys.midiHz(midi)
    fun isLoop(hold: Float): Boolean = hold >= HOLD_LOOP
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: CisternVoice, @Suppress("UNUSED_PARAMETER") macros: Map<String, Float> = emptyMap()): DrumClass = DrumClass.LOOP
    fun render(voice: CisternVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI, velocity: Float = 1f): Snip =
        renderInternal(voice, macros, midi, velocity).snip

    internal fun settledMacros(voice: CisternVoice, macros: Map<String, Float>): Map<String, Float> {
        val out = LinkedHashMap(defaults(voice))
        for (name in NAMES) macros[name]?.takeIf { it.isFinite() }?.let { out[name] = it.coerceIn(0f, 1f) }
        return out
    }

    internal enum class EventKind { STRIKE, RELEASE, LANDING, MAINTENANCE, PUMP_RETURN }
    internal data class Event(
        val id: Int, val kind: EventKind, val time: Double, val slot: Int,
        val region: Int, val cause: Int, val mass: Double = 0.0, val travel: Double = 0.0,
        val impulse: Double = 0.0,
    )
    internal data class Trace(
        val time: Double, val surface: FloatArray, val suspended: Double,
        val airborne: Double, val drained: Double, val pump: Double,
        val inventory: Double, val energy: Double, val dissipated: Double,
        val inputEnergy: Double, val drainFlow: Double,
    )
    internal class Diagnostics(
        val events: List<Event>, val traces: List<Trace>, val slotCount: Int,
        val initialInventory: Double, val maxInventoryError: Double,
        val maxEnergy: Double, val finalEnergy: Double, val maxLoad: Double,
        val nonFiniteRecoveries: Int, val pendingLandings: Int,
        val inputEnergy: Double, val dissipated: Double,
    )
    internal class LoopReport(
        val period: FloatArray, val previous: FloatArray, val seam: Double,
        val periodDiff: Float, val stateDiff: Double, val materialDiff: Double,
        val iterations: Int, val converged: Boolean, val pumpCapacity: Double,
        val pumpDelay: Double, val eventsPerPeriod: Int, val crossfadeSamples: Int,
        val eventDiff: Double, val relativePeriodDiff: Double,
        val boundaryStepError: Double, val boundarySlopeError: Double,
    )
    internal class Rendered(
        val snip: Snip, val raw: FloatArray, val diagnostics: Diagnostics,
        val loop: LoopReport?, val bytes: Long, val renderNanos: Long,
        val frame: FloatArray,
    )

    /**
     * Diagnostic controls are deliberately outside the macro surface. A frozen
     * loading render first obtains the normal schedule and then replays those
     * exact impacts, so differences cannot be caused by changed branching.
     * [replayEvents] also permits an isolated drop-only audition of a real run.
     * [seconds] requests a bounded continuous diagnostic run, including HOLD's
     * initial transient; absent it, HOLD returns the converged loop instead.
     */
    internal fun renderInternal(
        voice: CisternVoice, macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = 1f, seconds: Float? = null,
        noDrops: Boolean = false, frozenLoad: Boolean = false,
        initialStrike: Boolean = true, dropSound: Boolean = true,
        maintenance: Boolean = true, replayEvents: List<Event>? = null,
    ): Rendered {
        val started = System.nanoTime()
        require(midi in MIDI_MIN..MIDI_MAX) { "CISTERN midi out of $MIDI_MIN..$MIDI_MAX: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "CISTERN velocity out of 0..1: $velocity" }
        require(seconds == null || seconds.isFinite() && seconds in .1f..30f) { "CISTERN diagnostic seconds out of .1..30: $seconds" }
        val m = settledMacros(voice, macros)
        val held = isLoop(m.getValue("HOLD"))
        require(!held || !frozenLoad && replayEvents == null) { "CISTERN frozen/replayed diagnostics require a one-shot; held circulation must run its pump state" }
        if (held && seconds == null && !frozenLoad && replayEvents == null) {
            return renderLoop(voice, m, midi, velocity, noDrops, initialStrike, dropSound, maintenance, started)
        }
        val length = seconds?.toDouble() ?: lengthSeconds(m).toDouble()
        val original = if (frozenLoad && replayEvents == null) {
            Engine(voice, m, midi, velocity, noDrops, initialStrike, dropSound, maintenance, held, false, null).run(length)
        } else null
        val schedule = replayEvents ?: original?.diagnostics?.events
        val run = Engine(voice, m, midi, velocity, noDrops, initialStrike, dropSound, maintenance, held, frozenLoad, schedule).run(length)
        val raw = finish(run.audio, fade = !held)
        val normalized = raw.copyOf()
        Dsp.levelTo(normalized, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        val frame = Dsp.decimate(run.frame, RATE)
        return Rendered(Snip(normalized, channels = 1, sampleRate = RATE), raw,
            run.diagnostics, null, run.bytes + raw.size * 4L * 4,
            System.nanoTime() - started, frame)
    }

    internal fun lengthSeconds(macros: Map<String, Float>): Float =
        (3.3f + 1.4f * macros.getValue("SUSPENSION") + .8f * macros.getValue("DROP") +
            .5f * (1f - macros.getValue("DRAIN")) + .4f * macros.getValue("HOLD").coerceAtMost(HOLD_LOOP)).coerceAtMost(6.4f)

    private fun finish(up: FloatArray, fade: Boolean): FloatArray {
        val out = Dsp.decimate(up, RATE)
        if (fade) {
            val n = min((.04 * RATE).toInt(), out.size)
            for (i in 0 until n) out[out.size - n + i] *= (1.0 - i / (n - 1.0)).toFloat()
        }
        return out
    }

    private class Slot(
        val region: Int, val position: Double, val mass: Double, val height: Double,
        val thresholdBias: Double, val phase: Double, var available: Double,
    ) {
        var state = 0 // suspended, airborne, unavailable (partial refill remains explicit)
        var accumulator = 0.0
        var releaseId = -1
        var landingId = -1
        var arrival = Double.POSITIVE_INFINITY
        var releasedAt = 0.0
        var lastOpportunity = -1
    }
    private data class Front(val at: Double, val region: Int, val strength: Double, val cause: Int)
    private data class Return(val at: Double, val mass: Double)
    private class Pulse(val at: Double, val length: Int, val amplitude: Double, val projection: DoubleArray, val noise: Dsp.Noise, val textureId: Int) {
        var index = 0
        var splash = 0.0
    }
    private class Run(val audio: FloatArray, val frame: FloatArray, val diagnostics: Diagnostics, val state: DoubleArray, val material: DoubleArray, val bytes: Long)

    private class Engine(
        val voice: CisternVoice, val m: Map<String, Float>, midi: Int, val velocity: Float,
        val noDrops: Boolean, val initialStrike: Boolean, val dropSound: Boolean,
        val maintenance: Boolean, val held: Boolean, val frozenLoad: Boolean,
        val replay: List<Event>?,
    ) {
        val strike = m.getValue("STRIKE").toDouble()
        val suspension = m.getValue("SUSPENSION").toDouble()
        val drop = m.getValue("DROP").toDouble()
        val skin = m.getValue("SKIN").toDouble()
        val drain = m.getValue("DRAIN").toDouble()
        val hz = frequencyFor(midi).toDouble()
        // Align the entire fluid/release clock as well as audio to the period.
        val cycleSamples = (HOLD_SECONDS * UP_RATE / CONTROL_SAMPLES).roundToInt() * CONTROL_SAMPLES
        val period = cycleSamples.toDouble() / UP_RATE
        val pumpCapacity: Double
        val pumpDelay = .22 + .18 * (1.0 - drain)
        val slots: List<Slot>
        val initialInventory: Double
        val q = DoubleArray(MODES)
        val p = DoubleArray(MODES)
        val fq = DoubleArray(FRAME_MODES)
        val fp = DoubleArray(FRAME_MODES)
        val c = DoubleArray(MODES)
        val s = DoubleArray(MODES)
        val r = DoubleArray(MODES)
        val fc = DoubleArray(FRAME_MODES)
        val fs = DoubleArray(FRAME_MODES)
        val fr = DoubleArray(FRAME_MODES)
        val modeMass = DoubleArray(MODES) { 1.0 }
        val loads = DoubleArray(REGIONS)
        val initialLoads = DoubleArray(REGIONS)
        val smoothedLoad = DoubleArray(REGIONS)
        val envelope = DoubleArray(REGIONS)
        val field = DoubleArray(REGIONS)
        val cause = IntArray(REGIONS) { -1 }
        val refractory = DoubleArray(REGIONS) { -1.0 }
        val fronts = ArrayList<Front>()
        val returns = ArrayList<Return>()
        val pulses = ArrayList<Pulse>()
        val events = ArrayList<Event>()
        val traces = ArrayList<Trace>()
        var drained = 0.0
        var ready = 0.0
        var pumpMass = 0.0
        var inputEnergy = 0.0
        var dissipated = 0.0
        var maxEnergy = 0.0
        var maxLoad = 0.0
        var maxInventoryError = 0.0
        var nonFinite = 0
        var sample = 0L
        var maintenanceBeat = -1
        var lastRelease = -1.0
        var replayIndex = 0
        var started = false
        var dcX = 0.0
        var dcY = 0.0
        var nextTrace = 0.0
        val shape = Array(REGIONS) { region -> DoubleArray(MODES) { mode -> spatial(mode, (region + .4) / REGIONS) } }
        val coupleC = DoubleArray(FRAME_MODES) { cos((13.0 + 12.0 * (1.0 - skin)) / (UP_RATE * (1.0 + .8 * it))) }
        val coupleS = DoubleArray(FRAME_MODES) { sin((13.0 + 12.0 * (1.0 - skin)) / (UP_RATE * (1.0 + .8 * it))) }

        init {
            // Placement and roughness remain fixed through macro sweeps.
            val random = Random(Dsp.seedFor("CISTERN", MODEL_VERSION, voice.name, "FIELD"))
            val count = when (voice) { CisternVoice.FIRST -> 28; CisternVoice.RIPPLE -> 44; else -> 36 }
            val wet = when (voice) { CisternVoice.POOL -> .10; CisternVoice.RECOVERY -> .045; else -> 0.0 }
            for (j in loads.indices) {
                loads[j] = if (noDrops) 0.0 else wet * (1.0 - .08 * j)
                initialLoads[j] = loads[j]
                smoothedLoad[j] = loads[j]
            }
            slots = if (noDrops) emptyList() else List(count) { i ->
                val region = i % REGIONS
                val position = ((region + .22 + random.nextDouble() * .56) / REGIONS).coerceAtMost(.98)
                val small = .017 + .090 * drop.pow(1.25)
                val mass = small * (.70 + random.nextDouble() * .60)
                val h = .20 + random.nextDouble() * .75
                val thresholdBias = if (i == 0) .18 else .55 + random.nextDouble() * 2.65
                Slot(region, position, mass, h, thresholdBias,
                    region * period / REGIONS + .055 + (i / REGIONS) * .012, mass)
            }
            // The powered version includes a finite priming tank, explicitly
            // counted in inventory. It supplies the liquid still in transit
            // while the next group refills; it is never created at the wrap.
            drained = if (held) slots.sumOf { it.mass } * .35 else 0.0
            initialInventory = slots.sumOf { it.mass } + loads.sum() + drained
            pumpCapacity = max(.08, initialInventory * 1.2)
            coefficients()
        }

        fun event(kind: EventKind, time: Double, slot: Int, region: Int, cause: Int, mass: Double = 0.0, travel: Double = 0.0, impulse: Double = 0.0): Event {
            val e = Event(events.size, kind, time, slot, region, cause, mass, travel, impulse)
            events.add(e)
            return e
        }

        fun start() {
            if (started) return
            started = true
            if (replay != null) return
            if (initialStrike && velocity > 0f) {
                val amplitude = (.70 + .70 * strike) * velocity
                val e = event(EventKind.STRIKE, 0.0, -1, 0, -1, impulse = amplitude)
                impact(0.0, 0, .07 + .20 * (1.0 - strike), amplitude, false, e.id)
            }
        }

        fun run(seconds: Double): Run = block((seconds * UP_RATE).roundToInt())

        fun block(frames: Int): Run {
            start()
            val audio = FloatArray(frames)
            val frame = FloatArray(frames)
            for (i in 0 until frames) {
                val t = sample.toDouble() / UP_RATE
                // Arrival times retain fractional samples. The continuous Hann
                // force is evaluated relative to that fractional start below.
                if (replay == null) {
                    while (true) {
                        var due = -1
                        var earliest = Double.POSITIVE_INFINITY
                        for (j in slots.indices) if (slots[j].state == 1 && slots[j].arrival <= t && slots[j].arrival < earliest) {
                            due = j; earliest = slots[j].arrival
                        }
                        if (due < 0) break
                        land(due, earliest)
                    }
                } else {
                    while (replayIndex < replay.size && replay[replayIndex].time <= t + 1e-12) {
                        val e = replay[replayIndex++]
                        events.add(e)
                        when (e.kind) {
                            EventKind.STRIKE -> if (initialStrike) impact(e.time, e.region, .07 + .20 * (1.0 - strike), e.impulse, false, e.id)
                            EventKind.LANDING -> {
                                val slot = slots.getOrNull(e.slot)
                                if (slot != null) {
                                    slot.state = 2; slot.available = 0.0; slot.landingId = e.id
                                    loads[e.region] += e.mass
                                    if (loads[e.region] > REGION_CAPACITY) { drained += loads[e.region] - REGION_CAPACITY; loads[e.region] = REGION_CAPACITY }
                                    if (dropSound) impact(e.time, e.region, slot.position, e.impulse, true, e.id)
                                }
                            }
                            EventKind.RELEASE -> slots.getOrNull(e.slot)?.let { it.state = 1; it.available = 0.0; it.arrival = e.time + e.travel }
                            EventKind.MAINTENANCE -> if (maintenance) impact(e.time, e.region, (e.region + .4) / REGIONS, e.impulse, false, e.id)
                            EventKind.PUMP_RETURN -> Unit
                        }
                    }
                }
                if (sample % CONTROL_SAMPLES == 0L) control(t)
                val beforeInput = energy()
                var splash = 0.0
                var pulseIndex = pulses.size - 1
                while (pulseIndex >= 0) {
                    val pulse = pulses[pulseIndex]
                    val x = (t - pulse.at) * UP_RATE
                    if (x >= pulse.length) { pulses.removeAt(pulseIndex); pulseIndex--; continue }
                    if (x >= 0.0) {
                        val w = (1.0 - cos(2.0 * PI * x / pulse.length)) / pulse.length
                        val force = pulse.amplitude * w
                        for (k in 0 until MODES) p[k] += force * pulse.projection[k]
                        pulse.splash += .12 * (pulse.noise.next() - pulse.splash)
                        splash += pulse.splash * w * pulse.amplitude * .20
                    }
                    pulseIndex--
                }
                val afterInput = energy()
                // Signed work of the actual external force, separate from
                // gravitational/material inventory bookkeeping.
                inputEnergy += afterInput - beforeInput
                for (k in 0 until MODES) {
                    val a = q[k]
                    val b = p[k]
                    q[k] = r[k] * (c[k] * a + s[k] * b)
                    p[k] = r[k] * (c[k] * b - s[k] * a)
                }
                for (k in 0 until FRAME_MODES) {
                    val a = fq[k]
                    val b = fp[k]
                    fq[k] = fr[k] * (fc[k] * a + fs[k] * b)
                    fp[k] = fr[k] * (fc[k] * b - fs[k] * a)
                }
                for (k in 0 until FRAME_MODES) {
                    val a = p[k]
                    val b = fp[k]
                    p[k] = coupleC[k] * a - coupleS[k] * b
                    fp[k] = coupleS[k] * a + coupleC[k] * b
                }
                val after = energy()
                dissipated += max(0.0, afterInput - after)
                maxEnergy = max(maxEnergy, after)
                var y = q[0] * 1.12 / sqrt(modeMass[0])
                for (k in 1 until MODES) y += q[k] * (.34 / (1.0 + .26 * k)) / sqrt(modeMass[k])
                var body = 0.0
                for (k in 0 until FRAME_MODES) body += fq[k] * (.13 / (1.0 + k))
                frame[i] = (body * OUTPUT_GAIN).toFloat()
                y += body + splash
                val blocked = y - dcX + .99960 * dcY
                dcX = y; dcY = blocked
                audio[i] = (blocked * OUTPUT_GAIN).toFloat()
                sample++
            }
            val time = sample.toDouble() / UP_RATE
            trace(time, 0.0)
            val diagnostic = Diagnostics(events.toList(), traces.toList(), slots.size, initialInventory,
                maxInventoryError, maxEnergy, energy(), maxLoad, nonFinite,
                slots.count { it.state == 1 }, inputEnergy, dissipated)
            return Run(audio, frame, diagnostic, stateSnapshot(time), materialSnapshot(time),
                frames * 8L + traces.size * 128L + events.size * 96L)
        }

        private fun energy(): Double {
            var e = 0.0
            for (k in 0 until MODES) e += .5 * (q[k] * q[k] + p[k] * p[k])
            for (k in 0 until FRAME_MODES) e += .5 * (fq[k] * fq[k] + fp[k] * fp[k])
            return e
        }

        private fun coefficients() {
            for (k in 0 until MODES) {
                var wet = 0.0
                var norm = 0.0
                for (j in 0 until REGIONS) { val w = shape[j][k] * shape[j][k]; wet += smoothedLoad[j] * w; norm += w }
                wet /= max(.1, norm)
                val nextMass = 1.0 + wet * (2.0 + 2.8 * (1.0 - skin))
                // Accreting liquid is inelastic: preserve no more than the old
                // energy. Draining keeps energy coordinates unchanged.
                if (nextMass > modeMass[k]) {
                    val gain = sqrt(modeMass[k] / nextMass)
                    val before = .5 * (q[k] * q[k] + p[k] * p[k])
                    q[k] *= gain; p[k] *= gain
                    dissipated += before * (1.0 - gain * gain)
                }
                modeMass[k] = nextMass
                val sagCents = -min(26.0, wet * (12.0 + 7.0 * (1.0 - skin)))
                val ratio = if (k == 0) 1.0 else RATIOS[k] * (1.0 + (skin - .5) * .032 * k / MODES) * voiceRatio(k)
                val loadingPitch = if (k == 0) 2.0.pow(sagCents / 1200.0) else 1.0 / sqrt(1.0 + wet * .10 * k)
                val frequency = min(15_000.0, hz * ratio * loadingPitch)
                val angle = 2.0 * PI * frequency / UP_RATE
                c[k] = cos(angle); s[k] = sin(angle)
                val dryLoss = if (k == 0) .95 + .32 * (1.0 - skin) else 1.60 + k * (.33 + .64 * (1.0 - skin))
                val wetLoss = wet * (1.6 + .80 * k * (1.3 - .5 * skin))
                r[k] = exp(-(dryLoss + wetLoss) / UP_RATE)
            }
            val ratios = doubleArrayOf(.53, .91, 1.37)
            for (k in 0 until FRAME_MODES) {
                val w = 2.0 * PI * hz * ratios[k] / UP_RATE
                fc[k] = cos(w); fs[k] = sin(w)
                fr[k] = exp(-(2.7 + k * 1.7 + if (voice == CisternVoice.POOL) .8 else 0.0) / UP_RATE)
            }
            for (k in q.indices) if (!q[k].isFinite() || !p[k].isFinite()) { q[k] = 0.0; p[k] = 0.0; nonFinite++ }
            for (k in fq.indices) if (!fq[k].isFinite() || !fp[k].isFinite()) { fq[k] = 0.0; fp[k] = 0.0; nonFinite++ }
        }

        private fun voiceRatio(k: Int): Double = when (voice) {
            CisternVoice.FIRST -> 1.0 + .004 * sin(k * 1.7)
            CisternVoice.DRIP -> 1.0 - .008 * k / MODES
            CisternVoice.CASCADE -> 1.0 + .006 * sin(k * .8)
            CisternVoice.POOL -> 1.0 - .025 * k / MODES
            CisternVoice.RIPPLE -> 1.0 + .017 * k / MODES
            CisternVoice.RECOVERY -> 1.0 - .006 * sin(k.toDouble())
        }

        private fun impact(t: Double, region: Int, position: Double, amplitude: Double, liquid: Boolean, id: Int) {
            val wet = if (frozenLoad) initialLoads[region] else loads[region]
            val softness = if (liquid) .58 + .70 * drop + .5 * wet + .28 * (1.0 - skin) else 1.0 - .64 * strike
            val duration = if (liquid) .00065 + .00145 * softness else .00045 + .0018 * softness
            val length = max(24, (duration * UP_RATE).roundToInt())
            val projection = DoubleArray(MODES) { k ->
                val footprint = exp(-k * (.11 + softness * .22))
                val level = if (k == 0) 1.0 else .55 * footprint
                level * spatial(k, position) * if (k == 0) 1.0 else (1.0 + .24 * skin)
            }
            val compliance = 1.0 / (1.0 + wet * (1.2 + .8 * drop))
            val textureId = if (held) Math.floorMod((t * UP_RATE).roundToLong(), cycleSamples.toLong()).toInt() else id
            pulses.add(Pulse(t, length, amplitude * compliance, projection,
                Dsp.Noise(Dsp.seedFor("CISTERN", MODEL_VERSION, voice.name, "CONTACT", liquid, region, textureId)), textureId))
            stimulate(t, region, amplitude * compliance, id, liquid)
        }

        private fun stimulate(t: Double, region: Int, strength: Double, id: Int, liquid: Boolean) {
            val local = strength * (if (liquid) 2.4 + 1.2 * drop else 1.9 + 1.7 * strike)
            field[region] = min(8.0, field[region] + local)
            cause[region] = id
            // The upward contact chiefly disturbs the inner field. Falling
            // impacts carry their own stored energy and reach farther, so the
            // outer response has a real secondary cause instead of every slot
            // already being spent by the first strike's delayed front.
            val reach = if (liquid) .18 + .72 * suspension else .12 + .32 * suspension
            for (j in 0 until REGIONS) {
                val distance = abs(j - region)
                if (distance == 0) continue
                val attenuation = reach.pow(distance) * (if (liquid) 1.12 else .84)
                if (attenuation > .008) fronts.add(Front(t + distance * (.028 + .020 * (1.0 - suspension)), j, local * attenuation, id))
            }
        }

        private fun control(t: Double) {
            var flow = 0.0
            // Symmetric diffusion is conservative and cannot empty a region in
            // one control update. Then each outlet removes available liquid.
            val exchange = DoubleArray(REGIONS)
            for (j in 0 until REGIONS - 1) {
                val transfer = (loads[j] - loads[j + 1]) * (.14 + .45 * drain) * CONTROL_DT
                exchange[j] -= transfer; exchange[j + 1] += transfer
            }
            for (j in loads.indices) {
                loads[j] = max(0.0, loads[j] + exchange[j])
                val rate = if (held) 2.8 + 5.2 * drain else .16 + 4.8 * drain.pow(1.5)
                val outlet = rate * (.85 + .06 * j)
                val out = loads[j] * (1.0 - exp(-outlet * CONTROL_DT))
                loads[j] -= out; drained += out; flow += out / CONTROL_DT
                maxLoad = max(maxLoad, loads[j])
                val target = if (frozenLoad) initialLoads[j] else loads[j]
                smoothedLoad[j] += (1.0 - exp(-CONTROL_DT / .012)) * (target - smoothedLoad[j])
            }
            if (held && replay == null && !noDrops) pump(t)
            var f = fronts.size - 1
            while (f >= 0) {
                val front = fronts[f]
                if (front.at <= t) {
                    field[front.region] = min(8.0, field[front.region] + front.strength)
                    cause[front.region] = front.cause; fronts.removeAt(f)
                }
                f--
            }
            for (j in 0 until REGIONS) {
                var local = 0.0
                for (k in 0 until MODES) {
                    val w = shape[j][k]
                    local += (q[k] * q[k] + p[k] * p[k]) * w * w / (1.0 + .3 * k)
                }
                val level = sqrt(local)
                envelope[j] += (1.0 - exp(-CONTROL_DT / .013)) * (level - envelope[j])
                field[j] *= exp(-CONTROL_DT / (.095 + .09 * suspension))
            }
            if (held && maintenance && velocity > 0f && replay == null) {
                val beat = (sample * REGIONS / cycleSamples).toInt()
                if (beat != maintenanceBeat) {
                    maintenanceBeat = beat
                    val region = beat % REGIONS
                    val amplitude = (.27 + .23 * strike) * (.80 + .40 * suspension) * velocity
                    val e = event(EventKind.MAINTENANCE, t, -1, region, -1, impulse = amplitude)
                    impact(t, region, (region + .4) / REGIONS, amplitude, false, e.id)
                }
            }
            if (replay == null) release(t)
            coefficients()
            if (t >= nextTrace) { trace(t, flow); nextTrace = t + .01 }
        }

        private fun release(t: Double) {
            if (velocity <= 0f) return
            for (j in slots.indices) {
                val slot = slots[j]
                if (slot.state != 0) continue
                val region = slot.region
                val releaseThreshold = (.76 - .44 * suspension) * slot.thresholdBias * (1.0 + .24 * region) *
                    if (held) .003 * velocity * velocity else 1.0
                val motion = envelope[region] * field[region]
                slot.accumulator = max(0.0, slot.accumulator * exp(-CONTROL_DT / .085) + motion * CONTROL_DT * (15.0 + 20.0 * suspension))
                var opportunity = true
                if (held) {
                    val cycle = floor(t / period).toInt()
                    val phase = t - cycle * period
                    opportunity = cycle > slot.lastOpportunity && phase >= slot.phase && phase < slot.phase + CONTROL_DT * 1.01
                }
                if (!opportunity || slot.accumulator < releaseThreshold || cause[region] < 0) continue
                if (t - refractory[region] < .008 || t - lastRelease < .0015) continue
                val voiceTravel = when (voice) { CisternVoice.DRIP -> .12; CisternVoice.FIRST -> .07; CisternVoice.RIPPLE -> -.035; else -> 0.0 }
                val travel = (MIN_TRAVEL + .20 * sqrt(slot.height) + .14 * drop * slot.height + voiceTravel).coerceIn(MIN_TRAVEL, MAX_TRAVEL)
                val e = event(EventKind.RELEASE, t, j, region, cause[region], slot.mass, travel)
                slot.releaseId = e.id; slot.releasedAt = t; slot.arrival = t + travel
                slot.state = 1; slot.available = 0.0; slot.accumulator = 0.0
                if (held) slot.lastOpportunity = floor(t / period).toInt()
                refractory[region] = t; lastRelease = t
            }
        }

        private fun land(j: Int, at: Double) {
            val slot = slots[j]
            val arrivalVelocity = .60 + .70 * sqrt(slot.height)
            val amplitude = (.26 + 2.2 * sqrt(slot.mass)) * arrivalVelocity * (.76 + .40 * skin) * velocity
            val e = event(EventKind.LANDING, at, j, slot.region, slot.releaseId,
                slot.mass, at - slot.releasedAt, amplitude)
            slot.state = 2; slot.landingId = e.id
            loads[slot.region] += slot.mass
            if (loads[slot.region] > REGION_CAPACITY) {
                drained += loads[slot.region] - REGION_CAPACITY; loads[slot.region] = REGION_CAPACITY
            }
            maxLoad = max(maxLoad, loads[slot.region])
            if (dropSound) impact(at, slot.region, slot.position, amplitude, true, e.id)
            else stimulate(at, slot.region, amplitude, e.id, true)
        }

        private fun pump(t: Double) {
            var i = returns.size - 1
            while (i >= 0) {
                val returned = returns[i]
                if (returned.at <= t) { ready += returned.mass; pumpMass -= returned.mass; returns.removeAt(i) }
                i--
            }
            // A pooled reservoir is allowed to replenish another slot, but
            // every unit first drained, spent time in the lift, and is debited.
            val lifted = min(drained, pumpCapacity * CONTROL_DT)
            if (lifted > 1e-14) {
                drained -= lifted; pumpMass += lifted; returns.add(Return(t + pumpDelay, lifted))
            }
            for (j in slots.indices) {
                val slot = slots[j]
                if (slot.state != 2 || ready <= 1e-14) continue
                val amount = min(ready, slot.mass - slot.available)
                ready -= amount; slot.available += amount
                if (slot.available >= slot.mass - 1e-12) {
                    slot.available = slot.mass; slot.state = 0; slot.accumulator = 0.0
                    event(EventKind.PUMP_RETURN, t, j, slot.region, slot.landingId, slot.mass)
                }
            }
        }

        private fun trace(t: Double, flow: Double) {
            val suspended = slots.sumOf { it.available }
            val airborne = slots.filter { it.state == 1 }.sumOf { it.mass }
            val pump = pumpMass + ready
            val total = suspended + airborne + loads.sum() + drained + pump
            maxInventoryError = max(maxInventoryError, abs(total - initialInventory))
            traces.add(Trace(t, FloatArray(REGIONS) { loads[it].toFloat() }, suspended, airborne,
                drained, pump, total, energy(), dissipated, inputEnergy, flow))
        }

        private fun stateSnapshot(t: Double): DoubleArray {
            val values = ArrayList<Double>()
            values.addAll(q.toList()); values.addAll(p.toList()); values.addAll(fq.toList()); values.addAll(fp.toList())
            values.addAll(smoothedLoad.toList()); values.addAll(envelope.toList()); values.addAll(field.toList())
            values.addAll(modeMass.toList())
            values.add(dcX); values.add(dcY)
            for (j in 0 until REGIONS) {
                values.add(min(.008, t - refractory[j]))
                appendCause(values, if (field[j] < 1e-10) -1 else cause[j], t)
            }
            values.add(min(.0015, t - lastRelease))
            values.add(pulses.size.toDouble())
            for (pulse in pulses.sortedBy { it.at }) {
                values.add(pulse.at - t); values.add(pulse.length.toDouble())
                values.add(pulse.amplitude); values.add(pulse.textureId.toDouble()); values.add(pulse.splash)
                values.addAll(pulse.projection.toList())
            }
            return values.toDoubleArray()
        }

        private fun appendCause(values: MutableList<Double>, id: Int, t: Double) {
            val e = events.getOrNull(id)
            values.add(e?.kind?.ordinal?.toDouble() ?: -1.0)
            values.add(e?.slot?.toDouble() ?: -1.0)
            values.add(e?.region?.toDouble() ?: -1.0)
            values.add(if (e == null) 0.0 else e.time - t)
        }

        private fun materialSnapshot(t: Double): DoubleArray {
            val values = ArrayList<Double>()
            values.addAll(loads.toList()); values.add(drained); values.add(pumpMass); values.add(ready)
            for (slot in slots) {
                values.add(slot.state.toDouble()); values.add(slot.available); values.add(slot.accumulator)
                values.add(if (slot.state == 1) slot.arrival - t else 0.0)
                // Past opportunities are equivalent once this cycle becomes
                // eligible; retaining their age falsely prevents convergence.
                values.add(if (slot.lastOpportunity < 0) -1.0 else max(-1.0, slot.lastOpportunity - sample / cycleSamples.toDouble()))
                if (slot.state == 1) appendCause(values, slot.releaseId, t)
            }
            values.add(returns.size.toDouble())
            for (returned in returns.sortedBy { it.at }) { values.add(returned.at - t); values.add(returned.mass) }
            values.add(fronts.size.toDouble())
            for (front in fronts.sortedWith(compareBy<Front> { it.at }.thenBy { it.region })) {
                values.add(front.at - t); values.add(front.region.toDouble()); values.add(front.strength)
                appendCause(values, front.cause, t)
            }
            return values.toDoubleArray()
        }
    }

    private fun spatial(mode: Int, position: Double): Double = if (mode == 0) .78 + .22 * cos(position * PI / 2.0) else
        cos(PI * position * (1 + mode / 2)) * if (mode % 2 == 0) .88 else 1.0

    private fun renderLoop(
        voice: CisternVoice, m: Map<String, Float>, midi: Int, velocity: Float,
        noDrops: Boolean, initialStrike: Boolean, dropSound: Boolean, maintenance: Boolean, started: Long,
    ): Rendered {
        val engine = Engine(voice, m, midi, velocity, noDrops, initialStrike, dropSound, maintenance, true, false, null)
        var previous: Run? = null
        var current: Run? = null
        var stateDiff = Double.POSITIVE_INFINITY
        var materialDiff = Double.POSITIVE_INFINITY
        var diff = Float.POSITIVE_INFINITY
        var eventDiff = Double.POSITIVE_INFINITY
        var relativeDiff = Double.POSITIVE_INFINITY
        var confirmations = 0
        var iterations = 0
        while (iterations < MAX_HOLD_CYCLES) {
            val run = engine.block(engine.cycleSamples)
            iterations++
            previous = current
            if (current != null) {
                diff = sampleDiff(current.audio, run.audio)
                relativeDiff = relativeSampleDiff(current.audio, run.audio)
                stateDiff = stateDiff(current.state, run.state)
                materialDiff = stateDiff(current.material, run.material)
                eventDiff = eventDifference(current.diagnostics, run.diagnostics, (iterations - 2) * engine.period, engine.period)
            }
            current = run
            if (iterations >= 4 && diff < 2e-5f && relativeDiff < 1e-4 && stateDiff < 2e-5 && materialDiff < 2e-5 && eventDiff < 2e-5) confirmations++ else confirmations = 0
            if (confirmations >= 2) break
        }
        val last = requireNotNull(current)
        val prev = previous ?: last
        // Two carried cycles make sinc decimation at the cut see its actual
        // surrounding samples. Preserve both independently for seam evidence.
        val next = engine.block(engine.cycleSamples)
        diff = sampleDiff(last.audio, next.audio)
        relativeDiff = relativeSampleDiff(last.audio, next.audio)
        stateDiff = stateDiff(last.state, next.state)
        materialDiff = stateDiff(last.material, next.material)
        eventDiff = eventDifference(last.diagnostics, next.diagnostics, (iterations - 1) * engine.period, engine.period)
        val guard = engine.block(4096)
        val joined = FloatArray(last.audio.size + next.audio.size + guard.audio.size)
        last.audio.copyInto(joined); next.audio.copyInto(joined, last.audio.size)
        guard.audio.copyInto(joined, last.audio.size + next.audio.size)
        val down = Dsp.decimate(joined, RATE)
        val frames = engine.cycleSamples / Dsp.OVERSAMPLE
        val oldPeriod = down.copyOfRange(0, frames)
        val period = down.copyOfRange(frames, frames * 2)
        val metric = FloatArray(256 + frames)
        oldPeriod.copyInto(metric, 0, frames - 256, frames)
        period.copyInto(metric, 256)
        val seam = if (period.all { it == 0f } && oldPeriod.all { it == 0f }) 0.0 else Keys.seamError(metric, 256)
        var neighborhoodEnergy = 0.0
        for (i in 0 until 256) neighborhoodEnergy += period[i] * period[i] + period[frames - 256 + i] * period[frames - 256 + i]
        val neighborhoodLevel = sqrt(neighborhoodEnergy / 512)
        val boundaryStepError = if (neighborhoodLevel < 1e-12) 0.0 else abs(period[0] - down[frames * 2]) / neighborhoodLevel
        val boundarySlopeError = if (neighborhoodLevel < 1e-12) 0.0 else
            abs((period[1] - period[0]) - (down[frames * 2 + 1] - down[frames * 2])) / neighborhoodLevel
        val converged = confirmations >= 2 && stateDiff < 2e-5 && materialDiff < 2e-5 && diff < 2e-5f && relativeDiff < 1e-4 && eventDiff < 2e-5
        require(converged && seam.isFinite() && seam < Keys.MAX_SEAM_ERROR && boundaryStepError < 1e-3 && boundarySlopeError < 1e-3) {
            "CISTERN HOLD did not converge: voice=$voice midi=$midi audio=$diff acoustic=$stateDiff material=$materialDiff events=$eventDiff seam=$seam cycles=$iterations"
        }
        val raw = FloatArray(frames * 2)
        period.copyInto(raw); period.copyInto(raw, frames)
        val normalized = raw.copyOf()
        Dsp.levelTo(normalized, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        val frame = Dsp.decimate(next.frame, RATE)
        val from = (iterations * engine.period)
        val eventsPerPeriod = next.diagnostics.events.count { it.time >= from && it.kind == EventKind.LANDING }
        val loop = LoopReport(period, oldPeriod, seam, diff, stateDiff, materialDiff, iterations,
            converged, engine.pumpCapacity, engine.pumpDelay, eventsPerPeriod, 0, eventDiff, relativeDiff, boundaryStepError, boundarySlopeError)
        return Rendered(Snip(normalized, channels = 1, sampleRate = RATE), raw, next.diagnostics,
            loop, next.bytes + prev.audio.size * 8L + raw.size * 16L, System.nanoTime() - started, frame)
    }

    private fun sampleDiff(a: FloatArray, b: FloatArray): Float {
        var difference = 0f
        for (i in 0 until min(a.size, b.size)) difference = max(difference, abs(a[i] - b[i]))
        return difference
    }
    private fun stateDiff(a: DoubleArray, b: DoubleArray): Double {
        var difference = abs(a.size - b.size).toDouble()
        for (i in 0 until min(a.size, b.size)) difference = max(difference, abs(a[i] - b[i]))
        return difference
    }

    private fun relativeSampleDiff(a: FloatArray, b: FloatArray): Double {
        var difference = 0.0
        var reference = 0.0
        for (i in 0 until min(a.size, b.size)) {
            val delta = a[i] - b[i].toDouble()
            difference += delta * delta
            reference += b[i] * b[i].toDouble()
        }
        return if (reference < 1e-24) if (difference < 1e-24) 0.0 else Double.POSITIVE_INFINITY else sqrt(difference / reference)
    }

    private fun eventDifference(a: Diagnostics, b: Diagnostics, from: Double, period: Double): Double {
        fun signature(d: Diagnostics, start: Double): DoubleArray {
            val out = ArrayList<Double>()
            for (e in d.events) if (e.time >= start - 1e-9 && e.time < start + period - 1e-9) {
                val parent = d.events.getOrNull(e.cause)
                out.add(e.kind.ordinal.toDouble()); out.add(e.slot.toDouble()); out.add(e.region.toDouble())
                out.add(e.time - start); out.add(e.mass); out.add(e.travel); out.add(e.impulse)
                out.add(parent?.kind?.ordinal?.toDouble() ?: -1.0); out.add(parent?.slot?.toDouble() ?: -1.0)
                out.add(parent?.region?.toDouble() ?: -1.0); out.add(if (parent == null) 0.0 else parent.time - e.time)
            }
            return out.toDoubleArray()
        }
        return stateDiff(signature(a, from), signature(b, from + period))
    }
}
