package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/** Five complete experimental encounters beside two byte-pinned accepted depth references. */
object OhmReplyRingAuditionGenerator {
    private const val RATE = WavWriter.MPC_SAMPLE_RATE
    private const val TYPE = "OHM_REPLY_RING_STUDY"
    private const val VERSION = 1
    private const val ROOT_HZ = 261.6255653005986
    private const val BUNDLE = "assets/ohm-reply-ring.zip"
    private const val MODEL2_SOURCE = "synth/src/main/kotlin/com/snipsnap/synth/OhmV2.kt"
    private const val MODEL2_SOURCE_SHA256 = "261f9409555b2e053c5db512579f776ee02dce42b610cf1bf79ce134d1914f78"
    private const val STUDY_DIRECTORY = "synth/src/ohmReplyRingStudy"
    private val anchors = mapOf(
        "reference-companions" to "b63d15e54f0d5dddcd7782b5034080d50c6520acdb645ac95c353ab5c9e1b8bb",
        "reference-shared" to "e2dbf07fa19271a154f840e27ffa29488fbcf58447771916f9646d4dc47a22dd",
    )
    private val base = linkedMapOf("TOUCH" to .5f, "BODY" to .5f, "LENGTH" to .65f,
        "MEDDLING" to .9f, "COMMUNE" to 1f)

    private data class Clip(val id: String, val title: String, val reply: Float? = null,
                            val ring: Float? = null, val referenceCommune: Float? = null) {
        val reference: Boolean get() = referenceCommune != null
        val macros: Map<String, Float> get() = if (reference) base + ("COMMUNE" to referenceCommune!!) else base
    }

    private val clips = listOf(
        Clip("reply-tight", "Tight replies · Medium ring", 0f, .5f),
        Clip("middle", "Natural replies · Medium ring", .5f, .5f),
        Clip("reply-spread", "Spaced replies · Medium ring", 1f, .5f),
        Clip("ring-short", "Natural replies · Short ring", .5f, 0f),
        Clip("ring-long", "Natural replies · Long ring", .5f, 1f),
        Clip("reference-companions", "Step 2 · Add companions", referenceCommune = 0f),
        Clip("reference-shared", "Step 3 · Share vibration", referenceCommune = 1f),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "--replay") {
            require(args.size in 3..4) { "--replay SCORE.json OUTPUT.wav [raw|matched]" }
            val score = Json.parse(File(args[1]).readText())
            val mode = args.getOrNull(3) ?: "matched"
            val sound = replay(score, mode)
            val bytes = encode(sound)
            val expected = score.obj().getValue("hashes").obj().getValue("${mode}WavSha256").str()
            check(sha(bytes) == expected) { "Replay differs from the saved $mode WAV; no output written" }
            File(args[2]).also { it.parentFile?.mkdirs(); it.writeBytes(bytes); validateWav(it, sound) }
            println("Replayed $mode ${args[2]} with the saved WAV hash")
            return
        }
        require(args.size <= 1) { "Supply only the generated output directory" }
        generate(File(args.firstOrNull() ?: "../testkit/ohm-reply-ring-audition"))
    }

    fun generate(output: File) {
        val repository = repository()
        val root = output.canonicalFile
        require(root != repository && !repository.toPath().startsWith(root.toPath())) { "Choose a generated output directory" }
        require(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        val sources = sourceHashes(repository)
        val revision = git(repository, "rev-parse", "HEAD")
        val dirty = git(repository, "status", "--porcelain").isNotEmpty()
        val paths = linkedSetOf<String>()
        val probes = clips.filterNot { it.reference }.associate { spec ->
            spec.id to OhmReplyRingStudy.probe(spec.macros, midi = 60, velocity = .8f, seed = 731,
                reply = spec.reply!!, ring = spec.ring!!, recordPorts = false)
        }
        val raw = clips.associate { spec -> spec.id to (probes[spec.id]?.snip ?: reference(spec.macros)) }
        for (spec in clips) {
            val sound = raw.getValue(spec.id)
            validateSound(sound)
            if (!spec.reference) check(sound.frameCount == 7 * RATE) { "${spec.id}: study must retain all seven seconds" }
        }
        val rawGain = minOf(1.0, .98 / raw.values.maxOf(::peak).coerceAtLeast(1e-12))
        val decoded = linkedMapOf<String, Snip>()
        val entries = clips.map { spec ->
            val sound = raw.getValue(spec.id)
            val matched = level(sound)
            val rawPath = "assets/audio/${spec.id}-raw.wav"
            val matchedPath = "assets/audio/${spec.id}-matched.wav"
            val recipePath = "assets/recipes/${spec.id}.json"
            writeWav(File(root, rawPath), scaled(sound, rawGain))
            writeWav(File(root, matchedPath), matched)
            paths += listOf(rawPath, matchedPath, recipePath)
            val hashes = linkedMapOf("rawWavSha256" to sha(File(root, rawPath)), "matchedWavSha256" to sha(File(root, matchedPath)))
            anchors[spec.id]?.let { check(hashes.getValue("matchedWavSha256") == it) { "${spec.id}: accepted reference WAV changed" } }
            val parameters = linkedMapOf<String, Any?>("macros" to spec.macros, "midi" to 60, "velocity" to .8f,
                "seed" to 731, "reply" to spec.reply, "ring" to spec.ring, "companionExcitation" to true)
            val score = json(linkedMapOf<String, Any?>(
                "schemaVersion" to VERSION, "type" to TYPE, "studyVersion" to VERSION, "name" to spec.title,
                "renderer" to if (spec.reference) "OhmV2.renderRaw" else "OhmReplyRingStudy.renderRaw",
                "acceptedModel" to if (spec.reference) 2 else null, "parameters" to parameters,
                "sampleRate" to RATE, "channels" to 1, "frames" to sound.frameCount,
                "rawExportGain" to rawGain, "matchedPolicy" to "House AuditionLevel: loudest 200ms target .03; peak guard .99",
                "sourceRevision" to revision, "sourceTreeDirty" to dirty, "rendererSources" to sources,
                "canonicalModel2SourceSha256" to MODEL2_SOURCE_SHA256, "hashes" to hashes,
            ))
            writeJson(File(root, recipePath), score)
            val saved = Json.parse(File(root, recipePath).readText())
            // Rendering from the serialized score catches hidden defaults and export gain differences.
            for (mode in listOf("raw", "matched")) {
                val bytes = encode(replay(saved, mode))
                check(sha(bytes) == hashes.getValue("${mode}WavSha256")) { "${spec.id}: $mode score replay changed the WAV" }
            }
            val rawPcm = WavReader.read(File(root, rawPath))
            val matchedPcm = WavReader.read(File(root, matchedPath))
            val measuredPitch = pitchWindows(matchedPcm)
            if (!spec.reference) for (window in measuredPitch.filter { (it.getValue("fromSeconds") as Float) <= 2.5f }) {
                val from = window.getValue("fromSeconds") as Float
                val role = window.getValue("detectedFamilyRegister")
                val cents = window[if (from <= .5f) "centsFromC4" else "centsFromHarmonic"] as? Double
                val confidence = window["confidence"] as? Float
                val allowedRole = if (from <= .5f) role == "root" else role in setOf("root", "octave", "third-harmonic")
                check(allowedRole && cents != null && abs(cents) < 20 && confidence != null && confidence >= .3f) {
                    "${spec.id}: decoded mixed pitch is outside its ${if (from <= .5f) "C4 root" else "root/octave/third companion"} target at ${from}s: $window"
                }
            }
            decoded[spec.id] = matchedPcm
            val probe = probes[spec.id]
            val sourceEnd = probe?.sourceEndSeconds ?: (.40 + 3.0 * .65)
            val entry = linkedMapOf<String, Any?>(
                "id" to spec.id, "title" to spec.title, "kind" to if (spec.reference) "accepted-reference" else "study",
                "studyVersion" to if (spec.reference) null else VERSION, "modelVersion" to if (spec.reference) 2 else null,
                "raw" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
                "seconds" to sound.frameCount.toDouble() / RATE, "parameters" to parameters,
                "hashes" to hashes + ("recipeSha256" to sha(File(root, recipePath))),
                "metrics" to linkedMapOf("frames" to sound.frameCount, "finite" to true,
                    "conditionedRawPeak" to peak(sound), "conditionedRawRms" to rms(sound.samples),
                    "decodedRaw" to audioMetrics(rawPcm), "decodedMatched" to audioMetrics(matchedPcm),
                    "decodedMatchedPitchWindows" to measuredPitch,
                    "decodedMatchedEnvelope50msRms" to envelope(matchedPcm),
                    "decodedMatchedTailFit" to tailFit(matchedPcm, sourceEnd + .2),
                    "maxInternalState" to probe?.maxState, "pcmValidated" to true, "rawAndMatchedReplayByteExact" to true),
                "studyTiming" to probe?.let {
                    linkedMapOf("whistleOnsetSeconds" to it.onsets.toList(),
                        "glassOnsetSeconds" to it.onsets.map { onset -> onset + .035 + .07 * spec.reply!! },
                        "requestedWhistleT60Seconds" to it.whistleT60, "requestedGlassT60Seconds" to it.glassT60,
                        "t60Policy" to "Model damping targets, not measured radiated decay; feedback safety limits can shorten the realized T60.",
                        "sourceEndSeconds" to it.sourceEndSeconds, "maximumPressureEnvelope" to it.pressurePeak)
                },
            )
            println("OHM Reply/Ring ${spec.id}: ${sound.frameCount} frames; raw and matched replay exact")
            entry
        }
        // The accepted mixture already has a late octave-register pitch estimate.
        // Verify that evidence from the actual byte-pinned decoded reference, rather
        // than excusing every late pitch or asserting that the mixture always reads C4.
        val acceptedLate = pitchWindows(decoded.getValue("reference-shared")).single { it["fromSeconds"] == 2.5f }
        val middleLate = pitchWindows(decoded.getValue("middle")).single { it["fromSeconds"] == 2.5f }
        check(acceptedLate["detectedFamilyRegister"] == "octave" && (acceptedLate["confidence"] as? Float ?: 0f) >= .3f) {
            "The accepted reference no longer supplies the documented 2.5s octave-register evidence: $acceptedLate"
        }
        val pitchPolicy = "Decoded mix must read C4 within20cents at .08/.5s; at1.25/2.5s it must remain within20cents of the root, octave or third harmonic, with confidence at least .3. Later quiet windows are reported without an all-time root claim."
        val latePitchEvidence = linkedMapOf<String, Any?>(
            "acceptedReferenceClipId" to "reference-shared", "acceptedMatchedWavSha256" to anchors.getValue("reference-shared"),
            "acceptedReferenceWindow" to acceptedLate, "studyMiddleWindow" to middleLate,
            "correction" to "The initially proposed all-time C4-only mix gate was invalid: the unchanged accepted shared reference also detects the octave register at2.5s. The corrected gate retains early C4 and rejects later off-series pitch or harmonic drift; foreground/native-root proof remains separate.",
            "interpretation" to "Detected family register reports a mixed autocorrelation frequency near1x/2x/3x the requested root. It does not identify a dominant physical port; the primary string itself contains harmonics.",
        )
        val changes = listOf("reply-tight" to "middle", "middle" to "reply-spread",
            "ring-short" to "middle", "middle" to "ring-long").map { (a, b) ->
            val measured = envelopeDifference(decoded.getValue(a), decoded.getValue(b))
            check(measured.getValue("after300msEnergyMassMoved") > .01) { "$a to $b moves less than 1% of the delivered temporal energy" }
            linkedMapOf("baseline" to a, "changed" to b, "decodedMatchedEnvelope" to measured)
        }
        val comparisons = listOf(
            linkedMapOf("id" to "reply", "title" to "When do the companions answer?",
                "description" to "The same five replies move closer together or farther apart. Ring stays in the middle.",
                "question" to "Which reply spacing would you use?",
                "variants" to listOf(variant("reply-tight", "Tight"), variant("middle", "Natural"), variant("reply-spread", "Spaced"))),
            linkedMapOf("id" to "ring", "title" to "How long do the companions ring?",
                "description" to "The two companions settle sooner or ring longer. Reply spacing stays natural.",
                "question" to "Which amount of ringing would you use?",
                "variants" to listOf(variant("ring-short", "Short"), variant("middle", "Medium"), variant("ring-long", "Long"))),
        )
        writeJson(File(root, "assets/manifest.json"), linkedMapOf(
            "schemaVersion" to VERSION, "type" to TYPE, "studyVersion" to VERSION,
            "title" to "OHM / Reply and Ring", "renderedAt" to Instant.now().toString(),
            "sourceRevision" to revision, "sourceTreeDirty" to dirty, "rendererSources" to sources,
            "canonicalModel2SourceSha256" to MODEL2_SOURCE_SHA256,
            "runtime" to mapOf("java" to System.getProperty("java.runtime.version"), "os" to System.getProperty("os.name"), "arch" to System.getProperty("os.arch")),
            "sampleRate" to RATE, "channels" to 1, "format" to "PCM_16", "rawExportGain" to rawGain,
            "bundle" to BUNDLE, "comparisons" to comparisons,
            "mixedPitchPolicy" to pitchPolicy, "lateMixedPitchEvidence" to latePitchEvidence,
            "references" to listOf(variant("reference-companions", "Step 2 · Add companions"), variant("reference-shared", "Step 3 · Share vibration")),
            "clips" to entries,
            "notes" to listOf("Isolated listening study derived from accepted model 2; not a new production renderer, patch, preset or app feature.",
                "All five study WAVs retain their complete seven seconds. The two unchanged accepted references have their original shorter duration and are direction references, not isolated A/B baselines.",
                "Each comparison offers all three control positions. Both share the exact same middle WAV.",
                "C4, velocity .8, seed731, TOUCH/BODY .5, LENGTH .65, MEDDLING .9, COMMUNE1 stay fixed in the study.",
                "REPLY changes the schedule; RING changes only companion damping targets. Reported T60s are targets, not measured acoustic decay.",
                "The root starts at C4. Later mixed autocorrelation may register at2x/3x; the byte-pinned accepted shared reference already detects the octave at2.5s. This does not identify a dominant physical port, claim all-time perceived C4, or replace the separate foreground tuning proof.",
                "Each complete note is matched by the house loudest200ms rule. Raw WAVs use one common scalar. No crop, loop, effect or browser synthesis.",
                "The serialized study score is replayable by this study generator; it is not a production PadRecipe or importable phone preset."),
        ))
        writeJson(File(root, "assets/diagnostics.json"), linkedMapOf(
            "clips" to entries, "comparisons" to changes,
            "mixedPitchPolicy" to pitchPolicy, "lateMixedPitchEvidence" to latePitchEvidence,
            "envelopePolicy" to "50ms decoded matched RMS bins; energy distributions normalized after300ms. Moved mass is half their L1 distance, not a waveform-null or phase test.",
            "minimumAdjacentMovedEnergyMass" to .01, "allRawAndMatchedScoresReplayByteExactly" to true,
        ))
        paths += listOf("assets/manifest.json", "assets/diagnostics.json")
        for (name in listOf("index.html", "styles.css", "app.js")) {
            val stream = requireNotNull(javaClass.getResourceAsStream("/ohm-reply-ring/$name")) { "Missing Reply/Ring page resource $name" }
            val bytes = stream.use { it.readBytes() }
            val source = "$STUDY_DIRECTORY/resources/ohm-reply-ring/$name"
            check(sha(bytes) == sources.getValue(source)) { "Loaded $name differs from its source fingerprint; rebuild the study classes" }
            File(root, name).writeBytes(bytes)
            paths += name
        }
        for ((path, hash) in sources) {
            val target = "source/$path"
            File(repository, path).copyTo(File(root, target).also { it.parentFile.mkdirs() }, overwrite = true)
            check(sha(File(root, target)) == hash) { "Source snapshot changed: $path" }
            paths += target
        }
        File(root, "README.txt").writeText(readme())
        paths += "README.txt"
        check(sourceHashes(repository) == sources) { "Renderer or page source changed while generating; rebuild from stable source" }
        File(root, "SHA256SUMS.txt").writeText(paths.sorted().joinToString("\n") { "${sha(File(root, it))}  $it" } + "\n")
        paths += "SHA256SUMS.txt"
        val archive = File(root, BUNDLE)
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (path in paths.sorted()) {
                zip.putNextEntry(ZipEntry(path).apply { time = 0L })
                File(root, path).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        File(root, "$BUNDLE.sha256").writeText("${sha(archive)}  ${archive.name}\n")
        println("OHM Reply/Ring: five study notes, two exact accepted references; ${archive.absolutePath}")
    }

    /** The source fingerprints deliberately bind this experimental score to its renderer. */
    fun replay(value: JsonValue, mode: String): Snip {
        require(mode in setOf("raw", "matched")) { "Replay mode must be raw or matched" }
        val score = value.obj()
        require(score.getValue("type").str() == TYPE && score.getValue("schemaVersion").int() == VERSION && score.getValue("studyVersion").int() == VERSION)
        require(score.getValue("sampleRate").int() == RATE && score.getValue("channels").int() == 1)
        require(score.getValue("canonicalModel2SourceSha256").str() == MODEL2_SOURCE_SHA256)
        val expected = score.getValue("rendererSources").obj().mapValues { it.value.str() }
        check(sourceHashes(repository()) == expected) { "Saved study source fingerprints differ; replay with the archived source revision" }
        val p = score.getValue("parameters").obj()
        val macros = p.getValue("macros").obj().mapValues { it.value.num().toFloat() }
        require(macros.keys == base.keys && macros.values.all { it.isFinite() && it in 0f..1f }) { "Study score must retain all five macros" }
        val midi = p.getValue("midi").int()
        val velocity = p.getValue("velocity").num().toFloat()
        val seed = p.getValue("seed").int()
        require(p.getValue("companionExcitation").bool()) { "Published scores require the complete companion sources" }
        val sound = when (score.getValue("renderer").str()) {
            "OhmReplyRingStudy.renderRaw" -> OhmReplyRingStudy.renderRaw(macros, midi, velocity, seed,
                p.getValue("reply").num().toFloat(), p.getValue("ring").num().toFloat())
            "OhmV2.renderRaw" -> {
                require(score.getValue("acceptedModel").int() == 2)
                OhmV2.renderRaw(macros, midi, velocity, seed, OhmGesture.STEADY)
            }
            else -> error("Unknown study renderer")
        }
        validateSound(sound)
        require(sound.frameCount == score.getValue("frames").int()) { "Saved study length changed" }
        val gain = score.getValue("rawExportGain").num()
        require(gain.isFinite() && gain > 0 && gain <= 1)
        return if (mode == "raw") scaled(sound, gain) else level(sound)
    }

    private fun reference(macros: Map<String, Float>) = OhmV2.renderRaw(macros, 60, .8f, 731, OhmGesture.STEADY)
    private fun variant(id: String, label: String) = mapOf("clipId" to id, "label" to label)

    /** Exact short reproduction of the house test AuditionLevel, isolated to this study source set. */
    private fun level(snip: Snip): Snip {
        val out = snip.samples.copyOf()
        val loudness = Loudness.of(snip)
        var g = .03f / loudness.coerceAtLeast(1e-9f)
        var peak = 0f
        for (v in out) peak = maxOf(peak, abs(v))
        if (peak * g > .99f) g = .99f / peak
        for (i in out.indices) out[i] *= g
        return Snip(out, snip.channels, snip.sampleRate)
    }

    private fun validateSound(s: Snip) {
        check(s.channels == 1 && s.sampleRate == RATE && s.samples.isNotEmpty())
        check(s.samples.all { it.isFinite() } && Loudness.of(s) > 1e-8f) { "Non-finite or silent study audio" }
    }
    private fun encode(s: Snip): ByteArray {
        check(peak(s) <= .990001) { "Study PCM exceeds export headroom" }
        return ByteArrayOutputStream().also { WavWriter.write(it, s, WavWriter.BitDepth.PCM_16) }.toByteArray()
    }
    private fun writeWav(file: File, s: Snip) {
        file.parentFile.mkdirs(); file.writeBytes(encode(s)); validateWav(file, s)
    }
    private fun validateWav(file: File, s: Snip) {
        val decoded = WavReader.read(file)
        check(decoded.sampleRate == RATE && decoded.channels == 1 && decoded.frameCount == s.frameCount)
        check(decoded.samples.indices.all { abs(decoded.samples[it] - s.samples[it]) < 2f / 32768f })
        check(abs(decoded.samples.sumOf { it.toDouble() } / decoded.frameCount) < .01) { "Decoded study DC exceeds .01" }
    }
    private fun scaled(s: Snip, gain: Double) = Snip(FloatArray(s.samples.size) { (s.samples[it] * gain).toFloat() }, 1, RATE)
    private fun peak(s: Snip): Double = s.samples.maxOf { abs(it).toDouble() }
    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
    private fun audioMetrics(s: Snip): Map<String, Any?> = mapOf("peak" to peak(s), "rms" to rms(s.samples),
        "dc" to s.samples.sumOf { it.toDouble() } / s.frameCount, "loudness" to Loudness.of(s),
        "finite" to s.samples.all { it.isFinite() }, "peakBelowFullScale" to (peak(s) < 1))
    private fun pitchWindows(s: Snip): List<Map<String, Any?>> = listOf(.08f, .5f, 1.25f, 2.5f, 4.5f, 5.8f)
        .filter { it + .30f <= s.durationSeconds }.map { from ->
            val estimate = Pitch.detect(s, from, .30f)
            val harmonic = estimate?.let { pitch -> (1..3).minBy { abs(1200 * ln(pitch.hz / (ROOT_HZ * it)) / ln(2.0)) } }
            val centsFromHarmonic = estimate?.let { 1200 * ln(it.hz / (ROOT_HZ * harmonic!!)) / ln(2.0) }
            val role = when {
                harmonic == null -> "unresolved"
                abs(centsFromHarmonic!!) >= 20 -> "off-series"
                harmonic == 1 -> "root"
                harmonic == 2 -> "octave"
                else -> "third-harmonic"
            }
            val start = (from * RATE).toInt()
            val end = minOf(s.frameCount, start + (.30f * RATE).toInt())
            mapOf("fromSeconds" to from, "windowSeconds" to .30f, "hz" to estimate?.hz, "confidence" to estimate?.confidence,
                "centsFromC4" to estimate?.let { 1200 * ln(it.hz / ROOT_HZ) / ln(2.0) },
                "detectedFamilyRegister" to role, "nearestHarmonic" to harmonic, "centsFromHarmonic" to centsFromHarmonic,
                "decodedWindowRms" to rms(s.samples.copyOfRange(start, end)))
        }
    private fun envelope(s: Snip): List<Double> {
        val width = RATE / 20
        return (s.samples.indices step width).map { from ->
            val end = minOf(from + width, s.frameCount)
            sqrt((from until end).sumOf { s.samples[it].toDouble() * s.samples[it] } / (end - from))
        }
    }
    private fun envelopeDifference(a: Snip, b: Snip): Map<String, Double> {
        require(a.frameCount == b.frameCount)
        val x = envelope(a); val y = envelope(b)
        fun moved(from: Int): Double {
            val sx = (from until x.size).sumOf { x[it] * x[it] }.coerceAtLeast(1e-24)
            val sy = (from until y.size).sumOf { y[it] * y[it] }.coerceAtLeast(1e-24)
            return .5 * (from until x.size).sumOf { abs(x[it] * x[it] / sx - y[it] * y[it] / sy) }
        }
        val first = 6 // Six complete 50ms bins omit the initiating pluck.
        val xr = rms(a.samples.copyOfRange((.3 * RATE).toInt(), a.frameCount))
        val yr = rms(b.samples.copyOfRange((.3 * RATE).toInt(), b.frameCount))
        return mapOf("wholeEnergyMassMoved" to moved(0), "after300msEnergyMassMoved" to moved(first),
            "after300msRmsRatioDb" to 20 * log10((yr + 1e-12) / (xr + 1e-12)))
    }
    private fun tailFit(s: Snip, from: Double): Map<String, Any?> {
        val values = envelope(s).mapIndexedNotNull { i, value ->
            val seconds = (i + .5) * .05
            if (seconds >= from && seconds < s.durationSeconds - .17 && value > 1e-7) seconds to 20 * log10(value) else null
        }
        if (values.size < 4) return mapOf("points" to values.size, "slopeDbPerSecond" to null,
            "policy" to "Mixed decoded tail after source release; insufficient signal, not a measured companion T60")
        val tx = values.map { it.first }.average(); val ty = values.map { it.second }.average()
        val slope = values.sumOf { (it.first - tx) * (it.second - ty) } / values.sumOf { (it.first - tx) * (it.first - tx) }
        val residual = values.sumOf { val d = it.second - ty - slope * (it.first - tx); d * d }
        val variance = values.sumOf { val d = it.second - ty; d * d }
        return mapOf("fromSeconds" to from, "points" to values.size, "slopeDbPerSecond" to slope,
            "rSquared" to if (variance > 1e-12) 1 - residual / variance else null,
            "policy" to "Descriptive mixed decoded tail slope, not a measured companion T60")
    }
    private fun repository() = File(git(File("."), "rev-parse", "--show-toplevel")).canonicalFile
    private fun git(repo: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + args).redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "git failed" }; return result
    }
    private fun sourceHashes(repo: File): Map<String, String> {
        val main = listOf("OhmV2.kt", "Strings.kt", "Modes.kt", "Dsp.kt", "Keys.kt", "Tide.kt")
            .map { "synth/src/main/kotlin/com/snipsnap/synth/$it" }
        val audio = listOf("Cleanup.kt", "Loudness.kt", "WavWriter.kt", "WavReader.kt", "Pitch.kt", "Resampler.kt")
            .map { "audio/src/main/kotlin/com/snipsnap/audio/$it" }
        val paths = main + audio + listOf("json/src/main/kotlin/com/snipsnap/json/Json.kt", "synth/build.gradle.kts",
            "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
            "$STUDY_DIRECTORY/kotlin/com/snipsnap/synth/OhmReplyRingStudy.kt",
            "$STUDY_DIRECTORY/kotlin/com/snipsnap/synth/OhmReplyRingStudyCheck.kt",
            "docs/OHM_REPLY_RING_STUDY.md",
            "$STUDY_DIRECTORY/kotlin/com/snipsnap/synth/OhmReplyRingAuditionGenerator.kt") +
            listOf("index.html", "styles.css", "app.js").map { "$STUDY_DIRECTORY/resources/ohm-reply-ring/$it" }
        val sources = paths.associateWith { path -> check(File(repo, path).isFile) { "Missing source $path" }; sha(File(repo, path)) }
        check(sources.getValue(MODEL2_SOURCE) == MODEL2_SOURCE_SHA256) { "Canonical accepted model-2 source changed" }
        return sources
    }
    private fun sha(file: File) = sha(file.readBytes())
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun writeJson(file: File, value: Any?) { file.parentFile.mkdirs(); file.writeText(Json.write(json(value)) + "\n") }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("Unsupported study metadata ${value.javaClass.name}")
    }
    private fun readme() = """
        OHM / REPLY AND RING — SMALL LISTENING STUDY

        REPLY: Tight, Natural, Spaced; RING stays at .5.
        RING: Short, Medium, Long; REPLY stays at .5.
        Both comparisons share one identical middle file. Five study notes are
        complete seven-second encounters: C4, velocity .8, seed731, TOUCH/BODY .5,
        LENGTH .65, MEDDLING .9 and COMMUNE1. There are no effects or held loops.
        The two accepted model-2 depth references keep their full original duration
        and exact published matched WAV hashes; they are not isolated study baselines.

        Reply changes the timing of five replies from two companions. Ring changes their damping
        targets, not the primary's own decay. In the shared bridge the companions
        can affect the primary's future response. Requested T60 metadata is a model
        target, not a measured acoustic decay; the feedback safety cap can shorten it.
        This is a prototype study, not a production renderer, factory preset or app update.

        The decoded mix is checked against C4 at .08/.5 seconds. At1.25/2.5 seconds
        the mixed estimate may register at1x/2x/3x the root, each within20cents.
        Confidence and detected family register are recorded for every measured window.
        This does not identify a dominant port: the primary itself has harmonics.
        The original proposed all-time C4-only mix gate was corrected because the
        unchanged, byte-pinned accepted shared reference also detects its octave at
        2.5 seconds. Diagnostics include that actual reference window beside the new
        middle note. Off-series pitch and harmonic drift still fail; foreground/native
        root checks remain separate. This is not an all-time perceived-C4 claim.

        Raw WAVs use one common headroom scalar. Matched WAVs reproduce the house
        AuditionLevel loudest200ms .03 target and .99 peak guard for the complete note.
        Measured decoded envelopes, pitch windows, DC and source timing are recorded
        in assets/manifest.json and diagnostics.json. Numbers do not establish listening approval.

        Every single-note score has type OHM_REPLY_RING_STUDY/schema1. It retains
        all parameters, gain, renderer/source fingerprints and raw/matched WAV hashes.
        It is not a PadRecipe and cannot be imported as a phone synth preset.
        Generate: ./gradlew :synth:generateOhmReplyRingAudition
        Output override: -PohmReplyRingOutput=/absolute/path/to/output
        Replay through the study generator with:
          --replay assets/recipes/middle.json /path/out.wav matched
        Use raw instead of matched to reproduce the common-gain raw export.
        Replay requires the fingerprinted source revision and verifies output bytes
        before writing. source/ contains the original renderer, shared primitives,
        generator and page sources. The prototype's preceding source cannot silently
        replace the accepted model-2 renderer, whose source and reference WAVs are pinned.

        The ZIP contains this page, all fourteen WAVs, scores, diagnostics and source.
        Serve the extracted folder over HTTP for playback. SHA256SUMS.txt covers
        all bundled files except itself; the ZIP's separate .sha256 covers the archive.
    """.trimIndent() + "\n"
}
