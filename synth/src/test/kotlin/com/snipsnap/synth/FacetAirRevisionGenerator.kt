package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Four new renders; the published Glass/Airy PCM and replay settings remain immutable. */
object FacetAirRevisionGenerator {
    private const val PREFIX = "air-revision"
    private const val REVISION = "air-veil-v1"
    private val variants = listOf("raw", "native", "matched")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && !args[0].startsWith("--")) { "usage: FacetAirRevisionGenerator existing-audition-directory" }
        val root = File(args[0]).canonicalFile
        val archived = File(root, "light-study/published-manifest.json")
        if (!archived.isFile) {
            val current = File(root, "manifest.json")
            require(Json.parse(current.readText()).obj()["revision"]?.str() == "light-study-v1") { "Expected the published light study" }
            current.copyTo(archived)
        }
        val old = Json.parse(archived.readText()).obj()
        require(old.getValue("revision").str() == "light-study-v1")
        val oldClips = old.getValue("sections").arr().flatMap { it.obj().getValue("clips").arr() }.map { it.obj() }
        require(oldClips.size == 12)
        val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val sources = sourceHashes(repository)
        val provenance = mapOf("repository" to "https://github.com/BertCalm/SnipSnap", "revision" to git(repository, "rev-parse", "HEAD"),
            "workingTreeDirty" to (git(repository, "status", "--porcelain")?.isNotBlank() ?: true),
            "sourceSha256" to sources, "runtimeClassSha256" to runtimeHashes())
        val mutable = setOf("manifest.json", "index.html", "style.css", "app.js", "light-study.zip", "air-revision.zip", "facet-air-revision-source.zip")
        val protected = root.walkTopDown().filter { it.isFile && it.relativeTo(root).invariantSeparatorsPath.let { p ->
            p !in mutable && !p.startsWith("$PREFIX/") } }.associateWith(::hash)
        val clips = mutableListOf<Map<String, Any?>>()
        val replayChecks = linkedMapOf<String, Any?>()
        val scratch = Files.createTempDirectory("facet-air-replay-").toFile()
        try {
            for (voice in listOf(FacetVoice.BLOOM, FacetVoice.SHIMMER)) for (midi in listOf(48, 60)) {
                val group = "air_${voice.name.lowercase()}_$midi"
                val published = oldClips.filter { it.getValue("voice").str() == voice.name && it.getValue("midi").int() == midi }
                val airy = published.single { it.getValue("direction").str() == "AIRY_WASH" }
                val macros = airy.getValue("macros").obj().mapValues { it.value.num().toFloat() }
                for ((order, direction) in listOf(FacetLightDirection.GLASS_GLINTS, FacetLightDirection.AIRY_WASH).withIndex()) {
                    val clip = published.single { it.getValue("direction").str() == direction.name }
                    val id = clip.getValue("id").str()
                    val expected = GOLDENS.getValue(id)
                    val original = clip.getValue("sha256").obj().mapValues { it.value.str() }
                    require(original == expected) { "Published metadata differs from the pinned light-study hashes: $id" }
                    for (variant in variants) require(hash(File(root, clip.getValue(variant).str())) == expected.getValue(variant)) { "Published file changed: $id $variant" }
                    val replay = render(voice, direction, macros, midi)
                    val replayHashes = export(scratch, id, replay, midi).first
                    require(replayHashes == expected) { "Published $id does not replay exactly: $replayHashes" }
                    replayChecks[id] = mapOf("allThreeExportsIdentical" to true, "sha256" to expected)
                    val label = if (order == 0) "Glass glints" else "Airy wash original"
                    clips += clip.mapValues { it.value as Any? }.toMutableMap().apply {
                        put("title", "${voice.name} · ${note(midi)} · $label"); put("directionLabel", label)
                        put("directionStatus", "Published reference"); put("section", PREFIX)
                        put("comparison", mapOf("group" to group, "variant" to direction.name.lowercase(), "order" to order))
                        put("goldenReplay", replayChecks[id])
                    }
                    println("FACET air: verified published $id (raw/native/matched)")
                }
                val id = "${group}_airy_resonance"
                val started = System.nanoTime()
                val rendered = render(voice, FacetLightDirection.AIRY_RESONANCE, macros, midi)
                val elapsed = (System.nanoTime() - started) / 1e6
                val repeated = render(voice, FacetLightDirection.AIRY_RESONANCE, macros, midi)
                require(rendered.raw.contentEquals(repeated.raw) && rendered.snip.samples.contentEquals(repeated.snip.samples)) { "$id: non-deterministic replay" }
                val (hashes, decoded) = export(File(root, PREFIX), id, rendered, midi)
                val paths = variants.associateWith { "$PREFIX/$it/$id.wav" }
                val settings = "$PREFIX/settings/$id.json"
                val trace = "$PREFIX/traces/$id.json"
                val raw = Snip(rendered.raw, 1, Dsp.RATE)
                val exportGain = minOf(1f, .99f / raw.peak().coerceAtLeast(1e-9f))
                write(File(root, settings), mapOf("format" to "snipsnap.facet-light-study", "studyVersion" to FacetLightStudy.STUDY_VERSION,
                    "sourceClass" to FacetLightStudy::class.java.name, "entrypoint" to "probe", "direction" to "AIRY_RESONANCE",
                    "voice" to voice.name, "macros" to macros, "midi" to midi, "velocity" to .8f, "seed" to 17, "initialAlignment" to 0f,
                    "options" to mapOf("seconds" to 6f, "resonance" to true, "commonGainRouting" to false, "oversample" to 4, "sourceMotion" to true),
                    "provenance" to provenance, "notes" to "Directional study settings, not registered FACET patch JSON. Only the broad veil gain changes from AIRY_WASH; geometry, modal excitation, random streams, envelopes and study version remain fixed."))
                write(File(root, trace), mapOf("clip" to id, "sourceClass" to FacetLightStudy::class.java.name,
                    "samples" to rendered.trace.map { mapOf("seconds" to it.seconds, "phase" to it.phase, "routeEnergy" to it.routeEnergy) }))
                val d = rendered.diagnostics
                val diagnostics = mapOf("settings" to settings, "trace" to trace, "renderMillis" to elapsed,
                    "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc, "statePeak" to d.statePeak,
                    "rotorHz" to d.rotorHz, "startPhase" to d.startPhase, "endPhase" to d.endPhase,
                    "effectiveFacets" to d.effectiveFacets, "routeDiversity" to d.routeDiversity, "routeMotion" to d.routeMotion, "oversample" to d.oversample)
                val metrics = mapOf("sourceRaw" to amplitudes(raw), "rawExportGain" to exportGain,
                    "raw" to amplitudes(decoded.getValue("raw")), "native" to amplitudes(decoded.getValue("native")),
                    "matched" to amplitudes(decoded.getValue("matched")), "pitch" to pitch(decoded.getValue("native"), midi),
                    "features" to features(decoded.getValue("matched")))
                clips += linkedMapOf("id" to id, "title" to "${voice.name} · ${note(midi)} · Airy wash less noise",
                    "description" to "The same airy reflection and ringing-decay study with the broad noise veil reduced; compare against the unchanged original.",
                    "voice" to voice.name, "midi" to midi, "noteName" to note(midi), "velocity" to .8f, "seconds" to 6f,
                    "frames" to rendered.snip.frameCount, "macros" to macros, "alignment" to 0f, "seed" to 17, "section" to PREFIX,
                    "direction" to "AIRY_RESONANCE", "directionLabel" to "Airy wash less noise", "directionStatus" to "Listening revision",
                    "sourceClass" to FacetLightStudy::class.java.name, "studyVersion" to FacetLightStudy.STUDY_VERSION,
                    "comparison" to mapOf("group" to group, "variant" to "airy_resonance", "order" to 2), "variant" to "airy_resonance",
                    "raw" to paths.getValue("raw"), "native" to paths.getValue("native"), "matched" to paths.getValue("matched"),
                    "recipe" to settings, "diagnostics" to diagnostics, "metrics" to metrics, "sha256" to hashes,
                    "waveform" to waveform(decoded.getValue("matched").samples), "deterministicReplay" to true)
                println("FACET air: rendered $id, ${elapsed.toInt()} ms, matched peak ${decoded.getValue("matched").peak()}")
            }
        } finally { scratch.deleteRecursively() }
        require(sources == sourceHashes(repository)) { "Sources changed during generation; recompile and regenerate" }
        require(protected.all { (file, expected) -> file.isFile && hash(file) == expected }) { "A preserved study asset changed" }
        val reportPath = "$PREFIX/report.json"
        val summary = mapOf("clipCount" to 12, "newClipCount" to 4, "newWavCount" to 12, "reusedPublishedClipCount" to 8,
            "publishedReplayChecks" to replayChecks, "publishedExportsVerified" to 24, "previousFilesPreserved" to protected.size,
            "allFinite" to true, "pcmExportsVerified" to true, "deterministicNewClips" to true,
            "distribution" to "The redundant light-study.zip is retired after building air-revision.zip. All original 36 light-study WAVs, settings, traces, report, metrics and source ZIP remain; the 57-clip R2 archive remains intact.")
        val generatedAt = Instant.now().toString()
        write(File(root, reportPath), mapOf("schemaVersion" to 1, "revision" to REVISION, "generatedAt" to generatedAt,
            "provenance" to provenance, "summary" to summary, "metricNotes" to NOTES, "clips" to clips))
        File(root, "$PREFIX/README.txt").writeText("FACET airy revision: Glass glints, original Airy wash, and Airy wash less noise. Twelve matched dry mono 44.1 kHz PCM24 WAVs and complete study settings. Four new clips reduce only the broad veil gain; eight references preserve published PCM bytes. No new patch default or sonic approval.\n$NOTES\n")
        zip(root, clips, reportPath)
        require(File(root, "light-study.zip").let { !it.exists() || it.delete() }) { "Could not retire redundant light-study.zip" }
        val bytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        require(bytes < 250L * 1024 * 1024) { "Expanded audition exceeds the 250 MiB preflight budget: $bytes" }
        write(File(root, "manifest.json"), mapOf("schemaVersion" to 1, "title" to "FACET · A little less air", "revision" to REVISION,
            "generatedAt" to generatedAt, "description" to "The airy study, with its broad noise veil reduced and its ringing reflections preserved.",
            "status" to "Listening revision · no new default or sonic approval", "engine" to "FACET study", "sampleRate" to Dsp.RATE,
            "channels" to 1, "bitDepth" to 24, "provenance" to provenance, "metricNotes" to NOTES, "summary" to summary,
            "previousStudy" to old.getValue("previousStudy"), "sections" to listOf(mapOf("id" to PREFIX, "title" to "A little less air",
                "description" to "Four trios keep the same voice controls, note, velocity, seed, alignment and duration. Glass glints and the original Airy wash are unchanged; the revised Airy wash reduces only the broad noise veil. Level matching helps compare the remaining reflections.", "clips" to clips)),
            "downloads" to mapOf("zip" to "air-revision.zip", "report" to reportPath)))
        for (asset in listOf("index.html", "style.css", "app.js")) requireNotNull(javaClass.getResourceAsStream("/audition/facet-audition/$asset"))
            .use { input -> File(root, asset).outputStream().use { input.copyTo(it) } }
        println("FACET air ready: four new clips, eight immutable references, $bytes expanded bytes, $root")
    }

    private fun render(voice: FacetVoice, direction: FacetLightDirection, macros: Map<String, Float>, midi: Int) =
        FacetLightStudy.probe(voice, direction, macros, midi, .8f, 17, 0f, FacetOptions(seconds = 6f, oversample = 4))

    private fun export(root: File, id: String, rendered: FacetRender, midi: Int): Pair<Map<String, String>, Map<String, Snip>> {
        val source = rendered.raw
        require(source.size == 6 * Dsp.RATE && source.all { it.isFinite() } && rendered.snip.samples.all { it.isFinite() })
        val gain = minOf(1f, .99f / source.maxOf { abs(it) }.coerceAtLeast(1e-9f))
        val audio = mapOf("raw" to Snip(FloatArray(source.size) { source[it] * gain }, 1, Dsp.RATE),
            "native" to rendered.snip, "matched" to AuditionLevel.level(rendered.snip))
        val hashes = linkedMapOf<String, String>(); val decoded = linkedMapOf<String, Snip>()
        for ((variant, snip) in audio) {
            require(snip.peak() in 1e-8f..1f)
            val file = WavWriter.write(File(root, "$variant/$id.wav"), snip, WavWriter.BitDepth.PCM_24, smpl = SmplChunk(midi))
            val read = WavReader.read(file)
            require(read.sampleRate == Dsp.RATE && read.channels == 1 && read.frameCount == snip.frameCount)
            require(read.samples.indices.all { abs(read.samples[it] - snip.samples[it]) < .0000003f })
            hashes[variant] = hash(file); decoded[variant] = read
        }
        return hashes to decoded
    }
    private fun amplitudes(snip: Snip) = mapOf("peak" to snip.peak(), "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size, "loudestWindowRms" to Loudness.of(snip), "finite" to true)
    private fun pitch(snip: Snip, midi: Int): Map<String, Any?> {
        val target = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val windows = listOf(.35f, 1.1f, 2.4f).map { from -> Pitch.detect(snip, from, .25f).let { found ->
            mapOf("fromSeconds" to from, "hz" to found?.hz, "confidence" to found?.confidence,
                "cents" to found?.let { 1200 * ln(it.hz / target) / ln(2.0) }) } }
        val best = windows.filter { it["confidence"] != null }.maxByOrNull { (it.getValue("confidence") as Number).toDouble() }
        return mapOf("requestedHz" to target, "hz" to best?.get("hz"), "confidence" to best?.get("confidence"), "cents" to best?.get("cents"),
            "windows" to windows, "method" to "Stock Pitch.detect; highest confidence of three 250 ms windows; no periodicity acceptance gate")
    }
    private fun features(snip: Snip): Map<String, Any?> {
        val body = Snip(snip.samples.copyOfRange((.4 * Dsp.RATE).toInt(), (.7 * Dsp.RATE).toInt()), 1, Dsp.RATE)
        val f = FeatureExtractor.extract(body); val c = Classifier.classify(snip)
        return mapOf("bodyStartSeconds" to .4, "bodyWindowSeconds" to .3, "spectralWindowFrames" to 4096,
            "centroidHz" to f.centroidHz, "flatness" to f.flatness, "highRatio" to f.highRatio,
            "wholeClassifier" to c.drumClass.name, "wholeClassifierConfidence" to c.confidence)
    }
    private fun waveform(samples: FloatArray) = (0 until 120).map { bin ->
        (bin * samples.size / 120 until (bin + 1) * samples.size / 120).maxOf { abs(samples[it]) } }
    private fun zip(root: File, clips: List<Map<String, Any?>>, report: String) {
        val paths = linkedSetOf("$PREFIX/README.txt", report)
        for (clip in clips) { paths += string(clip.getValue("matched")); paths += string(clip.getValue("recipe"))
            val d = clip.getValue("diagnostics"); paths += if (d is JsonValue) d.obj().getValue("trace").str() else (d as Map<*, *>)["trace"] as String }
        val file = File(root, "air-revision.zip")
        ZipOutputStream(file.outputStream().buffered()).use { out -> for (path in paths.sorted()) {
            out.putNextEntry(ZipEntry(path).apply { time = 0 }); File(root, path).inputStream().use { it.copyTo(out) }; out.closeEntry() } }
        require(file.length() < 24L * 1024 * 1024)
    }
    private fun string(value: Any?) = if (value is JsonValue) value.str() else value as String
    private fun sourceHashes(root: File): Map<String, String> {
        val synth = listOf("FacetLightStudy.kt", "Facet.kt", "FacetModel1.kt", "Dsp.kt", "Tide.kt", "Thump.kt").map { File(root, "synth/src/main/kotlin/com/snipsnap/synth/$it") }
        val test = listOf("FacetAirRevisionGenerator.kt", "AuditionLevel.kt").map { File(root, "synth/src/test/kotlin/com/snipsnap/synth/$it") }
        val audioJson = listOf("audio", "json").flatMap { File(root, "$it/src/main/kotlin").walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
        return (synth + test + audioJson).sortedBy { it.path }.associate { it.relativeTo(root).invariantSeparatorsPath to hash(it) }
    }
    private fun runtimeHashes(): Map<String, String> {
        val hashes = linkedMapOf<String, String>()
        for (type in listOf(Snip::class.java, Json::class.java, FacetLightStudy::class.java, javaClass)) {
            val root = File(type.protectionDomain.codeSource.location.toURI())
            require(root.isDirectory) { "Runtime provenance currently requires unpacked Gradle classes" }
            root.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { file ->
                val name = file.relativeTo(root).invariantSeparatorsPath
                if (name.startsWith("com/snipsnap/audio/") || name.startsWith("com/snipsnap/json/") ||
                    listOf("Facet", "Dsp", "Tide", "Thump", "MacroSpec", "AuditionLevel").any { name.startsWith("com/snipsnap/synth/$it") }) hashes[name] = hash(file)
            }
        }
        return hashes.toSortedMap()
    }
    private fun write(file: File, value: Any?) { file.parentFile.mkdirs(); file.writeText(Json.write(json(value)) + "\n") }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null; is JsonValue -> value; is String -> JsonValue.Str(value); is Boolean -> JsonValue.Bool(value)
        is Number -> { require(value.toDouble().isFinite()); JsonValue.Num(value.toDouble()) }
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json)); else -> error("Unsupported JSON ${value::class}")
    }
    private fun hash(file: File) = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) } }
    private fun git(root: File, vararg args: String): String? = runCatching {
        val p = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val result = p.inputStream.bufferedReader().readText().trim(); if (p.waitFor() == 0) result else null }.getOrNull()
    private fun note(midi: Int) = "C${midi / 12 - 1}"
    private const val NOTES = "Dry mono 44.1 kHz PCM24. Same controls, velocity .8, seed 17, alignment 0 and six seconds in each trio. Glass glints and original Airy wash are byte-identical published references, regenerated for 24 pinned export-hash checks. AIRY_RESONANCE changes only the broad veil gain from .070 to .016, preserving modal/reflection excitation, RNG streams, geometry, pitch ratios, envelopes and study version. Raw source metrics precede melodic leveling; rawExportGain records PCM peak protection. Native uses shared melodic leveling. Matched uses AuditionLevel loudest 200 ms RMS .03 with .99 peak protection. Playback metrics use decoded WAVs. Body features describe a .3-second crop from .4 seconds, with the stock extractor spectrum on its first 4096 frames. Pitch is the strongest detector result in three windows and may be weak, absent or inharmonic; it is not a musical acceptance gate. Whole-file LOOP is a duration label. No new registered patch model, default change or sonic acceptance is implied."
    private val GOLDENS = mapOf(
        "light_bloom_48_glass_glints" to mapOf("raw" to "c95a68e630eeb0626c59721f2545b9437990dbc30d6ce04a2c09a9d49495a345", "native" to "ed8eec3bc22176880d007e665d52e34883358f85aaf79c8944122ca0506b97cc", "matched" to "bcba51322d0f44600ac35b9c3fd65f60de08304d1d536771aa710c6c894332c4"),
        "light_bloom_48_airy_wash" to mapOf("raw" to "18cb8613da44b7cddb42312f75edc7a99887cccf29973b709b0580bdf7db63aa", "native" to "f26be59869947dae111c31515dd30fc6feefd445ab1505b9abcdb974935c50ee", "matched" to "b3c31655ea4ce38442db5a1eb60f883c6b62ee5ea3e8b7e738094c1a34d3b808"),
        "light_bloom_60_glass_glints" to mapOf("raw" to "2e7d2fbf9109f2cd297802b4ddae555d71c9551c50445a788cec1e19a670435d", "native" to "0ad054db0fdd1c0ca95d830c173d2c1878cfad80ead7e977b8fd30c88c1cd374", "matched" to "c160c721b161f34efc0eb352ef5d5cd8b7c943444f5ab126875195a896db5431"),
        "light_bloom_60_airy_wash" to mapOf("raw" to "3d8dd7a88bee52ff2935afe8dcf887c8f9d78806f33e307f8327f6c92e35c826", "native" to "07615c933ebb1d59a9b4d32826f472ce67e4edddf587bf9066a3378dfedb6d67", "matched" to "004eb2ee172689dc662fb9be13bb853a15dcdbd7667efa3cef4e2114588c25be"),
        "light_shimmer_48_glass_glints" to mapOf("raw" to "72160825897757a80dcae0a58f1f6a6c83c179b716d8333c43600f84f0bfc2b4", "native" to "ea33de418744a4ead6e67bf4573b14b45cd9d02ed537a61a4d46cfbe71698157", "matched" to "de6203951d9b1c42dcbeeb4ec56c01022244d263d14332194e137390821462f2"),
        "light_shimmer_48_airy_wash" to mapOf("raw" to "ceb47287a989249862a64f9dc3ed84756abb39340e92bdf003c123afe60cacb8", "native" to "0d96c6eb92be0bd152b89a5c5e582917dc5f552a25d939b2790739760e60b327", "matched" to "1ae98aab9e4c247948fa00ae93cc7405a613884f7a031c316c9dc6af606413df"),
        "light_shimmer_60_glass_glints" to mapOf("raw" to "965b068e31405f90c8663956897f853867ad2c0f72df3c7dadd3e268ffd3d065", "native" to "a6f041770ae8395b5d4a50ea5822350cd6942cebdd11d752cfac122a61ca0579", "matched" to "d1b99db8fff93e1e68ba0594350f36bf8d9540aae35ac8058f71cc0f2c35479f"),
        "light_shimmer_60_airy_wash" to mapOf("raw" to "c09e14c2168cb2bce5098cce85e2eff1f989322ab552b60c84f9f6dc15b3a5c0", "native" to "f4d040328b1ae3b22374f4a17b7c1d3909aa3826f3badbb2bf59d32dec3727ed", "matched" to "4a8f575cf460dcd58dfe677e96ee655df58f209a55760bef997550afe4b6c3db"),
    )
}
