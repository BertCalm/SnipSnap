package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** A comparison-only study. Model 3 remains the shipped instrument and its recordings are reused. */
object ChamberExtremesAuditionGenerator {
    internal val controls = listOf("STRIKE", "BALANCE", "MATERIAL", "DIVIDE", "PLACEMENT")
    private val windows = linkedMapOf("attack" to (.02 to .15), "body" to (.15 to .5),
        "answer" to (.75 to 1.5), "tail" to (1.5 to 3.5))
    private const val BASELINE_MODEL = 3
    private const val RAW_GAIN = 1f

    internal data class Exported(
        val patch: ChamberPatch, val raw: FloatArray, val matched: FloatArray,
        val metrics: Map<String, Any?>, val record: Map<String, Any?>,
    )

    private data class Baseline(val root: File, val manifest: Map<String, JsonValue>,
        val clips: List<Map<String, JsonValue>>, val sourceRevision: String) {
        fun find(voice: ChamberVoice, control: String? = null, endpoint: Float? = null): Map<String, JsonValue> {
            val candidates = clips.filter { clip ->
                clip["voice"]?.str() == voice.name && if (control == null) clip["section"]?.str() == "first-listen"
                else clip["section"]?.str() == "controls" && clip["metrics"]?.obj()?.let { m ->
                    m["control"]?.str() == control && m["controlValue"]?.num()?.toFloat() == endpoint
                } == true
            }
            require(candidates.size == 1) { "baseline must contain one $voice $control $endpoint recording" }
            return candidates.single()
        }
        fun asset(path: String): File = File(root, path).canonicalFile.also {
            require(it.toPath().startsWith(root.toPath()) && it.isFile) { "baseline asset missing or outside its study: $path" }
        }
        fun audio(clip: Map<String, JsonValue>, mode: String): FloatArray {
            val file = asset(clip.getValue(mode).str())
            val metrics = clip.getValue("metrics").obj()
            require(metrics.getValue("passedHardGates").bool()) { "cannot reuse a baseline clip whose numerical checks failed" }
            val hashes = metrics.getValue("audioSha256").obj()
            require(sha(file) == hashes.getValue(mode).str()) { "baseline $mode audio hash changed: ${clip["id"]?.str()}" }
            val snip = WavReader.read(file)
            require(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.samples.size == (4.5f * Dsp.RATE).toInt()) {
                "baseline must contain complete 4.5-second mono 44100 Hz WAVs"
            }
            return snip.samples
        }
        fun patch(clip: Map<String, JsonValue>): ChamberPatch {
            val file = asset(clip.getValue("recipe").str())
            require(sha(file) == clip.getValue("metrics").obj().getValue("recipeSha256").str()) {
                "baseline recipe hash changed: ${clip.getValue("id").str()}"
            }
            val value = Json.parse(file.readText())
            val obj = value.obj()
            val patch = if (obj.containsKey("diagnosticRecipe")) ChamberPatch.fromJsonValue(obj.getValue("patch"))
                else PadRecipe.fromJsonValue(value).patch as? ChamberPatch ?: error("baseline recipe is not CHAMBER")
            require(patch.model == BASELINE_MODEL && patch.midi == 60 && patch.velocity == .75f && patch.seed == 0L) {
                "baseline comparisons require model 3 at MIDI 60, velocity .75, seed zero"
            }
            require(clip.getValue("model").int() == patch.model && clip.getValue("voice").str() == patch.voice.name) {
                "baseline manifest and recipe identify different sounds"
            }
            return patch
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val flags = args.filter { it.startsWith("--") }
        require(flags.all { it.startsWith("--baseline=") || it == "--extended-checks" }) { "unknown CHAMBER extremes option" }
        val positional = args.filterNot { it.startsWith("--") }
        require(positional.size == 1 && flags.count { it.startsWith("--baseline=") } == 1) {
            "supply <output-directory> --baseline=<complete-finite-WAV-study> [--extended-checks]"
        }
        val root = File(positional.single()).canonicalFile
        val baselineRoot = File(flags.single { it.startsWith("--baseline=") }.substringAfter('=')).canonicalFile
        require(!root.toPath().startsWith(baselineRoot.toPath()) && !baselineRoot.toPath().startsWith(root.toPath())) {
            "comparison output and baseline directories must not contain one another"
        }
        require(!File(root, "manifest.json").exists()) { "use a new output directory; do not overwrite an existing study" }
        root.mkdirs()
        val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, ".git").exists() } ?: error("run inside the SnipSnap repository")
        val source = provenance(repo, root)
        val revision = source.getValue("revision") as String
        val manifestFile = File(baselineRoot, "manifest.json")
        val manifest = Json.parse(manifestFile.readText()).obj()
        require(manifest.getValue("model").int() == BASELINE_MODEL) { "baseline must be the accepted model-3 study" }
        val baseline = Baseline(baselineRoot, manifest, manifest.getValue("clips").arr().map { it.obj() },
            manifest.getValue("sourceRevision").str())
        for (name in listOf("index.html", "app.js", "styles.css")) {
            val resource = ChamberExtremesAuditionGenerator::class.java.getResourceAsStream("/chamber-extremes/$name")
                ?: error("missing CHAMBER extremes page resource $name")
            resource.use { input -> File(root, name).outputStream().use { input.copyTo(it) } }
        }
        val clips = mutableListOf<Map<String, Any?>>()
        val centers = mutableListOf<Map<String, Any?>>()
        val replays = mutableListOf<Map<String, Any?>>()
        val options = Chamber.ProbeOptions(normalize = true, recordStems = true, recordDiagnostics = true, seconds = 4.5f)
        for (voice in ChamberVoice.entries) {
            val slug = voice.name.lowercase(Locale.ROOT)
            val center = baseline.find(voice)
            val centerPatch = baseline.patch(center)
            require(centerPatch.macros == Chamber.defaults(voice) && centerPatch.configuration == Chamber.configFor(voice)) {
                "baseline $voice anchor is not the accepted factory default"
            }
            val old = render(centerPatch, options)
            val wide = render(centerPatch.copy(model = Chamber.WIDE_RANGE_MODEL), options)
            check(old.rawMix.contentEquals(wide.rawMix) && old.samples.contentEquals(wide.samples)) {
                "$voice candidate center changed accepted Float32 audio"
            }
            val centerGain = matchingGain(old.rawMix)
            check(pcm(old.rawMix).contentEquals(baseline.asset(center.getValue("raw").str()).readBytes()) &&
                pcm(scaled(old.rawMix, centerGain).also { Dsp.fadeTail(it) })
                    .contentEquals(baseline.asset(center.getValue("matched").str()).readBytes())) {
                "$voice accepted anchor no longer reproduces the baseline WAVs"
            }
            val oldCenterChecks = numericalChecks(old, centerPatch)
            val wideCenterChecks = numericalChecks(wide, centerPatch.copy(model = Chamber.WIDE_RANGE_MODEL))
            check(oldCenterChecks.getValue("passed") == true && wideCenterChecks.getValue("passed") == true) {
                "$voice accepted-center numerical checks failed"
            }
            centers += linkedMapOf("voice" to voice.name, "midi" to centerPatch.midi, "model3RawFloat32Sha256" to floatSha(old.rawMix),
                "model4RawFloat32Sha256" to floatSha(wide.rawMix), "productionFloat32Exact" to true,
                "rawFloat32Exact" to true, "baselineRawPcmExact" to true, "baselineMatchedPcmExact" to true,
                "model3NumericalChecks" to oldCenterChecks, "model4NumericalChecks" to wideCenterChecks)
            clips += reused(baseline, center, "$slug-accepted-default", null, null, "default").toMutableMap().also { record ->
                @Suppress("UNCHECKED_CAST") val metrics = (record.getValue("metrics") as Map<String, Any?>).toMutableMap()
                metrics.putAll(stemMetrics(old))
                record["metrics"] = metrics
            }
            for (control in controls) for (endpoint in listOf(0f, 1f)) {
                val endpointLabel = if (endpoint == 0f) "low" else "high"
                val id = "$slug-${control.lowercase(Locale.ROOT)}-$endpointLabel"
                val accepted = baseline.find(voice, control, endpoint)
                val patch = baseline.patch(accepted)
                require(patch.configuration == centerPatch.configuration &&
                    patch.macros == Chamber.defaults(voice) + (control to endpoint)) { "baseline $id changed other controls or room" }
                val current = reused(baseline, accepted, "$id-current", control, endpoint, "accepted")
                clips += current
                val exported = exportCandidate(root, "$id-wider", patch.copy(name = "${voice.name} wider $control $endpointLabel",
                    model = Chamber.WIDE_RANGE_MODEL), options, control, endpoint, revision)
                @Suppress("UNCHECKED_CAST") val currentMetrics = current.getValue("metrics") as Map<String, Any?>
                val differences = comparison(baseline.audio(accepted, "matched"), exported.matched, patch.midi)
                clips += exported.record.toMutableMap().also {
                    it["metrics"] = exported.metrics + mapOf("comparisonsToAccepted" to differences,
                        "acceptedBaselineClipId" to accepted.getValue("id").str(),
                        "acceptedT50Seconds" to currentMetrics["t50Seconds"], "acceptedT90Seconds" to currentMetrics["t90Seconds"])
                }
                if (control == "MATERIAL" && endpoint == 1f) {
                    val recipe = File(root, "recipes/$id-wider.json")
                    for (mode in listOf("raw", "matched")) {
                        val replay = File(root, "reports/$id-$mode-replay.wav")
                        replay.parentFile.mkdirs()
                        ChamberDiagnosticReplay.main(arrayOf(recipe.path, replay.path, mode))
                        val expected = File(root, "$mode/$id-wider.wav")
                        check(replay.readBytes().contentEquals(expected.readBytes())) { "$id $mode portable replay changed PCM" }
                        replays += mapOf("clip" to "$id-wider", "mode" to mode, "pcmExact" to true,
                            "sha256" to sha(expected), "scope" to "independent portable replay execution")
                        replay.delete()
                    }
                }
                println("${clips.size}/42 $id: current reused; wider rendered with complete passive-network checks")
            }
        }
        // C3 has the same accepted control anchor. It has no .75-velocity PCM
        // counterpart in the retained study, so prove identity directly and do
        // not invent a baseline-WAV comparison for this register.
        for (voice in ChamberVoice.entries) {
            val patch = ChamberPatch("${voice.name} accepted C3 center", voice, Chamber.defaults(voice),
                midi = 48, model = BASELINE_MODEL)
            val candidate = patch.copy(model = Chamber.WIDE_RANGE_MODEL)
            val old = render(patch, options)
            val wider = render(candidate, options)
            check(old.rawMix.contentEquals(wider.rawMix) && old.samples.contentEquals(wider.samples)) {
                "$voice candidate C3 center changed accepted Float32 audio"
            }
            val oldChecks = numericalChecks(old, patch)
            val widerChecks = numericalChecks(wider, candidate)
            check(oldChecks.getValue("passed") == true && widerChecks.getValue("passed") == true) {
                "$voice C3 center numerical checks failed"
            }
            centers += linkedMapOf("voice" to voice.name, "midi" to patch.midi,
                "model3RawFloat32Sha256" to floatSha(old.rawMix), "model4RawFloat32Sha256" to floatSha(wider.rawMix),
                "model3ProductionFloat32Sha256" to floatSha(old.samples), "model4ProductionFloat32Sha256" to floatSha(wider.samples),
                "rawFloat32Exact" to true, "productionFloat32Exact" to true,
                "baselinePcmComparison" to "Unavailable: retained C3 clips use other velocities, so no baseline PCM identity is claimed",
                "model3NumericalChecks" to oldChecks, "model4NumericalChecks" to widerChecks)
            println("${voice.name} C3 center: model 3 and model 4 preserve every raw and production Float32 bit")
        }
        check(clips.size == 42 && clips.map { it.getValue("id") }.distinct().size == 42)
        val extended = if ("--extended-checks" in flags) extendedChecks(root, options) else emptyList()
        // A shorter contact can traverse a half-sine excitation notch at a high
        // metal mode. Measure intermediate strikes, including the actual 4x
        // triangle fundamental, instead of promising monotonic brightness.
        val upperMetal = if ("--extended-checks" in flags) buildList<Map<String, Any?>> {
            val names = listOf("Membrane", "Bar", "Cowbell", "Triangle")
            for (voice in ChamberVoice.entries) for (strike in listOf(.65f, .75f, .85f)) {
                val patch = ChamberPatch("${voice.name} high-metal contact report", voice,
                    Chamber.defaults(voice) + ("STRIKE" to strike), midi = 84, model = Chamber.WIDE_RANGE_MODEL)
                val probe = render(patch, options)
                val numerical = numericalChecks(probe, patch)
                val instruments = (0..3).map { index ->
                    val hz = Keys.midiHz(patch.midi).toDouble() * patch.configuration.tuningRatios[index].toDouble()
                    val samples = probe.instrumentStems[index]
                    mapOf("instrument" to names[index], "actualRootHz" to hz,
                        "rms" to TangleMeasure.rms(samples),
                        "actualRootEnergyShare" to TangleMeasure.toneShare(samples, hz),
                        "octaveDownEnergyShare" to TangleMeasure.toneShare(samples, hz * .5))
                }
                val id = "${voice.name.lowercase(Locale.ROOT)}-upper-metal-strike-${(strike * 100).toInt()}"
                val report = "reports/$id.json"
                writeJson(File(root, report), diagnostic(probe, patch, numerical) + mapOf(
                    "instruments" to instruments,
                    "stemPolicy" to "Local source-radiation stems before direct-path distance and .46 gain, at their actual excitation; no solo normalization",
                    "toneMeasurementPolicy" to "TangleMeasure coherent tone share at each instrument's actual fundamental and half that frequency, over .12–.52 seconds",
                    "interpretation" to "Report-only contact-notch evidence; a low triangle fundamental share is not silently converted into a pass/fail pitch claim"))
                check(numerical.getValue("passed") == true) { "upper-metal $id failed full-mix numerical checks: $numerical" }
                add(mapOf("id" to id, "voice" to voice.name, "model" to patch.model, "midi" to patch.midi,
                    "velocity" to patch.velocity, "strike" to strike, "passed" to true,
                    "report" to report, "instruments" to instruments, "fullMixNumericalChecks" to numerical))
                println("Upper metal $id: source fundamental shares measured; complete mix remains passive and finite")
            }
        } else emptyList()
        val checks = linkedMapOf("passed" to true, "publicClips" to 42, "newClips" to 20, "reusedClips" to 22,
            "acceptedCenters" to centers, "portableReplays" to replays,
            "portableReplayCoverage" to "Both raw and matched for MATERIAL high in each voice; four full independent replays, not every candidate clip",
            "extendedChecksRequested" to ("--extended-checks" in flags), "extendedChecks" to extended,
            "upperMetalChecks" to upperMetal,
            "legacySourceRevision" to baseline.sourceRevision, "candidateSourceRevision" to revision)
        writeJson(File(root, "checks.json"), checks)
        val output = linkedMapOf("schemaVersion" to 1, "title" to "CHAMBER · wider extremes comparison",
            "sourceRevision" to revision, "createdAt" to Instant.now().toString(),
            "models" to mapOf("accepted" to BASELINE_MODEL, "wider" to Chamber.WIDE_RANGE_MODEL),
            "baseline" to mapOf("manifest" to "../manifest.json", "manifestSha256" to sha(manifestFile),
                "sourceRevision" to baseline.sourceRevision, "policy" to "Existing accepted WAVs are referenced, never replaced or relabelled as new renders"),
            "controls" to controls, "voices" to ChamberVoice.entries.map { it.name }, "clips" to clips,
            "checks" to "checks.json", "provenance" to "source-provenance.json", "metricsCsv" to "metrics.csv",
            "summary" to "Unchanged accepted centers and current endpoints beside an explicit model-4 candidate. This study does not promote model 4 into the app or factory library.",
            "normalization" to "Complete mixes use AuditionLevel's loudest 200 ms RMS .03 with .99 peak guard. Raw gain is 1. Stems are measured at actual mix gain and never independently normalized.",
            "measurementPolicy" to "Window descriptors are measured from the exported matched PCM16 heard on the page. Spectral and waveform distances normalize gain inside each window. Reused endpoint recordings did not retain stems; their room/direct ratio is unavailable.",
            "limitations" to listOf("Only Josh's listening establishes sound acceptance.",
                "Existing hard HYBRID strikes can fool the autocorrelation detector into an octave-low report; root and octave-down energy shares are shown separately.",
                "At MIDI 84, shortening high-metal contacts can traverse half-sine excitation notches. Intermediate STRIKE .65/.75/.85 reports measure each source's actual fundamental and octave-down energy; monotonic upper-metal brightness is not claimed.",
                "Fresh room renders still take seconds; exact-recipe cache reuse is separate from this comparison.",
                "No physical phone or MPC hardware acceptance, HOLD or new voices is claimed."))
        writeJson(File(root, "manifest.json"), output)
        writeCsv(File(root, "metrics.csv"), clips)
        println("Wrote 42 CHAMBER comparison cards, 20 new candidate clips, ${extended.size} combined-extreme and ${upperMetal.size} upper-metal reports to $root")
    }

    private fun reused(baseline: Baseline, clip: Map<String, JsonValue>, id: String,
        control: String?, endpoint: Float?, variant: String): Map<String, Any?> {
        val patch = baseline.patch(clip)
        val raw = baseline.audio(clip, "raw")
        val matched = baseline.audio(clip, "matched")
        val oldMetrics = clip.getValue("metrics").obj()
        val metrics = LinkedHashMap<String, Any?>(measure(raw, matched, patch.midi))
        metrics["baselineMetrics"] = JsonValue.Obj(oldMetrics)
        metrics["rawFloat32Available"] = false
        metrics["roomToDirectRmsRatio"] = null
        metrics["roomToDirectDb"] = null
        metrics["stemMetricsAvailable"] = false
        metrics["audioSha256"] = oldMetrics.getValue("audioSha256")
        return linkedMapOf("id" to id, "title" to if (control == null) "${patch.voice.name} · accepted default"
            else "${patch.voice.name} · $control ${if (endpoint == 0f) "low" else "high"} · current",
            "voice" to patch.voice.name, "control" to control, "endpoint" to endpoint, "variant" to variant,
            "model" to patch.model, "midi" to patch.midi, "velocity" to patch.velocity, "seed" to patch.seed.toString(),
            "sourceRevision" to baseline.sourceRevision, "baselineClipId" to clip.getValue("id").str(),
            "raw" to "../${clip.getValue("raw").str()}", "matched" to "../${clip.getValue("matched").str()}",
            "recipe" to "../${clip.getValue("recipe").str()}", "metrics" to metrics)
    }

    internal fun exportCandidate(root: File, id: String, patch: ChamberPatch, options: Chamber.ProbeOptions,
        control: String?, endpoint: Float?, sourceRevision: String): Exported {
        require(id.matches(Regex("[a-z0-9-]+")))
        require(patch.model == Chamber.WIDE_RANGE_MODEL)
        val started = System.nanoTime()
        val probe = render(patch, options)
        val checks = numericalChecks(probe, patch)
        check(checks.getValue("passed") == true) { "$id numerical checks failed: $checks" }
        val gain = matchingGain(probe.rawMix)
        val rawPath = "raw/$id.wav"; val matchedPath = "matched/$id.wav"
        val recipePath = "recipes/$id.json"; val diagnosticPath = "diagnostics/$id.json"
        val matchedSamples = scaled(probe.rawMix, gain).also { Dsp.fadeTail(it) }
        check(matchedSamples.all { it.isFinite() } && matchedSamples.maxOf { abs(it) } <= .991f) {
            "$id matched PCM exceeds its finite peak guard"
        }
        val rawFile = File(root, rawPath); val matchedFile = File(root, matchedPath)
        WavWriter.write(rawFile, Snip(scaled(probe.rawMix, RAW_GAIN), 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
        WavWriter.write(matchedFile, Snip(matchedSamples, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
        writeJson(File(root, recipePath), ChamberDiagnosticReplay.recipe(patch, options, "mix", gain, RAW_GAIN))
        val raw = WavReader.read(rawFile).samples; val matched = WavReader.read(matchedFile).samples
        val metrics = LinkedHashMap<String, Any?>(measure(raw, matched, patch.midi))
        metrics.putAll(stemMetrics(probe))
        metrics["rawFloat32Sha256"] = floatSha(probe.rawMix)
        metrics["rawFloat32Peak"] = probe.rawPeak
        metrics["rawExportGain"] = RAW_GAIN
        metrics["matchedGainFromPrelevel"] = gain
        metrics["matchedLoudness"] = Loudness.of(Snip(matched, 1, Dsp.RATE))
        metrics["renderMs"] = (System.nanoTime() - started) / 1e6
        metrics["audioSha256"] = mapOf("raw" to sha(rawFile), "matched" to sha(matchedFile))
        metrics["recipeSha256"] = sha(File(root, recipePath))
        metrics["passedHardGates"] = true
        writeJson(File(root, diagnosticPath), diagnostic(probe, patch, checks))
        val record = linkedMapOf("id" to id, "title" to "${patch.voice.name} · $control ${if (endpoint == 0f) "low" else "high"} · wider",
            "voice" to patch.voice.name, "control" to control, "endpoint" to endpoint, "variant" to "wider",
            "model" to patch.model, "midi" to patch.midi, "velocity" to patch.velocity, "seed" to patch.seed.toString(),
            "sourceRevision" to sourceRevision, "raw" to rawPath, "matched" to matchedPath,
            "recipe" to recipePath, "diagnostics" to diagnosticPath, "metrics" to metrics)
        return Exported(patch, raw, matched, metrics, record)
    }

    private fun render(p: ChamberPatch, options: Chamber.ProbeOptions) = Chamber.renderProbe(
        p.voice, p.macros, p.midi, p.velocity, p.configuration, p.seed, p.model, options)

    private fun numericalChecks(probe: Chamber.Probe, patch: ChamberPatch): Map<String, Any?> {
        val d = probe.diagnostics
        val checks = linkedMapOf("finiteAudio" to probe.rawMix.all { it.isFinite() }, "finiteStates" to d.finiteStates,
            "positiveExcitation" to (d.primaryWork > 0.0 && d.primaryWorkBudget > 0.0),
            "primaryBudget" to (d.primaryWork <= d.primaryWorkBudget * 1.0001 + 1e-10),
            "passiveNetwork" to (d.maxEnergy <= d.primaryWork * 1.0001 + 1e-10),
            "unforcedEnergy" to (d.maxUnforcedEnergyRise <= d.maxEnergy * 1e-4 + 1e-10),
            "contractiveComponents" to (d.passiveCouplingNorm <= 1.0000001 && d.maxDelayGain <= 1.0),
            "rawPcmHeadroom" to (probe.rawPeak < 1f), "nonSilent" to (TangleMeasure.rms(probe.rawMix) > 1e-12),
            "sharedStrikeTimestamp" to (d.sharedStrikeTimes.size == patch.configuration.instrumentEnabled.values.count { it } &&
                d.sharedStrikeTimes.all { it == 0.0 }))
        return checks + mapOf("passed" to checks.values.all { it })
    }

    private fun diagnostic(p: Chamber.Probe, patch: ChamberPatch, checks: Map<String, Any?>): Map<String, Any?> {
        val d = p.diagnostics
        return linkedMapOf("patch" to patch.toJsonValue(), "checks" to checks,
            "primaryWork" to d.primaryWork, "primaryWorkBudget" to d.primaryWorkBudget,
            "maxNetworkEnergy" to d.maxEnergy, "finalNetworkEnergy" to d.finalEnergy,
            "maxUnforcedEnergyRise" to d.maxUnforcedEnergyRise, "passiveCouplingNorm" to d.passiveCouplingNorm,
            "maxDelayGain" to d.maxDelayGain, "peakModalState" to d.peakModalState,
            "modalCount" to d.modalCount, "travelingSamples" to d.travelingSamples,
            "preparationMs" to d.preparationNanos / 1e6, "synthesisMs" to d.synthesisNanos / 1e6,
            "outputMs" to d.outputNanos / 1e6,
            "energy" to d.energy.map { e -> mapOf("seconds" to e.timeSeconds, "instruments" to e.instrumentEnergy,
                "surfaces" to e.surfaceEnergy, "traveling" to e.travelingEnergy, "total" to e.totalEnergy,
                "primaryWork" to e.primaryWork) },
            "materialPositions" to p.geometry.regionMaterialPositions,
            "arrivals" to p.arrivals.map { a -> mapOf("source" to a.sourceIndex, "kind" to a.kind,
                "seconds" to a.timeSeconds, "face" to a.face, "materialWeights" to a.materialWeights) })
    }

    private fun stemMetrics(p: Chamber.Probe): Map<String, Any?> {
        val direct = TangleMeasure.rms(p.direct)
        val room = FloatArray(p.direct.size) { p.early[it] + p.surface[it] + p.late[it] }
        val roomRms = TangleMeasure.rms(room)
        return mapOf("stemMetricsAvailable" to true, "stemRms" to mapOf("direct" to direct,
            "early" to TangleMeasure.rms(p.early), "surface" to TangleMeasure.rms(p.surface), "late" to TangleMeasure.rms(p.late)),
            "roomToDirectRmsRatio" to roomRms / direct.coerceAtLeast(1e-30),
            "roomToDirectDb" to 20 * log10((roomRms / direct.coerceAtLeast(1e-30)).coerceAtLeast(1e-30)))
    }

    private fun extendedChecks(root: File, options: Chamber.ProbeOptions): List<Map<String, Any?>> = buildList {
        for (voice in ChamberVoice.entries) {
            val center = ChamberPatch("Wider combined check", voice, Chamber.defaults(voice), model = Chamber.WIDE_RANGE_MODEL)
            val cases = mutableListOf<Pair<String, ChamberPatch>>()
            for ((a, b) in listOf("MATERIAL" to "PLACEMENT", "DIVIDE" to "PLACEMENT"))
                for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) cases +=
                    "${voice.name.lowercase(Locale.ROOT)}-${a.lowercase(Locale.ROOT)}-${b.lowercase(Locale.ROOT)}-${x.toInt()}-${y.toInt()}" to
                        center.copy(macros = center.macros + mapOf(a to x, b to y))
            for (midi in listOf(36, 84)) for (velocity in listOf(.15f, 1f)) cases +=
                "${voice.name.lowercase(Locale.ROOT)}-midi-$midi-v-${(velocity * 100).toInt()}" to center.copy(midi = midi, velocity = velocity)
            for ((id, patch) in cases) {
                val probe = render(patch, options)
                val checks = numericalChecks(probe, patch)
                writeJson(File(root, "reports/extended-$id.json"), diagnostic(probe, patch, checks))
                check(checks.getValue("passed") == true) { "combined extreme $id failed: $checks" }
                add(mapOf("id" to id, "model" to patch.model, "voice" to voice.name, "passed" to true,
                    "report" to "reports/extended-$id.json", "midi" to patch.midi, "velocity" to patch.velocity,
                    "measurementSource" to "Report-only Float32 simulation at the same audition gain; no public audio export",
                    "metrics" to (measure(probe.rawMix, scaled(probe.rawMix, matchingGain(probe.rawMix)), patch.midi) + stemMetrics(probe))))
                println("Extended $id: complete network remains passive and finite")
            }
        }
    }

    internal fun measure(raw: FloatArray, matched: FloatArray, midi: Int): Map<String, Any?> {
        require(raw.size == matched.size && raw.isNotEmpty())
        val total = matched.sumOf { it.toDouble() * it }.coerceAtLeast(1e-30)
        val wanted = Keys.midiHz(midi).toDouble()
        val measured = windows.mapValues { (_, limits) ->
            val samples = window(matched, limits)
            val spectrum = spectrum(samples)
            mapOf("fromSeconds" to limits.first, "toSeconds" to minOf(limits.second, matched.size.toDouble() / Dsp.RATE),
                "frames" to samples.size, "available" to samples.isNotEmpty(), "rms" to TangleMeasure.rms(samples),
                "energyFraction" to samples.sumOf { it.toDouble() * it } / total,
                "centroidHz" to spectrum.second, "spectralBands" to spectrum.first.toList(),
                "rootShare" to toneShare(samples, wanted), "octaveDownShare" to toneShare(samples, wanted * .5))
        }
        var energy = 0.0; var t50: Double? = null; var t90: Double? = null
        for (i in matched.indices) {
            energy += matched[i].toDouble() * matched[i]
            if (t50 == null && energy >= total * .5) t50 = i.toDouble() / Dsp.RATE
            if (t90 == null && energy >= total * .9) t90 = i.toDouble() / Dsp.RATE
        }
        return linkedMapOf("rawPeak" to raw.maxOf { abs(it) }, "rawRms" to TangleMeasure.rms(raw),
            "peak" to matched.maxOf { abs(it) }, "rms" to TangleMeasure.rms(matched), "dc" to matched.average(),
            "finite" to (raw.all { it.isFinite() } && matched.all { it.isFinite() }),
            "durationSeconds" to matched.size.toDouble() / Dsp.RATE, "matchedLoudness" to Loudness.of(Snip(matched, 1, Dsp.RATE)),
            "requestedRootHz" to wanted, "t50Seconds" to t50, "t90Seconds" to t90, "windows" to measured,
            "spectralBandPolicy" to "24 logarithmic power bands, 30–20000 Hz; Hann-windowed overlapping FFTs pooled across each complete window")
    }

    internal fun comparison(accepted: FloatArray, wider: FloatArray, midi: Int): Map<String, Any?> {
        require(accepted.size == wider.size)
        return mapOf("windows" to windows.mapValues { (_, limits) ->
            val a = window(accepted, limits); val b = window(wider, limits)
            val sa = spectrum(a).first; val sb = spectrum(b).first
            mapOf("shapeDistance" to TangleMeasure.distance(a, b),
                "spectralDistance" to sa.indices.sumOf { abs(sa[it] - sb[it]) } / 2,
                "rmsRatio" to TangleMeasure.rms(b) / TangleMeasure.rms(a).coerceAtLeast(1e-30),
                "requestedRootShareDifference" to (toneShare(b, Keys.midiHz(midi).toDouble()) - toneShare(a, Keys.midiHz(midi).toDouble())))
        })
    }

    private fun window(samples: FloatArray, seconds: Pair<Double, Double>): FloatArray {
        val from = (seconds.first * Dsp.RATE).toInt().coerceIn(0, samples.size)
        val to = (seconds.second * Dsp.RATE).toInt().coerceIn(from, samples.size)
        return samples.copyOfRange(from, to)
    }
    private fun spectrum(samples: FloatArray): Pair<DoubleArray, Double> {
        val bands = DoubleArray(24)
        if (samples.isEmpty()) return bands to 0.0
        var size = 256; while (size < minOf(samples.size, 4096)) size *= 2
        val last = (samples.size - size).coerceAtLeast(0)
        val starts = generateSequence(0) { it + size / 2 }.takeWhile { it <= last }.toMutableList()
        if (starts.last() != last) starts += last
        var weighted = 0.0; var power = 0.0
        for (start in starts) {
            val mag = Fft.magnitudeSpectrum(samples.copyOfRange(start, minOf(start + size, samples.size)), size)
            for (i in 1 until mag.size) {
                val hz = i.toDouble() * Dsp.RATE / size
                val energy = mag[i].toDouble() * mag[i]
                val band = (ln(hz.coerceAtLeast(30.0) / 30.0) / ln(20000.0 / 30.0) * bands.size).toInt().coerceIn(0, bands.lastIndex)
                bands[band] += energy; weighted += hz * energy; power += energy
            }
        }
        val norm = power.coerceAtLeast(1e-30)
        return DoubleArray(bands.size) { bands[it] / norm } to weighted / norm
    }
    private fun toneShare(samples: FloatArray, hz: Double): Double {
        if (samples.isEmpty()) return 0.0
        var re = 0.0; var im = 0.0; var energy = 0.0
        for (i in samples.indices) {
            val value = samples[i].toDouble()
            re += value * cos(2 * PI * hz * i / Dsp.RATE)
            im += value * sin(2 * PI * hz * i / Dsp.RATE)
            energy += value * value
        }
        return (2 * (re * re + im * im) / (samples.size * energy).coerceAtLeast(1e-30)).coerceIn(0.0, 1.0)
    }

    private fun provenance(repo: File, root: File): Map<String, Any?> {
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git") + args).directory(repo).start()
            val text = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "git source inspection failed" }; return text
        }
        require(git("status", "--porcelain=v1", "--untracked-files=normal").isEmpty()) {
            "commit the exact comparison sources before the final render"
        }
        val paths = listOf("synth/src/main/kotlin/com/snipsnap/synth/Chamber.kt", "synth/src/main/kotlin/com/snipsnap/synth/ChamberWideRange.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ChamberConfig.kt", "synth/src/main/kotlin/com/snipsnap/synth/ChamberRoom.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/ChamberPatch.kt", "synth/src/main/kotlin/com/snipsnap/synth/ChamberPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt", "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt", "synth/src/main/kotlin/com/snipsnap/synth/PadRecipe.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Patches.kt", "synth/src/main/kotlin/com/snipsnap/synth/Thump.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt", "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Resampler.kt", "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Pitch.kt", "audio/src/main/kotlin/com/snipsnap/audio/Classifier.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Fft.kt", "json/src/main/kotlin/com/snipsnap/json/Json.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/ChamberExtremesAuditionGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/ChamberExtremesAuditionExportTest.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/ChamberAuditionGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt", "synth/src/test/kotlin/com/snipsnap/synth/TangleMeasure.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/FineTuning.kt", "synth/build.gradle.kts",
            "synth/src/test/resources/chamber-extremes/index.html", "synth/src/test/resources/chamber-extremes/app.js",
            "synth/src/test/resources/chamber-extremes/styles.css")
        val source = mapOf("revision" to git("rev-parse", "HEAD"), "dirtyWorktree" to false,
            "javaRuntime" to System.getProperty("java.runtime.version"), "kotlinRuntime" to KotlinVersion.CURRENT.toString(),
            "sampleRate" to Dsp.RATE, "oversample" to Dsp.OVERSAMPLE,
            "files" to paths.map { path ->
                val file = File(repo, path); require(file.isFile) { "missing comparison provenance source $path" }
                File(root, "source/$path").also { it.parentFile.mkdirs(); file.copyTo(it, overwrite = false) }
                mapOf("path" to path, "sha256" to sha(file))
            })
        writeJson(File(root, "source-provenance.json"), source)
        return source
    }
    private fun writeCsv(file: File, clips: List<Map<String, Any?>>) {
        val rows = mutableListOf("id,voice,control,endpoint,variant,model,sourceRevision,rawPeak,matchedLoudness,t50Seconds,t90Seconds,roomToDirectDb")
        for (clip in clips) {
            @Suppress("UNCHECKED_CAST") val metrics = clip.getValue("metrics") as Map<String, Any?>
            rows += (listOf("id", "voice", "control", "endpoint", "variant", "model", "sourceRevision").map { clip[it] } +
                listOf("rawPeak", "matchedLoudness", "t50Seconds", "t90Seconds", "roomToDirectDb").map { metrics[it] })
                .joinToString(",") { "\"${it?.toString().orEmpty().replace("\"", "\"\"")}\"" }
        }
        file.writeText(rows.joinToString("\n", postfix = "\n"))
    }
    private fun matchingGain(raw: FloatArray): Float =
        (TangleMeasure.rms(AuditionLevel.level(Snip(raw, 1, Dsp.RATE)).samples) / TangleMeasure.rms(raw).coerceAtLeast(1e-30)).toFloat()
    private fun scaled(samples: FloatArray, gain: Float) = FloatArray(samples.size) { samples[it] * gain }
    private fun pcm(samples: FloatArray): ByteArray = ByteArrayOutputStream().also {
        WavWriter.write(it, Snip(samples, 1, Dsp.RATE), WavWriter.BitDepth.PCM_16)
    }.toByteArray()
    private fun floatSha(samples: FloatArray): String {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) buffer.putFloat(sample)
        return digest(buffer.array())
    }
    private fun sha(file: File) = digest(file.readBytes())
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun writeJson(file: File, value: Any?) = ChamberAuditionGenerator.writeJson(file, value)
}
