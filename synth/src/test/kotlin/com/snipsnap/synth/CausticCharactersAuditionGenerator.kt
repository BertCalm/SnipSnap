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
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Four ordinary native voices, separately exported from every earlier listening study.
 * Native files are exactly CausticPatch.render(); matched files use AuditionLevel once.
 */
object CausticCharactersAuditionGenerator {
    private const val GLOBAL = "CAUSTIC_NATIVE_VOICES_MANIFEST"
    private const val MAX_ASSET_BYTES = 25L * 1024 * 1024
    private data class Family(val voice: String, val title: String, val description: String) {
        val slug get() = voice.lowercase().replace('_', '-')
    }
    private val families = listOf(
        Family("ALLOY", "Alloy", "A metallic strike, a fuller tonal body and a ringing release."),
        Family("HOLLOW_MALLET", "Hollow Mallet", "A softer contact feeds a short, hollow low-and-odd-mode body."),
        Family("RUBBED_RING", "Rubbed Ring", "Rough contact grows into a driven swell and finite release."),
        Family("TRAVELLING_CATCHES", "Travelling Catches", "Successive contacts move inside one shared ringing body."),
    )
    private data class Case(val id: String, val title: String, val description: String,
        val section: String, val patch: CausticPatch, val comparison: String?)
    private data class Rendered(val case: Case, val snip: Snip, val metrics: Map<String, Any?>)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1) { "supply at most one CAUSTIC native-voices output directory" }
        val repo = repositoryRoot()
        val output = File(args.firstOrNull() ?: File(repo, "testkit/caustic-native-voices-audition").path).canonicalFile
        val protected = listOf("caustic-audition", "caustic-instrument-audition", "caustic-character-study")
            .map { File(repo, "testkit/$it").canonicalFile }
        require(!repo.toPath().startsWith(output.toPath())) { "output may not contain the repository" }
        require(protected.none { output.toPath().startsWith(it.toPath()) || it.toPath().startsWith(output.toPath()) }) {
            "native voices need an output directory separate from every earlier CAUSTIC study"
        }
        require(!output.toPath().startsWith(File(repo, "synth").toPath())) { "generated audio does not belong in source" }
        if (output.exists()) require(File(output, "manifest.js").takeIf { it.isFile }?.readText()?.contains(GLOBAL) == true) {
            "refusing to replace a directory that is not a generated CAUSTIC native-voices pack"
        }
        val preserved = protected.associateWith(::snapshot)
        output.parentFile.mkdirs()
        val staging = Files.createTempDirectory(output.parentFile.toPath(), ".caustic-native-voices-").toFile()
        try {
            generate(repo, staging, preserved.mapKeys { it.key.name }.mapValues { it.value.size })
            for ((root, before) in preserved) check(snapshot(root) == before) { "earlier CAUSTIC files changed: $root" }
            // Keep the previous generated pack until its replacement is complete.
            val backup = if (output.exists()) Files.createTempDirectory(output.parentFile.toPath(), ".caustic-native-backup-").toFile().also {
                check(it.delete()); Files.move(output.toPath(), it.toPath())
            } else null
            try { Files.move(staging.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            catch (error: Exception) { if (backup != null) Files.move(backup.toPath(), output.toPath()); throw error }
            backup?.deleteRecursively()
            println("CAUSTIC native voices: ${File(output, "manifest.json").absolutePath}; earlier artifacts unchanged")
        } finally { if (staging.exists()) staging.deleteRecursively() }
    }

    private fun generate(repo: File, root: File, preserved: Map<String, Int>) {
        check(CausticVoice.entries.map { it.name }.toSet() == families.map { it.voice }.toSet()) { "expected the four native CAUSTIC voices" }
        val revision = provenance(repo)
        val cases = cases()
        check(cases.size == 42 && cases.map { it.id }.distinct().size == cases.size)
        check(cases.count { it.section == "voices" } == 12)
        val rendered = cases.mapIndexed { index, case ->
            println("CAUSTIC native voices ${index + 1}/${cases.size}: ${case.id}")
            val started = System.nanoTime()
            val native = case.patch.render()
            val elapsed = (System.nanoTime() - started) / 1e6
            validate(case, native)
            val restored = CausticPatch.fromJsonText(case.patch.toJsonText())
            check(restored == case.patch && Patches.fromJsonText(case.patch.toJsonText()) == case.patch) { "${case.id}: patch round trip lost native state" }
            if (case.id.endsWith("-c4") || case.id.endsWith("-short-body"))
                check(native.samples.contentEquals(restored.render().samples)) { "${case.id}: patch replay changed samples" }
            val measured = metrics(case.patch, native, elapsed)
            if (case.section == "voices") {
                check((measured["pitchConfidence"] as? Float)?.let { it >= .3f } == true) {
                    "${case.id}: default pitch confidence ${measured["pitchConfidence"]} is below .3"
                }
                check((measured["pitchCents"] as? Double)?.let { abs(it) <= 20.0 } == true) {
                    "${case.id}: default pitch error ${measured["pitchCents"]} exceeds 20 cents"
                }
            }
            Rendered(case, native, measured)
        }
        val byId = rendered.associateBy { it.case.id }
        val records = rendered.map { clip ->
            val case = clip.case
            val ref = case.comparison?.let { byId.getValue(it) }
            if (ref != null) check(!clip.snip.samples.contentEquals(ref.snip.samples)) { "${case.id}: comparison did not change the native audio" }
            val measured = LinkedHashMap(clip.metrics)
            if (ref != null) measured["comparison"] = comparison(clip.snip.samples, ref.snip.samples, clip.snip.sampleRate)
            val matched = AuditionLevel.level(clip.snip.copy(samples = clip.snip.samples.copyOf()))
            check(matched.samples.all { it.isFinite() && abs(it) < 1f }) { "${case.id}: matched samples would clip" }
            val rawPath = "audio/${case.id}-native.wav"
            val matchedPath = "audio/${case.id}-matched.wav"
            val recipePath = "patches/${case.id}.json"
            WavWriter.write(File(root, rawPath), clip.snip, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            File(root, recipePath).apply { parentFile.mkdirs(); writeText(case.patch.toJsonText() + "\n") }
            linkedMapOf<String, Any?>(
                "id" to case.id, "title" to case.title, "description" to case.description,
                "section" to case.section, "group" to case.patch.voice.name, "comparison" to case.comparison,
                "midi" to case.patch.midi, "velocity" to case.patch.velocity, "voice" to case.patch.voice.name,
                "model" to case.patch.model, "macros" to case.patch.macros, "seconds" to clip.snip.durationSeconds,
                "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
                "rawSha256" to sha256(File(root, rawPath)), "matchedSha256" to sha256(File(root, matchedPath)),
                "recipeSha256" to sha256(File(root, recipePath)), "metrics" to measured,
            )
        }
        val kitWork = Files.createTempDirectory(root.parentFile.toPath(), ".caustic-native-voices-kit-").toFile()
        try {
            // The kit generator verifies its ordinary MPC exports before returning.
            // Only its completed ZIP enters the audition; its working files stay separate.
            CausticKitGenerator.generate(kitWork).copyTo(File(root, "caustic-native-voices-kit.zip"), overwrite = true)
        } finally { kitWork.deleteRecursively() }
        val downloads = linkedMapOf<String, Any?>(
            "comparison" to "caustic-native-voices-comparison.zip",
            "kit" to "caustic-native-voices-kit.zip",
            "voices" to families.map { linkedMapOf("voice" to it.voice, "title" to it.title, "path" to "caustic-${it.slug}.zip") },
        )
        val manifest = linkedMapOf<String, Any?>(
            "schema" to 1, "title" to "CAUSTIC / Native voices", "model" to CausticInstrument.MODEL_VERSION,
            "description" to "Four permanent native voices. Compare their playing gestures, then move SOURCE, FOCUS, BODY and LENGTH.",
            "generatedAt" to Instant.now().toString(), "sourceRevision" to revision,
            "voices" to families.map { linkedMapOf("id" to it.voice, "slug" to it.slug, "title" to it.title,
                "description" to it.description, "defaultClip" to "${it.slug}-c4") },
            "history" to linkedMapOf("prototype" to "../contrast/", "alloy" to "../instrument/"),
            "summary" to summary(rendered, preserved), "downloads" to downloads, "clips" to records,
        )
        val assets = File(repo, "synth/src/test/resources/caustic-native-voices-audition")
        for (name in listOf("index.html", "app.js", "style.css")) {
            check(File(assets, name).isFile) { "native-voices page is missing $name" }
            File(assets, name).copyTo(File(root, name))
        }
        writeManifest(root, manifest)
        diagnostics(root, manifest)
        File(root, "README.txt").writeText("""
            CAUSTIC / Four native voices, model ${CausticInstrument.MODEL_VERSION}

            These recordings come from ordinary Kotlin CausticPatch.render(), including
            its native house finish. Renderer-level WAVs receive no extra leveling.
            Matched WAVs apply AuditionLevel exactly once to a separate copy.
            Every patches/*.json is a replayable native Patch with voice, model,
            MIDI, velocity, SOURCE, FOCUS, BODY, LENGTH and reserved HOLD at zero.

            Open index.html. manifest.js supplies metadata without a JSON fetch.
            The comparison ZIP contains the twelve defaults, four voices at C2/C4/C6.
            Each family ZIP contains its default notes and focused control/pair checks.
            The hosted pack also includes caustic-native-voices-kit.zip: sixteen native
            pads, editable recipes and MPC 3/MPC 2 exports verified by their readers.
            No earlier Python character-study or Alloy-native WAV was overwritten.
            Earlier studies remain independent historical comparisons, not replay
            recipes for these Kotlin ports. Finite notes end; HOLD has no held loop.

            Rebuild from the recorded source revision:
            ./gradlew :synth:generateCausticCharactersAudition
            Optional: -PcausticCharactersOutput=/path/to/new/generated-directory

            Why it is built this way
            1. Four C4 defaults compare the accepted playing gestures at one pitch.
            2. C2/C6 show how the same native voices follow their saved note.
            3. One-control cases and two named pairs expose the actual native macros.
            4. Exact renderer files and separately matched copies serve different
               listening questions. Measurements do not establish audible contrast.
        """.trimIndent() + "\n")
        archive(root, File(root, "caustic-native-voices-comparison.zip"), manifest,
            records.filter { it["section"] == "voices" }, rendered.filter { it.case.section == "voices" }, preserved)
        for (family in families) archive(root, File(root, "caustic-${family.slug}.zip"), manifest,
            records.filter { it["voice"] == family.voice }, rendered.filter { it.case.patch.voice.name == family.voice }, preserved)
        for (file in root.walkTopDown().filter { it.isFile }) check(file.length() < MAX_ASSET_BYTES) { "asset exceeds 25 MiB: ${file.name}" }
        check(provenance(repo)["sourceFingerprintSha256"] == revision["sourceFingerprintSha256"]) { "CAUSTIC source changed during generation" }
    }

    private fun cases(): List<Case> = families.flatMap { family ->
        val voice = CausticVoice.valueOf(family.voice)
        val defaults = CausticInstrument.defaults(voice)
        val base = CausticPatch(family.title, voice, defaults, midi = 60, velocity = .75f, model = CausticInstrument.MODEL_VERSION)
        val id = family.slug
        val rootId = "$id-c4"
        val output = mutableListOf<Case>()
        for ((note, midi) in listOf("c4" to 60, "c2" to 36, "c6" to 84)) output += Case("$id-$note", "${family.title} / ${note.uppercase()}",
            "Native default, with its voice, requested note and effort saved in the patch.", "voices",
            base.copy(name = "${family.title} ${note.uppercase()}", midi = midi), if (midi == 60) null else rootId)
        fun moved(suffix: String, title: String, description: String, section: String = "controls",
            reference: String = rootId, vararg values: Pair<String, Float>) {
            val macros = defaults + values.toMap()
            if (macros == defaults) return
            output += Case("$id-$suffix", "${family.title} / $title", description, section,
                base.copy(name = "${family.title} $title", macros = macros), reference)
        }
        val travelling = family.voice == "TRAVELLING_CATCHES"
        moved("source-0", "SOURCE 0", if (travelling) "Shift the travelling source schedule toward its low end; other controls stay at their defaults."
            else "Parallel illumination; the remaining controls stay at this voice's defaults.", values = arrayOf("SOURCE" to 0f))
        moved("source-1", "SOURCE 1", if (travelling) "Shift the travelling source schedule toward its high end; other controls stay at their defaults."
            else "Rim illumination; the remaining controls stay at this voice's defaults.", values = arrayOf("SOURCE" to 1f))
        moved("focus-sparse", "FOCUS .85", if (travelling) "Shift the pickup schedule toward FOCUS .85, keeping SOURCE fixed."
            else "Move the finite pickup to a sparse position, keeping the source fixed.", values = arrayOf("FOCUS" to .85f))
        moved("body-0", "BODY 0", "Low BODY endpoint at the same note and default geometry.", values = arrayOf("BODY" to 0f))
        moved("body-1", "BODY 1", "High BODY endpoint at the same note and default geometry.", values = arrayOf("BODY" to 1f))
        moved("length-short", "LENGTH .25", "Shorten the finite gesture and its release with the native LENGTH control.", values = arrayOf("LENGTH" to .25f))
        moved("rim-focus", "SOURCE × FOCUS", "Move FOCUS to .245 with SOURCE at 1. Compare with the same source setting and the default pickup.",
            "pairs", "$id-source-1", "SOURCE" to 1f, "FOCUS" to .245f)
        val oppositeBody = if (defaults.getValue("BODY") < .5f) 1f else 0f
        moved("short-body", "BODY × LENGTH", "Change BODY under the same short LENGTH. Compare with LENGTH .25 at the default body.",
            "pairs", "$id-length-short", "BODY" to oppositeBody, "LENGTH" to .25f)
        output
    }

    private fun validate(case: Case, snip: Snip) {
        check(snip.channels == 1 && snip.sampleRate == WavWriter.MPC_SAMPLE_RATE && snip.samples.isNotEmpty())
        check(snip.samples.all { it.isFinite() && abs(it) < 1f }) { "${case.id}: nonfinite or clipped native output" }
        check(peak(snip.samples) > 1e-8) { "${case.id}: silent native output" }
        check(abs(snip.samples.sumOf { it.toDouble() } / snip.samples.size) < .01) { "${case.id}: excessive native DC" }
        check(case.patch.macros.getValue("HOLD") == 0f)
        val expected = (CausticInstrument.durationSeconds(case.patch.voice, case.patch.macros, case.patch.model) * snip.sampleRate).roundToInt()
        check(snip.samples.size == expected) { "${case.id}: native LENGTH disagrees with its file duration" }
    }

    private fun metrics(patch: CausticPatch, snip: Snip, millis: Double): Map<String, Any?> {
        val duration = snip.samples.size.toDouble() / snip.sampleRate
        val hz = 440.0 * 2.0.pow((patch.midi - 69) / 12.0)
        val lengthScale = duration / 8.0
        // A mallet's voiced attack and a rubbed pressure swell occupy different windows.
        // Measure each where it is driven, rather than scoring a quiet mallet tail.
        val (from, window) = when (patch.voice.name) {
            "HOLLOW_MALLET" -> .025 * lengthScale to .175 * lengthScale
            "RUBBED_RING" -> .5 * lengthScale to .5 * lengthScale
            else -> minOf(.5, duration * .15) to minOf(.35, duration * .6)
        }
        val pitch = Pitch.detect(snip, fromSec = from.toFloat(), windowSec = window.toFloat())
        val early = snip.copy(samples = snip.samples.copyOf(minOf(snip.samples.size, (1.4 * snip.sampleRate).toInt())))
        return linkedMapOf("rawPeak" to peak(snip.samples), "rawRms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
            "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size, "seconds" to duration,
            "requestedHz" to hz, "pitchHz" to pitch?.hz, "pitchConfidence" to pitch?.confidence,
            "pitchCents" to pitch?.let { 1200 * ln(it.hz / hz) / ln(2.0) },
            "pitchFromSeconds" to from, "pitchWindowSeconds" to window,
            "drumClass" to Classifier.classify(snip).drumClass.name,
            "shortWindowDrumClass" to Classifier.classify(early).drumClass.name,
            "renderMillis" to millis, "finite" to true, "clippedSamples" to 0)
    }

    private fun comparison(a: FloatArray, b: FloatArray, rate: Int): Map<String, Any?> {
        val start = (.15 * rate).toInt()
        val end = minOf(a.size, b.size, (1.5 * rate).toInt())
        var aa = 0.0; var bb = 0.0; var ab = 0.0
        for (i in start until end) { val x = a[i].toDouble(); val y = b[i].toDouble(); aa += x*x; bb += y*y; ab += x*y }
        val gain = if (bb > 1e-20) ab / bb else 0.0
        var residual = 0.0
        for (i in start until end) { val d = a[i] - gain*b[i]; residual += d*d }
        return linkedMapOf("fromSeconds" to .15, "toSeconds" to end.toDouble()/rate,
            "gainFitResidual" to sqrt(residual/aa.coerceAtLeast(1e-20)), "cosineSimilarity" to ab/sqrt((aa*bb).coerceAtLeast(1e-20)),
            "note" to "Native PCM shape comparison after a fitted scalar gain; not an audibility verdict")
    }

    private fun summary(rendered: List<Rendered>, preserved: Map<String, Int>): Map<String, Any?> {
        val peak = rendered.maxOf { peak(it.snip.samples) }
        val warnings = rendered.flatMap { clip -> buildList {
            val cents = clip.metrics["pitchCents"] as? Double
            val confidence = clip.metrics["pitchConfidence"] as? Float
            if (cents == null || abs(cents) > 20) add("${clip.case.id}: broad pitch estimate ${cents ?: "unavailable"} cents")
            if (confidence == null || confidence < .3f) add("${clip.case.id}: broad pitch confidence below .3")
            if (clip.metrics["drumClass"] in setOf("KICK", "SNARE", "CLAP", "HAT_CLOSED", "HAT_OPEN", "TOM")) add("${clip.case.id}: classifier reads ${clip.metrics["drumClass"]}")
            if (clip.metrics["shortWindowDrumClass"] in setOf("KICK", "SNARE", "CLAP", "HAT_CLOSED", "HAT_OPEN", "TOM")) add("${clip.case.id}: early classifier reads ${clip.metrics["shortWindowDrumClass"]}")
        } }
        return linkedMapOf("clipCount" to rendered.size, "voiceCount" to rendered.map { it.case.patch.voice }.distinct().size,
            "defaultCount" to rendered.count { it.case.section == "voices" }, "sampleRate" to WavWriter.MPC_SAMPLE_RATE,
            "channels" to 1, "bitDepth" to 24, "oversampling" to 4, "nativeExportGain" to 1.0,
            "maximumNativePeak" to peak, "worstSampleHeadroomDb" to -20*log10(peak),
            "worstPitchCents" to rendered.mapNotNull { it.metrics["pitchCents"] as? Double }.maxOfOrNull { abs(it) },
            "maximumRenderMillis" to rendered.maxOf { it.metrics.getValue("renderMillis") as Double },
            "preservedEarlierFiles" to preserved,
            "leveling" to "Native is exact CausticPatch.render() output with its house finish. Matched copies use AuditionLevel once.",
            "checks" to listOf("finite mono 44.1 kHz PCM, no clipping or silence", "native LENGTH equals the saved patch duration", "all C2/C4/C6 defaults have voiced-window pitch confidence at least .3 and error within 20 cents", "native patch JSON round trips for every clip", "each C4 default and BODY×LENGTH pair replays sample for sample", "every named comparison changes native samples", "four-voice kit passes its MPC export readers", "source fingerprint stable during generation", "all earlier audition files unchanged"),
            "warnings" to warnings,
            "limits" to listOf("Fixed Kotlin recordings; the browser does not synthesize", "Finite notes only; HOLD is reserved at zero", "Broad pitch/classifier measurements are reported independently of listening approval", "The accepted prototype directions are now native voices; new macro positions remain listening comparisons"))
    }

    private fun archive(root: File, target: File, manifest: Map<String, Any?>, records: List<Map<String, Any?>>,
        rendered: List<Rendered>, preserved: Map<String, Int>) {
        val work = Files.createTempDirectory(root.parentFile.toPath(), ".caustic-native-portable-").toFile()
        try {
            for (name in listOf("index.html", "app.js", "style.css", "README.txt")) File(root, name).copyTo(File(work, name))
            for (record in records) for (field in listOf("raw", "matched", "recipe")) {
                val path = record.getValue(field) as String
                File(work, path).parentFile.mkdirs(); File(root, path).copyTo(File(work, path))
            }
            val voices = (manifest.getValue("voices") as List<*>).filter { (it as Map<*, *>)["id"] in records.map { record -> record["voice"] }.toSet() }
            val subset = LinkedHashMap(manifest).also {
                it["clips"] = records; it["voices"] = voices; it["downloads"] = emptyMap<String, String>()
                it["summary"] = summary(rendered, preserved) + ("portableSubset" to true)
            }
            writeManifest(work, subset); diagnostics(work, subset)
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                for (file in work.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(work).invariantSeparatorsPath }) {
                    zip.putNextEntry(ZipEntry(file.relativeTo(work).invariantSeparatorsPath).also { it.time = 0L })
                    file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        } finally { work.deleteRecursively() }
        check(target.length() < MAX_ASSET_BYTES) { "${target.name} exceeds 25 MiB" }
    }

    private fun writeManifest(root: File, manifest: Map<String, Any?>) {
        val text = Json.write(json(manifest)); File(root, "manifest.json").writeText(text + "\n")
        File(root, "manifest.js").writeText("window.$GLOBAL = $text;\n")
    }
    private fun diagnostics(root: File, manifest: Map<String, Any?>) = File(root, "diagnostics.json").writeText(
        Json.write(json(linkedMapOf("sourceRevision" to manifest["sourceRevision"], "summary" to manifest["summary"], "clips" to manifest["clips"]))) + "\n")
    private fun snapshot(root: File) = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.associate {
        it.relativeTo(root).invariantSeparatorsPath to sha256(it)
    } else emptyMap()
    private fun repositoryRoot(): File {
        var file: File? = File(System.getProperty("user.dir")).canonicalFile
        while (file != null) { if (File(file, ".git").exists()) return file; file = file.parentFile }
        error("run the native CAUSTIC voices generator in the SnipSnap checkout")
    }
    private fun provenance(repo: File): Map<String, Any?> {
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", repo.path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            check(process.waitFor() == 0) { "cannot record CAUSTIC native-voices source provenance" }; return output
        }
        val roots = arrayOf("synth/src/main", "synth/src/test/kotlin/com/snipsnap/synth/CausticCharactersAuditionGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/CausticKitGenerator.kt", "synth/src/test/resources/caustic-native-voices-audition",
            "audio/src/main", "kit/src/main", "mpc3/src/main", "xpm/src/main", "json/src/main", "synth/build.gradle.kts")
        val tracked = git("ls-files", "-z", "--", *roots).split('\u0000').filter { it.isNotBlank() }.toSet()
        val untracked = git("ls-files", "--others", "--exclude-standard", "-z", "--", *roots).split('\u0000').filter { it.isNotBlank() }.toSet()
        val files = (tracked + untracked).sorted().mapNotNull { path -> File(repo, path).takeIf { it.isFile }?.let {
            linkedMapOf("path" to path, "sha256" to sha256(it), "tracked" to (path in tracked))
        } }
        return linkedMapOf("commit" to git("rev-parse", "HEAD").trim(), "branch" to git("rev-parse", "--abbrev-ref", "HEAD").trim(),
            "dirty" to git("status", "--porcelain", "--", *roots).isNotBlank(),
            "sourceFingerprintSha256" to sha256(files.joinToString("\n") { "${it["path"]}\u0000${it["sha256"]}" }.toByteArray()),
            "files" to files, "runtime" to "${System.getProperty("java.runtime.version")} ${System.getProperty("os.arch")}")
    }
    private fun peak(samples: FloatArray) = samples.maxOf { abs(it.toDouble()) }
    private fun sha256(file: File) = sha256(file.readBytes())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { check(it.isFinite()) { "nonfinite CAUSTIC evidence" } })
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported CAUSTIC native-voices evidence ${value::class.simpleName}")
    }
}
