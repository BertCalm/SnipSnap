package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The four CALABASH kits as the owner heard and approved them on 2026-11-15 (round 0, `prototype/calabash/kits.py`
 * at 967f53f7f): sixteen pads each, A01 bottom left, every pad one CALABASH voice at the kit's settings, ported pad
 * for pad. Round 1 rendered them for the A/B against the prototype; round 2 puts them on the shelf ([build]).
 *
 * - [Kit.AFROBEAT], 112 BPM: Lagos in the 1970s, a live rhythm section in one room, sixteen-track tape.
 * - [Kit.AFROBEATS], 104 BPM: today's Lagos and Accra pop, programmed, close and dry, a clean chain.
 * - [Kit.MBALAX], 126 BPM: Dakar's dance bands, a sabar ensemble and a tama over a kit, a bright live room.
 * - [Kit.AFROCUBAN], 176 BPM: New York in the 1950s, a Latin section beside a jazz drummer, valve and tape.
 *
 * Each pad is a grid of three velocity zones (the audition's 0.35, 0.65, 1.0) of four takes, rendered by the engine
 * itself: take t is engine take t, and a softer zone is a different stroke. The sampler, not the render, makes a soft
 * zone quieter. The roll or riser on every kit's A16 is the percussion gate.
 *
 * Level within a zone is CLUTTER's law, not the audition's. The audition peak-matched every take at 0.89, and
 * measured on the prototype's kits that left a zone's takes a median 1.9 dB apart in loudness over their first 150 ms,
 * up to 4.5 dB on a hit (the Afrobeat sticks; the rimshot and the Mbalax high tom near 4), and 0.1 to 6.3 dB over the
 * whole length of the rolls and risers: a pad dealing its takes in turn would sound like an uneven player. So every take of a zone is matched in loudness ([LEVEL_WINDOW_S] for a hit, the whole phrase for
 * a roll or riser) and the zone is set so its highest-peaking take peaks at [PAD_PEAK], the audition's level. The
 * prototype's chokes are the pads' mute groups ([muteGroup]).
 */
object CalabashKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 1

    enum class Kit(val id: String, val title: String, val bpm: Int) {
        AFROBEAT("calabash-afrobeat", "Afrobeat", 112),
        AFROBEATS("calabash-afrobeats", "Afrobeats", 104),
        MBALAX("calabash-mbalax", "Mbalax", 126),
        AFROCUBAN("calabash-afrocuban", "Afro-Cuban Jazz", 176),
    }

    val velocities = listOf(.35f, .65f, 1f)
    const val TAKES = 4
    const val PADS = 16

    /** One pad: its name, the voice, and the voice's settings (the rest at the voice's defaults). */
    class Pad(val name: String, val voice: CalabashVoice, val macros: Map<String, Float>)

    fun pads(kit: Kit): List<Pad> = when (kit) {
        Kit.AFROBEAT -> afrobeatPads; Kit.AFROBEATS -> afrobeatsPads; Kit.MBALAX -> mbalaxPads; Kit.AFROCUBAN -> afrocubanPads
    }

    /** The span over which a one-shot zone's takes are matched in loudness: CLUTTER's, where a hit's loudness lives. "shape" */
    const val LEVEL_WINDOW_S = .15f
    /** A zone's highest-peaking take peaks here: the level every hit had in the approved audition. */
    const val PAD_PEAK = Calabash.PEAK

    /** The four A16 phrases and what each is: a phrase is matched in loudness over its whole length, not its first 150 ms. */
    val PHRASES = mapOf(
        CalabashVoice.SHEKERE_RUSH to "riser", CalabashVoice.TALKING_CALL to "riser",
        CalabashVoice.SABAR_ROLL to "roll", CalabashVoice.ABANICO to "roll",
    )

    /**
     * The prototype's chokes (`kits.py` CHOKES) as MPC mute groups, roles 0-based: hats close hats, and strokes on one
     * drum cut that drum's ring. A mute group is mutual, where two of the prototype's were one way (a closed hat or
     * the foot cut the half-open hat): the half-open hat now cuts them too.
     */
    fun muteGroup(kit: Kit, role: Int): Int = CHOKES.getValue(kit)[role] ?: 0

    private val CHOKES: Map<Kit, Map<Int, Int>> = mapOf(
        Kit.AFROBEAT to mapOf(3 to 1, 4 to 1, 5 to 1, 10 to 2, 11 to 2),
        Kit.AFROBEATS to mapOf(6 to 1, 7 to 1, 11 to 2, 12 to 2),
        Kit.MBALAX to mapOf(2 to 1, 3 to 1, 4 to 2, 5 to 2, 6 to 2, 7 to 3, 8 to 3, 9 to 4, 10 to 4, 11 to 5, 12 to 5),
        Kit.AFROCUBAN to mapOf(4 to 1, 5 to 1, 8 to 1),
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
                val g = gains.getOrPut(Triple(kit, role, velocities[z])) { levels(pad.voice, zone) }
                zone.mapIndexed { t, x -> levelled(x, g[t]) }
            }
            ArrangedPad(
                grid.last().first(), pad.voice.drumClass, displayName = pad.name, velocityTakes = grid,
                muteGroup = muteGroup(kit, role),
                source = mapOf(
                    "engine" to "CALABASH", "kitVersion" to VERSION.toString(), "model" to Calabash.MODEL_VERSION.toString(),
                    "kit" to kit.name, "role" to role.toString(), "bpm" to kit.bpm.toString(), "voice" to pad.voice.name,
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to (PHRASES[pad.voice] ?: "finite"),
                ),
            )
        }
    }

    /** The finished pad at one velocity and take. Pure: the same arguments give the same bytes as [build]'s. */
    fun render(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        val g = gains.getOrPut(Triple(kit, role, velocity)) { levels(pads(kit)[role].voice, List(TAKES) { raw(kit, role, velocity, it) }) }
        return levelled(raw(kit, role, velocity, take), g[take])
    }

    /** The engine's own hit, finished and peak-matched as the audition played it. */
    private fun raw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val pad = pads(kit)[role]
        return Calabash.render(pad.voice, pad.macros, velocity, take).samples
    }

    private fun levelled(x: FloatArray, gain: Float) = Snip(FloatArray(x.size) { x[it] * gain }, 1, Calabash.RATE)

    private val gains = ConcurrentHashMap<Triple<Kit, Int, Float>, FloatArray>()

    /**
     * One gain per take: equal loudness across the zone (over [LEVEL_WINDOW_S], or the whole phrase), the highest
     * resulting peak at [PAD_PEAK]. Never a boost past it: every take leaves the engine at [PAD_PEAK] already.
     */
    private fun levels(voice: CalabashVoice, takes: List<FloatArray>): FloatArray {
        val window = if (voice in PHRASES) Int.MAX_VALUE else (LEVEL_WINDOW_S * Calabash.RATE).toInt()
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

    /** The hand-drum and sabar STROKE anchors the kits sit on: open (and the sabar's hand slap), muffled (open stick). */
    private const val O = 1f / 3f
    private const val M = 2f / 3f

    private val afrobeatPads = listOf(
        Pad("Kick", CalabashVoice.KICK, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.45f, "DAMP" to 0.55f, "BEATER" to 0.45f)),
        Pad("Snare", CalabashVoice.SNARE, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.7f, "DAMP" to 0.35f, "WIRES" to 0.45f)),
        Pad("Rimshot", CalabashVoice.RIMSHOT, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.7f, "DAMP" to 0.4f, "WIRES" to 0.4f)),
        Pad("Hat Closed", CalabashVoice.HAT_CLOSED, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.55f, "DAMP" to 0.35f)),
        Pad("Hat Half Open", CalabashVoice.HAT_OPEN, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.55f, "DAMP" to 0.55f)),
        Pad("Hat Foot", CalabashVoice.HAT_FOOT, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.55f, "DAMP" to 0.3f)),
        Pad("Rack Tom", CalabashVoice.TOM, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.62f, "DAMP" to 0.45f)),
        Pad("Floor Tom", CalabashVoice.TOM, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.3f, "DAMP" to 0.45f)),
        Pad("Shekere", CalabashVoice.SHEKERE, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "STROKE" to 0.0f, "DAMP" to 0.3f)),
        Pad("Shekere Slap", CalabashVoice.SHEKERE, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "STROKE" to 1.0f, "DAMP" to 0.3f)),
        Pad("Conga Open", CalabashVoice.CONGA, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "STROKE" to O)),
        Pad("Conga Slap", CalabashVoice.CONGA, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "STROKE" to 1.0f)),
        Pad("Tumba Open", CalabashVoice.TUMBA, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "STROKE" to O, "TUNE" to 0.45f)),
        Pad("Sticks", CalabashVoice.STICKS, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "TUNE" to 0.45f, "DAMP" to 0.45f)),
        Pad("Bell", CalabashVoice.BIG_BELL, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "STROKE" to 0.0f, "TUNE" to 0.45f)),
        Pad("Shekere Rush", CalabashVoice.SHEKERE_RUSH, mapOf("ROOM" to 0.3f, "AGE" to 0.55f, "SPEED" to 0.5f)),
    )
    private val afrobeatsPads = listOf(
        Pad("Deep Kick", CalabashVoice.DEEP_KICK, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "TUNE" to 0.45f, "DAMP" to 0.45f, "CLICK" to 0.4f)),
        Pad("Rim", CalabashVoice.RIM, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "TUNE" to 0.6f, "DAMP" to 0.4f)),
        Pad("Clap", CalabashVoice.CLAP, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "CROWD" to 0.35f, "TUNE" to 0.5f)),
        Pad("Snaps", CalabashVoice.SNAP, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "CROWD" to 0.4f, "TUNE" to 0.55f)),
        Pad("Shaker", CalabashVoice.SHAKER, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "STROKE" to 0.0f, "TUNE" to 0.55f)),
        Pad("Shaker Back", CalabashVoice.SHAKER, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "STROKE" to 0.5f, "TUNE" to 0.55f)),
        Pad("Hat", CalabashVoice.HAT_CLOSED, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "TUNE" to 0.65f, "DAMP" to 0.5f)),
        Pad("Open Hat", CalabashVoice.HAT_OPEN, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "TUNE" to 0.65f, "DAMP" to 0.65f)),
        Pad("Conga Slap", CalabashVoice.CONGA, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "STROKE" to 1.0f, "TUNE" to 0.6f)),
        Pad("Quinto", CalabashVoice.QUINTO, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "STROKE" to O, "TUNE" to 0.55f)),
        Pad("Tumba", CalabashVoice.TUMBA, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "STROKE" to O, "TUNE" to 0.4f)),
        Pad("Talking Drum", CalabashVoice.DUNDUN, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "SQUEEZE" to 0.4f, "CONTOUR" to 0.0f)),
        Pad("Talking Drum Fall", CalabashVoice.DUNDUN, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "SQUEEZE" to 0.6f, "CONTOUR" to 0.5f, "TUNE" to 0.55f)),
        Pad("Bell", CalabashVoice.AGOGO_HIGH, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "TUNE" to 0.55f, "DAMP" to 0.4f)),
        Pad("Shekere", CalabashVoice.SHEKERE, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "STROKE" to 0.5f, "TUNE" to 0.6f, "DAMP" to 0.45f)),
        Pad("Talking Call", CalabashVoice.TALKING_CALL, mapOf("ROOM" to 0.05f, "AGE" to 0.0f, "SPEED" to 0.5f, "SQUEEZE" to 0.6f)),
    )
    private val mbalaxPads = listOf(
        Pad("Kick", CalabashVoice.KICK, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "TUNE" to 0.55f, "DAMP" to 0.6f, "BEATER" to 0.8f)),
        Pad("Snare", CalabashVoice.SNARE, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "TUNE" to 0.85f, "DAMP" to 0.4f, "WIRES" to 0.6f)),
        Pad("Hat", CalabashVoice.HAT_CLOSED, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "TUNE" to 0.6f, "DAMP" to 0.4f)),
        Pad("Open Hat", CalabashVoice.HAT_OPEN, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "TUNE" to 0.6f, "DAMP" to 0.5f)),
        Pad("Nder Stick", CalabashVoice.NDER, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to M)),
        Pad("Nder Slap", CalabashVoice.NDER, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to O)),
        Pad("Nder Pressed", CalabashVoice.NDER, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to 1.0f)),
        Pad("Mbeng Open", CalabashVoice.MBENG, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to 0.0f)),
        Pad("Mbeng Stick", CalabashVoice.MBENG, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to M)),
        Pad("Col Bass", CalabashVoice.COL, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to 0.0f)),
        Pad("Col Stick", CalabashVoice.COL, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "STROKE" to M)),
        Pad("Tama", CalabashVoice.TAMA, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "SQUEEZE" to 0.3f, "CONTOUR" to 0.0f)),
        Pad("Tama Talk", CalabashVoice.TAMA, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "SQUEEZE" to 0.75f, "CONTOUR" to 1.0f, "TUNE" to 0.45f)),
        Pad("High Tom", CalabashVoice.TOM, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "TUNE" to 0.85f, "DAMP" to 0.5f)),
        Pad("Crash", CalabashVoice.CRASH, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "TUNE" to 0.55f, "DAMP" to 0.35f)),
        Pad("Sabar Roll", CalabashVoice.SABAR_ROLL, mapOf("ROOM" to 0.35f, "AGE" to 0.25f, "SPEED" to 0.6f)),
    )
    private val afrocubanPads = listOf(
        Pad("Kick", CalabashVoice.KICK, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "TUNE" to 0.65f, "DAMP" to 0.2f, "BEATER" to 0.15f)),
        Pad("Snare", CalabashVoice.SNARE, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "TUNE" to 0.5f, "DAMP" to 0.3f, "WIRES" to 0.55f)),
        Pad("Ride", CalabashVoice.RIDE, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "TUNE" to 0.5f, "DAMP" to 0.3f)),
        Pad("Hat Foot", CalabashVoice.HAT_FOOT, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "TUNE" to 0.45f, "DAMP" to 0.3f)),
        Pad("Conga Open", CalabashVoice.CONGA, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to O)),
        Pad("Conga Slap", CalabashVoice.CONGA, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to 1.0f)),
        Pad("Tumba Open", CalabashVoice.TUMBA, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to O)),
        Pad("Quinto Open", CalabashVoice.QUINTO, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to O)),
        Pad("Conga Heel", CalabashVoice.CONGA, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to 0.0f)),
        Pad("Bongo High", CalabashVoice.BONGO_HIGH, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to O)),
        Pad("Bongo Low", CalabashVoice.BONGO_LOW, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to O)),
        Pad("Timbale", CalabashVoice.TIMBALE, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to 0.0f, "TUNE" to 0.55f)),
        Pad("Paila", CalabashVoice.TIMBALE, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to 1.0f, "TUNE" to 0.55f)),
        Pad("Mambo Bell", CalabashVoice.COWBELL, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "STROKE" to 0.0f, "TUNE" to 0.45f)),
        Pad("Clave", CalabashVoice.CLAVE, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "TUNE" to 0.5f, "DAMP" to 0.3f)),
        Pad("Abanico", CalabashVoice.ABANICO, mapOf("ROOM" to 0.6f, "AGE" to 0.7f, "SPEED" to 0.55f, "TUNE" to 0.55f)),
    )
}
