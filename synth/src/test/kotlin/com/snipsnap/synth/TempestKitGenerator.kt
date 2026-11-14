package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
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
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One dry sixteen-pad native TEMPEST kit, with editable recipes and both MPC formats. */
object TempestKitGenerator {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 2 && (args.size < 2 || args[1] == "--tuned")) { "supply one TEMPEST kit output directory and optional --tuned flag" }
        val archive = generate(File(args.firstOrNull() ?: "testkit/SnipSnap Tempest Full Kit").canonicalFile, fullKit = args.getOrNull(1) != "--tuned")
        println("TEMPEST kit: ${archive.absolutePath} (${archive.length()} bytes)")
    }

    /** Also used by the instrument audition so its kit download is generated, never a placeholder. */
    internal fun generate(destination: File, fullKit: Boolean = true): File {
        destination.parentFile.mkdirs()
        val staging = Files.createTempDirectory(destination.parentFile.toPath(), ".tempest-kit-building-").toFile()
        val work = Files.createTempDirectory("tempest-kit-source-").toFile()
        try {
            val arranged = if (fullKit) SynthKits.tempest() else SynthKits.tempestTuned()
            check(arranged.size == 16 && arranged.all { it != null }) { "TEMPEST starter must contain sixteen pads" }
            val assembled = KitAssembler.assembleArranged(if (fullKit) "SnipSnap Tempest Full Kit" else "SnipSnap Tempest Kit",
                arranged, work, if (fullKit) null else KeySpec.parse("Eminpent"))
            // Choke groups are carried by the same arrangement used by the ordinary starter.
            val kit = assembled
            check(kit.pads.size == 16)
            val result = Mpc3Exporter.exportTrack(kit, work, staging, overwrite = true, mpc2Twin = true)
            val data = File(staging, Mpc3TrackWriter.trackDataDirName(kit.name))
            val twin = File(data, "${kit.name}.xpm")
            check(result.samples.map { it.name }.toSet() == kit.pads.map { it.sampleFile }.toSet())
            // One set of WAVs serves the native track, compatibility program and editable sidecar.
            KitStore.save(kit, data)
            check(KitStore.load(data) == kit) { "TEMPEST kit sidecar did not round-trip" }
            val findings = listOf(
                ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER, twin, null, result.findings),
            ).flatMap { ExportReadBack.verify(kit, it) }
            check(!findings.blocked()) { "TEMPEST kit read-back failed: ${findings.joinToString { it.message }}" }
            File(staging, "read-back.txt").writeText("MPC 3 track read-back: passed\nMPC 2 program read-back: passed\n" +
                findings.joinToString("\n") { "${it.severity}: ${it.message}" } + "\n")
            File(staging, "pads.tsv").writeText(buildString {
                append("pad\tname\tvoice\tmidi\tclass\tmodel\tTUNE\tSOURCE\tFILTER\tDRIVE\tSPACE\tDECAY\tHOLD\tsample\n")
                for (pad in kit.pads.sortedBy { it.slot }) {
                    val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                    val patch = recipe.patch as? TempestPatch ?: error("TEMPEST pad lost its native patch")
                    check(recipe.fx == null && recipe.board == null && patch.macros.getValue("HOLD") == 0f)
                    append("${PadNoteMap.labelForPad(pad.slot)}\t${patch.name}\t${patch.voice.name}\t${Tempest.midiFor(patch.macros.getValue("TUNE"))}\t${pad.drumClass}\t${patch.model}")
                    for (macro in listOf("TUNE", "SOURCE", "FILTER", "DRIVE", "SPACE", "DECAY", "HOLD")) append("\t${patch.macros[macro] ?: ""}")
                    append("\t${pad.sampleFile}\n")
                }
            })
            File(staging, "README.txt").writeText(if (fullKit) """
                TEMPEST / accepted six-voice drum kit

                Sixteen dry one-shot pads, mono 44.1 kHz PCM24, 1.20 seconds each.
                A01-A02: accepted GROUND/BREAK kicks, unchanged model 2.
                A03-A04: two accepted SNARE recipes. A05-A06: two accepted CLAP recipes.
                A07-A09: low/mid/high TOM recipes.
                A10-A12: closed/open/noise HAT recipes.
                A13-A16: accepted SHARD/GRAIN/BURR/TRAIL metal colors, unchanged model 2.
                All sixteen positions are owner-accepted factory recipes.
                The ten added positions retain their auditioned model-3 sound.

                MPC 3: copy the complete folder and load SnipSnap Tempest Full Kit.xtd.
                MPC 2: load SnipSnap Tempest Full Kit.xpm inside the TrackData folder.
                Keep all WAVs and kit.json together. Every pad has an editable native recipe.
                pads.tsv records role, measured capture class, model and controls.
                Role names describe the intended sound; capture classification may differ.
                DECAY is available for the four new voices; KICK/METAL keep their old controls.

                Finite samples only. HOLD remains reserved; no sustain loops or velocity layers.
                Closed/open/noise hats share mute group 1, so each cuts off the preceding hat.
                Read-back checks verify the exports; actual MPC loading and phone render timing
                have not been verified. No external effects or copied samples are included.
            """.trimIndent() + "\n" else """
                TEMPEST model 2 / digital percussion

                Sixteen dry one-shot pads, mono 44.1 kHz PCM24, 1.20 seconds each.
                A01-A08: GROUND kicks at E1 G1 A1 B1 D2 E2 G2 A2 (E minor pentatonic).
                A09 RUST, A10 BREAK: alternate KICK colors.
                A11-A16: SHARD WIRE GRAIN LOW BURR TRAIL, the six METAL presets.
                Named colors retain the accepted model-2 audition settings.
                The tuned scale applies those approved settings at the listed notes.

                MPC 3: copy this folder intact and load SnipSnap Tempest Kit.xtd.
                MPC 2: open the TrackData folder and load SnipSnap Tempest Kit.xpm.
                Keep the track/program and sample folders together when copying.
                The TrackData folder contains kit.json with every editable native recipe.
                pads.tsv records voice, requested root, measured class, model and controls.
                The root is an oscillator reference; bent or inharmonic hits need not read
                as that note to an automatic pitch detector.

                HOLD is reserved at zero. These are finite one-shots, without loop points
                or velocity layers. MPC playback velocity changes sample level.
                No external effects or copied sample libraries are included.
                read-back.txt records both export-reader checks. Hardware loading and
                target-phone render timing have not been verified in this environment.
            """.trimIndent() + "\n")
            val files = staging.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(staging).invariantSeparatorsPath }.toList()
            File(staging, "SHA256SUMS.txt").writeText(files.joinToString("\n") {
                "${sha256(it)}  ${it.relativeTo(staging).invariantSeparatorsPath}"
            } + "\n")
            val archive = File(staging, if (fullKit) "tempest-full-kit.zip" else "tempest-kit.zip")
            val contents = staging.walkTopDown().filter { it.isFile && it != archive }.sortedBy { it.relativeTo(staging).invariantSeparatorsPath }.toList()
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                for (file in contents) {
                    zip.putNextEntry(ZipEntry(kit.name + "/" + file.relativeTo(staging).invariantSeparatorsPath).also { it.time = 0L })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            check(archive.length() < 25L * 1024 * 1024) { "TEMPEST kit archive exceeds 25 MiB" }
            if (destination.exists()) check(destination.deleteRecursively()) { "cannot replace generated kit $destination" }
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return File(destination, archive.name)
        } finally {
            work.deleteRecursively()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
