package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** R3 candidate articulations over the approved R2 recording, with no reference samples. */
object BrineKitExpansion {
    const val VERSION = 3
    enum class Voice(val label: String) {
        OPEN_HAT("Open hat"), FLOOR_TOM("Floor tom"), CROSS_STICK("Cross-stick"),
        GHOST_SNARE("Ghost snare"), RIMSHOT("Rimshot")
    }
    data class Articulation(val part: BrineKit.Part, val velocity: Float = .88f, val rimMix: Float = 0f)
    private fun part(id: String, voice: SkinVoice, gain: Float, delay: Int, far: Float,
        vararg macros: Pair<String, Float>) = BrineKit.Part(id, voice, gain, delay, far, Skin.defaults(voice) + macros.toMap())

    fun articulation(voice: Voice): Articulation = when (voice) {
        Voice.OPEN_HAT -> Articulation(part("open-hat", SkinVoice.HAT_OPEN, .48f, 161, .18f,
            "TUNE" to .10f, "DECAY" to .50f, "TONE" to .20f, "RING" to .22f, "PUNCH" to .35f))
        Voice.FLOOR_TOM -> Articulation(part("floor-tom", SkinVoice.TOM, .34f, 97, .20f,
            "TUNE" to .12f, "DECAY" to .58f, "TONE" to .58f, "CLICK" to .12f,
            "DROP" to .08f, "PUNCH" to .40f))
        Voice.CROSS_STICK -> Articulation(part("cross-stick", SkinVoice.STICK, .60f, 123, .22f,
            "TUNE" to .22f, "DECAY" to .38f, "BODY" to .58f, "CLICK" to .12f, "PUNCH" to .30f))
        Voice.GHOST_SNARE -> Articulation(BrineKit.parts()[1].copy(id = "ghost-snare"), .30f)
        Voice.RIMSHOT -> Articulation(BrineKit.parts()[1].copy(id = "rimshot"), .98f, .32f)
    }

    data class Event(val part: BrineKit.Part, val beat: Double, val velocity: Float, val rimMix: Float = 0f,
        val cymbal: BrineCymbals.Voice? = null, val metal: Boolean = false)
    private fun event(v: Voice, beat: Double): Event = articulation(v).let { Event(it.part, beat, it.velocity, it.rimMix) }

    /** Same tempo and two-bar frame; new articulations replace hits rather than just piling on. */
    fun groove(): List<Event> = buildList {
        val p = BrineKit.parts()
        for (h in BrineKit.hits) {
            if (h.part == 2 && h.beat in listOf(1.5, 5.5, 7.5)) continue
            if (h.part == 3 || (h.part == 1 && h.beat in listOf(1.0, 3.0, 7.0))) continue
            add(Event(p[h.part], h.beat, h.velocity))
        }
        add(event(Voice.CROSS_STICK, 1.0)); add(event(Voice.CROSS_STICK, 3.0))
        add(event(Voice.RIMSHOT, 7.0))
        add(event(Voice.OPEN_HAT, 1.5)); add(event(Voice.OPEN_HAT, 5.5))
        add(event(Voice.FLOOR_TOM, 7.5))
    }.sortedBy { it.beat }

    fun render(voice: Voice? = null, recorded: Boolean = true): Snip =
        renderEvents(if (voice == null) groove() else listOf(event(voice, 0.0)),
            if (voice == null) 5.4 else 1.8, recorded)

    /** R4 floor-tom voicing: more membrane weight and decay, less stick click. */
    fun thumpyTom(): Articulation = articulation(Voice.FLOOR_TOM).let { a ->
        a.copy(part = a.part.copy(id = "thumpy-floor-tom", macros = a.part.macros + mapOf(
            "TUNE" to .06f, "DECAY" to .98f, "TONE" to .38f,
            "CLICK" to .055f, "DROP" to .22f, "PUNCH" to .60f)))
    }

    fun renderThumpyTom(inGroove: Boolean = false, recorded: Boolean = true): Snip {
        val a = thumpyTom()
        val events = if (inGroove) groove().map { e ->
            if (e.part.id == "floor-tom") e.copy(part = a.part) else e
        } else listOf(Event(a.part, 0.0, a.velocity))
        return renderEvents(events, if (inGroove) 5.4 else 1.8, recorded)
    }

    fun snarePhrase(): Snip = renderEvents(listOf(
        event(Voice.GHOST_SNARE, 0.0), Event(BrineKit.parts()[1], 1.0, .88f),
        event(Voice.GHOST_SNARE, 1.75), event(Voice.RIMSHOT, 2.0)), 3.2)

    // Cut the metal vibration at the next closed strike. A 6 ms release avoids a sample step;
    // the shared room and pedal returns continue naturally after the physical source stops.
    internal fun choke(samples: FloatArray, at: Int) {
        val release = (Dsp.RATE * .006).roundToInt()
        for (i in at.coerceAtLeast(0) until samples.size) {
            val phase = ((i - at).toDouble() / release).coerceIn(0.0, 1.0)
            samples[i] *= (.5 * (1.0 + cos(PI * phase))).toFloat()
        }
    }

    internal fun renderEvents(events: List<Event>, seconds: Double, recorded: Boolean = true,
        sourceOverride: ((Event) -> FloatArray?)? = null): Snip {
        val ordered = events.sortedBy { it.beat }
        val out = FloatArray((seconds * Dsp.RATE).roundToInt())
        ordered.forEachIndexed { take, hit ->
            val p = hit.part
            val source = sourceOverride?.invoke(hit) ?: hit.cymbal?.let { if (hit.metal) BrineMetal.source(it) else BrineCymbals.source(it) }
                ?: Skin.render(p.voice, p.macros, hit.velocity, take).samples
            // The rim and membrane are struck together, so this is not a flam.
            val rim = if (hit.rimMix > 0f) Skin.render(SkinVoice.STICK,
                Skin.defaults(SkinVoice.STICK) + mapOf("TUNE" to .48f, "BODY" to .20f,
                    "CLICK" to .48f, "DECAY" to .24f)).samples else FloatArray(0)
            val x = FloatArray(maxOf(source.size, rim.size)) { i ->
                (source.getOrNull(i) ?: 0f) + hit.rimMix * (rim.getOrNull(i) ?: 0f)
            }
            if (p.voice == SkinVoice.HAT_OPEN) {
                ordered.firstOrNull { it.beat > hit.beat && it.part.voice == SkinVoice.HAT_CLOSED }?.let {
                    choke(x, ((it.beat-hit.beat)*60/BrineKit.BPM*Dsp.RATE).roundToInt())
                }
            }
            val near = Dsp.FixedOnePole(8000f); val far = Dsp.FixedOnePole(3800f)
            val at = (hit.beat*60/BrineKit.BPM*Dsp.RATE).roundToInt()
            val gain = p.gain*hit.velocity*.45f
            for (i in x.indices) {
                val a = near.lp(x[i]); val b = far.lp(x[i])
                if (at+i in out.indices) out[at+i] += a*gain
                if (at+i+p.farDelay in out.indices) out[at+i+p.farDelay] += b*gain*p.farGain
            }
        }
        return BrineKit.finish(out, recorded = recorded)
    }
}
