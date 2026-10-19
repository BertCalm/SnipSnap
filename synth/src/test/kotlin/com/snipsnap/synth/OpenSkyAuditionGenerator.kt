package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Reproducible OPENSKY listening spike: real Kotlin-engine PCM, eight recipes,
 * dry/wet pairs, register/musical examples, mono folds, macro sweeps and a HALO
 * sustain period repeated without crossfades. Run :synth:generateOpenSkyAudition.
 *
 * A comparison family shares one static attenuation, applied only if needed for
 * 0.8 sample-peak headroom. Files are never individually loudness-normalized.
 * Shorter dry renders are zero-padded to their wet partner's length. Mono folds
 * average the already-exported stereo pair without a second gain adjustment.
 *
 * The accepted recipes come from SnipSnap's production OPENSKY roster.
 * The held WAV is a settled, loop-only period; it does not implement key attack,
 * release, pitch-zone mapping or a continuously playable polyphonic synth.
 */
object OpenSkyAuditionGenerator {
    private const val REVISION = "R3"
    private const val RATE = 44_100
    private const val HEADROOM = 0.8f
    private const val AUDIO_BUDGET_MIB = 128
    private val MACROS = listOf("RISE", "WIDTH", "GLOW", "AIR")
    private val STEPS = listOf(0f, 0.5f, 1f)

    private data class Preset(
        val id: String,
        val patch: OpenSkyPatch,
        val listen: String,
    ) {
        val name get() = patch.name
        val voice get() = patch.voice
        val macros get() = patch.macros
        val timbre get() = patch.timbre
    }

    private data class Rendered(
        val id: String,
        val title: String,
        val description: String,
        val preset: Preset,
        val variant: String,
        val snip: Snip,
        val midi: Int = 60,
        val macros: Map<String, Float> = preset.macros,
        val extra: Map<String, Any?> = emptyMap(),
    )

    private val PRESETS = OpenSkyPresets.entries().map { entry ->
        Preset(entry.id, entry.patch, entry.description)
    }

    private val CONTRASTS = listOf(
        "First Light's tight narrow pulse/brass attack → Full Column's wide saw and octave anthem.",
        "Blue Hour's rounded sine-supported lead → Solar Arc's cutting saw/pulse lead.",
        "Soft Horizon's dark, slowly opening sine-rich pad → Open Sky's luminous layered saw/pulse pad.",
        "Sun Fleck's short fundamental-led ping → Skylight's upper-partial, longer ringing bell.",
    )

    /** Source-contrast probes use the real recipes without maintaining a second bank. */
    internal fun sourcePairsForTest(): List<Triple<OpenSkyVoice, OpenSkyTimbre, OpenSkyTimbre>> =
        PRESETS.chunked(2).map { pair -> Triple(pair[0].voice, pair[0].timbre, pair[1].timbre) }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.none { it.startsWith("--") }) {
            "usage: OpenSkyAuditionGenerator [output-directory]"
        }
        val root = File(args.firstOrNull() ?: "../testkit/opensky-audition").absoluteFile
        require(root.isDirectory || root.mkdirs()) { "cannot create $root" }
        val page = requireNotNull(OpenSkyAuditionGenerator::class.java.getResourceAsStream("/audition/opensky-audition.html")) {
            "the reproducible listening page is missing from synth/src/test/resources/audition/"
        }.use { it.readBytes() }
        val started = System.nanoTime()
        val groups = mutableListOf<Map<String, Any?>>()
        val written = linkedMapOf<String, Map<String, Any?>>()
        val presetWet = linkedMapOf<OpenSkyVoice, Pair<Rendered, Map<String, Any?>>>()
        val familyGains = linkedMapOf<String, Float>()
        val repository = repositoryRoot()

        fun writeFamily(family: String, input: List<Rendered>): List<Map<String, Any?>> {
            require(input.isNotEmpty())
            input.forEach { validate(it.id, it.snip) }
            val familyPeak = input.maxOf { it.snip.peak() }
            val gain = if (familyPeak > HEADROOM) HEADROOM / familyPeak else 1f
            familyGains[family] = gain
            return input.map { rendered ->
                require(rendered.id !in written) { "duplicate clip id ${rendered.id}" }
                val output = applyGain(rendered.snip, gain)
                val path = "audio/${rendered.id}.wav"
                WavWriter.write(File(root, path), output, WavWriter.BitDepth.PCM_16)
                linkedMapOf<String, Any?>(
                    "id" to rendered.id,
                    "title" to rendered.title,
                    "description" to rendered.description,
                    "file" to path,
                    "voice" to rendered.preset.voice.name,
                    "preset" to rendered.preset.name,
                    "variant" to rendered.variant,
                    "midi" to rendered.midi,
                    "duration" to output.durationSeconds,
                    "channels" to output.channels,
                    "sampleRate" to output.sampleRate,
                    "peak" to output.peak(),
                    "rawPeak" to rendered.snip.peak(),
                    "rms" to rms(output),
                    "exportGain" to gain,
                    "comparisonId" to family,
                    "gainFamily" to family,
                    "macros" to rendered.macros,
                    "timbre" to timbreJson(rendered.preset.timbre),
                    "revision" to REVISION,
                    "bytes" to File(root, path).length(),
                    "sha256" to sha256(File(root, path)),
                ).apply {
                    putAll(rendered.extra)
                    (rendered.extra["sourceEndSeconds"] as? Number)?.toDouble()?.let { end ->
                        val tail = tailMetrics(output, (end * RATE).roundToInt())
                        put("afterSourceRms", tail.first)
                        put("afterSourcePeak", tail.second)
                    }
                }.also { written[rendered.id] = it }
            }
        }

        fun group(id: String, title: String, description: String, clips: List<Map<String, Any?>>) {
            groups += linkedMapOf("id" to id, "title" to title, "description" to description, "clips" to clips)
        }

        val presetClips = mutableListOf<Map<String, Any?>>()
        val presetDry = linkedMapOf<String, Snip>()
        for (voicePresets in PRESETS.chunked(2)) {
            val rendered = voicePresets.flatMap { preset ->
                val dry = render(preset, wet = false)
                val wet = render(preset, wet = true)
                presetDry[preset.id] = dry
                val frames = maxOf(dry.frameCount, wet.frameCount)
                listOf(
                    Rendered("${preset.id}-dry", "${preset.name} · dry", preset.listen +
                        " This is the source before the shimmer-room stage; silence at the end matches its wet partner.",
                        preset, "dry", padTo(dry, frames), extra = mapOf("gateSeconds" to 1.5, "wet" to false,
                            "comparisonId" to "preset-${preset.id}")),
                    Rendered("${preset.id}-wet", "${preset.name} · wet", preset.listen +
                        " Both presets and their dry/wet variants in this voice share one gain; compare at the same player volume.",
                        preset, "wet", padTo(wet, frames), extra = mapOf("gateSeconds" to 1.5, "wet" to true,
                            "comparisonId" to "preset-${preset.id}")),
                )
            }
            val rows = writeFamily("presets-${voicePresets.first().voice.name.lowercase()}", rendered)
            presetClips += rows
            presetWet[voicePresets.first().voice] = rendered[1] to rows[1]
        }
        group("presets", "Eight first listens", "The R2 source blends and articulation remain unchanged in R3. Both same-note presets and all dry/wet variants within a voice use one shared gain.", presetClips)

        val presetPairs = PRESETS.chunked(2).mapIndexed { index, pair ->
            val first = pair[0]
            val second = pair[1]
            val a = presetDry.getValue(first.id)
            val b = presetDry.getValue(second.id)
            val gapFrames = (.65 * RATE).roundToInt()
            val secondOffset = a.frameCount + gapFrames
            val pairSnip = mix(listOf(a to 0, b to secondOffset), gain = 1f)
            val segments = pair.mapIndexed { segment, preset ->
                linkedMapOf("preset" to preset.name, "presetId" to preset.id,
                    "offsetSeconds" to if (segment == 0) 0.0 else secondOffset.toDouble() / RATE,
                    "duration" to if (segment == 0) a.durationSeconds else b.durationSeconds,
                    "macros" to preset.macros, "timbre" to timbreJson(preset.timbre))
            }
            writeFamily("preset-pair-${first.voice.name.lowercase()}", listOf(Rendered(
                "${first.voice.name.lowercase()}-preset-pair", "${first.name} → ${second.name} · dry",
                CONTRASTS[index] + " Both use C4, velocity 1 and a 1.5-second gate. A 650 ms silence gap separates the unaltered dry sources; one common gain applies to the entire comparison, without RMS matching.",
                first, "dry-pair", pairSnip, extra = mapOf("presets" to pair.map { it.name },
                    "segments" to segments, "silenceGapSeconds" to .65, "gateSeconds" to 1.5,
                    "velocity" to 1.0, "wet" to false, "rmsMatched" to false)))).single()
        }
        group("preset-pairs", "Preset A → B", "Hear both recipes in each voice at the same note and gate, dry, separated by silence. Source blend and articulation change; pitch, velocity and player gain stay fixed.", presetPairs)

        val studies = PRESETS.filterIndexed { index, _ -> index % 2 == 0 }
        val registers = studies.flatMap { preset ->
            writeFamily("register-${preset.id}", listOf(48, 84).map { midi ->
                Rendered("${preset.id}-midi-$midi", "${preset.name} · ${noteName(midi)}",
                    "Compare the low and high registers at the same gain. Listen for pitch clarity, brightness and changes in the tail's density.",
                    preset, if (midi == 48) "low" else "high", render(preset, midi = midi), midi,
                    extra = mapOf("gateSeconds" to 1.5, "wet" to true))
            })
        }
        group("registers", "Low and high", "C3 and C6 expose how the source and octave tail change across three octaves. Each voice's pair shares its gain.", registers)

        val chordNotes = listOf(48, 55, 64, 72)
        val chords = studies.map { preset ->
            val raw = chordNotes.map { render(preset, midi = it, gateSeconds = 2f) }
            writeFamily("chord-${preset.id}", listOf(Rendered("${preset.id}-chord", "${preset.name} · C major",
                "A voiced C major chord (C3, G3, E4, C5), four independently rendered notes summed at one quarter each. Listen for separation, beating and accumulation in the upper tail.",
                preset, "chord", mix(raw.map { it to 0 }, gain = 0.25f), midi = 48,
                extra = mapOf("notes" to chordNotes, "gateSeconds" to 2.0, "mixGain" to 0.25, "wet" to true)))).single()
        }
        group("chord", "A major chord", "One four-note voicing per voice. The fixed quarter-level mix leaves room for overlap; this is baked sample composition.", chords)

        val phraseNotes = listOf(60, 64, 67, 72, 71, 67, 64, 60)
        val phrases = studies.map { preset ->
            val events = phraseNotes.mapIndexed { index, midi ->
                render(preset, midi = midi, gateSeconds = 0.38f, velocity = if (index == 3) 1f else 0.85f) to
                    (index * 0.45 * RATE).roundToInt()
            }
            writeFamily("phrase-${preset.id}", listOf(Rendered("${preset.id}-phrase", "${preset.name} · rising phrase",
                "Eight notes rise C–E–G–C, then return B–G–E–C at 450 ms spacing. Listen for articulation and how older tails overlap the next note; all events use the same fixed 0.35 mix gain.",
                preset, "phrase", mix(events, gain = 0.35f),
                extra = mapOf("notes" to phraseNotes, "noteSpacingSeconds" to 0.45, "gateSeconds" to 0.38,
                    "mixGain" to 0.35, "wet" to true)))).single()
        }
        group("phrase", "A short climb", "The same eight-note performance through each voice. Listen for the attack's role when shimmer tails overlap.", phrases)

        val monoClips = studies.flatMap { preset ->
            val (wetRendered, stereoRow) = presetWet.getValue(preset.voice)
            val gain = (stereoRow.getValue("exportGain") as Number).toFloat()
            val fold = mono(applyGain(wetRendered.snip, gain))
            // Fold the exported source gain; writeFamily must not renormalize it.
            val monoRow = writeFamily("mono-${preset.id}", listOf(Rendered("${preset.id}-mono", "${preset.name} · mono",
                "The stereo preset averaged (L + R) / 2, without added gain. Compare with stereo at the same volume and listen for losses of body, brightness or movement.",
                preset, "mono", fold, extra = mapOf("sourceClipId" to stereoRow["id"],
                    "sourceGain" to gain, "fold" to "(L + R) / 2", "wet" to true)))).single()
            val reference = LinkedHashMap(stereoRow).apply {
                this["id"] = "${preset.id}-stereo-reference"
                this["title"] = "${preset.name} · stereo"
                this["description"] = "The same wet preset as the first-listen section. Compare its width with the mono fold at the same volume."
                this["variant"] = "stereo"
                this["comparisonId"] = "mono-${preset.id}"
            }
            listOf(reference, monoRow)
        }
        group("mono", "Stereo and mono", "Four wet presets beside their arithmetic mono folds. The fold keeps the stereo file's gain so cancellations remain audible.", monoClips)

        val macroBase = PRESETS.first { it.id == "soft-horizon" }
        val macroPrompts = mapOf(
            "RISE" to "Listen for the octave-up contribution and ascending brightness; note whether the fundamental remains legible.",
            "WIDTH" to "Listen for detune beating and lateral spread, with all other controls held still.",
            "GLOW" to "Listen for persistence and build-up in the halo after the gated source ends.",
            "AIR" to "Listen for upper sheen and openness across the source and room tail.",
        )
        val sweepClips = MACROS.flatMap { macro ->
            writeFamily("macro-$macro", STEPS.map { value ->
                val controls = macroBase.macros + (macro to value)
                val percent = (value * 100).roundToInt()
                Rendered("macro-${macro.lowercase()}-$percent", "$macro · $percent%",
                    macroPrompts.getValue(macro) + " This three-step family shares one gain; the other three macros keep Soft Horizon's settings.",
                    macroBase, "macro", render(macroBase, macros = controls), macros = controls,
                    extra = mapOf("macro" to macro, "macroValue" to value, "gateSeconds" to 1.5, "wet" to true))
            })
        }
        group("macros", "Four controls", "Three steps for each macro on HALO / Soft Horizon. The controls are rendered authoring parameters; these are separate samples.", sweepClips)

        val excitationPreset = PRESETS.first { it.id == "sun-fleck" }
        val effectControls = excitationPreset.macros + mapOf("RISE" to 1f, "GLOW" to .9f, "AIR" to .95f)
        val effectGate = .12f
        val effectInputs = mutableListOf<Rendered>()
        val effectStudy = mutableListOf<Map<String, Any?>>()
        for (midi in listOf(60, 84)) {
            val tag = if (midi == 60) "c4" else "c6"
            val dry = OpenSky.probe(excitationPreset.voice, effectControls, midi = midi, velocity = 1f,
                gateSeconds = effectGate, wet = false, timbre = excitationPreset.timbre)
            val room = OpenSky.probe(excitationPreset.voice, effectControls, midi = midi, velocity = 1f,
                gateSeconds = effectGate, wet = true, shimmer = false, timbre = excitationPreset.timbre)
            val shimmer = OpenSky.probe(excitationPreset.voice, effectControls, midi = midi, velocity = 1f,
                gateSeconds = effectGate, wet = true, shimmer = true, timbre = excitationPreset.timbre)
            require(dry.sourceFrames == room.sourceFrames && room.sourceFrames == shimmer.sourceFrames) {
                "effect comparison must use exactly the same source duration"
            }
            val frames = maxOf(dry.snip.frameCount, room.snip.frameCount, shimmer.snip.frameCount)
            val sourceEnd = dry.sourceFrames.toDouble() / RATE
            val common = mapOf<String, Any?>(
                "gateSeconds" to effectGate, "sourceEndSeconds" to sourceEnd,
                "sourceFrames" to dry.sourceFrames, "velocity" to 1.0,
                "excitationPreset" to excitationPreset.name,
                "tailSeconds" to (frames.toDouble() / RATE - sourceEnd),
                "tailTruncated" to false,
                "tailPolicy" to "Complete engine-provided finite tail, with a common final 20 ms fade; no additional crop.",
                "comparisonId" to "effect-$tag",
            )
            fun diagnostic(variant: String, title: String, description: String, snip: Snip,
                extra: Map<String, Any?> = emptyMap()): Rendered {
                val out = padTo(snip, frames)
                Dsp.fadeTail(out.samples, 20f, RATE, channels = 2)
                return Rendered("effect-$tag-$variant", "${noteName(midi)} · $title", description,
                    excitationPreset, variant, out, midi, effectControls, common + extra)
            }
            effectInputs += diagnostic("dry", "dry source",
                "A short Sun Fleck strike at ${noteName(midi)}, with its R2 root-led bell source unchanged. The source and release end after about ${"%.3f".format(java.util.Locale.ROOT, sourceEnd)} seconds; the remaining comparison window is silence. Compare with the two wet files at the same volume.", dry.snip,
                mapOf("wet" to false, "shimmer" to false, "paddedToMatchWet" to true, "tailContainsEffect" to false))
            effectInputs += diagnostic("room", "source + room",
                "The identical short strike feeding the room with octave returns disabled. Once the source ends, listen to the room alone: its density, width and natural decay.", room.snip,
                mapOf("wet" to true, "shimmer" to false, "tailContainsEffect" to true,
                    "roomStatePeak" to room.roomPeak, "shimmerFeedbackPeak" to room.shimmerPeak))
            effectInputs += diagnostic("shimmer", "source + room + shimmer",
                "The identical source and room with octave returns enabled. Compare the source-free tail with room only, listening for higher tones, upward color and pitch smear. This file shares the dry and room file's gain.", shimmer.snip,
                mapOf("wet" to true, "shimmer" to true, "tailContainsEffect" to true,
                    "roomStatePeak" to shimmer.roomPeak, "shimmerFeedbackPeak" to shimmer.shimmerPeak))
            if (midi == 60) {
                effectInputs += diagnostic("return", "effect return only",
                    "The exact room-plus-shimmer return from the engine, without the dry strike added and without extra gain. Compare this effect stem with the complete wet file.",
                    Snip(shimmer.wetSamples.copyOf(), shimmer.snip.channels, shimmer.snip.sampleRate),
                    mapOf("wet" to true, "shimmer" to true, "tailContainsEffect" to true,
                        "stem" to true, "stemDefinition" to "Engine wetSamples: the exact room-plus-shimmer return before adding the dry source and before common export gain."))
                effectInputs += diagnostic("shimmer-difference", "shimmer difference only",
                    "The room-plus-shimmer output minus room only, without added gain. This is the incremental effect of enabling both octave paths, including their interaction with room feedback; it is not a separately normalized pitch-shifter solo.", subtract(shimmer.snip, room.snip),
                    mapOf("wet" to true, "shimmer" to true, "tailContainsEffect" to true,
                        "stem" to true, "stemDefinition" to "(source + room + shimmer) minus (source + room); includes feedback-state changes, before common export gain."))
            }
            effectStudy += mapOf("midi" to midi, "sourceEndSeconds" to sourceEnd,
                "sourceFrames" to dry.sourceFrames, "durationSeconds" to frames.toDouble() / RATE)
        }
        val shimmerClips = writeFamily("short-strike-effects", effectInputs)
        group("shimmer", "Room / shimmer", "A short, root-led Sun Fleck strike stops early. Hear dry, room only and room plus shimmer at C4 and C6, then the unboosted C4 effect stems. Every file uses one common gain; 'Hear tail' jumps to the actual source end.", shimmerClips)

        val glowClips = writeFamily("short-strike-glow", STEPS.map { glow ->
            val controls = effectControls + ("GLOW" to glow)
            val probe = OpenSky.probe(excitationPreset.voice, controls, midi = 60, velocity = 1f,
                gateSeconds = effectGate, wet = true, shimmer = false, timbre = excitationPreset.timbre)
            val sourceEnd = probe.sourceFrames.toDouble() / RATE
            val snip = probe.snip.copy(samples = probe.snip.samples.copyOf())
            Dsp.fadeTail(snip.samples, 20f, RATE, channels = 2)
            val percent = (glow * 100).roundToInt()
            Rendered("effect-glow-$percent", "GLOW · $percent% · room only",
                "The same short C4 strike, with only GLOW changed and octave returns disabled. After the source ends, compare how long the ordinary room holds its energy. All three levels share one gain and retain the complete engine tail.",
                excitationPreset, "glow-decay", snip, macros = controls,
                extra = mapOf("gateSeconds" to effectGate, "sourceEndSeconds" to sourceEnd,
                    "sourceFrames" to probe.sourceFrames, "velocity" to 1.0,
                    "excitationPreset" to excitationPreset.name, "tailSeconds" to (snip.durationSeconds - sourceEnd),
                    "wet" to true, "shimmer" to false, "macro" to "GLOW", "macroValue" to glow,
                    "tailContainsEffect" to true, "tailTruncated" to false,
                    "tailPolicy" to "Complete engine-provided finite tail with a final 20 ms fade; no additional crop.",
                    "roomStatePeak" to probe.roomPeak, "shimmerFeedbackPeak" to probe.shimmerPeak))
        })
        group("glow-decay", "GLOW decay", "The same early-ending strike through the ordinary room at GLOW 0, 50 and 100%. Shimmer stays off, source settings stay fixed, and one gain keeps the decay differences audible.", glowClips)

        val heldProbe = OpenSky.heldProbe(macroBase.voice, macroBase.macros, midi = 60, loopSeconds = 3f, wet = true,
            timbre = macroBase.timbre)
        val period = heldProbe.snip
        validate("halo-period", period)
        val seam = seamError(period)
        val periodResidual = periodDifference(heldProbe.previousCycle, period.samples)
        val repeated = repeat(period, 4)
        val loopEvidence = mapOf<String, Any?>(
            "loop" to true,
            "requestedLoopSeconds" to 3.0,
            "loopSeconds" to period.durationSeconds,
            "seamError" to seam,
            "settleCycles" to heldProbe.settleCycles,
            "engineConvergenceError" to heldProbe.loopError,
            "roomStatePeak" to heldProbe.roomPeak,
            "shimmerFeedbackPeak" to heldProbe.shimmerPeak,
            "periodDifferencePeak" to periodResidual.first,
            "periodDifferenceRms" to periodResidual.second,
            "wet" to true,
        )
        val loops = writeFamily("halo-loop", listOf(
            Rendered("halo-period", "HALO · one sustain period",
                "The settled sustain period alone, approximately 3 seconds, without a note attack or release. Its length fits complete root cycles. Listen for motion through one cycle; its end joins its start in the repeat clip.",
                macroBase, "period", period, extra = loopEvidence + ("repeats" to 1)),
            Rendered("halo-repeat-four", "HALO · four uninterrupted periods",
                "Exactly four copies of the same period, joined without fades or crossfades. Listen near 3, 6 and 9 seconds for clicks, level jumps or repetitive motion. This loop-only study does not demonstrate keyboard attack or note-off behavior.",
                macroBase, "repeated-loop", repeated, extra = loopEvidence + ("repeats" to 4)),
        ))
        group("loop", "A held HALO", "A settled HALO sustain period of approximately three seconds, then four exact copies. Assess the audible boundary and the musical repetition separately.", loops)

        val bytes = written.values.sumOf { (it.getValue("bytes") as Number).toLong() }
        require(bytes < AUDIO_BUDGET_MIB.toLong() * 1024 * 1024) { "listening audio exceeds $AUDIO_BUDGET_MIB MiB: $bytes bytes" }
        val sourceFiles = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/OpenSky.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OpenSkyPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/OpenSkyPresets.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/OpenSkyAuditionGenerator.kt",
            "synth/src/test/resources/audition/opensky-audition.html",
        ).associateWith { path -> File(repository, path).takeIf { it.isFile }?.let(::sha256) }
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 2,
            "revision" to REVISION,
            "modelVersion" to OpenSky.MODEL_VERSION,
            "productionPresetSource" to "OpenSkyPresets",
            "defaultGroup" to "shimmer",
            "defaultClipId" to "effect-c4-shimmer",
            "title" to "OPENSKY / SnipSnap",
            "description" to "R3 revises the room and octave-return stages and exposes their source-free tails with short-strike comparisons. The eight R2 source recipes remain unchanged. These WAVs come from SnipSnap's Kotlin DSP; listening decisions are yours.",
            "changes" to "R3 changes the room and shimmer paths, keeps all eight R2 source recipes, and adds same-excitation dry/room/shimmer comparisons, high-register checks, an unboosted engine return stem, a shimmer difference stem and an isolated GLOW decay study. Engine-provided tails are retained in full; the audio budget rises to 128 MiB. R1 and R2 remain separate preserved auditions.",
            "generatedAt" to Instant.now().toString(),
            "sourceCommit" to git(repository, "rev-parse", "HEAD"),
            "sourceDirty" to (git(repository, "status", "--porcelain")?.isNotBlank() ?: true),
            "sourceFilesSha256" to sourceFiles,
            "conceptUrl" to "https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs%2Fconcepts%2Fxopensky_concept_brief.md",
            "repositoryUrl" to "https://github.com/BertCalm/SnipSnap",
            "reproduce" to "./gradlew :synth:generateOpenSkyAudition",
            "presets" to PRESETS.map { linkedMapOf("id" to it.id, "name" to it.name, "voice" to it.voice.name,
                "macros" to it.macros, "timbre" to timbreJson(it.timbre), "description" to it.listen) },
            "presetContrasts" to PRESETS.chunked(2).mapIndexed { index, pair -> linkedMapOf(
                "voice" to pair.first().voice.name, "presets" to pair.map { it.name }, "description" to CONTRASTS[index]) },
            "groups" to groups,
            "effectStudy" to mapOf("excitationPreset" to excitationPreset.name, "gateSeconds" to effectGate,
                "macros" to effectControls, "timbre" to timbreJson(excitationPreset.timbre),
                "registers" to effectStudy,
                "gainPolicy" to "One attenuation-only gain across both registers, all three signal paths and both C4 effect stems; no independent normalization or boost."),
            "model" to linkedMapOf(
                "stage" to "Accepted R3 recipes registered in SnipSnap. This page retains the standalone source, effect and loop listening studies.",
                "rendering" to "Deterministic stereo 44.1 kHz Kotlin renders with four voice identities and a pitch-shifted feedback halo.",
                "authoring" to "RISE, WIDTH, GLOW and AIR shape samples at render time. R2 recipes also save source blends, harmonic balance and envelope settings; no live browser synthesizer or continuous control modulation.",
                "macroBehavior" to linkedMapOf(
                    "RISE" to "Audible octave return, bounded octave feedback amount and source opening.",
                    "WIDTH" to "Unison detune and stereo spread.",
                    "GLOW" to "Nominal room decay target from 0.6 to 7.5 seconds, with a complete bounded finite tail.",
                    "AIR" to "Source and room brightness; room-return gain stays fixed.",
                ),
                "held" to "The engine produces a fully settled loop-only sustain period with periodic oscillator and shifter state. This pack requests 3 seconds; actual duration fits whole root cycles. Supported requests are 1–8 seconds. Unsafe convergence is rejected by the renderer.",
                "limits" to listOf("The loop-only listening study does not demonstrate keyboard attack, release handling or pitch-zone playback; those are separate instrument checks.",
                    "The dual-read-head octave shifter introduces granular sidebands and can smear pitch; this is not an exact harmonic recurrence model.",
                    "Pitching a baked sample changes its shimmer timing. Separate native-note renders here do not evaluate transposed sample playback.",
                    "The original XOpenSky concept's XOmnibus coupling and full live engine behavior are not implemented by this listening spike."),
            ),
            "measurements" to linkedMapOf(
                "clipCount" to written.size,
                "audioBytes" to bytes,
                "audioBudgetMiB" to AUDIO_BUDGET_MIB,
                "tailPolicy" to "All finite clips retain the engine-provided render tail, followed by a 20 ms final fade. No additional tail cropping. R3 raises the budget from 80 to 128 MiB to preserve the longer decay.",
                "finiteTailFormula" to "max(1.2 seconds, 1.6 × the nominal room decay target); target = 0.6 × (7.5 / 0.6)^GLOW seconds.",
                "audioFormat" to "PCM16 / 44.1 kHz WAV",
                "headroomPeak" to HEADROOM,
                "levelPolicy" to "One static gain per gain family, min(1, 0.8 / family peak); no per-clip RMS or loudness normalization. Both presets and all dry/wet variants in a voice share gain. A → B dry previews use one gain across their unaltered segments, without RMS matching. Dry/wet pairs are length-matched with silence. Mono is the already-gained stereo average.",
                "familyGains" to familyGains,
                "heldSeamError" to seam,
                "heldPeriodSeconds" to period.durationSeconds,
                "heldSettleCycles" to heldProbe.settleCycles,
                "heldEngineConvergenceError" to heldProbe.loopError,
                "heldPeriodDifferencePeak" to periodResidual.first,
                "heldPeriodDifferenceRms" to periodResidual.second,
                "periodDifferenceDefinition" to "Independent sample-by-sample difference between the last two complete settled engine periods, before export gain and PCM16. Engine convergence error is squared difference energy divided by current-period energy.",
                "seamDefinition" to "Maximum absolute final-to-first sample difference over the channels, before PCM16 quantization. This includes legitimate one-frame waveform slope; it does not quantify periodic mismatch or establish sonic acceptance.",
                "renderSeconds" to (System.nanoTime() - started) / 1_000_000_000.0,
            ),
        )
        File(root, "manifest.json").writeText(Json.write(json(manifest)) + "\n")
        File(root, "index.html").writeBytes(page)
        println("wrote ${written.size} WAVs (${bytes / 1024 / 1024} MiB) + manifest.json under $root")
    }

    private fun render(preset: Preset, midi: Int = 60, wet: Boolean = true, gateSeconds: Float = 1.5f,
        velocity: Float = 1f, macros: Map<String, Float> = preset.macros): Snip =
        preset.patch.copy(macros = macros, midi = midi, velocity = velocity, gateSeconds = gateSeconds, wet = wet).render()

    private fun timbreJson(timbre: OpenSkyTimbre): JsonValue.Obj = OpenSkyPatch.timbreJson(timbre)

    private fun validate(id: String, snip: Snip) {
        require(snip.samples.isNotEmpty() && snip.channels in 1..2 && snip.sampleRate == RATE) { "$id must be nonempty mono/stereo 44.1 kHz" }
        require(snip.samples.all { it.isFinite() }) { "$id contains non-finite audio" }
        require(snip.peak() > 0f) { "$id is silent" }
    }

    private fun applyGain(snip: Snip, gain: Float): Snip = snip.copy(samples = FloatArray(snip.samples.size) { snip.samples[it] * gain })

    private fun padTo(snip: Snip, frames: Int): Snip = snip.copy(samples = snip.samples.copyOf(frames * snip.channels))

    private fun subtract(a: Snip, b: Snip): Snip {
        require(a.channels == b.channels && a.sampleRate == b.sampleRate)
        return Snip(FloatArray(maxOf(a.samples.size, b.samples.size)) { index ->
            (a.samples.getOrNull(index) ?: 0f) - (b.samples.getOrNull(index) ?: 0f)
        }, a.channels, a.sampleRate)
    }

    /** Exported-level tail evidence, measured after the actual finite source ends. */
    private fun tailMetrics(snip: Snip, sourceEndFrame: Int): Pair<Double, Float> {
        val start = sourceEndFrame.coerceIn(0, snip.frameCount) * snip.channels
        var squared = 0.0
        var peak = 0f
        for (index in start until snip.samples.size) {
            val sample = snip.samples[index]
            squared += sample.toDouble() * sample
            peak = maxOf(peak, abs(sample))
        }
        val count = snip.samples.size - start
        return (if (count == 0) 0.0 else sqrt(squared / count)) to peak
    }

    private fun mono(snip: Snip): Snip = Snip(FloatArray(snip.frameCount) {
        (snip.samples[it * 2] + snip.samples[it * 2 + 1]) * 0.5f
    }, 1, snip.sampleRate)

    private fun mix(events: List<Pair<Snip, Int>>, gain: Float): Snip {
        require(events.isNotEmpty() && events.all { it.first.channels == 2 && it.first.sampleRate == RATE && it.second >= 0 })
        val frames = events.maxOf { it.first.frameCount + it.second }
        val samples = FloatArray(frames * 2)
        for ((snip, offset) in events) for (index in snip.samples.indices) samples[index + offset * 2] += snip.samples[index] * gain
        return Snip(samples, 2, RATE)
    }

    private fun repeat(period: Snip, count: Int) = period.copy(samples = FloatArray(period.samples.size * count) { period.samples[it % period.samples.size] })

    private fun seamError(period: Snip): Float = (0 until period.channels).maxOf { channel ->
        abs(period.samples[period.samples.size - period.channels + channel] - period.samples[channel])
    }

    private fun periodDifference(previous: FloatArray, current: FloatArray): Pair<Double, Double> {
        require(previous.size == current.size && current.isNotEmpty()) { "held evidence requires two complete periods" }
        var peak = 0.0
        var squared = 0.0
        for (index in current.indices) {
            val difference = previous[index].toDouble() - current[index]
            peak = maxOf(peak, abs(difference))
            squared += difference * difference
        }
        return peak to sqrt(squared / current.size)
    }

    private fun rms(snip: Snip): Double = sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size)

    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)

    private fun repositoryRoot(): File = generateSequence(File(".").absoluteFile) { it.parentFile }.firstOrNull {
        File(it, "settings.gradle.kts").isFile && File(it, "synth").isDirectory
    } ?: File("..").absoluteFile

    private fun git(repository: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).directory(repository).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0) output.trim() else null
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) { "non-finite manifest measurement" } })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map { json(it) })
        else -> error("unsupported manifest value ${value::class}")
    }
}
