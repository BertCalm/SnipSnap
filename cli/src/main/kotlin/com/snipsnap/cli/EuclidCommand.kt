package com.snipsnap.cli

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.GrooveVariations
import com.snipsnap.kit.KitStore
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap euclid <kit-dir>` — the T-1 half of Torso's DNA: Bjorklund
 * patterns as a groove. `--kick 3,8`, `--snare 2,8,2`, `--hat 7,16` —
 * k hits spread as evenly as the integers allow across n steps of one
 * bar, optionally rotated — played on the kit's own pads (the Ear's
 * stand-in map fills gaps: a clap covers a missing snare) and saved
 * through the standard groove door, so the tight/half/sparse
 * variations ride along and the native exports carry the clip.
 *
 * Accents are structural, not random: the downbeat leads (0.95),
 * onsets landing on quarter boundaries anchor (0.85), the rest speak
 * at 0.7 — deterministic, so the same spec always writes the same
 * groove.
 */
object EuclidCommand {

    const val ACCENT_DOWNBEAT = 0.95f
    const val ACCENT_QUARTER = 0.85f
    const val ACCENT_REST = 0.7f

    private val CLASS_FLAGS: Map<String, DrumClass> = linkedMapOf(
        "--kick" to DrumClass.KICK,
        "--snare" to DrumClass.SNARE,
        "--hat" to DrumClass.HAT_CLOSED,
        "--openhat" to DrumClass.HAT_OPEN,
        "--clap" to DrumClass.CLAP,
        "--perc" to DrumClass.PERC,
    )

    /** No spec given: the house pattern — a son-clave kick, a backbeat snare, driving hats. */
    private val DEFAULTS: Map<String, String> = linkedMapOf(
        "--kick" to "3,8",
        "--snare" to "2,8,2",
        "--hat" to "7,16",
    )

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = CLASS_FLAGS.keys, boolean = emptySet())
        val input = opts.positional.getOrNull(0)
            ?: throw CliError("euclid wants a kit: snipsnap euclid <kit-dir> [--kick k,n[,rot]] ...")
        if (opts.positional.size > 1) throw CliError("euclid takes one kit")
        val kitDir = File(input)
        if (!File(kitDir, "kit.json").isFile) throw CliError("no kit at $input")
        val kit = KitStore.load(kitDir)

        val specs = CLASS_FLAGS.keys.mapNotNull { flag -> opts[flag]?.let { flag to it } }
            .ifEmpty { DEFAULTS.entries.map { it.key to it.value } }

        val notes = mutableListOf<Mpc3Note>()
        val skipped = mutableListOf<String>()
        for ((flag, spec) in specs) {
            val dc = CLASS_FLAGS.getValue(flag)
            val (k, n, rot) = parseSpec(flag, spec)
            val slot = LearnCommand.padFor(kit, dc)
            if (slot == null) {
                skipped += "${flag.removePrefix("--")} (no pad plays it)"
                continue
            }
            val pattern = bjorklund(k, n)
            val step = Mpc3Clip.PULSES_PER_BAR / n
            val positions = (0 until n).filter { pattern[it] }.map { (it + rot) % n }.sorted()
            for (pos in positions) {
                val time = pos * step
                notes += Mpc3Note(
                    note = 35 + slot,
                    timePulses = time,
                    velocity = when {
                        time == 0L -> ACCENT_DOWNBEAT
                        time % (Mpc3Clip.PULSES_PER_BAR / 4) == 0L -> ACCENT_QUARTER
                        else -> ACCENT_REST
                    },
                )
            }
            out.println(
                "  ${flag.removePrefix("--")} E($k,$n)" + (if (rot > 0) "+$rot" else "") + ": " +
                    (0 until n).joinToString("") { if (it in positions) "x" else "." },
            )
        }
        if (notes.isEmpty()) {
            throw CliError(
                "nothing landed" +
                    (if (skipped.isNotEmpty()) " - ${skipped.joinToString(", ")}" else ""),
            )
        }

        val clip = Mpc3Clip("${kit.name} Euclid", 1, notes.sortedBy { it.timePulses })
        GrooveStore.save(kitDir, GrooveVariations.standard(clip))
        skipped.forEach { out.println("  ! $it") }
        out.println(
            "euclid: \"${clip.name}\" - ${notes.size} hits on ${kit.name}'s own pads, " +
                "saved with the standard variations; the native exports carry it",
        )
        return 0
    }

    /** "k,n" or "k,n,rotation" — k hits across n steps of one bar. */
    private fun parseSpec(flag: String, spec: String): Triple<Int, Int, Int> {
        val parts = spec.split(",").map { it.trim() }
        if (parts.size !in 2..3 || parts.any { it.toIntOrNull() == null }) {
            throw CliError("$flag wants k,n or k,n,rotation (like 3,8 or 2,8,2), got '$spec'")
        }
        val k = parts[0].toInt()
        val n = parts[1].toInt()
        val rot = parts.getOrNull(2)?.toInt() ?: 0
        if (n < 1 || n > 64 || Mpc3Clip.PULSES_PER_BAR % n != 0L) {
            throw CliError("$flag: n wants a divisor of the bar between 1 and 64, got $n")
        }
        if (k !in 1..n) throw CliError("$flag: k wants 1..n, got $k of $n")
        if (rot !in 0 until n) throw CliError("$flag: rotation wants 0..${n - 1}, got $rot")
        return Triple(k, n, rot)
    }

    /**
     * Toussaint's telling of Bjorklund: distribute k onsets across n
     * steps as evenly as the integers allow. E(3,8) is the tresillo
     * `x..x..x.`, E(5,8) the cinquillo `x.xx.xx.` — the textbook forms,
     * onset first.
     */
    internal fun bjorklund(k: Int, n: Int): BooleanArray {
        if (k == n) return BooleanArray(n) { true }
        var main = MutableList(k) { mutableListOf(true) }
        var rem = MutableList(n - k) { mutableListOf(false) }
        while (rem.size > 1) {
            val m = minOf(main.size, rem.size)
            val combined = MutableList(m) { (main[it] + rem[it]).toMutableList() }
            val leftover = (main.drop(m) + rem.drop(m)).toMutableList()
            main = combined
            rem = leftover
        }
        val flat = (main + rem).flatten()
        return BooleanArray(n) { flat[it] }
    }
}
