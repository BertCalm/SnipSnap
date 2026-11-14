package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** R9 candidates: stop the shell/wire contour from dominating metal, before shared saturation.
 * Reference-derived sources remain R7. No new physical-model or listening claim.
 */
object BrineCymbalReset {
    enum class Variant(val label: String) {
        CURRENT("Current cymbal"), LEAN("Less low-mid body"), FOCUSED("Less body + shorter wash"), RING("Shorter wash + more ride ring")
    }
    val voices = listOf(BrineCymbals.Voice.RIDE,BrineCymbals.Voice.CRASH,BrineCymbals.Voice.CHOKED_CRASH)
    fun tailSeconds(v: BrineCymbals.Voice): Float = when(v) {
        BrineCymbals.Voice.RIDE -> 1.8f
        BrineCymbals.Voice.CRASH -> 3f
        else -> 0f // The hand choke already supplies its own finite envelope.
    }
    internal fun source(v: BrineCymbals.Voice, variant: Variant, take: Int = 0): FloatArray {
        require(v in voices && take in 0..1)
        val x=if(v==BrineCymbals.Voice.RIDE && take>0) BrineReferenceCymbals.rideSource(1f,take) else BrineReferenceCymbals.source(v)
        if(variant==Variant.CURRENT) return x
        // Counter the broad +14 dB drum-body contour before it drives bus saturation.
        // Two gentle high passes remove rumble without adding a bright noise exciter.
        val body=Dsp.Biquad().apply {peaking(540f,-14f,.8f)}
        val hp1=Dsp.FixedOnePole(240f);val hp2=Dsp.FixedOnePole(240f)
        val t60=if(v==BrineCymbals.Voice.RIDE && variant==Variant.RING) 3.2f else tailSeconds(v)
        for(i in x.indices) {
            var a=body.process(x[i]);a-=hp1.lp(a);a-=hp2.lp(a)
            val t=i.toDouble()/Dsp.RATE
            // R10 relaxes only ride damping; the low-mid contour and crash envelope stay fixed.
            // Preserve the first 35 ms. Exponential damping stays continuous, never a gate.
            val damping=if((variant==Variant.FOCUSED || variant==Variant.RING) && t60>0) exp(-ln(1000.0)*max(0.0,t-.035)/t60) else 1.0
            x[i]=(a*damping).toFloat()
        }
        Dsp.fadeTailCosine(x,.05f)
        // No peak normalization: it would restore the energy deliberately removed above.
        return x
    }
    private fun render(events: List<BrineKitExpansion.Event>,variant: Variant,seconds: Double): Snip {
        return BrineKitExpansion.renderEvents(events,seconds,sourceOverride={ e ->
            e.cymbal?.takeIf {it in voices}?.let {source(it,variant)}
        })
    }
    fun hit(v: BrineCymbals.Voice,variant: Variant): Snip = render(
        listOf(BrineMetal.revised(BrineCymbals.event(v,0.0))),variant,8.6)
    /** Selected ride contour/decay at two phase realizations; velocity drives the recording bus.
     * No old R8 darkening filter is reapplied to the listener-selected R10 timbre.
     */
    fun playedRide(velocity: Float, take: Int): Snip {
        require(velocity in 0f..1f && take in 0..1)
        val e=BrineMetal.revised(BrineCymbals.event(BrineCymbals.Voice.RIDE,0.0,velocity))
        return BrineKitExpansion.renderEvents(listOf(e),8.6,sourceOverride={
            source(BrineCymbals.Voice.RIDE,Variant.RING,take)
        })
    }
    fun groove(ride: Boolean,variant: Variant): Snip {
        val events=BrineCymbals.events(if(ride) BrineCymbals.Groove.RIDE else BrineCymbals.Groove.HATS)
            .filter { !ride || it.cymbal !in listOf(BrineCymbals.Voice.CRASH,BrineCymbals.Voice.CHOKED_CRASH) }
            .map(BrineMetal::revised)
        return render(events,variant,9.0)
    }
}
