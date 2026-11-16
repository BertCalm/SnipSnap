package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.io.File
import kotlin.math.min

/** Same-passband reference: spectral removal of out-of-band energy before the real FIRs.
 * Total error includes production buffer edges, so it is deliberately not called alias power.
 */
object DrumlineEdgeReference {
    private const val RATE = Dsp.RATE * Dsp.OVERSAMPLE
    data class Result(val referenceDb: Double, val reference20To18kDb: Double,
                      val edgeDb: Double, val projectedDb: Double, val decompositionDb: Double)

    fun measure(filtered: FloatArray, guard: Int = 4096): Result {
        require(guard >= 64 && guard % 4 == 0 && filtered.isNotEmpty())
        var size=1
        while(size < filtered.size+2*guard)size*=2
        val padded=FloatArray(size)
        filtered.copyInto(padded,guard)
        val low=padded.copyOf();val imaginary=FloatArray(size)
        Fft.forward(low,imaginary)
        val high=FloatArray(size);val highImaginary=FloatArray(size)
        for(k in low.indices) {
            if(min(k,size-k).toDouble()*RATE/size > Dsp.RATE/2.0) {
                high[k]=low[k];highImaginary[k]=imaginary[k]
                low[k]=0f;imaginary[k]=0f
            }
        }
        Fft.inverse(low,imaginary)
        Fft.inverse(high,highImaginary)
        val actual=Dsp.decimate(filtered,Dsp.RATE)
        fun outputWindow(x: FloatArray): FloatArray {
            val y=Dsp.decimate(x,Dsp.RATE)
            return y.copyOfRange(guard/4,guard/4+actual.size)
        }
        val reference=outputWindow(low)
        val projected=outputWindow(high)
        val paddedActual=outputWindow(padded)
        val difference=FloatArray(actual.size) { actual[it]-reference[it] }
        val edge=FloatArray(actual.size) { actual[it]-paddedActual[it] }
        val residual=FloatArray(actual.size) { difference[it]-edge[it]-projected[it] }
        val denominator=DrumlineAliasAudit.energy(actual)
        require(denominator > 0 && denominator.isFinite())
        fun ratio(x: FloatArray)=DrumlineAliasAudit.db(DrumlineAliasAudit.energy(x)/denominator)
        return Result(ratio(difference),
            DrumlineAliasAudit.db(DrumlineAliasAudit.bandEnergy(difference)/denominator),
            ratio(edge),ratio(projected),ratio(residual))
    }

    @JvmStatic fun main(args: Array<String>) {
        val file=File(args.firstOrNull() ?: "testkit/drumline-edge-reference.csv")
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { out ->
            out.appendLine("voice,case,velocity,take,reference_db,reference_20_18k_db,edge_db,projected_db,decomposition_db")
            for(voice in DrumlineVoice.entries) for(case in listOf("default","bright_open","bright_damped"))
                for(velocity in listOf(.05f,1f)) {
                    val macros=if(case=="default") emptyMap() else mapOf("TUNE" to 1f,"STRIKE" to 1f,
                        "DAMP" to if(case=="bright_open")0f else 1f,"PLAYERS" to 1f,"TIGHT" to 1f)
                    val take=if(case=="default")0 else 2
                    val raw=Drumline.renderRaw(voice,macros,velocity,take)
                    Tide.bandLimit(raw,RATE)
                    val m=measure(raw)
                    check(listOf(m.referenceDb,m.reference20To18kDb,m.edgeDb,m.projectedDb,m.decompositionDb).all { it.isFinite() })
                    check(m.decompositionDb < -110) { "Reference decomposition failed: $voice $case $velocity $m" }
                    val row="$voice,$case,$velocity,$take,${m.referenceDb},${m.reference20To18kDb},${m.edgeDb},${m.projectedDb},${m.decompositionDb}"
                    println(row);out.appendLine(row);out.flush()
                }
        }
    }
}
