package com.snipsnap.shell

import com.snipsnap.mpc3.*
import com.snipsnap.synth.*
import java.io.File
import kotlin.math.*

/** The seven breakcore kits that follow SPLINTER, each delivered through [BreakcoreDelivery].
 * The grooves are the ones the owner auditioned (the first three on 2026-11-18, the second four on 2026-11-19): (1-based slot, beat, velocity). */
object BreakcoreFamilyDelivery {
    private fun clip(kit: BreakcoreKit, hits: List<Triple<Int, Double, Double>>): Mpc3Clip {
        val notes = hits.sortedBy { it.second }.map { (slot, beat, v) -> Mpc3Note(Mpc3Note.noteFor(slot), (beat * 960).roundToLong(), v.toFloat()) }
        return Mpc3Clip(kit.title, ceil((hits.maxOf { it.second } + 1) / 4).toInt(), notes)
    }

    /** Bars 1-2: the break as played, every slice at its own cut. Bar 3: re-sequenced.
     * Bar 4: the slice repeat. Bar 5: the break rush. Bar 6: the landing. */
    fun shatterGroove(): List<Triple<Int, Double, Double>> = buildList {
        for (i in 0..15) add(Triple(i + 1, Shatter.cuts[i] / 4.0, 1.0))
        listOf(9 to 8.0, 10 to 9.0, 4 to 9.5, 2 to 9.75, 1 to 10.0, 12 to 10.5, 12 to 10.75, 3 to 11.0, 16 to 11.5).forEach { (s, b) -> add(Triple(s, b, if (s == 2) .65 else 1.0)) }
        add(Triple(17, 12.0, 1.0)); add(Triple(18, 16.0, 1.0)); add(Triple(9, 20.0, 1.0)); add(Triple(10, 21.0, 1.0)); add(Triple(7, 22.5, .65)); add(Triple(15, 23.0, 1.0))
    }

    fun soundclashGroove(): List<Triple<Int, Double, Double>> = buildList {
        for (bar in 0..1) { val b = bar * 4.0
            add(Triple(1, b, 1.0)); add(Triple(4, b + 1, 1.0)); add(Triple(1, b + 2.5, .8)); add(Triple(4, b + 3, 1.0))
            for (i in 0..7) add(Triple(12, b + i * .5, if (i % 2 == 0) .65 else .3))
        }
        add(Triple(14, 0.0, 1.0)); add(Triple(5, 2.75, .8)); add(Triple(13, 3.5, .65)); add(Triple(15, 4.0, 1.0)); add(Triple(11, 5.5, .65)); add(Triple(9, 7.5, 1.0))
        add(Triple(7, 8.0, 1.0)); add(Triple(1, 8.0, 1.0)); add(Triple(2, 8.0, 1.0)); add(Triple(4, 9.0, 1.0)); add(Triple(3, 9.75, .65)); add(Triple(6, 10.0, 1.0)); add(Triple(1, 10.5, .8)); add(Triple(4, 11.0, 1.0))
        add(Triple(10, 12.0, 1.0)); add(Triple(8, 14.5, 1.0)); add(Triple(16, 15.0, .65))
        add(Triple(17, 16.0, 1.0)); add(Triple(18, 20.0, 1.0))
        add(Triple(1, 24.0, 1.0)); add(Triple(14, 24.0, 1.0)); add(Triple(4, 25.0, 1.0)); add(Triple(1, 26.5, .8)); add(Triple(4, 27.0, 1.0)); add(Triple(13, 27.5, .65))
    }

    fun redlineGroove(): List<Triple<Int, Double, Double>> = buildList {
        val line = listOf(1, 1, 2, 1, 3, 1, 4, 2)
        fun bar(b: Double, bass: List<Int>, snare: Int = 7) {
            for (i in 0..3) add(Triple(bass[i], b + i, 1.0))
            add(Triple(snare, b + 1, 1.0)); add(Triple(snare, b + 3, 1.0))
            for (i in 0..3) add(Triple(9, b + i + .5, .65))
        }
        bar(0.0, line.subList(0, 4)); bar(4.0, line.subList(4, 8)); bar(8.0, line.subList(0, 4), 8)
        add(Triple(6, 11.0, 1.0)); add(Triple(10, 11.5, .65))
        add(Triple(12, 12.0, 1.0)); for (i in 0..3) add(Triple(14, 12.0 + i, 1.0)); add(Triple(15, 14.0, 1.0)); add(Triple(5, 15.0, 1.0)); add(Triple(5, 15.25, .65)); add(Triple(5, 15.5, 1.0)); add(Triple(5, 15.75, 1.0))
        add(Triple(17, 16.0, 1.0)); for (i in 0..3) add(Triple(1, 16.0 + i, .65))
        add(Triple(18, 20.0, 1.0))
        add(Triple(11, 24.0, 1.0)); bar(24.0, line.subList(4, 8)); add(Triple(13, 26.0, 1.0)); add(Triple(16, 27.5, 1.0))
        bar(28.0, line.subList(0, 4)); for (i in 0..15) if (i % 4 != 2) add(Triple(9, 28 + i * .25, .3))
    }

    private fun h(slot: Int, beat: Double, v: Double = 1.0) = Triple(slot, beat, v)

    /** LULL: four bars of i-VI-III-VII with a music-box line and dust; the worn bar; the slowing repeat; the swell; a stop. */
    fun lullGroove(): List<Triple<Int, Double, Double>> = buildList {
        for ((k, chord) in listOf(1, 2, 3, 4).withIndex()) {
            val b = k * 4.0
            add(h(chord, b, .65))
            listOf(9 to 0.0, 8 to 1.0, 7 to 1.5, 8 to 2.5, 9 to 3.0).forEach { (s, o) -> add(h(s, b + o, if (o == 0.0) .65 else .3)) }
        }
        add(h(13, 0.0, .65)); add(h(10, 0.0, .3)); add(h(5, 4.0, .65)); add(h(13, 8.0, .65)); add(h(6, 12.0, .65)); add(h(16, 14.5, .65))
        for (i in 0 until 28) add(h(14, i.toDouble(), .3))
        add(h(12, 16.0, .65)); add(h(11, 19.0)); add(h(17, 20.0)); add(h(18, 24.0))
        add(h(1, 28.0)); add(h(7, 28.0, .65)); add(h(15, 30.0))
    }

    /** STROBE: the rave bar, the whine and the piano stab over a sped break, the roll, the rise, the drop. */
    fun strobeGroove(): List<Triple<Int, Double, Double>> = buildList {
        fun beatBar(b: Double) {
            add(h(16, b)); add(h(12, b)); add(h(12, b + 2.5, .65))
            for (i in 0..3) add(h(14, b + i + .5, .65))
        }
        for (bar in 0..3) beatBar(bar * 4.0)
        for (b in listOf(0.0, 4.0)) { add(h(4, b)); add(h(4, b + .75, .65)); add(h(5, b + 1.5)); add(h(4, b + 2.5, .65)); add(h(4, b + 3.25)) }
        add(h(7, 6.0)); add(h(7, 6.75, .65)); add(h(15, 7.5, .65))
        add(h(1, 8.0)); add(h(3, 10.5)); add(h(3, 11.0, .65)); add(h(2, 12.0)); add(h(8, 15.0))
        add(h(17, 16.0)); add(h(18, 20.0))
        beatBar(24.0); beatBar(28.0)
        add(h(8, 24.0)); add(h(1, 24.0)); listOf(24.0, 25.5, 26.5, 28.0, 29.5, 30.5).forEach { add(h(6, it)) }
        add(h(9, 25.0, .65)); add(h(9, 25.75)); add(h(10, 27.0)); add(h(11, 27.5)); add(h(4, 28.0)); add(h(4, 28.75, .65)); add(h(13, 29.0)); add(h(11, 31.5, .65))
    }

    /** CARTRIDGE: noise-channel drums under an arp and a lead, the crushed break, the roll, the rise, the drop. */
    fun cartridgeGroove(): List<Triple<Int, Double, Double>> = buildList {
        fun drums(b: Double) {
            add(h(1, b)); add(h(1, b + .75, .65)); add(h(1, b + 2.5))
            add(h(2, b + 1)); add(h(2, b + 3)); add(h(2, b + 3.75, .65))
            for (i in 0..7) add(h(if (i == 7) 4 else 3, b + i * .5, if (i % 2 == 0) .65 else .3))
        }
        for (bar in 0..3) {
            val b = bar * 4.0; drums(b)
            val arp = if (bar % 2 == 0) 7 else 8; for (i in 0..3) add(h(arp, b + i, .65))
            listOf(9, 9, 10, 9).forEachIndexed { i, s -> add(h(s, b + i + .5, .65)) }
        }
        add(h(12, 7.5)); add(h(5, 8.0)); add(h(6, 9.5)); add(h(16, 11.0)); add(h(15, 12.0)); add(h(14, 14.0)); add(h(14, 14.5, .65)); add(h(14, 14.75)); add(h(11, 15.0))
        add(h(17, 16.0)); add(h(18, 20.0))
        add(h(13, 24.0)); drums(24.0); drums(28.0); for (i in 0..7) add(h(if (i < 4) 7 else 8, 24.0 + i)); add(h(5, 28.0)); add(h(14, 30.0)); add(h(14, 30.5))
    }

    /** BARRICADE: the drum count-in, two punk bars, the chug roll, the feedback rise, the drop. */
    fun barricadeGroove(): List<Triple<Int, Double, Double>> = buildList {
        add(h(13, 0.0)); add(h(16, 2.0, .65)); add(h(6, 4.0, .65))
        fun bar(b: Double, chords: List<Int>) {
            add(h(8, b)); add(h(8, b + 1.5, .65)); add(h(8, b + 2.5)); add(h(9, b + 1)); add(h(9, b + 3))
            for (i in 0..7) add(h(11, b + i * .5, if (i % 2 == 0) .65 else .3))
            add(h(chords[0], b)); add(h(4, b + .5, .65)); add(h(4, b + 1.0)); add(h(4, b + 1.5, .65)); add(h(chords[1], b + 2)); add(h(chords[2], b + 3))
        }
        add(h(10, 8.0)); bar(8.0, listOf(1, 2, 3)); bar(12.0, listOf(1, 3, 2))
        add(h(12, 14.0)); add(h(12, 14.5, .65)); add(h(7, 15.5))
        add(h(17, 16.0)); add(h(18, 20.0))
        add(h(10, 24.0)); add(h(15, 24.0)); bar(24.0, listOf(1, 2, 3)); add(h(5, 26.0)); bar(28.0, listOf(1, 3, 2)); add(h(14, 31.5))
    }

    private fun readme(kit: BreakcoreKit, what: String, heard: String = "was heard by the owner on 2026-11-18 and kept") =
        "${kit.title}: sample-free synthesis at ${kit.bpm} BPM. 18 pads, three velocities x three takes on every pad, including the ${what}.\n" +
        "The groove and pads $heard; per-pad verdicts are open.\n" +
        "Native sourceRecipe metadata rerenders through Kotlin; not an app-editable synth patch. Native, SFZ and MPC 3 grids and PCM are software-verified. Phone and MPC playback unverified. No MPC 2 export.\n"
    private const val STATUS = "groove and pads heard 2026-11-18 and kept; per-pad verdicts, phone and MPC playback open"
    private const val STATUS2 = "groove and pads heard 2026-11-19 and kept; per-pad verdicts, phone and MPC playback open"
    private const val APPROVED = "were heard and kept on 2026-11-19"

    val specs: Map<String, BreakcoreDelivery.Spec> by lazy {
        listOf(
            BreakcoreDelivery.Spec(Shatter, "shatter", clip(Shatter, shatterGroove()), choke = null, touchSlots = listOf(1, 3, 9),
                readme = readme(Shatter, "slice repeat and break rush"), status = STATUS),
            BreakcoreDelivery.Spec(Soundclash, "soundclash", clip(Soundclash, soundclashGroove()), choke = 13 to 12, touchSlots = listOf(1, 5, 14),
                readme = readme(Soundclash, "dub roll and siren rise"), status = STATUS),
            BreakcoreDelivery.Spec(Redline, "redline", clip(Redline, redlineGroove()), choke = 10 to 9, touchSlots = listOf(1, 7, 12),
                readme = readme(Redline, "snare roll and scream rise"), status = STATUS),
            BreakcoreDelivery.Spec(Lull, "lull", clip(Lull, lullGroove()), choke = null, touchSlots = listOf(1, 13, 15),
                readme = readme(Lull, "fading repeat and swell rise", APPROVED), status = STATUS2),
            BreakcoreDelivery.Spec(Strobe, "strobe", clip(Strobe, strobeGroove()), choke = 15 to 14, touchSlots = listOf(1, 4, 12),
                readme = readme(Strobe, "stab roll and whine rise", APPROVED), status = STATUS2),
            BreakcoreDelivery.Spec(Cartridge, "cartridge", clip(Cartridge, cartridgeGroove()), choke = 4 to 3, touchSlots = listOf(5, 2, 14),
                readme = readme(Cartridge, "noise roll and arp rise", APPROVED), status = STATUS2),
            BreakcoreDelivery.Spec(Barricade, "barricade", clip(Barricade, barricadeGroove()), choke = 1 to 2, touchSlots = listOf(1, 4, 9),
                readme = readme(Barricade, "chug roll and feedback rise", APPROVED), status = STATUS2),
        ).associateBy { it.prefix }
    }

    /** Arguments: the kit's lower-case name, then the output folder. */
    @JvmStatic fun main(args: Array<String>) = BreakcoreDelivery.generate(checkNotNull(specs[args[0]]) { "unknown kit ${args[0]}" }, File(args[1]))
}
