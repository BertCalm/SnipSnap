package com.snipsnap.cli

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Ear
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Tempo
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.GrooveVariations
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Names
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import java.io.File
import java.io.PrintStream

/**
 * `snipsnap learn <beat.wav> --into <kit-dir>` — the Ear: bite a beat.
 * A recorded *performance* is transcribed — onsets heard, each hit
 * classified with the classifier chop already trusts — and played back
 * by YOUR kit: the hits land on the pads whose classes match, timing
 * kept raw (that's the feel), velocities from the hits' own dynamics,
 * and the result becomes the kit's groove with the standard variations.
 *
 * Honest throughout: no confident tempo refuses (the ear needs a
 * grid), hits under the confidence bar are counted and left out —
 * marked, never invented — and classes the kit has no pad for are
 * named, not guessed around.
 */
object LearnCommand {

    const val TARGET_RATE = 44_100

    /** Below this the ear won't claim it knows what a hit was. */
    const val SURE_CONFIDENCE = 0.5f

    fun run(args: List<String>, out: PrintStream): Int {
        val opts = Options.parse(args, valued = setOf("--into", "--pocket"), boolean = emptySet())
        val input = opts.positional.firstOrNull()
            ?: throw CliError("learn wants a beat: snipsnap learn <beat.wav> --into <kit-dir>")
        if (opts.positional.size > 1) throw CliError("learn takes one beat file")
        val file = File(input)
        if (!file.isFile) throw CliError("no such file: $input")
        val intoArg = opts["--into"]
            ?: throw CliError("whose pads play it? --into <kit-dir>")
        val kitDir = File(intoArg)
        if (!File(kitDir, "kit.json").isFile) throw CliError("not a kit folder (no kit.json): $intoArg")

        var snip = WavReader.read(file)
        if (snip.sampleRate != TARGET_RATE) snip = Resampler.resample(snip, TARGET_RATE)

        val tempo = Tempo.estimate(snip)?.takeIf { it.confidence >= 0.3f }
            ?: throw CliError("no confident tempo in ${file.name} - the ear needs a grid to write onto")
        val hits = Ear.listen(snip)
        if (hits.size < 2) throw CliError("no beat heard in ${file.name} - the ear finds hits, not tones")

        val kit = KitStore.load(kitDir)
        val sure = hits.filter { it.confidence >= SURE_CONFIDENCE }
        val uncertain = hits.size - sure.size

        val unmapped = linkedSetOf<DrumClass>()
        val framesPerPulse = 60.0 / tempo.bpm * TARGET_RATE / 960.0
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
        if (notes.isEmpty()) {
            throw CliError(
                "nothing landed: " +
                    (if (uncertain > 0) "$uncertain uncertain hit(s) left out" else "") +
                    (if (unmapped.isNotEmpty()) "; no pads for ${unmapped.joinToString(", ")}" else ""),
            )
        }
        val bars = ((notes.maxOf { it.timePulses } / Mpc3Clip.PULSES_PER_BAR) + 1).toInt().coerceIn(1, 64)
        val clip = Mpc3Clip("${Names.sanitizeStem(file.nameWithoutExtension)} Learned", bars, notes)
        GrooveStore.save(kitDir, GrooveVariations.standard(clip))

        val byClass = sure.mapNotNull { h -> h.drumClass.takeIf { padFor(kit, it) != null } }
            .groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
            .joinToString(", ") { "${it.value} ${it.key.name.lowercase()}" }
        out.println(
            "learned: \"${clip.name}\" - ${notes.size} hits ($byClass) over $bars bar(s) at ~${tempo.label}",
        )
        if (uncertain > 0) out.println("  $uncertain uncertain hit(s) left out - marked, not invented")
        if (unmapped.isNotEmpty()) {
            out.println("  no pad for: ${unmapped.joinToString(", ") { it.name.lowercase() }} - those hits sat out")
        }
        out.println("patterns rewritten: the beat plays on ${kit.name}'s own pads (captured/tight/half/sparse)")
        return 0
    }

    /** The class's pad, with the preview's own sensible stand-ins. */
    internal fun padFor(kit: Kit, dc: DrumClass): Int? {
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
