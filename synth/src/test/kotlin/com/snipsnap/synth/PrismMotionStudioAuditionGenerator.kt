package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.sqrt

/**
 * A bounded listening matrix for the actual source picker and motion editor.
 * Native files are public engine renders; matched files change their gain only.
 * HOLD evidence uses a separately generated preceding cycle, never a duplicated export.
 */
object PrismMotionStudioAuditionGenerator {
    private data class Case(
        val id: String,
        val title: String,
        val section: String,
        val sourceTitle: String,
        val variant: String,
        val patch: Patch,
        val description: String,
        val sourceId: String? = (patch as? PrismPatch)?.motion?.sourceId,
        val sourceSelectionMillis: Double? = null,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) {
            "usage: PrismMotionStudioAuditionGenerator [output-directory]"
        }
        val root = File(args.firstOrNull() ?: "../testkit/prism-motion-studio-audition").absoluteFile
        require(root.isDirectory || root.mkdirs()) { "cannot create $root" }
        val repository = repositoryRoot()
        val started = System.nanoTime()
        val accepted = verifyAcceptedDefaults()
        val cases = cases()
        check(cases.size == 28 && cases.map { it.id }.toSet().size == cases.size) {
            "the studio needs four accepted notes, fifteen source comparisons and nine controls"
        }
        PrismOpeningPresets.all().forEach { it.render() }
        val rows = cases.mapIndexed { index, case ->
            val row = render(root, case)
            println("PRISM studio ${index + 1}/${cases.size}: ${case.title}")
            row
        }
        check(rows.count { it["loop"] == true } == 6) { "missing source or time HOLD comparisons" }
        val sourcePaths = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Prism.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismMotion.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismOpeningPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismMotionEditor.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/PrismMotionSources.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/PrismMotionStudioAuditionGenerator.kt",
            "synth/src/test/resources/audition/prism-motion-studio.html",
        )
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "title" to "PRISM motion studio",
            "generatedAt" to Instant.now().toString(),
            "sourceRevision" to git(repository, "rev-parse", "HEAD"),
            "sourceDirty" to git(repository, "status", "--porcelain", "--untracked-files=normal")?.isNotBlank(),
            "sourceFilesSha256" to sourcePaths.associateWith { hash(File(repository, it)) },
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 24,
            "javaVersion" to System.getProperty("java.version"),
            "osArchitecture" to System.getProperty("os.arch"),
            "clipCount" to rows.size, "clips" to rows,
            "acceptedDefaults" to accepted,
            "sourceCatalog" to PrismMotionSources.all().map {
                linkedMapOf("id" to it.id, "title" to it.title, "category" to it.category,
                    "engine" to it.patch.engine, "voice" to it.patch.voiceName)
            },
            "nativeMeaning" to "Dry public engine output with its normal conditioning and performance dynamics.",
            "matchedMeaning" to "Gain only: shared loudest-200-ms RMS target .03 with peak guard.",
            "sourceComparisonNote" to "Original donors play their complete saved recipes at their own saved pitch. Spectral transfer keeps PRISM's carrier pitch; it borrows amplitude colour, not the donor's whole sound.",
            "repeatNote" to "REPEAT on a finite note keeps its attack and release. HOLD exports a periodic region without an attack, containing enough complete colour cycles to exceed 1.5 seconds. Eight-loop files concatenate that exact exported region eight times.",
            "measurementNote" to "Loop seams compare an independently generated preceding cycle with the exported cycle. Complete recipes replay bit-for-bit on this runtime. Carrier and seam checks are not a complete alias-spectrum measurement.",
            "timingNote" to "Elapsed milliseconds measure one warmed public render on this JVM, excluding recipe replay, matching, probes and file writes. Source selection includes its blocking cached donor analysis. Android interaction and MPC playback remain device checks.",
            "listeningStatus" to "Rendered and measured; no listening approval is inferred.",
            "generationSeconds" to (System.nanoTime() - started) / 1e9,
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        val page = requireNotNull(javaClass.getResourceAsStream("/audition/prism-motion-studio.html")) {
            "missing PRISM motion studio listening page"
        }
        File(root, "index.html").outputStream().use { output -> page.use { it.copyTo(output) } }
        println("Wrote ${rows.size} PRISM studio clips and six eight-exported-loop comparisons to $root")
    }

    private fun cases(): List<Case> = buildList {
        val accepted = PrismOpeningPresets.all()
        for ((index, patch) in accepted.withIndex()) {
            add(Case("accepted-${index + 1}", patch.name, "accepted", patch.name, "accepted", patch,
                "Accepted factory opening, with its original saved note, velocity, carrier and endpoints."))
        }
        val alternatives = listOf("fork-mellow-reed", "tessera-wooden-arrival", "fathom-driven-sub")
        for (id in alternatives) {
            val source = requireNotNull(PrismMotionSources.byId(id)) { "missing studio source $id" }
            val base = accepted.single { it.name == if (id.startsWith("fathom-")) "Hollow Rise" else "Soft Arrival" }
            val selectionStarted = System.nanoTime()
            val picked = PrismMotionSources.applySource(base, source)
            val selectionMillis = (System.nanoTime() - selectionStarted) / 1e6
            val opening = PrismMotionEditor.withTime(PrismMotionEditor.withAmount(
                PrismMotionEditor.withRepeat(picked, false), 1f), .8f)
            val repeating = PrismMotionEditor.withRepeat(opening, true)
            val held = repeating.copy(macros = repeating.macros + ("HOLD" to 1f))
            fun addVariant(variant: String, label: String, patch: Patch, description: String) {
                add(Case("$id-$variant", "${source.title} · $label", "sources", source.title,
                    variant, patch, description, source.id, selectionMillis))
            }
            addVariant("original", "Original donor", source.patch,
                "Complete original source recipe at its saved pitch. This is a reference sound, not a PRISM reconstruction.")
            addVariant("static", "Static colour", PrismMotionEditor.staticDestination(opening),
                "PRISM at the chosen destination colour without spectral travel; the carrier pitch stays anchored.")
            addVariant("open", "Open once", opening,
                "The dark body opens once into the chosen spectral colour over 0.8 seconds.")
            addVariant("repeat-finite", "Repeat as a note", repeating,
                "The same endpoints repeat inside a finite note, retaining its attack and release.")
            addVariant("hold", "HOLD loop", held,
                "A genuine held region with repeated spectral motion. Compare eight exported loops for the seam; one export may contain several colour cycles.")
        }
        val base = accepted.single { it.name == "Bowed Bloom" }
        for ((index, amount) in listOf(0f, .5f, 1f).withIndex()) {
            add(Case("amount-$index", "Bowed Bloom · amount $amount", "controls", base.name, "amount",
                PrismMotionEditor.withAmount(base, amount),
                "AMOUNT changes how far the body travels toward the saved colour; opening time stays at 0.8 seconds."))
        }
        for ((index, seconds) in listOf(.2f, .8f, 2.4f).withIndex()) {
            add(Case("time-$index", "Bowed Bloom · open ${seconds}s", "controls", base.name, "time",
                PrismMotionEditor.withTime(base, seconds),
                "OPEN changes the duration of a single spectral opening while leaving its endpoints intact."))
            val repeated = PrismMotionEditor.withTime(PrismMotionEditor.withRepeat(base, true), seconds)
            add(Case("repeat-time-$index", "Bowed Bloom · repeat ${seconds}s HOLD", "controls", base.name,
                "repeat-time", repeated.copy(macros = repeated.macros + ("HOLD" to 1f)),
                "Requested spectral period is ${seconds}s. The exported HOLD length is measured from the actual periodic render."))
        }
    }

    private fun render(root: File, case: Case): Map<String, Any?> {
        val started = System.nanoTime()
        val native = case.patch.render()
        val elapsedMillis = (System.nanoTime() - started) / 1e6
        check(native.channels == 1 && native.sampleRate == Dsp.RATE && native.samples.isNotEmpty() &&
            native.samples.all { it.isFinite() } && native.peak() <= 1f) { "${case.id}: invalid public render" }
        val recipe = PadRecipe(case.patch)
        val text = recipe.toJsonText()
        val reopened = PadRecipe.fromJsonText(text)
        check(reopened.toJsonText() == text && sameBits(native.samples, reopened.render().samples)) {
            "${case.id}: complete recipe replay changed the sound"
        }
        val prism = case.patch as? PrismPatch
        val motion = prism?.motion
        val loop = prism != null && Prism.isLoop(Prism.settled(prism.voice, prism.macros).getValue("HOLD"))
        val evidence = if (loop) {
            requireNotNull(prism)
            val probe = Prism.probe(prism.voice, prism.macros, prism.velocity, prism.template,
                normalize = true, keepRaw = false, motion = motion)
            val preceding = requireNotNull(probe.previousCycle) { "${case.id}: missing preceding-cycle evidence" }
            check(probe.motionCycleFrames > 0 && probe.loopFrames % probe.motionCycleFrames == 0 &&
                probe.loopFrames == native.frameCount && preceding.size == native.frameCount &&
                sameBits(probe.samples, native.samples)) { "${case.id}: public loop differs from its probe" }
            val seam = Keys.seamError(preceding + native.samples, native.frameCount)
            check(seam < Keys.MAX_SEAM_ERROR) { "${case.id}: preceding-cycle seam $seam" }
            val energy = native.samples.sumOf { it.toDouble() * it }
            val delta = sqrt(preceding.indices.sumOf {
                val difference = preceding[it].toDouble() - native.samples[it]
                difference * difference
            } / energy.coerceAtLeast(1e-20))
            check(delta < 1e-3) { "${case.id}: adjacent cycles differ by $delta relative RMS" }
            linkedMapOf<String, Any?>("loopFrames" to probe.loopFrames,
                "loopSeconds" to probe.loopFrames.toDouble() / Dsp.RATE,
                "motionCycleFrames" to probe.motionCycleFrames,
                "actualCycleSeconds" to probe.motionCycleFrames.toDouble() / Dsp.RATE,
                "motionCyclesPerExport" to probe.loopFrames / probe.motionCycleFrames,
                "seamError" to seam, "precedingCycleRelativeRmsDelta" to delta,
                "precedingCycleSamplesSha256" to samplesHash(preceding))
        } else emptyMap()
        val matched = AuditionLevel.level(native)
        val nativePath = "audio/${case.id}-native.wav"
        val matchedPath = "audio/${case.id}-matched.wav"
        val recipePath = "recipes/${case.id}.json"
        val midi = prism?.let { Prism.midiFor(it.voice, Prism.settled(it.voice, it.macros).getValue("TUNE")) }
        val sampler = midi?.let { SmplChunk(rootNote = it,
            loop = if (loop) SmplChunk.Loop(0, native.frameCount.toLong()) else null) }
        WavWriter.write(File(root, nativePath), native, WavWriter.BitDepth.PCM_24, smpl = sampler)
        WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24, smpl = sampler)
        File(root, recipePath).apply { parentFile.mkdirs(); writeText(text) }
        val hashes = linkedMapOf("native" to hash(File(root, nativePath)),
            "matched" to hash(File(root, matchedPath)), "recipe" to hash(File(root, recipePath)))
        val row = linkedMapOf<String, Any?>(
            "id" to case.id, "title" to case.title, "section" to case.section,
            "sourceTitle" to case.sourceTitle, "sourceId" to case.sourceId,
            "variant" to case.variant, "description" to case.description,
            "engine" to case.patch.engine, "voice" to case.patch.voiceName, "midi" to midi,
            "native" to nativePath, "matched" to matchedPath, "recipe" to recipePath,
            "loop" to loop, "frames" to native.frameCount, "seconds" to native.durationSeconds,
            "amount" to motion?.evolve, "motionSeconds" to motion?.seconds, "repeat" to motion?.repeat,
            "elapsedMs" to elapsedMillis, "sourceSelectionMillis" to case.sourceSelectionMillis,
            "recipeReplayExact" to true, "nativeSamplesSha256" to samplesHash(native.samples),
            "hashes" to hashes, "metrics" to mapOf("native" to metrics(native), "matched" to metrics(matched)),
        )
        row.putAll(evidence)
        if (loop) {
            val eightNative = "audio/${case.id}-native-eight.wav"
            val eightMatched = "audio/${case.id}-matched-eight.wav"
            // The same gain and samples repeat. Re-leveling the long file would obscure the cycle comparison.
            WavWriter.write(File(root, eightNative), eight(native), WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, eightMatched), eight(matched), WavWriter.BitDepth.PCM_24)
            row["eightNative"] = eightNative
            row["eightMatched"] = eightMatched
            row["eightFrames"] = native.frameCount * 8
            hashes["eightNative"] = hash(File(root, eightNative))
            hashes["eightMatched"] = hash(File(root, eightMatched))
        }
        return row
    }

    private fun verifyAcceptedDefaults(): List<Map<String, Any?>> {
        val manifest = Json.parse(resource("manifest.json").toString(Charsets.UTF_8)).obj()
        return manifest.getValue("clips").arr().map { value ->
            val row = value.obj()
            val name = row.getValue("factoryName").str()
            val bytes = resource(row.getValue("recipe").str())
            check(digest(bytes) == row.getValue("recipeSha256").str()) { "$name: accepted fixture changed" }
            val accepted = PadRecipe.fromJsonText(bytes.toString(Charsets.UTF_8))
            val original = accepted.patch as PrismPatch
            val factory = PrismOpeningPresets.all().single { it.name == name }
            check(factory.copy(name = original.name) == original &&
                PadRecipe(factory.copy(name = original.name)).toJsonText() == bytes.toString(Charsets.UTF_8)) {
                "$name: accepted recipe fields or serialization changed"
            }
            val samples = factory.render().samples
            check(sameBits(samples, accepted.render().samples)) { "$name: accepted sound changed on this runtime" }
            val actualHash = samplesHash(samples)
            linkedMapOf<String, Any?>("name" to name, "fixtureRecipeSha256" to digest(bytes),
                "recipeFieldsExact" to true, "sameRuntimeSamplesExact" to true,
                "currentSamplesSha256" to actualHash,
                "capturedSamplesSha256" to row.getValue("rawSamplesSha256").str(),
                "capturedRuntimeHashMatches" to (actualHash == row.getValue("rawSamplesSha256").str()))
        }
    }

    private fun resource(name: String): ByteArray = requireNotNull(javaClass
        .getResourceAsStream("/prism-opening-accepted/$name")) { "missing accepted fixture $name" }
        .use { it.readBytes() }

    private fun eight(snip: Snip) = snip.copy(samples = FloatArray(snip.samples.size * 8) { snip.samples[it % snip.samples.size] })

    private fun metrics(snip: Snip): Map<String, Any?> = linkedMapOf(
        "peak" to snip.peak(),
        "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size,
        "loudness" to Loudness.of(snip),
    )

    private fun sameBits(a: FloatArray, b: FloatArray) = a.size == b.size && a.indices.all { a[it].toRawBits() == b[it].toRawBits() }
    private fun samplesHash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putInt(it.toRawBits()) }
        return digest(bytes.array())
    }
    private fun hash(file: File) = digest(file.readBytes())
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun repositoryRoot(): File {
        var file = File(".").canonicalFile
        while (!File(file, ".git").exists() && file.parentFile != null) file = file.parentFile
        require(File(file, ".git").exists()) { "cannot locate source checkout" }
        return file
    }
    private fun git(repository: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git", "-C", repository.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble())
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associateTo(LinkedHashMap()) { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported studio manifest value ${value.javaClass.name}")
    }
}
