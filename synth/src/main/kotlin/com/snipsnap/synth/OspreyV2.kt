package com.snipsnap.synth

import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Model 2: the ocean plays five different materials. One exact root bears the note;
 * wire plucks, bow pressure, double wood contacts, bowl strikes and metal strums
 * excite the rest of the body. Weather blends laminar, chop and breaker lanes.
 * The lanes keep running at zero weight; neither coastline nor weather changes a seed.
 * These are bounded musical mechanics, not measured coastal folk instruments.
 */
internal object OspreyV2 {
    private const val RATE = Dsp.RATE
    private const val CONTROL = 64
    private const val TRIM = .36
    private val PRESSURE_RISE = doubleArrayOf(.15,.025,.005)
    private val PRESSURE_FALL = doubleArrayOf(.20,.05,.012)
    private data class Kick(val time: Double, val material: Int, val weather: Int,
        val activity: Double, val amplitude: Double, val widthMs: Double, val drive: DoubleArray)
    private data class Pressure(val time: Double, val duration: Double, val amplitude: Double, val weather: Int)
    private data class Plan(val kicks: List<Kick>, val pressure: List<Pressure>, val events: List<Osprey.ContactEvent>)

    fun probe(voice: OspreyVoice, macros: Map<String, Float>, midi: Int, velocity: Float, seed: Int,
        durationSeconds: Float, cancelled: () -> Boolean): Osprey.Probe {
        require(midi in Osprey.MIDI_MIN..Osprey.MIDI_MAX) { "OSPREY midi out of 36..84: $midi" }
        require(velocity.isFinite() && velocity in 0f..1f) { "OSPREY velocity out of 0..1: $velocity" }
        require(durationSeconds.isFinite() && durationSeconds in .25f..Osprey.MAX_SECONDS) { "OSPREY duration out of .25..12: $durationSeconds" }
        val m = Osprey.settled(voice, macros)
        val shore = m.getValue("SHORE")
        val sea = m.getValue("SEA").toDouble()
        val motion = m.getValue("MOTION").toDouble()
        val depth = m.getValue("DEPTH").toDouble()
        val space = m.getValue("SPACE").toDouble()
        val hold = m.getValue("HOLD").toDouble()
        val material = OspreyMaterial.morph(shore)
        val ecology = OspreyShore.profile(OspreyMaterial.ecologyPosition(shore))
        val weather = weatherWeights(sea)
        val root = Osprey.frequencyFor(midi).toDouble()
        val stop = min(durationSeconds * (.42 + .32 * hold), durationSeconds - min(1.4, durationSeconds * .25))
        val field = Ocean(ecology.fluid, motion, Dsp.seedFor("OSPREY", 2, seed, "FIELD"))
        val plan = plan(material.weights, weather, field, motion, velocity.toDouble(), stop, seed)
        val count = (durationSeconds * RATE).roundToInt()
        val samples = FloatArray(count * 2)
        val ocean = FloatArray(samples.size)
        val primary = FloatArray(samples.size)
        val sympathy = FloatArray(samples.size)
        val creatures = FloatArray(samples.size)
        val dry = FloatArray(samples.size)
        val modeHz = FloatArray(16)
        val modes = Array(16) { i ->
            val ratio = if (i < 12) material.ratios[i] else doubleArrayOf(2.0,3.0,5.0,8.0)[i-12]
            val t60 = if (i < 12) material.decay[i] / (1+.32*weather[2]) else 1.0+.8*depth
            val hz = (root*ratio).coerceIn(20.0,RATE*.43)
            modeHz[i]=hz.toFloat()
            Mode(hz,t60)
        }
        val radiation = DoubleArray(12) { material.radiation[it] * exp(-depth * it * .018) }
        val pan = doubleArrayOf(0.0,-.54,.42,-.30,.60,-.64,.25,-.40,.50,-.22,.34,-.48,-.46,.28,-.20,.52)
        val left = DoubleArray(16) { sqrt((1-pan[it])*.5) }
        val right = DoubleArray(16) { sqrt((1+pan[it])*.5) }
        // Pressure follows the speaking partials of the hybrid material. Keeping this
        // exciter harmonic while the body retuned to wood created a root-only resonant
        // hole between Nordic and Mediterranean shores.
        val bowSources = Array(12) { Oscillator(modes[it].frequency) }
        val bowDrive = OspreyMaterial.coasts[1].drive
        // A broad calm stick contact suppresses upper force harmonics; shorter chop and
        // breaker contacts release them. These are three source force spectra acting before
        // the body, not an EQ on the rendered bow or a change in output loudness.
        val stickWidth=doubleArrayOf(.00022,.00009,.000022)
        val bowSpectrum=Array(3) { lane -> DoubleArray(12) { i -> exp(-.5*sq(2*PI*modes[i].frequency*stickWidth[lane])) } }
        val noise = Dsp.Noise(Dsp.seedFor("OSPREY",2,seed,"FORCE"))
        val callers = Array(3) { i -> Caller(Osprey.CreatureKind.entries[i],ecology.creatures[i],
            Dsp.seedFor("OSPREY",2,seed,"CALL",i),sea,motion,depth) }
        val callPans = doubleArrayOf(-.56,.08,.58)
        val callLeft = DoubleArray(3) { sqrt((1-callPans[it])*.5) }
        val callRight = DoubleArray(3) { sqrt((1+callPans[it])*.5) }
        val calls = ArrayList<Osprey.CallEvent>()
        val room = Array(2) { Harbor(it) }
        val junction = .0010*(.5+.5*motion)
        val jc = cos(junction); val js = sin(junction)
        val partners = intArrayOf(2,5,8,10)
        var nextKick=0
        var nextPressure=0
        val activePressure = ArrayList<Pressure>()
        val pressureByLane=DoubleArray(3)
        var activity=.0
        var gate=.0
        var monitorBurst=.0
        var peakEnergy=.0
        var inputEnergy=plan.events.sumOf { it.energy.toDouble() }
        var pressureFrames=0
        var recoveries=0
        var monitorIn=.0; var monitorOut=.0
        val dcPole=exp(-2*PI*8/RATE)
        for (frame in 0 until count) {
            if (frame%4096==0 && (cancelled() || Thread.currentThread().isInterrupted)) throw CancellationException("OSPREY render no longer wanted")
            val time=frame.toDouble()/RATE
            val after=time+1.0/RATE
            if (frame%CONTROL==0) {
                activity=field.at(time,weather)
                gate=smooth(time/.035)*smooth((stop-time)/min(.3,durationSeconds*.18))*velocity
                for (caller in callers) caller.control(time,CONTROL.toDouble()/RATE,activity,gate,stop)?.let {
                    calls.add(Osprey.CallEvent(caller.eventTime.toFloat(),caller.kind,shore,activity.toFloat(),it.toFloat()))
                }
            }
            while (nextPressure<plan.pressure.size && plan.pressure[nextPressure].time<=time) activePressure.add(plan.pressure[nextPressure++])
            var pressure=.0
            pressureByLane.fill(.0)
            var p=0
            while (p<activePressure.size) {
                val window=activePressure[p]
                val age=time-window.time
                if (age>=window.duration) { activePressure.removeAt(p); continue }
                val rise=PRESSURE_RISE[window.weather]
                val fall=PRESSURE_FALL[window.weather]
                val contactPressure=window.amplitude*smooth(age/min(rise,window.duration*.3))*smooth((window.duration-age)/min(fall,window.duration*.3))
                pressure+=contactPressure
                pressureByLane[window.weather]+=contactPressure
                p++
            }
            val pressureScale=gate/(1+pressure)
            pressure*=pressureScale
            if (pressure>1e-6) pressureFrames++
            val n=noise.next().toDouble()
            var bowMonitor=.0
            for (i in 0 until 12) {
                val tone=bowSources[i].next()
                // Friction is an authored pressure-driven partial family acting through the
                // modes. Its pressure window belongs to the Nordic material, never a common
                // continuously audible fundamental underneath the other four coasts.
                var harmonicPressure=.0
                for (lane in 0..2) harmonicPressure+=pressureByLane[lane]*bowSpectrum[lane][i]
                harmonicPressure*=pressureScale
                val turbulencePressure=(pressureByLane[0]*.002+pressureByLane[1]*.010+pressureByLane[2]*.042)*pressureScale
                val force=bowDrive[i]*(harmonicPressure*modes[i].loss*.90*tone + turbulencePressure*modes[i].noiseScale*n)
                modes[i].v+=force
                inputEnergy+=force*force*.5
                if (i==0) bowMonitor=tone*pressure
            }
            for (mode in modes) if (!mode.step()) recoveries++
            while (nextKick<plan.kicks.size && plan.kicks[nextKick].time<after) {
                val kick=plan.kicks[nextKick++]
                // Gaussian contact width gives the force's participation at each mode.
                // Apply its analytic fractional-time response; no sample-grid impulse
                // relocation and no post-render EQ is involved.
                for (i in 0 until 12) {
                    val transfer=exp(-.5*sq(2*PI*modeHz[i]*kick.widthMs*.00035))
                    modes[i].kick(kick.amplitude*kick.drive[i]*transfer,after-kick.time)
                }
                monitorBurst=min(1.0,monitorBurst+abs(kick.amplitude)*.5)
            }
            for (i in 0..3) {
                val a=modes[partners[i]];val b=modes[12+i]
                val x=a.x;val v=a.v
                a.x=jc*x-js*b.x;b.x=js*x+jc*b.x
                a.v=jc*v-js*b.v;b.v=js*v+jc*b.v
            }
            var pl=.0;var pr=.0;var sl=.0;var sr=.0
            for (i in 0 until 12) { val value=modes[i].v*radiation[i];pl+=value*left[i];pr+=value*right[i] }
            for (i in 12 until 16) { val value=modes[i].v*.15;sl+=value*left[i];sr+=value*right[i] }
            var cl=.0;var cr=.0
            for (i in 0..2) { val value=callers[i].next(time);cl+=value*callLeft[i];cr+=value*callRight[i] }
            val at=frame*2
            primary[at]=(pl*TRIM).toFloat();primary[at+1]=(pr*TRIM).toFloat()
            sympathy[at]=(sl*TRIM).toFloat();sympathy[at+1]=(sr*TRIM).toFloat()
            creatures[at]=(cl*TRIM).toFloat();creatures[at+1]=(cr*TRIM).toFloat()
            val monitor=(.045*n*monitorBurst+.035*bowMonitor+.008*n*gate*activity*weather[2])
            val dc=monitor-monitorIn+dcPole*monitorOut;monitorIn=monitor;monitorOut=dc
            ocean[at]=(dc*TRIM).toFloat();ocean[at+1]=ocean[at]
            monitorBurst*=.996
            for (channel in 0..1) {
                val value=primary[at+channel]+sympathy[at+channel]+creatures[at+channel]
                dry[at+channel]=value
                samples[at+channel]=if (space==0.0) value else (value+space*.42*room[channel].next(value.toDouble())).toFloat()
            }
            if (frame%CONTROL==0) peakEnergy=max(peakEnergy,modes.sumOf { it.energy() })
        }
        for (tap in listOf(samples,ocean,primary,sympathy,creatures,dry)) Dsp.fadeTail(tap,12f,RATE,2)
        val peak=samples.maxOfOrNull { abs(it) } ?: 0f
        check(recoveries==0 && samples.all { it.isFinite() }) { "OSPREY model 2 recovered an invalid state" }
        return Osprey.Probe(samples,ocean,primary,sympathy,creatures,dry,calls,
            Osprey.Metrics(peak,modes.sumOf { it.energy() },peakEnergy,recoveries,stop.toFloat(),inputEnergy,pressureFrames),
            root.toFloat(),modeHz,plan.events,weather.map(Double::toFloat).toFloatArray(),intArrayOf(0))
    }

    private fun weatherWeights(sea:Double):DoubleArray {
        val chopping=smooth((sea-.18)/.42)
        val breaking=smooth((sea-.62)/.38)
        return doubleArrayOf(1-chopping,chopping*(1-breaking),chopping*breaking)
    }
    private fun plan(weights:DoubleArray,weather:DoubleArray,field:Ocean,motion:Double,velocity:Double,stop:Double,seed:Int):Plan {
        val kicks=ArrayList<Kick>();val pressure=ArrayList<Pressure>();val events=ArrayList<Osprey.ContactEvent>()
        val onset=doubleArrayOf(.055,.12,.027,.16,.082)
        for (coast in 0..4) for (lane in 0..2) {
            val body=OspreyMaterial.coasts[coast]
            var time=(onset[coast]+doubleArrayOf(0.0,.085,.045)[lane])*min(1.0,stop/.35)
            var count=0
            val random=Dsp.Noise(Dsp.seedFor("OSPREY",2,seed,"LANE",coast,lane))
            val base=when (lane) {0->body.period;1->body.chop;else->body.breaker}/(.65+1.3*motion)
            while (time<stop-.015) {
                val activity=field.at(time,weather)
                val amplitude=weights[coast]*weather[lane]*velocity*doubleArrayOf(.80,.55,.41)[lane]*(.60+.40*activity)
                val contacts=when(coast) {
                    0->intArrayOf(1,3,5)[lane]
                    1->intArrayOf(1,1,3)[lane]
                    2->intArrayOf(2,2,4)[lane]
                    3->intArrayOf(1,1,3)[lane]
                    else->intArrayOf(3,4,6)[lane]
                }
                val spacings=when(coast) {
                    0->doubleArrayOf(0.0,.009,.022,.047,.076)
                    1->doubleArrayOf(0.0,.038,.083)
                    2->doubleArrayOf(0.0,.018,.052,.083)
                    3->doubleArrayOf(0.0,.028,.085)
                    else->doubleArrayOf(0.0,.011,.029,.058,.091,.134)
                }
                if (coast==1 && amplitude>1e-10) {
                    val duration=doubleArrayOf(1.7,.26,.10)[lane]*(.75+.40*activity)
                    pressure.add(Pressure(time,duration,amplitude*2.2,lane))
                }
                for (sub in 0 until contacts) {
                    val contactTime=time+spacings[sub]*doubleArrayOf(1.0,.8,.5)[lane]
                    if (contactTime>=stop) continue
                    val width=body.widthMs*doubleArrayOf(1.0,.40,.14)[lane]
                    val drive=DoubleArray(12) { i ->
                        val group=if ((coast==0 || coast==4) && contacts>1) (if(i%3==sub%3)1.0 else .20) else 1.0
                        val pick=if(coast==0) .45+.55*abs(sin(PI*body.ratios[i]*.19)) else 1.0
                        body.drive[i]*group*pick
                    }
                    val norm=sqrt(drive.sumOf { it*it })
                    for (i in drive.indices) drive[i]/=norm
                    val polarity=if(coast==2 && sub%2==1)-.72 else 1.0
                    val amount=amplitude/sqrt(contacts.toDouble())*polarity*(if(coast==1).16 else 1.0)
                    // Inactive lanes still advance their deterministic schedules, but their
                    // zero-energy contacts are excluded from audible-event diagnostics.
                    if (abs(amount)>1e-10) {
                        kicks.add(Kick(contactTime,coast,lane,activity,amount,width,drive))
                        events.add(Osprey.ContactEvent(contactTime.toFloat(),Osprey.ContactKind.entries[lane],coast,
                            activity.toFloat(),(.5*amount*amount).toFloat(),width.toFloat()))
                    }
                }
                time+=base*(.82+.34*(random.next()+1)*.5)*(1+.12*(.5-activity))
                count++
                check(count<400) { "OSPREY exciter schedule escaped its budget" }
            }
        }
        return Plan(kicks.sortedBy { it.time },pressure.sortedBy { it.time },events.sortedBy { it.timeSeconds })
    }
    private fun sq(x:Double)=x*x
    private fun smooth(x:Double):Double {val t=x.coerceIn(0.0,1.0);return t*t*(3-2*t)}
    private class Mode(hz:Double,t60:Double) {
        var x=.0;var v=.0
        val frequency=hz
        private val omega=2*PI*hz
        private val radius=exp(-6.907755278982137/(t60*RATE))
        val loss=1-radius
        val noiseScale=sqrt(1-radius*radius)
        private val c=radius*cos(omega/RATE);private val s=radius*sin(omega/RATE)
        fun step():Boolean {val a=x;x=c*a+s*v;v=c*v-s*a
            if(!x.isFinite() || !v.isFinite()){x=.0;v=.0;return false}
            if(abs(x)+abs(v)<1e-25){x=.0;v=.0};return true}
        fun kick(amount:Double,offset:Double){x+=amount*sin(omega*offset);v+=amount*cos(omega*offset)}
        fun energy()=.5*(x*x+v*v)
    }
    private class Ocean(private val fluid:OspreyShore.Fluid,private val motion:Double,private val seed:Int) {
        private fun hash(index:Int,salt:Int):Double {
            var h=index*374761393+seed+salt*668265263
            h=(h xor(h ushr 13))*1274126177;h=h xor(h ushr 16)
            return (h ushr 1)/1073741824.0-1
        }
        private fun noise(t:Double,hz:Double,salt:Int):Double {
            val phase=t*hz;val lower=floor(phase).toInt();val f=smooth(phase-lower)
            val a=hash(lower,salt);return a+(hash(lower+1,salt)-a)*f
        }
        fun at(time:Double,weather:DoubleArray):Double {
            val period=fluid.period*.28/(.55+motion*1.4)
            val swell=.5+.5*sin(2*PI*time/period-.55)
            val chop=abs(sin(2*PI*time*fluid.chopHz*(1+motion*3)))
            var turbulence=.0
            for(i in 0..3)turbulence+=abs(noise(time,(.8+motion*2)*(1 shl i),i))/(1 shl i)
            return (.18+.60*swell+weather[1]*chop*.16+weather[2]*turbulence*.22).coerceIn(.05,1.0)
        }
    }

    private class Oscillator(hz: Double) {
        private var x = 0.0; private var y = 1.0
        private var c = cos(2 * PI * hz / RATE); private var s = sin(2 * PI * hz / RATE)
        fun phase(radians: Double) { x = sin(radians); y = cos(radians) }
        fun tune(hz: Double) { c = cos(2 * PI * hz.coerceIn(20.0, RATE * .40) / RATE); s = sin(2 * PI * hz.coerceIn(20.0, RATE * .40) / RATE) }
        fun next(): Double { val a = x; x = c * x + s * y; y = c * y - s * a; return x }
    }

    /** Three moving, state-preserving TPT bands with control interpolation. */
    private class Formant {
        private var ic1 = 0.0; private var ic2 = 0.0
        private var g = .01; private var k = .5; private var dg = 0.0; private var dk = 0.0
        fun reset() { ic1 = 0.0; ic2 = 0.0 }
        fun tune(center: Double, width: Double, immediate: Boolean) {
            val nextG = tan(PI * center.coerceIn(30.0, RATE * .40) / RATE)
            val nextK = (width / center).coerceIn(.07, 2.0)
            if (immediate) { g = nextG; k = nextK; dg = 0.0; dk = 0.0 }
            else { dg = (nextG - g) / CONTROL; dk = (nextK - k) / CONTROL }
        }
        fun next(input: Double): Double {
            g += dg; k += dk
            val a1 = 1.0 / (1.0 + g * (g + k))
            val v1 = a1 * (ic1 + g * (input - ic2))
            val v2 = ic2 + g * v1
            ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2
            if (abs(ic1) < 1e-25) ic1 = 0.0
            if (abs(ic2) < 1e-25) ic2 = 0.0
            return k * v1
        }
    }

    private class Caller(val kind: Osprey.CreatureKind, private val shape: OspreyShore.Creature,
        seed: Int, private val sea: Double, private val motion: Double, private val depth: Double) {
        private val noise = Dsp.Noise(seed)
        private val sourceNoise = Dsp.Noise(Dsp.seedFor(seed, "SOURCE"))
        private val filters = Array(3) { Formant() }
        private val source = Oscillator(shape.start[0])
        private val harmonic = Oscillator(shape.start[0] * 2)
        private var started = -100.0
        val eventTime get() = started
        private var sourceStarted = false
        private var duration = .1
        private var strength = 0.0
        private var cooldown = 0.0
        private var accumulated = 0.0
        // Small first opportunity makes the three roles inspectable in an eight-second proof;
        // subsequent calls wait their shore-specific gaps and independent seeded thresholds.
        private var threshold = doubleArrayOf(.12, .055, .035)[kind.ordinal] * (1.0 + .30 * noise.next())
        fun control(time: Double, dt: Double, activity: Double, gate: Double, stop: Double): Double? {
            val age = time - started
            if (age < duration) {
                val trajectory = smooth(age / duration)
                for (i in 0..2) filters[i].tune(shape.start[i] + (shape.end[i] - shape.start[i]) * trajectory, shape.widths[i], false)
                val hz = shape.start[0] + (shape.end[0] - shape.start[0]) * trajectory
                source.tune(hz); harmonic.tune(hz * 2)
            }
            if (time < .35 || time >= stop - .04 || gate <= .001) return null
            // A cooldown can expire inside this control interval. Integrate only its eligible
            // fraction, then locate the threshold crossing inside it; rounding every expiry
            // and call to the control grid caused cumulative whole-cycle jumps under SHORE.
            val eligibleFrom = max(time, cooldown)
            val eligibleUntil = min(time + dt, stop - .04)
            if (eligibleUntil <= eligibleFrom) return null
            val opportunities = (.24 + sea * .85 + motion * .25) * 2000.0 / shape.gapMs.coerceAtLeast(700.0)
            val hazardRate = opportunities * activity * activity * gate
            val remaining = threshold - accumulated
            val increment = hazardRate * (eligibleUntil - eligibleFrom)
            if (increment < remaining) { accumulated += increment; return null }
            started = eligibleFrom + remaining / hazardRate
            accumulated = 0.0
            duration = (shape.sweepMs / 1000.0).coerceIn(.055, 3.4) / (.80 + motion * .65)
            duration = min(duration, max(.04, stop - started + .20))
            strength = gate * (.25 + .75 * activity)
            cooldown = started + duration + shape.gapMs / 1000.0 * (.30 + .25 * (1.0 - sea))
            threshold = .45 + .65 * (noise.next() + 1.0) * .5
            for (i in 0..2) { filters[i].reset(); filters[i].tune(shape.start[i], shape.widths[i], true) }
            source.tune(shape.start[0]); harmonic.tune(shape.start[0] * 2)
            sourceStarted = false
            return strength
        }
        fun next(time: Double): Double {
            // The independent noise stream follows world time even in gaps. A fractional
            // onset changes the envelope, not which random waveform starts underneath it.
            val n = sourceNoise.next().toDouble()
            val age = time - started
            if (age < 0 || age >= duration) return 0.0
            if (!sourceStarted) {
                // Anchor onset phase to the shared render clock. A tiny smooth event-time
                // shift must not reset a kilohertz carrier to an unrelated world-time phase.
                source.phase(2 * PI * shape.start[0] * time)
                harmonic.phase(4 * PI * shape.start[0] * time)
                sourceStarted = true
            }
            val rise = smooth(age / min(.035, duration * .20))
            val fall = smooth((duration - age) / min(.12, duration * .30))
            val tone = source.next() + .25 * harmonic.next()
            val input = when (kind) {
                Osprey.CreatureKind.BIRD -> tone * .75 + n * .18
                Osprey.CreatureKind.DEEP -> tone * .9 + n * .05
                Osprey.CreatureKind.AMBIENT -> tone * .25 + n * .70
            }
            var value = 0.0
            for (i in 0..2) value += filters[i].next(input) / (i + 1.0)
            return value * rise * fall * strength * depth * shape.amplitude * .22 * (.45 + .55 * sea)
        }
    }

    private class Harbor(channel: Int) {
        private val stages = Array(4) { i -> Allpass(intArrayOf(1499, 2203, 3067, 4001)[i] + channel * 137) }
        private var low = 0.0
        fun next(input: Double): Double {
            var value = input
            for (stage in stages) value = stage.next(value)
            low += .12 * (value - low)
            return low
        }
    }
    private class Allpass(length: Int) {
        private val buffer = DoubleArray(length)
        private var at = 0
        fun next(input: Double): Double {
            val delayed = buffer[at]
            val out = delayed - .55 * input
            buffer[at] = input + .55 * out
            at++; if (at == buffer.size) at = 0
            return out
        }
    }
}
