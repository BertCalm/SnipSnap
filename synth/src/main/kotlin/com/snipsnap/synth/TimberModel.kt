package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.TimberKits.Gesture
import com.snipsnap.synth.TimberKits.Instrument
import kotlin.math.*

/** Model 2 ports the owner-approved Python R2/R3 contact laws and absolute body profiles.
 * Shapes, not measured acoustic instruments. One-way excitation, no coupled feedback.
 * Double modal state is intentional: Float coefficients lose precision at bass frequencies
 * at 176.4 kHz. This local recurrence avoids changing the legacy Modes sound globally.
 */
internal object TimberModel {
    internal const val CONTACT_GUARD=256
    internal const val DIRECT_GUARD=2048
    private data class Mode(val hz: Double, val gain: Double, val t60: Double)
    private data class Profile(val modes: List<Mode>, val strings: List<Double>,
        val stiff: Double, val board: Double, val collision: Double)
    private fun m(f: Double,g: Double,t: Double)=Mode(f,g,t)
    private val profiles=mapOf(
        Instrument.GUITAR to Profile(listOf(m(98.0,1.0,.25),m(188.0,.68,.19),m(285.0,.42,.13),m(420.0,.28,.09),m(690.0,.2,.06)), listOf(82.41,110.0,146.83,196.0,246.94,329.63),.00012,1750.0,.38),
        Instrument.ACOUSTIC_BASS to Profile(listOf(m(68.0,1.0,.34),m(134.0,.62,.26),m(231.0,.39,.18),m(365.0,.3,.11),m(560.0,.17,.08)),listOf(41.2,55.0,73.42,98.0),.00042,1050.0,.52),
        Instrument.CELLO to Profile(listOf(m(106.0,.6,.22),m(173.0,1.0,.31),m(287.0,.72,.2),m(463.0,.36,.15),m(730.0,.23,.08),m(1120.0,.12,.05)),listOf(65.41,98.0,146.83,220.0),.00075,2300.0,.45),
        Instrument.UPRIGHT to Profile(listOf(m(57.0,.85,.42),m(94.0,1.0,.32),m(158.0,.58,.23),m(247.0,.34,.16),m(410.0,.21,.12),m(640.0,.12,.07)),listOf(41.2,55.0,73.42,98.0),.0014,740.0,.68))
    private val positions=doubleArrayOf(-.009,.004,.011,-.004)
    private val times=doubleArrayOf(-.0003,.00015,.0004,-.0001)
    private val rate=Dsp.RATE*Dsp.OVERSAMPLE
    private val frames=(.95*rate).roundToInt()

    private fun pulse(x: FloatArray,at: Double,width: Double,energy: Double) {
        val start=max(0,(at*rate).roundToInt()); val n=max(8,(width*rate).roundToInt())
        // Exact Hann area is (n-1)/2; preserve impulse across contact widths.
        for(i in 0 until min(n,x.size-start)) x[start+i]+=(sin(PI*i/(n-1)).pow(2)*energy*2/(n-1)).toFloat()
    }
    private fun bandLimitContact(x: FloatArray) {
        val end=x.indexOfLast { it!=0f }
        if(end<0) return
        val short=x.copyOf(min(x.size,end+1+CONTACT_GUARD))
        Tide.bandLimit(short,rate)
        short.copyInto(x)
    }
    private fun ring(ex: FloatArray,modes: List<Mode>,out: FloatArray,tune: Float,damping: Float, truncate: Boolean) {
        val lastContact=ex.indexOfLast { it!=0f }
        if(lastContact<0) return
        for(mode in modes) {
            val hz=mode.hz*tune
            if(hz<=0 || hz>=rate*.5) continue
            val r=exp(-ln(1000.0)/(mode.t60*damping*rate)); val theta=2*PI*hz/rate
            val a=2*r*cos(theta); val b= -r*r; val g=mode.gain*sin(theta)
            var y1=0.0; var y2=0.0
            // Four T60s after the final forcing sample is -240 dB in modal amplitude.
            // Avoid both wasted silent work and eventual Double subnormal recurrence state.
            val end=if(truncate) min(ex.size,lastContact+1+ceil(4*mode.t60*damping*rate).toInt()) else ex.size
            for(i in 0 until end) {
                val y=g*ex[i]+a*y1+b*y2; y2=y1; y1=y
                out[i]+=y.toFloat()
            }
        }
    }
    fun strike(hit: TimberKits.Hit,v: Float,take: Int,damping: Float=1f, truncate: Boolean=true): Snip {
        require(v.isFinite() && v in 0f..1f && take in 0..3 && hit.tune.isFinite() && hit.tune>0 && damping>0)
        val p=profiles.getValue(hit.instrument); val delta=positions[take]
        val extended=hit.gesture in listOf(Gesture.PALM,Gesture.DEAD_STRING,Gesture.HARMONIC,Gesture.SCRATCH)
        val noise=Dsp.Noise(Dsp.seedFor("TIMBER",TimberKits.VERSION,hit.instrument.name,hit.gesture.name,if(hit.gesture==Gesture.SCRATCH) 0 else take))
        val body=FloatArray(frames); val direct=FloatArray(frames); val out=FloatArray(frames)
        var modes=p.modes
        val stringExcitations=mutableListOf<Pair<FloatArray,List<Mode>>>()
        if(extended) {
            val ex=FloatArray(frames); val at=.004+times[take]*.35
            when(hit.gesture) {
                Gesture.PALM -> {
                    pulse(body,at,.005*(1.3-.55*v)*(1+delta*5),1.8)
                    modes=modes.map { it.copy(gain=it.gain/(1+(it.hz/210).pow(2)),t60=it.t60*.5) }
                    pulse(ex,at,.004,.008)
                }
                Gesture.DEAD_STRING -> {
                    pulse(body,at,.001*(1.2-.6*v),.32); pulse(ex,at,.0005*(1.2-.6*v),.5)
                    for(j in p.strings.indices) {
                        val t=at+.0015+j*.0003+delta*.001
                        pulse(body,t,.0002,p.collision*v*.18); pulse(ex,t,.00015,p.collision*v*.12)
                    }
                    for(i in 0 until (.012*rate).toInt()) direct[(at*rate).roundToInt()+i]=(noise.next()*exp(-i/(rate*.0025))*.004*v).toFloat()
                    modes=modes.map { it.copy(t60=it.t60*.65) }
                }
                Gesture.HARMONIC -> {
                    pulse(body,at,.00045*(1.4-.8*v)*(1+delta*6),.7); pulse(ex,at,.00025,.08)
                    modes=modes.map { it.copy(gain=it.gain*(.35+it.hz/300),t60=it.t60*.6) }+listOf(m(p.board,.14,.025),m(p.board*1.63,.08,.016))
                }
                Gesture.SCRATCH -> {
                    val duration=.19-.045*v; var t=at; var ridge=0
                    while(t<at+duration) {
                        val progress=(t-at)/duration
                        val energy=.045*sin(PI*progress).pow(.6)*(1+TimberScrapeTexture.variation(hit.instrument,ridge++))
                        pulse(ex,t,.00012,energy); pulse(body,t,.00022,energy*.75); pulse(direct,t,.00009,energy*.15)
                        t+=(.0028-.0012*progress)*(1+TimberScrapeTexture.variation(hit.instrument,ridge++))*(1+delta*.3)
                    }
                    modes=modes.map { it.copy(t60=it.t60*.7) }
                }
                else -> error("extended gesture")
            }
            val decay=when(hit.gesture) { Gesture.PALM->.035; Gesture.DEAD_STRING->.025; Gesture.HARMONIC->.075; else->.042 }
            val partials=p.strings.flatMap { hz -> (1..12).map { k ->
                m(hz*k*sqrt(1+p.stiff*k*k),sin(PI*(.19+delta)*k)/(k.toDouble().pow(.9)*p.strings.size),decay/(1+.12*k))
            } }
            stringExcitations+=ex to partials
        } else {
            val contact=.0022*(1.3-.65*v)*(1+delta*8)
            if(hit.gesture==Gesture.KNUCKLE) pulse(body,.003+times[take],contact,1.0)
            else pulse(body,.003,.0012*(1.3-.65*v),.23)
            for((j,hz) in p.strings.withIndex()) {
                val ex=FloatArray(frames)
                if(hit.gesture==Gesture.KNUCKLE) pulse(ex,.003+.0007*j,contact,.014)
                else {
                    val at=.004+j*(if(hit.gesture==Gesture.SLAP) .0008 else .012)+times[take]*(j+1)*.08
                    pulse(ex,at,.00065*(1.3-.7*v),.20/sqrt(p.strings.size.toDouble()))
                    pulse(body,at,.0009,.14/p.strings.size)
                    val impact=max(0.0,v-.22).pow(1.25)*p.collision/sqrt(p.strings.size.toDouble())
                    val delay=.0026*(1.15-.45*v)+.0003*j
                    pulse(ex,at+delay,.00022,impact*.7); pulse(body,at+delay,.00028,impact)
                    val n=(.002*rate).roundToInt(); val start=((at+delay)*rate).roundToInt()
                    for(i in 0 until n) direct[start+i]+=(noise.next()*sin(PI*i/(n-1)).pow(2)*impact*.025).toFloat()
                }
                val decay=when(hit.gesture) { Gesture.KNUCKLE->.09; Gesture.SLAP->.13; else->.25 }
                stringExcitations+=ex to (1..8).map { k -> m(hz*k*sqrt(1+p.stiff*k*k),sin(PI*(.23+delta)*k)/k.toDouble().pow(1.25),decay/(1+.18*k)) }
            }
            if(hit.gesture==Gesture.RAKE) for(j in 0..18) pulse(body,.004+j*.004+noise.next()*.0005,.00025,.012*(.7+.3*v)*(1-.3*j/19))
        }
        bandLimitContact(body)
        ring(body,modes.mapIndexed { i,m -> m.copy(gain=m.gain*(1+delta*(i-2)*(if(extended) 1 else 2))) },out,hit.tune,damping,truncate)
        for((ex,partials) in stringExcitations) { bandLimitContact(ex); ring(ex,partials,out,hit.tune,damping,truncate) }
        // A small direct contact path accompanies the energy already driving the body.
        // House filters/noise differ from scipy; final Kotlin listening is a separate gate.
        val low=Dsp.FixedOnePole((if(extended) min(11000.0,p.board*4) else p.board).toFloat(),rate)
        val high=Dsp.FixedOnePole(900f,rate)
        // Stop the sparse direct path after its negligible filter tail. Otherwise
        // Float filter state stalls at subnormal values through most of the .95 s buffer.
        val lastDirect=direct.indexOfLast { it!=0f }
        val directEnd=if(lastDirect<0) 0 else min(frames,lastDirect+1+DIRECT_GUARD)
        for(i in 0 until directEnd) {
            val click=low.lp(direct[i]); val filtered=if(extended) click-high.lp(click) else click
            out[i]+=filtered
        }
        for(i in out.indices) out[i]*=v*.72f
        Tide.bandLimit(out,rate)
        val pcm=Dsp.decimate(out,Dsp.RATE)
        val mean=pcm.average().toFloat(); val dc=Dsp.FixedOnePole(22f,Dsp.RATE)
        for(i in pcm.indices) { val x=pcm[i]-mean; pcm[i]=(x-dc.lp(x))*OUTPUT_GAIN }
        Dsp.fadeTail(pcm)
        check(pcm.all { it.isFinite() && abs(it)<1f }) { "TIMBER model 2 exceeded fixed budget: $hit" }
        return Snip(pcm,1,Dsp.RATE)
    }
    // One fixed bank gain, never a limiter or normalization of individual takes.
    private const val OUTPUT_GAIN=.60f
}
