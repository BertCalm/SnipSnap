package com.snipsnap.synth

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.math.abs

/** Full-grid packages share one set of PCM files across native, SFZ and DecentSampler programs. */
object CosmicBreaksFamilyGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/cosmic-family").absoluteFile.apply { mkdirs() }
        val index = mutableListOf<JsonValue>()
        for (genre in CosmicBreaksGenres.Genre.entries) {
            val folder = File(root, "${genre.title} Full Grid")
            folder.deleteRecursively()
            val scratch = File(root, ".${genre.id}-work").apply { deleteRecursively(); mkdirs() }
            try {
                val pads = CosmicBreaksGenres.build(genre)
                val kit = KitAssembler.assembleArranged(genre.title, pads, folder, tempoBpm = genre.bpm)
                check(kit == KitStore.load(folder))
                // The downloadable audition bank is PCM16, matching the frozen source depth.
                // The application's normal SFZ export remains PCM24; frame windows are unchanged.
                for (pad in kit.pads) {
                    val file = File(folder, pad.sampleFile)
                    WavWriter.write(file, WavReader.read(file), depth = WavWriter.BitDepth.PCM_16)
                }
                val sfz = File(folder, "${kit.name}.sfz")
                sfz.writeText(SfzWriter.write(kit, folder, scratch).readText().replace("default_path=Samples/", "default_path=./"))
                val ds = File(folder, "${kit.name}.dspreset")
                ds.writeText(DecentSamplerWriter.write(kit, folder, scratch).readText().replace("path=\"Samples/", "path=\""))
                val imported = SfzImporter.import(sfz, File(scratch, "import"))
                check(imported.skipped.isEmpty()) { imported.skipped.toString() }
                check(imported.kit.pads.size == 18)
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ds)
                val samples = doc.getElementsByTagName("sample")
                check(samples.length == 216)
                for ((i, pad) in kit.pads.withIndex()) {
                    val wave = WavReader.read(File(folder, pad.sampleFile))
                    val chain = requireNotNull(pad.chain)
                    val back = imported.kit.pads[i]
                    check(back.chain == chain && back.oneShot == pad.oneShot && back.muteGroup == pad.muteGroup)
                    check(File(folder, pad.sampleFile).readBytes().contentEquals(File(imported.directory, back.sampleFile).readBytes()))
                    for ((n, take) in pads[i].velocityTakes.flatten().withIndex()) {
                        val window = chain.window(n, wave.frameCount.toLong())
                        check(window.last - window.first + 1 == take.frameCount.toLong())
                        check(take.samples.indices.all { abs(take.samples[it] - wave.samples[window.first.toInt() + it]) < (1f / 32768 + 2e-7f) })
                        val sample = samples.item(i * 12 + n) as Element
                        val zone = requireNotNull(chain.zones)[n / 4]
                        check(sample.getAttribute("path") == pad.sampleFile)
                        check(sample.getAttribute("rootNote").toInt() == 35 + pad.slot)
                        check(sample.getAttribute("loVel").toInt() == zone.velStart && sample.getAttribute("hiVel").toInt() == zone.velEnd)
                        check(sample.getAttribute("seqPosition").toInt() == n % 4 + 1)
                        check(sample.getAttribute("start").toLong() == window.first && sample.getAttribute("end").toLong() == window.last)
                        val group = sample.parentNode as Element
                        check(group.getAttribute("seqMode") == "round_robin" && group.getAttribute("seqLength") == "4")
                    }
                }
                File(folder, "README.txt").writeText("""
                    ${genre.title} / ${genre.bpm} BPM
                    ${genre.description}
                    Bank A: 16 hits. Bank B: Orbit Roll and Relay Ascent, finite one-bar gestures with release tails.
                    Every pad: soft 1-41, medium 42-84, hard 85-127; four takes per layer (216 strikes).
                    PCM16 mono / 44.1 kHz; export quantization is checked within one 16-bit step.
                    Open the SFZ or DecentSampler program. Keep all WAVs beside the program files.
                    Native kit.json retains SnipSnap's complete grid. SFZ export/import retains every sample window.
                    These are full-grid exports, not MPC compatibility packages. MPC 3 slice-map/hardware acceptance remains open.
                    MPC 2 cannot represent this 3-by-4 grid on one pad with its four layer slots; no silent fallback is included here.
                    Audio and program structure were checked automatically; physical devices and external sampler apps remain unverified.
                """.trimIndent())
                val zipFile = File(root, "${genre.id}-full-grid.zip")
                ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                    folder.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0L })
                        file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
                val hash = MessageDigest.getInstance("SHA-256").digest(zipFile.readBytes()).joinToString("") { "%02x".format(it) }
                index += JsonValue.Obj(linkedMapOf(
                    "id" to JsonValue.Str(genre.id), "title" to JsonValue.Str(genre.title),
                    "pads" to JsonValue.Arr(kit.pads.map { JsonValue.Str(it.displayName) }),
                    "bpm" to JsonValue.Num(genre.bpm.toDouble()), "description" to JsonValue.Str(genre.description),
                    "file" to JsonValue.Str(zipFile.name), "sha256" to JsonValue.Str(hash),
                    "bytes" to JsonValue.Num(zipFile.length().toDouble()), "folder" to JsonValue.Str(folder.name),
                ))
                println("${genre.title}: 216 PCM windows, SFZ round-trip and DecentSampler grid verified (${zipFile.length()} bytes)")
            } finally { scratch.deleteRecursively() }
        }
        File(root, "family.json").writeText(Json.write(JsonValue.Obj(mapOf(
            "version" to JsonValue.Num(1.0), "kits" to JsonValue.Arr(index),
            "mpcGate" to JsonValue.Str("open: missing verified MPC 3 slice-map/hardware acceptance; MPC 2 grid limit"),
        ))))
    }
}
