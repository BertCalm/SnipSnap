package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.*

/**
 * TRIAD: an audition-stage groove composer with a shared processing bus.
 * Existing generated drum voices supply all hits; no recordings of the references.
 * Not a Patch engine: tempo and finite bars belong to this arrangement, not HOLD.
 * Reuse a Source when changing treatments so expensive hit synthesis happens once.
 */
object Triad {
    const val MODEL_VERSION = 1
    enum class Rig { DRY, WAREHOUSE, SUBMERGED, FRACTURE }
    data class Settings(val bpm: Int = 124, val bars: Int = 4, val tune: Float = .5f,
        val weight: Float = .65f, val swing: Float = .38f, val density: Float = .7f) {
        init {
            require(bpm in 90..150 && bars in 1..8)
            require(listOf(tune, weight, swing, density).all { it.isFinite() && it in 0f..1f })
        }
    }
    data class Event(val frame: Int, val lane: String, val velocity: Float)
    data class Source(val settings: Settings, val frames: Int, val events: List<Event>,
        val stems: Map<String, FloatArray>) {
        fun mix(): FloatArray = FloatArray(frames).also { out ->
            for (stem in stems.values) for (i in out.indices) out[i] += stem[i]
        }
    }
    data class Stages(val first: FloatArray, val output: FloatArray)

    fun source(s: Settings = Settings()): Source {
        val beat = Dsp.RATE * 60.0 / s.bpm
        val frames = (s.bars * 4 * beat).roundToInt() + 3 * Dsp.RATE
        val events = mutableListOf<Event>()
        fun hit(step: Int, lane: String, velocity: Float, swing: Boolean = true) {
            val delay = if (swing && step % 2 == 1) .30 * s.swing else 0.0
            events += Event(((step + delay) * beat / 4).roundToInt(), lane, velocity)
        }
        for (bar in 0 until s.bars) {
            val b = bar * 16
            for (step in 0..15) {
                if (step % 4 == 0) { hit(b + step, "thud", .95f, false); hit(b + step, "sub", .95f, false) }
                if (step == 4 || step == 12) hit(b + step, "clap", .8f, false)
                if (step % 4 == 2) hit(b + step, "open-hat", .63f)
                else if (step % 2 == 0 || s.density > .8f) hit(b + step, "closed-hat", if (step % 2 == 0) .38f else .20f)
                if (step % 2 == 0 || s.density > .35f) hit(b + step, "shaker", if (step % 2 == 0) .34f else .54f)
            }
            for (step in listOf(3, 7, 10, 14)) hit(b + step, "conga", if (step == 10) .85f else .61f)
            if (s.density > .25f) for (step in listOf(1, 6, 11, 15)) hit(b + step, "bongo", if (step == 15) .73f else .45f)
            if (s.density > .65f) hit(b + 9, "rim", .35f)
        }
        val t = (s.tune - .5f) * .25f
        fun th(v: ThumpVoice, vararg m: Pair<String, Float>) = Thump.render(v, mapOf(*m)).samples
        val hits = linkedMapOf(
            "thud" to th(ThumpVoice.KICK, "TUNE" to .45f + t, "DECAY" to .22f, "SWEEP" to .65f, "CLICK" to .6f, "DRIVE" to .42f),
            "sub" to th(ThumpVoice.KICK, "TUNE" to .22f + t, "DECAY" to .7f, "SWEEP" to .2f, "CLICK" to .1f, "DRIVE" to .08f),
            "clap" to th(ThumpVoice.CLAP, "SPREAD" to .3f, "DECAY" to .3f, "TONE" to .65f),
            "open-hat" to th(ThumpVoice.HAT_OPEN, "METAL" to .85f, "DECAY" to .5f, "NOISE" to .15f),
            "closed-hat" to th(ThumpVoice.HAT_CLOSED, "METAL" to .8f, "DECAY" to .22f),
            "shaker" to Skin.render(SkinVoice.SHAKER, mapOf("DECAY" to .2f, "GRAIN" to .65f, "SWELL" to .25f)).samples,
            "conga" to Terra.render(TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to .34f + t, "DECAY" to .28f, "POS" to .28f, "FORCE" to .6f, "DROOP" to .1f)).samples,
            "bongo" to Terra.render(TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to .6f + t, "DECAY" to .12f, "POS" to .65f, "FORCE" to .75f, "DROOP" to .06f)).samples,
            "rim" to th(ThumpVoice.RIM, "DECAY" to .15f))
        val stems = hits.mapValues { FloatArray(frames) }
        val sorted = events.sortedBy { it.frame }
        for ((idx, e) in sorted.withIndex()) {
            val sample = hits.getValue(e.lane)
            val hat = e.lane.endsWith("hat")
            val next = if (hat) sorted.drop(idx + 1).firstOrNull { it.lane.endsWith("hat") }?.frame
                ?: Int.MAX_VALUE else Int.MAX_VALUE
            val count = min(sample.size, min(next - e.frame, frames - e.frame))
            val gain = when (e.lane) {
                "thud" -> .7f; "sub" -> .18f + .65f * s.weight
                "clap" -> .58f; "conga", "bongo" -> .48f; "rim" -> .2f; else -> .26f
            } * e.velocity
            for (i in 0 until count) {
                // Retain natural releases after the final bar; hats close over 3 ms.
                val choke = if (hat) ((count - 1 - i) / 132f).coerceIn(0f, 1f) else 1f
                stems.getValue(e.lane)[e.frame + i] += sample[i] * gain * choke
            }
        }
        return Source(s, frames, sorted, stems)
    }

    /** Stages return unlevelled PCM. Stage one is always upstream of stage two. */
    fun process(input: FloatArray, bpm: Int, rig: Rig, drive: Float = .55f, space: Float = .55f): Stages {
        require(bpm in 90..150 && input.isNotEmpty() && input.all { it.isFinite() })
        require(drive.isFinite() && drive in 0f..1f && space.isFinite() && space in 0f..1f)
        if (rig == Rig.DRY) return Stages(input.copyOf(), input.copyOf())
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val x = Resampler.resample(Snip(input, 1, Dsp.RATE), rate).samples
        val first: FloatArray
        val wet: FloatArray
        when (rig) {
            Rig.WAREHOUSE -> {
                first = compress(x, rate, .10f + .10f * (1-drive), 2f + drive*10, .0008, .08 + space*.18)
                val low = Dsp.FixedOnePole(190f, rate)
                val tone = Dsp.FixedOnePole(9500f - drive*3500, rate)
                wet = FloatArray(x.size) { i ->
                    val sub = low.lp(first[i])
                    val pre = first[i] + (first[i] - sub) * .6f
                    val g = 1f + 13f*drive
                    tone.lp((tanh(pre*g) * .82f + tanh(pre*g*2).coerceIn(-.65f,.65f)*.18f) / sqrt(g))
                }
            }
            Rig.SUBMERGED -> {
                val c = compress(x, rate, .24f, 1.4f + drive, .012, .16)
                first = FloatArray(x.size) { i -> .35f*x[i] + .65f*tanh(c[i]*(1+.8f*drive))/(1+.8f*drive) }
                wet = tape(first, rate, bpm, space)
            }
            Rig.FRACTURE -> {
                val steps = 2.0.pow((12 - drive*8).toDouble()).toFloat()
                val hold = (4 + drive*72).roundToInt()
                var held = 0f
                first = FloatArray(x.size) { i ->
                    if (i % hold == 0) held = (sin(x[i]*(1+drive*7))*steps).roundToInt()/steps
                    held
                }
                wet = grains(first, rate, bpm, space)
            }
            else -> error("handled above")
        }
        fun down(a: FloatArray): FloatArray { Tide.bandLimit(a,rate); return Dsp.decimate(a,rate) }
        return Stages(down(first), down(wet))
    }

    private fun compress(x: FloatArray, rate: Int, threshold: Float, ratio: Float, attack: Double, release: Double): FloatArray {
        val a = exp(-1/(rate*attack)).toFloat(); val r = exp(-1/(rate*release)).toFloat()
        var env = 0f
        return FloatArray(x.size) { i ->
            val v = abs(x[i]); val k = if(v>env) a else r
            env = k*env + (1-k)*v
            val gain = if(env > threshold) (threshold/env).pow(1-1/ratio) else 1f
            x[i]*gain
        }
    }
    private fun tape(x: FloatArray, rate: Int, bpm: Int, space: Float): FloatArray {
        val beat = 60.0/bpm*rate
        val line = FloatArray((beat*2+100).toInt())
        val lp = Dsp.FixedOnePole(5200f-space*3000,rate)
        val bass = Dsp.FixedOnePole(180f,rate)
        var w = 0
        return FloatArray(x.size) { i ->
            val wobble = sin(2*PI*i/rate*.7)*rate*.0015*space + sin(2*PI*i/rate*7.1)*rate*.00015*space
            val taps = .6f*Dsp.tap(line,w,(beat*.75+wobble).toFloat()) + .4f*Dsp.tap(line,w,(beat*1.5+wobble).toFloat())
            // Convex taps, passive lowpass and tanh with slope <=1; feedback <=.68.
            val returnSignal = lp.lp(tanh(taps))
            val high = x[i]-bass.lp(x[i])
            line[w] = high + (.2f+.48f*space)*returnSignal
            w = (w+1)%line.size
            x[i] + (.12f+.85f*space)*returnSignal
        }
    }
    private fun grains(x: FloatArray, rate: Int, bpm: Int, space: Float): FloatArray {
        val out = FloatArray(x.size) { x[it]*(1-.45f*space) }
        val hop = (rate*60.0/bpm/4).roundToInt()
        val length = (hop*(.55+space)).roundToInt()
        val noise = Dsp.Noise(Dsp.seedFor("TRIAD",MODEL_VERSION,"grains"))
        for (start in hop*8 until x.size step hop) {
            // Keep the entire forward or reverse window inside recorded history.
            val lag = min(hop*(4 + ((noise.next()+1)*3).toInt()), start-length*2-1)
            val reverse = noise.next() > .25f
            val speed = if(noise.next() > .4f) 2 else 1
            for(j in 0 until min(length,x.size-start)) {
                // Every grain reads only already-recorded input; bounded two-grain overlap, no feedback.
                val read = start-lag + if(reverse) -j*speed else j*speed
                if(read !in 0 until start) continue
                val window = (.5-.5*cos(2*PI*j/(length-1))).toFloat()
                out[start+j] += x[read]*window*(.2f+.7f*space)
            }
        }
        return out
    }
    fun finish(x: FloatArray): Snip {
        val out = x.copyOf()
        val mean = out.average().toFloat(); val dc = Dsp.FixedOnePole(20f)
        for(i in out.indices) { val v = out[i]-mean; out[i]=v-dc.lp(v) }
        Dsp.levelTo(out,Dsp.RATE,Dsp.MELODIC_LOUDNESS_TARGET)
        for (i in 0 until min(88,out.size)) out[i] *= i/88f
        Dsp.fadeTail(out)
        return Snip(out,1,Dsp.RATE)
    }
}
