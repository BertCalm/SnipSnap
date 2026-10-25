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
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.xpm.PadNoteMap
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One regenerable set of native-pitch quartet recordings shared by both MPC generations. */
object ChamberKitGenerator {
    const val KIT_NAME = ChamberKits.NAME
    const val ARCHIVE_NAME = "SnipSnap-Chamber-Kit.zip"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply only the destination root directory" }
        val archive = generate(File(args.firstOrNull() ?: "../testkit"))
        println("Wrote ${archive.absolutePath}: sixteen CHAMBER one-shots, MPC 3, MPC 2 and complete editable recipes")
    }

    fun generate(outputRoot: File): File {
        val root = outputRoot.canonicalFile
        require(root.isDirectory || root.mkdirs()) { "could not create $root" }
        val work = Files.createTempDirectory("snipsnap-chamber-kit-").toFile()
        val destination = File(root, KIT_NAME)
        try {
            val arranged = SynthKits.chamber()
            require(arranged.size == 16 && arranged.all { it != null }) { "CHAMBER must supply exactly sixteen pads" }
            val recipes = arranged.mapIndexed { index, entry ->
                val pad = requireNotNull(entry)
                require(pad.drumClass == DrumClass.TONAL && pad.oneShot) { "pad ${index + 1} is not a tonal one-shot" }
                require(pad.takes.isEmpty() && pad.softVariants.isEmpty()) { "CHAMBER starter pads have one sample each" }
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                val patch = recipe.patch as? ChamberPatch ?: error("pad ${index + 1} has no CHAMBER patch")
                require(recipe.fx == null && recipe.treatment == null && recipe.amount == null) { "CHAMBER starter pads must remain dry" }
                require(patch.model == ChamberKits.MODEL && patch.macros.getValue("HOLD") == 0f) {
                    "CHAMBER starter pads must retain the finite model-three recording"
                }
                require(Patches.fromJsonText(patch.toJsonText()) == patch) { "pad ${index + 1} lost room or performance fields on save" }
                recipe
            }
            val kit = KitAssembler.assembleArranged(KIT_NAME, arranged, work, key = KeySpec.parse("Cminpent"))
            require(kit.pads.map { it.slot } == (1..16).toList())
            require(kit.pads.all { it.oneShot && it.drumClass == DrumClass.TONAL && it.muteGroup == 0 &&
                it.chain == null && it.velocityLayers.isEmpty() && it.tuneCoarse == 0 && it.tuneFine == 0 })
            val staging = Preflight.check(kit, work)
            require(!staging.blocked()) { "CHAMBER staging failed preflight: $staging" }
            check(!destination.exists() || destination.deleteRecursively()) { "could not replace generated kit $destination" }
            val result = Mpc3Exporter.exportTrack(kit, work, destination, overwrite = true, mpc2Twin = true)
            require(result.samples.size == 16 && result.program.isFile)
            val data = result.samples.first().parentFile
            require(result.samples.all { it.parentFile == data })
            require(kit.pads.map { it.sampleFile }.toSet() == result.samples.map { it.name }.toSet()) {
                "exported filenames no longer match the editable recipes"
            }
            // Keep the exporter's actual-WAV fingerprint: the importer uses it before
            // trusting that a saved room recipe still describes these sample bytes.
            val restored = KitStore.load(data)
            require(restored.copy(pads = restored.pads.map { it.copy(source = it.source - "rawSha256") }) == kit) {
                "the exported kit lost saved recipes or pad settings"
            }
            val exported = Preflight.check(restored, data)
            require(!exported.blocked()) { "exported CHAMBER kit failed preflight: $exported" }
            val mpc2 = File(data, "$KIT_NAME.xpm")
            require(mpc2.isFile) { "MPC 2 twin is missing" }
            val readBack = listOf(
                ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings),
                ExportOutcome(ExportFormat.PROGRAM_FOLDER, mpc2, null, result.findings),
            ).flatMap { ExportReadBack.verify(kit, it) }
            require(!readBack.blocked()) { "CHAMBER export read-back failed: $readBack" }

            val pads = kit.pads.zip(recipes).map { (pad, recipe) ->
                val patch = recipe.patch as ChamberPatch
                val sample = File(data, pad.sampleFile)
                val info = WavInfo.read(sample)
                require(info.sampleRate == 44_100 && info.channels == 1 && info.bitsPerSample == 24 && info.frameCount > 0)
                require(info.frameCount <= Chamber.MAX_SECONDS.toLong() * info.sampleRate)
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
                    "name" to pad.displayName, "preset" to pad.source.getValue("preset"), "role" to pad.source.getValue("role"),
                    "voice" to patch.voice.name, "class" to "TONAL", "playback" to "ONE_SHOT",
                    "oneShot" to true, "loop" to false, "muteGroup" to 0, "effects" to false,
                    "pitchMidi" to patch.midi, "note" to Scales.nameOf(patch.midi),
                    "triggerMidiMpc3" to Mpc3Note.noteFor(pad.slot),
                    "triggerMidiMpc2" to PadNoteMap.noteForPad(pad.slot),
                    "velocity" to patch.velocity, "seed" to patch.seed.toString(), "model" to patch.model,
                    "macros" to patch.macros, "configuration" to patch.configuration.toJsonValue(),
                    "frames" to info.frameCount, "durationSeconds" to info.frameCount.toDouble() / info.sampleRate,
                    "sample" to sample.relativeTo(destination).invariantSeparatorsPath, "sampleSha256" to sha256(sample),
                    "recipe" to recipeFile.relativeTo(destination).invariantSeparatorsPath, "recipeSha256" to sha256(recipeFile),
                    "savedRecipe" to recipe.toJsonValue(),
                )
            }
            val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
                .firstOrNull { File(it, ".git").exists() } ?: error("run within the SnipSnap repository")
            val manifest = linkedMapOf<String, Any?>(
                "schemaVersion" to 1, "engine" to ChamberPatch.ENGINE, "name" to KIT_NAME,
                "padCount" to 16, "model" to ChamberKits.MODEL, "renderSource" to "PadRecipe.render",
                "class" to "TONAL", "playback" to "ONE_SHOT", "effects" to false,
                "scope" to "Sixteen finite strikes. HOLD is zero. Each note contains its own coupled room; no shared live room or sustained keygroup is exported.",
                "layout" to "A01–A08 Four Answers C3–F4 in C minor pentatonic; A09–A14 the six factory recipes; A15 Uniform Room; A16 Low Canopy.",
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
                "hardwareAcceptance" to "Pending owner phone/MPC loading, pad response and ending checks.",
                "pads" to pads,
            )
            File(destination, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
            File(destination, "read-back.txt").writeText(readBack.joinToString("\n") { "${it.severity}: ${it.message}" } + "\n")
            File(destination, "README.txt").writeText("""
                SNIPSNAP CHAMBER KIT — sixteen dry finite quartet recordings

                Copy this folder intact to the MPC card.
                MPC 3: load SnipSnap Chamber Kit.xtd and keep its _[TrackData] folder beside it.
                MPC 2: open SnipSnap Chamber Kit_[TrackData] and load SnipSnap Chamber Kit.xpm.
                Both programs share sixteen mono 44.1 kHz PCM24 WAVs. kit.json in the data
                folder makes those same samples an editable SnipSnap kit; recipes/ holds
                each complete PadRecipe separately. manifest.json records native notes,
                trigger mappings, model, velocity, exact seed, room configuration and hashes.
                MPC 3 triggers use chromatic notes from 36; the MPC 2 twin preserves
                the classic MPC pad map. Those trigger notes do not change WAV pitch.

                A01–A08: Four Answers — C3, Eb3, F3, G3, Bb3, C4, Eb4, F4.
                A09–A14: Four Answers, Soft Split, Glass Corners, Aquarium Ceiling,
                Quiet Canopy and High Water, at their saved C4 note.
                A15: Uniform Room. A16: Low Canopy. Both are saved C4 room contrasts.

                All pads retain model three, with the accepted membrane voicing and its
                three-decibel mix lift. Notes are rendered natively: the pad program adds
                no pitch shift or effects. Every pad is TONAL and ONE_SHOT, with its full
                finite ending, no mute group and one sample. Hardware velocity changes
                playback level; the saved excitation velocity is baked into each WAV.

                Each recording already contains its own coupled room. Chords overlap
                independent recordings. Transposing a WAV also transposes its room;
                native re-rendering preserves the authored room geometry and resonances.
                HOLD is zero. No settled sustain loop, note-off release or live room
                shared across pads is claimed. Test loading, note layout, pad response
                and clean endings on the actual phone/MPC before hardware acceptance.

                Rebuild: ./gradlew :synth:generateChamberKit
            """.trimIndent() + "\n")
            val archive = File(root, ARCHIVE_NAME)
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                for (file in destination.walkTopDown().filter { it.isFile }
                    .sortedBy { it.relativeTo(destination).invariantSeparatorsPath }) {
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
