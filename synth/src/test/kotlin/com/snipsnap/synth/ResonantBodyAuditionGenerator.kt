package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.lang.management.ManagementFactory
import kotlin.math.*

/** Standalone proof; float WAVs retain raw headroom and exact replay source PCM. */
object ResonantBodyAuditionGenerator {
    private const val SCHEMA = 1
    private const val SEED = 0x13579
    private const val GENERATOR = "snipsnap-resonant-body-r1"
    private data class Source(val id: String, val audio: Snip, val provenance: String)
    private data class Case(val id: String, val section: String, val source: Source, val settings: BodySettings)

    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "output directory required" }
        val root = File(args[0])
        if (args.drop(1) == listOf("--verify")) { verify(root); return }
        require(args.size == 1 || (args.size == 3 && args[1] == "--capture")) { "use OUT [--capture WAV] or OUT --verify" }
        generate(root, args.getOrNull(2)?.let(::File))
    }

    fun generate(root: File, capture: File? = null) {
        require(!root.exists() || root.listFiles()?.isEmpty() == true) { "use a fresh output directory; existing files are preserved" }
        root.mkdirs()
        listOf("sources", "raw", "matched", "recipes", "diagnostics").forEach { File(root, it).mkdirs() }
        val rate = 44_100
        val reference = BodySettings().referenceHz
        fun tone(hz: Double, r: Int = rate, amplitude: Double = 0.35) = Snip(FloatArray(r) {
            (amplitude * sin(2 * PI * hz * it / r)).toFloat()
        }, 1, r)
        val impulse = Source("impulse", Snip(FloatArray(rate / 4).also { it[0] = 0.8f }, 1, rate), "generated: impulse 0.8; 250 ms buffer")
        var randomState = SEED
        val noise = FloatArray(rate / 4) { frame ->
            randomState = randomState * 1664525 + 1013904223
            val x = (randomState ushr 8) / 8388608.0 - 1
            if (frame < 882) (x * 0.5 * sin(PI * frame / 881).pow(2)).toFloat() else 0f
        }
        val burst = Source("burst", Snip(noise, 1, rate), "generated: LCG seed $SEED; 20 ms sine-squared noise envelope; scale 0.5")
        val harmonic = Source("harmonic", Snip(FloatArray(rate) { n ->
            (0.2 * (1..4).sumOf { h -> sin(2 * PI * reference * h * n / rate) / h } / (1..4).sumOf { 1.0 / it }).toFloat()
        }, 1, rate), "generated: four C3 harmonics, weights 1/n normalized then scaled 0.2; one second")
        val resonant = Source("resonant", tone(reference), "generated: C3 sine, amplitude 0.35; one second")
        val square = Source("square", Snip(FloatArray(rate) { if (sin(2 * PI * reference * it / rate) >= 0) 1f else -1f }, 1, rate),
            "generated: C3 bipolar square, amplitude 1; one second; guard stress")
        val silent = Source("silence", Snip(FloatArray(rate / 4), 1, rate), "generated: exact zero; 250 ms")
        val sources = mutableListOf(impulse, burst, harmonic, resonant, square, silent)
        val cases = mutableListOf<Case>()
        fun add(id: String, section: String, source: Source, s: BodySettings) { cases += Case(id, section, source, s) }
        for (src in listOf(burst, harmonic)) for ((i, body) in listOf(0f, 0.25f, 0.5f, 0.75f, 1f).withIndex())
            add("${src.id}-body-$i", "Body endpoints and blends", src, BodySettings(body = body, mix = 1f))
        for (tune in listOf(0f, 1f / 3f, 1f)) for ((i, body) in listOf(0f, 0.5f, 1f).withIndex()) {
            val s = BodySettings(tune = tune, body = body, ring = 1f, mix = 1f)
            add("root-${s.rootMidi}-$i", "Register", impulse, s)
        }
        for (tune in listOf(0f, 1f / 3f, 1f)) for (ring in listOf(0f, 0.5f, 1f)) for (damp in listOf(0f, 0.5f, 1f)) {
            val s = BodySettings(tune = tune, ring = ring, damp = damp, mix = 1f)
            add("pitch-${s.rootMidi}-$ring-$damp", "Pitch and confidence", impulse, s)
        }
        for (body in listOf(0f, 0.5f, 1f)) for (ring in listOf(0f, 1f)) for (damp in listOf(0f, 1f))
            add("corners-$body-$ring-$damp", "Ring and damp", burst, BodySettings(body = body, ring = ring, damp = damp, mix = 1f))
        for (src in listOf(burst, resonant)) for (mix in listOf(0f, 0.25f, 0.5f, 1f))
            add("${src.id}-mix-$mix", "Dry attack and mix", src, BodySettings(body = 0.5f, mix = mix))
        add("guard", "Maximum resonance", square, BodySettings(ring = 1f, damp = 0f, mix = 1f))
        add("maximum-tail", "Tail", burst, BodySettings(body = 0.5f, ring = 1f))
        add("silence", "Silence", silent, BodySettings(body = 0.5f, ring = 1f, mix = 1f))
        for (kind in listOf("left", "equal", "unequal")) {
            val stereo = Source("stereo-$kind", Snip(FloatArray(rate * 2) { i ->
                if (i % 2 == 0) resonant.audio.samples[i / 2] else when (kind) {
                    "left" -> 0f
                    "equal" -> resonant.audio.samples[i / 2]
                    else -> (0.2 * sin(2 * PI * reference * 1.5 * (i / 2) / rate)).toFloat()
                }
            }, 2, rate), "generated: $kind stereo C3 channels; no widening")
            sources += stereo
            add(stereo.id, "Channels", stereo, BodySettings(body = 0.5f))
        }
        val rate48 = Source("rate48", tone(reference, 48_000), "generated: C3 sine, amplitude 0.35; 48000 Hz; one second")
        sources += rate48
        add(rate48.id, "Sample rate", rate48, BodySettings(body = 0.5f, ring = 1f))
        if (capture != null) {
            require(capture.isFile && capture.length() <= 8_000_000) { "capture must be a local WAV no larger than 8 MB" }
            val src = Source("capture", WavReader.read(capture), "owner-supplied private capture; no redistribution grant; original SHA-256 ${sha(capture.readBytes())}")
            ResonantBody().process(src.audio)
            sources += src
            for ((i, body) in listOf(0f, 0.5f, 1f).withIndex()) add("capture-$i", "Owner capture", src, BodySettings(body = body))
        }
        sources.forEach { writeFloatWav(File(root, "sources/${it.id}.wav"), it.audio) }
        val processor = ResonantBody()
        val rows = mutableListOf<Map<String, Any?>>()
        for (case in cases) {
            val start = System.nanoTime()
            val result = processor.process(case.source.audio, case.settings, captureWet = true)
            val elapsed = (System.nanoTime() - start) / 1e6
            val d = result.diagnostics
            val raw = "raw/${case.id}.wav"
            val wet = result.rawWet?.let { "raw/${case.id}-wet.wav" }
            val matched = "matched/${case.id}.wav"
            writeFloatWav(File(root, raw), result.audio)
            if (wet != null) writeFloatWav(File(root, wet), result.rawWet!!)
            val level = Loudness.of(result.audio).toDouble()
            val matchedGain = if (level > 1e-12 && result.audio.peak() > 0) min(0.1 / level, 0.9 / result.audio.peak()) else 1.0
            WavWriter.write(File(root, matched), result.audio.copy(samples = result.audio.samples.map { (it * matchedGain).toFloat() }.toFloatArray()), allowNonMpcRate = true)
            val estimate = Pitch.detect(result.audio, fromSec = 0.002f)
            val metrics = linkedMapOf<String, Any?>("inputPeak" to if (d.activeModes == 0) null else d.inputPeak,
                "statePeak" to d.statePeak, "wetPeak" to d.wetPeak,
                "mixedPeak" to d.mixedPeak, "attenuation" to d.attenuation, "tailFrames" to d.tailFrames,
                "activeModes" to d.activeModes, "cacheHit" to d.coefficientCacheHit, "mean" to result.audio.samples.average(),
                "rms" to sqrt(result.audio.samples.sumOf { it.toDouble().pow(2) } / result.audio.samples.size),
                "samplePeak" to result.audio.peak(), "truePeak4x" to TruePeak.of(result.audio), "loudness" to level,
                "pitchHz" to estimate?.hz, "pitchConfidence" to estimate?.confidence,
                "centsFromReference" to estimate?.let { 1200 * log2(it.hz / d.referenceHz) },
                "renderMsDesktop" to elapsed, "matchedGain" to matchedGain,
                "classification" to Classifier.classify(result.audio).drumClass.name)
            writeJson(File(root, "diagnostics/${case.id}.json"), metrics)
            val recipe = linkedMapOf<String, Any?>("schema" to SCHEMA, "modelVersion" to case.settings.modelVersion,
                "coefficientVersion" to ResonantBody.COEFFICIENT_VERSION, "tableIdentity" to d.tableIdentity,
                "source" to "sources/${case.source.id}.wav", "sourceSha256" to sha(File(root, "sources/${case.source.id}.wav").readBytes()),
                "sourcePcmSha256" to pcmHash(case.source.audio), "rate" to case.source.audio.sampleRate, "channels" to case.source.audio.channels,
                "settings" to settingsJson(case.settings), "referenceHz" to d.referenceHz, "rootMidi" to d.rootMidi, "rootT60" to d.rootT60,
                "output" to raw, "outputSha256" to sha(File(root, raw).readBytes()), "outputPcmSha256" to pcmHash(result.audio),
                "outputFrames" to result.audio.frameCount, "wet" to wet, "wetPcmSha256" to result.rawWet?.let(::pcmHash),
                "matched" to matched, "matchedSha256" to sha(File(root, matched).readBytes()), "diagnostics" to metrics)
            writeJson(File(root, "recipes/${case.id}.json"), recipe)
            rows += linkedMapOf("id" to case.id, "section" to case.section, "source" to recipe.getValue("source"),
                "provenance" to case.source.provenance, "recipe" to "recipes/${case.id}.json", "raw" to raw,
                "wet" to wet, "matched" to matched, "settings" to settingsJson(case.settings), "metrics" to metrics)
        }
        val manifest = linkedMapOf("generator" to GENERATOR, "schema" to SCHEMA, "modelVersion" to ResonantBody.MODEL_VERSION,
            "gitCommit" to git("rev-parse", "HEAD"), "workingTreeDirty" to git("status", "--porcelain").isNotEmpty(),
            "implementationSha256" to implementationHash(), "runtime" to System.getProperty("java.runtime.version"),
            "heard" to false, "phoneMeasured" to false, "captureIncluded" to (capture != null), "cases" to rows)
        writeJson(File(root, "manifest.json"), manifest)
        val headers = listOf("id", "section", "samplePeak", "truePeak4x", "rms", "mean", "wetPeak", "statePeak", "attenuation", "pitchHz", "pitchConfidence", "centsFromReference", "renderMsDesktop", "matchedGain")
        File(root, "metrics.csv").writeText(headers.joinToString(",") + "\n" + rows.joinToString("\n") { row ->
            val m = row.getValue("metrics") as Map<*, *>
            headers.joinToString(",") { csv(row[it] ?: m[it]) }
        } + "\n")
        val template = requireNotNull(javaClass.getResourceAsStream("/audition/resonant-body-audition.html")) { "missing audition template" }.bufferedReader().use { it.readText() }
        File(root, "index.html").writeText(template.replace("__MANIFEST__", Json.write(json(manifest)).replace("<", "\\u003c")))
        desktopBenchmark(root)
        verify(root)
        println("Rendered ${rows.size} cases into ${root.absolutePath}; listening and Nothing Phone (3) measurements pending")
    }

    /** Replay saved PCM, refusing changed source bytes, format, schema, model or outputs. */
    fun verify(root: File) {
        val manifest = Json.parse(File(root, "manifest.json").readText()).obj()
        require(manifest.getValue("generator").str() == GENERATOR && manifest.getValue("schema").int() == SCHEMA) { "unsupported audition schema" }
        require(manifest.getValue("modelVersion").int() == ResonantBody.MODEL_VERSION) { "unsupported audition model" }
        require(manifest.getValue("cases").arr().isNotEmpty()) { "empty audition cannot validate a replay" }
        require(manifest.getValue("implementationSha256").str() == implementationHash()) { "audition implementation changed" }
        val processor = ResonantBody()
        for (row in manifest.getValue("cases").arr()) {
            val entry = row.obj()
            val recipe = Json.parse(safeFile(root, entry.getValue("recipe").str()).readText()).obj()
            require(recipe.getValue("schema").int() == SCHEMA) { "unsupported recipe schema" }
            require(recipe.getValue("coefficientVersion").int() == ResonantBody.COEFFICIENT_VERSION) { "unsupported coefficients" }
            val sourceFile = safeFile(root, recipe.getValue("source").str())
            require(sha(sourceFile.readBytes()) == recipe.getValue("sourceSha256").str()) { "source hash mismatch" }
            val source = WavReader.read(sourceFile)
            require(pcmHash(source) == recipe.getValue("sourcePcmSha256").str()) { "source PCM hash mismatch" }
            require(source.sampleRate == recipe.getValue("rate").int() && source.channels == recipe.getValue("channels").int()) { "source format mismatch" }
            val s = recipe.getValue("settings").obj()
            val settings = BodySettings(s.getValue("tune").num().toFloat(), s.getValue("body").num().toFloat(),
                s.getValue("ring").num().toFloat(), s.getValue("damp").num().toFloat(), s.getValue("mix").num().toFloat(),
                BodyFamily.valueOf(s.getValue("from").str()), BodyFamily.valueOf(s.getValue("to").str()), recipe.getValue("modelVersion").int())
            val result = processor.process(source, settings, captureWet = true)
            require(result.diagnostics.tableIdentity == recipe.getValue("tableIdentity").str()) { "table identity changed" }
            require(pcmHash(result.audio) == recipe.getValue("outputPcmSha256").str()) { "replayed PCM differs" }
            require(result.audio.frameCount == recipe.getValue("outputFrames").int()) { "replayed length differs" }
            val output = safeFile(root, recipe.getValue("output").str())
            require(sha(output.readBytes()) == recipe.getValue("outputSha256").str()) { "output file changed" }
            require(pcmHash(WavReader.read(output)) == pcmHash(result.audio)) { "output WAV differs" }
            if (recipe.getValue("wet") != JsonValue.Null) {
                val wet = requireNotNull(result.rawWet)
                require(pcmHash(wet) == recipe.getValue("wetPcmSha256").str()) { "wet replay differs" }
                require(pcmHash(WavReader.read(safeFile(root, recipe.getValue("wet").str()))) == pcmHash(wet)) { "wet file differs" }
            }
            require(sha(safeFile(root, recipe.getValue("matched").str()).readBytes()) == recipe.getValue("matchedSha256").str()) { "matched file changed" }
        }
        println("Verified ${manifest.getValue("cases").arr().size} exact PCM replays and file hashes")
    }

    private fun desktopBenchmark(root: File) {
        val processor = ResonantBody()
        val source = Snip(FloatArray(44_100) { (0.35 * sin(2 * PI * 130.8128 * it / 44_100)).toFloat() }, 1, 44_100)
        val settings = BodySettings(body = 0.5f, ring = 1f)
        var checksum = 0.0
        repeat(20) { checksum += processor.process(source, settings).audio.samples[1000] }
        fun timed(cold: Boolean): Double {
            if (cold) processor.clearCoefficientCache()
            val start = System.nanoTime()
            val out = processor.process(source, settings)
            val elapsed = (System.nanoTime() - start) / 1e6
            checksum += out.audio.samples[1000]
            return elapsed
        }
        val warm = List(100) { timed(false) }
        val cold = List(20) { timed(true) }
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        val allocation = if (bean != null && bean.isThreadAllocatedMemorySupported) {
            bean.isThreadAllocatedMemoryEnabled = true
            val threadId = Thread.currentThread().id
            val before = bean.getThreadAllocatedBytes(threadId)
            repeat(20) { checksum += processor.process(source, settings).audio.samples[1000] }
            (bean.getThreadAllocatedBytes(threadId) - before) / 20.0
        } else null
        val sorted = warm.sorted()
        writeJson(File(root, "benchmark-desktop.json"), linkedMapOf("platform" to "desktop JVM, not Nothing Phone (3)",
            "runtime" to System.getProperty("java.runtime.version"), "os" to System.getProperty("os.name"),
            "warmMs" to warm, "warmMedianMs" to sorted[49], "warmP95Ms" to sorted[94], "warmMaxMs" to sorted.last(),
            "clearedCoefficientCacheMs" to cold, "allocatedBytesPerCall" to allocation,
            "allocationMethod" to "HotSpot ThreadMXBean allocated bytes on the single processing thread; separate batch",
            "outputFrames" to 70_560, "activeModes" to 8, "sourceRate" to 44_100, "sourceFrames" to 44_100,
            "channels" to 1, "checksum" to checksum, "phoneGatePassed" to false))
    }

    internal fun writeFloatWav(file: File, source: Snip) {
        val bytes = ByteBuffer.allocate(44 + source.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray()).putInt(16)
        bytes.putShort(3).putShort(source.channels.toShort()).putInt(source.sampleRate)
        bytes.putInt(source.sampleRate * source.channels * 4).putShort((source.channels * 4).toShort()).putShort(32)
        bytes.put("data".toByteArray()).putInt(source.samples.size * 4)
        source.samples.forEach { bytes.putFloat(it) }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes.array())
    }
    private fun settingsJson(s: BodySettings) = linkedMapOf("tune" to s.tune, "body" to s.body, "ring" to s.ring,
        "damp" to s.damp, "mix" to s.mix, "from" to s.from.name, "to" to s.to.name)
    private fun safeFile(root: File, relative: String): File {
        val file = File(root, relative).canonicalFile
        require(file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile) { "missing or escaping audition file: $relative" }
        return file
    }
    private fun implementationHash(): String {
        val root = if (File("audio/src/main").exists()) File(".") else File("..")
        val files = listOf("audio/src/main/kotlin/com/snipsnap/audio/ResonantBody.kt", "audio/src/main/kotlin/com/snipsnap/audio/ResonantBodyModels.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/ResonantBodyAuditionGenerator.kt", "synth/src/test/resources/audition/resonant-body-audition.html")
        return sha(files.joinToString("\n") { "$it:${sha(File(root, it).readBytes())}" }.toByteArray())
    }
    private fun git(vararg args: String): String = ProcessBuilder(listOf("git") + args).redirectErrorStream(true)
        .start().let { p -> val output = p.inputStream.bufferedReader().readText().trim(); check(p.waitFor() == 0); output }
    private fun pcmHash(s: Snip): String {
        val bytes = ByteBuffer.allocate(8 + s.samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(s.sampleRate).putInt(s.channels)
        s.samples.forEach { bytes.putInt(it.toRawBits()) }
        return sha(bytes.array())
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun csv(v: Any?): String = "\"${v?.toString()?.replace("\"", "\"\"") ?: ""}\""
    private fun writeJson(file: File, v: Any?) = file.writeText(Json.write(json(v)) + "\n")
    private fun json(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(v)
        is Boolean -> JsonValue.Bool(v)
        is Number -> JsonValue.Num(v.toDouble())
        is Map<*, *> -> JsonValue.Obj(v.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map(::json))
        else -> error("unsupported JSON type ${v.javaClass}")
    }
}
