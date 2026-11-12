package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** Sample-free two-voice listening prototype, not a registered/shipping engine.
 * Two five-mode heads and three shell modes exchange energy through Modes.Bank springs.
 * Sixteen mass-normalized wire modes collide with the bottom head using passive impulses.
 * Frequencies/participation/contact gaps are voicing hypotheses, not measurements of a drum.
 */
object SporeMembrane {
    const val MODEL_VERSION=1
    enum class Voice { KICK, SNARE }
    data class Controls(val tune:Float=.5f,val damp:Float=.45f,val strike:Float=.4f,val body:Float=.55f,val wires:Float=.6f) {
        init { require(listOf(tune,damp,strike,body,wires).all { it.isFinite() && it in 0f..1f }) }
    }
    data class Trace(val attackEnergy:Double,val maximumFreeEnergy:Double,val finalEnergy:Double,
        val bottomPeak:Double,val contacts:Int,val maximumContactEnergyAdded:Double,val rawPeak:Float)
    data class Probe(val sound:Snip,val trace:Trace)
    fun render(voice:Voice,c:Controls=Controls(),velocity:Float=1f,take:Int=0):Snip=probe(voice,c,velocity,take).sound

    fun probe(voice:Voice,c:Controls=Controls(),velocity:Float=1f,take:Int=0,
        coupling:Boolean=true,wireContact:Boolean=true):Probe {
        require(velocity.isFinite() && velocity in 0f..1f && take>=0)
        val snare=voice==Voice.SNARE
        val rate=Dsp.RATE*Dsp.OVERSAMPLE
        val size=if(snare)29 else 13
        val bank=Modes.Bank(size,rate)
        val ratios=Modes.tableFor(Modes.Material.MEMBRANE).map { it.ratio.toDouble() }
        val root=if(snare)Dsp.expMap(c.tune,200f,320f).toDouble() else Dsp.expMap(c.tune,48f,85f).toDouble()
        val decay=if(snare) .55-.36*c.damp else .8-.57*c.damp
        for(i in 0..4) {
            bank.tune(i,root*ratios[i],decay/(1+.18*i))
            bank.tune(i+5,root*ratios[i]*(1.16+.34*c.body),decay*.72/(1+.16*i))
        }
        val shell=if(snare)doubleArrayOf(510.0,1270.0,2890.0) else doubleArrayOf(230.0,810.0,2310.0)
        for(i in 0..2)bank.tune(10+i,shell[i],(.12+.12*(1-c.damp))/(i+1))
        if(coupling) {
            for(i in 0..4)bank.connect(i,i+5,.08+.34*c.body)
            for(i in 0..2)bank.connect(i,10+i,.025+.075*c.body)
        }
        if(snare)for(i in 0..15)bank.tune(13+i,490+(i+.8).pow(1.27)*220*(.85+.30*c.wires),.045+.14*c.wires/(1+.035*i))
        val random=Dsp.Noise(Dsp.seedFor("SPORE-MEMBRANE",MODEL_VERSION,voice.name,take))
        val position=(.12+.65*c.strike+.025*random.next()).coerceIn(.06,.85)
        val strike=DoubleArray(size)
        for(i in 0..4) {
            // Reduced modal participation: off-centre strikes light up different head modes.
            strike[i]=cos((i+.4)*PI*position)/(1+.10*i)*(if(i==0)1.0 else .3+.7*velocity)
        }
        for(i in 0..2)strike[10+i]=(.05+.2*c.strike)*velocity/(i+1)
        val norm=sqrt(strike.sumOf { it*it });for(i in strike.indices)strike[i]/=norm
        val input=bank.participation(strike)
        val pickup=DoubleArray(size)
        for(i in 0..4) { pickup[i]=(if(snare).48 else .9)/(1+i);pickup[i+5]=.30/(1+i) }
        for(i in 0..2)pickup[10+i]=.13/(i+1)
        val pickupNorm=sqrt(pickup.sumOf { it*it });for(i in pickup.indices)pickup[i]/=pickupNorm
        val output=bank.participation(pickup)
        val wireLow=Dsp.FixedOnePole(900f,rate)
        val impactLow=Dsp.FixedOnePole(1200f,rate)
        val impactHigh=Dsp.FixedOnePole(10500f,rate)
        val head=doubleArrayOf(.67,.38,-.26,.21,-.16)
        val headNorm=head.sumOf { it*it }
        val inverseWireMassSqrt=3.6
        val contactNorm=headNorm+inverseWireMassSqrt*inverseWireMassSqrt
        val contacts=if(snare)Array(16) { w -> bank.participation(DoubleArray(size) { i ->
            when { i in 5..9 -> head[i-5];i==13+w -> -inverseWireMassSqrt;else -> 0.0 }
        }) } else emptyArray()
        val gaps=DoubleArray(16) { i -> (1.5e-6+1.1e-5*(1-c.wires))*(.7+.6*((i*7)%17)/16) }
        val milliseconds=(if(snare).38 else 2.0)*(1.3-.5*velocity)*(.92+.08*random.next())*(1.15-.5*c.strike)
        val contactFrames=(milliseconds*rate/1000).roundToInt().coerceAtLeast(8)
        val impulse=velocity.toDouble().pow(1.15)*(1+.02*random.next())
        val raw=FloatArray((1.2*rate).toInt())
        var attackEnergy=0.0;var maximumFree=0.0;var bottomPeak=0.0;var hits=0;var contactAdded=0.0
        for(frame in raw.indices) {
            if(frame<contactFrames) {
                val window=sin(PI*frame/contactFrames).pow(2)
                bank.drive(input,2*impulse*rate/contactFrames*window*(1+.10*random.next()))
            }
            bank.step()
            var headQ=0.0;var headV=0.0
            for(i in 0..4) { headQ+=head[i]*bank.displacement(i+5);headV+=head[i]*bank.velocity(i+5) }
            bottomPeak=max(bottomPeak,abs(headV))
            var impact=0.0
            if(snare && wireContact)for(w in 0..15) {
                val penetration=headQ-inverseWireMassSqrt*bank.displacement(13+w)-gaps[w]
                val relative=headV-inverseWireMassSqrt*bank.velocity(13+w)
                if(penetration>0 && relative>0) {
                    // J changes relative velocity to -e*u; ΔE = -.5*(1-e²)*u² / ||b||² <= 0.
                    // No position projection or free-running noise source injects contact energy.
                    val restitution=.12+.26*c.wires
                    val j=-(1+restitution)*relative/contactNorm
                    val before=bank.energy()
                    bank.drive(contacts[w],j*rate)
                    contactAdded=max(contactAdded,bank.energy()-before)
                    headV+=j*headNorm
                    // Contact force is another radiation pickup, not an extra excitation/noise layer.
                    impact+=j*rate*(if(w%3==0)-.6 else 1.0)
                    hits++
                }
            }
            if(snare) {
                var wire=0.0
                for(w in 0..15)wire+=bank.velocity(13+w)*(if(w%3==0)-.25 else .25)
                val wireHigh=wire.toFloat()-wireLow.lp(wire.toFloat())
                val pressure=(impact*.00022).toFloat()
                val chatter=impactHigh.lp(pressure-impactLow.lp(pressure))
                raw[frame]=(.38*bank.velocityAlong(output)+1.8*wireHigh+chatter).toFloat()
            } else raw[frame]=(.85*bank.velocityAlong(output)).toFloat()
            if(frame==contactFrames)attackEnergy=bank.energy()
            if(frame>=contactFrames && frame%64==0)maximumFree=max(maximumFree,bank.energy())
        }
        val rawPeak=raw.maxOf { abs(it) }
        check(rawPeak.isFinite() && rawPeak<1.05f) { "Unconditioned membrane peak $rawPeak" }
        Tide.bandLimit(raw,rate)
        val samples=Dsp.decimate(raw,rate)
        val mean=samples.average().toFloat();val dc=Dsp.FixedOnePole(20f)
        for(i in samples.indices) { val x=samples[i]-mean;samples[i]=x-dc.lp(x) }
        // Fixed radiation gain preserves strike dynamics. Audition matching is outside the model.
        Dsp.fadeTail(samples)
        return Probe(Snip(samples,1,Dsp.RATE),Trace(attackEnergy,maximumFree,bank.energy(),bottomPeak,hits,contactAdded,rawPeak))
    }
}
