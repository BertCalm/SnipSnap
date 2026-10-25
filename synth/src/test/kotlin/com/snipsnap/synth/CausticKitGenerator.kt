package com.snipsnap.synth

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

/** One dry sixteen-pad native CAUSTIC kit, with editable recipes and both MPC formats. */
object CausticKitGenerator {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1) { "supply at most one CAUSTIC kit output directory" }
        val archive = generate(File(args.firstOrNull() ?: "../testkit/caustic-kit").canonicalFile)
        println("CAUSTIC kit: ${archive.absolutePath} (${archive.length()} bytes)")
    }

    /** Also used by the instrument audition so its kit download is generated, never a placeholder. */
    internal fun generate(destination: File): File {
        destination.parentFile.mkdirs()
        val staging = Files.createTempDirectory(destination.parentFile.toPath(), ".caustic-kit-building-").toFile()
        val work = Files.createTempDirectory("caustic-kit-source-").toFile()
        try {
            val arranged = SynthKits.caustic()
            check(arranged.size == 16 && arranged.all { it != null }) { "CAUSTIC starter must contain sixteen pads" }
            val kit = KitAssembler.assembleArranged("SnipSnap Caustic Kit", arranged, work)
            check(kit.pads.size == 16)
            val result = Mpc3Exporter.exportTrack(kit, work, staging, overwrite = true, mpc2Twin = true)
            val data = File(staging, Mpc3TrackWriter.trackDataDirName(kit.name))
            val twin = File(data, "${kit.name}.xpm")
            check(result.samples.map { it.name }.toSet() == kit.pads.map { it.sampleFile }.toSet())
            // One set of WAVs serves the native track, compatibility program and editable sidecar.
            KitStore.save(kit, data)
            check(KitStore.load(data) == kit) { "CAUSTIC kit sidecar did not round-trip" }
            val findings = listOf(
                ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER, twin, null, result.findings),
            ).flatMap { ExportReadBack.verify(kit, it) }
            check(!findings.blocked()) { "CAUSTIC kit read-back failed: ${findings.joinToString { it.message }}" }
            File(staging, "read-back.txt").writeText(findings.joinToString("\n") { "${it.severity}: ${it.message}" } + "\n")
            File(staging, "pads.tsv").writeText(buildString {
                append("pad\tname\tmidi\tvelocity\tmodel\tSOURCE\tFOCUS\tBODY\tLENGTH\tHOLD\tsample\n")
                for (pad in kit.pads.sortedBy { it.slot }) {
                    val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                    val patch = recipe.patch as? CausticPatch ?: error("CAUSTIC pad lost its native patch")
                    check(recipe.fx == null && recipe.board == null && patch.macros.getValue("HOLD") == 0f)
                    append("${PadNoteMap.labelForPad(pad.slot)}\t${patch.name}\t${patch.midi}\t${patch.velocity}\t${patch.model}")
                    for (macro in listOf("SOURCE", "FOCUS", "BODY", "LENGTH", "HOLD")) append("\t${patch.macros.getValue(macro)}")
                    append("\t${pad.sampleFile}\n")
                }
            })
            File(staging, "README.txt").writeText("""
                CAUSTIC / ALLOY native finite-note starter

                Sixteen dry one-shot pads from the native CausticPatch renderer.
                A01-A08 climb C minor pentatonic from C3 with Fuller Body.
                A09-A16 contain all eight starting patches. New positions await listening.

                MPC 3: copy this folder intact and load SnipSnap Caustic Kit.xtd.
                MPC 2: open the TrackData folder and load SnipSnap Caustic Kit.xpm.
                The same folder contains kit.json with every editable native patch and its WAV.
                pads.tsv records requested note, velocity, model and all five controls.

                HOLD is reserved at zero. These are finite notes, with no seamless loops or
                velocity-layer switching. MPC playback velocity changes sample level.
                LENGTH changes both the finite body/release and delivered duration.
                No external effects, copied sample libraries or realtime synthesis are included.
                read-back.txt records verification by SnipSnap's export readers.
            """.trimIndent() + "\n")
            val files = staging.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(staging).invariantSeparatorsPath }.toList()
            File(staging, "SHA256SUMS.txt").writeText(files.joinToString("\n") {
                "${sha256(it)}  ${it.relativeTo(staging).invariantSeparatorsPath}"
            } + "\n")
            val archive = File(staging, "caustic-kit.zip")
            val contents = staging.walkTopDown().filter { it.isFile && it != archive }.sortedBy { it.relativeTo(staging).invariantSeparatorsPath }.toList()
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                for (file in contents) {
                    zip.putNextEntry(ZipEntry("SnipSnap Caustic Kit/" + file.relativeTo(staging).invariantSeparatorsPath).also { it.time = 0L })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            check(archive.length() < 25L * 1024 * 1024) { "CAUSTIC kit archive exceeds 25 MiB" }
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
