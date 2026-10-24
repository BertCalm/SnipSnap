package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Dry, reproducible first-listen evidence; no listening verdict is implied by generation. */
object ApsisAuditionGenerator {
    private const val TARGET = .12f
    private const val CEILING = .90f
    private val BASE = mapOf("STRETCH" to .65f, "DISTANCE" to .4f, "BODIES" to .4f,
        "ALIGN" to .55f, "COUPLE" to .45f, "HOLD" to 0f)

    private data class Case(
        val id: String,
        val title: String,
        val group: String,
        val description: String,
        val voice: ApsisVoice = ApsisVoice.PASSAGE,
        val macros: Map<String, Float> = BASE,
        val midi: Int = 60,
        val velocity: Float = .8f,
        val seconds: Float? = null,
        val ablation: Apsis.Ablation = Apsis.Ablation.NONE,
        val disabledBody: Int? = null,
        val soloBody: Int? = null,
        val comparison: String = id,
        val sequence: Boolean = false,
        val config: ApsisConfig = ApsisConfig(modelVersion = 2),
        val individualMatch: Boolean = false,
    )

    private data class Prepared(
        val case: Case,
        val samples: FloatArray,
        val metrics: Map<String, Any?>,
        val trace: List<Map<String, Any?>>,
        val loop: Boolean,
        val recipe: Map<String, Any?>,
        val stems: Map<String, FloatArray> = emptyMap(),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/apsis-audition")
        val quick = "--quick" in args
        val performance = "--performance" in args
        val cases = if (performance) performanceCases() else cases().let { all -> if (quick) all.filter { it.id in setOf(
            "passage_default", "stretch_circle", "stretch_elongated", "coupling_on", "coupling_off",
            "recipient_on", "recipient_off", "held_passage", "passage_original") } else all }
        check(cases.map { it.id }.distinct().size == cases.size)
        root.mkdirs()
        val begin = System.nanoTime()
        val prepared = cases.mapIndexed { i, c ->
            println("APSIS ${i + 1}/${cases.size}: ${c.id}")
            prepare(c)
        }
        // A single gain per comparison retains real source, population, velocity and transfer differences.
        fun matchKey(c: Case) = if (c.individualMatch) c.id else c.comparison
        val gains = prepared.groupBy { matchKey(it.case) }.mapValues { (_, group) ->
            val loudness = group.maxOf { (it.metrics.getValue("rawLoudness") as Number).toFloat() }
            val peak = group.maxOf { (it.metrics.getValue("rawPeak") as Number).toFloat() }
            minOf(TARGET / loudness.coerceAtLeast(1e-9f), CEILING / peak.coerceAtLeast(1e-9f))
        }
        val rawGains = prepared.groupBy { it.case.comparison }.mapValues { (_, group) ->
            minOf(1f, .99f / group.maxOf { (it.metrics.getValue("rawPeak") as Number).toFloat() }.coerceAtLeast(1e-9f))
        }
        val rows = prepared.map { p ->
            val c = p.case
            val gain = gains.getValue(matchKey(c))
            val rawGain = rawGains.getValue(c.comparison)
            val rawPath = "clips/${c.id}_raw.wav"
            val matchedPath = "clips/${c.id}_matched.wav"
            val recipePath = "recipes/${c.id}.json"
            val patchPath = "recipes/${c.id}.patch"
            writeWav(File(root, rawPath), p.samples, rawGain)
            writeWav(File(root, matchedPath), p.samples, gain)
            writeJson(File(root, recipePath), p.recipe)
            p.recipe["patch"]?.let { writeJson(File(root, patchPath), it) }
            val stemFiles = p.stems.map { (name, samples) ->
                val path = "stems/${c.id}_$name.wav"
                writeWav(File(root, path), samples, rawGain)
                mapOf("name" to name, "file" to path, "gain" to rawGain)
            }
            linkedMapOf<String, Any?>(
                "id" to c.id, "title" to c.title, "group" to c.group,
                "description" to c.description, "voice" to c.voice.name,
                "modelVersion" to c.config.modelVersion,
                "macros" to (Apsis.defaults(c.voice) + c.macros), "midi" to c.midi,
                "velocity" to c.velocity, "rawFile" to rawPath, "matchedFile" to matchedPath,
                "recipeFile" to recipePath, "loop" to p.loop,
                "patchFile" to if (p.recipe.containsKey("patch")) patchPath else null,
                "stems" to stemFiles,
                "durationSeconds" to p.samples.size.toDouble() / Dsp.RATE,
                "comparisonGroup" to c.comparison,
                "gainPolicy" to if (c.individualMatch)
                    "Before/after and preset listening: each take independently targets the same loudest-200ms RMS, subject to the declared peak ceiling. Raw retains native balance."
                    else "One shared linear gain across this comparison group; no effects, limiter or stem normalization.",
                "metrics" to (p.metrics + mapOf("listeningGain" to gain, "rawExportGain" to rawGain)),
                "rawSha256" to sha256(File(root, rawPath).readBytes()),
                "matchedSha256" to sha256(File(root, matchedPath).readBytes()),
                "orbitTrace" to p.trace,
            )
        }
        val manifest = linkedMapOf<String, Any?>(
            "engine" to "APSIS", "prototype" to true, "modelVersion" to if (performance) Apsis.MODEL_VERSION else 2,
            "title" to if (performance) "Apsis / Playing response" else "Apsis / The bodies emerge", "baseRevision" to git("rev-parse", "HEAD"),
            "sourceStatus" to "Experimental voicing revision on the recorded base revision; exact source files are SHA-256 identified below.",
            "sourceFiles" to sourceHashes(),
            "workingTreeDirty" to !git("status", "--porcelain").isNullOrEmpty(),
            "sampleRate" to Dsp.RATE, "channels" to 1, "clipCount" to rows.size,
            "listeningTarget" to mapOf("loudest200msRms" to TARGET, "peakCeiling" to CEILING,
                "policy" to "Controlled motion, velocity and recipient comparisons share gain. Before/after and musical presets match each take independently; see each clip's gainPolicy."),
            "status" to "Experimental first-listen prototype. Mathematical and automated checks do not establish sonic acceptance.",
            "holdPolicy" to "Reference-compatible commensurate roster: periods divide one loop, axes derive from periods; frame/pitch fitting changes their common scale uniformly.",
            "buildSeconds" to (System.nanoTime() - begin) / 1e9,
            "clips" to rows,
        )
        writeJson(File(root, "manifest.json"), manifest)
        writeJson(File(root, "data/manifest.json"), manifest)
        // The polished site is supplied independently; retain a useful disk-local fallback.
        if (!File(root, "index.html").isFile) File(root, "index.html").writeText(fallback(rows))
        println("Wrote ${rows.size} dry APSIS performances, ${rows.size * 2} WAVs, recipes and manifest to ${root.absolutePath}")
    }

    private fun prepare(c: Case): Prepared {
        if (c.sequence) return phrase(c)
        val start = System.nanoTime()
        val options = Apsis.ProbeOptions(normalize = false, seconds = c.seconds,
            recordStems = true, recordTraces = true, ablation = c.ablation,
            directExcitationDisabled = c.disabledBody, soloBody = c.soloBody)
        val p = Apsis.probe(c.voice, c.macros, c.midi, c.velocity, c.config, options)
        val samples = p.samples
        require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "${c.id}: invalid audio" }
        val snip = Snip(samples, channels = 1, sampleRate = Dsp.RATE)
        val estimate = Pitch.detect(snip, .15f, .35f)
        val wanted = Keys.midiHz(c.midi).toDouble()
        // A recipient-only upper-mode probe deliberately excludes the protected root.
        val pitchFixture = c.soloBody == null
        val measured = if (pitchFixture && samples.any { abs(it) > 1e-9 })
            FineTuning.measuredHz(snip, wanted.toFloat(), .15f, .35f) else null
        val classification = Classifier.classify(snip).drumClass
        val forbidden = setOf(com.snipsnap.audio.DrumClass.KICK, com.snipsnap.audio.DrumClass.SNARE,
            com.snipsnap.audio.DrumClass.CLAP, com.snipsnap.audio.DrumClass.HAT_CLOSED,
            com.snipsnap.audio.DrumClass.HAT_OPEN, com.snipsnap.audio.DrumClass.TOM)
        require(!pitchFixture || classification !in forbidden) { "${c.id}: pitched fixture classified $classification" }
        require(!pitchFixture || measured != null && abs(1200 * ln(measured / wanted) / ln(2.0)) < 20.0) {
            "${c.id}: requested root did not survive synthesis"
        }
        require(samples.maxOf { abs(it) } < .95f) { "${c.id}: raw headroom exhausted" }
        val d = p.diagnostics
        val metrics = linkedMapOf<String, Any?>(
            "rawPeak" to (samples.maxOfOrNull { abs(it) } ?: 0f),
            "rawRms" to rms(samples), "rawDc" to samples.sumOf { it.toDouble() } / samples.size,
            "rawLoudness" to Loudness.of(snip),
            "requestedHz" to wanted, "effectiveHz" to p.effectiveHz,
            "measuredRootHz" to measured, "rootErrorCents" to measured?.let { 1200 * ln(it / wanted) / ln(2.0) },
            "detectedHz" to estimate?.hz, "pitchConfidence" to estimate?.confidence,
            "classifier" to classification.name, "pitchFixture" to pitchFixture,
            "seamError" to if (p.loopFrames > 0) finite(p.seamError) else null,
            "stateError" to if (p.loopFrames > 0) finite(p.stateError) else null,
            "maxStateNorm" to d.maxStateNorm,
            "maxUnforcedEnergyRise" to d.maxUnforcedEnergyRise,
            "finalEnergy" to d.finalEnergy,
            "sourceEndSeconds" to finite(d.sourceEndSeconds),
            "transientCounts" to d.transientCounts.toList(),
            "converged" to d.converged, "prerollCycles" to d.prerollCycles,
            "preparationMs" to d.preparationNanos / 1e6,
            "synthesisMs" to d.synthesisNanos / 1e6,
            "outputMs" to d.outputNanos / 1e6,
            "solverResidual" to d.solverResidual, "commonPeriodLawResidual" to d.commonPeriodLawResidual,
            "sourcePhaseClosureError" to d.sourcePhaseClosureError,
            "controlPhaseClosureError" to d.controlPhaseClosureError,
            "eventStateClosureError" to d.eventStateClosureError,
            "minDistance" to d.minDistance, "peakSpeed" to d.peakSpeed,
            "axes" to d.axes.toList(), "periods" to d.periods.toList(),
            "renderMs" to (System.nanoTime() - start) / 1e6,
        )
        val lastTraceTime = DoubleArray(6) { Double.NEGATIVE_INFINITY }
        val trace = p.traces.filter { f ->
            (f.timeSeconds - lastTraceTime[f.body] >= 1.0 / 30).also { keep -> if (keep) lastTraceTime[f.body] = f.timeSeconds }
        }.map { f -> linkedMapOf<String, Any?>(
            "timeSeconds" to f.timeSeconds, "body" to f.body, "x" to f.x, "y" to f.y,
            "r" to f.r, "speed" to f.speed, "excitation" to f.excitation, "sympathy" to f.sympathy) }
        val patch = ApsisPatch(c.title, c.voice, Apsis.defaults(c.voice) + c.macros, c.midi, c.velocity, c.config)
        val recipe = mapOf("schema" to "snipsnap.apsis-audition.v1", "patch" to fromJson(patch.toJsonValue()),
            "probe" to mapOf("normalize" to false, "seconds" to c.seconds,
                "ablation" to c.ablation.name, "directExcitationDisabled" to c.disabledBody,
                "soloBody" to c.soloBody, "oversample" to 4, "controlStride" to 64))
        val stems = if (c.id in setOf("passage_default", "mechanism_kepler")) linkedMapOf(
            "source" to p.source, "center" to p.center, "body_1" to p.bodies[0],
            "body_2" to p.bodies[1], "body_3" to p.bodies[2]) else emptyMap()
        return Prepared(c, samples, metrics, trace, p.loopFrames > 0, recipe, stems)
    }

    private fun phrase(c: Case): Prepared {
        val notes = listOf(60, 67, 63, 70)
        val starts = listOf(0.0, 1.5, 3.0, 4.5)
        val velocities = listOf(.65f, .8f, .55f, .9f)
        val out = FloatArray((10.5 * Dsp.RATE).roundToInt())
        val sequence = notes.indices.map { n ->
            val p = Apsis.probe(c.voice, c.macros, notes[n], velocities[n], c.config,
                Apsis.ProbeOptions(normalize = false, recordStems = false, recordTraces = false))
            val offset = (starts[n] * Dsp.RATE).roundToInt()
            for (i in p.samples.indices) if (offset + i < out.size) out[offset + i] += p.samples[i]
            mapOf("midi" to notes[n], "velocity" to velocities[n], "startSeconds" to starts[n])
        }
        val snip = Snip(out, channels = 1, sampleRate = Dsp.RATE)
        val metrics = mapOf("rawPeak" to out.maxOf { abs(it) }, "rawRms" to rms(out),
            "rawDc" to out.sumOf { it.toDouble() } / out.size, "rawLoudness" to Loudness.of(snip))
        return Prepared(c, out, metrics, emptyList(), false, mapOf(
            "schema" to "snipsnap.apsis-audition.v1", "patch" to fromJson(
                ApsisPatch(c.title, c.voice, c.macros, c.midi, c.velocity, c.config).toJsonValue()),
            "probe" to mapOf("normalize" to false, "seconds" to null, "oversample" to 4),
            "sequence" to sequence, "mix" to "Linear dry sum; no effects or additional layers."))
    }

    private fun performanceCases(): List<Case> = buildList {
        for (velocity in listOf(.25f, .65f, 1f)) add(Case(
            "performance_velocity_${(velocity * 100).roundToInt()}",
            "Excitation / ${when (velocity) { .25f -> "soft"; .65f -> "middle"; else -> "hard" }}", "playing",
            "Model 3 at C4, velocity $velocity. Geometry, seed and listening gain stay fixed; harder playing changes the injected spectrum before resonance.",
            velocity = velocity, config = ApsisConfig(), comparison = "performance_velocity"))
        for (couple in listOf(0f, .5f, 1f)) add(Case(
            "performance_couple_${(couple * 100).roundToInt()}", "Material exchange / ${(couple * 100).roundToInt()}%", "coupling",
            "Model 3, COUPLE $couple. The same C4 drive and orbits feed a normalized material port; only internal state exchange changes.",
            macros = BASE + ("COUPLE" to couple), config = ApsisConfig(), comparison = "performance_coupling"))
    }

    private fun cases(): List<Case> = buildList {
        add(Case("passage_original", "Original / steady root", "start",
            "Model 1, exactly the first published voicing. Compare its steady pitched bed with the revoiced passage at the same C4 and velocity. Matched adjusts each take independently.",
            config = ApsisConfig(modelVersion = 1), comparison = "before_after", individualMatch = true))
        add(Case("passage_default", "Revoiced / bodies emerge", "start",
            "Model 2: less steady-root masking, distinct body colors, and shorter upper-mode memory. Same orbit, C4 and velocity as Original. Judge whether the motion now earns its place.",
            comparison = "before_after", individualMatch = true))
        for ((index, patch) in ApsisPresets.all().withIndex()) add(Case(
            "preset_${index + 1}", patch.name, "voicings", when (index) {
                0 -> "One body on a slow, stretched orbit: a sparse encounter and a long interval before its return."
                1 -> "One close body passes quickly. Compare the attack spacing and color with Slow Apse, at the same C4."
                2 -> "All six stable body identities participate, with stronger exchange and overlapping colored tails."
                3 -> "Five bodies gather their first arrivals into a short bloom. Compare with Spaced Replies."
                4 -> "The same population and energy settings as Gathered Bloom, with arrivals spread apart by ALIGN."
                else -> "The revised population settles into a genuine repeating orbit. Repeat across several joins."
            }, macros = patch.macros, midi = patch.midi, velocity = .8f,
            config = patch.config.copy(modelVersion = 2), individualMatch = true))
        add(Case("stretch_circle", "Circular", "motion", "STRETCH 0. The same semimajor axes and periods as Elongated.",
            macros = BASE + ("STRETCH" to 0f), comparison = "stretch"))
        add(Case("stretch_elongated", "Elongated", "motion", "STRETCH 1. Listen for the short close passage and patient outer linger; periods remain fixed.",
            macros = BASE + ("STRETCH" to 1f), comparison = "stretch"))
        for ((id, value) in listOf("staggered" to 0f, "gathered" to 1f)) add(Case("align_$id",
            id.replaceFirstChar { it.titlecase() }, "motion", "ALIGN $value changes relative phases and orientations against a fixed excitation map.",
            macros = BASE + ("ALIGN" to value), seconds = 9f, comparison = "alignment"))
        for ((count, value) in listOf(1 to 0f, 3 to .4f, 6 to 1f)) add(Case("bodies_$count", "$count ${if (count == 1) "body" else "bodies"}",
            "motion", "Stable body identities join around the same center. Compare population and period layering at a shared gain.",
            macros = BASE + ("BODIES" to value), seconds = 8f, comparison = "bodies"))
        for ((name, value) in listOf("near" to 0f, "middle" to .5f, "far" to 1f)) add(Case("distance_$name",
            "Distance / $name", "motion", "DISTANCE $value: twelve seconds expose coherent period changes without cutting individual periods.",
            macros = BASE + ("DISTANCE" to value), seconds = 12f, comparison = "distance"))
        val coupling = BASE + mapOf("COUPLE" to .85f, "BODIES" to .4f)
        add(Case("coupling_on", "Shared center", "coupling", "The connected network: bodies exchange modal state through the center.",
            macros = coupling, comparison = "coupling"))
        add(Case("coupling_off", "Coupling bypassed", "coupling", "Same drive, geometry and pickup; orthogonal center/body exchange is bypassed.",
            macros = coupling, ablation = Apsis.Ablation.NO_COUPLING, comparison = "coupling"))
        add(Case("recipient_on", "An unexcited body answers", "coupling", "Only body 2 is heard. Its direct source injection is disabled; all sound must reach it through the shared center. Same listening gain as the full network.",
            macros = coupling, disabledBody = 1, soloBody = 1, comparison = "coupling"))
        add(Case("recipient_off", "Recipient / network removed", "coupling", "The same unexcited body with coupling bypassed. This intentionally silent control checks where its response came from.",
            macros = coupling, ablation = Apsis.Ablation.NO_COUPLING, disabledBody = 1, soloBody = 1, comparison = "coupling"))
        add(Case("recipient_magnified", "Recipient / magnified", "coupling",
            "The same weak upper-mode response, with a separate matched inspection gain. Raw retains its native level. This body receives no direct excitation.",
            macros = coupling, disabledBody = 1, soloBody = 1, comparison = "recipient_inspection"))
        add(Case("recipient_magnified_off", "Magnified bypass / silence", "coupling",
            "The same inspection gain as Recipient / magnified, with internal coupling bypassed. It remains silent.",
            macros = coupling, ablation = Apsis.Ablation.NO_COUPLING, disabledBody = 1, soloBody = 1, comparison = "recipient_inspection"))
        for ((name, mode) in listOf("kepler" to Apsis.Ablation.NONE, "uniform" to Apsis.Ablation.UNIFORM_ANGULAR,
            "output_only" to Apsis.Ablation.POST_OUTPUT)) add(Case("mechanism_$name", when (mode) {
                Apsis.Ablation.NONE -> "Kepler timing"; Apsis.Ablation.UNIFORM_ANGULAR -> "Uniform angular speed"; else -> "Motion after synthesis" },
            "motion", when (mode) {
                Apsis.Ablation.NONE -> "Reference: true equal-area orbital timing drives internal excitation and transfer."
                Apsis.Ablation.UNIFORM_ANGULAR -> "Diagnostic simplification: uniform angular speed around the same ellipse removes the slow linger and fast rush timing law."
                else -> "Diagnostic simplification: motion is moved to a final output gain; internal orbital excitation and transfer are removed." },
            macros = BASE + ("STRETCH" to .95f), ablation = mode, comparison = "mechanism"))
        for (stretch in listOf(0f, 1f)) for (align in listOf(0f, 1f)) add(Case(
            "interaction_${stretch.toInt()}_${align.toInt()}", "Stretch ${stretch.toInt()} / align ${align.toInt()}", "motion",
            "The fast passage meets the fixed excitation regions differently as STRETCH and ALIGN interact.",
            macros = BASE + mapOf("STRETCH" to stretch, "ALIGN" to align), seconds = 8f, comparison = "interaction"))
        for (midi in listOf(36, 60, 84)) for (velocity in listOf(.3f, .65f, 1f)) add(Case(
            "playing_${midi}_${(velocity * 100).roundToInt()}", "${noteName(midi)} / ${when (velocity) { .3f -> "soft"; .65f -> "medium"; else -> "hard" }}",
            "playing", "Requested MIDI $midi, velocity $velocity. All three velocities at this pitch retain one shared gain so playing energy remains audible.",
            midi = midi, velocity = velocity, comparison = "playing_$midi"))
        for (voice in listOf(ApsisVoice.PASSAGE)) add(Case("held_${voice.name.lowercase()}",
            "${voice.name.lowercase().replaceFirstChar { it.titlecase() }} / recurring field", "hold",
            "A genuinely settled reference-compatible orbital loop of about six seconds. Whole trajectories, excitation and modal state recur before the exported wrap is checked. Repeat several cycles.",
            voice, Apsis.defaults(voice) + mapOf("DISTANCE" to .425f, "HOLD" to 1f), seconds = null))
        add(Case("phrase", "Four passing notes", "playing", "C4, G4, E-flat4 and B-flat4, overlapped at 1.5-second intervals. Four independent dry renders; no effects.",
            sequence = true))
    }

    private fun finite(x: Double): Double? = x.takeIf { it.isFinite() }
    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size.coerceAtLeast(1))
    private fun writeWav(file: File, samples: FloatArray, gain: Float) = WavWriter.write(file,
        Snip(FloatArray(samples.size) { samples[it] * gain }, channels = 1, sampleRate = Dsp.RATE), WavWriter.BitDepth.PCM_24)
    private fun writeJson(file: File, value: Any?) { file.parentFile.mkdirs(); file.writeText(Json.write(json(value)) + "\n") }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) { "invalid evidence $value" } })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported evidence ${value.javaClass}")
    }
    private fun fromJson(value: JsonValue): Any? = when (value) {
        JsonValue.Null -> null; is JsonValue.Str -> value.value; is JsonValue.Bool -> value.value
        is JsonValue.Num -> value.value; is JsonValue.Obj -> value.entries.mapValues { fromJson(it.value) }
        is JsonValue.Arr -> value.items.map(::fromJson)
    }
    private fun git(vararg args: String): String? = runCatching {
        val p = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        out.takeIf { p.waitFor() == 0 }
    }.getOrNull()
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun sourceHashes(): List<Map<String, String>> {
        val repo = File(git("rev-parse", "--show-toplevel") ?: "..")
        return listOf("synth/src/main/kotlin/com/snipsnap/synth", "synth/src/test/kotlin/com/snipsnap/synth")
            .flatMap { path -> File(repo, path).listFiles()?.filter { it.isFile && it.name.startsWith("Apsis") && it.extension == "kt" }.orEmpty() }
            .sortedBy { it.name }.map { file -> mapOf("path" to file.relativeTo(repo).invariantSeparatorsPath,
                "sha256" to sha256(file.readBytes())) }
    }
    private fun noteName(midi: Int) = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[midi % 12] + (midi / 12 - 1)
    private fun escape(x: String) = x.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
    private fun fallback(rows: List<Map<String, Any?>>) = """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Apsis · First orbit</title><style>body{max-width:900px;margin:60px auto;padding:20px;background:#10151d;color:#eee;font:17px system-ui}article{border-top:1px solid #394454;padding:22px 0}audio{width:100%}a{color:#a5d8e9}</style><h1>Apsis / First orbit</h1><p>An experimental dry orbital instrument. Listen at a comfortable volume. Matched and raw versions share one performance; comparison groups share one listening gain.</p>${rows.joinToString("\n") { r -> "<article><h2>${escape(r.getValue("title").toString())}</h2><p>${escape(r.getValue("description").toString())}</p><audio controls preload=\"none\" src=\"${r["matchedFile"]}\"${if (r["loop"] == true) " loop" else ""}></audio><p><a href=\"${r["rawFile"]}\">Raw WAV</a> · <a href=\"${r["recipeFile"]}\">Recipe</a></p></article>" }}<p>Listening acceptance remains open. <a href="manifest.json">All measurements and recipes</a></p></html>"""
}
