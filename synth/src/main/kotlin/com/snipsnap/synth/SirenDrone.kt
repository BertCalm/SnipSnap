package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * SIREN, droning: the wail as a loop-grid track, its own motion snapped to
 * whole cycles of the loop the way [Siren.renderLoop] snaps to whole cycles
 * of itself (docs/superpowers/specs/2026-09-27-siren-dub-engine-design.md,
 * door 4).
 *
 * Unlike [ResinDrone], which invents MOTION and RATE because RESIN's own
 * held macros have no swing to carry over, SIREN already *is* a movement:
 * DEPTH and RATE are the patch's own swing and speed, so the drone plays
 * them as they are — RATE's own Hz is what gets snapped, onto the nearest
 * whole number of cycles the grid's loop can hold, the same "further is
 * longer" spirit [DroneFit] already applies to pitch. TUNE, HOLD and SWEEP
 * have no note-on to act on here and are ignored (TUNE is replaced by
 * [rootMidi], the same substitution [ResinDrone] makes).
 *
 * A separate loop from [Siren.synthesizeLoopStretch], sharing only
 * [Siren.lfoAt] and [Siren.square]: the held and one-shot renders must not
 * move by a bit, and this one answers to the grid's own frame count, not
 * [Siren.loopFrames]'s.
 */
object SirenDrone {

    /** The macros a drone hears: SIREN's own swing and speed and tone, unlike RESIN's which has none of its own. CONTOUR and DECAY have no equivalent to exclude; TUNE, HOLD and SWEEP do (TUNE is replaced by root; HOLD and SWEEP have no note-on). */
    val SOUNDING_MACROS = listOf("RATE", "DEPTH", "GRIT")

    /** Frames past the loop's end, so the decimator's edge never reaches what's kept — [ResinDrone]'s own margin. */
    private const val TAIL_FRAMES = 256

    /**
     * Before the loop starts: long enough for the de-zippered LFO and the
     * GRIT one-pole to reach their periodic steady state. Both are single
     * one-pole filters at time constants under a millisecond even at GRIT's
     * darkest ([Siren.LFO_SMOOTH_SECONDS], and [Siren.TONE_LOW_HZ]'s own
     * 1.2 kHz corner), so — unlike [ResinDrone.DRONE_PREROLL_SECONDS]'s
     * resonant ladder, measured in whole seconds — a tenth of a second is
     * many hundreds of time constants and the settle is complete well
     * inside it.
     */
    private const val PREROLL_SECONDS = 0.1f

    /** How often, in oversampled samples, a render asks whether it is still wanted: [Siren]'s own cadence for a held zone. */
    private const val CANCEL_CHECK_SAMPLES = 1 shl 15

    /**
     * A drone's recipe: the patch (its swing, speed and tone; CONTOUR,
     * DECAY, TUNE, HOLD and SWEEP have nothing here to act on), kept to
     * only what sounds so two drones differing solely in a macro the drone
     * ignores render once, not twice.
     */
    data class Spec(val voice: SirenVoice, val macros: Map<String, Float>) {
        fun toJson(): JsonValue = JsonValue.Obj(
            linkedMapOf(
                "engine" to JsonValue.Str(SirenPatch.ENGINE),
                "voice" to JsonValue.Str(voice.name),
                "macros" to JsonValue.Obj(macros.toSortedMap().mapValues { JsonValue.Num(it.value.toDouble()) }),
            ),
        )

        companion object {
            /** A spec from [toJson]'s shape, or null for another engine's recipe or a shape this can't read. */
            fun fromJson(value: JsonValue): Spec? = runCatching {
                val o = value.obj()
                if (o["engine"]?.str() != SirenPatch.ENGINE) return null
                val voice = SirenVoice.entries.firstOrNull { it.name == o["voice"]?.str() } ?: return null
                val macros = o["macros"]?.obj().orEmpty().mapValues { it.value.num().toFloat() }
                Spec(voice, macros)
            }.getOrNull()
        }
    }

    /** [Siren.Lfo]'s own shape, run again here: that class is private to Siren.kt, and a drone runs the identical de-zippered LFO over its own loop — phase from a whole cycle count over the grid's own frame count, rather than a continuous Hz over [Siren.loopFrames]'s. */
    private class SmoothedLfo(private val voice: SirenVoice, private val stepPerFrame: Double, rate: Int) {
        private val k = (1.0 - exp(-1.0 / (Siren.LFO_SMOOTH_SECONDS * rate))).toFloat()
        private var phase = 0.0
        private var smooth = Siren.lfoAt(voice, 0f)
        fun next(): Float {
            val raw = Siren.lfoAt(voice, phase.toFloat())
            smooth += k * (raw - smooth)
            phase += stepPerFrame
            if (phase >= 1.0) phase -= 1.0
            return smooth
        }
    }

    /**
     * The drone: mono, exactly [frames] long at [sampleRate], periodic with
     * period [frames]. Levelled to the melodic loudness target, the way a
     * held pad is.
     *
     * [cancelled] is asked every [CANCEL_CHECK_SAMPLES] samples, and so is
     * the thread's interrupt flag — [ResinDrone.render]'s own contract.
     */
    fun render(
        spec: Spec,
        rootMidi: Int,
        frames: Long,
        sampleRate: Int,
        cancelled: () -> Boolean = { false },
    ): FloatArray {
        val out = synthesize(spec, rootMidi, frames, sampleRate, cancelled = cancelled)
        val loud = Loudness.of(Snip(out, channels = 1, sampleRate = sampleRate))
        if (loud > 1e-6f) {
            val g = Dsp.MELODIC_LOUDNESS_TARGET / loud
            for (i in out.indices) out[i] *= g
        }
        Dsp.limitPeak(out, 0.99f)
        return out
    }

    /** [fitCarrier]'s own answer: the whole cycle count over one loop, and the base carrier Hz that gives it — [Siren.planLoop]'s `LoopPlan`, without the frame/period bookkeeping that only means something against [Siren.loopFrames]'s own duration choice. */
    internal data class CarrierFit(val cycles: Long, val baseHz: Double, val stepPerFrame: Double)

    /**
     * The carrier's own snap: [Siren.planLoop]'s trick — move the base
     * pitch so the pulse completes a whole number of cycles over one loop —
     * run at [frames] rather than [Siren.loopFrames]'s own RATE-derived
     * length, since the grid's own tempo picks the length here. Not
     * [DroneFit]'s own model: that assumes RESIN's even-only sub-octave
     * snap and pure pitch arithmetic, while SIREN's own cycle count allows
     * any whole number and depends on DEPTH through the LFO's own phase
     * integral, so a caller wanting SIREN's true nudge (`spanFor`-style
     * span choice, the readout) must run this, not [DroneFit.nudgeCents].
     *
     * [cancelled] reaches this pass too: an 8-interval drone's own loop can
     * itself be millions of oversampled samples before a caller's audio
     * loop, if any, even starts.
     */
    internal fun fitCarrier(
        voice: SirenVoice,
        macros: Map<String, Float>,
        rootMidi: Int,
        frames: Long,
        sampleRate: Int,
        prerollSeconds: Float = PREROLL_SECONDS,
        cancelled: () -> Boolean = { false },
    ): CarrierFit {
        require(frames > 0 && sampleRate > 0) { "a drone needs a length and a rate: $frames frames at $sampleRate Hz" }
        require(rootMidi in 0..127) { "root out of MIDI range: $rootMidi" }
        val m = Siren.defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val depth = Siren.depthSemitones(m.getValue("DEPTH")) / 12.0

        val os = Dsp.OVERSAMPLE
        val rate = sampleRate * os
        val loopOs = frames * os
        // RATE's own Hz, snapped onto the nearest whole number of cycles the
        // loop can hold, at least one — the same "further is longer" snap
        // DroneFit already applies to pitch, applied here to speed.
        val desiredHz = Siren.rateHz(m.getValue("RATE"))
        val cyclesPerLoop = Math.round(desiredHz * frames.toDouble() / sampleRate).coerceAtLeast(1)
        val stepPerFrame = cyclesPerLoop.toDouble() / loopOs

        val rootHz = 440.0 * 2.0.pow((rootMidi - 69) / 12.0)
        val lfo = SmoothedLfo(voice, stepPerFrame, rate)
        var asked = 0
        fun checkCancelled() {
            if (asked % CANCEL_CHECK_SAMPLES == 0 && (cancelled() || Thread.currentThread().isInterrupted)) {
                throw CancellationException("SIREN drone plan no longer wanted")
            }
            asked++
        }
        val pre = (prerollSeconds * sampleRate).toLong()
        repeat((pre * os).toInt()) { checkCancelled(); lfo.next() }
        var g = 0.0
        repeat(loopOs.toInt()) { checkCancelled(); g += 2.0.pow(depth * lfo.next()) / rate }
        val cycles = Math.round(rootHz * g)
        return CarrierFit(cycles, cycles / g, stepPerFrame)
    }

    /**
     * How far off [rootMidi] a drone of [frames] at [sampleRate] actually
     * lands — [DroneFit.nudgeCents]'s own shape, but read off SIREN's own
     * [fitCarrier] rather than RESIN's pure-pitch snap. [cancelled] reaches
     * [fitCarrier]'s own check: a span search (`SirenDroneMaker.span`) can
     * run this several times over, each a real integral, from a UI thread's
     * own coroutine — a rapid run of taps must be able to stop one after
     * another rather than piling up on the pool that runs them.
     */
    fun nudgeCents(
        voice: SirenVoice,
        macros: Map<String, Float>,
        rootMidi: Int,
        frames: Long,
        sampleRate: Int,
        cancelled: () -> Boolean = { false },
    ): Double {
        val fit = fitCarrier(voice, macros, rootMidi, frames, sampleRate, cancelled = cancelled)
        val rootHz = 440.0 * 2.0.pow((rootMidi - 69) / 12.0)
        return 1200.0 * ln(fit.baseHz / rootHz) / ln(2.0)
    }

    /**
     * The unlevelled drone, [periods] loops of [frames] after the pre-roll —
     * [ResinDrone.synthesize]'s own shape.
     */
    internal fun synthesize(
        spec: Spec,
        rootMidi: Int,
        frames: Long,
        sampleRate: Int,
        periods: Int = 1,
        prerollSeconds: Float = PREROLL_SECONDS,
        cancelled: () -> Boolean = { false },
    ): FloatArray {
        require(periods >= 1) { "periods: $periods" }
        val m = Siren.defaults(spec.voice).toMutableMap()
        for ((k, v) in spec.macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val depth = Siren.depthSemitones(m.getValue("DEPTH")) / 12.0
        val grit = m.getValue("GRIT")

        val os = Dsp.OVERSAMPLE
        val rate = sampleRate * os

        val fit = fitCarrier(spec.voice, spec.macros, rootMidi, frames, sampleRate, prerollSeconds, cancelled)
        val baseHz = fit.baseHz

        val pre = (prerollSeconds * sampleRate).toLong()
        val total = pre + periods * frames + TAIL_FRAMES
        require(total * os <= Int.MAX_VALUE) { "a drone of $frames frames is too long to render" }

        val lfo = SmoothedLfo(spec.voice, fit.stepPerFrame, rate)
        val cutoff = Dsp.expMap(grit, Siren.TONE_LOW_HZ, Siren.TONE_HIGH_HZ)
        val a = (1.0 - exp(-2.0 * PI * cutoff / rate)).toFloat()
        val drive = grit * Siren.DRIVE_MAX
        var state = 0f
        var phase = 0.0
        val raw = FloatArray((total * os).toInt())
        for (i in raw.indices) {
            if (i % CANCEL_CHECK_SAMPLES == 0 && (cancelled() || Thread.currentThread().isInterrupted)) {
                throw CancellationException("SIREN drone render no longer wanted")
            }
            val hz = (baseHz * 2.0.pow(depth * lfo.next())).coerceAtMost(Siren.PITCH_CEILING_HZ.toDouble())
            val dt = hz / rate
            val x = Siren.square(phase, dt)
            phase += dt
            if (phase >= 1.0) phase -= 1.0
            state += a * (x - state)
            raw[i] = Dsp.drive(state, drive)
        }
        Tide.bandLimit(raw, rate)
        val out = Dsp.decimate(raw, sampleRate)
        return out.copyOfRange(pre.toInt(), (pre + periods * frames).toInt())
    }
}
