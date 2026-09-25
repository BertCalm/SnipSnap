package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * RESIN, droning: one long note breathing through the ladder, rendered to a
 * loop that closes on whole cycles of everything in it
 * (docs/superpowers/specs/2026-09-25-resin-drone-design.md).
 *
 * A separate loop from [Resin.synthesize], sharing only its helpers, so the
 * one-shot and held renders cannot move a bit. Every moving part completes
 * a whole number of cycles in the loop: the saws because the note is
 * snapped (the same rounding as the grid's `DroneFit.snappedHz`, so the
 * note the grid reads out is the note that plays), the square because its
 * cycle count is snapped too, and the filter's breathing because RATE is a
 * whole number of breaths. Phases come from the sample index modulo the
 * loop, not an accumulator, so every period recomputes the same values; a
 * pre-roll ([DRONE_PREROLL_SECONDS]) lets the ladder land on its periodic
 * orbit, which it does exactly (0.0 period to period). No crossfade: a
 * drone that does not close is a failing test.
 *
 * Rendered at the session's own rate: resampling a seamless loop does not
 * keep a whole-frame length.
 */
object ResinDrone {

    /**
     * Long enough for the ladder to land on its periodic orbit, measured.
     * The spec's probe said 1.0 s, but its loop started wherever the pre-roll
     * left the breath; with the loop starting at the breath's zero, where
     * the grid's bar line is, the darkest full-MOTION corners were still
     * 2e-7 apart period to period at 1.0 s, 7e-15 at 1.5 s, and exactly 0
     * at 2.0 s on every corner at 44.1 and 48 kHz.
     */
    const val DRONE_PREROLL_SECONDS = 2.0f

    /** MOTION at 1 swings CUTOFF this many octaves either way. */
    const val MOTION_MAX_OCTAVES = 2f

    /** Breaths per drone. Whole, because a fractional breath is what would make the wrap click. */
    val RATES = listOf(1, 2, 4)

    /** Frames past the cut, so the decimator's edge never reaches the kept loop. */
    private const val TAIL_FRAMES = 256

    /**
     * How often, in oversampled samples, a render asks whether it is still
     * wanted: about every 0.2 s of audio at 44.1 kHz, so a stale render
     * stops within a few milliseconds of CPU, and the check costs nothing
     * against the ladder it interrupts.
     */
    private const val CANCEL_CHECK_SAMPLES = 1 shl 15

    /**
     * A drone's recipe: the patch (CONTOUR, DECAY and TUNE have no note-on
     * to act on and are ignored; ROOT replaces TUNE and lives outside, with
     * the grid), how far the filter breathes, and how many times per drone.
     */
    data class Spec(val voice: ResinVoice, val macros: Map<String, Float>, val motion: Float, val rate: Int) {
        init {
            require(motion in 0f..1f) { "MOTION wants 0..1, got $motion" }
            require(rate in RATES) { "RATE wants one of $RATES breaths, got $rate" }
        }

        fun toJson(): JsonValue = JsonValue.Obj(
            linkedMapOf(
                "engine" to JsonValue.Str(ResinPatch.ENGINE),
                "voice" to JsonValue.Str(voice.name),
                "macros" to JsonValue.Obj(macros.toSortedMap().mapValues { JsonValue.Num(it.value.toDouble()) }),
                "motion" to JsonValue.Num(motion.toDouble()),
                "rate" to JsonValue.Num(rate.toDouble()),
            ),
        )

        companion object {
            /** A spec from [toJson]'s shape, or null for another engine's recipe or a shape this can't read. */
            fun fromJson(value: JsonValue): Spec? = runCatching {
                val o = value.obj()
                if (o["engine"]?.str() != ResinPatch.ENGINE) return null
                val voice = ResinVoice.entries.firstOrNull { it.name == o["voice"]?.str() } ?: return null
                val macros = o["macros"]?.obj().orEmpty().mapValues { it.value.num().toFloat() }
                Spec(voice, macros, o["motion"]?.num()?.toFloat() ?: return null, o["rate"]?.int() ?: return null)
            }.getOrNull()
        }
    }

    private fun saw(phase: Double): Float = (2.0 * (phase - floor(phase)) - 1.0).toFloat()

    private fun square(phase: Double): Float = if (phase - floor(phase) < 0.5) 1f else -1f

    /**
     * The drone: mono, exactly [frames] long at [sampleRate], and periodic
     * with period [frames], so playing it end to end has no seam. Levelled
     * on the loop to the melodic loudness target, the way a held pad is.
     *
     * [cancelled] is asked every [CANCEL_CHECK_SAMPLES] samples, and so is
     * the thread's interrupt flag. Either stops the render with a
     * [CancellationException]: a drone takes seconds, and one rendered for a
     * tempo the grid has already left would hold a thread and tens of MB for
     * nothing.
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

    /**
     * The unlevelled drone, [periods] loops of [frames] after the pre-roll.
     * One period is what [render] keeps; more are how a test sees the loop
     * repeat, since a render of twice the length is a different loop (its
     * note snaps to a finer grid).
     */
    internal fun synthesize(
        spec: Spec,
        rootMidi: Int,
        frames: Long,
        sampleRate: Int,
        periods: Int = 1,
        prerollSeconds: Float = DRONE_PREROLL_SECONDS,
        cancelled: () -> Boolean = { false },
    ): FloatArray {
        require(frames > 0 && sampleRate > 0) { "a drone needs a length and a rate: $frames frames at $sampleRate Hz" }
        require(periods >= 1) { "periods: $periods" }
        require(rootMidi in 0..127) { "root out of MIDI range: $rootMidi" }
        val m = Resin.defaults(spec.voice).toMutableMap()
        for ((k, v) in spec.macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val stack = m.getValue("STACK")
        val cutoff = m.getValue("CUTOFF")
        val cream = m.getValue("CREAM")

        val os = Dsp.OVERSAMPLE
        val seconds = frames.toDouble() / sampleRate
        // The snap: whole sub-octave cycles, the same rounding as DroneFit.snappedHz.
        val rootHz = 440.0 * 2.0.pow((rootMidi - 69) / 12.0)
        val subCycles = Math.round(rootHz / 2 * seconds).coerceAtLeast(1)
        val hz = 2.0 * subCycles / seconds
        // The square snapped to whole cycles too, at least one beat against the note.
        val detune = 2.0.pow(Dsp.lin(stack, 3f, 14f) / 1200.0)
        val squareCycles = Math.round(hz * detune * seconds).coerceAtLeast(2 * subCycles + 1)

        val (g2, g3) = Resin.stackGains(stack)
        val mixScale = 1f / (1f + g2 + g3)
        val (lo, hi) = Resin.cutoffRange(spec.voice)
        val floorHz = Dsp.keyTrack(
            Dsp.expMap(cutoff, lo, hi), hz.toFloat(), Resin.frequencyFor(spec.voice, 0.5f), Resin.CUTOFF_KEY_TRACK_AMOUNT,
        ).toDouble()
        val resonance = Resin.resonanceFor(cream, held = true)
        val swing = spec.motion * MOTION_MAX_OCTAVES

        val pre = (prerollSeconds * sampleRate).toLong()
        val total = pre + periods * frames + TAIL_FRAMES
        require(total * os <= Int.MAX_VALUE) { "a drone of $frames frames is too long to render" }
        val loopOs = frames * os
        val raw = FloatArray((total * os).toInt())
        val ladder = Dsp.Ladder(sampleRate * os)
        for (i in raw.indices) {
            if (i % CANCEL_CHECK_SAMPLES == 0 && (cancelled() || Thread.currentThread().isInterrupted)) {
                throw CancellationException("drone render no longer wanted")
            }
            // The loop starts after the pre-roll; any offset would do, since
            // everything below repeats every loopOs samples.
            val k = (i.toLong() - pre * os).mod(loopOs).toDouble()
            val stackOut = mixScale * (
                saw(k * (2 * subCycles) / loopOs) +
                    g2 * saw(k * subCycles / loopOs) +
                    g3 * square(k * squareCycles / loopOs)
                )
            val lfo = sin(2.0 * PI * spec.rate * k / loopOs)
            val fc = (floorHz * 2.0.pow(swing * lfo)).toFloat().coerceAtMost(Resin.MAX_CUTOFF_HZ)
            raw[i] = ladder.process(stackOut, fc, resonance)
        }
        return Dsp.decimate(raw, sampleRate).copyOfRange(pre.toInt(), (pre + periods * frames).toInt())
    }
}
