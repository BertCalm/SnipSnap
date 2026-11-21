package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import kotlin.math.abs

/**
 * KERF's pitched-pad kits (2026-11-21): struck notes from the owner's melodic loops, rung out ([KerfRender.ring]) and
 * laid out, as the owner chose ("b"), as 16 notes of a scale in each kit's key. `prototype/kerf/tonal_bank.py` sorts
 * the loops into families by what they measure and, for each, picks the key and octave whose 16 pads need the least
 * retuning ([KerfBank.tonalKits]).
 *
 * - [Kit.METAL]: struck metal bars and keys (11 loops), E-flat major, MIDI 51 to 77.
 * - [Kit.GONG]: low, long-ringing gongs and tuned pans (3 loops), F minor, MIDI 41 to 67.
 * - [Kit.WOOD]: wooden bars, short-ringing (4 loops), F minor, MIDI 65 to 91.
 *
 * A bell family had only six clean notes and is left out. Every pad is a note at 3 touch zones by 4 takes
 * ([KerfTonal]), scaled by one gain so its loudest cell peaks at [Kerf.PEAK]: the notes of one instrument sit level.
 * Pads are pitched, so they file as TONAL; they are one-shots that ring up to [KerfRender.RING_MAX_SECONDS].
 *
 * Bump [VERSION] when any pad's audio changes.
 */
object KerfTonalKits {
    const val VERSION = 1
    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = 4
    const val PADS = 16

    enum class Kit(val id: String, val title: String, val family: String) {
        METAL("kerf-metal", "Metal", "METAL"),
        GONG("kerf-gong", "Gong", "GONG"),
        WOOD("kerf-wood", "Wood", "WOOD"),
    }

    private val NAMES = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    /** A MIDI note's name, C4 being 60: the pad's name. */
    fun noteName(midi: Int): String = NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1)

    fun data(k: Kit): KerfTonalKitData = KerfBank.tonalKits.getValue(k.family)

    fun bpm(k: Kit): Int = data(k).bpm

    /** One cell of a pad before the pad's gain ([build]). Pure. */
    fun render(k: Kit, pad: Int, velocity: Float, take: Int): Snip {
        val (midi, note) = data(k).pads[pad]
        return KerfTonal.render(note, midi.toDouble(), velocity.toDouble(), take)
    }

    /** The kit's 16 pads, each with its full grid, rendered in parallel. */
    fun build(k: Kit): List<ArrangedPad> = build(k, (0 until PADS).toList())

    /** Only [pads] (0-based) of [k], each levelled by its own grid, so it is the same pad as in a full [build]. */
    fun build(k: Kit, pads: List<Int>): List<ArrangedPad> {
        val d = data(k)
        require(d.pads.size == PADS) { "${k.title} has ${d.pads.size} pads" }
        val cells = pads.flatMap { p -> velocities.indices.flatMap { z -> (0 until TAKES).map { t -> Triple(p, z, t) } } }
        val raw = cells.parallelStream().map { (p, z, t) -> Triple(p, z, t) to render(k, p, velocities[z], t) }
            .collect(java.util.stream.Collectors.toList()).toMap()
        return pads.map { p ->
            val loudest = cells.filter { it.first == p }.maxOf { c -> raw.getValue(c).samples.maxOf { abs(it) } }
            val gain = Kerf.PEAK / loudest.coerceAtLeast(1e-6f)
            val grid = velocities.indices.map { z -> List(TAKES) { t ->
                val s = raw.getValue(Triple(p, z, t)); s.copy(samples = FloatArray(s.samples.size) { s.samples[it] * gain })
            } }
            val (midi, note) = d.pads[p]
            ArrangedPad(
                grid.last().first(), DrumClass.TONAL, displayName = noteName(midi), velocityTakes = grid, muteGroup = 0,
                source = mapOf(
                    "engine" to "KERF", "kit" to k.id, "kitVersion" to VERSION.toString(), "bank" to "tonal",
                    "role" to p.toString(), "rootMidi" to midi.toString(), "fittedRoot" to String.format(java.util.Locale.ROOT, "%.2f", note.rootMidi),
                    "bpm" to d.bpm.toString(), "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to "finite",
                ),
            )
        }
    }
}
