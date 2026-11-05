package com.snipsnap.synth

import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import java.nio.file.Files

/** Sixteen actual finite samples, editable kit sidecar and both MPC program formats. */
object LorentzKitGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/lorentz-kit").absoluteFile
        root.mkdirs()
        val work = Files.createTempDirectory("snipsnap-lorentz-kit-").toFile()
        try {
            val kit = KitAssembler.assembleArranged("SnipSnap Lorentz Kit", SynthKits.lorentz(), work)
            val result = Mpc3Exporter.exportTrack(kit, work, root, overwrite = true, mpc2Twin = true)
            val data = File(root, Mpc3TrackWriter.trackDataDirName(kit.name))
            KitStore.save(kit, data)
            check(KitStore.load(data) == kit)
            val findings = listOf(
                ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER, File(data, "${kit.name}.xpm"), null, result.findings),
            ).flatMap { ExportReadBack.verify(kit, it) }
            check(!findings.blocked()) { "LORENTZ export read-back failed: $findings" }
            File(root, "read-back.txt").writeText(findings.joinToString("\n") { "${it.severity}: ${it.message}" })
            println("Wrote ${result.program}, sixteen dry WAVs, compatibility XPM and editable kit.json")
        } finally {
            work.deleteRecursively()
        }
    }
}
