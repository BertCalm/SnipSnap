package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Actual Kotlin DSP evidence for the provisional Lacquer listening page. Each synthesis is
 * exported once at native level and once at an independent, declared comparison level.
 * A diagnostic solo keeps its original role gain in the native file; its matched file is
 * independently raised so the quieter support can be heard. No browser synthesis is used.
 *
 * Run :synth:generateLacquerAudition. One positional destination or --out destination is
 * accepted; --quick renders the eight finite/held preset pairs for a tooling smoke check.
 * This pack records measurements and leaves sonic acceptance to the owner.
 */
object LacquerAuditionGenerator {
    private const val RATE = 44_100
    // Report the shared AuditionLevel contract; the helper itself applies the gain.
    private const val TARGET = .03f
    private const val CEILING = .99f
    private val timbral = listOf("RELIEF", "SHEEN", "BODY", "CONTOUR", "GLUE")
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val neutral = linkedMapOf("RELIEF" to .30f, "SHEEN" to .45f, "BODY" to .50f,
        "CONTOUR" to .45f, "GLUE" to 0f, "HOLD" to 0f, "TUNE" to .5f)

    private data class Case(
        val id: String,
        val label: String,
        val voice: LacquerVoice,
        val macros: Map<String, Float>,
        val description: String,
        val kind: String,
        val group: String,
        val note: Int = 48,
        val velocity: Float = 1f,
        val presetId: String? = null,
        val profile: LacquerProfile = Lacquer.profileFor(voice),
        val macro: String? = null,
        val value: Float? = null,
        val macroB: String? = null,
        val valueB: Float? = null,
        val layer: LacquerRole? = null,
        val seconds: Float? = null,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val repository = repositoryRoot()
        var destination: String? = null
        var quick = false
        var index = 0
        while (index < args.size) {
            when {
                args[index] == "--quick" -> quick = true
                args[index] == "--out" -> {
                    require(index + 1 < args.size) { "--out needs a directory" }
                    require(destination == null) { "supply one destination" }
                    destination = args[++index]
                }
                args[index].startsWith("--out=") -> {
                    require(destination == null) { "supply one destination" }
                    destination = args[index].substringAfter('=')
                }
                args[index].startsWith("--") -> error("unknown argument ${args[index]}")
                else -> {
                    require(destination == null) { "supply one destination" }
                    destination = args[index]
                }
            }
            index++
        }
        val root = destination?.let(::File) ?: File(repository, "testkit/lacquer-audition")
        root.mkdirs()
        val presets = LacquerPresets.all()
        val cases = presetCases(presets, quick) + if (quick) emptyList() else diagnosticCases()
        require(cases.map { it.id }.distinct().size == cases.size) { "duplicate audition IDs" }
        val evidence = mutableListOf<Map<String, Any?>>()
        val waveformHashes = linkedMapOf<String, String>()
        val sweepReferences = mutableMapOf<String, FloatArray>()
        val started = System.nanoTime()
        var renderNanos = 0L
        var maxHeapBytes = 0L
        var determinismChecks = 0

        for ((caseIndex, case) in cases.withIndex()) {
            val before = System.nanoTime()
            val probe = Lacquer.renderProbe(case.voice, case.macros, case.note, case.velocity,
                case.profile, seconds = case.seconds, normalize = false, solo = case.layer)
            val nanos = System.nanoTime() - before
            renderNanos += nanos
            val samples = probe.samples
            require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "${case.id}: empty or non-finite audio" }
            val held = (case.macros["HOLD"] ?: 0f) >= .99f
            val raw = Snip(samples, channels = 1, sampleRate = RATE)
            val rawMetrics = metrics(raw, case.note, held)
            val peak = rawMetrics.getValue("peak") as Double
            // PCM never clips silently. Preserve pre-export metrics even if a safety gain is needed.
            val nativeGain = if (peak > .99) (.99 / peak).toFloat() else 1f
            val native = scale(raw, nativeGain)
            val matched = AuditionLevel.level(raw)
            val matchedGain = if (peak > 1e-12) (matched.peak() / peak).toFloat() else 0f
            val nativePath = "audio/${case.id}-native.wav"
            val matchedPath = "audio/${case.id}-matched.wav"
            WavWriter.write(File(root, nativePath), native, WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_16)
            val seam = if (held && probe.previousCycle.size >= 256) {
                Keys.seamError(probe.previousCycle + samples, probe.previousCycle.size)
            } else null
            require(seam == null || seam.isFinite()) { "${case.id}: non-finite seam metric" }
            val sourceHash = floatHash(samples)
            waveformHashes[case.id] = sourceHash
            var deterministic: Boolean? = null
            if (case.kind == "preset" && case.note == (if (case.voice == LacquerVoice.BASS) 36 else 48) && case.velocity == 1f) {
                val repeated = Lacquer.renderProbe(case.voice, case.macros, case.note, case.velocity,
                    case.profile, seconds = case.seconds, normalize = false, solo = case.layer)
                deterministic = samples.contentEquals(repeated.samples) && probe.onsetSeconds == repeated.onsetSeconds
                require(deterministic) { "${case.id}: repeated complete inputs changed audio or onsets" }
                determinismChecks++
            }
            val sweepKey = if (case.kind == "sweep" || case.kind == "held-relief") "${case.group}:${case.note}:${case.velocity}" else null
            val distance = if (sweepKey != null) {
                if (case.value == 0f) sweepReferences[sweepKey] = matched.samples.copyOf()
                sweepReferences[sweepKey]?.let { waveformDistance(it, matched.samples) }
            } else null
            val row = linkedMapOf<String, Any?>(
                "id" to case.id, "label" to case.label, "description" to case.description,
                "voice" to case.voice.name, "kind" to case.kind, "group" to case.group,
                "presetId" to case.presetId, "macros" to case.macros, "note" to case.note,
                "velocity" to case.velocity, "hold" to held, "layer" to case.layer?.name,
                "macro" to case.macro, "value" to case.value, "macroA" to case.macro,
                "macroB" to case.macroB, "valueB" to case.valueB,
                "file" to matchedPath, "matchedFile" to matchedPath, "nativeFile" to nativePath,
                "duration" to samples.size.toDouble() / RATE, "frames" to samples.size,
                "requestedDuration" to case.seconds, "onsetSeconds" to probe.onsetSeconds.mapKeys { it.key.name },
                "layerEndSeconds" to probe.layerEndSeconds.mapKeys { it.key.name },
                "requestedHz" to probe.requestedHz, "renderedHz" to probe.renderedHz,
                "prerollCycles" to probe.prerollCycles,
                "profile" to LacquerPatch(case.label, case.voice, case.macros, case.note, case.velocity, case.profile)
                    .toJsonValue().entries.getValue("profile"),
                "oversampledMetrics" to basicMetrics(probe.raw),
                "rawMetrics" to rawMetrics, "nativeMetrics" to metrics(native, case.note, held),
                "matchedMetrics" to metrics(matched, case.note, held),
                "nativeExportGain" to nativeGain, "matchedGain" to matchedGain,
                "seamError" to seam, "unwrappedSeamError" to if (held) probe.unwrappedSeamError else null,
                "renderMilliseconds" to nanos / 1_000_000.0,
                "renderMsPerAudioSecond" to nanos / 1_000_000.0 / raw.durationSeconds,
                "deterministicRerender" to deterministic, "matchedDistanceFromZero" to distance,
                "waveformSha256" to sourceHash,
                "nativeSha256" to fileHash(File(root, nativePath)), "matchedSha256" to fileHash(File(root, matchedPath)),
            )
            evidence += row
            val runtime = Runtime.getRuntime()
            maxHeapBytes = maxOf(maxHeapBytes, runtime.totalMemory() - runtime.freeMemory())
            if ((caseIndex + 1) % 10 == 0 || caseIndex == cases.lastIndex) {
                println("Lacquer audition: ${caseIndex + 1}/${cases.size} syntheses, ${(caseIndex + 1) * 2} WAVs")
            }
        }

        val sourceFiles = File(repository, "synth/src").walkTopDown().filter {
            it.isFile && it.name.startsWith("Lacquer") && it.extension == "kt"
        }.sortedBy { it.relativeTo(repository).path }.associate {
            it.relativeTo(repository).invariantSeparatorsPath to fileHash(it)
        }
        val sourceFingerprint = stringHash(sourceFiles.entries.joinToString("\n") { "${it.key}:${it.value}" })
        val gitRevision = runCatching {
            ProcessBuilder("git", "rev-parse", "HEAD").directory(repository).start().let { process ->
                val output = process.inputStream.bufferedReader().readText().trim()
                check(process.waitFor() == 0)
                output
            }
        }.getOrDefault("unavailable")
        val kitSource = File(repository, "testkit/SnipSnap-Lacquer-Kit.zip")
        val kitFile = if (kitSource.isFile) kitSource.name.also { name ->
            kitSource.copyTo(File(root, name), overwrite = true)
        } else null
        val report = linkedMapOf<String, Any?>(
            "sonicAcceptance" to "Pending owner listening; these measurements do not establish sonic approval.",
            "coverage" to if (quick) "Tooling smoke check: eight finite/held preset pairs." else "Eight recipes, six voices, MIDI 36/48/60/72, velocities 0.35/0.70/1.00, five-point macro sweeps, independent role solos, four 3×3 interaction grids, all-high extremes, late-onset duration boundaries and difficult held loops.",
            "synthesisCount" to evidence.size, "wavCount" to evidence.size * 2,
            "presetCount" to presets.size, "diagnosticCount" to evidence.count { it["presetId"] == null },
            "sampleRate" to RATE, "channels" to 1, "bitDepth" to 16,
            "listeningTarget" to TARGET, "listeningPeakCeiling" to CEILING,
            "levelMatching" to "Each clip is independently matched using shared SnipSnap AuditionLevel: loudest 200-ms low-cut RMS target 0.03, peak ceiling 0.99. Native clips preserve synthesis gain unless the recorded PCM safety gain is below 1. Solo matching is independent and does not express its gain within the full mix.",
            "metricNotes" to "Peak, full-file RMS, DC mean and adjacent-sample step use float samples before PCM quantization. Pitch is a bounded autocorrelation estimate around the requested period, with confidence; it is supporting evidence, not proof of pitch. Keys.seamError compares the preceding settled cycle with the exported held cycle after project-standard wrap handling; unwrappedSeamError separately records the settled cycles before that handling. Waveform distances compare matched samples with zero padding and no time alignment, so timing changes contribute.",
            "gitRevision" to gitRevision, "sourceSha256" to sourceFingerprint, "sourceFiles" to sourceFiles,
            "audioCorpusSha256" to stringHash(waveformHashes.entries.joinToString("\n") { "${it.key}:${it.value}" }),
            "generatedAt" to Instant.now().toString(), "renderSeconds" to renderNanos / 1e9,
            "elapsedSeconds" to (System.nanoTime() - started) / 1e9, "maxObservedHeapBytes" to maxHeapBytes,
            "maxRawPeak" to evidence.maxOf { (it.getValue("rawMetrics") as Map<*, *>)["peak"] as Double },
            "maxAbsoluteDc" to evidence.maxOf { abs((it.getValue("rawMetrics") as Map<*, *>)["dc"] as Double) },
            "nativeSafetyGainCount" to evidence.count { (it.getValue("nativeExportGain") as Float) < 1f },
            "heldClipCount" to evidence.count { it["hold"] == true },
            "maxSeamError" to evidence.mapNotNull { it["seamError"] as Double? }.maxOrNull(),
            "maxUnwrappedSeamError" to evidence.mapNotNull { it["unwrappedSeamError"] as Double? }.maxOrNull(),
            "determinismChecks" to determinismChecks,
            "testStatus" to "Generator verified finite samples, file export and declared deterministic rerenders. Repository test results are recorded separately.",
            "evidenceFile" to "evidence.json", "metricsFile" to "metrics.csv",
            "kitFile" to kitFile, "kitSha256" to kitFile?.let { fileHash(File(root, it)) },
            "kitPlayback" to "The kit is an MPC drum program: every pad plays its sample once, including held material. It is not a sustained keygroup instrument.",
        )
        val presetRows = presets.map { preset ->
            val id = slug(preset.name)
            linkedMapOf<String, Any?>("id" to id, "name" to preset.name, "voice" to preset.voice.name,
                "description" to presetDescription(preset.voice), "macros" to preset.macros,
                "clips" to evidence.filter { it["presetId"] == id })
        }
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "Lacquer", "sampleRate" to RATE,
            "revision" to "${gitRevision.take(12)} / ${sourceFingerprint.take(12)}",
            "presets" to presetRows, "diagnostics" to evidence.filter { it["presetId"] == null },
            "report" to report,
        )
        writeJson(File(root, "audition.json"), manifest)
        writeJson(File(root, "evidence.json"), linkedMapOf("report" to report, "clips" to evidence))
        writeCsv(File(root, "metrics.csv"), evidence)
        copyPageAssets(repository, root)
        pruneOldAudio(root, evidence.flatMap { listOf(it.getValue("nativeFile") as String, it.getValue("matchedFile") as String) }.toSet())
        println("Wrote ${evidence.size * 2} actual Kotlin-rendered WAVs and audition/evidence/metrics under ${root.canonicalPath}")
    }

    private fun presetCases(presets: List<LacquerPatch>, quick: Boolean): List<Case> = buildList {
        for (preset in presets) {
            val id = slug(preset.name)
            val base = if (preset.voice == LacquerVoice.BASS) 36 else 48
            val finite = preset.macros + ("HOLD" to 0f)
            add(Case("$id-default", "${noteName(base)} · velocity 1.00", preset.voice, finite,
                "Saved timbral recipe; finite note at its audition register.", "preset", preset.name,
                base, 1f, id, preset.profile))
            if (!quick) {
                for (note in listOf(36, 48, 60, 72).filter { it != base }) {
                    add(Case("$id-note-$note", "${noteName(note)} · velocity 1.00", preset.voice, finite,
                        "Same saved recipe in another register, without an additional chord.", "note", preset.name,
                        note, 1f, id, preset.profile))
                }
                for (velocity in listOf(.35f, .70f)) {
                    add(Case("$id-velocity-${tag(velocity)}", "${noteName(base)} · velocity ${fmt(velocity)}", preset.voice, finite,
                        "Same saved recipe with a quieter source gesture; matched playback makes articulation easier to compare.",
                        "velocity", preset.name, base, velocity, id, preset.profile))
                }
            }
            add(Case("$id-held", "${noteName(base)} · held loop", preset.voice, preset.macros + ("HOLD" to 1f),
                "Settled sustained region, with the isolated attack excluded. RELIEF offsets periodic layer development.",
                "held", preset.name, base, 1f, id, preset.profile))
        }
    }

    private fun diagnosticCases(): List<Case> = buildList {
        for (macro in timbral) for (value in steps) {
            add(Case("sweep-key-${macro.lowercase()}-${tag(value)}", "KEY · $macro ${fmt(value)}", LacquerVoice.KEY,
                neutral + (macro to value), "Five-point $macro sweep; companion controls use the declared neutral values.",
                "sweep", "$macro · KEY finite", macro = macro, value = value))
        }
        for (voice in listOf(LacquerVoice.KEY, LacquerVoice.SILK)) {
            if (voice == LacquerVoice.SILK) for (value in steps) {
                add(Case("sweep-silk-relief-${tag(value)}", "SILK · RELIEF ${fmt(value)}", voice,
                    neutral + ("RELIEF" to value), "Finite SILK layer arrival comparison at neutral companion controls.",
                    "sweep", "RELIEF · SILK finite", macro = "RELIEF", value = value))
            }
            for (value in steps) {
                add(Case("held-${voice.name.lowercase()}-relief-${tag(value)}", "${voice.name} held · RELIEF ${fmt(value)}", voice,
                    neutral + mapOf("RELIEF" to value, "HOLD" to 1f), "Settled loop-only comparison. Independent periodic contours retain RELIEF activity after initial attacks.",
                    "held-relief", "RELIEF · ${voice.name} held", macro = "RELIEF", value = value))
            }
            for (role in Lacquer.profileFor(voice).layers.map { it.role }) {
                add(Case("solo-${voice.name.lowercase()}-${role.name.lowercase()}", "${voice.name} · ${role.name} solo", voice,
                    Lacquer.defaults(voice) + ("RELIEF" to .8f), "One independent source/envelope role through the same finishing path. Native keeps its blend gain; matched independently raises its detail for listening.",
                    "solo", "Independent layers · ${voice.name}", layer = role))
            }
        }
        for ((a, b) in listOf("RELIEF" to "CONTOUR", "RELIEF" to "SHEEN", "BODY" to "GLUE", "SHEEN" to "GLUE")) {
            for (va in listOf(0f, .5f, 1f)) for (vb in listOf(0f, .5f, 1f)) {
                add(Case("grid-${a.lowercase()}-${b.lowercase()}-${tag(va)}-${tag(vb)}", "$a ${fmt(va)} · $b ${fmt(vb)}", LacquerVoice.KEY,
                    neutral + mapOf(a to va, b to vb), "KEY 3 × 3 interaction grid; other controls use neutral values.",
                    "interaction", "$a × $b", macro = a, value = va, macroB = b, valueB = vb))
            }
        }
        for (voice in LacquerVoice.entries) {
            val note = if (voice == LacquerVoice.BASS) 36 else 48
            val high = Lacquer.defaults(voice) + timbral.associateWith { 1f }
            add(Case("extreme-${voice.name.lowercase()}-all-high", "${voice.name} · all five macros high", voice,
                high + ("HOLD" to 0f), "All five timbral controls at one, strong velocity, before shared leveling.",
                "extreme", "All-high combinations", note))
            for (seconds in listOf(.12f, .30f)) {
                add(Case("boundary-${voice.name.lowercase()}-${tag(seconds)}", "${voice.name} · ${fmt(seconds)} seconds", voice,
                    Lacquer.defaults(voice) + mapOf("RELIEF" to 1f, "HOLD" to 0f), "Short requested duration at maximum RELIEF. Onset metadata shows which independent layers can arrive before the render ends.",
                    "boundary", "Latest-layer duration boundaries", note, seconds = seconds))
            }
            add(Case("difficult-hold-${voice.name.lowercase()}", "${voice.name} · difficult held loop", voice,
                high + ("HOLD" to 1f), "All timbral macros high, velocity one; inspect seam, pitch, raw peak and cyclic development.",
                "difficult-hold", "Difficult held cases", note))
        }
    }

    private fun metrics(snip: Snip, note: Int, held: Boolean): Map<String, Any?> {
        val samples = snip.samples
        var sum = 0.0
        var square = 0.0
        var peak = 0.0
        var step = 0.0
        for (i in samples.indices) {
            val value = samples[i].toDouble()
            sum += value
            square += value * value
            peak = maxOf(peak, abs(value))
            if (i > 0) step = maxOf(step, abs(value - samples[i - 1]))
        }
        val rms = sqrt(square / samples.size)
        val pitch = pitch(samples, note)
        return linkedMapOf("peak" to peak, "rms" to rms, "dc" to sum / samples.size,
            "loudness" to Loudness.of(snip), "crestFactor" to if (rms > 1e-12) peak / rms else null,
            "maxAdjacentStep" to step, "wrapStep" to if (held) abs(samples.first().toDouble() - samples.last()) else null,
            "earlyRms" to windowRms(samples, 0, minOf(samples.size, RATE / 5)),
            "tailRms" to windowRms(samples, maxOf(0, samples.size - RATE / 5), samples.size),
            "requestedHz" to frequency(note), "estimatedPitchHz" to pitch?.first,
            "pitchConfidence" to pitch?.second, "pitchCents" to pitch?.let { 1200 * ln(it.first / frequency(note)) / ln(2.0) })
    }

    private fun basicMetrics(samples: FloatArray): Map<String, Any?> {
        var peak = 0.0
        var square = 0.0
        var sum = 0.0
        var index = 0
        while (index < samples.size) {
            val sample = samples[index].toDouble()
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
            square += sample * sample
            sum += sample
            index++
        }
        return linkedMapOf("sampleRate" to RATE * 4, "frames" to samples.size,
            "peak" to peak, "rms" to sqrt(square / samples.size), "dc" to sum / samples.size)
    }

    /** Expected-period-constrained autocorrelation, supporting evidence rather than a classifier. */
    private fun pitch(samples: FloatArray, note: Int): Pair<Double, Double>? {
        val expected = RATE / frequency(note)
        val start = minOf(RATE / 5, samples.size / 4)
        val end = minOf(samples.size, start + RATE / 4)
        val minLag = (expected * .80).roundToInt().coerceAtLeast(2)
        val maxLag = (expected * 1.25).roundToInt().coerceAtMost((end - start) / 3)
        if (maxLag < minLag || end - start < 512) return null
        val mean = samples.sliceArray(start until end).sumOf { it.toDouble() } / (end - start)
        var best = -1.0
        var bestLag = minLag
        for (lag in minLag..maxLag) {
            var xy = 0.0
            var xx = 0.0
            var yy = 0.0
            for (i in start until end - lag step 2) {
                val x = samples[i] - mean
                val y = samples[i + lag] - mean
                xy += x * y
                xx += x * x
                yy += y * y
            }
            val confidence = if (xx * yy > 1e-20) xy / sqrt(xx * yy) else 0.0
            if (confidence > best) { best = confidence; bestLag = lag }
        }
        if (best < .45) return null
        return RATE.toDouble() / bestLag to best
    }

    private fun windowRms(samples: FloatArray, start: Int, end: Int): Double {
        if (end <= start) return 0.0
        var energy = 0.0
        for (i in start until end) energy += samples[i].toDouble() * samples[i]
        return sqrt(energy / (end - start))
    }

    private fun waveformDistance(a: FloatArray, b: FloatArray): Double {
        var energy = 0.0
        val count = maxOf(a.size, b.size)
        for (i in 0 until count) {
            val difference = (a.getOrElse(i) { 0f } - b.getOrElse(i) { 0f }).toDouble()
            energy += difference * difference
        }
        return sqrt(energy / count)
    }

    private fun scale(snip: Snip, gain: Float) = Snip(FloatArray(snip.samples.size) { snip.samples[it] * gain }, 1, RATE)
    private fun frequency(note: Int) = 440.0 * 2.0.pow((note - 69) / 12.0)
    private fun noteName(note: Int) = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")[note % 12] + (note / 12 - 1)
    private fun slug(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun tag(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun fmt(value: Float) = String.format(Locale.ROOT, "%.2f", value)
    private fun presetDescription(voice: LacquerVoice) = when (voice) {
        LacquerVoice.KEY -> "A clear pitched body with a brighter articulated edge and softer trailing support."
        LacquerVoice.GLASS -> "A glass-like upper edge over a steady root and warm support."
        LacquerVoice.BRASS -> "A rounded synthetic brass-like body with a controlled bright arrival."
        LacquerVoice.BASS -> "Low body and a compact defining edge, with restrained trailing support."
        LacquerVoice.PLUCK -> "A tight plucked arrival with differentiated source and envelope roles."
        LacquerVoice.SILK -> "A broad layered sustain with softer trailing development."
    }

    private fun repositoryRoot(): File {
        val cwd = File(".").canonicalFile
        return if (cwd.name == "synth") cwd.parentFile else cwd
    }

    private fun copyPageAssets(repository: File, root: File) {
        val assets = File(repository, "synth/src/test/resources/lacquer-audition")
        if (!assets.isDirectory) {
            println("Page assets not present yet at ${assets.path}; WAVs and manifests are complete")
            return
        }
        for (asset in assets.walkTopDown().filter { it.isFile }) {
            val output = File(root, asset.relativeTo(assets).path)
            output.parentFile.mkdirs()
            asset.copyTo(output, overwrite = true)
        }
    }

    private fun pruneOldAudio(root: File, keep: Set<String>) {
        val audio = File(root, "audio")
        if (!audio.isDirectory) return
        for (file in audio.listFiles().orEmpty()) {
            val path = "audio/${file.name}"
            if (file.isFile && Regex("[a-z0-9-]+-(native|matched)\\.wav").matches(file.name) && path !in keep) {
                check(file.delete()) { "could not remove stale generated file $path" }
            }
        }
    }

    private fun writeCsv(file: File, rows: List<Map<String, Any?>>) {
        val lines = mutableListOf("id,voice,kind,note,velocity,hold,seconds,raw_peak,raw_rms,raw_dc,raw_loudness,native_gain,matched_gain,seam_error,unwrapped_seam_error,pitch_hz,pitch_cents,pitch_confidence,render_ms,waveform_sha256,native_sha256,matched_sha256")
        for (row in rows) {
            val metric = row.getValue("rawMetrics") as Map<*, *>
            lines += listOf(row["id"], row["voice"], row["kind"], row["note"], row["velocity"], row["hold"], row["duration"],
                metric["peak"], metric["rms"], metric["dc"], metric["loudness"], row["nativeExportGain"], row["matchedGain"], row["seamError"], row["unwrappedSeamError"],
                metric["estimatedPitchHz"], metric["pitchCents"], metric["pitchConfidence"], row["renderMilliseconds"],
                row["waveformSha256"], row["nativeSha256"], row["matchedSha256"]).joinToString(",") { it?.toString() ?: "" }
        }
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun writeJson(file: File, value: Any?) = file.writeText(Json.write(json(value)) + "\n")
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> {
            val number = value.toDouble()
            require(number.isFinite()) { "cannot write non-finite metric" }
            JsonValue.Num(number)
        }
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported evidence value ${value.javaClass.name}")
    }

    private fun stringHash(value: String) = hex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    private fun fileHash(file: File) = hex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
    private fun floatHash(samples: FloatArray): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (sample in samples) {
            val bits = sample.toRawBits()
            hash.update((bits and 255).toByte())
            hash.update((bits ushr 8 and 255).toByte())
            hash.update((bits ushr 16 and 255).toByte())
            hash.update((bits ushr 24 and 255).toByte())
        }
        return hex(hash.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}
