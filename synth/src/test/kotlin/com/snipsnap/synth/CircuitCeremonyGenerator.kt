package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One continuous performance, one shared output gain, and its authored/mechanical evidence. */
object CircuitCeremonyGenerator {
    private const val WEB_GAIN = 4.0
    private const val WEB_LEVEL = .12
    private const val WEB_PEAK = .90
    private val sourcePaths = listOf(
        "synth/src/main/kotlin/com/snipsnap/synth/Circuit.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/CircuitPhrase.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/CircuitCeremony.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/CircuitInstruments.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/CircuitCeremonyGenerator.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 &&
            args.all { !it.startsWith("--") || it.startsWith("--inputs=") }) {
            "Usage: CircuitCeremonyGenerator [output-directory] [--inputs=source-manifest.json]"
        }
        val out = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/circuit-ceremony")
        require(listOf("raw.wav", "matched.wav", "ceremony.json").none { File(out, it).exists() }) {
            "Preserve the existing ceremony artifacts; choose a new output directory"
        }
        require(out.isDirectory || out.mkdirs()) { "Cannot create ceremony output directory: $out" }
        val repo = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, sourcePaths.first()).isFile }
            ?: error("Run from the source checkout or its synth directory to record source pins")
        val inputName = args.singleOrNull { it.startsWith("--inputs=") }?.substringAfter('=')
            ?: System.getProperty("circuit.ceremony.inputs") ?: System.getenv("CIRCUIT_CEREMONY_INPUTS")
        val inputFile = inputName?.let(::File)
        val supplied = inputFile?.let { Json.parse(it.readText()).obj() }
        val suppliedPins = (supplied?.get("sourceSha256") ?: supplied?.get("source_sha256"))?.obj()
        val pins = sourcePaths.associateWith { path ->
            val actual = sha256(File(repo, path))
            suppliedPins?.get(path)?.let { require(it.str() == actual) { "Source pin mismatch: $path" } }
            actual
        }
        if (suppliedPins != null) require(sourcePaths.take(6).all { it in suppliedPins }) {
            "Supplied source pins must include Circuit, Phrase, Ceremony, Instruments, Dsp and this generator"
        }
        val sourceCommit = git(repo, "rev-parse", "HEAD")
        val sourceDirty = git(repo, "status", "--porcelain")?.isNotEmpty()
        val macros = Circuit.defaults(CircuitVoice.ROOT) + ("TUNE" to 1f)
        require(Circuit.midiFor(macros.getValue("TUNE")) == 60)
        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        val started = System.nanoTime()
        // The only synthesis call. No stems, cell joins, speed changes or repeated renders.
        val report = Circuit.inspectCeremony()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        val heapAfter = runtime.totalMemory() - runtime.freeMemory()
        validate(report)
        val raw = Snip(report.raw, channels = 1, sampleRate = Dsp.RATE)
        val rawStats = stats(raw)
        val rawLevel = Loudness.of(raw).toDouble()
        require(rawLevel > 1e-8 && rawStats.peak > 1e-8) { "Ceremony is silent" }
        // AuditionLevel's low-cut/loudest-200ms convention: .03 canonical becomes
        // .12 on the listening site at gain 4. A single peak guard joins that gain.
        val desiredGain = WEB_LEVEL / WEB_GAIN / rawLevel
        val peakLimitedGain = WEB_PEAK / WEB_GAIN / rawStats.peak
        val masterGain = min(desiredGain, peakLimitedGain).toFloat()
        require(masterGain.isFinite() && masterGain > 0f)
        val matched = Snip(FloatArray(report.raw.size) { report.raw[it] * masterGain }, 1, Dsp.RATE)
        val matchedStats = stats(matched)
        val matchedLevel = Loudness.of(matched).toDouble()
        require(matchedStats.peak * WEB_GAIN <= WEB_PEAK + 1e-6) { "Web master would clip" }
        require(abs(rawStats.dc) <= rawStats.rms * .01) { "Excessive ceremony DC" }
        require(rawStats.lastQuarterSecondRms <= rawStats.rms * .01) { "Final acoustic tail has not settled" }
        require(sourcePaths.all { sha256(File(repo, it)) == pins.getValue(it) }) {
            "Source changed during the render; preserve this run and compile the frozen source before retrying"
        }
        val rawFile = File(out, "raw.wav")
        val matchedFile = File(out, "matched.wav")
        WavWriter.write(rawFile, raw, WavWriter.BitDepth.PCM_24)
        WavWriter.write(matchedFile, matched, WavWriter.BitDepth.PCM_24)
        val body = obj(
            "engine" to str("CIRCUIT"), "formatVersion" to num(1), "complete" to bool(true),
            "mode" to str("continuous-finite-ceremony"), "title" to str(CircuitCeremony.title),
            "renderInvocations" to num(1), "voice" to str("ROOT"), "velocity" to num(1),
            "rootMidi" to num(60), "rootHz" to num(Circuit.frequencyFor(1f)),
            "macros" to JsonValue.Obj(macros.mapValues { num(it.value) }),
            "sourceCommit" to (sourceCommit?.let(::str) ?: JsonValue.Null),
            "sourceDirty" to (sourceDirty?.let(::bool) ?: JsonValue.Null),
            "sourceRoot" to str(repo.path), "sourceSha256" to JsonValue.Obj(pins.mapValues { str(it.value) }),
            "compiledSourceBinding" to str("Runner must bind these frozen source pins to the compiled classes and final commit."),
            "inputManifest" to (inputFile?.let {
                obj("path" to str(it.canonicalPath), "sha256" to str(sha256(it)))
            } ?: JsonValue.Null),
            "render" to obj("sampleRate" to num(Dsp.RATE), "channels" to num(1),
                "frames" to num(report.raw.size), "durationSeconds" to num(CircuitCeremony.seconds),
                "continuous" to bool(true), "joinedSegments" to num(0), "resampled" to bool(false),
                "elapsedMs" to num(elapsedMs), "heapBeforeBytes" to num(heapBefore),
                "heapAfterBytes" to num(heapAfter),
                "memoryMeasure" to str("Used heap at render boundaries; not a peak-memory measurement")),
            "authored" to authored(),
            "matching" to obj("meter" to str("Loudness.of: loudest 200ms after low cut; AuditionLevel convention"),
                "targetCanonicalLoudness" to num(WEB_LEVEL / WEB_GAIN), "targetWebLoudness" to num(WEB_LEVEL),
                "webGain" to num(WEB_GAIN), "canonicalPeakCeiling" to num(WEB_PEAK / WEB_GAIN),
                "webPeakCeiling" to num(WEB_PEAK), "rawToMatchedGain" to num(masterGain),
                "rawToWebGain" to num(masterGain * WEB_GAIN), "desiredGainBeforePeakGuard" to num(desiredGain),
                "peakGuardApplied" to bool(peakLimitedGain < desiredGain),
                "measuredCanonicalLoudness" to num(matchedLevel), "predictedWebLoudness" to num(matchedLevel * WEB_GAIN),
                "predictedWebPeak" to num(matchedStats.peak * WEB_GAIN),
                "oneGainForEntireRecording" to bool(true), "perSectionNormalization" to bool(false)),
            "raw" to rawStats.json(rawFile, rawLevel), "matched" to matchedStats.json(matchedFile, matchedLevel),
            "recoveries" to num(report.recoveries), "loop" to JsonValue.Null,
            "eventCount" to num(report.events.size), "pathCount" to num(report.paths.size),
            "rates" to obj("orbitHz" to num(report.rates.orbitHz), "paceHz" to num(report.rates.paceHz),
                "requestedOrbitHz" to num(report.rates.requestedOrbitHz),
                "requestedPaceHz" to num(report.rates.requestedPaceHz), "loopSeconds" to num(report.rates.loopSeconds)),
            "events" to arr(report.events.map { e ->
                obj("source" to num(e.source), "kind" to str(e.kind), "timeSeconds" to num(e.timeSeconds),
                    "energy" to num(e.energy), "reply" to bool(e.reply), "depth" to num(e.depth),
                    "trigger" to str(e.reason), "emittedSeconds" to nullable(e.emittedSeconds),
                    "observerCueSeconds" to nullable(e.observerCueSeconds), "cueSeconds" to nullable(e.cueSeconds),
                    "cueSource" to (e.cueSource?.let(::num) ?: JsonValue.Null),
                    "reflection" to (e.reflection?.let(::num) ?: JsonValue.Null))
            }),
            "paths" to arr(report.paths.map { p ->
                obj("timeSeconds" to num(p.timeSeconds), "source" to num(p.source),
                    "x" to num(p.x), "y" to num(p.y), "directMeters" to num(p.directMeters),
                    "reflectedMeters" to arr(p.reflectedMeters.map(::num)))
            }),
            "ownerListeningAcceptance" to str("pending"),
        )
        File(out, "ceremony.json").writeText(Json.write(body) + "\n")
        println("CIRCUIT ceremony: ${report.raw.size} frames, 60s, C4, ${report.events.size} events, ${report.paths.size} paths")
        println("One global raw-to-matched gain=$masterGain; web gain=$WEB_GAIN; web loudness=${matchedLevel * WEB_GAIN}; web peak=${matchedStats.peak * WEB_GAIN}")
        println("Wrote ${out.canonicalPath}/{raw.wav,matched.wav,ceremony.json}")
    }

    private fun validate(r: Circuit.Report) {
        val frames = (CircuitCeremony.seconds * Dsp.RATE).roundToInt()
        require(r.raw.size == frames && r.snip.samples.size == frames) { "Ceremony must be exactly 60 seconds" }
        require(r.snip.channels == 1 && r.snip.sampleRate == Dsp.RATE && Dsp.RATE == 44_100)
        require(r.recoveries == 0 && r.loop == null && r.rates.loopSeconds == 0.0) { "Ceremony must be finite and need no numerical recovery" }
        require(r.raw.all { it.isFinite() && abs(it) <= 1f } && r.snip.samples.all { it.isFinite() }) { "Invalid ceremony samples" }
        require(abs(r.rates.paceHz - CircuitCeremony.paceHz) < 1e-9) { "Authored clock changed" }
        require(r.events.isNotEmpty() && (3..6).all { source -> r.events.any { it.source == source && it.energy > 0 } })
        require(r.events.all { it.source in 0..6 && it.timeSeconds.isFinite() &&
            it.timeSeconds in 0.0..CircuitCeremony.phraseSeconds && it.energy.isFinite() && it.energy in 0.0..4.0 }) { "Invalid event" }
        require(r.paths.isNotEmpty() && r.paths.all { p ->
            p.source in 0..6 && p.timeSeconds.isFinite() && p.timeSeconds in 0.0..CircuitCeremony.seconds &&
                p.x.isFinite() && p.y.isFinite() && p.directMeters.isFinite() && p.directMeters > 0 &&
                p.reflectedMeters.size == 2 && p.reflectedMeters.all { it.isFinite() && it > p.directMeters }
        }) { "Invalid acoustic path evidence" }
        val replies = r.events.filter { it.reply }
        require(replies.size <= Circuit.MAX_REPLIES && replies.sumOf { it.energy } <= Circuit.MAX_REPLY_ENERGY + 1e-9)
        require(listOf(9, 11).all { cell -> replies.any {
            floor((it.timeSeconds - CircuitPhrase.ORIGIN) / CircuitCeremony.cellSeconds).toInt() == cell
        } }) { "The listening cells must receive actual accepted replies" }
    }

    private fun authored() = obj(
        "seconds" to num(CircuitCeremony.seconds), "phraseSeconds" to num(CircuitCeremony.phraseSeconds),
        "originSeconds" to num(CircuitPhrase.ORIGIN), "releasePadSeconds" to num(CircuitPhrase.RELEASE_PAD),
        "cellSeconds" to num(CircuitCeremony.cellSeconds), "cells" to num(CircuitCeremony.cells),
        "paceHz" to num(CircuitCeremony.paceHz), "clockPolicy" to str("Fixed authored clock; PACE/HOLD recipe values do not change the ceremony"),
        "sharedGainApplication" to str("Force input for all seven sources; passive resonances and room tails are not faded"),
        "cellWindows" to arr((0 until CircuitCeremony.cells).map { cell ->
            obj("index" to num(cell), "startSeconds" to num(CircuitPhrase.ORIGIN + cell * CircuitCeremony.cellSeconds),
                "endSeconds" to num(CircuitPhrase.ORIGIN + (cell + 1) * CircuitCeremony.cellSeconds),
                "replyAllowed" to bool(CircuitCeremony.replyCell(cell)))
        }),
        "stages" to arr(CircuitCeremony.stages.map {
            obj("name" to str(it.name), "startSeconds" to num(it.startSeconds),
                "endSeconds" to num(it.endSeconds), "intent" to str(it.intent))
        }),
        "gainPoints" to arr(CircuitCeremony.gainPoints.map { obj("timeSeconds" to num(it.timeSeconds), "gain" to num(it.gain)) }),
        "replyCells" to arr(CircuitCeremony.replyCells.sorted().map(::num)),
        "omissions" to arr(listOf(
            "Cells 0,1,9,11 omit ordinary vocal affirmation, weak wood and rising rattle.",
            "Cells 16–18 also omit weak wood, rising rattle, weak rattle and settling rattle.",
            "Cell 18 omits its ordinary vocal affirmation; replies remain causal and budgeted.",
        ).map(::str)),
    )

    private data class Stats(val peak: Double, val rms: Double, val dc: Double,
                             val firstSecondRms: Double, val lastSecondRms: Double, val lastQuarterSecondRms: Double) {
        fun json(file: File, loudness: Double) = obj("file" to str(file.name), "bitDepth" to num(24),
            "bytes" to num(file.length()), "sha256" to str(sha256(file)), "peak" to num(peak),
            "rms" to num(rms), "dc" to num(dc), "loudness" to num(loudness),
            "firstSecondRms" to num(firstSecondRms), "lastSecondRms" to num(lastSecondRms),
            "lastQuarterSecondRms" to num(lastQuarterSecondRms))
    }

    private fun stats(snip: Snip): Stats {
        val a = snip.samples
        fun rms(from: Int, until: Int): Double {
            var sum = 0.0
            for (i in from until until) sum += a[i].toDouble() * a[i]
            return sqrt(sum / (until - from))
        }
        return Stats(a.maxOf { abs(it).toDouble() }, rms(0, a.size), a.sumOf { it.toDouble() } / a.size,
            rms(0, Dsp.RATE), rms(a.size - Dsp.RATE, a.size), rms(a.size - Dsp.RATE / 4, a.size))
    }

    private fun git(repo: File, vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) text else null
    } catch (_: java.io.IOException) { null }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun obj(vararg fields: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*fields))
    private fun arr(values: List<JsonValue>) = JsonValue.Arr(values)
    private fun str(value: String) = JsonValue.Str(value)
    private fun bool(value: Boolean) = JsonValue.Bool(value)
    private fun num(value: Number) = JsonValue.Num(value.toDouble())
    private fun nullable(value: Double?) = value?.let(::num) ?: JsonValue.Null
}
