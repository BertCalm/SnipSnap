package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.io.File
import kotlin.math.*

/** Spectral projection isolates the output decimator's out-of-band contribution.
 * This does not measure aliases already present in the internal-rate synthesis.
 */
object DrumlineAliasAudit {
    private const val RATE = Dsp.RATE * Dsp.OVERSAMPLE
    data class Measurement(val foldedDb: Double, val folded20To18kDb: Double, val bypassDb: Double, val reconstructionDb: Double)
    internal fun energy(x: FloatArray) = x.sumOf { it.toDouble()*it }
    internal fun db(ratio: Double) = 10*log10(max(1e-30,ratio))

    internal fun bandEnergy(x: FloatArray): Double {
        var size=1
        while(size < x.size)size*=2
        val re=x.copyOf(size);val im=FloatArray(size)
        Fft.forward(re,im)
        var sum=0.0
        for(k in re.indices) {
            val hz=min(k,re.size-k).toDouble()*Dsp.RATE/re.size
            if(hz in 20.0..18000.0)sum+=re[k].toDouble()*re[k]+im[k].toDouble()*im[k]
        }
        return sum/re.size // Parseval: same units as time-domain sum of squares.
    }

    fun measure(filtered: FloatArray): Measurement {
        // Silence separates both ends from the FFT's periodic seam and FIR edge handling.
        val guard=4096
        var size=1
        while(size < filtered.size+2*guard)size*=2
        val padded=FloatArray(size)
        filtered.copyInto(padded,guard)
        val re=padded.copyOf();val im=FloatArray(size)
        Fft.forward(re,im)
        val high=re.copyOf();val highIm=im.copyOf()
        for(k in re.indices) {
            val above=min(k,size-k).toDouble()*RATE/size > Dsp.RATE/2.0
            if(!above) { high[k]=0f;highIm[k]=0f }
        }
        Fft.inverse(high,highIm)
        Fft.inverse(re,im)
        val denominator=energy(Dsp.decimate(padded,Dsp.RATE))
        require(denominator > 0 && denominator.isFinite())
        val folded=Dsp.decimate(high,Dsp.RATE)
        val bypass=energy(FloatArray(size/4) { high[it*4] })
        val residual=energy(FloatArray(size) { re[it]-padded[it] })/energy(padded)
        return Measurement(db(energy(folded)/denominator),db(bandEnergy(folded)/denominator),db(bypass/denominator),db(residual))
    }

    @JvmStatic fun main(args: Array<String>) {
        val file=File(args.firstOrNull() ?: "testkit/drumline-alias.csv")
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { out ->
            out.appendLine("voice,case,velocity,take,folded_db,folded_20_18k_db,bypass_db,fft_reconstruction_db")
            for(voice in DrumlineVoice.entries) for(case in listOf("default","bright_open","bright_damped")) {
                val macros=if(case=="default") emptyMap() else mapOf("TUNE" to 1f,"STRIKE" to 1f,
                    "DAMP" to if(case=="bright_open")0f else 1f,"PLAYERS" to 1f,"TIGHT" to 1f)
                val take=if(case=="default")0 else 2
                val raw=Drumline.renderRaw(voice,macros,1f,take)
                Tide.bandLimit(raw,RATE)
                val m=measure(raw)
                val row="$voice,$case,1.0,$take,${m.foldedDb},${m.folded20To18kDb},${m.bypassDb},${m.reconstructionDb}"
                println(row);out.appendLine(row);out.flush()
                check(m.foldedDb.isFinite() && m.bypassDb.isFinite() && m.reconstructionDb < -110)
            }
        }
    }
}
