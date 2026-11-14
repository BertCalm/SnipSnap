package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * The CLUTTER genre kits: four sixteen-pad found-sound kits, the owner's choice of genres, accepted as authored on
 * 2026-11-14 ("All are good", "I like it as is"). CLUTTER round 2 (docs/CLUTTER.md).
 *
 * Every pad is one CLUTTER object at the kit's settings, ported pad for pad from the accepted
 * `prototype/clutter/kits.py`, laid out as AutoPlace expects: A01 kick, A02 snare, A03/A04 closed and open hat,
 * A05 second kick, A06 clap or second snare, A07/A08 a second hat pair, A09/A10 toms, then percussion.
 *
 * - [Kit.LOFI], 82 BPM: bedroom lo-fi. Palms and wooden spoons, damped, the phone on the counter.
 * - [Kit.STREET], 120 BPM: busker drumline. Sticks and a ladle, everything loose, outdoors and close.
 * - [Kit.BOOMBAP], 90 BPM: junk boom bap. Suitcase and heel knock, paper-bag and book crack, tight hats.
 * - [Kit.MICRO], 100 BPM: microbeat. Tiny objects tuned up, damped, the phone almost touching.
 *
 * Each pad is a grid of three velocity zones of four takes, rendered by the engine itself ([Clutter] takes a
 * velocity and a take): a softer zone is a different strike, past the lift threshold the loose parts move, and a
 * take is another hand. The zones sit at the velocities the accepted audition played (0.35, 0.65, 1.0); take t
 * is engine take t. The sampler, not the render, makes a soft zone quieter.
 *
 * Level within a zone is CLUTTER's own law, not GATECRASH's (the first take's peak sets one gain the takes
 * share). Measured on these kits, that law left takes of the granular objects 6 to 10 dB apart in peak and up
 * to 7 dB in loudness: a sand jar's or a zip's peak is where a few grains happened to coincide. The accepted
 * audition peak-matched every take, so no take was heard quieter; peak-matching a granular take would instead
 * make the one with no coincidence the loudest. So every take of a zone is matched in loudness over its first
 * [LEVEL_WINDOW_S] (where a hit's loudness lives), and the zone is set so its highest-peaking take peaks at
 * [PAD_PEAK]. A03 chokes A04 and A07 chokes A08, as hats do.
 *
 * No roll or riser: the owner waived the percussion gate for CLUTTER ("I overrule that gate", 2026-11-14).
 */
object ClutterKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 1

    enum class Kit(val id: String, val title: String, val bpm: Int) {
        LOFI("clutter-lofi", "Kitchen Lo-fi", 82),
        STREET("clutter-street", "Street Bucket", 120),
        BOOMBAP("clutter-boombap", "Junk Boom Bap", 90),
        MICRO("clutter-micro", "Microbeat", 100),
    }

    val velocities = listOf(.35f, .65f, 1f)
    const val TAKES = 4
    const val PADS = 16
    /** Where each zone's highest-peaking take peaks. */
    const val PAD_PEAK = .92f
    /** The span over which a zone's takes are matched in loudness. "shape" */
    const val LEVEL_WINDOW_S = .15f

    /** One pad: its name, the object, the kit role it is filed as, and the object's settings. */
    class Pad(val name: String, val voice: ClutterVoice, val cls: DrumClass, val macros: Map<String, Float>)

    /** The hat pairs choke: A03 with A04, A07 with A08. */
    fun muteGroup(role: Int): Int = when (role) { 2, 3 -> 1; 6, 7 -> 2; else -> 0 }

    fun pads(kit: Kit): List<Pad> = when (kit) {
        Kit.LOFI -> lofiPads; Kit.STREET -> streetPads; Kit.BOOMBAP -> boombapPads; Kit.MICRO -> microPads
    }

    fun build(kit: Kit): List<ArrangedPad> {
        val pads = pads(kit)
        return List(PADS) { role ->
            // Each take is rendered once: the zone's level is set from all four, then each is finished.
            val grid = velocities.map { v -> zone(kit, role, v) }
            val pad = pads[role]
            ArrangedPad(
                grid.last().first(), pad.cls, displayName = pad.name, velocityTakes = grid, muteGroup = muteGroup(role),
                source = mapOf(
                    "engine" to "CLUTTER", "kitVersion" to VERSION.toString(), "model" to Clutter.MODEL_VERSION.toString(),
                    "kit" to kit.name, "role" to role.toString(), "bpm" to kit.bpm.toString(), "object" to pad.voice.name,
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
        val g = gains.getOrPut(Triple(kit, role, velocity)) { levels(raws) }
        return raws.mapIndexed { t, x -> KitStrikes.finish(x, g[t]) }
    }

    private fun raw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val pad = pads(kit)[role]
        return Clutter.renderRaw(pad.voice, pad.macros, velocity, take)
    }

    private val gains = ConcurrentHashMap<Triple<Kit, Int, Float>, FloatArray>()

    private fun gains(kit: Kit, role: Int, velocity: Float): FloatArray =
        gains.getOrPut(Triple(kit, role, velocity)) { levels(List(TAKES) { raw(kit, role, velocity, it) }) }

    /**
     * One gain per take: equal loudness over [LEVEL_WINDOW_S] across the zone, the highest resulting peak at
     * [PAD_PEAK]. Measured on the takes as the finish leaves them at unit gain (centred, 20 Hz out).
     */
    private fun levels(raws: List<FloatArray>): FloatArray {
        val n = (LEVEL_WINDOW_S * Dsp.RATE).toInt()
        val finished = raws.map { KitStrikes.finish(it.copyOf(), 1f, ceiling = Float.MAX_VALUE).samples }
        val rms = finished.map { x -> val m = minOf(n, x.size); kotlin.math.sqrt((0 until m).sumOf { x[it].toDouble() * x[it] } / m.coerceAtLeast(1)).toFloat().coerceAtLeast(1e-9f) }
        val crest = finished.indices.maxOf { peakOf(finished[it]) / rms[it] }
        val target = PAD_PEAK / crest
        return FloatArray(raws.size) { target / rms[it] }
    }

    private fun peakOf(x: FloatArray): Float { var p = 0f; for (v in x) p = max(p, abs(v)); return p }

    // ---------- the four kits, generated from prototype/clutter/kits.py (the accepted round 0c) ----------

    private val lofiPads = listOf(
        Pad("Soft Bucket", ClutterVoice.BUCKET, DrumClass.KICK, mapOf("TUNE" to 0.42f, "TOOL" to 0.05f, "DAMP" to 0.45f, "RATTLE" to 0.2f, "MIC" to 0.35f)),
        Pad("Rice Box Brush", ClutterVoice.BOX, DrumClass.SNARE, mapOf("TUNE" to 0.45f, "TOOL" to 0.05f, "RATTLE" to 0.45f, "DAMP" to 0.45f, "MIC" to 0.4f)),
        Pad("Matchbox", ClutterVoice.MATCHBOX, DrumClass.HAT_CLOSED, mapOf("TOOL" to 0.35f, "RATTLE" to 0.45f, "DAMP" to 0.45f, "MIC" to 0.35f)),
        Pad("Loose Keys", ClutterVoice.KEYS, DrumClass.HAT_OPEN, mapOf("TOOL" to 0.2f, "RATTLE" to 0.35f, "DAMP" to 0.45f, "MIC" to 0.4f)),
        Pad("Floor Stomp", ClutterVoice.STOMP, DrumClass.KICK, mapOf("TUNE" to 0.45f, "TOOL" to 0.15f, "RATTLE" to 0.25f, "MIC" to 0.45f)),
        Pad("Board Slap", ClutterVoice.BOARD, DrumClass.CLAP, mapOf("TOOL" to 0.05f, "RATTLE" to 0.25f, "MIC" to 0.45f)),
        Pad("Sand Jar", ClutterVoice.JAR, DrumClass.HAT_CLOSED, mapOf("TOOL" to 0.1f, "DAMP" to 0.5f, "RATTLE" to 0.4f, "MIC" to 0.3f)),
        Pad("Drawer Pushed", ClutterVoice.DRAWER, DrumClass.HAT_OPEN, mapOf("TOOL" to 0.2f, "RATTLE" to 0.3f, "DAMP" to 0.5f, "MIC" to 0.45f)),
        Pad("Tub Low", ClutterVoice.TUB, DrumClass.TOM, mapOf("TUNE" to 0.3f, "TOOL" to 0.1f, "DAMP" to 0.5f, "MIC" to 0.4f)),
        Pad("Pot Thump", ClutterVoice.POT, DrumClass.TOM, mapOf("TUNE" to 0.3f, "TOOL" to 0.1f, "DAMP" to 0.65f, "RATTLE" to 0.2f, "MIC" to 0.4f)),
        Pad("Mug Tink", ClutterVoice.MUG, DrumClass.PERC, mapOf("TOOL" to 0.5f, "DAMP" to 0.45f, "RATTLE" to 0.3f, "MIC" to 0.4f)),
        Pad("Knuckle", ClutterVoice.TABLE, DrumClass.PERC, mapOf("TOOL" to 0.05f, "RATTLE" to 0.2f, "MIC" to 0.4f)),
        Pad("Glass and Ice", ClutterVoice.GLASS, DrumClass.PERC, mapOf("RATTLE" to 0.7f, "DAMP" to 0.35f, "MIC" to 0.45f)),
        Pad("Book Close", ClutterVoice.BOOK, DrumClass.PERC, mapOf("TOOL" to 0.2f, "RATTLE" to 0.3f, "DAMP" to 0.45f, "MIC" to 0.45f)),
        Pad("Bottle Spoon", ClutterVoice.BOTTLE, DrumClass.PERC, mapOf("TUNE" to 0.4f, "TOOL" to 0.4f, "DAMP" to 0.4f, "MIC" to 0.4f)),
        Pad("Slow Zip", ClutterVoice.ZIP, DrumClass.PERC, mapOf("TOOL" to 0.15f, "MIC" to 0.35f)),
    )

    private val streetPads = listOf(
        Pad("Big Bucket", ClutterVoice.BUCKET, DrumClass.KICK, mapOf("TUNE" to 0.38f, "TOOL" to 0.55f, "RATTLE" to 0.6f, "DAMP" to 0.2f, "MIC" to 0.12f)),
        Pad("Bucket Rim", ClutterVoice.BUCKET, DrumClass.SNARE, mapOf("TUNE" to 0.92f, "TOOL" to 0.9f, "RATTLE" to 0.1f, "DAMP" to 0.5f, "MIC" to 0.12f)),
        Pad("Can Tick", ClutterVoice.CAN, DrumClass.HAT_CLOSED, mapOf("TOOL" to 0.9f, "DAMP" to 0.55f, "MIC" to 0.1f)),
        Pad("Bin Lid", ClutterVoice.BINLID, DrumClass.HAT_OPEN, mapOf("TOOL" to 0.75f, "RATTLE" to 0.5f, "MIC" to 0.15f)),
        Pad("Suitcase Stomp", ClutterVoice.SUITCASE, DrumClass.KICK, mapOf("TOOL" to 0.6f, "RATTLE" to 0.7f, "MIC" to 0.12f)),
        Pad("Pan Clang", ClutterVoice.PAN, DrumClass.SNARE, mapOf("TOOL" to 0.95f, "DAMP" to 0.55f, "RATTLE" to 0.6f, "MIC" to 0.12f)),
        Pad("Keys Choked", ClutterVoice.KEYS, DrumClass.HAT_CLOSED, mapOf("TOOL" to 0.9f, "DAMP" to 0.75f, "RATTLE" to 0.3f, "MIC" to 0.1f)),
        Pad("Drawer Slam", ClutterVoice.DRAWER, DrumClass.HAT_OPEN, mapOf("TOOL" to 0.95f, "RATTLE" to 0.8f, "MIC" to 0.15f)),
        Pad("Tub Floor", ClutterVoice.TUB, DrumClass.TOM, mapOf("TUNE" to 0.25f, "TOOL" to 0.7f, "MIC" to 0.12f)),
        Pad("Bucket Mid", ClutterVoice.BUCKET, DrumClass.TOM, mapOf("TUNE" to 0.7f, "TOOL" to 0.7f, "RATTLE" to 0.3f, "DAMP" to 0.35f, "MIC" to 0.12f)),
        Pad("Ladle Pot", ClutterVoice.POT, DrumClass.PERC, mapOf("TOOL" to 0.95f, "RATTLE" to 0.55f, "MIC" to 0.15f)),
        Pad("Stockpot", ClutterVoice.POT, DrumClass.PERC, mapOf("TUNE" to 0.15f, "TOOL" to 0.7f, "RATTLE" to 0.9f, "MIC" to 0.15f)),
        Pad("Bottle Key", ClutterVoice.BOTTLE, DrumClass.PERC, mapOf("TOOL" to 0.95f, "RATTLE" to 0.6f, "MIC" to 0.12f)),
        Pad("Lid Choke", ClutterVoice.BINLID, DrumClass.PERC, mapOf("TOOL" to 0.9f, "DAMP" to 0.85f, "MIC" to 0.1f)),
        Pad("Board Stick", ClutterVoice.BOARD, DrumClass.PERC, mapOf("TOOL" to 0.9f, "RATTLE" to 0.6f, "MIC" to 0.12f)),
        Pad("Bag Pop", ClutterVoice.BAG, DrumClass.CLAP, mapOf("TOOL" to 0.7f, "MIC" to 0.15f)),
    )

    private val boombapPads = listOf(
        Pad("Suitcase Knock", ClutterVoice.SUITCASE, DrumClass.KICK, mapOf("TUNE" to 0.4f, "TOOL" to 0.3f, "DAMP" to 0.35f, "RATTLE" to 0.25f, "MIC" to 0.3f)),
        Pad("Paper Bag Crack", ClutterVoice.BAG, DrumClass.SNARE, mapOf("TOOL" to 0.6f, "RATTLE" to 0.35f, "MIC" to 0.35f)),
        Pad("Tight Jar", ClutterVoice.JAR, DrumClass.HAT_CLOSED, mapOf("TOOL" to 0.4f, "DAMP" to 0.65f, "RATTLE" to 0.4f, "MIC" to 0.25f)),
        Pad("Key Shake", ClutterVoice.KEYS, DrumClass.HAT_OPEN, mapOf("TOOL" to 0.55f, "RATTLE" to 0.55f, "DAMP" to 0.35f, "MIC" to 0.3f)),
        Pad("Heel Stomp", ClutterVoice.STOMP, DrumClass.KICK, mapOf("TUNE" to 0.35f, "TOOL" to 0.7f, "RATTLE" to 0.2f, "MIC" to 0.3f)),
        Pad("Book Slam", ClutterVoice.BOOK, DrumClass.CLAP, mapOf("TOOL" to 0.75f, "RATTLE" to 0.55f, "MIC" to 0.35f)),
        Pad("Matchbox Flick", ClutterVoice.MATCHBOX, DrumClass.HAT_CLOSED, mapOf("TOOL" to 0.85f, "DAMP" to 0.5f, "MIC" to 0.25f)),
        Pad("Drawer Shut", ClutterVoice.DRAWER, DrumClass.HAT_OPEN, mapOf("TOOL" to 0.4f, "RATTLE" to 0.45f, "DAMP" to 0.4f, "MIC" to 0.35f)),
        Pad("Tub Low", ClutterVoice.TUB, DrumClass.TOM, mapOf("TUNE" to 0.2f, "TOOL" to 0.4f, "DAMP" to 0.45f, "MIC" to 0.3f)),
        Pad("Dead Pan", ClutterVoice.PAN, DrumClass.TOM, mapOf("TOOL" to 0.5f, "DAMP" to 0.75f, "RATTLE" to 0.3f, "MIC" to 0.3f)),
        Pad("Board Rim", ClutterVoice.BOARD, DrumClass.PERC, mapOf("TOOL" to 0.8f, "DAMP" to 0.5f, "MIC" to 0.3f)),
        Pad("Knuckle Fork", ClutterVoice.TABLE, DrumClass.PERC, mapOf("TOOL" to 0.9f, "RATTLE" to 0.6f, "MIC" to 0.3f)),
        Pad("Rice Box Crack", ClutterVoice.BOX, DrumClass.SNARE, mapOf("TUNE" to 0.35f, "TOOL" to 0.4f, "RATTLE" to 0.8f, "DAMP" to 0.3f, "MIC" to 0.35f)),
        Pad("Can Flick", ClutterVoice.CAN, DrumClass.PERC, mapOf("TOOL" to 0.6f, "MIC" to 0.3f)),
        Pad("Low Bottle", ClutterVoice.BOTTLE, DrumClass.PERC, mapOf("TUNE" to 0.25f, "TOOL" to 0.6f, "DAMP" to 0.35f, "MIC" to 0.3f)),
        Pad("Mug Spoon", ClutterVoice.MUG, DrumClass.PERC, mapOf("TOOL" to 0.9f, "RATTLE" to 0.6f, "MIC" to 0.3f)),
    )

    private val microPads = listOf(
        Pad("Tiny Bucket", ClutterVoice.BUCKET, DrumClass.KICK, mapOf("TUNE" to 0.78f, "TOOL" to 0.35f, "DAMP" to 0.65f, "RATTLE" to 0f, "MIC" to 0.03f)),
        Pad("Small Bag Pop", ClutterVoice.BAG, DrumClass.SNARE, mapOf("TUNE" to 0.85f, "TOOL" to 0.1f, "RATTLE" to 0.2f, "MIC" to 0.03f)),
        Pad("Switch", ClutterVoice.SWITCH, DrumClass.HAT_CLOSED, mapOf("TOOL" to 0.8f, "MIC" to 0.02f)),
        Pad("Short Zip", ClutterVoice.ZIP, DrumClass.HAT_OPEN, mapOf("TOOL" to 0.95f, "TUNE" to 0.7f, "RATTLE" to 0f, "MIC" to 0.03f)),
        Pad("Box Thump", ClutterVoice.BOX, DrumClass.KICK, mapOf("TUNE" to 0.85f, "TOOL" to 0.3f, "RATTLE" to 0f, "DAMP" to 0.7f, "MIC" to 0.03f)),
        Pad("Board Tap", ClutterVoice.BOARD, DrumClass.CLAP, mapOf("TUNE" to 0.85f, "TOOL" to 0.5f, "DAMP" to 0.6f, "MIC" to 0.03f)),
        Pad("Sand Tick", ClutterVoice.JAR, DrumClass.HAT_CLOSED, mapOf("TOOL" to 0.05f, "DAMP" to 0.8f, "RATTLE" to 0.25f, "TUNE" to 0.8f, "MIC" to 0.02f)),
        Pad("Tiny Keys", ClutterVoice.KEYS, DrumClass.HAT_OPEN, mapOf("TUNE" to 0.9f, "RATTLE" to 0.1f, "DAMP" to 0.55f, "TOOL" to 0.8f, "MIC" to 0.03f)),
        Pad("Mug High", ClutterVoice.MUG, DrumClass.TOM, mapOf("TUNE" to 0.85f, "DAMP" to 0.55f, "RATTLE" to 0.25f, "MIC" to 0.03f)),
        Pad("Can High", ClutterVoice.CAN, DrumClass.TOM, mapOf("TUNE" to 0.85f, "DAMP" to 0.5f, "MIC" to 0.03f)),
        Pad("Glass Tick", ClutterVoice.GLASS, DrumClass.PERC, mapOf("TUNE" to 0.9f, "DAMP" to 0.7f, "RATTLE" to 0.3f, "MIC" to 0.03f)),
        Pad("Bottle Tick", ClutterVoice.BOTTLE, DrumClass.PERC, mapOf("TUNE" to 0.9f, "DAMP" to 0.65f, "RATTLE" to 0f, "MIC" to 0.03f)),
        Pad("Fork Tap", ClutterVoice.TABLE, DrumClass.PERC, mapOf("TOOL" to 1f, "TUNE" to 0.8f, "RATTLE" to 0.3f, "DAMP" to 0.5f, "MIC" to 0.03f)),
        Pad("Coin Jar", ClutterVoice.JAR, DrumClass.PERC, mapOf("TOOL" to 0.95f, "RATTLE" to 0.2f, "TUNE" to 0.7f, "MIC" to 0.03f)),
        Pad("Slow Zip", ClutterVoice.ZIP, DrumClass.PERC, mapOf("TOOL" to 0.05f, "TUNE" to 0.85f, "MIC" to 0.03f)),
        Pad("Switch Slow", ClutterVoice.SWITCH, DrumClass.PERC, mapOf("TOOL" to 0.1f, "RATTLE" to 0.8f, "TUNE" to 0.75f, "MIC" to 0.03f)),
    )
}
