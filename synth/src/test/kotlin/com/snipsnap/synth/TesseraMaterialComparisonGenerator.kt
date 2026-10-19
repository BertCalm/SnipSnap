package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.sqrt

/** Twelve controlled comparisons. Published WAVs and traces are copied without processing. */
object TesseraMaterialComparisonGenerator {
    private const val TARGET = .12f
    private const val CEILING = .90f
    private val sourcePaths = listOf(
        "synth/src/main/kotlin/com/snipsnap/synth/Tessera.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/TesseraTest.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/TesseraMaterialComparisonGenerator.kt",
        "synth/src/test/resources/audition/tessera-material-comparison.html",
        "synth/build.gradle.kts",
    )
    private data class Case(val id: String, val section: String, val title: String, val description: String)
    private val cases = listOf(
        Case("answer_material_000", "endpoints", "MATERIAL 0 · C4", "ANSWER with wood at the material endpoint. Every companion control remains at the published neutral value."),
        Case("answer_material_050", "endpoints", "MATERIAL 0.5 · C4", "ANSWER at the paired-string midpoint, with the same neutral companion controls as the adjacent material endpoints."),
        Case("answer_material_100", "endpoints", "MATERIAL 1 · C4", "ANSWER with metal at the material endpoint. Compare attack, upper resonance and decay at the same requested note."),
        Case("wood_low", "registers", "WOOD · C3", "Published WOOD controls at the lowest supported note; compare the proposed material response at the exact same settings."),
        Case("wood_middle", "registers", "WOOD · C4", "Published WOOD controls in the middle register. The chamber and shared frame remain connected."),
        Case("wood_high", "registers", "WOOD · C5", "Published WOOD controls at the highest supported note; compare articulation and the pitched body."),
        Case("tube_low", "registers", "TUBE · C3", "Published TUBE controls at the lowest supported note; compare the direct strike and its longer upper resonance."),
        Case("tube_middle", "registers", "TUBE · C4", "Published TUBE controls in the middle register. The chamber and shared frame remain connected."),
        Case("tube_high", "registers", "TUBE · C5", "Published TUBE controls at the highest supported note; compare the root and metallic upper modes."),
        Case("diagnostic_isolated_wood", "isolated", "Isolated wood · C4", "Only wood receives the hammer. Frame transfer, chamber paths, receiving ports, collectors and wall movement are disabled in both versions."),
        Case("diagnostic_isolated_course", "isolated", "Isolated paired strings · C4", "Only paired strings receive the hammer. The exact published isolation switches remain in effect."),
        Case("diagnostic_isolated_tube", "isolated", "Isolated metal · C4", "Only metal receives the hammer. The exact published isolation switches remain in effect."),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val positional = args.filterNot { it.startsWith("--") }
        require(positional.size <= 1 && args.filter { it.startsWith("--") }.all {
            it.startsWith("--baseline-dir=") || it.startsWith("--baseline-revision=") || it.startsWith("--source-dir=")
        }) { "supply one output directory, --baseline-dir=, and optionally --baseline-revision= and --source-dir=" }
        fun option(prefix: String) = args.filter { it.startsWith(prefix) }.let {
            require(it.size <= 1) { "duplicate $prefix" }; it.singleOrNull()?.removePrefix(prefix)?.also { value -> require(value.isNotBlank()) }
        }
        val source = File(option("--source-dir=") ?: "..").canonicalFile
        val baseline = File(option("--baseline-dir=") ?: error("set -PtesseraComparisonBaseline=/absolute/path/to/published-audition")).canonicalFile
        val output = File(positional.firstOrNull() ?: "../testkit/tessera-material-comparison").canonicalFile
        require(!output.toPath().startsWith(baseline.toPath()) && !baseline.toPath().startsWith(output.toPath())) { "comparison output and published baseline must be separate directories" }
        val baselineManifest = File(baseline, "manifest.json")
        val original = Json.parse(baselineManifest.readText()).obj()
        require(original.getValue("engine").str() == "TESSERA")
        require(kotlin.math.abs(original.getValue("listeningTarget").num() - TARGET) < 1e-6 &&
            kotlin.math.abs(original.getValue("listeningPeakCeiling").num() - CEILING) < 1e-6) { "baseline must use RMS 0.12 and peak ceiling 0.90" }
        val baselineRows = original.getValue("clips").arr().associate { it.obj().getValue("id").str() to it.obj() }
        val publishedProvenance = File(baseline, "downloads/provenance.json").takeIf { it.isFile }
        val declared = publishedProvenance?.let { Json.parse(it.readText()).obj() }
        val declaredCommit = declared?.get("sourceCommit")?.str()
        val baselineCommit = option("--baseline-revision=") ?: declaredCommit
            ?: error("baseline has no downloads/provenance.json sourceCommit; supply -PtesseraComparisonBaselineRevision=<full Git commit>")
        require(baselineCommit.matches(Regex("[0-9a-f]{40}"))) { "baseline revision must be a full Git commit" }
        require(declaredCommit == null || declaredCommit == baselineCommit) { "baseline revision disagrees with published provenance" }
        val declaredAudition = declared?.get("audition")?.obj()
        require(declaredAudition?.get("manifestSha256")?.str()?.let { it == sha(baselineManifest) } != false) { "published baseline manifest hash disagrees with its provenance" }
        val baselineSources = listOf("synth/src/main/kotlin/com/snipsnap/synth/Tessera.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/TesseraAuditionGenerator.kt", "synth/src/test/resources/audition/tessera-audition.html")
            .associateWith { path -> sha(git(source, "show", "$baselineCommit:$path")) }
        declaredAudition?.get("sourceSha256")?.obj()?.forEach { (path, hash) ->
            require(baselineSources[path] == hash.str()) { "published source hash disagrees with $baselineCommit:$path" }
        }
        val candidateCommit = gitText(source, "rev-parse", "HEAD")
        require(candidateCommit.matches(Regex("[0-9a-f]{40}")))
        val initialHashes = sourcePaths.associateWith { sha(File(source, it)) }
        val candidateSources = sourcePaths.associateWith { path ->
            val committedHash = sha(git(source, "show", "HEAD:$path"))
            require(initialHashes.getValue(path) == committedHash) { "commit candidate source before rendering: $path" }
            linkedMapOf("sha256" to initialHashes.getValue(path), "gitCommitSha256" to committedHash, "matchesCommit" to true)
        }
        val initialDirty = gitText(source, "status", "--porcelain=v1", "--untracked-files=all").isNotBlank()
        val classes = compiledClassHashes()
        output.mkdirs()
        val pairs = mutableListOf<Map<String, Any?>>()
        var renderNanos = 0L
        for (case in cases) {
            val row = baselineRows[case.id] ?: error("published baseline is missing ${case.id}")
            require(row.getValue("branch").str() == "full" && row.getValue("level").str() == "matched" && !row.getValue("loop").bool())
            val voice = TesseraVoice.valueOf(row.getValue("voice").str())
            val macros = row.getValue("macros").obj().mapValues { it.value.num().toFloat() }
            require(macros.keys == Tessera.defaults(voice).keys && macros.getValue("HOLD") < Tessera.LOOP_THRESHOLD)
            val velocity = row.getValue("velocity").num().toFloat()
            val controls = row.getValue("options").obj()
            fun enabled(key: String) = controls.getValue(key).bool()
            fun nullableNumber(key: String) = controls[key]?.takeIf { it != JsonValue.Null }?.num()
            require(!enabled("normalize")) { "comparison requires the baseline's raw probe before listening gain" }
            val options = Tessera.ProbeOptions(normalize = false, chamberEnabled = enabled("chamberEnabled"),
                receiversEnabled = enabled("receiversEnabled"), collectorsEnabled = enabled("collectorsEnabled"),
                primaryStrikeEnabled = enabled("primaryStrikeEnabled"), wallMotionEnabled = enabled("wallMotionEnabled"),
                frameCouplingEnabled = enabled("frameCouplingEnabled"), isolatedMaterial = nullableNumber("isolatedMaterial")?.toInt(),
                durationSeconds = nullableNumber("durationSeconds")?.toFloat(), seedContext = controls.getValue("seedContext").int(), recordDiagnostics = true)
            val oldWav = safeAsset(baseline, row.getValue("path").str())
            val oldTrace = safeAsset(baseline, row.getValue("eventPath").str())
            require(sha(oldWav) == row.getValue("audioSha256").str() && sha(oldTrace) == row.getValue("eventSha256").str()) { "baseline asset hash failed for ${case.id}" }
            val oldAudio = WavReader.read(oldWav)
            require(oldAudio.channels == 1 && oldAudio.sampleRate == Tessera.RATE && oldAudio.frameCount == 6 * Tessera.RATE)
            val beforeWav = "clips/before_${case.id}.wav"
            val beforeTrace = "events/before_${case.id}.json"
            copy(oldWav, File(output, beforeWav)); copy(oldTrace, File(output, beforeTrace))
            require(sha(File(output, beforeWav)) == sha(oldWav) && sha(File(output, beforeTrace)) == sha(oldTrace))
            val before = linkedMapOf<String, Any?>("version" to "before", "path" to beforeWav, "eventPath" to beforeTrace,
                "audioSha256" to sha(oldWav), "eventSha256" to sha(oldTrace), "sourceClipId" to case.id,
                "metrics" to metrics(oldAudio), "originalEvidence" to fromJson(JsonValue.Obj(row)))
            val started = System.nanoTime()
            val probe = Tessera.probe(voice, macros, velocity, options)
            val nanos = System.nanoTime() - started; renderNanos += nanos
            require(probe.recoveredStates == 0 && probe.samples.size == oldAudio.frameCount && probe.samples.all { it.isFinite() }) { "invalid candidate ${case.id}" }
            require(listOf(probe.primaryWork, probe.primaryWorkBudget, probe.wallWork, probe.wallWorkBudget,
                probe.initialCollectorBudget, probe.remainingCollectorBudget).all { it.isFinite() && it >= 0 })
            require(probe.primaryWork <= probe.primaryWorkBudget + 1e-9 && probe.wallWork <= probe.wallWorkBudget + 1e-9)
            require(probe.remainingCollectorBudget <= probe.initialCollectorBudget + 1e-9 && probe.collectorEvents.size <= Tessera.MAX_COLLECTOR_RELEASES)
            require(probe.collectorEvents.sumOf { it.spentEnergy } <= probe.initialCollectorBudget + 1e-7)
            val raw = Snip(probe.samples, 1, Tessera.RATE)
            val listened = Snip(probe.samples.copyOf(), 1, Tessera.RATE)
            Dsp.levelTo(listened.samples, Tessera.RATE, target = TARGET, ceiling = CEILING, channels = 1)
            require(listened.peak() <= CEILING + 1e-6f)
            val afterWav = "clips/after_${case.id}.wav"
            WavWriter.write(File(output, afterWav), listened, WavWriter.BitDepth.PCM_16)
            val proof = linkedMapOf<String, Any?>("recoveredStates" to probe.recoveredStates,
                "primaryWork" to probe.primaryWork, "primaryWorkBudget" to probe.primaryWorkBudget,
                "wallWork" to probe.wallWork, "wallWorkBudget" to probe.wallWorkBudget,
                "initialCollectorBudget" to probe.initialCollectorBudget, "remainingCollectorBudget" to probe.remainingCollectorBudget,
                "collectorSpentEnergy" to probe.collectorEvents.sumOf { it.spentEnergy },
                "finalPassiveEnergy" to probe.finalPassiveEnergy, "maxPassiveEnergy" to probe.maxPassiveEnergy,
                "directMaterialRms" to probe.directMaterials.map(::rms), "reexcitedMaterialRms" to probe.reexcitedMaterials.map(::rms),
                "continuousReceivingForceRms" to probe.continuousReturns.map(::rms), "chamberRms" to rms(probe.chamber), "frameRms" to rms(probe.frame))
            val events = linkedMapOf<String, Any?>("id" to "after_${case.id}", "baselineClipId" to case.id,
                "sourceCommit" to candidateCommit, "voice" to voice.name, "macros" to macros, "velocity" to velocity,
                "options" to fromJson(JsonValue.Obj(controls)), "probeEnergyProof" to proof,
                "hammerContacts" to probe.hammerContacts.map { linkedMapOf("timeSeconds" to it.timeSeconds, "rebound" to it.rebound, "reservedEnergy" to it.reservedEnergy, "durationSeconds" to it.durationSeconds) },
                "arrivals" to probe.arrivals.map { linkedMapOf("timeSeconds" to it.timeSeconds, "path" to it.path, "material" to it.material, "strength" to it.strength, "delaySeconds" to it.delaySeconds) },
                "collectorEvents" to probe.collectorEvents.map { linkedMapOf("timeSeconds" to it.timeSeconds, "material" to it.material, "strength" to it.strength, "spentEnergy" to it.spentEnergy, "remainingBudget" to it.remainingBudget) },
                "geometry" to probe.geometry.map { linkedMapOf("timeSeconds" to it.timeSeconds, "size" to it.size, "fold" to it.fold, "velocity" to it.velocity, "wallWork" to it.wallWork, "passiveEnergy" to it.passiveEnergy, "collectorEnergy" to it.collectorEnergy, "releaseCount" to it.releaseCount) })
            val afterTrace = "events/after_${case.id}.json"
            writeJson(File(output, afterTrace), events)
            val exported = WavReader.read(File(output, afterWav))
            val after = linkedMapOf<String, Any?>("version" to "after", "path" to afterWav, "eventPath" to afterTrace,
                "audioSha256" to sha(File(output, afterWav)), "eventSha256" to sha(File(output, afterTrace)),
                "metrics" to metrics(exported), "rawMetrics" to metrics(raw), "prePcmListeningMetrics" to metrics(listened),
                "listeningGain" to Loudness.of(listened) / Loudness.of(raw).coerceAtLeast(1e-9f),
                "renderMilliseconds" to nanos / 1_000_000.0, "probeEnergyProof" to proof)
            pairs += linkedMapOf("id" to case.id, "section" to case.section, "title" to case.title,
                "description" to case.description, "voice" to voice.name, "macros" to macros, "velocity" to velocity,
                "options" to fromJson(JsonValue.Obj(controls)), "before" to before, "after" to after)
            println("rendered material comparison ${pairs.size}/${cases.size}: ${case.id}")
        }
        require(gitText(source, "rev-parse", "HEAD") == candidateCommit && sourcePaths.all { sha(File(source, it)) == initialHashes.getValue(it) }) { "candidate source changed while rendering" }
        require(compiledClassHashes() == classes) { "compiled Tessera classes changed while rendering" }
        val manifest = linkedMapOf<String, Any?>("engine" to "TESSERA", "formatVersion" to 1,
            "description" to "Proposed material revision; owner listening acceptance pending. Hardware downloads remain the published baseline.",
            "generatedUtc" to Instant.now().toString(), "pairCount" to pairs.size, "clipCount" to 2 * pairs.size,
            "audioStartsOff" to true, "sampleRate" to Tessera.RATE, "channels" to 1, "pcmBits" to 16,
            "durationSeconds" to 6, "hold" to false, "listeningTarget" to TARGET, "listeningPeakCeiling" to CEILING,
            "baselineCopyPolicy" to "WAVs and event JSON are copied byte-for-byte, without resampling or gain changes.",
            "materialOrder" to listOf("wood", "paired strings", "metal"),
            "baseline" to linkedMapOf("sourceCommit" to baselineCommit, "manifestSha256" to sha(baselineManifest),
                "sourceSha256" to baselineSources, "publishedProvenanceSha256" to publishedProvenance?.let(::sha)),
            "candidate" to linkedMapOf("sourceCommit" to candidateCommit, "gitDirtyAtStart" to initialDirty,
                "gitDirtyAtEnd" to gitText(source, "status", "--porcelain=v1", "--untracked-files=all").isNotBlank(),
                "sourceFiles" to candidateSources, "compiledClassSha256" to classes),
            "renderMilliseconds" to renderNanos / 1_000_000.0, "pairs" to pairs)
        val manifestText = Json.write(json(manifest)) + "\n"
        File(output, "manifest.json").writeText(manifestText)
        val template = javaClass.getResourceAsStream("/audition/tessera-material-comparison.html")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("material comparison template is missing")
        require(template.contains("<!-- TESSERA_MATERIAL_CARDS -->") && template.contains("<!-- TESSERA_MATERIAL_MANIFEST -->"))
        File(output, "index.html").writeText(template.replace("<!-- TESSERA_MATERIAL_CARDS -->", cards(pairs))
            .replace("<!-- TESSERA_MATERIAL_MANIFEST -->", "<script type=\"application/json\" id=\"tessera-material-manifest\">${manifestText.replace("<", "\\u003c")}</script>"))
        File(output, "README.md").writeText("""
            # Tessera proposed material comparison

            Twelve before/after pairs, 24 dry mono PCM16 clips at 44.1 kHz and six seconds each.
            The published baseline WAVs and traces are copied byte-for-byte. Candidate renders
            use the exact same voice, macros, velocity and diagnostic switches, then the established
            loudest-200-ms RMS 0.12 target with a 0.90 peak ceiling. No HOLD or effects are included.

            Start with MATERIAL 0 / 0.5 / 1 at C4. WOOD and TUBE at C3/C4/C5 and the three isolated
            C4 material sources provide supporting comparisons. Audio starts off. Enable playback,
            choose Play, and use Pause/Resume or Stop all/Escape; only one clip plays at a time.
            Native controls also work without JavaScript. No external assets are required.

            Baseline source: $baselineCommit. Candidate source: $candidateCommit.
            The manifest includes source and compiled-class hashes, actual Git dirty flags,
            before/after audio and trace hashes, measured levels and raw probe energy accounting.
            Each candidate trace records hammer/rebound contacts, arrivals, collectors and geometry.

            This is a proposed revision. Owner listening acceptance remains pending.
            Hardware downloads remain the published baseline.
        """.trimIndent() + "\n")
        println("wrote ${pairs.size} pairs / ${pairs.size * 2} clips under ${output.absolutePath}; engine render ${renderNanos / 1e9} s")
    }

    private fun cards(pairs: List<Map<String, Any?>>): String = buildString {
        for ((section, title) in linkedMapOf("endpoints" to "Material endpoints and midpoint · C4", "registers" to "WOOD and TUBE · three registers", "isolated" to "Isolated material sources · C4")) {
            append("<section class=\"comparison-section\" id=\"$section\"><h2>${h(title)}</h2>")
            for (pair in pairs.filter { it["section"] == section }) {
                val id = pair.getValue("id").toString()
                val macros = (pair.getValue("macros") as Map<*, *>).entries.joinToString(" · ") { "${it.key} ${number((it.value as Number).toDouble())}" }
                append("<article class=\"pair\" id=\"pair-$id\"><h3>${h(pair.getValue("title").toString())}</h3><p class=\"description\">${h(pair.getValue("description").toString())}</p><p class=\"macro-line\">${h(macros)}</p><div class=\"pair-grid\">")
                for (version in listOf("before", "after")) {
                    @Suppress("UNCHECKED_CAST") val clip = pair.getValue(version) as Map<String, Any?>
                    @Suppress("UNCHECKED_CAST") val measured = clip.getValue("metrics") as Map<String, Any?>
                    val label = if (version == "before") "Before · published baseline" else "After · proposed revision"
                    val accessible = "${pair.getValue("title")} · $label"
                    append("""
                        <article class="clip" data-version="$version"><h4>${h(label)}</h4>
                        <div class="badges"><span>${h(pair.getValue("voice").toString())}</span><span>matched</span></div>
                        <p class="clip-meta">6 s · mono · 44.1 kHz · PCM16</p>
                        <div class="clip-actions"><button class="play" type="button" disabled aria-label="Play ${h(accessible)}" aria-pressed="false">Play</button><a class="download" href="${clip.getValue("path")}" download>WAV <span class="sr-only">${h(accessible)}</span></a></div>
                        <progress value="0" max="6" aria-label="Playback progress for ${h(accessible)}"></progress>
                        <details class="evidence"><summary>Measurements and source trace</summary><dl><dt>PCM peak</dt><dd>${number((measured.getValue("peak") as Number).toDouble())}</dd><dt>PCM listening level</dt><dd>${number((measured.getValue("loudness") as Number).toDouble())}</dd><dt>WAV SHA-256</dt><dd><code>${h(clip.getValue("audioSha256").toString())}</code></dd></dl><a href="${clip.getValue("eventPath")}" download>Download trace <span class="sr-only">${h(accessible)}</span></a></details>
                        <details class="native"><summary>Native audio controls</summary><p class="native-hint" hidden>Enable audio playback above to show these controls.</p><audio controls preload="none" src="${clip.getValue("path")}" aria-label="${h(accessible)}">Download the WAV above to listen.</audio></details></article>
                    """.trimIndent())
                }
                append("</div></article>")
            }
            append("</section>")
        }
    }
    private fun metrics(snip: Snip): Map<String, Any?> = linkedMapOf("sampleRate" to snip.sampleRate, "channels" to snip.channels,
        "frames" to snip.frameCount, "durationSeconds" to snip.durationSeconds, "peak" to snip.peak(), "loudness" to Loudness.of(snip), "rms" to rms(snip.samples))
    private fun rms(a: FloatArray): Double = if (a.isEmpty()) 0.0 else sqrt(a.sumOf { it.toDouble() * it } / a.size)
    private fun safeAsset(root: File, path: String) = File(root, path).canonicalFile.also {
        require(it.toPath().startsWith(root.toPath()) && it.isFile) { "missing or unsafe published asset: $path" }
    }
    private fun copy(from: File, to: File) { to.parentFile.mkdirs(); from.copyTo(to, overwrite = true) }
    private fun writeJson(file: File, value: Any?) { file.parentFile.mkdirs(); file.writeText(Json.write(json(value)) + "\n") }
    private fun git(root: File, vararg args: String): ByteArray {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val bytes = process.inputStream.readBytes(); require(process.waitFor() == 0) { "Git provenance command failed: ${args.firstOrNull()}" }; return bytes
    }
    private fun gitText(root: File, vararg args: String) = git(root, *args).toString(Charsets.UTF_8).trim()
    private fun compiledClassHashes(): Map<String, String> {
        val location = File(Tessera::class.java.protectionDomain.codeSource.location.toURI())
        val folder = File(location, "com/snipsnap/synth")
        require(folder.isDirectory) { "comparison requires the Gradle runtime class directories" }
        return folder.listFiles().orEmpty().filter { it.name == "Tessera.class" || it.name.startsWith("Tessera$") }
            .sortedBy { it.name }.associate { it.name to sha(it) }.also { require(it.isNotEmpty()) }
    }
    private fun sha(file: File) = sha(file.readBytes())
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) { "non-finite comparison evidence" } })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported comparison evidence ${value.javaClass}")
    }
    private fun fromJson(v: JsonValue): Any? = when (v) {
        JsonValue.Null -> null
        is JsonValue.Str -> v.value
        is JsonValue.Bool -> v.value
        is JsonValue.Num -> v.value
        is JsonValue.Obj -> v.entries.mapValues { fromJson(it.value) }
        is JsonValue.Arr -> v.items.map(::fromJson)
    }
    private fun number(v: Double) = String.format(Locale.ROOT, "%.5f", v).trimEnd('0').trimEnd('.')
    private fun h(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
