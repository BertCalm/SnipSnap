package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/** Register/source diagnosis only: numerical measurements do not establish listening acceptance. */
object ChamberRegisterStudy {
    private data class Case(val voice: ChamberVoice, val midi: Int, val model: Int,
        val setting: String, val macros: Map<String, Float>)

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.any { it == "--candidate" || it == "--audition" }) {
            compare(args)
            return
        }
        require(args.size == 1)
        val output = File(args[0]).canonicalFile
        require(!File(output, "study.json").exists()) { "Use a fresh study destination" }
        output.mkdirs()
        val cases = mutableListOf<Case>()
        val settings = linkedMapOf("default" to emptyMap(), "balance-low" to mapOf("BALANCE" to 0f),
            "balance-high" to mapOf("BALANCE" to 1f), "material-high" to mapOf("MATERIAL" to 1f),
            "divide-low" to mapOf("DIVIDE" to 0f))
        for (voice in ChamberVoice.entries) {
            for ((setting, macros) in settings) for (model in 3..4) cases += Case(voice, 60, model, setting, macros)
            for (midi in listOf(48, 72, 84)) for (setting in listOf("default", "material-high"))
                cases += Case(voice, midi, 4, setting, settings.getValue(setting))
            for (midi in listOf(48, 84)) cases += Case(voice, midi, 3, "material-high", settings.getValue("material-high"))
        }
        check(cases.size == 36)
        val records = mutableListOf<Map<String, Any?>>()
        val options = Chamber.ProbeOptions(seconds = 4.5f, recordStems = true, recordDiagnostics = true)
        for ((index, case) in cases.withIndex()) {
            val id = "${case.voice.name.lowercase()}-m${case.model}-midi${case.midi}-${case.setting}"
            val patch = ChamberPatch(id, case.voice, Chamber.defaults(case.voice) + case.macros,
                midi = case.midi, velocity = .75f, seed = 0L, model = case.model)
            val p = Chamber.probe(patch.voice, patch.macros, midi = patch.midi, velocity = patch.velocity,
                configuration = patch.configuration, seed = patch.seed, model = patch.model, options = options)
            val matched = AuditionLevel.level(Snip(p.rawMix, 1, Dsp.RATE)).samples.also { Dsp.fadeTail(it) }
            val d = p.diagnostics
            val checks = linkedMapOf("finite" to (d.finiteStates && p.rawMix.all { it.isFinite() }),
                "primaryBudget" to (d.primaryWork <= d.primaryWorkBudget * 1.0001 + 1e-10),
                "passiveNetwork" to (d.maxEnergy <= d.primaryWork * 1.0001 + 1e-10),
                "unforcedEnergy" to (d.maxUnforcedEnergyRise <= d.maxEnergy * 1e-4 + 1e-10),
                "contractiveComponents" to (d.passiveCouplingNorm <= 1.0000001 && d.maxDelayGain <= 1.0),
                "rawHeadroom" to (p.rawPeak < 1f), "nonSilent" to (rms(p.rawMix) > 1e-12))
            check(checks.values.all { it }) { "$id failed numerical checks: $checks" }
            val stems = p.instrumentStems.mapIndexed { source, samples ->
                shape(samples) + mapOf("source" to listOf("membrane", "bar", "cowbell", "triangle")[source],
                    "actualRootHz" to Keys.midiHz(patch.midi) * patch.configuration.tuningRatios[source],
                    "rootShare" to TangleMeasure.toneShare(samples,
                        Keys.midiHz(patch.midi).toDouble() * patch.configuration.tuningRatios[source]))
            }
            val rawPath = "raw/$id.wav"; val matchedPath = "matched/$id.wav"
            WavWriter.write(File(output, rawPath), Snip(p.rawMix, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(output, matchedPath), Snip(matched, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
            val record = linkedMapOf<String, Any?>("id" to id, "voice" to case.voice.name,
                "midi" to case.midi, "model" to case.model, "setting" to case.setting,
                "patch" to patch.toJsonValue(), "raw" to rawPath, "matched" to matchedPath,
                "rawFloat32Sha256" to sha(p.rawMix), "mix" to shape(p.rawMix),
                "mixDescriptors" to ChamberExtremesAuditionGenerator.measure(p.rawMix, matched, patch.midi),
                "sources" to stems, "direct" to shape(p.direct), "early" to shape(p.early),
                "surface" to shape(p.surface), "late" to shape(p.late),
                "roomToDirectRms" to rms(FloatArray(p.surface.size) { p.surface[it] + p.late[it] }) / rms(p.direct).coerceAtLeast(1e-30),
                "checks" to checks, "primaryWork" to d.primaryWork,
                "maxNetworkEnergy" to d.maxEnergy, "finalNetworkEnergy" to d.finalEnergy,
                "maxUnforcedEnergyRise" to d.maxUnforcedEnergyRise)
            records += record
            ChamberAuditionGenerator.writeJson(File(output, "partial.json"), mapOf("completed" to records.size, "records" to records))
            println("REGISTER ${index + 1}/${cases.size} $id t90=${shape(p.rawMix)["t90Seconds"]} tail=${shape(p.rawMix)["lateToAttackRms"]}")
        }
        ChamberAuditionGenerator.writeJson(File(output, "study.json"), mapOf("sourceRevision" to "efb365650f7b70a42fc82389b235605958a174e5",
            "models" to listOf(3, 4), "sampleRate" to Dsp.RATE, "seconds" to 4.5,
            "velocity" to .75, "seed" to "0", "normalization" to "AuditionLevel loudest 200ms RMS .03, .99 peak guard; stem descriptors use unnormalized Float32 observer output",
            "note" to "Kotlin measurements, not listening acceptance. Source stems are taps before propagation; window RMS is relative to each stem's first 200ms. T90 is cumulative-energy time, not T60.",
            "records" to records))
    }

    /** Eight new candidates; the old full-length recordings are reused with checked provenance. */
    private fun compare(args: Array<String>) {
        val flags = args.filter { it.startsWith("--") }
        require(flags.all { it in listOf("--candidate", "--audition") ||
            it.startsWith("--baseline=") || it.startsWith("--published=") })
        val final = "--audition" in flags
        require(flags.count { it in listOf("--candidate", "--audition") } == 1)
        val root = File(args.single { !it.startsWith("--") }).canonicalFile
        require(!root.exists() || root.listFiles().isNullOrEmpty()) { "Use a fresh output directory" }
        val baseline = File(flags.single { it.startsWith("--baseline=") }.substringAfter('=')).canonicalFile
        val published = flags.singleOrNull { it.startsWith("--published=") }?.substringAfter('=')?.let { File(it).canonicalFile }
        require(!final || published != null)
        require(Chamber.MAX_MODEL_VERSION >= 5) { "The register candidate must be implemented before comparison" }
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { File(it, ".git").exists() }
        val baselineFile = File(baseline, "study.json")
        val priorStudy = Json.parse(baselineFile.readText()).obj()
        val prior = priorStudy.getValue("records").arr().map { it.obj() }
        val baselineRevision = priorStudy.getValue("sourceRevision").str()
        root.mkdirs()
        val provenance = snapshot(repo, root, baselineRevision, final)
        val revision = provenance.getValue("revision") as String
        val options = Chamber.ProbeOptions(seconds = 4.5f, recordStems = true, recordDiagnostics = true)
        val candidates = mutableListOf<Map<String, Any?>>()
        val identity = mutableListOf<Map<String, Any?>>()
        val clips = mutableListOf<Map<String, Any?>>()
        val cards = mutableListOf<Map<String, Any?>>()
        val baselineReplays = mutableListOf<Map<String, Any?>>()
        val portableReplays = mutableListOf<Map<String, Any?>>()

        fun old(voice: ChamberVoice, midi: Int): Map<String, JsonValue> = prior.single {
            it.getValue("voice").str() == voice.name && it.getValue("midi").int() == midi &&
                it.getValue("model").int() == 4 && it.getValue("setting").str() == "material-high"
        }
        fun oldAudio(record: Map<String, JsonValue>, mode: String): File =
            File(baseline, record.getValue(mode).str()).canonicalFile.also {
                require(it.toPath().startsWith(baseline.toPath()) && it.isFile)
            }
        for (voice in ChamberVoice.entries) for (midi in listOf(48, 60, 72, 84)) {
            val previous = old(voice, midi)
            val patch = ChamberPatch.fromJsonValue(previous.getValue("patch")).copy(model = 5)
            val id = "${voice.name.lowercase()}-c${midi / 12 - 1}-revised"
            val p = probe(patch, options)
            val gain = gainFor(p.rawMix)
            val matched = FloatArray(p.rawMix.size) { p.rawMix[it] * gain }.also { Dsp.fadeTail(it) }
            val checks = numerical(p)
            check(checks.values.all { it }) { "$id failed numerical checks: $checks" }
            val record = export(root, id, patch, p, matched, gain, options, revision, "after")
            candidates += record
            if (midi <= 60) {
                val exact = sha(p.rawMix) == previous.getValue("rawFloat32Sha256").str()
                val rawExact = File(root, record.getValue("raw") as String).readBytes().contentEquals(oldAudio(previous, "raw").readBytes())
                val matchedExact = File(root, record.getValue("matched") as String).readBytes().contentEquals(oldAudio(previous, "matched").readBytes())
                check(exact && rawExact && matchedExact) { "$voice MIDI$midi changed its accepted lower/center recording" }
                identity += mapOf("voice" to voice.name, "midi" to midi, "rawFloat32Exact" to exact,
                    "rawWavExact" to rawExact, "matchedWavExact" to matchedExact,
                    "baselineRawFloat32Sha256" to previous.getValue("rawFloat32Sha256").str())
            }
            if (midi >= 72 && final) {
                val oldPatch = ChamberPatch.fromJsonValue(previous.getValue("patch"))
                val replay = probe(oldPatch, options)
                check(sha(replay.rawMix) == previous.getValue("rawFloat32Sha256").str()) { "Old model4 Float32 replay changed" }
                val oldGain = gainFor(replay.rawMix)
                val oldMatched = FloatArray(replay.rawMix.size) { replay.rawMix[it] * oldGain }.also { Dsp.fadeTail(it) }
                val before = export(root, "${voice.name.lowercase()}-c${midi / 12 - 1}-before", oldPatch,
                    replay, oldMatched, oldGain, options, baselineRevision, "before")
                for (mode in listOf("raw", "matched")) check(File(root, before.getValue(mode) as String)
                    .readBytes().contentEquals(oldAudio(previous, mode).readBytes())) { "Old model4 $mode WAV replay changed" }
                baselineReplays += mapOf("id" to before.getValue("id"), "rawFloat32Exact" to true,
                    "rawWavExact" to true, "matchedWavExact" to true,
                    "baselineRawSha256" to fileSha(oldAudio(previous, "raw")),
                    "baselineMatchedSha256" to fileSha(oldAudio(previous, "matched")))
                clips += before; clips += record
                if (midi == 84) for (mode in listOf("raw", "matched")) {
                    val recipe = File(root, record.getValue("recipe") as String)
                    val replayFile = File(root, "replay-proof/$id-$mode.wav")
                    ChamberDiagnosticReplay.main(arrayOf(recipe.absolutePath, replayFile.absolutePath, mode))
                    check(replayFile.readBytes().contentEquals(File(root, record.getValue(mode) as String).readBytes())) {
                        "$id portable $mode replay changed"
                    }
                    portableReplays += mapOf("id" to id, "mode" to mode, "wavExact" to true,
                        "audioSha256" to fileSha(replayFile), "recipeSha256" to fileSha(recipe))
                    replayFile.delete()
                }
            }
            println("REGISTER revised $voice MIDI$midi T90=${shape(p.rawMix)["t90Seconds"]} room/direct=${record.getValue("roomToDirectRms")}")
        }

        val registerQualityChecks = qualityChecks(candidates, prior)
        check(registerQualityChecks.all { it["passed"] == true }) {
            "The full-length C6 response missed its unchanged register targets: $registerQualityChecks"
        }

        if (final) {
            val publishedManifestFile = File(published!!, "manifest.json")
            val publishedManifest = Json.parse(publishedManifestFile.readText()).obj()
            val publishedClips = publishedManifest.getValue("clips").arr().map { it.obj() }
            for (voice in ChamberVoice.entries) {
                val baselineId = "${voice.name.lowercase()}-material-high-wider"
                val anchor = publishedClips.single { it.getValue("id").str() == baselineId }
                val id = "${voice.name.lowercase()}-c4-anchor"
                val metrics = anchor.getValue("metrics").obj()
                val paths = mutableMapOf<String, String>()
                for (mode in listOf("raw", "matched", "recipe")) {
                    val source = File(published, anchor.getValue(mode).str()).canonicalFile
                    require(source.toPath().startsWith(published.toPath()) && source.isFile)
                    val expected = if (mode == "recipe") metrics.getValue("recipeSha256").str()
                        else metrics.getValue("audioSha256").obj().getValue(mode).str()
                    check(fileSha(source) == expected) { "Published C4 anchor asset changed" }
                    val relative = "$mode/$id.${if (mode == "recipe") "json" else "wav"}"
                    val destination = File(root, relative).also { it.parentFile.mkdirs() }
                    source.copyTo(destination)
                    paths[mode] = relative
                }
                val anchorRecord = mapOf("id" to id, "title" to "${voice.name} C4 unchanged anchor", "voice" to voice.name,
                    "midi" to 60, "noteLabel" to "C4", "variant" to "anchor", "model" to 4,
                    "sourceRevision" to publishedManifest.getValue("sourceRevision").str(),
                    "baselineClipId" to baselineId, "raw" to paths.getValue("raw"), "matched" to paths.getValue("matched"),
                    "recipe" to paths.getValue("recipe"), "metrics" to anchor.getValue("metrics"),
                    "anchorIdentityReport" to identity.single { it["voice"] == voice.name && it["midi"] == 60 })
                clips += anchorRecord
                for (midi in listOf(60, 72, 84)) for (variant in listOf("before", "after")) {
                    val clipId = if (midi == 60) id else "${voice.name.lowercase()}-c${midi / 12 - 1}-${if (variant == "after") "revised" else "before"}"
                    cards += mapOf("voice" to voice.name, "midi" to midi, "noteLabel" to "C${midi / 12 - 1}",
                        "variant" to variant, "clipId" to clipId)
                }
            }
            check(clips.size == 10 && cards.size == 12 && clips.map { it["id"] }.distinct().size == 10)
            for (name in listOf("index.html", "app.js", "styles.css"))
                File(repo, "synth/src/test/resources/chamber-register/$name").copyTo(File(root, name))
            val manifest = mapOf("schemaVersion" to 1, "title" to "CHAMBER / Keep the upper notes ringing", "sourceRevision" to revision,
                "createdAt" to Instant.now().toString(), "models" to mapOf("before" to 4, "after" to 5),
                "voices" to ChamberVoice.entries.map { it.name }, "notes" to listOf(60, 72, 84),
                "clips" to clips, "cards" to cards, "checks" to "checks.json", "provenance" to "source-provenance.json",
                "baseline" to mapOf("sourceRevision" to baselineRevision, "studySha256" to fileSha(baselineFile),
                    "publishedManifestSha256" to fileSha(publishedManifestFile)),
                "summary" to "Model4 and the revised model5 candidate use the same material-high patch, velocity and seed. C4 remains byte-identical; higher notes compare their own native renders.",
                "limitations" to listOf("Only Josh's listening establishes acceptance.",
                    "This page compares MATERIAL1 in two rooms at three notes; it does not establish every knob combination.",
                    "Old recipes and default model3 are retained. HOLD and physical MPC acceptance are not claimed."))
            ChamberAuditionGenerator.writeJson(File(root, "manifest.json"), manifest)
        }
        val comparisons = candidates.map { c ->
            val previous = old(ChamberVoice.valueOf(c.getValue("voice") as String), c.getValue("midi") as Int)
            val before = WavReader.read(oldAudio(previous, "matched")).samples
            val after = WavReader.read(File(root, c.getValue("matched") as String)).samples
            mapOf("id" to c.getValue("id"), "voice" to c.getValue("voice"), "midi" to c.getValue("midi"),
                "before" to previous.getValue("mix"), "after" to c.getValue("mix"),
                "beforeRoomToDirectRms" to previous.getValue("roomToDirectRms"),
                "afterRoomToDirectRms" to c.getValue("roomToDirectRms"),
                "gainNormalizedComparison" to ChamberExtremesAuditionGenerator.comparison(before, after, c.getValue("midi") as Int))
        }
        // Lower/center candidate WAVs exist only long enough to prove identity.
        // The public C4 anchor reuses the prior recording; C3 stays a checked report.
        val candidateRecords = candidates.map { c ->
            if (final && (c.getValue("midi") as Int) <= 60) {
                for (mode in listOf("raw", "matched")) check(File(root, c.getValue(mode) as String).delete())
                c.filterKeys { it !in listOf("raw", "matched") } + mapOf("audioAvailable" to false,
                    "audioPolicy" to "Temporary identity-check exports removed; C4 public cards reuse the unchanged model4 anchor")
            } else c + mapOf("audioAvailable" to true)
        }
        ChamberAuditionGenerator.writeJson(File(root, "checks.json"), mapOf("passed" to true,
            "candidateRenderCount" to 8, "sourceRevision" to revision, "dirtyExperiment" to !final,
            "acceptedLowerAndCenterIdentities" to identity,
            "registerQualityPolicy" to mapOf("measurementSeconds" to 4.5,
                "metricDomain" to "Prelevel Float32 mix; RMS ratios remove gain, T90 is cumulative energy rather than T60",
                "answerWindowSeconds" to listOf(.75, 1.5), "attackWindowSeconds" to listOf(0.0, .2),
                "minimumAnswerGrowthExclusive" to 1.25, "minimumC4AnswerFraction" to .5,
                "minimumC4T90Fraction" to .6, "scope" to "Both C6 MATERIAL1 voices against their complete C4 anchor and prior C6"),
            "registerQualityChecks" to registerQualityChecks,
            "allCandidateNumericalChecks" to candidates.map { c ->
                mapOf("id" to c.getValue("id"), "checks" to c.getValue("checks")) },
            "baselineReplays" to baselineReplays, "portableReplays" to portableReplays,
            "portableReplayCoverage" to "Final audition replays both raw and matched portable recipes for both revised C6 clips; every old C5/C6 recording is re-rendered and verified bit-exact.",
            "comparisons" to comparisons))
        ChamberAuditionGenerator.writeJson(File(root, "candidate-study.json"), mapOf("sourceRevision" to revision,
            "dirtyExperiment" to !final, "baselineSourceRevision" to baselineRevision,
            "baselineStudySha256" to fileSha(baselineFile), "records" to candidateRecords, "comparisons" to comparisons))
    }

    /** Same targets as the source regression test, measured over the complete 4.5-second study. */
    private fun qualityChecks(candidates: List<Map<String, Any?>>, prior: List<Map<String, JsonValue>>): List<Map<String, Any?>> =
        ChamberVoice.entries.map { voice ->
            fun candidate(midi: Int) = candidates.single { it["voice"] == voice.name && it["midi"] == midi }
                .getValue("mix") as Map<*, *>
            val center = candidate(60)
            val high = candidate(84)
            val before = prior.single { it.getValue("voice").str() == voice.name && it.getValue("midi").int() == 84 &&
                it.getValue("model").int() == 4 && it.getValue("setting").str() == "material-high" }
                .getValue("mix").obj()
            val oldAnswer = before.getValue("answerToAttackRms").num()
            val centerAnswer = (center["answerToAttackRms"] as Number).toDouble()
            val newAnswer = (high["answerToAttackRms"] as Number).toDouble()
            val centerT90 = (center["t90Seconds"] as Number).toDouble()
            val newT90 = (high["t90Seconds"] as Number).toDouble()
            val checks = mapOf(
                "meaningfulReferences" to listOf(oldAnswer, centerAnswer, newAnswer, centerT90, newT90).all { it.isFinite() && it > 0 },
                "answerGrewOverPrior" to (newAnswer > oldAnswer * 1.25),
                "retainsHalfC4RelativeAnswer" to (newAnswer >= centerAnswer * .5),
                "retainsSixtyPercentC4T90" to (newT90 >= centerT90 * .6))
            mapOf("voice" to voice.name, "midi" to 84, "setting" to "material-high",
                "priorC6AnswerToAttackRms" to oldAnswer, "revisedC6AnswerToAttackRms" to newAnswer,
                "fullC4AnswerToAttackRms" to centerAnswer, "answerGrowthRatio" to newAnswer / oldAnswer,
                "fractionOfC4Answer" to newAnswer / centerAnswer, "revisedC6T90Seconds" to newT90,
                "fullC4T90Seconds" to centerT90, "fractionOfC4T90" to newT90 / centerT90,
                "checks" to checks, "passed" to checks.values.all { it })
        }

    private fun probe(p: ChamberPatch, o: Chamber.ProbeOptions) = Chamber.probe(p.voice, p.macros,
        p.midi, p.velocity, p.configuration, p.seed, p.model, o)

    private fun numerical(p: Chamber.Probe): Map<String, Boolean> {
        val d = p.diagnostics
        return mapOf("finite" to (d.finiteStates && p.rawMix.all { it.isFinite() }),
            "positiveExcitation" to (d.primaryWork > 0 && d.primaryWorkBudget > 0),
            "primaryBudget" to (d.primaryWork <= d.primaryWorkBudget * 1.0001 + 1e-10),
            "passiveNetwork" to (d.maxEnergy <= d.primaryWork * 1.0001 + 1e-10),
            "unforcedEnergy" to (d.maxUnforcedEnergyRise <= d.maxEnergy * 1e-4 + 1e-10),
            "contractiveComponents" to (d.passiveCouplingNorm <= 1.0000001 && d.maxDelayGain <= 1.0),
            "rawHeadroom" to (p.rawPeak < 1f), "nonSilent" to (rms(p.rawMix) > 1e-12))
    }

    private fun gainFor(a: FloatArray): Float {
        var gain = .03f / Loudness.of(Snip(a, 1, Dsp.RATE)).coerceAtLeast(1e-9f)
        val peak = a.maxOf { abs(it) }
        if (peak * gain > .99f) gain = .99f / peak
        return gain
    }

    private fun export(root: File, id: String, patch: ChamberPatch, p: Chamber.Probe, matched: FloatArray,
        gain: Float, options: Chamber.ProbeOptions, revision: String, variant: String): Map<String, Any?> {
        val rawPath = "raw/$id.wav"; val matchedPath = "matched/$id.wav"; val recipePath = "recipes/$id.json"
        val checks = numerical(p)
        check(checks.values.all { it })
        WavWriter.write(File(root, rawPath), Snip(p.rawMix, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
        WavWriter.write(File(root, matchedPath), Snip(matched, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
        ChamberAuditionGenerator.writeJson(File(root, recipePath), ChamberDiagnosticReplay.recipe(patch, options, "mix", gain, 1f))
        val diagnosticPath = "diagnostics/$id.json"
        val d = p.diagnostics
        ChamberAuditionGenerator.writeJson(File(root, diagnosticPath), mapOf("patch" to patch.toJsonValue(),
            "checks" to checks, "primaryWork" to d.primaryWork, "primaryWorkBudget" to d.primaryWorkBudget,
            "maxNetworkEnergy" to d.maxEnergy, "finalNetworkEnergy" to d.finalEnergy,
            "maxUnforcedEnergyRise" to d.maxUnforcedEnergyRise,
            "energy" to d.energy.map { e -> mapOf("seconds" to e.timeSeconds, "instruments" to e.instrumentEnergy,
                "surfaces" to e.surfaceEnergy, "traveling" to e.travelingEnergy, "total" to e.totalEnergy) }))
        return mapOf("id" to id, "title" to "${patch.voice.name} C${patch.midi / 12 - 1} ${if (variant == "after") "Revised" else "Wider before"}",
            "voice" to patch.voice.name, "midi" to patch.midi, "noteLabel" to "C${patch.midi / 12 - 1}",
            "variant" to variant, "model" to patch.model, "sourceRevision" to revision,
            "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath, "diagnostics" to diagnosticPath,
            "rawFloat32Sha256" to sha(p.rawMix), "mix" to shape(p.rawMix),
            "roomToDirectRms" to rms(FloatArray(p.surface.size) { p.surface[it] + p.late[it] }) / rms(p.direct).coerceAtLeast(1e-30),
            "sources" to p.instrumentStems.map { shape(it) }, "checks" to checks,
            "metrics" to (ChamberExtremesAuditionGenerator.measure(WavReader.read(File(root, rawPath)).samples,
                WavReader.read(File(root, matchedPath)).samples, patch.midi) + mapOf("audioSha256" to mapOf(
                    "raw" to fileSha(File(root, rawPath)), "matched" to fileSha(File(root, matchedPath))),
                    "recipeSha256" to fileSha(File(root, recipePath)), "matchedGainFromPrelevel" to gain)))
    }

    private fun snapshot(repo: File, root: File, baselineRevision: String, final: Boolean): Map<String, Any?> {
        fun git(vararg arguments: String): String {
            val p = ProcessBuilder(listOf("git") + arguments).directory(repo).start()
            val text = p.inputStream.bufferedReader().readText().trim()
            check(p.waitFor() == 0) { "Git provenance read failed" }
            return text
        }
        val dirty = git("status", "--porcelain=v1", "--untracked-files=normal").isNotEmpty()
        require(!final || !dirty) { "Commit the exact sources before a final audition render" }
        val dependencies = listOf("Dsp.kt", "Tide.kt", "Keys.kt", "PadRecipe.kt", "Patches.kt", "Thump.kt",
            "AuditionLevel.kt", "TangleMeasure.kt", "FineTuning.kt")
        fun selected(path: String) = path.startsWith("synth/") && (
            path.startsWith("synth/src/main/") && path.substringAfterLast('/').startsWith("Chamber") ||
            path.substringAfterLast('/') in listOf("ChamberRegisterStudy.kt", "ChamberRegisterTest.kt",
                "ChamberAuditionGenerator.kt", "ChamberExtremesAuditionGenerator.kt") ||
            path.substringAfterLast('/') in dependencies || path.startsWith("synth/src/test/resources/chamber-register/")) ||
            path in listOf("synth/build.gradle.kts", "json/src/main/kotlin/com/snipsnap/json/Json.kt") ||
            path.startsWith("audio/src/main/kotlin/com/snipsnap/audio/") && path.substringAfterLast('/') in
                listOf("WavReader.kt", "WavWriter.kt", "Resampler.kt", "Loudness.kt", "Pitch.kt", "Classifier.kt", "Fft.kt")
        val paths = git("ls-files", "--cached", "--others", "--exclude-standard").lines().filter(::selected).distinct()
        val oldPaths = git("ls-tree", "-r", "--name-only", baselineRevision).lines().filter(::selected)
        val oldFiles = oldPaths.map { path ->
            val process = ProcessBuilder("git", "show", "$baselineRevision:$path").directory(repo).start()
            val bytes = process.inputStream.readBytes(); check(process.waitFor() == 0)
            File(root, "source-baseline/$path").also { it.parentFile.mkdirs(); it.writeBytes(bytes) }
            mapOf("path" to path, "sha256" to bytesSha(bytes))
        }
        val result = mapOf("revision" to git("rev-parse", "HEAD"), "dirtyWorktree" to dirty,
            "javaRuntime" to System.getProperty("java.runtime.version"), "oversample" to Dsp.OVERSAMPLE,
            "sampleRate" to Dsp.RATE, "baseline" to mapOf("revision" to baselineRevision,
                "diagnosticRunnerWasUncommitted" to true, "files" to oldFiles),
            "files" to paths.map { path ->
                val source = File(repo, path)
                File(root, "source/$path").also { it.parentFile.mkdirs(); source.copyTo(it) }
                mapOf("path" to path, "sha256" to fileSha(source))
            })
        ChamberAuditionGenerator.writeJson(File(root, "source-provenance.json"), result)
        return result
    }

    private fun fileSha(file: File) = bytesSha(file.readBytes())
    private fun bytesSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun rms(a: FloatArray): Double = sqrt(a.sumOf { it.toDouble() * it } / a.size.coerceAtLeast(1))
    private fun window(a: FloatArray, start: Double, end: Double) = a.copyOfRange(
        (start * Dsp.RATE).toInt().coerceIn(0, a.size), (end * Dsp.RATE).toInt().coerceIn(0, a.size))
    private fun shape(a: FloatArray): Map<String, Any?> {
        val total = a.sumOf { it.toDouble() * it }
        val attack = rms(window(a, 0.0, .2))
        var accumulated = 0.0; var t50 = 0.0; var t90 = 0.0
        for (i in a.indices) {
            accumulated += a[i].toDouble() * a[i]
            if (t50 == 0.0 && accumulated >= total * .5) t50 = i.toDouble() / Dsp.RATE
            if (t90 == 0.0 && accumulated >= total * .9) t90 = i.toDouble() / Dsp.RATE
        }
        val answer = window(a, .75, 1.5); val late = window(a, 1.5, 3.5)
        return mapOf("rms" to rms(a), "peak" to (a.maxOfOrNull { abs(it) } ?: 0f),
            "t50Seconds" to t50, "t90Seconds" to t90, "attack200msRms" to attack,
            "answerRms" to rms(answer), "lateRms" to rms(late),
            "answerToAttackRms" to rms(answer) / attack.coerceAtLeast(1e-30),
            "lateToAttackRms" to rms(late) / attack.coerceAtLeast(1e-30),
            "answerEnergyFraction" to answer.sumOf { it.toDouble() * it } / total.coerceAtLeast(1e-30),
            "lateEnergyFraction" to late.sumOf { it.toDouble() * it } / total.coerceAtLeast(1e-30))
    }
    private fun sha(a: FloatArray): String {
        val bytes = java.nio.ByteBuffer.allocate(a.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in a) bytes.putFloat(v)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
