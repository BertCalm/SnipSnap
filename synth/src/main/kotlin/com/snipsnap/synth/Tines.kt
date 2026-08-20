package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
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
enum class TinesVoice { BELL, CHIME, BLOCK, ZAP, TOY }

object Tines {

    /**
     * The RATIO macro snaps to these carrier:modulator ratios. Integer-ish
     * ratios sound tonal, the fractional ones clang — a knob position is a
     * *character*, never a mistuning. Snapping is what keeps a two-operator
     * FM panel playable at first touch.
     */
    val RATIOS = floatArrayOf(1f, 1.4f, 2f, 2.7f, 3.5f, 4.2f, 5.8f)

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
    }

    fun defaults(voice: TinesVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun scramble(voice: TinesVoice, random: Random): Map<String, Float> =
        macrosFor(voice).associate { it.name to random.nextFloat() }

    fun render(voice: TinesVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val buf = when (voice) {
            TinesVoice.BELL -> bell(m)
            TinesVoice.CHIME -> chime(m)
            TinesVoice.BLOCK -> block(m)
            TinesVoice.ZAP -> zap(m)
            TinesVoice.TOY -> toy(m)
        }
        Dsp.normalize(buf)
        Dsp.fadeTail(buf)
        return Snip(buf, channels = 1, sampleRate = RATE)
    }

    // ---------- the one FM shape everything here is made of ----------

    private fun frames(seconds: Float) = (seconds * RATE).toInt().coerceAtLeast(64)

    private fun snapRatio(macro: Float): Float =
        RATIOS[(macro.coerceIn(0f, 1f) * (RATIOS.size - 1)).toInt().coerceIn(0, RATIOS.size - 1)]

    /**
     * One struck two-operator note: carrier at [carrierHz], modulator at
     * ratio × carrier, index starting at [index] and decaying [bite] times
     * faster than the amplitude. Everything in this file is this function
     * with different numbers — which is exactly the "deliberately small" bet.
     */
    private fun strike(
        out: FloatArray,
        carrierHz: Float,
        ratio: Float,
        index: Float,
        t60: Float,
        bite: Float,
        gain: Float = 1f,
    ) {
        var pc = 0.0
        var pm = 0.0
        val modHz = carrierHz * ratio
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            pc += carrierHz / RATE
            pm += modHz / RATE
            val idx = index * Dsp.envAt(t, t60 / bite)
            out[i] += gain * Dsp.envAt(t, t60) *
                sin(2.0 * PI * pc + idx * sin(2.0 * PI * pm)).toFloat()
        }
    }

    // ---------- voices ----------

    private fun bell(m: Map<String, Float>): FloatArray {
        val carrier = Dsp.expMap(m.getValue("TUNE"), 220f, 740f)
        val ratio = snapRatio(m.getValue("RATIO"))
        val index = Dsp.lin(m.getValue("BRIGHT"), 0.8f, 5f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.25f, 1.1f)

        val out = FloatArray(frames(t60 * 1.3f))
        // A quiet second strike an octave up thickens the hit without a
        // third operator; bite 2.5 keeps the clang at the front.
        strike(out, carrier, ratio, index, t60, bite = 2.5f)
        strike(out, carrier * 2.01f, ratio, index * 0.6f, t60 * 0.6f, bite = 2.5f, gain = 0.35f)
        return out
    }

    private fun chime(m: Map<String, Float>): FloatArray {
        val carrier = Dsp.expMap(m.getValue("TUNE"), 520f, 1500f)
        val shimmer = m.getValue("SHIMMER")
        val index = Dsp.lin(m.getValue("BRIGHT"), 0.6f, 4f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.2f, 0.9f)

        val out = FloatArray(frames(t60 * 1.3f))
        // Glass is two near-identical bells beating against each other:
        // SHIMMER is the detune between them, in cents-ish territory.
        val detune = Dsp.lin(shimmer, 1.001f, 1.02f)
        strike(out, carrier, 3.5f, index, t60, bite = 3f, gain = 0.6f)
        strike(out, carrier * detune, 3.5f, index, t60 * 0.9f, bite = 3f, gain = 0.6f)
        return out
    }

    private fun block(m: Map<String, Float>): FloatArray {
        val carrier = Dsp.expMap(m.getValue("TUNE"), 380f, 950f)
        val index = Dsp.lin(m.getValue("BRIGHT"), 0.4f, 2.2f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.045f, 0.16f)

        // Woodblock: a near-harmonic ratio and a low index, gone in a blink.
        val out = FloatArray(frames(maxOf(t60 * 1.5f, 0.06f)))
        strike(out, carrier, 1.4f, index, t60, bite = 2f)
        return out
    }

    private fun zap(m: Map<String, Float>): FloatArray {
        val endHz = Dsp.expMap(m.getValue("TUNE"), 55f, 120f)
        val dropMult = Dsp.lin(m.getValue("DROP"), 4f, 16f)
        val index = Dsp.lin(m.getValue("BRIGHT"), 0.5f, 3f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.09f, 0.35f)

        // The laser tom: carrier and modulator ride the same exponential
        // drop, so the FM colour holds while the pitch falls onto the floor.
        val out = FloatArray(frames(t60 * 1.4f))
        var pc = 0.0
        var pm = 0.0
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            val f = endHz * (1f + (dropMult - 1f) * Math.exp(-24.0 * t).toFloat())
            pc += f / RATE
            pm += f * 2.7f / RATE
            val idx = index * Dsp.envAt(t, t60 / 2f)
            out[i] = Dsp.envAt(t, t60) *
                sin(2.0 * PI * pc + idx * sin(2.0 * PI * pm)).toFloat()
        }
        return out
    }

    private fun toy(m: Map<String, Float>): FloatArray {
        val carrier = Dsp.expMap(m.getValue("TUNE"), 300f, 900f)
        val wobble = m.getValue("WOBBLE")
        val index = Dsp.lin(m.getValue("BRIGHT"), 1f, 4.5f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.09f, 0.4f)

        // Sampling-era honesty: the wobble is an LFO on the carrier, baked
        // into the render. It's the cheap-keyboard laser/game hit.
        val wobHz = Dsp.lin(wobble, 6f, 34f)
        val wobDepth = Dsp.lin(wobble, 0.02f, 0.35f)
        val out = FloatArray(frames(t60 * 1.4f))
        var pc = 0.0
        var pm = 0.0
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            val f = carrier * (1f + wobDepth * sin(2.0 * PI * wobHz * t).toFloat())
            pc += f / RATE
            pm += f * 2f / RATE
            val idx = index * Dsp.envAt(t, t60 / 1.8f)
            out[i] = Dsp.envAt(t, t60) *
                sin(2.0 * PI * pc + idx * sin(2.0 * PI * pm)).toFloat()
        }
        return out
    }
}
