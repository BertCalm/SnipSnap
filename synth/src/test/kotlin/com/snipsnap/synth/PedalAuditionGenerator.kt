package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the pedal board's Stage 0 (revised) listening clips under testkit/pedal-audition/
 * (gitignored), for the owner's listen to the shipped row in
 * docs/superpowers/specs/2026-10-04-pedal-board-design.md: DRIVE and COMP (the WAH chip is held
 * back for its own section) on a snare and a choir, and on both MAGNET guitar pads two ways (as the
 * pad sheet does it, on the pad's baked audio, and as a rig would, the chip in front of the landing
 * amp: COMP as a section of the landing chain, DRIVE as two renders in a row, the pedal and then
 * the amp, because a rack chain has one VALVE and an offline render is not limited to it). Each chip
 * at AMT 0.7 (the pad sheet's default) and 1. The snare and the choir also carry the card's PUNCH
 * and the AMPED character at AMT 0.7, to hear COMP and DRIVE beside what the rack already has. Every
 * clip shares one loudness ([AuditionLevel]). Clears any .wav and manifest.json already under its
 * output folder first, then writes manifest.json. Run via
 * `./gradlew :synth:generatePedalAudition`.
 */
object PedalAuditionGenerator {

    /** The pad sheet's chip words and the characters they draw. */
    private val CHIPS = linkedMapOf("DRIVE" to "driven", "COMP" to "compressed")
    private val AMOUNTS = listOf(0.7f, 1f)

    private fun pct(amount: Float) = (amount * 100).roundToInt()

    private fun idOf(chip: String, amount: Float) = chip.lowercase() + "_" + pct(amount)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/pedal-audition")
        // A rerun must not leave a stale clip beside the new manifest (a clip this run no longer writes).
        root.walkTopDown().filter { it.isFile && (it.extension == "wav" || it.name == "manifest.json") }.forEach { it.delete() }
        root.mkdirs()
        val entries = mutableListOf<String>()
        // AuditionLevel meters a stereo clip on its mono fold and keeps both
        // channels, so the fold here is not for levelling: the listening page
        // and the phone play mono, so CHOIR is folded (averaged, never summed)
        // to what the listener would hear.
        fun mono(s: Snip): Snip =
            if (s.channels == 1) s
            else Snip(FloatArray(s.frameCount) { f -> 0.5f * (s.samples[f * 2] + s.samples[f * 2 + 1]) }, 1, s.sampleRate)
        fun write(source: String, id: String, label: String, snip: Snip) {
            check(listOf(source, id, label).none { s -> s.any { it == '"' || it == '\\' || it < ' ' } }) { "manifest field needs escaping: $source / $id / $label" }
            val dir = File(root, source).apply { mkdirs() }
            WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono(snip)), WavWriter.BitDepth.PCM_16)
            entries += """{"source":"$source","id":"$id","file":"$source/$id.wav","label":"$label"}"""
        }
        val plain = linkedMapOf("SNARE" to Thump.render(ThumpVoice.SNARE), "CHOIR" to Vox.render(VoxVoice.CHOIR))
        for ((name, dry) in plain) {
            write(name, "dry", "$name DRY", dry)
            for ((chip, character) in CHIPS) for (amount in AMOUNTS) {
                write(name, idOf(chip, amount), "$name $chip AMT ${pct(amount)}", Treatments.apply(character, dry, amount).snip)
            }
            // The card's own references at the pad sheet's default AMT, to hear COMP and DRIVE beside.
            val reference = AMOUNTS.first()
            write(name, "punched_${pct(reference)}", "$name PUNCH AMT ${pct(reference)} (THE CARD OWN PUNCH, TO HEAR COMP BESIDE)", Treatments.apply("punched", dry, reference).snip)
            write(name, "amped_${pct(reference)}", "$name AMPED AMT ${pct(reference)} (THE RACK AMP CHARACTER, TO HEAR DRIVE BESIDE)", Treatments.apply("amped", dry, reference).snip)
        }
        for (voice in MagnetVoice.values()) {
            val name = voice.name
            val dry = Magnet.render(voice)
            val baked = Magnet.landingChain(voice).process(dry)
            write(name, "baked", "$name AS A KIT PAD (THE DRY ENGINE THROUGH ITS LANDING AMP)", baked)
            for ((chip, character) in CHIPS) for (amount in AMOUNTS) {
                write(name, "sheet_" + idOf(chip, amount), "$name $chip AMT ${pct(amount)}, ON THE BAKED PAD (AS THE PAD SHEET DOES IT)", Treatments.apply(character, baked, amount).snip)
            }
            for (amount in AMOUNTS) {
                val board = Treatments.chain(CHIPS.getValue("COMP"), amount)
                var rig = Magnet.landingChain(voice)
                for (section in FxChain.SECTION_NAMES) board.section(section)?.let { rig = rig.withSection(section, it) }
                write(name, "rig_" + idOf("COMP", amount), "$name COMP AMT ${pct(amount)}, IN FRONT OF ITS LANDING AMP (A RIG)", rig.process(dry))
            }
            for (amount in AMOUNTS) {
                val pedal = Treatments.apply(CHIPS.getValue("DRIVE"), dry, amount).snip
                write(name, "rig_" + idOf("DRIVE", amount), "$name DRIVE AMT ${pct(amount)}, IN FRONT OF ITS LANDING AMP (A RIG: THE PEDAL, THEN THE AMP)", Magnet.landingChain(voice).process(pedal))
            }
        }
        check(entries.size == 32) { "expected 32 clips (2 plain sources x 7, 2 guitars x 9), wrote ${entries.size}" }
        File(root, "manifest.json").writeText("{\"clips\":[\n" + entries.joinToString(",\n") + "\n]}\n")
        println("pedal audition: ${entries.size} clips under ${root.absolutePath}")
    }
}
