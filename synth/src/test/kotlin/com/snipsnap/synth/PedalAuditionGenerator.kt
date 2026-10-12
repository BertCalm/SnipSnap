package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * Renders the pedal board's Stage 0 listening clips under testkit/pedal-audition/ (gitignored),
 * for the gate in docs/superpowers/specs/2026-10-04-pedal-board-design.md: DRIVE, COMP and WAH on a
 * snare and a choir, and on both MAGNET guitar pads two ways (as the pad sheet does it, on the pad's
 * baked audio, and as a rig would, the chip's section in front of the landing amp: COMP and WAH only,
 * because a rack chain has one VALVE). Each chip at AMT 0.7 (the pad sheet's default) and 1. Every clip
 * shares one loudness ([AuditionLevel]). Writes manifest.json. Run via `./gradlew :synth:generatePedalAudition`.
 */
object PedalAuditionGenerator {

    /** The pad sheet's chip words and the characters they draw. */
    private val CHIPS = linkedMapOf("DRIVE" to "driven", "COMP" to "compressed", "WAH" to "quacked")
    private val AMOUNTS = listOf(0.7f, 1f)

    private fun idOf(chip: String, amount: Float) = chip.lowercase() + "_" + (amount * 100).toInt()

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/pedal-audition")
        root.mkdirs()
        val entries = mutableListOf<String>()
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
                write(name, idOf(chip, amount), "$name $chip AMT ${(amount * 100).toInt()}", Treatments.apply(character, dry, amount).snip)
            }
        }
        for (voice in MagnetVoice.values()) {
            val name = voice.name
            val dry = Magnet.render(voice)
            val baked = Magnet.landingChain(voice).process(dry)
            write(name, "baked", "$name AS A KIT PAD (THE DRY ENGINE THROUGH ITS LANDING AMP)", baked)
            for ((chip, character) in CHIPS) for (amount in AMOUNTS) {
                write(name, "sheet_" + idOf(chip, amount), "$name $chip AMT ${(amount * 100).toInt()}, ON THE BAKED PAD (AS THE PAD SHEET DOES IT)", Treatments.apply(character, baked, amount).snip)
            }
            for (chip in listOf("COMP", "WAH")) for (amount in AMOUNTS) {
                val board = Treatments.chain(CHIPS.getValue(chip), amount)
                var rig = Magnet.landingChain(voice)
                for (section in FxChain.SECTION_NAMES) board.section(section)?.let { rig = rig.withSection(section, it) }
                write(name, "rig_" + idOf(chip, amount), "$name $chip AMT ${(amount * 100).toInt()}, IN FRONT OF ITS LANDING AMP (A RIG)", rig.process(dry))
            }
        }
        check(entries.size == 36) { "expected 36 clips (2 plain sources x 7, 2 guitars x 11), wrote ${entries.size}" }
        File(root, "manifest.json").writeText("{\"clips\":[\n" + entries.joinToString(",\n") + "\n]}\n")
        println("pedal audition: ${entries.size} clips under ${root.absolutePath}")
    }
}
