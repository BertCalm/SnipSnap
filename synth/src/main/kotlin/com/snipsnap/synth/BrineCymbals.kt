package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.*

/** R5 recipe family. All metallic sources use SKIN's oversampled resonant noise banks. */
object BrineCymbals {
    const val VERSION = 5
    enum class Voice(val id: String, val label: String) {
        HALF_HAT("half-hat", "Half-open hat"), RIDE("dark-ride", "Dark ride"),
        CRASH("thin-crash", "Thin crash"), CHOKED_CRASH("choked-crash", "Choked crash")
    }
    enum class Groove { HATS, RIDE }
    private fun macros(voice: SkinVoice, vararg changes: Pair<String, Float>) = Skin.defaults(voice) + changes.toMap()
    val halfHat = macros(SkinVoice.HAT_OPEN, "TUNE" to .10f, "DECAY" to .18f,
        "TONE" to .20f, "RING" to .22f, "PUNCH" to .35f)
    val ride = macros(SkinVoice.RIDE, "TUNE" to .0f, "DECAY" to .48f,
        "TONE" to .18f, "SIZZLE" to 0f, "PUNCH" to .32f)
    val rideStick = macros(SkinVoice.STICK, "TUNE" to .34f, "DECAY" to .12f,
        "BODY" to .05f, "CLICK" to .05f, "PUNCH" to .20f)
    val crashLayers = listOf(.0f, .28f, .65f).map { tune -> macros(SkinVoice.RIDE,
        "TUNE" to tune, "DECAY" to .88f, "TONE" to .72f, "SIZZLE" to .08f, "PUNCH" to .20f) }

    fun part(v: Voice): BrineKit.Part = BrineKit.Part(v.id,
        if (v == Voice.HALF_HAT) SkinVoice.HAT_OPEN else SkinVoice.RIDE,
        when (v) { Voice.HALF_HAT -> .48f; Voice.RIDE -> .34f; else -> .42f },
        161, .18f, if (v == Voice.HALF_HAT) halfHat else ride)

    internal fun source(v: Voice): FloatArray {
        if (v == Voice.HALF_HAT) return Skin.render(SkinVoice.HAT_OPEN, halfHat).samples
        if (v == Voice.RIDE) {
            val body = Skin.render(SkinVoice.RIDE, ride).samples
            val stick = Skin.render(SkinVoice.STICK, rideStick).samples
            return FloatArray(maxOf(body.size, stick.size)) { i ->
                .90f * (body.getOrNull(i) ?: 0f) + .10f * (stick.getOrNull(i) ?: 0f)
            }
        }
        // Three differently tuned banks broaden the wash without adding bright white hiss.
        // A short swelling edge separates a crash accent from the ride's pinpoint attack.
        val layers = crashLayers.map { Skin.render(SkinVoice.RIDE, it).samples }
        val x = FloatArray(layers.maxOf { it.size }) { i ->
            val bloom = (i.toFloat() / (Dsp.RATE * .006f)).coerceAtMost(1f)
            layers.sumOf { (it.getOrNull(i) ?: 0f).toDouble() }.toFloat() / layers.size * bloom
        }
        if (v == Voice.CHOKED_CRASH) BrineKitExpansion.choke(x, (Dsp.RATE * .17).roundToInt())
        return x
    }

    fun event(v: Voice, beat: Double, velocity: Float = .78f) =
        BrineKitExpansion.Event(part(v), beat, velocity, cymbal = v)

    fun render(v: Voice, recorded: Boolean = true): Snip =
        BrineKitExpansion.renderEvents(listOf(event(v, 0.0)), 2.0, recorded)

    fun events(style: Groove, cymbalScale: Float = 1f): List<BrineKitExpansion.Event> {
        require(cymbalScale.isFinite() && cymbalScale in 0f..1f)
        return buildList {
            for (e in BrineKitExpansion.groove()) {
                if (e.part.voice in listOf(SkinVoice.HAT_CLOSED, SkinVoice.HAT_OPEN)) continue
                add(if (e.part.id == "floor-tom") e.copy(part = BrineKitExpansion.thumpyTom().part) else e)
            }
            for (n in 0..15) {
                val beat = n * .5
                val velocity = if (n%2 == 0) .72f else .48f
                if (style == Groove.RIDE) add(event(Voice.RIDE, beat, velocity))
                else if (n in listOf(3, 11)) add(event(Voice.HALF_HAT, beat, velocity))
                else if (n == 7) {
                    val a = BrineKitExpansion.articulation(BrineKitExpansion.Voice.OPEN_HAT)
                    add(BrineKitExpansion.Event(a.part, beat, velocity))
                } else add(BrineKitExpansion.Event(BrineKit.parts()[2], beat, velocity))
            }
            add(event(Voice.CRASH, 0.0, .78f))
            add(event(Voice.CHOKED_CRASH, 6.0, .72f))
        }.map { e ->
            if (e.cymbal != null || e.part.voice in listOf(SkinVoice.HAT_CLOSED, SkinVoice.HAT_OPEN))
                e.copy(part = e.part.copy(gain = e.part.gain * cymbalScale)) else e
        }.sortedBy { it.beat }
    }
    fun renderGroove(style: Groove, cymbalScale: Float = 1f): Snip =
        BrineKitExpansion.renderEvents(events(style, cymbalScale), 6.0)
}
