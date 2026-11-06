package com.snipsnap.synth

import kotlin.math.*

/** Normalized scattering preserves the energy argument even with changing corners.
 * Both coefficient components interpolate on the same chord of the unit circle. */
internal class ChitinPhaser(private val rate: Int, root: Double, private val phase: Double,
                            private val wing: Boolean, private val protected: Boolean = true) {
    private val state = DoubleArray(6)
    private val a = DoubleArray(6); private val b = DoubleArray(6)
    private val endA = DoubleArray(6); private val endB = DoubleArray(6)
    private val hpA = Dsp.TptSvf(rate); private val hpB = Dsp.TptSvf(rate)
    private val protectHz = max(180.0,3*root).toFloat()
    private val center = 650*(root/65.4063913).pow(.25)
    private var theta=0.0
    private var last=0.0
    private var node=64
    var energyExcess=0.0; private set
    var storage=0.0; private set
    var maxStorage=0.0; private set
    private fun coefficients(m: Double, theta: Double, aa: DoubleArray, bb: DoubleArray) {
        val excursion=(.15+1.85*phase)*(.35+.65*m)
        for(j in 0..5) {
            val corner=(center*2.0.pow(excursion*sin(2*PI*theta)+(j-2.5)*.12)).coerceIn(100.0,10000.0)
            val g=tan(PI*corner/rate)
            aa[j]=(g-1)/(g+1); bb[j]=sqrt(1-aa[j]*aa[j])
        }
    }
    fun step(x: Double, morph: Double, nextMorph: Double): Double {
        if(phase==0.0) return x
        val rateA=if(wing) .35 else .2
        val rateB=if(wing) 12.0 else 8.0
        if(node==64) {
            coefficients(morph,theta,a,b)
            // Midpoint quadrature predicts the node phase; integration stays continuous.
            val nextTheta=theta+64*rateA*(rateB/rateA).pow((morph+nextMorph)*.5)/rate
            coefficients(nextMorph,nextTheta,endA,endB)
            node=0
        }
        val f=node++/64.0
        theta=(theta+rateA*(rateB/rateA).pow(morph)/rate)%1
        var h=x
        if(protected) { hpA.process(x.toFloat(),protectHz,sqrt(2.0).toFloat()); h=hpA.high.toDouble() }
        val before=storage
        var y=.65*h+.35*last
        for(j in 0..5) {
            val aa=a[j]+f*(endA[j]-a[j]); val bb=b[j]+f*(endB[j]-b[j])
            val s=state[j]
            val out=aa*y+bb*s
            state[j]=bb*y-aa*s; y=out
        }
        last=y
        storage=state.sumOf { it*it }+.35*last*last
        maxStorage=max(maxStorage,storage)
        energyExcess=max(energyExcess,storage-before-.65*(h*h-y*y))
        var delta=y-h
        if(protected) { hpB.process(delta.toFloat(),protectHz,sqrt(2.0).toFloat()); delta=hpB.high.toDouble() }
        return x+.5*sqrt(phase)*delta
    }
}
