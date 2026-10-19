package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Fft
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reproducible first listen for the static circular SOURCE/FOCUS experiment.
 * No moving geometry, HOLD, external effects or factory integration is implied.
 */
object CausticAuditionGenerator {
    private const val RATE = 44_100
    private const val RAW_STATE_CEILING = 8.0
    /** A deliberately moderate first-listen gate, measured on the actual rendered root. */
    private const val ROOT_TOLERANCE_CENTS = 20.0
    private val forbiddenDrums = setOf("KICK", "SNARE", "CLAP", "HAT_CLOSED", "HAT_OPEN", "TOM")
    private data class Case(
        val id: String,
        val title: String,
        val description: String,
        val section: String,
        val group: String,
        val recipe: CausticRecipe,
        val comparison: String? = null,
        val recommendedOrder: Int? = null,
    )
    private data class Heard(val case: Case, val rendered: Caustic.Rendered, val metrics: Map<String, Any?>)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1) { "supply at most one CAUSTIC audition output directory" }
        val repo = repositoryRoot()
        val output = File(args.firstOrNull() ?: File(repo, "testkit/caustic-audition").path).canonicalFile
        require(output != repo && !File(output, ".git").exists()) { "audition output must be a generated-artifact directory" }
        output.parentFile.mkdirs()
        // A failed rerender must not leave a previous run's success manifest or pack.
        for (name in listOf("manifest.json", "manifest.js", "diagnostics.json", "audition.zip", "caustic-audition.zip")) {
            File(output, name).delete()
        }
        val staging = Files.createTempDirectory(output.parentFile.toPath(), ".caustic-audition-building-").toFile()
        try {
            generate(repo, staging)
            if (output.exists()) check(output.deleteRecursively()) { "could not replace generated audition directory $output" }
            Files.move(staging.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            println("CAUSTIC: 24 reproducible static clips and portable pack written to $output")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun generate(repo: File, root: File) {
        val sourceRevision = revision(repo)
        val cases = cases()
        check(cases.size == 24 && cases.map { it.id }.toSet().size == cases.size)
        val heard = cases.mapIndexed { index, case ->
            println("CAUSTIC ${index + 1}/${cases.size}: ${case.id}")
            val rendered = case.recipe.inspect()
            validate(case, rendered)
            // Saved files, not hidden generator options, are sufficient to replay a clip.
            val restored = CausticRecipe.fromJsonText(case.recipe.toJsonText())
            check(restored == case.recipe) { "${case.id}: recipe lost render inputs" }
            if (case.id == "mechanism-full") {
                val replay = restored.inspect()
                check(rendered.raw.contentEquals(replay.raw)) { "${case.id}: portable recipe replay changed raw samples" }
            }
            val measurements = metrics(case.recipe, rendered)
            check(abs((measurements.getValue("rootCents") as Number).toDouble()) < ROOT_TOLERANCE_CENTS) {
                "${case.id}: rendered fundamental misses requested note by ${measurements["rootCents"]} cents"
            }
            check((measurements["pitchConfidence"] as? Number)?.toDouble()?.let { it >= .3 } == true) {
                "${case.id}: rendered note lacks confident periodicity (${measurements["pitchConfidence"]})"
            }
            Heard(case, rendered, measurements)
        }
        // Every native clip shares this one gain, including the simple source.
        // Never peak-normalize individual positions: that would hide focus contrast.
        val maximumRawPeak = heard.maxOf { peak(it.rendered.raw) }
        val rawGain = minOf(1.0, .90 / maximumRawPeak)
        val records = heard.map { clip ->
            val case = clip.case
            val native = Snip(FloatArray(clip.rendered.raw.size) { (clip.rendered.raw[it] * rawGain).toFloat() }, 1, RATE)
            val matched = AuditionLevel.level(Snip(clip.rendered.raw.copyOf(), 1, RATE))
            check(native.samples.all { it.isFinite() && abs(it) < 1f }) { "${case.id}: native PCM would clip" }
            check(matched.samples.all { it.isFinite() && abs(it) < 1f }) { "${case.id}: matched PCM would clip" }
            val rawPath = "audio/${case.id}-raw.wav"
            val matchedPath = "audio/${case.id}-matched.wav"
            val recipePath = "recipes/${case.id}.json"
            WavWriter.write(File(root, rawPath), native, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            File(root, recipePath).apply { parentFile.mkdirs(); writeText(case.recipe.toJsonText() + "\n") }
            linkedMapOf<String, Any?>(
                "id" to case.id, "title" to case.title, "description" to case.description,
                "section" to case.section, "group" to case.group, "comparison" to case.comparison,
                "recommendedOrder" to case.recommendedOrder,
                "midi" to case.recipe.config.midi, "velocity" to case.recipe.config.velocity,
                "source" to case.recipe.config.source, "focus" to case.recipe.config.focus,
                "seconds" to case.recipe.config.seconds, "variant" to case.recipe.variant.name,
                "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
                "rawSha256" to sha256(File(root, rawPath).readBytes()),
                "matchedSha256" to sha256(File(root, matchedPath).readBytes()),
                "recipeSha256" to sha256(File(root, recipePath).readBytes()),
                "metrics" to clip.metrics,
            )
        }
        val summary = linkedMapOf<String, Any?>(
            "clipCount" to records.size,
            "sampleRate" to RATE, "channels" to 1, "bitDepth" to 24,
            "oversampling" to 4, "rayCount" to cases.first().recipe.config.rayCount,
            "pickupCount" to heard.first { it.case.recipe.variant == Caustic.Mode.FULL }.rendered.densities.size,
            "modeCount" to heard.first { it.case.recipe.variant == Caustic.Mode.FULL }.rendered.modalEnergy.size,
            "rawExportGain" to rawGain,
            "maximumRawPeak" to maximumRawPeak,
            "maximumModalPeak" to heard.maxOf { it.rendered.modalPeak },
            "maximumAbsoluteDc" to heard.maxOf { abs((it.metrics.getValue("dc") as Number).toDouble()) },
            "worstPitchCents" to heard.mapNotNull { (it.metrics["pitchCents"] as? Number)?.toDouble() }.maxOfOrNull { abs(it) },
            "worstRootCents" to heard.maxOf { abs((it.metrics.getValue("rootCents") as Number).toDouble()) },
            "rootToleranceCents" to ROOT_TOLERANCE_CENTS,
            "totalPreparationMillis" to heard.sumOf { it.rendered.prepareMillis },
            "totalRenderMillis" to heard.sumOf { it.rendered.renderMillis },
            "portableRecipeReplay" to "Full-control raw buffer replayed sample for sample; every recipe round-tripped exactly.",
            "leveling" to "Native: one common gain across every clip. Matched: existing AuditionLevel loudest-200-ms rule applied separately to each clip.",
            "checks" to listOf("finite raw/output/modal diagnostics", "raw and modal peaks below prototype ceiling 8", "no exported clipping", "rendered root within 20 cents with periodicity confidence at least .3", "raw full/short-window classifier avoids drum classes", "exact recipe round trips and representative sample replay"),
            "limits" to listOf("Static circular geometry only", "No ORBIT, deformation, SCATTER or HOLD", "Finite notes; no seamless-loop claim", "No phone picker or factory-preset integration", "Geometry density maxima do not guarantee phase-coherent audio maxima", "Pitch and spectra are measurements, not listening acceptance"),
        )
        val manifest = linkedMapOf<String, Any?>(
            "title" to "CAUSTIC · first static listen",
            "description" to "A simple finite harmonic tone, circular reflected paths and finite pickups. SOURCE changes illumination; FOCUS samples different positions. This first experiment tests whether the vessel gives the source a distinct singing body.",
            "model" to 1, "generatedAt" to Instant.now().toString(),
            "sourceRevision" to sourceRevision, "summary" to summary, "clips" to records,
        )
        val manifestText = Json.write(json(manifest))
        File(root, "manifest.json").writeText(manifestText + "\n")
        File(root, "manifest.js").writeText("window.CAUSTIC_MANIFEST = $manifestText;\n")
        val diagnostics = linkedMapOf<String, Any?>(
            "model" to 1, "sourceRevision" to sourceRevision, "summary" to summary,
            "clips" to heard.map { clip -> linkedMapOf(
                "id" to clip.case.id, "recipe" to clip.case.recipe.toJsonValue(), "metrics" to clip.metrics,
                "pickupDensities" to clip.rendered.densities,
                "modalEnergy" to clip.rendered.modalEnergy,
                "modeFrequencies" to clip.rendered.diagnostics.modeFrequencies,
                "excitationMethod" to clip.rendered.diagnostics.excitationMethod,
            ) },
        )
        File(root, "diagnostics.json").writeText(Json.write(json(diagnostics)) + "\n")
        val assets = File(repo, "synth/src/test/resources/caustic-audition")
        check(File(assets, "index.html").isFile) { "CAUSTIC listening page assets are missing: $assets" }
        assets.copyRecursively(root, overwrite = true)
        File(root, "README.txt").writeText(
            "CAUSTIC static prototype, model 1.\n\nOpen index.html locally; manifest.js makes the pack work without a server.\n" +
                "Each recipes/*.json is a complete CausticRecipe accepted by CausticRecipe.fromJsonText.\n" +
                "Rebuild this pack: ./gradlew :synth:generateCausticAudition\n" +
                "Native WAVs share one export gain; matched WAVs use the project's AuditionLevel rule.\n" +
                "SHA-256 hashes, source revision/fingerprints and measurements are in manifest.json and diagnostics.json.\n" +
                "This is static geometry only, with finite notes and no phone or HOLD implementation.\n",
        )
        check(revision(repo)["sourceFingerprintSha256"] == sourceRevision["sourceFingerprintSha256"]) {
            "CAUSTIC source inputs changed during generation; rerun after the source is stable"
        }
        writeZip(root)
    }

    private fun cases(): List<Case> {
        val base = Caustic.Config(source = .5, focus = .19, midi = 60, velocity = .75, seconds = 4.5)
        val selected = mapOf(
            "source-only" to 1,
            "source-050-focus-0190" to 2,
            "source-050-focus-0850" to 3,
            "source-050-focus-0380" to 4,
            "source-000-focus-0120" to 5,
            "source-100-focus-0245" to 6,
            "mechanism-phase-disabled" to 7,
            "mechanism-modes-bypassed" to 8,
            "mechanism-gain-only" to 9,
            "touch-soft" to 10,
        )
        val source = Case(
            "source-only", "The simple source", "The same finite source before reflections and resonances. Start here, then compare the full vessel at SOURCE .50 / FOCUS .190.",
            "first", "source-anchor", CausticRecipe(base, Caustic.Mode.SOURCE_ONLY), comparison = "mechanism-full",
        )
        val grid = listOf(0.0, .5, 1.0).flatMap { s ->
            listOf(.12, .19, .245, .38, .85).map { f ->
                val id = "source-${(s * 100).roundToInt().toString().padStart(3, '0')}-focus-${(f * 1000).roundToInt().toString().padStart(4, '0')}"
                val label = when (s) { 0.0 -> "Parallel illumination"; 1.0 -> "Rim-source illumination"; else -> "Intermediate illumination" }
                Case(id, "$label · FOCUS ${"%.3f".format(java.util.Locale.ROOT, f)}",
                    "SOURCE ${"%.2f".format(java.util.Locale.ROOT, s)}, FOCUS ${"%.3f".format(java.util.Locale.ROOT, f)}. The same focus positions are used at every source setting so a column isolates SOURCE. Native playback preserves position-dependent level; matched playback helps compare timbre.",
                    "positions", "source-${(s * 100).roundToInt()}", CausticRecipe(base.copy(source = s, focus = f)))
            }
        }
        val mechanisms = listOf(
            Triple(Caustic.Mode.FULL, "full", "Full reflected vessel"),
            Triple(Caustic.Mode.PHASE_DISABLED, "phase-disabled", "Path carrier phases aligned"),
            Triple(Caustic.Mode.MODES_BYPASSED, "modes-bypassed", "Resonator bank bypassed"),
            Triple(Caustic.Mode.GAIN_ONLY, "gain-only", "One gain for a fixed reference sound"),
        ).map { (mode, id, title) ->
            val description = when (mode) {
                Caustic.Mode.FULL -> "Reference for the following mechanism controls. All use the same note, effort, SOURCE .50 and FOCUS .190."
                Caustic.Mode.PHASE_DISABLED -> "Keep path arrivals and routing, but remove carrier phase differences. This checks whether coherent path phase affects the sound."
                Caustic.Mode.MODES_BYPASSED -> "Keep the same signed routing channels, fixed projection gains and quiet direct root, replacing resonant memory with a fixed 9× excitation gain. Compare after level matching to isolate the bank's contribution."
                Caustic.Mode.GAIN_ONLY -> "Use the fixed SOURCE .40 / FOCUS .20 reference spectrum and change only its overall gain by the collection ratio. Compare its spectrum with the full model after loudness matching."
                else -> error("unexpected mechanism mode")
            }
            Case("mechanism-$id", title, description, "mechanism", "mechanism", CausticRecipe(base, mode), comparison = "mechanism-full")
        }
        val touch = listOf(
            Case("register-low", "Low note · C2", "The full static vessel at MIDI 36; the source and modal relationships are rebuilt for the requested note.", "range", "register", CausticRecipe(base.copy(midi = 36)), comparison = "mechanism-full"),
            Case("register-high", "High note · C6", "The same static controls at MIDI 84. Listen for a clear root and unwanted sharpness.", "range", "register", CausticRecipe(base.copy(midi = 84)), comparison = "mechanism-full"),
            Case("touch-soft", "Soft effort · .25", "Velocity changes source effort and harmonic participation. Native mode also preserves its quieter level.", "range", "touch", CausticRecipe(base.copy(velocity = .25)), comparison = "touch-hard"),
            Case("touch-hard", "Hard effort · 1.00", "The same note and geometry at maximum velocity; compare its attack and upper harmonics with the soft effort.", "range", "touch", CausticRecipe(base.copy(velocity = 1.0)), comparison = "touch-soft"),
        )
        return (listOf(source) + grid + mechanisms + touch).map { it.copy(recommendedOrder = selected[it.id]) }
    }

    private fun validate(case: Case, r: Caustic.Rendered) {
        val expectedFrames = (case.recipe.config.seconds * RATE).roundToInt()
        check(r.raw.size == expectedFrames && r.samples.size == expectedFrames) { "${case.id}: unexpected output duration" }
        check(r.raw.all { it.isFinite() } && r.samples.all { it.isFinite() }) { "${case.id}: non-finite audio" }
        check(r.densities.all { it.isFinite() && it >= 0 } && r.modalEnergy.all { it.isFinite() && it >= 0 }) { "${case.id}: non-finite or negative diagnostic energy" }
        check(r.modalPeak.isFinite() && r.modalPeak < RAW_STATE_CEILING) { "${case.id}: modal-state peak ${r.modalPeak} exceeds prototype ceiling" }
        check(peak(r.raw) in 1e-10..<RAW_STATE_CEILING) { "${case.id}: silent or excessive raw state ${peak(r.raw)}" }
        check(r.prepareMillis.isFinite() && r.prepareMillis >= 0 && r.renderMillis.isFinite() && r.renderMillis >= 0)
        // The full-length classifier calls sustained notes LOOP by duration.
        // Its early 1.4-second window is checked separately for false drums.
        for ((name, audio) in listOf("full" to r.raw, "early" to r.raw.copyOf(minOf(r.raw.size, (1.4 * RATE).toInt())))) {
            val cls = Classifier.classify(Snip(audio, 1, RATE)).drumClass.name
            check(cls !in forbiddenDrums) { "${case.id}: $name raw classifier reads $cls" }
        }
    }

    private fun metrics(recipe: CausticRecipe, r: Caustic.Rendered): Map<String, Any?> {
        val raw = Snip(r.raw, 1, RATE)
        val estimate = Pitch.detect(raw, fromSec = .5f, windowSec = .35f)
        val hz = 440.0 * 2.0.pow((recipe.config.midi - 69) / 12.0)
        val measuredRoot = FineTuning.measuredHz(raw, hz.toFloat(), fromSec = .3f, bodySeconds = .75f)
        return linkedMapOf(
            "rawPeak" to peak(r.raw), "rawRms" to rms(r.raw), "dc" to r.raw.map { it.toDouble() }.average(),
            "pitchHz" to estimate?.hz, "pitchConfidence" to estimate?.confidence,
            "pitchCents" to estimate?.let { 1200 * ln(it.hz / hz) / ln(2.0) },
            "rootHz" to measuredRoot, "rootCents" to FineTuning.cents(measuredRoot, hz),
            "requestedHz" to hz,
            "drumClass" to Classifier.classify(raw).drumClass.name,
            "shortWindowDrumClass" to Classifier.classify(Snip(r.raw.copyOf(minOf(r.raw.size, (1.4 * RATE).toInt())), 1, RATE)).drumClass.name,
            "collection" to r.diagnostics.collection, "pathCount" to r.diagnostics.pathCount,
            "modalPeak" to r.modalPeak, "maxDelaySeconds" to r.diagnostics.maxDelaySeconds,
            "prepareMillis" to r.prepareMillis, "renderMillis" to r.renderMillis,
            "bandsDb" to bands(r.raw),
        )
    }

    /** Hann-windowed octave-band powers in the ringing body, before export gain. */
    private fun bands(samples: FloatArray): Map<String, Double> {
        val size = 65536
        val re = FloatArray(size)
        val im = FloatArray(size)
        val start = (RATE * .5).roundToInt()
        val count = minOf(size, samples.size - start)
        for (i in 0 until count) re[i] = samples[start + i] * (.5 - .5 * cos(2 * PI * i / (count - 1))).toFloat()
        Fft.forward(re, im)
        return listOf(63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000).associate { center ->
            val lower = center / sqrt(2.0)
            val upper = minOf(RATE / 2.0, center * sqrt(2.0))
            var sum = 0.0
            for (i in max(1, (lower * size / RATE).toInt())..minOf(size / 2 - 1, (upper * size / RATE).toInt())) {
                sum += re[i].toDouble() * re[i] + im[i].toDouble() * im[i]
            }
            "${center}Hz" to 10 * log10((sum / (size.toDouble() * size)).coerceAtLeast(1e-24))
        }
    }

    private fun revision(repo: File): Map<String, Any?> {
        val roots = listOf("synth/src", "audio/src/main", "json/src/main", "synth/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle/libs.versions.toml")
        val tracked = git(repo, listOf("ls-files", "-z", "--") + roots).split('\u0000').filter { it.isNotBlank() }.toSet()
        val untracked = git(repo, listOf("ls-files", "--others", "--exclude-standard", "-z", "--") + roots).split('\u0000').filter { it.isNotBlank() }.toSet()
        val files = (tracked + untracked).sorted().mapNotNull { path ->
            val file = File(repo, path)
            if (!file.isFile) null else linkedMapOf("path" to path, "sha256" to sha256(file.readBytes()), "tracked" to (path in tracked))
        }
        val combined = files.joinToString("\n") { "${it["path"]}\u0000${it["sha256"]}" }.toByteArray(Charsets.UTF_8)
        val dirty = git(repo, listOf("status", "--porcelain", "--untracked-files=normal", "--") + roots)
        return linkedMapOf(
            "commit" to git(repo, listOf("rev-parse", "HEAD")).trim(),
            "branch" to git(repo, listOf("rev-parse", "--abbrev-ref", "HEAD")).trim(),
            "dirty" to dirty.isNotBlank(), "changes" to dirty.lineSequence().filter { it.isNotBlank() }.toList(),
            "sourceFingerprintSha256" to sha256(combined), "files" to files,
            "runtime" to "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")} on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
        )
    }

    private fun git(repo: File, args: List<String>): String {
        val p = ProcessBuilder(listOf("git", "-C", repo.path) + args).redirectErrorStream(true).start()
        val result = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { "cannot record source revision: git ${args.first()} failed" }
        return result
    }

    private fun repositoryRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).canonicalFile
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        error("run the CAUSTIC generator from inside the SnipSnap checkout")
    }

    private fun writeZip(root: File) {
        val files = root.walkTopDown().filter { it.isFile }.toList().sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        ZipOutputStream(File(root, "audition.zip").outputStream().buffered()).use { zip ->
            for (file in files) {
                val entry = ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0L }
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun peak(a: FloatArray): Double = a.maxOf { abs(it.toDouble()) }
    private fun rms(a: FloatArray): Double = sqrt(a.sumOf { it.toDouble() * it } / a.size)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { check(it.isFinite()) { "non-finite diagnostic JSON" } })
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        is DoubleArray -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported diagnostic JSON ${value::class.simpleName}")
    }
}
