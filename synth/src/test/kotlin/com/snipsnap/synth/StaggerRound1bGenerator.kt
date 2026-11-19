package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.StaggerKits.Kit
import java.io.File
import kotlin.math.min

/**
 * STAGGER round 1b: the owner heard round 1 as "too much like drum machine sounds". The same CARDBOARD kit with
 * every drum sourced from EMBER's acoustic models, beside round 1's THUMP and SKIN drums and beside slices of the
 * kit played in its room (SHATTER's manner), for the owner's ear.
 *
 * Output (args[0], default testkit/stagger-round1b):
 *   compare/<drum>-round1.wav, -ember.wav     the same pad from round 1's engines and from EMBER, no kit print
 *   kick/ember-open.wav, ember-lp250/300/400  EMBER's kick open and muffled at three cutoffs; thump-lp300 is round 1's
 *   slices/kick-slice.wav, snare-slice, hat-slice   the groove cut at a hit, bleed and all
 *   pads/NN-<name>.wav (+ -dry), strips/      every pad through the kit's print, its takes and zones
 *   groove/cardboard-<law>.wav                the groove under the kit's clock, straight, and CYPHER's humanised
 *   manifest.json
 */
object StaggerRound1bGenerator {
    private val kit = Kit.CARDBOARD

    private fun strip(pads: List<Snip>, gapSeconds: Float = .8f): Snip {
        val step = (gapSeconds * Dsp.RATE).toInt()
        val out = FloatArray(step * pads.size)
        for ((i, p) in pads.withIndex()) {
            val n = min(p.samples.size, step - 64)
            for (j in 0 until n) out[i * step + j] = p.samples[j] * if (j > n - 256) (n - j) / 256f else 1f
        }
        return Snip(out, 1, Dsp.RATE)
    }

    private fun lone(strike: Strike, velocity: Float = 1f, take: Int = 0): Snip =
        KitStrikes.finish(KitStrikes.struck(strike, velocity, take, StaggerKits.touch(velocity)), 1f)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/stagger-round1b")
        val manifest = StringBuilder("{\n  \"kit\": \"${kit.title}\", \"bpm\": ${kit.bpm}, \"version\": ${StaggerKits.VERSION}, \"round\": \"1b\", \"files\": [\n")
        var first = true
        fun write(path: String, s: Snip, what: String) {
            WavWriter.write(File(root, path), s).also { println("wrote ${it.path}") }
            if (!first) manifest.append(",\n")
            first = false
            manifest.append("    {\"file\": \"$path\", \"what\": \"${what.replace("\"", "'")}\", \"seconds\": ${"%.2f".format(java.util.Locale.ROOT, s.durationSeconds)}}")
        }
        val names = StaggerKits.roles(kit)
        val parts = StaggerKits.parts(kit)
        for ((name, old) in StaggerKits.round1) {
            val slug = name.lowercase().replace(' ', '-')
            write("compare/$slug-round1.wav", lone(old), "$name from round 1's engines, no kit print")
            write("compare/$slug-ember.wav", lone(parts[names.indexOf(name)].strike), "$name from EMBER, no kit print")
        }
        write("kick/ember-open.wav", lone(StaggerKits.emberKickOpen), "EMBER's kick with no muffle")
        for (hz in listOf(250f, 300f, 400f)) write("kick/ember-lp${hz.toInt()}.wav", lone(StaggerKits.emberKickAt(hz)), "EMBER's kick muffled at ${hz.toInt()} Hz")
        write("kick/thump-lp300.wav", lone(StaggerKits.boxKick), "round 1's kick muffled at 300 Hz")
        for ((slug, part) in listOf("kick-slice" to StaggerKits.KICK, "snare-slice" to StaggerKits.SNARE, "hat-slice" to StaggerKits.HAT)) {
            write("slices/$slug.wav", lone(StaggerKits.sliced(kit, part)), "the groove in its room, cut at a ${slug.substringBefore('-')}: bleed and all")
            write("slices/$slug-takes.wav", strip(List(StaggerKits.TAKES) { lone(StaggerKits.sliced(kit, part), 1f, it) }), "the same slice, four takes")
        }
        for (role in 0 until StaggerKits.PADS) {
            val slug = "%02d-%s".format(role + 1, names[role].lowercase().replace(' ', '-'))
            write("pads/$slug.wav", StaggerKits.render(kit, role, 1f, 0), "${names[role]}: hard first take through the kit's print")
            write("pads/$slug-dry.wav", StaggerKits.dry(kit, role, 1f, 0), "${names[role]}: the same strike with no kit print")
            if (role < StaggerKits.HITS) {
                write("strips/$slug-takes.wav", strip(List(StaggerKits.TAKES) { StaggerKits.render(kit, role, 1f, it) }), "${names[role]}: four takes at full velocity")
                write("strips/$slug-velocity.wav", strip(StaggerKits.velocities.map { StaggerKits.render(kit, role, it, 0) }), "${names[role]}: soft, medium, hard")
            }
        }
        val laws = StaggerRound1Generator.grooveLaws()
        for (law in listOf("kit", "straight", "humanised-10ms")) write("groove/cardboard-$law.wav", StaggerRound1Generator.grooveUnder(laws.getValue(law)), "the two-bar groove under the '$law' clock")
        manifest.append("\n  ]\n}\n")
        File(root, "manifest.json").writeText(manifest.toString())
        println("wrote ${File(root, "manifest.json").path}")
    }
}
