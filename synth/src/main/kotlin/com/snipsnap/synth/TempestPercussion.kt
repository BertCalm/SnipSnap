package com.snipsnap.synth

import kotlin.math.*

/**
 * Model-3 candidates: exciter -> positive-damped SVF -> bounded fold -> DC/highpass ->
 * TEMPEST's existing contractive memory. No new feedback route or normalization per burst.
 * These are digital drum gestures, not physical membranes or copied machine circuits.
 */
internal object TempestPercussion {
    fun macrosFor(voice: TempestVoice): List<MacroSpec> {
        val values = when (voice) {
            TempestVoice.SNARE -> floatArrayOf(.55f, .55f, .68f, .28f, .10f, .40f)
            TempestVoice.CLAP -> floatArrayOf(.60f, .45f, .65f, .22f, .18f, .45f)
            TempestVoice.TOM -> floatArrayOf(.45f, .25f, .65f, .25f, .12f, .48f)
            TempestVoice.HAT -> floatArrayOf(.50f, .45f, .70f, .18f, .04f, .18f)
            else -> error("$voice uses the approved source")
        }
        return listOf("TUNE", "SOURCE", "FILTER", "DRIVE", "SPACE", "DECAY").mapIndexed { i, k ->
            MacroSpec(k, values[i])
        } + MacroSpec("HOLD", 0f)
    }

    /** Bandwidth-aware wet-branch voltage, fixed once per render. No event normalization.
     * A noisy, wide-open snare needs less fold index than its tonal body; otherwise its
     * high-drive corner aliases before the output decimator can remove the new harmonics. */
    internal fun driveVoltageFor(voice: TempestVoice, source: Double, filter: Double): Double = when (voice) {
        TempestVoice.SNARE -> 2.6/(1+2.25*source*filter.pow(4))
        TempestVoice.CLAP -> 8.0/(1+.5*filter.pow(4))
        TempestVoice.HAT -> 2.2
        TempestVoice.TOM -> 2.6
        else -> error("$voice keeps its approved drive")
    }

    /** Fixed 44.1 kHz random clock, interpolated at the internal rate. A 4x/16x reference
     * therefore sees the same continuous excitation, rather than unrelated random draws. */
    private class ClockNoise(seed: Int, private val factor: Int) {
        private val noise = Dsp.Noise(seed)
        private var left = noise.next()
        private var right = noise.next()
        fun step(frame: Int): Float {
            val sub = frame % factor
            if (sub == 0 && frame != 0) { left = right; right = noise.next() }
            return left + (right - left) * (sub.toFloat() / factor)
        }
    }

    /** Constant-rate unit-circle rotation: no trigonometric calls in the hat's sample loop.
     * Double state bounds numerical drift over the finite 1.2-second render. */
    private class Cluster(base: Double, source: Double, rate: Int) {
        private val ratios = doubleArrayOf(1.0, 1.342, 1.681, 1.940, 2.318, 2.703)
        private val re = DoubleArray(6) { 1.0 }
        private val im = DoubleArray(6)
        private val cr = DoubleArray(6) { cos(2*PI*base*ratios[it].pow(.8 + .2*source)/rate) }
        private val ci = DoubleArray(6) { sin(2*PI*base*ratios[it].pow(.8 + .2*source)/rate) }
        fun step(): Double {
            var sum = 0.0
            for (j in re.indices) {
                sum += im[j]
                val x = re[j]*cr[j] - im[j]*ci[j]
                im[j] = im[j]*cr[j] + re[j]*ci[j]
                re[j] = x
            }
            return sum / 6
        }
    }

    private fun strike(t: Double, tau: Double, rise: Double = .0005): Double {
        if (t <= 0) return 0.0
        val a = min(1.0, t/rise)
        return a*a*(3-2*a)*exp(-t/tau)
    }

    internal fun synthesize(voice: TempestVoice, macros: Map<String, Float>, rate: Int,
                            preDriveCapture: FloatArray? = null): FloatArray {
        require(voice in Tempest.EXPANSION_VOICES)
        require(rate >= Dsp.RATE && rate % Dsp.RATE == 0)
        require(preDriveCapture == null || preDriveCapture.size == Tempest.OUTPUT_FRAMES*(rate/Dsp.RATE))
        val m = Tempest.settled(voice, macros)
        val tune = m.getValue("TUNE").toDouble()
        val source = m.getValue("SOURCE").toDouble()
        val decay = m.getValue("DECAY").toDouble()
        val filter = m.getValue("FILTER").toDouble()
        val amount = m.getValue("DRIVE").toDouble()
        val voltage = driveVoltageFor(voice, source, filter)
        val root = Tempest.frequencyFor(tune.toFloat()).toDouble()
        val noise = ClockNoise(Dsp.seedFor("TEMPEST", 3, voice.name, Tempest.midiFor(tune.toFloat())), rate/Dsp.RATE)
        val cluster = if (voice == TempestVoice.HAT) Cluster(1600.0*4.0.pow(tune), source, rate) else null
        val tau = when (voice) {
            TempestVoice.SNARE -> .020*16.0.pow(decay)
            TempestVoice.CLAP -> .025*9.0.pow(decay)
            TempestVoice.TOM -> .022*18.0.pow(decay)
            TempestVoice.HAT -> .007*45.0.pow(decay)
            else -> error("unreachable")
        }
        val cutoff = when (voice) {
            TempestVoice.SNARE -> 2*root*.7*32.0.pow(filter)
            TempestVoice.TOM -> root*.5*8.0.pow(filter)
            TempestVoice.CLAP -> 700.0*9.0.pow(filter)*2.0.pow(tune-.5)
            TempestVoice.HAT -> 1000.0*10.0.pow(filter)
            else -> error("unreachable")
        }
        val svf = Dsp.TptSvf(rate)
        // The wire band must survive independently of the low body filter; otherwise
        // increasing SOURCE mostly turns up noise that the body's lowpass immediately removes.
        val wiresFilter = if (voice == TempestVoice.SNARE) Dsp.TptSvf(rate) else null
        val wiresCutoff = 900.0*10.0.pow(filter)
        val noiseCutoff = when (voice) {
            TempestVoice.SNARE -> 900f
            TempestVoice.HAT -> 1800f
            else -> 900f
        }
        val noiseLow = Dsp.FixedOnePole(noiseCutoff, rate)
        val dc = Dsp.FixedOnePole(10f, rate)
        val hatLow = Dsp.FixedOnePole(1200f, rate)
        val memory = Tempest.Memory(voice, m.getValue("SPACE"), rate)
        val ampMultiplier = exp(-1.0/(rate*tau))
        val contourMultiplier = exp(-1.0/(rate*.018))
        var amp = 1.0
        var contour = 1.0
        val spacing = .008 + .022*source
        return FloatArray(Tempest.OUTPUT_FRAMES*(rate/Dsp.RATE)) { i ->
            val t = i.toDouble()/rate
            val white = noise.step(i)
            val bright = (white-noiseLow.lp(white))*.5 // bounded by 1, before amplification
            val attack = min(1.0, t/.0006).let { it*it*(3-2*it) }
            // Every excitation ends smoothly by .60 s. The already lossy memory then lands naturally.
            val release = ((t-.35)/.25).coerceIn(0.0, 1.0)
            val terminal = 1-release*release*(3-2*release)
            var wires = 0f
            val excited = when (voice) {
                TempestVoice.SNARE -> {
                    val phase = 2*PI*(2*root)*(t + .12*.009*(1-exp(-t/.009)))
                    val body = .72*sin(phase) + .28*sin(1.73*phase)
                    val bodyAmp = exp(-t/(.020*5.0.pow(decay)))
                    // A fixed wire pickup gain balances the broadband source against tonal body
                    // energy. SOURCE alters their balance before the shared drive and memory.
                    wires = (attack*(.12+3.0*source)*bright*amp*terminal).toFloat()
                    attack*(.75*(1-.85*source))*body*bodyAmp
                }
                TempestVoice.CLAP -> {
                    // SOURCE continuously spreads three short strikes; the fourth is the release.
                    // Summed fixed gains preserve their relative attack energy across renders.
                    val bursts = (0..2).sumOf { n -> .23*strike(t-n*spacing, .0035+.004*source) }
                    val tail = .48*strike(t-3*spacing, tau, .001)
                    val color = (.92-.12*source)*bright + (.08+.12*source)*sin(2*PI*root*17*t)
                    color*(bursts+tail)
                }
                TempestVoice.TOM -> {
                    // Analytic integration makes the pitch fall rate-independent for the reference probe.
                    val phase = 2*PI*root*(t + (.20+.35*source)*.024*(1-exp(-t/.024)))
                    val body = (1-.50*source)*sin(phase) + .32*source*sin((1.5+.1*source)*phase) +
                        .18*source*sin((2+.3*source)*phase + source*.6*contour*sin(3*phase))
                    attack*amp*((1-.08*source)*body + .08*source*bright)
                }
                TempestVoice.HAT -> attack*amp*((1-.8*source)*cluster!!.step() + .8*source*bright)
                else -> error("unreachable")
            }.toFloat()*terminal.toFloat()
            // One positive-damped filter: lowpass for body drums, normalized bandpass for noisy voices.
            val damping = if (voice == TempestVoice.TOM) .9f else 1.1f
            svf.process(excited, min(14000.0, cutoff*(1+.45*contour)).toFloat(), damping)
            var filtered = if (voice == TempestVoice.TOM || voice == TempestVoice.SNARE) svf.low else damping*svf.band
            if (wiresFilter != null) {
                wiresFilter.process(wires, min(14000.0,wiresCutoff*(1+.3*contour)).toFloat(),1.1f)
                filtered += 1.1f*wiresFilter.band
            }
            preDriveCapture?.set(i, filtered)
            // The clap's fixed burst gains are lower than a sustained oscillator's. Its higher
            // wet-branch voltage exposes folding in the middle of DRIVE, before final leveling.
            val driven = Tempest.drive(filtered, amount, voltage)
            var centered = driven-dc.lp(driven)
            if (voice == TempestVoice.HAT) centered -= hatLow.lp(centered)
            amp *= ampMultiplier
            contour *= contourMultiplier
            memory.step(centered)
        }
    }
}
