package com.snipsnap.synth

import com.snipsnap.kit.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object KneeBreakKitGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/knee-break-native").absoluteFile.apply { mkdirs() }
        for (c in KneeBreakKits.Choice.entries) {
            val folder = File(root,c.title).apply { mkdirs() }
            val kit = KitAssembler.assembleArranged(c.title,KneeBreakKits.build(c),folder,tempoBpm=94f)
            check(kit == KitStore.load(folder))
            val export = Mpc3Exporter.exportTrack(kit,folder,File(folder,"MPC"),mpc2Twin=true)
            check(ExportReadBack.verifyProgram(kit,export.program,ExportFormat.MPC3_TRACK).none { it.severity == Severity.FAIL })
            val scratch = File(root,".${c.id}-export")
            try {
                val sfz = SfzWriter.write(kit,folder,scratch)
                File(folder,"${kit.name}.sfz").writeText(sfz.readText().replace("default_path=Samples/","default_path=./"))
                val ds = DecentSamplerWriter.write(kit,folder,scratch)
                File(folder,"${kit.name}.dspreset").writeText(ds.readText().replace("path=\"Samples/","path=\""))
            } finally { scratch.deleteRecursively() }
            File(folder,"REFERENCE-LICENSE.txt").writeBytes(KneeBreakKits.resource("REFERENCE-LICENSE.txt"))
            File(folder,"NOTICE.txt").writeBytes(KneeBreakKits.resource("NOTICE.txt"))
            File(folder,"READ ME.txt").writeText("Knee Break synthesized kit bank v1: 16 pads, 3 velocities x 3 strikes. Native kit.json, SFZ, DecentSampler and MPC 3 retain the grid in software. MPC 2 twin uses each velocity zone's first take (format limit). No hardware playback claim. Printed synth renders preserve the audition exactly; source-model editing is not supplied by this bank. Pad level .65 matches the audition. Treatments are printed per hit, not a shared live bus.\n")
            ZipOutputStream(File(root,"${c.id}.zip").outputStream().buffered()).use { z ->
                folder.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { p ->
                    z.putNextEntry(ZipEntry(p.relativeTo(root).invariantSeparatorsPath).apply { time=0 });p.inputStream().use { it.copyTo(z) };z.closeEntry()
                }
            }
            println("${c.id}: native, SFZ, DecentSampler, MPC 3 and MPC 2 twin written")
        }
    }
}
