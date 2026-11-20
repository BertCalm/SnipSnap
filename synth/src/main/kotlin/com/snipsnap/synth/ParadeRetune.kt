package com.snipsnap.synth

import kotlin.math.*

/** Band-limited fractional sampling for parade fitted sources. Two bounded coefficient banks;
 * per-call buffers, no PCM cache. Blackman-Harris 64-tap kernel, 1024 phases with linear phase blending.
 */
internal object ParadeRetune {
    private const val RADIUS=32
    private const val PHASES=1024
    private val banks=object: LinkedHashMap<Double,Array<DoubleArray>>(3,.75f,true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Double,Array<DoubleArray>>?) = size>2
    }
    @Synchronized private fun bank(ratio: Double): Array<DoubleArray> = banks.getOrPut(ratio) {
        val cutoff=.9/max(1.0,ratio)
        Array(PHASES+1) { phase ->
            val fraction=phase.toDouble()/PHASES
            val taps=DoubleArray(2*RADIUS) { j ->
                val d=j-(RADIUS-1)-fraction;val u=.5*(d/RADIUS+1)
                val window=if(abs(d)>=RADIUS)0.0 else .35875-.48829*cos(2*PI*u)+.14128*cos(4*PI*u)-.01168*cos(6*PI*u)
                val z=PI*cutoff*d
                cutoff*(if(abs(z)<1e-12)1.0 else sin(z)/z)*window
            }
            val total=taps.sum();for(j in taps.indices)taps[j]/=total;taps
        }
    }
    fun sample(x: DoubleArray,ratio: Double): DoubleArray {
        require(ratio.isFinite() && ratio in .5..2.0)
        if(ratio==1.0)return x.copyOf()
        val table=bank(ratio)
        return DoubleArray(x.size) { i ->
            val at=i*ratio;val base=at.toInt();val fraction=at-base
            if(base>x.lastIndex)0.0 else {
                val phase=fraction*PHASES;val p=phase.toInt().coerceIn(0,PHASES-1);val mix=phase-p
                val a=table[p];val b=table[p+1];var sum=0.0;var weight=0.0
                for(j in a.indices) {
                    val source=base+j-(RADIUS-1)
                    if(source in x.indices) { val w=a[j]+(b[j]-a[j])*mix;sum+=w*x[source];weight+=w }
                }
                if(abs(weight)<1e-12)0.0 else sum/weight
            }
        }
    }
}
