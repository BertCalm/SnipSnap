package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
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
    val articulationDisabled: Boolean = false,
    val contactMemoryDisabled: Boolean = false,
    val bodyDisabled: Boolean = false,
    val quartetDisabled: Boolean = false,
)

/** Control traces include every 5 ms and the final frame, before loudness matching. Offsets are cents. */
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
    val contact: Double = 0.0,
    val oscillatorBGain: Double = 1.0,
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
 * HOLD's approximation is explicit: gestures replay a seeded, irregular periodic schedule,
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
        val phaseTravel: Double = 1.0,
    )

    private fun shape(v: SkitterVoice): Shape = when (v) {
        SkitterVoice.BASS -> Shape(floatArrayOf(.40f,.20f,.30f,.10f,.05f), 1.30,.45,.012, 3.4,1.15,9.0,.028,.13,.14,.065)
        SkitterVoice.LEAD -> Shape(floatArrayOf(.70f,.45f,.45f,.25f,.10f), 1.85,.48,.009, 9.0,.80,17.0,.022,.11,.18,.085)
        SkitterVoice.HOLLOW -> Shape(floatArrayOf(.35f,.30f,.25f,.50f,.10f), 2.0,.62,.018, 3.2,.70,9.0,.044,.16,.22,.09,2.0)
        SkitterVoice.FLUTTER -> Shape(floatArrayOf(.55f,.55f,.70f,.40f,.20f), 2.05,.68,.013, 6.4,.74,22.0,.020,.13,.24,.09)
        SkitterVoice.CHIRP -> Shape(floatArrayOf(.50f,.25f,.65f,.65f,.35f), 1.45,.56,.012, 2.6,.52,28.0,.030,.14,.16,.11)
        // Dense edges already provoke FRAY strongly; keep its irregular pitch gestures compact.
        SkitterVoice.FRAY -> Shape(floatArrayOf(.85f,.65f,.85f,.45f,.60f), 2.10,.70,.016, 7.4,.82,33.0,.019,.10,.30,.10,phaseTravel=.25)
    }

    /** The waveform families differ before the filter, including when all reaction controls are off. */
    private data class Wave(val edgeLow: Double, val edgeHigh: Double, val pulse: Double,
                            val dutyLow: Double = .5, val dutyHigh: Double = .16, val sine: Double = 0.0,
                            val third: Double = 0.0)
    private data class Source(val a: Wave, val b: Wave, val bMix: Double, val rootSupport: Double)
    private data class Color(val drive: Double, val buzzDrive: Double, val memoryDrive: Double,
                             val bias: Double, val signedBias: Double, val dampingFloor: Double)
    private data class Reaction(val motion: Double, val memoryMotion: Double, val zeta: Double,
                                val zetaDepth: Double, val energy: Double, val gesture: Double, val noise: Double)
    private data class Character(val source: Source, val color: Color, val reaction: Reaction)

    /**
     * Circuit voicings, not output effects. Rounded bass, saw/pulse bite, odd-harmonic hollow,
     * complementary pulses, a clean resonant carrier, and an asymmetrically driven pair share
     * one coherent filter. The same six controls retain their scope within each family.
     */
    private fun character(voice: SkitterVoice): Character = when(voice) {
        SkitterVoice.BASS -> Character(
            Source(Wave(.05,.70,.08,sine=.15),Wave(.15,.80,.15,dutyHigh=.30),.18,.035),
            Color(1.0,.65,2.2,0.0,.50,.90), Reaction(4.0,1.8,.95,.12,1.6,.55,.60))
        SkitterVoice.LEAD -> Character(
            Source(Wave(.40,1.0,.10),Wave(.25,1.0,.90,dutyLow=.42,dutyHigh=.14),.38,.030),
            Color(1.1,1.7,5.5,.035,3.0,.58), Reaction(3.6,2.2,.88,.20,1.4,.95,.70))
        SkitterVoice.HOLLOW -> Character(
            Source(Wave(.15,1.0,1.0,dutyHigh=.48),Wave(.20,.90,.60,dutyHigh=.35),.21,.055),
            Color(.95,.90,2.8,0.0,1.0,.62), Reaction(3.6,1.8,.94,.15,1.1,.65,.55))
        SkitterVoice.FLUTTER -> Character(
            Source(Wave(.25,1.0,1.0,dutyHigh=.44),Wave(.35,1.0,1.0,dutyLow=.30,dutyHigh=.12),.32,.025),
            Color(1.0,1.2,3.0,0.0,1.3,.55), Reaction(5.0,2.4,.80,.25,1.35,1.05,.70))
        SkitterVoice.CHIRP -> Character(
            Source(Wave(.025,.45,.10,sine=.65,third=.35),Wave(.30,1.0,.80,dutyLow=.46,dutyHigh=.18),.16,.045),
            Color(.95,.70,1.8,0.0,.60,.48), Reaction(5.5,1.5,.86,.24,2.0,.75,1.0))
        SkitterVoice.FRAY -> Character(
            Source(Wave(.65,1.0,.65,dutyLow=.22,dutyHigh=.12),Wave(.60,1.0,.95,dutyLow=.16,dutyHigh=.12),.42,.030),
            Color(1.25,1.9,6.0,.13,4.0,.62), Reaction(3.5,2.4,.86,.24,1.3,.85,1.25))
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

    private fun oscillator(phase: Double, increment: Double, buzz: Double, wave: Wave): Double {
        val saw = 2.0*phase - 1.0 - blep(phase,increment)
        val duty = (wave.dutyLow+(wave.dutyHigh-wave.dutyLow)*buzz).coerceIn(.12,.50)
        val shifted = (phase-duty+1.0)%1.0
        val pulse = (if (phase<duty) 1.0 else -1.0) + blep(phase,increment) - blep(shifted,increment) - (2.0*duty-1.0)
        val edge = wave.edgeLow+(wave.edgeHigh-wave.edgeLow)*buzz
        val p=2.0*PI*phase
        val fundamental=sin(p)
        val third=sin(3.0*p)
        // A four-partial triangle is intentionally rounded, with no discontinuity or aliased corner.
        val triangle=8.0/(PI*PI)*(fundamental-third/9.0+sin(5.0*p)/25.0-sin(7.0*p)/49.0)
        // CHIRP excites the shared filter near its third-harmonic resonance; this remains
        // part of the rooted oscillator, with no extra note, recursive state, or output effect.
        val rounded=triangle+(fundamental-triangle)*wave.sine+wave.third*third
        // Match fundamental polarity across the morph; opposing fundamentals would leave a
        // misleadingly thin/noisy middle while the shared leveler concealed the cancellation.
        return -(1.0-edge)*rounded + edge*((1.0-wave.pulse)*saw - wave.pulse*pulse*.75)
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

    /**
     * Touch -> contact -> body. A keeps its existing contribution; only B acquires a positive
     * prefilter articulation. LEAD contacts quickly, while HOLLOW's octave answers later.
     * Two stable positive states avoid a gain discontinuity at a new gesture. Recent load
     * weakens the next contact, so a breathing gap can restore it. This is source excitation,
     * not modulation of the completed signal, and it introduces no new random stream.
     */
    private class ContactEnvelope(voice: SkitterVoice, rate: Int, val velocity: Double,
                                  val probe: SkitterProbe) {
        private val rise=if(voice==SkitterVoice.LEAD) .002 else .030
        private val recovery=if(voice==SkitterVoice.LEAD) .030 else .070
        private val depth=if(voice==SkitterVoice.LEAD) .45 else .90
        private val riseDecay=exp(-1.0/(rate*rise))
        private val recoveryDecay=exp(-1.0/(rate*recovery))
        private val transfer=recovery/(recovery-rise)*(recoveryDecay-riseDecay)
        private val peakTime=ln(recovery/rise)/(1.0/rise-1.0/recovery)
        private val kernelPeak=recovery/(recovery-rise)*(exp(-peakTime/recovery)-exp(-peakTime/rise))
        var excitation=0.0; private set
        var contact=0.0; private set
        // Smooth compression bounds overlapping replies. An isolated hard contact reaches
        // 1+depth; even the state-space maximum keeps B quieter than A in these two voices.
        val gain: Double get()=1.0+2.0*depth*contact/(kernelPeak+contact)

        fun strike(strength: Double, history: Double, agitate: Double) {
            val load=if(probe.contactMemoryDisabled) 0.0 else agitate*tanh(18.0*history)
            val impulse=(velocity*abs(strength)/(1.0+1.5*load)).coerceIn(0.0,1.0)
            // Convex injection keeps excitation in [0,1] without clipping either audio path.
            excitation+=impulse*(1.0-excitation)
        }

        fun step() {
            // Exact discretization of a decaying excitation feeding an attack follower.
            contact=riseDecay*contact+transfer*excitation
            excitation*=recoveryDecay
        }
    }

    /**
     * Persistent source bodies. LEAD couples the oscillator phases into a finite harmonic
     * packet, with A retaining the root while B strains the upper formant during recovery.
     * HOLLOW phase-modulates a sine with the octave oscillator. A quadrature modulator and
     * explicit carrier preserve the root while exposing strong odd upper partials.
     * Contact and delayed circuit history move these bodies; neither returns to the old
     * linear blend after its onset. There are no oscillator resets or new recursive states.
     */
    private fun source(voice: SkitterVoice, phaseA: Double, phaseB: Double,
                       incrementA: Double, incrementB: Double, buzz: Double, c: Character,
                       circuit: Circuit, contact: ContactEnvelope?, body: Boolean,
                       quartet: Boolean, velocity: Double): Double {
        if(quartet) return quartetSource(voice,phaseA,phaseB,buzz,circuit,velocity)
        if(!body) {
            // Preserve the R4/R5 expression and oscillator evaluations exactly for provenance.
            val oa=oscillator(phaseA,incrementA,buzz,c.source.a)
            val ob=oscillator(phaseB,incrementB,buzz,c.source.b)
            return if(contact==null) (1.0-c.source.bMix)*oa+c.source.bMix*ob
                else (1.0-c.source.bMix)*oa+c.source.bMix*contact.gain*ob
        }
        val a=2.0*PI*phaseA; val b=2.0*PI*phaseB
        val sinA=sin(a)
        val touch=((contact?.gain ?: 1.0)-1.0)/(if(voice==SkitterVoice.LEAD) .45 else .90)
        // Both controls read the previous circuit state, never their own unsolved output.
        val positionWeight=if(voice==SkitterVoice.LEAD) 1.2 else .70
        val response=circuit.agitate*(positionWeight*circuit.position/(.15+abs(circuit.position))+
            .45*circuit.history/(.12+circuit.history))
        if(voice==SkitterVoice.LEAD) {
            val center=(2.3+4.4*buzz+.60*touch+response).coerceIn(2.1,6.4)
            // Agitation also opens/closes the packet's spectral aperture, so provocation
            // remains audible when the formant center reaches its bounded upper range.
            val width=(.65+.25*buzz)*(1.0+.60*circuit.agitate*circuit.position/(.15+abs(circuit.position)))
            val sinB=sin(b); val cosB=cos(b)
            var partialSin=sinA; var partialCos=cos(a)
            var signal=1.6*sinA; var power=1.6*1.6
            // Recurrence generates A+(n-1)B without a transcendental call for each partial.
            // At rest these are harmonics 2..7; even MIDI84 stays below 7.4kHz here.
            for(n in 2..7) {
                val nextSin=partialSin*cosB+partialCos*sinB
                partialCos=partialCos*cosB-partialSin*sinB
                partialSin=nextSin
                val d=(n-center)/width; val d2=d*d
                val weight=.06/n+.95/(1.0+d2*d2)
                signal+=weight*partialSin; power+=weight*weight
            }
            return -signal/sqrt(power)
        }
        val index=(1.85+.65*buzz+.28*touch+.20*response).coerceIn(1.6,2.6)
        // Smooth sine PM has no reset discontinuity. At this bounded index its meaningful
        // octave sidebands remain far below the internal Nyquist at the highest root.
        return -.55*sinA-.80*sin(a+index*cos(b))
    }

    /**
     * Four source operators, not four settings of the same oscillator morph. Their nonzero
     * bodies persist after the initial touch. Velocity changes source geometry throughout
     * the note; previous circuit energy/agitation then loads and releases that geometry.
     * All are algebraic functions of the existing periodic phases and delayed circuit state.
     */
    private fun quartetSource(voice: SkitterVoice, phaseA: Double, phaseB: Double,
                              buzz: Double, circuit: Circuit, velocity: Double): Double {
        val a=2.0*PI*phaseA; val b=2.0*PI*phaseB
        val sinA=sin(a)
        val motion=circuit.agitate*circuit.position/(.15+abs(circuit.position))
        val memory=circuit.agitate*circuit.history/(.12+circuit.history)
        return when(voice) {
            SkitterVoice.BASS -> {
                // A centered quadratic supplies charge asymmetry; the centered cubic
                // controls the third harmonic. B moves charge without a near-DC beat product.
                val square=sinA*sinA-.5
                val cubic=sinA*(sinA*sinA-.75)
                val charge=((.85+1.25*buzz)*(.55+.45*velocity)-.25*memory).coerceIn(.35,2.5)
                val balance=.45+.35*buzz
                // Pressure is displacement from the learned energy-only equilibrium,
                // so small jitters can open and close charge around rest without first
                // overcoming a steady position offset or a clipped charge coefficient.
                val rest=.85*tanh(.30*circuit.agitate*circuit.history)
                val pressure=circuit.agitate*(circuit.position-rest)
                val aperture=1.0+.90*pressure/(.05+abs(pressure))
                // Provocation also shifts the push-pull third harmonic independently of
                // the positive charge aperture; this is source shape, not a final gain.
                val compression=.70+.50*buzz+.25*memory+2.0*motion
                val signal=1.6*sinA+aperture*(charge+balance*sin(b))*square+compression*cubic
                -signal/sqrt(1.6*1.6+aperture*aperture*(.25*charge*charge+.0625*balance*balance)+.0625*compression*compression)
            }
            SkitterVoice.FLUTTER -> {
                // A positive audio-rate aperture gives amplitude-product sidebands. Using
                // 2B/3B avoids the near-zero difference tone of direct unison ring mixing.
                val aperture=((.20+1.10*buzz)*(.15+.85*velocity)+.25*motion+.10*memory).coerceIn(.15,1.35)
                val gate=1.0+aperture*(.55*cos(2.0*b)+.45*cos(3.0*b))
                -sinA*(.45+gate)/sqrt(1.45*1.45+.25*aperture*aperture*(.55*.55+.45*.45))
            }
            SkitterVoice.CHIRP -> {
                // A chirplet lives inside EVERY root cycle. The fourth-power window has
                // three zero derivatives at the wrap, avoiding a hard-sync discontinuity.
                val center=(2.2+1.4*buzz+.25*velocity+.55*motion+.20*memory).coerceIn(1.8,5.2)
                val sweep=((.65+buzz)*(.65+.35*velocity)+.40*motion).coerceIn(.25,2.2)
                val window=sin(PI*phaseA).let { val squared=it*it; squared*squared }
                val packetPhase=2.0*PI*((center-.5*sweep)*phaseA+.5*sweep*phaseA*phaseA+phaseB-phaseA)
                val level=(1.15+.70*buzz)*(.65+.35*velocity)
                -(1.6*sinA+level*window*sin(packetPhase))/sqrt(1.6*1.6+.2734375*level*level)
            }
            SkitterVoice.FRAY -> {
                // Nonmonotonic folding changes the beating pair's body, rather than
                // saturating a completed signal. A remains explicit when fold nulls move.
                val fold=((2.2+3.0*buzz)*(.70+.30*velocity)+.65*motion+.25*memory).coerceIn(1.5,5.2)
                val imbalance=circuit.agitate*circuit.signed/.055
                val bias=(.12+.08*buzz+.15*motion+.12*imbalance).coerceIn(-.20,.32)
                val pair=.72*sinA+.28*sin(b)
                // Center the biased fold's carrier-cycle mean, rather than its value at
                // zero input. The beating pair's phase-conditioned amplitude is R, and
                // mean(sin(F*(R*sin(t)+bias))) = sin(F*bias)*J0(F*R).
                val radiusSquared=.5968+.4032*cos(a-b)
                val grain=sin(fold*(pair+bias))-sin(fold*bias)*foldJ0(fold*fold*radiusSquared)
                // Open the folded branch around the circuit's energy-only resting force.
                // This retains grain movement when the fold index reaches its ceiling,
                // while the protected carrier and steady grain loading remain anchored.
                val rest=.85*tanh(.30*circuit.agitate*circuit.history)
                val shake=circuit.agitate*(circuit.position-rest)
                val aperture=1.0+.90*shake/(.10+abs(shake))
                // Keep the calibration fixed: normalizing this touch gain away would let
                // a deeper fold's moving fundamental null make a harder note lose energy.
                val carrier=1.0+2.2*velocity
                -(carrier*sinA+1.05*aperture*grain)/sqrt(1.6*1.6+1.05*1.05)
            }
            else -> error("accepted R6 voices must bypass the quartet source")
        }
    }

    /** J0(x), expressed in x²; x is bounded by 5.2 here. Taylor remainder < 9e-7. */
    private fun foldJ0(xSquared: Double): Double {
        val z=.25*xSquared
        var p=1.0/13168189440000.0
        p=-1.0/131681894400.0+z*p
        p=1.0/1625702400.0+z*p
        p=-1.0/25401600.0+z*p
        p=1.0/518400.0+z*p
        p=-1.0/14400.0+z*p
        p=1.0/576.0+z*p
        p=-1.0/36.0+z*p
        p=.25+z*p
        p=-1.0+z*p
        return 1.0+z*p
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
                max(abs(a),abs(b))>PITCH_OFFSET_LIMIT_CENTS ||
                (e.contact?.excitation ?: 0.0) !in 0.0..1.000000001 ||
                (e.contact?.contact ?: 0.0) !in 0.0..1.000000001) boundHits++
            check(e.ic1.isFinite() && e.ic2.isFinite() && e.position.isFinite() && e.history.isFinite() &&
                (e.contact?.contact ?: 0.0).isFinite()) {
                "SKITTER internal state became nonfinite"
            }
        }
    }

    /** All recursive state, including coefficient smoothing and the noise/DC detectors. */
    private class Circuit(val s: Shape, val c: Character, val m: Map<String,Float>, val rate: Int,
                          val probe: SkitterProbe, val held: Boolean, val contact: ContactEnvelope?) {
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
        private val zeta=(c.reaction.zeta-c.reaction.zetaDepth*agitate).coerceIn(.55,.95)
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
            noiseLow,noiseSlow,noiseEnergy,noiseVariance,noiseAudio1,noiseAudio2,dc,oldLogCutoff,a1,a2,a3,damping,
            contact?.excitation ?: 0.0,contact?.contact ?: 0.0)

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
            val logTarget=ln(baseCutoff)+agitate*(c.reaction.motion*position+c.reaction.memoryMotion*history)
            if(sample==0 && oldLogCutoff==0.0) oldLogCutoff=logTarget
            oldLogCutoff+=coefficientSlew*(logTarget-oldLogCutoff)
            cutoff=exp(oldLogCutoff).coerceIn(35.0, min(16000.0,rate*.20))
            drive=c.color.drive+c.color.buzzDrive*buzz+agitate*(c.color.memoryDrive*history+.75*abs(position))
            val bias=c.color.bias+agitate*c.color.signedBias*signed
            damping=(s.damping-.20*agitate*history-.08*agitate*position).coerceIn(c.color.dampingFloor,1.30)
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
            val forcing=.85*tanh(agitate*(.30*history+c.reaction.energy*energyRise+
                c.reaction.gesture*gesture+c.reaction.noise*noiseForce))
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

    /** Pitch impulses are relative to the bounded excursion; [primary] starts a provoke/reply phrase. */
    internal data class GestureEvent(
        val frame: Int, val a: Double, val b: Double, val kick: Double, val primary: Boolean = true,
    )

    private data class GestureShape(val a: Double, val b: Double, val kick: Double, val spacing: Double)

    /**
     * Four unequal departures balance over an episode, without a compulsory up/down pair.
     * This structural variation uses its own seed stream; STATIC still owns noise excitation.
     */
    private class GestureSequence(seed: Int) {
        private val random=Random(Dsp.seedFor("SKITTER-GESTURES",seed))
        private var episode=DoubleArray(0)
        private var index=0

        fun next(): GestureShape {
            if(index==episode.size) {
                val signs=mutableListOf(1.0,1.0,-1.0,-1.0)
                signs.shuffle(random)
                if((1 until signs.size).all { signs[it]!=signs[it-1] }) {
                    val swap=signs[0]; signs[0]=signs[1]; signs[1]=swap
                }
                episode=DoubleArray(4) { signs[it]*(.55+.40*random.nextDouble()) }
                val mean=episode.sum()/episode.size
                for(i in episode.indices) episode[i]-=mean
                val scale=.90/episode.maxOf { abs(it) }
                for(i in episode.indices) episode[i]*=scale
                index=0
            }
            val a=episode[index++]
            val b=-a*(.48+.22*random.nextDouble())
            val kick=(.70+.30*random.nextDouble())*(if(random.nextBoolean()) 1.0 else -.55)
            return GestureShape(a,b,kick,.76+.48*random.nextDouble())
        }
    }

    private data class PhraseStyle(val triples: Double, val replyLow: Double, val replyHigh: Double,
                                   val tailLow: Double, val tailHigh: Double)

    private fun phraseStyle(voice: SkitterVoice): PhraseStyle = when(voice) {
        SkitterVoice.BASS, SkitterVoice.HOLLOW -> PhraseStyle(.15,.45,.55,.22,.30)
        SkitterVoice.LEAD -> PhraseStyle(.40,.52,.62,.28,.36)
        SkitterVoice.FLUTTER, SkitterVoice.CHIRP, SkitterVoice.FRAY -> PhraseStyle(.70,.60,.70,.32,.40)
    }

    private fun phraseSize(style: PhraseStyle, random: Random, remaining: Int = Int.MAX_VALUE): Int = when(remaining) {
        2 -> 2
        3 -> 3
        4 -> 2 // Two pairs, never a triple with a stranded single event.
        else -> if(random.nextDouble()<style.triples) 3 else 2
    }

    private fun replyKick(style: PhraseStyle, random: Random, primary: Double, polarity: Double, reply: Int): Double {
        val low=if(reply==1) style.replyLow else style.tailLow
        val high=if(reply==1) style.replyHigh else style.tailHigh
        return (if(polarity<0.0) -1.0 else 1.0)*abs(primary)*(low+(high-low)*random.nextDouble())
    }

    private data class PlannedGesture(val shape: GestureShape, val gap: Double, val primary: Boolean)

    /**
     * Keep the R2 pitch draws separate from phrase roles. A group spends its original cadence
     * budget on quick replies and a longer breath; only that breath hears the circuit history.
     * The note's onset is the first primary, so a low-SKITTER finite note still has one gesture.
     */
    private class PhraseSequence(voice: SkitterVoice, seed: Int, skitter: Double) {
        private val random=Random(Dsp.seedFor("SKITTER-PHRASES",seed))
        private val style=phraseStyle(voice)
        private val cadence=.36-.20*skitter
        private var size=phraseSize(style,random)
        private var index=1
        private var primaryKick=1.0
        private var spent=0.0

        fun next(shape: GestureShape): PlannedGesture {
            if(index<size) {
                val gap=(cadence*.5+(.010*random.nextDouble()-.005)).coerceIn(1.0/MAX_GESTURE_RATE_HZ,.155)
                spent+=gap
                val kick=replyKick(style,random,primaryKick,shape.kick,index)
                index++
                return PlannedGesture(shape.copy(kick=kick),gap,false)
            }
            val breath=max(1.0/MAX_GESTURE_RATE_HZ,size*cadence-spent)
            size=phraseSize(style,random)
            index=1; spent=0.0; primaryKick=shape.kick
            return PlannedGesture(shape,breath,true)
        }
    }

    /**
     * Reserve every gap, including the wrap, before distributing phrase slack. Quick replies
     * use at most 10% of that slack; phrase endings receive the rest as breathing room. Event
     * count, pitch draws, loop length and the 8 Hz ceiling stay the same as R2.
     */
    internal fun heldGestureSchedule(skitter: Double, seed: Int, periodFrames: Int,
                                     voice: SkitterVoice = SkitterVoice.FLUTTER): List<GestureEvent> {
        require(skitter.isFinite() && skitter in 0.0..1.0)
        require(periodFrames>0)
        if(skitter==0.0) return emptyList()
        val minimumGap=ceil(RATE/MAX_GESTURE_RATE_HZ).toInt()
        val maximumEvents=periodFrames/minimumGap
        require(maximumEvents>=2) { "SKITTER held period is too short for two bounded gestures" }
        val periodSeconds=periodFrames.toDouble()/RATE
        val count=(periodSeconds/(.36-.20*skitter)).roundToInt().coerceIn(2,maximumEvents)
        val sequence=GestureSequence(seed)
        val shapes=List(count) { sequence.next() }
        val random=Random(Dsp.seedFor("SKITTER-PHRASES",seed))
        val style=phraseStyle(voice)
        val primaries=BooleanArray(count)
        val kicks=DoubleArray(count) { shapes[it].kick }
        val replies=ArrayList<Int>()
        val breaths=ArrayList<Int>()
        var first=0
        while(first<count) {
            val size=phraseSize(style,random,count-first)
            primaries[first]=true
            for(reply in 1 until size) {
                val at=first+reply
                kicks[at]=replyKick(style,random,shapes[first].kick,shapes[at].kick,reply)
                replies.add(at-1)
            }
            breaths.add(first+size-1)
            first+=size
        }
        val slack=periodFrames-count*minimumGap
        val gaps=IntArray(count) { minimumGap }
        val replySlack=min((slack*.10).toInt(),(replies.size*.010*RATE).toInt())
        fun distribute(indices: List<Int>, frames: Int) {
            val weights=DoubleArray(indices.size) { .85+.30*random.nextDouble() }
            val sum=weights.sum()
            for(i in indices.indices) gaps[indices[i]]+=(frames*weights[i]/sum).toInt()
        }
        distribute(replies,replySlack)
        distribute(breaths,slack-replySlack)
        var remainder=periodFrames-gaps.sum()
        var gap=0
        while(remainder-->0) { gaps[breaths[gap%breaths.size]]++; gap++ }
        var frame=0
        return shapes.mapIndexed { i,s ->
            GestureEvent(frame,s.a,s.b,kicks[i],primaries[i]).also { frame+=gaps[i] }
        }
    }

    private const val GESTURE_ATTACK_SECONDS=.007
    private const val GESTURE_RECOVERY_SECONDS=.025
    // Equal area: an energy impulse tears quickly, then recovers with a weaker opposite pull.
    private const val GESTURE_RECOVERY_WEIGHT=GESTURE_ATTACK_SECONDS/GESTURE_RECOVERY_SECONDS

    /**
     * A cent departure moves farther through oscillator phase in a high register or during a
     * slow recovery. Budget that relative travel, so irregular episodes remain quick tears
     * instead of walking the pair into broad scoops and sustained cancellation. The budget
     * grows with SKITTER; smooth saturation keeps every part of the control responsive.
     * Velocity scales the bounded departure afterward, retaining its excitation contrast.
     */
    private fun excursionFor(skitter: Double, velocity: Double, hz: Double, s: Shape): Double {
        val cents=9.0*skitter+170.0*skitter*skitter
        val phaseBudget=(.055+.28*skitter*skitter)*s.phaseTravel
        val relativeRecovery=s.recoveryA+.70*s.interval*s.recoveryB
        // Keep a small perceptible departure even at the top of the supported register.
        val ceiling=max(4.0,phaseBudget*1200.0/(ln(2.0)*hz*relativeRecovery))
        return cents/sqrt(1.0+(cents/ceiling).pow(2.0))*(.55+.45*velocity)
    }

    /** Exact periodic recovery state from the complete seeded episode, including its previous periods. */
    private fun heldForcing(voice: SkitterVoice, s: Shape, m: Map<String,Float>, velocity: Double, seed: Int, plan: LoopPlan,
                            oversample: Int, probe: SkitterProbe): Forcing {
        val frames=plan.frames*oversample; val rate=RATE*oversample
        val skitter=m.getValue("SKITTER").toDouble()
        val schedule=heldGestureSchedule(skitter,seed,plan.frames,voice)
        val excursion=excursionFor(skitter,velocity,plan.rootHz,s)
        val ra=s.recoveryA; val rb=if(probe.equalRecovery) ra else s.recoveryB
        val periodSeconds=frames/rate.toDouble()
        fun recovered(age: Double, tau: Double)=exp(-age/tau)/(1.0-exp(-periodSeconds/tau))
        fun offset(i: Int, tau: Double, second: Boolean): Double {
            var total=0.0
            for(event in schedule) {
                val age=(i-event.frame*oversample+frames)%frames/rate.toDouble()
                total+=excursion*(if(second) event.b else event.a)*recovered(age,tau)
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
            for(event in schedule) {
                val age=(i-event.frame*oversample+frames)%frames/rate.toDouble()
                kick+=skitter*event.kick*(recovered(age,GESTURE_ATTACK_SECONDS)-
                    GESTURE_RECOVERY_WEIGHT*recovered(age,GESTURE_RECOVERY_SECONDS))
            }
            gesture[i]=kick.toFloat()
        }
        return Forcing(a,b,gesture,noise(plan.frames,Dsp.seedFor("SKITTER-NOISE",seed)),
            frames/sumA,frames/sumB,schedule.size)
    }

    // SynthScreen previews run in runInterruptible: a newer request interrupts this thread,
    // and the long loops and the passes poll it so a stale render stops instead of finishing unwanted.
    private fun stopIfUnwanted() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("SKITTER render no longer wanted")
    }

    /**
     * Blackman-windowed sinc, 48 output-sample support, >70 dB far-stopband rejection: a tone
     * from the output Nyquist up to the internal one leaves more than 70 dB below a passband
     * tone, at every oversample [renderProbe] accepts (SkitterDecimatorTest).
     */
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
        val n=raw.size
        for(i in out.indices) {
            if(i%8192==0) stopIfUnwanted()
            var y=0.0
            val centre=i*oversample
            if(periodic) {
                // The wrapped tap index advances by one per tap; only its start needs a modulo.
                var at=((centre-radius)%n+n)%n
                for(j in -radius..radius) {
                    y+=raw[at]*kernel[j+radius]
                    at++
                    if(at==n) at=0
                }
            } else {
                // Taps outside the note are skipped, never summed as zero: visit only the in-bounds ones.
                for(j in max(-radius,-centre)..min(radius,n-1-centre)) y+=raw[centre+j]*kernel[j+radius]
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
        val m=settled(voice,macros); val s=shape(voice); val c=character(voice)
        val vel=velocity.takeIf { it.isFinite() }?.coerceIn(0f,1f)?.toDouble() ?: 1.0
        val held=isLoop(m.getValue("HOLD")); val rate=RATE*oversample
        val hz=Keys.midiHz(midi).toDouble()
        val contact=if(!probe.articulationDisabled && (voice==SkitterVoice.LEAD || voice==SkitterVoice.HOLLOW))
            ContactEnvelope(voice,rate,vel,probe) else null
        val body=!probe.bodyDisabled && (voice==SkitterVoice.LEAD || voice==SkitterVoice.HOLLOW)
        val quartet=!probe.quartetDisabled && voice!=SkitterVoice.LEAD && voice!=SkitterVoice.HOLLOW
        val circuit=Circuit(s,c,m,rate,probe,held,contact); val stats=Stats(); val trace=ArrayList<SkitterTrace>()
        val contextSeed=Dsp.seedFor("SKITTER",voice.name,midi,seed)
        val buzz=m.getValue("BUZZ").toDouble(); val skitter=m.getValue("SKITTER").toDouble()
        val vent=m.getValue("VENT").toDouble()
        // Let the voiced filter define ordinary timbre. Preserve the previous root support
        // at full high-pass VENT and in the upper register, where it protects the musical root.
        val support=c.source.rootSupport+(s.support+.10-c.source.rootSupport)*vent.pow(3.0)+
            .035*((midi-60)/12.0).coerceIn(0.0,1.0)
        val baseCutoff=(hz*s.cutoffRatio*(.62+.70*buzz)*(.75+.25*vel)).coerceIn(90.0,5500.0)
        var phaseA=.173; var phaseB=.381; var rootPhase=.0
        var a=0.0; var b=0.0; var gesture=0.0
        val traceStride=max(1,(rate*.005).roundToInt())
        fun record(i: Int, final: Boolean = false) {
            stats.measure(circuit,a,b)
            if(i%traceStride==0 || final) trace.add(SkitterTrace(i.toDouble()/rate,circuit.history,circuit.signed,
                circuit.position,circuit.velocity,a,b,circuit.cutoff,circuit.drive,
                contact?.contact ?: 0.0,if(body) 1.0 else contact?.gain ?: 1.0))
        }
        var periods=0; var converged=!held; var stateError=0.0; var loopStart=-1
        val rawInternal: FloatArray
        if(held) {
            val plan=planLoop(hz,s.interval)
            val force=heldForcing(voice,s,m,vel,contextSeed,plan,oversample,probe)
            val contacts=if(contact!=null) heldGestureSchedule(skitter,contextSeed,plan.frames,voice) else emptyList()
            val n=plan.frames*oversample
            fun cycle(recordTrace: Boolean): FloatArray {
                val raw=FloatArray(n)
                var nextContact=0
                for(i in raw.indices) {
                    if(i%8192==0) stopIfUnwanted()
                    if(nextContact<contacts.size && i==contacts[nextContact].frame*oversample) {
                        contact?.strike(skitter*contacts[nextContact].kick,circuit.history,circuit.agitate)
                        nextContact++
                    }
                    contact?.step()
                    a=force.a[i].toDouble(); b=force.b[i].toDouble(); gesture=force.gesture[i].toDouble()
                    val fa=plan.rootHz*force.correctionA*2.0.pow(a/1200.0)
                    val fb=plan.rootHz*s.interval*force.correctionB*2.0.pow(b/1200.0)
                    val input=vel*.35*source(voice,phaseA,phaseB,fa/rate,fb/rate,buzz,c,circuit,contact,body,quartet,vel)
                    val root=sin(2.0*PI*rootPhase)*vel
                    raw[i]=circuit.step(input,interpolate(force.noise,i.toDouble()/oversample,true)*vel,gesture*vel,
                        root,support,baseCutoff,i).toFloat()
                    phaseA=(phaseA+fa/rate)%1.0; phaseB=(phaseB+fb/rate)%1.0; rootPhase=(rootPhase+plan.rootHz/rate)%1.0
                    if(recordTrace) record(i,final=i==raw.lastIndex) else stats.measure(circuit,a,b)
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
            val gestureAttackDecay=exp(-1.0/(rate*GESTURE_ATTACK_SECONDS))
            val gestureRecoveryDecay=exp(-1.0/(rate*GESTURE_RECOVERY_SECONDS))
            val excursion=excursionFor(skitter,vel,hz,s)
            a=excursion; b=-.65*excursion; stats.gestures=if(skitter>0.0) 1 else 0
            var gestureAttack=skitter; var gestureRecovery=skitter
            val sequence=GestureSequence(contextSeed)
            val phrases=PhraseSequence(voice,contextSeed,skitter)
            var upcoming=phrases.next(sequence.next())
            var nextGesture=upcoming.gap
            var armed=true
            // Note-on remains a touch articulation at SKITTER zero; that control still
            // removes pitch departures and repeated events. HOLD has no repeated note-on.
            contact?.strike(1.0,circuit.history,circuit.agitate)
            for(i in rawInternal.indices) {
                if(i%8192==0) stopIfUnwanted()
                val t=i.toDouble()/rate
                if(skitter>.28 && t<gate-.12 && t>=nextGesture && armed) {
                    // Recent energy and agitation alter a bounded episode, rather than run a final LFO.
                    val response=.85+.25*circuit.agitate*tanh(2.0*circuit.history+.5*abs(circuit.position))
                    a+=upcoming.shape.a*excursion
                    b+=upcoming.shape.b*excursion
                    val kick=skitter*upcoming.shape.kick*response
                    gestureAttack+=kick; gestureRecovery+=kick
                    contact?.strike(kick,circuit.history,circuit.agitate)
                    stats.gestures++; armed=false
                    upcoming=phrases.next(sequence.next())
                    val breathResponse=if(upcoming.primary)
                        1.0-circuit.agitate*(.10*tanh(2.0*circuit.position)+.08*tanh(3.0*circuit.history))
                        else 1.0
                    nextGesture=t+max(1.0/MAX_GESTURE_RATE_HZ,
                        upcoming.gap*breathResponse)
                }
                // Explicit refractory/hysteresis guard; no agitation-driven event chatter.
                if(!armed && max(abs(gestureAttack),abs(gestureRecovery))<.08 && t<nextGesture-.04) armed=true
                contact?.step()
                a*=ra; b*=rb
                gestureAttack*=gestureAttackDecay; gestureRecovery*=gestureRecoveryDecay
                gesture=gestureAttack-GESTURE_RECOVERY_WEIGHT*gestureRecovery
                val attack=1.0-exp(-t/s.attack)
                val release=if(t<=gate) 1.0 else exp(-6.907755*(t-gate)/s.release)
                // A little extra early energy provokes the circuit, then the powered source settles.
                val excitation=if(t>gate+s.release) 0.0 else attack*release*(.90+.18*exp(-t/.08))
                val brightness=(buzz+.13*vel*exp(-t/.10)).coerceAtMost(1.0)
                val fa=hz*2.0.pow(a/1200.0)
                val fb=hz*s.interval*2.0.pow(b/1200.0)
                val input=excitation*vel*.35*source(voice,phaseA,phaseB,fa/rate,fb/rate,brightness,c,circuit,contact,body,quartet,vel)
                val root=sin(2.0*PI*rootPhase)*excitation*vel
                rawInternal[i]=circuit.step(input,interpolate(nse,i.toDouble()/oversample,false)*excitation*vel,
                    gesture*excitation*vel,root,support,baseCutoff,i).toFloat()
                phaseA=(phaseA+fa/rate)%1.0; phaseB=(phaseB+fb/rate)%1.0; rootPhase=(rootPhase+hz/rate)%1.0
                record(i,final=i==rawInternal.lastIndex)
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
