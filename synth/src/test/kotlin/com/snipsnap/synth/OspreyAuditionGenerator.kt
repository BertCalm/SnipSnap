package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The first OSPREY listening proof. Every WAV comes from the Kotlin engine; this generator
 * writes assets and evidence only. The separately maintained page consumes manifest.json.
 * Region names describe invented synthesis profiles, not sampled instruments or wildlife.
 */
object OspreyAuditionGenerator {
    private const val SAMPLE_RATE = 44_100
    private const val CHANNELS = 2
    private const val LISTENING_TARGET = .12f
    private const val LISTENING_CEILING = .90f
    private const val REQUEST_SEED = 731
    private const val DURATION_SECONDS = 8f
    private const val MODEL_VERSION = 1

    private val shoreNames = listOf("Atlantic", "Nordic", "Mediterranean", "Pacific", "Southern")
    private val shoreDescriptions = listOf(
        "Rolling swells with guitarra-, kora- and pipe-inspired modal colors; petrel, humpback and foghorn forms.",
        "Slow waves with fiddle-, zither- and vocal-inspired modal colors; tern, beluga and ice-crack forms.",
        "Shorter choppy waves with bouzouki-, oud- and ney-inspired modal colors; gull, dolphin and cicada forms.",
        "Broad swells with koto-, conch- and bowl-inspired modal colors; albatross, whale and reef forms.",
        "Rolling waves with cavaquinho-, valiha- and gamelan-inspired modal colors; tropicbird, whale and rain forms.",
    )
    private val seaStates = listOf("Calm" to .10f, "Rough" to .50f, "Storm" to .90f)
    private val baseMacros = linkedMapOf(
        "SHORE" to 0f, "SEA" to .50f, "MOTION" to .45f,
        "DEPTH" to .55f, "SPACE" to 0f, "HOLD" to .45f,
    )
    private data class OspreyAuditionSection(val id: String, val title: String, val description: String)
    private data class OspreyAuditionRequest(
        val id: String,
        val title: String,
        val description: String,
        val section: String,
        val macros: Map<String, Float> = baseMacros,
        val midi: Int = 60,
        val velocity: Float = 1f,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one OSPREY audition output directory." }
        val root = File(args.firstOrNull() ?: "../testkit/osprey-audition")
        root.mkdirs()
        val repository = repositoryRoot()
        val sourceCommit = git(repository, "rev-parse", "HEAD")
        val workingTreeDirty = git(repository, "status", "--porcelain")?.isNotBlank() ?: true
        val sourceFiles = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Osprey.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OspreyV1.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OspreyShore.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OspreyPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OspreyPatch.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OspreyAuditionGenerator.kt",
        ).mapNotNull { path -> File(repository, path).takeIf { it.isFile }?.let { path to sha256(it) } }.toMap()
        val sections = listOf(
            OspreyAuditionSection("shores", "Five shores · three seas", "The same C4 gesture and controls at five shore endpoints. Each shore has calm, rough and storm versions; SPACE stays at zero."),
            OspreyAuditionSection("morphs", "Between shores", "Four adjacent-shore midpoints at rough sea. Compare each with its two endpoint cards above; all source families change together."),
            OspreyAuditionSection("anatomy", "Inside one sea", "Atlantic rough sea split into a full mix and four diagnostic taps. Every tap uses the full mix's listening gain, preserving its actual balance."),
            OspreyAuditionSection("controls", "Move one control", "MOTION, DEPTH and SPACE at 0, 0.5 and 1. The other settings, note, velocity and seed remain fixed."),
            OspreyAuditionSection("pitch", "One shore · three notes", "Atlantic rough sea at C3, C4 and C5. Listen for a useful pitched center as the same ecology changes register."),
            OspreyAuditionSection("depth", "With and without creatures", "Nordic and Southern rough seas at DEPTH 0 and 0.8. DEPTH also darkens the primary radiation and lengthens the sympathetic decay; ocean excitation stays unchanged."),
            OspreyAuditionSection("hold", "Finite HOLD", "HOLD lengthens the finite energy gesture. These are one-shots, including HOLD 1; none is advertised as a seamless loop."),
            OspreyAuditionSection("velocity", "Gesture energy", "The same Atlantic gesture at soft, medium and strong velocity. Use Raw to hear the natural level differences; matched playback compares articulation."),
        )
        val clips = mutableListOf<Map<String, Any?>>()
        val ids = mutableSetOf<String>()
        var totalRenderNanos = 0L
        var renderCount = 0
        val started = System.nanoTime()

        fun render(request: OspreyAuditionRequest): Osprey.Probe {
            val before = System.nanoTime()
            val probe = Osprey.probe(
                OspreyVoice.SWELL, request.macros, midi = request.midi,
                velocity = request.velocity, seed = REQUEST_SEED, durationSeconds = DURATION_SECONDS,
                modelVersion = MODEL_VERSION,
            )
            totalRenderNanos += System.nanoTime() - before
            renderCount++
            require(probe.metrics.nonFiniteRecoveries == 0) { "${request.id}: numerical recovery occurred." }
            return probe
        }

        fun writeClip(
            request: OspreyAuditionRequest,
            probe: Osprey.Probe,
            component: String = "full",
            referenceId: String? = null,
        ) {
            require(ids.add(request.id)) { "Duplicate clip id ${request.id}." }
            val samples = when (component) {
                "full" -> probe.samples
                "ocean" -> probe.ocean
                "primary" -> probe.primary
                "sympathy" -> probe.sympathy
                "creatures" -> probe.creatures
                else -> error("Unknown OSPREY component $component.")
            }
            require(samples.isNotEmpty() && samples.size % CHANNELS == 0 && samples.all { it.isFinite() }) {
                "${request.id}: invalid stereo samples."
            }
            val raw = Snip(samples, channels = CHANNELS, sampleRate = SAMPLE_RATE)
            val rawPeak = peak(samples)
            require(rawPeak <= 1f) { "${request.id}: raw PCM would clip at peak $rawPeak." }
            val referencePeak = peak(probe.samples)
            val referenceRms = listeningRms(probe.samples)
            require(referenceRms > 1e-8f) { "${request.id}: complete reference is silent." }
            val gain = minOf(LISTENING_TARGET / referenceRms, LISTENING_CEILING / referencePeak.coerceAtLeast(1e-9f))
            val matchedSamples = FloatArray(samples.size) { samples[it] * gain }
            val matchedPeak = peak(matchedSamples)
            // A diagnostic tap may cancel with another branch in the complete mix. Refuse to
            // silently cap it or normalize it independently: its shared gain must remain honest.
            require(matchedPeak <= 1f) { "${request.id}: reference-gained anatomy tap exceeds PCM range ($matchedPeak)." }
            if (component == "full") require(matchedPeak <= LISTENING_CEILING + 1e-6f)
            val matched = Snip(matchedSamples, channels = CHANNELS, sampleRate = SAMPLE_RATE)
            val filePath = "audio/${request.id}.wav"
            val rawPath = "audio/${request.id}_raw.wav"
            val patchPath = "patches/${request.id}.json"
            val matchedFile = WavWriter.write(File(root, filePath), matched, WavWriter.BitDepth.PCM_16)
            val rawFile = WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_16)
            val patch = OspreyPatch(
                request.title, OspreyVoice.SWELL, request.macros, request.midi, request.velocity,
                REQUEST_SEED, DURATION_SECONDS, MODEL_VERSION,
            )
            val patchFile = File(root, patchPath)
            patchFile.parentFile.mkdirs()
            patchFile.writeText(patch.toJsonText() + "\n")
            val events = probe.callEvents.map { event -> linkedMapOf(
                "timeSeconds" to event.timeSeconds, "kind" to event.kind.name,
                "shore" to event.shore, "activity" to event.activity, "strength" to event.strength,
            ) }
            clips += linkedMapOf(
                "id" to request.id, "title" to request.title, "description" to request.description,
                "section" to request.section, "voice" to "SWELL", "component" to component,
                "referenceId" to referenceId, "shore" to request.macros.getValue("SHORE") * 4f,
                "shoreName" to shoreName(request.macros.getValue("SHORE")),
                "sea" to request.macros.getValue("SEA"), "seaName" to seaName(request.macros.getValue("SEA")),
                "midi" to request.midi,
                "velocity" to request.velocity, "macros" to request.macros,
                "file" to filePath, "rawFile" to rawPath, "patchFile" to patchPath,
                "duration" to samples.size.toDouble() / CHANNELS / SAMPLE_RATE,
                "sampleRate" to SAMPLE_RATE, "channels" to CHANNELS,
                "peak" to matchedPeak, "rms" to rms(matchedSamples),
                "listeningRms" to listeningRms(matchedSamples), "gain" to gain,
                "rawPeak" to rawPeak, "rawRms" to rms(samples), "rawListeningRms" to listeningRms(samples),
                "waveform" to waveform(matchedSamples).map { it / matchedPeak.coerceAtLeast(1e-9f) },
                "waveformKind" to "relative-channel-peak", "waveformPeakReference" to matchedPeak,
                "loop" to false, "renderModel" to MODEL_VERSION, "seed" to REQUEST_SEED,
                "sourceCommit" to sourceCommit, "workingTreeDirty" to workingTreeDirty,
                "matchedSha256" to sha256(matchedFile), "rawSha256" to sha256(rawFile),
                "patchSha256" to sha256(patchFile), "events" to events,
                "diagnostics" to linkedMapOf(
                    "rawPeak" to probe.metrics.rawPeak,
                    "finalModalEnergy" to probe.metrics.finalModalEnergy,
                    "peakModalEnergy" to probe.metrics.peakModalEnergy,
                    "nonFiniteRecoveries" to probe.metrics.nonFiniteRecoveries,
                    "excitationStopSeconds" to probe.metrics.excitationStopSeconds,
                ),
            )
            println("${clips.size.toString().padStart(2)} · ${request.id} · ${fmt(matchedPeak)} peak · ${fmt(listeningRms(matchedSamples))} listening RMS")
        }

        fun add(request: OspreyAuditionRequest) { writeClip(request, render(request)) }

        for ((shoreIndex, shoreName) in shoreNames.withIndex()) {
            for ((seaName, sea) in seaStates) {
                add(OspreyAuditionRequest(
                    "shore_${shoreName.lowercase(Locale.ROOT)}_${seaName.lowercase(Locale.ROOT)}",
                    "$shoreName · $seaName",
                    "${shoreDescriptions[shoreIndex]} Compare this $seaName sea with the other two settings in the same shore. C4, dry source, identical requested seed.",
                    "shores", baseMacros + mapOf("SHORE" to shoreIndex / 4f, "SEA" to sea),
                ))
            }
        }
        for (index in 0..3) {
            add(OspreyAuditionRequest(
                "morph_${index}_${index + 1}", "${shoreNames[index]} ↔ ${shoreNames[index + 1]}",
                "Halfway between the two adjacent shore profiles at C4 and rough sea. Modal ratios, creatures and fluid character interpolate together; this is one rendered model, not a crossfade of exported endpoint WAVs.",
                "morphs", baseMacros + ("SHORE" to (index + .5f) / 4f),
            ))
        }

        val anatomy = OspreyAuditionRequest(
            "anatomy_full", "Complete dry sea",
            "Atlantic rough sea at C4: primary modes, sympathetic modes and creatures together, with SPACE off. The following taps keep this reference's gain.", "anatomy",
        )
        val anatomyProbe = render(anatomy)
        writeClip(anatomy, anatomyProbe)
        val taps = listOf(
            Triple("ocean", "Ocean excitation", "Diagnostic monitor of the common ocean excitation that drives the resonators. It is not an additional audio layer in the complete mix."),
            Triple("primary", "Primary resonators", "The three shore-inspired modal families receiving the same ocean energy, without the sympathetic or creature output."),
            Triple("sympathy", "Sympathetic resonators", "The additional sympathetic modal response alone. It retains the complete mix's gain, so a quiet response stays quiet."),
            Triple("creatures", "Creature forms", "Synthesized bird, deep and ambient forms alone. No recordings are used; their strength retains the complete mix's gain."),
        )
        for ((component, title, description) in taps) {
            writeClip(anatomy.copy(id = "anatomy_$component", title = title, description = description),
                anatomyProbe, component = component, referenceId = anatomy.id)
        }

        val controlDescriptions = mapOf(
            "MOTION" to "Compare the speed and density of wave movement and creature activity.",
            "DEPTH" to "Compare darker upper-mode radiation, longer sympathetic decay and linked creature presence. DEPTH zero mutes the creature branch; it does not change the ocean excitation.",
            "SPACE" to "Compare the close source with its harbor distance and diffusion. SPACE zero is the dry reference.",
        )
        for (macro in listOf("MOTION", "DEPTH", "SPACE")) {
            for (value in listOf(0f, .5f, 1f)) {
                add(OspreyAuditionRequest(
                    "control_${macro.lowercase(Locale.ROOT)}_${tag(value)}", "$macro · ${fmt(value)}",
                    "${controlDescriptions.getValue(macro)} All other controls, C4, velocity and requested seed stay fixed. Matched level makes timbral comparisons easier; Raw retains source amplitude.",
                    "controls", baseMacros + (macro to value),
                ))
            }
        }
        for (midi in listOf(48, 60, 72)) {
            add(OspreyAuditionRequest(
                "pitch_$midi", "Atlantic · C${midi / 12 - 1}",
                "The same Atlantic rough-sea settings at MIDI $midi. Listen to the note's modal center and to the creature balance across the register.",
                "pitch", midi = midi,
            ))
        }
        for (index in listOf(1, 4)) {
            for (depth in listOf(0f, .8f)) {
                add(OspreyAuditionRequest(
                    "depth_${shoreNames[index].lowercase(Locale.ROOT)}_${tag(depth)}",
                    "${shoreNames[index]} · ${if (depth == 0f) "creatures off" else "creatures present"}",
                    "DEPTH ${fmt(depth)} at C4 and rough sea. ${if (depth == 0f) "The creature output is muted." else "The linked depth control admits the shore's creature forms."} Depth also changes modal radiation and sympathetic decay, so this is a musical control comparison rather than an isolated creature bypass.",
                    "depth", baseMacros + mapOf("SHORE" to index / 4f, "DEPTH" to depth),
                ))
            }
        }
        for (hold in listOf(0f, .5f, 1f)) {
            add(OspreyAuditionRequest(
                "hold_${tag(hold)}", "Finite HOLD · ${fmt(hold)}",
                "HOLD ${fmt(hold)} extends the finite ocean-energy gesture inside this eight-second render. Listen for the energy stopping and the resonators ringing down; even the top setting is not a periodic loop.",
                "hold", baseMacros + ("HOLD" to hold),
            ))
        }
        for ((name, velocity) in listOf("Soft" to .25f, "Medium" to .6f, "Strong" to 1f)) {
            add(OspreyAuditionRequest(
                "velocity_${tag(velocity)}", "$name gesture · ${fmt(velocity)}",
                "Velocity ${fmt(velocity)} with the Atlantic rough-sea recipe unchanged. Matched playback compares tone and articulation; Raw preserves the natural amplitude difference.",
                "velocity", velocity = velocity,
            ))
        }

        require(clips.size == 46) { "The initial listening matrix should contain 46 clips, found ${clips.size}." }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "engine" to "OSPREY", "title" to "OSPREY · Five Shores",
            "description" to "A first SnipSnap proof: one synthesized sea drives pitched shore resonators and creature forms. Listening acceptance remains open.",
            "renderModel" to MODEL_VERSION, "sourceCommit" to sourceCommit,
            "workingTreeDirty" to workingTreeDirty, "sourceFilesSha256" to sourceFiles,
            "sampleRate" to SAMPLE_RATE, "channels" to CHANNELS, "bitDepth" to 16,
            "clipCount" to clips.size, "audioFileCount" to clips.size * 2,
            "listeningTarget" to LISTENING_TARGET, "listeningPeakCeiling" to LISTENING_CEILING,
            "masterVolume" to .85f, "playbackInitiallyEnabled" to false, "audioStartsOff" to true,
            "loudness" to "Full mixes target loudest 200 ms stereo RMS 0.12 with peak at most 0.90. Anatomy taps inherit the complete dry mix's gain; Raw files retain engine amplitude. RMS fields measure all interleaved channels without summing them to mono.",
            "seedPolicy" to "Every comparison requests seed 731. Each independent render begins from fresh deterministic state; pads do not share a live sea timeline.",
            "shoreRange" to listOf(0, 4), "macroShoreRange" to listOf(0, 1),
            "shoreNames" to shoreNames, "noteRange" to "C3–C5 in this listening proof",
            "loopPolicy" to "All clips are finite one-shots. HOLD extends a gesture; a validated periodic HOLD loop is deferred.",
            "anatomyPolicy" to "Primary + sympathy + creatures make the dry source. The ocean tap monitors excitation and is not added directly to the audible mix. Saved anatomy patches reproduce the complete source, not the diagnostic tap.",
            "renderCount" to renderCount, "renderMilliseconds" to totalRenderNanos / 1_000_000.0,
            "elapsedMilliseconds" to (System.nanoTime() - started) / 1_000_000.0,
            "sections" to sections.map { linkedMapOf("id" to it.id, "title" to it.title, "description" to it.description) },
            "clips" to clips,
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        println("OSPREY: ${clips.size} listening cards, ${clips.size * 2} WAVs and ${clips.size} patches written to ${root.canonicalPath}.")
    }

    private fun peak(samples: FloatArray): Float = samples.maxOfOrNull { abs(it) } ?: 0f
    private fun rms(samples: FloatArray): Float = if (samples.isEmpty()) 0f else
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size).toFloat()

    /** Direct stereo-energy RMS of the loudest full 200 ms window, scanned every 50 ms. */
    private fun listeningRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        val window = minOf(samples.size, SAMPLE_RATE / 5 * CHANNELS)
        val hop = SAMPLE_RATE / 20 * CHANNELS
        var sum = 0.0
        for (index in 0 until window) sum += samples[index].toDouble() * samples[index]
        var best = sum / window
        var start = 0
        while (start + window + hop <= samples.size) {
            for (index in start until start + hop) sum -= samples[index].toDouble() * samples[index]
            for (index in start + window until start + window + hop) sum += samples[index].toDouble() * samples[index]
            start += hop
            best = maxOf(best, sum / window)
        }
        return sqrt(best.coerceAtLeast(0.0)).toFloat()
    }

    private fun waveform(samples: FloatArray): List<Float> = (0 until 64).map { bin ->
        val start = samples.size.toLong() * bin / 64
        val end = samples.size.toLong() * (bin + 1) / 64
        var peak = 0f
        for (index in start.toInt() until end.toInt()) peak = maxOf(peak, abs(samples[index]))
        peak
    }
    private fun shoreName(normalized: Float): String {
        val position = (normalized * 4f).coerceIn(0f, 4f)
        val lower = position.toInt()
        return if (position - lower < 1e-5f || lower == 4) shoreNames[lower]
        else "${shoreNames[lower]} ↔ ${shoreNames[lower + 1]}"
    }
    private fun seaName(value: Float): String = when {
        value < .3f -> "Calm"
        value < .7f -> "Rough"
        else -> "Storm"
    }

    private fun repositoryRoot(): File {
        var candidate = File(".").canonicalFile
        while (!File(candidate, ".git").exists()) {
            candidate = candidate.parentFile ?: error("Run the audition generator inside the SnipSnap checkout.")
        }
        return candidate
    }
    private fun git(repository: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).directory(repository).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun tag(value: Float): String = (value * 100).toInt().toString().padStart(3, '0')
    private fun fmt(value: Float): String = String.format(Locale.ROOT, "%.2f", value)
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported OSPREY manifest value ${value.javaClass.name}.")
    }
}
