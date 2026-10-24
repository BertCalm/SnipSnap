package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Production recipes for the six candidates selected in the owner's dated listening export. */
object MurmurApprovedAuditionGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 2) { "Supply an output directory and optional listening-export path" }
        val output = File(args.getOrNull(0) ?: "../testkit/murmur-approved-audition")
        val export = File(args.getOrNull(1) ?: "../docs/murmur-luthier/listening-2026-10-24.json")
        val evidence = Json.parse(export.readText()).obj()
        require(evidence.getValue("engine").str() == MurmurPatch.ENGINE)
        val original = evidence.getValue("manifest").obj()
        val notes = evidence.getValue("notes").obj()
        val selected = setOf("key_motion_000", "key_motion_050", "key_motion_075", "key_motion_100",
            "choir_hold_c2", "choir_hold_c3")
        require(notes.keys == selected && notes.values.all { it.obj().getValue("verdict").str() == "candidate" }) {
            "This promotion requires the six recorded candidate preferences" }
        val candidates = original.getValue("clips").arr().map { it.obj() }
            .filter { it.getValue("variant").str() == "candidate" }
        require(candidates.size == selected.size && candidates.map { it.getValue("comparisonId").str() }.toSet() == selected)
        output.mkdirs()
        val rows = candidates.map { heard ->
            val id = heard.getValue("id").str()
            val comparisonId = heard.getValue("comparisonId").str()
            require(id == "${comparisonId}_candidate")
            val voice = MurmurVoice.valueOf(heard.getValue("voice").str())
            val macros = heard.getValue("macros").obj().mapValues { it.value.num().toFloat() }
            val midi = heard.getValue("midi").int()
            val name = if (voice == MurmurVoice.KEY) "KEY MOTION ${macros.getValue("MOTION")}" else "CHOIR C${midi / 12 - 1} HOLD"
            val patch = MurmurPatch(name, voice, macros,
                midi, heard.getValue("velocity").num().toFloat(),
                Murmur.configurationFor(voice, model = 3))
            val patchText = patch.toJsonText()
            check(MurmurPatch.fromJsonText(patchText) == patch) { "$id lost its saved patch identity" }
            val padText = PadRecipe(patch).toJsonText()
            val replay = PadRecipe.fromJsonText(padText).patch as MurmurPatch
            check(replay == patch) { "$id lost its pad recipe identity" }
            val start = System.nanoTime()
            val probe = Murmur.probe(replay.voice, replay.macros, replay.midi, replay.velocity, replay.config)
            val renderMs = (System.nanoTime() - start) / 1e6
            val raw = Snip(probe.samples, channels = 1, sampleRate = Dsp.RATE)
            check(raw.samples.isNotEmpty() && raw.samples.all { it.isFinite() } && raw.peak() <= 1f)
            val matched = AuditionLevel.level(raw)
            val rawPath = "raw/$id.wav"
            val matchedPath = "matched/$id.wav"
            val recipePath = "recipes/$id.json"
            val padRecipePath = "pads/$id.json"
            WavWriter.write(File(output, rawPath), raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(output, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            val rawHash = sha256(File(output, rawPath))
            val matchedHash = sha256(File(output, matchedPath))
            check(rawHash == heard.getValue("rawSha256").str()) { "$id changed the selected raw audio" }
            check(matchedHash == heard.getValue("matchedSha256").str()) { "$id changed the selected matched audio" }
            File(output, recipePath).apply { parentFile.mkdirs(); writeText(patchText + "\n") }
            File(output, padRecipePath).apply { parentFile.mkdirs(); writeText(padText + "\n") }
            println("MURMUR approved: $id reproduces selected raw and matched WAVs")
            linkedMapOf<String, Any?>(
                "id" to id, "comparisonId" to comparisonId, "voice" to voice.name,
                "midi" to patch.midi, "velocity" to patch.velocity, "model" to patch.config.model,
                "macros" to macros, "loop" to Murmur.isLoop(macros.getValue("HOLD")),
                "frames" to raw.frameCount, "durationSeconds" to raw.durationSeconds,
                "rawPath" to rawPath, "matchedPath" to matchedPath, "recipePath" to recipePath,
                "padRecipePath" to padRecipePath, "rawSha256" to rawHash, "matchedSha256" to matchedHash,
                "renderedRootHz" to probe.rootHz, "seamError" to probe.seamError,
                "renderMilliseconds" to renderMs, "matchesOwnerSelectedAudio" to true,
                "productionRecipe" to true, "patchRoundTrip" to true, "padRecipeRoundTrip" to true,
            )
        }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to MurmurPatch.ENGINE, "title" to "Murmur owner-selected recipes",
            "sourceRevision" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to (git("status", "--porcelain")?.isNotBlank()),
            "sourceSha256" to sha256(File("src/main/kotlin/com/snipsnap/synth/Murmur.kt")),
            "generatorSha256" to sha256(File("src/test/kotlin/com/snipsnap/synth/MurmurApprovedAuditionGenerator.kt")),
            "listeningExportSha256" to sha256(export), "listeningExportedAt" to evidence.getValue("exportedAt").str(),
            "originalAuditionSourceRevision" to original.getValue("sourceRevision").str(),
            "ownerPreferences" to evidence.getValue("notes"), "clipCount" to rows.size, "wavCount" to rows.size * 2,
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 24, "listeningTarget" to .03f,
            "acceptance" to "Owner preferred all six candidates in the 2026-10-24 export; these production recipes reproduce their exact published WAVs.",
            "scope" to "KEY settings heard at C4 and CHOIR HOLD at C2/C3. Higher-register changes and the expanded factory bank have no new listening approval.",
            "requiresUpdatedRenderer" to true, "clips" to rows,
        )
        File(output, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(output, "README.md").writeText("""
            # Murmur owner-selected recipes

            These six saved production patches reproduce the raw and matched WAVs selected
            in the owner's 2026-10-24 listening export. They require the updated renderer;
            this archive does not update the previously published phone APK.

            `recipes/` contains ordinary MurmurPatch JSON. `pads/` contains the corresponding
            ordinary PadRecipe JSON. Both preserve the explicit render version, complete
            configuration, root, velocity and macros. CHOIR uses the production low-register
            loop policy; no experimental reflection or wrapper is needed to replay these recipes.

            Reproduce with `./gradlew --no-daemon :synth:generateMurmurApprovedAudition`.
            The optional second generator argument selects another listening-export file.
            Generation fails if any raw or matched WAV hash differs from the original selection.
            Raw files precede shared loudness targeting; matched files use the shared .03
            audition level. App patch rendering retains its own normal output level.

            Existing saved patches and the published factory bank retain their prior render
            definitions. The recorded preferences cover these six cases, not unheard higher
            registers, other macro settings or approval of the expanded factory bank.
        """.trimIndent() + "\n")
        val bundle = File(output, "murmur-approved-recipes.zip")
        ZipOutputStream(bundle.outputStream().buffered()).use { zip ->
            val paths = rows.flatMap { row -> listOf("rawPath", "matchedPath", "recipePath", "padRecipePath")
                .map { row.getValue(it) as String } } + listOf("manifest.json", "README.md")
            for (path in paths.sorted()) {
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                File(output, path).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).redirectError(File("/dev/null")).start()
        val text = process.inputStream.bufferedReader().use { it.readText().trim() }
        text.takeIf { process.waitFor() == 0 }
    }.getOrNull()
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported manifest type ${value::class}")
    }
}
