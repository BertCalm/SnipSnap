package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.*

/** R7 measured-envelope spectral resynthesis. Profiles derive from CC0 recordings;
 * original waveform phases are discarded. See resources/brine/SOURCES.json and LICENSE.
 * This is reference-derived sound design, not a physical cymbal simulation.
 */
object BrineReferenceCymbals {
    const val VERSION = 7
    private data class Profile(val frames: Int, val times: DoubleArray, val magnitudes: List<FloatArray>)
    private fun profile(id: String): Profile {
        val text = checkNotNull(javaClass.getResourceAsStream("/brine/$id.json")).bufferedReader().use { it.readText() }
        val obj = (Json.parse(text) as JsonValue.Obj).entries
        fun number(k: String) = (obj.getValue(k) as JsonValue.Num).value
        require(number("fftSize")==2048.0 && number("hop")==256.0 && number("sampleRate")==44100.0)
        val times=(obj.getValue("times") as JsonValue.Arr).items.map { (it as JsonValue.Num).value }.toDoubleArray()
        val mags=(obj.getValue("magnitude") as JsonValue.Arr).items.map { row ->
            (row as JsonValue.Arr).items.map { (it as JsonValue.Num).value.toFloat() }.toFloatArray()
        }
        require(mags.size==times.size && mags.all { it.size==1025 && it.all { x -> x.isFinite() && x>=0f } })
        return Profile(number("frames").toInt(),times,mags)
    }
    private val ride by lazy { synthesize("ride-main") }
    private val crash by lazy { synthesize("crash-main") }
    private val choke by lazy { synthesize("choke-main") }

    private fun synthesize(id: String, take: Int = 0): FloatArray {
        val p=profile(id); val n=2048; val hop=256
        val win=FloatArray(n) { (.5-.5*cos(2*PI*it/n)).toFloat() }
        val re=FloatArray(n); val im=FloatArray(n); val out=FloatArray(p.frames)
        val noise=Dsp.Noise(if (take == 0) Dsp.seedFor("BRINE","REFERENCE",VERSION,id)
            else Dsp.seedFor("BRINE","REFERENCE",VERSION,id,"take",take))
        var left=0; var center=0
        while (center < p.frames+n/2) {
            val t=center.toDouble()/Dsp.RATE
            while (left < p.times.size-2 && t>p.times[left+1]) left++
            val blend=((t-p.times[left])/(p.times[left+1]-p.times[left])).coerceIn(0.0,1.0).toFloat()
            re.fill(0f);im.fill(0f)
            for (k in 1 until n/2) {
                val a=if(t>p.times.last()) 0f else p.magnitudes[left][k]*(1-blend)+p.magnitudes[left+1][k]*blend
                val phase=noise.next()*PI
                re[k]=(a*cos(phase)).toFloat();im[k]=(a*sin(phase)).toFloat()
                re[n-k]=re[k];im[n-k]=-im[k]
            }
            Fft.inverse(re,im)
            // Linear, band-limited synthesis at output rate: no nonlinear stage or alias source.
            // Overlapping Hann frames keep independently generated phases from making seams.
            for(i in 0 until n) {
                val at=center+i-n/2
                if(at in out.indices) out[at]+=re[i]*win[i]
            }
            center+=hop
        }
        Dsp.normalize(out,.9f)
        for(i in 0 until minOf(88,out.size)) out[i]*=i/87f
        Dsp.fadeTailCosine(out,.05f)
        return out
    }
    internal fun source(v: BrineCymbals.Voice): FloatArray = when(v) {
        BrineCymbals.Voice.RIDE -> ride.copyOf()
        BrineCymbals.Voice.CRASH -> crash.copyOf()
        BrineCymbals.Voice.CHOKED_CRASH -> choke.copyOf()
        BrineCymbals.Voice.HALF_HAT -> BrineCymbals.source(v)
    }
    // Two bounded cached phase realizations; the approved take zero remains byte-identical.
    private val alternateRide by lazy { synthesize("ride-main", 1) }
    internal fun rideSource(velocity: Float, take: Int): FloatArray {
        require(velocity in 0f..1f && take in 0..1)
        val x = (if (take == 0) ride else alternateRide).copyOf()
        // Proposed touch law, not a fitted soft/hard recording: gentler strikes excite less treble.
        val low = Dsp.FixedOnePole(3500f)
        val shade = .55f * (1f - velocity)
        for (i in x.indices) x[i] = x[i] * (1f-shade) + low.lp(x[i])*shade
        return x
    }
    /** Identical pickup/recording path for measured references and synthesized candidates. */
    fun record(v: BrineCymbals.Voice, samples: FloatArray): Snip {
        val x=samples.copyOf(); Dsp.normalize(x,.9f)
        val e=BrineMetal.revised(BrineCymbals.event(v,0.0))
        return BrineKitExpansion.renderEvents(listOf(e), x.size.toDouble()/Dsp.RATE+.1,
            sourceOverride = { x })
    }
    fun render(v: BrineCymbals.Voice): Snip = record(v,source(v))
    fun renderGroove(style: BrineCymbals.Groove): Snip = BrineKitExpansion.renderEvents(
        BrineCymbals.events(style).map(BrineMetal::revised),9.0,sourceOverride = { e ->
            e.cymbal?.takeIf { it!=BrineCymbals.Voice.HALF_HAT }?.let(::source)
        })
}
