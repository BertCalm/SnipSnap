package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.synth.Cypher.Note
import com.snipsnap.synth.StaggerKits.Kit
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * STAGGER round 1: CARDBOARD's candidates rendered from the engines as they stand, for the owner's ear.
 *
 * Output (args[0], default testkit/stagger-round1):
 *   pads/NN-<name>.wav            each pad's hard first take, through the kit's print
 *   pads/NN-<name>-dry.wav        the same strike with no kit print (a muffle or a foley print stays: it is the drum)
 *   strips/NN-<name>-takes.wav    four takes at full velocity, 0.8 s apart (round robin)
 *   strips/NN-<name>-velocity.wav soft, medium, hard, 0.8 s apart
 *   kick/box-open.wav             the identity kick with no muffle; box-lp250 / 300 / 400: the muffle at three cutoffs
 *   kick/box-lp300-nodrive.wav    the filter with no clipper ahead of it (what a phone would lose)
 *   glass/glass-dry.wav           the pane breaking, unconverted; glass-down5 / 7 / 9: through the foley print
 *   groove/cardboard-<law>.wav    the two-bar groove: the kit's clock, three widths, three rhos, humanised, behind, straight
 *   manifest.json                 what each file is
 *
 * Nothing here has been heard. Every number is a shape value aimed at the brief's measured references.
 */
object StaggerRound1Generator {
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

    /** A lone strike, struck hard, levelled like a pad, with no kit print. */
    private fun lone(strike: Strike, velocity: Float = 1f, take: Int = 0): Snip =
        KitStrikes.finish(KitStrikes.struck(strike, velocity, take, StaggerKits.touch(velocity)), 1f)

    /** The groove under another clock law, printed and levelled like the kit's own. */
    internal fun grooveUnder(notes: List<Note>): Snip {
        val raw = StaggerKits.grooveRaw(kit, notes, 1f, 0)
        val print = StaggerKits.printOf(kit)
        val n = raw.size
        val p = print.apply(FloatArray(2 * n) { raw[it % n] }, phrase = true, converted = false)
        val fade = (.02f * Dsp.RATE).toInt()
        val x = FloatArray(n) { j -> if (j < n - fade) p[n + j] else { val w = (j - (n - fade) + 1).toFloat() / fade; p[n + j] * (1f - w) + p[j] * w } }
        val mean = x.average().toFloat()
        val peak = x.maxOf { kotlin.math.abs(it - mean) }
        return Snip(FloatArray(n) { ((x[it] - mean) * StaggerKits.PAD_PEAK / max(peak, 1e-9f)) }, 1, Dsp.RATE)
    }

    /** The clock laws the page compares, each applied to the same notes and then played through the room's dynamics. */
    internal fun grooveLaws(): Map<String, List<Note>> {
        val notes = StaggerKits.cardboardNotes
        val c = StaggerKits.clockOf(kit)
        fun clocked(kick: Float, snare: Float, hat: Float, wobble: Float, rho: Float) =
            StaggerKits.played(kit, notes, StaggerKits.Clock(kick, snare, hat, wobble, rho, c.seed))
        return linkedMapOf(
            "kit" to StaggerKits.played(kit, notes),
            "straight" to clocked(0f, 0f, 0f, 0f, 0f),
            "behind-only" to clocked(c.kickMs, c.snareMs, c.hatMs, 0f, 0f),
            "narrow-5ms" to clocked(c.kickMs, c.snareMs, c.hatMs, 5f, c.rho),
            "wide-18ms" to clocked(c.kickMs, c.snareMs, c.hatMs, 18f, c.rho),
            "rho-0" to clocked(c.kickMs, c.snareMs, c.hatMs, c.wobbleMs, 0f),
            "rho-0.75" to clocked(c.kickMs, c.snareMs, c.hatMs, c.wobbleMs, .75f),
            "kick-24ms" to clocked(24f, c.snareMs, c.hatMs, c.wobbleMs, c.rho),
            "humanised-10ms" to StaggerKits.played(kit, Cypher.humanised(notes, 10f, "cardboard"), StaggerKits.Clock(0f, 0f, 0f, 0f, 0f, c.seed)),
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/stagger-round1")
        val manifest = StringBuilder("{\n  \"kit\": \"${kit.title}\", \"bpm\": ${kit.bpm}, \"version\": ${StaggerKits.VERSION}, \"files\": [\n")
        var first = true
        fun write(path: String, s: Snip, what: String) {
            WavWriter.write(File(root, path), s).also { println("wrote ${it.path}") }
            if (!first) manifest.append(",\n")
            first = false
            manifest.append("    {\"file\": \"$path\", \"what\": \"${what.replace("\"", "'")}\", \"seconds\": ${"%.2f".format(java.util.Locale.ROOT, s.durationSeconds)}}")
        }
        val names = StaggerKits.roles(kit)
        for (role in 0 until StaggerKits.PADS) {
            val slug = "%02d-%s".format(role + 1, names[role].lowercase().replace(' ', '-'))
            write("pads/$slug.wav", StaggerKits.render(kit, role, 1f, 0), "${names[role]}: hard first take through the kit's print")
            write("pads/$slug-dry.wav", StaggerKits.dry(kit, role, 1f, 0), "${names[role]}: the same strike with no kit print")
            if (role < StaggerKits.HITS) {
                write("strips/$slug-takes.wav", strip(List(StaggerKits.TAKES) { StaggerKits.render(kit, role, 1f, it) }), "${names[role]}: four takes at full velocity")
                write("strips/$slug-velocity.wav", strip(StaggerKits.velocities.map { StaggerKits.render(kit, role, it, 0) }), "${names[role]}: soft, medium, hard")
            }
        }
        write("kick/box-open.wav", lone(StaggerKits.boxKickOpen), "the identity kick with no muffle (CLICK 0, no filter)")
        for (hz in listOf(250f, 300f, 400f)) write("kick/box-lp$hz.wav".replace(".0", ""), lone(StaggerKits.boxKickAt(hz)), "the muffle at $hz Hz with the clipper at 3")
        write("kick/box-lp300-nodrive.wav", lone(StaggerKits.boxKickAt(300f, 1f)), "the filter at 300 Hz with no clipper ahead of it")
        write("glass/glass-dry.wav", lone(StaggerKits.glassDry), "GLASS: a pane breaking, unconverted")
        for (semis in listOf(5, 7, 9)) write("glass/glass-down$semis.wav", lone(StaggerKits.glassAt(-semis)), "GLASS through the foley print, $semis semitones down")
        write("glass/glass-takes.wav", strip(List(StaggerKits.TAKES) { lone(StaggerKits.glassAt(-5), 1f, it) }, 1.2f), "GLASS at -5: four takes, four panes")
        for ((law, notes) in grooveLaws()) write("groove/cardboard-$law.wav", grooveUnder(notes), "the two-bar groove under the '$law' clock")
        manifest.append("\n  ]\n}\n")
        File(root, "manifest.json").writeText(manifest.toString())
        println("wrote ${File(root, "manifest.json").path}")
    }
}
