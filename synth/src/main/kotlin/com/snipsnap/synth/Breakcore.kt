package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.*
import com.snipsnap.kit.ArrangedPad
import java.util.Random
import kotlin.math.*

/** One kit of the breakcore family. Each kit owns one job a breakcore track needs:
 * [Splinter] the edit, [Shatter] the break, [Soundclash] the sound system, [Redline] speedcore,
 * [Lull] the quiet side, [Strobe] the rave, [Cartridge] the chip, [Barricade] digital hardcore.
 * The shape is shared: 16 voices on A01 to A16, an authored roll on B01 and riser on B02,
 * three velocities by three takes on every pad, no per-hit levelling. */
interface BreakcoreKit {
    val id: String
    val title: String
    val bpm: Int
    val layout: List<Splinter.Part>
    fun renderPad(pad: Int, velocity: Double = 1.0, take: Int = 0): Snip
}

/** The family's shared building blocks. Synthesized sources render at [Dsp.OVERSAMPLE]x
 * where they make corners (saws, clipped noise) and come down through the house decimator. */
object Breakcore {
    const val RECIPE_VERSION = 1
    val VELOCITIES: List<Double> = listOf(.3, .65, 1.0)
    val kits: List<BreakcoreKit> get() = listOf(Splinter, Shatter, Soundclash, Redline, Lull, Strobe, Cartridge, Barricade)
    /** The name a saved recipe carries: the first word of the kit's title. */
    fun name(kit: BreakcoreKit): String = kit.title.substringBefore(' ')
    fun byName(name: String): BreakcoreKit = requireNotNull(kits.firstOrNull { name(it) == name }) { "unknown breakcore kit $name" }

    /** A saved source for one cell of a family kit. Rerenders through [BreakcoreKit.renderPad];
     * the JSON is the same shape SPLINTER's own recipe writes. */
    data class Recipe(val kit: BreakcoreKit, val pad: Int, val velocity: Double = 1.0, val take: Int = 0, val version: Int = RECIPE_VERSION) {
        init { require(pad in kit.layout.indices && take >= 0 && velocity.isFinite() && velocity in 0.0..1.0 && version == RECIPE_VERSION) }
        fun render(): Snip = kit.renderPad(pad, velocity, take)
        fun toJsonText(): String = Json.write(JsonValue.Obj(mapOf(
            "source" to JsonValue.Str(name(kit)), "version" to JsonValue.Num(version.toDouble()),
            "pad" to JsonValue.Num(pad.toDouble()), "velocity" to JsonValue.Num(velocity), "take" to JsonValue.Num(take.toDouble()))))
        companion object {
            fun fromJsonText(s: String): Recipe {
                val m = Json.parse(s).obj()
                fun number(k: String) = (m.getValue(k) as JsonValue.Num).value
                fun integer(k: String): Int { val n = number(k); require(n == n.toInt().toDouble()); return n.toInt() }
                return Recipe(byName(m.getValue("source").str()), integer("pad"), number("velocity"), integer("take"), integer("version"))
            }
        }
    }

    /** The shelf pads of a family kit: three velocities by three takes on every pad, saved as source recipes. */
    fun arranged(kit: BreakcoreKit): List<ArrangedPad> = kit.layout.mapIndexed { i, p ->
        val grid = VELOCITIES.map { v -> (0..2).map { t -> Recipe(kit, i, v, t).render() } }
        ArrangedPad(grid[2][0], p.kind, level = p.level, oneShot = true, displayName = p.name, muteGroup = p.muteGroup, velocityTakes = grid,
            source = mapOf("sourceBank" to "${name(kit)}/v$RECIPE_VERSION", "sourceRecipe" to Recipe(kit, i).toJsonText(),
                "velocities" to ".3,.65,1.0", "takes" to "3", "status" to "groove and pads heard ${heard(kit)} and kept; per-pad verdicts, phone and MPC playback open"))
    }

    /** The day the owner heard the kit's groove and pads and kept them: round one on 2026-11-18, round two the next day. */
    private fun heard(kit: BreakcoreKit): String = if (kit in listOf(Lull, Strobe, Cartridge, Barricade)) "2026-11-19" else "2026-11-18"

    fun beat(bpm: Int): Double = 60.0 / bpm * Dsp.RATE
    fun frames(seconds: Double): Int = (seconds * Dsp.RATE).roundToInt()

    fun mix(length: Int, vararg parts: Pair<Int, FloatArray>): FloatArray {
        val out = FloatArray(length)
        for ((at, p) in parts) for (j in p.indices) if (at + j in out.indices) out[at + j] += p[j]
        return out
    }

    fun scale(x: FloatArray, g: Double): FloatArray = FloatArray(x.size) { (x[it] * g).toFloat() }

    /** Mean removal, a 20 Hz high-pass, a fixed gain, the tail fade and a range check. As
     * SPLINTER's: a softer strike stays softer. */
    fun finish(x: FloatArray, gain: Double, seconds: Double): Snip {
        val out = x.copyOf(min(x.size, frames(seconds)))
        val mean = out.average().toFloat(); val dc = Dsp.FixedOnePole(20f)
        for (i in out.indices) { val s = out[i] - mean; out[i] = ((s - dc.lp(s)) * gain).toFloat() }
        Dsp.fadeTail(out)
        // A short low hit (a 140 ms kick is six cycles of its note) keeps a residual offset after
        // the high-pass. Take it out under a sine-squared window, which is zero at both ends, so the
        // attack and the silent last sample are untouched and the mean is exactly zero.
        val w = DoubleArray(out.size) { if (it == 0 || it == out.lastIndex) 0.0 else sin(PI * it / (out.size - 1)).pow(2) }
        val c = out.sumOf { it.toDouble() } / max(1e-12, w.sum())
        for (i in out.indices) out[i] = (out[i] - c * w[i]).toFloat()
        check(out.all { it.isFinite() } && out.maxOf { abs(it) } < 1f) { "breakcore pad out of range: ${out.maxOf { abs(it) }}" }
        return Snip(out, 1, Dsp.RATE)
    }

    /** A 1 ms fade-in, for a cut that starts inside something already sounding. */
    fun fadeIn(x: FloatArray): FloatArray {
        val n = min(x.size, frames(.001)); for (i in 0 until n) x[i] *= i.toFloat() / n
        return x
    }

    fun noise(n: Int, seed: Int): FloatArray { val r = Random(seed.toLong()); return FloatArray(n) { (r.nextFloat() * 2 - 1) } }

    /** Exponential decay to silence with a [attackMs] ramp in. */
    fun env(n: Int, tau: Double, attackMs: Double = 1.0): FloatArray {
        val a = max(1, frames(attackMs / 1000))
        return FloatArray(n) { i -> (min(1.0, i.toDouble() / a) * exp(-i / (tau * Dsp.RATE))).toFloat() }
    }

    /** A sine whose frequency follows [hz] (per frame at the base rate). */
    fun sine(n: Int, phase0: Double = 0.0, hz: (Int) -> Double): FloatArray {
        var p = phase0
        return FloatArray(n) { i -> val s = sin(2 * PI * p); p += hz(i) / Dsp.RATE; p -= floor(p); s.toFloat() }
    }

    /** A band-limited (polyBLEP) saw at [hz], rendered at 4x and decimated. */
    fun saw(n: Int, phase0: Double, hz: (Int) -> Double): FloatArray {
        val up = FloatArray(n * Dsp.OVERSAMPLE); var p = phase0; val rate = Dsp.RATE * Dsp.OVERSAMPLE
        for (i in up.indices) {
            val dt = hz(i / Dsp.OVERSAMPLE) / rate
            up[i] = (2 * p - 1 - blep(p, dt)).toFloat(); p += dt; if (p >= 1) p -= 1
        }
        return Dsp.decimate(up, Dsp.RATE).copyOf(n)
    }

    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1 }
        t > 1 - dt -> { val x = (t - 1) / dt; x * x + x + x + 1 }
        else -> 0.0
    }

    /** One pass of the house TPT state-variable filter. [mode] 0 low, 1 band, 2 high. */
    fun svf(x: FloatArray, k: Float, mode: Int, hz: (Int) -> Double): FloatArray {
        val f = Dsp.TptSvf(); return FloatArray(x.size) { i ->
            f.process(x[i], hz(i).toFloat(), k); when (mode) { 0 -> f.low; 1 -> f.band; else -> f.high }
        }
    }

    /** tanh drive, normalised so a full-scale input still reaches full scale. */
    fun drive(x: FloatArray, amount: Double): FloatArray {
        val n = tanh(amount); return FloatArray(x.size) { (tanh(x[it] * amount) / n).toFloat() }
    }

    /** Echo with its repeat time set in beats at [bpm] (Echo's TIME spans 60 to 350 ms). */
    fun echo(x: FloatArray, bpm: Int, beats: Double, repeat: Float, tone: Float, mix: Float): FloatArray {
        val ms = beats * 60000.0 / bpm
        val time = (ln(ms / 60) / ln(350.0 / 60)).coerceIn(0.0, 1.0).toFloat()
        return Echo.process(Snip(x, 1, Dsp.RATE), mapOf("TIME" to time, "REPEAT" to repeat, "TONE" to tone, "MIX" to mix)).samples
    }

    /** Read [x] at a varying [speed] (1 forward, negative backwards), in the 4x domain so
     * a fast spin-back folds nothing into the band. Stops at either end of the buffer. */
    fun spin(x: FloatArray, n: Int, speed: (Int) -> Double): FloatArray {
        val up = Valve.upsample(x, Dsp.RATE); val out = FloatArray(n * Dsp.OVERSAMPLE); var pos = 0.0
        for (i in out.indices) {
            val j = pos.toInt()
            if (j < 0 || j + 1 >= up.size) break
            out[i] = (up[j] + (up[j + 1] - up[j]) * (pos - j)).toFloat()
            pos += speed(i / Dsp.OVERSAMPLE)
        }
        return Dsp.decimate(out, Dsp.RATE).copyOf(n)
    }
}
