package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.*

/** Compact fitted resonances and envelopes, not a sampled attack or recorded residual.
 * CC0 calibration: Virtuosity Drums (Versilian Studios/Austin McMahon) and VSCO CE
 * (Sam Gossner/Simon Dalzell, edits by Elan Hickler). See scripts/east_africa provenance.
 * The accepted Python study used PCG64; this native revision uses house seeded noise.
 * Filter coefficients and scalar take offsets preserve the fitted spectral model.
 */
internal object EastAfricaModel {
    private const val RATE = 176400
    private fun resource(name: String): JsonValue = Json.parse(requireNotNull(javaClass.getResourceAsStream("/east-africa/$name.json")).bufferedReader().use { it.readText() })
    private fun JsonValue.obj() = (this as JsonValue.Obj).entries
    private fun JsonValue.arr() = (this as JsonValue.Arr).items
    private fun JsonValue.num() = (this as JsonValue.Num).value
    private fun JsonValue.numbers() = arr().map { it.num() }.toDoubleArray()
    private fun JsonValue.rows() = arr().map { it.numbers() }
    private val models by lazy { resource("models").obj().getValue("voices").obj() }
    private val calibration by lazy { resource("calibration").obj() }
    private val native by lazy { resource("native").obj() }
    private val lowpass by lazy { native.getValue("lowpass").rows() }
    private val highpass by lazy { native.getValue("highpass").rows() }
    private val fir by lazy { native.getValue("fir").numbers() }
    private data class NoiseKey(val voice: String, val take: Int, val tune: Double, val n: Int)
    // Six carriers cover two voices' three takes. Bound memory rather than retaining all kits.
    private val cache = object : LinkedHashMap<NoiseKey, List<DoubleArray>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NoiseKey,List<DoubleArray>>) = size > 6
    }
    private class Sos(coefficients: List<DoubleArray>) {
        private val c = coefficients
        private val a = DoubleArray(c.size)
        private val b = DoubleArray(c.size)
        fun step(input: Double): Double {
            var x = input
            for(i in c.indices) {
                val q=c[i]; val y=q[0]*x+a[i]
                a[i]=q[1]*x-q[4]*y+b[i]; b[i]=q[2]*x-q[5]*y; x=y
            }
            return x
        }
    }
    private class Gaussian(seed: Int) {
        private val noise=Dsp.Noise(seed)
        private var spare: Double?=null
        fun next(): Double {
            spare?.let { spare=null; return it }
            val r=sqrt(-2*ln(((noise.next().toDouble()+1)*.5).coerceIn(1e-12,1.0)))
            val phase=PI*(noise.next()+1)
            spare=r*sin(phase)
            return r*cos(phase)
        }
    }
    private fun noise(voice: String, take: Int, tune: Double, n: Int): List<DoubleArray> = synchronized(cache) {
        cache.getOrPut(NoiseKey(voice,take,tune,n)) {
            val filters=native.getValue("filters").obj().getValue(tune.toString()).arr()
            filters.mapIndexed { band, value ->
                val p=value.obj(); val filter=Sos(p.getValue("sos").rows()); val norm=p.getValue("norm").num()
                val random=Gaussian(Dsp.seedFor("EAST_AFRICA",1,voice,take,band))
                repeat(RATE/5) { filter.step(random.next()) }
                DoubleArray(n) { filter.step(random.next())/norm }
            }
        }
    }
    private fun decay(x: Double) = exp(-min(x,80.0))
    fun render(voice: String, zone: Int, take: Int, tune: Double, damp: Double): FloatArray {
        val log=voice=="low-log" || voice=="high-log"
        val layers=if(log) emptyList() else models.getValue(voice).arr()
        val duration=if(log) .95 else min(1.2,layers.maxOf { it.obj().getValue("tail_end").num() }/damp+.04)
        val n=(duration*RATE).roundToInt(); val x=DoubleArray(n)
        val velocity=doubleArrayOf(.34,.64,1.0)[zone]
        if(log) {
            val f=(if(voice=="low-log")49.0 else 73.41619198)*tune
            for(i in x.indices) {
                val t=i.toDouble()/RATE; val phase=2*PI*f*t; val attack=1-exp(-t/.0007)
                x[i]=.27*velocity*sin(phase+(.55+2.8*velocity)*exp(-t/(.020+.0015*take))*sin(2*phase))*attack*exp(-t/(.22/damp)) +
                    .025*velocity*sin(3*phase)*attack*exp(-t/.075)
            }
        } else {
            val p=layers[zone].obj()
            val offsets=native.getValue("offsets").obj().getValue(voice).arr()[zone].arr()[take].obj()
            val modeOffsets=offsets.getValue("modes").rows()
            for((j,m) in p.getValue("modes").arr().withIndex()) {
                val q=m.obj(); val offset=modeOffsets[j]
                val hz=q.getValue("hz").num()*tune*(1+offset[0])
                if(hz>17500) continue
                val phase=q.getValue("phase").num()+offset[1]
                val amp=q.getValue("amp").num()*(1+offset[2]); val tau=q.getValue("tau").num()
                for(i in x.indices) {
                    val t=i.toDouble()/RATE
                    x[i]+=amp*cos(2*PI*hz*t+phase)*(1-exp(-t/.00045))*decay(t*damp/tau)
                }
            }
            val carriers=noise(voice,take,tune,n); val noiseOffsets=offsets.getValue("noise").numbers()
            for((j,b) in p.getValue("noise").arr().withIndex()) {
                val q=b.obj(); val attack=q.getValue("attack").num(); val fast=q.getValue("fast").num()/damp
                val slow=q.getValue("slow").num()/damp; val mix=q.getValue("mix").num()
                val amp=q.getValue("amp").num()*(1+noiseOffsets[j]); val carrier=carriers[j]
                for(i in x.indices) {
                    val t=i.toDouble()/RATE
                    x[i]+=amp*(1-decay(t/attack))*(mix*decay(t/fast)+(1-mix)*decay(t/slow))*carrier[i]
                }
            }
            val end=p.getValue("tail_end").num()/damp
            for(i in x.indices) x[i]*=((end-i.toDouble()/RATE)/.015).coerceIn(0.0,1.0)
        }
        // Preserve the study's output transfer function and fixed gain: no per-hit leveling.
        val lp=Sos(lowpass); for(i in x.indices) x[i]=lp.step(x[i])
        val out=FloatArray(52920); val hp=Sos(highpass); val length=(n+3)/4
        val gain=(calibration[voice]?.numbers()?.get(zone) ?: 1.0)*EastAfricaKits.BANK_GAIN
        for(i in 0 until min(length,out.size)) {
            var y=0.0
            for(j in fir.indices) {
                val at=i*4+40-j
                if(at in x.indices) y+=x[at]*fir[j]
            }
            y=hp.step(y)
            val fade=if(i>=length-441) (length-1-i)/440.0 else 1.0
            out[i]=(y*fade*gain).toFloat()
        }
        return out
    }
}
