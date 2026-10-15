package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Reproducible, dry Kotlin renders. The full pack is a listening gate, not a listening verdict. */
object SkitterAuditionGenerator {
    private val timbral = listOf("BUZZ", "SKITTER", "AGITATE", "VENT", "STATIC")
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val neutral = mapOf("BUZZ" to .45f, "SKITTER" to .25f, "AGITATE" to .35f,
        "VENT" to .25f, "STATIC" to .15f, "HOLD" to 0f)
    private val percussion = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
    private val comparisonKeys = SkitterVoice.entries.map { "DEFAULTS/${it.name.lowercase(Locale.ROOT)}" } +
        listOf("PRESETS/nervous_wire", "HOLD/flutter_default", "HOLD/fray_default")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 && args.count { it.startsWith("--baseline=") } <= 1 &&
            args.all { !it.startsWith("--") || it == "--quick" || it.startsWith("--baseline=") }) {
            "supply one output directory, optional --quick and --baseline=<frozen R1 directory>"
        }
        val quick = "--quick" in args
        val baseline = args.firstOrNull { it.startsWith("--baseline=") }?.substringAfter('=')?.let {
            require(it.isNotBlank()) { "--baseline needs a frozen R1 directory" }
            loadBaseline(File(it))
        }
        require(!quick || baseline == null) { "the nine R1/R2 comparisons need the full pack; omit --quick" }
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/skitter-audition").also { it.mkdirs() }
        if (baseline != null) require(!root.canonicalFile.toPath().startsWith(baseline.root.toPath()) &&
            !baseline.root.toPath().startsWith(root.canonicalFile.toPath())) { "output and frozen baseline must be separate directories" }
        val sections = mutableListOf<JsonValue>()
        val measurements = mutableListOf<JsonValue>()
        var count = 0
        var wavCount = 0

        fun clip(section: String, id: String, name: String, description: String,
                 voice: SkitterVoice = SkitterVoice.LEAD, macros: Map<String, Float> = Skitter.defaults(voice),
                 midi: Int = Skitter.DEFAULT_MIDI, velocity: Float = 1f, seed: Int = 0,
                 probe: SkitterProbe = SkitterProbe(), oversample: Int = 4,
                 baseline: FloatArray? = null, capture: ((FloatArray) -> Unit)? = null,
                 matchedRawGain: Double? = null, prepared: SkitterRender? = null): JsonValue {
            val resolved = Skitter.defaults(voice) + macros
            val report = prepared ?: Skitter.renderProbe(voice, resolved, midi, velocity, seed, oversample = oversample, probe = probe)
            require(report.raw.all { it.isFinite() } && report.snip.samples.all { it.isFinite() }) { "$section/$id: nonfinite audio" }
            val rawStats = stats(report.raw)
            require(rawStats.peak <= 1.0) { "$section/$id: raw PCM would clip (${rawStats.peak})" }
            val native = report.snip
            val matched = if (matchedRawGain == null) AuditionLevel.level(native) else
                Snip(FloatArray(report.raw.size) { (report.raw[it] * matchedRawGain).toFloat() }, 1, native.sampleRate)
            require(matched.peak() <= 1f) { "$section/$id: matched PCM would clip" }
            val held = report.loopStart >= 256
            val sampler = SmplChunk(midi, if (held) SmplChunk.Loop(report.loopStart.toLong(), native.frameCount.toLong()) else null)
            val features = FeatureExtractor.extract(native)
            val classification = Classifier.classify(features).drumClass
            // The cropped opening is a heuristic diagnostic: shortening a pitched sound can provoke false percussion labels.
            val body = Snip(native.samples.copyOfRange(0, minOf(native.samples.size, (1.4f * native.sampleRate).toInt())), 1, native.sampleRate)
            val bodyClassification = Classifier.classify(body).drumClass
            val expectedHz = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val pitch = if (expectedHz >= 40.0) Pitch.detect(native, .35f, .25f)?.let { it.hz.toDouble() to it.confidence.toDouble() }
                else lowPitch(native, expectedHz)
            val pitchCents = pitch?.let { 1200.0 * ln(it.first / expectedHz) / ln(2.0) }
            val directory = File(root, section).also { it.mkdirs() }
            val rawFile = "$section/$id-raw.wav"
            val nativeFile = "$section/$id-native.wav"
            val matchedFile = "$section/$id-matched.wav"
            WavWriter.write(File(root, rawFile), Snip(report.raw, 1, native.sampleRate), WavWriter.BitDepth.PCM_24, smpl = sampler)
            WavWriter.write(File(root, nativeFile), native, WavWriter.BitDepth.PCM_24, smpl = sampler)
            WavWriter.write(File(root, matchedFile), matched, WavWriter.BitDepth.PCM_24, smpl = sampler)
            wavCount += 3

            var repeatFile: String? = null
            var pcmSeam: Double? = null
            var rawPcmSeam: Double? = null
            var nativePcmSeam: Double? = null
            if (held) {
                // Measure the exact PCM-24 values exported to listeners, using the repository's seam metric.
                val quantized = WavReader.read(File(root, matchedFile)).samples
                pcmSeam = Keys.seamError(quantized, report.loopStart)
                rawPcmSeam = Keys.seamError(WavReader.read(File(root, rawFile)).samples, report.loopStart)
                nativePcmSeam = Keys.seamError(WavReader.read(File(root, nativeFile)).samples, report.loopStart)
                require(pcmSeam.isFinite() && pcmSeam < 1e-3) { "$section/$id: exported held seam $pcmSeam" }
                val period = matched.samples.copyOfRange(report.loopStart, matched.samples.size)
                repeatFile = "$section/$id-seams.wav"
                WavWriter.write(File(root, repeatFile), Snip(FloatArray(period.size * 4) { period[it % period.size] }, 1, native.sampleRate), WavWriter.BitDepth.PCM_24)
                wavCount++
            }
            val patchFile = "$section/$id-patch.json"
            File(root, patchFile).writeText(SkitterPatch(name, voice, resolved, midi, velocity, seed).toJsonText() + "\n")
            val diagnosticFile = "$section/$id-diagnostics.json"
            val d = report.diagnostics
            val agitationTail = report.trace.lastOrNull { abs(it.agitation) > d.agitationPeak * .1 }?.seconds
            val distance = baseline?.let { sampleDistance(it, matched.samples) }
            val probeJson = obj("memoryFrozen" to bool(probe.memoryFrozen), "agitationDisabled" to bool(probe.agitationDisabled),
                "equalRecovery" to bool(probe.equalRecovery), "noiseForcingDisabled" to bool(probe.noiseForcingDisabled))
            val renderSettings = obj("midi" to num(midi), "velocity" to num(velocity), "seed" to num(seed),
                "oversample" to num(oversample), "seconds" to JsonValue.Null, "probe" to probeJson)
            val measurement = obj(
                "id" to str("$section/$id"), "raw" to rawStats.json(), "native" to stats(native.samples).json(),
                "matched" to stats(matched.samples).json(), "pitchHz" to nullable(pitch?.first),
                "pitchCents" to nullable(pitchCents), "pitchConfidence" to nullable(pitch?.second),
                "expectedHz" to num(expectedHz), "centroidHz" to num(features.centroidHz),
                "matchedGainFromRaw" to num(stats(matched.samples).rms / rawStats.rms.coerceAtLeast(1e-20)),
                "matchTreatment" to str(if (matchedRawGain == null) "Shared quiet loudest-window target" else "Ablation set: common gain from intact raw circuit, with common peak budget"),
                "classification" to str(classification.name), "bodyClassification" to str(bodyClassification.name),
                "pitchedClassifier" to bool(classification !in percussion),
                "bodyPercussionDiagnostic" to bool(bodyClassification in percussion),
                "macroDistanceRms" to nullable(distance), "lastAgitationAbove10PctSeconds" to nullable(agitationTail),
                "pcm24LoopSeam" to nullable(pcmSeam), "rawPcm24LoopSeam" to nullable(rawPcmSeam),
                "nativePcm24LoopSeam" to nullable(nativePcmSeam), "sampleRate" to num(native.sampleRate),
                "durationSeconds" to num(native.durationSeconds), "oversample" to num(oversample),
                "mechanism" to obj("rawPeak" to num(d.rawPeak), "rawRms" to num(d.rawRms), "rawDc" to num(d.rawDc),
                    "filterStatePeak" to num(d.filterStatePeak), "historyPeak" to num(d.historyPeak),
                    "signedHistoryPeak" to num(d.signedHistoryPeak), "agitationPeak" to num(d.agitationPeak),
                    "agitationVelocityPeak" to num(d.agitationVelocityPeak), "pitchOffsetPeakCents" to num(d.pitchOffsetPeakCents),
                    "gestureCount" to num(d.gestureCount), "boundHits" to num(d.boundHits),
                    "loopPrerollPeriods" to num(d.loopPrerollPeriods), "loopConverged" to bool(d.loopConverged),
                    "loopStateError" to num(d.loopStateError), "loopSeam" to num(d.loopSeam), "renderMillis" to num(d.renderMillis)),
            )
            File(directory, "$id-diagnostics.json").writeText(Json.write(obj(
                "measurement" to measurement,
                "renderSettings" to renderSettings,
                "sourcePatchNote" to str("The source patch reproduces the standard 4x engine render. To reproduce an ablation or higher-rate probe, pass these renderSettings to Skitter.renderProbe."),
                "traceColumns" to arr(listOf("seconds", "energyHistory", "signedHistory", "agitation", "agitationVelocity",
                    "pitchOffsetA", "pitchOffsetB", "cutoffHz", "drive").map(::str)),
                "trace" to arr(report.trace.map { t -> arr(listOf(t.seconds, t.energyHistory, t.signedHistory, t.agitation,
                    t.agitationVelocity, t.pitchOffsetA, t.pitchOffsetB, t.cutoffHz, t.drive).map(::num)) }),
            )) + "\n")
            measurements += measurement
            capture?.invoke(matched.samples.copyOf())
            count++
            println("[$count] $section/$id (${d.renderMillis.roundToInt()} ms, full=$classification / body=$bodyClassification)")
            return obj("id" to str(id), "name" to str(name), "description" to str(description), "voice" to str(voice.name),
                "macros" to JsonValue.Obj(resolved.mapValues { num(it.value) }), "midi" to num(midi), "note" to str(noteName(midi)),
                "velocity" to num(velocity), "seed" to num(seed), "raw" to str(rawFile), "native" to str(nativeFile),
                "matched" to str(matchedFile), "repeat" to (repeatFile?.let(::str) ?: JsonValue.Null),
                "patch" to str(patchFile), "diagnostics" to str(diagnosticFile), "metrics" to measurement,
                "held" to bool(held), "loopStart" to num(report.loopStart), "oversample" to num(oversample),
                "renderSettings" to renderSettings)
        }
        fun group(label: String, clips: List<JsonValue>) = obj("label" to str(label), "clips" to arr(clips))
        fun section(id: String, title: String, description: String, groups: List<JsonValue>) {
            sections += obj("id" to str(id), "title" to str(title), "description" to str(description), "groups" to arr(groups))
        }

        section("DEFAULTS", "Six circuit personalities", "Dry voice defaults at C3 / MIDI 48, velocity 1. Start here, then compare the factory patches.",
            listOf(group("Defaults", SkitterVoice.entries.map { v -> clip("DEFAULTS", v.name.lowercase(), v.name, voiceDescription(v), v) })))

        val presets = SkitterPresets.all()
        require(presets.size == 36) { "the full factory roster must contain 36 presets" }
        section("PRESETS", "36 starting points", "A searchable factory palette: clean circuits, moderate reactions, experimental extremes and one held variant per voice.",
            listOf(group("Factory patches", (if (quick) presets.filter { it.macros["HOLD"] == 1f }.take(2) + presets.take(6) else presets).map { p ->
                clip("PRESETS", slug(p.name), p.name, "${p.voice.name} · dry engine, empty effect chain", p.voice, p.macros, p.midi, p.velocity, p.seed)
            })))

        val voices = if (quick) listOf(SkitterVoice.LEAD) else SkitterVoice.entries
        for (voice in voices) {
            val notes = if (quick) listOf(24, 84) else listOf(24, 48, 84)
            val velocities = if (quick) listOf(.25f, 1f) else listOf(.25f, .6f, 1f)
            section("RANGE_${voice.name}", "${voice.name}: register × velocity", "Initial evidence spans C1, C3 and C6. Velocity changes excitation and provocation before leveling.",
                notes.map { midi -> group("${noteName(midi)} · MIDI $midi", velocities.map { v ->
                    clip("RANGE_${voice.name}", "m${midi}_v${tag(v)}", "${noteName(midi)} / ${fmt(v)}", "Voice defaults; finite sample.", voice, midi = midi, velocity = v)
                }) })
            section("SWEEPS_${voice.name}", "${voice.name}: macro sweeps", "Each control moves through 0, .25, .50, .75, 1. Companions use the starting neutral patch; HOLD stays 0.",
                (if (quick) listOf("SKITTER", "AGITATE") else timbral).map { macro ->
                    var previous: FloatArray? = null
                    group(macro, (if (quick) listOf(0f, 1f) else steps).map { value ->
                        clip("SWEEPS_${voice.name}", "${macro.lowercase()}_${tag(value)}", "$macro ${fmt(value)}", macroDescription(macro),
                            voice, neutral + (macro to value), baseline = previous, capture = { previous = it })
                    })
                })
        }

        val interactions = listOf("BUZZ" to "AGITATE", "SKITTER" to "AGITATE", "STATIC" to "AGITATE", "VENT" to "AGITATE")
        section("INTERACTIONS", "Four coupled reactions", "LEAD at C3. Each 3 × 3 grid combines 0, .5 and 1; compare corners for reaction changes beyond loudness.",
            interactions.map { (a, b) -> group("$a × $b", (if (quick) listOf(0f, 1f) else listOf(0f, .5f, 1f)).flatMap { av ->
                (if (quick) listOf(0f, 1f) else listOf(0f, .5f, 1f)).map { bv ->
                    clip("INTERACTIONS", "${a.lowercase()}_${tag(av)}_${b.lowercase()}_${tag(bv)}", "$a ${fmt(av)} / $b ${fmt(bv)}",
                        "Neutral companions; interacting filter provocation.", macros = neutral + mapOf(a to av, b to bv))
                }
            }) })

        section("EXTREMES", "All five timbral controls high", "Maximum velocity at the initial low, middle and high registers. HOLD stays finite; raw files expose the gain budget.",
            (if (quick) listOf(SkitterVoice.FRAY) else SkitterVoice.entries).map { v -> group(v.name,
                (if (quick) listOf(84) else listOf(24, 48, 84)).map { midi ->
                    clip("EXTREMES", "${v.name.lowercase()}_m$midi", "${v.name} / ${noteName(midi)}", "BUZZ, SKITTER, AGITATE, VENT, STATIC all 1.",
                        v, neutral + timbral.associateWith { 1f }, midi)
                }) })

        val ablationPatch = neutral + mapOf("BUZZ" to .8f, "SKITTER" to .8f, "AGITATE" to .85f, "STATIC" to .55f)
        val ablations = listOf("Coupled circuit" to SkitterProbe(), "Memory frozen" to SkitterProbe(memoryFrozen = true),
            "Agitation disabled" to SkitterProbe(agitationDisabled = true), "Equal recovery" to SkitterProbe(equalRecovery = true),
            "Noise forcing disabled" to SkitterProbe(noiseForcingDisabled = true))
        val ablationRenders = ablations.map { (_, probe) -> Skitter.renderProbe(SkitterVoice.LEAD, ablationPatch, probe = probe) }
        val intactMatched = AuditionLevel.level(ablationRenders.first().snip)
        val referenceGain = stats(intactMatched.samples).rms / stats(ablationRenders.first().raw).rms.coerceAtLeast(1e-20)
        val commonGain = minOf(referenceGain, .99 / ablationRenders.maxOf { stats(it.raw).peak }.coerceAtLeast(1e-20))
        var intact: FloatArray? = null
        section("ABLATIONS", "Causal listening bench", "Same patch, note, velocity and seed. Matched playback applies one common gain to all five raw renders, preserving source noise gain. Native retains the engine's own leveling. Listen for changes in filter behavior beyond hiss.",
            listOf(group("One mechanism removed at a time", ablations.mapIndexed { i, (name, probe) ->
                clip("ABLATIONS", slug(name), name, if (i == 4) "Audible noise retained at matched source gain; behavioral noise forcing removed." else "${name}; same source and finite envelope.",
                    macros = ablationPatch, probe = probe, baseline = intact, capture = { if (i == 0) intact = it },
                    matchedRawGain = commonGain, prepared = ablationRenders[i])
            })))

        section("HOLD", "Repeated seams", "Each seam clip repeats the final exported period four times with no extra boundary repair. Use Repeat for longer listening. Periodic gesture replay and treated periodic noise approximate a stationary coupled circuit; AGITATE and STATIC remain audible.",
            (if (quick) listOf(SkitterVoice.FRAY) else SkitterVoice.entries).map { v -> group(v.name, listOf(
                clip("HOLD", "${v.name.lowercase()}_default", "${v.name} / held defaults", "Voice defaults with HOLD 1; convergence and PCM seam recorded.", v,
                    Skitter.defaults(v) + ("HOLD" to 1f)),
                clip("HOLD", "${v.name.lowercase()}_bright_noisy", "${v.name} / bright and noisy", "HOLD 1 with strong unequal recovery, agitation and noise.", v,
                    neutral + mapOf("BUZZ" to .95f, "SKITTER" to .9f, "AGITATE" to .95f, "STATIC" to .8f, "VENT" to .75f, "HOLD" to 1f), 72),
            )) })

        var reference: FloatArray? = null
        section("REFERENCE", "Bright 4× / 8× reference", "FRAY at C6, all timbral controls high. Both use the same requested patch, note, seed and base-rate seeded noise with identical interpolation. Internal dynamic integration can differ. Compare brightness and texture; waveform distance is phase sensitive and does not isolate aliasing.",
            listOf(group("Higher internal rate", listOf(4, 8).map { rate ->
                clip("REFERENCE", "bright_${rate}x", "${rate}× oversampling", "Output-rate PCM; internal nonlinear filter runs at ${rate}×.", SkitterVoice.FRAY,
                    neutral + timbral.associateWith { 1f }, 84, oversample = rate, baseline = reference, capture = { if (rate == 4) reference = it })
            })))

        val comparison = baseline?.let { comparisons(root, it, indexedClips(sections)) }
        val manifestFields = linkedMapOf("engine" to str("SKITTER"), "formatVersion" to num(1), "quick" to bool(quick),
            "variants" to num(count), "wavFiles" to num(wavCount), "presetCount" to num(presets.size),
            "acceptance" to str("Provisional: owner listening verdict pending"), "sections" to arr(sections))
        if (comparison != null) {
            manifestFields["revision"] = str("R2")
            manifestFields["comparisons"] = arr(comparison.pairs)
            manifestFields["comparisonCount"] = num(comparison.pairs.size)
            manifestFields["comparisonWavFiles"] = num(comparison.wavFiles)
            manifestFields["baseline"] = comparison.provenance
        }
        val manifest = Json.write(JsonValue.Obj(manifestFields))
        File(root, "manifest.json").writeText(manifest + "\n")
        File(root, "measurements.json").writeText(Json.write(obj("engine" to str("SKITTER"), "quick" to bool(quick),
            "complete" to bool(true), "variants" to num(count), "wavFiles" to num(wavCount),
            "machine" to str("${System.getProperty("os.name")} / ${System.getProperty("os.arch")} / ${Runtime.getRuntime().availableProcessors()} logical processors / ${System.getProperty("java.version")}"),
            "measureNotes" to str("Raw output is DC-removed prelevel audio. Audio statistics precede PCM encoding. PCM seam uses the exported 24-bit values. Macro distance is adjacent matched-wave RMS difference, phase sensitive. Pitch uses repository body detection at .35s, with a constrained low-note autocorrelation fallback below 40Hz; diagnostic confidence is not a listening verdict. pitchedClassifier checks the actual full sample. The first-1.4-second body classifier is a heuristic diagnostic that can falsely label cropped pitched sounds as percussion. Full samples longer than 1.5 seconds trigger LOOP; classifier labels therefore do not prove pitch. Last agitation above 10% is an observation, not an asserted settling-time acceptance threshold. Numeric tuning and render budget remain provisional."),
            "clips" to arr(measurements))) + "\n")
        val template = SkitterAuditionGenerator::class.java.getResourceAsStream("/audition/skitter-audition.html")
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("missing Skitter listening page")
        val marker = "/* SKITTER_MANIFEST */null"
        require(marker in template) { "missing page manifest marker" }
        File(root, "index.html").writeText(template.replace(marker, manifest.replace("<", "\\u003c")))
        println("wrote $count variants, $wavCount WAVs, patch JSON, mechanism traces, manifest, measurements and listening page under ${root.absolutePath}")
        if (comparison != null) println("added ${comparison.pairs.size} matched R1/R2 comparison pairs and ${comparison.wavFiles} frozen R1 WAVs; current render counts stay separate")
        if (quick) println("quick development pack; run without --quick for the full acceptance matrix")
    }

    private data class Baseline(val root: File, val manifest: Map<String, JsonValue>,
                                val clips: Map<String, JsonValue.Obj>, val hashes: Map<String, String>)
    private data class Comparison(val pairs: List<JsonValue>, val wavFiles: Int, val provenance: JsonValue)

    /** A frozen recording is provenance, never a recipe that the current engine claims to replay. */
    private fun loadBaseline(directory: File): Baseline {
        val root = directory.canonicalFile
        require(root.isDirectory) { "frozen R1 directory is missing: $root" }
        val manifest = Json.parse(File(root, "manifest.json").readText()).obj()
        require(manifest.getValue("engine").str() == "SKITTER" && manifest.getValue("revision").str() == "R1") {
            "baseline must be a frozen SKITTER R1 snapshot"
        }
        val commit = manifest.getValue("sourceCommit").str()
        require(Regex("[0-9a-f]{40}").matches(commit)) { "baseline source commit must be a full Git SHA" }
        val checksums = Json.parse(File(root, "sha256.json").readText()).obj()
        require(checksums.getValue("algorithm").str() == "SHA-256" && checksums.getValue("sourceCommit").str() == commit)
        val hashes = checksums.getValue("files").obj().mapValues { (_, value) -> value.obj().getValue("sha256").str() }
        for ((relative, expected) in hashes) {
            val file = snapshotFile(root, relative)
            require(sha256(file) == expected) { "frozen R1 asset changed: $relative" }
        }
        require(hashes["manifest.json"] == sha256(File(root, "manifest.json"))) { "baseline manifest lacks its checksum" }
        require(hashes["source-manifest.json"] == manifest.getValue("sourceManifestSha256").str()) { "baseline source manifest checksum differs" }
        val clips = manifest.getValue("clips").arr().associate { value ->
            val item = value.obj()
            item.getValue("sourceKey").str() to (item.getValue("clip") as JsonValue.Obj)
        }
        require(clips.keys == comparisonKeys.toSet()) { "baseline needs all six defaults, Nervous Wire and the FLUTTER/FRAY held defaults" }
        val originals = indexedClips(Json.parse(File(root, "source-manifest.json").readText()).obj().getValue("sections").arr())
        for ((key, clip) in clips) require(originals[key] == clip) { "snapshot metadata differs from the original R1 manifest: $key" }
        return Baseline(root, manifest, clips, hashes)
    }

    private fun indexedClips(sections: List<JsonValue>): Map<String, JsonValue.Obj> = buildMap {
        for (section in sections) {
            val data = section.obj()
            for (group in data.getValue("groups").arr()) for (clip in group.obj().getValue("clips").arr()) {
                val item = clip as JsonValue.Obj
                put(data.getValue("id").str() + "/" + item.entries.getValue("id").str(), item)
            }
        }
    }

    private fun comparisons(root: File, baseline: Baseline, current: Map<String, JsonValue.Obj>): Comparison {
        val copied = linkedMapOf<String, JsonValue>()
        var wavFiles = 0
        val commit = baseline.manifest.getValue("sourceCommit")
        val pairs = comparisonKeys.map { key ->
            val before = LinkedHashMap(baseline.clips.getValue(key).entries)
            val after = LinkedHashMap(current.getValue(key).entries)
            for (field in listOf("voice", "macros", "midi", "velocity", "seed", "oversample", "held", "renderSettings")) {
                require(before[field] == after[field]) { "$key: R1/R2 $field differs; comparisons require the exact same patch and render settings" }
            }
            // Publish only the matched snapshot, its repeated seams, the archival patch and its original trace.
            before.remove("raw")
            before.remove("native")
            for (field in listOf("matched", "repeat", "patch", "diagnostics")) {
                val value = before[field]
                if (value == null || value == JsonValue.Null) continue
                val relative = value.str()
                val source = snapshotFile(baseline.root, relative)
                val expected = baseline.hashes[relative] ?: error("R1 asset has no recorded checksum: $relative")
                require(sha256(source) == expected) { "R1 source changed during rendering: $relative" }
                val path = "R1_BASELINE/$relative"
                val target = File(root, path).also { it.parentFile.mkdirs() }
                // Baseline files are read-only; replacing a prior copied snapshot needs a fresh writable destination.
                if (target.exists()) require(target.delete()) { "cannot replace prior snapshot: $path" }
                source.copyTo(target)
                require(sha256(target) == expected) { "R1 copy differs: $path" }
                before[field] = str(path)
                copied[path] = obj("sha256" to str(expected), "bytes" to num(target.length()))
                if (relative.endsWith(".wav")) wavFiles++
            }
            before["revision"] = str("R1")
            before["sourceCommit"] = commit
            before["snapshot"] = bool(true)
            before["comparisonOnly"] = bool(true)
            after["revision"] = str("R2")
            after["snapshot"] = bool(false)
            after["comparisonOnly"] = bool(true)
            val source = before.getValue("name").str()
            obj("id" to str(key.lowercase(Locale.ROOT).replace('/', '_')), "sourceKey" to str(key),
                "label" to str(if (key.startsWith("DEFAULTS/")) "$source · voice defaults" else source),
                "before" to JsonValue.Obj(before), "after" to JsonValue.Obj(after))
        }
        val provenancePath = "R1_BASELINE/provenance.json"
        val provenance = obj("revision" to str("R1"), "sourceCommit" to commit,
            "capturedAt" to baseline.manifest.getValue("capturedAt"),
            "sourceManifestSha256" to baseline.manifest.getValue("sourceManifestSha256"),
            "note" to str("Original R1 render snapshots. Patch JSON is archival provenance; the current engine does not reproduce these R1 sounds. WAV checksums refer to the decoded original bytes."),
            "assets" to JsonValue.Obj(copied))
        File(root, provenancePath).writeText(Json.write(provenance) + "\n")
        return Comparison(pairs, wavFiles, obj("revision" to str("R1"), "sourceCommit" to commit,
            "sourceManifestSha256" to baseline.manifest.getValue("sourceManifestSha256"), "provenance" to str(provenancePath)))
    }

    private fun snapshotFile(root: File, relative: String): File {
        require(relative.isNotBlank() && !File(relative).isAbsolute) { "unsafe R1 asset path" }
        val file = File(root, relative).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.isFile) { "missing or escaping R1 asset: $relative" }
        return file
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class Stats(val peak: Double, val rms: Double, val dc: Double) {
        fun json() = obj("peak" to num(peak), "rms" to num(rms), "dc" to num(dc))
    }
    private fun stats(samples: FloatArray): Stats {
        var peak = 0.0; var energy = 0.0; var sum = 0.0
        for (sample in samples) { val v = sample.toDouble(); peak = maxOf(peak, abs(v)); energy += v * v; sum += v }
        return Stats(peak, sqrt(energy / samples.size.coerceAtLeast(1)), sum / samples.size.coerceAtLeast(1))
    }
    private fun sampleDistance(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        var energy = 0.0
        for (i in 0 until n) { val d = a[i] - b[i].toDouble(); energy += d * d }
        return sqrt(energy / n.coerceAtLeast(1))
    }
    /** Known-note diagnostic fallback; the generic detector intentionally excludes the C1 register. */
    private fun lowPitch(snip: Snip, expected: Double): Pair<Double, Double>? {
        val stride = (snip.sampleRate / 8000).coerceAtLeast(1)
        val rate = snip.sampleRate.toDouble() / stride
        val start = (.35 * snip.sampleRate).toInt()
        val n = minOf((.4 * rate).toInt(), (snip.samples.size - start) / stride)
        if (n <= 0) return null
        val values = DoubleArray(n) { snip.samples[start + it * stride].toDouble() }
        val lo = (rate / (expected * 1.4)).toInt().coerceAtLeast(2)
        val hi = (rate / (expected / 1.4)).toInt().coerceAtMost(n / 2)
        if (hi <= lo + 2) return null
        val correlation = DoubleArray(hi + 1)
        for (lag in lo..hi) {
            var xy = 0.0; var aa = 0.0; var bb = 0.0
            for (i in 0 until n - lag) { xy += values[i] * values[i + lag]; aa += values[i] * values[i]; bb += values[i + lag] * values[i + lag] }
            correlation[lag] = xy / sqrt(aa * bb).coerceAtLeast(1e-20)
        }
        val best = (lo + 1 until hi).filter { correlation[it] >= correlation[it - 1] && correlation[it] >= correlation[it + 1] }
            .maxByOrNull { correlation[it] } ?: return null
        if (correlation[best] < .3) return null
        val curvature = correlation[best - 1] - 2 * correlation[best] + correlation[best + 1]
        val offset = if (abs(curvature) < 1e-12) 0.0 else (.5 * (correlation[best - 1] - correlation[best + 1]) / curvature).coerceIn(-.5, .5)
        return rate / (best + offset) to correlation[best].coerceIn(0.0, 1.0)
    }
    private fun voiceDescription(v: SkitterVoice) = when (v) {
        SkitterVoice.BASS -> "Compact bass with a recovering edge."
        SkitterVoice.LEAD -> "Sharp, briefly tearing melodic tone."
        SkitterVoice.HOLLOW -> "Open intervals and nasal resonance."
        SkitterVoice.FLUTTER -> "Repeated departures and filter movement."
        SkitterVoice.CHIRP -> "Resonant chirps around a pitched sustain."
        SkitterVoice.FRAY -> "Dense, noisy, agitated melodic texture."
    }
    private fun macroDescription(macro: String) = when (macro) {
        "BUZZ" -> "Waveform edges and pulse-width character provoke the filter."
        "SKITTER" -> "Unequal pitch departures, recovery and recurrence."
        "AGITATE" -> "Signal-history sensitivity and bounded filter flutter."
        "VENT" -> "Coherent low-pass / band-pass / high-pass blend."
        else -> "Audible noise and noise-driven filter perturbation."
    }
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun tag(v: Float) = (v * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(v: Float) = "%.2f".format(Locale.ROOT, v)
    private fun slug(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun obj(vararg pairs: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*pairs))
    private fun arr(values: List<JsonValue>) = JsonValue.Arr(values)
    private fun str(v: String) = JsonValue.Str(v)
    private fun num(v: Number) = if (v.toDouble().isFinite()) JsonValue.Num(v.toDouble()) else JsonValue.Null
    private fun bool(v: Boolean) = JsonValue.Bool(v)
    private fun nullable(v: Number?) = v?.let(::num) ?: JsonValue.Null
}
