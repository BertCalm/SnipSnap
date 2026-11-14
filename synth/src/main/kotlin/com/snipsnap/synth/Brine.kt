package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.*

/**
 * BRINE listening build: a shared recording -> dispersive spring -> phaser -> combo.
 * This is a finite compound processor, not a registered engine or an accepted pedal model.
 * All voicing constants are shape values awaiting the owner's audition.
 * Process a complete phrase to preserve interaction between hits; calls retain no state.
 */
object Brine {
    const val VERSION = 1
    const val TAIL_SECONDS = 1f
    val MACROS = listOf(MacroSpec("ROOM", .2f), MacroSpec("GRIT", .25f),
        MacroSpec("SPLASH", .3f), MacroSpec("SWEEP", .25f),
        MacroSpec("RATE", .2f), MacroSpec("DRIVE", .3f))
    enum class Stage { DRY, RECORDING, SPRING, MODULATION, AMP }

    /** Versioned, complete settings: malformed saved recipes fail instead of changing silently. */
    data class Settings(val room: Float = .2f, val grit: Float = .25f,
        val splash: Float = .3f, val sweep: Float = .25f,
        val rate: Float = .2f, val drive: Float = .3f) {
        init { require(values().values.all { it.isFinite() && it in 0f..1f }) }
        fun values(): Map<String, Float> = linkedMapOf("ROOM" to room, "GRIT" to grit,
            "SPLASH" to splash, "SWEEP" to sweep, "RATE" to rate, "DRIVE" to drive)
        fun toJsonText(): String = Json.write(JsonValue.Obj(linkedMapOf(
            "brine" to JsonValue.Num(VERSION.toDouble()),
            "macros" to JsonValue.Obj(values().mapValues { JsonValue.Num(it.value.toDouble()) }))))
        companion object {
            fun fromJsonText(text: String): Settings {
                val root = Json.parse(text).obj()
                require(root.keys == setOf("brine", "macros"))
                require(root.getValue("brine").num() == VERSION.toDouble())
                val m = root.getValue("macros").obj()
                require(m.keys == MACROS.map { it.name }.toSet())
                fun v(k: String): Float { val x = m.getValue(k).num(); require(x.isFinite() && x in 0.0..1.0); return x.toFloat() }
                return Settings(v("ROOM"), v("GRIT"), v("SPLASH"), v("SWEEP"), v("RATE"), v("DRIVE"))
            }
        }
    }

    fun process(input: Snip, settings: Settings = Settings(), through: Stage = Stage.AMP): Snip {
        require(input.channels == 1 && input.sampleRate == Dsp.RATE) { "BRINE R1 takes mono 44.1 kHz" }
        require(input.samples.all { it.isFinite() }) { "BRINE requires finite input" }
        if (through == Stage.DRY || input.frameCount == 0) return input
        // Allocate one tail for the entire board. Every downstream stage receives that tail.
        var x = input.samples.copyOf(input.frameCount + (TAIL_SECONDS * Dsp.RATE).toInt())
        x = recording(x, settings)
        if (through >= Stage.SPRING && settings.splash > 0f) x = spring(x, settings.splash)
        if (through >= Stage.MODULATION && settings.sweep > 0f) {
            x = Phase.process(Snip(x, 1, Dsp.RATE), mapOf("DEPTH" to settings.sweep,
                "RATE" to settings.rate, "FEEDBACK" to .12f)).samples
        }
        if (through >= Stage.AMP) {
            // Stay below VALVE's high-gain knee. Its speaker still colours DRIVE zero.
            x = Valve.process(Snip(x, 1, Dsp.RATE), mapOf("DRIVE" to (.25f + .35f * settings.drive),
                "SAG" to .18f, "TONE" to .43f, "CAB" to .55f)).samples
        }
        val dc = Dsp.FixedOnePole(20f)
        for (i in x.indices) x[i] -= dc.lp(x[i])
        Dsp.fadeTailCosine(x, .08f)
        // Attenuation only, never lift silence or hide feedback growth by normalising each pass.
        val peak = x.maxOfOrNull { abs(it) } ?: 0f
        check(peak.isFinite()) { "BRINE produced non-finite output" }
        if (peak > .99f) for (i in x.indices) x[i] *= .99f / peak
        return Snip(x, 1, Dsp.RATE)
    }

    private fun recording(x: FloatArray, s: Settings): FloatArray {
        val room = Dsp.FixedOnePole(3800f)
        val delays = intArrayOf(485, 1014, 1631) // 11, 23, 37 ms: short fixed room, no stereo promise.
        val gains = floatArrayOf(.36f, .23f, .14f)
        val out = FloatArray(x.size)
        for (i in x.indices) {
            var reflection = 0f
            for (j in delays.indices) if (i >= delays[j]) reflection += gains[j] * x[i - delays[j]]
            out[i] = x[i] + s.room * room.lp(reflection)
        }
        return if (s.grit == 0f) out else saturate(out, 1f + 5f * s.grit)
    }

    /** Fixed-gain oversampled saturation preserves relative ghost-note levels. */
    internal fun saturate(x: FloatArray, gain: Float): FloatArray {
        val up = Valve.upsample(x, Dsp.RATE)
        for (i in up.indices) up[i] = tanh(up[i] * gain) / sqrt(gain)
        Tide.bandLimit(up, Dsp.RATE * Dsp.OVERSAMPLE)
        return Dsp.decimate(up, Dsp.RATE).copyOf(x.size)
    }

    /**
     * Three independent lossy waveguides. Cascaded first-order allpasses disperse each
     * round trip, so frequencies return at different times instead of a plain room wash.
     * |a| < 1, low-pass magnitude <= 1, feedback <= .79: each frozen loop is contractive.
     * Saturation is only on the feed, never relied upon to stabilise feedback.
     * This is a spring-inspired model, not a fit to a measured tank or named pedal.
     */
    internal fun spring(x: FloatArray, splash: Float, dispersion: Boolean = true): FloatArray {
        val feed = saturate(x, 1f + 7f * splash)
        val hp = Dsp.FixedOnePole(180f)
        val loops = arrayOf(SpringLine(1327, .73f, dispersion),
            SpringLine(1597, .77f, dispersion), SpringLine(1901, .79f, dispersion))
        val tone = Dsp.FixedOnePole(4200f)
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val v = feed[i] - hp.lp(feed[i])
            var wet = 0f
            for (line in loops) wet += line.step(v * .18f)
            out[i] = x[i] + splash * 1.3f * tone.lp(wet)
        }
        return out
    }

    private class SpringLine(length: Int, val feedback: Float, val dispersion: Boolean) {
        val delay = FloatArray(length)
        var at = 0
        val loss = Dsp.FixedOnePole(4800f)
        val states = FloatArray(12)
        fun step(input: Float): Float {
            var v = delay[at]
            if (dispersion) for (j in states.indices) {
                val a = .52f + .025f * j
                val y = states[j] - a * v
                states[j] = v + a * y
                v = y
            }
            val damped = loss.lp(v)
            delay[at] = input + feedback * damped
            at = (at + 1) % delay.size
            return damped
        }
    }
}
