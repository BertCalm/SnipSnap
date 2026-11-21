package com.snipsnap.synth

import com.snipsnap.kit.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SunroomKitGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/sunroom-native").absoluteFile.apply { mkdirs() }
        for (c in SunroomKits.Choice.entries) {
            val folder = File(root,c.title).apply { mkdirs() }
            val kit = KitAssembler.assembleArranged(c.title,SunroomKits.build(c),folder,tempoBpm=c.tempoBpm)
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
            for (name in listOf("NOTICE.txt", "manifest.json", "percussion-provenance.json", "acoustic-provenance.json", "brush-provenance.json", "crescendo-provenance.json")) {
                File(folder,name).writeBytes(SunroomKits.resource(name))
            }
            File(folder,"READ ME.txt").writeText("Sunroom printed synthesis and resynthesis bank v2: 16 pads, 3 velocities x 3 strikes. Native kit.json, SFZ, DecentSampler and MPC 3 retain the grid in software. MPC 2 twin uses each velocity zone's first take (format limit). Owner reported version 1 hardware playback passes on 2026-11-19; device models and individual format results were not specified. Six soft/medium crescendo files have authored timbral shading in version 2 and await listening approval; all other audio is unchanged. Printed synth renders preserve the matching revision audition exactly; source-model editing is not supplied by this bank. Pad level .65 matches the audition. Finite roll/riser gestures are printed. Psych A12 is the complete eight-second crescendo with authored timbral velocity; other layers use authored or recorded-profile dynamics. This bank does not claim independent physical modeling or listening approval of the revised soft/medium crescendo.\n")
            ZipOutputStream(File(root,"${c.id}.zip").outputStream().buffered()).use { z ->
                folder.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { p ->
                    z.putNextEntry(ZipEntry(p.relativeTo(root).invariantSeparatorsPath).apply { time=0 });p.inputStream().use { it.copyTo(z) };z.closeEntry()
                }
            }
            println("${c.id}: native, SFZ, DecentSampler, MPC 3 and MPC 2 twin written")
        }
    }
}
