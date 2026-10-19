package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Export geometry from the actual Kotlin preparer rather than independently approximating its RNG. */
object FacetKeyboardGeometryGenerator {
    private const val SEED = 17
    private const val RATE = 176_400
    private val directions = FacetLightDirection.entries

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && !args[0].startsWith("--")) { "usage: FacetKeyboardGeometryGenerator audition-directory" }
        val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val sources = sourceHashes(repository)
        val voices = FacetVoice.entries.associate { voice ->
            val macros = Facet.defaults(voice)
            val noiseSeed = Dsp.seedFor("FACET-LIGHT", FacetLightStudy.STUDY_VERSION, voice, SEED)
            val seeds = (0..16).map { Dsp.seedFor(noiseSeed, it) }
            voice.name to mapOf("macros" to macros, "geometrySeed" to Dsp.seedFor("FACET", 1, voice, SEED),
                "noiseSeed" to noiseSeed, "noiseSeeds" to seeds,
                "noiseFirst16" to seeds.map { seed -> Dsp.Noise(seed).let { noise -> List(16) { noise.next() } } },
                "rotorHz" to rotation(macros.getValue("REVOLVE"), voice),
                "levels" to (0..4).map { depth ->
                    val geometry = FacetLightStudy.performanceGeometry(voice, depth, SEED)
                    require(geometry.routes.size == 8 && geometry.routes.all { it.size == 2048 && it.all { v -> v.isFinite() && v >= 0f } })
                    require(geometry.events.size == geometry.facets * 4)
                    require(geometry.events.all { it.phase in 0.0..1.0 && it.group in 0..7 && it.amplitude.isFinite() && it.amplitude >= 0 && it.polarity in listOf(-1.0, 1.0) })
                    mapOf("depth" to depth, "facets" to geometry.facets, "routes" to geometry.routes,
                        "events" to geometry.events.map { mapOf("phase" to it.phase, "group" to it.group, "amplitude" to it.amplitude, "polarity" to it.polarity) })
                })
        }
        val references = FacetVoice.entries.flatMap { voice -> directions.flatMap { direction -> listOf(48, 60).map { midi ->
            reference(voice, direction, midi)
        } } }
        val model = mapOf("glassRatios" to FacetLightStudy.performanceRatios(FacetLightDirection.GLASS_GLINTS),
            "airRatios" to FacetLightStudy.performanceRatios(FacetLightDirection.AIRY_RESONANCE),
            "veilGain" to mapOf("GLASS_GLINTS" to .012, "AIRY_WASH" to .070, "AIRY_RESONANCE" to .016),
            "revolvePositions" to listOf(0f, .1f, .25f, .5f, .7f, .85f, 1f),
            "revolveRates" to mapOf("BLOOM" to listOf(0f, .025f, .1f, .5f, 2f, 8f, 24f),
                "SHIMMER" to listOf(0f, .025f, .1f, .5f, 2f, 8f, 40f)),
            "rootGain" to .004, "rootPhase" to .4, "referenceCoefficients" to references)
        val artifact = mapOf("schemaVersion" to 1, "format" to "snipsnap.facet-keyboard-geometry",
            "generatedAt" to Instant.now().toString(), "studyVersion" to FacetLightStudy.STUDY_VERSION,
            "seed" to SEED, "sampleRate" to Dsp.RATE, "tableSize" to 2048, "groups" to 8, "modes" to 16,
            "voices" to voices, "model" to model,
            "provenance" to mapOf("repository" to "https://github.com/BertCalm/SnipSnap", "revision" to git(repository, "rev-parse", "HEAD"),
                "workingTreeDirty" to (git(repository, "status", "--porcelain")?.isNotBlank() ?: true),
                "sourceSha256" to sources, "runtimeClassSha256" to runtimeHashes()),
            "notes" to "Routes and phase-sorted events are copied directly from FacetLightStudy.prepare for each of its five hierarchical facet levels. ILLUMINATE, ELEVATION and CHROMA use the recorded voice defaults; continuous FACETS interpolates adjacent route tables and amplitude-weighted event lists with the renderer's energy budget. REVOLVE uses the recorded piecewise mapping. Noise channels use the exact Kotlin LCG seeds and Float arithmetic; noiseFirst16 is a port reference. Reference coefficients reproduce six-second C3/C4 study preparation at 4x, not a seamless HOLD claim. The browser performance envelope and final output conditioning may differ; reference WAVs remain the finite Kotlin render authority.")
        require(sourceHashes(repository) == sources) { "Sources changed during geometry export" }
        val json = compactJson(artifact)
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size < 3 * 1024 * 1024) { "Geometry exceeds 3 MiB: ${bytes.size}" }
        val out = File(File(args[0]).canonicalFile, "keyboard/geometry.json")
        out.parentFile.mkdirs(); out.writeBytes(bytes)
        val report = mapOf("schemaVersion" to 1, "format" to "snipsnap.facet-keyboard-geometry-report",
            "generatedAt" to artifact.getValue("generatedAt"), "geometry" to "keyboard/geometry.json",
            "geometrySha256" to hash(out), "geometryBytes" to bytes.size, "voiceCount" to 2,
            "levelsPerVoice" to 5, "routeCoefficientCount" to 163840, "eventCount" to 1984,
            "noiseChannelsPerVoice" to 17, "noiseReferenceSamplesPerChannel" to 16,
            "referenceCoefficientCases" to references.size, "allFinite" to true,
            "provenance" to artifact.getValue("provenance"))
        File(out.parentFile, "geometry-report.json").writeText(compactJson(report) + "\n")
        println("FACET keyboard geometry: 2 voices × 5 levels, 163840 coefficients, 1984 events, ${bytes.size} bytes, ${hash(out)}, $out")
    }

    /** Independent reference values mirror the existing probe formulas; probe arithmetic is unchanged. */
    private fun reference(voice: FacetVoice, direction: FacetLightDirection, midi: Int): Map<String, Any?> {
        val macros = Facet.defaults(voice)
        val density = macros.getValue("FACETS").toDouble()
        val rotor = rotation(macros.getValue("REVOLVE"), voice)
        val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
        val airy = direction != FacetLightDirection.GLASS_GLINTS
        val ratios = FacetLightStudy.performanceRatios(direction)
        val real = mutableListOf<Double>(); val imag = mutableListOf<Double>()
        val injection = mutableListOf<Double>(); val levels = mutableListOf<Double>()
        for (j in 0 until 16) {
            val band = j / 15.0
            val t60 = if (airy) (1.9 - .85 * band) * (1.15 - .35 * density) else (.60 - .27 * band) * (1.25 - .65 * density)
            val decayRate = ln(1000.0) / t60
            val radius = exp(-decayRate / RATE)
            val hz = frequency * ratios[j]
            real += radius * cos(2.0 * PI * hz / RATE)
            imag += radius * kotlin.math.sin(2.0 * PI * hz / RATE)
            injection += sqrt(4.0 * decayRate * Dsp.RATE) / RATE
            levels += if (hz >= 13_000.0) 0.0 else (.45 + .55 * band) / sqrt(16.0) * min(1.0, (13_000.0 - hz) / 2000.0)
        }
        val attackSeconds = if (airy) .035 - .014 * density else .018 - .010 * density
        val burstSeconds = if (airy) .12 - .045 * density else .050 - .025 * density
        val a = Dsp.Biquad().apply { bandpass(if (airy) 4100f else 4800f, if (airy) .75f else 1.15f, RATE) }
        val b = Dsp.Biquad().apply { bandpass(if (airy) 4100f else 4800f, if (airy) .75f else 1.15f, RATE) }
        return mapOf("voice" to voice.name, "direction" to direction.name, "midi" to midi, "rate" to RATE,
            "density" to density, "rotorHz" to rotor, "poleReal" to real, "poleImag" to imag, "injection" to injection,
            "modeLevel" to levels, "attackSeconds" to attackSeconds, "burstSeconds" to burstSeconds,
            "fastPole" to exp(-1.0 / (attackSeconds * RATE)), "slowPole" to exp(-1.0 / (burstSeconds * RATE)),
            "packetBudget" to (if (airy) .40 else .85) * sqrt(.25 / (.18 + rotor)) * sqrt(.05 / burstSeconds),
            "filterImpulse" to List(32) { i -> b.process(a.process(if (i == 0) 1f else 0f)) })
    }

    private fun rotation(value: Float, voice: FacetVoice): Float {
        if (value == 0f) return 0f
        val positions = floatArrayOf(0f, .1f, .25f, .5f, .7f, .85f, 1f)
        val rates = floatArrayOf(0f, .025f, .1f, .5f, 2f, 8f, if (voice == FacetVoice.BLOOM) 24f else 40f)
        val j = (0 until positions.lastIndex).firstOrNull { value <= positions[it + 1] } ?: positions.lastIndex - 1
        val x = (value - positions[j]) / (positions[j + 1] - positions[j])
        return if (j == 0) rates[1] * x else (rates[j] * (rates[j + 1] / rates[j]).toDouble().pow(x.toDouble())).toFloat()
    }
    private fun sourceHashes(root: File) = (listOf("FacetLightStudy.kt", "Facet.kt", "Dsp.kt", "Tide.kt", "Thump.kt").map {
        File(root, "synth/src/main/kotlin/com/snipsnap/synth/$it") } + File(root, "synth/src/test/kotlin/com/snipsnap/synth/FacetKeyboardGeometryGenerator.kt"))
        .plus(File(root, "json/src/main/kotlin/com/snipsnap/json/Json.kt"))
        .associate { it.relativeTo(root).invariantSeparatorsPath to hash(it) }
    private fun runtimeHashes(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        fun included(name: String) = name.endsWith(".class") && listOf("Facet", "Dsp", "Thump", "MacroSpec")
            .any { name.startsWith("com/snipsnap/synth/$it") }
        for (root in listOf(FacetLightStudy::class.java, javaClass).map { File(it.protectionDomain.codeSource.location.toURI()) }.distinct()) {
            if (root.isDirectory) root.walkTopDown().filter { it.isFile }.forEach { file ->
                val name = file.relativeTo(root).invariantSeparatorsPath; if (included(name)) out[name] = hash(file)
            } else java.util.jar.JarFile(root).use { jar -> for (entry in jar.entries().asSequence().filter { !it.isDirectory && included(it.name) }) {
                out[entry.name] = jar.getInputStream(entry).use { digest(it.readBytes()) }
            } }
        }
        return out.toSortedMap()
    }
    /** Float.toString keeps the exact Float round-trip without bloating routes into Double decimals. */
    private fun compactJson(value: Any?): String = buildString { appendJson(value) }
    private fun StringBuilder.appendJson(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> append(Json.write(JsonValue.Str(value)))
            is Number -> { require(value.toDouble().isFinite()); append(value.toString()) }
            is Boolean -> append(value)
            is Map<*, *> -> { append('{'); value.entries.forEachIndexed { i, entry ->
                if (i > 0) append(','); appendJson(entry.key.toString()); append(':'); appendJson(entry.value)
            }; append('}') }
            is Iterable<*> -> { append('['); value.forEachIndexed { i, v -> if (i > 0) append(','); appendJson(v) }; append(']') }
            else -> error("Unsupported JSON ${value::class}")
        }
    }
    private fun hash(file: File) = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }; hex(digest.digest()) }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun git(root: File, vararg args: String): String? = runCatching {
        val p = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val result = p.inputStream.bufferedReader().readText().trim(); if (p.waitFor() == 0) result else null }.getOrNull()
}
