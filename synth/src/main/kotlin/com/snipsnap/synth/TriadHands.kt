package com.snipsnap.synth

import kotlin.math.*

/** Authored hand-drum revision, not a measured hardware or acoustic-drum recreation. */
internal object TriadHands {
    const val VERSION = 2
    fun render(bongo: Boolean, tune: Float, velocity: Float, take: Int): FloatArray {
        require(tune.isFinite() && tune in 0f..1f && velocity.isFinite() && velocity in 0f..1f)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val hz = (if(bongo) 330f else 185f) * 2f.pow((tune-.5f)*.8f)
        val duration = if(bongo) .48 else .8
        val frames = (duration*rate).toInt()
        val noise = Dsp.Noise(Dsp.seedFor("TRIAD-HANDS",VERSION,bongo,take))
        val contact = ((if(bongo) .0011 else .0020)*(1.35-.65*velocity)*rate).toInt().coerceAtLeast(8)
        val bright = Dsp.Biquad().apply { bandpass(if(bongo) 3600f else 2300f,.8f,rate) }
        val excitation = FloatArray(frames)
        val slap = FloatArray(frames)
        for(i in 0 until min(frames,(rate*.045).toInt())) {
            val t = i.toDouble()/rate
            val n = bright.process(noise.next())
            // A finite palm/fingertip contact drives the body; noisy skin contact
            // also radiates directly, with a softer onset than an impulse click.
            if(i<contact) excitation[i] = (.5-.5*cos(2*PI*i/(contact-1))).toFloat()
            excitation[i] += n * exp(-t/.003).toFloat() * (.08f+.18f*velocity)
            slap[i] = n * (1-exp(-t/.0003)).toFloat() * exp(-t/(if(bongo) .009 else .015)).toFloat()
        }
        // Ideal circular-membrane low-mode ratios, with an authored low body mode.
        // They deliberately depart from the nearly harmonic six-sine R1 body.
        val ratios = floatArrayOf(1f,1.593f,2.136f,2.296f,2.653f,2.918f,3.155f,3.5f,4.06f)
        val modes = ratios.mapIndexed { k,r ->
            val weight = (if(k==0) 1f else .8f/r.pow(.7f)) * (1 + velocity*.16f*k)
            val decay = (if(bongo) .32f else .56f) / (1+.22f*k) * (1.08f-.16f*velocity)
            Modes.Mode(r,weight,decay)
        }
        val head = Modes.ring(excitation,hz,modes,rate)
        Dsp.normalize(head,.76f) // Compensate Modes.ring's rate/pitch-dependent gain before mixing.
        val shell = Modes.ring(excitation,hz, listOf(Modes.Mode(.61f,1f,if(bongo) .10f else .19f)),rate)
        Dsp.normalize(shell,.13f)
        val raw = FloatArray(frames) { i ->
            val t=i.toDouble()/rate
            val finger=(if(bongo) .32f else .21f)*(.5f+velocity)*slap[i]
            // Mild contact-dependent saturation thickens the early body without a bus change.
            val drive=1.15f + .55f*velocity*exp(-t/.025).toFloat()
            tanh((head[i]+shell[i]+finger)*drive)/1.35f
        }
        Tide.bandLimit(raw,rate)
        val out=Dsp.decimate(raw,rate)
        val mean=out.average().toFloat();val dc=Dsp.FixedOnePole(20f)
        for(i in out.indices) {val x=out[i]-mean;out[i]=x-dc.lp(x)}
        Dsp.limitPeak(out,.95f)
        Dsp.fadeTail(out)
        return out
    }
}
