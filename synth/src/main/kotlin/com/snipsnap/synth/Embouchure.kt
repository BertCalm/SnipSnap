package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class EmbouchureVoice { REED, BRASS, BASS, WHISTLE, BOW, SOFT }
enum class EmbouchureDriver { REED, LIP, BOW }
enum class EmbouchureGesture { SOFT_ENTRY, LEANING_SWELL, OVERPRESSURE, PRESSURE_RELEASE, BOWED_CAPTURE }

/**
 * One player, one root-bearing instrument. Pressure and velocity use dimensionless acoustic
 * units. REED uses a regularised single-reed scattering law; BRASS adds compliant lip loading;
 * BOW uses two travelling-wave segments of ONE string and velocity-dependent friction.
 * WHISTLE is a clear reed-derived approximation, not a jet-edge flute or Yamaha algorithm.
 *
 * Model 2 retains each voice's exact finite default sound and the same source seed as model 1.
 * [EmbouchureV1] freezes old saved recipes, including its gestures and complete loop policy.
 * Changes span signed, voice-relative distances from the default: EFFORT widens capture and
 * relaxation gestures, REED changes contact plus correlated tuned loss, SHAPE changes tuned
 * impedance/body response, and GRAIN changes pressure-linked flow and mobile-aperture texture.
 * Bow contact also moves along the string as grip tightens, while SHAPE moves its body position.
 * LENGTH preserves the original finite duration range and changes its relaxation proportions.
 *
 * Update order at 176.4 kHz: smooth player segments -> bounded roughness -> returning wave ->
 * contact -> outgoing wave into the SAME delay -> passive body modes -> radiation/DC.
 * No pitched oscillator supplies sustain. An eight-period tongue/capture impulse starts the
 * resonator; it is finite player work with frequency supplied by that resonator's root.
 * Source and travelling waves share velocityScale=v^.40 to preserve soft-velocity capture.
 * Zero velocity supplies no work and takes no division. Pressure <= 1.5, bow speed <= .22,
 * reflection magnitude <= .98, and friction coefficient <= .995.
 *
 * Aperture irregularity vanishes at immobile closure, preventing a roughness knob from merely
 * quenching the captured note. Bounded turbulent flow follows P, the live aperture and the
 * regularised root-flow sqrt(abs(dp)+.0025)-.05. It supplies exactly zero work after release;
 * friction texture likewise vanishes with bow speed/contact. No output noise or distortion is
 * added. At source-off the reed/lip scattering is contractive, bridge reflection is .95, and
 * delay, body and radiation stores are passive. Stores below 1e-30 flush to exact zero.
 * The production path band-limits before decimation, then uses shared melodic loudness.
 *
 * HOLD preserves the narrower captured driver window instead of applying finite articulation
 * excursions to a repeating note. Seeded flow stays periodic; SOFT uses half finite GRAIN,
 * and REED smoothly reduces it to .18 as EFFORT*REED rises from .70 to 1. Bow keeps its proven
 * speed/friction capture window. Body shaping remains active. True complete-state closure is
 * measured after two/three warm periods, extending to seven when needed; a failed state is
 * refused rather than repaired with a seam crossfade.
 *
 * Delay tuning charges filter and fractional-delay phase using [Strings.tune]. Both models
 * support BASS C2-C4, REED/BRASS/BOW/SOFT C3-C5 and WHISTLE C4-C6. These reduced instrument
 * roles are not acoustic reconstruction claims. Numerical capture/bounds do not replace the
 * owner's dry listening approval.
 */
object Embouchure {
    const val MODEL_VERSION = 2
    /** Explicit opt-in; ordinary patches and omitted model fields keep their existing meanings. */
    const val BRASS_GROW_MODEL = 3
    fun supportsModel(model: Int): Boolean = model == 1 || model == MODEL_VERSION || model == BRASS_GROW_MODEL
    /** Preserve the source realization across versions for direct control comparisons. */
    internal fun seedFor(voice: EmbouchureVoice, midi: Int): Int = Dsp.seedFor("EMBOUCHURE",1,voice,midi)
    const val TUNE_SEMITONES = 24
    private const val INTERNAL_RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val TRACE_HOP = 64

    private data class Voice(val root: Int, val driver: EmbouchureDriver, val gesture: EmbouchureGesture,
                             val defaults: FloatArray, val brightness: Float, val body: Float, val attack: Float)
    private fun voice(v: EmbouchureVoice): Voice = when (v) {
        EmbouchureVoice.REED -> Voice(48, EmbouchureDriver.REED, EmbouchureGesture.LEANING_SWELL,
            floatArrayOf(.45f,.50f,.40f,.20f,.50f), 1f, .18f, .038f)
        EmbouchureVoice.BRASS -> Voice(48, EmbouchureDriver.LIP, EmbouchureGesture.OVERPRESSURE,
            floatArrayOf(.55f,.45f,.45f,.15f,.50f), 1.3f, .28f, .052f)
        EmbouchureVoice.BASS -> Voice(36, EmbouchureDriver.REED, EmbouchureGesture.PRESSURE_RELEASE,
            floatArrayOf(.45f,.40f,.55f,.20f,.40f), .65f, .20f, .045f)
        EmbouchureVoice.WHISTLE -> Voice(60, EmbouchureDriver.REED, EmbouchureGesture.SOFT_ENTRY,
            floatArrayOf(.35f,.30f,.20f,.10f,.55f), .34f, .055f, .070f)
        EmbouchureVoice.BOW -> Voice(48, EmbouchureDriver.BOW, EmbouchureGesture.BOWED_CAPTURE,
            floatArrayOf(.45f,.45f,.40f,.25f,.65f), .9f, .32f, .110f)
        EmbouchureVoice.SOFT -> Voice(48, EmbouchureDriver.REED, EmbouchureGesture.SOFT_ENTRY,
            floatArrayOf(.30f,.25f,.50f,.20f,.75f), .46f, .30f, .145f)
    }

    fun rootMidi(voice: EmbouchureVoice): Int = voice(voice).root
    fun midiRange(voice: EmbouchureVoice): IntRange = rootMidi(voice)..rootMidi(voice)+TUNE_SEMITONES
    fun driverFor(voice: EmbouchureVoice): EmbouchureDriver = voice(voice).driver
    fun gestureFor(voice: EmbouchureVoice): EmbouchureGesture = voice(voice).gesture
    private fun unit(x: Float, fallback: Float = 0f): Float = if (x.isFinite()) x.coerceIn(0f,1f) else fallback
    /** Flush acoustically irrelevant stores, well below even the smallest supported velocity. */
    private fun acousticZero(x: Float): Float = if(abs(x)<1e-30f) 0f else x
    private fun acousticZero(x: Double): Double = if(abs(x)<1e-30) 0.0 else x
    fun midiFor(voice: EmbouchureVoice, tune: Float): Int = rootMidi(voice)+Math.round(unit(tune,.5f)*TUNE_SEMITONES)
    fun frequencyFor(voice: EmbouchureVoice, tune: Float): Float = Keys.midiHz(midiFor(voice,tune))
    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= .999f
    fun macrosFor(voice: EmbouchureVoice, model: Int = MODEL_VERSION): List<MacroSpec> {
        require(supportsModel(model)) { "unsupported embouchure model $model" }
        if(model==BRASS_GROW_MODEL) {
            require(voice==EmbouchureVoice.BRASS) { "EMBOUCHURE model 3 is BRASS only" }
            return BrassGrow.MACROS
        }
        val d = voice(voice).defaults
        return listOf(MacroSpec("TUNE",.5f,neutral=.5f), MacroSpec("EFFORT",d[0],neutral=.40f),
            MacroSpec("REED",d[1],neutral=.45f), MacroSpec("SHAPE",d[2],neutral=.40f),
            MacroSpec("GRAIN",d[3],neutral=.10f), MacroSpec("LENGTH",d[4],neutral=.50f),
            MacroSpec("HOLD",0f,neutral=0f))
    }
    fun defaults(voice: EmbouchureVoice, model: Int = MODEL_VERSION): Map<String,Float> =
        macrosFor(voice,model).associate { it.name to it.default }
    internal fun settled(macros: Map<String,Float>, voice: EmbouchureVoice): Map<String,Float> =
        defaults(voice).mapValues { (key,value) -> unit(macros[key] ?: value,value) }
    fun scramble(voice: EmbouchureVoice, random: Random, temperature: Float = .35f, near: Patch? = null,
                 model: Int = MODEL_VERSION): Map<String,Float> {
        val d = defaults(voice,if(near is EmbouchurePatch) near.model else model)
        val m = d + (near?.macros ?: emptyMap()).filterKeys { it in d }
        return Dsp.scrambleNear(m,unit(temperature,.35f),random).toMutableMap().also {
            it["HOLD"] = it.getValue("HOLD").coerceAtMost(.95f)
            if(near is EmbouchurePatch) it["TUNE"] = it.getValue("TUNE").coerceAtMost(
                ((midiRange(voice).last-near.midi).toFloat()/TUNE_SEMITONES).coerceIn(0f,1f))
        }
    }

    internal fun gestureSeconds(voice: EmbouchureVoice, macros: Map<String,Float>): Float {
        val m=settled(macros,voice)
        val long = voice==EmbouchureVoice.BOW || voice==EmbouchureVoice.SOFT
        return (if(long) 1.65f+3.15f*m.getValue("LENGTH") else 1.0f+2.2f*m.getValue("LENGTH")) *
            (1f+.08f*m.getValue("EFFORT")*m.getValue("LENGTH"))
    }
    internal fun renderFrames(voice: EmbouchureVoice, macros: Map<String,Float>): Int =
        ((gestureSeconds(voice,macros)+.85f)*Dsp.RATE).toInt().coerceAtMost(6*Dsp.RATE)
    fun drumClassFor(voice: EmbouchureVoice, macros: Map<String,Float> = emptyMap()): DrumClass =
        if(isLoop(settled(macros,voice).getValue("HOLD"))) DrumClass.LOOP else DrumClass.TONAL

    /** A normalized mouth-pressure score, applied before the nonlinear contact junction. */
    internal class PressureScore(times: List<Float>, values: List<Float>) {
        val times: List<Float> = java.util.Collections.unmodifiableList(times.toList())
        val values: List<Float> = java.util.Collections.unmodifiableList(values.toList())
        init {
            require(this.times.size in 2..32 && this.times.size==this.values.size)
            require(this.times.first()==0f && this.times.last()==1f &&
                this.times.all { it.isFinite() && it in 0f..1f } &&
                this.times.zipWithNext().all { (a,b) -> a<b }) { "pressure score times must rise from 0 to 1" }
            require(this.values.all { it.isFinite() && it in 0f..2f }) { "pressure score values must be finite and bounded" }
        }
    }

    internal class Probe(
        val velocity: Float=1f,
        val feedback: Boolean=true,
        val sourceStopSeconds: Float?=null,
        val totalSeconds: Float?=null,
        val gestureDurationSeconds: Float?=null,
        val pressureScore: PressureScore?=null,
        val rate: Int=INTERNAL_RATE,
        val seed: Int?=null,
        val midi: Int?=null,
        val gesture: EmbouchureGesture?=null,
        val record: Boolean=true,
        val traceHop: Int=TRACE_HOP,
        val held: Boolean=false,
        val controlStride: Int=1,
        val model: Int=MODEL_VERSION,
    )
    internal class Trace(val rate: Float, val hop: Int, frames: Int) {
        val player=FloatArray(frames)
        val returning=FloatArray(frames)
        val injected=FloatArray(frames)
        val driver=FloatArray(frames)
        val energy=FloatArray(frames)
        var maxEnergy=0.0; internal set
        var maxAbs=0f; internal set
        var finite=true; internal set
    }
    internal class Played(val raw: FloatArray, val trace: Trace, val hz: Float, val sourceEndSeconds: Float,
                          val rate: Int, val seed: Int, val maxEnergy: Double, val maxAbs: Float)

    /** Copy of the shared tuned wave path with explicit energy storage for acceptance probes. */
    private class Wave(hz: Float, cutoff: Float, private val rate: Int, private val gain: Float,
                       roundTrip: Double=1.0, dcHz: Float=0f) {
        private val tuning=Strings.tune(hz,cutoff,rate,dcBlock=dcHz>0f,dcHz=dcHz,roundTrip=roundTrip)
        private val n=tuning.n
        private val a=tuning.a
        private val history=FloatArray(n+2)
        private var cursor=0
        private var samples=0
        private var delayEnergy=0.0
        private var ap=0f
        private var lp=0f
        private var dc=0f
        private val lpA=(1.0-exp(-2*PI*cutoff.coerceAtMost(rate*.45f)/rate)).toFloat()
        private val dcA=if(dcHz>0f) (1.0-exp(-2*PI*dcHz/rate)).toFloat() else 0f
        fun returning(): Float {
            if(samples<=n) return 0f
            val at=(cursor-n+history.size)%history.size
            val older=(at-1+history.size)%history.size
            val x=.5f*(history[at]+history[older])
            val y=a*x+ap
            ap=acousticZero(x-a*y)
            lp=acousticZero(lp+lpA*(y-lp))
            dc=acousticZero(dc+dcA*(lp-dc))
            return gain*(lp-dc)
        }
        fun inject(x: Float) {
            val value=acousticZero(x)
            val old=history[cursor]
            delayEnergy += value.toDouble()*value-old.toDouble()*old
            history[cursor]=value
            cursor=(cursor+1)%history.size
            samples++
        }
        // Waveguide energy includes fractional allpass storage. Scale by samples/period so register
        // changes do not multiply the diagnostic's acoustic unit. Filters are additional stores.
        fun energy(): Double = max(0.0,delayEnergy+ap.toDouble()*ap/max(1e-7,1.0-a*a)) / history.size +
            .02*(lp.toDouble()*lp+dc.toDouble()*dc)
        fun state(): DoubleArray = DoubleArray(history.size+3) { k -> when(k) {
            history.size -> ap.toDouble(); history.size+1 -> lp.toDouble(); history.size+2 -> dc.toDouble()
            else -> history[(cursor+k)%history.size].toDouble()
        } }
    }

    /** Exact damped complex rotation, with bounded external drive; no stiff Euler solver. */
    private class Mode(hz: Float, t60: Float, rate: Int) {
        private val r=exp(-6.907755/(t60*rate))
        private val c=cos(2*PI*hz/rate)*r
        private val s=sin(2*PI*hz/rate)*r
        private val input=1.0-r
        var re=0.0; private set
        var im=0.0; private set
        fun step(x: Float): Float {
            val next=c*re-s*im+input*x
            im=acousticZero(s*re+c*im); re=acousticZero(next)
            return re.toFloat()
        }
        fun energy(): Double = re*re+im*im
    }

    /** Signed distance from this voice's approved default, with each side spanning its full knob travel. */
    private fun relative(value: Float, anchor: Float): Float =
        if(value < anchor) (value-anchor)/anchor else (value-anchor)/(1f-anchor)
    private fun heldSeconds(macros: Map<String,Float>): Double {
        val length=macros.getValue("LENGTH")
        return 1.65+.55*length
    }
    private fun smooth(x: Float): Float { val v=x.coerceIn(0f,1f); return v*v*(3f-2f*v) }
    /** Smooth segments with zero slopes at every knot; the gesture is a reproducible score. */
    private fun segment(t: Float, times: FloatArray, values: FloatArray): Float {
        if(t<=times[0]) return values[0]
        for(k in 1 until times.size) if(t<times[k]) {
            val a=smooth((t-times[k-1])/(times[k]-times[k-1]))
            return values[k-1]+a*(values[k]-values[k-1])
        }
        return values.last()
    }
    private class Player(v: Voice, private val gesture: EmbouchureGesture, effort: Float, length: Float,
                         val duration: Float, private val held: Boolean, private val period: Int, private val rate: Int,
                         pressureScore: PressureScore?=null) {
        private val effortMotion=relative(effort,v.defaults[0])
        private val lengthMotion=relative(length,v.defaults[4])
        private val attack=if(held) (v.attack*(1.35f-.7f*effort)).coerceAtLeast(.018f) else
            (v.attack*(1.35f-.7f*effort)*exp(-.95f*effortMotion)).coerceIn(.010f,duration*.16f)
        private val times=floatArrayOf(0f,attack/duration,.19f,.39f,.65f,.84f+.08f*lengthMotion,1f)
        private val values=when(gesture) {
            EmbouchureGesture.SOFT_ENTRY -> floatArrayOf(0f,.68f,.90f,1f,1f,.73f,0f)
            EmbouchureGesture.LEANING_SWELL -> floatArrayOf(0f,.84f,.86f,1f+.11f*effort,1f,.68f,0f)
            EmbouchureGesture.OVERPRESSURE -> floatArrayOf(0f,1f+.12f*effort,.90f,1f,1f,.72f,0f)
            EmbouchureGesture.PRESSURE_RELEASE -> floatArrayOf(0f,1f,1f,.98f,.94f,.82f,0f)
            EmbouchureGesture.BOWED_CAPTURE -> floatArrayOf(0f,1f+.20f*effort,.96f,1f,.96f,.67f,0f)
        }
        private val expressiveValues=values.copyOf().also { knots ->
            // A firm player captures the note early; a gentle player leans into it gradually.
            knots[1] *= 1f+.28f*effortMotion
            knots[2] *= 1f+.13f*effortMotion
            // Overpressure is an entry accent followed by recovered playing pressure.
            if(gesture!=EmbouchureGesture.OVERPRESSURE) knots[3] *= 1f+.09f*effortMotion
            knots[5] *= 1f+.12f*lengthMotion
        }
        private val heldDepth=.007f+.025f*effort*(.35f+.65f*length)
        private val scoreTimes=pressureScore?.times?.toFloatArray()
        private val scoreValues=pressureScore?.values?.toFloatArray()
        fun finiteScore() = PressureScore(times.toList(),expressiveValues.toList())
        fun at(i: Int): Float {
            if(!held && scoreTimes!=null) return segment(i.toFloat()/rate/duration,scoreTimes,scoreValues!!)
            if(!held) return segment(i.toFloat()/rate/duration,times,expressiveValues)
            val phase=(i%period).toDouble()/period
            val contour=1f+heldDepth*sin(2*PI*phase).toFloat()+heldDepth*.35f*sin(4*PI*phase).toFloat()
            return smooth(i.toFloat()/rate/attack)*contour
        }
    }

    /** Reuse the exact finite articulation math when constructing a model-3 source score. */
    internal fun finitePressureScore(voice: EmbouchureVoice, macros: Map<String,Float>,
                                     gesture: EmbouchureGesture, duration: Float): PressureScore {
        val m=settled(macros,voice)
        return Player(voice(voice),gesture,m.getValue("EFFORT"),m.getValue("LENGTH"),
            duration,false,1,INTERNAL_RATE).finiteScore()
    }

    private class Engine(val name: EmbouchureVoice, val m: Map<String,Float>, val probe: Probe,
                         val midi: Int, val hz: Float, val gesture: EmbouchureGesture, val heldFrames: Int=0) {
        private val v=voice(name)
        val rate=probe.rate
        val duration=probe.gestureDurationSeconds ?: gestureSeconds(name,m)
        val stop=probe.sourceStopSeconds?.let { if(it.isFinite()) it.coerceAtLeast(0f) else duration } ?: duration
        val seed=probe.seed ?: seedFor(name,midi)
        private val effort=m.getValue("EFFORT")
        private val reed=m.getValue("REED")
        private val shape=m.getValue("SHAPE")
        private val grain=m.getValue("GRAIN")
        private val effortMotion=relative(effort,v.defaults[0])
        private val reedMotion=relative(reed,v.defaults[1])
        private val shapeMotion=relative(shape,v.defaults[2])
        private val grainMotion=relative(grain,v.defaults[3])
        private val velocity=unit(probe.velocity,1f)
        private val sourceScale=velocity.pow(.40f)
        private val held=heldFrames>0 || probe.held
        // Firm contact already raises pressure/speed. Keep extra texture in the same captured
        // window instead of multiplying its depth by the hard-effort square a second time.
        private val roughEffortScale=if(held) 1f else exp(-1.2f*max(0f,effortMotion))
        private val roughBandMix=if(held) 0f else
            smooth(max(0f,effortMotion)/.65f)*smooth(max(0f,grainMotion)/.5f)
        private val roughMotion=sqrt(max(0f,grainMotion))
        private val roughExpansion=1f+(if(held) 0f else 2.2f)*roughMotion
        private val flowExpansion=1f+(if(held) .2f else when(name) {
            EmbouchureVoice.WHISTLE -> 22f
            EmbouchureVoice.SOFT -> 12f
            EmbouchureVoice.BRASS -> 10f
            EmbouchureVoice.REED -> 5f
            else -> 7f
        })*roughMotion
        // Capture the repeating driver rather than forcing a seam onto an aperiodic state.
        // Held GRAIN remains proportional, including in the hardest closure regime.
        private val sourceGrain=grain*(if(held) when(name) {
            EmbouchureVoice.SOFT -> .5f
            EmbouchureVoice.REED -> 1f-.82f*smooth((effort*reed-.70f)/.30f)
            else -> 1f
        } else 1f+when(name) {
            EmbouchureVoice.WHISTLE -> 1.2f
            EmbouchureVoice.SOFT -> .6f
            EmbouchureVoice.BASS -> .4f
            else -> 0f
        }*roughMotion)
        private val contactGrain=sqrt(max(0f,grainMotion))*(if(held)
            if(grain>0f) .10f*sourceGrain/grain else 0f else 1f)
        private val period=if(heldFrames>0) heldFrames else (heldSeconds(m)*rate).toInt()
        private val player=Player(v,gesture,effort,m.getValue("LENGTH"),duration,held,period,rate,probe.pressureScore)
        private val noise=Dsp.Noise(seed)
        private val periodicNoise=FloatArray(4096).also { b ->
            val n=Dsp.Noise(seed)
            var z=0f
            for(i in b.indices) { z += .22f*(n.next()-z); b[i]=z }
            val fade=64
            for(i in 0 until fade) { val a=smooth(i.toFloat()/fade); b[i]=b[i]*a+b[b.size-fade+i]*(1f-a) }
        }
        private var noiseLp=0f
        private var contactNoiseLp=0f
        private val contactNoiseA=(1-exp(-2*PI*(hz*3f).coerceAtMost(7000f)/rate)).toFloat()
        private val noiseA=(1-exp(-2*PI*(hz*(5f+(if(held) 0f else 15f)*max(0f,grainMotion))).coerceAtMost(14000f)/rate)).toFloat()
        private val offset=.52f+.20f*reed+(if(held) 0f else .035f)*reedMotion
        private val closing=(1f-offset)/.3f
        // Contact-dependent speaking threshold fitted by BORE's reed probes. Preserve a working
        // pressure window at tight contact and soft velocity rather than creating a silent knob end.
        private val shareWanted=.60f+.63f*(offset-.50f)+.095f+.060f*effort+(if(held) 0f else .045f)*effortMotion
        // At R=.98 the junction closes before its idealised R=1 pressure. A finite
        // overpressure accent may cross that boundary; its underlying sustained pressure may not.
        private val share=if(held) shareWanted else
            shareWanted.coerceAtMost((.98f-offset)/(1f-offset)-.035f)
        private val pressure=closing*share*(.88f+.12f*sqrt(velocity))
        // Extra loss under simultaneous firm effort/tight contact selects the root
        // rather than sustaining the briefly approached upper regime; tuning charges this loss.
        private val cutoff=(hz*(3.2f+10f*v.brightness*(1f-.63f*shape)*(.65f+.35f*reed+.55f*effort))*
            exp(-(if(held) .30f else if(name==EmbouchureVoice.WHISTLE) .45f else 1.1f)*shapeMotion+
                (if(held) 0f else .18f)*effortMotion+(if(held) .12f else .90f)*reedMotion-
                (if(held) 0f else 1.5f)*max(0f,effortMotion)*max(0f,reedMotion))).coerceIn(hz*2.2f,14000f)
        private val dcHz=hz/35f
        // Bounded lip loading's phase is small and its compensation measured by held pitch matching.
        private val lip=Mode(hz*(4.5f+2.5f*reed),.004f+.009f*(1f-reed),rate)
        private val bore=if(v.driver!=EmbouchureDriver.BOW) Wave(hz,cutoff,rate,.95f,dcHz=dcHz) else null
        private val beta=.133+(if(held) 0.0 else .035)*shapeMotion+(if(held) 0.0 else .018)*reedMotion
        private val bridgeCutoff=((hz*(7f+7f*reed+5f*effort)*(1f-.35f*shape)).coerceIn(1700f,6500f)*
            exp((if(held) 0f else -1.05f)*shapeMotion+(if(held) 0f else .18f)*effortMotion+
                (if(held) 0f else .85f)*reedMotion)).coerceIn(hz*2.2f,14000f)
        private val bridgeHz: Float = run {
            val first=Strings.tune(hz,bridgeCutoff,rate,roundTrip=beta)
            val tau=(rate/hz)*beta-.5-first.exact
            (hz/(1.0+.15*tau*hz/(beta*rate))).toFloat()
        }
        private val bridge=if(v.driver==EmbouchureDriver.BOW) Wave(bridgeHz,bridgeCutoff,rate,-.95f,roundTrip=beta) else null
        private val nut=if(v.driver==EmbouchureDriver.BOW) Wave(hz,rate*.45f,rate,-1f,roundTrip=1-beta) else null
        private val mode1=Mode((hz*(2.35f+.8f*shape+.30f*shapeMotion)).coerceAtMost(11000f),
            (.04f+.065f*shape)*exp(.35f*shapeMotion),rate)
        private val mode2=Mode((hz*(4.6f+1.1f*shape+.30f*shapeMotion)).coerceAtMost(14000f),
            (.026f+.04f*shape)*exp(.35f*shapeMotion),rate)
        private var outputDc=0f
        // BASS radiation drains the mouth-entry pressure and opens its upper root harmonics
        // under rough effort. This passive root-relative outlet keeps the long bass envelope
        // perceptible instead of letting a changing asymmetric crest mimic a low drum's decay.
        // It is outside the guide: its phase never changes the requested resonator pitch.
        private val radiationHz=if(name==EmbouchureVoice.BASS) hz*(.75f+.85f*effort*grain) else max(12f,hz/6f)
        private val outputDcA=(1-exp(-2*PI*radiationHz/rate)).toFloat()
        private val starterN=(8*rate/hz).toInt().coerceAtLeast(2)
        private val starterAt=(v.attack*.35f*rate).toInt()
        private val starterAmp=when(name) {
            EmbouchureVoice.BASS -> .22f
            EmbouchureVoice.BOW -> .004f
            EmbouchureVoice.SOFT, EmbouchureVoice.WHISTLE -> .035f
            else -> .070f
        }
        var sample=0; private set
        var playerNow=0f; private set
        var returned=0f; private set
        var injected=0f; private set
        var driverNow=0f; private set
        var maxEnergy=0.0; private set
        var maxAbs=0f; private set
        private var control=0f
        private var controlIncrement=0f
        fun step(): Float {
            val sourceOn=velocity>0f && (held || sample.toFloat()/rate<stop)
            if(sample%probe.controlStride==0) {
                control=if(sourceOn) player.at(sample) else 0f
                controlIncrement=if(sourceOn) (player.at(sample+probe.controlStride)-control)/probe.controlStride else 0f
            }
            playerNow=if(sourceOn) (control+controlIncrement*(sample%probe.controlStride))*sourceScale else 0f
            val rough=if(held) {
                val at=(sample%period).toDouble()/period*periodicNoise.size
                val k=floor(at).toInt(); val f=(at-k).toFloat()
                periodicNoise[k]+f*(periodicNoise[(k+1)%periodicNoise.size]-periodicNoise[k])
            } else noise.next()
            noiseLp += noiseA*(rough-noiseLp)
            // Hard, rough contact constrains the turbulent/friction bandwidth before it
            // reaches the junction. Gentle/default effort retains the calibrated full flow curve.
            contactNoiseLp += contactNoiseA*(noiseLp-contactNoiseLp)
            val sourceNoise=if(roughBandMix==0f) noiseLp else
                noiseLp+roughBandMix*(contactNoiseLp-noiseLp)
            // The clear whistle has less turbulent area; a held lip uses smaller source
            // fluctuations to stay in the captured periodic regime rather than a noisy flutter.
            val roughScale=(if(name==EmbouchureVoice.WHISTLE) .5f else 1f)*(if(held) .55f else 1f)
            val texture=sourceGrain*roughExpansion*(.045f+.19f*effort*effort)*roughEffortScale*sourceNoise*playerNow*roughScale
            val s=sample-starterAt
            val starter=if(sourceOn && s in 0 until starterN)
                starterAmp*velocity*playerNow*(.5f-.5f*cos(2*PI*s/starterN).toFloat())*sin(2*PI*hz*s/rate).toFloat() else 0f
            val out: Float
            if(v.driver==EmbouchureDriver.BOW) {
                val rb=bridge!!.returning(); val rn=nut!!.returning()
                val actual=rb+rn
                returned=actual
                val observed=if(probe.feedback) actual else 0f
                val speed=((.070f+.080f*effort)*exp((if(held) 0f else .30f)*effortMotion)*(.6f+.4f*sqrt(velocity))*playerNow*(1f+texture)).coerceIn(0f,.22f)
                val dv=speed-observed
                val slope=(1.05f+.42f*(1f-reed)+.11f*shape)*exp((if(held) 0f else if(reedMotion>0f) .55f else .08f)*reedMotion)
                val knee=.75f+(if(held) 0f else .035f)*reedMotion+(if(held) 0f else .08f)*contactGrain*sourceNoise*roughScale*playerNow
                val rho=(knee+slope*abs(dv)).pow(-4f).coerceAtMost(.995f)
                val contact=smooth(playerNow*5f)
                val frictionTexture=.22f*roughExpansion*speed*tanhUnit(sourceGrain*(2f+12f*effort*effort)*roughEffortScale*sourceNoise*roughScale)*contact
                val push=contact*dv*rho+frictionTexture+starter
                playerNow=speed
                driverNow=push
                // Feedback ablation opens the junction while preserving the seeded player and impulse.
                val nb=(if(probe.feedback) rn else 0f)+push
                val nn=(if(probe.feedback) rb else 0f)+push
                bridge.inject(nb); nut.inject(nn)
                injected=nb
                out=nb
            } else {
                val actual=bore!!.returning()
                returned=actual
                val observed=if(probe.feedback) actual else 0f
                val p=(pressure*playerNow*(1f+texture)).coerceIn(0f,1.5f)
                playerNow=p
                val dp=observed-p
                // Pressure/flow share a velocity-dependent acoustic scale. Rebase the closure
                // gradient with that same scale, so a quiet player still reaches a speaking
                // aperture rather than falling through a large numerical silence threshold.
                val normalizedDp=if(sourceScale>0f) dp/sourceScale else dp
                val lipA=lip.step(normalizedDp)
                val compliance=if(v.driver==EmbouchureDriver.LIP)
                    .035f*(1.35f-.65f*reed)*exp((if(held) 0f else -.75f)*reedMotion)*tanhUnit(8f*lipA)
                    else (if(held) 0f else .035f)*reedMotion*tanhUnit(8f*lipA)
                // Source-linked aperture irregularity changes the actual scattering contact.
                // It contributes no work once the player stops; the reflection remains passive.
                val baseReflection=(offset-.3f*normalizedDp+compliance).coerceIn(-.98f,.98f)
                val mobility=(1f-(baseReflection/.98f).pow(2)).coerceAtLeast(0f)
                val irregularity=when(name) {
                    EmbouchureVoice.REED -> .30f
                    EmbouchureVoice.BRASS -> .55f
                    EmbouchureVoice.SOFT -> .70f
                    EmbouchureVoice.WHISTLE -> 1f
                    else -> 1.2f
                }*contactGrain*mobility*roughEffortScale*sourceNoise*roughScale*playerNow
                val reflection=(baseReflection+irregularity).coerceIn(-.98f,.98f)
                // Bounded turbulent FLOW through the currently open contact. Its energy source
                // is P and it ends exactly with P; WHISTLE has a smaller calibrated noisy area.
                val aperture=1f-reflection
                val flowBound=when(name) {
                    EmbouchureVoice.REED -> if(held) .32f else .36f
                    EmbouchureVoice.BASS -> .27f
                    EmbouchureVoice.WHISTLE -> .045f
                    else -> .18f
                }*(if(held && (v.driver==EmbouchureDriver.LIP || name==EmbouchureVoice.REED || name==EmbouchureVoice.BASS))
                    .35f else if(held) .55f else 1f)
                val turbulentFlow=flowBound*flowExpansion*p*tanhUnit(sourceGrain*(2f+12f*effort*effort)*roughEffortScale*aperture*
                    (sqrt(abs(normalizedDp)+.0025f)-.05f)*sourceNoise)
                val y=p+dp*reflection+turbulentFlow+starter
                driverNow=y-observed
                bore.inject(y); injected=y
                // Root wave rather than mouth's DC pressure is the acoustic output.
                out=actual
            }
            val b1=mode1.step(out); val b2=mode2.step(out)
            val body=v.body*(.35f+1.6f*shape)*(1f+.35f*reed)*exp(.65f*shapeMotion)
            val x=out+body*(b1-.58f*b2)
            outputDc=acousticZero(outputDc+outputDcA*(x-outputDc))
            val radiated=acousticZero(x-outputDc)
            val e=energy()
            check(e.isFinite() && radiated.isFinite() && e<16.0 && abs(radiated)<8f) {
                "EMBOUCHURE $name unstable raw state at $sample: energy=$e sample=$radiated"
            }
            maxEnergy=max(maxEnergy,e); maxAbs=max(maxAbs,abs(radiated)); sample++
            return radiated
        }
        fun energy(): Double = (bore?.energy() ?: 0.0)+(bridge?.energy() ?: 0.0)+(nut?.energy() ?: 0.0)+
            .08*(mode1.energy()+mode2.energy())+.004*lip.energy()
        fun state(): DoubleArray = (bore?.state() ?: doubleArrayOf())+(bridge?.state() ?: doubleArrayOf())+
            (nut?.state() ?: doubleArrayOf())+doubleArrayOf(lip.re,lip.im,mode1.re,mode1.im,mode2.re,mode2.im,outputDc.toDouble(),noiseLp.toDouble(),contactNoiseLp.toDouble())
    }
    private fun tanhUnit(x: Float): Float = kotlin.math.tanh(x)

    private fun effectiveMidi(voice: EmbouchureVoice, m: Map<String,Float>, base: Int): Int {
        val midi=base+Math.round(m.getValue("TUNE")*TUNE_SEMITONES)
        require(midi in midiRange(voice)) { "EMBOUCHURE $voice supports MIDI ${midiRange(voice)}, got $midi (base $base + TUNE)" }
        return midi
    }
    internal fun play(voice: EmbouchureVoice, macros: Map<String,Float>, probe: Probe=Probe()): Played {
        require(supportsModel(probe.model)) { "unsupported embouchure model ${probe.model}" }
        require(probe.model!=BRASS_GROW_MODEL) { "use BrassGrow.playSource for model-3 physical diagnostics" }
        require(probe.gestureDurationSeconds==null || (probe.gestureDurationSeconds.isFinite() &&
            probe.gestureDurationSeconds in .05f..16f)) { "gesture duration override must be .05..16 seconds" }
        require(probe.pressureScore==null || !probe.held) { "custom pressure scores use the finite-calibration source" }
        if(probe.model==1) {
            val p=EmbouchureV1.play(voice,macros,EmbouchureV1.Probe(
                velocity=probe.velocity,feedback=probe.feedback,sourceStopSeconds=probe.sourceStopSeconds,
                totalSeconds=probe.totalSeconds,rate=probe.rate,seed=probe.seed,midi=probe.midi,
                gesture=probe.gesture,record=probe.record,traceHop=probe.traceHop,held=probe.held,
                controlStride=probe.controlStride))
            val t=Trace(p.trace.rate,p.trace.hop,p.trace.player.size)
            p.trace.player.copyInto(t.player); p.trace.returning.copyInto(t.returning)
            p.trace.injected.copyInto(t.injected); p.trace.driver.copyInto(t.driver); p.trace.energy.copyInto(t.energy)
            t.maxEnergy=p.trace.maxEnergy; t.maxAbs=p.trace.maxAbs; t.finite=p.trace.finite
            return Played(p.raw,t,p.hz,p.sourceEndSeconds,p.rate,p.seed,p.maxEnergy,p.maxAbs)
        }
        require(probe.rate==Dsp.RATE || probe.rate==Dsp.RATE*2 || probe.rate==INTERNAL_RATE || probe.rate==INTERNAL_RATE*2)
        require(probe.traceHop>0 && probe.controlStride in 1..64)
        val m=settled(macros,voice)
        val midi=effectiveMidi(voice,m,probe.midi ?: rootMidi(voice))
        val hz=Keys.midiHz(midi)
        val e=Engine(voice,m,probe,midi,hz,probe.gesture ?: gestureFor(voice))
        val seconds=(probe.totalSeconds ?: probe.gestureDurationSeconds?.plus(.85f))?.let {
            if(it.isFinite()) it.coerceIn(.02f,20f) else 6f }
        val frames=seconds?.let { (it*probe.rate).toInt() } ?: renderFrames(voice,m)*(probe.rate/Dsp.RATE)
        val raw=FloatArray(frames)
        val trace=Trace(probe.rate.toFloat()/probe.traceHop,probe.traceHop,if(probe.record) (frames+probe.traceHop-1)/probe.traceHop else 0)
        for(i in raw.indices) {
            if(i%8192==0 && Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("EMBOUCHURE render no longer wanted")
            raw[i]=e.step()
            if(probe.record && i%probe.traceHop==0) {
                val k=i/probe.traceHop
                // Player input is energy loading as well as score shape. A virtual velocity changes
                // the player's pressure/speed effort, and zero velocity supplies no work.
                trace.player[k]=e.playerNow
                trace.returning[k]=e.returned; trace.injected[k]=e.injected
                trace.driver[k]=e.driverNow; trace.energy[k]=e.energy().toFloat()
            }
        }
        trace.maxEnergy=e.maxEnergy; trace.maxAbs=e.maxAbs
        return Played(raw,trace,hz,e.stop,probe.rate,e.seed,e.maxEnergy,e.maxAbs)
    }

    /** The production path is band-limited before decimation; diagnostic velocity never gets normalised. */
    internal fun finish(raw: FloatArray, normalize: Boolean=true, loop: Boolean=false, rate: Int=INTERNAL_RATE): FloatArray {
        // The synthesis loops poll the preview thread's interrupt; this band-limit and
        // decimation pass over the full oversampled buffer deserves its own check first.
        if(Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("EMBOUCHURE render no longer wanted")
        val copy=raw.copyOf()
        Tide.bandLimit(copy,rate)
        val out=if(rate==INTERNAL_RATE) Dsp.decimate(copy,Dsp.RATE) else if(rate==Dsp.RATE) copy else
            Resampler.resample(Snip(copy,channels=1,sampleRate=rate),Dsp.RATE).samples
        if(loop) {
            val mean=out.sumOf { it.toDouble() }/max(1,out.size)
            for(i in out.indices) out[i]-=mean.toFloat()
        }
        if(normalize) Dsp.levelTo(out,Dsp.RATE,target=Dsp.MELODIC_LOUDNESS_TARGET)
        if(!loop) Dsp.fadeTail(out)
        // Passive conditioning/decimation can create new subnormal tails after the model's
        // own stores reached zero. Flush the exported samples too, before host velocity gain.
        for(i in out.indices) out[i]=acousticZero(out[i])
        return out
    }

    internal class LoopRender(val audio: FloatArray, val loopStart: Int, val seam: Double,
                              val settleSeconds: Float, val converged: Boolean, val stateError: Double,
                              val hz: Float, val seed: Int, val rawRms: Double) {
        val samples: FloatArray get()=audio.copyOfRange(loopStart,audio.size)
    }

    /**
     * Whole pitched periods fit a 1.65-2.2s contour without semitone quantisation. Warm the entire
     * driver/delay/body/noise state, measure its TRUE captured period, retune by that residual,
     * and compare two successive continuous regions. Copying a loop twice is not the seam test.
     * Periodic seeded texture stays bounded; HOLD uses shallower contact motion than a finite
     * overpressure/capture gesture to avoid a chaotic or permanently overblown regime.
     */
    internal fun renderLoopMeasured(voice: EmbouchureVoice, macros: Map<String,Float>, midi: Int=rootMidi(voice),
                                    velocity: Float=1f, gesture: EmbouchureGesture=gestureFor(voice),
                                    normalize: Boolean=true, model: Int=MODEL_VERSION): LoopRender {
        require(supportsModel(model)) { "unsupported embouchure model $model" }
        require(model!=BRASS_GROW_MODEL) { "model 3 uses played-source capture; use BrassGrow.renderMeasured" }
        if(model==1) {
            val p=EmbouchureV1.renderLoopMeasured(voice,macros,midi,velocity,gesture,normalize)
            return LoopRender(p.audio,p.loopStart,p.seam,p.settleSeconds,p.converged,p.stateError,p.hz,p.seed,p.rawRms)
        }
        val m=settled(macros,voice)
        val note=effectiveMidi(voice,m,midi)
        val target=Keys.midiHz(note)
        val cycles=ceil(heldSeconds(m)*target).toInt()
        val frames=Math.round(cycles*Dsp.RATE.toDouble()/target).toInt()
        if(unit(velocity,1f)==0f) return LoopRender(FloatArray(frames*2),frames,0.0,0f,true,0.0,target,
            seedFor(voice,note),0.0)
        val period=frames*Dsp.OVERSAMPLE
        var warmPeriods=if(voice==EmbouchureVoice.BOW) 3 else 2
        var tuned=target.toDouble()
        var best: LoopRender?=null
        for(pass in 0 until 7) {
            val warm=period*warmPeriods
            val probe=Probe(velocity=velocity,record=false,held=true)
            val e=Engine(voice,m,probe,note,tuned.toFloat(),gesture,period)
            val raw=FloatArray(warm+period*2+4096)
            var stateA: DoubleArray?=null
            var stateB: DoubleArray?=null
            for(i in raw.indices) {
                if(i%8192==0 && Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("EMBOUCHURE loop no longer wanted")
                raw[i]=e.step()
                if(i==warm-1) stateA=e.state()
                if(i==warm+period-1) stateB=e.state()
            }
            val measured=Bore.measureLoopSamples(raw,warm,period.toDouble(),cycles)
            val ratio=if(pass<2) measured/period else phaseRatio(raw,warm,period,cycles)
            val conditioned=finish(raw,normalize=false,loop=true)
            val kept=conditioned.copyOfRange(warm/Dsp.OVERSAMPLE,warm/Dsp.OVERSAMPLE+frames*2)
            val seam=Keys.seamError(kept,frames)
            val a=stateA!!; val b=stateB!!
            var diff=0.0; var norm=0.0
            for(k in a.indices) { diff+=(a[k]-b[k]).pow(2); norm+=.5*(a[k]*a[k]+b[k]*b[k]) }
            val stateError=sqrt(diff/max(1e-12,norm))
            val rms=sqrt(raw.sliceArray(warm until warm+period).sumOf { it.toDouble()*it }/period)
            if(normalize) {
                Dsp.levelTo(kept,Dsp.RATE,target=Dsp.MELODIC_LOUDNESS_TARGET)
                val gain=unit(velocity,1f).pow(.60f)
                for(i in kept.indices) kept[i]=acousticZero(kept[i]*gain)
            }
            val converged=seam<Keys.MAX_SEAM_ERROR && stateError<.02
            val result=LoopRender(kept,frames,seam,warm.toFloat()/INTERNAL_RATE,converged,
                stateError,tuned.toFloat(),e.seed,rms)
            if(best==null || (converged && !best.converged) || (converged==best.converged && seam<best.seam)) best=result
            if(converged && seam<3e-4 && abs(ratio-1.0)<2e-7) break
            if(stateError>=.02 && warmPeriods<7) warmPeriods++
            tuned*=ratio
        }
        return best!!
    }
    /** Fundamental phase between complete repeated contours refines the noisy long-lag estimate.
     * The first two passes resolve whole-cycle ambiguity with the shared period measurement. */
    private fun phaseRatio(raw: FloatArray, from: Int, period: Int, cycles: Int): Double {
        val angle=2*PI*cycles/period
        val dc=cos(angle); val ds=sin(angle)
        var c=1.0; var s=0.0
        var ar=0.0; var ai=0.0; var br=0.0; var bi=0.0
        for(i in 0 until period) {
            val a=raw[from+i]; val b=raw[from+period+i]
            ar+=a*c; ai-=a*s; br+=b*c; bi-=b*s
            val nc=c*dc-s*ds; s=s*dc+c*ds; c=nc
        }
        val drift=atan2(bi*ar-br*ai,br*ar+bi*ai)
        return 1.0-drift/(2*PI*cycles)
    }
    internal fun renderLoop(voice: EmbouchureVoice, macros: Map<String,Float>, midi: Int=rootMidi(voice),
                            velocity: Float=1f, gesture: EmbouchureGesture=gestureFor(voice), model: Int=MODEL_VERSION): FloatArray {
        require(supportsModel(model)) { "unsupported embouchure model $model" }
        if(model==1) return EmbouchureV1.renderLoop(voice,macros,midi,velocity,gesture)
        val result=renderLoopMeasured(voice,macros,midi,velocity,gesture,model=model)
        check(result.converged) { "EMBOUCHURE $voice held state did not settle: seam=${result.seam}, state=${result.stateError}" }
        val one=result.samples
        val cut=Siren.bestCut(one,one.size)
        return one.copyOfRange(cut,one.size)+one.copyOfRange(0,cut)
    }
    fun render(voice: EmbouchureVoice, macros: Map<String,Float> = emptyMap(), midi: Int=rootMidi(voice),
               velocity: Float=1f, gesture: EmbouchureGesture=gestureFor(voice), model: Int=MODEL_VERSION): Snip {
        require(supportsModel(model)) { "unsupported embouchure model $model" }
        if(model==BRASS_GROW_MODEL) {
            require(voice==EmbouchureVoice.BRASS) { "EMBOUCHURE model 3 is BRASS only" }
            return BrassGrow.render(macros,midi,velocity)
        }
        if(model==1) return EmbouchureV1.render(voice,macros,midi,velocity,gesture)
        val m=settled(macros,voice)
        if(isLoop(m.getValue("HOLD"))) return Snip(renderLoop(voice,m,midi,velocity,gesture),channels=1,sampleRate=Dsp.RATE)
        val played=play(voice,m,Probe(midi=midi,velocity=velocity,gesture=gesture,record=false))
        val out=finish(played.raw)
        // Shared melodic level is the forte reference; host velocity preserves its intended loudness
        // after matching while also changing source work, capture and contact BEFORE matching.
        val gain=unit(velocity,1f).pow(.60f)
        for(i in out.indices) out[i]=acousticZero(out[i]*gain)
        return Snip(out,channels=1,sampleRate=Dsp.RATE)
    }
}
