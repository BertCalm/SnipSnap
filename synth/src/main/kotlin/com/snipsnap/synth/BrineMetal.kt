package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** R6: short impacts excite persistent inharmonic modes; no noise drives the sustain.
 * Frequencies/decays are audition shapes, not measured cymbal modes or a physical plate solver.
 */
object BrineMetal {
    const val VERSION = 6
    const val CHOKE_SECONDS = .23
    const val CHOKE_RELEASE_SECONDS = .025
    data class Partial(val hz: Float, val weight: Float, val t60: Float)
    val rideModes: List<Partial> = buildList {
        val centers = listOf(735f, 983f, 1368f, 1791f, 2270f, 2847f, 3495f, 4231f, 5050f, 6017f, 7151f)
        for ((i, f) in centers.withIndex()) for ((j, detune) in listOf(-.008f, .006f, .017f).withIndex())
            add(Partial(f*(1f+detune), (1f/(i+1).toFloat().pow(.6f)) * if (j==1) 1f else .55f,
                2.5f-i*.13f-j*.035f))
        add(Partial(1640f, .40f, 2.8f))
    }
    val crashModes: List<Partial> = (0..55).map { i ->
        Partial(650f + i*85f + i*i*2.3f, 1f/(i+1).toFloat().pow(.25f), 1.8f-i*.018f)
    }
    private val rideSource by lazy { strike(rideModes, .0012, "RIDE") }
    private val crashSource by lazy { strike(crashModes, .004, "CRASH") }
    private val chokedSource by lazy {
        crashSource.copyOf().also { x ->
            val at = (CHOKE_SECONDS*Dsp.RATE).roundToInt()
            val length = (CHOKE_RELEASE_SECONDS*Dsp.RATE).roundToInt()
            for (i in at until x.size) {
                val p = ((i-at).toDouble()/length).coerceIn(0.0,1.0)
                x[i] *= (.5*(1+cos(PI*p))).toFloat()
            }
        }
    }

    private fun strike(partials: List<Partial>, contactSeconds: Double, tag: String): FloatArray {
        val rate = Dsp.RATE*Dsp.OVERSAMPLE
        val excitation = FloatArray((3.2*rate).roundToInt())
        val count = (contactSeconds*rate).roundToInt()
        val noise = Dsp.Noise(Dsp.seedFor("BRINE", "METAL", VERSION, tag))
        for (i in 0 until count) {
            val p = i.toDouble()/count
            excitation[i] = (noise.next()*sin(PI*p)*exp(-4*p)).toFloat()
        }
        excitation[0] += .7f
        // Modes.ring's impulse response has a 1/sin(theta) amplitude factor. Compensate it
        // so the authored weights describe the ring, not an unintended low-frequency boost.
        val modes = partials.map { p -> Modes.Mode(p.hz,
            // Counter the dark shared recording's strong low-mid emphasis. This is a
            // voicing curve, not a physical cymbal law: retain shimmer above the body.
            p.weight*(p.hz/1600f).pow(2.4f).coerceAtMost(12f)*sin(2*PI*p.hz/rate).toFloat(), p.t60) }
        val raw = Modes.ring(excitation, 1f, modes, rate)
        Tide.bandLimit(raw, rate)
        val out = Dsp.decimate(raw, Dsp.RATE)
        Dsp.normalize(out, .90f)
        val dc = Dsp.FixedOnePole(20f)
        for (i in out.indices) out[i] -= dc.lp(out[i])
        Dsp.fadeTailCosine(out, .08f)
        Dsp.limitPeak(out)
        return out
    }

    /** Cached immutable source templates; callers receive copies before choking/mixing. */
    internal fun source(v: BrineCymbals.Voice): FloatArray = when (v) {
        BrineCymbals.Voice.RIDE -> rideSource.copyOf()
        BrineCymbals.Voice.CRASH -> crashSource.copyOf()
        BrineCymbals.Voice.CHOKED_CRASH -> chokedSource.copyOf()
        BrineCymbals.Voice.HALF_HAT -> BrineCymbals.source(v)
    }
    fun revised(e: BrineKitExpansion.Event): BrineKitExpansion.Event =
        if (e.cymbal == null || e.cymbal == BrineCymbals.Voice.HALF_HAT) e else
            e.copy(part = e.part.copy(gain = if (e.cymbal == BrineCymbals.Voice.RIDE) .18f else .25f), metal = true)

    fun render(v: BrineCymbals.Voice): Snip =
        BrineKitExpansion.renderEvents(listOf(revised(BrineCymbals.event(v, 0.0))), 3.4)

    fun renderGroove(style: BrineCymbals.Groove): Snip =
        BrineKitExpansion.renderEvents(BrineCymbals.events(style).map(::revised), 7.6)
}
