package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Ear
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Tempo
import com.snipsnap.kit.GrooveEdit
import com.snipsnap.kit.GrooveFeel
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.GrooveVariations
import com.snipsnap.kit.Kit
import com.snipsnap.kit.Names
import com.snipsnap.kit.PocketStore
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File

/**
 * A snip read as a rhythm instead of a sound (wave ZZ): the CLI's
 * `learn` verb for the phone. The Ear finds the hits, the classifier
 * names them, the tempo gives them a grid, and the open kit's own pads
 * play them back — a beatboxed bar, a recorded beat, a shared voice
 * memo, all the same reading. Everything refuses in words: no confident
 * tempo, no beat heard, nothing that maps onto a pad.
 *
 * [feel] is the same reading kept as timing and accent alone — a real
 * drummer's pocket off the record, bottled as a `.pocket` file and
 * poured over the kit's own pattern as PROG E, so A–D stay untouched.
 */
object ReadGroove {

    const val TARGET_RATE = 44_100

    /** A hit the classifier is at least this sure of gets a pad; the rest are counted, not invented. */
    const val SURE_CONFIDENCE = 0.5f

    data class Reading(
        val clip: Mpc3Clip,
        val hits: Int,
        val bars: Int,
        val bpm: Float,
        /** Hits heard but not sure enough to place. */
        val uncertain: Int,
        /** Classes heard with no pad to land on. */
        val unmapped: List<DrumClass>,
    )

    data class Felt(
        val pocket: PocketStore.Pocket,
        /** How many of the 16 positions the record actually played. */
        val covered: Int,
        /** The kit's pattern with the feel poured over it, already PROG E. */
        val progE: Mpc3Clip,
    )

    fun read(snip: Snip, kit: Kit, name: String): Reading {
        require(snip.frameCount > 0) { "nothing to hear - the tape is empty" }
        require(kit.pads.isNotEmpty()) { "the kit has no pads for the beat to land on" }
        val (tempo, sure, uncertain) = listen(snip)
        val framesPerPulse = 60.0 / tempo.bpm * TARGET_RATE / 960.0
        val unmapped = linkedSetOf<DrumClass>()
        val notes = sure.mapNotNull { hit ->
            val slot = padFor(kit, hit.drumClass)
            if (slot == null) {
                unmapped += hit.drumClass
                null
            } else {
                Mpc3Note(
                    note = 35 + slot,
                    timePulses = Math.round(hit.frame / framesPerPulse),
                    velocity = hit.velocity,
                )
            }
        }
        require(notes.isNotEmpty()) {
            "nothing landed - " + when {
                unmapped.isNotEmpty() -> "no pads for ${unmapped.joinToString(", ") { it.name.lowercase() }}"
                else -> "$uncertain uncertain hit(s), none sure enough to place"
            }
        }
        val bars = barsOf(notes)
        val clip = Mpc3Clip("${Names.sanitizeStem(name)} Learned", bars, notes)
        return Reading(clip, notes.size, bars, tempo.bpm, uncertain, unmapped.toList())
    }

    /**
     * Lands a reading as the kit's captured base: the standard variation
     * set is rewritten from it, and an existing PROG E rides along
     * untouched — the user's own edits are never the price of a new ear.
     */
    fun land(kitDir: File, reading: Reading): File {
        val e = GrooveEdit.load(kitDir)
        return GrooveStore.save(kitDir, GrooveVariations.standard(reading.clip) + listOfNotNull(e))
    }

    /**
     * The feel alone, bottled and poured: the snip's timing and accent
     * per 16th over the kit's captured base, landed as PROG E (replacing
     * any E, as an explicit re-fork does). Refuses when the kit has no
     * pattern to pour it on.
     */
    fun feel(snip: Snip, kitDir: File, name: String): Felt {
        require(snip.frameCount > 0) { "nothing to hear - the tape is empty" }
        val base = GrooveStore.load(kitDir).firstOrNull { !GrooveEdit.isProgE(it) }
            ?: throw IllegalArgumentException("no pattern to pour it on - read a groove or capture one first")
        val (tempo, sure, _) = listen(snip)
        require(sure.size >= 2) { "too few confident hits to bottle a feel" }
        val framesPerPulse = 60.0 / tempo.bpm * TARGET_RATE / 960.0
        val notes = sure.map {
            Mpc3Note(note = 36, timePulses = Math.round(it.frame / framesPerPulse), velocity = it.velocity)
        }
        val stem = Names.sanitizeStem(name)
        val template = GrooveFeel.extract(Mpc3Clip("$stem Pocket", barsOf(notes), notes))
        val covered = template.offsets.count { it != null }
        require(covered >= 2) { "the record only played ${covered} position(s) - not a feel, a hit" }
        val felt = GrooveFeel.apply(template, base)
        val progE = GrooveEdit.fork(kitDir, felt, replace = true)
        return Felt(PocketStore.Pocket(stem, template), covered, progE)
    }

    /** Keeps the bottled feel on the shelf's own rack, `<root>/Pockets/<name>.pocket`, fresh-named on collision. */
    fun keepPocket(pocket: PocketStore.Pocket, root: File): File {
        val dir = File(root, POCKETS_DIR).apply { mkdirs() }
        var file = File(dir, "${pocket.name}.${PocketStore.EXTENSION}")
        var n = 2
        while (file.exists()) {
            file = File(dir, "${pocket.name} $n.${PocketStore.EXTENSION}")
            n++
        }
        return PocketStore.save(pocket, file)
    }

    const val POCKETS_DIR = "Pockets"

    private data class Heard(val tempo: com.snipsnap.audio.TempoEstimate, val sure: List<Ear.Hit>, val uncertain: Int)

    private fun listen(source: Snip): Heard {
        val snip = if (source.sampleRate == TARGET_RATE) source else Resampler.resample(source, TARGET_RATE)
        val tempo = Tempo.estimate(snip)?.takeIf { it.confidence >= 0.3f }
            ?: throw IllegalArgumentException("no confident tempo - the ear needs a grid to write onto")
        val hits = Ear.listen(snip)
        require(hits.size >= 2) { "no beat heard - the ear finds hits, not tones" }
        val sure = hits.filter { it.confidence >= SURE_CONFIDENCE }
        return Heard(tempo, sure, hits.size - sure.size)
    }

    private fun barsOf(notes: List<Mpc3Note>): Int =
        ((notes.maxOf { it.timePulses } / Mpc3Clip.PULSES_PER_BAR) + 1).toInt().coerceIn(1, 64)

    /** The class's pad, with the preview's own sensible stand-ins. */
    fun padFor(kit: Kit, dc: DrumClass): Int? {
        val exact = kit.pads.firstOrNull { it.drumClass == dc }?.slot
        if (exact != null) return exact
        val fallback = when (dc) {
            DrumClass.SNARE -> DrumClass.CLAP
            DrumClass.CLAP -> DrumClass.SNARE
            DrumClass.HAT_CLOSED -> DrumClass.HAT_OPEN
            DrumClass.HAT_OPEN -> DrumClass.HAT_CLOSED
            DrumClass.PERC -> DrumClass.HAT_CLOSED
            else -> null
        } ?: return null
        return kit.pads.firstOrNull { it.drumClass == fallback }?.slot
    }
}
