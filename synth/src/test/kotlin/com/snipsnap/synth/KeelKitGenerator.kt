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
 * A downloadable sixteen-pad KEEL first-listen kit through the ordinary
 * drum-kit pipeline. Both MPC generations and the editable SnipSnap sidecar
 * share one set of WAVs, keeping the complete artifact below the 25 MiB
 * download budget.
 *
 * Run via `./gradlew :synth:generateKeelKit`. Nothing here establishes that
 * anything sounds right; the kit exists so the owner can hear the roster.
 */
object KeelKitGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/keel-kit").absoluteFile
        check(root.isDirectory || root.mkdirs()) { "could not create $root" }
        val work = Files.createTempDirectory("snipsnap-keel-kit-").toFile()
        try {
            val arranged = SynthKits.keel().map { pad ->
                pad?.copy(displayName = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch?.name)
            }
            val kit = KitAssembler.assembleArranged("SnipSnap Keel Kit", arranged, work)
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
                "KEEL kit read-back failed: " + readBack.filter { it.severity == com.snipsnap.kit.Severity.FAIL }
                    .joinToString { it.message }
            }
            File(root, "read-back.txt").writeText(readBack.joinToString("\n") { "${it.severity}: ${it.message}" } + "\n")

            File(root, "pads.tsv").writeText(buildString {
                append("pad\tname\tvoice\tnote\tmidi\tstrike_velocity\tsample\n")
                for (pad in kit.pads.sortedBy { it.slot }) {
                    val patch = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch as KeelPatch
                    val midi = Keel.midiFor(patch.voice, patch.macros.getValue("TUNE"), patch.midi)
                    append("${PadNoteMap.labelForPad(pad.slot)}\t${patch.name}\t${patch.voice}\t${Scales.nameOf(midi)}\t$midi\t${patch.velocity}\t${pad.sampleFile}\n")
                }
            })
            File(root, "README.txt").writeText("""
                KEEL — first-listen kit

                Sixteen dry bass notes from SnipSnap's KEEL engine. Each has a bright attack that settles into the note's own body.
                A01–A08 walk C minor pentatonic up from C2 on REDUCE, the FM voice, at its defaults.
                A09–A12 are UPRIGHT presets (a plucked string through a wooden body). A13–A14 are REDUCE presets.
                A15 and A16 are the held loops, three seconds each and seamless. A16 is the bowed string.

                Copy this folder intact to the MPC card.
                MPC 3: load SnipSnap Keel Kit.xtd.
                Compatibility: open SnipSnap Keel Kit_[TrackData] and load SnipSnap Keel Kit.xpm.
                The TrackData folder also contains kit.json, with the complete recipes and their WAVs.
                pads.tsv names every note and its saved strike velocity.

                Each pad holds one sampled note. Playback velocity changes its level only; there are no velocity layers.
                To play KEEL across a keyboard, use the keygroups in testkit/keel-keys.
                The presets await the owner's listening verdict. read-back.txt checks the files with SnipSnap's reader.
            """.trimIndent() + "\n")
            val bytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            check(bytes < 25L * 1024 * 1024) { "KEEL kit exceeds the 25 MiB download budget: $bytes bytes" }
            println("Wrote ${result.program.absolutePath}, ${result.samples.size} WAVs, compatibility twin and regenerable kit.json ($bytes bytes)")
        } finally {
            work.deleteRecursively()
        }
    }
}
