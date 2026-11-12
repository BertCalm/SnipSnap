package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One dry strike of a kit part: how hard ([velocity], 0..1), which take ([take], a fresh strike where the
 * engine allows one and a small move of its controls where it does not), and a pitch offset in semitones
 * ([tuneSemis], 0 for a plain hit; a hat roll that rises uses it). Returns raw audio at [Dsp.RATE].
 */
internal typealias Strike = (velocity: Float, take: Int, tuneSemis: Float) -> FloatArray

/**
 * The strike helpers a rendered kit family uses to make velocity zones and takes out of engines that do
 * not all read velocity or takes themselves. Lifted out of [GatecrashKits] when [CypherKits] needed the
 * same laws: a private helper copied between files is the roster's recurring defect.
 *
 * - An engine that strikes at a velocity and a take (SKIN's struck voices, THUMP's KICK and SUB, RIVET at a
 *   velocity) is asked to: [skin], [thumpKick], [sub], [rivet].
 * - A voice with no strike velocity is [touched]: velocity moves the controls a softer stick would move and
 *   a one-pole contact filter takes the top off (18 kHz at full velocity down to 7 kHz), and a take moves
 *   two or three controls a little, as a second strike lands elsewhere.
 * - Two strikes can be [layered] into one drum (a synth dive under an acoustic knock).
 *
 * Every number here is a shape value; GATECRASH's rounds heard them and the owner approved round 4.
 */
internal object KitStrikes {
    /** Takes sit at -1.5, -0.5, 0.5 and 1.5: four strikes around the authored one, none of them on it. */
    fun jitter(take: Int): Float = take - 1.5f

    /** How far one step of [jitter] moves a 0..1 control. At 0.02 a hat's pitch moves about a sixth of a semitone. */
    const val TAKE_STEP = .02f

    fun varied(m: Map<String, Float>, vary: List<String>, take: Int): Map<String, Float> {
        val out = m.toMutableMap()
        // Alternate the direction from control to control, so a take is not simply "everything up".
        for ((i, name) in vary.withIndex()) {
            val step = jitter(take) * TAKE_STEP * if (i % 2 == 0) 1f else -1f
            out[name] = ((out[name] ?: .5f) + step).coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * How many octaves a THUMP voice's TUNE spans, so a semitone offset can be written as a TUNE move
     * (`Dsp.expMap` is exponential, so the span is constant across the knob). Only the voices a phrase
     * retunes are listed; an unlisted voice ignores the offset.
     */
    private val THUMP_TUNE_OCTAVES: Map<ThumpVoice, Float> = mapOf(
        ThumpVoice.HAT_CLOSED to 0.954f, ThumpVoice.HAT_OPEN to 0.954f, // 320 to 620 Hz
        ThumpVoice.RIM to 0.737f,                                        // 420 to 700 Hz
    )

    private fun retuned(m: Map<String, Float>, tuneSemis: Float, tuneOctaves: Float): Map<String, Float> =
        if (tuneSemis == 0f || tuneOctaves <= 0f) m
        else m + ("TUNE" to ((m["TUNE"] ?: .5f) + tuneSemis / 12f / tuneOctaves).coerceIn(0f, 1f))

    /**
     * A voice whose engine has no strike velocity. The soft hit is built from what a softer stick does:
     * [soft] moves the named controls with velocity, and a softer contact loses its top (a one-pole falling
     * from 18 kHz to 7 kHz). A take moves the [vary] controls a little, as a second strike lands elsewhere.
     * [tuneOctaves] is the voice's TUNE span, for a phrase's pitch offset; 0 ignores the offset.
     */
    fun touched(
        render: (Map<String, Float>) -> FloatArray,
        base: Map<String, Float>,
        vary: List<String>,
        soft: (Float) -> Map<String, Float>,
        tuneOctaves: Float = 0f,
    ): Strike = { v, t, semis ->
        val x = render(retuned(varied(base + soft(v), vary, t), semis, tuneOctaves))
        val contact = Dsp.FixedOnePole(2_500f + 15_500f * v)
        FloatArray(x.size) { contact.lp(x[it]) }
    }

    fun thump(voice: ThumpVoice, base: Map<String, Float>, vary: List<String> = listOf("TUNE", "DECAY"),
              soft: (Float) -> Map<String, Float> = { emptyMap() }): Strike =
        touched({ Thump.render(voice, it).samples }, base, vary, soft, THUMP_TUNE_OCTAVES[voice] ?: 0f)

    fun skinTouched(voice: SkinVoice, base: Map<String, Float>, vary: List<String> = listOf("TUNE", "DECAY"),
                    soft: (Float) -> Map<String, Float> = { emptyMap() }): Strike =
        touched({ Skin.render(voice, it).samples }, base, vary, soft)

    /** SKIN's struck voices (kick, snare, tom) take velocity and take themselves: a real re-strike. */
    fun skin(voice: SkinVoice, vararg m: Pair<String, Float>): Strike =
        { v, t, _ -> Skin.render(voice, mapOf(*m), v, t).samples }

    /** THUMP's kick reads velocity itself (less pitch drop, a duller click, less drive). */
    fun thumpKick(vararg m: Pair<String, Float>): Strike =
        { v, t, _ -> Thump.render(ThumpVoice.KICK, varied(mapOf(*m), listOf("TUNE", "CLICK"), t), v).samples }

    /** THUMP's SUB reads velocity and the take itself ([Thump.SUB_TAKES]); the root never moves. */
    fun sub(vararg m: Pair<String, Float>): Strike =
        { v, t, _ -> Thump.render(ThumpVoice.SUB, mapOf(*m), v, t).samples }

    /** RIVET reads velocity as effort; a take moves its [vary] controls, since it has no take of its own. */
    fun rivet(voice: RivetVoice, base: Map<String, Float>, vary: List<String> = listOf("TUNE", "METAL")): Strike =
        { v, t, _ -> Rivet.render(voice, varied(base, vary, t), RIVET_MODEL, v).samples }

    /** The RIVET model the kits are printed with, pinned so a later RIVET cannot move a kit. */
    const val RIVET_MODEL = 2

    /**
     * ONSET's snare does not change with its velocity argument (measured: the same brightness to eight
     * figures at 0.3 and 1), so it is touched like THUMP's voices. The model version is pinned so a later
     * ONSET cannot move a kit.
     */
    fun onset(voice: OnsetVoice, base: Map<String, Float>, soft: (Float) -> Map<String, Float>): Strike =
        touched({ Onset.render(voice, it, ONSET_MODEL).samples }, base, listOf("TUNE", "SNAP", "EDGE"), soft)

    const val ONSET_MODEL = 4

    /**
     * Two strikes as one drum: each peak-normalised, summed at [weightA] and [weightB], then normalised
     * again. They share an onset, so the sum reads as one hit rather than two.
     */
    fun layered(a: Strike, weightA: Float, b: Strike, weightB: Float): Strike = { v, t, semis ->
        val x = normalised(a(v, t, semis))
        val y = normalised(b(v, t, semis))
        val out = FloatArray(max(x.size, y.size)) { i ->
            (if (i < x.size) x[i] * weightA else 0f) + (if (i < y.size) y[i] * weightB else 0f)
        }
        normalised(out)
    }

    private fun normalised(x: FloatArray): FloatArray {
        val peak = peakOf(x)
        return if (peak > 1e-9f) FloatArray(x.size) { x[it] / peak } else x
    }

    fun peakOf(x: FloatArray): Float {
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        return peak
    }

    /** A dry strike scaled to peak at [peak]. The engines peak-normalise every render, so level is set here. */
    fun struck(strike: Strike, velocity: Float, take: Int, peak: Float, tuneSemis: Float = 0f): FloatArray {
        val x = strike(velocity, take, tuneSemis)
        val k = peakOf(x).let { if (it > 1e-9f) peak / it else 0f }
        return FloatArray(x.size) { x[it] * k }
    }

    /**
     * A rendered kit pad's finish: remove the mean and anything under 20 Hz, apply the zone's [gain], never
     * boost past [ceiling], stop 80 dB under the peak 20 ms later, and fade the last 4 ms.
     */
    fun finish(x: FloatArray, gain: Float, ceiling: Float = .98f): Snip {
        check(x.all { it.isFinite() })
        val mean = x.average().toFloat()
        val dc = Dsp.FixedOnePole(20f)
        var peak = 0f
        for (i in x.indices) {
            val centred = x[i] - mean
            x[i] = (centred - dc.lp(centred)) * gain
            peak = max(peak, abs(x[i]))
        }
        // Safety only: another take may land a little hotter than the first. Never a boost.
        if (peak > ceiling) { val k = ceiling / peak; for (i in x.indices) x[i] *= k; peak = ceiling }
        // Stop 80 dB under the pad's peak, 20 ms later.
        var last = 0
        val floor = max(peak, 1e-6f) * 1e-4f
        for (i in x.indices) if (abs(x[i]) > floor) last = i
        val out = x.copyOf(min(x.size, max(last + (.02f * Dsp.RATE).toInt(), 2048)))
        Dsp.fadeTail(out)
        return Snip(out, 1, Dsp.RATE)
    }
}

/**
 * CYPHER: the hip-hop kit family's phrase model.
 *
 * A phrase is a list of notes on a beat grid, rendered as a dry sum of fresh strikes and then printed once.
 * Three things a note carries that GATECRASH's did not:
 *
 * - [Note.offsetMs]: the note lands early or late by an authored number of milliseconds. Swing, a backbeat
 *   behind the beat and an "unquantized" break are all tables of offsets, fixed by the kit version, because
 *   `render` receives no tempo and the MPC owns its own swing: the only place a kit can carry its feel is
 *   a phrase pad.
 * - [Note.tuneSemis]: a pitch offset in semitones, for a hat roll that rises. The part's strike decides
 *   whether it listens ([KitStrikes.touched]'s `tuneOctaves`).
 * - Notes on any grid: a beat is a double, so 1/32, triplets and 1/64 are ordinary notes.
 */
internal object Cypher {
    /** Bump when a change alters rendered audio: saved kits name the version they were printed with. */
    const val VERSION = 1

    /** One note of a phrase: where (beats), which part, how hard against the phrase's own velocity, and the feel. */
    class Note(val beat: Double, val part: Int, val weight: Float, val offsetMs: Float = 0f, val tuneSemis: Float = 0f) {
        init {
            require(beat >= 0.0 && weight in 0f..1f && offsetMs.isFinite() && abs(offsetMs) <= 60f && tuneSemis.isFinite() && abs(tuneSemis) <= 24f)
        }
    }

    /** Where a note starts, in frames: its beat at [bpm], moved by its offset, never before the pad's first frame. */
    fun startFrame(note: Note, bpm: Int, rate: Int = Dsp.RATE): Int =
        max(0, Math.round(note.beat * rate * 60.0 / bpm + note.offsetMs * rate / 1000.0).toInt())

    /** Every off-beat sixteenth late by [swing] of a sixteenth: 0 is straight, 0.14 is a sampler's "58 percent". */
    fun swung(notes: List<Note>, swing: Float, bpm: Int): List<Note> {
        val sixteenthMs = 60_000f / bpm / 4f
        return notes.map { n ->
            val step = Math.round(n.beat * 4).toInt()
            val offbeat = abs(n.beat * 4 - step) < 1e-6 && step % 2 == 1
            if (offbeat) Note(n.beat, n.part, n.weight, n.offsetMs + swing * sixteenthMs, n.tuneSemis) else n
        }
    }

    /**
     * A ratchet: [count] notes of [part] from [beat], one every [division] of a beat, the weight rising from
     * [weightFrom] to [weightTo] and the pitch from 0 to [riseSemis]. A hat roll, a snare build.
     */
    fun ratchet(part: Int, beat: Double, division: Double, count: Int, weightFrom: Float, weightTo: Float, riseSemis: Float = 0f): List<Note> =
        List(count) { i ->
            val p = if (count == 1) 1f else i.toFloat() / (count - 1)
            Note(beat + i * division, part, weightFrom + (weightTo - weightFrom) * p, tuneSemis = riseSemis * p)
        }

    /**
     * A dry phrase: every note is its own strike, so neighbouring notes are never the same audio (the take
     * walks), scaled to the phrase's velocity times the note's weight, placed at its offset start, summed.
     * [seconds] is the buffer length; a phrase with a long sub needs its tail.
     */
    fun phraseRaw(
        notes: List<Note>, bpm: Int, velocity: Float, take: Int, takes: Int, seconds: Float,
        touch: (Float) -> Float, strike: (part: Int) -> Strike,
    ): FloatArray {
        val out = FloatArray((seconds * Dsp.RATE).toInt())
        for ((i, note) in notes.withIndex()) {
            val v = (velocity * note.weight).coerceIn(.05f, 1f)
            val hit = KitStrikes.struck(strike(note.part), v, (take + i) % takes, touch(v), note.tuneSemis)
            val start = startFrame(note, bpm)
            for (j in 0 until min(hit.size, out.size - start)) out[start + j] += hit[j]
        }
        return out
    }
}
