package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Scales
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The sixteen finite factory pads, with complete recipes and shared MPC 3 / MPC 2 audio. */
object FlockKitGenerator {
    private const val NAME = "SnipSnap Flock Kit"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply only the destination root directory" }
        val root = File(args.firstOrNull() ?: "../testkit").canonicalFile
        val work = File(root, ".flock-work")
        val destination = File(root, NAME)
        require(root.isDirectory || root.mkdirs()) { "could not create $root" }
        check(!work.exists() || work.deleteRecursively()) { "could not clear generated staging directory $work" }
        try {
            val arranged = SynthKits.flock()
            require(arranged.size == 16 && arranged.all { it != null }) { "FLOCK must supply exactly sixteen pads" }
            val recipes = arranged.mapIndexed { index, entry ->
                val pad = requireNotNull(entry)
                require(pad.drumClass == DrumClass.TONAL && pad.oneShot) { "pad ${index + 1} is not a tonal one-shot" }
                require(pad.takes.isEmpty() && pad.softVariants.isEmpty()) { "pad ${index + 1} adds a chain or velocity layers" }
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe) { "pad ${index + 1} has no saved recipe" })
                val patch = recipe.patch as? FlockPatch ?: error("pad ${index + 1} has no FLOCK patch")
                require(recipe.fx == null && recipe.treatment == null && recipe.amount == null) { "factory FLOCK pads must remain dry" }
                require(patch.model == 2) { "factory FLOCK pads must preserve model 2" }
                require(Patches.fromJsonText(patch.toJsonText()) == patch) { "pad ${index + 1} loses its seeded performance on save" }
                recipe
            }
            val kit = KitAssembler.assembleArranged(NAME, arranged, work,
                key = KeySpec(rootSemitone = 0, scale = Scale.MINOR_PENTATONIC))
            require(kit.pads.size == 16 && kit.pads.map { it.slot } == (1..16).toList())
            require(kit.pads.all { it.oneShot && it.drumClass == DrumClass.TONAL && it.muteGroup == 0 &&
                it.chain == null && it.velocityLayers.isEmpty() }) { "FLOCK export must contain sixteen unchoked tonal one-shots" }
            val stagedFindings = Preflight.check(kit, work)
            require(!stagedFindings.blocked()) { "FLOCK staging failed preflight: $stagedFindings" }
            val result = Mpc3Exporter.exportTrack(kit, work, destination, overwrite = true, mpc2Twin = true)
            require(result.samples.size == 16 && result.program.isFile)
            val data = result.samples.first().parentFile
            require(result.samples.all { it.parentFile == data })
            require(kit.pads.map { it.sampleFile }.toSet() == result.samples.map { it.name }.toSet()) {
                "exported filenames no longer match the editable kit recipes"
            }
            KitStore.save(kit, data)
            val loaded = KitStore.load(data)
            require(loaded.pads.map { it.recipe } == recipes.map { it.toJsonValue() }) { "exported kit lost a complete recipe" }
            require(loaded.pads.all { it.oneShot && it.drumClass == DrumClass.TONAL && it.muteGroup == 0 })
            val exportedFindings = Preflight.check(loaded, data)
            require(!exportedFindings.blocked()) { "exported FLOCK kit failed preflight: $exportedFindings" }
            val mpc2 = File(data, "$NAME.xpm")
            require(mpc2.isFile) { "the MPC 2 twin is missing" }
            val pads = kit.pads.zip(recipes).map { (pad, recipe) ->
                val patch = recipe.patch as FlockPatch
                val sample = File(data, pad.sampleFile)
                val info = WavInfo.read(sample)
                require(info.sampleRate == 44_100 && info.channels == 1 && info.bitsPerSample == 24 && info.frameCount > 0)
                val duration = info.frameCount.toDouble() / info.sampleRate
                require(duration in 2.0..6.0001) { "pad ${pad.slot} is not a finite 2–6 second performance" }
                require(sha256(sample) == sha256(File(work, pad.sampleFile))) { "the export changed pad ${pad.slot}'s rendered audio" }
                val recipeFile = File(destination, "recipes/A%02d.json".format(Locale.ROOT, pad.slot))
                recipeFile.parentFile.mkdirs()
                recipeFile.writeText(recipe.toJsonText() + "\n")
                require(PadRecipe.fromJsonText(recipeFile.readText()) == recipe)
                linkedMapOf<String, Any?>(
                    "slot" to pad.slot, "pad" to "A%02d".format(Locale.ROOT, pad.slot),
                    "name" to pad.displayName, "voice" to patch.voice.name,
                    "class" to "TONAL", "playback" to "ONE_SHOT", "oneShot" to true, "loop" to false,
                    "muteGroup" to pad.muteGroup, "effects" to false,
                    "midi" to patch.midi, "note" to Scales.nameOf(patch.midi), "velocity" to patch.velocity,
                    "model" to patch.model, "seed" to patch.seed.toString(), "macros" to patch.macros,
                    "durationSeconds" to duration, "frames" to info.frameCount,
                    "sample" to sample.relativeTo(destination).invariantSeparatorsPath,
                    "sampleSha256" to sha256(sample),
                    "recipe" to recipeFile.relativeTo(destination).invariantSeparatorsPath,
                    "recipeSha256" to sha256(recipeFile), "savedRecipe" to recipe.toJsonValue(),
                )
            }
            val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
                .firstOrNull { File(it, ".git").exists() } ?: error("run within the SnipSnap repository")
            val manifest = linkedMapOf<String, Any?>(
                "schemaVersion" to 1, "engine" to "FLOCK", "name" to NAME, "padCount" to 16,
                "model" to 2, "playback" to "ONE_SHOT", "class" to "TONAL", "effects" to false,
                "scope" to "Finite 2–6 second notes. HOLD sets duration; no sustained instrument or seamless loop is exported.",
                "layout" to "A01–A08: GATHER, C minor pentatonic C4–F5. A09–A16: eight saved GATHER, RIBBON and SPLIT contrasts.",
                "audio" to linkedMapOf("sampleRate" to 44_100, "channels" to 1, "format" to "PCM_24"),
                "source" to linkedMapOf("revision" to git(repo, "rev-parse", "HEAD"),
                    "dirty" to git(repo, "status", "--porcelain").isNotEmpty()),
                "programs" to linkedMapOf(
                    "mpc3" to result.program.relativeTo(destination).invariantSeparatorsPath,
                    "mpc3Sha256" to sha256(result.program),
                    "mpc2" to mpc2.relativeTo(destination).invariantSeparatorsPath,
                    "mpc2Sha256" to sha256(mpc2),
                    "kit" to File(data, KitStore.FILE_NAME).relativeTo(destination).invariantSeparatorsPath,
                    "kitSha256" to sha256(File(data, KitStore.FILE_NAME)),
                ),
                "preflight" to linkedMapOf("stagingPassed" to true, "exportPassed" to true,
                    "findings" to exportedFindings.map { linkedMapOf("severity" to it.severity.name, "message" to it.message, "slot" to it.slot) }),
                "pads" to pads,
            )
            File(destination, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
            File(destination, "README.txt").writeText("""
                SNIPSNAP FLOCK KIT — sixteen dry finite pads

                MPC 3: load SnipSnap Flock Kit.xtd. Keep its _[TrackData] folder beside it.
                MPC 2: browse into SnipSnap Flock Kit_[TrackData] and load SnipSnap Flock Kit.xpm.
                The same sixteen 44.1 kHz mono PCM24 WAVs serve both programs.
                kit.json in that folder makes this an ordinary editable SnipSnap kit.
                recipes/ holds each complete pad recipe separately; manifest.json lists notes,
                seeds, velocities, model versions and SHA256 hashes for the exported files.

                A01–A08: GATHER in C minor pentatonic: C4, Eb4, F4, G4, Bb4, C5, Eb5, F5.
                A09–A16: Glass Returning, Close Feathers, Traveling Turn, Wide Current,
                Silver Thread, Three Flights, Open Harmonics and Soft Formation.

                Every pad is TONAL and ONE_SHOT, without choking or effects. HOLD selects a
                finite duration of 2–6 seconds. These drum programs play each note once;
                no held loop, note-off release or keygroup sustain behavior is claimed.

                Acceptance: staging and exported-kit preflight passed; all sixteen recipes
                survived save/load; exported samples retain the rendered WAV bytes exactly.
                On hardware, check the eight rising notes, voice contrasts and clean endings.
                Rebuild with ./gradlew :synth:generateFlockKit.
            """.trimIndent() + "\n")
            val archiveFile = File(root, "SnipSnap-Flock-Kit.zip")
            archive(destination, archiveFile)
            File(root, "${archiveFile.name}.sha256").writeText("${sha256(archiveFile)}  ${archiveFile.name}\n")
            println("Wrote ${result.program.absolutePath}, sixteen samples, MPC 2 twin, complete recipes and ${archiveFile.absolutePath}")
        } finally {
            work.deleteRecursively()
        }
    }

    private fun git(repo: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0) { "git failed: $output" }
        return output
    }

    private fun archive(directory: File, archive: File) {
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (file in directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }) {
                val path = directory.name + "/" + file.relativeTo(directory).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(path).also { it.time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
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
