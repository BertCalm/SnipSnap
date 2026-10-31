package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Optional OHM sources and a continuously driven sustain, based on OHM model 2 at
 * SnipSnap 5e953da5493d08112f10fcd1bd2b000bd7946b92. Models 1 and 2 are untouched.
 *
 * The characteristic gesture is touch -> answering sources -> shared body -> held company.
 * A bow is literally Strings.Bow's two-segment friction junction. Its bridge port shares the
 * same passive membrane as the other strings; it is never a sawtooth layered over a pluck.
 * Captures, FM and grains inject bounded source energy before that junction. No audio output
 * is recirculated into the texture history.
 *
 * Full HOLD is a driven adaptation: periodically renewed excitation keeps the strings alive.
 * GATHER happens once, then stays at its final relationship. A Session owns one uninterrupted
 * physical state through onset, settling, the exported cycle and a genuinely subsequent cycle.
 * Wander and source clocks share the note-fitted period. Plucked/captured drive must prove
 * physical periodicity. A nonlinear bow instead uses a measured, disclosed capture cut from
 * its live sustain, preserving the real following audio and its nonzero continuation error.
 *
 * The new source gains, bow pressure range, drive and finite hold-time maps are shape choices
 * until the model-3 audition has been heard. Model 3 consistently uses this new source schedule;
 * setting a texture amount to zero never silently switches to another renderer. The accepted
 * old recipes continue through their unchanged model-1/model-2 dispatch and default model 2.
 * Explicit revision 4 reuses this physical core with revised source-memory preparation;
 * direct calls to the original public methods continue to select revision 3.
 */
object OhmV3 {
    const val MODEL_VERSION = 3
    val MACROS = OhmV2.MACROS + MacroSpec("HOLD", 0f)
    const val LOOP_THRESHOLD = .99f
    private const val RATE = Dsp.RATE
    private const val OS = Dsp.OVERSAMPLE
    private const val IR = RATE * OS
    private const val CHECK = 4096
    private const val LOG_1000 = 6.907755278982137
    private const val MAX_SECONDS = 12
    private const val CONTINUATION_BAR = 1e-3

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }
    fun isLoop(hold: Float) = hold >= LOOP_THRESHOLD

    private fun settled(macros: Map<String, Float>): Map<String, Float> = defaults().toMutableMap().also { out ->
        for ((key, value) in macros) if (key in out) {
            require(value.isFinite()) { "OHM $key must be finite" }
            out[key] = value.coerceIn(0f, 1f)
        }
    }

    private fun validate(midi: Int, velocity: Float, options: OhmOptions) {
        require(midi in 48..72) { "OHM midi must be 48..72: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OHM velocity must be 0..1: $velocity" }
        require(options.exciter != OhmExciter.CAPTURED || options.capture != null) { "OHM captured excitation needs a capture" }
    }

    fun renderRaw(
        macros: Map<String, Float> = emptyMap(), midi: Int = 60, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY, options: OhmOptions = OhmOptions(),
    ): Snip = renderRaw(macros, midi, velocity, seed, gesture, options, 3)

    /** The saved renderer selects its source revision; legacy callers retain model 3. */
    internal fun renderRaw(
        macros: Map<String, Float>, midi: Int, velocity: Float,
        seed: Int, gesture: OhmGesture, options: OhmOptions, textureRevision: Int,
    ): Snip {
        require(textureRevision in 3..4) { "unsupported OHM texture revision $textureRevision" }
        validate(midi, velocity, options)
        val m = settled(macros)
        if (isLoop(m.getValue("HOLD"))) {
            val held = heldProbe(m, midi, velocity, seed, gesture, options, textureRevision = textureRevision)
            return Snip(held.rawLoop.copyOf(), 1, RATE)
        }
        val seconds = 1.8 + 4.2 * m.getValue("LENGTH") + 3 * m.getValue("HOLD")
        val frames = (seconds * RATE).toInt()
        if (velocity == 0f) return Snip(FloatArray(frames), 1, RATE)
        val session = Session(m, midi, velocity, seed, gesture, options, null, seconds, textureRevision = textureRevision)
        val raw = FloatArray(frames * OS)
        session.advance(raw)
        val out = condition(raw)
        Dsp.fadeTail(out, 120f, RATE)
        return Snip(out, 1, RATE)
    }

    fun render(
        macros: Map<String, Float> = emptyMap(), midi: Int = 60, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY, options: OhmOptions = OhmOptions(),
    ): Snip = render(macros, midi, velocity, seed, gesture, options, 3)

    internal fun render(
        macros: Map<String, Float>, midi: Int, velocity: Float,
        seed: Int, gesture: OhmGesture, options: OhmOptions, textureRevision: Int,
    ): Snip {
        validate(midi, velocity, options)
        val m = settled(macros)
        val out = renderRaw(m, midi, velocity, seed, gesture, options, textureRevision)
        Dsp.levelTo(out.samples, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        val gain = velocity.pow(.8f)
        for (i in out.samples.indices) out.samples[i] *= gain
        return out
    }

    fun renderHeld(
        macros: Map<String, Float> = emptyMap(), midi: Int = 60, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY, options: OhmOptions = OhmOptions(),
        cancelled: () -> Boolean = { false },
    ): KeyNote = heldProbe(macros + ("HOLD" to 1f), midi, velocity, seed, gesture, options, cancelled).note

    internal fun renderHeld(
        macros: Map<String, Float>, midi: Int, velocity: Float,
        seed: Int, gesture: OhmGesture, options: OhmOptions,
        cancelled: () -> Boolean, textureRevision: Int,
    ): KeyNote = heldProbe(macros + ("HOLD" to 1f), midi, velocity, seed, gesture, options, cancelled, textureRevision).note

    /** Conditioned attack plus sustain before output leveling, for reproducible audition exports. */
    fun renderHeldRaw(
        macros: Map<String, Float> = emptyMap(), midi: Int = 60, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY, options: OhmOptions = OhmOptions(),
        cancelled: () -> Boolean = { false },
    ): KeyNote = heldProbe(macros + ("HOLD" to 1f), midi, velocity, seed, gesture, options, cancelled).rawNote

    internal fun renderHeldRaw(
        macros: Map<String, Float>, midi: Int, velocity: Float,
        seed: Int, gesture: OhmGesture, options: OhmOptions,
        cancelled: () -> Boolean, textureRevision: Int,
    ): KeyNote = heldProbe(macros + ("HOLD" to 1f), midi, velocity, seed, gesture, options, cancelled, textureRevision).rawNote

    internal data class HeldProbe(
        val note: KeyNote, val rawNote: KeyNote, val nextCycle: FloatArray, val rawLoop: FloatArray, val rawNextCycle: FloatArray,
        val loopStart: Int, val loopFrames: Int, val continuationError: Double,
        val rawContinuationError: Double, val settledCycles: Int, val appliedBlend: Boolean = false,
        val maxState: Float, val effectiveHz: Float,
        val sustainMethod: String = "physical-periodic", val physicalLoop: FloatArray = rawLoop,
        val blendFrames: Int = 0, val joinCorrelation: Double = 1.0, val blendDipDb: Double = 0.0,
        val wrapStepRatio: Double = 0.0, val wrapSlopeRatio: Double = 0.0,
        val physicalSourceStartFrame: Int = loopStart,
        val followingSourceStartFrame: Int = physicalSourceStartFrame + loopFrames,
    )

    internal fun heldProbe(
        macros: Map<String, Float> = emptyMap(), midi: Int = 60, velocity: Float = 1f,
        seed: Int = 1, gesture: OhmGesture = OhmGesture.STEADY, options: OhmOptions = OhmOptions(),
        cancelled: () -> Boolean = { false },
        textureRevision: Int = 3,
    ): HeldProbe {
        require(textureRevision in 3..4) { "unsupported OHM texture revision $textureRevision" }
        validate(midi, velocity, options)
        stop(cancelled)
        val m = settled(macros)
        val hz = Keys.midiHz(midi)
        val periods = (hz * (0.65 + .40 * m.getValue("LENGTH"))).roundToInt().coerceAtLeast(1)
        val loopFrames = (periods * RATE / hz).roundToInt().coerceAtLeast(256)
        val loopOs = loopFrames * OS
        val onsetCycles = if (gesture == OhmGesture.GATHER) 2 else 1
        val maximum = (MAX_SECONDS * RATE / loopFrames).coerceAtLeast(onsetCycles + 4)
        val session = Session(m, midi, velocity, seed, gesture, options, loopFrames,
            onsetCycles * loopFrames.toDouble() / RATE, cancelled, textureRevision)
        val cycles = ArrayList<FloatArray>()
        var previous: FloatArray? = null
        var rawError = Double.POSITIVE_INFINITY
        var settledAt = -1
        val captureBow = options.exciter == OhmExciter.BOW
        // The spare cycle is real future audio, and 48 extra internal samples supply both
        // decimation filters' right context. Neither is manufactured from the kept cycle.
        for (cycle in 0 until maximum) {
            val current = FloatArray(loopOs)
            session.advance(current)
            cycles += current
            previous?.let { rawError = relativeError(it, current) }
            if (captureBow && cycle >= onsetCycles + 3) {
                settledAt = cycle - 1
                break
            }
            if (cycle >= onsetCycles + 2 && rawError < CONTINUATION_BAR * .25) {
                settledAt = cycle
                break
            }
            previous = current
        }
        check(settledAt >= 0) { "OHM held physical state did not converge within ${MAX_SECONDS}s: MIDI $midi, source ${options.exciter}, error $rawError" }
        val future = FloatArray(loopOs)
        session.advance(future)
        cycles += future
        val context = FloatArray(48)
        session.advance(context)
        val all = FloatArray(cycles.size * loopOs + context.size)
        for (i in cycles.indices) cycles[i].copyInto(all, i * loopOs)
        context.copyInto(all, cycles.size * loopOs)
        val conditioned = condition(all, cancelled)
        if (captureBow) return capturedBowProbe(conditioned, settledAt * loopFrames,
            loopFrames, midi, velocity, session.maxState, settledAt + 1, cancelled)
        val start = settledAt * loopFrames
        val end = start + loopFrames
        val rawLoop = conditioned.copyOfRange(start, end)
        val rawNext = conditioned.copyOfRange(end, end + loopFrames)
        val rawOutputError = relativeError(rawLoop, rawNext)
        check(rawOutputError < CONTINUATION_BAR) { "OHM held output continuation did not close: $rawOutputError" }
        val noteAudio = conditioned.copyOfRange(0, end)
        val rawNote = KeyNote(Snip(noteAudio.copyOf(), 1, RATE), start.toLong())
        val gain = levelGain(rawLoop, noteAudio, velocity)
        for (i in noteAudio.indices) noteAudio[i] *= gain
        val next = FloatArray(loopFrames) { rawNext[it] * gain }
        if (velocity > 0f) Keys.requireSeam("OHM held", noteAudio, start)
        return HeldProbe(KeyNote(Snip(noteAudio, 1, RATE), start.toLong()), rawNote, next, rawLoop, rawNext,
            start, loopFrames, relativeError(noteAudio.copyOfRange(start, end), next), rawError,
            settledAt + 1, maxState = session.maxState, effectiveHz = periods.toFloat() * RATE / loopFrames)
    }

    /**
     * The bow is a sustained physical source, but need not repeat its stick/slip trajectory.
     * Cut only after the one-time entrance. The loop head starts on the real audio following
     * the cut, then blends back to the held stretch; its tail remains untouched. Entry from
     * the preserved onset therefore follows real adjacent source samples. The unmodified
     * stretch and genuinely later stretch remain separate evidence, never a copied proof.
     */
    private fun capturedBowProbe(
        source: FloatArray, start: Int, planned: Int, midi: Int, velocity: Float,
        maxState: Float, settledCycles: Int, cancelled: () -> Boolean,
    ): HeldProbe {
        stop(cancelled)
        val blend = (RATE * .12).toInt()
        val period = (RATE / Keys.midiHz(midi)).roundToInt()
        // A local whole-wave search fits the actual bowed pitch without changing playback speed.
        val low = planned - period
        val high = planned + period
        val (length, correlation) = Bore.bestLength(source, start, blend, low, high)
        require(correlation >= .85 || velocity == 0f) { "OHM bow capture correlation $correlation is below .85" }
        stop(cancelled)
        val physical = source.copyOfRange(start, start + length)
        val following = source.copyOfRange(start + length, start + 2 * length)
        val loop = Bore.assembleLoop(source, start, length, blend, correlation)
        val dip = if (velocity == 0f) 0.0 else Bore.blendDipDb(loop, blend)
        require(dip >= -2.0) { "OHM bow capture dips $dip dB at its blend" }
        val stepRatio = boundaryRatio(loop, slope = false)
        val slopeRatio = boundaryRatio(loop, slope = true)
        require(stepRatio <= 1.5 && slopeRatio <= 1.5) {
            "OHM bow capture has an excessive wrap step/slope: $stepRatio / $slopeRatio"
        }
        val marker = start + length
        val rawAudio = FloatArray(marker + length)
        source.copyInto(rawAudio, 0, 0, marker)
        loop.copyInto(rawAudio, marker)
        val rawNote = KeyNote(Snip(rawAudio.copyOf(), 1, RATE), marker.toLong())
        val gain = levelGain(loop, rawAudio, velocity)
        for (i in rawAudio.indices) rawAudio[i] *= gain
        val next = FloatArray(length) { following[it] * gain }
        // This metric checks the manufactured sample's wrap only. The physical error below
        // explicitly remains nonzero; it is not replaced with a copied-loop comparison.
        return HeldProbe(KeyNote(Snip(rawAudio, 1, RATE), marker.toLong()), rawNote, next,
            loop, following, marker, length, relativeError(loop, following), relativeError(physical, following),
            settledCycles, appliedBlend = true, maxState = maxState, effectiveHz = Keys.midiHz(midi),
            sustainMethod = "captured-loop", physicalLoop = physical, blendFrames = blend,
            joinCorrelation = correlation, blendDipDb = dip, wrapStepRatio = stepRatio, wrapSlopeRatio = slopeRatio,
            physicalSourceStartFrame = start, followingSourceStartFrame = start + length)
    }

    private fun boundaryRatio(loop: FloatArray, slope: Boolean): Double {
        if (loop.size < 4) return 0.0
        val movements = DoubleArray(loop.size - if (slope) 2 else 1) { i ->
            if (slope) abs(loop[i + 2].toDouble() - 2 * loop[i + 1] + loop[i])
            else abs(loop[i + 1].toDouble() - loop[i])
        }
        movements.sort()
        val reference = movements[((movements.size - 1) * .999).toInt()]
        val boundary = if (slope) abs((loop[0].toDouble() - loop.last()) -
            (loop.last().toDouble() - loop[loop.lastIndex - 1])) else abs(loop[0].toDouble() - loop.last())
        return if (reference < 1e-12) if (boundary < 1e-12) 0.0 else Double.POSITIVE_INFINITY else boundary / reference
    }

    private fun relativeError(a: FloatArray, b: FloatArray): Double {
        var error = 0.0
        var energy = 0.0
        for (i in a.indices) { val d = a[i].toDouble() - b[i]; error += d * d; energy += a[i].toDouble() * a[i] }
        return if (energy < 1e-24) if (error < 1e-24) 0.0 else Double.POSITIVE_INFINITY else error / energy
    }

    private fun levelGain(loop: FloatArray, all: FloatArray, velocity: Float): Float {
        if (velocity == 0f) return 0f
        val leveled = loop.copyOf()
        Dsp.levelTo(leveled, RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        var num = 0.0; var den = 0.0
        for (i in loop.indices) { num += leveled[i].toDouble() * loop[i]; den += loop[i].toDouble() * loop[i] }
        var gain = if (den > 0) (num / den).toFloat() else 0f
        val peak = all.maxOfOrNull { abs(it) } ?: 0f
        if (peak * gain > .99f) gain = .99f / peak
        return gain * velocity.pow(.8f)
    }

    private fun condition(raw: FloatArray, cancelled: () -> Boolean = { false }): FloatArray {
        stop(cancelled)
        Tide.bandLimit(raw, IR)
        val out = Dsp.decimate(raw, RATE)
        val dc = Dsp.FixedOnePole(3f, RATE)
        for (i in out.indices) { if (i % CHECK == 0) stop(cancelled); out[i] -= dc.lp(out[i]) }
        return out
    }

    private fun stop(cancelled: () -> Boolean = { false }) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("OHM render cancelled")
    }
    private fun smooth(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    /** The actual primary state before the shared body/readout, for causal junction tests. */
    internal data class ForegroundProbe(val foreground: FloatArray, val sampleRate: Int, val maxState: Float)

    internal fun foregroundProbe(
        macros: Map<String, Float>, midi: Int = 60, velocity: Float = .8f, seed: Int = 731,
        gesture: OhmGesture = OhmGesture.STEADY, options: OhmOptions = OhmOptions(),
        textureRevision: Int = 3,
    ): ForegroundProbe {
        validate(midi, velocity, options)
        val m = settled(macros)
        require(!isLoop(m.getValue("HOLD"))) { "foreground proof uses the live finite state" }
        val seconds = 1.8 + 4.2 * m.getValue("LENGTH") + 3 * m.getValue("HOLD")
        val count = (seconds * RATE).toInt() * OS
        val output = FloatArray(count)
        val foreground = FloatArray(count)
        val session = Session(m, midi, velocity, seed, gesture, options, null, seconds, textureRevision = textureRevision)
        session.advance(output, foreground)
        return ForegroundProbe(foreground, IR, session.maxState)
    }

    /** All signal state belongs to this note, never to an object cache or the last render. */
    private class Session(
        val m: Map<String, Float>, midi: Int, val velocity: Float, seed: Int, val gesture: OhmGesture,
        val options: OhmOptions, val loopFrames: Int?, val onsetSeconds: Double,
        val cancelled: () -> Boolean = { false },
        val textureRevision: Int = 3,
    ) {
        private val held = loopFrames != null
        private val loopOs = (loopFrames ?: 1) * OS
        private val touch = m.getValue("TOUCH")
        private val body = m.getValue("BODY")
        private val length = m.getValue("LENGTH")
        private val nominal = Keys.midiHz(midi)
        private val periods = if (held) (nominal * loopFrames!! / RATE).roundToInt() else 0
        private val root = if (held) periods.toFloat() * RATE / loopFrames!! else nominal
        private val rootCycle = IR / root
        private val loopHz = floatArrayOf(root * (24 + 16 * touch), root * 24, root * 38)
        private val decay = floatArrayOf(if (held) .4f + 1.2f * length else .9f + 5.4f * length,
            .24f + 1.25f * length, .18f + length)
        private val membrane = Strings.Membrane(IR).also { it.tune(
            floatArrayOf(root * (1.6f + .32f * (1 - body)), root * (4.4f - body), root * (7.8f - 1.6f * body)),
            floatArrayOf(12f + 3 * body, 17f + 4 * body, 23f + 6 * body), floatArrayOf(.40f, .32f, .20f)) }
        private val bridge = Strings.Bridge(3, membrane)
        private val ceiling = guardedCoupling(root, decay[0], membrane)
        private val loops = Array(3) { j ->
            val hz = root * (j + 1)
            val tuning = Strings.tune(hz * .95f, loopHz[j], IR)
            Strings.Loop(tuning.n, tuning.a, feedback(hz, loopHz[j], decay[j]), loopHz[j], IR, cacheLoopFilter = true)
        }
        private val bow = if (options.exciter == OhmExciter.BOW) Strings.Bow(root * .95f, .16f,
            bridgeHz = minOf(6_000f, root * (10f + 8f * touch)), share = Strings.Bow.SHARE, rate = IR) else null
        private val primary = Strings.pluckExciter(Strings.tune(root, loopHz[0], IR).n, root,
            Dsp.expMap(touch, root * 2, minOf(root * 34, 10_000f)) * (.65f + .35f * velocity),
            .34f - .25f * touch, Dsp.seedFor("OHM", 3, midi, seed, "pluck"), IR, IR)
        private val glass = Strings.pluckExciter(Strings.tune(root * 3, loopHz[2], IR).n, root * 3,
            Dsp.expMap(touch, root * 6, minOf(root * 45, 14_000f)), .24f - .16f * touch,
            Dsp.seedFor("OHM", 3, midi, seed, "glass"), IR, IR)
        private val capture = options.capture?.samples()
        private val textures = OhmTextures(options, midi, seed, IR, if (held) loopOs else null,
            revision = textureRevision, rootDecaySeconds = decay[0])
        private val texture = FloatArray(2)
        private val reflected = FloatArray(3)
        private val back = FloatArray(3)
        private val cavity = Dsp.Biquad().also { it.peaking(root * (1.75f - .5f * body), -5f + 13 * body, .85f, IR) }
        private val formant = Dsp.Biquad().also { it.peaking(root * (4.8f - 1.5f * body), 5f - 10 * body, .9f, IR) }
        private val output = Dsp.FixedOnePole(minOf(10_000f, root * (14 - 4 * body)), IR)
        private val sourceGain = velocity.pow(.65f)
        private val finiteOnsets = if (gesture == OhmGesture.STEADY)
            doubleArrayOf(.12, .52, .96, 1.42, 1.92)
        else doubleArrayOf(.13, .28, .43, .58, .70).also { times ->
            for (i in times.indices) times[i] *= onsetSeconds
        }
        private var frame = 0
        private var activity = 0f
        private var agreement = 0f
        private var coupling = 0f
        private var observed = 0f
        private val targets = FloatArray(3) { Float.NaN }
        private val heldSource = if (held) FloatArray(loopOs) { dry(it, true) } else null
        var maxState = 0f
            private set

        init {
            if (heldSource != null) textures.prepareLoop(heldSource)
            bow?.let { b -> repeat(b.bridgeAge) { b.next(0f, 1.8f, 0f, 0f) } }
        }

        private fun dry(i: Int, repeat: Boolean): Float {
            val local = if (repeat) Math.floorMod(i, loopOs) else i
            val picked = if (local in primary.indices) (.18f + .30f * touch) * primary[local] else 0f
            val fundamental = if (local < rootCycle) (.045f - .017f * touch) * sin(2 * PI * local / rootCycle).toFloat() else 0f
            val carrier = if (repeat) .00045f * sin(2 * PI * periods * local / loopOs).toFloat() else 0f
            return when (options.exciter) {
                OhmExciter.PLUCK -> picked + fundamental + carrier
                OhmExciter.BOW -> carrier * 3f + picked * .15f + fundamental * .1f
                OhmExciter.CAPTURED -> {
                    val samples = capture!!
                    val position = local.toDouble() / OS
                    val index = position.toInt()
                    if (index + 1 < samples.size) {
                        val t = (position - index).toFloat()
                        val edge = minOf(1f, index / 64f, (samples.size - 1 - index) / 64f).coerceAtLeast(0f)
                        .12f * edge * (samples[index] + t * (samples[index + 1] - samples[index])) + carrier + fundamental
                    } else carrier + fundamental
                }
            }
        }

        fun advance(out: FloatArray, foregroundTap: FloatArray? = null) {
            require(foregroundTap == null || foregroundTap.size == out.size)
            for (i in out.indices) {
                if (frame % CHECK == 0) stop(cancelled)
                val time = frame.toDouble() / IR
                val progress = (time / onsetSeconds).coerceAtMost(1.0)
                if ((if (held) Math.floorMod(frame, loopOs) else frame) % 176 == 0) {
                    val relation = OhmV2.relationship(progress, m.getValue("MEDDLING"), m.getValue("COMMUNE"), gesture)
                    activity = relation.first; agreement = relation.second
                    coupling = ceiling * activity * agreement
                    val wanderTime = if (held) Math.floorMod(frame, loopOs).toDouble() / loopOs else time * .71
                    val cents = 14 * sin(2 * PI * wanderTime) * (1 - .97 * agreement)
                    for (j in 0..2) {
                        val hz = if (j == 1) (root * 2 * 2.0.pow(cents / 1200)).toFloat() else root * (j + 1)
                        val target = bridgeTuned(hz, coupling, membrane)
                        if (target != targets[j]) {
                            if (j == 0 && bow != null) bow.retune(target) else loops[j].retune(target)
                            targets[j] = target
                        }
                    }
                }
                val renewable = held && time >= onsetSeconds
                val dry = if (renewable) heldSource!![Math.floorMod(frame, loopOs)] else dry(frame, false)
                val release = if (held) 1f else (1 - smooth((time - onsetSeconds * .78) / .15)).toFloat()
                val attack = smooth(time / (.02 + .12 * (1 - touch))).toFloat()
                val bowSpeed = (.10f + .12f * touch) * (.5f + .5f * velocity) * attack * release
                val source = dry * release
                reflected[0] = bow?.atBridge() ?: loops[0].reflected()
                reflected[1] = loops[1].reflected(); reflected[2] = loops[2].reflected()
                val radiation = bridge.couple(reflected, coupling, back)
                textures.fill(frame, activity, agreement, observed, texture)
                observed = source
                val phase = if (held) periods.toDouble() * Math.floorMod(frame, loopOs) / loopOs else time * root
                // A finite contact must not leave a continuously powered octave after its
                // fundamental has decayed. The answering breaths are short physical drives;
                // only HOLD supplies continuous pressure. This also keeps captured key zones
                // on their root after the captured contact itself has finished.
                var breathEnvelope = if (held) attack.toDouble() else 0.0
                if (!held) for (onset in finiteOnsets) {
                    val age = (time - onset) / (.16 + .40 * length)
                    if (age > 0 && age < 1) breathEnvelope += smooth(age / (.34 - .26 * touch)) *
                        (1 - smooth((age - .40) / .60))
                }
                val breath = activity * (.00018f + .00022f * touch) * release * breathEnvelope.toFloat() *
                    (sin(4 * PI * phase) + (.025 + .20 * touch) * sin(8 * PI * phase)).toFloat()
                val local = if (renewable) Math.floorMod(frame, loopOs) else frame
                val glassAt = local - (if (held) .18 * loopOs else .35 * IR).toInt()
                val struck = if (glassAt in glass.indices) (.08f + .08f * touch) * activity * glass[glassAt] * release else 0f
                val foreground: Float
                if (bow != null) {
                    bow.toBridge(back[0] + source)
                    bow.next(bowSpeed, 5f - 4f * (.67f + .24f * touch), 0f, attack * release)
                    foreground = back[0] + source
                } else foreground = loops[0].inject(back[0] + source)
                // BowPoint lies inside the friction junction. A readout of back[0] alone
                // could change even if toBridge were accidentally disconnected from the bow.
                if (foregroundTap != null) foregroundTap[i] = if (bow != null) bow.bowPoint else foreground
                val whistle = loops[1].inject(back[1] + breath + texture[0] * release)
                val crystal = loops[2].inject(back[2] + struck + texture[1] * release)
                maxState = maxOf(maxState, abs(foreground), abs(whistle), abs(crystal))
                val mixed = foreground + (1 - .80f * agreement) * (.55f * whistle + .68f * crystal) + (.18f + .36f * agreement) * radiation
                val value = .75f * sourceGain * output.lp(formant.process(cavity.process(mixed)))
                check(value.isFinite() && maxState < 8f) { "OHM model $textureRevision lost internal headroom" }
                out[i] = value
                frame++
            }
        }
    }

    private fun bridgeTuned(hz: Float, coupling: Float, membrane: Strings.Membrane): Float {
        if (coupling == 0f) return hz
        val h = membrane.response(hz.toDouble()); val k = 2.0 * coupling / 3
        val phase = atan2(-k * h[1], 1.0 - k * h[0])
        return (hz * 2 * PI / (2 * PI + phase)).toFloat()
    }

    private fun guardedCoupling(root: Float, t60: Float, membrane: Strings.Membrane): Float {
        val h = membrane.response(root.toDouble()); val budget = .20 * LOG_1000 / (root * t60)
        fun cost(c: Double): Double { val k = 2 * c / 3; return -ln(hypot(1 - k * h[0], -k * h[1])) }
        if (cost(.10) <= budget) return .10f
        var low = 0.0; var high = .10
        repeat(24) { val mid = (low + high) / 2; if (cost(mid) <= budget) low = mid else high = mid }
        return low.toFloat()
    }

    private fun feedback(hz: Float, loopHz: Float, t60: Float): Float {
        val w = 2 * PI * hz / IR; val pole = exp(-2 * PI * loopHz / IR)
        val magnitude = (1 - pole) / sqrt(1 + pole * pole - 2 * pole * cos(w)) * cos(w / 2)
        return (exp(-LOG_1000 / (hz * t60)) / magnitude).toFloat().coerceAtMost(.9995f)
    }
}
