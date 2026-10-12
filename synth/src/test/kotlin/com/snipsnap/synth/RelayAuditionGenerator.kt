package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A review pack from Relay's real offline DSP. Native exports preserve engine level; matched exports
 * use the same [AuditionLevel] as the sibling listening pages. No factory-preset quota or sonic
 * approval is implied by generating this pack. Run `:synth:generateRelayAudition`.
 */
object RelayAuditionGenerator {
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val timbral = listOf("ORDER", "PULSE", "BLEND", "COLOR", "LENGTH")
    private val descriptions = mapOf(
        "KEY" to "One picked entrance grows into a rounded body with soft and bell color inside the note.",
        "BASS" to "A rounded low body evolves through string and soft-tone color with restrained emphasis.",
        "PLUCK" to "A clear string entrance rings into a blended bell and soft-tone decay.",
        "ORGAN" to "A sustained drawbar body slowly changes its internal soft support and upper detail.",
        "CHIME" to "A bell entrance rings into a softer tonal body without separate internal attacks.",
        "FLOW" to "A soft swelling entrance opens into broad, slowly changing source overlap.",
    )
    private val macroGuides = mapOf(
        "ORDER" to "Listen for a different source handoff order at the same phrase clock. Accents keep their positions.",
        "PULSE" to "Listen for flowing contour becoming stronger rhythmic emphasis. Source order and pitch stay fixed.",
        "BLEND" to "Listen for clear handoffs opening into overlapping source mixtures at the same clock.",
        "COLOR" to "Listen for darker to brighter source detail without losing the four source roles.",
        "LENGTH" to "Listen for a longer finite gesture and terminal tail. The phrase clock stays fixed.",
        "HOLD" to "Below the looping threshold, the phrase ends. At the top, the actual engine loop repeats three times.",
    )
    private data class Case(
        val id: String, val group: String, val voice: RelayVoice, val title: String,
        val description: String, val macros: Map<String, Float> = emptyMap(),
        val midi: Int = 48, val velocity: Float = 1f, val bpm: Float? = null,
        val phrase: RelayPhrase? = null,
    )
    private val groups = listOf(
        group("identities", "Six refined identities", "Start here. All six voices play C3 at full velocity. Listen for a single evolving note with smoother overlap and gentler internal emphasis, then compare with the previous audition at matched level."),
        group("sources", "Hear the four sources", "One source at a time, at the same C3 and COLOR. Listen for a damped string, drawbar body, bright bell and soft swell before comparing their handoffs."),
        group("registers", "Notes and velocity", "Each voice at the ends of its supported two-octave register, then three excitation strengths on KEY and BASS. Use raw playback to hear the energy change; matched playback exposes articulation."),
        group("sweeps", "One control at a time", "Five points on every control, on both KEY and FLOW. Other controls remain at that voice's defaults. The HOLD examples include repeated playback wherever the engine produces a loop."),
        group("interactions", "The controls together", "Four corners of ORDER × PULSE, ORDER × BLEND, BLEND × COLOR and PULSE × LENGTH. Compare both directions of each pair."),
        group("edges", "High settings and tempo", "All five timbral controls high on each voice, plus 60 and 180 BPM on KEY, BASS and FLOW. Tempo is a phrase setting; sample transposition will also change its timing."),
        group("loops", "HOLD through three wraps", "Six challenging held cases: strong pulses, short articulation, broad blends and bright detail. Each WAV contains three exact repetitions of the engine's loop region. Check the handoff and accents at every wrap."),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 &&
            args.count { it.startsWith("--baseline=") } <= 1 &&
            args.filter { it.startsWith("--") }.all { it == "--quick" || it.startsWith("--baseline=") }) {
            "supply one output directory, optional --quick and --baseline=<previous audition directory>"
        }
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/relay-audition")
        val quick = "--quick" in args
        val baseline = args.firstOrNull { it.startsWith("--baseline=") }?.substringAfter('=')?.let(::File)
        val cases = if (quick) cases().filter { it.group in listOf("identities", "sources", "loops") } else cases()
        check(cases.map { it.id }.distinct().size == cases.size)
        root.mkdirs()
        val clips = ArrayList<Map<String, Any?>>(cases.size)
        val started = System.nanoTime()
        for ((index, case) in cases.withIndex()) {
            val tune = (case.midi - Relay.rootMidi(case.voice)).toFloat() / 24f
            val macros = Relay.defaults(case.voice) + case.macros + ("TUNE" to tune)
            val basePhrase = case.phrase ?: Relay.phraseFor(case.voice)
            val phrase = basePhrase.copy(tempoBpm = case.bpm ?: basePhrase.tempoBpm)
            val before = System.nanoTime()
            val report = Relay.inspect(case.voice, macros, velocity = case.velocity, normalize = false, phrase = phrase)
            val renderMs = (System.nanoTime() - before) / 1e6
            val engine = report.snip
            check(engine.channels == 1 && engine.sampleRate == Dsp.RATE)
            check(engine.samples.isNotEmpty() && engine.samples.all { it.isFinite() }) { "invalid Relay clip ${case.id}" }
            val loopRegion = engine.samples
            val repetitions = if (report.loop) 3 else 1
            val playback = if (repetitions == 1) engine else Snip(
                FloatArray(loopRegion.size * repetitions) { loopRegion[it % loopRegion.size] }, 1, Dsp.RATE,
            )
            val rawMetrics = metrics(playback)
            // PCM can only represent [-1, 1]. Record any export reduction without hiding source peak.
            val exportGain = minOf(1f, .99f / playback.peak().coerceAtLeast(1e-9f))
            val native = Snip(FloatArray(playback.samples.size) { playback.samples[it] * exportGain }, 1, Dsp.RATE)
            val matched = AuditionLevel.level(playback)
            val rawPath = "raw/${case.id}.wav"
            val matchedPath = "matched/${case.id}.wav"
            val rawFile = WavWriter.write(File(root, rawPath), native, WavWriter.BitDepth.PCM_24)
            val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            val loop = if (report.loop) mapOf(
                "regionFrames" to loopRegion.size,
                "regionSeconds" to loopRegion.size.toDouble() / Dsp.RATE,
                "playbackRepetitions" to repetitions,
                "seamError" to report.seamError,
                "seamPass" to (report.seamError < 1e-3),
                "wrapStep" to abs(loopRegion.first() - loopRegion.last()),
                "wrapSlopeDifference" to abs((loopRegion[1] - loopRegion[0]) - (loopRegion.last() - loopRegion[loopRegion.lastIndex - 1])),
            ) else null
            val evidence = linkedMapOf<String, Any?>(
                "id" to case.id, "group" to case.group, "voice" to case.voice.name,
                "title" to case.title, "description" to case.description,
                "sources" to phrase.sources.map { it.role.name },
                "comparisonId" to if (baseline != null && case.group == "identities") case.voice.name.lowercase() + "_previous" else null,
                "comparisonLabel" to if (baseline != null && case.group == "identities") "Previous" else null,
                "midi" to case.midi, "note" to noteName(case.midi), "velocity" to case.velocity,
                "tempoBpm" to phrase.tempoBpm, "actualTempoBpm" to phrase.phraseBeats * 60.0 / report.phraseSeconds, "macros" to macros,
                "requestedHz" to report.requestedHz, "actualHz" to report.actualHz,
                "tuningErrorCents" to 1200.0 * ln(report.actualHz / report.requestedHz) / ln(2.0),
                "phraseSeconds" to report.phraseSeconds, "gateSeconds" to report.gateSeconds,
                "frames" to playback.frameCount, "seconds" to playback.durationSeconds,
                "rawPath" to rawPath, "matchedPath" to matchedPath,
                "rawExportGain" to exportGain, "rawSha256" to sha256(rawFile), "matchedSha256" to sha256(matchedFile),
                "rawMetrics" to rawMetrics, "matchedMetrics" to metrics(matched),
                "internalRawPeak" to report.rawPeak, "internalRawMetrics" to metrics(Snip(report.raw, 1, Dsp.RATE * 4)),
                "renderMs" to renderMs, "renderMsPerAudioSecond" to renderMs / engine.durationSeconds,
                "loop" to loop,
                "accents" to report.accents.map { mapOf("beat" to it.beat, "timeSeconds" to it.timeSeconds, "strength" to it.strength) },
                "weights" to report.weights.map { mapOf("timeSeconds" to it.timeSeconds, "weights" to it.weights) },
                "contour" to contour(engine),
            )
            clips += evidence
            if ((index + 1) % 10 == 0 || index == cases.lastIndex) println("Relay audition: ${index + 1}/${cases.size} cases · ${(index + 1) * 2} WAVs")
        }
        if (baseline != null) clips += previousIdentities(root, baseline)
        val auditionGroups = if (baseline == null) groups else groups + group(
            "previous", "Previous audition · revision 2", "The six unchanged recordings from the preceding audition. Compare each with its refined C3 identity at the same playback level.")
        val currentClips = clips.filter { it["group"] != "previous" }
        val manifest = Json.write(json(linkedMapOf(
            "schemaVersion" to 1, "engine" to "RELAY", "status" to "working audition — awaiting owner sonic approval",
            "presetExpansion" to "36-preset target deferred until dry identity is approved",
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 24,
            "referenceNote" to "C3", "quick" to quick, "clipCount" to clips.size, "auditionRevision" to 3,
            "listeningTarget" to .03, "listeningPeakCeiling" to .99, "loopThreshold" to Relay.LOOP_THRESHOLD,
            "sourceRevision" to git("rev-parse", "HEAD"), "workingTreeDirty" to (git("status", "--porcelain")?.isNotBlank() ?: true),
            "metricNotes" to "Raw WAVs preserve the conditioned engine output before loudness leveling, except a recorded PCM export gain if required. internalRawPeak is the unconditioned 4× path peak. loudestWindowRms uses SnipSnap's loudest 200 ms rule. HOLD fits the loop clock to the requested root; actualTempoBpm records the resulting tempo. Loop seamError is measured by the engine against the preceding settled cycle; pass requires < 0.001 and owner listening. contour brightness is first-difference RMS divided by RMS, a high-frequency proxy, not spectral centroid. Raw and matched files are 24-bit PCM from the same render. No rack effects.",
            "groups" to auditionGroups, "clips" to clips,
            "summary" to mapOf(
                "wavCount" to clips.size * 2,
                "maxInternalRawPeak" to currentClips.maxOf { (it.getValue("internalRawPeak") as Number).toDouble() },
                "heldCases" to clips.count { it["loop"] != null },
                "loopSeamsPassed" to clips.filter { it["loop"] != null }.all { (it["loop"] as Map<*, *>)["seamPass"] == true },
                "generationSeconds" to (System.nanoTime() - started) / 1e9,
            ),
        ))) + "\n"
        prunePrevious(root, clips.flatMap { listOf(it.getValue("rawPath") as String, it.getValue("matchedPath") as String) }.toSet())
        File(root, "manifest.json").writeText(manifest)
        val page = RelayAuditionGenerator::class.java.getResourceAsStream("/audition/relay-audition.html")
            ?.bufferedReader()?.use { it.readText() } ?: error("Relay audition page resource is missing")
        check(page.contains("__MANIFEST__"))
        File(root, "index.html").writeText(page.replace("__MANIFEST__", manifest.replace("</", "<\\/")))
        File(root, "README.md").writeText("""
            # Relay audition

            ${clips.size} dry DSP cases, each with native-level and shared-level 24-bit mono WAVs.
            Open index.html locally or serve this directory as a static site. The manifest is embedded
            in the page, so file playback needs no server or remote assets.

            Working audition; owner sonic approval is pending. The 36-preset expansion is deferred.
            Revision 3 refines overlap and source excitation toward one evolving voice.
            The isolated-source section exposes each role. If supplied with -PrelayBaseline=<directory>,
            six unchanged previous identities are copied with their own measurements and hashes.
            Raw playback preserves the conditioned output before loudness leveling. Matched playback
            uses AuditionLevel (loudest 200 ms RMS 0.03, peak guard 0.99). Any raw PCM export reduction
            is recorded as rawExportGain; unscaled output metrics remain in manifest.json.
            Held clips contain three exact copies of the actual engine loop, not a browser synthesis.
            Loop metrics describe the single engine region and the preceding settled cycle.

            Regenerate: ./gradlew :synth:generateRelayAudition${if (quick) " -PrelayQuick" else ""}
        """.trimIndent() + "\n")
        println("Wrote ${clips.size * 2} WAVs, manifest.json, index.html and README.md under ${root.absolutePath}")
    }

    private fun cases(): List<Case> = buildList {
        for (role in RelayRole.entries) {
            val source = Relay.phraseFor(RelayVoice.KEY).sources.single { it.role == role }.copy(gain = 1f)
            val staticPhrase = RelayPhrase(listOf(source), List(4) { RelaySlot(0, 1f, .2f) },
                listOf(List(4) { 0 }), listOf(RelayAccent(0f, 1f)))
            add(Case("source_${role.name.lowercase()}", "sources", RelayVoice.KEY, "${role.name} source · C3",
                "Isolated generated source, with the same pitch, COLOR and phrase clock as the other source probes.",
                mapOf("ORDER" to 0f, "PULSE" to 0f, "BLEND" to 0f, "COLOR" to .55f, "LENGTH" to .65f),
                phrase = staticPhrase))
        }
        for (voice in RelayVoice.entries) {
            val tag = voice.name.lowercase()
            add(Case("${tag}_identity", "identities", voice, "${voice.name} · C3", descriptions.getValue(voice.name)))
            for (midi in listOf(Relay.rootMidi(voice), Relay.rootMidi(voice) + 24)) {
                add(Case("${tag}_midi$midi", "registers", voice, "${voice.name} · ${noteName(midi)}", "Voice defaults at its supported register boundary.", midi = midi))
            }
            add(Case("${tag}_all_high", "edges", voice, "${voice.name} · all high${if (voice == RelayVoice.CHIME) " · C5" else ""}",
                "All five timbral controls at 1; HOLD remains finite.${if (voice == RelayVoice.CHIME) " C5 tests the brightest high-register bandwidth edge." else ""}",
                timbral.associateWith { 1f }, midi = if (voice == RelayVoice.CHIME) 72 else 48))
        }
        for (voice in listOf(RelayVoice.KEY, RelayVoice.BASS)) for (velocity in listOf(.3f, .65f, 1f)) {
            add(Case("${voice.name.lowercase()}_velocity${tag(velocity)}", "registers", voice, "${voice.name} · velocity ${fmt(velocity)}", "Same C3 phrase with different excitation energy. Compare raw level, then matched articulation.", velocity = velocity))
        }
        for (voice in listOf(RelayVoice.KEY, RelayVoice.FLOW)) for (macro in timbral + "HOLD") for (value in steps) {
            add(Case("${voice.name.lowercase()}_${macro.lowercase()}${tag(value)}", "sweeps", voice, "${voice.name} · $macro ${fmt(value)}", macroGuides.getValue(macro), mapOf(macro to value)))
        }
        val interactions = listOf(
            Triple(RelayVoice.KEY, "ORDER", "PULSE"), Triple(RelayVoice.FLOW, "ORDER", "BLEND"),
            Triple(RelayVoice.CHIME, "BLEND", "COLOR"), Triple(RelayVoice.PLUCK, "PULSE", "LENGTH"),
        )
        for ((voice, a, b) in interactions) for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) {
            add(Case("${voice.name.lowercase()}_${a.lowercase()}${tag(x)}_${b.lowercase()}${tag(y)}", "interactions", voice, "$a ${fmt(x)} × $b ${fmt(y)} · ${voice.name}", "Four-corner comparison; other controls at ${voice.name}'s defaults.", mapOf(a to x, b to y)))
        }
        for (voice in listOf(RelayVoice.KEY, RelayVoice.BASS, RelayVoice.FLOW)) for (bpm in listOf(60f, 180f)) {
            add(Case("${voice.name.lowercase()}_tempo${bpm.toInt()}", "edges", voice, "${voice.name} · ${bpm.toInt()} BPM", "The phrase clock changes; source handoff order and requested C3 remain the same.", bpm = bpm))
        }
        for (voice in RelayVoice.entries) {
            val overrides = when (voice) {
                RelayVoice.KEY -> mapOf("ORDER" to 1f, "PULSE" to 1f)
                RelayVoice.BASS -> mapOf("PULSE" to 1f, "LENGTH" to 0f)
                RelayVoice.PLUCK -> mapOf("PULSE" to 1f, "BLEND" to 0f, "LENGTH" to .25f)
                RelayVoice.ORGAN -> mapOf("ORDER" to 1f, "BLEND" to 1f)
                RelayVoice.CHIME -> mapOf("COLOR" to 1f, "BLEND" to .25f)
                RelayVoice.FLOW -> timbral.associateWith { 1f }
            }
            val midi = if (voice == RelayVoice.BASS) 24 else if (voice == RelayVoice.CHIME) 72 else 48
            add(Case("${voice.name.lowercase()}_held_challenge", "loops", voice, "${voice.name} · held challenge · ${noteName(midi)}",
                "Three repetitions of the actual loop. Listen for doubled accents, a broken handoff, pumping or a click at each wrap.",
                overrides + ("HOLD" to 1f), midi = midi, bpm = if (voice == RelayVoice.BASS) 60f else if (voice == RelayVoice.CHIME) 180f else 100f))
        }
    }

    private fun previousIdentities(root: File, baseline: File): List<Map<String, Any?>> {
        require(baseline.isDirectory) { "previous audition directory is missing" }
        val manifest = Json.parse(File(baseline, "manifest.json").readText()).obj()
        val original = manifest.getValue("clips").arr().filter { it.obj().getValue("group").str() == "identities" }
        require(original.size == RelayVoice.entries.size) { "previous audition needs exactly six identity clips" }
        return RelayVoice.entries.map { voice ->
            val item = original.single { it.obj().getValue("voice").str() == voice.name }.obj()
            val data = item.mapValues { native(it.value) }.toMutableMap()
            val id = voice.name.lowercase() + "_previous"
            for ((pathKey, hashKey) in listOf("rawPath" to "rawSha256", "matchedPath" to "matchedSha256")) {
                val oldPath = item.getValue(pathKey).str()
                require(Regex("(raw|matched)/[a-z0-9_]+\\.wav").matches(oldPath)) { "unsafe previous WAV path" }
                val file = File(baseline, oldPath).canonicalFile
                require(file.toPath().startsWith(baseline.canonicalFile.toPath()) && file.isFile)
                require(sha256(file) == item.getValue(hashKey).str()) { "previous WAV does not match its evidence" }
                val path = "previous/" + oldPath
                File(root, path).apply { parentFile.mkdirs(); file.copyTo(this, overwrite = true) }
                data[pathKey] = path
            }
            data["id"] = id
            data["group"] = "previous"
            data["title"] = "${voice.name} · previous · C3"
            data["description"] = "Unchanged recording from the previous audition, before the smoothing refinement. Compare with ${voice.name} in Six refined identities."
            data["sourceRevision"] = native(manifest.getValue("sourceRevision"))
            data["auditionRevision"] = manifest["auditionRevision"]?.let(::native)
            data["comparisonId"] = voice.name.lowercase() + "_identity"
            data["comparisonLabel"] = "Refined"
            data
        }
    }

    private fun native(value: JsonValue): Any? = when (value) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        is JsonValue.Num -> value.value
        is JsonValue.Bool -> value.value
        is JsonValue.Arr -> value.items.map(::native)
        is JsonValue.Obj -> value.entries.mapValues { native(it.value) }
    }

    private fun metrics(snip: Snip): Map<String, Any> {
        val x = snip.samples
        var power = 0.0; var sum = 0.0; var maxDifference = 0.0
        for (i in x.indices) {
            power += x[i].toDouble() * x[i]; sum += x[i]
            if (i > 0) maxDifference = maxOf(maxDifference, abs(x[i].toDouble() - x[i - 1]))
        }
        val tailFrames = minOf(x.size, snip.sampleRate / 20)
        var tailPower = 0.0
        for (i in x.size - tailFrames until x.size) tailPower += x[i].toDouble() * x[i]
        return mapOf(
            "peak" to snip.peak(), "rms" to sqrt(power / x.size), "dc" to sum / x.size,
            "loudestWindowRms" to Loudness.of(snip), "last50msRms" to sqrt(tailPower / tailFrames),
            "firstSample" to x.first(), "lastSample" to x.last(), "firstDifferencePeak" to maxDifference,
            "finite" to x.all { it.isFinite() },
        )
    }

    private fun contour(snip: Snip): List<Map<String, Any>> {
        val x = snip.samples
        return (0 until 24).map { block ->
            val from = block * x.size / 24
            val to = (block + 1) * x.size / 24
            var energy = 0.0; var difference = 0.0
            for (i in from until to) {
                energy += x[i].toDouble() * x[i]
                if (i > from) { val d = x[i].toDouble() - x[i - 1]; difference += d * d }
            }
            mapOf("timeSeconds" to (from + to).toDouble() / 2 / Dsp.RATE,
                "rms" to sqrt(energy / (to - from)), "brightnessProxy" to sqrt(difference / energy.coerceAtLeast(1e-20)))
        }
    }

    private fun prunePrevious(root: File, retained: Set<String>) {
        val previous = File(root, "manifest.json")
        if (!previous.isFile) return
        val clips = runCatching { Json.parse(previous.readText()).obj().getValue("clips").arr() }.getOrDefault(emptyList())
        for (clip in clips) for (key in listOf("rawPath", "matchedPath")) {
            val path = runCatching { clip.obj().getValue(key).str() }.getOrNull() ?: continue
            if (!Regex("(previous/)?(raw|matched)/[a-z0-9_]+\\.wav").matches(path)) continue
            if (path in retained) continue
            val file = File(root, path).canonicalFile
            if (file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile) check(file.delete())
        }
    }
    private fun group(id: String, title: String, description: String) = mapOf("id" to id, "title" to title, "description" to description)
    private fun tag(value: Float) = (value * 100).toInt().toString().padStart(3, '0')
    private fun fmt(value: Float) = "%.2f".format(Locale.ROOT, value)
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        is FloatArray -> JsonValue.Arr(value.map(::json))
        is DoubleArray -> JsonValue.Arr(value.map(::json))
        is Array<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported Relay manifest value: ${value.javaClass}")
    }
}
