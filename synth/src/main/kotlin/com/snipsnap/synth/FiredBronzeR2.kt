package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.*

/** Dry analysis/resynthesis study. V1 remains replayable in FiredBronze.
 * Five profiles derive from ajaysm's CC BY 4.0 tabla previews; see resource NOTICE.
 * Kettle/gong frequencies are published measurements; their amplitudes/decays,
 * pressure gesture and pot model are explicitly uncalibrated hypotheses.
 * No PCM playback or membrane solver. No factory registration or kit promotion.
 */
object FiredBronzeR2 {
    const val VERSION = 2
    enum class Voice(val label: String) {
        TIN("Edge Ring"), TUN("Center Ring"), NA("Rim Ring"), TE("Pressed Tap"), GA("Bass Stroke"),
        PRESSURE("Moving Bass"), CLAY_OPEN("Open Clay"), CLAY_CLOSED("Covered Clay"),
        KETTLE("Measured Kettle"), GONG("Long Gong")
    }
    data class Recipe(val voice: Voice, val tune: Float = .5f, val touch: Float = .5f,
                      val damp: Float = 0f, val motion: Float = .5f,
                      val velocity: Float = 1f, val take: Int = 0) {
        init { require(listOf(tune, touch, damp, motion, velocity).all { it.isFinite() && it in 0f..1f }) }
        fun render(): Snip = FiredBronzeR2.render(this)
        fun toJsonText(): String = Json.write(JsonValue.Obj(linkedMapOf(
            "study" to JsonValue.Str("FIRED_BRONZE_DRY"), "version" to JsonValue.Num(VERSION.toDouble()),
            "voice" to JsonValue.Str(voice.name), "tune" to JsonValue.Num(tune.toDouble()),
            "touch" to JsonValue.Num(touch.toDouble()), "damp" to JsonValue.Num(damp.toDouble()),
            "motion" to JsonValue.Num(motion.toDouble()), "velocity" to JsonValue.Num(velocity.toDouble()),
            "take" to JsonValue.Num(take.toDouble()))))
        companion object {
            fun fromJsonText(text: String): Recipe {
                val o = Json.parse(text).obj()
                require(o.getValue("study").str() == "FIRED_BRONZE_DRY" && o.getValue("version").num() == VERSION.toDouble())
                fun f(k: String) = o.getValue(k).num().toFloat()
                return Recipe(Voice.valueOf(o.getValue("voice").str()), f("tune"), f("touch"), f("damp"), f("motion"), f("velocity"), o.getValue("take").int())
            }
        }
    }
    private data class Partial(val hz: Double, val real: DoubleArray, val imag: DoubleArray)
    private data class Band(val lo: Double, val hi: Double, val rms: DoubleArray)
    private data class Profile(val seconds: Double, val hop: Double, val modes: List<Partial>, val noise: List<Band>)
    private val profiles by lazy {
        Voice.entries.take(5).associateWith { voice ->
            val text = checkNotNull(javaClass.getResourceAsStream("/fired-bronze-r2/${voice.name.lowercase()}.json")).bufferedReader().use { it.readText() }
            val o = Json.parse(text).obj()
            fun array(v: JsonValue) = v.arr().map { it.num() }.toDoubleArray()
            Profile(o.getValue("seconds").num(), o.getValue("hopSeconds").num(),
                o.getValue("modes").arr().map { v -> val m = v.obj(); Partial(m.getValue("hz").num(), array(m.getValue("real")), array(m.getValue("imag"))) },
                o.getValue("noise").arr().map { v -> val b = v.obj(); Band(b.getValue("lo").num(), b.getValue("hi").num(), array(b.getValue("rms"))) })
        }
    }
    private fun lerp(a: DoubleArray, pos: Double): Double {
        val i = pos.toInt().coerceIn(0, a.lastIndex)
        return if (i == a.lastIndex) a[i] else a[i] + (a[i + 1] - a[i]) * (pos - i)
    }
    private fun tilt(hz: Double, touch: Float): Double = 2.0.pow((touch - .5) * ln((hz / 600).coerceIn(.25, 8.0)) / ln(2.0))

    fun render(r: Recipe): Snip {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = raw(r.copy(touch = r.touch * (.35f + .65f * r.velocity)), rate)
        Tide.bandLimit(raw, rate)
        val out = Dsp.decimate(raw, Dsp.RATE)
        val mean = out.average().toFloat()
        val hp = Dsp.FixedOnePole(20f)
        for (i in out.indices) { val x = out[i] - mean; out[i] = x - hp.lp(x) }
        // House levelling precedes the single velocity gain. Touch changes the
        // spectral distribution; native velocity is never peak matched here.
        Dsp.levelTo(out, Dsp.RATE, Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTailCosine(out, .008f)
        for (i in out.indices) out[i] *= r.velocity * min(1f, i / (Dsp.RATE * .001f))
        return Snip(out, 1, Dsp.RATE)
    }

    internal fun raw(r: Recipe, rate: Int): FloatArray {
        require(rate >= Dsp.RATE)
        val profile = profiles[r.voice]
        return if (profile != null) fitted(r, profile, rate) else constructed(r, rate)
    }

    private fun fitted(r: Recipe, p: Profile, rate: Int): FloatArray {
        val out = FloatArray(ceil(p.seconds * rate).toInt())
        val pitch = 2.0.pow((r.tune - .5) * 2)
        for (m in p.modes) {
            if (m.hz * pitch > 17000) continue
            val step = 2 * PI * m.hz * pitch / rate
            val c = cos(step); val s = sin(step)
            var re = 1.0; var im = 0.0
            val gain = tilt(m.hz, r.touch)
            var damping = 1.0; val loss = exp(-12.0 * r.damp / rate)
            for (i in out.indices) {
                val at = i.toDouble() / rate / p.hop
                out[i] += ((lerp(m.real, at) * re - lerp(m.imag, at) * im) * gain * damping).toFloat()
                val next = re * c - im * s; im = im * c + re * s; re = next
                damping *= loss
            }
        }
        for ((b, band) in p.noise.withIndex()) {
            val noise = Dsp.Noise(Dsp.seedFor("FIRED_BRONZE_R2_RESIDUAL", r.voice.name, r.take, b))
            val low = Dsp.FixedOnePole(band.lo.toFloat(), rate); val high = Dsp.FixedOnePole(band.hi.toFloat(), rate)
            val a = 1 - exp(-2 * PI * band.lo / rate); val z = 1 - exp(-2 * PI * band.hi / rate)
            // Analytic variance of the difference of two one-poles driven by
            // uniform [-1,1] noise. Keeps the measured RMS independent of rate.
            val variance = (a / (2-a) + z / (2-z) - 2*a*z / (1-(1-a)*(1-z))) / 3
            val scale = tilt(sqrt(band.lo * band.hi), r.touch) / sqrt(variance)
            for (i in out.indices) {
                val n = noise.next(); val filtered = high.lp(n) - low.lp(n)
                out[i] += (filtered * scale * lerp(band.rms, i.toDouble() / rate / p.hop) * exp(-12.0 * r.damp * i / rate)).toFloat()
            }
        }
        return out
    }

    // Frequency rows are absolute Hz, not a universal material law.
    internal val kettleHz = doubleArrayOf(595.0, 904.0, 2030.0, 2326.0)
    internal val gongHz = doubleArrayOf(91.59,132.92,151.44,158.65,182.44,224.97,241.24,256.29,274.84)
    internal fun pressure(t: Double, motion: Float): Double {
        val p = when { t < .035 -> 0.0; t < .20 -> .5-.5*cos(PI*(t-.035)/.165)
            t < .32 -> 1.0; t < .85 -> .5+.5*cos(PI*(t-.32)/.53); else -> 0.0 }
        return p * motion
    }
    private fun constructed(r: Recipe, rate: Int): FloatArray {
        val pot = r.voice == Voice.CLAY_OPEN || r.voice == Voice.CLAY_CLOSED
        val seconds = when(r.voice) { Voice.GONG -> 8.0; Voice.KETTLE -> 3.5; Voice.PRESSURE -> 1.8; else -> 1.0 }
        val hz = when(r.voice) { Voice.GONG -> gongHz; Voice.KETTLE -> kettleHz
            Voice.PRESSURE -> doubleArrayOf(120.8,237.5,244.0,360.0,489.5,645.3)
            else -> doubleArrayOf(185.0,390.0,674.7,1127.1,1638.0) }
        // All gains/T60 below are proposed shapes, unlike the cited gong frequencies.
        val gains = when(r.voice) { Voice.GONG -> doubleArrayOf(1.0,.15,.20,.19,.65,.09,.12,.11,.36)
            Voice.KETTLE -> doubleArrayOf(1.0,.55,.35,.25)
            Voice.PRESSURE -> doubleArrayOf(1.0,.24,.18,.2,.10,.06)
            else -> doubleArrayOf(.75,1.0,.6,.3,.12) }
        val t60 = when(r.voice) { Voice.GONG -> doubleArrayOf(7.0,2.0,2.8,2.7,4.6,1.2,1.5,1.4,3.3)
            Voice.KETTLE -> doubleArrayOf(1.5,2.6,.35,.25)
            Voice.PRESSURE -> doubleArrayOf(1.6,.65,.60,.5,.25,.18)
            else -> doubleArrayOf(.40,.25,.14,.09,.05) }
        val out = FloatArray((seconds * rate).toInt())
        val pitch = 2.0.pow((r.tune-.5)*2)
        for (j in hz.indices) {
            var phase = 0.0; var envelope = 1.0
            val loss = exp(-(6.907755/t60[j]+12*r.damp)/rate)
            val gain = gains[j] * tilt(hz[j],r.touch) / gains.sum()
            for (i in out.indices) {
                val t = i.toDouble()/rate
                val press = if(r.voice == Voice.PRESSURE) pressure(t,r.motion) else 0.0
                val ratio = when {
                    r.voice == Voice.PRESSURE -> 2.0.pow(press * 6 / 12)
                    r.voice == Voice.GONG -> 1 - .022*r.motion*exp(-t/.22)
                    pot && j == 0 -> { val area = if(r.voice == Voice.CLAY_CLOSED) .30 else .95
                        sqrt(area + .04*r.motion*sin(2*PI*3*t)*exp(-t/.25)) }
                    else -> 1.0
                }
                phase += 2*PI*hz[j]*pitch*ratio/rate
                val attack = 1-exp(-t/if(pot && j==0) .008 else .0015)
                // Pressure alters both frequency and loss, mode-dependently.
                if(press>0) envelope *= exp(-press*(2+j*.6)/rate)
                out[i] += (gain * envelope * attack * sin(phase)).toFloat()
                envelope *= loss
            }
        }
        if(pot) {
            val noise = Dsp.Noise(Dsp.seedFor("FIRED_BRONZE_R2_CLAY",r.take))
            val lo = Dsp.FixedOnePole(900f,rate);val hi = Dsp.FixedOnePole(9000f,rate)
            for(i in out.indices) { val n=noise.next();out[i] += ((hi.lp(n)-lo.lp(n)) * (.10+.2*r.touch) * exp(-i.toDouble()/rate/.004)).toFloat() }
        }
        return out
    }
}
