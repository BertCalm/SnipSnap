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

    fun scramble(voice: VelvetVoice, random: Random): Map<String, Float> =
        macrosFor(voice).associate { it.name to random.nextFloat() }

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

    fun render(voice: VelvetVoice, macros: Map<String, Float> = emptyMap()): Snip {
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
        // grounds the stack. Detune is in cents-ish territory, never soup.
        val detune = Dsp.lin(fat, 1.0005f, 1.012f)
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

        val out = FloatArray((t60 * 1.4f * RATE).toInt().coerceAtLeast(64))
        val svf = Dsp.TptSvf()
        var p1 = 0.0
        var p2 = 0.0
        var pSub = 0.0
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            p1 += base / RATE
            p2 += base * detune / RATE
            pSub += base * 0.5 / RATE

            fun osc(phase: Double): Float =
                (1f - pulseMix) * saw(phase) + pulseMix * pulse(phase, width)

            val stack = 0.5f * (osc(p1) + osc(p2)) + subGain * pulse(pSub, 0.5f)

            // The filter envelope decays faster than the amp - the stab's
            // "wow" is the cutoff falling while the note still sounds.
            val fEnv = Dsp.envAt(t, t60 * 0.5f)
            val fc = floorHz + (peakHz - floorHz) * fEnv
            svf.process(stack, fc, damp)

            val attack = (t / 0.003f).coerceAtMost(1f)
            out[i] = svf.low * attack * Dsp.envAt(t, t60)
        }

        Dsp.normalize(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
