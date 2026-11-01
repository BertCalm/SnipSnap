package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scales
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
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Both MPC generations and the editable SnipSnap kit share one set of sixteen dry WAVs. */
object MurmurSelectedKitGenerator {
    const val KIT_NAME = "SnipSnap Murmur Selected Kit"
    const val ARCHIVE_NAME = "SnipSnap-Murmur-Selected-Kit.zip"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply only the destination root directory" }
        val archive = generate(File(args.firstOrNull() ?: "../testkit"))
        println("Wrote ${archive.absolutePath}: sixteen selected Murmur one-shots, MPC 3, MPC 2 and complete editable recipes")
    }

    fun generate(outputRoot: File): File {
        val root = outputRoot.canonicalFile
        require(root.isDirectory || root.mkdirs()) { "could not create $root" }
        val work = Files.createTempDirectory("snipsnap-murmur-selected-kit-").toFile()
        val destination = File(root, KIT_NAME)
        try {
            val arranged = SynthKits.murmurSelected()
            require(arranged.size == 16 && arranged.all { it != null }) { "MURMUR SELECTED must supply exactly sixteen pads" }
            val recipes = arranged.mapIndexed { index, entry ->
                val pad = requireNotNull(entry)
                require(pad.drumClass in setOf(DrumClass.TONAL, DrumClass.LOOP) && pad.oneShot) { "pad ${index + 1} is not a tonal one-shot" }
                require(pad.takes.isEmpty() && pad.softVariants.isEmpty()) { "MURMUR SELECTED starter pads have one sample each" }
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                val patch = recipe.patch as? MurmurPatch ?: error("pad ${index + 1} has no MURMUR SELECTED patch")
                require(recipe.fx == null && recipe.treatment == null && recipe.amount == null) { "MURMUR SELECTED starter pads must remain dry" }
                require(patch.config.model in 2..3) { "selected recipes must retain their saved render definition" }
                require(Patches.fromJsonText(patch.toJsonText()) == patch) { "pad ${index + 1} lost performance fields on save" }
                recipe
            }
            val kit = KitAssembler.assembleArranged(KIT_NAME, arranged, work, key = KeySpec.parse("Cminpent"))
            require(kit.pads.map { it.slot } == (1..16).toList())
            require(kit.pads.all { it.oneShot && it.drumClass in setOf(DrumClass.TONAL, DrumClass.LOOP) && it.muteGroup == 0 &&
                it.chain == null && it.velocityLayers.isEmpty() && it.tuneCoarse == 0 && it.tuneFine == 0 })
            val staging = Preflight.check(kit, work)
            require(!staging.blocked()) { "MURMUR SELECTED staging failed preflight: $staging" }
            check(!destination.exists() || destination.deleteRecursively()) { "could not replace generated kit $destination" }
            val result = Mpc3Exporter.exportTrack(kit, work, destination, overwrite = true, mpc2Twin = true)
            require(result.samples.size == 16 && result.program.isFile)
            val data = result.samples.first().parentFile
            require(result.samples.all { it.parentFile == data })
            require(kit.pads.map { it.sampleFile }.toSet() == result.samples.map { it.name }.toSet()) {
                "exported filenames no longer match the editable recipes"
            }
            // The exporter already writes kit.json, including the actual WAV fingerprint used
            // when editable history is imported. Keep that fingerprint rather than overwriting
            // the exported sidecar with the pre-export kit.
            val restored = KitStore.load(data)
            require(restored.copy(pads = restored.pads.map { it.copy(source = it.source - "rawSha256") }) == kit) {
                "the exported kit lost saved recipes or pad settings"
            }
            val exported = Preflight.check(restored, data)
            require(!exported.blocked()) { "exported MURMUR SELECTED kit failed preflight: $exported" }
            val mpc2 = File(data, "$KIT_NAME.xpm")
            require(mpc2.isFile) { "MPC 2 twin is missing" }
            val readBack = listOf(
                ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER, mpc2, null, result.findings),
            ).flatMap { ExportReadBack.verify(kit, it) }
            require(!readBack.blocked()) { "MURMUR SELECTED export read-back failed: $readBack" }

            val pads = kit.pads.zip(recipes).map { (pad, recipe) ->
                val patch = recipe.patch as MurmurPatch
                val sample = File(data, pad.sampleFile)
                val info = WavInfo.read(sample)
                require(info.sampleRate == 44_100 && info.channels == 1 && info.bitsPerSample == 24 && info.frameCount > 0)
                val duration = info.frameCount.toDouble() / info.sampleRate
                require(duration > 0.0 && duration <= Murmur.MAX_SECONDS) { "pad ${pad.slot} has an invalid duration" }
                require(sha256(sample) == sha256(File(work, pad.sampleFile))) { "export changed pad ${pad.slot}'s audio" }
                require(restored.pads.single { it.slot == pad.slot }.source["rawSha256"] == sha256(sample)) {
                    "pad ${pad.slot}'s exported recipe fingerprint does not match its WAV"
                }
                val recipeFile = File(destination, "recipes/${PadNoteMap.labelForPad(pad.slot)}.json")
                recipeFile.parentFile.mkdirs()
                recipeFile.writeText(recipe.toJsonText() + "\n")
                require(PadRecipe.fromJsonText(recipeFile.readText()) == recipe)
                linkedMapOf<String, Any?>(
                    "slot" to pad.slot, "pad" to PadNoteMap.labelForPad(pad.slot),
                    "name" to pad.displayName, "preset" to patch.name,
                    "voice" to patch.voice.name, "class" to pad.drumClass.name, "playback" to "ONE_SHOT",
                    "oneShot" to true, "loop" to false, "muteGroup" to 0, "effects" to false,
                    "pitchMidi" to patch.midi, "note" to Scales.nameOf(patch.midi),
                    "triggerMidi" to PadNoteMap.noteForPad(pad.slot),
                    "velocity" to patch.velocity, "model" to patch.config.model, "macros" to patch.macros,
                    "terminalFadeMs" to recipe.terminalFadeMs, "sourceHeld" to Murmur.isLoop(patch.macros.getValue("HOLD")),
                    "frames" to info.frameCount, "durationSeconds" to duration,
                    "sample" to sample.relativeTo(destination).invariantSeparatorsPath, "sampleSha256" to sha256(sample),
                    "recipe" to recipeFile.relativeTo(destination).invariantSeparatorsPath, "recipeSha256" to sha256(recipeFile),
                    "savedRecipe" to recipe.toJsonValue(),
                )
            }
            val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
                .firstOrNull { File(it, ".git").exists() } ?: error("run within the SnipSnap repository")
            val manifest = linkedMapOf<String, Any?>(
                "schemaVersion" to 1, "engine" to MurmurPatch.ENGINE, "name" to KIT_NAME,
                "padCount" to 16, "renderSource" to "PadRecipe.render",
                "playback" to "ONE_SHOT", "effects" to false,
                "scope" to "Four heard C4 KEY settings, lower-breath C2/C3 CHOIR, a six-note KEY scale and four original voice contrasts. Held sources play once with an 8 ms terminal fade.",
                "layout" to "A01–A06 KEY C3/Eb3/F3/G3/Bb3/C4; A07–A10 KEY C4 MOTION 0/.5/.75/1; A11–A14 BASS/PLUCK/LEAD/METAL contrasts; A15–A16 CHOIR C2/C3 BREATH .1.",
                "audio" to linkedMapOf("sampleRate" to 44_100, "channels" to 1, "format" to "PCM_24"),
                "source" to linkedMapOf("revision" to git(repo, "rev-parse", "HEAD"),
                    "dirty" to git(repo, "status", "--porcelain").isNotEmpty()),
                "programs" to linkedMapOf(
                    "mpc3" to result.program.relativeTo(destination).invariantSeparatorsPath, "mpc3Sha256" to sha256(result.program),
                    "mpc2" to mpc2.relativeTo(destination).invariantSeparatorsPath, "mpc2Sha256" to sha256(mpc2),
                    "kit" to File(data, KitStore.FILE_NAME).relativeTo(destination).invariantSeparatorsPath,
                    "kitSha256" to sha256(File(data, KitStore.FILE_NAME)),
                ),
                "preflight" to linkedMapOf("stagingPassed" to true, "exportPassed" to true, "readBackPassed" to true),
                "pads" to pads,
            )
            File(destination, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
            File(destination, "read-back.txt").writeText(readBack.joinToString("\n") { "${it.severity}: ${it.message}" } + "\n")
            File(destination, "README.txt").writeText("""
                SNIPSNAP MURMUR SELECTED KIT

                Copy this folder intact to the MPC card.
                MPC 3: load SnipSnap Murmur Selected Kit.xtd and retain its _[TrackData] folder.
                MPC 2: open that data folder and load SnipSnap Murmur Selected Kit.xpm.
                Both formats share sixteen mono 44.1 kHz PCM24 WAVs. kit.json in the data
                folder retains editable recipes; recipes/ also saves each PadRecipe separately.

                A01–A06: KEY, MOTION .5, C3/Eb3/F3/G3/Bb3/C4.
                A07–A10: the four heard KEY settings, C4, MOTION 0/.5/.75/1.
                A11–A14: original CLOSED CIRCUIT, SHORT ANSWER, OPEN THROAT and COPPER HUM.
                A15–A16: lower-air CHOIR, C2/C3, BREATH .1, with the longer held source.

                All pads play once. A15/A16 use an 8 ms saved terminal fade for their stop;
                their underlying held source and saved configuration retain seamless cycles.
                No continuous MPC drum-pad loop or keygroup sustain is claimed. Program
                tuning is neutral, with no rack effects or mute groups. Excitation velocity
                is saved in each patch; hardware velocity changes playback level.

                The six selected settings reproduce the current listening recipes. Transposed
                KEY notes and the complete factory bank have no separate new listening approval.
                The original MURMUR starter and its preset definitions remain available.
                Check phone playback, MPC loading, pad response and endings on your hardware.

                Rebuild: ./gradlew --no-daemon :synth:generateMurmurSelectedKit
            """.trimIndent() + "\n")
            val archive = File(root, ARCHIVE_NAME)
            val packagedFiles = destination.walkTopDown().filter { it.isFile }
                .sortedBy { it.relativeTo(destination).invariantSeparatorsPath }.toList()
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                for (file in packagedFiles) {
                    val path = destination.name + "/" + file.relativeTo(destination).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(path).also { it.time = 0L })
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

    private fun git(repo: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0) { "git failed" }
        return output
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported JSON value ${value.javaClass.name}")
    }
}
