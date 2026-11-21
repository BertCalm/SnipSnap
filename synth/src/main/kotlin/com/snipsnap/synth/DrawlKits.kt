package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad

/**
 * DRAWL's two starters, in D major pentatonic (D E F# A B), the key every voice's root already sits in.
 *
 * DRAWL ([arranged]): sixteen dry, finite pads.
 * - A01-A08: RESO TWANG walked up the scale from D3 to F#4, so the resonator plays as keys on the pads.
 * - A09-A12: the four lead voices at their own roots (BOTTLENECK, LAP STEEL, PEDAL STEEL, WESTERN NYLON).
 * - A13-A14: two long slides, the lap steel's and the pedal steel's.
 * - A15-A16: two strums, the flat-top's open D (the key's I chord) and the resonator's open A (its V).
 * Pads A09 to A16 are factory presets as they were heard and kept ([DrawlPresets]); A01 to A08 are RESO
 * TWANG retuned with TUNE, rendered by the same engine, not heard pad by pad.
 *
 * DRAWL TAKES ([takesArranged]): the same sixteen pads, each with three touches (soft .3, medium .75,
 * hard 1, the touches heard in round 1) of all four engine takes ([Drawl.TAKES]): the takes ROBIN deals.
 * Each pad's medium first take is its DRAWL pad, sample for sample. DRAWL's velocity is the pick's
 * character and level at one gain per sound, so the zones are not peak-matched. The whole kit is about
 * nine minutes of audio (some 72 MB of PCM24), under ACCORD TAKES' two-take chain.
 *
 * Every pad is longer than 1.5 s, so it files as LOOP, a duration class, never a wrap claim: no DRAWL
 * note loops (HOLD rings a note out to at most 8 s, the owner's round-3 decision).
 */
object DrawlKits {
    const val ID = "drawl"
    const val TAKES_ID = "drawl-takes"
    const val VERSION = 1

    /** The key the starters are in. */
    const val KEY = "Dmajpent"

    /** A01-A08's notes: D major pentatonic from D3. */
    val SCALE_MIDI: List<Int> = listOf(50, 52, 54, 57, 59, 62, 64, 66)

    /** The three touches, softest first, and the engine takes each one deals, in order. */
    val TOUCHES: List<Float> = listOf(.3f, .75f, 1f)
    val TAKES: List<Int> = (0 until Drawl.TAKES).toList()

    private val COLORS = mapOf(
        DrawlVoice.CONE to "#c9a46b", DrawlVoice.BOTTLE to "#b8735a", DrawlVoice.LAP to "#8fb3a8",
        DrawlVoice.STEEL to "#9aa9c9", DrawlVoice.FLATTOP to "#d6b98a", DrawlVoice.GUT to "#c7a0a0",
    )

    /** The sixteen performances, in pad order. */
    fun patches(): List<DrawlPatch> {
        val twang = DrawlPresets.named(DrawlVoice.CONE, "RESO TWANG")
        val home = Drawl.rootMidi(DrawlVoice.CONE)
        val scale = SCALE_MIDI.map { midi ->
            twang.copy(name = "RESO ${Scales.nameOf(midi)}",
                macros = twang.macros + ("TUNE" to .5f + (midi - home).toFloat() / Drawl.TUNE_SEMITONES))
        }
        return scale + listOf(
            DrawlPresets.named(DrawlVoice.BOTTLE, "BOTTLENECK"),
            DrawlPresets.named(DrawlVoice.LAP, "LAP STEEL"),
            DrawlPresets.named(DrawlVoice.STEEL, "PEDAL STEEL"),
            DrawlPresets.named(DrawlVoice.GUT, "WESTERN NYLON"),
            DrawlPresets.named(DrawlVoice.LAP, "LAP SLIDE"),
            DrawlPresets.named(DrawlVoice.STEEL, "PEDAL SLIDE"),
            DrawlPresets.named(DrawlVoice.FLATTOP, "FLAT-TOP STRUM"),
            DrawlPresets.named(DrawlVoice.CONE, "RESO STRUM"),
        )
    }

    /** The note a performance plays (its lowest string, for a strum). */
    fun midiOf(patch: DrawlPatch): Int = Drawl.midiFor(patch.voice, patch.macros["TUNE"] ?: .5f)

    private fun source(patch: DrawlPatch): Map<String, String> = buildMap {
        put("engine", DrawlPatch.ENGINE)
        put("voice", patch.voice.name)
        put("rootMidi", midiOf(patch).toString())
        put("rootNote", Scales.nameOf(midiOf(patch)))
        put("velocity", patch.velocity.toString())
        patch.strum?.let { put("strum", it.picked.joinToString(",")) }
        DrawlPresets.forVoice(patch.voice).firstOrNull { it == patch }?.let { put("factoryPreset", it.name) }
        put("playback", "FINITE_ONE_SHOT")
    }

    /** DRAWL: the sixteen pads, each carrying its recipe. */
    fun arranged(render: (DrawlPatch) -> Snip = { it.render() }): List<ArrangedPad> = patches().map { patch ->
        ArrangedPad(
            snip = render(patch), drumClass = Drawl.drumClassFor(patch.voice, patch.macros),
            recipe = PadRecipe(patch).toJsonValue(), oneShot = true, displayName = patch.name,
            source = source(patch), colorHex = COLORS.getValue(patch.voice),
        )
    }

    /** Engine take [take] of [patch] at [velocity]: the engine's own render, pure in its arguments. */
    fun render(patch: DrawlPatch, velocity: Float, take: Int): Snip {
        require(velocity in TOUCHES && take in TAKES)
        return patch.copy(velocity = velocity, take = take).render()
    }

    /**
     * DRAWL TAKES: the sixteen pads, soft to hard zones of four takes. The medium zone's first take is
     * the DRAWL pad, reused. Grid pads carry no PadRecipe (one performance's recipe cannot regenerate
     * twelve takes); each pad's recipe rides in its source as JSON beside the take rule, as ACCORD
     * TAKES' do.
     */
    fun takesArranged(): List<ArrangedPad> {
        val patches = patches()
        val kept = arranged()
        return patches.indices.map { i ->
            val patch = patches[i]
            val pad = kept[i]
            require(patch.velocity in TOUCHES) { "${patch.name}: kept touch ${patch.velocity} is not a zone" }
            val grid = TOUCHES.map { v ->
                TAKES.map { k -> if (v == patch.velocity && k == 0) pad.snip else render(patch, v, k) }
            }
            ArrangedPad(
                snip = grid.last().first(),
                drumClass = pad.drumClass,
                oneShot = true,
                displayName = pad.displayName,
                colorHex = pad.colorHex,
                velocityTakes = grid,
                source = source(patch) + mapOf(
                    "touch" to TOUCHES.joinToString(","), "keptTouch" to patch.velocity.toString(),
                    "recipe" to patch.toJsonText(),
                    "roundRobin" to "${TAKES.size} takes per touch: take 1 is the plain render, takes 2 to 4 are the " +
                        "player's other passes (DrawlPatch.take ${TAKES.drop(1).joinToString(", ")}), the takes ROBIN deals",
                    "roundRobinVersion" to VERSION.toString(),
                ),
            )
        }
    }
}
