package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportOutcome
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.xpm.PadNoteMap
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Sixteen production PLUCK/UPRIGHT notes, with the same audio and recipes in both MPC generations. */
object UprightKitGenerator {
    const val KIT_NAME = "SnipSnap Upright Kit"
    const val ARCHIVE_NAME = "SnipSnap-Upright-Kit.zip"
    private val midis = listOf(28, 31, 33, 35, 38, 40, 43, 45)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply only the destination root directory" }
        println("Wrote ${generate(File(args.firstOrNull() ?: "../testkit")).absolutePath}")
    }

    fun generate(outputRoot: File): File {
        val root = outputRoot.canonicalFile
        require(root.isDirectory || root.mkdirs()) { "could not create $root" }
        val work = Files.createTempDirectory("snipsnap-upright-kit-").toFile()
        val destination = File(root, KIT_NAME)
        try {
            val arranged = UprightKits.classic()
            require(arranged.size == 16 && arranged.all { it != null }) { "UPRIGHT needs sixteen nonempty pads" }
            val recipes = arranged.mapIndexed { index, entry ->
                val pad = requireNotNull(entry)
                require(pad.drumClass == DrumClass.TONAL && pad.oneShot && pad.takes.isEmpty() && pad.softVariants.isEmpty())
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                val patch = recipe.patch as? PluckPatch ?: error("pad ${index + 1} has no PLUCK patch")
                require(patch.voice == PluckVoice.UPRIGHT && patch.velocity == .65f)
                val midi = 28 + (patch.macros.getValue("TUNE") * Pluck.TUNE_SEMITONES).roundToInt()
                require(midi == midis[index % midis.size]) { "pad ${index + 1} has the wrong native note" }
                require(patch.macros.getValue("DAMP") == if (index < 8) .18f else 1f)
                require(recipe.fx == null && recipe.treatment == null && recipe.amount == null &&
                    recipe.board == null && recipe.pedals == null && recipe.terminalFadeMs == null) { "the starter stays dry" }
                require(PadRecipe.fromJsonText(recipe.toJsonText()) == recipe) { "pad ${index + 1} loses editable state" }
                recipe
            }
            val kit = KitAssembler.assembleArranged(KIT_NAME, arranged, work, key = KeySpec.parse("Eminpent"))
            require(kit.pads.map { it.slot } == (1..16).toList())
            require(kit.pads.all { it.drumClass == DrumClass.TONAL && it.oneShot && it.muteGroup == 0 &&
                it.chain == null && it.velocityLayers.isEmpty() && it.tuneCoarse == 0 && it.tuneFine == 0 })
            val staging = Preflight.check(kit, work)
            require(!staging.blocked()) { "UPRIGHT staging failed preflight: $staging" }
            check(!destination.exists() || destination.deleteRecursively()) { "could not replace generated kit $destination" }
            val result = Mpc3Exporter.exportTrack(kit, work, destination, overwrite = true, mpc2Twin = true)
            require(result.samples.size == 16 && result.program.isFile)
            val data = result.samples.first().parentFile
            require(result.samples.all { it.parentFile == data })
            val restored = KitStore.load(data)
            require(restored.copy(pads = restored.pads.map { it.copy(source = it.source - "rawSha256") }) == kit) {
                "the exported kit lost its saved recipes or pad settings"
            }
            val exported = Preflight.check(restored, data)
            require(!exported.blocked()) { "UPRIGHT export failed preflight: $exported" }
            val mpc2 = File(data, "$KIT_NAME.xpm")
            require(mpc2.isFile)
            val readBack = listOf(
                ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER, mpc2, null, result.findings),
            ).flatMap { ExportReadBack.verify(kit, it) }
            require(!readBack.blocked()) { "UPRIGHT program read-back failed: $readBack" }

            val pads = kit.pads.zip(recipes).mapIndexed { index, (pad, recipe) ->
                val patch = recipe.patch as PluckPatch
                val sample = File(data, pad.sampleFile)
                val info = WavInfo.read(sample)
                require(info.sampleRate == 44_100 && info.channels == 1 && info.bitsPerSample == 24)
                require(info.frameCount == requireNotNull(arranged[index]).snip.frameCount.toLong())
                require(sha256(sample) == sha256(File(work, pad.sampleFile))) { "export changed pad ${pad.slot}'s audio" }
                require(restored.pads.single { it.slot == pad.slot }.source["rawSha256"] == sha256(sample))
                // Rebuild from the decoded, saved production recipe, then encode using the actual writer.
                // This compares a fresh render with the exported PCM, not an array with itself.
                val replay = PadRecipe.fromJsonText(recipe.toJsonText()).render()
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, replay) }.toByteArray()
                require(sha256(encoded) == sha256(sample)) { "saved recipe cannot replay pad ${pad.slot}" }
                val pcm = WavReader.read(sample)
                require(pcm.samples.all { it.isFinite() } && pcm.samples.maxOf { abs(it) } < 1f)
                val recipeFile = File(destination, "recipes/${PadNoteMap.labelForPad(pad.slot)}.json")
                recipeFile.parentFile.mkdirs()
                recipeFile.writeText(recipe.toJsonText() + "\n")
                linkedMapOf<String, Any?>(
                    "slot" to pad.slot, "pad" to PadNoteMap.labelForPad(pad.slot), "name" to pad.displayName,
                    "engine" to PluckPatch.ENGINE, "voice" to patch.voice.name,
                    "articulation" to if (index < 8) "ROUND" else "MUTED",
                    "pitchMidi" to midis[index % midis.size], "note" to Scales.nameOf(midis[index % midis.size]),
                    "triggerMidi" to PadNoteMap.noteForPad(pad.slot), "velocity" to patch.velocity,
                    "macros" to patch.macros, "class" to "TONAL", "oneShot" to true,
                    "loop" to false, "muteGroup" to 0, "effects" to false,
                    "frames" to info.frameCount, "durationSeconds" to info.frameCount.toDouble() / info.sampleRate,
                    "sample" to sample.relativeTo(destination).invariantSeparatorsPath, "sampleSha256" to sha256(sample),
                    "recipe" to recipeFile.relativeTo(destination).invariantSeparatorsPath,
                    "recipeSha256" to sha256(recipeFile), "savedRecipe" to recipe.toJsonValue(),
                    "productionRecipeReplaysExportedPcm" to true,
                )
            }
            val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
                .firstOrNull { File(it, ".git").exists() } ?: error("run within the SnipSnap checkout")
            val sourcePaths = listOf("UprightBass.kt", "UprightKits.kt", "Pluck.kt", "Patches.kt", "Dsp.kt", "Tide.kt")
            val manifest = linkedMapOf<String, Any?>(
                "schemaVersion" to 1, "name" to KIT_NAME, "engine" to PluckPatch.ENGINE, "voice" to "UPRIGHT",
                "padCount" to 16, "renderSource" to "PadRecipe.render", "class" to "TONAL", "oneShot" to true,
                "loop" to false, "effects" to false,
                "layout" to "A01–A08 ROUND, A09–A16 MUTED. Both rows: E1, G1, A1, B1, D2, E2, G2, A2.",
                "audio" to linkedMapOf("sampleRate" to 44_100, "channels" to 1, "format" to "PCM_24"),
                "source" to linkedMapOf("revision" to git(repo, "rev-parse", "HEAD"),
                    "dirty" to git(repo, "status", "--porcelain").isNotEmpty(),
                    "fileSha256" to sourcePaths.associateWith { sha256(File(repo, "synth/src/main/kotlin/com/snipsnap/synth/$it")) }),
                "programs" to linkedMapOf("mpc3" to result.program.relativeTo(destination).invariantSeparatorsPath,
                    "mpc3Sha256" to sha256(result.program), "mpc2" to mpc2.relativeTo(destination).invariantSeparatorsPath,
                    "mpc2Sha256" to sha256(mpc2), "kit" to File(data, KitStore.FILE_NAME).relativeTo(destination).invariantSeparatorsPath,
                    "kitSha256" to sha256(File(data, KitStore.FILE_NAME))),
                "verification" to linkedMapOf("stagingPreflight" to true, "exportPreflight" to true,
                    "bothProgramsReadBack" to true, "allSavedRecipesReplayPcm" to true, "hardwareListen" to false),
                "scope" to "Finite one-shot pads with saved excitation velocity. MPC touch changes playback level; no held keygroup or release behavior is claimed.",
                "pads" to pads,
            )
            File(destination, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
            File(destination, "read-back.txt").writeText(readBack.joinToString("\n") { "${it.severity}: ${it.message}" } + "\n")
            File(destination, "README.txt").writeText("""
                SNIPSNAP UPRIGHT KIT — sixteen dry PLUCK/UPRIGHT notes

                Copy this folder intact to the MPC card.
                MPC 3: load SnipSnap Upright Kit.xtd and keep its _[TrackData] folder beside it.
                MPC 2: open SnipSnap Upright Kit_[TrackData] and load SnipSnap Upright Kit.xpm.
                Both programs share sixteen mono 44.1 kHz PCM24 WAVs. The data folder's
                kit.json retains the editable SnipSnap recipes; recipes/ also holds each
                complete PadRecipe separately. manifest.json records notes, playback,
                excitation velocity, source fingerprints and audio hashes.

                A01–A08 ROUND; A09–A16 MUTED.
                Each row: E1, G1, A1, B1, D2, E2, G2, A2 (E minor pentatonic).

                The saved playing strength is .65 for every note. The two articulations
                share TOUCH .5 and WOOD .55; DAMP is .18 or 1. Every pad is a dry TONAL
                one-shot without tuning offsets, choke groups, loops or velocity layers.
                MPC touch scales the baked sample's playback level; it does not rerender
                the excitation. Program structure and exact recipe replay were checked
                by this generator. Phone/MPC listening remains an owner hardware check.

                Rebuild: ./gradlew :synth:generateUprightKit
            """.trimIndent() + "\n")
            val archive = File(root, ARCHIVE_NAME)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                destination.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(destination).invariantSeparatorsPath }.forEach { file ->
                    zip.putNextEntry(ZipEntry(destination.name + "/" + file.relativeTo(destination).invariantSeparatorsPath).also { it.time = 0L })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            File(root, "$ARCHIVE_NAME.sha256").writeText("${sha256(archive)}  $ARCHIVE_NAME\n")
            return archive
        } finally {
            work.deleteRecursively()
        }
    }

    private fun git(repo: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0) { "git failed: $text" }
        return text
    }

    private fun sha256(file: File) = sha256(file.readBytes())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported JSON value ${value.javaClass.name}")
    }
}
