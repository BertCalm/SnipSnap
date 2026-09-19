package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.pow
import kotlin.random.Random

/**
 * VELVET — subtractive synthesis, the playability king.
 *
 * Filter plus resonance is the most gratifying knob in synthesis and it's
 * nearly impossible to make an ugly sound: oscillators rich in harmonics,
 * a resonant low-pass, an envelope that opens and closes it. The oscillators
 * are naive saws and pulses — aliasing and all, lo-fi is on-brand — and the
 * filter is the Chamberlin SVF already in the toolbox.
 *
 * S-scope here is one-shot stabs onto pads (the key-patch side waits on
 * keygroup export, like every other engine). TUNE snaps to semitones.
 *
 * Macros are the roadmap's: SHAPE (saw ↔ pulse, with PWM in the pulse half),
 * FAT (detune spread of the unison pair), CUTOFF, SQUEEZE (resonance and
 * envelope amount moving together — one knob, always acid), DECAY.
 */
enum class VelvetVoice { BASS, BRASS, SQUELCH, CHIP }

object Velvet {

    const val TUNE_SEMITONES = 24

    /**
     * Per-voice nudge on top of [Dsp.MELODIC_LOUDNESS_TARGET], zeroed out
     * awaiting a listening pass (task-4-report.md) - a table edit here, not
     * a refactor of [render], whenever that pass decides BASS should sit
     * under BRASS or the like.
     */
    private val LOUDNESS_OFFSET: Map<VelvetVoice, Float> = VelvetVoice.entries.associateWith { 0f }

    fun macrosFor(voice: VelvetVoice): List<MacroSpec> = when (voice) {
        VelvetVoice.BASS -> listOf(
            MacroSpec("TUNE", 0.3f), MacroSpec("SHAPE", 0.2f), MacroSpec("FAT", 0.3f),
            MacroSpec("CUTOFF", 0.35f), MacroSpec("SQUEEZE", 0.4f), MacroSpec("DECAY", 0.45f),
        )
        VelvetVoice.BRASS -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("SHAPE", 0.1f), MacroSpec("FAT", 0.6f),
            MacroSpec("CUTOFF", 0.55f), MacroSpec("SQUEEZE", 0.3f), MacroSpec("DECAY", 0.5f),
        )
        VelvetVoice.SQUELCH -> listOf(
            MacroSpec("TUNE", 0.4f), MacroSpec("SHAPE", 0.35f), MacroSpec("FAT", 0.2f),
            MacroSpec("CUTOFF", 0.3f), MacroSpec("SQUEEZE", 0.85f), MacroSpec("DECAY", 0.4f),
        )
        VelvetVoice.CHIP -> listOf(
            MacroSpec("TUNE", 0.6f), MacroSpec("SHAPE", 0.9f), MacroSpec("FAT", 0.1f),
            MacroSpec("CUTOFF", 0.9f), MacroSpec("SQUEEZE", 0.1f), MacroSpec("DECAY", 0.35f),
        )
    }

    fun defaults(voice: VelvetVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /** SCRAMBLE near a preset; see [Thump.scramble] (docs/SYNTH_UPGRADE.md, U2). */
    fun scramble(voice: VelvetVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + VelvetPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    fun frequencyFor(voice: VelvetVoice, tune: Float): Float {
        val root = when (voice) {
            VelvetVoice.BASS -> 55f
            VelvetVoice.BRASS -> 110f
            VelvetVoice.SQUELCH -> 82.4f
            VelvetVoice.CHIP -> 220f
        }
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return root * 2f.pow(semis / 12f)
    }

    /** Naive saw from a 0..1 phase. Aliases; that's the charm. */
    private fun saw(phase: Double): Float = (2.0 * (phase - Math.floor(phase)) - 1.0).toFloat()

    /** Naive pulse with settable width. */
    private fun pulse(phase: Double, width: Float): Float =
        if (phase - Math.floor(phase) < width) 1f else -1f

    /**
     * FAT's actual detune ratio for a note of fundamental [baseHz], macro
     * value [fat], and [t60] decay. [Dsp.minBeatDetune] raises a too-slow
     * beat toward audibility, but it is clamped to the macro's own ceiling
     * ([askedHi]) here: completing even a fraction of a beat cycle inside
     * a short bass note already takes more detune than FAT is ever allowed
     * to ask for on its own, and beyond that ceiling it reads as an
     * out-of-tune interval, not width. The floor can lift FAT's bottom; it
     * must never be able to override FAT's top.
     *
     * BASS is the canary for this: it's the lowest voice, so the same
     * [Dsp.minBeatDetune] floor sits closest to [askedHi] there of all four
     * VELVET voices (low baseHz means a given `cycles` needs proportionally
     * more detune to cover). At its factory DECAY, BASS's floor only fully
     * swallows FAT's own range around `cycles >= ~0.465` - see the
     * regression test in VelvetTest for the exact number. If `cycles` ever
     * moves, BASS is where the macro goes quiet first.
     */
    internal fun detuneFor(baseHz: Float, fat: Float, t60: Float): Float {
        val askedLo = 1.0005f
        val askedHi = 1.012f
        val asked = Dsp.lin(fat, askedLo, askedHi)
        val floor = Dsp.minBeatDetune(baseHz = baseHz, seconds = t60 * 1.4f).coerceAtMost(askedHi)
        return maxOf(asked, floor)
    }

    /**
     * The raw synth loop, at whatever [rate] the caller wants - split out of
     * [render] so U6's oversampled dispatch (docs/SYNTH_UPGRADE.md) can be
     * tested directly against a native-rate render, rather than trusting
     * that reading [render]'s own source matches what it actually does.
     */
    internal fun synthesize(voice: VelvetVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val base = frequencyFor(voice, m.getValue("TUNE"))
        val shape = m.getValue("SHAPE")
        val fat = m.getValue("FAT")
        val cutoff = m.getValue("CUTOFF")
        val squeeze = m.getValue("SQUEEZE")
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.15f, 0.9f)

        // SHAPE's lower half is all saw; the upper half crossfades to pulse
        // and then narrows it — one knob walking saw, square, and PWM nasal.
        val pulseMix = ((shape - 0.5f) * 2f).coerceIn(0f, 1f)
        val width = Dsp.lin(((shape - 0.75f) * 4f).coerceIn(0f, 1f), 0.5f, 0.12f)

        // FAT spreads the unison pair; a fixed sub square an octave down
        // grounds the stack. detuneFor is split out of this function so a
        // test can assert the macro's own range survives the beat floor
        // (mirrors why synthesize itself is internal, above: test the code
        // that actually runs, not a reimplementation of it).
        val detune = detuneFor(base, fat, t60)
        val subGain = Dsp.lin(fat, 0.15f, 0.45f)

        // SQUEEZE is resonance and filter-envelope amount together: at the
        // top, the SVF rings and the envelope sweeps it — instant acid.
        // The trapezoidal SVF is stable to Nyquist (unlike the Chamberlin
        // that used to cap this filter at 5.2 kHz), so CUTOFF finally opens
        // all the way.
        val damp = Dsp.lin(squeeze, 1.8f, 0.3f)
        val envAmount = Dsp.lin(squeeze, 0.3f, 1f)
        val floorHz = Dsp.expMap(cutoff, 180f, 12_000f)
        val peakHz = (floorHz * Dsp.lin(envAmount, 1.5f, 6f)).coerceAtMost(16_000f)

        val raw = FloatArray((t60 * 1.4f * rate).toInt().coerceAtLeast(64))
        // The SVF's own cutoff/damping math is rate-relative (Dsp.TptSvf's
        // trapezoidal coefficient depends on fc/rate), so it needs the same
        // rate passed in, not just the oscillators.
        val svf = Dsp.TptSvf(rate)
        val env = Dsp.Env(attackSeconds = 0.003f, decay2T60 = t60)
        // Seeded per voice so the detuned pair no longer opens phase-locked
        // — every attack used to be the same coherent transient.
        val ph = Dsp.phases(3, Dsp.seedFor("VELVET", voice.name))
        var p1 = ph[0]
        var p2 = ph[1]
        var pSub = ph[2]
        for (i in raw.indices) {
            val t = i.toFloat() / rate
            p1 += base / rate
            p2 += base * detune / rate
            pSub += base * 0.5 / rate

            fun osc(phase: Double): Float =
                (1f - pulseMix) * saw(phase) + pulseMix * pulse(phase, width)

            val stack = 0.5f * (osc(p1) + osc(p2)) + subGain * pulse(pSub, 0.5f)

            // The filter envelope decays faster than the amp - the stab's
            // "wow" is the cutoff falling while the note still sounds.
            val fEnv = Dsp.envAt(t, t60 * 0.5f)
            val fc = floorHz + (peakHz - floorHz) * fEnv
            // SQUEEZE is the acid knob, so the filter self-limits instead
            // of ringing cleanly at the top of its own travel (U5).
            svf.process(stack, fc, damp, saturate = true)

            raw[i] = svf.low * env.at(t)
        }
        return raw
    }

    fun render(voice: VelvetVoice, macros: Map<String, Float> = emptyMap()): Snip {
        // U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE so the naive saw/
        // pulse oscillators' own aliasing folds down above 22.05kHz instead
        // of into the audible band, then Dsp.decimate brings it back to
        // RATE.
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, macros, renderRate)
        val out = Dsp.decimate(raw, RATE)
        // Loudness, not peak: a sine-heavy voice at equal peak reads quieter
        // (Dsp.MELODIC_LOUDNESS_TARGET's doc comment has the measurement).
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET + LOUDNESS_OFFSET.getValue(voice))
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
