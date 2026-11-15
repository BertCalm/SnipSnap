package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue.*
import com.snipsnap.kit.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Audition assets are written directly from the Kotlin grid, with no Python DSP surrogate. */
object VelourRoundRobinGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/velour-round-robin").absoluteFile.apply { mkdirs() }
        val entries = mutableListOf<com.snipsnap.json.JsonValue>()
        for (t in VelourApprovedKits.Treatment.entries) {
            val started = System.nanoTime()
            val pads = VelourRoundRobinKits.build(t)
            val ms = (System.nanoTime()-started)/1e6
            val folder = File(root, "Velour ${t.id} RR").apply { mkdirs() }
            val kit = KitAssembler.assembleArranged(folder.name, pads, folder, tempoBpm = 84f)
            check(kit == KitStore.load(folder))
            val scratch = File(root, ".${t.id}-export")
            try {
                val sfz = SfzWriter.write(kit, folder, scratch)
                File(folder, "${kit.name}.sfz").writeText(sfz.readText().replace("default_path=Samples/", "default_path=./"))
                val ds = DecentSamplerWriter.write(kit, folder, scratch)
                File(folder, "${kit.name}.dspreset").writeText(ds.readText().replace("path=\"Samples/", "path=\""))
            } finally { scratch.deleteRecursively() }
            File(folder, "READ ME.txt").writeText("Velour round-robin listening candidates v1. 16 pads, three velocities, four takes each. Take 1 is the approved Round 5 sample. Alternates are sample-derived contact/tone variations, not new recordings. Native kit.json, SFZ and DecentSampler contain the full grid. MPC cycling is NOT established; no MPC compatibility fallback is supplied. Hats share choke group 1. CC0 tambourine source attribution: see REFERENCE-LICENSE.txt.\n")
            File(folder, "REFERENCE-LICENSE.txt").writeBytes(VelourApprovedKits.resource("REFERENCE-LICENSE.txt"))
            ZipOutputStream(File(root, "${t.id}-rr.zip").outputStream().buffered()).use { zip ->
                folder.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0 })
                    file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            for ((slot, pad) in pads.withIndex()) for ((zone, takes) in pad.velocityTakes.withIndex()) {
                for ((take, snip) in takes.withIndex()) {
                    val path = "audio/${t.id}/${slot+1}-${zone}-${take}.wav"
                    val file = File(root, path).apply { parentFile.mkdirs() }
                    WavWriter.write(file, snip, depth = WavWriter.BitDepth.PCM_24)
                    entries += Obj(mapOf("treatment" to Str(t.id), "slot" to Num((slot+1).toDouble()),
                        "name" to Str(pad.displayName!!), "zone" to Num(zone.toDouble()), "take" to Num(take.toDouble()),
                        "file" to Str(path), "frames" to Num(snip.frameCount.toDouble()), "level" to Num(pad.level!!.toDouble())))
                }
            }
            println("${t.id}: 192 takes, build ${ms.toInt()} ms; native, SFZ and DecentSampler package written")
        }
        File(root, "manifest.json").writeText(Json.write(Obj(mapOf("version" to Num(1.0), "samples" to Arr(entries)))))
    }
}
