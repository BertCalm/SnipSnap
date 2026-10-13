package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

enum class SkitterVoice { BASS, LEAD, HOLLOW, FLUTTER, CHIRP, FRAY }

/** Mechanism ablations. Noise forcing leaves the audible noise feed exactly unchanged. */
data class SkitterProbe(
    val memoryFrozen: Boolean = false,
    val agitationDisabled: Boolean = false,
    val equalRecovery: Boolean = false,
    val noiseForcingDisabled: Boolean = false,
)

/** Control traces are sampled every 5 ms, before final loudness matching. Pitch offsets are cents. */
data class SkitterTrace(
    val seconds: Double,
    val energyHistory: Double,
    val signedHistory: Double,
    val agitation: Double,
    val agitationVelocity: Double,
    val pitchOffsetA: Double,
    val pitchOffsetB: Double,
    val cutoffHz: Double,
    val drive: Double,
)

data class SkitterDiagnostics(
    val rawPeak: Double,
    val rawRms: Double,
    val rawDc: Double,
    val filterStatePeak: Double,
    val historyPeak: Double,
    val signedHistoryPeak: Double,
    val agitationPeak: Double,
    val agitationVelocityPeak: Double,
    val pitchOffsetPeakCents: Double,
    val gestureCount: Int,
    val boundHits: Int,
    val loopPrerollPeriods: Int,
    val loopConverged: Boolean,
    val loopStateError: Double,
    val loopSeam: Double,
    val renderMillis: Double,
)

/**
 * [raw] is the 44.1 kHz, antialiased/DC-blocked, unlevelled export. Its gain is untouched.
 * [loopStart] is -1 for a finite note. HOLD exports two successive converged periods, so
 * Keys.seamError measures independently evolved audio, rather than two copied buffers.
 */
data class SkitterRender(
    val snip: Snip,
    val raw: FloatArray,
    val diagnostics: SkitterDiagnostics,
    val loopStart: Int,
    val trace: List<SkitterTrace>,
)

/**
 * SKITTER — two edged oscillators provoking a filter that remembers their recent signal.
 * This is a perceptual Wasp tribute, not a transistor circuit model. The history and flutter
 * are proposed extensions. The SVF uses Dsp.TptSvf's trapezoidal integrator equations in
 * double precision, with positive damping and a delayed, dissipative nonlinear feedback
 * branch. No completed signal is clipped to make the model stable.
 *
 * HOLD's approximation is explicit: gestures replay a periodic, alternating schedule,
 * stochastic forcing is reduced to 70%, and noise repeats a smoothly interpolated seeded
 * sequence. Both oscillators' frequency integrals fit whole cycles, preserving the requested
 * root within a fraction of a cent. Every remaining recursive state must converge under
 * those periodic inputs. A failed capped preroll is an error, not a silently fabricated loop.
 */
object Skitter {
    const val MODEL = 1
    const val DEFAULT_MIDI = 48
    const val MIDI_MIN = 24
    const val MIDI_MAX = 84
    const val HOLD_LOOP_THRESHOLD = .999f
    const val FILTER_STATE_LIMIT = 6.0
    const val HISTORY_LIMIT = 1.0
    const val SIGNED_HISTORY_LIMIT = .08
    const val AGITATION_LIMIT = 1.25
    const val AGITATION_VELOCITY_LIMIT = 250.0
    const val PITCH_OFFSET_LIMIT_CENTS = 250.0
    const val MAX_GESTURE_RATE_HZ = 8.0
    const val LOOP_PREROLL_CAP = 16
    const val LOOP_STATE_TOLERANCE = 2e-5
    private const val RATE = Dsp.RATE

    private data class Shape(
        val defaults: FloatArray,
        val gate: Double, val release: Double, val attack: Double,
        val cutoffRatio: Double, val damping: Double, val flutterHz: Double,
        val recoveryA: Double, val recoveryB: Double,
        val historyRelease: Double, val support: Double, val interval: Double = 1.0,
    )

    private fun shape(v: SkitterVoice): Shape = when (v) {
        SkitterVoice.BASS -> Shape(floatArrayOf(.40f,.20f,.30f,.10f,.05f), 1.30,.45,.012, 5.5,.95,7.0,.035,.19,.14,.065)
        SkitterVoice.LEAD -> Shape(floatArrayOf(.70f,.45f,.45f,.25f,.10f), 1.85,.48,.009, 9.0,.80,13.0,.028,.16,.18,.085)
        SkitterVoice.HOLLOW -> Shape(floatArrayOf(.35f,.30f,.25f,.50f,.10f), 2.0,.62,.018, 3.2,.70,6.5,.055,.24,.22,.09,2.0)
        SkitterVoice.FLUTTER -> Shape(floatArrayOf(.55f,.55f,.70f,.40f,.20f), 2.05,.68,.013, 6.4,.74,17.0,.025,.19,.24,.09)
        SkitterVoice.CHIRP -> Shape(floatArrayOf(.50f,.25f,.65f,.65f,.35f), 1.45,.56,.012, 3.6,.62,22.0,.038,.21,.16,.11)
        SkitterVoice.FRAY -> Shape(floatArrayOf(.85f,.65f,.85f,.45f,.60f), 2.10,.70,.016, 7.4,.72,26.0,.024,.14,.30,.10)
    }

    fun macrosFor(voice: SkitterVoice): List<MacroSpec> {
        val d = shape(voice).defaults
        return listOf(MacroSpec("BUZZ",d[0]), MacroSpec("SKITTER",d[1]),
            MacroSpec("AGITATE",d[2]), MacroSpec("VENT",d[3]), MacroSpec("STATIC",d[4]), MacroSpec("HOLD",0f))
    }

    fun defaults(voice: SkitterVoice): Map<String, Float> = macrosFor(voice).associate { it.name to it.default }
    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= HOLD_LOOP_THRESHOLD
    private fun settled(v: SkitterVoice, macros: Map<String, Float>): Map<String, Float> =
        defaults(v).mapValues { (k,d) -> (macros[k]?.takeIf { it.isFinite() } ?: d).coerceIn(0f,1f) }

    /** Kit placement preserves melodic intent; the measured audio classifier also sees duration. */
    fun drumClassFor(voice: SkitterVoice, macros: Map<String, Float> = emptyMap()): DrumClass =
        if(isLoop(settled(voice,macros).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    fun scramble(voice: SkitterVoice, random: Random, temperature: Float = .35f, near: Patch? = null): Map<String, Float> {
        val base = settled(voice, near?.macros ?: emptyMap())
        val amount=temperature.takeIf { it.isFinite() } ?: .35f
        val rolled = Dsp.scrambleNear(base, amount, random).toMutableMap()
        rolled["HOLD"] = rolled.getValue("HOLD").coerceAtMost(.97f)
        return rolled
    }

    /** PolyBLEP removes both saw and pulse discontinuities; a band-limited triangle rounds low BUZZ. */
    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x*x - 1.0 }
        t > 1.0-dt -> { val x = (t-1.0)/dt; x*x + x+x + 1.0 }
        else -> 0.0
    }

    private fun oscillator(phase: Double, increment: Double, buzz: Double): Double {
        val saw = 2.0*phase - 1.0 - blep(phase,increment)
        val duty = .5 - .34*buzz
        val shifted = (phase-duty+1.0)%1.0
        val pulse = (if (phase<duty) 1.0 else -1.0) + blep(phase,increment) - blep(shifted,increment) - (2.0*duty-1.0)
        val edge = .23 + .77*buzz
        val p=2.0*PI*phase
        // A four-partial triangle is intentionally rounded, with no discontinuity or aliased corner.
        val triangle=8.0/(PI*PI)*(sin(p)-sin(3.0*p)/9.0+sin(5.0*p)/25.0-sin(7.0*p)/49.0)
        // Match fundamental polarity across the morph; opposing fundamentals would leave a
        // misleadingly thin/noisy middle while the shared leveler concealed the cancellation.
        return -(1.0-edge)*triangle + edge*((1.0-.58*buzz)*saw - .58*buzz*pulse*.75)
    }

    /** Deterministic base-rate noise also makes the 8x/16x reference use the same excitation. */
    private fun noise(frames: Int, seed: Int): FloatArray {
        val rng = Random(seed)
        return FloatArray(frames.coerceAtLeast(2)) { rng.nextFloat()*2f-1f }
    }

    private fun interpolate(a: FloatArray, position: Double, periodic: Boolean): Double {
        val i = position.toInt()
        val t = position-i
        val left = if (periodic) a[i%a.size] else a[min(i,a.lastIndex)]
        val right = if (periodic) a[(i+1)%a.size] else a[min(i+1,a.lastIndex)]
        return left + t*(right-left)
    }

    private class Stats {
        var filter=0.0; var history=0.0; var signed=0.0; var agitation=0.0; var velocity=0.0; var offsets=0.0
        var gestures=0; var boundHits=0
        fun measure(e: Circuit, a: Double, b: Double) {
            filter=max(filter,max(abs(e.ic1),abs(e.ic2)))
            history=max(history,e.history); signed=max(signed,abs(e.signed))
            agitation=max(agitation,abs(e.position)); velocity=max(velocity,abs(e.velocity))
            offsets=max(offsets,max(abs(a),abs(b)))
            if (abs(e.ic1)>FILTER_STATE_LIMIT || abs(e.ic2)>FILTER_STATE_LIMIT ||
                e.history>HISTORY_LIMIT || abs(e.signed)>SIGNED_HISTORY_LIMIT ||
                abs(e.position)>AGITATION_LIMIT || abs(e.velocity)>AGITATION_VELOCITY_LIMIT ||
                max(abs(a),abs(b))>PITCH_OFFSET_LIMIT_CENTS) boundHits++
            check(e.ic1.isFinite() && e.ic2.isFinite() && e.position.isFinite() && e.history.isFinite()) {
                "SKITTER internal state became nonfinite"
            }
        }
    }

    /** All recursive state, including coefficient smoothing and the noise/DC detectors. */
    private class Circuit(val s: Shape, val m: Map<String,Float>, val rate: Int, val probe: SkitterProbe, val held: Boolean) {
        // Force and depth both depend on AGITATE. Square-root mapping keeps their product
        // responsive near zero instead of reserving the defining reaction for the top half.
        val agitate=if(probe.agitationDisabled) 0.0 else sqrt(m.getValue("AGITATE").toDouble())
        val buzz=m.getValue("BUZZ").toDouble(); val static=m.getValue("STATIC").toDouble(); val vent=m.getValue("VENT").toDouble()
        var ic1=0.0; var ic2=0.0; var band=0.0
        var history=0.0; var signed=0.0; var position=0.0; var velocity=0.0
        var noiseLow=0.0; var noiseSlow=0.0; var noiseEnergy=0.0; var noiseVariance=0.0
        var noiseAudio1=0.0; var noiseAudio2=0.0
        var dc=0.0; var cutoff=1.0; var drive=1.0; var oldLogCutoff=0.0
        var a1=1.0; var a2=0.0; var a3=0.0; var damping=s.damping
        private val attack=1.0-exp(-1.0/(rate*(.003+.004*(1.0-agitate))))
        private val release=1.0-exp(-1.0/(rate*s.historyRelease*(.7+agitate)))
        private val signedSlew=1.0-exp(-1.0/(rate*.075))
        private val noiseSlew=1.0-exp(-2.0*PI*4700.0/rate)
        private val noiseAudioSlew=1.0-exp(-2.0*PI*1400.0/rate)
        private val noiseSlowSlew=1.0-exp(-2.0*PI*35.0/rate)
        private val noiseEnergySlew=1.0-exp(-1.0/(rate*.06))
        private val noiseVarianceSlew=1.0-exp(-1.0/(rate*.009))
        private val dcSlew=1.0-exp(-2.0*PI*9.0/rate)
        private val coefficientSlew=1.0-exp(-1.0/(rate*.0015))
        private val omega=2.0*PI*s.flutterHz*(.8+.3*agitate)
        private val zeta=.90-.30*agitate
        private val damped=omega*sqrt(1.0-zeta*zeta)
        private val decay=exp(-zeta*omega/rate)
        private val cc=cos(damped/rate)
        private val ss=sin(damped/rate)/damped
        // exp(A dt) for the damped second-order control state: stable even at extreme AGITATE.
        private val p00=decay*(cc+zeta*omega*ss)
        private val p01=decay*ss
        private val p10=-decay*omega*omega*ss
        private val p11=decay*(cc-zeta*omega*ss)

        fun state(): DoubleArray = doubleArrayOf(ic1,ic2,band,history,signed,position,velocity/omega,
            noiseLow,noiseSlow,noiseEnergy,noiseVariance,noiseAudio1,noiseAudio2,dc,oldLogCutoff,a1,a2,a3,damping)

        fun step(input: Double, noise: Double, gesture: Double, root: Double, support: Double,
                 baseCutoff: Double, sample: Int): Double {
            noiseLow+=noiseSlew*(noise-noiseLow)
            noiseAudio1+=noiseAudioSlew*(noiseLow-noiseAudio1)
            noiseAudio2+=noiseAudioSlew*(noiseAudio1-noiseAudio2)
            noiseSlow+=noiseSlowSlew*(noise-noiseSlow)
            noiseVariance+=noiseVarianceSlew*(noiseLow*noiseLow-noiseVariance)
            noiseEnergy+=noiseEnergySlew*(noiseVariance-noiseEnergy)
            val x=input+static*.11*noiseAudio2
            // Read PREVIOUS history/agitation. Their updates below cannot feed today's own output.
            val logTarget=ln(baseCutoff)+agitate*(4.5*position+3.0*history)
            if(sample==0 && oldLogCutoff==0.0) oldLogCutoff=logTarget
            oldLogCutoff+=coefficientSlew*(logTarget-oldLogCutoff)
            cutoff=exp(oldLogCutoff).coerceIn(35.0, min(16000.0,rate*.20))
            drive=1.0+1.3*buzz+agitate*(4.5*history+.75*abs(position))
            val bias=agitate*1.3*signed
            damping=(s.damping-.20*agitate*history-.08*agitate*position).coerceIn(.48,1.30)
            if(sample%16==0) {
                val g=tan(PI*cutoff/rate)
                a1=1.0/(1.0+g*(g+damping)); a2=g*a1; a3=g*a2
            }
            // Nonlinearity is inside the excitation and delayed damping feedback, not a final FX.
            val shaped=(tanh(drive*x+bias)-tanh(bias))/sqrt(drive) - .045*agitate*tanh(2.0*band)
            val v3=shaped-ic2
            val v1=a1*ic1+a2*v3
            val v2=ic2+a2*ic1+a3*v3
            ic1=2.0*v1-ic1; ic2=2.0*v2-ic2
            band=v1
            val high=shaped-damping*v1-v2
            val mixed=if(vent<=.5) v2*(1.0-2.0*vent)+v1*1.24*(2.0*vent)
                else v1*1.24*(2.0-2.0*vent)+high*.90*(2.0*vent-1.0)
            // The quiet root path keeps the high-pass end useful without hiding the filter's voice.
            val y=.68*mixed+support*root
            dc+=dcSlew*(y-dc)

            val oldHistory=history
            if(!probe.memoryFrozen) {
                val detector=tanh((x*x+.14*v1*v1)/.22)
                history+=(if(detector>history) attack else release)*(detector-history)
                signed+=signedSlew*(.055*tanh(2.0*x+.3*v1)-signed)
            }
            val energyRise=max(0.0,(history-oldHistory)*rate*.025)
            val noiseDeviation=(noiseVariance-noiseEnergy)*7.0 + .85*noiseSlow
            val noiseForce=if(probe.noiseForcingDisabled) 0.0 else static*noiseDeviation*(if(held) .70 else 1.0)
            val forcing=.85*tanh(agitate*(.30*history+1.5*energyRise+.80*gesture+.85*noiseForce))
            val displaced=position-forcing
            val nextPosition=forcing+p00*displaced+p01*velocity
            velocity=p10*displaced+p11*velocity
            position=nextPosition
            return y-dc
        }
    }

    private data class LoopPlan(val frames: Int, val rootCycles: Int, val rootHz: Double)

    /** Fit whole periods to output frames, searching around one second instead of quantizing notes. */
    private fun planLoop(hz: Double, interval: Double): LoopPlan {
        var bestFrames=RATE; var bestCycles=hz.roundToInt(); var bestError=Double.POSITIVE_INFINITY
        val low=max(8,(hz*.85).roundToInt()); val high=max(low,(hz*1.15).roundToInt())
        for(k in low..high) {
            if(interval==1.5 && k%2!=0) continue
            val frames=(k*RATE/hz).roundToInt()
            val fitted=k*RATE.toDouble()/frames
            val error=abs(ln(fitted/hz))
            if(error<bestError) { bestError=error; bestFrames=frames; bestCycles=k }
        }
        return LoopPlan(bestFrames,bestCycles,bestCycles*RATE.toDouble()/bestFrames)
    }

    private data class Forcing(val a: FloatArray, val b: FloatArray, val gesture: FloatArray,
                               val noise: FloatArray, val correctionA: Double, val correctionB: Double, val events: Int)

    /** Exact periodic recovery state from the last repeated, alternating impulses. */
    private fun heldForcing(s: Shape, m: Map<String,Float>, velocity: Double, seed: Int, plan: LoopPlan,
                            oversample: Int, probe: SkitterProbe): Forcing {
        val frames=plan.frames*oversample; val rate=RATE*oversample
        val skitter=m.getValue("SKITTER").toDouble()
        val events=if(skitter==0.0) 0 else if(skitter<.35) 2 else 2+2*(skitter*2.0).toInt()
        val excursion=(9.0*skitter+170.0*skitter*skitter)*(.55+.45*velocity)
        val times=IntArray(events) { it*frames/events }
        val ra=s.recoveryA; val rb=if(probe.equalRecovery) ra else s.recoveryB
        fun offset(i: Int, tau: Double, second: Boolean): Double {
            var total=0.0
            for(j in times.indices) {
                val age=(i-times[j]+frames)%frames/rate.toDouble()
                val sign=if(j%2==0) 1.0 else -1.0
                total+=sign*excursion*(if(second) -.72 else 1.0)*exp(-age/tau)/(1.0-exp(-frames/rate.toDouble()/tau))
            }
            return total
        }
        val a=FloatArray(frames); val b=FloatArray(frames); val gesture=FloatArray(frames)
        var sumA=0.0; var sumB=0.0
        for(i in 0 until frames) {
            if(i%8192==0) stopIfUnwanted()
            a[i]=offset(i,ra,false).toFloat(); b[i]=offset(i,rb,true).toFloat()
            sumA+=2.0.pow(a[i]/1200.0); sumB+=2.0.pow(b[i]/1200.0)
            var kick=0.0
            for(t in times) { val age=(i-t+frames)%frames/rate.toDouble(); kick+=skitter*exp(-age/.019) }
            gesture[i]=kick.toFloat()
        }
        return Forcing(a,b,gesture,noise(plan.frames,Dsp.seedFor("SKITTER-NOISE",seed)),
            frames/sumA,frames/sumB,events)
    }

    // SynthScreen previews run in runInterruptible: a newer request interrupts this thread,
    // and the long loops and the passes poll it so a stale render stops instead of finishing unwanted.
    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("SKITTER render no longer wanted")
    }

    /** Blackman-windowed sinc, 48 output-sample support, >70 dB far-stopband rejection. */
    internal fun decimate(raw: FloatArray, oversample: Int, periodic: Boolean): FloatArray {
        val radius=24*oversample
        val kernel=DoubleArray(2*radius+1)
        var norm=0.0
        for(j in -radius..radius) {
            val z=j.toDouble()/oversample
            val sinc=if(j==0) .84 else sin(PI*.84*z)/(PI*z)
            val window=.42+.5*cos(PI*j/radius)+.08*cos(2.0*PI*j/radius)
            kernel[j+radius]=sinc*window; norm+=kernel[j+radius]
        }
        for(i in kernel.indices) kernel[i]/=norm
        val out=FloatArray(raw.size/oversample)
        for(i in out.indices) {
            if(i%8192==0) stopIfUnwanted()
            var y=0.0
            for(j in -radius..radius) {
                val p=i*oversample+j
                val at=if(periodic) (p%raw.size+raw.size)%raw.size else p
                if(at in raw.indices) y+=raw[at]*kernel[j+radius]
            }
            out[i]=y.toFloat()
        }
        return out
    }

    fun render(voice: SkitterVoice, macros: Map<String,Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
               velocity: Float = 1f, seed: Int = 0): Snip = renderProbe(voice,macros,midi,velocity,seed).snip

    fun renderProbe(voice: SkitterVoice, macros: Map<String,Float> = emptyMap(), midi: Int = DEFAULT_MIDI,
                    velocity: Float = 1f, seed: Int = 0, seconds: Float? = null, oversample: Int = 4,
                    probe: SkitterProbe = SkitterProbe()): SkitterRender {
        require(midi in MIDI_MIN..MIDI_MAX) { "SKITTER range is MIDI $MIDI_MIN..$MIDI_MAX, got $midi" }
        require(oversample==4 || oversample==8 || oversample==16) { "SKITTER oversample must be 4, 8 or 16" }
        require(seconds==null || (seconds.isFinite() && seconds>0f && seconds<=12f)) { "SKITTER probe seconds must be (0,12]" }
        val started=System.nanoTime()
        val m=settled(voice,macros); val s=shape(voice)
        val vel=velocity.takeIf { it.isFinite() }?.coerceIn(0f,1f)?.toDouble() ?: 1.0
        val held=isLoop(m.getValue("HOLD")); val rate=RATE*oversample
        val hz=Keys.midiHz(midi).toDouble()
        val circuit=Circuit(s,m,rate,probe,held); val stats=Stats(); val trace=ArrayList<SkitterTrace>()
        val contextSeed=Dsp.seedFor("SKITTER",voice.name,midi,seed)
        val buzz=m.getValue("BUZZ").toDouble(); val skitter=m.getValue("SKITTER").toDouble()
        val vent=m.getValue("VENT").toDouble()
        val support=s.support+.10*vent.pow(3.0)+.035*((midi-60)/12.0).coerceIn(0.0,1.0)
        val baseCutoff=(hz*s.cutoffRatio*(.62+.70*buzz)*(.75+.25*vel)).coerceIn(90.0,5500.0)
        var phaseA=.173; var phaseB=.381; var rootPhase=.0
        var a=0.0; var b=0.0; var gesture=0.0
        val traceStride=max(1,(rate*.005).roundToInt())
        fun record(i: Int, timeOffset: Double = 0.0) {
            stats.measure(circuit,a,b)
            if(i%traceStride==0) trace.add(SkitterTrace(i.toDouble()/rate+timeOffset,circuit.history,circuit.signed,
                circuit.position,circuit.velocity,a,b,circuit.cutoff,circuit.drive))
        }
        var periods=0; var converged=!held; var stateError=0.0; var loopStart=-1
        val rawInternal: FloatArray
        if(held) {
            val plan=planLoop(hz,s.interval)
            val force=heldForcing(s,m,vel,contextSeed,plan,oversample,probe)
            val n=plan.frames*oversample
            fun cycle(recordTrace: Boolean): FloatArray {
                val raw=FloatArray(n)
                for(i in raw.indices) {
                    if(i%8192==0) stopIfUnwanted()
                    a=force.a[i].toDouble(); b=force.b[i].toDouble(); gesture=force.gesture[i].toDouble()
                    val fa=plan.rootHz*force.correctionA*2.0.pow(a/1200.0)
                    val fb=plan.rootHz*s.interval*force.correctionB*2.0.pow(b/1200.0)
                    val oa=oscillator(phaseA,fa/rate,buzz); val ob=oscillator(phaseB,fb/rate,buzz)
                    val input=vel*.35*(.57*oa+.43*ob)
                    val root=sin(2.0*PI*rootPhase)*vel
                    raw[i]=circuit.step(input,interpolate(force.noise,i.toDouble()/oversample,true)*vel,gesture*vel,
                        root,support,baseCutoff,i).toFloat()
                    phaseA=(phaseA+fa/rate)%1.0; phaseB=(phaseB+fb/rate)%1.0; rootPhase=(rootPhase+plan.rootHz/rate)%1.0
                    if(recordTrace) record(i) else stats.measure(circuit,a,b)
                }
                return raw
            }
            var before=circuit.state()
            for(period in 1..LOOP_PREROLL_CAP) {
                cycle(false)
                val after=circuit.state()
                stateError=after.indices.maxOf { abs(after[it]-before[it]) }
                before=after; periods=period
                if(period>=3 && stateError<LOOP_STATE_TOLERANCE) { converged=true; break }
            }
            check(converged) { "SKITTER $voice HOLD state failed to converge after $periods periods: $stateError" }
            // These are two independently evolved periods. The seam is measured on both below.
            val first=cycle(true); val second=cycle(false)
            rawInternal=first+second
            loopStart=plan.frames
            stats.gestures=force.events*2
        } else {
            val gate=s.gate*(1.0+1.15*m.getValue("HOLD"))
            val duration=seconds?.toDouble() ?: gate+s.release+.18
            val frames=(duration*RATE).roundToInt().coerceAtLeast(1)
            rawInternal=FloatArray(frames*oversample)
            val nse=noise(frames+2,Dsp.seedFor("SKITTER-NOISE",contextSeed))
            val ra=exp(-1.0/(rate*s.recoveryA)); val rb=exp(-1.0/(rate*(if(probe.equalRecovery) s.recoveryA else s.recoveryB)))
            val gestureDecay=exp(-1.0/(rate*.019))
            val excursion=(9.0*skitter+170.0*skitter*skitter)*(.55+.45*vel)
            a=excursion; b=-.72*excursion; gesture=skitter; stats.gestures=if(skitter>0.0) 1 else 0
            var nextGesture=.48-.29*skitter; var event=1; var armed=true
            for(i in rawInternal.indices) {
                if(i%8192==0) stopIfUnwanted()
                val t=i.toDouble()/rate
                if(skitter>.28 && t<gate-.12 && t>=nextGesture && armed) {
                    val sign=if(event%2==0) 1.0 else -1.0
                    a=(a+sign*excursion*.78).coerceIn(-PITCH_OFFSET_LIMIT_CENTS,PITCH_OFFSET_LIMIT_CENTS)
                    b=(b-sign*excursion*.56).coerceIn(-PITCH_OFFSET_LIMIT_CENTS,PITCH_OFFSET_LIMIT_CENTS)
                    gesture+=skitter*.75; stats.gestures++; event++; armed=false
                    nextGesture=t+max(1.0/MAX_GESTURE_RATE_HZ,
                        (.48-.30*skitter)*(1.0-.16*abs(circuit.position)).coerceAtLeast(.75))
                }
                // Explicit refractory/hysteresis guard; no agitation-driven event chatter.
                if(!armed && gesture<.08 && t<nextGesture-.04) armed=true
                a*=ra; b*=rb; gesture*=gestureDecay
                val attack=1.0-exp(-t/s.attack)
                val release=if(t<=gate) 1.0 else exp(-6.907755*(t-gate)/s.release)
                // A little extra early energy provokes the circuit, then the powered source settles.
                val excitation=if(t>gate+s.release) 0.0 else attack*release*(.90+.18*exp(-t/.08))
                val brightness=(buzz+.13*vel*exp(-t/.10)).coerceAtMost(1.0)
                val fa=hz*2.0.pow(a/1200.0)
                val fb=hz*s.interval*2.0.pow(b/1200.0)
                val oa=oscillator(phaseA,fa/rate,brightness); val ob=oscillator(phaseB,fb/rate,brightness)
                val input=excitation*vel*.35*(.57*oa+.43*ob)
                val root=sin(2.0*PI*rootPhase)*excitation*vel
                rawInternal[i]=circuit.step(input,interpolate(nse,i.toDouble()/oversample,false)*excitation*vel,
                    gesture*excitation*vel,root,support,baseCutoff,i).toFloat()
                phaseA=(phaseA+fa/rate)%1.0; phaseB=(phaseB+fb/rate)%1.0; rootPhase=(rootPhase+hz/rate)%1.0
                record(i)
            }
        }
        check(stats.boundHits==0) { "SKITTER internal bounds exceeded ${stats.boundHits} times" }
        stopIfUnwanted()
        val raw=decimate(rawInternal,oversample,held)
        val output=raw.copyOf()
        stopIfUnwanted()
        Dsp.levelTo(output,RATE,Dsp.MELODIC_LOUDNESS_TARGET)
        if(!held) Dsp.fadeTail(output)
        stopIfUnwanted()
        val seam=if(held && vel>0.0) Keys.seamError(output,loopStart) else 0.0
        check(!held || seam<Keys.MAX_SEAM_ERROR) { "SKITTER $voice HOLD seam $seam exceeds ${Keys.MAX_SEAM_ERROR}" }
        val peak=raw.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
        val dc=raw.sumOf { it.toDouble() }/raw.size.coerceAtLeast(1)
        val rms=sqrt(raw.sumOf { it.toDouble()*it }/raw.size.coerceAtLeast(1))
        val diagnostics=SkitterDiagnostics(peak,rms,dc,stats.filter,stats.history,stats.signed,stats.agitation,
            stats.velocity,stats.offsets,stats.gestures,stats.boundHits,periods,converged,stateError,seam,
            (System.nanoTime()-started)/1e6)
        return SkitterRender(Snip(output,channels=1,sampleRate=RATE),raw,diagnostics,loopStart,trace)
    }
}
