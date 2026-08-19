package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * THUMP — the analog-style drum voice engine.
 *
 * Everything renders offline: a voice type plus a macro map (every macro
 * normalized 0..1) becomes a mono [Snip], ready for the same cleanup/kit/
 * export pipeline as captured audio.
 *
 * The playability rules from docs/SYNTH_ROADMAP.md are enforced by
 * construction here: macros are 0..1 and mapped internally onto bounded
 * musical ranges, so the worst any knob position can sound is "not for me" —
 * never broken. SCRAMBLE is just a uniform roll of that same space.
 */
enum class ThumpVoice { KICK, SNARE, HAT_CLOSED, HAT_OPEN, CLAP, TOM, COWBELL, RIM }

/** One macro knob: plain-word name, factory default. Range is always 0..1. */
data class MacroSpec(val name: String, val default: Float)

object Thump {

    fun macrosFor(voice: ThumpVoice): List<MacroSpec> = when (voice) {
        ThumpVoice.KICK -> listOf(
            MacroSpec("TUNE", 0.35f), MacroSpec("SWEEP", 0.5f), MacroSpec("DECAY", 0.45f),
            MacroSpec("CLICK", 0.35f), MacroSpec("DRIVE", 0.25f),
        )
        ThumpVoice.SNARE -> listOf(
            MacroSpec("TUNE", 0.4f), MacroSpec("SNAP", 0.55f), MacroSpec("DECAY", 0.4f),
            MacroSpec("TONE", 0.55f),
        )
        ThumpVoice.HAT_CLOSED -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.3f), MacroSpec("METAL", 0.5f),
        )
        ThumpVoice.HAT_OPEN -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.55f), MacroSpec("METAL", 0.5f),
        )
        ThumpVoice.CLAP -> listOf(
            MacroSpec("SPREAD", 0.5f), MacroSpec("DECAY", 0.45f), MacroSpec("TONE", 0.5f),
        )
        ThumpVoice.TOM -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("SWEEP", 0.4f), MacroSpec("DECAY", 0.5f),
        )
        ThumpVoice.COWBELL -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.4f),
        )
        ThumpVoice.RIM -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DECAY", 0.3f),
        )
    }

    /** The factory macro settings for [voice]. */
    fun defaults(voice: ThumpVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /**
     * SCRAMBLE: a uniform roll of the macro space. Bounded ranges mean any
     * roll is playable; a seeded [random] makes rolls reproducible.
     */
    fun scramble(voice: ThumpVoice, random: Random): Map<String, Float> =
        macrosFor(voice).associate { it.name to random.nextFloat() }

    /** Render [voice] with [macros]; missing macros fall back to defaults. */
    fun render(voice: ThumpVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val buf = when (voice) {
            ThumpVoice.KICK -> kick(m)
            ThumpVoice.SNARE -> snare(m)
            ThumpVoice.HAT_CLOSED -> hat(m, open = false)
            ThumpVoice.HAT_OPEN -> hat(m, open = true)
            ThumpVoice.CLAP -> clap(m)
            ThumpVoice.TOM -> tom(m)
            ThumpVoice.COWBELL -> cowbell(m)
            ThumpVoice.RIM -> rim(m)
        }
        Dsp.normalize(buf)
        Dsp.fadeTail(buf)
        return Snip(buf, channels = 1, sampleRate = RATE)
    }

    // ---------- voices ----------

    private fun frames(seconds: Float) = (seconds * RATE).toInt().coerceAtLeast(64)

    private fun kick(m: Map<String, Float>): FloatArray {
        // Ranges stay in kick territory on purpose: the playability contract
        // says a scrambled kick still reads as a kick, and spectral centroid
        // is merciless - a whisper of broadband click outweighs a wall of
        // 46 Hz fundamental. Hence the low-passed thump-click and the fast
        // sweep, both tuned against the classifier.
        val base = Dsp.expMap(m.getValue("TUNE"), 34f, 62f)
        val sweepMult = Dsp.lin(m.getValue("SWEEP"), 1.2f, 4f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.09f, 0.85f)
        val click = m.getValue("CLICK")
        val driveAmt = m.getValue("DRIVE")

        val out = FloatArray(frames(t60 * 1.4f))
        var phase = 0.0
        val noise = Dsp.Noise(7)
        val clickLp = Dsp.OnePole()
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            // The defining kick shape: frequency falls fast onto the base.
            val f = base * (1f + (sweepMult - 1f) * exp((-90.0 * t)).toFloat())
            phase += f / RATE
            var s = Dsp.envAt(t, t60) * sin(2.0 * PI * phase).toFloat()
            if (t < 0.005f) {
                s += click * 0.6f * clickLp.lp(noise.next(), 1000f) * (1f - t / 0.005f) * 2f
            }
            out[i] = Dsp.drive(s, driveAmt)
        }
        return out
    }

    private fun snare(m: Map<String, Float>): FloatArray {
        val tune = Dsp.expMap(m.getValue("TUNE"), 140f, 260f)
        val snap = m.getValue("SNAP")
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 0.5f)
        val toneHz = Dsp.expMap(m.getValue("TONE"), 2200f, 9000f)

        val out = FloatArray(frames(t60 * 1.4f))
        val noise = Dsp.Noise(3)
        val lp = Dsp.OnePole()
        var p1 = 0.0; var p2 = 0.0
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            p1 += tune / RATE
            p2 += tune * 1.83 / RATE
            val body = (0.6f * sin(2.0 * PI * p1) + 0.4f * sin(2.0 * PI * p2)).toFloat() *
                Dsp.envAt(t, t60 * 0.45f)
            val rattle = lp.lp(noise.next(), toneHz) * 2.4f * Dsp.envAt(t, t60)
            out[i] = (1f - snap) * body + snap * rattle
        }
        return out
    }

    /**
     * The classic metallic recipe: a cluster of six inharmonic squares,
     * high-passed hard so only the shimmer survives.
     */
    private fun hat(m: Map<String, Float>, open: Boolean): FloatArray {
        val base = Dsp.expMap(m.getValue("TUNE"), 320f, 620f)
        val t60 = if (open) Dsp.expMap(m.getValue("DECAY"), 0.25f, 1.0f)
        else Dsp.expMap(m.getValue("DECAY"), 0.04f, 0.16f)
        val metal = m.getValue("METAL")
        val ratios = floatArrayOf(1f, 1.342f, 1.681f, 1.940f, 2.318f, 2.703f)
        val spread = Dsp.lin(metal, 0.9f, 1.25f)

        val out = FloatArray(frames(t60 * 1.4f))
        val phases = DoubleArray(6)
        val lp1 = Dsp.OnePole(); val lp2 = Dsp.OnePole()
        val hpHz = Dsp.lin(metal, 6800f, 9200f)
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            var s = 0f
            for (k in 0 until 6) {
                phases[k] += base * Math.pow(ratios[k].toDouble(), spread.toDouble()) / RATE
                s += Dsp.square(phases[k])
            }
            s /= 6f
            // Two cascaded one-pole high-passes: keep the sizzle, dump the body.
            val hp = s - lp1.lp(s, hpHz)
            val hp2 = hp - lp2.lp(hp, hpHz)
            out[i] = hp2 * 2.2f * Dsp.envAt(t, t60)
        }
        return out
    }

    private fun clap(m: Map<String, Float>): FloatArray {
        val spreadS = Dsp.lin(m.getValue("SPREAD"), 0.007f, 0.016f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.15f, 0.5f)
        val toneHz = Dsp.expMap(m.getValue("TONE"), 1600f, 5200f)

        val out = FloatArray(frames(t60 * 1.4f + 3 * spreadS))
        val noise = Dsp.Noise(4)
        val lp = Dsp.OnePole()
        val bursts = floatArrayOf(0f, spreadS, 2 * spreadS, 3 * spreadS)
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            val n = lp.lp(noise.next(), toneHz) * 2.4f
            var env = 0f
            // A hand clap is several impacts a few ms apart, then a tail.
            for (b in bursts) if (t >= b) env = maxOf(env, exp((-320.0 * (t - b))).toFloat())
            val tail = if (t >= bursts.last()) 0.6f * Dsp.envAt(t - bursts.last(), t60) else 0f
            out[i] = n * maxOf(env, tail)
        }
        return out
    }

    private fun tom(m: Map<String, Float>): FloatArray {
        val base = Dsp.expMap(m.getValue("TUNE"), 82f, 240f)
        val sweep = Dsp.lin(m.getValue("SWEEP"), 1.05f, 1.6f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.18f, 0.7f)

        val out = FloatArray(frames(t60 * 1.4f))
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            val f = base * (1f + (sweep - 1f) * exp((-30.0 * t)).toFloat())
            phase += f / RATE
            out[i] = Dsp.envAt(t, t60) * sin(2.0 * PI * phase).toFloat()
        }
        return out
    }

    private fun cowbell(m: Map<String, Float>): FloatArray {
        val base = Dsp.expMap(m.getValue("TUNE"), 420f, 700f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.09f, 0.45f)

        val out = FloatArray(frames(t60 * 1.4f))
        var p1 = 0.0; var p2 = 0.0
        val svf = Dsp.Svf()
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            p1 += base / RATE
            p2 += base * 1.48 / RATE
            val s = (Dsp.square(p1) + Dsp.square(p2)) * 0.5f
            svf.process(s, base * 1.2f, 0.6f)
            out[i] = svf.band * 1.6f * Dsp.envAt(t, t60)
        }
        return out
    }

    private fun rim(m: Map<String, Float>): FloatArray {
        val freq = Dsp.expMap(m.getValue("TUNE"), 1200f, 2400f)
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.03f, 0.12f)

        val out = FloatArray(frames(maxOf(t60 * 1.6f, 0.05f)))
        val svf = Dsp.Svf()
        val noise = Dsp.Noise(9)
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            // A damped resonator struck by a 1 ms excitation: the tick.
            val excite = if (t < 0.001f) noise.next() + 1.5f else 0f
            svf.process(excite, freq, 0.12f)
            out[i] = svf.band * Dsp.envAt(t, t60)
        }
        return out
    }
}
