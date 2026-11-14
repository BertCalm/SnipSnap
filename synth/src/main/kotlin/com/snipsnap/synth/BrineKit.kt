package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/**
 * BRINE source study, revision 2. Original SKIN performance, not reference samples.
 * Reference-informed balance and bandwidth; constants are audition shapes, not a fitted kit.
 * Source voicing stays separate from the spring/phaser/amp board and its saved settings.
 */
object BrineKit {
    const val VERSION = 2
    const val BPM = 100.0 // Keep R1's clock so comparisons isolate source and recording changes.
    enum class Character { BALANCED, WIRES, ROOM }
    data class Part(val id: String, val voice: SkinVoice, val gain: Float,
        val farDelay: Int, val farGain: Float, val macros: Map<String, Float>)
    data class Hit(val part: Int, val beat: Double, val velocity: Float)
    private fun part(id: String, voice: SkinVoice, gain: Float, delay: Int, far: Float,
        vararg changes: Pair<String, Float>) = Part(id, voice, gain, delay, far, Skin.defaults(voice) + changes.toMap())

    fun parts(character: Character = Character.BALANCED): List<Part> = listOf(
        part("kick", SkinVoice.KICK, .32f, 79, .16f, "TUNE" to .55f, "DECAY" to .4f,
            "TONE" to .72f, "DROP" to .12f, "CLICK" to .48f, "PUNCH" to .40f),
        part("snare", SkinVoice.SNARE, 1.25f, 123, .22f, "TUNE" to .58f, "DECAY" to .58f,
            "SNAP" to if (character == Character.WIRES) .94f else .86f,
            "RATTLE" to if (character == Character.WIRES) .79f else .70f,
            "SIZZLE" to .10f, "PUNCH" to .40f),
        part("hat", SkinVoice.HAT_CLOSED, .55f, 161, .18f, "TUNE" to .10f,
            "DECAY" to .52f, "TONE" to .20f, "RING" to .22f, "PUNCH" to .35f),
        part("tom", SkinVoice.TOM, .28f, 97, .20f, "TUNE" to .35f, "DECAY" to .40f,
            "TONE" to .65f, "CLICK" to .14f, "PUNCH" to .40f))

    val hits: List<Hit> = buildList {
        for (bar in 0..1) {
            val b = bar * 4.0
            for (t in listOf(0.0, 1.75, 2.5)) add(Hit(0, b+t, .9f))
            for (t in listOf(1.0, 3.0)) add(Hit(1, b+t, .88f))
            add(Hit(1, b+2.75, .30f))
            for (n in 0..7) add(Hit(2, b+n*.5, if (n%2==0) .72f else .48f))
        }
        add(Hit(3, 7.5, .7f))
    }.sortedBy { it.beat }

    /** Same events and velocity/take policy as R1; no groove change disguises the voicing change. */
    fun render(character: Character = Character.BALANCED, solo: Int? = null,
        recorded: Boolean = true): Snip {
        require(solo == null || solo in 0..3)
        val parts = parts(character)
        val out = FloatArray(((if (solo == null) 8*60/BPM+.6 else 1.4)*Dsp.RATE).roundToInt())
        val events = if (solo == null) hits else listOf(Hit(solo, 0.0, .88f))
        events.forEachIndexed { take, h ->
            val p = parts[h.part]
            val x = Skin.render(p.voice, p.macros, h.velocity, take).samples
            val near = Dsp.FixedOnePole(8000f)
            val far = Dsp.FixedOnePole(3800f)
            val at = (h.beat*60/BPM*Dsp.RATE).roundToInt()
            for (i in x.indices) {
                val a = near.lp(x[i]); val b = far.lp(x[i])
                val gain = p.gain*h.velocity*.45f
                if (at+i < out.size) out[at+i] += a*gain
                if (at+i+p.farDelay < out.size) out[at+i+p.farDelay] += b*gain*p.farGain
            }
        }
        return finish(out, character, recorded)
    }

    /** Shared recording path; expansion voices enter before the approved R2 bandwidth stage. */
    internal fun finish(out: FloatArray, character: Character = Character.BALANCED,
        recorded: Boolean = true): Snip {
        // Reference has virtually no power above 10 kHz. A four-pole acoustic bandwidth
        // replaces R1's weak one-pole rolloff without suppressing the snare's low-mid wires.
        Dsp.TptSvf(Dsp.RATE).lowPassInPlace(out, 6500f, .765367f)
        Dsp.TptSvf(Dsp.RATE).lowPassInPlace(out, 6500f, 1.847759f)
        if (!recorded) return Snip(out, 1, Dsp.RATE)
        // Early bleed is a shared room response, rather than random noise between hits.
        val room = out.copyOf()
        val mix = if (character == Character.ROOM) .24f else .09f
        for (i in out.indices) {
            if (i >= 617) room[i] += mix*out[i-617]
            if (i >= 1367) room[i] += mix*.65f*out[i-1367]
        }
        val compressed = Compressor.process(Snip(room, 1, Dsp.RATE),
            mapOf("AMOUNT" to .18f, "ATTACK" to .32f, "RECOVER" to .72f))
        // Reuse the recording pass only. Reference target is compared before the added pedals.
        val recordedSource = Brine.process(compressed, Brine.Settings(room = 0f, grit = .18f), Brine.Stage.RECORDING)
        // Broad reference-fit contour restores the missing shell/wire low mids. These rounded
        // values fit six-band balance and crest factor, not a measured microphone or circuit.
        // The high shelf prevents extra wire energy turning into modern bright cymbal hiss.
        val body = Dsp.Biquad().apply { peaking(540f, 14f, .8f) }
        val air = Dsp.Biquad().apply { highShelf(2000f, -6.2f) }
        val shaped = FloatArray(recordedSource.frameCount) { air.process(body.process(recordedSource.samples[it])) }
        val saturated = Brine.saturate(shaped, 6.6f)
        val dc = Dsp.FixedOnePole(20f)
        for (i in saturated.indices) saturated[i] -= dc.lp(saturated[i])
        Dsp.fadeTailCosine(saturated, .08f)
        Dsp.limitPeak(saturated)
        return Snip(saturated, 1, Dsp.RATE)
    }
}
