package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Three coherent room-size kits, verified native exports and grooves using their actual velocity WAVs. */
object RivetKitGenerator {
    data class Hit(val slot: Int, val beat: Double, val velocity: Int)
    private fun events(): List<Hit> = buildList {
        for (bar in 0..3) {
            val b = bar * 4.0
            add(Hit(1, b, 127)); add(Hit(5, b + 2.5, 110))
            add(Hit(2, b + 1, 127)); add(Hit(6, b + 3, 110))
            add(Hit(14, b + 2.75, 38))
            for (i in 0..7) add(Hit(if (i == 6) 4 else 3, b + i * .5, if (i % 2 == 0) 100 else 76))
            if (bar % 2 == 1) { add(Hit(7, b + 1.75, 100)); add(Hit(8, b + 3.5, 76)) }
            if (bar == 3) { add(Hit(9, b + 3.25, 110)); add(Hit(10, b + 3.5, 110)); add(Hit(11, b + 3.75, 127)) }
        }
    }.sortedBy { it.beat }

    private fun value(x: Any?): JsonValue = when (x) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(x)
        is Number -> JsonValue.Num(x.toDouble())
        is Boolean -> JsonValue.Bool(x)
        is Map<*, *> -> JsonValue.Obj(x.entries.associate { it.key.toString() to value(it.value) })
        is Iterable<*> -> JsonValue.Arr(x.map(::value))
        else -> error("Unsupported manifest value")
    }

    private fun zip(folder: File, output: File) {
        ZipOutputStream(output.outputStream().buffered()).use { z ->
            for (f in folder.walkTopDown().filter { it.isFile && !it.relativeTo(folder).invariantSeparatorsPath.startsWith(".work/") }.sortedBy { it.relativeTo(folder).path }) {
                z.putNextEntry(ZipEntry(f.relativeTo(folder).invariantSeparatorsPath).apply { time = 0L })
                f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/rivet-kit").absoluteFile.apply { mkdirs() }
        val html = StringBuilder("<!doctype html><html lang=en><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>RIVET kit trial</title><style>body{font:17px/1.5 system-ui;max-width:960px;margin:40px auto;padding:0 20px;background:#181b1d;color:#eee}audio{display:block;width:100%;margin:8px 0}article{padding:16px 0;border-bottom:1px solid #485057}a{color:#ecb871}</style><h1>RIVET · the full drum kit</h1><p>The first kick/snare sound was approved. Tom, metal percussion, the hat pair and these sixteen candidate roles await this listen.</p><p>Three rooms, the same source recipes and the same four-bar 120 BPM score. Grooves play the exported velocity WAVs, with hats choking each other. Preview dynamics use a linear velocity gain and a 2 ms choke fade; this is a software audition, not recorded MPC playback.</p>")
        val allRows = mutableListOf<Map<String, Any?>>()
        val grooves = linkedMapOf<String, FloatArray>()
        for ((space, size) in linkedMapOf("Workshop" to .1f, "Factory" to .55f, "Warehouse" to 1f)) {
            val name = "Rivet $space Kit"
            val folder = File(root, space.lowercase()).apply { mkdirs() }
            val staging = File(folder, ".work")
            val pads = RivetKits.build(size)
            val patches = RivetKits.patches(size)
            try {
                val kit = KitAssembler.assembleArranged(name, pads, staging, tempoBpm = 120f)
                val notes = events().map { Mpc3Note(Mpc3Note.noteFor(it.slot),
                    (it.beat * Mpc3Clip.PULSES_PER_BEAT).roundToLong(), it.velocity / 127f) }
                val exported = Mpc3Exporter.exportTrack(kit, staging, folder, overwrite = true,
                    clip = Mpc3Clip("Rivet Four Bars", 4, notes), mpc2Twin = true)
                val data = File(folder, Mpc3TrackWriter.trackDataDirName(name))
                val checks = ExportReadBack.verify(kit, ExportOutcome(ExportFormat.MPC3_TRACK, exported.program, data, exported.findings)) +
                    ExportReadBack.verify(kit, ExportOutcome(ExportFormat.PROGRAM_FOLDER, File(data, "$name.xpm"), null, exported.findings))
                check(!checks.blocked()) { checks.toString() }
                val saved = KitStore.load(data)
                check(saved.pads.size == 16 && saved.pads.all { it.velocityLayers.size == 3 })
                html.append("<h2>$space</h2><audio controls preload=none src='groove-${space.lowercase()}.wav'></audio><p><a href='${space.lowercase()}-mpc-kit.zip'>Download $space MPC kit</a></p>")
                for (pad in saved.pads.sortedBy { it.slot }) {
                    val i = pad.slot - 1
                    val expected = pads[i].softVariants + pads[i].snip
                    val recipeFile = File(folder, "recipes/A${pad.slot.toString().padStart(2, '0')}.json")
                    recipeFile.parentFile.mkdirs()
                    recipeFile.writeText(PadRecipe(patches[i]).toJsonText())
                    check(PadRecipe.fromJsonValue(pad.recipe!!).patch == patches[i])
                    for ((zone, layer) in pad.velocityLayers.withIndex()) {
                        val file = File(data, layer.sampleFile)
                        val info = WavInfo.read(file); val actual = WavReader.read(file)
                        check(info.sampleRate == 44100 && info.channels == 1 && info.bitsPerSample == 24)
                        check(actual.frameCount == expected[zone].frameCount)
                        val error = actual.samples.indices.maxOf { abs(actual.samples[it] - expected[zone].samples[it]) }
                        check(error <= 3f / 8388608) { "${pad.slot} zone $zone PCM changed by $error" }
                        allRows += mapOf("space" to space, "slot" to pad.slot, "name" to pad.displayName,
                            "voice" to patches[i].voice.name, "zone" to zone, "strike" to RivetKits.velocities[zone],
                            "file" to file.relativeTo(root).invariantSeparatorsPath, "frames" to actual.frameCount,
                            "muteGroup" to pad.muteGroup, "pcmMaxError" to error,
                            "recipe" to recipeFile.relativeTo(root).invariantSeparatorsPath)
                    }
                    val audio = File(data, pad.sampleFile).relativeTo(root).invariantSeparatorsPath
                    html.append("<article><b>A${pad.slot.toString().padStart(2, '0')} ${pad.displayName}</b><audio controls preload=none src='$audio'></audio><a href='${recipeFile.relativeTo(root).invariantSeparatorsPath}'>Saved recipe</a></article>")
                }
                val groove = FloatArray(44100 * 10)
                val score = events()
                for ((index, hit) in score.withIndex()) {
                    val pad = saved.pads.single { it.slot == hit.slot }
                    val layer = pad.velocityLayers.single { hit.velocity in it.velStart..it.velEnd }
                    val snip = WavReader.read(File(data, layer.sampleFile))
                    val start = (hit.beat * .5 * 44100).roundToInt()
                    val next = if (pad.muteGroup == 0) Int.MAX_VALUE else score.drop(index + 1)
                        .firstOrNull { saved.pads.single { p -> p.slot == it.slot }.muteGroup == pad.muteGroup }
                        ?.let { (it.beat * .5 * 44100).roundToInt() } ?: Int.MAX_VALUE
                    for (i in snip.samples.indices) {
                        val at = start + i
                        if (at >= groove.size || at >= next.toLong() + 88) break
                        val choke = if (at < next) 1f else .5f * (1f + cos(PI * (at - next) / 88).toFloat())
                        groove[at] += snip.samples[i] * pad.level * (hit.velocity / 127f) * choke
                    }
                }
                grooves[space] = groove
                File(folder, "PAD-LAYOUT.txt").writeText("RIVET candidate kit: native MPC 3 .xtd, MPC 2 .xpm inside TrackData.\nThree native strike zones per pad. Hat pads 3, 4, 15, 16 share mute group 1.\n" +
                    patches.mapIndexed { i, p -> "A${(i + 1).toString().padStart(2, '0')} ${p.name} (${p.voice})" }.joinToString("\n") + "\nPhysical MPC loading remains unverified for this kit.\n")
            } finally { staging.deleteRecursively() }
            zip(folder, File(root, "${space.lowercase()}-mpc-kit.zip"))
        }
        val peak = grooves.values.maxOf { it.maxOf { x -> abs(x) } }
        val commonGain = .9f / peak.coerceAtLeast(1e-9f)
        for ((space, x) in grooves) {
            for (i in x.indices) x[i] *= commonGain
            WavWriter.write(File(root, "groove-${space.lowercase()}.wav"), Snip(x, 1, 44100))
        }
        val manifest = mapOf("status" to "candidate new voices; first kick/snare core approved",
            "modelVersion" to Rivet.MODEL_VERSION, "bpm" to 120, "grooveCommonGain" to commonGain,
            "events" to events().map { mapOf("slot" to it.slot, "beat" to it.beat, "velocity" to it.velocity) }, "zones" to allRows)
        File(root, "manifest.json").writeText(Json.write(value(manifest)))
        File(root, "index.html").writeText(html.append("<p><a href='manifest.json'>Recipes and verification manifest</a></p></html>").toString())
        println("RIVET kit trial: ${root.absolutePath}; verified ${allRows.size} PCM zones and both MPC formats in three rooms")
    }
}
