package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Actual JVM-engine listening evidence, with untouched native PCM and the repository's
 * shared audition level. The compact library is bounded for browser use;
 * --full adds the complete pitch/velocity and interaction matrix.
 * All presets and sonic conclusions remain provisional until the owner's listening.
 */
object MurmurAuditionGenerator {
    private val TIMBRE = listOf("MOUTH", "EDGE", "BREATH", "MOTION", "BODY")
    private val STEPS = listOf(0f, .25f, .5f, .75f, 1f)
    private val NOTES = listOf(36, 60, 96)
    private val VELOCITIES = listOf(.25f, .6f, 1f)
    private const val LISTENING_TARGET = .03f
    private const val CURRENT_MODEL = 2
    private const val LEGACY_MODEL = 1
    private const val LEGACY_REFERENCE_REVISION = "46e1d838ea67ac9398e504a3409f10daf9e1c53e"

    private data class Case(
        val id: String,
        val group: String,
        val title: String,
        val description: String,
        val patch: MurmurPatch,
        val referenceClipId: String? = null,
    )

    private val groups = linkedMapOf(
        "defaults" to ("Compare six voices" to "Model 2, same C4 root, velocity 1 and matched listening level. Each voice retains its own defaults and full articulation. Listen for source color as well as attack, sustain and release; duration alone does not establish identity."),
        "voice-before-after" to ("Before / after · full gestures" to "Each voice alternates model 1 (previously published) and model 2 (revised). Same C4, velocity 1 and default macro settings; each model keeps its full gesture. Model 1 is rendered again with its saved algorithm, with the original source revision linked below."),
        "held-before-after" to ("Before / after · settled sustain" to "The same six model pairs with HOLD 1. The original attack is absent: compare sustained source color and mouth shaping beyond note length or onset speed. Repeat the settled buffer and check each clip's measured duration."),
        "first-listen" to ("Preset proposals" to "Twelve original dry presets using model 2. Settings and names remain provisional; the planned 36-preset roster awaits your sonic approval."),
        "body-sweeps" to ("BODY · thin to full" to "All six voices at C4, velocity 1. Compare 0, .25, .5, .75 and 1 at matched level: listen for low/mid weight, direct support and upper damping. All other controls retain each voice's defaults."),
        "motion-sweeps" to ("MOTION · still to moving" to "All six voices at C4, velocity 1. Compare 0, .25, .5, .75 and 1. Listen through the whole note for a wider, developing vowel contour; all other controls retain each voice's defaults."),
        "pitch-velocity" to ("Pitch & velocity" to "C2, C4 and C7 span the declared MIDI 36–96 range. Compare native level to hear excitation energy; matched level makes timbre easier to compare."),
        "macro-sweeps" to ("MOUTH, EDGE & BREATH sweeps" to "KEY at C4, velocity 1. Change one macro through 0, .25, .5, .75 and 1; all companions retain KEY defaults. BODY and MOTION have their own all-voice comparison groups."),
        "mouth-edge" to ("MOUTH × EDGE" to "LEAD: compare mouth-shaped spectral emphasis against increasing phase-modulation bite."),
        "mouth-breath" to ("MOUTH × BREATH" to "CHOIR: the unvoiced source should share the mouth shape while a voiced root remains."),
        "motion-breath" to ("MOTION × BREATH" to "CHOIR: compare a static target with moving formants and increasingly airy excitation."),
        "body-mouth" to ("BODY × MOUTH" to "BASS: compare direct tonal support and lower-formant fullness against mouth shape."),
        "extremes" to ("All controls high" to "All five timbral macros at 1, finite HOLD 0, at C4. Measure and listen for retained root, bounded output and usable articulation."),
        "held" to ("Settled HOLD loops" to "Each voice at its defaults and with all five timbral controls high. These are sustain-only exports: the original attack is omitted. Repeat the buffer to hear several wraps."),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1) { "Supply one output directory and optional --full" }
        require(args.filter { it.startsWith("--") }.all { it == "--full" }) { "Unknown MURMUR audition option" }
        val full = "--full" in args
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/murmur-audition")
        val compactCount = cases(false).size
        val fullCount = cases(true).size
        val cases = cases(full)
        check(cases.map { it.id }.distinct().size == cases.size) { "Duplicate audition IDs" }
        root.mkdirs()
        // Publish a page only after the complete render succeeds.
        listOf("manifest.json", "index.html").forEach { name ->
            val old = File(root, name)
            require(!old.exists() || old.delete()) { "Cannot replace $old" }
        }
        val rows = ArrayList<Map<String, Any?>>(cases.size)
        val assetPaths = mutableSetOf<String>()
        val voiceReferences = mutableMapOf<MurmurVoice, FloatArray>()
        val started = System.nanoTime()
        var renderMilliseconds = 0.0

        for ((index, case) in cases.withIndex()) {
            val p = case.patch
            val settings = Murmur.defaults(p.voice) + p.macros
            val loop = Murmur.isLoop(settings.getValue("HOLD"))
            val before = System.nanoTime()
            val probe = Murmur.probe(p.voice, settings, p.midi, p.velocity, p.config)
            val renderMs = (System.nanoTime() - before) / 1_000_000.0
            renderMilliseconds += renderMs
            val raw = Snip(probe.samples, channels = 1, sampleRate = Dsp.RATE)
            require(raw.samples.isNotEmpty() && raw.samples.all { it.isFinite() } && raw.peak() <= 1f) {
                "${case.id} cannot be exported as untouched raw PCM: empty/non-finite samples or peak ${raw.peak()} above full scale"
            }
            val matched = AuditionLevel.level(raw)
            require(matched.samples.all { it.isFinite() } && matched.peak() <= .991f)
            val rawPath = "raw/${case.id}.wav"
            val matchedPath = "matched/${case.id}.wav"
            WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
            WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            assetPaths += listOf(rawPath, matchedPath)
            val pitch = pitch(raw, midiHz(p.midi))
            val classification = Classifier.classify(raw)
            // The full classifier calls long material LOOP. Also expose the first 1.25 s
            // so that duration alone does not hide a drum-like onset in the evidence.
            val head = Snip(raw.samples.copyOf(minOf(raw.samples.size, (1.25 * Dsp.RATE).toInt())), 1, Dsp.RATE)
            val headClass = Classifier.classify(head)
            val feature = headClass.features
            val seam = if (loop) probe.seamError else null
            if (loop) require(probe.previousCycle.isNotEmpty() && seam != null && seam.isFinite()) {
                "${case.id} has no independent previous cycle for its seam measurement"
            }
            if (case.group == "defaults") voiceReferences[p.voice] = matched.samples.copyOf()
            val macroDistance = if (case.group in setOf("macro-sweeps", "body-sweeps", "motion-sweeps"))
                voiceReferences[p.voice]?.let { normalizedDistance(it, matched.samples) } else null
            val recipePath = "recipes/${case.id}.json"
            File(root, recipePath).apply { parentFile.mkdirs(); writeText(Json.write(p.toJsonValue()) + "\n") }
            assetPaths += recipePath
            rows += linkedMapOf(
                "id" to case.id, "group" to case.group, "section" to groups.getValue(case.group).first,
                "title" to case.title, "label" to case.title, "description" to case.description,
                "voice" to p.voice.name, "midi" to p.midi, "velocity" to p.velocity,
                "model" to p.config.model,
                "modelLabel" to when { p.config.model == LEGACY_MODEL -> "Before · model 1"; case.referenceClipId != null -> "After · model 2"; else -> "Current · model 2" },
                "listeningCue" to if (p.config.model == LEGACY_MODEL) "Previous shared-formant voice; retained for direct comparison." else roleDescription(p.voice),
                "referenceClipId" to case.referenceClipId,
                "referenceSourceRevision" to LEGACY_REFERENCE_REVISION.takeIf { case.referenceClipId != null },
                "macros" to settings, "config" to p.toJsonValue().obj().getValue("config"),
                "loop" to loop, "attackIncluded" to !loop, "requestedHz" to midiHz(p.midi),
                "renderedRootHz" to probe.rootHz, "durationSeconds" to raw.durationSeconds,
                "frames" to raw.frameCount, "sampleRate" to Dsp.RATE, "channels" to 1,
                "rawPath" to rawPath, "matchedPath" to matchedPath, "recipePath" to recipePath,
                "rawExportGain" to 1f,
                "matchedGain" to (Loudness.of(matched) / Loudness.of(raw).coerceAtLeast(1e-9f)),
                "rawMetrics" to audioMetrics(raw, loop), "matchedMetrics" to audioMetrics(matched, loop),
                "pitch" to pitch, "classifier" to classification.drumClass.name,
                "classifierConfidence" to classification.confidence,
                "headClassifier" to headClass.drumClass.name, "headClassifierConfidence" to headClass.confidence,
                "headFeatures" to linkedMapOf(
                    "centroidHz" to feature.centroidHz, "rolloffHz" to feature.rolloffHz,
                    "flatness" to feature.flatness, "lowRatio" to feature.lowRatio,
                    "midRatio" to feature.midRatio, "highRatio" to feature.highRatio,
                    "decayMs" to feature.decayMs, "attackBursts" to feature.attackBursts,
                ),
                "bodySpectrum" to bodySpectrum(raw), "seamError" to seam,
                "seamMetric" to if (loop) "Keys.seamError on two independently simulated adjacent settled cycles" else null,
                "normalizedDistanceFromKeyDefault" to macroDistance.takeIf { p.voice == MurmurVoice.KEY },
                "normalizedDistanceFromVoiceDefault" to macroDistance,
                "renderMilliseconds" to renderMs,
                "renderMillisecondsPerAudioSecond" to renderMs / raw.durationSeconds,
                "rawSha256" to sha256(File(root, rawPath)), "matchedSha256" to sha256(File(root, matchedPath)),
            )
            if ((index + 1) % 12 == 0 || index == cases.lastIndex) {
                println("MURMUR audition ${index + 1}/${cases.size} · ${(index + 1) * 2} WAVs")
            }
        }

        // Remove only obsolete generated assets in owned folders, never arbitrary user files.
        for (directory in listOf("raw", "matched", "recipes")) File(root, directory).walkTopDown()
            .filter { it.isFile && it.extension in listOf("wav", "json") }
            .filter { it.relativeTo(root).invariantSeparatorsPath !in assetPaths }
            .forEach { require(it.delete()) { "Cannot prune old audition asset $it" } }
        val manifest = linkedMapOf(
            "engine" to "MURMUR", "schemaVersion" to 1, "title" to "Murmur · SnipSnap audition",
            "sourceRevision" to git("rev-parse", "HEAD"), "sourceTreeDirty" to (git("status", "--porcelain")?.isNotBlank()),
            "source" to "Real offline SnipSnap JVM engine renders; no browser synthesis or sample assets",
            "renderModel" to CURRENT_MODEL, "comparisonModels" to listOf(LEGACY_MODEL, CURRENT_MODEL),
            "legacyReferenceRevision" to LEGACY_REFERENCE_REVISION,
            "comparisonProvenance" to "Both models are rendered by the source revision above. Model 1 retains the previously published algorithm; its original source revision is the legacy reference. Raw/matched hashes and complete model-bearing recipes identify every clip.",
            "full" to full, "clipCount" to rows.size, "wavCount" to rows.size * 2,
            "compactClipCount" to compactCount, "fullClipCount" to fullCount,
            "sampleRate" to Dsp.RATE, "bitDepth" to 24, "channels" to 1, "internalOversampling" to 4,
            "listeningTarget" to LISTENING_TARGET, "loudnessMetric" to "Loudness.of: loudest 200 ms RMS window after the shared gentle low cut",
            "sonicAcceptance" to "Pending owner listening; twelve provisional presets, expansion to 36 deferred until approval",
            "supportedMidiRange" to listOf(Murmur.MIN_MIDI, Murmur.MAX_MIDI),
            "noteMatrix" to NOTES, "velocityMatrix" to VELOCITIES,
            "pitchVelocityCartesian" to full, "interactionSteps" to if (full) listOf(0f, .5f, 1f) else listOf(0f, 1f),
            "macroSweepVoice" to "KEY", "macroSweepSteps" to STEPS,
            "bodyMotionSweepVoices" to MurmurVoice.entries.map { it.name },
            "bodyMotionSweepSteps" to STEPS,
            "renderMilliseconds" to renderMilliseconds,
            "generationMilliseconds" to (System.nanoTime() - started) / 1_000_000.0,
            "metricNotes" to METRIC_NOTES,
            "groups" to groups.map { (id, detail) -> linkedMapOf("id" to id, "title" to detail.first, "description" to detail.second) },
            "clips" to rows,
        )
        val manifestText = Json.write(json(manifest)) + "\n"
        File(root, "manifest.json").writeText(manifestText)
        val page = checkNotNull(javaClass.getResourceAsStream("/audition/murmur-audition.html")) {
            "Missing MURMUR audition page resource"
        }.bufferedReader().use { it.readText() }
        File(root, "index.html").writeText(page.replace("__MANIFEST__", manifestText.replace("</", "<\\/")))
        File(root, "README.md").writeText("""
            # Murmur audition

            ${rows.size} actual JVM-engine cases; ${rows.size * 2} mono 24-bit WAVs at 44.1 kHz.
            Generated with `./gradlew :synth:generateMurmurAudition${if (full) " -PmurmurFull" else ""}`.
            The default compact set has $compactCount cases; `--full` adds the complete 3 × 3 pitch/velocity
            matrix and 3 × 3 interaction grids ($fullCount cases). No rack effects are applied.
            BODY and MOTION each have five-point comparisons for all six voices; MOUTH,
            EDGE and BREATH retain five-point KEY comparisons.

            Open index.html, or serve the folder over HTTP for sample-accurate Web Audio playback.
            Start with Compare six voices: model 2 at C4 and velocity 1. Then compare interleaved
            model 1/model 2 full gestures and settled HOLD pairs. The 24 before/after cases use the
            same root, velocity and macro defaults; compare sustained color as well as articulation.
            The model 1 reference was published from $LEGACY_REFERENCE_REVISION; all files here
            are rendered again from the source revision recorded in manifest.json. Their hashes,
            model-bearing recipes and original reference clip IDs preserve the comparison trail.
            The twelve preset proposals remain available. Use matched level to compare
            timbre at the repository's shared .03 target, and raw level to compare excitation energy.
            Raw WAVs retain the engine's pre-leveling gain; the generator refuses out-of-range PCM.
            HOLD exports contain settled sustaining material only. Repeat buffers without retriggering
            the original attack. Notes and decisions are stored in this browser; export them to retain
            your review. Sonic identity, names, and the planned expansion to 36 presets await approval.

            All settings and the complete serialized FM/formant recipe accompany every clip.
            manifest.json records measured audio, pitch, classifier, spectral and seam evidence.
            $METRIC_NOTES
        """.trimIndent() + "\n")
        println("MURMUR audition complete: ${root.canonicalPath}/index.html")
    }

    private fun cases(full: Boolean): List<Case> = buildList {
        for (p in MurmurPresets.all()) add(Case("preset_${slug(p.name)}", "first-listen", p.name,
            "Original dry ${p.voice.name.lowercase()} proposal at ${note(p.midi)}. Settings and name await listening review." +
                if (Murmur.isLoop(p.macros.getValue("HOLD"))) " Settled sustain only; repeat to inspect the wrap." else "", p))
        for (voice in MurmurVoice.entries) add(case("default_${voice.name.lowercase()}", "defaults", voice,
            "${voice.name} · C4", "Same C4 root and velocity 1; each revised voice keeps its own full dry gesture."))
        for (held in listOf(false, true)) for (voice in MurmurVoice.entries) for (model in listOf(LEGACY_MODEL, CURRENT_MODEL)) {
            val group = if (held) "held-before-after" else "voice-before-after"
            val reference = if (held) "${voice.name.lowercase()}_held" else "default_${voice.name.lowercase()}"
            val title = "${voice.name} · ${if (model == LEGACY_MODEL) "before" else "after"} · model $model"
            val settings = Murmur.defaults(voice) + ("HOLD" to if (held) 1f else 0f)
            add(Case("identity_${voice.name.lowercase()}_${if (held) "held" else "finite"}_model$model", group, title,
                "Same C4, velocity 1 and voice defaults. " +
                    if (held) "Settled sustain only: compare color without the original attack."
                    else "Full gesture: compare source color, articulation and release.",
                MurmurPatch(title, voice, settings, midi = 60, velocity = 1f, config = Murmur.configurationFor(voice, model)),
                reference))
        }
        for (voice in MurmurVoice.entries) for (midi in NOTES) for (velocity in VELOCITIES) {
            if (midi == 60 && velocity == 1f) continue // The six default cards already provide these cells.
            if (!full && midi != 60 && velocity != 1f) continue
            add(case("${voice.name.lowercase()}_m${midi}_v${tag(velocity)}", "pitch-velocity", voice,
                "${voice.name} · ${note(midi)} · velocity $velocity",
                "Voice defaults at MIDI $midi, ${note(midi)}, velocity $velocity." +
                    if (midi == 96) " At C7, sparse harmonic sampling may weaken apparent vowel identity." else "",
                midi = midi, velocity = velocity))
        }
        for (macro in TIMBRE) {
            val voices = if (macro in listOf("BODY", "MOTION")) MurmurVoice.entries else listOf(MurmurVoice.KEY)
            val group = when (macro) { "BODY" -> "body-sweeps"; "MOTION" -> "motion-sweeps"; else -> "macro-sweeps" }
            for (voice in voices) for (step in STEPS) add(case("${voice.name.lowercase()}_${macro.lowercase()}_${tag(step)}", group,
                voice, "${voice.name} · $macro $step", sweepDescription(macro), mapOf(macro to step)))
        }
        val interactions = listOf(
            Triple("mouth-edge", MurmurVoice.LEAD, "MOUTH" to "EDGE"),
            Triple("mouth-breath", MurmurVoice.CHOIR, "MOUTH" to "BREATH"),
            Triple("motion-breath", MurmurVoice.CHOIR, "MOTION" to "BREATH"),
            Triple("body-mouth", MurmurVoice.BASS, "BODY" to "MOUTH"),
        )
        val grid = if (full) listOf(0f, .5f, 1f) else listOf(0f, 1f)
        for ((group, voice, pair) in interactions) for (a in grid) for (b in grid) {
            val (ma, mb) = pair
            add(case("${group}_${tag(a)}_${tag(b)}", group, voice, "$ma $a · $mb $b",
                "${voice.name} at C4; only $ma and $mb change, all companion controls use voice defaults.", mapOf(ma to a, mb to b)))
        }
        for (voice in MurmurVoice.entries) {
            add(case("${voice.name.lowercase()}_all_high", "extremes", voice, "${voice.name} · all high",
                "MOUTH, EDGE, BREATH, MOTION and BODY at 1; finite HOLD 0.", TIMBRE.associateWith { 1f }))
            add(case("${voice.name.lowercase()}_held", "held", voice, "${voice.name} · held defaults",
                "Settled default sustain without the initial onset. Repeat to listen through several independent wraps.", mapOf("HOLD" to 1f)))
            add(case("${voice.name.lowercase()}_held_all_high", "held", voice, "${voice.name} · held all high",
                "All five timbral macros and HOLD at 1. Compare the formant/breath texture across wraps.", TIMBRE.associateWith { 1f } + ("HOLD" to 1f)))
        }
    }.sortedBy { groups.keys.indexOf(it.group) }

    private fun case(id: String, group: String, voice: MurmurVoice, title: String, description: String,
                     macros: Map<String, Float> = emptyMap(), midi: Int = 60, velocity: Float = 1f) =
        Case(id, group, title, description, MurmurPatch(title, voice, Murmur.defaults(voice) + macros, midi, velocity))

    private fun roleDescription(voice: MurmurVoice): String = when (voice) {
        MurmurVoice.BASS -> "Listen for rounded, FM-first talking body."
        MurmurVoice.KEY -> "Listen for a struck tine color and third-harmonic carrier."
        MurmurVoice.PLUCK -> "Listen for a dry, short fourth-harmonic pluck."
        MurmurVoice.LEAD -> "Listen for a sustained nasal or reed-like stack."
        MurmurVoice.CHOIR -> "Listen for soft near-unison hum and integrated air."
        MurmurVoice.METAL -> "Listen for persistent high-carrier ringing FM."
    }

    private fun audioMetrics(snip: Snip, loop: Boolean): Map<String, Any?> {
        val x = snip.samples
        val rms = sqrt(x.sumOf { it.toDouble() * it } / x.size)
        return linkedMapOf("peak" to snip.peak(), "rms" to rms,
            "dc" to x.sumOf { it.toDouble() } / x.size, "loudness" to Loudness.of(snip),
            "wrapStep" to if (loop) abs(x.first().toDouble() - x.last()) else null,
            "wrapSlopeChange" to if (loop) abs((x[1] - x[0]).toDouble() - (x.last() - x[x.lastIndex - 1])) else null)
    }

    private fun pitch(snip: Snip, requestedHz: Double): Map<String, Any?> {
        val ordinary = Pitch.detect(snip, .08f, .25f)
        val high = if (requestedHz > 1200) highPitch(snip, requestedHz) else null
        val hz = high?.first ?: ordinary?.hz?.toDouble()
        return linkedMapOf("hz" to hz, "confidence" to (high?.second ?: ordinary?.confidence?.toDouble()),
            "centsFromRequested" to hz?.let { 1200 * ln(it / requestedHz) / ln(2.0) },
            "method" to if (requestedHz > 1200) "Requested-root-range autocorrelation above 1200 Hz" else "Shared Pitch.detect autocorrelation",
            "stockDetectorHz" to ordinary?.hz, "stockDetectorConfidence" to ordinary?.confidence)
    }

    /** The shared detector stops at 1200 Hz. A separately labelled high-register probe
     * searches only requested root ± a fifth and cannot diagnose arbitrary out-of-range pitches. */
    private fun highPitch(snip: Snip, hz: Double): Pair<Double, Double>? {
        val from = (.08 * snip.sampleRate).toInt()
        val to = minOf(snip.frameCount, from + (.25 * snip.sampleRate).toInt())
        val minLag = (snip.sampleRate / (hz * 1.5)).toInt().coerceAtLeast(2)
        val maxLag = (snip.sampleRate / (hz / 1.5)).toInt()
        if (to - from <= maxLag + 2) return null
        val correlation = DoubleArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var dot = 0.0; var aa = 0.0; var bb = 0.0
            for (i in from until to - lag) {
                val a = snip.samples[i].toDouble(); val b = snip.samples[i + lag].toDouble()
                dot += a * b; aa += a * a; bb += b * b
            }
            correlation[lag] = dot / sqrt(aa * bb).coerceAtLeast(1e-20)
        }
        val candidates = (minLag + 1 until maxLag).filter {
            correlation[it] >= correlation[it - 1] && correlation[it] >= correlation[it + 1]
        }
        val lag = candidates.maxByOrNull { correlation[it] } ?: return null
        if (correlation[lag] < .3) return null
        val curve = correlation[lag - 1] - 2 * correlation[lag] + correlation[lag + 1]
        val offset = if (abs(curve) > 1e-12) (.5 * (correlation[lag - 1] - correlation[lag + 1]) / curve).coerceIn(-.5, .5) else 0.0
        return snip.sampleRate / (lag + offset) to correlation[lag].coerceIn(0.0, 1.0)
    }

    private fun bodySpectrum(snip: Snip): Map<String, Any?> {
        val start = (.12 * snip.sampleRate).toInt().coerceAtMost(snip.samples.lastIndex)
        val spectrum = Fft.magnitudeSpectrum(snip.samples.copyOfRange(start, minOf(start + 4096, snip.frameCount)), 4096)
        var total = 0.0; var veryHigh = 0.0; var weighted = 0.0
        for (bin in spectrum.indices) {
            val power = spectrum[bin].toDouble().pow(2)
            val frequency = Fft.binToHz(bin, 4096, snip.sampleRate)
            total += power; weighted += power * frequency
            if (frequency >= 16000) veryHigh += power
        }
        return linkedMapOf("fromSeconds" to .12, "fftFrames" to 4096,
            "centroidHz" to weighted / total.coerceAtLeast(1e-20),
            "powerAbove16kRatio" to veryHigh / total.coerceAtLeast(1e-20))
    }

    private fun normalizedDistance(a: FloatArray, b: FloatArray): Double {
        var difference = 0.0; var energy = 0.0
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0f }.toDouble(); val y = b.getOrElse(i) { 0f }.toDouble()
            difference += (x - y) * (x - y); energy += x * x + y * y
        }
        return sqrt(difference / energy.coerceAtLeast(1e-20))
    }

    private fun sweepDescription(macro: String) = when (macro) {
        "MOUTH" -> "Closed/rounded → open/narrow spectral targets. Listen for mouth shape while C4 remains the requested root."
        "EDGE" -> "Rounded phase modulation → brighter metallic/nasal detail and transient bite."
        "BREATH" -> "Clear voiced source → airy, formant-shaped noise and softer/contact-colored onset."
        "MOTION" -> "Static mouth target → wider, faster spectral contour. Compare timbre over the note body."
        else -> "Thin/intimate → rounded/full carrier and lower-formant support, with stronger upper damping."
    }
    private fun midiHz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun note(midi: Int) = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")[midi % 12] + (midi / 12 - 1)
    private fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    private fun tag(v: Float) = (v * 100).roundToInt().toString().padStart(3, '0')
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun git(vararg arguments: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + arguments).redirectError(File("/dev/null")).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        output.takeIf { process.waitFor() == 0 }
    }.getOrNull()
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) { "Non-finite manifest number" } })
        is String -> JsonValue.Str(value)
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported manifest type ${value::class}")
    }

    private const val METRIC_NOTES = "Raw peak/RMS/DC and Loudness.of use the engine's final-rate buffer before shared loudness targeting or PCM quantization. Raw export gain is exactly 1; out-of-range raw PCM aborts generation. Matched WAVs use the repository's shared .03 audition target and .99 peak guard; actual gain and loudness are recorded. Pitch uses the shared detector over .08–.33 s; C7 exceeds its 1200 Hz bound, so a labelled requested-root-range autocorrelation probe searches only the requested root ± a fifth. That guided probe cannot diagnose arbitrary out-of-range pitches; the unmodified stock detector is also recorded. Whole-file classification often reads LOOP due to duration; headClassifier separately measures the first 1.25 s without that duration shortcut. Body spectral power above 16 kHz describes high-frequency content, not a proven aliasing measurement. Macro distance compares matched buffers against the same voice's default with zero padding and is a waveform diagnostic, not a perceptual verdict. Keys.seamError compares the actual independent preceding and final settled cycle, and is gain invariant; adjacent wrapStep and wrapSlopeChange include ordinary waveform motion and have no invented acceptance threshold. Rendering cost excludes encoding/metrics and varies with JIT/hardware. Measurements support listening; no metric approves sonic identity."
}
