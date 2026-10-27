package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * Renders Stage 3's listening clips under testkit/amp-front-audition/ (gitignored): the pedals on both
 * MAGNET guitar pads two ways, as the pad sheet does it today (on the baked pad, after its amp) and in
 * front of the pad's own landing amp ([PedalBoard.processAround], the rig way), for the owner's gate in
 * docs/superpowers/specs/2026-10-27-pedals-in-front-of-the-amp-design.md. SUSTAIN, WAH and DRIVE each at
 * the pad sheet's default AMT 0.7; SUSTAIN's RECOVER at both ends in front of the amp (the owner judged the
 * guitars' ends "fine until the pedal goes in front of the amp"); and a four-pedal board (SUSTAIN, WAH,
 * DRIVE, DELAY) both ways. Every clip shares one loudness ([AuditionLevel]). Clears any .wav and
 * manifest.json under its folder first. Run via `./gradlew :synth:generateAmpFrontAudition`.
 */
object AmpFrontAuditionGenerator {

    private val CHIPS = linkedMapOf("SUSTAIN" to "compressed", "WAH" to "wahed", "DRIVE" to "driven")
    private const val AMT = 0.7f

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/amp-front-audition")
        root.walkTopDown().filter { it.isFile && (it.extension == "wav" || it.name == "manifest.json") }.forEach { it.delete() }
        root.mkdirs()
        val entries = mutableListOf<String>()
        fun write(source: String, id: String, label: String, snip: Snip) {
            check(listOf(source, id, label).none { s -> s.any { it == '"' || it == '\\' || it < ' ' } }) { "manifest field needs escaping: $source / $id / $label" }
            val dir = File(root, source).apply { mkdirs() }
            WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
            entries += """{"source":"$source","id":"$id","file":"$source/$id.wav","label":"$label"}"""
        }
        for (voice in MagnetVoice.values()) {
            val name = voice.name
            val dry = Magnet.render(voice)
            val landing = Magnet.landingChain(voice)
            val baked = landing.process(dry)
            write(name, "baked", "$name AS A KIT PAD (THE DRY ENGINE THROUGH ITS LANDING AMP)", baked)
            for ((word, key) in CHIPS) {
                val board = PedalBoard(listOf(PedalBoard.Pick(key, AMT)))
                write(name, "${word.lowercase()}_after", "$name $word AT 70, AFTER THE AMP (THE PAD SHEET TODAY)", board.process(baked))
                write(name, "${word.lowercase()}_front", "$name $word AT 70, IN FRONT OF THE AMP (STAGE 3)", board.processAround(dry, landing))
            }
            for ((id, recover) in listOf("recover_0" to 0f, "recover_100" to 1f)) {
                val sustain = FxChain(compressor = mapOf("AMOUNT" to AMT, "RECOVER" to recover))
                write(name, "sustain_front_$id", "$name SUSTAIN AT 70 IN FRONT OF THE AMP, RECOVER ${(recover * 100).toInt()}", landing.process(sustain.process(dry)))
            }
            val four = PedalBoard(listOf("compressed", "wahed", "driven", "delayed").map { PedalBoard.Pick(it, AMT) })
            write(name, "board_after", "$name SUSTAIN, WAH, DRIVE AND DELAY AT 70, AFTER THE AMP", four.process(baked))
            write(name, "board_front", "$name SUSTAIN, WAH, DRIVE AND DELAY AT 70, AROUND THE AMP (DELAY STAYS AFTER IT)", four.processAround(dry, landing))
        }
        check(entries.size == 22) { "expected 22 clips (2 guitars x 11), wrote ${entries.size}" }
        File(root, "manifest.json").writeText("{\"clips\":[\n" + entries.joinToString(",\n") + "\n]}\n")
        println("amp-front audition: ${entries.size} clips under ${root.absolutePath}")
    }
}
