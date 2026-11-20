package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The six RIDDIM kits as the owner heard and approved them: round 1's three on 2026-11-18 (`prototype/riddim/kits.py`)
 * and round 3's three on 2026-11-19 (`prototype/riddim/kits_wide.py`), with a harder wood mallet and a real ratchet. Round 1's:
 * sixteen pads each, A01 bottom left, ported pad for pad. Drums and percussion share a kit: a pad strikes either a
 * RIDDIM voice or one of CALABASH's (the shaker, claves, sticks, cowbell, conga, high bongo and talking drum, fitted
 * to CC0 recordings and approved by the owner on 2026-11-15) at that voice's defaults, as the prototype did.
 *
 * - [Kit.CLAVE_DANCEHALL], 102 BPM: bright and tight; kick and rimshot on the 3-2 son clave, four woods in F# minor.
 * - [Kit.DEMBOW], 95 BPM: deep and heavier; the sub-heavy dembow kick, a lower snare and clap, hand drums.
 * - [Kit.BOOM_RIDDIM], 100 BPM: the kick is the bass; six tuned booms that cut each other off, bronze in F minor.
 * - [Kit.BAILE_FUNK], 130 BPM: Brazilian funk; one tight kick a bar, the tamborzao on CALABASH's tumba and quinto,
 *   a high ting bell and a wooden ratchet.
 * - [Kit.SABAR_SWING], 103 BPM: slow and dark; a sub kick just after each beat under CALABASH's sabar family.
 * - [Kit.WOOD_BRONZE], 96 BPM: quiet and pitched; five hard-mallet woods and four bronze pairs in D minor.
 *
 * Swing belongs to the groove, not the kit: the owner's references swung 58%, 61% and 55%, and the MPC or the app's
 * sequencer sets it. Clave Dancehall's woods play the hardest mallet since the owner's A/B (2026-11-19), so its pads
 * printed before that carry kit version 1.
 *
 * Each pad is a grid of three velocity zones (0.35, 0.65, 1.0) of four takes, rendered by the engines themselves: take
 * t is engine take t, and a softer zone is a different stroke. The sampler makes a soft zone quieter. A16 is each
 * kit's roll or riser, played at the kit's tempo through SPEED.
 *
 * Level within a zone follows CALABASH's law, not the prototype's audition: there every take peaked at 0.89, which
 * leaves a zone's takes unequal in loudness; here every take of a zone is matched in loudness ([LEVEL_WINDOW_S] for a
 * hit, the whole phrase for a roll) and the zone's highest-peaking take peaks at [PAD_PEAK].
 */
object RiddimKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 2

    enum class Kit(val id: String, val title: String, val bpm: Int) {
        CLAVE_DANCEHALL("riddim-clave-dancehall", "Clave Dancehall", 102),
        DEMBOW("riddim-dembow", "Dembow", 95),
        BOOM_RIDDIM("riddim-boom-riddim", "Boom Riddim", 100),
        BAILE_FUNK("riddim-baile-funk", "Baile Funk", 130),
        SABAR_SWING("riddim-sabar-swing", "Sabar Swing", 103),
        WOOD_BRONZE("riddim-wood-bronze", "Wood and Bronze", 96),
    }

    val velocities = listOf(.35f, .65f, 1f)
    const val TAKES = 4
    const val PADS = 16

    /** One pad: its name, and either a RIDDIM voice or a CALABASH voice, with that voice's settings. */
    class Pad internal constructor(
        val name: String,
        val riddim: RiddimVoice?,
        val calabash: CalabashVoice?,
        val macros: Map<String, Float>,
    ) {
        val engine: String get() = if (riddim != null) RiddimPatch.ENGINE else CalabashPatch.ENGINE
        val voiceName: String get() = riddim?.name ?: calabash!!.name
        val drumClass: DrumClass get() = riddim?.drumClass ?: calabash!!.drumClass
        /** A roll, a riser or a call: matched in loudness over its whole length. CALABASH's own phrases count as its kits
         *  count them (Sabar Swing's talking call). */
        val isPhrase: Boolean get() = if (riddim != null) Riddim.isRoll(riddim) else calabash in CalabashKits.PHRASES
        /** What the pad plays, for its saved source: "roll", "riser" or "finite". */
        val articulation: String get() = when {
            riddim == RiddimVoice.HAT_RISER || riddim == RiddimVoice.RATCHET_RISER -> "riser"
            riddim != null -> if (Riddim.isRoll(riddim)) "roll" else "finite"
            else -> CalabashKits.PHRASES[calabash] ?: "finite"
        }
    }

    private fun r(name: String, voice: RiddimVoice, macros: Map<String, Float> = emptyMap()) = Pad(name, voice, null, macros)
    private fun c(name: String, voice: CalabashVoice, macros: Map<String, Float> = emptyMap()) = Pad(name, null, voice, macros)
    /** [voice]'s TUNE moved [semis] semitones, with any other settings. */
    private fun st(voice: RiddimVoice, semis: Int, vararg more: Pair<String, Float>) = mapOf("TUNE" to Riddim.tuneFor(voice, semis)) + more

    fun pads(kit: Kit): List<Pad> = when (kit) {
        Kit.CLAVE_DANCEHALL -> claveDancehallPads; Kit.DEMBOW -> dembowPads; Kit.BOOM_RIDDIM -> boomRiddimPads
        Kit.BAILE_FUNK -> baileFunkPads; Kit.SABAR_SWING -> sabarSwingPads; Kit.WOOD_BRONZE -> woodBronzePads
    }

    /** The span over which a one-shot zone's takes are matched in loudness (CALABASH's and CLUTTER's). "shape" */
    const val LEVEL_WINDOW_S = .15f
    /** A zone's highest-peaking take peaks here: the level every hit had in the approved audition. */
    const val PAD_PEAK = Riddim.PEAK.toFloat()

    /** The prototype's chokes as MPC mute groups, roles 0-based: the hats close each other; on Boom Riddim every boom
     *  cuts the last, one bass voice. */
    fun muteGroup(kit: Kit, role: Int): Int = CHOKES.getValue(kit)[role] ?: 0

    private val CHOKES: Map<Kit, Map<Int, Int>> = mapOf(
        Kit.CLAVE_DANCEHALL to mapOf(2 to 1, 3 to 1),
        Kit.DEMBOW to mapOf(2 to 1, 3 to 1),
        Kit.BOOM_RIDDIM to mapOf(2 to 1, 3 to 1, 0 to 2, 5 to 2, 6 to 2, 7 to 2, 8 to 2, 9 to 2),
        Kit.BAILE_FUNK to mapOf(2 to 1, 3 to 1),
        Kit.SABAR_SWING to mapOf(2 to 1, 3 to 1),
        Kit.WOOD_BRONZE to mapOf(2 to 1, 3 to 1),
    )

    /** The sixteen pads, each with its full grid. The 192 renders are independent, so they run in parallel. */
    fun build(kit: Kit): List<ArrangedPad> = arranged(kit, (0 until PADS).toList())

    /** One pad with its whole grid: the same bytes as that pad in [build]. */
    fun pad(kit: Kit, role: Int): ArrangedPad {
        require(role in 0 until PADS)
        return arranged(kit, listOf(role)).single()
    }

    private fun arranged(kit: Kit, roles: List<Int>): List<ArrangedPad> {
        val pads = pads(kit)
        val cells = roles.flatMap { role -> velocities.indices.flatMap { z -> (0 until TAKES).map { t -> Triple(role, z, t) } } }
        val raws = cells.parallelStream().map { c -> c to raw(kit, c.first, velocities[c.second], c.third) }
            .collect(java.util.stream.Collectors.toList()).toMap()
        return roles.map { role ->
            val pad = pads[role]
            val grid = velocities.indices.map { z ->
                val zone = List(TAKES) { t -> raws.getValue(Triple(role, z, t)) }
                val g = gains.getOrPut(Triple(kit, role, velocities[z])) { levels(pad, zone) }
                zone.mapIndexed { t, x -> levelled(x, g[t]) }
            }
            ArrangedPad(
                grid.last().first(), pad.drumClass, displayName = pad.name, velocityTakes = grid,
                muteGroup = muteGroup(kit, role),
                source = mapOf(
                    "engine" to pad.engine, "family" to "RIDDIM", "kitVersion" to VERSION.toString(),
                    "model" to (if (pad.riddim != null) Riddim.MODEL_VERSION else Calabash.MODEL_VERSION).toString(),
                    "kit" to kit.name, "role" to role.toString(), "bpm" to kit.bpm.toString(), "voice" to pad.voiceName,
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to pad.articulation,
                ),
            )
        }
    }

    /** The finished pad at one velocity and take. Pure: the same arguments give the same bytes as [build]'s. */
    fun render(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        val g = gains.getOrPut(Triple(kit, role, velocity)) { levels(pads(kit)[role], List(TAKES) { raw(kit, role, velocity, it) }) }
        return levelled(raw(kit, role, velocity, take), g[take])
    }

    /** The engine's own hit, finished and peak-matched as the audition played it. */
    private fun raw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val pad = pads(kit)[role]
        return if (pad.riddim != null) Riddim.render(pad.riddim, pad.macros, velocity, take).samples
        else Calabash.render(pad.calabash!!, pad.macros, velocity, take).samples
    }

    private fun levelled(x: FloatArray, gain: Float) = Snip(FloatArray(x.size) { x[it] * gain }, 1, Riddim.RATE)

    private val gains = ConcurrentHashMap<Triple<Kit, Int, Float>, FloatArray>()

    /** One gain per take: equal loudness across the zone, the highest resulting peak at [PAD_PEAK]. */
    private fun levels(pad: Pad, takes: List<FloatArray>): FloatArray {
        val window = if (pad.isPhrase) Int.MAX_VALUE else (LEVEL_WINDOW_S * Riddim.RATE).toInt()
        val rms = takes.map { x -> loudness(x, window).coerceAtLeast(1e-9) }
        val crest = takes.indices.maxOf { peakOf(takes[it]) / rms[it] }
        val target = PAD_PEAK / crest
        return FloatArray(takes.size) { (target / rms[it]).toFloat() }
    }

    internal fun loudness(x: FloatArray, window: Int): Double {
        val m = min(window, x.size).coerceAtLeast(1)
        var s = 0.0
        for (i in 0 until m) s += x[i].toDouble() * x[i]
        return sqrt(s / m)
    }

    private fun peakOf(x: FloatArray): Double { var p = 0f; for (v in x) p = max(p, abs(v)); return p.toDouble() }

    private val claveDancehallPads = listOf(
        r("Kick", RiddimVoice.KICK),
        r("Rimshot", RiddimVoice.RIMSHOT),
        r("Closed Hat", RiddimVoice.HAT_CLOSED, mapOf("DECAY" to .35f)),
        r("Open Hat", RiddimVoice.HAT_OPEN),
        r("Clap", RiddimVoice.CLAP),
        r("High Kick", RiddimVoice.KICK, st(RiddimVoice.KICK, 4, "DECAY" to .3f)),
        r("Tight Snare", RiddimVoice.SNARE, st(RiddimVoice.SNARE, 2, "DECAY" to .3f)),
        c("Shaker", CalabashVoice.SHAKER),
        c("Claves", CalabashVoice.CLAVE),
        c("Sticks", CalabashVoice.STICKS),
        c("Cowbell", CalabashVoice.COWBELL),
        // The hardest mallet: the owner heard an A/B of the approved mallet against it and chose it (2026-11-19).
        r("Wood F#3", RiddimVoice.WOOD, mapOf("BEATER" to 1f)),
        r("Wood A3", RiddimVoice.WOOD, st(RiddimVoice.WOOD, 3, "BEATER" to 1f)),
        r("Wood C#4", RiddimVoice.WOOD, st(RiddimVoice.WOOD, 7, "BEATER" to 1f)),
        r("Wood F#4", RiddimVoice.WOOD, st(RiddimVoice.WOOD, 12, "BEATER" to 1f)),
        r("Rim Roll", RiddimVoice.RIM_ROLL, mapOf("TUNE" to .5f, "DECAY" to .5f, "SPEED" to Riddim.speedFor(102.0))),
    )

    private val dembowPads = listOf(
        r("Dembow Kick", RiddimVoice.DEMBOW_KICK, mapOf("DRIVE" to .7f)),
        r("Snare", RiddimVoice.SNARE, st(RiddimVoice.SNARE, -2, "DECAY" to .55f)),
        r("Closed Hat", RiddimVoice.HAT_CLOSED, st(RiddimVoice.HAT_CLOSED, -2, "DECAY" to .4f)),
        r("Open Hat", RiddimVoice.HAT_OPEN, st(RiddimVoice.HAT_OPEN, -2)),
        r("Wide Clap", RiddimVoice.CLAP, st(RiddimVoice.CLAP, -2, "DECAY" to .7f)),
        r("Low Rim", RiddimVoice.RIMSHOT, st(RiddimVoice.RIMSHOT, -4)),
        r("Punch Kick", RiddimVoice.KICK),
        c("Shaker", CalabashVoice.SHAKER),
        c("Conga", CalabashVoice.CONGA),
        c("High Bongo", CalabashVoice.BONGO_HIGH),
        c("Cowbell", CalabashVoice.COWBELL),
        c("Claves", CalabashVoice.CLAVE),
        c("Talking Drum", CalabashVoice.TAMA),
        c("Sticks", CalabashVoice.STICKS),
        r("Low Metal G4", RiddimVoice.METAL, st(RiddimVoice.METAL, -7, "WAVE" to .3f)),
        r("Snare Roll", RiddimVoice.SNARE_ROLL, st(RiddimVoice.SNARE, -2, "DECAY" to .4f, "SPEED" to Riddim.speedFor(95.0))),
    )

    private val boomRiddimPads = listOf(
        r("Boom F1", RiddimVoice.BOOM),
        r("Ringing Rim", RiddimVoice.RIMSHOT, st(RiddimVoice.RIMSHOT, 2, "DECAY" to .7f)),
        r("Closed Hat", RiddimVoice.HAT_CLOSED, mapOf("DECAY" to .3f)),
        r("Open Hat", RiddimVoice.HAT_OPEN),
        r("Clap", RiddimVoice.CLAP),
        r("Slide F1", RiddimVoice.SLIDE),
        r("Boom Ab1", RiddimVoice.BOOM, st(RiddimVoice.BOOM, 3)),
        r("Boom Bb1", RiddimVoice.BOOM, st(RiddimVoice.BOOM, 5)),
        r("Boom C2", RiddimVoice.BOOM, st(RiddimVoice.BOOM, 7)),
        r("Boom Eb2", RiddimVoice.BOOM, st(RiddimVoice.BOOM, 10)),
        r("Metal F5", RiddimVoice.METAL, st(RiddimVoice.METAL, 3)),
        r("Metal Ab5", RiddimVoice.METAL, st(RiddimVoice.METAL, 6)),
        r("Metal C6", RiddimVoice.METAL, st(RiddimVoice.METAL, 10)),
        r("Snare", RiddimVoice.SNARE),
        c("Sticks", CalabashVoice.STICKS),
        r("Hat Riser", RiddimVoice.HAT_RISER, mapOf("DECAY" to .3f, "SPEED" to Riddim.speedFor(100.0))),
    )

    private val baileFunkPads = listOf(
        r("Tight Kick", RiddimVoice.KICK, st(RiddimVoice.KICK, 6, "DECAY" to .2f, "DRIVE" to .7f)),
        r("Clap", RiddimVoice.CLAP, st(RiddimVoice.CLAP, 2, "DECAY" to .35f)),
        r("Closed Hat", RiddimVoice.HAT_CLOSED, st(RiddimVoice.HAT_CLOSED, 2, "DECAY" to .25f)),
        r("Open Hat", RiddimVoice.HAT_OPEN, st(RiddimVoice.HAT_OPEN, 2, "DECAY" to .35f)),
        r("Short Boom", RiddimVoice.BOOM, st(RiddimVoice.BOOM, 5, "DECAY" to .25f)),
        c("Tambor Low", CalabashVoice.TUMBA, mapOf("STROKE" to 1f / 3)),
        c("Tambor Slap", CalabashVoice.TUMBA, mapOf("STROKE" to 1f)),
        c("Tambor High", CalabashVoice.QUINTO, mapOf("STROKE" to 1f / 3)),
        r("Ting", RiddimVoice.TING),
        r("Ting Low", RiddimVoice.TING, st(RiddimVoice.TING, -5)),
        r("Ratchet", RiddimVoice.RATCHET, mapOf("DECAY" to .2f)),
        r("Long Ratchet", RiddimVoice.RATCHET, mapOf("DECAY" to .7f, "WAVE" to .4f)),
        c("Agogo", CalabashVoice.AGOGO_HIGH),
        c("Shaker", CalabashVoice.SHAKER),
        r("Rimshot", RiddimVoice.RIMSHOT, st(RiddimVoice.RIMSHOT, 3)),
        r("Ratchet Riser", RiddimVoice.RATCHET_RISER, mapOf("DECAY" to 0f, "SPEED" to Riddim.speedFor(130.0))),
    )

    private val sabarSwingPads = listOf(
        r("Sub Kick", RiddimVoice.DEMBOW_KICK, st(RiddimVoice.DEMBOW_KICK, 4, "DECAY" to .4f)),
        r("Snare", RiddimVoice.SNARE, st(RiddimVoice.SNARE, -3, "DECAY" to .4f)),
        r("Closed Hat", RiddimVoice.HAT_CLOSED, st(RiddimVoice.HAT_CLOSED, -3, "DECAY" to .3f)),
        r("Open Hat", RiddimVoice.HAT_OPEN, st(RiddimVoice.HAT_OPEN, -3)),
        r("Low Rim", RiddimVoice.RIMSHOT, st(RiddimVoice.RIMSHOT, -3)),
        c("Mbeng", CalabashVoice.MBENG),
        c("Mbeng Slap", CalabashVoice.MBENG, mapOf("STROKE" to 1f / 3)),
        c("Nder", CalabashVoice.NDER),
        c("Col", CalabashVoice.COL),
        c("Dundun", CalabashVoice.DUNDUN),
        c("Talking Call", CalabashVoice.TALKING_CALL),
        c("Shekere", CalabashVoice.SHEKERE),
        c("Tama", CalabashVoice.TAMA),
        r("Clap", RiddimVoice.CLAP, st(RiddimVoice.CLAP, -3, "DECAY" to .6f)),
        r("Boom G1", RiddimVoice.BOOM, st(RiddimVoice.BOOM, 2, "DECAY" to .4f)),
        r("Sabar Roll", RiddimVoice.NDER_ROLL, mapOf("SPEED" to Riddim.speedFor(103.0))),
    )

    private val woodBronzePads = listOf(
        r("Soft Kick", RiddimVoice.KICK, st(RiddimVoice.KICK, -2, "DECAY" to .45f, "DRIVE" to .25f)),
        c("Sticks", CalabashVoice.STICKS),
        r("Closed Hat", RiddimVoice.HAT_CLOSED, st(RiddimVoice.HAT_CLOSED, -4, "DECAY" to .2f)),
        r("Open Hat", RiddimVoice.HAT_OPEN, st(RiddimVoice.HAT_OPEN, -4, "DECAY" to .4f)),
        c("Claves", CalabashVoice.CLAVE),
        // The hardest mallet, the owner's choice: "wood might need more of an attack" (2026-11-18, 2026-11-19).
        r("Wood D3", RiddimVoice.WOOD, st(RiddimVoice.WOOD, -4, "BEATER" to 1f)),
        r("Wood F3", RiddimVoice.WOOD, st(RiddimVoice.WOOD, -1, "BEATER" to 1f)),
        r("Wood G3", RiddimVoice.WOOD, st(RiddimVoice.WOOD, 1, "BEATER" to 1f)),
        r("Wood A3", RiddimVoice.WOOD, st(RiddimVoice.WOOD, 3, "BEATER" to 1f)),
        r("Wood C4", RiddimVoice.WOOD, st(RiddimVoice.WOOD, 6, "BEATER" to 1f)),
        // METAL's default D5 does not darken from mid to soft, so the bronze skips it: F5, A5, C6, D6.
        r("Bronze F5", RiddimVoice.METAL, st(RiddimVoice.METAL, 3, "WAVE" to .35f)),
        r("Bronze A5", RiddimVoice.METAL, st(RiddimVoice.METAL, 7, "WAVE" to .35f)),
        r("Bronze C6", RiddimVoice.METAL, st(RiddimVoice.METAL, 10, "WAVE" to .35f)),
        r("Bronze D6", RiddimVoice.METAL, st(RiddimVoice.METAL, 12, "WAVE" to .35f)),
        r("Rimshot", RiddimVoice.RIMSHOT, st(RiddimVoice.RIMSHOT, -5, "DECAY" to .6f)),
        r("Wood Tremolo", RiddimVoice.WOOD_TREMOLO, st(RiddimVoice.WOOD, -4, "BEATER" to 1f, "SPEED" to Riddim.speedFor(96.0))),
    )
}
