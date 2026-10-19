package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Native dry pads, an MPC 2 twin, and complete editable source recipes in one archive. */
object OverlapKitGenerator {
    const val KIT_NAME = "SnipSnap Overlap Kit"
    const val ARCHIVE_NAME = "SnipSnap-Overlap-Kit.zip"

    @JvmStatic
    fun main(args: Array<String>) {
        println("OVERLAP kit: ${generate(File(args.firstOrNull() ?: "../testkit")).absolutePath}")
    }

    fun generate(root: File): File {
        check(root.isDirectory || root.mkdirs()) { "Cannot create $root" }
        val work = File(root, ".overlap-work")
        val destination = File(root, KIT_NAME)
        check(!work.exists() || work.deleteRecursively()) { "Cannot replace generated staging $work" }
        try {
            val arranged = SynthKits.overlap().map { requireNotNull(it) }
            check(arranged.size == 16)
            val bank = OverlapMusicalBank.all()
            val kit = KitAssembler.assembleArranged(KIT_NAME, arranged, work)
            check(kit.pads.size == 16)
            check(!destination.exists() || destination.deleteRecursively()) { "Cannot replace generated kit $destination" }
            val result = Mpc3Exporter.exportTrack(kit, work, destination, overwrite = true, mpc2Twin = true)
            val data = result.samples.first().parentFile
            val source = File(destination, "Source Kit")
            check(work.copyRecursively(source, overwrite = true)) { "Cannot preserve source kit" }
            val restored = KitStore.load(source)
            check(restored == kit && restored.pads.size == 16) { "Source kit lost pads or recipes" }
            check(result.samples.size == 16 && File(data, "$KIT_NAME.xpm").isFile)
            val imported = Mpc3Project.read(result.program)
            check(imported.isTrack && imported.drumPrograms().size == 1)
            val paths = (imported.data?.get("samples") as JsonValue.Arr).items
                .map { it.obj().getValue("path").str() }
            check(paths.toSet() == restored.pads.map { it.sampleFile }.toSet()) { "Track sample pool differs from the source kit" }

            val padRows = restored.pads.mapIndexed { index, pad ->
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                val patch = recipe.patch as? OverlapPatch ?: error("Pad ${pad.slot} lost its OVERLAP recipe")
                check(recipe.fx == null && patch.model == 2 && patch.macros.size == 6 && pad.oneShot)
                check(if (index < 12) patch == bank[index].patch else {
                    val names = listOf("LOW THREAD", "LOW PIN", "LOW CUP", "LOW SEED")
                    val base = bank[index - 12].patch
                    patch == base.copy(name = names[index - 12], midi = base.midi - 12)
                }) { "Pad ${pad.slot} changes an approved recipe" }
                val replay = recipe.render()
                check(replay.samples.contentEquals(arranged[index].snip.samples)) { "Pad ${pad.slot} cannot replay its native samples" }
                check(replay.channels == 1 && replay.sampleRate == WavWriter.MPC_SAMPLE_RATE &&
                    replay.samples.all { it.isFinite() } && replay.peak() in .000001f..1f)
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, replay) }.toByteArray()
                val original = File(source, pad.sampleFile)
                val exported = result.samples.single { it.name == pad.sampleFile }
                val sha = sha256(original.readBytes())
                check(sha == sha256(encoded) && sha == sha256(exported.readBytes())) {
                    "Pad ${pad.slot} changed gain, format or tail during export"
                }
                val info = WavInfo.read(exported)
                check(info.frameCount == replay.frameCount.toLong() && info.bitsPerSample == 24)
                JsonValue.Obj(linkedMapOf(
                    "pad" to JsonValue.Num(pad.slot.toDouble()),
                    "label" to JsonValue.Str(String.format(Locale.ROOT, "A%02d", pad.slot)),
                    "name" to JsonValue.Str(patch.name),
                    "family" to JsonValue.Str(pad.source.getValue("presetFamily")),
                    "presetId" to JsonValue.Str(pad.source.getValue("presetId")),
                    "basePresetId" to JsonValue.Str(pad.source.getValue("basePresetId")),
                    "transposeSemitones" to JsonValue.Num(pad.source.getValue("transposeSemitones").toDouble()),
                    "referenceMidi" to JsonValue.Num(patch.midi.toDouble()),
                    "referenceNote" to JsonValue.Str(pad.source.getValue("referenceNote")),
                    "seconds" to JsonValue.Num(replay.frameCount.toDouble() / replay.sampleRate),
                    "frames" to JsonValue.Num(replay.frameCount.toDouble()),
                    "oneShot" to JsonValue.Bool(true),
                    "sample" to JsonValue.Str(exported.relativeTo(destination).invariantSeparatorsPath),
                    "sourceSample" to JsonValue.Str(original.relativeTo(destination).invariantSeparatorsPath),
                    "sha256" to JsonValue.Str(sha),
                    "recipeReplayExact" to JsonValue.Bool(true),
                    "recipe" to recipe.toJsonValue(),
                ))
            }
            val manifest = JsonValue.Obj(linkedMapOf(
                "name" to JsonValue.Str(KIT_NAME), "engine" to JsonValue.Str("OVERLAP"),
                "sourceRevision" to JsonValue.Str(git("rev-parse", "HEAD")),
                "sourceTreeDirty" to JsonValue.Bool(git("status", "--porcelain").isNotEmpty()),
                "model" to JsonValue.Num(2.0), "padCount" to JsonValue.Num(16.0),
                "sampleRate" to JsonValue.Num(WavWriter.MPC_SAMPLE_RATE.toDouble()),
                "channels" to JsonValue.Num(1.0), "bitsPerSample" to JsonValue.Num(24.0),
                "leveling" to JsonValue.Str("Exact native dry recipe renders; standard kit mixer level, no audition matching or FX."),
                "playback" to JsonValue.Str("Finite one-shot drum pads. HOLD is finite excitation; no seamless loops or tempo synchronization."),
                "program" to JsonValue.Str(result.program.relativeTo(destination).invariantSeparatorsPath),
                "mpc2Program" to JsonValue.Str(File(data, "$KIT_NAME.xpm").relativeTo(destination).invariantSeparatorsPath),
                "sourceKit" to JsonValue.Str("Source Kit/kit.json"),
                "pads" to JsonValue.Arr(padRows),
            ))
            File(destination, "kit-manifest.json").writeText(Json.write(manifest) + "\n")
            File(destination, "README.txt").writeText(buildString {
                appendLine(KIT_NAME)
                appendLine()
                appendLine("MPC 3: load $KIT_NAME.xtd beside its _[TrackData] folder.")
                appendLine("MPC 2: open the .xpm inside that _[TrackData] folder, beside its WAVs.")
                appendLine("Source Kit/ keeps all sixteen editable SnipSnap recipes and their exact WAVs.")
                appendLine("Keep each program with its sample folder. Copy the complete kit folder to your card.")
                appendLine()
                appendLine("Dry native 44.1 kHz mono PCM24. Every pad plays its full finite sample once.")
                appendLine("Textures and breaths are finite samples, not seamless loops or tempo-synced phrases.")
                appendLine("MIDI references set the resonator geometry; a chord or mixed circuit can imply another pitch.")
                appendLine("A01-A12 preserve the approved musical bank. A13-A16 transpose its four plucks down one octave.")
                appendLine()
                appendLine("PAD MAP")
                restored.pads.forEach { pad ->
                    appendLine(String.format(Locale.ROOT, "A%02d  %-12s  %-7s  reference %s / MIDI %s",
                        pad.slot, pad.displayName, pad.source.getValue("presetFamily"),
                        pad.source.getValue("referenceNote"), pad.source.getValue("referenceMidi")))
                }
                appendLine()
                appendLine("kit-manifest.json records full recipes, lengths and matching source/export WAV SHA-256 hashes.")
            })
            val archive = File(root, ARCHIVE_NAME)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                destination.walkTopDown().filter { it.isFile }
                    .sortedBy { it.relativeTo(destination).invariantSeparatorsPath }.forEach { file ->
                        val path = destination.name + "/" + file.relativeTo(destination).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(path).also { it.time = 0L })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            return archive
        } finally {
            work.deleteRecursively()
        }
    }

    private fun git(vararg args: String): String = runCatching {
        ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start().let { process ->
            val result = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0)
            result
        }
    }.getOrDefault("unavailable")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}
