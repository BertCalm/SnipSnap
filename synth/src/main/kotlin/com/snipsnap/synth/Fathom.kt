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

    /**
     * Per-voice nudge on top of [Dsp.MELODIC_LOUDNESS_TARGET], zeroed out
     * awaiting a listening pass (task-4-report.md) - a table edit here, not
     * a refactor of [render].
     */
    private val LOUDNESS_OFFSET: Map<FathomVoice, Float> = FathomVoice.entries.associateWith { 0f }

    /**
     * The FM ratios RATIO snaps to, chosen for low end: sub-octave, unison, a
     * hollow fifth-ish, octave, and a metallic twelfth. Snapping is the same
     * guarantee TINES makes — the knob cannot land on a mistuning.
     */
    val RATIOS = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f)

    private fun ratioFor(macro: Float): Float =
        RATIOS[Math.round(macro.coerceIn(0f, 1f) * (RATIOS.size - 1))]

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

    /** SCRAMBLE near a preset; see [Thump.scramble] (docs/SYNTH_UPGRADE.md, U2). */
    fun scramble(voice: FathomVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + FathomPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /**
     * PLACEHOLDER awaiting the audition gate — how hard CUTOFF tracks the
     * note (see [Dsp.keyTrack]). Same taste call as VELVET's own constant
     * of the same name (`Velvet.kt`); its KDoc has the general reasoning.
     *
     * Measured for this engine's own cutoff mapping (90..4000 Hz):
     * because every FATHOM voice's TUNE also spans exactly
     * [TUNE_SEMITONES] (2 octaves) centred on [keyTrackReferenceHz], the
     * top-to-bottom ratio at this setting is the same voice-independent
     * `4^0.6 ≈ 2.30x` (~1.2 octaves) VELVET gets. Concretely for DEEP at
     * its factory CUTOFF (0.4 -> 410.6 Hz): 270.9 Hz at the bottom of
     * TUNE, 622.3 Hz at the top, a swing of about 351 Hz. Every shipped
     * preset's TUNE sits below the 0.5 centre (DEEP 0.25, GRIND 0.3, GLASS
     * 0.35), so factory presets also come out a little darker than before
     * this landed, not just proportional across a run - re-run the sweep
     * in task-6-and-5b-fix-report.md before trusting 0.6 for real.
     *
     * `internal`, not `private`, only so `synthesize`'s own `cutoffKeyTrackAmount`
     * default can be checked against it byte-for-byte from `FathomTest` (the
     * permanent reachability proof this constant's tracking actually reaches
     * the render - see `FathomTest`'s own KDoc on that test). Nothing outside
     * this module ever sees it; production callers still only ever get the
     * shipped 0.6.
     */
    internal const val CUTOFF_KEY_TRACK_AMOUNT = 0.6f

    /**
     * Key-tracking's reference pitch for [voice]: the tuning centre of its
     * own TUNE range, not an arbitrary Hz - see [Velvet.keyTrackReferenceHz]
     * for the full reasoning, identical here.
     */
    private fun keyTrackReferenceHz(voice: FathomVoice): Float = frequencyFor(voice, 0.5f)

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
        // The ceiling is high because DEEP's sine starts with nothing above
        // the fundamental: without enough folding, CUTOFF has no harmonics
        // to open onto and the filter appears to do nothing. GRIND and GLASS
        // are already harmonically rich, so the same ceiling gives them more
        // headroom than they need rather than too little.
        val k = Dsp.lin(amount, 1f, 48f)
        return (tanh((k * x).toDouble()) / tanh(k.toDouble())).toFloat()
    }

    /** Naive saw from a 0..1 phase. Aliases; lo-fi is on-brand. */
    private fun saw(phase: Double): Float = (2.0 * (phase - Math.floor(phase)) - 1.0).toFloat()

    /**
     * The raw synth loop, at whatever [rate] the caller wants - split out of
     * [render] so U6's oversampled dispatch (docs/SYNTH_UPGRADE.md) can be
     * tested directly against a native-rate render, rather than trusting
     * that reading [render]'s own source matches what it actually does.
     */
    internal fun synthesize(
        voice: FathomVoice,
        macros: Map<String, Float>,
        rate: Int,
        // Internal, default-valued, purely for testability - mirrors
        // Dsp.TptSvf.process's own optional `saturate` param. Every
        // production caller (render(), below) omits this and gets the
        // shipped CUTOFF_KEY_TRACK_AMOUNT; FathomTest passes explicit
        // values to prove key tracking actually reaches this function
        // (a test that only called Dsp.keyTrack() directly could pass even
        // if this line were never wired up).
        cutoffKeyTrackAmount: Float = CUTOFF_KEY_TRACK_AMOUNT,
    ): FloatArray {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val base = frequencyFor(voice, m.getValue("TUNE"))
        val driveAmt = m.getValue("DRIVE")
        val cutoff = m.getValue("CUTOFF")
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.2f, 1.6f)

        // Bass wants a low, gently resonant filter. The 4 kHz ceiling is a
        // taste choice, not a stability one — TptSvf is stable to Nyquist,
        // as Velvet.kt documents — because nothing here needs air.
        // Tracked to the note so brightness is an interval, not a fixed
        // Hz - a run up the pads used to get duller as it climbed (Task 6).
        val fc = Dsp.keyTrack(
            Dsp.expMap(cutoff, 90f, 4_000f), base, keyTrackReferenceHz(voice), cutoffKeyTrackAmount,
        )
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
        // beats slowly — a throb at the bottom of the knob, a growl at the
        // top. Split symmetrically about the centre: a one-sided detune
        // would drag the perceived pitch sharp as the knob opens, which
        // fights TUNE's exact semitone snapping.
        val spreadCents = Dsp.lin(m["SPREAD"] ?: 0f, 4f, 90f)
        val detune = 2f.pow(spreadCents / 2400f)       // half the spread, each way

        // GLASS: DRIVE moves the FM index and the output saturation together,
        // the same one-knob-can't-be-ugly device VELVET uses for SQUEEZE.
        val fmRatio = ratioFor(m["RATIO"] ?: 0f)
        val fmIndex = Dsp.lin(driveAmt, 1f, 14f)

        val out = FloatArray((t60 * 1.4f * rate).toInt().coerceAtLeast(64))
        val svf = Dsp.TptSvf(rate)
        val env = Dsp.Env(attackSeconds = 0.004f, decay2T60 = t60)
        // Seeded per voice: GRIND's saw pair no longer starts locked, and
        // GLASS's carrier/modulator no longer starts at the same coherent
        // transient every note.
        val ph = Dsp.phases(4, Dsp.seedFor("FATHOM", voice.name))
        var phase = ph[0]
        var phaseLow = ph[1]
        var phase2 = ph[2]
        var phaseMod = ph[3]
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val blip = 2f.pow(sweepSemis * Dsp.envAt(t, sweepT60) / 12f)
            // Linear in semitones, which is what a portamento should be:
            // constant semitones per second reads as an even slide.
            val glideAt = (1f - t / glideTime).coerceIn(0f, 1f)
            val slide = 2f.pow(-glideSemis * glideAt / 12f)
            // One pitch, derived once: every oscillator must inherit the
            // SWEEP blip and the GLIDE slide, or it will drift away from
            // the others mid-note.
            val pitchHz = base * blip * slide
            phase += pitchHz / rate

            val source = when (voice) {
                FathomVoice.DEEP -> sin(2.0 * PI * phase).toFloat()
                FathomVoice.GRIND -> {
                    phaseLow += pitchHz / detune / rate
                    phase2 += pitchHz * detune / rate
                    // The hollowness *is* the beating between the two saws.
                    // No comb or notch stage — interference alone does it.
                    0.5f * (saw(phaseLow) + saw(phase2))
                }
                FathomVoice.GLASS -> {
                    // Derived from the same pitchHz as the carrier: if the
                    // modulator missed the glide, the FM ratio would drift
                    // during the slide and the timbre would smear.
                    phaseMod += pitchHz * fmRatio / rate
                    // The index rides the amp envelope, so the metallic edge
                    // decays faster than the fundamental. That is what real FM
                    // basses do, and it is what stops this being a static buzz.
                    val idx = fmIndex * Dsp.envAt(t, t60 * 0.6f)
                    sin(2.0 * PI * phase + idx * sin(2.0 * PI * phaseMod)).toFloat()
                }
            }
            val driven = drive(source, driveAmt)
            svf.process(driven, fc, damp)

            out[i] = svf.low * env.at(t)
        }
        return out
    }

    fun render(voice: FathomVoice, macros: Map<String, Float> = emptyMap()): Snip {
        // U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE so the naive saw/FM
        // oscillators' and the DRIVE saturation's own harmonics fold down
        // above 22.05kHz instead of into the audible band, then
        // Dsp.decimate brings it back to RATE.
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
