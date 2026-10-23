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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Five real ROOT patches, with one scalar audition gain per complete native render. */
object CircuitPlayableGenerator {
    private const val WEB_GAIN = 4.0
    private const val WEB_LEVEL = .12
    private const val WEB_PEAK = .90
    private val sourcePaths = listOf(
        "synth/src/main/kotlin/com/snipsnap/synth/Circuit.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/CircuitPhrase.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/CircuitRootScore.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/CircuitCeremony.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/CircuitInstruments.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/CircuitPresets.kt",
        "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/CircuitPlayableGenerator.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/FineTuning.kt",
        "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
        "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
    )

    private data class Case(val id: String, val title: String, val preset: String?, val macros: Map<String, Float>)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 &&
            args.all { !it.startsWith("--") || it.startsWith("--inputs=") }) {
            "Usage: CircuitPlayableGenerator [output-directory] [--inputs=source-manifest.json]"
        }
        val out = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/circuit-playable")
        val repo = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, sourcePaths.first()).isFile }
            ?: error("Run from the source checkout or its synth directory to record source pins")
        val inputName = args.singleOrNull { it.startsWith("--inputs=") }?.substringAfter('=')
            ?: System.getProperty("circuit.playable.inputs") ?: System.getenv("CIRCUIT_PLAYABLE_INPUTS")
        val inputFile = inputName?.let(::File)
        val supplied = inputFile?.let { Json.parse(it.readText()).obj() }
        val suppliedPins = (supplied?.get("sourceSha256") ?: supplied?.get("source_sha256"))?.obj()
        val pins = sourcePaths.associateWith { path ->
            val actual = sha256(File(repo, path))
            suppliedPins?.get(path)?.let { require(it.str() == actual) { "Source pin mismatch: $path" } }
            actual
        }
        if (suppliedPins != null) require(sourcePaths.all { it in suppliedPins }) {
            "Supplied source pins must include all playable generator, engine, preset and measurement sources"
        }
        val sourceCommit = git(repo, "rev-parse", "HEAD")
        val sourceDirty = git(repo, "status", "--porcelain")?.isNotEmpty()
        val defaults = Circuit.defaults(CircuitVoice.ROOT)
        val presets = CircuitPresets.forVoice(CircuitVoice.ROOT)
        fun preset(id: String, name: String) = presets.single { it.name == name }.let {
            Case(id, name, name, defaults + it.macros)
        }
        val cases = listOf(
            Case("root-finite", "ROOT finite default", null, defaults),
            Case("root-held", "ROOT held default", null, defaults + ("HOLD" to 1f)),
            preset("fire-circle", "FIRE CIRCLE"),
            preset("night-reply", "NIGHT REPLY"),
            preset("ember-pulse", "EMBER PULSE"),
        )
        require(!File(out, "playable.json").exists() && cases.none { File(out, it.id).exists() }) {
            "Preserve existing playable artifacts; choose a new output directory"
        }
        require(out.isDirectory || out.mkdirs()) { "Cannot create $out" }
        val entries = ArrayList<JsonValue>()
        var allPassed = true
        for (case in cases) {
            require(case.macros.keys == defaults.keys && case.macros.values.all { it.isFinite() && it in 0f..1f })
            require(Circuit.midiFor(case.macros.getValue("TUNE")) == 60) { "Playable examples must start at C4" }
            require(sourcePaths.all { sha256(File(repo, it)) == pins.getValue(it) }) { "Source changed before ${case.id}" }
            val started = System.nanoTime()
            // Exactly one native synthesis call per case. Diagnostic paths come from that same call.
            val report = Circuit.inspect(CircuitVoice.ROOT, case.macros, 1f, Circuit.Probe(recordPaths = true))
            val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
            require(sourcePaths.all { sha256(File(repo, it)) == pins.getValue(it) }) {
                "Source changed during ${case.id}; preserve this run and compile the frozen source before retrying"
            }
            require(report.raw.isNotEmpty() && report.raw.all { it.isFinite() && abs(it) <= 1f }) {
                "${case.id} has invalid raw audio; PCM encoding must not conceal poisoned samples"
            }
            val raw = Snip(report.raw, 1, Dsp.RATE)
            val rawStats = stats(raw)
            val rawLevel = Loudness.of(raw).toDouble()
            require(rawLevel > 1e-8 && rawStats.peak > 1e-8) { "${case.id} is silent" }
            val desiredGain = WEB_LEVEL / WEB_GAIN / rawLevel
            val peakLimitedGain = WEB_PEAK / WEB_GAIN / rawStats.peak
            val masterGain = min(desiredGain, peakLimitedGain).toFloat()
            require(masterGain.isFinite() && masterGain > 0f)
            val matched = Snip(FloatArray(report.raw.size) { report.raw[it] * masterGain }, 1, Dsp.RATE)
            // Export directly from the unquantized matched samples. The web player applies no further gain.
            val web = Snip(FloatArray(report.raw.size) { matched.samples[it] * WEB_GAIN.toFloat() }, 1, Dsp.RATE)
            val matchedStats = stats(matched)
            val webStats = stats(web)
            val matchedLevel = Loudness.of(matched).toDouble()
            val webLevel = Loudness.of(web).toDouble()
            require(webStats.peak <= WEB_PEAK + 1e-6) { "${case.id} web master would clip" }
            val evidence = validate(case, report, raw, rawStats)
            allPassed = allPassed && evidence.passed
            val dir = File(out, case.id).apply { check(mkdirs()) }
            val rawFile = File(dir, "raw.wav")
            val matchedFile = File(dir, "matched.wav")
            val webFile = File(dir, "web.wav")
            WavWriter.write(rawFile, raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(matchedFile, matched, WavWriter.BitDepth.PCM_24)
            WavWriter.write(webFile, web, WavWriter.BitDepth.PCM_16)
            val body = obj(
                "engine" to str("CIRCUIT"), "formatVersion" to num(1), "id" to str(case.id),
                "title" to str(case.title), "complete" to bool(evidence.passed),
                "sourceCommit" to nullable(sourceCommit), "sourceDirty" to nullable(sourceDirty),
                "sourceRoot" to str(repo.path), "sourceSha256" to JsonValue.Obj(pins.mapValues { str(it.value) }),
                "compiledSourceBinding" to str("Runner must bind these frozen source pins to the compiled classes and final commit."),
                "recipe" to obj("engine" to str("CIRCUIT"), "voice" to str("ROOT"), "preset" to nullable(case.preset),
                    "macros" to JsonValue.Obj(case.macros.mapValues { num(it.value) }), "velocity" to num(1),
                    "rootMidi" to num(Circuit.midiFor(case.macros.getValue("TUNE"))),
                    "rootHz" to num(Circuit.frequencyFor(case.macros.getValue("TUNE"))),
                    "held" to bool(Circuit.isLoop(case.macros.getValue("HOLD")))),
                "render" to obj("frames" to num(report.raw.size), "sampleRate" to num(Dsp.RATE), "channels" to num(1),
                    "durationSeconds" to num(report.raw.size.toDouble() / Dsp.RATE), "elapsedMs" to num(elapsedMs),
                    "renderInvocations" to num(1), "joinedSegments" to num(0), "resampled" to bool(false)),
                "raw" to rawStats.json(rawFile, rawLevel, 24),
                "matched" to matchedStats.json(matchedFile, matchedLevel, 24),
                "web" to webStats.json(webFile, webLevel, 16),
                "matching" to obj("meter" to str("Loudness.of: loudest 200ms after low cut; AuditionLevel convention"),
                    "targetCanonicalLoudness" to num(WEB_LEVEL / WEB_GAIN), "targetWebLoudness" to num(WEB_LEVEL),
                    "webGain" to num(WEB_GAIN), "canonicalPeakCeiling" to num(WEB_PEAK / WEB_GAIN),
                    "webPeakCeiling" to num(WEB_PEAK), "rawToMatchedGain" to num(masterGain),
                    "rawToWebGain" to num(masterGain * WEB_GAIN), "desiredGainBeforePeakGuard" to num(desiredGain),
                    "peakGuardApplied" to bool(peakLimitedGain < desiredGain),
                    "measuredCanonicalLoudness" to num(matchedLevel), "measuredWebLoudness" to num(webLevel),
                    "oneGainForEntireRecording" to bool(true), "perSectionNormalization" to bool(false),
                    "webPlaybackGain" to num(1)),
                "recoveries" to num(report.recoveries), "rawPeak" to num(report.rawPeak),
                "rates" to rates(report.rates), "loop" to loop(report.loop),
                "eventCount" to num(report.events.size), "pathCount" to num(report.paths.size),
                "events" to arr(report.events.map(::event)), "paths" to arr(report.paths.map(::path)),
                "guardEvidence" to evidence.json,
                "ownerListeningAcceptance" to str("pending"),
            )
            val diagnostic = File(dir, "case.json").apply { writeText(Json.write(body) + "\n") }
            entries.add(obj("id" to str(case.id), "title" to str(case.title),
                "diagnosticFile" to str("${case.id}/case.json"), "diagnosticSha256" to str(sha256(diagnostic)),
                "recipe" to body.obj().getValue("recipe"),
                "durationSeconds" to num(report.raw.size.toDouble() / Dsp.RATE),
                "webFile" to str("${case.id}/web.wav"), "webSha256" to str(sha256(webFile)),
                "complete" to bool(evidence.passed)))
            println("CIRCUIT ${case.id}: ${report.raw.size} frames, ${report.events.size} events, ${report.paths.size} paths, ${elapsedMs}ms; guards=${evidence.passed}")
        }
        require(sourcePaths.all { sha256(File(repo, it)) == pins.getValue(it) }) { "Source changed during the bank export" }
        val summary = obj("engine" to str("CIRCUIT"), "formatVersion" to num(1), "mode" to str("playable-root-bank"),
            "complete" to bool(allPassed), "renderInvocations" to num(cases.size),
            "sourceCommit" to nullable(sourceCommit), "sourceDirty" to nullable(sourceDirty), "sourceRoot" to str(repo.path),
            "sourceSha256" to JsonValue.Obj(pins.mapValues { str(it.value) }),
            "compiledSourceBinding" to str("Runner must bind these frozen source pins to the compiled classes and final commit."),
            "inputManifest" to (inputFile?.let { obj("path" to str(it.canonicalPath), "sha256" to str(sha256(it))) } ?: JsonValue.Null),
            "cases" to arr(entries), "ownerListeningAcceptance" to str("pending"))
        File(out, "playable.json").writeText(Json.write(summary) + "\n")
        check(allPassed) { "Playable diagnostics failed a real render guard; inspect the saved per-case evidence" }
        println("Wrote five native ROOT recipes and fifteen WAVs to ${out.canonicalPath}")
    }

    private data class Evidence(val passed: Boolean, val json: JsonValue)

    private fun validate(case: Case, r: Circuit.Report, raw: Snip, stats: Stats): Evidence {
        val checks = linkedMapOf<String, Boolean>()
        fun check(name: String, passed: Boolean) { checks[name] = passed }
        val duration = r.raw.size.toDouble() / Dsp.RATE
        val held = Circuit.isLoop(case.macros.getValue("HOLD"))
        val period = r.rates.loopSeconds
        val cellSeconds = 4.0 / r.rates.paceHz
        val tolerance = 1.0 / Dsp.RATE
        val base = r.events.filterNot { it.reply }
        val replies = r.events.filter { it.reply }
        val opening = base.filter { it.source == 1 && it.reason == "opening-pulse" }.sortedBy { it.timeSeconds }
        val cellCount = opening.size
        fun cell(e: Circuit.Event): Int {
            val c = floor((e.timeSeconds - CircuitPhrase.ORIGIN) / cellSeconds + 1e-9).toInt()
            return if (held && cellCount > 0) Math.floorMod(c, cellCount) else c
        }
        check("mono44100NativeFrames", r.snip.channels == 1 && r.snip.sampleRate == Dsp.RATE && Dsp.RATE == 44_100 &&
            r.raw.size == Circuit.renderFrames(case.macros, CircuitVoice.ROOT) && r.snip.samples.size == r.raw.size)
        check("finiteHealthyAudioWithoutRecovery", r.recoveries == 0 && r.snip.samples.all { it.isFinite() && abs(it) <= 1f } &&
            r.raw.all { it.isFinite() && abs(it) <= 1f } && r.rawPeak.isFinite() && r.rawPeak > 0 && r.rawPeak <= 1)
        check("boundedDc", abs(stats.dc) <= stats.rms * .01)
        check("finiteIndependentClockRates", listOf(r.rates.orbitHz, r.rates.requestedOrbitHz, r.rates.paceHz,
            r.rates.requestedPaceHz, period).all { it.isFinite() && it >= 0 } && r.rates.paceHz > 0)
        check("validOrderedEvents", r.events.isNotEmpty() && r.events == r.events.sortedWith(
            compareBy<Circuit.Event> { it.timeSeconds }.thenBy { it.source }.thenBy { it.reply }) &&
            r.events.all { it.source in 0..6 && it.timeSeconds.isFinite() && it.timeSeconds >= 0 && it.timeSeconds < duration &&
                it.energy.isFinite() && it.energy in 0.0..4.0 })
        check("baseEnsembleRolesRetained", (3..6).all { source -> base.any { it.source == source && it.energy > 0 } })
        check("validAcousticPathsForAllSevenSources", (0..6).all { source -> r.paths.any { it.source == source } } && r.paths.all {
            it.source in 0..6 && it.timeSeconds.isFinite() && it.timeSeconds >= 0 && it.timeSeconds < duration &&
                it.x.isFinite() && it.y.isFinite() && it.directMeters.isFinite() && it.directMeters > 0 &&
                it.reflectedMeters.size == 2 && it.reflectedMeters.all { distance -> distance.isFinite() && distance > it.directMeters }
        })
        check("sourceOneOpeningRepeatsAtFixedClock", cellCount > 0 &&
            opening.zipWithNext().all { (a, b) -> abs(b.timeSeconds - a.timeSeconds - cellSeconds) <= tolerance } &&
            (!held || abs(period - cellCount * cellSeconds) <= tolerance))
        val callCells = base.filter { it.source == 6 }.map(::cell).distinct().sorted()
        val replyCells = replies.map(::cell).distinct().sorted()
        check("completeCallAndListeningAlternation", cellCount > 0 && callCells == (0 until cellCount step 2).toList() &&
            if (!held && cellCount == 1) replies.isNotEmpty() && replyCells == listOf(0)
            else replies.isNotEmpty() && replyCells.all { it in 0 until cellCount && it % 2 == 1 })
        check("heldCompletePairsAndRecurringListening", !held ||
            (cellCount > 0 && cellCount % 2 == 0 && replyCells == (1 until cellCount step 2).toList()))
        check("heldDefaultKeepsSixOpeningCells", case.id != "root-held" || held && cellCount == 6)
        val vocal = r.events.filter { it.source == 6 && it.energy > 0 }.sortedBy { it.timeSeconds }
        fun span(e: Circuit.Event) = when (e.kind) { "GRUNT" -> .28; "UH_HUH" -> .58; else -> .20 }
        val vocalGaps = vocal.zipWithNext { a, b -> b.timeSeconds - a.timeSeconds - span(a) }.toMutableList()
        if (held && vocal.isNotEmpty()) vocalGaps.add(period + vocal.first().timeSeconds - vocal.last().timeSeconds - span(vocal.last()))
        check("completeVocalGesturesWithBreathIncludingHeldSeam", vocal.isNotEmpty() && vocal.all { it.kind in setOf("GRUNT", "UH_HUH") } &&
            vocalGaps.all { it + tolerance >= .08 })
        val invalidReplies = replies.filter { e ->
            val emitted = e.emittedSeconds
            val arrived = e.cueSeconds
            val observer = e.observerCueSeconds
            val source = e.cueSource
            if (emitted == null || arrived == null || observer == null || source == null) true else {
                val emittedInCycle = if (held) emitted - floor(emitted / period) * period else emitted
                val earliest = arrived + .10 + .10 * case.macros.getValue("CANYON")
                val end = earliest + min(4.0, max(.60, .60 * cellSeconds))
                e.depth != 1 || e.source == source || e.reflection !in listOf(0, 1) || emitted >= arrived ||
                    observer <= emitted || e.timeSeconds < earliest - tolerance || e.timeSeconds > end + tolerance ||
                    base.none { it.source == source && abs(it.timeSeconds - emittedInCycle) <= tolerance }
            }
        }
        check("actualCausalReflectedRepliesInsideReactionWindow", replies.isNotEmpty() && invalidReplies.isEmpty())
        val replyGaps = replies.groupBy { it.source }.values.flatMap { own ->
            val times = own.sortedBy { it.timeSeconds }
            times.zipWithNext { a, b -> b.timeSeconds - a.timeSeconds } +
                if (held && times.isNotEmpty()) listOf(period + times.first().timeSeconds - times.last().timeSeconds) else emptyList()
        }
        check("replyRefractoryIncludingHeldSeam", replyGaps.all { it + tolerance >= .35 + .45 / r.rates.paceHz })
        val reservationViolations = replies.count { reply ->
            base.any { own -> own.source == reply.source && (if (held) -1..1 else 0..0).any { cycle ->
                val start = own.timeSeconds + cycle * period
                val gap = if (reply.source == 6) .08 else .03
                reply.timeSeconds < start + span(own) + gap - tolerance && start < reply.timeSeconds + span(reply) + gap - tolerance
            } }
        }
        check("replyRespectsOwnBaseGestureReservation", reservationViolations == 0)
        check("finiteReplyCountAndEnergyBudgets", replies.size <= Circuit.MAX_REPLIES &&
            replies.sumOf { it.energy } <= Circuit.MAX_REPLY_ENERGY + 1e-9 && replies.all { it.depth == 1 })
        var maxInteriorStep = 0.0
        for (i in 1 until r.raw.size) maxInteriorStep = max(maxInteriorStep, abs(r.raw[i] - r.raw[i - 1]).toDouble())
        val wrapStep = abs(r.raw.last() - r.raw.first()).toDouble()
        check("finiteOrSettledHeldLoopEvidence", if (held) r.loop?.let {
            period > 0 && abs(period - duration) <= tolerance && it.seamError.isFinite() && it.seamError < 1e-3 &&
                it.convergenceError.isFinite() && it.convergenceError < 1e-3 && it.prerollCycles > 0 && wrapStep <= maxInteriorStep + 1e-7
        } == true else r.loop == null && period == 0.0 && stats.lastQuarterSecondRms <= stats.rms * .01)
        val hz = Circuit.frequencyFor(case.macros.getValue("TUNE"))
        val probeStarts = if (held) listOf(.4, duration / 2, duration - 1.1) else listOf(.4, 1.5)
        val pitchProbes = probeStarts.map { start ->
            val measured = FineTuning.measuredHz(raw, hz, start.toFloat(), .7f)
            val cents = FineTuning.cents(measured, hz.toDouble())
            Triple(start, measured, cents)
        }
        check("stableC4AtExistingTenCentTolerance", Circuit.midiFor(case.macros.getValue("TUNE")) == 60 &&
            pitchProbes.all { it.second.isFinite() && it.third.isFinite() && abs(it.third) < 10 })
        return Evidence(checks.values.all { it }, obj(
            "checks" to JsonValue.Obj(checks.mapValues { bool(it.value) }),
            "cellSeconds" to num(cellSeconds), "cellCount" to num(cellCount),
            "openingPulseTimes" to arr(opening.map { num(it.timeSeconds) }),
            "ordinaryVocalCells" to arr(callCells.map(::num)), "replyCells" to arr(replyCells.map(::num)),
            "minimumVocalBreathGapSeconds" to (vocalGaps.minOrNull()?.let(::num) ?: JsonValue.Null),
            "minimumReplyGapSeconds" to (replyGaps.minOrNull()?.let(::num) ?: JsonValue.Null),
            "replyCount" to num(replies.size), "replyEnergy" to num(replies.sumOf { it.energy }),
            "invalidReplyCount" to num(invalidReplies.size), "reservationViolations" to num(reservationViolations),
            "rawWrapStep" to num(wrapStep), "maximumRawInteriorStep" to num(maxInteriorStep),
            "pitchProbes" to arr(pitchProbes.map { (start, measured, cents) ->
                obj("startSeconds" to num(start), "bodySeconds" to num(.7), "targetHz" to num(hz),
                    "measuredHz" to num(measured), "cents" to num(cents),
                    "method" to str("Existing FineTuning.measuredHz: 65536 FFT, Hann body, semitone search, interpolated peak"))
            }),
        ))
    }

    private fun rates(r: Circuit.Rates) = obj("orbitHz" to num(r.orbitHz), "paceHz" to num(r.paceHz),
        "requestedOrbitHz" to num(r.requestedOrbitHz), "requestedPaceHz" to num(r.requestedPaceHz), "loopSeconds" to num(r.loopSeconds))
    private fun loop(l: Circuit.LoopEvidence?) = l?.let {
        obj("seamError" to num(it.seamError), "convergenceError" to num(it.convergenceError), "prerollCycles" to num(it.prerollCycles))
    } ?: JsonValue.Null
    private fun event(e: Circuit.Event) = obj("source" to num(e.source), "kind" to str(e.kind),
        "timeSeconds" to num(e.timeSeconds), "energy" to num(e.energy), "reply" to bool(e.reply), "depth" to num(e.depth),
        "trigger" to str(e.reason), "emittedSeconds" to nullable(e.emittedSeconds),
        "observerCueSeconds" to nullable(e.observerCueSeconds), "cueSeconds" to nullable(e.cueSeconds),
        "cueSource" to (e.cueSource?.let(::num) ?: JsonValue.Null), "reflection" to (e.reflection?.let(::num) ?: JsonValue.Null))
    private fun path(p: Circuit.PathFrame) = obj("timeSeconds" to num(p.timeSeconds), "source" to num(p.source),
        "x" to num(p.x), "y" to num(p.y), "directMeters" to num(p.directMeters), "reflectedMeters" to arr(p.reflectedMeters.map(::num)))

    private data class Stats(val peak: Double, val rms: Double, val dc: Double,
                             val firstSecondRms: Double, val lastSecondRms: Double, val lastQuarterSecondRms: Double) {
        fun json(file: File, loudness: Double, bitDepth: Int) = obj("file" to str(file.name), "bitDepth" to num(bitDepth),
            "bytes" to num(file.length()), "sha256" to str(sha256(file)), "peak" to num(peak), "rms" to num(rms),
            "dc" to num(dc), "loudness" to num(loudness), "firstSecondRms" to num(firstSecondRms),
            "lastSecondRms" to num(lastSecondRms), "lastQuarterSecondRms" to num(lastQuarterSecondRms))
    }
    private fun stats(snip: Snip): Stats {
        val a = snip.samples
        fun rms(from: Int, until: Int): Double {
            var sum = 0.0
            for (i in from until until) sum += a[i].toDouble() * a[i]
            return sqrt(sum / (until - from))
        }
        return Stats(a.maxOf { abs(it).toDouble() }, rms(0, a.size), a.sumOf { it.toDouble() } / a.size,
            rms(0, min(Dsp.RATE, a.size)), rms(max(0, a.size - Dsp.RATE), a.size), rms(max(0, a.size - Dsp.RATE / 4), a.size))
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
    private fun nullable(value: String?) = value?.let(::str) ?: JsonValue.Null
    private fun nullable(value: Boolean?) = value?.let(::bool) ?: JsonValue.Null
}
