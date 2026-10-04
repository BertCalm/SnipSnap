package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * BALLAST — a bass actuator driving one resonant structure.
 *
 * The direct bass, frame, octave wires and suspended glass are not parallel
 * sound generators. The bass waveform supplies the actuator; the actuator
 * drives the frame; the frame excites the wires and tile motion; tile
 * collisions excite the glass modes and return an equal-and-opposite impulse
 * to the frame. Lower-octave wires receive only onset/release energy and frame
 * motion: no steady sub-oscillator is hidden in the output.
 */
enum class BallastVoice { ROOT, WIRE, GLINT, DEEP, BLOOM, SWARM }

object Ballast {
    const val DEFAULT_MIDI = 36
    const val LOOP_THRESHOLD = 0.99f
    const val SCRAMBLE_HOLD_CEILING = 0.95f
    const val LOOP_SECONDS = 2f
    const val RAW_PEAK_CEILING = 8f

    private const val STRINGS = 6
    private const val TILES = 6
    private const val FRAME_MODES = 3
    private const val GLASS_MODES = 3
    private const val OUTPUT_DC_HZ = 20f
    private const val CONTACT_EPSILON = 1e-5f
    private const val ENDPOINT_FRAMES = 256
    private val STRING_RATIOS = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f, 8f)
    private val FRAME_RATIOS = floatArrayOf(0.72f, 1.37f, 2.18f)

    internal data class Shape(
        val drive: Float,
        val sympathy: Float,
        val span: Float,
        val glass: Float,
        val frame: Float,
        val sourceDark: Float,
        val releaseSeconds: Float,
        val tileScale: Float,
    )

    internal fun shapeOf(voice: BallastVoice): Shape = when (voice) {
        BallastVoice.ROOT -> Shape(.40f, .25f, .30f, .10f, .35f, .48f, 1.8f, .65f)
        BallastVoice.WIRE -> Shape(.45f, .70f, .45f, .20f, .50f, .55f, 3.3f, .8f)
        BallastVoice.GLINT -> Shape(.40f, .45f, .60f, .65f, .45f, .58f, 2.6f, 1.15f)
        BallastVoice.DEEP -> Shape(.45f, .45f, .70f, .15f, .65f, .35f, 3.0f, .8f)
        BallastVoice.BLOOM -> Shape(.55f, .65f, .50f, .40f, .70f, .52f, 4.2f, 1.1f)
        BallastVoice.SWARM -> Shape(.75f, .65f, .80f, .85f, .65f, .70f, 4.8f, 1.4f)
    }

    fun macrosFor(voice: BallastVoice): List<MacroSpec> {
        val s = shapeOf(voice)
        return listOf(
            MacroSpec("DRIVE", s.drive, neutral = .45f),
            MacroSpec("SYMPATHY", s.sympathy, neutral = .40f),
            MacroSpec("SPAN", s.span, neutral = .40f),
            MacroSpec("GLASS", s.glass, neutral = .25f),
            MacroSpec("FRAME", s.frame, neutral = .45f),
            MacroSpec("HOLD", 0f, neutral = 0f),
        )
    }

    fun defaults(voice: BallastVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    internal fun settled(macros: Map<String, Float>, voice: BallastVoice): Map<String, Float> {
        val out = defaults(voice).toMutableMap()
        for ((name, value) in macros) if (name in out) out[name] = value.coerceIn(0f, 1f)
        return out
    }

    fun isLoop(hold: Float): Boolean = hold >= LOOP_THRESHOLD

    fun drumClassFor(voice: BallastVoice, macros: Map<String, Float> = emptyMap()): DrumClass {
        val m = settled(macros, voice)
        return if (isLoop(m.getValue("HOLD")) || durationSeconds(voice, m) > 1.5f) DrumClass.LOOP else DrumClass.TONAL
    }

    fun scramble(
        voice: BallastVoice,
        random: Random,
        temperature: Float = .35f,
        near: Patch? = null,
    ): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near == null) base else base + near.macros.filterKeys { it in base }
        return Dsp.scrambleNear(seed, temperature, random).toMutableMap().also {
            it["HOLD"] = it.getValue("HOLD").coerceAtMost(SCRAMBLE_HOLD_CEILING)
        }
    }

    private fun durationSeconds(voice: BallastVoice, m: Map<String, Float>): Float {
        val hold = Dsp.lin(m.getValue("HOLD").coerceAtMost(LOOP_THRESHOLD) / LOOP_THRESHOLD, .55f, 3.2f)
        val tail = shapeOf(voice).releaseSeconds * Dsp.lin(m.getValue("SYMPATHY"), .65f, 1.15f)
        return (hold + tail).coerceIn(2f, 8f)
    }

    /**
     * A stable second-order mode. [strike] is force, [next] returns displacement.
     * Its pole radius is derived from t60 and is always inside the unit circle.
     */
    private class Mode(hz: Float, t60: Float, private val gain: Float, rate: Int) {
        private val r = 10.0.pow(-3.0 / (t60 * rate)).toFloat().coerceAtMost(.999999f)
        private val a = (2.0 * r * cos(2.0 * PI * hz / rate)).toFloat()
        private val b = r * r
        private var y1 = 0f
        private var y2 = 0f
        fun next(strike: Float): Float {
            val y = strike * gain + a * y1 - b * y2
            y2 = y1
            y1 = y
            return y
        }
    }

    internal data class Diagnostics(
        val raw: FloatArray,
        val contacts: Int,
        val contactStrength: Double,
        val stringEnergy: Double,
        val frameEnergy: Double,
        val glassEnergy: Double,
    )

    /** Test/probe doors. These are ablations, never user macros. */
    internal data class Probe(
        val actuator: Boolean = true,
        val frameReturn: Boolean = true,
        val tileMotion: Boolean = true,
        val strings: Boolean = true,
    )

    /**
     * Complete oversampled physical render. All arrays and state are allocated
     * before the sample loop; iteration and contact ordering are fixed.
     */
    internal fun simulate(
        voice: BallastVoice,
        midi: Int,
        macros: Map<String, Float>,
        velocity: Float = 1f,
        probe: Probe = Probe(),
        loop: Boolean = false,
    ): Diagnostics {
        require(midi in 24..72) { "BALLAST pitch is MIDI 24..72, got $midi" }
        val m = settled(macros, voice)
        val shape = shapeOf(voice)
        val rate = RATE * Dsp.OVERSAMPLE
        val seconds = if (loop) LOOP_SECONDS + 1f else durationSeconds(voice, m)
        val length = ceil(seconds * rate).toInt()
        val out = FloatArray(length)
        val root = Keys.midiHz(midi)
        val drive = m.getValue("DRIVE")
        val sympathy = m.getValue("SYMPATHY")
        val span = m.getValue("SPAN")
        val glass = m.getValue("GLASS")
        val frame = m.getValue("FRAME")
        val vel = velocity.coerceIn(0f, 1f)
        val holdSeconds = if (loop) seconds else durationSeconds(voice, m) - shape.releaseSeconds
        val attackSeconds = Dsp.lin(vel, .055f, .012f)
        val releaseSeconds = .18f

        val frameModes = Array(FRAME_MODES) { k ->
            val hz = (FRAME_RATIOS[k] * root).coerceIn(28f, 190f)
            Mode(hz, Dsp.lin(frame, .25f, 1.8f) * (1f + .2f * k), (1f - .12f * k) / FRAME_MODES, rate)
        }
        val stringLoops = Array(STRINGS) { j ->
            val hz = root * STRING_RATIOS[j]
            val cutoff = minOf(rate * .42f, max(180f, hz * Dsp.lin(drive, 5f, 16f)))
            val tune = Strings.tune(hz, cutoff, rate)
            val t60 = Dsp.lin(sympathy, .55f, 5.5f) * if (j < 2) .75f else 1f
            val feedback = 10f.pow(-3f / (max(hz, 8f) * t60)).coerceAtMost(.9997f)
            Strings.Loop(tune.n, tune.a, feedback, cutoff, rate)
        }
        val stringWeights = FloatArray(STRINGS) { j ->
            val distance = abs(j - 2)
            val spread = .12f + .88f * span.pow(.7f + .45f * distance)
            val rootBias = if (j == 2) 1f else if (j < 2) .55f else .72f
            spread * rootBias
        }

        val glassModes = Array(TILES) { tile ->
            Array(GLASS_MODES) { k ->
                val base = 1_450f + tile * 185f
                val hz = base * floatArrayOf(1f, 1.73f, 2.61f)[k]
                Mode(hz, Dsp.lin(glass, .18f, 1.65f) * (1f - .14f * k), .035f / (k + 1), rate)
            }
        }
        val tileX = FloatArray(TILES) { j -> (j - 2.5f) * 0.00012f }
        val tileV = FloatArray(TILES)
        val tileMass = FloatArray(TILES) { j -> .75f + .11f * j }
        val tileDrive = FloatArray(TILES) { j -> shape.tileScale * (if (j % 2 == 0) 1f else -.82f) * (1f + .07f * j) }
        val collisionImpulse = FloatArray(TILES)
        val gap = Dsp.lin(glass, .010f, .0015f)
        val mountHz = Dsp.lin(frame, 11f, 4.5f)
        val mountK = (2f * PI.toFloat() * mountHz).let { it * it }
        val mountD = Dsp.lin(frame, 8f, 2.2f)
        val contactK = Dsp.lin(glass, 7_000f, 28_000f)
        val contactD = Dsp.lin(glass, 18f, 6f)
        val wasContact = BooleanArray(TILES - 1)

        val noise = Dsp.Noise(Dsp.seedFor("BALLAST", voice.name, midi, m.entries.sortedBy { it.key }))
        var phase1 = 0.0
        var phase2 = .173
        val detune = 2.0.pow((1.4 * (drive - .5)) / 1200.0)
        var slowEnergy = 0f
        var previousEnv = 0f
        var frameReturn = 0f
        var contacts = 0
        var contactStrength = 0.0
        var stringEnergy = 0.0
        var frameEnergy = 0.0
        var glassEnergy = 0.0

        for (i in 0 until length) {
            val t = i.toFloat() / rate
            val attack = (t / attackSeconds).coerceIn(0f, 1f)
            val release = if (loop || t < holdSeconds) 1f else ((holdSeconds + releaseSeconds - t) / releaseSeconds).coerceIn(0f, 1f)
            val env = attack * attack * release
            val harmonics = 2 + (drive * 6f).toInt()
            var osc1 = 0.0
            var osc2 = 0.0
            for (h in 1..harmonics) {
                val roll = 1.0 / h.toDouble().pow(Dsp.lin(shape.sourceDark, 1.7f, .85f).toDouble())
                osc1 += roll * sin(2.0 * PI * phase1 * h)
                osc2 += roll * sin(2.0 * PI * phase2 * h)
            }
            val source = (tanh(Dsp.lin(drive, .75f, 2.8f) * ((osc1 + .72 * osc2) / 2.2)).toFloat() * env * Dsp.lin(vel, .5f, 1f))
            phase1 = (phase1 + root / rate) % 1.0
            phase2 = (phase2 + root * detune / rate) % 1.0

            val energy = source * source
            slowEnergy += .00045f * (energy - slowEnergy)
            val gesture = abs(env - previousEnv) * 22f
            previousEnv = env
            val actuator = if (probe.actuator) {
                Dsp.lin(drive, .12f, .52f) * (source + .42f * sqrt(max(0f, slowEnergy)) * sign(source)) +
                    gesture * noise.next()
            } else 0f

            var frameWave = 0f
            val frameInput = actuator + if (probe.frameReturn) frameReturn else 0f
            for (mode in frameModes) frameWave += mode.next(frameInput * Dsp.lin(frame, .004f, .012f))
            frameReturn = 0f

            var wires = 0f
            if (probe.strings) for (j in 0 until STRINGS) {
                val lower = j < 2
                val onsetRelease = if (lower && probe.actuator) gesture * (.0025f + .004f * span) * noise.next() else 0f
                val harmonicDrive = if (lower) 0f else actuator * (.00035f + .0012f * sympathy)
                val structural = frameWave * (.001f + .004f * sympathy) * stringWeights[j]
                val y = stringLoops[j].next(onsetRelease + harmonicDrive + structural)
                wires += y * stringWeights[j]
                stringEnergy += y.toDouble() * y
                if (probe.frameReturn) frameReturn -= y * (.00018f + .00045f * frame)
            }

            collisionImpulse.fill(0f)
            if (probe.tileMotion) {
                for (j in 0 until TILES) {
                    val acceleration = tileDrive[j] * frameWave * (85f + 180f * glass) -
                        mountK * tileX[j] - mountD * tileV[j]
                    tileV[j] = (tileV[j] + acceleration / tileMass[j] / rate).coerceIn(-2f, 2f)
                    tileX[j] = (tileX[j] + tileV[j] / rate).coerceIn(-.05f, .05f)
                }
                for (j in 0 until TILES - 1) {
                    val delta = tileX[j] - tileX[j + 1]
                    val compression = abs(delta) - gap
                    val touching = compression > 0f
                    if (touching) {
                        val direction = if (delta == 0f) 1f else sign(delta)
                        val relative = (tileV[j] - tileV[j + 1]) * direction
                        val force = max(0f, contactK * compression + contactD * relative)
                        val impulse = force / rate
                        tileV[j] -= direction * impulse / tileMass[j]
                        tileV[j + 1] += direction * impulse / tileMass[j + 1]
                        tileX[j] = tileX[j].coerceIn(-.05f, .05f)
                        tileX[j + 1] = tileX[j + 1].coerceIn(-.05f, .05f)
                        if (!wasContact[j] && impulse > CONTACT_EPSILON) {
                            collisionImpulse[j] += impulse
                            collisionImpulse[j + 1] += impulse
                            contacts++
                            contactStrength += impulse
                        }
                        if (probe.frameReturn) frameReturn -= direction * impulse * .08f
                    }
                    wasContact[j] = touching
                }
            }

            var ringing = 0f
            for (tile in 0 until TILES) {
                val strike = collisionImpulse[tile] * (35f + 95f * glass)
                for (mode in glassModes[tile]) ringing += mode.next(strike)
            }
            frameEnergy += frameWave.toDouble() * frameWave
            glassEnergy += ringing.toDouble() * ringing
            out[i] = .62f * source + (.16f + .20f * sympathy) * wires +
                (.08f + .13f * frame) * frameWave + (.5f + glass) * ringing
        }
        return Diagnostics(out, contacts, contactStrength, stringEnergy, frameEnergy, glassEnergy)
    }

    internal fun finish(raw: FloatArray): FloatArray {
        val work = raw.copyOf()
        Tide.bandLimit(work, RATE * Dsp.OVERSAMPLE)
        val out = Dsp.decimate(work, RATE)
        val mean = if (out.isEmpty()) 0f else out.average().toFloat()
        val lp = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val x = out[i] - mean
            out[i] = x - lp.lp(x, OUTPUT_DC_HZ)
        }
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return out
    }

    /**
     * HOLD extracts a settled two-second physical render and makes the two
     * 256-frame endpoint neighborhoods identical. Contacts stay causal in the
     * source render; the bounded wrap edit only closes the exported period.
     */
    internal fun renderLoop(
        voice: BallastVoice,
        midi: Int,
        macros: Map<String, Float>,
        velocity: Float,
    ): FloatArray {
        val physical = finish(simulate(voice, midi, macros, velocity, loop = true).raw)
        val frames = (LOOP_SECONDS * RATE).toInt()
        val from = (physical.size - frames).coerceAtLeast(0)
        val loop = physical.copyOfRange(from, minOf(physical.size, from + frames))
        if (loop.size >= ENDPOINT_FRAMES * 2) {
            for (i in 0 until ENDPOINT_FRAMES) {
                val a = i.toFloat() / (ENDPOINT_FRAMES - 1)
                val left = loop[i]
                val rightIndex = loop.size - ENDPOINT_FRAMES + i
                val right = loop[rightIndex]
                val joined = left * a + right * (1f - a)
                loop[i] = joined
                loop[rightIndex] = joined
            }
        }
        require(loopSeamError(loop) < Keys.MAX_SEAM_ERROR) { "BALLAST loop does not close" }
        return loop
    }

    internal fun loopSeamError(loop: FloatArray): Double {
        require(loop.size > ENDPOINT_FRAMES)
        val measured = loop.copyOfRange(loop.size - ENDPOINT_FRAMES, loop.size) + loop
        return Keys.seamError(measured, ENDPOINT_FRAMES)
    }

    fun render(
        voice: BallastVoice,
        macros: Map<String, Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI,
        velocity: Float = 1f,
    ): Snip {
        val m = settled(macros, voice)
        val samples = if (isLoop(m.getValue("HOLD"))) {
            renderLoop(voice, midi, m, velocity)
        } else {
            finish(simulate(voice, midi, m, velocity).raw)
        }
        return Snip(samples, channels = 1, sampleRate = RATE)
    }
}
