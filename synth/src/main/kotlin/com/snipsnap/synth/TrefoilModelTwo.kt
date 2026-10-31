package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

/** Acoustic revision after the first listening rejection.
 *
 * Rich, band-limited contact drives eight modes per body. Geometry rotates the
 * same partial's full (omega*q,v) states between bodies, so exchange redistributes
 * actual modal energy and the different pickups expose that redistribution.
 * Memory stores upper partials with a longer decay than the dry bodies. It has
 * no return path and never changes the dry pickup gain.
 */
internal object TrefoilModelTwo {
    private const val TAU=2*PI
    private const val IR=Dsp.RATE*Dsp.OVERSAMPLE
    private val ratios=doubleArrayOf(1.0,2.0,3.0,4.0,5.0,6.0,8.0,10.0)
    private val colors=arrayOf(
        doubleArrayOf(.85,.90,.25,.12,.08,.04,.02,.01),
        doubleArrayOf(.75,.20,.95,.25,.65,.20,.10,.04),
        doubleArrayOf(.60,.15,.25,.95,.25,.85,.70,.50))
    private val exchangeWeight=doubleArrayOf(.10,1.0,1.35,1.7,2.0,2.3,2.6,2.9)
    private class Bank(hz: DoubleArray,t60: DoubleArray) {
        val x=DoubleArray(hz.size); val v=DoubleArray(hz.size)
        private val co=DoubleArray(hz.size); private val si=DoubleArray(hz.size)
        init { for(i in hz.indices) { val r=exp(-ln(1000.0)/(t60[i]*IR)); co[i]=r*cos(TAU*hz[i]/IR); si[i]=r*sin(TAU*hz[i]/IR) } }
        fun step() { for(i in x.indices) { val a=x[i]; val b=v[i]; x[i]=co[i]*a+si[i]*b; v[i]=co[i]*b-si[i]*a } }
        fun energy(): Double { var e=0.0; for(i in x.indices) e+=.5*(x[i]*x[i]+v[i]*v[i]); return e }
        fun dot(p: DoubleArray): Double { var z=0.0; for(i in v.indices) z+=p[i]*v[i]; return z }
    }
    private data class Descriptor(val c: DoubleArray,val approach: DoubleArray,val attachment: DoubleArray)
    private fun descriptor(y: DoubleArray): Descriptor {
        val c=DoubleArray(3); val a=DoubleArray(3); val distance=DoubleArray(3)
        for(k in 0..2) {
            val(i,j)=TrefoilTrajectory.pairs[k]; val dx=y[2*j]-y[2*i]; val dy=y[2*j+1]-y[2*i+1]; val r=hypot(dx,dy)
            val rate=(dx*(y[6+2*j]-y[6+2*i])+dy*(y[7+2*j]-y[7+2*i]))/r
            c[k]=1/(1+(r/1.15).pow(4)); a[k]=tanh(-rate/1.8); distance[i]+=r*.5; distance[j]+=r*.5
        }
        return Descriptor(c,a,DoubleArray(3) { 1/(1+(distance[it]/3.5).pow(2)) })
    }
    private fun finish(x: FloatArray): FloatArray {
        val out=Dsp.decimate(x,Dsp.RATE); val pole=Dsp.OnePole(Dsp.RATE)
        for(i in out.indices) out[i]-=pole.lp(out[i],16f)
        Dsp.fadeTail(out,ms=60f); return out
    }
    fun probe(p: TrefoilPatch,options: Trefoil.Options): Trefoil.Probe {
        val start=System.nanoTime(); val m=Trefoil.settled(p.voice,p.macros)
        val trace=TrefoilTrajectory.forPatch(p); val desc=trace.states.map(::descriptor)
        val root=Keys.midiHz(p.midi).toDouble(); val thread=m.getValue("THREAD").toDouble()
        val memory=if(options.memoryEnabled) m.getValue("MEMORY").toDouble() else 0.0
        val exchange=m.getValue("EXCHANGE").toDouble()
        val excite=Array(3) { b -> DoubleArray(8) { k -> colors[b][k]*(if(k==0) .8 else .8+1.5*thread) }.also { v ->
            val norm=sqrt(v.sumOf { it*it }); for(k in v.indices) v[k]/=norm } }
        val rootPickup=.45+.20*(1-min(1.0,root/261.625565))
        val pickup=Array(3) { b -> DoubleArray(8) { k -> colors[b][k]*(if(k==0) rootPickup else .8+1.2*thread) } }
        val banks=Array(3) { b -> Bank(
            DoubleArray(8) { k -> root*ratios[k]*(1+if(k>=4) .003*thread.pow(3)*(b-1) else 0.0) },
            DoubleArray(8) { k -> if(k==0) 1.4 else (.65+1.1*thread)/ratios[k].pow(.18) }) }
        // A root-heavy send was nearly another copy of the dry note. These
        // upper modes retain contact color, and even default MEMORY outlasts dry.
        val memoryBank=Bank(DoubleArray(7) { root*ratios[it+1] },DoubleArray(7) { (2+4.5*memory)/(1+.10*it) })
        val memorySend=120*sqrt(memory)/IR; val memoryReturn=.45*sqrt(memory)
        val noise=Array(3) { Random(Dsp.seedFor("TREFOIL-ACOUSTIC-2",p.seed,p.midi,it)) }
        val noiseState=DoubleArray(3); val alpha=1-exp(-TAU*(900+6500*thread)/IR)
        val phase=doubleArrayOf(0.0,.61,1.73); val carrierX=DoubleArray(3) { cos(phase[it]) }; val carrierY=DoubleArray(3) { sin(phase[it]) }
        val co=cos(TAU*root/IR); val si=sin(TAU*root/IR)
        val harmonic=DoubleArray(10) { k -> if(k==0) 1.0 else (.8+1.4*thread)/(k+1.0).pow(1.2-.9*thread) }
        val harmonicNorm=sqrt(harmonic.sumOf { it*it })
        val count=(p.seconds*IR).roundToInt(); val raw=FloatArray(count)
        val stems=if(options.stems) Array(3) { FloatArray(count) } else emptyArray()
        val memOut=if(options.stems) FloatArray(count) else FloatArray(0)
        val work=DoubleArray(3); val budget=.025*p.velocity*p.velocity; val peakEnergy=DoubleArray(3)
        var maxEnergy=0.0; var unforcedRise=0.0; var finite=true
        val energy=ArrayList<Trefoil.EnergyPoint>(); val matrices=Array(8) { Trefoil.rotation(0.0,0.0,0.0) }
        val c=DoubleArray(3); val approach=DoubleArray(3); val attachment=DoubleArray(3)
        val onset=(IR/root*(.10+.25*(1-thread))).roundToInt().coerceAtLeast(4)
        val pulseAge=IntArray(3); val pulseStrength=DoubleArray(3) { .05*(root/130).coerceIn(.4,1.0) }
        val previousApproach=desc[0].approach.copyOf(); val lastAccent=DoubleArray(3) { -1.0 }
        val termination=if(trace.diagnostics.outcome in setOf("COMPLETE","STATIONARY")) Double.POSITIVE_INFINITY
            else trace.diagnostics.reached/(TrefoilTrajectory.PERIOD*TrefoilTrajectory.cyclesPerSecond(m.getValue("WEAVE")))
        for(frame in 0 until count) {
            if(frame and 8191==0 && Thread.currentThread().isInterrupted) throw CancellationException("TREFOIL render cancelled")
            val seconds=frame.toDouble()/IR; val at=if(options.frozen) 0.0 else (seconds*1000).coerceAtMost(desc.size-1.000001)
            val k=at.toInt(); val f=at-k; val d0=desc[k]; val d1=desc[min(k+1,desc.lastIndex)]
            for(j in 0..2) { c[j]=d0.c[j]+f*(d1.c[j]-d0.c[j]); approach[j]=d0.approach[j]+f*(d1.approach[j]-d0.approach[j]); attachment[j]=d0.attachment[j]+f*(d1.attachment[j]-d0.attachment[j]) }
            if(!options.frozen && !options.impulseOnly && seconds<min(p.activeSeconds.toDouble(),termination)) for(j in 0..2) {
                if(previousApproach[j]>0 && approach[j]<=0 && c[j]>.3) for(body in listOf(TrefoilTrajectory.pairs[j].first,TrefoilTrajectory.pairs[j].second)) {
                    if(seconds-lastAccent[body]>.035) { pulseAge[body]=0; pulseStrength[body]=.065*c[j]; lastAccent[body]=seconds }
                }
            }
            for(j in 0..2) previousApproach[j]=approach[j]
            if(frame%32==0) for(mode in 0..7) {
                // Preserve the same slow-envelope relationship to the note in
                // the bass; a fixed hertz exchange rate blurred low-note periods.
                val rate=18*min(1.0,root/261.625565)*exchange.pow(1.1)*exchangeWeight[mode]/IR
                matrices[mode]=Trefoil.rotation(rate*c[1]*(.85+.15*approach[1]),rate*c[2]*(.85+.15*approach[2]),rate*c[0]*(.85+.15*approach[0]))
            }
            val before=banks.sumOf { it.energy() }; var frameWork=0.0
            val envelope=((min(p.activeSeconds.toDouble(),termination)-seconds)/.08).coerceIn(0.0,1.0)
            for(b in 0..2) {
                val bx=carrierX[b]; val by=carrierY[b]; carrierX[b]=co*bx-si*by; carrierY[b]=si*bx+co*by
                if(frame%4096==0) { val norm=hypot(carrierX[b],carrierY[b]); carrierX[b]/=norm; carrierY[b]/=norm }
                if(p.velocity>0 && (options.excitedParticipant==null || options.excitedParticipant==b)) {
                    val bank=banks[b]; var hx=1.0; var hy=0.0; var carrier=0.0
                    for(h in harmonic.indices) { val next=hx*carrierX[b]-hy*carrierY[b]; hy=hx*carrierY[b]+hy*carrierX[b]; hx=next; carrier+=harmonic[h]*hy }
                    carrier/=harmonicNorm
                    noiseState[b]+=alpha*(noise[b].nextDouble(-1.0,1.0)-noiseState[b])
                    val left=if(b==0) 0 else b; val right=if(b==0) 2 else b-1
                    val pressure=.10+1.8*(c[left].pow(3)+c[right].pow(3))
                    val friction=tanh(1.4*(.65*carrier+(.06+.18*thread)*noiseState[b])-1.8*bank.dot(excite[b]))
                    var delta=if(options.impulseOnly) 0.0 else .8*sqrt(max(1.0,130/root))*p.velocity*envelope*attachment[b]*pressure*friction/IR
                    if(pulseAge[b]<onset) delta+=pulseStrength[b]*p.velocity*(1-cos(TAU*(pulseAge[b]+.5)/onset))/onset
                    val port=bank.dot(excite[b]); val injected=port*delta+.5*delta*delta
                    if(work[b]+max(0.0,injected)<=budget+1e-15) {
                        for(mode in 0..7) bank.v[mode]+=excite[b][mode]*delta
                        work[b]+=max(0.0,injected); frameWork+=max(0.0,injected)
                    }
                }
                pulseAge[b]++
            }
            // A simultaneous orthogonal rotation of BOTH coordinates preserves
            // each partial's combined energy exactly. Low root coupling keeps
            // the pitch useful while upper-partial ownership can move strongly.
            for(mode in 0..7) {
                val r=matrices[mode]; val ax=banks[0].x[mode]; val bx=banks[1].x[mode]; val cx=banks[2].x[mode]
                val av=banks[0].v[mode]; val bv=banks[1].v[mode]; val cv=banks[2].v[mode]
                for(b in 0..2) {
                    banks[b].x[mode]=r[b*3]*ax+r[b*3+1]*bx+r[b*3+2]*cx
                    banks[b].v[mode]=r[b*3]*av+r[b*3+1]*bv+r[b*3+2]*cv
                }
            }
            for(bank in banks) bank.step()
            var mix=0.0; var core=0.0
            for(b in 0..2) {
                val sample=banks[b].dot(pickup[b])*.8/sqrt(3.0)
                mix+=sample; if(options.stems) stems[b][frame]=sample.toFloat()
                val e=banks[b].energy(); peakEnergy[b]=max(peakEnergy[b],e); core+=e
            }
            for(mode in 0..6) {
                var source=0.0; for(b in 0..2) source+=banks[b].v[mode+1]*pickup[b][mode+1]*.8/sqrt(3.0)
                memoryBank.v[mode]+=source*memorySend
            }
            memoryBank.step(); var sympathetic=0.0
            for(mode in 0..6) sympathetic+=memoryBank.v[mode]*memoryReturn/sqrt(mode+1.0)
            raw[frame]=(mix+sympathetic).toFloat(); if(options.stems) memOut[frame]=sympathetic.toFloat()
            maxEnergy=max(maxEnergy,core); unforcedRise=max(unforcedRise,core-before-frameWork)
            if(frame%1764==0) { energy+=Trefoil.EnergyPoint(seconds,core,memoryBank.energy(),work.sum()); finite=finite&&core.isFinite()&&memoryBank.energy().isFinite() }
        }
        val finished=finish(raw); val samples=finished.copyOf(); Dsp.levelTo(samples,Dsp.RATE,Dsp.MELODIC_LOUDNESS_TARGET)
        var peak=0.0; var sum=0.0; var squares=0.0
        for(value in finished) { peak=max(peak,abs(value.toDouble())); sum+=value; squares+=value*value; finite=finite&&value.isFinite() }
        return Trefoil.Probe(finished,samples,if(options.stems) Array(3) { finish(stems[it]) } else emptyArray(),
            if(options.stems) finish(memOut) else FloatArray(0),Trefoil.Diagnostics(peak,sqrt(squares/finished.size),sum/finished.size,
                maxEnergy,banks.sumOf { it.energy() },work.sum(),budget*3,unforcedRise,finite,peakEnergy.toList(),energy,
                (System.nanoTime()-start)/1e6,trace.diagnostics),trace)
    }
}
