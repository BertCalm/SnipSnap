package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A finite production-instrument listening bench. Native WAVs are exactly the
 * CausticPatch renderer's output; only the separate matched copies use AuditionLevel.
 * This never regenerates, edits or levels any file in the earlier prototype study.
 */
object CausticInstrumentAuditionGenerator {
    private data class Case(
        val id: String, val title: String, val description: String, val section: String,
        val patch: CausticPatch, val comparison: String? = "default-c4",
        val presetNames: List<String> = emptyList(),
    )
    private data class Heard(val case: Case, val snip: Snip, val metrics: Map<String, Any?>)
    private val compactIds = setOf("default-c4", "body-0", "body-half", "source-0", "source-1", "focus-sparse", "length-0", "velocity-hard")

    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1) { "supply at most one native CAUSTIC audition output directory" }
        val repo = repositoryRoot()
        val output = File(args.firstOrNull() ?: File(repo, "testkit/caustic-instrument-audition").path).canonicalFile
        val oldStudy = File(repo, "testkit/caustic-audition").canonicalFile
        require(!repo.toPath().startsWith(output.toPath()) && !output.toPath().startsWith(oldStudy.toPath())) { "native audition must have its own generated directory" }
        val preserved = if (oldStudy.isDirectory) oldStudy.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(oldStudy).invariantSeparatorsPath to sha256(it)
        } else emptyMap()
        output.parentFile.mkdirs()
        val staging = Files.createTempDirectory(output.parentFile.toPath(), ".caustic-instrument-building-").toFile()
        try {
            generate(repo, staging, preserved.size)
            for ((path, hash) in preserved) check(sha256(File(oldStudy, path)) == hash) { "earlier CAUSTIC study changed: $path" }
            if (output.exists()) check(output.deleteRecursively()) { "cannot replace generated audition $output" }
            Files.move(staging.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            println("Native CAUSTIC: ${File(output, "manifest.json").absolutePath}; earlier ${preserved.size} files unchanged")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun generate(repo: File, root: File, oldFileCount: Int) {
        val provenance = provenance(repo)
        val cases = cases()
        check(cases.size in 18..24 && cases.map { it.id }.distinct().size == cases.size)
        check(cases.count { it.id in compactIds } == 8)
        val heard = cases.mapIndexed { index, case ->
            println("CAUSTIC instrument ${index + 1}/${cases.size}: ${case.id}")
            val started = System.nanoTime()
            val native = case.patch.render()
            val elapsed = (System.nanoTime() - started) / 1e6
            validate(case, native)
            val restored = CausticPatch.fromJsonText(case.patch.toJsonText())
            check(restored == case.patch && Patches.fromJsonText(case.patch.toJsonText()) == case.patch) { "${case.id}: native patch lost saved state" }
            if (case.id in setOf("default-c4", "body-0", "length-0"))
                check(native.samples.contentEquals(restored.render().samples)) { "${case.id}: native patch replay changed audio" }
            val measured = metrics(case.patch, native, elapsed)
            check((measured["pitchConfidence"] as? Float)?.let { it >= .3f } == true) {
                "${case.id}: full-render pitch confidence ${measured["pitchConfidence"]} is below .3"
            }
            check((measured["pitchCents"] as? Double)?.let { abs(it) <= 20.0 } == true) {
                "${case.id}: full-render pitch error ${measured["pitchCents"]} exceeds 20 cents"
            }
            check((measured["drumClass"] as String) !in setOf("KICK", "SNARE", "CLAP", "HAT_CLOSED", "HAT_OPEN", "TOM")) {
                "${case.id}: full-render classifier reads ${measured["drumClass"]}"
            }
            Heard(case, native, measured)
        }
        val records = heard.map { heardClip ->
            val case = heardClip.case
            // The native renderer already finishes the instrument. Never apply a second
            // house level or per-clip peak normalization to this export.
            val matched = AuditionLevel.level(heardClip.snip.copy(samples = heardClip.snip.samples.copyOf()))
            check(matched.samples.all { it.isFinite() && abs(it) < 1f }) { "${case.id}: matched PCM would clip" }
            val raw = "audio/${case.id}-native.wav"
            val matchedPath = "audio/${case.id}-matched.wav"
            val recipe = "patches/${case.id}.json"
            WavWriter.write(File(root, raw), heardClip.snip, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            File(root, recipe).apply { parentFile.mkdirs(); writeText(case.patch.toJsonText() + "\n") }
            linkedMapOf<String, Any?>(
                "id" to case.id, "title" to case.title, "description" to case.description,
                "section" to case.section, "group" to case.section, "comparison" to case.comparison,
                "midi" to case.patch.midi, "velocity" to case.patch.velocity, "voice" to case.patch.voice.name,
                "model" to case.patch.model, "macros" to case.patch.macros,
                "seconds" to heardClip.snip.durationSeconds,
                "presetNames" to case.presetNames,
                "heardTimbralAnchor" to case.presetNames.any { CausticPresets.isHeardAnchor(it) },
                "raw" to raw, "matched" to matchedPath, "recipe" to recipe,
                "rawSha256" to sha256(File(root, raw)), "matchedSha256" to sha256(File(root, matchedPath)),
                "recipeSha256" to sha256(File(root, recipe)), "metrics" to heardClip.metrics,
            )
        }
        val kitWork = Files.createTempDirectory(root.parentFile.toPath(), ".caustic-audition-kit-").toFile()
        try {
            CausticKitGenerator.generate(kitWork).copyTo(File(root, "caustic-kit.zip"), overwrite = true)
        } finally { kitWork.deleteRecursively() }
        val maximumPeak = heard.maxOf { peak(it.snip.samples) }
        val warnings = heard.flatMap { clip ->
            val messages = mutableListOf<String>()
            val cents = clip.metrics["pitchCents"] as? Double
            val confidence = clip.metrics["pitchConfidence"] as? Float
            if (cents == null || abs(cents) > 20) messages += "${clip.case.id}: broad pitch detector ${cents ?: "unavailable"} cents"
            if (confidence == null || confidence < .3f) messages += "${clip.case.id}: low broad pitch confidence"
            if ((clip.metrics["shortWindowDrumClass"] as? String) in setOf("KICK", "SNARE", "CLAP", "HAT_CLOSED", "HAT_OPEN", "TOM"))
                messages += "${clip.case.id}: early classifier reads ${clip.metrics["shortWindowDrumClass"]}"
            messages
        }
        val summary = linkedMapOf<String, Any?>(
            "clipCount" to records.size, "voiceCount" to 1, "presetCount" to CausticPresets.all().size,
            "sampleRate" to WavWriter.MPC_SAMPLE_RATE, "channels" to 1, "bitDepth" to 24,
            "maximumNativePeak" to maximumPeak, "worstSampleHeadroomDb" to -20 * log10(maximumPeak),
            "worstPitchCents" to heard.mapNotNull { it.metrics["pitchCents"] as? Double }.maxOfOrNull { abs(it) },
            "maximumRenderMillis" to heard.maxOf { it.metrics.getValue("renderMillis") as Double },
            "earlierStudyFilesUnchanged" to oldFileCount,
            "nativeExportGain" to 1.0,
            "leveling" to "Native is unmodified CausticPatch.render() output, including the native house finish. Matched copies use AuditionLevel exactly once, separately.",
            "patchReplay" to "Every native patch round-tripped; default, Preferred Alloy and shortest LENGTH replayed sample for sample.",
            "checks" to listOf("finite mono 44.1 kHz native output", "native and matched PCM do not clip", "full-render pitch confidence at least .3", "full-render broad pitch within 20 cents", "full-render classifier avoids drum classes", "native patch round trips and representative sample replay", "earlier listening artifacts unchanged"),
            "warnings" to warnings,
            "limits" to listOf("One ALLOY voice", "Fixed offline recordings, no realtime browser synthesis", "Finite notes only; HOLD is refused above zero", "BODY endpoints are heard timbral anchors; intermediate values and six other presets are new listening candidates", "Native finishing and LENGTH delivery are separate from preserved prototype WAVs"),
        )
        val manifest = linkedMapOf<String, Any?>(
            "title" to "CAUSTIC / ALLOY", "description" to "A finite native instrument. Move the source and pickup, then balance the bright strike against a fuller body.",
            "model" to 1, "generatedAt" to Instant.now().toString(), "sourceRevision" to provenance,
            "summary" to summary,
            "downloads" to linkedMapOf("comparison" to "caustic-instrument-comparison.zip", "kit" to "caustic-kit.zip"),
            "clips" to records,
        )
        writeManifest(root, manifest)
        File(root, "diagnostics.json").writeText(Json.write(json(linkedMapOf("summary" to summary, "clips" to records))) + "\n")
        val assets = File(repo, "synth/src/test/resources/caustic-instrument-audition")
        check(File(assets, "index.html").isFile) { "native CAUSTIC listening-page assets are missing" }
        assets.copyRecursively(root, overwrite = true)
        File(root, "README.txt").writeText("""
            CAUSTIC / ALLOY native instrument, model 1

            Open index.html. manifest.js keeps the standalone page usable without a server.
            Every patches/*.json is an ordinary native CausticPatch, with model, MIDI note,
            velocity and all five controls. Native WAVs are exact renderer output; matched
            WAVs apply AuditionLevel once separately. SHA-256 hashes are in manifest.json.

            Four musical controls are exposed: SOURCE, FOCUS, BODY and LENGTH.
            HOLD is reserved at zero. No endless sustain or seamless-loop claim is made.
            The preferred/full body endpoints have been heard in the earlier study. Native
            delivery, intermediate controls and other starter positions await listening.

            caustic-instrument-comparison.zip contains eight anchor/control recordings.
            caustic-kit.zip is a separate sixteen-pad MPC 3 kit with an MPC 2 twin and
            editable kit.json. Its files were read back by the ordinary export readers.
            Rebuild: ./gradlew :synth:generateCausticInstrumentAudition
        """.trimIndent() + "\n")
        val compact = Files.createTempDirectory(root.parentFile.toPath(), ".caustic-comparison-").toFile()
        try {
            val chosen = records.filter { (it.getValue("id") as String) in compactIds }
            val chosenHeard = heard.filter { it.case.id in compactIds }
            val chosenPeak = chosenHeard.maxOf { peak(it.snip.samples) }
            val compactSummary = LinkedHashMap(summary).also {
                it["clipCount"] = chosen.size
                it["presetCount"] = chosenHeard.flatMap { clip -> clip.case.presetNames }.distinct().size
                it["maximumNativePeak"] = chosenPeak
                it["worstSampleHeadroomDb"] = -20 * log10(chosenPeak)
                it["worstPitchCents"] = chosenHeard.maxOf { clip -> abs(clip.metrics.getValue("pitchCents") as Double) }
                it["maximumRenderMillis"] = chosenHeard.maxOf { clip -> clip.metrics.getValue("renderMillis") as Double }
                it["warnings"] = warnings.filter { warning -> chosenHeard.any { clip -> warning.startsWith(clip.case.id + ":") } }
                it["compactComparison"] = true
            }
            for (name in listOf("index.html", "app.js", "style.css", "README.txt")) File(root, name).copyTo(File(compact, name))
            for (record in chosen) for (field in listOf("raw", "matched", "recipe")) {
                val path = record.getValue(field) as String
                File(compact, path).parentFile.mkdirs()
                File(root, path).copyTo(File(compact, path))
            }
            writeManifest(compact, LinkedHashMap(manifest).also {
                it["clips"] = chosen
                it["downloads"] = emptyMap<String, String>()
                it["summary"] = compactSummary
                it["fullStudySummary"] = summary
            })
            zip(compact, File(root, "caustic-instrument-comparison.zip"))
        } finally { compact.deleteRecursively() }
        check(File(root, "caustic-instrument-comparison.zip").length() < 25L * 1024 * 1024) { "compact CAUSTIC comparison exceeds 25 MiB" }
    }

    private fun cases(): List<Case> {
        val base = CausticPresets.all().first { it.name == "Fuller Body" }
        fun moved(name: String, vararg macros: Pair<String, Float>) = base.copy(name = name, macros = base.macros + macros.toMap())
        val cases = mutableListOf(
            Case("default-c4", "Fuller Body / C4", "The native default: SOURCE .50, FOCUS .19, BODY 1 and LENGTH 1.", "anchors", base, null),
            Case("default-c2", "Fuller Body / C2", "The same instrument rebuilt at C2, with the requested root saved in the patch.", "anchors", base.copy(name = "Fuller Body C2", midi = 36)),
            Case("default-c6", "Fuller Body / C6", "The same instrument rebuilt at C6.", "anchors", base.copy(name = "Fuller Body C6", midi = 84)),
            Case("body-0", "BODY 0 / Preferred Alloy", "The heard bright Alloy endpoint, through the native finite-note finish.", "body", moved("Preferred Alloy", "BODY" to 0f)),
            Case("body-half", "BODY .50 / Between the anchors", "A new midpoint between the preferred and fuller excitation mechanisms.", "body", moved("Half Body", "BODY" to .5f)),
            Case("source-0", "SOURCE 0 / Parallel", "Hold the pickup fixed while moving to parallel illumination.", "geometry", moved("Parallel Source", "SOURCE" to 0f)),
            Case("source-1", "SOURCE 1 / Rim", "Hold the pickup fixed while moving to rim-source illumination.", "geometry", moved("Rim Source", "SOURCE" to 1f)),
            Case("focus-shoulder", "FOCUS .38 / Shoulder", "Move the finite pickup away from the default concentration.", "geometry", moved("Shoulder", "FOCUS" to .38f)),
            Case("focus-sparse", "FOCUS .85 / Sparse Alloy", "A sparse position with the same saved note, velocity and body.", "geometry", moved("Sparse Alloy", "FOCUS" to .85f)),
            Case("length-0", "LENGTH 0 / Short", "The shortest finite body and release. This changes decay, not just the file endpoint.", "length", moved("Short Length", "LENGTH" to 0f)),
            Case("length-half", "LENGTH .50 / Medium", "A medium finite body and release.", "length", moved("Medium Length", "LENGTH" to .5f)),
            Case("velocity-soft", "Soft effort / .25", "A separately rendered soft excitation, with the saved strike effort retained.", "touch", base.copy(name = "Soft Effort", velocity = .25f)),
            Case("velocity-hard", "Hard effort / 1.00", "A separately rendered maximum-effort excitation.", "touch", base.copy(name = "Hard Effort", velocity = 1f)),
            Case("rim-focus", "SOURCE × FOCUS / Rim concentration", "Move SOURCE to 1 and FOCUS to .245 together. Compare with the fixed-pickup rim source.", "pairs", moved("Rim Concentration", "SOURCE" to 1f, "FOCUS" to .245f), "source-1"),
            Case("short-preferred", "BODY × LENGTH / Preferred, medium", "Use the preferred bright body with medium LENGTH. Compare with the fuller body at the same duration.", "pairs", moved("Medium Preferred", "BODY" to 0f, "LENGTH" to .5f), "length-half"),
        )
        fun sameSound(a: CausticPatch, b: CausticPatch) = a.voice == b.voice && a.macros == b.macros && a.midi == b.midi && a.velocity == b.velocity && a.model == b.model
        for (preset in CausticPresets.all()) {
            val index = cases.indexOfFirst { sameSound(it.patch, preset) }
            if (index >= 0) cases[index] = cases[index].copy(presetNames = cases[index].presetNames + preset.name)
            else cases += Case("preset-" + preset.name.lowercase().replace(' ', '-'), preset.name,
                CausticPresets.descriptionFor(preset.name) ?: "New starting position awaiting listening.", "presets", preset,
                presetNames = listOf(preset.name))
        }
        return cases
    }

    private fun validate(case: Case, snip: Snip) {
        check(snip.channels == 1 && snip.sampleRate == WavWriter.MPC_SAMPLE_RATE && snip.samples.isNotEmpty())
        check(snip.samples.all { it.isFinite() && abs(it) < 1f }) { "${case.id}: nonfinite or clipped native output" }
        check(peak(snip.samples) > 1e-8) { "${case.id}: silent native output" }
        check(case.patch.macros.getValue("HOLD") == 0f)
    }

    private fun metrics(patch: CausticPatch, snip: Snip, millis: Double): Map<String, Any?> {
        val duration = snip.samples.size.toDouble() / snip.sampleRate
        val hz = 440.0 * 2.0.pow((patch.midi - 69) / 12.0)
        val pitch = Pitch.detect(snip, fromSec = minOf(.5, duration * .15).toFloat(), windowSec = minOf(.35, duration * .6).toFloat())
        val early = snip.copy(samples = snip.samples.copyOf(minOf(snip.samples.size, (1.4 * snip.sampleRate).toInt())))
        return linkedMapOf(
            "rawPeak" to peak(snip.samples), "rawRms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
            "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size, "seconds" to duration,
            "requestedHz" to hz, "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence,
            "pitchCents" to pitch?.let { 1200 * ln(it.hz / hz) / ln(2.0) },
            "drumClass" to Classifier.classify(snip).drumClass.name,
            "shortWindowDrumClass" to Classifier.classify(early).drumClass.name,
            "renderMillis" to millis, "finite" to true, "clippedSamples" to 0,
        )
    }

    private fun writeManifest(root: File, manifest: Map<String, Any?>) {
        val text = Json.write(json(manifest))
        File(root, "manifest.json").writeText(text + "\n")
        File(root, "manifest.js").writeText("window.CAUSTIC_INSTRUMENT_MANIFEST = $text;\n")
    }
    private fun zip(root: File, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            for (file in root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }) {
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).also { it.time = 0L })
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
    private fun repositoryRoot(): File {
        var file: File? = File(System.getProperty("user.dir")).canonicalFile
        while (file != null) { if (File(file, ".git").exists()) return file; file = file.parentFile }
        error("run the native CAUSTIC generator inside the SnipSnap checkout")
    }
    private fun provenance(repo: File): Map<String, Any?> {
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", repo.path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            check(process.waitFor() == 0) { "cannot record CAUSTIC source provenance" }; return output
        }
        val roots = arrayOf("synth/src/main", "synth/src/test/kotlin/com/snipsnap/synth/CausticInstrumentAuditionGenerator.kt", "synth/src/test/kotlin/com/snipsnap/synth/CausticKitGenerator.kt", "synth/src/test/resources/caustic-instrument-audition", "audio/src/main", "kit/src/main", "mpc3/src/main", "xpm/src/main", "json/src/main", "synth/build.gradle.kts")
        val tracked = git("ls-files", "-z", "--", *roots).split('\u0000').filter { it.isNotBlank() }.toSet()
        val untracked = git("ls-files", "--others", "--exclude-standard", "-z", "--", *roots).split('\u0000').filter { it.isNotBlank() }.toSet()
        val files = (tracked + untracked).sorted().mapNotNull { path -> File(repo, path).takeIf { it.isFile }?.let {
            linkedMapOf("path" to path, "sha256" to sha256(it), "tracked" to (path in tracked))
        } }
        val combined = files.joinToString("\n") { "${it["path"]}\u0000${it["sha256"]}" }.toByteArray()
        return linkedMapOf("commit" to git("rev-parse", "HEAD").trim(), "branch" to git("rev-parse", "--abbrev-ref", "HEAD").trim(),
            "dirty" to git("status", "--porcelain", "--", *roots).isNotBlank(), "sourceFingerprintSha256" to sha256(combined), "files" to files,
            "runtime" to "${System.getProperty("java.runtime.version")} ${System.getProperty("os.arch")}")
    }
    private fun peak(samples: FloatArray) = samples.maxOf { abs(it.toDouble()) }
    private fun sha256(file: File) = sha256(file.readBytes())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { check(it.isFinite()) { "nonfinite CAUSTIC evidence" } })
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported native CAUSTIC evidence ${value::class.simpleName}")
    }
}
