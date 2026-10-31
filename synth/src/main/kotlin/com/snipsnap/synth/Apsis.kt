package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

enum class ApsisVoice { PASSAGE }

/** Saved models replay their original sound; new patches use the current musical model. */
data class ApsisConfig(val modelVersion: Int = 3, val seedContext: Int = 0)

/**
 * A powered root, one central body, and six stable orbital identities.
 * Motion changes injection, modal loss and state exchange before radiation.
 *
 * Each modal state is (omega*q, velocity), with E=.5*sum(x*x+v*v). A free step
 * is an exact rotation times a radius <1. Every center/body junction is a proper
 * rotation of two normalized velocity ports. Reconstructing each junction from its
 * Cayley parameter on EVERY sample preserves orthogonality even while controls
 * move. Thus the complete unforced update contracts the same norm for every
 * allowed trajectory. External drives and finite raised-cosine pulses are
 * separate bounded inputs. Listener gains do not enter the network.
 *
 * All voicing numbers are musical prototype choices, awaiting listening review.
 */
object Apsis {
    const val MODEL_VERSION = 3
    const val LEGACY_MODEL_VERSION = 1
    fun supportsModel(model: Int) = model in LEGACY_MODEL_VERSION..MODEL_VERSION
    const val MIDI_MIN = 36
    const val MIDI_MAX = 84
    const val DEFAULT_MIDI = 60
    const val HOLD_LOOP = .99f
    private const val BODIES = 6
    private const val MODES = 4
    private const val CENTER = 3
    private const val COUNT = CENTER + BODIES * MODES
    private const val TAU = 2.0 * PI
    private const val TIME_SCALE = .95
    // First-listen calibration: rate7 changed the low/middle full mix only .00103.
    // This controls the orthogonal junction angle, never a positive feedback send.
    private const val EXCHANGE_RATE = 120.0
    private const val BRIGHT_ANGLE = -.35
    private const val MAX_PREPARED_ENTRY_BYTES = 12L * 1024 * 1024
    private val PERIOD_RATIOS = doubleArrayOf(1.0, 1.2, 1.5, 2.0, 3.0, 6.0)
    private val UPPER = doubleArrayOf(5.0, 4.0, 6.0, 7.0, 8.0, 10.0)
    // A distinct material answers each passage; these are imaginary modal ratios.
    private val MATERIAL_UPPER = doubleArrayOf(2.73, 3.91, 5.43, 7.17, 4.37, 6.81)

    fun defaults(@Suppress("UNUSED_PARAMETER") voice: ApsisVoice) = linkedMapOf(
        "STRETCH" to .65f, "DISTANCE" to .40f, "BODIES" to .40f,
        "ALIGN" to .55f, "COUPLE" to .45f, "HOLD" to 0f)
    fun macrosFor(voice: ApsisVoice) = defaults(voice).map { (name, value) ->
        MacroSpec(name, value, when (name) { "STRETCH" -> .45f; "ALIGN" -> .45f; "HOLD" -> 0f; else -> .40f })
    }
    fun settled(voice: ApsisVoice, macros: Map<String, Float>) = defaults(voice).mapValues { (k, v) ->
        (macros[k]?.takeIf { it.isFinite() } ?: v).coerceIn(0f, 1f)
    }
    fun isLoop(hold: Float) = hold.isFinite() && hold >= HOLD_LOOP
    fun rootMidi(@Suppress("UNUSED_PARAMETER") voice: ApsisVoice) = DEFAULT_MIDI
    fun frequencyFor(midi: Int) = Keys.midiHz(midi)
    fun drumClassFor(voice: ApsisVoice, macros: Map<String, Float> = emptyMap()) =
        if (isLoop(settled(voice, macros).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL
    fun scramble(voice: ApsisVoice, random: Random, temperature: Float = .35f, near: Patch? = null) =
        Dsp.scrambleNear(defaults(voice) + near?.macros.orEmpty().filterKeys { it in defaults(voice) },
            temperature, random).toMutableMap().also { it["HOLD"] = 0f }

    internal enum class Ablation { NONE, UNIFORM_ANGULAR, POST_OUTPUT, NO_COUPLING }
    internal data class ProbeOptions(
        val normalize: Boolean = false,
        val seconds: Float? = null,
        val recordStems: Boolean = true,
        val recordTraces: Boolean = true,
        val controlStride: Int = 64,
        val ablation: Ablation = Ablation.NONE,
        val directExcitationDisabled: Int? = null,
        val soloBody: Int? = null,
        val oversample: Int = Dsp.OVERSAMPLE,
        val recordDiagnostics: Boolean = true,
        val usePreparationCache: Boolean = true,
    )
    internal data class OrbitFrame(val timeSeconds: Double, val body: Int, val x: Double,
        val y: Double, val r: Double, val speed: Double, val excitation: Double, val sympathy: Double)
    internal data class ExcitationEvent(val body: Int, val timeSeconds: Double,
        val durationSeconds: Double, val impulse: Double)
    internal data class Diagnostics(
        val rawPeak: Double, val rawRms: Double, val rawDc: Double,
        val maxStateNorm: Double, val maxUnforcedEnergyRise: Double, val finalEnergy: Double,
        val sourceEndSeconds: Double, val transientCounts: IntArray,
        val preparationNanos: Long, val synthesisNanos: Long, val outputNanos: Long,
        val converged: Boolean, val prerollCycles: Int,
        val solverResidual: Double, val commonPeriodLawResidual: Double,
        val minDistance: Double, val peakSpeed: Double,
        val axes: DoubleArray, val periods: DoubleArray,
        val sourcePhaseClosureError: Double = 0.0,
        val controlPhaseClosureError: Double = 0.0,
        val eventStateClosureError: Double = 0.0,
        val preparationCacheHit: Boolean = false,
        val preparationCacheRetainedBytes: Long = 0,
        val preparationEntryBytes: Long = 0,
        val diagnosticsRecorded: Boolean = true,
    )
    internal data class Probe(
        val samples: FloatArray, val rawMix: FloatArray, val source: FloatArray,
        val center: FloatArray, val bodies: Array<FloatArray>, val traces: List<OrbitFrame>,
        val diagnostics: Diagnostics, val previousCycle: FloatArray,
        val seamError: Double, val stateError: Double, val loopFrames: Int,
        val effectiveHz: Double, val events: List<ExcitationEvent>,
    )

    fun render(voice: ApsisVoice, macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, config: ApsisConfig = ApsisConfig()): Snip =
        Snip(probe(voice, macros, midi, velocity, config,
            ProbeOptions(normalize = true, recordStems = false, recordTraces = false, recordDiagnostics = false)).samples,
            channels = 1, sampleRate = Dsp.RATE)

    private fun stop(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("APSIS render cancelled")
    }
    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
    private fun angular(angle: Double, center: Double, width: Double): Double =
        exp((cos(angle - center) - 1.0) / (width * width))

    private class Controls {
        val drive = DoubleArray(BODIES)
        val bright = DoubleArray(BODIES)
        val sympathy = DoubleArray(BODIES)
        val near = DoubleArray(BODIES)
        fun copyFrom(other: Controls) {
            other.drive.copyInto(drive); other.bright.copyInto(bright)
            other.sympathy.copyInto(sympathy); other.near.copyInto(near)
        }
    }
    private data class MotionKey(val model: Int, val voice: ApsisVoice, val eccentricityBits: Long, val alignBits: Long,
        val innerPeriodBits: Long, val held: Boolean, val rate: Int, val stride: Int,
        val frames: Int, val eventEndBits: Long, val uniformAngular: Boolean)
    private data class ContactGeometry(val body: Int, val time: Double, val width: Double, val strength: Double)
    /** Seven doubles per body: drive, brightness, sympathy, near, radius, speed, residual. */
    private class PreparedMotion(val frames: Int, val stride: Int, private val values: DoubleArray,
        val contacts: List<ContactGeometry>) {
        val nodeCount get() = values.size / (BODIES * 7)
        fun index(frame: Int): Int? = when {
            values.isEmpty() -> null
            frame == frames -> nodeCount - 1
            frame in 0..frames && frame % stride == 0 -> frame / stride
            else -> null // A shortened diagnostic capture can end between canonical nodes.
        }
        fun value(node: Int, body: Int, channel: Int) = values[(node * BODIES + body) * 7 + channel]
    }
    private val preparationCache = ApsisPreparationCache<MotionKey, PreparedMotion>(maxEntryBytes = MAX_PREPARED_ENTRY_BYTES)
    internal fun clearPreparationCache() = preparationCache.clear()
    internal fun preparationCacheSnapshot() = preparationCache.snapshot()
    private class Bank(val rate: Int, val hz: Double, val upperRatios: DoubleArray) {
        val x = DoubleArray(COUNT)
        val v = DoubleArray(COUNT)
        val co = DoubleArray(COUNT)
        val si = DoubleArray(COUNT)
        val radiation = DoubleArray(COUNT)
        init {
            for (i in 0 until COUNT) {
                val ratio = if (i < CENTER) i + 1.0 else when ((i - CENTER) % MODES) {
                    0 -> 1.0; 1 -> 2.0; 2 -> 3.0; else -> upperRatios[(i - CENTER) / MODES]
                }
                val frequency = hz * ratio
                co[i] = cos(TAU * frequency / rate); si[i] = sin(TAU * frequency / rate)
                radiation[i] = smooth((19_000 - frequency) / 3_000)
            }
        }
        fun energy(): Double { var e = 0.0; for (i in x.indices) e += .5 * (x[i] * x[i] + v[i] * v[i]); return e }
        fun snapshot() = x + v
        fun rotate(i: Int, radius: Double) {
            val oldX = x[i]; val oldV = v[i]
            x[i] = radius * (co[i] * oldX + si[i] * oldV)
            v[i] = radius * (co[i] * oldV - si[i] * oldX)
        }
        fun exchange(a: Int, b: Int, angle: Double) {
            val t = .5 * angle
            val denominator = 1 + t * t
            val c = (1 - t * t) / denominator
            val s = 2 * t / denominator
            val oldA = v[a]; val oldB = v[b]
            v[a] = c * oldA + s * oldB
            v[b] = c * oldB - s * oldA
        }
        /** Exchange two unit-normalized velocity ports, leaving their orthogonal states unchanged. */
        fun exchangeMaterialPort(body: Int, angle: Double) {
            val index = CENTER + body * MODES
            val b0 = .65
            val b1 = .50
            val b2 = sqrt(1 - b0 * b0 - b1 * b1)
            val center = .8 * v[1] + .6 * v[2]
            val material = b0 * v[index + 1] + b1 * v[index + 2] + b2 * v[index + 3]
            val t = .5 * angle
            val denominator = 1 + t * t
            val c = (1 - t * t) / denominator
            val s = 2 * t / denominator
            val centerDelta = (c - 1) * center + s * material
            val bodyDelta = (c - 1) * material - s * center
            v[1] += .8 * centerDelta; v[2] += .6 * centerDelta
            v[index + 1] += b0 * bodyDelta
            v[index + 2] += b1 * bodyDelta
            v[index + 3] += b2 * bodyDelta
        }
    }

    internal fun probe(voice: ApsisVoice = ApsisVoice.PASSAGE,
        macros: Map<String, Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f, config: ApsisConfig = ApsisConfig(),
        options: ProbeOptions = ProbeOptions(), cancelled: () -> Boolean = { false }): Probe {
        val began = System.nanoTime()
        require(supportsModel(config.modelVersion)) { "Unsupported APSIS model ${config.modelVersion}" }
        val legacy = config.modelVersion == LEGACY_MODEL_VERSION
        val musical = config.modelVersion >= 3
        require(midi in MIDI_MIN..MIDI_MAX) { "APSIS MIDI must be $MIDI_MIN..$MIDI_MAX" }
        require(velocity.isFinite() && velocity in 0f..1f) { "APSIS velocity must be finite and in 0..1" }
        require(options.seconds == null || options.seconds.isFinite() && options.seconds in .1f..30f)
        require(options.oversample in listOf(4, 8, 16))
        require(options.controlStride > 0)
        require(options.directExcitationDisabled == null || options.directExcitationDisabled in 0 until BODIES)
        require(options.soloBody == null || options.soloBody in -1 until BODIES)
        stop(cancelled)
        val m = settled(voice, macros)
        val held = isLoop(m.getValue("HOLD"))
        val exportedLoop = held && options.seconds == null
        val rate = Dsp.RATE * options.oversample
        val dt = 1.0 / rate
        val requestedHz = frequencyFor(midi).toDouble()
        val requestedInner = .60 * (2.0 / .60).pow(m.getValue("DISTANCE").toDouble())
        val requestedLoop = 6 * requestedInner
        val rootCycles = (requestedLoop * requestedHz).roundToInt().coerceAtLeast(1)
        val loopFrames = (rootCycles * Dsp.RATE / requestedHz).roundToInt()
        val loopSeconds = loopFrames.toDouble() / Dsp.RATE
        val hz = if (held) rootCycles / loopSeconds else requestedHz
        val inner = if (held) loopSeconds / 6 else requestedInner
        val eccentricity = .85 * m.getValue("STRETCH")
        val align = m.getValue("ALIGN").toDouble()
        val participation = DoubleArray(BODIES) { (1.0 + 5 * m.getValue("BODIES") - it).coerceIn(0.0, 1.0) }
        val weight = participation.sum().coerceAtLeast(1.0)
        val allocation = sqrt(weight)
        val elements = Array(BODIES) { body ->
            val period = inner * PERIOD_RATIOS[body]
            val a = (period / TIME_SCALE).pow(2.0 / 3.0)
            val passage = .28 + (1 - align) * (.18 + .35 * body)
            val mean0 = -TAU * passage / period
            val orientation = (1 - align) * (.25 + body * .58) + align * (.06 * body)
            ApsisOrbit.Elements(a, eccentricity, period, mean0, orientation)
        }
        val axes = DoubleArray(BODIES) { elements[it].a }
        val periods = DoubleArray(BODIES) { elements[it].period }
        val finiteDuration = 6.0 + 1.8 * m.getValue("HOLD")
        val duration = options.seconds?.toDouble() ?: if (held) loopSeconds else finiteDuration
        val tail = min(.8, duration * .25)
        val sourceEnd = if (held) Double.POSITIVE_INFINITY else if (options.seconds != null)
            max(duration - min(2.2, duration * .25), duration * .5)
            else min(duration - tail, 3.6 + 1.8 * m.getValue("HOLD"))
        val eventEnd = if (held) loopSeconds else max(0.0, sourceEnd - if (legacy) .08 else .10)
        val stride = max(1, options.controlStride * options.oversample / Dsp.OVERSAMPLE)
        val motionFrames = ((if (held) loopSeconds else duration) * rate).roundToInt()
        val nodeCount = (motionFrames + stride - 1) / stride + 1
        val contactBytes = 512 + elements.sumOf { (ceil(eventEnd / it.period).toLong() + 2) * 48 }
        val controlBytes = nodeCount.toLong() * BODIES * 7 * 8
        val prepareControls = controlBytes + contactBytes <= MAX_PREPARED_ENTRY_BYTES
        val preparationBytes = contactBytes + if (prepareControls) controlBytes else 0
        var cacheHit = false
        val prepared = if (musical) {
            val key = MotionKey(config.modelVersion, voice, eccentricity.toBits(), align.toBits(), inner.toBits(),
                held, rate, stride, motionFrames, eventEnd.toBits(), options.ablation == Ablation.UNIFORM_ANGULAR)
            val build = {
                // Fine/long diagnostic grids use direct controls instead of allocating a huge
                // table only to reject it later. Their small contact schedule still reuses safely.
                val values = DoubleArray(if (prepareControls) nodeCount * BODIES * 7 else 0)
                for (node in 0 until (if (prepareControls) nodeCount else 0)) {
                    if (node % 256 == 0) stop(cancelled)
                    val frame = min(node * stride, motionFrames)
                    // The held endpoint is exactly frame zero, including solver rounding.
                    val time = (if (held && frame == motionFrames) 0 else frame).toDouble() / rate
                    for (body in 0 until BODIES) {
                        val orbit = elements[body]
                        val p = if (options.ablation == Ablation.UNIFORM_ANGULAR)
                            ApsisOrbit.sampleUniformAngular(orbit, time) else ApsisOrbit.sample(orbit, time)
                        val angle = atan2(p.y, p.x)
                        val encounter = 1.0 / (1.0 + (p.radius / orbit.a / .70).pow(4))
                        val absorption = .5 + .5 / (1 + (p.radius / 2.0).pow(2))
                        val base = (node * BODIES + body) * 7
                        values[base] = absorption * 2.0 * encounter.pow(1.2) * angular(angle, -.10, .68)
                        values[base + 1] = absorption * encounter.pow(1.1) * angular(angle, 0.0, .35)
                        values[base + 2] = angular(angle, 1.4, .85)
                        values[base + 3] = encounter
                        values[base + 4] = p.radius
                        values[base + 5] = p.speed
                        values[base + 6] = if (options.ablation == Ablation.UNIFORM_ANGULAR) 0.0 else
                            abs(p.eccentricAnomaly - orbit.e * sin(p.eccentricAnomaly) - p.meanAnomaly)
                    }
                }
                val contacts = ArrayList<ContactGeometry>()
                for (body in 0 until BODIES) {
                    stop(cancelled)
                    val orbit = elements[body]
                    val times = if (options.ablation == Ablation.UNIFORM_ANGULAR)
                        ApsisOrbit.crossingsUniformAngular(orbit, BRIGHT_ANGLE, -1e-10, eventEnd)
                        else ApsisOrbit.crossings(orbit, BRIGHT_ANGLE, -1e-10, eventEnd)
                    for (time in times) {
                        if (held && time >= loopSeconds - 1e-12) continue
                        val point = if (options.ablation == Ablation.UNIFORM_ANGULAR)
                            ApsisOrbit.sampleUniformAngular(orbit, time) else ApsisOrbit.sample(orbit, time)
                        val exits = if (options.ablation == Ablation.UNIFORM_ANGULAR)
                            ApsisOrbit.crossingsUniformAngular(orbit, BRIGHT_ANGLE + .70, time + 1e-10, time + orbit.period)
                            else ApsisOrbit.crossings(orbit, BRIGHT_ANGLE + .70, time + 1e-10, time + orbit.period)
                        val width = ((exits.firstOrNull() ?: (time + .04)) - time).coerceIn(.012, .090)
                        val encounter = 1.0 / (1.0 + (point.radius / orbit.a / .70).pow(4))
                        val absorption = .5 + .5 / (1 + (point.radius / 2.0).pow(2))
                        contacts += ContactGeometry(body, time, width, absorption * (.08 + .92 * encounter))
                    }
                }
                stop(cancelled)
                PreparedMotion(motionFrames, stride, values, contacts.toList())
            }
            if (options.usePreparationCache) preparationCache.getOrPrepare(key, preparationBytes, cancelled, build)
                .also { cacheHit = it.hit }.value
            else build()
        } else null
        val events = ArrayList<ExcitationEvent>()
        if (prepared != null) {
            for (contact in prepared.contacts) if (participation[contact.body] > 0 &&
                contact.body != options.directExcitationDisabled && options.ablation != Ablation.POST_OUTPUT) {
                val impulse = .45 * velocity.toDouble() * participation[contact.body] / allocation * contact.strength
                events += ExcitationEvent(contact.body, contact.time, contact.width, impulse)
            }
        } else for (body in 0 until BODIES) if (participation[body] > 0 && body != options.directExcitationDisabled &&
            options.ablation != Ablation.POST_OUTPUT) {
            val orbit = elements[body]
            val times = if (options.ablation == Ablation.UNIFORM_ANGULAR)
                ApsisOrbit.crossingsUniformAngular(orbit, BRIGHT_ANGLE, -1e-10, eventEnd)
                else ApsisOrbit.crossings(orbit, BRIGHT_ANGLE, -1e-10, eventEnd)
            for (time in times) {
                if (held && time >= loopSeconds - 1e-12) continue
                val point = if (options.ablation == Ablation.UNIFORM_ANGULAR)
                    ApsisOrbit.sampleUniformAngular(orbit, time) else ApsisOrbit.sample(orbit, time)
                val near = 1.0 / (1.0 + (point.radius / 1.05).pow(2))
                val width: Double
                val impulse: Double
                if (legacy) {
                    width = (.020 + .035 * (1 - near)).coerceIn(.012, .08)
                    impulse = .11 * velocity.pow(.8f) * participation[body] / allocation * (.25 + 1.8 * near)
                } else {
                    // Equal angular regions have unequal encounter durations under Kepler timing.
                    // The finite bandwidth floor rounds even the fastest close contact.
                    val exits = if (options.ablation == Ablation.UNIFORM_ANGULAR)
                        ApsisOrbit.crossingsUniformAngular(orbit, BRIGHT_ANGLE + .70, time + 1e-10, time + orbit.period)
                        else ApsisOrbit.crossings(orbit, BRIGHT_ANGLE + .70, time + 1e-10, time + orbit.period)
                    width = ((exits.firstOrNull() ?: (time + .04)) - time).coerceIn(.012, .090)
                    val encounter = 1.0 / (1.0 + (point.radius / orbit.a / .70).pow(4))
                    val absorption = .5 + .5 / (1 + (point.radius / 2.0).pow(2))
                    impulse = .45 * velocity.pow(1.15f) * participation[body] / allocation *
                        absorption * (.08 + .92 * encounter)
                }
                events += ExcitationEvent(body, time, width, impulse)
            }
        }
        events.sortWith(compareBy<ExcitationEvent> { it.timeSeconds }.thenBy { it.body })
        val trace = ArrayList<OrbitFrame>()
        var maxResidual = 0.0
        var minDistance = Double.POSITIVE_INFINITY
        var maxSpeed = 0.0
        fun controls(time: Double, record: Boolean): Controls {
            val result = Controls()
            val cachedNode = prepared?.index((time * rate).roundToInt())
            if (cachedNode != null) {
                for (body in 0 until BODIES) {
                    result.drive[body] = prepared.value(cachedNode, body, 0)
                    result.bright[body] = prepared.value(cachedNode, body, 1)
                    result.sympathy[body] = prepared.value(cachedNode, body, 2)
                    result.near[body] = prepared.value(cachedNode, body, 3)
                    if (participation[body] > 0) {
                        val radius = prepared.value(cachedNode, body, 4)
                        val speed = prepared.value(cachedNode, body, 5)
                        minDistance = min(minDistance, radius); maxSpeed = max(maxSpeed, speed)
                        maxResidual = max(maxResidual, prepared.value(cachedNode, body, 6))
                        if (record && options.recordTraces) {
                            val p = if (options.ablation == Ablation.UNIFORM_ANGULAR)
                                ApsisOrbit.sampleUniformAngular(elements[body], time) else ApsisOrbit.sample(elements[body], time)
                            trace += OrbitFrame(time, body, p.x, p.y, radius, speed, result.drive[body], result.sympathy[body])
                        }
                    }
                }
                return result
            }
            for (body in 0 until BODIES) {
                val orbit = elements[body]
                val p = if (options.ablation == Ablation.UNIFORM_ANGULAR)
                    ApsisOrbit.sampleUniformAngular(orbit, time) else ApsisOrbit.sample(orbit, time)
                val angle = atan2(p.y, p.x)
                val near = 1.0 / (1.0 + (p.radius / 1.05).pow(2))
                val sustain = .25 + .75 * angular(angle, -.45, 1.15)
                val bright = angular(angle, 0.0, .48) * near
                val sympathy = angular(angle, 1.4, .85)
                if (legacy) {
                    result.near[body] = near
                    result.drive[body] = (.035 + 1.65 * near * sustain)
                    result.bright[body] = bright
                } else {
                    // Encounter shape belongs to each ellipse. Absolute propagation loss is
                    // bounded separately so distant bodies retain their resonant identities.
                    val encounter = 1.0 / (1.0 + (p.radius / orbit.a / .70).pow(4))
                    val absorption = .5 + .5 / (1 + (p.radius / 2.0).pow(2))
                    result.near[body] = encounter
                    result.drive[body] = absorption * 2.0 * encounter.pow(1.2) * angular(angle, -.10, .68)
                    result.bright[body] = absorption * encounter.pow(1.1) * angular(angle, 0.0, .35)
                }
                result.sympathy[body] = sympathy
                if (participation[body] > 0) {
                    minDistance = min(minDistance, p.radius); maxSpeed = max(maxSpeed, p.speed)
                    if (options.ablation != Ablation.UNIFORM_ANGULAR)
                        maxResidual = max(maxResidual, abs(p.eccentricAnomaly - orbit.e * sin(p.eccentricAnomaly) - p.meanAnomaly))
                    if (record && options.recordTraces) trace += OrbitFrame(time, body, p.x, p.y, p.radius,
                        p.speed, result.drive[body], sympathy)
                }
            }
            return result
        }
        val preparationNanos = System.nanoTime() - began
        val synthBegan = System.nanoTime()
        val upperRatios = if (legacy) UPPER else MATERIAL_UPPER
        val bank = Bank(rate, hz, upperRatios)
        val harmonicSource = DoubleArray(11)
        val sourceWeights = DoubleArray(9) { if (it == 0) 0.0 else 1.0 / it.toDouble().pow(1.45) }
        val sourcePhase = Dsp.phases(1, Dsp.seedFor("APSIS", config.modelVersion, config.seedContext, midi))[0] * TAU
        val contactPhases = Dsp.phases(BODIES,
            Dsp.seedFor("APSIS_CONTACT", config.modelVersion, config.seedContext, midi))
        // Model one varies the common source phase; later models also fix each contact's seed phase.
        val rootRadius = if (legacy) exp(-ln(1000.0) / (2.2 * rate)) else exp(-ln(1000.0) / (.70 * rate))
        val centerRadius = if (legacy) exp(-ln(1000.0) / (1.15 * rate)) else exp(-ln(1000.0) / (.48 * rate))
        val previousRadii = DoubleArray(BODIES * MODES)
        val nextRadii = DoubleArray(BODIES * MODES)
        fun radii(c: Controls, into: DoubleArray) {
            for (body in 0 until BODIES) for (mode in 0 until MODES) {
                val near = if (options.ablation == Ablation.POST_OUTPUT) .45 else c.near[body]
                if (legacy) {
                    val decay = if (mode == 0) 2.5 else 1.5 / (1 + .12 * body + .15 * mode)
                    into[body * MODES + mode] = exp(-ln(1000.0) / decay * (1.35 - .65 * near) * dt)
                } else {
                    // The root outlives the bright modes: close contact leaves a darker tail,
                    // while the next encounter can still articulate instead of accumulating a pad.
                    val decay = when (mode) { 0 -> 1.10; 1 -> .50; 2 -> .42; else -> .85 } / (1 + .025 * body)
                    into[body * MODES + mode] = exp(-ln(1000.0) / decay * (1.40 - .40 * near) * dt)
                }
            }
        }
        val previousControls = Controls()
        val nextControls = Controls()
        val currentControls = Controls()
        val counts = IntArray(BODIES)
        // Touch hardness shapes actual excitation spectra. Playing energy still scales
        // every anchor linearly; soft notes do not acquire a hidden bright contact boost.
        val hardness = velocity.toDouble()
        val upperTouch2 = if (musical) .25 + hardness else 1.0
        val upperTouch3 = if (musical) .12 + 1.28 * hardness * hardness else 1.0
        val materialTouch = if (musical) .08 + 1.47 * hardness * hardness else 1.0
        var maxNorm = 0.0
        var maxUnforcedRise = 0.0
        val disabled = options.directExcitationDisabled
        val passOutput = options.ablation == Ablation.POST_OUTPUT
        val coupling = if (options.ablation == Ablation.NO_COUPLING) 0.0 else m.getValue("COUPLE").toDouble()
        var cycleStateError = 0.0
        var prerollCycles = 0
        var converged = !exportedLoop
        data class Block(val mix: FloatArray, val source: FloatArray, val center: FloatArray,
            val bodies: Array<FloatArray>)
        fun run(seconds: Double, record: Boolean, stems: Boolean = record): Block {
            val frames = (seconds * rate).roundToInt()
            val output = FloatArray(frames)
            val sourceStem = if (stems && options.recordStems) FloatArray(frames) else FloatArray(0)
            val centerStem = if (stems && options.recordStems) FloatArray(frames) else FloatArray(0)
            val bodyStem = Array(BODIES) { if (stems && options.recordStems) FloatArray(frames) else FloatArray(0) }
            var eventIndex = 0
            val activePulses = ArrayList<ExcitationEvent>()
            if (held) for (event in events) if (event.timeSeconds + event.durationSeconds > loopSeconds)
                activePulses += event.copy(timeSeconds = event.timeSeconds - loopSeconds)
            val cycleInternalFrames = loopFrames * options.oversample
            previousControls.copyFrom(controls(0.0, false))
            nextControls.copyFrom(previousControls)
            radii(previousControls, previousRadii)
            previousRadii.copyInto(nextRadii)
            var intervalStart = 0
            var intervalLength = 0
            var intervalEnd = 0
            var controlIndex = 0
            for (frame in 0 until frames) {
                // Integer sample clocks recur exactly; no accumulated wall-clock or phase error.
                val localFrame = if (held) frame % cycleInternalFrames else frame
                val time = localFrame.toDouble() / rate
                if (frame == intervalEnd) {
                    stop(cancelled)
                    intervalStart = frame
                    intervalLength = min(stride, frames - frame)
                    if (held) intervalLength = min(intervalLength, cycleInternalFrames - localFrame)
                    intervalEnd = frame + intervalLength
                    previousControls.copyFrom(nextControls)
                    nextRadii.copyInto(previousRadii)
                    val endFrame = if (held) (localFrame + intervalLength) % cycleInternalFrames else frame + intervalLength
                    nextControls.copyFrom(controls(endFrame.toDouble() / rate,
                        record && controlIndex++ % 14 == 0))
                    radii(nextControls, nextRadii)
                }
                val alpha = (frame - intervalStart).toDouble() / intervalLength
                for (body in 0 until BODIES) {
                    currentControls.drive[body] = previousControls.drive[body] + alpha * (nextControls.drive[body] - previousControls.drive[body])
                    currentControls.bright[body] = previousControls.bright[body] + alpha * (nextControls.bright[body] - previousControls.bright[body])
                    currentControls.near[body] = previousControls.near[body] + alpha * (nextControls.near[body] - previousControls.near[body])
                    currentControls.sympathy[body] = previousControls.sympathy[body] + alpha * (nextControls.sympathy[body] - previousControls.sympathy[body])
                }
                val localTime = time
                if (held && frame > 0 && localFrame == 0) {
                    eventIndex = 0
                    activePulses.clear()
                    for (event in events) if (event.timeSeconds + event.durationSeconds > loopSeconds)
                        activePulses += event.copy(timeSeconds = event.timeSeconds - loopSeconds)
                }
                while (eventIndex < events.size && events[eventIndex].timeSeconds <= localTime) {
                    activePulses += events[eventIndex++]
                    if (record) counts[activePulses.last().body]++
                }
                val before = if (options.recordDiagnostics) bank.energy() else 0.0
                bank.rotate(0, rootRadius)
                bank.rotate(1, centerRadius); bank.rotate(2, centerRadius)
                for (body in 0 until BODIES) {
                    for (mode in 0 until MODES) {
                        val index = body * MODES + mode
                        // A convex interpolation of positive radii strictly below one also contracts.
                        val radius = previousRadii[index] + alpha * (nextRadii[index] - previousRadii[index])
                        bank.rotate(CENTER + body * MODES + mode, radius)
                    }
                }
                for (body in 0 until BODIES) if (participation[body] > 0 && coupling > 0) {
                    val near = if (passOutput) .45 else currentControls.near[body]
                    val sympathy = if (passOutput) .5 else currentControls.sympathy[body]
                    val angle = dt * EXCHANGE_RATE * coupling * participation[body] / allocation *
                        (.12 + .88 * near) * (.25 + .75 * sympathy)
                    // The material contact loads the common bridge, so matched ports receive
                    // a modestly larger exchange budget in the musical model.
                    val harmonicAngle = if (musical) angle * 1.35 else angle
                    bank.exchange(1, CENTER + body * MODES + 1, harmonicAngle)
                    bank.exchange(2, CENTER + body * MODES + 2, harmonicAngle * .8)
                    // Matched harmonic ports retain the sympathetic backbone. A second,
                    // weaker reciprocal contact also reaches the body's distinct material.
                    if (musical) bank.exchangeMaterialPort(body, angle * .35)
                }
                if (options.recordDiagnostics) check(bank.energy().isFinite()) { "APSIS non-finite passive state" }
                val attack = if (held) 1.0 else smooth(time / .045)
                val release = if (held) 1.0 else smooth((sourceEnd - time) / .40)
                val gate = attack * release * velocity.toDouble()
                val phase = TAU * hz * localTime + sourcePhase
                harmonicSource[1] = sin(phase)
                // Harmonic recurrence avoids six trigonometric calls per audio sample.
                val fundamentalCos = cos(phase)
                harmonicSource[0] = 0.0
                for (h in 2 until harmonicSource.size) harmonicSource[h] =
                    2 * fundamentalCos * harmonicSource[h - 1] - harmonicSource[h - 2]
                var sourceValue = 0.0
                for (h in 1..8) sourceValue += harmonicSource[h] * sourceWeights[h]
                sourceValue *= gate
                var populationDrive = 0.0
                var populationBright = 0.0
                if (!legacy) for (body in 0 until BODIES) {
                    populationDrive += participation[body] * (if (passOutput) .55 else currentControls.drive[body]) / weight
                    populationBright += participation[body] * (if (passOutput) .25 else currentControls.bright[body]) / weight
                }
                if (legacy) {
                    bank.v[0] += dt * 3.1 * gate * harmonicSource[1]
                    bank.v[1] += dt * .85 * gate * harmonicSource[2]
                    bank.v[2] += dt * .50 * gate * harmonicSource[3]
                } else {
                    // The center supports the gesture instead of supplying a fixed sine pad.
                    // Its upper ports remain the actual donors/receivers of sympathetic energy.
                    bank.v[0] += dt * (.12 + 1.4 * populationDrive) * gate * harmonicSource[1]
                    if (musical) {
                        bank.v[1] += dt * (.08 + 1.6 * populationBright) * gate * harmonicSource[2] * upperTouch2
                        bank.v[2] += dt * (.05 + 1.1 * populationBright) * gate * harmonicSource[3] * upperTouch3
                    } else {
                        bank.v[1] += dt * (.08 + 1.6 * populationBright) * gate * harmonicSource[2]
                        bank.v[2] += dt * (.05 + 1.1 * populationBright) * gate * harmonicSource[3]
                    }
                }
                for (body in 0 until BODIES) if (participation[body] > 0 && body != disabled) {
                    val drive = if (passOutput) .55 else currentControls.drive[body]
                    val bright = if (passOutput) .25 else currentControls.bright[body]
                    val scale = dt * participation[body] / allocation * gate
                    val index = CENTER + body * MODES
                    if (legacy) {
                        bank.v[index] += scale * 2.2 * drive * harmonicSource[1]
                        bank.v[index + 1] += scale * (1.0 * drive + 2.8 * bright) * harmonicSource[2]
                        bank.v[index + 2] += scale * (.55 * drive + 2.4 * bright) * harmonicSource[3]
                        val upper = UPPER[body].toInt()
                        if (upper < harmonicSource.size) bank.v[index + 3] += scale * 1.5 * bright * harmonicSource[upper]
                    } else {
                        bank.v[index] += scale * 1.7 * drive * harmonicSource[1]
                        if (musical) {
                            bank.v[index + 1] += scale * (.30 * drive + 3.5 * bright) * harmonicSource[2] * upperTouch2
                            bank.v[index + 2] += scale * (.20 * drive + 2.7 * bright) * harmonicSource[3] * upperTouch3
                        } else {
                            bank.v[index + 1] += scale * (.30 * drive + 3.5 * bright) * harmonicSource[2]
                            bank.v[index + 2] += scale * (.20 * drive + 2.7 * bright) * harmonicSource[3]
                        }
                        // The material mode receives finite contacts, not a global locked carrier.
                    }
                }
                val pulseIterator = activePulses.iterator()
                var pulseActive = false
                while (pulseIterator.hasNext()) {
                    val pulse = pulseIterator.next()
                    val age = localTime - pulse.timeSeconds
                    if (age >= pulse.durationSeconds) pulseIterator.remove()
                    else if (age >= 0) {
                        pulseActive = true
                        val force = dt * pulse.impulse * (1 - cos(TAU * age / pulse.durationSeconds)) / pulse.durationSeconds
                        val index = CENTER + pulse.body * MODES
                        // A finite band-limited bright contact: the rounded envelope excites
                        // resonant carriers, rather than injecting a long near-DC force.
                        if (legacy) {
                            bank.v[index] += .30 * force * harmonicSource[1]
                            bank.v[index + 1] += force * harmonicSource[2]
                            bank.v[index + 2] += .75 * force * harmonicSource[3]
                            bank.v[index + 3] += .50 * force * harmonicSource[UPPER[pulse.body].toInt()]
                        } else {
                            // Contact age resets at the actual crossing. The finite envelope and
                            // body seed recur in HOLD even when the material ratio is noninteger.
                            val contact = TAU * hz * age + TAU * contactPhases[pulse.body]
                            // Keep the root anchor coherent with its continuous drive. Random
                            // contact-root phase caused a moving spectral peak at low hard notes;
                            // upper/material contacts retain their body-specific local phases.
                            bank.v[index] += .70 * force * harmonicSource[1]
                            if (musical) {
                                bank.v[index + 1] += .85 * force * sin(2 * contact) * upperTouch2
                                bank.v[index + 2] += .65 * force * sin(3 * contact) * upperTouch3
                                bank.v[index + 3] += .85 * force * sin(upperRatios[pulse.body] * contact) * materialTouch
                            } else {
                                bank.v[index + 1] += .85 * force * sin(2 * contact)
                                bank.v[index + 2] += .65 * force * sin(3 * contact)
                                bank.v[index + 3] += .85 * force * sin(upperRatios[pulse.body] * contact)
                            }
                        }
                    }
                }
                if (options.recordDiagnostics) {
                    val energy = bank.energy()
                    check(energy.isFinite()) { "APSIS non-finite excited state" }
                    maxNorm = max(maxNorm, sqrt(2 * energy))
                    if (gate == 0.0 && !pulseActive) maxUnforcedRise = max(maxUnforcedRise, energy - before)
                }
                val centerValue = if (legacy)
                    .45 * bank.x[0] + .15 * bank.x[1] * bank.radiation[1] + .10 * bank.x[2] * bank.radiation[2]
                    else .28 * bank.x[0] + .40 * bank.x[1] * bank.radiation[1] + .27 * bank.x[2] * bank.radiation[2]
                val direct = if (legacy) .045 * sourceValue else .004 * sourceValue * (.12 + .88 * populationDrive.coerceAtMost(1.0))
                var mixed = if (options.soloBody == null) direct + centerValue else if (options.soloBody == -1) centerValue else 0.0
                if (sourceStem.isNotEmpty()) sourceStem[frame] = direct.toFloat()
                if (centerStem.isNotEmpty()) centerStem[frame] = centerValue.toFloat()
                for (body in 0 until BODIES) {
                    val index = CENTER + body * MODES
                    val tint = 1.0 + body * .13
                    var pickup = if (legacy)
                        .42 * bank.x[index] + (.29 / tint) * bank.x[index + 1] * bank.radiation[index + 1] +
                            (.20 * tint) * bank.x[index + 2] * bank.radiation[index + 2] +
                            .11 * bank.x[index + 3] * bank.radiation[index + 3]
                        else .55 * bank.x[index] + (.50 / tint) * bank.x[index + 1] * bank.radiation[index + 1] +
                            (.35 * tint) * bank.x[index + 2] * bank.radiation[index + 2] +
                            .42 * bank.x[index + 3] * bank.radiation[index + 3]
                    pickup *= participation[body] / allocation
                    if (passOutput) pickup *= sqrt(currentControls.drive[body] / .55)
                    if (bodyStem[body].isNotEmpty()) bodyStem[body][frame] = pickup.toFloat()
                    if (options.soloBody == null || options.soloBody == body) mixed += pickup
                }
                output[frame] = mixed.toFloat()
            }
            return Block(output, sourceStem, centerStem, bodyStem)
        }
        var warmup = Block(FloatArray(0), FloatArray(0), FloatArray(0), Array(BODIES) { FloatArray(0) })
        var previous = Block(FloatArray(0), FloatArray(0), FloatArray(0), Array(BODIES) { FloatArray(0) })
        val block: Block
        if (exportedLoop) {
            // Source attack is absent from held cycles; complete periodic forcing contracts to a unique orbit.
            var previousState = bank.snapshot()
            for (cycle in 1..12) {
                run(loopSeconds, false)
                val state = bank.snapshot()
                var diff = 0.0; var norm = 0.0
                for (i in state.indices) { diff += (state[i] - previousState[i]).pow(2); norm += state[i] * state[i] }
                cycleStateError = sqrt(diff / norm.coerceAtLeast(1e-30))
                previousState = state
                prerollCycles = cycle
                if (cycle >= 2 && cycleStateError < 1e-7) { converged = true; break }
            }
            check(converged) { "APSIS held complete state did not converge in $prerollCycles cycles ($cycleStateError)" }
            warmup = run(loopSeconds, false, stems = true)
            previous = run(loopSeconds, false, stems = true)
            block = run(loopSeconds, true)
        } else block = run(duration, true)
        val following = if (exportedLoop) run(loopSeconds, false, stems = true) else null
        check(bank.x.all { it.isFinite() } && bank.v.all { it.isFinite() }) { "APSIS non-finite final modal state" }
        val synthesisNanos = System.nanoTime() - synthBegan
        val outputBegan = System.nanoTime()
        fun finish(raw: FloatArray, finite: Boolean): FloatArray {
            if (raw.isEmpty()) return raw
            val filtered = raw.copyOf()
            Tide.bandLimit(filtered, rate)
            var result = filtered
            var os = options.oversample
            while (os > 1) {
                val snip = Snip(result, channels = 1, sampleRate = Dsp.RATE * os)
                result = com.snipsnap.audio.Resampler.resample(snip, Dsp.RATE * os / 2).samples
                os /= 2
            }
            val hp = Dsp.OnePole(Dsp.RATE)
            for (i in result.indices) result[i] -= hp.lp(result[i], 18f)
            if (finite) Dsp.fadeTail(result, ms = 8f)
            return result
        }
        val rawMix: FloatArray
        var previousCycle = FloatArray(0)
        if (exportedLoop) {
            // Conditioning sees real adjacent cycles, so its filter state also recurs.
            val both = finish(warmup.mix + previous.mix + block.mix + following!!.mix, false)
            previousCycle = both.copyOfRange(loopFrames, 2 * loopFrames)
            rawMix = both.copyOfRange(2 * loopFrames, 3 * loopFrames)
        } else rawMix = finish(block.mix, true)
        check(rawMix.all { it.isFinite() }) { "APSIS non-finite exported audio" }
        val samples = rawMix.copyOf()
        val peak = rawMix.maxOfOrNull { abs(it).toDouble() } ?: 0.0
        val rms = sqrt(rawMix.sumOf { it.toDouble() * it } / rawMix.size.coerceAtLeast(1))
        val dc = rawMix.sumOf { it.toDouble() } / rawMix.size.coerceAtLeast(1)
        if (options.normalize) Dsp.levelTo(samples, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET * velocity.pow(.70f))
        if (options.normalize && peak > 1e-20 && previousCycle.isNotEmpty()) {
            val gain = (samples.maxOfOrNull { abs(it) } ?: 0f) / peak
            for (i in previousCycle.indices) previousCycle[i] = (previousCycle[i] * gain).toFloat()
        }
        val seam = if (exportedLoop && samples.any { it != 0f }) Keys.seamError(previousCycle + samples, previousCycle.size) else 0.0
        check(!exportedLoop || seam < 1e-3) { "APSIS held seam did not recur: $seam" }
        val law = periods.indices.maxOf { abs(periods[it].pow(2) / axes[it].pow(3) / TIME_SCALE.pow(2) - 1) }
        val diagnostics = Diagnostics(peak, rms, dc, maxNorm, maxUnforcedRise, bank.energy(),
            if (held) -1.0 else sourceEnd, counts, preparationNanos, synthesisNanos, System.nanoTime() - outputBegan,
            converged, prerollCycles, maxResidual, law, minDistance, maxSpeed, axes, periods,
            if (held) abs(sin(TAU * rootCycles + sourcePhase) - sin(sourcePhase)) else 0.0,
            if (held) periods.maxOf { abs(TAU * loopSeconds / it - TAU * (loopSeconds / it).roundToInt()) } else 0.0,
            0.0, // Half-open event schedule and carried seam pulses repeat analytically.
            cacheHit, preparationCache.snapshot().bytes, if (prepared != null) preparationBytes else 0,
            options.recordDiagnostics)
        fun stem(select: (Block) -> FloatArray): FloatArray {
            if (!exportedLoop || select(block).isEmpty()) return finish(select(block), !held)
            val conditioned = finish(select(warmup) + select(previous) + select(block) + select(following!!), false)
            return conditioned.copyOfRange(2 * loopFrames, 3 * loopFrames)
        }
        return Probe(samples, rawMix, stem { it.source }, stem { it.center },
            Array(BODIES) { body -> stem { it.bodies[body] } }, trace, diagnostics, previousCycle,
            seam, cycleStateError, if (exportedLoop) loopFrames else 0, hz, events)
    }
}
