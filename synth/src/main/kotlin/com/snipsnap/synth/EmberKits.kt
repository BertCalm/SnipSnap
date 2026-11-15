package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The EMBER kits: the club kit and three genre kits from one night in 1962, sixteen pads each, generated pad for pad
 * from `prototype/ember/kits.py`. EMBER round 2 (docs/EMBER.md). The owner approved the voices' sound on 2026-11-15
 * ("I've heard the sound and approve", round 1b); the kits are the four the owner heard in Python at round 0e.
 *
 * - [Kit.CLUB], 138 BPM: hard bop on sticks. An open kick feathered under the band, a high crisp snare, the riveted
 *   ride carrying the time, the hat foot on 2 and 4.
 * - [Kit.AFTER_HOURS], 66 BPM: a ballad on brushes. Slow sweeps, taps on 2 and 4, a far sizzle, two people snapping.
 * - [Kit.SOUL], 104 BPM: the organ-trio set. A fat loose backbeat, tambourine and claps, congas, the chain warmer.
 * - [Kit.FREE], 80 BPM: the late set. Mallets on toms tuned wide, a bowed cymbal, snares off, an elbow in the floor tom.
 *
 * Each pad is a grid of three velocity zones of four takes, rendered by the engine ([Ember] takes a velocity and a
 * take): a softer zone is a softer stroke (the stick's contact, the wires, the swell's arc), and a take is another
 * strike. The zones sit at the velocities the approved audition played (0.35, 0.65, 1.0); take t is engine take t.
 * The sampler, not the render, makes a soft zone quieter.
 *
 * Level within a zone is CLUTTER's law, not the audition's: the takes of a zone are matched in loudness and the zone
 * is set so its highest-peaking take peaks at [PAD_PEAK]. See [levels].
 *
 * The pads keep the kits' own layout, not AutoPlace's: the approved kits put the roll or riser on A15/A16 (the
 * percussion gate) and the brushes where a brush player reaches. Hats choke as one hi-hat: each kit's pads on the
 * same hi-hat share a mute group (`CHOKES` in kits.py, made mutual as MPC mute groups are).
 */
object EmberKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 1

    enum class Kit(val id: String, val title: String, val bpm: Int) {
        CLUB("ember", "EMBER", 138),
        AFTER_HOURS("ember-after-hours", "EMBER After Hours", 66),
        SOUL("ember-soul-jazz", "EMBER Soul Jazz", 104),
        FREE("ember-free", "EMBER Free", 80),
    }

    val velocities = listOf(.35f, .65f, 1f)
    const val TAKES = 4
    const val PADS = 16
    /** Where each zone's highest-peaking take peaks. */
    const val PAD_PEAK = .92f
    /** The span over which a struck pad's takes are matched in loudness: where a hit's loudness lives. "shape" */
    const val LEVEL_WINDOW_S = .15f
    /** The moving voices, matched over the whole render: their first 150 ms is a soft start, not their level. */
    val WHOLE = setOf(EmberVoice.BRUSH_SWEEP, EmberVoice.PRESS_ROLL, EmberVoice.BRUSH_ROLL, EmberVoice.CYMBAL_SWELL, EmberVoice.BOWED_CYMBAL)

    /** One pad: its name, the voice, and the voice's settings. Its kit role is the voice's own class. */
    class Pad(val name: String, val voice: EmberVoice, val macros: Map<String, Float>)

    /** The pads on one hi-hat choke one another (kits.py `CHOKES`): 1 for the kit's hat, 0 for everything else. */
    fun muteGroup(kit: Kit, role: Int): Int = if (role in hats.getValue(kit)) 1 else 0

    private val hats = mapOf(
        Kit.CLUB to setOf(2, 3, 10),        // A03 foot, A04 open, A11 stick on the closed hat
        Kit.AFTER_HOURS to setOf(2, 12),    // A03 foot, A13 open
        Kit.SOUL to setOf(2, 3, 6),         // A03 tight, A04 open, A07 foot
        Kit.FREE to setOf(11, 12),          // A12 loose hats, A13 foot
    )

    fun pads(kit: Kit): List<Pad> = when (kit) {
        Kit.CLUB -> clubPads; Kit.AFTER_HOURS -> afterHoursPads; Kit.SOUL -> soulPads; Kit.FREE -> freePads
    }

    /** Every pad of [kit]. The pads render in parallel: one strike is 0.1 to 1.2 s of engine. */
    fun build(kit: Kit): List<ArrangedPad> {
        val pads = pads(kit)
        val grids = (0 until PADS).toList().parallelStream().map { role -> velocities.map { v -> zone(kit, role, v) } }.toList()
        return List(PADS) { role ->
            val grid = grids[role]
            val pad = pads[role]
            ArrangedPad(
                grid.last().first(), pad.voice.drumClass, displayName = pad.name, velocityTakes = grid, muteGroup = muteGroup(kit, role),
                source = mapOf(
                    "engine" to "EMBER", "kitVersion" to VERSION.toString(), "model" to Ember.MODEL_VERSION.toString(),
                    "kit" to kit.name, "role" to role.toString(), "bpm" to kit.bpm.toString(), "voice" to pad.voice.name,
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(), "articulation" to "finite",
                ),
            )
        }
    }

    /** The finished pad at one velocity and take. Pure: the same arguments give the same bytes. */
    fun render(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        return KitStrikes.finish(raw(kit, role, velocity, take), gains(kit, role, velocity)[take])
    }

    /** A zone's four finished takes, each rendered once. The same bytes as [render] take by take. */
    private fun zone(kit: Kit, role: Int, velocity: Float): List<Snip> {
        val raws = List(TAKES) { raw(kit, role, velocity, it) }
        val g = gains.getOrPut(Triple(kit, role, velocity)) { levels(pads(kit)[role].voice, raws) }
        return raws.mapIndexed { t, x -> KitStrikes.finish(x, g[t]) }
    }

    private fun raw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val pad = pads(kit)[role]
        return Ember.renderRaw(pad.voice, pad.macros, velocity, take)
    }

    private val gains = ConcurrentHashMap<Triple<Kit, Int, Float>, FloatArray>()

    private fun gains(kit: Kit, role: Int, velocity: Float): FloatArray =
        gains.getOrPut(Triple(kit, role, velocity)) { levels(pads(kit)[role].voice, List(TAKES) { raw(kit, role, velocity, it) }) }

    /**
     * One gain per take: equal loudness across the zone, the highest resulting peak at [PAD_PEAK]. Loudness is
     * measured over [LEVEL_WINDOW_S] for a struck pad and over the whole render for the [WHOLE] voices, on the takes
     * as the finish leaves them at unit gain (centred, 20 Hz out).
     *
     * The approved audition peak-matched every take instead. Measured on these kits (docs/EMBER.md, round 2), that
     * left a zone's takes up to 5 dB apart in loudness: the hats 2 to 5 dB, the sweeps up to 4.8 and the bow 5.4.
     * A hat repeated on the eighths would jump. CLUTTER met the same and moved to loudness (ClutterKits).
     */
    private fun levels(voice: EmberVoice, raws: List<FloatArray>): FloatArray {
        val finished = raws.map { KitStrikes.finish(it.copyOf(), 1f, ceiling = Float.MAX_VALUE).samples }
        val window = if (voice in WHOLE) Int.MAX_VALUE else (LEVEL_WINDOW_S * Dsp.RATE).toInt()
        val rms = finished.map { x ->
            val m = minOf(window, x.size)
            sqrt((0 until m).sumOf { x[it].toDouble() * x[it] } / m.coerceAtLeast(1)).toFloat().coerceAtLeast(1e-9f)
        }
        val crest = finished.indices.maxOf { peakOf(finished[it]) / rms[it] }
        val target = PAD_PEAK / crest
        return FloatArray(raws.size) { target / rms[it] }
    }

    private fun peakOf(x: FloatArray): Float { var p = 0f; for (v in x) p = max(p, abs(v)); return p }

    // ---------- the four kits, generated from prototype/ember/kits.py (round 0e, approved as voices at round 1b) ----------

    private val clubPads = listOf(
        Pad("Kick", EmberVoice.KICK, mapOf("TUNE" to 0.55f, "DAMP" to 0.25f)),
        Pad("Snare", EmberVoice.SNARE, emptyMap()),
        Pad("Hat Foot", EmberVoice.HAT_FOOT, emptyMap()),
        Pad("Hat Open", EmberVoice.HAT_OPEN, emptyMap()),
        Pad("Ride", EmberVoice.RIDE, emptyMap()),
        Pad("Ride Bell", EmberVoice.RIDE_BELL, emptyMap()),
        Pad("Cross Stick", EmberVoice.CROSS_STICK, emptyMap()),
        Pad("Rimshot", EmberVoice.RIMSHOT, emptyMap()),
        Pad("Rack Tom", EmberVoice.TOM_RACK, emptyMap()),
        Pad("Floor Tom", EmberVoice.TOM_FLOOR, emptyMap()),
        Pad("Hat Stick", EmberVoice.HAT_CLOSED, emptyMap()),
        Pad("Snaps", EmberVoice.SNAPS, mapOf("CROWD" to 0.5f)),
        Pad("Sticks", EmberVoice.STICKS, emptyMap()),
        Pad("Elbow Tom", EmberVoice.ELBOW_TOM, emptyMap()),
        Pad("Press Roll", EmberVoice.PRESS_ROLL, emptyMap()),
        Pad("Cymbal Swell", EmberVoice.CYMBAL_SWELL, emptyMap()),
    )

    private val afterHoursPads = listOf(
        Pad("Soft Kick", EmberVoice.KICK, mapOf("TUNE" to 0.45f, "DAMP" to 0.5f, "BUZZ" to 0.3f, "DISTANCE" to 0.4f, "WARMTH" to 0.35f)),
        Pad("Brush Tap", EmberVoice.BRUSH_TAP, mapOf("BUZZ" to 0.6f, "DISTANCE" to 0.4f, "WARMTH" to 0.35f)),
        Pad("Hat Foot", EmberVoice.HAT_FOOT, mapOf("DAMP" to 0.55f, "TUNE" to 0.45f, "DISTANCE" to 0.45f, "WARMTH" to 0.35f)),
        Pad("Slow Sweep", EmberVoice.BRUSH_SWEEP, mapOf("SPEED" to 0.1f, "DISTANCE" to 0.4f, "WARMTH" to 0.35f)),
        Pad("Brush Ride", EmberVoice.BRUSH_RIDE, mapOf("DISTANCE" to 0.45f, "WARMTH" to 0.35f)),
        Pad("Far Sizzle", EmberVoice.SIZZLE, mapOf("TUNE" to 0.3f, "DAMP" to 0.3f, "DISTANCE" to 0.6f, "WARMTH" to 0.35f)),
        Pad("Quick Sweep", EmberVoice.BRUSH_SWEEP, mapOf("SPEED" to 0.8f, "TUNE" to 0.4f, "BUZZ" to 0.7f, "DISTANCE" to 0.4f, "WARMTH" to 0.35f)),
        Pad("Soft Cross Stick", EmberVoice.CROSS_STICK, mapOf("TUNE" to 0.35f, "DAMP" to 0.55f, "DISTANCE" to 0.5f, "WARMTH" to 0.35f)),
        Pad("Brushed Tom", EmberVoice.BRUSH_TOM, mapOf("TUNE" to 0.65f, "DISTANCE" to 0.45f, "WARMTH" to 0.35f)),
        Pad("Brushed Floor", EmberVoice.BRUSH_TOM, mapOf("TUNE" to 0.3f, "DAMP" to 0.2f, "DISTANCE" to 0.45f, "WARMTH" to 0.35f)),
        Pad("Mallet Tom", EmberVoice.MALLET_TOM, mapOf("TUNE" to 0.45f, "DAMP" to 0.2f, "DISTANCE" to 0.5f, "WARMTH" to 0.35f)),
        Pad("Two Snapping", EmberVoice.SNAPS, mapOf("CROWD" to 0.2f, "TUNE" to 0.45f, "DISTANCE" to 0.6f, "WARMTH" to 0.35f)),
        Pad("Hat Open", EmberVoice.HAT_OPEN, mapOf("DAMP" to 0.55f, "TUNE" to 0.45f, "DISTANCE" to 0.5f, "WARMTH" to 0.35f)),
        Pad("Loose Brush", EmberVoice.BRUSH_TAP, mapOf("TUNE" to 0.35f, "BUZZ" to 0.95f, "DAMP" to 0.5f, "DISTANCE" to 0.45f, "WARMTH" to 0.35f)),
        Pad("Brush Roll", EmberVoice.BRUSH_ROLL, mapOf("SPEED" to 0.3f, "DISTANCE" to 0.45f, "WARMTH" to 0.35f)),
        Pad("Far Swell", EmberVoice.CYMBAL_SWELL, mapOf("SPEED" to 0.3f, "TUNE" to 0.4f, "DISTANCE" to 0.65f, "WARMTH" to 0.35f)),
    )

    private val soulPads = listOf(
        Pad("Kick", EmberVoice.KICK, mapOf("TUNE" to 0.4f, "DAMP" to 0.6f, "BUZZ" to 0.3f, "DISTANCE" to 0.15f, "WARMTH" to 0.6f)),
        Pad("Fat Snare", EmberVoice.SNARE, mapOf("TUNE" to 0.3f, "DAMP" to 0.55f, "BUZZ" to 0.75f, "DISTANCE" to 0.2f, "WARMTH" to 0.6f)),
        Pad("Hat Tight", EmberVoice.HAT_CLOSED, mapOf("DAMP" to 0.65f, "TUNE" to 0.55f, "DISTANCE" to 0.2f, "WARMTH" to 0.6f)),
        Pad("Hat Open", EmberVoice.HAT_OPEN, mapOf("DAMP" to 0.35f, "TUNE" to 0.55f, "DISTANCE" to 0.25f, "WARMTH" to 0.6f)),
        Pad("Sizzle Crash", EmberVoice.SIZZLE, mapOf("TUNE" to 0.6f, "DAMP" to 0.55f, "DISTANCE" to 0.3f, "WARMTH" to 0.6f)),
        Pad("Tambourine", EmberVoice.TAMBOURINE, mapOf("DISTANCE" to 0.3f, "WARMTH" to 0.6f)),
        Pad("Hat Foot", EmberVoice.HAT_FOOT, mapOf("TUNE" to 0.55f, "DISTANCE" to 0.25f, "WARMTH" to 0.6f)),
        Pad("Claps", EmberVoice.CLAPS, mapOf("CROWD" to 0.6f, "DISTANCE" to 0.5f, "WARMTH" to 0.6f)),
        Pad("Conga High", EmberVoice.CONGA, mapOf("TUNE" to 0.65f, "SLAP" to 0.2f, "DISTANCE" to 0.3f, "WARMTH" to 0.6f)),
        Pad("Conga Low", EmberVoice.CONGA, mapOf("TUNE" to 0.3f, "SLAP" to 0.1f, "DISTANCE" to 0.3f, "WARMTH" to 0.6f)),
        Pad("Rimshot", EmberVoice.RIMSHOT, mapOf("TUNE" to 0.4f, "BUZZ" to 0.65f, "DAMP" to 0.45f, "DISTANCE" to 0.2f, "WARMTH" to 0.6f)),
        Pad("Cross Stick", EmberVoice.CROSS_STICK, mapOf("TUNE" to 0.45f, "DISTANCE" to 0.2f, "WARMTH" to 0.6f)),
        Pad("Conga Slap", EmberVoice.CONGA, mapOf("TUNE" to 0.55f, "SLAP" to 0.9f, "DISTANCE" to 0.3f, "WARMTH" to 0.6f)),
        Pad("Floor Tom", EmberVoice.TOM_FLOOR, mapOf("TUNE" to 0.35f, "DAMP" to 0.45f, "DISTANCE" to 0.25f, "WARMTH" to 0.6f)),
        Pad("Snare Roll", EmberVoice.PRESS_ROLL, mapOf("TUNE" to 0.35f, "SPEED" to 0.7f, "BUZZ" to 0.75f, "DISTANCE" to 0.25f, "WARMTH" to 0.6f)),
        Pad("The Room Claps", EmberVoice.CLAPS, mapOf("CROWD" to 1.0f, "TUNE" to 0.45f, "DISTANCE" to 0.85f, "WARMTH" to 0.6f)),
    )

    private val freePads = listOf(
        Pad("Open Kick", EmberVoice.KICK, mapOf("TUNE" to 0.7f, "DAMP" to 0.0f, "BUZZ" to 0.8f, "DISTANCE" to 0.5f, "WARMTH" to 0.25f)),
        Pad("Snares Off", EmberVoice.SNARE, mapOf("BUZZ" to 0.0f, "TUNE" to 0.6f, "DAMP" to 0.1f, "DISTANCE" to 0.45f, "WARMTH" to 0.25f)),
        Pad("Mallet Tom High", EmberVoice.MALLET_TOM, mapOf("TUNE" to 0.8f, "DAMP" to 0.1f, "BUZZ" to 0.3f, "DISTANCE" to 0.5f, "WARMTH" to 0.25f)),
        Pad("Mallet Tom Low", EmberVoice.MALLET_TOM, mapOf("TUNE" to 0.2f, "DAMP" to 0.1f, "BUZZ" to 0.3f, "DISTANCE" to 0.5f, "WARMTH" to 0.25f)),
        Pad("Bowed Cymbal", EmberVoice.BOWED_CYMBAL, mapOf("DISTANCE" to 0.5f, "WARMTH" to 0.25f)),
        Pad("Bowed, Biting", EmberVoice.BOWED_CYMBAL, mapOf("TUNE" to 0.3f, "PRESSURE" to 0.9f, "DISTANCE" to 0.5f, "WARMTH" to 0.25f)),
        Pad("Elbow Tom", EmberVoice.ELBOW_TOM, mapOf("BEND" to 0.9f, "TUNE" to 0.45f, "DISTANCE" to 0.5f, "WARMTH" to 0.25f)),
        Pad("Hard Rimshot", EmberVoice.RIMSHOT, mapOf("TUNE" to 0.65f, "DAMP" to 0.1f, "BUZZ" to 0.9f, "DISTANCE" to 0.45f, "WARMTH" to 0.25f)),
        Pad("Sticks", EmberVoice.STICKS, mapOf("TUNE" to 0.65f, "DAMP" to 0.1f, "DISTANCE" to 0.5f, "WARMTH" to 0.25f)),
        Pad("Bell, Open", EmberVoice.RIDE_BELL, mapOf("TUNE" to 0.7f, "DAMP" to 0.1f, "DISTANCE" to 0.5f, "WARMTH" to 0.25f)),
        Pad("Fast Sweep", EmberVoice.BRUSH_SWEEP, mapOf("SPEED" to 1.0f, "BUZZ" to 0.9f, "DISTANCE" to 0.45f, "WARMTH" to 0.25f)),
        Pad("Loose Hats", EmberVoice.HAT_OPEN, mapOf("BUZZ" to 0.6f, "DAMP" to 0.1f, "TUNE" to 0.4f, "DISTANCE" to 0.5f, "WARMTH" to 0.25f)),
        Pad("Hat Foot", EmberVoice.HAT_FOOT, mapOf("DAMP" to 0.1f, "BUZZ" to 0.4f, "TUNE" to 0.4f, "DISTANCE" to 0.5f, "WARMTH" to 0.25f)),
        Pad("One Snapping", EmberVoice.SNAPS, mapOf("CROWD" to 0.0f, "TUNE" to 0.6f, "DISTANCE" to 0.35f, "WARMTH" to 0.25f)),
        Pad("Swell", EmberVoice.CYMBAL_SWELL, mapOf("SPEED" to 0.8f, "TUNE" to 0.3f, "DISTANCE" to 0.55f, "WARMTH" to 0.25f)),
        Pad("Floor Tom, Snares On", EmberVoice.TOM_FLOOR, mapOf("TUNE" to 0.2f, "DAMP" to 0.0f, "BUZZ" to 1.0f, "DISTANCE" to 0.5f, "WARMTH" to 0.25f)),
    )
}
