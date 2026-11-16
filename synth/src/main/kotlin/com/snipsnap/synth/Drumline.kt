package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.random.Random

/** A battery section, or an authored finite articulation. Bass sizes each have one player. */
enum class DrumlineVoice {
    SNARE, RIMSHOT, TENOR_1, TENOR_2, TENOR_3, TENOR_4, TENOR_5, TENOR_6,
    BASS_1, BASS_2, BASS_3, BASS_4, BASS_5, CRASH, CHOKE, TOUCH,
    FLAM, ROLL, RISER, BASS_UNISON, BASS_SPLIT
}

/**
 * Coordinated contact -> independent heads/plates -> overlapping ring -> finite release.
 * Model 1 ports the preferred Python cymbal revision's mechanism, not its NumPy noise bytes.
 * Model 2 adds lower-head body, a separate rim contact and a short snare-wire snap.
 * Model 3 makes rim contact dominate the rimshot and reduces its shared snare-wire tail.
 * Model 4 gives the smallest bass a broader mallet and stronger lower-head body.
 * Saved model-1/2/3 recipes retain their sound; unrelated voices are unchanged.
 * Positive modal loss bounds each oscillator. Choking progressively damps high modes first;
 * it never gates the entire plate to zero. All acoustic constants remain listening shapes.
 */
object Drumline {
    const val MODEL_VERSION = 4
    private const val IDENTITY_VERSION = 1 // Keep the same players and excitation streams across revisions.
    private const val RATE = Dsp.RATE * Dsp.OVERSAMPLE
    private const val LOSS = 6.907755278982137
    private val metal = setOf(DrumlineVoice.CRASH, DrumlineVoice.CHOKE, DrumlineVoice.TOUCH)
    private val figures = setOf(DrumlineVoice.FLAM, DrumlineVoice.ROLL, DrumlineVoice.RISER, DrumlineVoice.BASS_UNISON, DrumlineVoice.BASS_SPLIT)
    fun macrosFor(voice: DrumlineVoice): List<MacroSpec> = buildList {
        add(MacroSpec("TUNE", .5f)); add(MacroSpec("STRIKE", .55f)); add(MacroSpec("DAMP", .5f))
        if (!voice.name.startsWith("BASS")) {
            add(MacroSpec("PLAYERS", if (voice in metal) 2f/3 else if (voice.name.startsWith("TENOR")) 2f/3 else 5f/7))
            add(MacroSpec("TIGHT", .8f))
        }
    }
    fun defaults(voice: DrumlineVoice) = macrosFor(voice).associate { it.name to it.default }
    fun settled(voice: DrumlineVoice, macros: Map<String, Float>) = defaults(voice).mapValues { (k,v) -> macros[k]?.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: v }
    fun scramble(voice: DrumlineVoice, random: Random, temperature: Float = .35f, near: Patch? = null) =
        Dsp.scrambleNear(settled(voice, near?.macros ?: emptyMap()), temperature, random)
    fun drumClassFor(voice: DrumlineVoice) = when {
        voice == DrumlineVoice.SNARE || voice == DrumlineVoice.RIMSHOT || voice == DrumlineVoice.FLAM -> DrumClass.SNARE
        voice.name.startsWith("TENOR") || voice.name.startsWith("BASS_") && voice !in figures -> DrumClass.TOM
        else -> DrumClass.PERC // Long releases and scored rudiments remain finite, not sustained loops.
    }
    data class Event(val seconds: Double, val voice: DrumlineVoice, val strength: Float, val hand: Int = 0)
    fun score(voice: DrumlineVoice): List<Event> = when (voice) {
        DrumlineVoice.FLAM -> listOf(Event(0.0, DrumlineVoice.SNARE,.3f,1),Event(.025,DrumlineVoice.SNARE,1f))
        DrumlineVoice.ROLL -> List(32) { Event(it*.075,DrumlineVoice.SNARE,if(it%2==0).78f else .48f,if(it%4<2)0 else 1) } + Event(2.5,DrumlineVoice.RIMSHOT,1f)
        DrumlineVoice.RISER -> buildList {
            var at=0.0
            for(i in 0..28) { add(Event(at,DrumlineVoice.SNARE,.28f+.65f*i/28,i%2)); if(i<28) at+=.13-(.13-.035)*i/27 }
            add(Event(at+.12,DrumlineVoice.RIMSHOT,1f))
        }
        DrumlineVoice.BASS_UNISON -> (1..5).map { Event(0.0,DrumlineVoice.valueOf("BASS_$it"),.9f) }
        DrumlineVoice.BASS_SPLIT -> List(16) { Event(it*.16,DrumlineVoice.valueOf("BASS_${intArrayOf(1,2,3,4,5,4,3,2)[it%8]}"),.8f) }
        else -> listOf(Event(0.0,voice,1f))
    }
    fun render(voice: DrumlineVoice, macros: Map<String,Float> = emptyMap(), velocity: Float = 1f, take: Int = 0, model: Int = MODEL_VERSION): Snip {
        val raw=renderRaw(voice,macros,velocity,take,model)
        Tide.bandLimit(raw,RATE)
        val out=Dsp.decimate(raw,Dsp.RATE)
        val mean=out.average().toFloat(); val hp=Dsp.FixedOnePole(20f,Dsp.RATE)
        for(i in out.indices) { val x=out[i]-mean;out[i]=x-hp.lp(x) }
        Dsp.levelTo(out,Dsp.RATE,Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out,1,Dsp.RATE)
    }
    /** Internal-rate signal before the shared output stage, exposed for spectral audits. */
    internal fun renderRaw(voice: DrumlineVoice, macros: Map<String,Float> = emptyMap(), velocity: Float = 1f,
                           take: Int = 0, model: Int = MODEL_VERSION): FloatArray {
        require(model in 1..MODEL_VERSION && take >= 0)
        val m=settled(voice,macros); val v=velocity.takeIf { it.isFinite() }?.coerceIn(.05f,1f) ?: 1f
        return if(voice !in figures) strike(voice,m,v,take,0,model) else {
            val score=score(voice)
            val out=FloatArray(((score.maxOf { it.seconds }+1.3)*RATE).roundToInt())
            for((i,e) in score.withIndex()) {
                val hit=strike(e.voice,settled(e.voice,m),v*e.strength,take*64+i,e.hand,model)
                val at=(e.seconds*RATE).roundToInt();val gain=if(voice==DrumlineVoice.BASS_UNISON) 1/sqrt(5.0) else 1.0
                for(k in 0 until minOf(hit.size,out.size-at)) out[at+k]+=(hit[k]*gain).toFloat()
            }
            out
        }
    }
    /** Integral of smoothstep hand contact; its derivative is nonnegative. */
    internal fun contactIntegral(t: Double, start: Double, width: Double = .14): Double {
        val u=max(0.0,t-start);val z=min(u,width)
        return z*z*z/(width*width)-.5*z*z*z*z/(width*width*width)+max(0.0,u-width)
    }
    /** A damped complex rotation. No trig or natural-decay exponentials in the sample loop. */
    private fun addMode(out: DoubleArray, hz: Double, gain: Double, life: Double, phase: Double = 0.0,
                        force: DoubleArray? = null, chokeStart: Double? = null) {
        if(hz>=18000 || life<=0)return
        val w=2*PI*hz/RATE;val q=exp(-LOSS/(life*RATE));val a=cos(w);val b=sin(w)
        var real=cos(phase);var imag=sin(phase)
        if(force!=null) { real=0.0;imag=0.0 }
        var damp=q
        for(i in out.indices) {
            if(chokeStart!=null && i%64==0) {
                val t=i.toDouble()/RATE
                val extra=(13+29*(hz/15500).pow(.65))*(contactIntegral(t+64.0/RATE,chokeStart)-contactIntegral(t,chokeStart))/64
                damp=q*exp(-extra)
            }
            if(force!=null && i<force.size)real+=force[i]
            out[i]+=gain*imag
            val next=(real*a-imag*b)*damp
            imag=(imag*a+real*b)*damp;real=next
        }
    }
    /** RBJ bandpass, double precision. A cord resonance rather than broadband hiss. */
    private class Band(hz: Double, q: Double) {
        private val w=2*PI*hz/RATE;private val alpha=sin(w)/(2*q)
        private val b=alpha/(1+alpha);private val a1=-2*cos(w)/(1+alpha);private val a2=(1-alpha)/(1+alpha)
        private var x1=0.0;private var x2=0.0;private var y1=0.0;private var y2=0.0
        fun at(x: Double): Double { val y=b*x-b*x2-a1*y1-a2*y2;x2=x1;x1=x;y2=y1;y1=y;return y }
    }
    private fun random(noise: Dsp.Noise, lo: Double, hi: Double) = lo+(hi-lo)*(noise.next()+1)/2
    private fun strike(voice: DrumlineVoice,m: Map<String,Float>,velocity: Float,take: Int,hand: Int,model: Int): FloatArray {
        val isMetal=voice in metal;val snare=voice==DrumlineVoice.SNARE || voice==DrumlineVoice.RIMSHOT
        val distinctRim=model >= 3 && voice == DrumlineVoice.RIMSHOT
        val smallBass=model >= 4 && voice == DrumlineVoice.BASS_1
        val family=if(isMetal)"CYMBAL" else if(snare)"SNARE" else voice.name
        val cap=if(isMetal)4 else if(snare)8 else if(voice.name.startsWith("TENOR"))4 else 1
        val count=1+((m["PLAYERS"]?:0f)*(cap-1)).roundToInt()
        val damp=2.0.pow(1.0-2*m.getValue("DAMP"));val tilt=2.0.pow((m.getValue("TUNE")-.5)*.7)
        val duration=when(voice) { DrumlineVoice.CRASH -> 4.5*damp;DrumlineVoice.CHOKE -> 1.4;DrumlineVoice.TOUCH -> .9*damp;else -> 1.2 }
        val out=FloatArray((duration*RATE).roundToInt())
        for(player in 0 until count) {
            val identity=Dsp.Noise(Dsp.seedFor("DRUMLINE",IDENTITY_VERSION,family,player))
            val hit=Dsp.Noise(Dsp.seedFor("DRUMLINE",IDENTITY_VERSION,family,player,take,hand))
            val noise=Dsp.Noise(Dsp.seedFor("DRUMLINE_NOISE",IDENTITY_VERSION,family,player,take,hand))
            val detune=random(identity,-6.0,6.0)
            val delay=if(player==0)0 else (random(hit,.0005,.002+.010*(1-(m["TIGHT"]?:.8f)).pow(2))*RATE).roundToInt()
            val v=min(1.0,velocity*random(hit,.96,1.02));val n=out.size-delay;val y=DoubleArray(n)
            if(isMetal) {
                for(k in 0 until 112) {
                    val f=340.0*(15500.0/340).pow(k/111.0)*random(identity,.97,1.03)*tilt
                    val ring=exp(-.5*(ln(f/1300)/.62).pow(2))
                    val g=(f/1000).pow(-.48)*exp(-f/((2800+10000*v)*(.6+.4*m.getValue("STRIKE")/.55)))*random(hit,.75,1.25)/9
                    val life=(1+2.2*exp(-f/2300))*(1+.65*ring)*damp*(if(voice==DrumlineVoice.TOUCH).18 else 1.0)
                    addMode(y,f,g,life,random(hit,-PI,PI),chokeStart=if(voice==DrumlineVoice.CHOKE).18+player*.008 else null)
                }
                val low=Dsp.FixedOnePole(12000f,RATE);val high=Dsp.FixedOnePole(1800f,RATE)
                for(i in y.indices) {
                    val t=i.toDouble()/RATE;val x=noise.next();val h=x-high.lp(x);val wash=low.lp(h)
                    val loss=if(voice==DrumlineVoice.CHOKE)contactIntegral(t,.18+player*.008) else 0.0
                    y[i]=(y[i]+.55*v*wash*exp(-t/(if(voice==DrumlineVoice.TOUCH).045 else .12)-35*loss))*min(t/.0007,1.0)
                }
            } else {
                val index=voice.name.last().digitToIntOrNull()?.minus(1)?:0
                val root=(if(snare)345.0 else if(voice.name.startsWith("TENOR"))doubleArrayOf(390.0,330.0,270.0,220.0,175.0,140.0)[index] else doubleArrayOf(145.0,116.0,93.0,74.0,59.0)[index])*tilt*2.0.pow(detune/1200)
                val life=(if(snare).21 else if(voice.name.startsWith("TENOR")) .28+index*.035 else .43+index*.075)*damp
                val ratios=doubleArrayOf(1.0,1.594,2.136,2.296,2.653,2.918,3.156,3.50,4.06,4.61)
                val gains=doubleArrayOf(1.0,.62,.42,.23,.18,.13,.09,.07,.05,.03)
                val width=(.00018+.00065*(1-m.getValue("STRIKE")))*(1.65-.8*v)*random(hit,.92,1.08)*(if(family.startsWith("BASS"))5 else 1)*(if(smallBass)1.5 else 1.0)
                val force=DoubleArray(max(4,(width*RATE).roundToInt())) { .5-.5*cos(2*PI*it/(max(4,(width*RATE).roundToInt())-1)) }
                val sum=force.sum();for(i in force.indices)force[i]/=sum
                for(k in ratios.indices) {
                    // Let the lowest membrane modes carry a body after the stick/mallet leaves.
                    // Higher modes keep their old losses, so extra fullness does not become a long bright ring.
                    val body = model >= 2 && !snare
                    // A broader mallet favors the paired lower heads over the tenor-like upper ring.
                    val weight = (if(body && k < 2) (if(k == 0) 1.3 else 1.12) else 1.0) *
                        (if(smallBass && k > 0) .45 else 1.0)
                    val sustain = if(body) 1.0 + (if(family.startsWith("BASS")) .65+.35*index/4 else .5) / ratios[k].pow(2) else 1.0
                    addMode(y,root*ratios[k],gains[k]*exp(-ratios[k]*(.1+1.2*(1-v)))*random(hit,if(family.startsWith("BASS")) .65 else .9,if(family.startsWith("BASS")) 1.35 else 1.1)*weight*(if(distinctRim).85 else 1.0),life*sustain/ratios[k].pow(.65),force=force)
                }
                if(model >= 2 && !snare) {
                    // A lower companion-head resonance adds chest without a sub-octave oscillator.
                    // It follows the same finite mallet force and positive losses as the main head.
                    // On the largest basses, favor the main head over a barely audible sub-bass companion.
                    val companionWeight=if(family.startsWith("BASS")) (root/145).coerceIn(.35,1.0) else 1.0
                    addMode(y,root*(if(smallBass).70 else .78),(if(smallBass)1.6 else .38)*(.6+.4*v)*companionWeight,life*1.25,force=force)
                }
                if(voice==DrumlineVoice.RIMSHOT)for((f,g,l) in listOf(Triple(1250.0,.55,.055),Triple(2180.0,.38,.035),Triple(3380.0,.22,.022)))addMode(y,f*tilt,g*v.pow(.4),l)
                if(model >= 2 && voice == DrumlineVoice.RIMSHOT) {
                    // The stick meets the hard rim as well as the head: much shorter contact,
                    // with a compact inharmonic clack instead of only a pitched rim ring.
                    val rimWidth=max(4,(RATE*(if(distinctRim) .000055+.00006*(1-v) else .00009+.00008*(1-v))).roundToInt())
                    val rimForce=DoubleArray(rimWidth) { .5-.5*cos(2*PI*it/(rimWidth-1)) }
                    val rimSum=rimForce.sum();for(i in rimForce.indices)rimForce[i]/=rimSum
                    // A rimshot must be identifiable against the ordinary snare, not just
                    // brighter than an older rimshot. Give the hard contact a woody lower
                    // resonance and enough decay to outlast the very first noise transient.
                    val rimModes=if(distinctRim)
                        listOf(Triple(1050.0,3.8,.065),Triple(1980.0,2.6,.044),Triple(3250.0,1.5,.030),Triple(5100.0,.65,.018))
                    else listOf(Triple(1750.0,1.35,.028),Triple(2950.0,1.0,.019),Triple(4700.0,.7,.013))
                    for((f,g,l) in rimModes)
                        addMode(y,f*tilt*(if(distinctRim)2.0.pow(detune/1200) else 1.0),g*v.pow(.6),l,force=rimForce)
                }
                val contact=Dsp.FixedOnePole((2500+8500*v).toFloat(),RATE);val bands=listOf(Band(1900.0,2.0),Band(3600.0,2.5),Band(6200.0,3.0))
                val snapLow=Dsp.FixedOnePole(10500f,RATE);val snapHigh=Dsp.FixedOnePole(2200f,RATE)
                // These quantities are constant for this strike. Keep the exact arithmetic
                // order while avoiding map lookups, powers and filter setup per sample.
                val contactGain=.35*(.2+m.getValue("STRIKE"))*v
                val wireLife=if(distinctRim)life*.68 else life
                val wireGain=3.2*(if(distinctRim).32 else 1.0)*v.pow(.65)
                val wireDecay=wireLife*(.75+.4*v)
                val wireRate=2*PI*root*1.07
                val snapGain=2.8*v.pow(.8)
                for(i in y.indices) {
                    val t=i.toDouble()/RATE;val x=noise.next();val attack=min(t/.0007,1.0)
                    y[i]+=contactGain*contact.lp(x)*attack*exp(-t/.0012)
                    if(snare) {
                        val cords=.6*bands[0].at(x.toDouble())+bands[1].at(x.toDouble())+.6*bands[2].at(x.toDouble())
                        // The same wire bed was masking both voices. A rimshot still has
                        // snare wires, but its shorter, quieter response sits behind the rim.
                        y[i]+=wireGain*cords*exp(-LOSS*t/wireDecay)*(.65+.35*sin(wireRate*t).pow(2))*attack
                        if(model >= 2 && !distinctRim) {
                            // A brief wire/contact burst sharpens the leading edge without extending the hiss.
                            val snap=snapLow.lp(x-snapHigh.lp(x))
                            y[i]+=snapGain*snap*min(t/.00012,1.0)*exp(-t/.0035)
                        }
                    }
                }
            }
            val gain=v*random(identity,.94,1.06)/sqrt(count.toDouble())
            for(i in y.indices)out[i+delay]+=(y[i]*gain).toFloat()
        }
        return out
    }
}
