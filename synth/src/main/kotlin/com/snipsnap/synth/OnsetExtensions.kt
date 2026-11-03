package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Model-four sources from the Study009 audition: membrane tom, released string,
 * damped wood and ringing metal. Voice identity selects a model; a patch name never does.
 *
 * Why these stages remain separate:
 * - Fixed source gains preserve strike energy; neither source nor hit is normalized.
 * - Tom/metal retain their bounded interior AM; pluck/wood hand an electronic attack
 *   into a material body, so the accepted contact does not acquire a new clang.
 * - The source snapshot is Float, but model arithmetic is Double until the original
 *   Float write. Reordering that boundary changes the audition's PCM24 samples.
 * - This finish has no PUNCH or limiter. Reusing the original drum finish would revoice
 *   these already auditioned hits. The public Onset entry point still owns version checks.
 *
 * Sources: separate audition adapter at SnipSnap c9807eba, Study009 source revision 3
 * for pluck/wood and retained source revision 2 for tom/metal. The deterministic seed
 * family/version stays frozen independently of the native model version.
 */
internal object OnsetExtensions {
    private const val RATE = Dsp.RATE
    private const val IR = RATE * Dsp.OVERSAMPLE
    private const val TAU = 2.0 * PI
    private const val SEED_VERSION = 2
    private val ln1000 = Math.log(1000.0)

    fun supports(voice: OnsetVoice): Boolean = when (voice) {
        OnsetVoice.TOM, OnsetVoice.PERC_PLUCK, OnsetVoice.PERC_WOOD, OnsetVoice.PERC_METAL -> true
        else -> false
    }

    fun macrosFor(voice: OnsetVoice): List<MacroSpec> {
        val values = when (voice) {
            OnsetVoice.TOM -> floatArrayOf(0.47057701472336333f, .46f, .5f, .44f, .58f, .44f)
            OnsetVoice.PERC_PLUCK -> floatArrayOf(0.31739380551401475f, .28f, .5f, .32f, .24f, .34f)
            OnsetVoice.PERC_WOOD -> floatArrayOf(0.21166135567408279f, .18f, .5f, .40f, .18f, .34f)
            OnsetVoice.PERC_METAL -> floatArrayOf(0.7625662524497593f, .72f, .5f, .48f, .86f, .74f)
            else -> error("$voice is not an ONSET extension")
        }
        return listOf("TUNE", "DECAY", "BLEND", "SNAP", "BODY", "EDGE").mapIndexed { index, name ->
            if (name == "TUNE") MacroSpec(name, values[index], .5f) else MacroSpec(name, values[index])
        }
    }

    private fun root(voice: OnsetVoice, m: Map<String, Float>): Double = when (voice) {
        OnsetVoice.TOM -> exponential(m.getValue("TUNE").toDouble(), 70.0, 240.0)
        OnsetVoice.PERC_PLUCK -> exponential(m.getValue("TUNE").toDouble(), 180.0, 900.0)
        OnsetVoice.PERC_WOOD, OnsetVoice.PERC_METAL -> exponential(m.getValue("TUNE").toDouble(), 250.0, 1400.0)
        else -> error("$voice is not an ONSET extension")
    }

    private fun t60(voice: OnsetVoice, m: Map<String, Float>): Double = when (voice) {
        OnsetVoice.TOM -> exponential(m.getValue("DECAY").toDouble(), .16, .75)
        OnsetVoice.PERC_PLUCK -> exponential(m.getValue("DECAY").toDouble(), .08, .65)
        OnsetVoice.PERC_WOOD, OnsetVoice.PERC_METAL -> exponential(m.getValue("DECAY").toDouble(), .10, .85)
        else -> error("$voice is not an ONSET extension")
    }

    fun settingsFor(voice: OnsetVoice, macros: Map<String, Float>, modelVersion: Int): OnsetSettings {
        val hz = root(voice, macros).toFloat()
        val natural = voice == OnsetVoice.PERC_PLUCK || voice == OnsetVoice.PERC_WOOD
        return OnsetSettings(
            modelVersion, voice, macros, hz, 0f, hz, hz, t60(voice, macros).toFloat(),
            electronicGain = 1f,
            physicalGain = if (voice == OnsetVoice.PERC_METAL) .85f else 1f,
            outputGain = when (voice) {
                OnsetVoice.TOM -> .25f
                OnsetVoice.PERC_PLUCK, OnsetVoice.PERC_METAL -> .28f
                OnsetVoice.PERC_WOOD -> .30f
                else -> error("$voice is not an ONSET extension")
            },
            electronicSource = "ONSET",
            physicalSource = if (voice == OnsetVoice.PERC_PLUCK) "STRINGS" else "MODES",
            electronicModel = when (voice) {
                OnsetVoice.TOM -> "Clean swept oscillator; bounded root-carrier AM in the middle"
                OnsetVoice.PERC_PLUCK -> "Harmonic pulse pluck; electronic attack into released-string body"
                OnsetVoice.PERC_WOOD -> "Electronic block tap; attack into damped wooden body"
                OnsetVoice.PERC_METAL -> "Harmonic FM strike; bounded root-carrier AM in the middle"
                else -> error("$voice is not an ONSET extension")
            },
            physicalModel = when (voice) {
                OnsetVoice.TOM -> "Six contact-projected circular-membrane modes"
                OnsetVoice.PERC_PLUCK -> "Triangular string release; tuned passive loop with frequency-dependent loss"
                OnsetVoice.PERC_WOOD -> "WOOD_XYLO 1/3/6 modes with short upper losses"
                OnsetVoice.PERC_METAL -> "Wood-to-metal bar modes with independent losses"
                else -> error("$voice is not an ONSET extension")
            } + if (natural) "; bounded attack/body handoff" else "",
        )
    }

    private class Sources(val settings: OnsetSettings) {
        val voice = settings.voice
        val macros = settings.macros
        val root = root(voice, macros)
        val t60 = t60(voice, macros)
        val size = ceil(t60 * 1.5 * IR).toInt()
        val electronic = FloatArray(size)
        var material = FloatArray(size)
        val materialFundamental = FloatArray(size)
        val transient = FloatArray(size)
        val rootCarrier = FloatArray(size)
        var materialGain = 1.0
        var outputGain = .25
    }

    private fun sources(settings: OnsetSettings): Sources {
        val p = Sources(settings)
        val snap = settings.macros.getValue("SNAP").toDouble()
        val edge = settings.macros.getValue("EDGE").toDouble()
        // Both native wood and metal retain the original PERC_MODAL seed family.
        val family = when (settings.voice) {
            OnsetVoice.TOM -> "TOM"
            OnsetVoice.PERC_PLUCK -> "PERC_PLUCK"
            else -> "PERC_MODAL"
        }
        val noise = Dsp.Noise(Dsp.seedFor("ONSET_EXTENSION", SEED_VERSION, family, "SHARED_STRIKE"))
        val lp = Dsp.OnePole(IR)
        val strikeSeconds = if (settings.voice == OnsetVoice.TOM) .0022 else .0011
        for (i in 0 until minOf(p.size, (strikeSeconds * IR).toInt())) {
            val t = i.toDouble() / IR
            val envelope = Math.pow(sin(PI * t / strikeSeconds), 2.0)
            p.transient[i] = (lp.lp(noise.next(), linear(edge, 1800.0, 7000.0).toFloat()).toDouble() * snap * .085 * envelope).toFloat()
        }
        when (settings.voice) {
            OnsetVoice.TOM -> tom(p)
            OnsetVoice.PERC_PLUCK -> pluck(p)
            OnsetVoice.PERC_WOOD -> wood(p)
            OnsetVoice.PERC_METAL -> metal(p)
            else -> error("${settings.voice} is not an ONSET extension")
        }
        return p
    }

    private fun tom(p: Sources) {
        val snap = p.macros.getValue("SNAP").toDouble()
        val body = p.macros.getValue("BODY").toDouble()
        val edge = p.macros.getValue("EDGE").toDouble()
        val zeros = doubleArrayOf(2.4048255577, 3.8317059702, 5.1356223018, 5.5200781103, 7.0155866698, 8.4172441404)
        val orders = intArrayOf(0, 1, 2, 0, 1, 2)
        val strengths = doubleArrayOf(.43, .66, .53, .39, .28, .21)
        val losses = doubleArrayOf(1.0, .95, .90, .85, .80, .75)
        val levels = DoubleArray(6)
        val phases = DoubleArray(6)
        val position = linear(body, .52, .25)
        val radius = linear(edge, .105, .026)
        for (k in levels.indices) {
            if (k == 0) levels[k] = strengths[k] else {
                val reference = bessel(orders[k], zeros[k] * .376) * cos(orders[k] * .18) * exp(-.5 * zeros[k] * zeros[k] * .065 * .065)
                val contact = bessel(orders[k], zeros[k] * position) * cos(orders[k] * .18) * exp(-.5 * zeros[k] * zeros[k] * radius * radius)
                levels[k] = strengths[k] * Math.max(-1.4, Math.min(1.4, contact / reference)) * linear(edge, .65, 1.25)
            }
        }
        for (i in 0 until p.size) {
            val t = i.toDouble() / IR
            val bend = 1 + snap * .50 * exp(-55 * t)
            val attack = attack(t, .0008)
            val envelope = exp(-ln1000 * t / p.t60) * attack
            phases[0] += p.root * bend / IR
            val phase = TAU * phases[0]
            val fundamental = .70 * sin(phase) * envelope
            p.rootCarrier[i] = sin(phase).toFloat()
            p.electronic[i] = (fundamental + (linear(body, .018, .07) * sin(2 * phase) + edge * .025 * sin(3 * phase)) * exp(-ln1000 * t / (p.t60 * .35)) * attack).toFloat()
            var sum = levels[0] * sin(phase) * envelope
            p.materialFundamental[i] = sum.toFloat()
            for (k in 1 until 6) {
                phases[k] += p.root * (zeros[k] / zeros[0]) * bend / IR
                sum += levels[k] * sin(TAU * phases[k]) * exp(-ln1000 * t / (p.t60 * losses[k])) * attack
            }
            p.material[i] = sum.toFloat()
        }
    }

    private fun pluck(p: Sources) {
        val snap = p.macros.getValue("SNAP").toDouble()
        val body = p.macros.getValue("BODY").toDouble()
        val edge = p.macros.getValue("EDGE").toDouble()
        val position = linear(body, .16, .38)
        val pickHz = Math.min(6000.0, p.root * linear(edge, 4.0, 9.0))
        // Lowering the loop cutoff costs root energy too. Pay that magnitude loss as
        // well as Strings.tune's phase delay, while refusing active feedback.
        val loopHz = Math.min(32000.0, p.root * (3.5 + .25 * sqrt(p.root * p.t60)) * linear(edge, .75, 1.4))
        val a = 1 - exp(-TAU * loopHz / IR)
        val w = TAU * p.root / IR
        val lowMagnitude = a / sqrt(1 + (1 - a) * (1 - a) - 2 * (1 - a) * cos(w))
        val averageMagnitude = cos(w / 2)
        val feedback = exp(-ln1000 / (p.root * p.t60)) / (lowMagnitude * averageMagnitude)
        check(feedback > 0 && feedback < .9995) { "ONSET string needs passive feedback: $feedback" }
        val tuning = Strings.tune(p.root.toFloat(), loopHz.toFloat(), IR, 0f, 0f, null, false, 2f, 1.0)
        val noise = Strings.pluckExciter(tuning.n, p.root.toFloat(), pickHz.toFloat(), position.toFloat(), Dsp.seedFor("ONSET_EXTENSION", SEED_VERSION, "PERC_PLUCK", "PICK"), IR, p.size)
        val excitation = FloatArray(minOf(p.size, tuning.n))
        val contact = Dsp.OnePole(IR)
        var mean = 0.0
        for (i in excitation.indices) {
            val phase = (i + .5) / excitation.size
            val triangle = if (phase < position) phase / position else (1 - phase) / (1 - position)
            val input = (.55 * (2 * triangle - 1) + noise.getOrElse(i) { 0f }.toDouble() * linear(edge, .025, .055)).toFloat()
            excitation[i] = contact.lp(input, pickHz.toFloat())
            mean += excitation[i]
        }
        mean /= excitation.size
        for (i in excitation.indices) excitation[i] = ((excitation[i] - mean) * linear(snap, .65, 1.25)).toFloat()
        val loop = Strings.Loop(tuning.n, tuning.a, feedback.toFloat(), loopHz.toFloat(), IR, 0f, 0f, 1e-6f, null, false, 2f, 1.0)
        for (i in 0 until p.size) p.material[i] = loop.next(if (i < excitation.size) excitation[i] else 0f)
        // Align phase to the actual released string, never fit a source gain.
        var aa = 0.0
        var bb = 0.0
        val first = (.025 * IR).toInt()
        val last = minOf(p.size, (.12 * IR).toInt())
        for (i in first until last) {
            val angle = TAU * p.root * i / IR
            aa += p.material[i] * sin(angle)
            bb += p.material[i] * cos(angle)
        }
        val phaseOffset = Math.atan2(bb, aa)
        val amplitude = hypot(aa, bb) * 2 / (last - first)
        val amplitudeAtZero = amplitude * exp(ln1000 * (first + last) / 2.0 / IR / p.t60)
        for (i in 0 until p.size) {
            val t = i.toDouble() / IR
            val phase = TAU * p.root * t + phaseOffset
            val front = attack(t, linear(snap, .0013, .00035))
            val envelope = exp(-ln1000 * t / p.t60) * linear(snap, .65, 1.25) * front
            val fundamental = linear(body, .44, .52) * sin(phase)
            val upper = (.18 * sin(2 * phase) + .11 * sin(3 * phase) + .07 * sin(4 * phase) + .04 * sin(5 * phase)) * exp(-t / linear(edge, .004, .016))
            p.electronic[i] = ((fundamental + upper) * envelope).toFloat()
            p.materialFundamental[i] = (amplitudeAtZero * sin(phase) * exp(-ln1000 * t / p.t60)).toFloat()
            p.rootCarrier[i] = sin(phase).toFloat()
        }
        p.outputGain = .28
    }

    private fun wood(p: Sources) {
        val snap = p.macros.getValue("SNAP").toDouble()
        val body = p.macros.getValue("BODY").toDouble()
        val edge = p.macros.getValue("EDGE").toDouble()
        val wood = Modes.tableFor(Modes.Material.WOOD_XYLO)
        val contactSeconds = linear(snap, .24, .10) / p.root
        val count = maxOf(4, Math.round(contactSeconds * IR).toInt())
        val excitation = FloatArray(p.size)
        var area = 0.0
        for (i in 0 until minOf(p.size, count)) {
            excitation[i] = Math.pow(sin(PI * (i + .5) / count), 2.0).toFloat()
            area += excitation[i]
        }
        for (i in 0 until minOf(p.size, count)) excitation[i] = (excitation[i] / area * linear(snap, .65, 1.25)).toFloat()
        val weights = doubleArrayOf(linear(body, .54, .66), linear(body, .28, .46) * linear(edge, .7, 1.1), .13 * linear(edge, .5, 1.2))
        val losses = doubleArrayOf(.78, linear(body, .12, .24), linear(body, .06, .10))
        val modes = (0 until 3).map { k ->
            val ratio = wood[k].ratio.toDouble()
            val hz = p.root * ratio
            check(hz < RATE * .47)
            // Modes.ring has 1/sin(theta) impulse gain; cancel it analytically.
            Modes.Mode(ratio.toFloat(), (weights[k] * sin(TAU * hz / IR)).toFloat(), (p.t60 * losses[k]).toFloat(), .5f)
        }
        p.material = Modes.ring(excitation, p.root.toFloat(), modes, IR)
        Modes.ring(excitation, p.root.toFloat(), listOf(modes[0]), IR).copyInto(p.materialFundamental)
        for (i in 0 until p.size) {
            val t = i.toDouble() / IR
            val phase = TAU * p.root * t
            val front = attack(t, linear(snap, .0009, .00025))
            val envelope = exp(-ln1000 * t / (p.t60 * .68)) * linear(snap, .65, 1.25) * front
            val fundamental = linear(body, .44, .52) * sin(phase)
            val upper = (.24 * sin(2 * phase) + .12 * sin(3 * phase)) * exp(-t / linear(edge, .0015, .005))
            p.electronic[i] = ((fundamental + upper) * envelope).toFloat()
            p.rootCarrier[i] = sin(phase).toFloat()
        }
        p.transient.fill(0f)
        val noise = Dsp.Noise(Dsp.seedFor("ONSET_EXTENSION", SEED_VERSION, "WOOD_CONTACT"))
        val lp = Dsp.OnePole(IR)
        val seconds = linear(snap, .0015, .0007)
        for (i in 0 until minOf(p.size, (seconds * IR).toInt())) {
            val t = i.toDouble() / IR
            val envelope = Math.pow(sin(PI * t / seconds), 2.0)
            p.transient[i] = (lp.lp(noise.next(), (p.root * linear(edge, 4.0, 8.0)).toFloat()).toDouble() * linear(snap, .05, .15) * envelope).toFloat()
        }
        p.outputGain = .30
    }

    private fun metal(p: Sources) {
        val snap = p.macros.getValue("SNAP").toDouble()
        val body = p.macros.getValue("BODY").toDouble()
        val edge = p.macros.getValue("EDGE").toDouble()
        val wood = Modes.tableFor(Modes.Material.WOOD_XYLO)
        val metal = Modes.tableFor(Modes.Material.METAL_BAR)
        val ratios = doubleArrayOf(1.0, linear(body, wood[1].ratio.toDouble(), metal[1].ratio.toDouble()), linear(body, wood[2].ratio.toDouble(), metal[2].ratio.toDouble()), metal[3].ratio.toDouble())
        val weights = doubleArrayOf(.48, .82 * linear(edge, .70, 1.0), .60 * linear(edge, .65, 1.0), .42 * body * linear(edge, .6, 1.0))
        val contactCycles = linear(edge, .11, .025) * linear(snap, 1.3, .7)
        val count = maxOf(4, Math.round(contactCycles * IR / p.root).toInt())
        val excitation = FloatArray(p.size)
        var area = 0.0
        for (i in 0 until count) {
            excitation[i] = Math.pow(sin(PI * (i + .5) / count), 2.0).toFloat()
            area += excitation[i]
        }
        for (i in 0 until count) excitation[i] = (excitation[i] / area * linear(snap, .65, 1.35)).toFloat()
        val modes = (0 until 4).map { k ->
            val hz = p.root * ratios[k]
            check(hz < RATE * .47)
            val modeT60 = p.t60 * if (k == 0) 1.0 else Math.pow(linear(body, .72, .96), k * .55)
            Modes.Mode(ratios[k].toFloat(), (weights[k] * sin(TAU * hz / IR)).toFloat(), modeT60.toFloat(), .5f)
        }
        p.material = Modes.ring(excitation, p.root.toFloat(), modes, IR)
        Modes.ring(excitation, p.root.toFloat(), listOf(modes[0]), IR).copyInto(p.materialFundamental)
        for (i in 0 until p.size) {
            val t = i.toDouble() / IR
            val phase = TAU * p.root * t
            val envelope = exp(-ln1000 * t / p.t60) * attack(t, linear(snap, .0016, .0004)) * linear(snap, .65, 1.35)
            val index = linear(edge, .8, 1.6) * linear(snap, .75, 1.2) * exp(-ln1000 * t / (p.t60 * linear(body, .5, .9)))
            val carrier = linear(body, .12, .20) * sin(phase) * envelope
            p.electronic[i] = (carrier + .60 * sin(phase + index * sin(2 * phase)) * envelope).toFloat()
            p.rootCarrier[i] = sin(phase).toFloat()
        }
        p.materialGain = .85
        p.outputGain = .28
    }

    private data class Pass(val probe: Onset.Probe, val conditioned: FloatArray)

    private fun process(settings: OnsetSettings): Pass {
        val p = sources(settings)
        val blend = settings.macros.getValue("BLEND").toDouble()
        val body = settings.macros.getValue("BODY").toDouble()
        val x = if (blend == 1.0) 0.0 else cos(blend * PI / 2)
        val o = if (blend == 0.0) 0.0 else sin(blend * PI / 2)
        val hybrid = if (blend == 0.0 || blend == 1.0) 0.0 else Math.pow(sin(PI * blend), 2.0)
        val natural = settings.voice == OnsetVoice.PERC_PLUCK || settings.voice == OnsetVoice.PERC_WOOD
        val mixed = FloatArray(p.size)
        val conditioned = FloatArray(p.size)
        val electronicWeight = FloatArray(p.size)
        val physicalWeight = FloatArray(p.size)
        val shelf = Dsp.OnePole(IR)
        for (i in 0 until p.size) {
            val v: Double
            if (natural) {
                val t = i.toDouble() / IR
                val eScale = 1 - .40 * hybrid * (1 - exp(-t / (.006 + .010 * body)))
                val mScale = 1 - .70 * hybrid * exp(-t / (.002 + .003 * body))
                electronicWeight[i] = (x * eScale).toFloat()
                physicalWeight[i] = (o * mScale).toFloat()
                val e = p.electronic[i] * 1.0 * x * eScale
                val material = p.material[i] * p.materialGain * o * mScale
                v = e + material + p.transient[i]
            } else {
                var material = p.material[i].toDouble()
                var factor = 1.0
                if (hybrid > 0) {
                    factor = 1 - .85 * hybrid + .85 * hybrid * p.rootCarrier[i]
                    material *= factor
                }
                electronicWeight[i] = x.toFloat()
                physicalWeight[i] = (o * factor).toFloat()
                // The explicit *1 terms retain the audition's Double operation order.
                v = p.electronic[i] * 1.0 * x * 1.0 + material * p.materialGain * o + p.transient[i]
            }
            mixed[i] = v.toFloat()
            // Do not replace v by mixed[i]: that adds a premature Float rounding step.
            // The audition multiplied these two Floats before promoting to Double.
            val shelfBody = shelf.lp(v.toFloat(), (p.root * 2).toFloat()) * settings.macros.getValue("BODY")
            conditioned[i] = ((v + shelfBody.toDouble() * .12) * p.outputGain).toFloat()
            check(conditioned[i].isFinite()) { "non-finite ONSET extension sample" }
        }
        val electronic = p.electronic.copyOf()
        val physical = FloatArray(p.size) { (p.material[it] * p.materialGain).toFloat() }
        val fundamental = FloatArray(p.size) { (p.materialFundamental[it] * p.materialGain).toFloat() }
        return Pass(Onset.Probe(settings, electronic, physical, p.transient, mixed, fundamental, null, electronicWeight, physicalWeight), conditioned)
    }

    fun probe(settings: OnsetSettings): Onset.Probe = process(settings).probe

    fun render(settings: OnsetSettings): Snip {
        val raw = process(settings).conditioned
        Tide.bandLimit(raw, IR)
        val out = Dsp.decimate(raw, RATE, 1)
        var mean = 0.0
        for (sample in out) mean += sample
        mean /= out.size
        val dc = Dsp.OnePole(RATE)
        for (i in out.indices) {
            val v = (out[i] - mean).toFloat()
            out[i] = v - dc.lp(v, 20f)
        }
        for (i in 0 until minOf(out.size, (.0007 * RATE).toInt())) {
            out[i] *= Math.pow(sin(PI * .5 * i / (.0007 * RATE)), 2.0).toFloat()
        }
        Dsp.fadeTail(out, 4f, RATE, 1)
        check(out.all { it.isFinite() && abs(it) < .99f }) { "ONSET extension exhausted fixed headroom" }
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    private fun attack(t: Double, seconds: Double): Double = Math.min(1.0, t / seconds)
    private fun linear(value: Double, low: Double, high: Double): Double = low + (high - low) * value
    private fun exponential(value: Double, low: Double, high: Double): Double = low * Math.pow(high / low, value)

    private fun bessel(order: Int, x: Double): Double {
        var term = Math.pow(x / 2, order.toDouble())
        for (j in 2..order) term /= j
        var sum = term
        for (k in 1..40) {
            term *= -(x * x / 4) / (k * (k + order))
            sum += term
            if (abs(term) < 1e-14) break
        }
        return sum
    }
}
