package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Actual keyboard attack-to-loop comparisons; the accepted R5 library remains intact. */
object RegistersTransistorSustainAuditionGenerator {
    private const val REVISION = "r6-transistor-sustain"
    private const val BASELINE_REVISION = "r5-transistor-depth"
    private const val BASELINE_AUDIO_HASH = "3613b9c7f7eabb5f838289f41d12ab174b9ddecad8d24c8022aebc55837f9b72"
    private const val BASELINE_MANIFEST_HASH = "7a63e734ffeb22299265b70baeb764b93e0286b13fc40cbd9fd7256515fd2fda"
    private const val MODEL = 6
    private const val REPEATS = 3
    private val voice = RegistersVoice.TRANSISTOR

    private data class Baseline(val root: File, val manifest: JsonValue,
        val clips: List<Map<String, Any?>>, val recipes: Map<String, String>,
        val sources: List<Map<String, String>>)
    private data class Case(val preset: String, val tag: String, val midi: Int, val control: Boolean = false)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 && args.count { it.startsWith("--baseline=") } <= 1 &&
            args.filter { it.startsWith("--") }.all { it.startsWith("--baseline=") }) {
            "Supply one output directory and optional --baseline=<published R5 archive>."
        }
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/registers-audition-r6").canonicalFile
        val baseline = loadBaseline(File(args.firstOrNull { it.startsWith("--baseline=") }?.substringAfter('=')
            ?: "../testkit/registers-audition-r5"))
        check(root != baseline.root && !root.toPath().startsWith(baseline.root.toPath()) &&
            !baseline.root.toPath().startsWith(root.toPath())) { "R5 archive and R6 output must be separate directories." }
        check(root.mkdirs() || root.isDirectory)
        preserveArchive(root, baseline)
        val clips = baseline.clips.map { old -> LinkedHashMap(old).also { clip ->
            // Make implicit R5 review scopes explicit without moving any clip or asset id.
            if (clip["reviewAudioHash"] == null) {
                clip["reviewClipId"] = old.getValue("id")
                clip["reviewRevision"] = BASELINE_REVISION
                clip["reviewAudioHash"] = BASELINE_AUDIO_HASH
            }
            clip["recipeSha256"] = baseline.recipes.getValue(old.getValue("id") as String)
            clip["retainedFromR5"] = true
        } }.toMutableList<Map<String, Any?>>()
        val cases = listOf("Compact Keys" to "compact", "Garage Buzz" to "garage").flatMap { (name, tag) ->
            listOf(48, 60, 72).map { Case(name, tag, it) }
        } + Case("Clean Combo", "clean", 60, control = true)
        val pairs = cases.map { case ->
            val sides = listOf(5, MODEL).map { model -> render(root, case, model).also { clips += it } }
            val old = sides[0]; val revised = sides[1]
            check((old.getValue("recipe") as Map<*, *>) - "model" ==
                (revised.getValue("recipe") as Map<*, *>) - "model")
            linkedMapOf<String, Any?>(
                "id" to "sustain_${case.tag}_${case.midi}", "group" to "sustain",
                "label" to "${case.preset} · ${noteName(case.midi)}${if (case.control) " · SPREAD 0 control" else ""}",
                "description" to if (case.control)
                    "SPREAD stays at zero. Compare the accepted foundation and keying while the ensemble layer is off. Both files play the actual instrument attack, handoff and three complete loop repeats."
                else "Same preset, note, velocity and controls. Listen through the 350–450 ms handoff, then follow the gentle ensemble through three complete loop repeats. R6 retains small SPREAD settings as periodic phase movement.",
                "originalId" to old.getValue("id"), "revisedId" to revised.getValue("id"),
                "originalLabel" to "Baseline R5", "revisedLabel" to "Revised sustain R6",
                "sameControls" to true, "originalModel" to 5, "revisedModel" to MODEL,
                "zeroSpreadControl" to case.control, "renderOrigin" to "Keys.registersPad",
                "handoffStartSeconds" to .350, "handoffEndSeconds" to .450,
            )
        }
        check(clips.size == 161 && clips.map { it.getValue("id") }.distinct().size == clips.size)
        validateRetained(root, baseline)
        val repository = repositoryRoot()
        val sources = sourceFingerprints(repository, root)
        val metadata = mapOf(
            "revision" to BASELINE_REVISION, "audioHash" to BASELINE_AUDIO_HASH,
            "manifest" to "baseline/r5-manifest.json", "manifestSha256" to BASELINE_MANIFEST_HASH,
            "sourceRoot" to "baseline/source/r5", "sourceFingerprint" to baseline.manifest.obj().getValue("sourceFingerprint"),
            "sourceFingerprintStatus" to "retained-published-metadata", "sourceSnapshotStatus" to "complete-and-verified",
            "sourceSnapshotFingerprints" to baseline.sources, "verifiedWavCount" to 294,
            "verifiedRecipeCount" to 147, "copiedOriginalCases" to 147,
            "recipeFingerprints" to baseline.recipes.map { (id, hash) -> mapOf("id" to id, "sha256" to hash) },
        )
        @Suppress("UNCHECKED_CAST")
        val manifest = LinkedHashMap(plain(baseline.manifest) as Map<String, Any?>)
        val audioHash = audioHash(clips)
        val old = baseline.manifest.obj()
        manifest.putAll(mapOf(
            "revision" to REVISION, "model" to MODEL, "clipCount" to clips.size, "audioHash" to audioHash,
            "status" to "Accepted R5 body retained; revised TRANSISTOR keyboard sustain awaiting listening; physical phone and MPC listening pending",
            "voiceStatus" to mapOf("PIPE" to "working-audition-baseline", "MUSETTE" to "working-audition-baseline",
                "TRANSISTOR" to "sustain-listening-pending"),
            "sourceRevision" to git(repository, "rev-parse", "HEAD"),
            "workingTreeDirty" to (git(repository, "status", "--porcelain")?.isNotBlank() ?: true),
            "sourceFingerprints" to sources, "sourceFingerprint" to sha256(Json.write(json(sources)).toByteArray()),
            "previous" to metadata, "baselines" to old.getValue("baselines").arr().map(::plain) + metadata,
            "clips" to clips, "sustainComparisonPairs" to pairs,
            "sections" to old.getValue("sections").arr().map(::plain) + mapOf("id" to "sustain-comparisons",
                "label" to "Sustained ensemble", "description" to "Seven actual keyboard attack-to-loop comparisons: Compact Keys and Garage Buzz at C3, C4 and C5, plus a SPREAD 0 Clean Combo control."),
            "metricNotes" to "All 147 published R5 clips, their 294 WAVs, recipes, clip ids, earlier comparison pairs and listening-note scopes are retained. Fourteen new cases compare actual Keys.registersPad output at model 5 and 6, preserving all preset controls. The source prefix through the MPC loop marker is followed by three copies of its final complete loop. Only the last 4 ms receive an audition-only fade; these finite study WAVs play in full and do not retrigger their attack. Independent held-cycle recurrence is measured from synthesis before copying, under instrumentLoop. R6 small SPREAD uses periodic phase ensemble, not a slower constant detune. Matched playback targets loudest 200 ms RMS 0.03; raw uses the instrument renderer's output level with any PCM guard recorded. Existing depth, airflow and chiff evidence retains its original meaning. Desktop timing and phone/MPC listening are separate checks.",
            "summary" to mapOf("wavCount" to clips.size * 2, "preservedR5Clips" to 147,
                "newSustainClips" to 14, "sustainPairs" to pairs.size,
                "allRetainedWavsByteIdentical" to true, "allRetainedRecipesByteIdentical" to true,
                "allNewRecipesDeterministic" to true, "allNewInstrumentLoopsPassed" to true,
                "totalAudioSeconds" to clips.sumOf { number(it.getValue("seconds")) }),
        ))
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        for (asset in listOf("index.html", "styles.css", "app.js")) {
            val stream = javaClass.getResourceAsStream("/audition/registers-audition/$asset") ?: error("Missing $asset")
            File(root, asset).outputStream().use { output -> stream.use { it.copyTo(output) } }
        }
        File(root, "README.md").writeText("""
            # REGISTERS audition · $REVISION

            All 147 published R5 clips / 294 WAVs and their recipes remain byte-identical.
            Seven new model-5/model-6 pairs exercise the actual Keys.registersPad instrument:
            Compact Keys and Garage Buzz at C3/C4/C5, plus Clean Combo with SPREAD 0 at C4.
            Preset settings, pitch and velocity are identical within each pair. There are
            161 cases / 322 mono PCM24 WAVs at 44.1 kHz in the complete library.

            Play the complete finite comparison file: the instrument's speaking attack,
            its 350–450 ms handoff, the original prefix through its loop marker, then three
            complete repetitions of that measured final loop. The final 4 ms fade is an
            audition-only ending. Files are not onset-free loops and must not be looped from
            frame zero. instrumentLoop records loop boundaries and independently measured
            adjacent synthesis cycles; copying the playback region is not seam evidence.

            Raw is the actual levelled instrument output with only a declared peak guard
            and the terminal audition fade. Matched uses AuditionLevel's loudest-window
            0.03 RMS target. A recipe replays through Keys.registersPad at its explicit MIDI,
            then the documented prefix/repeat/fade transformation, rather than patch.render.

            All earlier comparison pairs and R1–R5 listening-note identities are retained.
            The complete R5 manifest and 17 source snapshots are archived under baseline.
            R5's accepted body remains available. R6 sustain, phone and MPC listening await
            the listener; numerical measurements do not supply a listening verdict.

            Regenerate: ./gradlew :synth:generateRegistersTransistorSustainAudition -PregistersR5Baseline=<published R5 archive>
            Published R5 manifest SHA-256: $BASELINE_MANIFEST_HASH
            Published R5 audio fingerprint: $BASELINE_AUDIO_HASH
            R6 audio fingerprint: $audioHash
        """.trimIndent() + "\n")
        println("Wrote ${clips.size} clips / ${clips.size * 2} WAVs and ${pairs.size} sustain pairs under $root")
    }

    private fun render(root: File, case: Case, model: Int): Map<String, Any?> {
        val base = RegistersPresets.forVoice(voice).single { it.name == case.preset }
        val patch = base.copy(model = model, midi = case.midi)
        val id = "sustain_r${model}_${case.tag}_${case.midi}"
        val recipe = patch.toJsonValue()
        val replay = RegistersPatch.fromJsonValue(recipe)
        check(replay == patch)
        val note = Keys.registersPad(patch, case.midi)
        val repeated = Keys.registersPad(replay, case.midi)
        check(note.loopStartFrame == repeated.loopStartFrame && note.snip.samples.contentEquals(repeated.snip.samples)) {
            "$id: instrument recipe replay changed the rendered samples"
        }
        val held = patch.copy(macros = patch.macros + ("HOLD" to 1f)).probe()
        val previous = requireNotNull(held.previousCycle)
        val cycle = relativeError(previous, held.samples)
        val seam = relativeError(previous, held.samples, (held.samples.size - 256).coerceAtLeast(0))
        check(previous !== held.samples && cycle == held.cycleError && seam == held.seamError)
        check(cycle.isFinite() && cycle < .00001 && seam.isFinite() && seam < Keys.MAX_SEAM_ERROR)
        val start = note.loopStartFrame.toInt()
        val frames = note.snip.frameCount - start
        check(start > 0 && frames == held.samples.size && note.snip.channels == 1 && note.snip.sampleRate == Dsp.RATE)
        val samples = FloatArray(start + REPEATS * frames) { i ->
            if (i < start) note.snip.samples[i] else note.snip.samples[start + (i - start) % frames]
        }
        val fadeFrames = (.004 * Dsp.RATE).roundToInt()
        for (i in 0 until fadeFrames) samples[samples.size - fadeFrames + i] *= 1f - i.toFloat() / (fadeFrames - 1)
        val source = Snip(samples, 1, Dsp.RATE)
        validateSnip(source)
        val exportGain = minOf(1f, .99f / source.peak().coerceAtLeast(1e-9f))
        val raw = Snip(FloatArray(samples.size) { samples[it] * exportGain }, 1, Dsp.RATE)
        val matched = AuditionLevel.level(source)
        val paths = mapOf("raw" to "raw/$id.wav", "matched" to "matched/$id.wav")
        val levels = linkedMapOf<String, Any>()
        val hashes = linkedMapOf<String, String>()
        for ((mode, snip) in listOf("raw" to raw, "matched" to matched)) {
            val file = WavWriter.write(File(root, paths.getValue(mode)), snip, WavWriter.BitDepth.PCM_24)
            val decoded = WavReader.read(file)
            validateSnip(decoded)
            check(decoded.frameCount == snip.frameCount && decoded.peak() <= .990001f)
            check(decoded.samples.indices.all { abs(decoded.samples[it] - snip.samples[it]) <= 1.3e-7f })
            levels[mode] = metrics(decoded); hashes[mode] = sha256(file)
        }
        check(abs(number((levels.getValue("matched") as Map<*, *>)["loudestWindowRms"]) - .03) < .00001)
        val recipePath = "recipes/$id.json"
        val recipeFile = File(root, recipePath).also { it.parentFile.mkdirs() }
        recipeFile.writeText(Json.write(recipe) + "\n")
        check(RegistersPatch.fromJsonText(recipeFile.readText()) == patch)
        println("REGISTERS R6 $id · independent cycle=$cycle seam=$seam")
        return linkedMapOf(
            "id" to id, "section" to "sustain-comparisons", "voice" to voice.name,
            "label" to "${if (model == 5) "Baseline R5" else "Revised sustain R6"} · ${case.preset}",
            "description" to "Actual keyboard attack, 350–450 ms handoff and three complete loop repeats${if (case.control) "; SPREAD 0 control" else ""}.",
            "note" to case.midi, "noteName" to noteName(case.midi), "velocity" to patch.velocity,
            "gesture" to "keyed sustain", "macros" to patch.macros, "factoryPreset" to case.preset, "macro" to "SPREAD",
            "raw" to paths.getValue("raw"), "matched" to paths.getValue("matched"),
            "recipeUrl" to recipePath, "recipe" to plain(recipe), "recipeSha256" to sha256(recipeFile),
            "frames" to samples.size, "seconds" to samples.size.toDouble() / Dsp.RATE,
            "metrics" to levels, "sourceMetrics" to metrics(source), "rawExportGain" to exportGain,
            "sha256" to hashes, "renderOrigin" to "Keys.registersPad", "auditionRevision" to REVISION,
            "listeningStatus" to "sustain-pending", "zeroSpreadControl" to case.control,
            "auditionOnlyTerminalFadeFrames" to fadeFrames,
            "instrumentLoop" to mapOf("startFrame" to start, "regionFrames" to frames,
                "regionSeconds" to frames.toDouble() / Dsp.RATE, "playbackRepetitions" to REPEATS,
                "handoffStartSeconds" to .350, "handoffEndSeconds" to .450,
                "cycleError" to cycle, "seamError" to seam, "cyclePass" to true, "seamPass" to true,
                "independentPreviousCycle" to true,
                "previousCycleFloatSha256" to floatHash(previous), "currentCycleFloatSha256" to floatHash(held.samples),
                "evidenceNotes" to "Independent adjacent engine synthesis cycles measured before Keys repetition. Finite audition plays the actual instrument prefix and three complete copies of its final loop, with only a final 4 ms audition fade."),
            "diagnostics" to mapOf("model" to model, "requestedHz" to held.requestedHz, "effectiveHz" to held.effectiveHz,
                "effectiveSpreadHz" to held.effectiveSpreadHz, "spreadModulationHz" to held.spreadModulationHz,
                "spreadMode" to if (held.spreadModulationHz > 0.0) "periodic-phase-ensemble" else "constant-detune",
                "spreadQuantityNotes" to "Constant bank detune for model 5; nominal peak bank offset before common vibrato for periodic model 6. A zero SPREAD control keeps the offset zero.",
                "motionHz" to held.motionHz,
                "tuningErrorCents" to 1200.0 * ln(held.effectiveHz / held.requestedHz) / ln(2.0),
                "rawPeak" to held.rawPeak, "deterministic" to true, "recipeRoundtripSamplesExact" to true),
        )
    }

    private fun loadBaseline(directory: File): Baseline {
        val root = directory.canonicalFile
        val file = archiveAsset(root, "manifest.json")
        check(sha256(file) == BASELINE_MANIFEST_HASH) { "R5 manifest differs from the published accepted revision." }
        val manifest = Json.parse(file.readText())
        val obj = manifest.obj()
        check(obj.getValue("revision").str() == BASELINE_REVISION && obj.getValue("audioHash").str() == BASELINE_AUDIO_HASH &&
            obj.getValue("clipCount").int() == 147 && obj.getValue("model").int() == 5)
        val recipes = linkedMapOf<String, String>()
        val clips = obj.getValue("clips").arr().map { entry ->
            val clip = entry.obj(); val id = clip.getValue("id").str()
            check(id !in recipes)
            for (mode in listOf("raw", "matched")) check(sha256(archiveAsset(root, clip.getValue(mode).str())) ==
                clip.getValue("sha256").obj().getValue(mode).str()) { "R5 $id $mode changed" }
            val recipe = archiveAsset(root, clip.getValue("recipeUrl").str())
            check(Json.parse(recipe.readText()) == clip.getValue("recipe"))
            recipes[id] = sha256(recipe)
            @Suppress("UNCHECKED_CAST")
            (plain(entry) as Map<String, Any?>)
        }
        check(audioHash(clips) == BASELINE_AUDIO_HASH)
        val sources = obj.getValue("sourceFingerprints").arr().map { entry ->
            val source = entry.obj(); val path = source.getValue("path").str(); val hash = source.getValue("sha256").str()
            check(sha256(archiveAsset(root, "source/$path")) == hash)
            mapOf("path" to path, "sha256" to hash)
        }
        check(sources.size == 17)
        for (entry in obj.getValue("baselines").arr()) {
            val metadata = entry.obj()
            val archived = archiveAsset(root, metadata.getValue("manifest").str())
            check(sha256(archived) == metadata.getValue("manifestSha256").str())
            val sourceRoot = metadata["sourceRoot"]
            if (sourceRoot != null && sourceRoot != JsonValue.Null) for (source in Json.parse(archived.readText()).obj().getValue("sourceFingerprints").arr()) {
                val fingerprint = source.obj()
                check(sha256(archiveAsset(root, "${sourceRoot.str()}/${fingerprint.getValue("path").str()}")) == fingerprint.getValue("sha256").str())
            }
        }
        return Baseline(root, manifest, clips, recipes, sources)
    }

    private fun preserveArchive(root: File, baseline: Baseline) {
        baseline.root.walkTopDown().forEach { file ->
            val relative = file.relativeTo(baseline.root).path; val target = File(root, relative)
            if (file.isDirectory) check(target.mkdirs() || target.isDirectory)
            else if (file.isFile) {
                check(file.canonicalFile.toPath().startsWith(baseline.root.toPath()))
                target.parentFile.mkdirs(); file.copyTo(target, overwrite = true)
                check(sha256(file) == sha256(target))
            }
        }
        File(baseline.root, "manifest.json").copyTo(File(root, "baseline/r5-manifest.json"), overwrite = true)
        for (source in baseline.sources) {
            val path = source.getValue("path"); val target = File(root, "baseline/source/r5/$path")
            target.parentFile.mkdirs(); archiveAsset(baseline.root, "source/$path").copyTo(target, overwrite = true)
            check(sha256(target) == source.getValue("sha256"))
        }
    }

    private fun validateRetained(root: File, baseline: Baseline) {
        for (clip in baseline.clips) {
            val id = clip.getValue("id") as String
            for (mode in listOf("raw", "matched")) check(sha256(archiveAsset(root, clip.getValue(mode) as String)) ==
                (clip.getValue("sha256") as Map<*, *>)[mode]) { "$id $mode changed in R6" }
            check(sha256(archiveAsset(root, clip.getValue("recipeUrl") as String)) == baseline.recipes.getValue(id))
        }
    }

    private fun sourceFingerprints(repository: File, output: File): List<Map<String, String>> {
        val paths = listOf("synth/src/main/kotlin/com/snipsnap/synth/Registers.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/RegistersTransistor.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/RegistersPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/RegistersPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt", "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/RegistersTransistorSustainAuditionGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt", "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt", "json/src/main/kotlin/com/snipsnap/json/Json.kt",
            "synth/src/test/resources/audition/registers-audition/index.html",
            "synth/src/test/resources/audition/registers-audition/styles.css",
            "synth/src/test/resources/audition/registers-audition/app.js")
        check(File(output, "source").deleteRecursively())
        return paths.map { path ->
            val source = File(repository, path); val target = File(output, "source/$path")
            target.parentFile.mkdirs(); source.copyTo(target, overwrite = true)
            check(sha256(source) == sha256(target)); mapOf("path" to path, "sha256" to sha256(source))
        }
    }

    private fun relativeError(a: FloatArray, b: FloatArray, start: Int = 0): Double {
        var difference = 0.0; var energy = 0.0
        for (i in start until a.size) { val d = a[i] - b[i].toDouble(); difference += d * d; energy += a[i].toDouble() * a[i] }
        return difference / energy.coerceAtLeast(1e-30)
    }
    private fun validateSnip(snip: Snip) = check(snip.channels == 1 && snip.sampleRate == Dsp.RATE &&
        snip.frameCount > 2 && snip.samples.all { it.isFinite() } && snip.peak() > 1e-6f)
    private fun metrics(snip: Snip): Map<String, Any> {
        var sum = 0.0; var energy = 0.0; var differences = 0.0
        for (i in snip.samples.indices) { val x = snip.samples[i].toDouble(); sum += x; energy += x * x
            if (i > 0) { val d = x - snip.samples[i - 1]; differences += d * d } }
        val rms = sqrt(energy / snip.frameCount)
        return mapOf("peak" to snip.peak().toDouble(), "rms" to rms, "dcMean" to sum / snip.frameCount,
            "loudestWindowRms" to Loudness.of(snip).toDouble(),
            "firstDifferenceRms" to sqrt(differences / (snip.frameCount - 1)),
            "crestDb" to 20 * log10(snip.peak().toDouble() / rms.coerceAtLeast(1e-20)))
    }
    private fun archiveAsset(root: File, path: String): File = File(root, path).canonicalFile.also {
        check(it.toPath().startsWith(root.toPath()) && it.isFile) { "Invalid archive path $path" } }
    private fun audioHash(clips: List<Map<String, Any?>>): String = sha256(clips.sortedBy { it.getValue("id") as String }.joinToString("\n") {
        val hashes = it.getValue("sha256") as Map<*, *>; "${it.getValue("id")}:${hashes["raw"]}:${hashes["matched"]}"
    }.toByteArray(Charsets.UTF_8))
    private fun floatHash(samples: FloatArray): String { val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putInt(it.toRawBits()) }; return sha256(bytes.array()) }
    private fun number(value: Any?): Double = (value as Number).toDouble()
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun sha256(file: File) = sha256(file.readBytes())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun repositoryRoot(): File = generateSequence(File(".").canonicalFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private fun git(repository: File, vararg args: String): String? = runCatching { val process = ProcessBuilder(listOf("git") + args)
        .directory(repository).redirectErrorStream(true).start(); val text = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) text else null }.getOrNull()
    private fun plain(value: JsonValue): Any? = when (value) { JsonValue.Null -> null
        is JsonValue.Str -> value.value; is JsonValue.Bool -> value.value; is JsonValue.Num -> value.value
        is JsonValue.Obj -> value.entries.mapValues { plain(it.value) }; is JsonValue.Arr -> value.items.map(::plain) }
    private fun json(value: Any?): JsonValue = when (value) { null -> JsonValue.Null; is JsonValue -> value
        is String -> JsonValue.Str(value); is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json)); else -> error("Unsupported manifest value ${value.javaClass}") }
}
