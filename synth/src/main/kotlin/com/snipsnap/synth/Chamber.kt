package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.*
import kotlin.random.Random

enum class ChamberVoice { HYBRID, AQUARIUM }

/**
 * A finite, single-strike quartet in a room with separately addressed boundaries.
 * Material names are designed responses, rather than measured building materials.
 *
 * Stability is established for the COMPLETE feedback network in energy coordinates.
 * A mode stores (omega*q,v), and free evolution is an exact damped rotation. Every
 * instrument and boundary exchanges one unit-normalized modal velocity with one
 * unit-normalized combination of traveling waves through an orthogonal rotation.
 * The remaining wave combinations are unchanged. The single shared late junction
 * is an orthogonal Householder scatter. Integer, causal delays preserve traveling
 * energy and their loss factors are at most one. Therefore, between finite primary
 * strike increments, the sum of ALL modal and traveling squared states cannot grow
 * (apart from Float storage rounding). The measured primary work is its explicit
 * upper bound. This is a passive-network proof, not a claim that small coefficients
 * or independently stable resonators prove stability. Listener taps do not feed back.
 * The modulated submerged-glass listener branch is excluded from this proof and
 * entirely excluded from feedback. HOLD remains deliberately unsupported.
 */
object Chamber {
    const val RATE = Dsp.RATE
    const val DEFAULT_MIDI = 60
    const val ROOT_MIDI = DEFAULT_MIDI
    const val MIDI_MIN = 36
    const val MIDI_MAX = 84
    const val DEFAULT_SEED = 0L
    const val MODEL_VERSION = 3
    /** Explicit range audition; the accepted default and factory recipes stay on model 3. */
    const val WIDE_RANGE_MODEL = 4
    const val MAX_MODEL_VERSION = WIDE_RANGE_MODEL
    const val MAX_SECONDS = 6f
    private const val IR = RATE * Dsp.OVERSAMPLE
    private const val TAU = 2.0 * PI
    private const val AUDIO_GAIN = 38.0
    private const val DIRECT_GAIN = .46
    private const val EARLY_GAIN = .21
    private const val SURFACE_GAIN = .23
    private const val LATE_GAIN = .10
    // Listener pressure units differ between a modal boundary pickup and a
    // traveling-wave pickup. These measured first-listen calibrations bring the
    // passive boundary answers into the audible mix. Neither enters feedback or
    // changes primary excitation work. Mix weights alone are not an energy bound.
    private const val SURFACE_PICKUP_CALIBRATION = 16.0
    private const val LATE_PICKUP_CALIBRATION = 8.0
    // A vibraphone-like bar needs to retain its actual modal body. The initial
    // 9/s port load damped its root faster than the intrinsic 2.45s T60. A 0.25/s
    // acoustic load leaves a sustained pitched body; this pressure calibration
    // preserves the original bar attack level in the listener, outside feedback.
    private const val BAR_PORT_RATE = .25
    private const val BAR_PRESSURE_CALIBRATION = 6.0
    // Model 1's 12/s membrane port swallowed its pitched body within a few
    // hundred milliseconds. A light, passive acoustic load lets the membrane's
    // intrinsic decay speak. Radiation is calibrated separately in the observer;
    // this gain never changes the energy exchanged with the room.
    private const val MEMBRANE_PORT_RATE = .5
    private const val MEMBRANE_PRESSURE_CALIBRATION = 24.0
    // The model-2 membrane voicing was accepted. Model 3 is only a +3 dB
    // listener mix lift; it never changes the membrane or room energy states.
    private val MEMBRANE_MIX_LIFT = 10.0.pow(3.0 / 20.0)

    fun macrosFor(voice: ChamberVoice): List<MacroSpec> {
        val d = if (voice == ChamberVoice.HYBRID) floatArrayOf(.50f,.45f,.40f,.90f,.60f)
            else floatArrayOf(.35f,.60f,.95f,.80f,.70f)
        return listOf(MacroSpec("STRIKE", d[0], .35f), MacroSpec("BALANCE", d[1], .4f),
            MacroSpec("MATERIAL", d[2], .4f), MacroSpec("DIVIDE", d[3], .4f),
            MacroSpec("PLACEMENT", d[4], .4f), MacroSpec("HOLD", 0f, 0f))
    }
    fun defaults(voice: ChamberVoice) = macrosFor(voice).associate { it.name to it.default }
    fun configFor(voice: ChamberVoice) = ChamberConfig.forVoice(voice)
    internal fun settled(voice: ChamberVoice, macros: Map<String, Float>) = defaults(voice).mapValues { (k,d) ->
        macros[k]?.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: d
    }
    fun drumClassFor(@Suppress("UNUSED_PARAMETER") voice: ChamberVoice,
        @Suppress("UNUSED_PARAMETER") macros: Map<String, Float> = emptyMap()) = DrumClass.TONAL
    fun scramble(voice: ChamberVoice, random: Random = Random.Default, temperature: Float = .35f,
        near: Patch? = null): Map<String, Float> = Dsp.scrambleNear(
        defaults(voice) + (near?.macros?.filterKeys { it in defaults(voice) } ?: emptyMap()),
        if (temperature.isFinite()) temperature else .35f, random).toMutableMap().also { it["HOLD"] = 0f }

    internal data class ProbeOptions(
        val normalize: Boolean = false,
        val roomEnabled: Boolean = true,
        val returnsEnabled: Boolean = true,
        val isolatedInstrument: Int? = null,
        val averageSurfaces: Boolean = false,
        val submergedModulationEnabled: Boolean = true,
        val seconds: Float = 4.5f,
        val recordDiagnostics: Boolean = true,
        val oversample: Int = Dsp.OVERSAMPLE,
        val recordStems: Boolean = true,
    )
    internal data class Arrival(val sourceIndex: Int, val kind: String, val timeSeconds: Double,
        val face: String? = null, val materialWeights: List<Float> = emptyList())
    internal data class EnergySnapshot(val timeSeconds: Double, val instrumentEnergy: Double,
        val surfaceEnergy: Double, val travelingEnergy: Double, val primaryWork: Double) {
        val totalEnergy get() = instrumentEnergy + surfaceEnergy + travelingEnergy
    }
    internal data class Diagnostics(
        val primaryWork: Double, val maxEnergy: Double, val finalEnergy: Double,
        val maxUnforcedEnergyRise: Double, val passiveCouplingNorm: Double,
        val maxDelayGain: Double, val finiteStates: Boolean, val peakModalState: Double,
        val energy: List<EnergySnapshot>, val preparationNanos: Long, val synthesisNanos: Long,
        val outputNanos: Long, val modalCount: Int, val travelingSamples: Int,
        val sharedStrikeTimes: List<Double> = emptyList(),
        val primaryWorkBudget: Double = 0.0,
        val primaryEndSeconds: Double = 0.0,
        val energyAtPrimaryEnd: Double = 0.0,
    )
    internal data class Probe(
        val samples: FloatArray, val rawMix: FloatArray, val instrumentStems: Array<FloatArray>,
        val direct: FloatArray, val early: FloatArray, val surface: FloatArray, val late: FloatArray,
        val surfaceStems: Array<FloatArray>, val arrivals: List<Arrival>,
        val geometry: ChamberGeometry, val diagnostics: Diagnostics, val rawPeak: Float,
        val sampleRate: Int = RATE,
    )

    /** One exact damped mode; all modal ports below use an explicit unit vector. */
    private class Bank(frequencies: DoubleArray, t60: DoubleArray, weights: DoubleArray, val rate: Int) {
        val x = DoubleArray(frequencies.size)
        val v = DoubleArray(frequencies.size)
        val p = weights.copyOf()
        private val co = DoubleArray(x.size)
        private val si = DoubleArray(x.size)
        private var portIsCurrent = false
        private var currentPort = 0.0
        init {
            val norm = sqrt(p.sumOf { it*it }).coerceAtLeast(1e-20)
            for (i in x.indices) {
                p[i] /= norm
                val damp = exp(-ln(1000.0)/(t60[i]*rate))
                co[i] = cos(TAU*frequencies[i]/rate)*damp
                si[i] = sin(TAU*frequencies[i]/rate)*damp
            }
        }
        fun port(): Double {
            if (!portIsCurrent) {
                var out=0.0; for(i in v.indices) out += v[i]*p[i]
                currentPort=out; portIsCurrent=true
            }
            return currentPort
        }
        fun movePort(delta: Double) {
            for(i in v.indices) v[i] += delta*p[i]
            portIsCurrent=false
        }
        fun strike(delta: Double): Double {
            val before = port()
            movePort(delta)
            return before*delta+.5*delta*delta
        }
        fun step() {
            for(i in x.indices) { val a=x[i]; val b=v[i]; x[i]=co[i]*a+si[i]*b; v[i]=co[i]*b-si[i]*a }
            portIsCurrent=false
        }
        fun energy(): Double { var e=0.0; for(i in x.indices) e += .5*(x[i]*x[i]+v[i]*v[i]); return e }
        fun peak(): Double { var p=0.0; for(i in x.indices) p=max(p,max(abs(x[i]),abs(v[i]))); return p }
    }

    /** Single-read/single-write integer waveguide. Its ledger includes every stored sample. */
    private class Delay(seconds: Double, val rate: Int, val gain: Double = 1.0,
        private val trackEnergy: Boolean = true) {
        val data = FloatArray(max(1,(seconds*rate).roundToInt()))
        private var cursor = 0
        private var accumulatedEnergy = 0.0
        val energy: Double get() = if(trackEnergy) accumulatedEnergy else {
            var total=0.0
            for(sample in data) total += .5*sample.toDouble()*sample
            total
        }
        fun read() = data[cursor].toDouble()
        fun put(value: Double) {
            val stored = (value*gain).toFloat()
            // Production needs only the final state check. The incremental ledger
            // is diagnostic work on every waveguide, never part of its audio.
            if(trackEnergy) {
                val old = data[cursor].toDouble()
                accumulatedEnergy += .5*(stored.toDouble()*stored-old*old)
            }
            data[cursor]=stored
            if (++cursor==data.size) cursor=0
        }
    }

    /** A nonfeedback listener delay; fractional reads are convex and uniformly bounded. */
    private class Tap(seconds: Double, private val rate: Int, extraSeconds: Double = 0.0) {
        private val delay = seconds*rate
        private val data = FloatArray(ceil((seconds+extraSeconds)*rate).toInt()+4)
        private val fixedDelay = delay.toInt()
        private val fixedFraction = delay-floor(delay)
        private var cursor = 0
        // The 38 stationary observer taps share the exact interpolation law but
        // need no per-sample clamp, floor or delay conversion. The aquatic pickup
        // below retains its varying fractional delay.
        fun tick(input: Double): Double {
            data[cursor]=input.toFloat()
            var a=cursor-fixedDelay; if(a<0) a+=data.size
            var b=a-1; if(b<0) b+=data.size
            val out=data[a]*(1-fixedFraction)+data[b]*fixedFraction
            if(++cursor==data.size) cursor=0
            return out
        }
        fun tick(input: Double, offsetSeconds: Double): Double {
            data[cursor]=input.toFloat()
            val d=(delay+offsetSeconds*rate).coerceIn(0.0,(data.size-2).toDouble())
            var a=cursor-d.toInt(); while(a<0) a+=data.size
            var b=a-1; if(b<0) b+=data.size
            val frac=d-floor(d)
            val out=data[a]*(1-frac)+data[b]*frac
            if(++cursor==data.size) cursor=0
            return out
        }
    }

    /** Five parallel contractive one-poles: material interpolation is a convex output mix. */
    private class Reflection(weights: List<Float>, private val rate: Int, cutoffScale: Double = 1.0) {
        val w=DoubleArray(5) { weights[it].toDouble() }
        private val state=DoubleArray(5)
        private val coeff=DoubleArray(5) {
            val cutoff=(doubleArrayOf(2400.0,4100.0,10500.0,15500.0,4800.0)[it]*cutoffScale).coerceAtMost(19000.0)
            1-exp(-TAU*cutoff/rate)
        }
        fun tick(input: Double): Double {
            var out=0.0
            for(i in 0..4) if(w[i]>0.0) {
                state[i] += coeff[i]*(input-state[i]); out += w[i]*state[i]
            }
            return out
        }
    }

    private val ratios = arrayOf(
        doubleArrayOf(1.0,1.505,1.985,2.48,2.96,3.43,4.08),
        doubleArrayOf(1.0,3.96,9.65,15.7,18.4,22.5),
        doubleArrayOf(1.0,1.48,2.17,2.91,4.16),
        doubleArrayOf(1.0,1.527,2.137,2.894,4.103,5.27))
    private fun instrument(index: Int, hz: Double, ratio: Double, hardness: Double,
        rate: Int, seed: Long, model: Int, spectralTilt: Double = 0.0): Bank {
        // Keep every source's established random realization across revisions.
        // Model 2 revises only the membrane; the other three banks are unchanged.
        val random=Random(Dsp.seedFor("CHAMBER", 1,seed,index))
        val frequencies=ratios[index].mapIndexed { j,r ->
            hz*ratio*r*(if(j==0 || index<2) 1.0 else 1+random.nextDouble(-.002,.002))
        }.takeWhile { it<19000.0 }.toDoubleArray()
        val base=if(index==0 && model>=2) 1.65 else doubleArrayOf(1.15,2.45,1.20,2.65)[index]
        val decay=DoubleArray(frequencies.size) { j -> base/(1+.20*j)*(.94+.12*random.nextDouble()) }
        val weights=DoubleArray(frequencies.size) { j ->
            when(index) {
                0 -> if(model==1) (if(j==0) 1.5 else 1.0)*exp(-j*(.58-.27*hardness))
                    else {
                        // Keep the half-integer family audible without letting
                        // its shared two-period repeat imply an octave-low root.
                        // The near-2x/3x/4x body modes retain their full weights.
                        val weight=(if(j==0) 1.25 else 1.1)*exp(-j*(.42-.18*hardness))
                        weight*(if(j%2==1) .7 else 1.0)
                    }
                1 -> (if(j==0) 1.7 else .70)*exp(-j*(.62-.30*hardness))
                2 -> doubleArrayOf(.85,1.0,.8,.5,.35)[j]*(.6+.4*hardness).pow(j*.25)
                else -> doubleArrayOf(.48,.9,1.0,.7,.5,.3)[j]*(.5+.5*hardness).pow(j*.2)
            }
        }
        if(spectralTilt!=0.0) for(j in weights.indices) weights[j] *= exp(j*spectralTilt)
        return Bank(frequencies,decay,weights,rate)
    }

    private val surfaceHz = arrayOf(
        doubleArrayOf(155.0,431.0,1127.0), doubleArrayOf(92.0,247.0,613.0),
        doubleArrayOf(379.0,997.0,2111.0), doubleArrayOf(643.0,1439.0,2933.0),
        doubleArrayOf(487.0,1171.0,2417.0))
    private val surfaceT60 = arrayOf(
        doubleArrayOf(.38,.26,.17), doubleArrayOf(.63,.43,.28),
        doubleArrayOf(1.75,1.30,.95), doubleArrayOf(1.42,1.14,.78),
        doubleArrayOf(1.38,1.05,.75))
    private fun boundary(region: Int, weights: List<Float>, rate: Int, profile: ChamberWideRange.Profile?): Bank {
        val f=ArrayList<Double>(); val t=ArrayList<Double>(); val p=ArrayList<Double>()
        // Fixed family modes with convex parallel participation. Inactive families
        // allocate no states; a nonzero family keeps the same modes across a sweep.
        for(material in 0..4) if(weights[material]>0f) for(mode in 0..2) {
            f += surfaceHz[material][mode]*(1+.013*region)
            t += profile?.boundaryT60(surfaceT60[material][mode]) ?: surfaceT60[material][mode]
            p += sqrt(weights[material].toDouble())*doubleArrayOf(.82,.50,.28)[mode]
        }
        return Bank(f.toDoubleArray(),t.toDoubleArray(),p.toDoubleArray(),rate)
    }

    private fun stopIfUnwanted(cancelled: () -> Boolean) {
        if(cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("CHAMBER render cancelled")
    }
    // Keep collection preparation/measurement outside the hot render method.
    // Kotlin inlines these small loops; together they otherwise put renderProbe
    // over HotSpot's 8,000-byte huge-method limit and leave synthesis interpreted.
    private fun averageMaterials(geometry: ChamberGeometry): List<Float> {
        val area=geometry.regionAreas.sumOf { it.toDouble() }
        return List(5) { k -> geometry.regionMaterialWeights.indices.sumOf { r ->
            geometry.regionMaterialWeights[r][k]*geometry.regionAreas[r].toDouble()
        }.div(area).toFloat() }
    }
    private fun sourceWeights(edges: Array<Array<ChamberSurfaceCoupling>>, localityPower: Double) = Array(4) { s ->
        DoubleArray(10) { r -> edges[s][r].weight.toDouble() }.also { localize(it,localityPower) }
    }
    private fun regionWeights(edges: Array<Array<ChamberSurfaceCoupling>>, localityPower: Double) = Array(10) { r ->
        DoubleArray(5) { s -> if(s==4) .65 else edges[s][r].weight.toDouble() }.also { localize(it,localityPower) }
    }
    private fun localize(weights: DoubleArray, power: Double) {
        if(power!=1.0) for(i in weights.indices) weights[i]=weights[i].pow(power)
        normalize(weights)
    }
    private fun normalize(weights: DoubleArray) {
        val norm=sqrt(weights.sumOf { it*it })
        for(i in weights.indices) weights[i]/=norm
    }
    private fun reflectionGains(geometry: ChamberGeometry): DoubleArray {
        // Each of six first-order rays keeps its actual distance attenuation.
        // Their average is bounded by the direct path's attenuation because no
        // reflected route is shorter. Normalizing each source's ray sum to one
        // instead made distant walls louder and could cancel a low-note anchor.
        return DoubleArray(geometry.reflections.size) { i ->
            geometry.reflections[i].gain/6.0
        }
    }
    private fun bankEnergy(banks: Array<Bank>) = banks.sumOf { it.energy() }
    private fun delayEnergy(delays: List<Delay>) = delays.sumOf { it.energy }.coerceAtLeast(0.0)
    private fun modalCount(banks: Array<Bank>) = banks.sumOf { it.x.size }
    private fun travelingSamples(delays: List<Delay>) = delays.sumOf { it.data.size }
    private data class PreparedStrike(val lengths: IntArray, val strengths: DoubleArray, val workBudget: Double)
    /** Keep parameter preparation out of the near-limit HotSpot render method. */
    private fun prepareStrike(m: Map<String,Float>, midi: Int, velocity: Float, hardness: Double,
        configuration: ChamberConfig, rate: Int, isolated: Int?, profile: ChamberWideRange.Profile?): PreparedStrike {
        val lengths=IntArray(4) { i ->
            val cycles=doubleArrayOf(.47,.20,.13,.085)[i]*(1-.50*hardness)
            val legacy=(rate*cycles/Keys.midiHz(midi)).roundToInt().coerceIn((.0005*rate).toInt(),(.012*rate).toInt())
            profile?.contactFrames(legacy,Keys.midiHz(midi).toDouble()*configuration.tuningRatios[i],rate) ?: legacy
        }
        val balance=m.getValue("BALANCE").toDouble()*3
        val floor=profile?.balanceFloor ?: .17
        val width=profile?.balanceWidth ?: .69
        val strengths=DoubleArray(4) { i -> floor+(1.0-floor)*exp(-.5*((balance-i)/width).pow(2)) }
        val norm=sqrt(strengths.sumOf { it*it })
        val strength=velocity.toDouble().pow(.72)*(.32+.95*m.getValue("STRIKE"))
        for(i in 0..3) strengths[i] = strengths[i]/norm*strength*
            doubleArrayOf(1.08,1.23,.92,.88)[i]*
            (if(isolated==null || isolated==i) 1.0 else 0.0)*
            (if(configuration.instrumentEnabled[i]) 1.0 else 0.0)
        // The force sum is the same independent upper bound used by models 1–3.
        val integratedForce=strengths.indices.sumOf { s ->
            strengths[s]*PI/(2*lengths[s])/sin(PI/(2*lengths[s]))
        }
        return PreparedStrike(lengths,strengths,.5*integratedForce*integratedForce)
    }
    private fun arrivals(geometry: ChamberGeometry) =
        geometry.directPaths.map { Arrival(it.sourceIndex,"direct",it.delaySeconds.toDouble()) }+
            geometry.reflections.map { Arrival(it.sourceIndex,"first-reflection",it.delaySeconds.toDouble(),it.face.toString(),it.materialWeights) }
    private fun peak(samples: FloatArray) = samples.maxOfOrNull { abs(it) } ?: 0f
    private fun finite(samples: FloatArray) = samples.all { it.isFinite() }
    private fun finishStems(stems: Array<FloatArray>,rate: Int, cancelled: () -> Boolean) =
        Array(stems.size) { finish(stems[it],rate,cancelled) }
    private fun finish(input: FloatArray, internalRate: Int, cancelled: () -> Boolean): FloatArray {
        stopIfUnwanted(cancelled)
        if(input.isEmpty()) return input
        Tide.bandLimit(input, internalRate)
        stopIfUnwanted(cancelled)
        val out=if(internalRate==IR) Dsp.decimate(input,RATE) else {
            // The repository sinc kernel is calibrated for ratios near 1/2..2;
            // use the same cascaded 2x path for the 8x reference as for production.
            var stage=Snip(input,channels=1,sampleRate=internalRate)
            while(stage.sampleRate>RATE) stage=com.snipsnap.audio.Resampler.resample(stage,stage.sampleRate/2)
            stage.samples
        }
        val hp=Dsp.OnePole(RATE)
        for(i in out.indices) out[i] -= hp.lp(out[i],12f)
        stopIfUnwanted(cancelled)
        return out
    }

    internal fun renderProbe(voice: ChamberVoice, macros: Map<String,Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = .75f,
        configuration: ChamberConfig = configFor(voice), seed: Long = DEFAULT_SEED,
        model: Int = MODEL_VERSION, options: ProbeOptions = ProbeOptions(),
        cancelled: () -> Boolean = { false }): Probe {
        stopIfUnwanted(cancelled)
        require(model in 1..MAX_MODEL_VERSION) { "Unsupported CHAMBER model $model" }
        require(midi in MIDI_MIN..MIDI_MAX) { "CHAMBER MIDI must be $MIDI_MIN..$MIDI_MAX" }
        require(velocity.isFinite() && velocity in 0f..1f) { "CHAMBER velocity must be 0..1" }
        require(options.seconds.isFinite() && options.seconds in .1f..MAX_SECONDS)
        require(options.isolatedInstrument==null || options.isolatedInstrument in 0..3)
        require(options.oversample==4 || options.oversample==8)
        val m=settled(voice,macros)
        require(m.getValue("HOLD")==0f) { "CHAMBER model $model supports finite strikes only; HOLD must be 0" }
        val profile=if(model==WIDE_RANGE_MODEL) ChamberWideRange.resolve(voice,m) else null
        val preparationStart=System.nanoTime()
        val rate=RATE*options.oversample
        // Traveling samples use energy-normalized coordinates: their amplitude
        // falls as sqrt(dt). Convert observer pressure back to the same units for
        // the 8x reference. Production4x retains exactly its calibrated gain.
        val observerGain=AUDIO_GAIN*sqrt(rate.toDouble()/IR)
        val geometry=ChamberWideRange.geometry(configuration,m.getValue("MATERIAL"),m.getValue("DIVIDE"),m.getValue("PLACEMENT"),profile)
        val hardness=(.12+.75*m.getValue("STRIKE")+.13*velocity).coerceIn(0.0,1.0)
        val banks=Array(4) { i -> instrument(i,Keys.midiHz(midi).toDouble(),configuration.tuningRatios[i].toDouble(),hardness,rate,seed,model,profile?.spectralTilt ?: 0.0) }
        val average=averageMaterials(geometry)
        val materials=if(options.averageSurfaces) List(10) { average } else geometry.regionMaterialWeights
        val boundaries=Array(10) { boundary(it,materials[it],rate,profile) }
        val edges=Array(4) { source -> Array(10) { region ->
            geometry.surfaceCouplings.first { it.sourceIndex==source && it.region.ordinal==region }
        } }
        val outgoing=Array(4) { s -> Array(10) { r -> Delay(edges[s][r].delaySeconds.toDouble(),rate,.991,options.recordDiagnostics) } }
        val returning=Array(4) { s -> Array(10) { r -> Delay(edges[s][r].delaySeconds.toDouble(),rate,.991,options.recordDiagnostics) } }
        val sourceWeights=sourceWeights(edges,profile?.localityPower ?: 1.0)
        val regionWeights=regionWeights(edges,profile?.localityPower ?: 1.0)
        val regionToField=Array(10) { r -> Delay(.009+.0011*r,rate,.985,options.recordDiagnostics) }
        val fieldToRegion=Array(10) { r -> Delay(.009+.0011*r,rate,.985,options.recordDiagnostics) }
        val field=Array(8) { i -> Delay(doubleArrayOf(.0233,.0297,.0371,.0419,.0473,.0539,.0611,.0677)[i],rate,.973,options.recordDiagnostics) }
        val allDelays=outgoing.flatMap { it.toList() }+returning.flatMap { it.toList() }+
            regionToField.toList()+fieldToRegion.toList()+field.toList()
        val sourceS=DoubleArray(4) { sqrt(2*doubleArrayOf(if(model==1) 12.0 else MEMBRANE_PORT_RATE,BAR_PORT_RATE,15.0,8.0)[it]/rate) }
        val sourceC=DoubleArray(4) { sqrt(1-sourceS[it]*sourceS[it]) }
        val boundaryS=sqrt(2*28.0/rate); val boundaryC=sqrt(1-boundaryS*boundaryS)
        val sourceTaps=Array(4) { Tap(geometry.directPaths[it].delaySeconds.toDouble(),rate) }
        val directGains=DoubleArray(4) { geometry.directPaths[it].gain.toDouble() }
        val reflectionSources=IntArray(geometry.reflections.size) { geometry.reflections[it].sourceIndex }
        val reflectionTaps=Array(geometry.reflections.size) { Tap(geometry.reflections[it].delaySeconds.toDouble(),rate) }
        val reflections=Array(geometry.reflections.size) { Reflection(if(options.averageSurfaces) average else geometry.reflections[it].materialWeights,rate,profile?.reflectionCutoffScale ?: 1.0) }
        val reflectionGains=reflectionGains(geometry)
        val earlyGain=EARLY_GAIN*(profile?.roomPresence ?: 1.0)
        val surfaceGain=SURFACE_GAIN*(profile?.roomPresence ?: 1.0)
        val lateGain=LATE_GAIN*(profile?.roomPresence ?: 1.0)
        // Each region has one fixed radiation centre; moving the membrane cannot
        // accidentally move the pickup of the other three instruments' boundaries.
        val surfaceDistances=DoubleArray(10) { r ->
            val p=geometry.regionCenters[r]; val l=geometry.listener
            sqrt((p.x-l.x).toDouble().pow(2)+(p.y-l.y).toDouble().pow(2)+(p.z-l.z).toDouble().pow(2))
        }
        val surfaceTaps=Array(10) { r -> Tap(surfaceDistances[r]/343.0,rate) }
        val surfaceGains=DoubleArray(10) { r -> 1/surfaceDistances[r].coerceAtLeast(1.0) }
        val aquaticTaps=Array(10) { r -> Tap(.027+.0017*r,rate,.004) }
        val aquaticWeights=DoubleArray(10) { materials[it][4].toDouble() }
        val fluidSinA=DoubleArray(10) { sin(.7*it) }; val fluidCosA=DoubleArray(10) { cos(.7*it) }
        val fluidSinB=DoubleArray(10) { sin(1.3*it) }; val fluidCosB=DoubleArray(10) { cos(1.3*it) }
        val fluidStepSinA=DoubleArray(10) { sin(TAU*(.31+.017*it)/rate) }
        val fluidStepCosA=DoubleArray(10) { cos(TAU*(.31+.017*it)/rate) }
        val fluidStepSinB=sin(TAU*.47/rate); val fluidStepCosB=cos(TAU*.47/rate)
        val strike=prepareStrike(m,midi,velocity,hardness,configuration,rate,options.isolatedInstrument,profile)
        val strikeLengths=strike.lengths
        val sourceStrength=strike.strengths
        val sourceWorkBudget=strike.workBudget
        val strikeStarts=IntArray(4) { -1 }
        val strikeEnd=strikeLengths.max()-1
        val frames=(options.seconds*RATE).roundToInt()*options.oversample
        val raw=FloatArray(frames)
        val stemFrames=if(options.recordStems) frames else 0
        val rawDirect=FloatArray(stemFrames); val rawEarly=FloatArray(stemFrames)
        val rawSurface=FloatArray(stemFrames); val rawLate=FloatArray(stemFrames)
        val instrumentTaps=Array(4) { FloatArray(stemFrames) }; val surfaceStems=Array(10) { FloatArray(stemFrames) }
        val sourceWave=DoubleArray(4); val regionWave=DoubleArray(10)
        val fieldInputs=DoubleArray(18); val energySnapshots=ArrayList<EnergySnapshot>()
        val sourceIncoming=Array(4) { DoubleArray(10) }
        val boundaryIncoming=Array(4) { DoubleArray(10) }
        val fieldReturn=DoubleArray(10)
        var primaryWork=0.0; var maxEnergy=0.0; var previousEnergy=0.0; var maxUnforcedRise=0.0; var modalPeak=0.0
        var energyAtPrimaryEnd=0.0
        val preparationNanos=System.nanoTime()-preparationStart
        val synthStart=System.nanoTime()
        for(frame in 0 until frames) {
            if(frame and 8191==0) stopIfUnwanted(cancelled)
            // Read every delay's OLD causal state before any junction writes. This
            // matters to both the physical delays and the complete energy ledger.
            if(options.roomEnabled) {
                for(s in 0..3) for(r in 0..9) {
                    sourceIncoming[s][r]=returning[s][r].read()
                    boundaryIncoming[s][r]=outgoing[s][r].read()
                }
                for(r in 0..9) {
                    fieldInputs[r]=regionToField[r].read()
                    fieldReturn[r]=fieldToRegion[r].read()
                }
                for(i in 0..7) fieldInputs[10+i]=field[i].read()
            }
            var frameWork=0.0
            for(s in 0..3) {
                if(frame<strikeLengths[s] && sourceStrength[s]>0) {
                    // A finite positive half-sine contact, beginning at the shared
                    // timestamp. Pitch-aware widths preserve identifying upper modes.
                    val pulse=sin(PI*(frame+.5)/strikeLengths[s])
                    val delta=sourceStrength[s]*PI/(2*strikeLengths[s])*pulse
                    if(strikeStarts[s]<0) strikeStarts[s]=frame
                    frameWork += banks[s].strike(delta).coerceAtLeast(0.0)
                }
                val v=banks[s].port()
                var incoming=0.0
                if(options.roomEnabled) for(r in 0..9) incoming += sourceWeights[s][r]*sourceIncoming[s][r]
                val received=if(options.returnsEnabled && options.roomEnabled) incoming else 0.0
                banks[s].movePort((sourceC[s]-1)*v+sourceS[s]*received)
                val change=(sourceC[s]-1)*incoming-sourceS[s]*v
                if(options.roomEnabled) for(r in 0..9) {
                    val reflected=if(options.returnsEnabled) sourceIncoming[s][r] else 0.0
                    outgoing[s][r].put(reflected+sourceWeights[s][r]*(
                        if(options.returnsEnabled) change else -sourceS[s]*v))
                }
                banks[s].step()
                sourceWave[s]=banks[s].port()*sourceS[s]*observerGain*(when {
                    s==1 -> BAR_PRESSURE_CALIBRATION
                    s==0 && model>=3 -> MEMBRANE_PRESSURE_CALIBRATION*MEMBRANE_MIX_LIFT
                    s==0 && model==2 -> MEMBRANE_PRESSURE_CALIBRATION
                    else -> 1.0
                })
                if(options.recordStems) instrumentTaps[s][frame]=sourceWave[s].toFloat()
            }
            primaryWork += frameWork
            if(options.roomEnabled) {
                for(r in 0..9) {
                    var incoming=regionWeights[r][4]*fieldReturn[r]
                    for(s in 0..3) incoming += regionWeights[r][s]*boundaryIncoming[s][r]
                    val v=boundaries[r].port()
                    boundaries[r].movePort((boundaryC-1)*v+boundaryS*incoming)
                    val change=(boundaryC-1)*incoming-boundaryS*v
                    for(s in 0..3) returning[s][r].put(boundaryIncoming[s][r]+regionWeights[r][s]*change)
                    regionToField[r].put(fieldReturn[r]+regionWeights[r][4]*change)
                    boundaries[r].step()
                    regionWave[r]=boundaries[r].port()*boundaryS*observerGain
                }
                var dot=0.0
                for(r in 0..9) dot+=fieldInputs[r]
                for(i in 0..7) dot+=fieldInputs[10+i]
                // H = I - 2 uu' with u=(1,...,1)/sqrt(18).
                val correction=2*dot/18
                for(r in 0..9) fieldToRegion[r].put(fieldInputs[r]-correction)
                for(i in 0..7) field[i].put(fieldInputs[10+i]-correction)
            }
            var direct=0.0
            for(s in 0..3) direct += sourceTaps[s].tick(sourceWave[s])*directGains[s]
            direct *= DIRECT_GAIN
            var early=0.0; var surface=0.0; var late=0.0
            if(options.roomEnabled) {
                for(i in reflectionTaps.indices) {
                    early += reflections[i].tick(reflectionTaps[i].tick(sourceWave[reflectionSources[i]]))*reflectionGains[i]
                }
                early *= earlyGain
                for(r in 0..9) {
                    val stationary=surfaceTaps[r].tick(regionWave[r])
                    val aquatic=aquaticWeights[r]
                    val modulation=if(options.submergedModulationEnabled && aquatic>0.0)
                        .0018*(.65*fluidSinA[r]+.35*fluidSinB[r]) else 0.0
                    val secondary=if(aquatic>0.0) aquaticTaps[r].tick(stationary,.002+modulation) else 0.0
                    if(options.submergedModulationEnabled && aquatic>0.0) {
                        val sa=fluidSinA[r]; val ca=fluidCosA[r]
                        fluidSinA[r]=sa*fluidStepCosA[r]+ca*fluidStepSinA[r]
                        fluidCosA[r]=ca*fluidStepCosA[r]-sa*fluidStepSinA[r]
                        val sb=fluidSinB[r]; val cb=fluidCosB[r]
                        fluidSinB[r]=sb*fluidStepCosB+cb*fluidStepSinB
                        fluidCosB[r]=cb*fluidStepCosB-sb*fluidStepSinB
                    }
                    val output=(stationary*(1-.32*aquatic)+secondary*.58*aquatic)*
                        surfaceGain*SURFACE_PICKUP_CALIBRATION*surfaceGains[r]/sqrt(10.0)
                    if(options.recordStems) surfaceStems[r][frame]=output.toFloat()
                    surface+=output
                }
                for(i in 0..7) late += fieldInputs[10+i]*(if(i%2==0) 1 else -1)
                late *= observerGain*lateGain*LATE_PICKUP_CALIBRATION/sqrt(8.0)
            }
            if(options.recordStems) {
                rawDirect[frame]=direct.toFloat();rawEarly[frame]=early.toFloat()
                rawSurface[frame]=surface.toFloat();rawLate[frame]=late.toFloat()
            }
            raw[frame]=(direct+early+surface+late).toFloat()
            if(options.recordDiagnostics && (frame%256==0 || frame==strikeEnd || frame==frames-1)) {
                val ie=bankEnergy(banks);val se=bankEnergy(boundaries)
                val te=delayEnergy(allDelays)
                val total=ie+se+te
                if(frame==strikeEnd) energyAtPrimaryEnd=total
                maxEnergy=max(maxEnergy,total)
                if(frameWork==0.0 && frame>strikeLengths.max()) maxUnforcedRise=max(maxUnforcedRise,total-previousEnergy)
                previousEnergy=total
                for(b in banks) modalPeak=max(modalPeak,b.peak())
                for(b in boundaries) modalPeak=max(modalPeak,b.peak())
                if(frame%(rate/20)<256 || frame==strikeEnd || frame==frames-1)
                    energySnapshots += EnergySnapshot(frame.toDouble()/rate,ie,se,te,primaryWork)
            }
        }
        val synthesisNanos=System.nanoTime()-synthStart
        val outputStart=System.nanoTime()
        val finished=finish(raw,rate,cancelled)
        val direct=finish(rawDirect,rate,cancelled);val early=finish(rawEarly,rate,cancelled)
        val surface=finish(rawSurface,rate,cancelled);val late=finish(rawLate,rate,cancelled)
        val stems=finishStems(instrumentTaps,rate,cancelled);val surfaces=finishStems(surfaceStems,rate,cancelled)
        val samples=finished.copyOf()
        if(options.normalize && velocity>0f) Dsp.levelTo(samples,RATE,Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(samples)
        val finalEnergy=bankEnergy(banks)+bankEnergy(boundaries)+delayEnergy(allDelays)
        val finite=finalEnergy.isFinite() && modalPeak.isFinite() && finite(finished)
        stopIfUnwanted(cancelled)
        return Probe(samples,finished,stems,direct,early,surface,late,surfaces,arrivals(geometry),geometry,
            Diagnostics(primaryWork,maxEnergy,finalEnergy,maxUnforcedRise,1.0,.991,finite,modalPeak,
                energySnapshots,preparationNanos,synthesisNanos,System.nanoTime()-outputStart,
                modalCount(banks)+modalCount(boundaries),travelingSamples(allDelays),
                strikeStarts.filter { it>=0 }.map { it.toDouble()/rate },sourceWorkBudget,
                (strikeEnd+1).toDouble()/rate,energyAtPrimaryEnd),
            peak(finished))
    }

    internal fun probe(voice: ChamberVoice, macros: Map<String,Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = .75f,
        configuration: ChamberConfig = configFor(voice), seed: Long = DEFAULT_SEED,
        model: Int = MODEL_VERSION, options: ProbeOptions = ProbeOptions()) =
        renderProbe(voice,macros,midi,velocity,configuration,seed,model,options)

    fun render(voice: ChamberVoice, macros: Map<String,Float> = emptyMap(),
        midi: Int = DEFAULT_MIDI, velocity: Float = .75f,
        configuration: ChamberConfig = configFor(voice), seed: Long = DEFAULT_SEED,
        model: Int = MODEL_VERSION, cancelled: () -> Boolean = { false }): Snip = Snip(
        renderProbe(voice,macros,midi,velocity,configuration,seed,model,
            ProbeOptions(normalize=true,recordDiagnostics=false,recordStems=false),cancelled).samples,channels=1,sampleRate=RATE)
}
