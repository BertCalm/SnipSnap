package com.snipsnap.shell

import com.snipsnap.mpc3.*
import com.snipsnap.synth.*
import java.io.File
import kotlin.math.*

/** The three breakcore kits that follow SPLINTER, each delivered through [BreakcoreDelivery].
 * The grooves are the ones the owner auditioned on 2026-11-18: (1-based slot, beat, velocity). */
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

    private fun readme(kit: BreakcoreKit, what: String) =
        "${kit.title}: sample-free synthesis at ${kit.bpm} BPM. 18 pads, three velocities x three takes on every pad, including the ${what}.\n" +
        "The groove and pads were heard by the owner on 2026-11-18 and kept; per-pad verdicts are open.\n" +
        "Native sourceRecipe metadata rerenders through Kotlin; not an app-editable synth patch. Native, SFZ and MPC 3 grids and PCM are software-verified. Phone and MPC playback unverified. No MPC 2 export.\n"
    private const val STATUS = "groove and pads heard 2026-11-18 and kept; per-pad verdicts, phone and MPC playback open"

    val specs: Map<String, BreakcoreDelivery.Spec> by lazy {
        listOf(
            BreakcoreDelivery.Spec(Shatter, "shatter", clip(Shatter, shatterGroove()), choke = null, touchSlots = listOf(1, 3, 9),
                readme = readme(Shatter, "slice repeat and break rush"), status = STATUS),
            BreakcoreDelivery.Spec(Soundclash, "soundclash", clip(Soundclash, soundclashGroove()), choke = 13 to 12, touchSlots = listOf(1, 5, 14),
                readme = readme(Soundclash, "dub roll and siren rise"), status = STATUS),
            BreakcoreDelivery.Spec(Redline, "redline", clip(Redline, redlineGroove()), choke = 10 to 9, touchSlots = listOf(1, 7, 12),
                readme = readme(Redline, "snare roll and scream rise"), status = STATUS),
        ).associateBy { it.prefix }
    }

    /** Arguments: the kit's lower-case name, then the output folder. */
    @JvmStatic fun main(args: Array<String>) = BreakcoreDelivery.generate(checkNotNull(specs[args[0]]) { "unknown kit ${args[0]}" }, File(args[1]))
}
