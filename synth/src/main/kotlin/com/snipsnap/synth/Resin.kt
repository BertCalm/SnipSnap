package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.pow
import kotlin.random.Random

/**
 * RESIN — the ladder engine.
 *
 * VELVET is subtractive through a state-variable filter; FATHOM slides and
 * beats; neither can make the sound this engine exists for, because that
 * sound *is* a filter: four one-poles in a row, the last fed back to the
 * first and soft-clipped inside the loop ([Dsp.Ladder]). Resonance thins
 * the bass on the way up and sings at the top, and the drive is the
 * filter's own input stage — there is no separate saturator to route
 * wrong.
 *
 * Three oscillators feed it: a saw at the note, a saw an octave down, and
 * a detuned square. STACK is their mixer on one knob (thin → deep → fat).
 * CUTOFF tracks the note, CONTOUR is the filter envelope's amount and
 * speed together (the wah, then the snap), CREAM is the feedback. TUNE
 * snaps to semitones. One-shot stabs onto pads, like every other engine.
 *
 * Design: docs/superpowers/specs/2026-09-24-resin-ladder-engine-design.md.
 */
enum class ResinVoice { BASS, LEAD, BRASS }

object Resin {

    const val TUNE_SEMITONES = 24

    /** A taste ceiling, not a stability one (`g < 1` for any finite fc); keeps the tuning error inside the measured range. */
    const val MAX_CUTOFF_HZ = 16_000f

    /**
     * Per-voice nudge on top of [Dsp.MELODIC_LOUDNESS_TARGET], zeroed out
     * awaiting a listening pass — a table edit here, not a refactor of
     * [render], same as every melodic engine.
     */
    private val LOUDNESS_OFFSET: Map<ResinVoice, Float> = ResinVoice.entries.associateWith { 0f }

    fun macrosFor(voice: ResinVoice): List<MacroSpec> = when (voice) {
        ResinVoice.BASS -> listOf(
            MacroSpec("TUNE", 0.3f), MacroSpec("STACK", 0.5f), MacroSpec("CUTOFF", 0.35f),
            MacroSpec("CREAM", 0.35f), MacroSpec("CONTOUR", 0.4f), MacroSpec("DECAY", 0.5f),
        )
        ResinVoice.LEAD -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("STACK", 0.8f), MacroSpec("CUTOFF", 0.55f),
            MacroSpec("CREAM", 0.5f), MacroSpec("CONTOUR", 0.5f), MacroSpec("DECAY", 0.45f),
        )
        ResinVoice.BRASS -> listOf(
            MacroSpec("TUNE", 0.4f), MacroSpec("STACK", 0.6f), MacroSpec("CUTOFF", 0.4f),
            MacroSpec("CREAM", 0.3f), MacroSpec("CONTOUR", 0.75f), MacroSpec("DECAY", 0.5f),
        )
    }

    fun defaults(voice: ResinVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /** SCRAMBLE near a preset; see [Thump.scramble] (docs/SYNTH_UPGRADE.md, U2). */
    fun scramble(voice: ResinVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + ResinPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /**
     * PLACEHOLDER awaiting the audition gate — how hard CUTOFF tracks the
     * note. Inherited from VELVET's constant of the same name, whose KDoc
     * has the measurement and the caveat; nothing here re-derives it.
     */
    internal const val CUTOFF_KEY_TRACK_AMOUNT = 0.6f

    /** The tuning centre of the voice's own TUNE range — see [Velvet.keyTrackReferenceHz]. */
    private fun keyTrackReferenceHz(voice: ResinVoice): Float = frequencyFor(voice, 0.5f)

    fun frequencyFor(voice: ResinVoice, tune: Float): Float {
        val root = when (voice) {
            ResinVoice.BASS -> 55f     // A1
            ResinVoice.LEAD -> 220f    // A3
            ResinVoice.BRASS -> 110f   // A2
        }
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return root * 2f.pow(semis / 12f)
    }

    /** CUTOFF's floor and ceiling per voice, Hz, before key tracking. */
    private fun cutoffRange(voice: ResinVoice): Pair<Float, Float> = when (voice) {
        ResinVoice.BASS -> 60f to 5_000f
        ResinVoice.LEAD -> 200f to 14_000f
        ResinVoice.BRASS -> 120f to 9_000f
    }

    /** Naive saw from a 0..1 phase. Aliases; the render is oversampled. */
    private fun saw(phase: Double): Float = (2.0 * (phase - Math.floor(phase)) - 1.0).toFloat()

    /** Naive square from a 0..1 phase. */
    private fun square(phase: Double): Float = if (phase - Math.floor(phase) < 0.5) 1f else -1f

    /**
     * STACK's mixer: osc 2 (the sub-octave saw) rises over the bottom half
     * of the knob, osc 3 (the detuned square) over the top half. Osc 1 is
     * always at 1. `internal` so a test can assert the curve rather than
     * infer it from a spectrum. PLACEHOLDER awaiting the audition gate:
     * authored from the DSP, never listened to.
     */
    internal fun stackGains(stack: Float): Pair<Float, Float> {
        val s = stack.coerceIn(0f, 1f)
        val g2 = 0.9f * (s * 2f).coerceIn(0f, 1f)
        val g3 = ((s - 0.4f) / 0.6f).coerceIn(0f, 1f)
        return g2 to g3
    }

    /**
     * The raw synth loop at whatever [rate] the caller wants — split out
     * of [render] so the oversampled dispatch can be tested against a
     * native-rate render (VelvetTest has the reasoning).
     */
    internal fun synthesize(voice: ResinVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val base = frequencyFor(voice, m.getValue("TUNE"))
        val stack = m.getValue("STACK")
        val cutoff = m.getValue("CUTOFF")
        val cream = m.getValue("CREAM")
        val contour = m.getValue("CONTOUR")
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.15f, 1.2f)

        // The stack is scaled to unity so how hard it hits the filter's
        // tanh does not change with STACK: the knob is a mixer, not a drive.
        val (g2, g3) = stackGains(stack)
        val mixScale = 1f / (1f + g2 + g3)
        // Width, not movement: no beat floor (VELVET's is for FAT, which promises motion).
        val detune = 2f.pow(Dsp.lin(stack, 3f, 14f) / 1200f)

        // Tracked to the note so brightness is an interval above the
        // fundamental, not a fixed Hz (Velvet.kt has the measurement).
        val (lo, hi) = cutoffRange(voice)
        val floorHz = Dsp.keyTrack(Dsp.expMap(cutoff, lo, hi), base, keyTrackReferenceHz(voice), CUTOFF_KEY_TRACK_AMOUNT)
        // CONTOUR is amount and speed together, the same one-knob device as
        // VELVET's SQUEEZE: a bigger sweep falls faster. In octaves, not Hz,
        // because a ladder sweep is heard in octaves. The sweep's T60 is at
        // most 0.6 of the note's, so it always lands before the note ends.
        // PLACEHOLDER awaiting the audition gate: the 0.6 -> 0.2 shape is taste.
        val octavesUp = Dsp.lin(contour, 0f, 4f)
        val contourT60 = t60 * Dsp.lin(contour, 0.6f, 0.2f)
        // The top of the knob is past the linear threshold: the filter sings.
        val resonance = Dsp.lin(cream, 0f, Dsp.Ladder.MAX_RESONANCE)

        val out = FloatArray((t60 * 1.4f * rate).toInt().coerceAtLeast(64))
        val ladder = Dsp.Ladder(rate)
        val env = Dsp.Env(attackSeconds = 0.003f, decay2T60 = t60)
        // Seeded per voice so the stack never opens phase-locked.
        val ph = Dsp.phases(3, Dsp.seedFor("RESIN", voice.name))
        var p1 = ph[0]
        var p2 = ph[1]
        var p3 = ph[2]
        for (i in out.indices) {
            val t = i.toFloat() / rate
            p1 += base / rate
            p2 += base * 0.5 / rate
            p3 += base * detune / rate
            val stackOut = mixScale * (saw(p1) + g2 * saw(p2) + g3 * square(p3))
            val fc = (floorHz * 2f.pow(octavesUp * Dsp.envAt(t, contourT60))).coerceAtMost(MAX_CUTOFF_HZ)
            out[i] = ladder.process(stackOut, fc, resonance) * env.at(t)
        }
        return out
    }

    fun render(voice: ResinVoice, macros: Map<String, Float> = emptyMap()): Snip {
        // U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE so the naive
        // oscillators' and the ladder's tanh harmonics fold down above
        // 22.05 kHz instead of into the audible band, then decimate.
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, macros, renderRate)
        val out = Dsp.decimate(raw, RATE)
        // Loudness, not peak (Dsp.MELODIC_LOUDNESS_TARGET's KDoc has the
        // measurement); the ladder's own 1/(1+r) passband loss is
        // restored here as loudness, never inside the filter.
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET + LOUDNESS_OFFSET.getValue(voice))
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
