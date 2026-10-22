package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Model 2's 88-case matrix plus six same-note identity/revision comparisons. */
object ShoalAuditionGenerator {
    private val timbre = listOf("DENSITY", "CURRENT", "COHESION", "STRAY", "GLOW")
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private val voices = listOf(ShoalVoice.GLASS, ShoalVoice.RIPPLE)
    private val groups = listOf(
        group("defaults", "First contact", "The two unmodified voice defaults at C3, velocity 1. Listen for a clear root and energy gathering into moving groups."),
        group("ablations", "Take it apart", "Identical patch, root and velocity. A diagnostic override removes one mechanism; no companion macro changes."),
        group("sweeps", "One control, five steps", "Both voices at C3, velocity 1. Only the named control moves; companions retain that voice's defaults."),
        group("interactions", "When controls meet", "Four corners of each required pair. The selected voice is stated on every case."),
        group("pitch-velocity", "Register & effort", "MIDI 24 / 48 / 84, each at velocity .25 and 1. The defaults supply the C3 / hard cells."),
        group("extremes", "All controls high", "All five timbral controls at 1; a finite note with HOLD 0."),
        group("held", "Keep the shoal moving", "Sustain-only loops using a captured periodic energy/STRAY trajectory from the same population model. The nonlinear state is not claimed to converge. Repeat for several wraps; the original attack is absent."),
        group("identity", "The voices at C4", "Revised GLASS and RIPPLE, the same C4 root and full velocity."),
        group("legacy", "Original model 1", "Legacy GLASS and RIPPLE at C3 and C4. Model 1 C3 WAVs are checked against the preserved original defaults when that archive is present."),
    )

    private data class Case(
        val id: String,
        val group: String,
        val title: String,
        val question: String,
        val patch: ShoalPatch,
        val ablation: Shoal.Ablation = Shoal.Ablation.NONE,
        val macro: String? = null,
        val step: Float? = null,
        val pair: String? = null,
        val values: List<Float>? = null,
        val seconds: Float? = null,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 && args.filter { it.startsWith("--") }.all { it == "--refresh-page" }) {
            "usage: ShoalAuditionGenerator [output-directory] [--refresh-page]"
        }
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/shoal-audition").absoluteFile
        require(root.isDirectory || root.mkdirs()) { "Cannot create $root" }
        if ("--refresh-page" in args) {
            refreshPage(root)
            return
        }
        val cases = cases()
        check(cases.size == 94 && cases.map { it.id }.distinct().size == cases.size)
        val legacyRoot = File(root.parentFile, "shoal-audition-v1")
        val legacyManifest = File(legacyRoot, "manifest.json").takeIf { it.isFile }?.let { Json.parse(it.readText()).obj() }
        val legacyRows = legacyManifest?.getValue("clips")?.arr()?.associateBy { it.obj().getValue("id").str() }.orEmpty()
        val started = System.nanoTime()
        val rows = mutableListOf<Map<String, Any?>>()
        val references = mutableMapOf<ShoalVoice, FloatArray>()
        val retained = mutableSetOf<String>()
        for ((index, case) in cases.withIndex()) {
            val p = case.patch
            val macros = Shoal.defaults(p.voice) + p.macros
            val held = macros.getValue("HOLD") >= Shoal.LOOP_THRESHOLD
            val before = System.nanoTime()
            val probe = Shoal.probe(p.voice, macros, p.midi, p.velocity, case.seconds, case.ablation, model = p.model)
            val elapsed = (System.nanoTime() - before) / 1_000_000.0
            val raw = Snip(probe.samples, channels = 1, sampleRate = Dsp.RATE)
            require(raw.samples.isNotEmpty() && raw.samples.all { it.isFinite() } && raw.peak() < 1f) {
                "${case.id}: non-finite, empty or clipping prelevel output (${raw.peak()})"
            }
            require(probe.raw.all { it.isFinite() }) { "${case.id}: non-finite internal output" }
            val matched = AuditionLevel.level(raw)
            require(matched.samples.all { it.isFinite() } && matched.peak() <= .991f)
            val rawPath = "raw/v${p.model}/${case.id}.wav"
            val matchedPath = "matched/v${p.model}/${case.id}.wav"
            val recipePath = "recipes/v${p.model}/${case.id}.json"
            val tracePath = "traces/v${p.model}/${case.id}.json"
            val sampler = SmplChunk(p.midi, if (held) SmplChunk.Loop(0, raw.frameCount.toLong()) else null)
            for ((path, snip) in listOf(rawPath to raw, matchedPath to matched)) {
                val file = File(root, path).apply { parentFile.mkdirs() }
                WavWriter.write(file, snip, WavWriter.BitDepth.PCM_24, smpl = sampler)
            }
            val recipe = JsonValue.Obj(p.toJsonValue().entries + ("audition" to json(linkedMapOf(
                "diagnosticAblation" to case.ablation.name, "requestedSeconds" to case.seconds,
                "render" to "Shoal.probe(voice, macros, midi, velocity, seconds, ablation, model = model); raw samples before melodic leveling",
            ))))
            File(root, recipePath).apply { parentFile.mkdirs(); writeText(Json.write(recipe) + "\n") }
            val trace = probe.energyTrace.let { frames ->
                if (frames.size <= 96) frames else (0 until 96).map { frames[(it * (frames.size - 1) / 95.0).roundToInt()] }
            }.map { frame -> linkedMapOf("seconds" to frame.seconds, "energy" to frame.energy, "centers" to frame.centers, "strayCents" to frame.strayCents, "totalEnergy" to frame.totalEnergy, "supply" to frame.supply) }
            require(trace.isNotEmpty()) { "${case.id}: missing population-state evidence" }
            File(root, tracePath).apply { parentFile.mkdirs(); writeText(Json.write(json(linkedMapOf(
                "id" to case.id, "description" to "Actual simulated moving-partial energy and cluster-center state. Downsampled in time; not an FFT or audio spectrum.",
                "originalFrameCount" to probe.energyTrace.size, "frames" to trace,
            ))) + "\n") }
            retained += listOf(rawPath, matchedPath, recipePath, tracePath)
            val head = Snip(raw.samples.copyOf(minOf(raw.samples.size, (1.25 * Dsp.RATE).toInt())), 1, Dsp.RATE)
            val fullClass = Classifier.classify(raw)
            val headClass = Classifier.classify(head)
            val previous = probe.previousCycle
            if (held) require(previous != null && previous.size == raw.samples.size && probe.seamError.isFinite() && probe.seamError < Keys.MAX_SEAM_ERROR) {
                "${case.id}: no independent preceding cycle or held seam ${probe.seamError} exceeds ${Keys.MAX_SEAM_ERROR}"
            }
            if (case.group == "defaults") references[p.voice] = matched.samples
            val d = probe.diagnostics
            val rawHash = sha256(File(root, rawPath))
            val matchedHash = sha256(File(root, matchedPath))
            val originalId = "${p.voice.name.lowercase()}_default".takeIf { p.model == 1 && p.midi == 48 && case.group == "legacy" }
            val original = originalId?.let { legacyRows[it]?.obj() }
            if (original != null) require(original.getValue("rawSha256").str() == rawHash && original.getValue("matchedSha256").str() == matchedHash) {
                "${case.id}: legacy replay differs from preserved original model1 PCM"
            }
            rows += linkedMapOf(
                "id" to case.id, "group" to case.group, "title" to case.title, "question" to case.question,
                "voice" to p.voice.name, "midi" to p.midi, "note" to note(p.midi), "velocity" to p.velocity,
                "model" to p.model, "macros" to macros, "ablation" to case.ablation.name,
                "macro" to case.macro, "step" to case.step, "pair" to case.pair, "values" to case.values,
                "loop" to held, "attackIncluded" to !held, "frames" to raw.frameCount, "durationSeconds" to raw.durationSeconds,
                "requestedSeconds" to case.seconds, "effectiveHz" to probe.effectiveHz, "requestedHz" to midiHz(p.midi),
                "rawPath" to rawPath, "matchedPath" to matchedPath, "recipePath" to recipePath, "tracePath" to tracePath,
                "rawExportGain" to 1, "matchedGain" to Loudness.of(matched) / Loudness.of(raw).coerceAtLeast(1e-9f),
                "rawMetrics" to metrics(raw), "matchedMetrics" to metrics(matched),
                "internalDiagnostics" to linkedMapOf(
                    "rawPeak" to d.rawPeak, "rawRms" to d.rawRms, "rawDc" to d.rawDc,
                    "minEnergy" to d.minEnergy, "maxEnergySum" to d.maxEnergySum,
                    "maxTransferResidual" to d.maxTransferResidual, "activePartials" to d.activePartials,
                    "raw4xFrames" to probe.raw.size, "maxFrequencyHz" to d.maxFrequencyHz, "phaseClosureError" to d.phaseClosureError,
                ),
                "pitch" to pitch(raw, midiHz(p.midi)), "spectrum" to spectrum(raw),
                "classifier" to fullClass.drumClass.name, "headClassifier" to headClass.drumClass.name,
                "headClassifierConfidence" to headClass.confidence,
                "energyTrace" to trace, "energyTraceOriginalFrames" to probe.energyTrace.size,
                "normalizedDistanceFromDefault" to references[p.voice]?.let { distance(it, matched.samples) },
                "referenceModelVersion" to Shoal.HARMONIC_MODEL_VERSION,
                "seamError" to if (held) probe.seamError else null,
                "stateError" to if (held) probe.stateError else null,
                "cycleDifferenceRatio" to if (held) cycleDifference(requireNotNull(previous), raw.samples) else null,
                "loopFrames" to if (held) probe.loopFrames else null, "periodicCapture" to probe.periodicCapture,
                "supplyCutoffSeconds" to probe.supplyCutoffSeconds.takeIf { it.isFinite() }, "sustainedSupply" to held,
                "wrapStep" to if (held) abs(raw.samples.first().toDouble() - raw.samples.last()) else null,
                "renderMilliseconds" to elapsed, "renderMillisecondsPerAudioSecond" to elapsed / raw.durationSeconds,
                "rawSha256" to rawHash, "matchedSha256" to matchedHash,
                "legacyClipId" to originalId, "legacyHashVerified" to (original != null),
                "baselineSourceRevision" to if (p.model == 1) legacyManifest?.get("sourceRevision") else null,
            )
            println("SHOAL ${index + 1}/${cases.size} ${case.id}: ${raw.frameCount} frames, ${elapsed.roundToInt()} ms")
        }
        for (folder in listOf("raw", "matched", "recipes", "traces")) File(root, folder).walkTopDown()
            .filter { it.isFile && it.extension in listOf("wav", "json") && it.relativeTo(root).invariantSeparatorsPath !in retained }
            .forEach { check(it.delete()) { "Cannot prune obsolete generated asset $it" } }
        val firstListen = listOf("glass_default", "ripple_default", "ripple_no_transport", "ripple_no_local_cohesion", "ripple_no_stray", "ripple_hold")
        val compact = rows.filter { it["group"] in listOf("defaults", "ablations", "held", "identity", "legacy") }
        val legacyZip = File(legacyRoot, "shoal-first-listen.zip")
        val legacyArchive = if (legacyZip.isFile) {
            val destination = File(root, "legacy-v1").apply { mkdirs() }
            legacyZip.copyTo(File(destination, legacyZip.name), overwrite = true)
            File(legacyRoot, "README.md").takeIf { it.isFile }?.copyTo(File(destination, "README.md"), overwrite = true)
            File(destination, "provenance.json").writeText(Json.write(json(linkedMapOf(
                "modelVersion" to 1, "sourceRevision" to legacyManifest?.get("sourceRevision"),
                "sourceTreeDirty" to legacyManifest?.get("sourceTreeDirty"), "archiveSha256" to sha256(legacyZip),
                "description" to "Preserved original ten-clip model1 listening ZIP, copied without modifying its bytes.",
            ))) + "\n")
            "legacy-v1/shoal-first-listen.zip"
        } else null
        val manifest = linkedMapOf(
            "schemaVersion" to 1, "engine" to "SHOAL", "modelVersion" to Shoal.HARMONIC_MODEL_VERSION,
            "title" to "Shoal / SnipSnap listening room", "sourceRevision" to git("rev-parse", "HEAD"),
            "sourceTreeDirty" to (git("status", "--porcelain")?.isNotBlank()),
            "sourceDescription" to "Actual offline SnipSnap Kotlin engine renders. No samples, browser synthesis or rack effects.",
            "sonicAcceptance" to "Model2 revision addresses insufficient voice contrast in model1. Owner listening completes sonic acceptance.",
            "auditionRevision" to "model2-voice-contrast", "legacyArchive" to legacyArchive,
            "legacySourceRevision" to legacyManifest?.get("sourceRevision"), "legacyBaselineCount" to 4,
            "sampleRate" to Dsp.RATE, "internalSampleRate" to Dsp.RATE * Dsp.OVERSAMPLE,
            "bitDepth" to 24, "channels" to 1, "clipCount" to rows.size, "wavCount" to rows.size * 2,
            "listeningTarget" to .03, "loudnessMeasure" to "AuditionLevel: loudest 200 ms RMS window, .99 peak guard",
            "rawDescription" to "Conditioned, decimated engine output before shared melodic/audition leveling; export gain exactly 1. Internal 4× diagnostics are retained separately.",
            "metricNotes" to METRIC_NOTES, "groups" to groups, "macroNames" to timbre, "macroSteps" to steps,
            "noteMatrix" to listOf(24, 48, 84), "velocityMatrix" to listOf(.25f, 1f),
            "firstListen" to firstListen, "compactClipIds" to compact.map { it.getValue("id") },
            "compactArchive" to "shoal-first-listen.zip", "compactClipCount" to compact.size,
            "generationMilliseconds" to (System.nanoTime() - started) / 1_000_000.0,
            "initialTrace" to linkedMapOf("id" to "ripple_default", "frames" to rows.first { it["id"] == "ripple_default" }.getValue("energyTrace")),
            "clips" to rows.map { row -> row.filterKeys { it != "energyTrace" } },
        )
        val manifestText = Json.write(json(manifest)) + "\n"
        val summary = linkedMapOf(
            "engine" to "SHOAL", "modelVersion" to Shoal.HARMONIC_MODEL_VERSION,
            "sourceRevision" to manifest["sourceRevision"], "sourceTreeDirty" to manifest["sourceTreeDirty"],
            "metricNotes" to METRIC_NOTES,
            "clips" to rows.map { row -> row.filterKeys { it != "energyTrace" } },
        )
        File(root, "manifest.json").writeText(manifestText)
        File(root, "measurements.json").writeText(Json.write(json(summary)) + "\n")
        File(root, "recipes.json").writeText(Json.write(json(rows.map { row -> linkedMapOf(
            "id" to row.getValue("id"), "recipe" to Json.parse(File(root, row.getValue("recipePath") as String).readText()),
        ) })) + "\n")
        File(root, "README.md").writeText("""
            # Shoal — model2 voice comparison

            94 dry engine cases; 188 mono 24-bit WAVs at 44.1 kHz. GLASS and RIPPLE only.
            The original 88-case matrix now uses model2. Six additional cases provide
            model2 C4 and model1 C3/C4 identity comparisons, all at velocity 1.
            Reproduce with `./gradlew :synth:generateShoalAudition`.
            Refresh only the page and compact manifest, keeping existing audio unchanged, with
            `./gradlew :synth:generateShoalAudition -PshoalRefreshPage`.
            This revises model1's insufficient voice contrast. Sonic acceptance awaits listening.

            Start with GLASS, then RIPPLE, then the three RIPPLE mechanism removals.
            Repeat each moving HOLD for several cycles. Matched files use the shared .03
            audition target; raw files preserve prelevel engine gain. No rack effects.
            All five timbral macros have five-point sweeps on both voices. Four corners
            cover CURRENT × COHESION, DENSITY × COHESION, COHESION × STRAY and CURRENT × GLOW.
            Every voice has MIDI 24 / 48 / 84 at velocity .25 / 1, an all-high finite note,
            and a moving sustain-only HOLD. Default C3/hard cases also supply matrix cells.

            Recipes are ordinary serialized ShoalPatch JSON with an additional audition
            annotation for duration and diagnostic mechanism removals. For ablations,
            reproduce with Shoal.probe(..., ablation = named override); patch playback
            itself always runs the full engine. The same macros/root/velocity are retained.
            Energy heatmaps show actual simulated moving-partial state, not an audio FFT.
            traces/ retains up to 96 actual state snapshots per case and original counts.

            shoal-first-listen.zip contains 16 matched default/ablation/HOLD/identity WAVs,
            their recipes and measurement summaries. The complete 94-case collection
            remains on the page with individual raw/matched WAV and recipe downloads.
            When the preserved original pack is present, legacy-v1/ contains its ten-clip
            ZIP copied byte for byte, README and provenance. Model 1 C3 replays are
            checked against the original raw/matched SHA256 fingerprints.
            Serve this folder over HTTP(S) for sample-accurate Web Audio looping.

            $METRIC_NOTES
        """.trimIndent() + "\n")
        val compactPaths = listOf("README.md", "measurements.json") + compact.flatMap { listOf(it.getValue("matchedPath") as String, it.getValue("recipePath") as String) }
        ZipOutputStream(File(root, "shoal-first-listen.zip").outputStream()).use { zip ->
            for (path in compactPaths.sorted()) {
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                File(root, path).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val page = checkNotNull(javaClass.getResourceAsStream("/audition/shoal-audition.html")).bufferedReader().use { it.readText() }
        File(root, "index.html").writeText(page.replace("__MANIFEST__", manifestText.replace("</", "<\\/")))
        val bytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        println("SHOAL complete: ${rows.size} cases, ${bytes / 1_000_000.0} MB under ${root.canonicalPath}")
    }

    /** Refresh presentation/metadata without rerendering or altering any recorded audio. */
    private fun refreshPage(root: File) {
        val previous = Json.parse(File(root, "manifest.json").readText()).obj()
        val clips = previous.getValue("clips").arr().map { clip -> JsonValue.Obj(clip.obj().filterKeys { it != "energyTrace" }) }
        val initial = clips.first { it.entries.getValue("id").str() == "ripple_default" }
        val frames = Json.parse(File(root, initial.entries.getValue("tracePath").str()).readText()).obj().getValue("frames")
        val updated = JsonValue.Obj(previous + mapOf(
            "clips" to JsonValue.Arr(clips), "initialTrace" to JsonValue.Obj(mapOf("id" to JsonValue.Str("ripple_default"), "frames" to frames)),
        ))
        val manifestText = Json.write(updated) + "\n"
        val page = checkNotNull(javaClass.getResourceAsStream("/audition/shoal-audition.html")).bufferedReader().use { it.readText() }
        File(root, "manifest.json").writeText(manifestText)
        File(root, "index.html").writeText(page.replace("__MANIFEST__", manifestText.replace("</", "<\\/")))
        println("SHOAL page and compact manifest refreshed; WAVs, recipes and traces retained unchanged")
    }

    private fun cases(): List<Case> = buildList {
        for (voice in voices) add(case(voice, "default", "defaults", "${voice.name} · default",
            if (voice == ShoalVoice.GLASS) "Does a clear key open into small, luminous gatherings?" else "Can you hear a group migrate, loosen and gather again?"))
        for (voice in voices) for ((ablation, question) in listOf(
            Shoal.Ablation.NO_TRANSPORT to "What disappears when the population stays at its energy-weighted mean distribution, with the same total-energy envelope?",
            Shoal.Ablation.NO_LOCAL_COHESION to "Do the groups lose their coordination when local occupancy attraction is removed?",
            Shoal.Ablation.NO_STRAY to "Does harmonic alignment become steadier when frequency departures are removed?",
        )) add(case(voice, ablation.name.lowercase(), "ablations", if (ablation == Shoal.Ablation.NO_TRANSPORT) "population motion removed" else ablation.name.removePrefix("NO_").replace('_', ' ').lowercase() + " removed",
            question, ablation = ablation))
        for (voice in voices) for (macro in timbre) for (step in steps) add(case(voice,
            "${macro.lowercase()}_${tag(step)}", "sweeps", "$macro · ${format(step)}", sweepQuestion(macro),
            mapOf(macro to step), macro = macro, step = step))
        for ((voice, pair) in listOf(
            ShoalVoice.RIPPLE to ("CURRENT" to "COHESION"), ShoalVoice.GLASS to ("DENSITY" to "COHESION"),
            ShoalVoice.RIPPLE to ("COHESION" to "STRAY"), ShoalVoice.RIPPLE to ("CURRENT" to "GLOW"),
        )) for (a in listOf(0f, 1f)) for (b in listOf(0f, 1f)) add(case(voice,
            "${pair.first.lowercase()}${a.toInt()}_${pair.second.lowercase()}${b.toInt()}", "interactions",
            "${pair.first} ${a.toInt()} / ${pair.second} ${b.toInt()}", "How does changing one control alter the effect of its companion?",
            mapOf(pair.first to a, pair.second to b), pair = "${pair.first} × ${pair.second}", values = listOf(a, b)))
        for (voice in voices) for (midi in listOf(24, 48, 84)) for (velocity in listOf(.25f, 1f)) {
            if (midi == 48 && velocity == 1f) continue
            add(case(voice, "m${midi}_v${tag(velocity)}", "pitch-velocity", "${note(midi)} · ${if (velocity == 1f) "hard" else "soft"}",
                "Does the root remain clear, and does velocity change the excitation as well as level?", midi = midi, velocity = velocity))
        }
        for (voice in voices) {
            add(case(voice, "all_high", "extremes", "${voice.name} · all high", "Is the busiest corner still pitched, bounded and useful?", timbre.associateWith { 1f }))
            add(case(voice, "hold", "held", "${voice.name} · moving HOLD", "Listen over several wraps: does the population keep moving without a fresh attack?", mapOf("HOLD" to 1f)))
        }
        for (voice in voices) {
            add(case(voice, "identity_m60", "identity", "${voice.name} · C4", "At the same C4 root, are the two revised voice identities easy to distinguish?", midi = 60))
            for (midi in listOf(48, 60)) add(case(voice, "v1_m$midi", "legacy", "Original v1 · ${voice.name}",
                "Compare the original voice with its model2 revision at the same root and shared listening level.", midi = midi, model = 1))
        }
    }

    private fun case(voice: ShoalVoice, suffix: String, group: String, title: String, question: String,
                     overrides: Map<String, Float> = emptyMap(), midi: Int = 48, velocity: Float = 1f,
                     ablation: Shoal.Ablation = Shoal.Ablation.NONE, macro: String? = null, step: Float? = null,
                     pair: String? = null, values: List<Float>? = null, seconds: Float? = null, model: Int = Shoal.HARMONIC_MODEL_VERSION) =
        Case("${voice.name.lowercase()}_$suffix", group, title, question,
            ShoalPatch("${voice.name} · $title", voice, Shoal.defaults(voice) + overrides, midi, velocity, model), ablation, macro, step, pair, values, seconds)

    private fun metrics(snip: Snip) = linkedMapOf(
        "peak" to snip.peak(), "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size, "loudness" to Loudness.of(snip),
    )

    /** Guided root probe covers C1 below the stock detector's40Hz floor. It cannot detect arbitrary wrong notes. */
    private fun pitch(snip: Snip, requestedHz: Double): Map<String, Any?> {
        val stock = Pitch.detect(snip, .08f, .35f)
        val x = snip.samples
        val from = (.1 * Dsp.RATE).toInt()
        val size = minOf((.4 * Dsp.RATE).toInt(), x.size - from - ceil(Dsp.RATE / (requestedHz * .94)).toInt() - 1)
        val low = floor(Dsp.RATE / (requestedHz * 1.04)).toInt().coerceAtLeast(2)
        val high = ceil(Dsp.RATE / (requestedHz * .96)).toInt()
        fun correlation(lag: Int): Double {
            var cross = 0.0; var a = 0.0; var b = 0.0
            for (i in from until from + size) {
                val u = x[i].toDouble(); val v = x[i + lag].toDouble()
                cross += u * v; a += u * u; b += v * v
            }
            return cross / sqrt(a * b).coerceAtLeast(1e-30)
        }
        val candidates = (low..high).associateWith(::correlation)
        val best = candidates.maxBy { it.value }
        val before = correlation(best.key - 1); val after = correlation(best.key + 1)
        val curvature = before - 2 * best.value + after
        val delta = if (abs(curvature) > 1e-12) (.5 * (before - after) / curvature).coerceIn(-.5, .5) else 0.0
        val hz = Dsp.RATE / (best.key + delta)
        return linkedMapOf("hz" to hz, "confidence" to best.value,
            "centsFromRequested" to 1200 * ln(hz / requestedHz) / ln(2.0),
            "method" to "Requested-root ±4% normalized autocorrelation with parabolic lag refinement; guided measurement, not an unconstrained pitch detector",
            "stockHz" to stock?.hz, "stockConfidence" to stock?.confidence)
    }

    private fun spectrum(snip: Snip): Map<String, Any> {
        val size = 4096
        val from = (.15 * Dsp.RATE).toInt()
        val mag = Fft.magnitudeSpectrum(snip.samples.copyOfRange(from, minOf(from + size, snip.samples.size)), size)
        val power = DoubleArray(mag.size) { mag[it].toDouble() * mag[it] }
        val sum = power.sum().coerceAtLeast(1e-30)
        var centroid = 0.0; var entropy = 0.0; var high = 0.0
        for (i in 1 until power.size) {
            val hz = Fft.binToHz(i, size, Dsp.RATE).toDouble(); val p = power[i] / sum
            centroid += hz * p
            if (p > 0) entropy -= p * ln(p)
            if (hz >= 16000) high += p
        }
        return linkedMapOf("fromSeconds" to .15, "fftFrames" to size, "centroidHz" to centroid,
            "normalizedEntropy" to entropy / ln(power.size.toDouble()), "powerAbove16kRatio" to high)
    }

    private fun distance(a: FloatArray, b: FloatArray): Double {
        var delta = 0.0; var power = 0.0
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0f }.toDouble(); val y = b.getOrElse(i) { 0f }.toDouble()
            delta += (x - y) * (x - y); power += x * x + y * y
        }
        return sqrt(delta / power.coerceAtLeast(1e-30))
    }

    private fun cycleDifference(previous: FloatArray, current: FloatArray): Double {
        var delta = 0.0; var power = 0.0
        for (i in current.indices) {
            val d = current[i].toDouble() - previous[i]
            delta += d * d; power += current[i].toDouble() * current[i]
        }
        return delta / power.coerceAtLeast(1e-30)
    }

    private fun group(id: String, title: String, description: String) = linkedMapOf("id" to id, "title" to title, "description" to description)
    private fun note(midi: Int) = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")[midi % 12] + (midi / 12 - 1)
    private fun midiHz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun tag(value: Float) = (value * 100).roundToInt().toString().padStart(3, '0')
    private fun format(value: Float) = "%.2f".format(java.util.Locale.ROOT, value)
    private fun sweepQuestion(macro: String) = when (macro) {
        "DENSITY" -> "Do a few dominant partials open into a broader population without losing the root?"
        "CURRENT" -> "Does the spectral energy travel farther or faster as current rises?"
        "COHESION" -> "Does dispersed energy gather into coordinated groups?"
        "STRAY" -> "Do selected groups loosen their tuning while the protected root stays steady?"
        else -> "Does the upper population persist differently, beyond a longer final envelope?"
    }
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).redirectError(File("/dev/null")).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        output.takeIf { process.waitFor() == 0 }
    }.getOrNull()
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) { "Non-finite evidence $value" } })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        is DoubleArray -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported Shoal manifest value ${value.javaClass}")
    }

    private const val METRIC_NOTES = "Raw PCM is the conditioned 44.1 kHz engine buffer at gain 1, before shared melodic/audition leveling. Internal 4× raw peak/RMS/DC and conservative-transport residual remain visible. Matched PCM uses AuditionLevel (.03 loudest 200 ms RMS, .99 peak guard). Energy heatmaps show up to 96 actual moving-partial simulation frames, normalized per frame for occupancy color; a separate line shows total moving energy. They are not spectrograms. Pitch combines the stock 40–1200 Hz detector with a separately labelled root-guided ±4% autocorrelation, which covers MIDI 24 but cannot diagnose arbitrary wrong notes. Whole-file classification can become LOOP by duration; the first 1.25 s classifier is retained separately. Spectral values use a 4096-frame Hann-windowed body FFT; high-frequency power is not proof of absence of aliasing. Waveform distance is not a perceptual score. HOLD uses the specified captured-trajectory approximation, joined in control space; it does not claim nonlinear-state convergence. Phase closure is fitted per partial. Seam and cycle-difference measurements compare independently synthesized adjacent cycles; duplicated buffers are not evidence. Render timings exclude metrics/encoding and depend on JIT/hardware. Sonic identity and loop quality require owner listening."
}
