package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.blocked
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import java.nio.file.Files

/**
 * A downloadable sixteen-pad OVEN starter through the ordinary drum-kit
 * pipeline. Both MPC generations and the editable SnipSnap sidecar share one
 * set of WAVs, keeping the complete artifact below the 25 MiB download budget.
 */
object OvenKitGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/oven-kit").absoluteFile
        check(root.isDirectory || root.mkdirs()) { "could not create $root" }
        val work = Files.createTempDirectory("snipsnap-oven-kit-").toFile()
        try {
            val arranged = SynthKits.oven().map { pad ->
                pad?.copy(displayName = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch?.name)
            }
            val kit = KitAssembler.assembleArranged("SnipSnap Oven Kit", arranged, work)
            check(kit.pads.size == 16 && kit.pads.all { it.recipe != null })
            val result = Mpc3Exporter.exportTrack(kit, work, root, overwrite = true, mpc2Twin = true)
            val dataDir = File(root, Mpc3TrackWriter.trackDataDirName(kit.name))
            val twin = File(dataDir, "${kit.name}.xpm")
            check(result.samples.map { it.name }.toSet() == kit.pads.map { it.sampleFile }.toSet()) {
                "export renamed a sample needed by the editable sidecar"
            }
            // kit.json points at the same bare WAV names the MPC programs use.
            // The export's data folder therefore remains an editable SnipSnap kit.
            KitStore.save(kit, dataDir)
            check(KitStore.load(dataDir) == kit) { "editable kit sidecar changed on disk" }

            val readBack = listOf(
                ExportOutcome(ExportFormat.MPC3_TRACK, result.program, dataDir, result.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER, twin, null, result.findings),
            ).flatMap { ExportReadBack.verify(kit, it) }
            check(!readBack.blocked()) {
                "OVEN kit read-back failed: " + readBack.filter { it.severity == com.snipsnap.kit.Severity.FAIL }
                    .joinToString { it.message }
            }
            File(root, "read-back.txt").writeText(readBack.joinToString("\n") { "${it.severity}: ${it.message}" } + "\n")

            File(root, "pads.tsv").writeText(buildString {
                append("pad\tname\tarticulation\tnote\tmidi\tstrike_velocity\tsample\n")
                for (pad in kit.pads.sortedBy { it.slot }) {
                    val patch = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch as OvenPatch
                    val midi = Oven.midiFor(patch.voice, patch.macros.getValue("TUNE"), patch.midi)
                    append("${PadNoteMap.labelForPad(pad.slot)}\t${patch.name}\t${patch.voice}\t${Scales.nameOf(midi)}\t$midi\t${patch.velocity}\t${pad.sampleFile}\n")
                }
            })
            File(root, "README.txt").writeText("""
                OVEN — first-listen starter

                Sixteen dry, finite notes from SnipSnap's original struck-string and iron-body renderer.
                A01–A08 climb C minor pentatonic from C3. A09–A16 compare GRAND, FELT, FORGED and BLOOM articulations.

                Copy this folder intact to the MPC card.
                MPC 3: load SnipSnap Oven Kit.xtd.
                Compatibility: open SnipSnap Oven Kit_[TrackData] and load SnipSnap Oven Kit.xpm.
                The TrackData folder also contains kit.json, with the complete recipes and their WAVs.
                pads.tsv names every note and its saved strike velocity.

                Each pad contains one sampled strike. Playback velocity changes its level; this starter has no velocity-layer switching.
                Its body, bloom and halo are baked into each note. Pedal-dependent resonance and live interaction between notes are outside this audition.
                The renderer is a musical physical-model approximation; it uses no measured concert-grand or cast-iron material data.
                The sound awaits the owner's listening verdict. read-back.txt checks the files with SnipSnap's reader.
            """.trimIndent() + "\n")
            val bytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            check(bytes < 25L * 1024 * 1024) { "OVEN kit exceeds the 25 MiB download budget: $bytes bytes" }
            println("Wrote ${result.program.absolutePath}, ${result.samples.size} WAVs, compatibility twin and regenerable kit.json ($bytes bytes)")
        } finally {
            work.deleteRecursively()
        }
    }
}
