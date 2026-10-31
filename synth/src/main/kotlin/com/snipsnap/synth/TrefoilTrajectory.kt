package com.snipsnap.synth

import org.apache.commons.math3.ode.FirstOrderDifferentialEquations
import org.apache.commons.math3.ode.nonstiff.DormandPrince853Integrator
import org.apache.commons.math3.ode.sampling.StepHandler
import org.apache.commons.math3.ode.sampling.StepInterpolator
import org.apache.commons.math3.exception.MaxCountExceededException
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

/** Unsoftened equal-mass Newtonian motion; no audio or dissipative correction enters this solver. */
internal object TrefoilTrajectory {
    // Reference family v1. Positions fix scale and orientation, G=m=1. Velocities
    // and period were refined from the supplied rounded figure-eight seed using
    // SciPy DOP853 + least_squares (rtol 2.3e-14, atol 2e-15); see TREFOIL.md.
    const val PERIOD = 6.3259140097821724
    const val EXCLUSION = 1e-3
    val pairs = arrayOf(0 to 1, 1 to 2, 2 to 0)
    fun reference() = doubleArrayOf(.97000436,-.24308753,-.97000436,.24308753,0.0,0.0,
        .46620368406955315,.4323657309174746,.46620368406955315,.4323657309174746,
        -.9324073681391063,-.8647314618349492)

    data class Invariants(val energy: Double, val px: Double, val py: Double,
        val cx: Double, val cy: Double, val angular: Double)
    data class Diagnostics(val outcome: String, val evaluations: Int, val acceptedSteps: Int,
        val horizon: Double, val reached: Double, val minSeparation: Double,
        val energyDrift: Double, val momentumDrift: Double, val comDrift: Double,
        val angularDrift: Double, val initial: Invariants, val solver: String = "Apache Commons Math 3.6.1 DormandPrince853Integrator (8(5,3))")
    data class Trace(val states: Array<DoubleArray>, val diagnostics: Diagnostics)
    private class Encounter : RuntimeException()
    private class WorkLimit : RuntimeException()

    fun invariant(y: DoubleArray): Invariants {
        var e=0.0; var px=0.0; var py=0.0; var cx=0.0; var cy=0.0; var l=0.0
        for(i in 0..2) { val k=2*i; e+=.5*(y[6+k]*y[6+k]+y[7+k]*y[7+k]);
            px+=y[6+k]; py+=y[7+k]; cx+=y[k]/3; cy+=y[k+1]/3; l+=y[k]*y[7+k]-y[k+1]*y[6+k] }
        for((i,j) in pairs) e-=1/hypot(y[2*i]-y[2*j],y[2*i+1]-y[2*j+1])
        return Invariants(e,px,py,cx,cy,l)
    }
    fun initial(nudge: Float, seed: Long): DoubleArray {
        val y=reference()
        if(nudge==0f) return y
        val rng=Random(Dsp.seedFor("TREFOIL-DYNAMICS-1",seed))
        val p=DoubleArray(6) { rng.nextDouble(-1.0,1.0) }
        for(axis in 0..1) { val mean=(p[axis]+p[axis+2]+p[axis+4])/3; for(i in 0..2) p[2*i+axis]-=mean }
        val rms=sqrt(p.sumOf { it*it }/3).coerceAtLeast(1e-15)
        val referenceRms=sqrt((6..11).sumOf { y[it]*y[it] }/3)
        val strength=.38*nudge.toDouble().pow(1.35)*referenceRms/rms
        for(i in 0..5) y[i+6]+=p[i]*strength
        return y // General families permit angular momentum and energy to change.
    }
    fun cyclesPerSecond(weave: Float): Double {
        if(weave==0f) return 0.0
        val x=weave.toDouble(); fun smooth(t: Double)=t*t*(3-2*t)
        if(x<.1) return smooth(x/.1)/16
        val knob=doubleArrayOf(.1,.25,.5,.75,1.0)
        val hz=doubleArrayOf(1.0/16,1.0/8,.5,2.0,8.0)
        val k=(0..3).firstOrNull { x<=knob[it+1] } ?: 3
        return exp(ln(hz[k])+(ln(hz[k+1])-ln(hz[k]))*smooth((x-knob[k])/(knob[k+1]-knob[k])))
    }
    fun solve(initial: DoubleArray, horizon: Double, count: Int,
        maxEvaluations: Int=250_000, tolerance: Double=1e-10): Trace {
        require(initial.size==12 && initial.all { it.isFinite() } && horizon in 0.0..512.0 && count>=2)
        val base=invariant(initial)
        val states=Array(count) { initial.copyOf() }
        var accepted=0; var next=1; var reached=0.0; var last=initial.copyOf()
        var minD=Double.POSITIVE_INFINITY; var ed=0.0; var pd=0.0; var cd=0.0; var ld=0.0
        fun measure(y: DoubleArray) {
            val v=invariant(y)
            ed=max(ed,abs(v.energy-base.energy)/max(1.0,abs(base.energy)))
            pd=max(pd,hypot(v.px-base.px,v.py-base.py)); cd=max(cd,hypot(v.cx-base.cx,v.cy-base.cy))
            ld=max(ld,abs(v.angular-base.angular))
            for((i,j) in pairs) minD=min(minD,hypot(y[2*i]-y[2*j],y[2*i+1]-y[2*j+1]))
        }
        measure(initial)
        if(horizon==0.0) return Trace(states,Diagnostics("STATIONARY",0,0,0.0,0.0,minD,ed,pd,cd,ld,base))
        val integrator=DormandPrince853Integrator(1e-12,.04,1e-12,tolerance)
        integrator.maxEvaluations=maxEvaluations
        integrator.addStepHandler(object: StepHandler {
            override fun init(t0: Double,y0: DoubleArray,t: Double) {}
            override fun handleStep(s: StepInterpolator,isLast: Boolean) {
                if(++accepted>50_000) throw WorkLimit()
                if(Thread.currentThread().isInterrupted) throw CancellationException("TREFOIL trajectory cancelled")
                // Dense intermediate checks complement the derivative-stage exclusion guard.
                for(k in 1..8) {
                    s.interpolatedTime=s.previousTime+(s.currentTime-s.previousTime)*k/8
                    val y=s.interpolatedState
                    for((i,j) in pairs) if(hypot(y[2*i]-y[2*j],y[2*i+1]-y[2*j+1])<EXCLUSION) throw Encounter()
                }
                while(next<count && horizon*next/(count-1)<=s.currentTime+1e-13) {
                    s.interpolatedTime=horizon*next/(count-1)
                    states[next]=s.interpolatedState.copyOf(); measure(states[next]); next++
                }
                s.interpolatedTime=s.currentTime; last=s.interpolatedState.copyOf()
                reached=s.currentTime; measure(last)
            }
        })
        val equations=object: FirstOrderDifferentialEquations {
            override fun getDimension()=12
            override fun computeDerivatives(t: Double,y: DoubleArray,d: DoubleArray) {
                for(i in 0..5) { d[i]=y[i+6]; d[i+6]=0.0 }
                for((i,j) in pairs) {
                    val dx=y[2*j]-y[2*i]; val dy=y[2*j+1]-y[2*i+1]; val r=hypot(dx,dy)
                    // Stop conservatively on ANY trial stage entering the exclusion
                    // region, including rejected steps. Never divide through a collision.
                    if(r<EXCLUSION || !r.isFinite()) throw Encounter()
                    val f=1/(r*r*r)
                    d[6+2*i]+=dx*f; d[7+2*i]+=dy*f; d[6+2*j]-=dx*f; d[7+2*j]-=dy*f
                }
            }
        }
        val outcome=try { integrator.integrate(equations,0.0,initial.copyOf(),horizon,DoubleArray(12)); "COMPLETE" }
        catch(e: Encounter) { "NEAR_COLLISION" }
        catch(e: WorkLimit) { "WORK_LIMIT" }
        catch(e: MaxCountExceededException) { "WORK_LIMIT" }
        catch(e: org.apache.commons.math3.exception.MathIllegalStateException) { "SOLVER_FAILURE" }
        catch(e: org.apache.commons.math3.exception.MathIllegalArgumentException) { "SOLVER_FAILURE" }
        while(next<count) states[next++]=last.copyOf()
        return Trace(states,Diagnostics(outcome,integrator.evaluations,accepted,horizon,reached,minD,ed,pd,cd,ld,base))
    }

    private data class Key(val nudge: Float,val seed: Long,val weave: Float,val seconds: Float)
    private val cache=object: LinkedHashMap<Key,Trace>(8,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key,Trace>?)=size>8
    }
    fun forPatch(p: TrefoilPatch): Trace {
        val m=Trefoil.settled(p.voice,p.macros)
        val n=m.getValue("NUDGE"); val w=m.getValue("WEAVE")
        val key=Key(n,if(n==0f) 0 else p.seed,w,p.seconds)
        synchronized(cache) { cache[key]?.let { return it } }
        val trace=solve(initial(n,p.seed),p.seconds*PERIOD*cyclesPerSecond(w),ceil(p.seconds*1000.0).toInt()+1)
        synchronized(cache) { cache[key]=trace }; return trace
    }
}
