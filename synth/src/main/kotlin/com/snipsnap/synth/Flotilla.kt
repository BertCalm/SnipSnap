package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * FLOTILLA — pitched emitters on a circular pool, floating vessels, a warm dome.
 *
 * Provisional name (the design handoff, 3 October 2026). Naming availability
 * was not checked. The hide is a tunable warm membrane, not a kangaroo-hide
 * model, and the coupling into the water is deliberately exaggerated: ordinary
 * airborne sound would not throw vessels around like this.
 *
 * Two clocks. The pool is a handful of damped spatial modes stepped at
 * [MECH_RATE] (210 Hz, so a step is an integer number of audio samples). The
 * heard signal is ordinary 44.1 kHz audio: a band-limited pitched source, wood
 * and cavity resonators struck only when a contact actually happens, sparse
 * laps, four dome modes, and a short early-reflection tap. Contact kernels are
 * formed at 4×, band-limited, and decimated; the resonators themselves are
 * linear and stay under Nyquist, so the long buffer is not rendered at 4×.
 *
 * Vessel reaction on the pool is extra nonnegative damping where a hull covers
 * a mode. That changes settling with density. It is not a claim of reciprocal
 * fluid pressure: hull acceleration is not fed back as an inertial force, and
 * the dome does not drive the pool in v1.
 *
 * HOLD above [HOLD_LOOP] does not integrate the hulls until they repeat. A
 * free vessel does not have to come home. The held note is a stationary,
 * phase-locked activity pattern (periodic source, contacts keyed to the route
 * field) rendered until the filters converge, then cut on that period. The
 * seam is measured. It is not evidence that the hulls returned to their start.
 */
enum class FlotillaVoice { RIPPLE, KNOCK, HOLLOW, CROSSWAVE, DRIFT, GATHER }

object Flotilla {

    const val MODEL_VERSION = 1
    const val MIDI_MIN = 36
    const val MIDI_MAX = 84
    const val DEFAULT_MIDI = 60

    /** One-shot below this; at and above it, the stationary held loop. */
    const val HOLD_LOOP = 0.85f

    const val MECH_RATE = 210
    const val SAMPLES_PER_STEP = RATE / MECH_RATE // 210

    private const val MODES = 8
    private const val PAIRS = 4
    private const val MIN_VESSELS = 6
    private const val MAX_VESSELS = 18
    private const val POOL = 1.0
    private const val GAP = 0.012
    private const val Z_MAX = 0.35
    private const val V_MAX = 1.15
    private const val FORCE_MAX = 90.0
    private const val MODAL_GAIN = 14.0
    private const val ROUTE_PUSH = 5.5

    private val PAIR_ANGLE = DoubleArray(PAIRS) { it * PI / PAIRS }

    private val MACRO_NAMES = listOf("PULSE", "CROSSING", "FLOTILLA", "VESSEL", "SURFACE", "SKIN", "HOLD")

    private class Body(
        val wood: FloatArray,
        val cavity: Float,
        val contact: Float,
        val partialBias: Int,
        val center: Double,
        val dome: Float,
    )

    private val BODIES: Map<FlotillaVoice, Body> = mapOf(
        FlotillaVoice.RIPPLE to Body(floatArrayOf(2.07f, 3.23f, 4.71f), 1.62f, 0.75f, 0, 0.02, 0.85f),
        FlotillaVoice.KNOCK to Body(floatArrayOf(1.73f, 2.91f, 5.05f), 2.15f, 1.30f, 1, 0.05, 0.70f),
        FlotillaVoice.HOLLOW to Body(floatArrayOf(1.31f, 2.17f, 3.41f), 1.22f, 1.10f, 0, 0.04, 1.15f),
        FlotillaVoice.CROSSWAVE to Body(floatArrayOf(1.89f, 2.77f, 4.33f), 1.74f, 1.05f, 0, 0.08, 0.90f),
        FlotillaVoice.DRIFT to Body(floatArrayOf(1.55f, 2.49f, 3.83f), 1.36f, 0.60f, -1, 0.03, 1.30f),
        FlotillaVoice.GATHER to Body(floatArrayOf(1.67f, 2.83f, 4.97f), 1.48f, 1.40f, 1, 0.20, 0.95f),
    )

    private val DEFAULTS: Map<FlotillaVoice, Map<String, Float>> = mapOf(
        FlotillaVoice.RIPPLE to macros(0.20f, 0.15f, 0.30f, 0.35f, 0.25f, 0.40f, 0f),
        FlotillaVoice.KNOCK to macros(0.65f, 0.25f, 0.45f, 0.35f, 0.45f, 0.35f, 0f),
        FlotillaVoice.HOLLOW to macros(0.30f, 0.30f, 0.40f, 0.80f, 0.40f, 0.60f, 0f),
        FlotillaVoice.CROSSWAVE to macros(0.45f, 0.80f, 0.50f, 0.45f, 0.60f, 0.45f, 0f),
        FlotillaVoice.DRIFT to macros(0.10f, 0.45f, 0.35f, 0.55f, 0.30f, 0.70f, 0f),
        FlotillaVoice.GATHER to macros(0.70f, 0.65f, 0.80f, 0.60f, 0.70f, 0.60f, 0f),
    )

    private fun macros(pulse: Float, crossing: Float, flotilla: Float, vessel: Float, surface: Float, skin: Float, hold: Float) =
        linkedMapOf(
            "PULSE" to pulse, "CROSSING" to crossing, "FLOTILLA" to flotilla, "VESSEL" to vessel,
            "SURFACE" to surface, "SKIN" to skin, "HOLD" to hold,
        )

    /** Neutral and default are the voice's own setting: a knob at rest is that voice. */
    fun macrosFor(voice: FlotillaVoice): List<MacroSpec> {
        val d = defaults(voice)
        return MACRO_NAMES.map { name -> MacroSpec(name, d.getValue(name), d.getValue(name)) }
    }

    fun defaults(voice: FlotillaVoice): Map<String, Float> = DEFAULTS.getValue(voice)

    fun scramble(voice: FlotillaVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val seed = when (near) {
            is FlotillaPatch -> defaults(voice) + near.macros
            else -> defaults(voice)
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    fun isLoop(hold: Float): Boolean = hold >= HOLD_LOOP

    fun frequencyFor(midi: Int): Float = Keys.midiHz(midi)

    /**
     * Long enough that the classifier's length rule files the pad LOOP, which
     * is the honest class for a note that rings past 1.5 s. Never a drum class:
     * standard voices are pitched texture.
     */
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: FlotillaVoice, @Suppress("UNUSED_PARAMETER") macros: Map<String, Float> = emptyMap()): DrumClass =
        DrumClass.LOOP

    fun render(
        voice: FlotillaVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
    ): Snip = renderInternal(voice, macros, midi, velocity).snip

    internal fun renderInternal(
        voice: FlotillaVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
        seconds: Float? = null,
        driveSurface: Boolean = true,
        collisionSound: Boolean = true,
    ): Rendered {
        val m = settled(voice, macros)
        val v = velocity.coerceIn(0f, 1f)
        require(midi in MIDI_MIN..MIDI_MAX) { "FLOTILLA midi out of $MIDI_MIN..$MIDI_MAX: $midi" }
        require(v.isFinite()) { "FLOTILLA velocity is not finite" }
        val hold = m.getValue("HOLD")
        if (isLoop(hold) && seconds == null) {
            val loop = renderLoop(voice, m, midi, v, driveSurface, collisionSound)
            require(loop.seam < Keys.MAX_SEAM_ERROR) {
                "FLOTILLA $voice MIDI $midi: the loop does not close (seam %.2e, bar %.0e)".format(
                    Locale.ROOT, loop.seam, Keys.MAX_SEAM_ERROR,
                )
            }
            val twice = FloatArray(loop.period.size * 2)
            for (i in loop.period.indices) {
                twice[i] = loop.period[i]
                twice[i + loop.period.size] = loop.period[i]
            }
            Dsp.levelTo(twice, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
            val bytes = loop.scene.bytes + twice.size * 4L * 4
            return Rendered(Snip(twice, channels = 1, sampleRate = RATE), loop.scene, loop, bytes)
        }
        val length = (seconds ?: lengthSeconds(m)).coerceIn(0.4f, 8f)
        val scene = simulate(voice, m, midi, v, length, driveSurface)
        val audio = renderAudio(voice, m, midi, v, scene, collisionSound)
        val bytes = scene.bytes + audio.samples.size * 4L * 6
        return Rendered(audio, scene, null, bytes)
    }

    internal fun lengthSeconds(m: Map<String, Float>): Float {
        val pulse = m.getValue("PULSE")
        val skin = m.getValue("SKIN")
        val hold = m.getValue("HOLD").coerceAtMost(HOLD_LOOP)
        val decay = 0.55f + (1f - pulse) * 0.40f + hold * 1.15f
        val ring = 0.40f + skin * 0.85f
        return (0.40f + decay + ring).coerceIn(1.75f, 5.2f)
    }

    /** Route weights. Pair 0 is always on. Each further pair fades in across the knob. */
    internal fun routeWeights(crossing: Float): FloatArray {
        val c = crossing.coerceIn(0f, 1f)
        val w = FloatArray(PAIRS)
        w[0] = 1f
        for (p in 1 until PAIRS) {
            val start = (p - 1) / 3f
            val x = ((c - start) * 3f).coerceIn(0f, 1f)
            w[p] = x * x * (3f - 2f * x)
        }
        return w
    }

    internal fun population(flotilla: Float): Pair<Int, Float> {
        val exact = MIN_VESSELS + flotilla.coerceIn(0f, 1f) * (MAX_VESSELS - MIN_VESSELS)
        val count = ceil(exact - 1e-4).toInt().coerceIn(MIN_VESSELS, MAX_VESSELS)
        val last = (exact - (count - 1)).toFloat().coerceIn(0.15f, 1f)
        return count to last
    }

    private fun settled(voice: FlotillaVoice, macros: Map<String, Float>): Map<String, Float> {
        val out = LinkedHashMap(defaults(voice))
        for ((k, v) in macros) if (k in out && v.isFinite()) out[k] = v.coerceIn(0f, 1f)
        return out
    }

    private fun seed(label: String, voice: FlotillaVoice, vararg parts: Any): Int {
        val all = ArrayList<Any>(parts.size + 4)
        all.add("FLOTILLA")
        all.add(MODEL_VERSION)
        all.add(label)
        all.add(voice.name)
        for (p in parts) all.add(p)
        return Dsp.seedFor(*all.toTypedArray())
    }

    private fun milli(v: Float): Int = (v * 10000f).roundToInt()

    // ---------- geometry and motion ----------

    internal data class Contact(val step: Int, val a: Int, val b: Int, val impulse: Float, val x: Float, val y: Float)

    internal class Scene(
        val count: Int,
        val x0: FloatArray,
        val y0: FloatArray,
        val radii: FloatArray,
        val engagement: FloatArray,
        val mass: FloatArray,
        val detune: FloatArray,
        val angle: FloatArray,
        val cavityHz: FloatArray,
        val woodHz: Array<FloatArray>,
        val routeWeights: FloatArray,
        val contacts: List<Contact>,
        val splashes: List<Contact>,
        val obstruction: Float,
        val meanSpeed: Float,
        val meanHeave: Float,
        val surfacePeak: Float,
        val surfaceEnd: Float,
        val kineticEnd: Float,
        val maxOverlap: Float,
        val periodic: Boolean,
        val bytes: Long,
        val steps: Int,
    )

    internal class LoopReport(
        val period: FloatArray,
        val previous: FloatArray,
        val seam: Double,
        val periodDiff: Float,
        val crossfadeGain: Float,
        val crossfadeSamples: Int,
        val iterations: Int,
        val scene: Scene,
        val diffs: FloatArray,
    )

    internal class Rendered(val snip: Snip, val scene: Scene, val loop: LoopReport?, val bytes: Long)

    private class Hull(
        val x: Double,
        val y: Double,
        val radius: Double,
        val mass: Double,
        val engagement: Double,
        val detune: Double,
        val angle: Double,
    )

    internal fun simulate(
        voice: FlotillaVoice,
        macros: Map<String, Float>,
        midi: Int,
        velocity: Float,
        seconds: Float,
        driveSurface: Boolean,
    ): Scene {
        val m = settled(voice, macros)
        val body = BODIES.getValue(voice)
        val pulse = m.getValue("PULSE")
        val surface = m.getValue("SURFACE")
        val vessel = m.getValue("VESSEL")
        val flotilla = m.getValue("FLOTILLA")
        val weights = routeWeights(m.getValue("CROSSING"))
        val (count, lastEng) = population(flotilla)
        val hulls = place(voice, count, lastEng, vessel, midi, flotilla)
        val steps = max(8, (seconds * MECH_RATE).roundToInt())
        val dt = 1.0 / MECH_RATE
        val z = DoubleArray(MODES)
        val zv = DoubleArray(MODES)
        val omega = DoubleArray(MODES)
        val baseDamp = DoubleArray(MODES)
        val transfer = 0.18 + 0.82 * surface.toDouble().pow(0.55)
        for (k in 0 until MODES) {
            val hz = (0.85 + surface * 3.2) * (1.0 + 0.20 * k)
            omega[k] = 2.0 * PI * hz
            baseDamp[k] = (1.15 + 0.28 * k) * (1.20 - 0.72 * surface)
        }
        val x = DoubleArray(count) { hulls[it].x }
        val y = DoubleArray(count) { hulls[it].y }
        val vx = DoubleArray(count)
        val vy = DoubleArray(count)
        val prevHeave = DoubleArray(count)
        val lastPair = Array(count) { IntArray(count) { -10_000 } }
        val lastSplash = IntArray(count) { -10_000 }
        val lastWall = IntArray(count) { -10_000 }
        val contacts = ArrayList<Contact>()
        val splashes = ArrayList<Contact>()
        var overlapMax = 0.0
        var speedSum = 0.0
        var heaveSum = 0.0
        var obsSum = 0.0
        var surfacePeak = 0.0
        var surfaceEnd = 0.0
        var kineticEnd = 0.0
        val noteHz = frequencyFor(midi)
        val attack = (0.010 + (1.0 - pulse) * 0.045) / (0.45 + 0.55 * velocity)
        val decay = 0.50 + (1.0 - pulse) * 0.45 + m.getValue("HOLD").coerceAtMost(HOLD_LOOP) * 1.1

        for (step in 0 until steps) {
            val t = step * dt
            val env = if (!driveSurface) 0.0 else if (t < attack) t / attack else exp(-(t - attack) / decay)
            val edge = 0.06 + (1.0 - pulse) * 0.42
            val rate = 1.3 + pulse * 5.2
            val ph = (t * rate) % 1.0
            val rise = smooth((ph / edge).coerceIn(0.0, 1.0))
            val fall = smooth(((1.0 - ph) / (0.40 + (1.0 - pulse) * 0.45)).coerceIn(0.0, 1.0))
            val contour = rise * fall
            val drive = env * (0.40 + 0.60 * contour) * transfer * (0.35 + 0.65 * velocity)

            var extraMean = 0.0
            for (k in 0 until MODES) {
                var extra = 0.0
                val mOrder = (k % 4) + 1
                for (i in 0 until count) {
                    val s = shape(k, x[i], y[i])
                    extra += hulls[i].engagement * hulls[i].radius * s * s * (0.35 + 1.4 * flotilla)
                }
                extraMean += extra
                val damp = baseDamp[k] + extra
                var force = 0.0
                if (driveSurface) {
                    for (p in 0 until PAIRS) {
                        val w = weights[p].toDouble()
                        if (w <= 1e-4) continue
                        val spatial = cos(mOrder * PAIR_ANGLE[p] + 0.4 * k)
                        val returned = 0.30 * cos(mOrder * (PAIR_ANGLE[p] + PI) + 0.4 * k)
                        force += w * (spatial + returned)
                    }
                    force *= drive * (0.65 + 0.35 * pulse)
                }
                val acc = force * 18.0 - damp * zv[k] - omega[k] * omega[k] * z[k]
                zv[k] += acc * dt
                z[k] += zv[k] * dt
                if (z[k] > Z_MAX) { z[k] = Z_MAX; if (zv[k] > 0) zv[k] = 0.0 }
                if (z[k] < -Z_MAX) { z[k] = -Z_MAX; if (zv[k] < 0) zv[k] = 0.0 }
            }
            obsSum += extraMean / MODES

            var eMode = 0.0
            for (k in 0 until MODES) eMode += zv[k] * zv[k] + omega[k] * omega[k] * z[k] * z[k]
            surfacePeak = max(surfacePeak, eMode)
            surfaceEnd = eMode

            var kin = 0.0
            for (i in 0 until count) {
                val e0 = eta(z, x[i], y[i])
                val gx = (eta(z, x[i] + 1e-3, y[i]) - e0) / 1e-3
                val gy = (eta(z, x[i], y[i] + 1e-3) - e0) / 1e-3
                val mob = MODAL_GAIN * transfer / hulls[i].mass
                vx[i] += gx * mob * dt * (if (driveSurface) 1.0 else 0.0)
                vy[i] += gy * mob * dt * (if (driveSurface) 1.0 else 0.0)
                if (driveSurface) {
                    var px = 0.0
                    var py = 0.0
                    for (p in 0 until PAIRS) {
                        val w = weights[p].toDouble()
                        if (w <= 1e-4) continue
                        val ax = cos(PAIR_ANGLE[p])
                        val ay = sin(PAIR_ANGLE[p])
                        val side = x[i] * ax + y[i] * ay
                        px += w * ax * side
                        py += w * ay * side
                    }
                    val push = ROUTE_PUSH * drive / hulls[i].mass
                    vx[i] += px * push * dt
                    vy[i] += py * push * dt
                }
                vx[i] += -x[i] * body.center * dt
                vy[i] += -y[i] * body.center * dt
                val drag = (1.0 - (1.15 + 0.4 / hulls[i].mass) * dt).coerceAtLeast(0.0)
                vx[i] *= drag
                vy[i] *= drag
                var sp = hypot(vx[i], vy[i])
                if (sp > V_MAX) {
                    vx[i] *= V_MAX / sp
                    vy[i] *= V_MAX / sp
                    sp = V_MAX
                }
                x[i] += vx[i] * dt
                y[i] += vy[i] * dt
                speedSum += sp
                val heave = e0
                heaveSum += abs(heave)
                val dh = abs(heave - prevHeave[i]) / dt
                prevHeave[i] = heave
                val splashLine = 0.55 - surface * 0.40
                if (dh > splashLine && step - lastSplash[i] > 12 && driveSurface) {
                    lastSplash[i] = step
                    val amp = (dh * (0.12 + 0.88 * surface) * hulls[i].engagement).toFloat()
                    splashes.add(Contact(step, i, -1, amp, x[i].toFloat(), y[i].toFloat()))
                }
                kin += hulls[i].mass * (vx[i] * vx[i] + vy[i] * vy[i])
            }
            kineticEnd = kin

            for (i in 0 until count) {
                for (j in i + 1 until count) {
                    val dx = x[j] - x[i]
                    val dy = y[j] - y[i]
                    val dist = max(1e-6, hypot(dx, dy))
                    val minD = hulls[i].radius + hulls[j].radius
                    val overlap = minD - dist
                    if (overlap > 0) {
                        overlapMax = max(overlapMax, overlap)
                        val nx = dx / dist
                        val ny = dy / dist
                        val rel = (vx[i] - vx[j]) * nx + (vy[i] - vy[j]) * ny
                        var force = 55.0 * overlap
                        if (rel > 0) force += 9.0 * rel
                        force = force.coerceIn(0.0, FORCE_MAX)
                        val impulse = force * dt
                        vx[i] -= force * nx / hulls[i].mass * dt
                        vy[i] -= force * ny / hulls[i].mass * dt
                        vx[j] += force * nx / hulls[j].mass * dt
                        vy[j] += force * ny / hulls[j].mass * dt
                        val corr = overlap * 0.5
                        x[i] -= nx * corr
                        y[i] -= ny * corr
                        x[j] += nx * corr
                        y[j] += ny * corr
                        if (impulse > 0.004 && rel > 0.015 && step - lastPair[i][j] > 10) {
                            lastPair[i][j] = step
                            val cx = ((x[i] + x[j]) * 0.5).toFloat()
                            val cy = ((y[i] + y[j]) * 0.5).toFloat()
                            contacts.add(Contact(step, i, j, (impulse * (0.5 + 0.5 * rel)).toFloat(), cx, cy))
                        }
                    }
                }
                val d = hypot(x[i], y[i])
                val limit = POOL - hulls[i].radius
                if (d > limit && d > 1e-6) {
                    val nx = x[i] / d
                    val ny = y[i] / d
                    val overlap = d - limit
                    overlapMax = max(overlapMax, overlap)
                    val outward = vx[i] * nx + vy[i] * ny
                    var force = 70.0 * overlap
                    if (outward > 0) force += 8.0 * outward
                    force = force.coerceIn(0.0, FORCE_MAX)
                    vx[i] -= force * nx / hulls[i].mass * dt
                    vy[i] -= force * ny / hulls[i].mass * dt
                    x[i] -= nx * overlap
                    y[i] -= ny * overlap
                    if (force * dt > 0.008 && outward > 0.02 && step - lastWall[i] > 18) {
                        lastWall[i] = step
                        contacts.add(Contact(step, i, -1, (force * dt).toFloat(), x[i].toFloat(), y[i].toFloat()))
                    }
                }
            }
        }

        val cavity = FloatArray(count)
        val wood = Array(count) { FloatArray(3) }
        for (i in 0 until count) {
            val size = (0.78f + vessel * 0.65f) * hulls[i].detune.toFloat()
            cavity[i] = (noteHz * body.cavity / size).coerceIn(noteHz * 1.05f, 5000f)
            for (k in 0 until 3) {
                wood[i][k] = (noteHz * body.wood[k] / size).coerceIn(noteHz * 1.15f, 12000f)
            }
        }
        val nHull = count
        return Scene(
            count = nHull,
            x0 = FloatArray(nHull) { hulls[it].x.toFloat() },
            y0 = FloatArray(nHull) { hulls[it].y.toFloat() },
            radii = FloatArray(nHull) { hulls[it].radius.toFloat() },
            engagement = FloatArray(nHull) { hulls[it].engagement.toFloat() },
            mass = FloatArray(nHull) { hulls[it].mass.toFloat() },
            detune = FloatArray(nHull) { hulls[it].detune.toFloat() },
            angle = FloatArray(nHull) { hulls[it].angle.toFloat() },
            cavityHz = cavity,
            woodHz = wood,
            routeWeights = weights,
            contacts = contacts,
            splashes = splashes,
            obstruction = (obsSum / steps).toFloat(),
            meanSpeed = (speedSum / (steps * count)).toFloat(),
            meanHeave = (heaveSum / (steps * count)).toFloat(),
            surfacePeak = surfacePeak.toFloat(),
            surfaceEnd = surfaceEnd.toFloat(),
            kineticEnd = kineticEnd.toFloat(),
            maxOverlap = overlapMax.toFloat(),
            periodic = false,
            bytes = (steps * 8L) + (count * 64L),
            steps = steps,
        )
    }

    private fun place(voice: FlotillaVoice, count: Int, lastEng: Float, vessel: Float, midi: Int, flotilla: Float): List<Hull> {
        val geo = Random(seed("GEOMETRY", voice, milli(vessel), milli(flotilla), count, midi))
        val ang0 = geo.nextDouble() * PI * 2
        val golden = PI * (3 - sqrt(5.0))
        val tightness = if (voice == FlotillaVoice.GATHER) 0.72 else 0.86
        var radScale = 1.0
        var x = DoubleArray(count)
        var y = DoubleArray(count)
        var radii = DoubleArray(count)
        repeat(6) {
            radii = DoubleArray(count) {
                val jitter = 0.84 + geo.nextDouble() * 0.32
                (0.040 + vessel * 0.072) * jitter * radScale
            }
            for (i in 0 until count) {
                val t = (i + 0.5) / count
                val ring = sqrt(t) * (POOL * tightness - radii[i])
                val ang = ang0 + i * golden
                x[i] = ring * cos(ang)
                y[i] = ring * sin(ang)
            }
            repeat(20) {
                for (i in 0 until count) {
                    for (j in i + 1 until count) {
                        val dx = x[j] - x[i]
                        val dy = y[j] - y[i]
                        val dist = hypot(dx, dy)
                        val minD = radii[i] + radii[j] + GAP
                        if (dist < minD) {
                            val nx = if (dist < 1e-6) 1.0 else dx / dist
                            val ny = if (dist < 1e-6) 0.0 else dy / dist
                            val push = (minD - dist) * 0.5
                            x[i] -= nx * push
                            y[i] -= ny * push
                            x[j] += nx * push
                            y[j] += ny * push
                        }
                    }
                    val d = hypot(x[i], y[i])
                    val limit = POOL - radii[i] - GAP
                    if (d > limit && d > 1e-8) {
                        x[i] *= limit / d
                        y[i] *= limit / d
                    }
                }
            }
            if (!overlaps(x, y, radii)) return hullsOf(x, y, radii, count, lastEng, vessel, geo)
            radScale *= 0.90
        }
        return hullsOf(x, y, radii, count, lastEng, vessel, geo)
    }

    private fun overlaps(x: DoubleArray, y: DoubleArray, radii: DoubleArray): Boolean {
        for (i in x.indices) {
            for (j in i + 1 until x.size) {
                if (hypot(x[j] - x[i], y[j] - y[i]) < radii[i] + radii[j] - 1e-4) return true
            }
        }
        return false
    }

    private fun hullsOf(x: DoubleArray, y: DoubleArray, radii: DoubleArray, count: Int, lastEng: Float, vessel: Float, geo: Random): List<Hull> {
        val out = ArrayList<Hull>(count)
        for (i in 0 until count) {
            val eng = if (i < count - 1) 1.0 else lastEng.toDouble()
            val scale = radii[i] / (0.040 + vessel * 0.072).coerceAtLeast(0.02)
            val mass = scale * scale * (0.55 + vessel * 0.95) * eng.coerceAtLeast(0.25)
            val detune = 0.96 + geo.nextDouble() * 0.08
            out.add(Hull(x[i], y[i], radii[i], mass, eng, detune, atan2(y[i], x[i])))
        }
        return out
    }

    private fun shape(mode: Int, x: Double, y: Double): Double {
        val r = min(1.0, hypot(x, y))
        if (r < 1e-4) return 0.0
        val th = atan2(y, x)
        val m = (mode % 4) + 1
        val radial = if (mode < 4) r else r * (1.0 - r)
        return cos(m * th) * radial
    }

    private fun eta(z: DoubleArray, x: Double, y: Double): Double {
        var s = 0.0
        for (k in z.indices) s += z[k] * shape(k, x, y)
        return s
    }

    private fun smooth(x: Double): Double = x * x * (3.0 - 2.0 * x)

    // ---------- audio ----------

    private class Bank(val freq: Float, val decay: Float, val at: IntArray, val amp: FloatArray)

    private fun renderAudio(voice: FlotillaVoice, m: Map<String, Float>, midi: Int, velocity: Float, scene: Scene, collisionSound: Boolean): Snip {
        val n = scene.steps * SAMPLES_PER_STEP
        val pulse = m.getValue("PULSE")
        val skin = m.getValue("SKIN")
        val surface = m.getValue("SURFACE")
        val body = BODIES.getValue(voice)
        val hz = frequencyFor(midi).toDouble()
        val src = sourceBuffer(n, hz, pulse, velocity, m.getValue("HOLD"), body.partialBias, seed("SOURCE", voice, midi, milli(pulse), milli(velocity)))
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = src[i]

        if (collisionSound) {
            val banks = banksFor(scene, body, pulse, velocity, m.getValue("VESSEL"))
            val wet = FloatArray(n)
            val scratch = FloatArray(n)
            for (bank in banks) addBank(scratch, wet, bank, 1f)
            // The resonators are near the unit circle, so an unscaled bank
            // swamps the source and the pitch detector follows the wood.
            // Hold them to a share of the source's energy: audible, under the note.
            val srcRms = rms(src)
            val wetRms = rms(wet)
            val share = (0.16f + 0.20f * (body.contact / 1.4f)).coerceIn(0.16f, 0.40f)
            if (wetRms > 1e-8f && srcRms > 1e-8f) {
                val g = (share * srcRms / wetRms).coerceAtMost(8f)
                for (i in wet.indices) out[i] += wet[i] * g
            }
        }
        val noise = Dsp.Noise(seed("AQUATIC", voice, midi, milli(surface), milli(velocity), scene.splashes.size))
        for (s in scene.splashes) {
            val amp = s.impulse * (0.15f + 0.85f * surface) * 0.55f
            addBurst(out, s.step * SAMPLES_PER_STEP, amp, noise, n)
        }
        val dry = out.copyOf()
        reflect(out, dry, skin)
        val pressure = FloatArray(n)
        val couple = (0.12f + 0.28f * skin) * body.dome
        for (i in 0 until n) pressure[i] = src[i] * couple
        if (collisionSound) {
            for (c in scene.contacts) {
                addKernel(pressure, c.step * SAMPLES_PER_STEP, c.impulse * (0.35f + 0.65f * skin) * 0.25f, n)
            }
        }
        addDome(out, pressure, hz.toFloat(), skin, body.dome)
        dcBlock(out)
        lowpass(out, 14000f)
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        for (v in out) if (!v.isFinite()) error("FLOTILLA produced a non-finite sample")
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    private fun banksFor(scene: Scene, body: Body, pulse: Float, velocity: Float, vessel: Float): List<Bank> {
        val lists = Array(scene.count * 4) { ArrayList<Int>() to ArrayList<Float>() }
        for (c in scene.contacts) {
            val amp = c.impulse * body.contact * scene.engagement[c.a] * (0.40f + 0.60f * velocity) * (0.55f + 0.45f * pulse)
            val loc = atan2(c.y.toDouble(), c.x.toDouble())
            for (k in 0 until 3) {
                val w = (0.55 + 0.45 * cos(loc * (k + 1))).toFloat()
                val slot = lists[c.a * 4 + k]
                slot.first.add(c.step * SAMPLES_PER_STEP)
                slot.second.add(amp * w)
            }
            val cav = lists[c.a * 4 + 3]
            cav.first.add(c.step * SAMPLES_PER_STEP)
            cav.second.add(amp * (0.75f + 0.5f * vessel))
        }
        val banks = ArrayList<Bank>()
        for (i in 0 until scene.count) {
            val decayWood = woodDecaySeconds(vessel, scene.detune[i])
            val decayCav = cavityDecaySeconds(vessel, scene.detune[i])
            for (k in 0 until 3) {
                val slot = lists[i * 4 + k]
                if (slot.first.isEmpty()) continue
                banks.add(Bank(scene.woodHz[i][k], decayWood, slot.first.toIntArray(), slot.second.toFloatArray()))
            }
            val cav = lists[i * 4 + 3]
            if (cav.first.isNotEmpty()) {
                banks.add(Bank(scene.cavityHz[i], decayCav, cav.first.toIntArray(), cav.second.toFloatArray()))
            }
        }
        return banks
    }

    private fun addBank(scratch: FloatArray, out: FloatArray, bank: Bank, mix: Float) {
        scratch.fill(0f)
        for (e in bank.at.indices) addKernel(scratch, bank.at[e], bank.amp[e], scratch.size)
        val w = (2.0 * PI * bank.freq / RATE).toFloat()
        val r = exp(-bank.decay / RATE).coerceIn(0.99f, 0.99995f)
        val coeff = 2f * r * cos(w)
        val r2 = r * r
        var y1 = 0f
        var y2 = 0f
        for (i in scratch.indices) {
            val y = coeff * y1 - r2 * y2 + scratch[i]
            y2 = y1
            y1 = y
            out[i] += y * mix
        }
    }

    private fun addDome(out: FloatArray, pressure: FloatArray, noteHz: Float, skin: Float, domeMix: Float) {
        val base = (noteHz * (0.55f + (1f - skin) * 0.7f)).coerceIn(90f, 640f)
        val ratios = floatArrayOf(1f, 1.47f, 2.11f, 2.83f)
        val decay = (11f - skin * 7.5f).coerceIn(3f, 12f)
        for (k in ratios.indices) {
            val freq = (base * ratios[k]).coerceAtMost(3500f)
            val w = (2.0 * PI * freq / RATE).toFloat()
            val r = exp(-decay * (1f + 0.15f * k) / RATE).coerceIn(0.99f, 0.9999f)
            val coeff = 2f * r * cos(w)
            val r2 = r * r
            val scale = (1f - r) * domeMix * (0.9f - 0.12f * k)
            var y1 = 0f
            var y2 = 0f
            for (i in out.indices) {
                val y = coeff * y1 - r2 * y2 + pressure[i] * scale
                y2 = y1
                y1 = y
                out[i] += y
            }
        }
    }

    private fun reflect(out: FloatArray, dry: FloatArray, skin: Float) {
        val taps = intArrayOf(293, 521, 877, 1201)
        val gains = floatArrayOf(0.11f, 0.08f, 0.055f, 0.035f)
        val absorb = 0.75f + 0.25f * skin
        for (t in taps.indices) {
            val d = taps[t]
            val g = gains[t] * absorb
            for (i in d until out.size) out[i] += g * dry[i - d]
        }
    }

    private fun sourceBuffer(n: Int, hz: Double, pulse: Float, velocity: Float, hold: Float, partialBias: Int, phaseSeed: Int): FloatArray {
        val partials = (2 + (pulse * 6f).roundToInt() + partialBias).coerceIn(2, 10)
        val tilt = 1.85 - pulse * 1.05
        val phases = Dsp.phases(partials, phaseSeed)
        val attack = (0.008 + (1.0 - pulse) * 0.042) / (0.45 + 0.55 * velocity)
        val decay = 0.48 + (1.0 - pulse) * 0.40 + hold.coerceAtMost(HOLD_LOOP).toDouble() * 1.05
        val out = FloatArray(n)
        val amp = 0.35 + 0.65 * velocity
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val env = if (t < attack) t / attack else exp(-(t - attack) / decay)
            val edge = 0.06 + (1.0 - pulse) * 0.42
            val rate = 1.3 + pulse * 5.2
            val ph = (t * rate) % 1.0
            val contour = smooth((ph / edge).coerceIn(0.0, 1.0)) * smooth(((1.0 - ph) / (0.40 + (1.0 - pulse) * 0.45)).coerceIn(0.0, 1.0))
            var y = 0.0
            var norm = 0.0
            for (k in 1..partials) {
                val fk = hz * k
                if (fk > 15000.0) break
                val a = 1.0 / k.toDouble().pow(tilt)
                y += a * sin(2.0 * PI * fk * t + phases[k - 1] * 2.0 * PI)
                norm += a
            }
            out[i] = (y / norm * env * (0.58 + 0.42 * contour) * amp).toFloat()
        }
        return out
    }

    private fun rms(buf: FloatArray): Float {
        var e = 0.0
        for (v in buf) e += v * v
        return sqrt(e / buf.size.coerceAtLeast(1)).toFloat()
    }

    private fun addBurst(buf: FloatArray, at: Int, amp: Float, noise: Dsp.Noise, n: Int) {
        val len = 420
        val a = exp(-2.0 * PI * 3500.0 / RATE).toFloat()
        var lp = 0f
        for (i in 0 until len) {
            val j = at + i
            if (j >= n) break
            val white = noise.next()
            lp += (1f - a) * (white - lp)
            val env = sin(PI * i / len).toFloat()
            buf[j] += amp * env * lp
        }
    }

    private fun dcBlock(buf: FloatArray) {
        var x1 = 0f
        var y1 = 0f
        val r = 0.9975f
        for (i in buf.indices) {
            val x = buf[i]
            val y = x - x1 + r * y1
            buf[i] = y
            x1 = x
            y1 = y
        }
    }

    private fun lowpass(buf: FloatArray, hz: Float) {
        val a = exp(-2f * PI.toFloat() * hz / RATE)
        var y = 0f
        for (i in buf.indices) {
            y = a * y + (1f - a) * buf[i]
            buf[i] = y
        }
    }

    private val CONTACT_KERNEL: FloatArray by lazy { buildKernel() }

    private fun buildKernel(): FloatArray {
        val upRate = RATE * Dsp.OVERSAMPLE
        val n = (0.0045 * upRate).toInt()
        val up = FloatArray(n)
        for (i in up.indices) {
            val x = i / (n - 1.0)
            val hann = 0.5 * (1.0 - cos(2.0 * PI * x))
            up[i] = kotlin.math.tanh(5.0 * (hann - 0.35)).toFloat()
        }
        Tide.bandLimit(up, upRate)
        val down = Dsp.decimate(up, RATE)
        var mean = 0.0
        for (v in down) mean += v
        mean /= down.size.coerceAtLeast(1)
        var peak = 0f
        for (i in down.indices) {
            down[i] = (down[i] - mean).toFloat()
            peak = max(peak, abs(down[i]))
        }
        if (peak > 1e-8f) for (i in down.indices) down[i] /= peak
        return down
    }

    private fun addKernel(buf: FloatArray, at: Int, amp: Float, n: Int) {
        val k = CONTACT_KERNEL
        for (i in k.indices) {
            val j = at + i
            if (j in 0 until n) buf[j] += amp * k[i]
        }
    }

    private fun addKernelWrap(buf: FloatArray, at: Int, amp: Float) {
        val k = CONTACT_KERNEL
        val n = buf.size
        for (i in k.indices) buf[Math.floorMod(at + i, n)] += amp * k[i]
    }

    // ---------- HOLD ----------

    private fun renderLoop(voice: FlotillaVoice, m: Map<String, Float>, midi: Int, velocity: Float, driveSurface: Boolean, collisionSound: Boolean): LoopReport {
        val hz = frequencyFor(midi).toDouble()
        val cycles = max(6, (0.92 * hz).roundToInt())
        val frames = max(4096, (cycles * RATE / hz).roundToInt())
        val played = cycles * RATE.toDouble() / frames
        val pulse = m.getValue("PULSE")
        val skin = m.getValue("SKIN")
        val surface = m.getValue("SURFACE")
        val vessel = m.getValue("VESSEL")
        val body = BODIES.getValue(voice)
        val (count, lastEng) = population(m.getValue("FLOTILLA"))
        val hulls = place(voice, count, lastEng, vessel, midi, m.getValue("FLOTILLA"))
        val weights = routeWeights(m.getValue("CROSSING"))
        val scene = stationaryScene(voice, m, midi, velocity, hulls, weights, played.toFloat(), frames, surface, pulse, body, driveSurface)
        val src = periodicSource(frames, played, cycles, pulse, velocity, body.partialBias)
        val banks = if (collisionSound && driveSurface) stationaryBanks(scene, vessel) else emptyList()
        val aqua = FloatArray(frames)
        if (driveSurface) {
            val noise = Dsp.Noise(seed("AQUATIC-HOLD", voice, midi, milli(surface)))
            for (s in scene.splashes) addBurstWrap(aqua, s.step, s.impulse * (0.15f + 0.85f * surface) * 0.45f, noise)
        }
        val state = LoopState(banks)
        var prev = FloatArray(frames)
        var curr = FloatArray(frames)
        var iters = 0
        var diff = 1f
        val cap = 8
        val diffs = FloatArray(cap)
        while (iters < cap) {
            iters++
            curr = mixPeriod(src, aqua, banks, state, skin, body.dome, played.toFloat(), collisionSound && driveSurface)
            diff = maxDiff(prev, curr)
            diffs[iters - 1] = diff
            val snapshot = curr.copyOf()
            prev = snapshot
            if (diff < 1e-4f && iters >= 2) break
        }
        val period = prev
        val seamBuf = FloatArray(256 + period.size)
        val earlier = if (iters >= 2) prev else period
        // previous period was overwritten; re-run one extra carried step so the seam compares two real periods.
        val next = mixPeriod(src, aqua, banks, state, skin, body.dome, played.toFloat(), collisionSound && driveSurface)
        for (i in 0 until 256) seamBuf[i] = period[period.size - 256 + i]
        for (i in period.indices) seamBuf[256 + i] = next[i]
        // That compares the tail of `period` with... wait, seamError compares preroll to the end of `next` only
        // if next's tail matches. Use period as preroll source and next as the loop: preroll must be period's
        // tail and the loop is next, and seam checks periodTail vs nextTail. If they converged, those match.
        val seam = try {
            Keys.seamError(seamBuf, 256)
        } catch (e: IllegalArgumentException) {
            1.0
        }
        val gain = overlapGain(next, 64)
        return LoopReport(next, period, seam, diff, gain, 0, iters, scene, diffs)
    }

    private class LoopState(val banks: List<Bank>) {
        val y1 = FloatArray(banks.size)
        val y2 = FloatArray(banks.size)
        val dome1 = FloatArray(4)
        val dome2 = FloatArray(4)
        var dcX = 0f
        var dcY = 0f
        var lp = 0f
    }

    private fun mixPeriod(
        src: FloatArray,
        aqua: FloatArray,
        banks: List<Bank>,
        state: LoopState,
        skin: Float,
        domeMix: Float,
        noteHz: Float,
        collision: Boolean,
    ): FloatArray {
        val n = src.size
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = src[i] + aqua[i]
        if (collision) {
            for ((b, bank) in banks.withIndex()) {
                val scratch = FloatArray(n)
                for (e in bank.at.indices) addKernelWrap(scratch, bank.at[e], bank.amp[e])
                val ww = (2.0 * PI * bank.freq / RATE).toFloat()
                val r = exp(-bank.decay / RATE).coerceIn(0.99f, 0.99995f)
                val coeff = 2f * r * cos(ww)
                val r2 = r * r
                var a = state.y1[b]
                var c = state.y2[b]
                for (i in 0 until n) {
                    val y = coeff * a - r2 * c + scratch[i]
                    c = a
                    a = y
                    out[i] += y * 0.42f
                }
                state.y1[b] = a
                state.y2[b] = c
            }
        }
        val dry = out.copyOf()
        val taps = intArrayOf(293, 521, 877, 1201)
        val gains = floatArrayOf(0.11f, 0.08f, 0.055f, 0.035f)
        val absorb = 0.75f + 0.25f * skin
        for (t in taps.indices) {
            val d = taps[t]
            val g = gains[t] * absorb
            for (i in 0 until n) out[i] += g * dry[Math.floorMod(i - d, n)]
        }
        val base = (noteHz * (0.55f + (1f - skin) * 0.7f)).coerceIn(90f, 640f)
        val ratios = floatArrayOf(1f, 1.47f, 2.11f, 2.83f)
        val decay = (11f - skin * 7.5f).coerceIn(3f, 12f)
        for (k in ratios.indices) {
            val freq = (base * ratios[k]).coerceAtMost(3500f)
            val ww = (2.0 * PI * freq / RATE).toFloat()
            val r = exp(-decay * (1f + 0.15f * k) / RATE).coerceIn(0.99f, 0.9999f)
            val coeff = 2f * r * cos(ww)
            val r2 = r * r
            val scale = (1f - r) * domeMix * (0.9f - 0.12f * k)
            var a = state.dome1[k]
            var c = state.dome2[k]
            for (i in 0 until n) {
                val drive = src[i] * (0.12f + 0.28f * skin) * domeMix
                val y = coeff * a - r2 * c + drive * scale
                c = a
                a = y
                out[i] += y
            }
            state.dome1[k] = a
            state.dome2[k] = c
        }
        var x1 = state.dcX
        var y1 = state.dcY
        val rr = 0.9975f
        for (i in out.indices) {
            val x = out[i]
            val y = x - x1 + rr * y1
            out[i] = y
            x1 = x
            y1 = y
        }
        state.dcX = x1
        state.dcY = y1
        val lpA = exp(-2f * PI.toFloat() * 14000f / RATE)
        var lp = state.lp
        for (i in out.indices) {
            lp = lpA * lp + (1f - lpA) * out[i]
            out[i] = lp
        }
        state.lp = lp
        for (i in out.indices) if (!out[i].isFinite()) out[i] = 0f
        return out
    }

    private fun periodicSource(frames: Int, hz: Double, cycles: Int, pulse: Float, velocity: Float, partialBias: Int): FloatArray {
        val partials = (2 + (pulse * 6f).roundToInt() + partialBias).coerceIn(2, 10)
        val tilt = 1.85 - pulse * 1.05
        val bumps = 2 + (pulse * 3f).roundToInt()
        val out = FloatArray(frames)
        val amp = 0.35 + 0.65 * velocity
        for (i in 0 until frames) {
            var y = 0.0
            var norm = 0.0
            for (k in 1..partials) {
                val fk = hz * k
                if (fk > 15000.0) break
                val a = 1.0 / k.toDouble().pow(tilt)
                val phase = 2.0 * PI * k * cycles * i / frames
                y += a * sin(phase)
                norm += a
            }
            val frac = (bumps * i.toDouble() / frames) % 1.0
            val shaped = sin(PI * frac).pow(1.15 + (1.0 - pulse) * 2.2)
            out[i] = (y / norm * (0.60 + 0.40 * shaped) * amp).toFloat()
        }
        return out
    }

    private fun stationaryScene(
        voice: FlotillaVoice,
        m: Map<String, Float>,
        midi: Int,
        velocity: Float,
        hulls: List<Hull>,
        weights: FloatArray,
        noteHz: Float,
        frames: Int,
        surface: Float,
        pulse: Float,
        body: Body,
        driveSurface: Boolean,
    ): Scene {
        val contacts = ArrayList<Contact>()
        val splashes = ArrayList<Contact>()
        val ticks = max(8, frames / SAMPLES_PER_STEP)
        val threshold = 0.85 - surface * 0.7
        if (driveSurface) {
            for (i in hulls.indices) {
                var prev = fieldAt(hulls[i], weights, 0, ticks)
                for (tick in 1 until ticks) {
                    val f = fieldAt(hulls[i], weights, tick, ticks)
                    if (prev < threshold && f >= threshold) {
                        val sample = min(frames - 1, tick * SAMPLES_PER_STEP)
                        val amp = ((0.25 + 0.75 * surface) * hulls[i].engagement * body.contact * (0.45 + 0.55 * pulse) * (0.4 + 0.6 * velocity)).toFloat()
                        contacts.add(Contact(sample, i, (i + 1) % hulls.size, amp, hulls[i].x.toFloat(), hulls[i].y.toFloat()))
                        splashes.add(Contact(sample, i, -1, amp * (0.3f + surface), hulls[i].x.toFloat(), hulls[i].y.toFloat()))
                    }
                    prev = f
                }
            }
        }
        val vessel = m.getValue("VESSEL")
        val cavity = FloatArray(hulls.size)
        val wood = Array(hulls.size) { FloatArray(3) }
        for (i in hulls.indices) {
            val size = (0.78f + vessel * 0.65f) * hulls[i].detune.toFloat()
            cavity[i] = (noteHz * body.cavity / size).coerceIn(noteHz * 1.05f, 5000f)
            for (k in 0 until 3) wood[i][k] = (noteHz * body.wood[k] / size).coerceIn(noteHz * 1.15f, 12000f)
        }
        return Scene(
            count = hulls.size,
            x0 = FloatArray(hulls.size) { hulls[it].x.toFloat() },
            y0 = FloatArray(hulls.size) { hulls[it].y.toFloat() },
            radii = FloatArray(hulls.size) { hulls[it].radius.toFloat() },
            engagement = FloatArray(hulls.size) { hulls[it].engagement.toFloat() },
            mass = FloatArray(hulls.size) { hulls[it].mass.toFloat() },
            detune = FloatArray(hulls.size) { hulls[it].detune.toFloat() },
            angle = FloatArray(hulls.size) { hulls[it].angle.toFloat() },
            cavityHz = cavity,
            woodHz = wood,
            routeWeights = weights,
            contacts = contacts,
            splashes = splashes,
            obstruction = hulls.sumOf { it.radius * it.engagement }.toFloat(),
            meanSpeed = 0f,
            meanHeave = surface * 0.1f,
            surfacePeak = if (driveSurface) surface else 0f,
            surfaceEnd = 0f,
            kineticEnd = 0f,
            maxOverlap = 0f,
            periodic = true,
            bytes = frames * 4L,
            steps = ticks,
        )
    }

    private fun fieldAt(h: Hull, weights: FloatArray, tick: Int, ticks: Int): Double {
        val phase = tick.toDouble() / ticks
        var s = 0.0
        for (p in 0 until PAIRS) {
            val w = weights[p].toDouble()
            if (w <= 1e-4) continue
            val spatial = cos(h.angle - PAIR_ANGLE[p])
            s += w * spatial * sin(2.0 * PI * 3.0 * phase + p * 0.85 + h.angle)
        }
        return s
    }

    private fun stationaryBanks(scene: Scene, vessel: Float): List<Bank> {
        val lists = Array(scene.count * 4) { ArrayList<Int>() to ArrayList<Float>() }
        for (c in scene.contacts) {
            val loc = atan2(c.y.toDouble(), c.x.toDouble())
            for (k in 0 until 3) {
                val w = (0.55 + 0.45 * cos(loc * (k + 1))).toFloat()
                lists[c.a * 4 + k].first.add(c.step)
                lists[c.a * 4 + k].second.add(c.impulse * w)
            }
            lists[c.a * 4 + 3].first.add(c.step)
            lists[c.a * 4 + 3].second.add(c.impulse * (0.75f + 0.5f * vessel))
        }
        val banks = ArrayList<Bank>()
        for (i in 0 until scene.count) {
            val decayWood = woodDecaySeconds(vessel, scene.detune[i])
            val decayCav = cavityDecaySeconds(vessel, scene.detune[i])
            for (k in 0 until 3) {
                val slot = lists[i * 4 + k]
                if (slot.first.isEmpty()) continue
                banks.add(Bank(scene.woodHz[i][k], decayWood, slot.first.toIntArray(), slot.second.toFloatArray()))
            }
            val cav = lists[i * 4 + 3]
            if (cav.first.isNotEmpty()) banks.add(Bank(scene.cavityHz[i], decayCav, cav.first.toIntArray(), cav.second.toFloatArray()))
        }
        return banks
    }

    /**
     * Wood resonator t60. The extra `- 2` sheds ring so a one-shot hit dies
     * with the note; HOLD reuses the same law because its contacts re-excite
     * every period and a longer held decay was a leftover from the stationary
     * path, not a second instrument.
     */
    internal fun woodDecaySeconds(vessel: Float, detune: Float): Float =
        ((16f - vessel * 7f - 2f) / detune).coerceIn(3.5f, 28f)

    internal fun cavityDecaySeconds(vessel: Float, detune: Float): Float =
        ((8f - vessel * 3.5f) / detune).coerceIn(2.8f, 18f)

    private fun addBurstWrap(buf: FloatArray, at: Int, amp: Float, noise: Dsp.Noise) {
        val len = 420
        val n = buf.size
        val a = exp(-2.0 * PI * 3500.0 / RATE).toFloat()
        var lp = 0f
        for (i in 0 until len) {
            val white = noise.next()
            lp += (1f - a) * (white - lp)
            val env = sin(PI * i / len).toFloat()
            buf[Math.floorMod(at + i, n)] += amp * env * lp
        }
    }

    private fun maxDiff(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        val n = min(a.size, b.size)
        for (i in 0 until n) m = max(m, abs(a[i] - b[i]))
        return m
    }

    /** Equal-power overlap of the first [n] samples with the last [n], as a gain on the dry start. Measured, not assumed. */
    internal fun overlapGain(loop: FloatArray, n: Int): Float {
        if (loop.size <= n || n < 2) return 1f
        var wet = 0.0
        var dry = 0.0
        for (i in 0 until n) {
            val t = i / (n - 1f)
            val a = sin(t * PI.toFloat() / 2f)
            val b = cos(t * PI.toFloat() / 2f)
            val mixed = a * loop[i] + b * loop[loop.size - n + i]
            wet += mixed * mixed
            dry += loop[i] * loop[i]
        }
        if (dry <= 1e-12) return 1f
        return sqrt(wet / dry).toFloat()
    }

    /** Settled macros for tests and the patch. */
    internal fun settledMacros(voice: FlotillaVoice, macros: Map<String, Float>): Map<String, Float> = settled(voice, macros)

    internal fun describeScene(scene: Scene): String = String.format(
        Locale.ROOT,
        "vessels=%d contacts=%d splashes=%d overlap=%.4f obstruction=%.4f speed=%.4f heave=%.4f surfacePeak=%.4f surfaceEnd=%.4f",
        scene.count, scene.contacts.size, scene.splashes.size, scene.maxOverlap, scene.obstruction, scene.meanSpeed, scene.meanHeave, scene.surfacePeak, scene.surfaceEnd,
    )
}
