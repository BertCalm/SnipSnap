package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * TINES — FM, deliberately small.
 *
 * Two operators, phase modulation, and that's the whole synthesis story. Full
 * FM is famously unfun to program; the fun version (per docs/SYNTH_ROADMAP.md)
 * is a snapped RATIO and one BRIGHT knob driving the modulation index. The
 * index envelope always decays faster than the amplitude — the bite — because
 * that's what makes struck-metal sounds read as struck rather than droned.
 *
 * S3 scope: percussion one-shots that join THUMP kits. The key-patch side of
 * TINES (e-pianos, growl basses) waits on keygroup export.
 */
enum class TinesVoice { BELL, CHIME, BLOCK, ZAP, TOY, KALIMBA }

object Tines {

    /**
     * The RATIO macro snaps to these carrier:modulator ratios. Integer-ish
     * ratios sound tonal, the fractional ones clang — a knob position is a
     * *character*, never a mistuning. Snapping is what keeps a two-operator
     * FM panel playable at first touch.
     */
    val RATIOS = floatArrayOf(1f, 1.4f, 2f, 2.7f, 3.5f, 4.2f, 5.8f)

    /**
     * KALIMBA is TINES' one melodic voice, so its TUNE snaps to semitones
     * from a root of A3 over two octaves — PLUCK's convention
     * ([Pluck.TUNE_SEMITONES]), applied here because `SynthKits.melodic()`'s
     * kalimba pads are pad recipes that replay through macro values and
     * the note has to be reachable as one. The root and span match the
     * PLUCK voice this one replaces, so the kit's notes do not move.
     */
    const val KALIMBA_TUNE_SEMITONES = 24
    const val KALIMBA_ROOT_HZ = 220f

    /**
     * A clamped-free bar's partials — a kalimba tine — from the
     * Euler–Bernoulli eigenvalues βL = 1.8751, 4.6941, 7.8548 squared and
     * normalised to the first. The free-free bar in [Modes.tableFor] is
     * the same family. Citations: Fletcher & Rossing, The Physics of
     * Musical Instruments (bars); Rossing, Science of Percussion
     * Instruments (mbira) — recorded in the plan workspace before landing.
     * Two overtones only: the fourth (34.4 f0) would sit above 15 kHz over
     * most of the range and above Nyquist at the top.
     */
    internal val KALIMBA_PARTIALS = floatArrayOf(1f, 6.267f, 17.548f)

    /** The snapped note KALIMBA's TUNE lands on; every other TINES voice has a continuous carrier range. */
    fun frequencyFor(voice: TinesVoice, tune: Float): Float =
        frequencyFor(voice, Math.round(tune.coerceIn(0f, 1f) * KALIMBA_TUNE_SEMITONES))

    internal fun frequencyFor(voice: TinesVoice, semitone: Int): Float {
        require(voice == TinesVoice.KALIMBA) { "only KALIMBA snaps TUNE to semitones; $voice has a continuous carrier range" }
        return KALIMBA_ROOT_HZ * 2f.pow(semitone / 12f)
    }

    fun macrosFor(voice: TinesVoice): List<MacroSpec> = when (voice) {
        TinesVoice.BELL -> listOf(
            MacroSpec("TUNE", 0.45f), MacroSpec("RATIO", 0.7f), MacroSpec("BRIGHT", 0.5f),
            MacroSpec("DECAY", 0.55f),
        )
        TinesVoice.CHIME -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("SHIMMER", 0.45f), MacroSpec("BRIGHT", 0.55f),
            MacroSpec("DECAY", 0.5f),
        )
        TinesVoice.BLOCK -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("BRIGHT", 0.4f), MacroSpec("DECAY", 0.35f),
        )
        TinesVoice.ZAP -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DROP", 0.55f), MacroSpec("BRIGHT", 0.45f),
            MacroSpec("DECAY", 0.45f),
        )
        TinesVoice.TOY -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("WOBBLE", 0.5f), MacroSpec("BRIGHT", 0.5f),
            MacroSpec("DECAY", 0.4f),
        )
        TinesVoice.KALIMBA -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("BUZZ", 0.15f), MacroSpec("BRIGHT", 0.5f),
            MacroSpec("DECAY", 0.45f),
        )
    }

    fun defaults(voice: TinesVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /** SCRAMBLE near a preset; see [Thump.scramble] (docs/SYNTH_UPGRADE.md, U2). */
    fun scramble(voice: TinesVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float> {
        val base = defaults(voice)
        val seed = when {
            near != null -> base + near.macros.filterKeys { it in base }
            temperature >= 1f -> base
            else -> base + TinesPresets.forVoice(voice).random(random).macros.filterKeys { it in base }
        }
        return Dsp.scrambleNear(seed, temperature, random)
    }

    fun render(voice: TinesVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        // U6 (docs/SYNTH_UPGRADE.md): render at 4x RATE so the FM
        // operators' own aliasing folds down above 22.05kHz instead of
        // into the audible band, then Dsp.decimate brings it back to RATE.
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = when (voice) {
            TinesVoice.BELL -> bell(m, renderRate)
            TinesVoice.CHIME -> chime(m, renderRate)
            TinesVoice.BLOCK -> block(m, renderRate)
            TinesVoice.ZAP -> zap(m, renderRate)
            TinesVoice.TOY -> toy(m, renderRate)
            TinesVoice.KALIMBA -> kalimba(m, renderRate)
        }
        val buf = Dsp.decimate(raw, RATE)
        Dsp.normalize(buf)
        Dsp.fadeTail(buf)
        return Snip(buf, channels = 1, sampleRate = RATE)
    }

    // ---------- the one FM shape everything here is made of ----------

    private fun frames(seconds: Float, rate: Int) = (seconds * rate).toInt().coerceAtLeast(64)

    private fun snapRatio(macro: Float): Float =
        RATIOS[(macro.coerceIn(0f, 1f) * (RATIOS.size - 1)).toInt().coerceIn(0, RATIOS.size - 1)]

    /**
     * One struck two-operator note: carrier at [carrierHz], modulator at
     * ratio × carrier, index starting at [index] and decaying [bite] times
     * faster than the amplitude. Everything in this file is this function
     * with different numbers — which is exactly the "deliberately small" bet.
     * Internal so [Keys] can strike at exact MIDI frequencies for the
     * key-patch instruments (S5) without a second FM core.
     */
    internal fun strike(
        out: FloatArray,
        carrierHz: Float,
        ratio: Float,
        index: Float,
        t60: Float,
        bite: Float,
        gain: Float = 1f,
        rate: Int = RATE,
    ) {
        var pc = 0.0
        var pm = 0.0
        val modHz = carrierHz * ratio
        // A 1ms attack ramp (U5, docs/SYNTH_UPGRADE.md) - declicks the
        // instant onset every strike used to jump straight into, short
        // enough that the "struck" bite is untouched.
        val env = Dsp.Env(attackSeconds = 0.001f, decay2T60 = t60)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            pc += carrierHz / rate
            pm += modHz / rate
            val idx = index * Dsp.envAt(t, t60 / bite)
            out[i] += gain * env.at(t) *
                sin(2.0 * PI * pc + idx * sin(2.0 * PI * pm)).toFloat()
        }
    }

    // ---------- voices ----------

    private fun bell(m: Map<String, Float>, rate: Int): FloatArray {
        val carrier = Dsp.expMap(m.getValue("TUNE"), 220f, 740f)
        val ratio = snapRatio(m.getValue("RATIO"))
        val index = Dsp.lin(m.getValue("BRIGHT"), 0.8f, 5f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.25f, 1.1f)

        val out = FloatArray(frames(t60 * 1.3f, rate))
        // A quiet second strike an octave up thickens the hit without a
        // third operator; bite 2.5 keeps the clang at the front.
        strike(out, carrier, ratio, index, t60, bite = 2.5f, rate = rate)
        strike(out, carrier * 2.01f, ratio, index * 0.6f, t60 * 0.6f, bite = 2.5f, gain = 0.35f, rate = rate)
        return out
    }

    private fun chime(m: Map<String, Float>, rate: Int): FloatArray {
        val carrier = Dsp.expMap(m.getValue("TUNE"), 520f, 1500f)
        val shimmer = m.getValue("SHIMMER")
        val index = Dsp.lin(m.getValue("BRIGHT"), 0.6f, 4f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.2f, 0.9f)

        val out = FloatArray(frames(t60 * 1.3f, rate))
        // Glass is two near-identical bells beating against each other:
        // SHIMMER is the detune between them, in cents-ish territory.
        val detune = Dsp.lin(shimmer, 1.001f, 1.02f)
        strike(out, carrier, 3.5f, index, t60, bite = 3f, gain = 0.6f, rate = rate)
        strike(out, carrier * detune, 3.5f, index, t60 * 0.9f, bite = 3f, gain = 0.6f, rate = rate)
        return out
    }

    private fun block(m: Map<String, Float>, rate: Int): FloatArray {
        val carrier = Dsp.expMap(m.getValue("TUNE"), 380f, 950f)
        val index = Dsp.lin(m.getValue("BRIGHT"), 0.4f, 2.2f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.045f, 0.16f)

        // Woodblock: a near-harmonic ratio and a low index, gone in a blink.
        val out = FloatArray(frames(maxOf(t60 * 1.5f, 0.06f), rate))
        strike(out, carrier, 1.4f, index, t60, bite = 2f, rate = rate)
        return out
    }

    private fun zap(m: Map<String, Float>, rate: Int): FloatArray {
        val endHz = Dsp.expMap(m.getValue("TUNE"), 55f, 120f)
        val dropMult = Dsp.lin(m.getValue("DROP"), 4f, 16f)
        val index = Dsp.lin(m.getValue("BRIGHT"), 0.5f, 3f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.09f, 0.35f)

        // The laser tom: carrier and modulator ride the same exponential
        // drop, so the FM colour holds while the pitch falls onto the floor.
        val out = FloatArray(frames(t60 * 1.4f, rate))
        var pc = 0.0
        var pm = 0.0
        val env = Dsp.Env(attackSeconds = 0.001f, decay2T60 = t60)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val f = endHz * (1f + (dropMult - 1f) * Math.exp(-24.0 * t).toFloat())
            pc += f / rate
            pm += f * 2.7f / rate
            val idx = index * Dsp.envAt(t, t60 / 2f)
            out[i] = env.at(t) *
                sin(2.0 * PI * pc + idx * sin(2.0 * PI * pm)).toFloat()
        }
        return out
    }

    private fun toy(m: Map<String, Float>, rate: Int): FloatArray {
        val carrier = Dsp.expMap(m.getValue("TUNE"), 300f, 900f)
        val wobble = m.getValue("WOBBLE")
        val index = Dsp.lin(m.getValue("BRIGHT"), 1f, 4.5f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.09f, 0.4f)

        // Sampling-era honesty: the wobble is an LFO on the carrier, baked
        // into the render. It's the cheap-keyboard laser/game hit.
        val wobHz = Dsp.lin(wobble, 6f, 34f)
        val wobDepth = Dsp.lin(wobble, 0.02f, 0.35f)
        val out = FloatArray(frames(t60 * 1.4f, rate))
        var pc = 0.0
        var pm = 0.0
        val env = Dsp.Env(attackSeconds = 0.001f, decay2T60 = t60)
        for (i in out.indices) {
            val t = i.toFloat() / rate
            val f = carrier * (1f + wobDepth * sin(2.0 * PI * wobHz * t).toFloat())
            pc += f / rate
            pm += f * 2f / rate
            val idx = index * Dsp.envAt(t, t60 / 1.8f)
            out[i] = env.at(t) *
                sin(2.0 * PI * pc + idx * sin(2.0 * PI * pm)).toFloat()
        }
        return out
    }

    /**
     * A plucked tine: a harmonic strike for the tongue, then two near-pure
     * partials at the bar's own ratios, each dying faster than the one
     * below it, then the buzzers. DECAY tops out at 1.0 s so the voice stays
     * under the 1.5 s one-shot bound every TINES voice keeps.
     */
    private fun kalimba(m: Map<String, Float>, rate: Int): FloatArray {
        val hz = frequencyFor(TinesVoice.KALIMBA, m.getValue("TUNE"))
        val bright = m.getValue("BRIGHT")
        val buzz = m.getValue("BUZZ")
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.3f, 1.0f)

        val out = FloatArray(frames(t60 * 1.3f, rate))
        // The tongue: its index is the thumb's hardness, and the bite keeps
        // the pluck at the front.
        strike(out, hz, ratio = 1f, index = Dsp.lin(bright, 0.3f, 1.6f), t60 = t60, bite = 2.5f, rate = rate)
        // The bar's overtones, each a near-pure partial (ratio 1, tiny
        // index) that BRIGHT brings up: the partials are the kalimba's
        // shimmer, and die slower than the tongue's index but faster than
        // the tongue.
        val upper = Dsp.lin(bright, 0.3f, 0.9f)
        strike(out, hz * KALIMBA_PARTIALS[1], ratio = 1f, index = 0.2f, t60 = t60 * 0.6f, bite = 2f, gain = upper, rate = rate)
        strike(out, hz * KALIMBA_PARTIALS[2], ratio = 1f, index = 0.1f, t60 = t60 * 0.25f, bite = 2f, gain = upper * 0.5f, rate = rate)
        tick(out, bright, rate, Dsp.seedFor("TINES", TinesVoice.KALIMBA.name, "TICK"))
        if (buzz > 0.01f) rattle(out, buzz, Dsp.seedFor("TINES", TinesVoice.KALIMBA.name, "BUZZ"))
        return out
    }

    /**
     * The thumbnail's tick: a few milliseconds of seeded noise at the
     * onset, high-passed above the tongue, louder as BRIGHT rises. A real
     * tine is struck by a nail or a flesh-and-nail edge and the tick is
     * what says "struck" before the tone says "kalimba"; without it the
     * gate heard the voice as muffled at every brightness and register.
     */
    private fun tick(out: FloatArray, bright: Float, rate: Int, seed: Int) {
        val noise = Dsp.Noise(seed)
        val n = (0.003f * rate).toInt().coerceAtMost(out.size)
        val gain = Dsp.lin(bright, 0.2f, 0.7f)
        // One-pole high-pass at 3 kHz, matched-Z: y = x - lp(x).
        val a = exp(-2.0 * PI * 3000.0 / rate).toFloat()
        var lp = 0f
        for (i in 0 until n) {
            val x = noise.next()
            lp = a * lp + (1f - a) * x
            val env = 1f - i.toFloat() / n
            out[i] += gain * env * env * (x - lp)
        }
    }

    /**
     * The mbira's buzzers — bottle caps, shells on the soundboard — rattle
     * at the peaks of the vibration, so the buzz lives in the attack and
     * dies with the note. Amplitude-gated noise: wherever the tongue swings
     * past a threshold BUZZ lowers, add seeded noise scaled by the excess.
     * BUZZ 0 is a clean thumb piano; BUZZ 1 is a full rattle, the ugly end.
     *
     * At BUZZ 1 the rattle can add roughly twice the tine's own peak before
     * [render]'s `Dsp.normalize` pulls the whole buffer back down (~10 dB),
     * so a full rattle reads quieter in the app than a clean tine does, not
     * louder as the raw gain here would suggest. The noise is also white at
     * the 4x render rate, and `Dsp.decimate`'s low-pass on the way back down
     * to RATE discards most of that energy - only what survives under
     * Nyquist at RATE actually reaches the ear. Both to revisit once the
     * presets are authored by ear rather than from this table.
     */
    private fun rattle(out: FloatArray, buzz: Float, seed: Int) {
        val noise = Dsp.Noise(seed)
        var peak = 0f
        for (v in out) peak = maxOf(peak, abs(v))
        if (peak <= 0f) return
        val threshold = peak * Dsp.lin(buzz, 0.9f, 0.15f)
        val gain = Dsp.lin(buzz, 0.5f, 2.5f)
        for (i in out.indices) {
            val excess = abs(out[i]) - threshold
            if (excess > 0f) out[i] += gain * excess * noise.next()
        }
    }
}
