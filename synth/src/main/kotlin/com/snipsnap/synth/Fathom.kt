package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * FATHOM — the bass engine.
 *
 * The other engines already cover most of what "bass" usually means: VELVET's
 * BASS voice has a sub oscillator, SQUELCH is resonance-and-envelope acid, and
 * a THUMP kick with a long decay is a boom. FATHOM exists for the three things
 * none of them can do — a pitch envelope travelling *between* notes, two
 * detuned oscillators beating over a tail long enough to hear it, and FM tuned
 * for the bottom rather than for percussive bite.
 *
 * Every voice runs the same path: source → DRIVE → resonant low-pass → amp
 * envelope. Drive sits **before** the filter deliberately. Saturation makes
 * harmonics and the filter has to be downstream to shape them; running an FX
 * distortion after the filter is why that combination sounds like a blanket.
 *
 * GLIDE is on every voice rather than being one voice's trick, because a slide
 * is a performance gesture, not a timbre.
 */
enum class FathomVoice { DEEP, GRIND, GLASS }

object Fathom {

    const val TUNE_SEMITONES = 24

    fun macrosFor(voice: FathomVoice): List<MacroSpec> = when (voice) {
        FathomVoice.DEEP -> listOf(
            MacroSpec("TUNE", 0.25f), MacroSpec("GLIDE", 0f), MacroSpec("DRIVE", 0.3f),
            MacroSpec("CUTOFF", 0.4f), MacroSpec("DECAY", 0.55f), MacroSpec("SWEEP", 0.35f),
        )
        FathomVoice.GRIND -> listOf(
            MacroSpec("TUNE", 0.3f), MacroSpec("GLIDE", 0f), MacroSpec("DRIVE", 0.45f),
            MacroSpec("CUTOFF", 0.35f), MacroSpec("DECAY", 0.7f), MacroSpec("SPREAD", 0.4f),
        )
        FathomVoice.GLASS -> listOf(
            MacroSpec("TUNE", 0.35f), MacroSpec("GLIDE", 0f), MacroSpec("DRIVE", 0.4f),
            MacroSpec("CUTOFF", 0.5f), MacroSpec("DECAY", 0.45f), MacroSpec("RATIO", 0.25f),
        )
    }

    fun defaults(voice: FathomVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun scramble(voice: FathomVoice, random: Random): Map<String, Float> =
        macrosFor(voice).associate { it.name to random.nextFloat() }

    fun frequencyFor(voice: FathomVoice, tune: Float): Float {
        val root = when (voice) {
            FathomVoice.DEEP -> 41.2f    // E1 — low enough to feel
            FathomVoice.GRIND -> 55f     // A1
            FathomVoice.GLASS -> 55f     // A1
        }
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return root * 2f.pow(semis / 12f)
    }

    /**
     * The engine's own saturation. Normalised by `tanh(k)` so turning DRIVE up
     * adds harmonics without also acting as a volume knob — a drive control
     * that doubles as a gain control is impossible to set by ear.
     */
    private fun drive(x: Float, amount: Float): Float {
        // The ceiling is high because a sine starts with nothing above the
        // fundamental: without enough folding, CUTOFF has no harmonics to
        // open onto and the filter appears to do nothing.
        val k = Dsp.lin(amount, 1f, 48f)
        return (tanh((k * x).toDouble()) / tanh(k.toDouble())).toFloat()
    }

    /** Naive saw from a 0..1 phase. Aliases; lo-fi is on-brand. */
    private fun saw(phase: Double): Float = (2.0 * (phase - Math.floor(phase)) - 1.0).toFloat()

    fun render(voice: FathomVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val base = frequencyFor(voice, m.getValue("TUNE"))
        val driveAmt = m.getValue("DRIVE")
        val cutoff = m.getValue("CUTOFF")
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.2f, 1.6f)

        // Bass wants a low, gently resonant filter; the range tops out well
        // short of the SVF's stable limit because nothing here needs air.
        val fc = Dsp.expMap(cutoff, 90f, 4_000f)
        val damp = 1.2f

        // SWEEP: a fast downward pitch blip at the attack. This is the thump,
        // and it is a different envelope from GLIDE — attack, not journey.
        val sweepSemis = Dsp.lin(m["SWEEP"] ?: 0f, 0f, 30f)
        val sweepT60 = 0.035f

        // GLIDE: start up to an octave below the target and slide into it.
        // Unipolar and upward-only on purpose — a downward or overshooting
        // slide puts "sounds like a mistake" inside the knob's travel.
        val glideSemis = Dsp.lin(m.getValue("GLIDE"), 0f, 12f)
        // Always completes well inside the note. A slide still travelling when
        // the sound ends is the one way this can sound broken, so make it
        // impossible rather than documenting it.
        val glideTime = t60 * 0.35f

        // SPREAD is a beat-rate knob. Bass is low, so even a wide detune
        // beats slowly — a throb at the bottom of the knob, a growl at the top.
        val spreadCents = Dsp.lin(m["SPREAD"] ?: 0f, 4f, 90f)
        val detune = 2f.pow(spreadCents / 1200f)

        val out = FloatArray((t60 * 1.4f * RATE).toInt().coerceAtLeast(64))
        val svf = Dsp.TptSvf()
        var phase = 0.0
        var phase2 = 0.0
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            val blip = 2f.pow(sweepSemis * Dsp.envAt(t, sweepT60) / 12f)
            // Linear in semitones, which is what a portamento should be:
            // constant semitones per second reads as an even slide.
            val glideAt = (1f - t / glideTime).coerceIn(0f, 1f)
            val slide = 2f.pow(-glideSemis * glideAt / 12f)
            // One pitch, derived once: every oscillator must inherit the
            // SWEEP blip and the GLIDE slide, or it will drift away from
            // the others mid-note.
            val pitchHz = base * blip * slide
            phase += pitchHz / RATE

            val source = when (voice) {
                FathomVoice.GRIND -> {
                    phase2 += pitchHz * detune / RATE
                    // The hollowness *is* the beating between the two saws.
                    // No comb or notch stage — interference alone does it.
                    0.5f * (saw(phase) + saw(phase2))
                }
                else -> sin(2.0 * PI * phase).toFloat()
            }
            val driven = drive(source, driveAmt)
            svf.process(driven, fc, damp)

            val attack = (t / 0.004f).coerceAtMost(1f)
            out[i] = svf.low * attack * Dsp.envAt(t, t60)
        }

        Dsp.normalize(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
