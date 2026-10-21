package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

enum class TrefoilVoice { WEAVE, HOLLOW, WIRE, TANGLE, TRACE }

/** Finite first-listen model: three equal pitched banks and a feedforward sympathetic trace. */
object Trefoil {
    const val MODEL_VERSION=1
    const val MIN_MIDI=36
    const val MAX_MIDI=84
    const val DEFAULT_MIDI=60
    private const val TAU=2*PI
    private val controls=listOf("WEAVE","NUDGE","EXCHANGE","THREAD","MEMORY")
    fun macrosFor(v: TrefoilVoice): List<MacroSpec> {
        val d=when(v) {
            TrefoilVoice.WEAVE -> floatArrayOf(.42f,0f,.55f,.28f,.25f)
            TrefoilVoice.HOLLOW -> floatArrayOf(.32f,.10f,.60f,.10f,.45f)
            TrefoilVoice.WIRE -> floatArrayOf(.55f,.20f,.70f,.80f,.30f)
            TrefoilVoice.TANGLE -> floatArrayOf(.55f,.60f,.85f,.60f,.45f)
            TrefoilVoice.TRACE -> floatArrayOf(.25f,.32f,.55f,.65f,.85f)
        }
        return controls.mapIndexed { i,k -> MacroSpec(k,d[i],d[i]) }+MacroSpec("HOLD",0f,0f)
    }
    fun defaults(v: TrefoilVoice)=macrosFor(v).associate { it.name to it.default }
    internal fun settled(v: TrefoilVoice,m: Map<String,Float>)=defaults(v)+m
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") v: TrefoilVoice,
        @Suppress("UNUSED_PARAMETER") m: Map<String,Float> = emptyMap())=DrumClass.TONAL

    data class Options(val frozen: Boolean=false,val impulseOnly: Boolean=false,
        val excitedParticipant: Int?=null,val memoryEnabled: Boolean=true,val stems: Boolean=false) {
        init { require(excitedParticipant==null || excitedParticipant in 0..2) }
    }
    data class EnergyPoint(val seconds: Double,val core: Double,val memory: Double,val positiveWork: Double)
    internal data class Diagnostics(val rawPeak: Double,val rawRms: Double,val rawDc: Double,
        val coreMaxEnergy: Double,val coreFinalEnergy: Double,val positiveWork: Double,val workBudget: Double,
        val unforcedRise: Double,val finite: Boolean,val participantEnergy: List<Double>,
        val energy: List<EnergyPoint>,val synthesisMs: Double,val trajectory: TrefoilTrajectory.Diagnostics)
    internal data class Probe(val raw: FloatArray,val samples: FloatArray,val stems: Array<FloatArray>,
        val memory: FloatArray,val diagnostics: Diagnostics,val trace: TrefoilTrajectory.Trace)

    /** In energy coordinates x=omega*q, v, a sample is an exact damped rotation.
     * A normalized vector p selects one velocity port. Replacing its projection
     * with an orthogonal three-port mix preserves ALL core squared state energy,
     * even while geometry changes the mixing matrix. Observer weights do not feed back.
     */
    private class Bank(hz: DoubleArray,t60: DoubleArray,val port: DoubleArray,val excite: DoubleArray) {
        val x=DoubleArray(hz.size); val v=DoubleArray(hz.size)
        private val co=DoubleArray(hz.size); private val si=DoubleArray(hz.size)
        init {
            normalize(port); normalize(excite)
            for(i in x.indices) { val r=exp(-ln(1000.0)/(t60[i]*IR)); co[i]=r*cos(TAU*hz[i]/IR); si[i]=r*sin(TAU*hz[i]/IR) }
        }
        fun step() { for(i in x.indices) { val a=x[i]; val b=v[i]; x[i]=co[i]*a+si[i]*b; v[i]=co[i]*b-si[i]*a } }
        fun dot(p: DoubleArray): Double { var z=0.0; for(i in v.indices) z+=p[i]*v[i]; return z }
        fun move(p: DoubleArray,d: Double) { for(i in v.indices) v[i]+=p[i]*d }
        fun energy(): Double { var z=0.0; for(i in v.indices) z+=.5*(x[i]*x[i]+v[i]*v[i]); return z }
    }
    private const val IR=Dsp.RATE*Dsp.OVERSAMPLE
    private fun normalize(p: DoubleArray) { val n=sqrt(p.sumOf { it*it }); for(i in p.indices) p[i]/=n }

    /** exp(K) for the 3x3 cross-product skew matrix. Identity at exact zero. */
    internal fun rotation(x: Double,y: Double,z: Double): DoubleArray {
        val a=sqrt(x*x+y*y+z*z)
        if(a<1e-16) return doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        val u=x/a; val v=y/a; val w=z/a; val c=cos(a); val s=sin(a); val d=1-c
        return doubleArrayOf(c+u*u*d,u*v*d-w*s,u*w*d+v*s,
            v*u*d+w*s,c+v*v*d,v*w*d-u*s,w*u*d-v*s,w*v*d+u*s,c+w*w*d)
    }
    private data class Descriptor(val c: DoubleArray,val approach: DoubleArray,val attachment: DoubleArray)
    private fun descriptor(y: DoubleArray): Descriptor {
        val c=DoubleArray(3); val a=DoubleArray(3); val distance=DoubleArray(3)
        for(k in 0..2) { val(i,j)=TrefoilTrajectory.pairs[k]
            val dx=y[2*j]-y[2*i]; val dy=y[2*j+1]-y[2*i+1]; val r=hypot(dx,dy)
            val rate=(dx*(y[6+2*j]-y[6+2*i])+dy*(y[7+2*j]-y[7+2*i]))/r
            c[k]=1/(1+(r/1.15).pow(4)); a[k]=tanh(-rate/1.8)
            distance[i]+=r*.5; distance[j]+=r*.5
        }
        return Descriptor(c,a,DoubleArray(3) { 1/(1+(distance[it]/3.5).pow(2)) })
    }
    private fun finish(x: FloatArray): FloatArray {
        val out=Dsp.decimate(x,Dsp.RATE)
        val pole=Dsp.OnePole(Dsp.RATE)
        for(i in out.indices) out[i]-=pole.lp(out[i],16f)
        Dsp.fadeTail(out,ms=60f)
        return out
    }
    internal fun probe(p: TrefoilPatch,options: Options=Options()): Probe {
        val start=System.nanoTime(); val m=settled(p.voice,p.macros)
        val trace=TrefoilTrajectory.forPatch(p)
        val desc=trace.states.map(::descriptor)
        val root=Keys.midiHz(p.midi).toDouble(); val thread=m.getValue("THREAD").toDouble()
        val memory=if(options.memoryEnabled) m.getValue("MEMORY").toDouble() else 0.0
        val exchange=m.getValue("EXCHANGE").toDouble()
        // Two root degrees of freedom per body: one local, one exchangeable.
        // This permits clearly audible transfer without moving the root-bearing
        // mode's pitch. Every participant has the identical six-mode structure.
        val ratios=doubleArrayOf(1.0,1.0,2.0,3.0,4.0,6.0)
        val colors=arrayOf(doubleArrayOf(.70,.65,.7,.3,.15,.08),doubleArrayOf(.70,.65,.3,.8,.55,.22),doubleArrayOf(.70,.65,.2,.4,.8,.85))
        val ports=arrayOf(doubleArrayOf(0.0,1.0,.65,.4,.2,.1),doubleArrayOf(0.0,1.0,.35,.75,.5,.25),doubleArrayOf(0.0,1.0,.2,.35,.75,.8))
        val banks=Array(3) { b ->
            val f=DoubleArray(6) { k -> root*ratios[k]*(1+if(k>=4) .008*thread.pow(3)*(b-1)*(k-3) else 0.0) }
            val t=DoubleArray(6) { k -> if(k<2) 1.8 else (1.5-.6*thread)/sqrt(ratios[k]) }
            val ex=DoubleArray(6) { k -> colors[b][k]*(if(k<2) 1.0 else (.35+.95*thread)*(.45+.55*p.velocity)) }
            Bank(f,t,ports[b].copyOf(),ex)
        }
        val memoryBank=Bank(DoubleArray(8) { root*doubleArrayOf(1.0,2.0,3.0,4.0,5.0,6.0,8.0,9.0)[it] },
            DoubleArray(8) { (.1+5.9*memory*memory)/(1+it*.13) },
            DoubleArray(8) { 1.0/(it+1) },DoubleArray(8) { 1.0/sqrt(it+1.0) })
        val noise=Array(3) { Random(Dsp.seedFor("TREFOIL-ACOUSTIC-1",p.seed,p.midi,it)) }
        val noiseState=DoubleArray(3)
        val alpha=1-exp(-TAU*(650+5500*thread)/IR)
        val count=(p.seconds*IR).roundToInt(); val raw=FloatArray(count)
        val stem=if(options.stems) Array(3) { FloatArray(count) } else emptyArray()
        val memOut=if(options.stems) FloatArray(count) else FloatArray(0)
        val work=DoubleArray(3); val budget=.025*p.velocity*p.velocity
        val peakEnergy=DoubleArray(3); var maxEnergy=0.0; var unforcedRise=0.0
        var finite=true; val energy=ArrayList<EnergyPoint>(); var matrix=rotation(0.0,0.0,0.0)
        val u=DoubleArray(3); val c=DoubleArray(3); val approach=DoubleArray(3); val attachment=DoubleArray(3)
        val active=p.activeSeconds.toDouble()
        // Note-relative contact width keeps upper modes present across the register.
        val onset=(IR/root*(.12+.35*(1-thread))).roundToInt().coerceAtLeast(4)
        val pulseAge=IntArray(3)
        val pulseStrength=DoubleArray(3) { .05*(root/130).coerceIn(.4,1.0) }
        val lastAccent=DoubleArray(3) { -1.0 }
        val previousApproach=desc[0].approach.copyOf()
        val termination=if(trace.diagnostics.outcome=="COMPLETE" || trace.diagnostics.outcome=="STATIONARY") Double.POSITIVE_INFINITY
            else trace.diagnostics.reached/(TrefoilTrajectory.PERIOD*TrefoilTrajectory.cyclesPerSecond(m.getValue("WEAVE")))
        for(frame in 0 until count) {
            if(frame and 8191==0 && Thread.currentThread().isInterrupted) throw CancellationException("TREFOIL render cancelled")
            val seconds=frame.toDouble()/IR
            val at=if(options.frozen) 0.0 else (seconds*1000).coerceAtMost(desc.size-1.000001)
            val k=at.toInt(); val frac=at-k; val d0=desc[k]; val d1=desc[min(k+1,desc.lastIndex)]
            for(j in 0..2) { c[j]=d0.c[j]+frac*(d1.c[j]-d0.c[j]); approach[j]=d0.approach[j]+frac*(d1.approach[j]-d0.approach[j]); attachment[j]=d0.attachment[j]+frac*(d1.attachment[j]-d0.attachment[j]) }
            if(!options.frozen && !options.impulseOnly && seconds<min(active,termination)) {
                for(j in 0..2) {
                    if(previousApproach[j]>0 && approach[j]<=0 && c[j]>.3) {
                        val(a,b)=TrefoilTrajectory.pairs[j]
                        for(body in intArrayOf(a,b)) if(seconds-lastAccent[body]>.035) {
                            pulseAge[body]=0; pulseStrength[body]=.032*c[j]; lastAccent[body]=seconds
                        }
                    }
                }
            }
            for(j in 0..2) previousApproach[j]=approach[j]
            if(frame%32==0) {
                val rate=100*min(1.0,root/261.625565)*exchange*exchange/IR
                matrix=rotation(rate*c[1]*(.7+.3*approach[1]),rate*c[2]*(.7+.3*approach[2]),rate*c[0]*(.7+.3*approach[0]))
            }
            var before=0.0; for(b in banks) before+=b.energy()
            var framePositiveWork=0.0
            val release=(min(active,termination)-seconds)/.08
            val envelope=release.coerceIn(0.0,1.0)
            for(b in 0..2) {
                val bank=banks[b]
                if(p.velocity>0 && (options.excitedParticipant==null || options.excitedParticipant==b)) {
                    val left=if(b==0) 0 else b; val right=if(b==0) 2 else b-1
                    val pressure=.12+1.3*(c[left].pow(3)+c[right].pow(3))
                    noiseState[b]+=alpha*(noise[b].nextDouble(-1.0,1.0)-noiseState[b])
                    val phase=TAU*root*seconds+b*.17
                    val carrier=sin(phase)+.45*thread*sin(2*phase)+.3*thread*sin(3*phase)
                    val target=.55*carrier+(.15+.4*thread)*noiseState[b]
                    val friction=tanh(2.0*(target-3*bank.dot(bank.excite)))
                    var delta=if(!options.impulseOnly) .8*sqrt(max(1.0,130/root))*p.velocity*envelope*attachment[b]*pressure*friction/IR else 0.0
                    if(pulseAge[b]<onset) delta+=pulseStrength[b]*p.velocity*(1-cos(TAU*(pulseAge[b]+.5)/onset))/onset
                    val port=bank.dot(bank.excite); val injected=port*delta+.5*delta*delta
                    if(work[b]+max(0.0,injected)<=budget+1e-15) {
                        bank.move(bank.excite,delta); work[b]+=max(0.0,injected); framePositiveWork+=max(0.0,injected)
                    }
                }
                pulseAge[b]++
                u[b]=bank.dot(bank.port)
            }
            // All reads precede all writes. Orthogonal mixing changes internal
            // states, not the final audio gains; zero is exactly independent.
            for(b in 0..2) {
                val changed=matrix[b*3]*u[0]+matrix[b*3+1]*u[1]+matrix[b*3+2]*u[2]
                banks[b].move(banks[b].port,changed-u[b]); banks[b].step()
            }
            var mix=0.0; var coreEnergy=0.0
            for(b in 0..2) {
                var pickup=0.0
                for(j in 0..5) pickup+=banks[b].v[j]*colors[b][j]*(if(j<2) 1.0 else .3+.9*thread)
                val sample=pickup*(.9-.2*memory)/sqrt(3.0)
                mix+=sample; if(options.stems) stem[b][frame]=sample.toFloat()
                val e=banks[b].energy(); peakEnergy[b]=max(peakEnergy[b],e); coreEnergy+=e
            }
            val memDelta=mix*memory*60/IR
            memoryBank.move(memoryBank.excite,memDelta); memoryBank.step()
            val sympathetic=memoryBank.dot(memoryBank.port)*.48*memory
            if(options.stems) memOut[frame]=sympathetic.toFloat()
            raw[frame]=(mix+sympathetic).toFloat()
            maxEnergy=max(maxEnergy,coreEnergy)
            unforcedRise=max(unforcedRise,coreEnergy-before-framePositiveWork)
            if(frame%1764==0) { energy+=EnergyPoint(seconds,coreEnergy,memoryBank.energy(),work.sum()); finite=finite&&coreEnergy.isFinite()&&memoryBank.energy().isFinite() }
        }
        val finished=finish(raw); val samples=finished.copyOf()
        Dsp.levelTo(samples,Dsp.RATE,Dsp.MELODIC_LOUDNESS_TARGET)
        var peak=0.0; var sum=0.0; var squares=0.0
        for(v in finished) { peak=max(peak,abs(v.toDouble())); sum+=v; squares+=v*v; finite=finite&&v.isFinite() }
        return Probe(finished,samples,if(options.stems) Array(3) { finish(stem[it]) } else emptyArray(),
            if(options.stems) finish(memOut) else FloatArray(0),
            Diagnostics(peak,sqrt(squares/finished.size),sum/finished.size,maxEnergy,banks.sumOf { it.energy() },
                work.sum(),budget*3,unforcedRise,finite,peakEnergy.toList(),energy,(System.nanoTime()-start)/1e6,trace.diagnostics),trace)
    }
    fun render(v: TrefoilVoice,macros: Map<String,Float> = emptyMap(),midi: Int=DEFAULT_MIDI,
        velocity: Float=.8f,seed: Long=43L,seconds: Float=6f,activeSeconds: Float=3.5f): Snip =
        Snip(probe(TrefoilPatch("Render",v,macros,midi,velocity,seed,seconds,activeSeconds)).samples,1,Dsp.RATE)
}
