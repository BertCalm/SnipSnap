package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/** Real Kotlin DSP evidence for a provisional organ/reed family, before application integration. */
object RegistersAuditionGenerator {
    private const val REVISION = 1
    private const val TARGET = .03
    private const val SEAM_LIMIT = .001
    private const val CYCLE_LIMIT = .00001
    private val controls = listOf("COLOR", "AIR", "EDGE", "SPREAD", "MOTION")
    private val pitches = listOf(48, 60, 69)

    private data class Case(
        val id: String,
        val section: String,
        val voice: String,
        val label: String,
        val description: String,
        val note: Int = 60,
        val velocity: Float = .8f,
        val overrides: Map<String, Float> = emptyMap(),
        val gesture: String? = null,
        val macro: String? = null,
        val setting: String? = null,
        val value: Float? = null,
    )

    private data class Render(
        val snip: Snip,
        val macros: Map<String, Float>,
        val recipe: JsonValue,
        val diagnostics: Map<String, Any?>,
        val loop: Map<String, Any?>? = null,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply at most one output directory." }
        val root = File(args.firstOrNull() ?: "../testkit/registers-audition").canonicalFile
        check(root.mkdirs() || root.isDirectory)
        val cases = cases()
        check(cases.size == 56 && cases.map { it.id }.distinct().size == cases.size)
        val clips = ArrayList<Map<String, Any?>>(cases.size)
        for ((index, case) in cases.withIndex()) {
            val rendered = render(case)
            validateSnip(rendered.snip, case.id)
            val exportGain = minOf(1f, .99f / rendered.snip.peak().coerceAtLeast(1e-9f))
            val raw = Snip(FloatArray(rendered.snip.frameCount) { rendered.snip.samples[it] * exportGain }, 1, Dsp.RATE)
            val matched = AuditionLevel.level(rendered.snip)
            val rawPath = "raw/${case.id}.wav"
            val matchedPath = "matched/${case.id}.wav"
            val recipePath = "recipes/${case.id}.json"
            val rawFile = WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
            val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
            val decodedRaw = validateExport(rawFile, raw)
            val decodedMatched = validateExport(matchedFile, matched)
            val rawMetrics = metrics(decodedRaw)
            val matchedMetrics = metrics(decodedMatched)
            check(abs((matchedMetrics.getValue("loudestWindowRms") as Number).toDouble() - TARGET) < .00001) {
                "${case.id}: shared listening target was peak-limited or not reached"
            }
            File(root, recipePath).also { it.parentFile.mkdirs() }.writeText(Json.write(rendered.recipe) + "\n")
            val clip = linkedMapOf<String, Any?>(
                "id" to case.id, "label" to case.label, "description" to case.description,
                "section" to case.section, "voice" to case.voice,
                "noteName" to noteName(case.note), "note" to case.note, "velocity" to case.velocity,
                "macros" to rendered.macros, "gesture" to case.gesture,
                "macro" to case.macro, "setting" to case.setting, "value" to case.value,
                "raw" to rawPath, "matched" to matchedPath, "recipeUrl" to recipePath,
                "frames" to raw.frameCount, "seconds" to raw.frameCount.toDouble() / Dsp.RATE,
                "metrics" to mapOf("raw" to rawMetrics, "matched" to matchedMetrics),
                "sourceMetrics" to metrics(rendered.snip), "rawExportGain" to exportGain,
                "sha256" to mapOf("raw" to sha256(rawFile), "matched" to sha256(matchedFile)),
                "recipe" to rendered.recipe, "diagnostics" to rendered.diagnostics, "loop" to rendered.loop,
            )
            clips += clip
            println("REGISTERS ${index + 1}/${cases.size}: ${case.id} · ${fmt(raw.durationSeconds.toDouble())} s · raw peak ${fmt(raw.peak().toDouble())} · matched ${fmt((matchedMetrics.getValue("loudestWindowRms") as Number).toDouble())}")
        }
        val repository = repositoryRoot()
        val sources = sourceFingerprints(repository)
        val audioHash = sha256(clips.sortedBy { it.getValue("id") as String }.joinToString("\n") {
            val hashes = it.getValue("sha256") as Map<*, *>
            "${it.getValue("id")}:${hashes["raw"]}:${hashes["matched"]}"
        }.toByteArray(Charsets.UTF_8))
        val manifest = Json.write(json(linkedMapOf(
            "schemaVersion" to 1, "title" to "REGISTERS · Pipe + Musette", "revision" to REVISION,
            "engine" to "REGISTERS", "model" to Registers.MODEL_VERSION,
            "status" to "Prototype audition — listening approval pending",
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 24,
            "clipCount" to clips.size, "listeningTarget" to TARGET, "listeningPeakCeiling" to .99,
            "audioHash" to audioHash, "sourceRevision" to git(repository, "rev-parse", "HEAD"),
            "workingTreeDirty" to (git(repository, "status", "--porcelain")?.isNotBlank() ?: true),
            "sourceFingerprints" to sources,
            "sourceFingerprint" to sha256(Json.write(json(sources)).toByteArray(Charsets.UTF_8)),
            "inspiration" to "https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/octave-engine-architecture.md",
            "repository" to "https://github.com/BertCalm/SnipSnap",
            "metricNotes" to "All WAVs are dry mono 44.1 kHz PCM24 from Kotlin DSP. Raw playback preserves conditioned pre-level engine samples, with any PCM peak guard recorded as rawExportGain. Matched playback uses AuditionLevel: loudest 200 ms RMS 0.03 after SnipSnap's gentle low cut. Playback metrics are measured after reading the exported WAVs; sourceMetrics describe the unscaled float render. First-difference RMS is a brightness proxy, not spectral centroid. Held WAVs contain three exact copies of the actual onset-free engine region. Independent generated cycleError and seamError are checked before export; wrapStep is a normal waveform step, not a continuity test by itself. Baselines use existing FULL TONEWHEEL and ORGAN PRISM DSP before normalization, at exact MIDI 48 and 60. No effects, reverb, browser synthesis, application registry integration or factory presets.",
            "sections" to sections(), "voices" to voices(), "clips" to clips,
            "summary" to mapOf(
                "wavCount" to clips.size * 2, "heldCases" to clips.count { it["loop"] != null },
                "allDeterministic" to clips.all { (it.getValue("diagnostics") as Map<*, *>)["deterministic"] == true },
                "allLoopsPassed" to clips.filter { it["loop"] != null }.all { (it["loop"] as Map<*, *>)["seamPass"] == true },
                "maxRawPeak" to clips.maxOf { ((it.getValue("metrics") as Map<*, *>)["raw"] as Map<*, *>)["peak"] as Double },
                "totalAudioSeconds" to clips.sumOf { it.getValue("seconds") as Double },
            ),
        ))) + "\n"
        File(root, "manifest.json").writeText(manifest)
        for (asset in listOf("index.html", "styles.css", "app.js")) {
            val resource = RegistersAuditionGenerator::class.java.getResourceAsStream("/audition/registers-audition/$asset")
                ?: error("Missing REGISTERS listening page resource $asset")
            File(root, asset).outputStream().use { output -> resource.use { it.copyTo(output) } }
        }
        File(root, "README.md").writeText("""
            # REGISTERS audition · revision $REVISION

            56 dry prototype and reference cases; 112 mono PCM24 WAVs at 44.1 kHz.
            Serve this directory as a static site. index.html reads manifest.json.
            Audio is rendered by Kotlin DSP; the browser only plays exported WAVs.

            PIPE and MUSETTE are provisional standalone RegistersPatch configurations.
            Identity notes are C3, C4 and A4. Existing TONEWHEEL FULL and PRISM ORGAN
            references use their shared supported C3/C4 register without pitch clamping.
            Five controls receive low/default/high comparisons at C4. Velocity cases
            preserve level changes in raw playback; matched playback exposes timbre.
            Four HOLD cases retain periodic motion; low spread can fit to zero detuning.
            Each file begins with
            the settled region and contains three exact copies, with no fade or crossfade.

            Raw files preserve conditioned engine level except a recorded peak guard.
            Matched files use AuditionLevel's loudest 200 ms RMS target of 0.03.
            Metrics come from decoded exported WAVs. Recipes, independent loop evidence,
            deterministic checks and source/audio SHA-256 fingerprints are in manifest.json.
            Listening approval and SnipSnap application integration remain pending.

            Regenerate: ./gradlew :synth:generateRegistersAudition
            Audio fingerprint: $audioHash
        """.trimIndent() + "\n")
        println("Wrote ${clips.size} cases / ${clips.size * 2} WAVs, recipes, manifest and audition page under ${root.absolutePath}")
    }

    private fun cases(): List<Case> = buildList {
        for (voice in RegistersVoice.entries) {
            val tag = voice.name.lowercase()
            for (midi in pitches) for (gesture in listOf("restrained", "expressive")) {
                add(Case("${tag}_${midi}_$gesture", "identities", voice.name,
                    "${voice.name} · ${noteName(midi)} · $gesture",
                    if (voice == RegistersVoice.PIPE) "Compare gradual pipe speech, wind and chiff across the register."
                    else "Compare the three beating reeds, bellows contour and pressure-dependent buzz across the register.",
                    note = midi, overrides = gestureMacros(voice, gesture), gesture = gesture))
            }
            for (velocity in listOf(.25f, .6f, 1f)) {
                add(Case("${tag}_velocity_${(velocity * 100).toInt()}", "velocity", voice.name,
                    "${voice.name} · velocity ${(velocity * 100).toInt()}%",
                    "The same C4 patch at three excitation strengths. Compare raw dynamics and matched articulation.",
                    velocity = velocity, overrides = mapOf("HOLD" to .35f)))
            }
            for (macro in controls) for (setting in listOf("low", "default", "high")) {
                val value = when (setting) { "low" -> 0f; "high" -> 1f; else -> Registers.defaults(voice).getValue(macro) }
                add(Case("${tag}_${macro.lowercase()}_$setting", "sweeps", voice.name,
                    "${voice.name} · $macro · $setting", controlDescription(voice, macro),
                    overrides = mapOf("HOLD" to .35f, macro to value), macro = macro, setting = setting, value = value))
            }
            for (setting in listOf("low", "high")) {
                val value = if (setting == "low") .05f else .9f
                add(Case("${tag}_held_$setting", "loops", voice.name,
                    "${voice.name} · held · $setting motion + spread",
                    "Three exact passes of the onset-free held region. Listen through every wrap; periodic motion remains active." +
                        if (setting == "low") " Low spread can fit to zero detuning on the loop clock." else " Strong fitted detuning remains active.",
                    overrides = mapOf("HOLD" to 1f, "SPREAD" to value, "MOTION" to value), setting = setting))
            }
        }
        for (voice in listOf("TONEWHEEL", "PRISM")) for (midi in listOf(48, 60)) {
            add(Case("${voice.lowercase()}_reference_$midi", "references", voice,
                "$voice ${if (voice == "TONEWHEEL") "FULL" else "ORGAN"} · ${noteName(midi)}",
                "Existing SnipSnap organ DSP at its genuine supported pitch. Compare against PIPE and MUSETTE at the same note and matched level.",
                note = midi, velocity = 1f))
        }
    }

    private fun gestureMacros(voice: RegistersVoice, gesture: String): Map<String, Float> {
        val values = when {
            voice == RegistersVoice.PIPE && gesture == "restrained" -> listOf(.18f, .20f, .08f, .06f, .05f)
            voice == RegistersVoice.PIPE -> listOf(.55f, .72f, .62f, .62f, .55f)
            gesture == "restrained" -> listOf(.32f, .24f, .12f, .20f, .08f)
            else -> listOf(.64f, .78f, .52f, .78f, .50f)
        }
        return controls.zip(values).toMap() + ("HOLD" to .35f)
    }

    private fun render(case: Case): Render = if (case.voice in RegistersVoice.entries.map { it.name }) {
        val voice = RegistersVoice.valueOf(case.voice)
        val macros = Registers.defaults(voice) + case.overrides + ("TUNE" to (case.note - 48) / 24f)
        val patch = RegistersPatch(case.label, voice, macros, case.note, case.velocity)
        val probe = patch.probe()
        val recipe = Json.parse(patch.toJsonText())
        val repeated = RegistersPatch.fromJsonText(Json.write(recipe)).probe()
        check(probe.samples.contentEquals(repeated.samples)) { "${case.id}: patch roundtrip was not deterministic" }
        val loop = if (probe.isLoop) {
            val frames = requireNotNull(probe.loopFrames)
            check(frames == probe.samples.size)
            val seam = requireNotNull(probe.seamError)
            val cycle = requireNotNull(probe.cycleError)
            check(seam.isFinite() && seam < SEAM_LIMIT && cycle.isFinite() && cycle < CYCLE_LIMIT) {
                "${case.id}: independent held cycles failed (seam=$seam, cycle=$cycle)"
            }
            mapOf(
                "regionFrames" to frames, "regionSeconds" to frames.toDouble() / Dsp.RATE,
                "playbackRepetitions" to 3, "seamError" to seam, "cycleError" to cycle, "seamPass" to true,
                "wrapStep" to abs(probe.samples.first().toDouble() - probe.samples.last()),
                "wrapSlopeDifference" to abs((probe.samples[1].toDouble() - probe.samples[0]) -
                    (probe.samples.last().toDouble() - probe.samples[probe.samples.lastIndex - 1])),
            )
        } else null
        val output = if (probe.isLoop) FloatArray(probe.samples.size * 3) { probe.samples[it % probe.samples.size] } else probe.samples
        Render(Snip(output, 1, Dsp.RATE), macros, recipe, mapOf(
            "requestedHz" to probe.requestedHz, "effectiveHz" to probe.effectiveHz,
            "tuningErrorCents" to 1200.0 * ln(probe.effectiveHz / probe.requestedHz) / ln(2.0),
            "rawPeak" to probe.rawPeak, "effectiveSpreadHz" to probe.effectiveSpreadHz,
            "motionHz" to probe.motionHz, "deterministic" to true,
        ), loop)
    } else renderReference(case)

    private fun renderReference(case: Case): Render {
        val hz = Keys.midiHz(case.note).toDouble()
        if (case.voice == "TONEWHEEL") {
            val macros = Tonewheel.defaults(TonewheelVoice.FULL) + ("TUNE" to (case.note - 45) / 24f)
            check(abs(Tonewheel.frequencyFor(macros.getValue("TUNE")).toDouble() - hz) < .001)
            fun samples(): FloatArray {
                val raw = Tonewheel.synthesize(TonewheelVoice.FULL, macros, 2.1f, Dsp.RATE * Dsp.OVERSAMPLE, motion = true)
                val out = Dsp.decimate(raw, Dsp.RATE)
                Dsp.fadeTail(out)
                return out
            }
            val output = samples()
            check(output.contentEquals(samples())) { "${case.id}: TONEWHEEL reference was not deterministic" }
            return Render(Snip(output, 1, Dsp.RATE), macros, json(mapOf(
                "engine" to "TONEWHEEL", "voice" to "FULL", "note" to case.note,
                "macros" to macros, "renderConfiguration" to mapOf(
                    "gateSeconds" to 2.1, "motion" to true, "normalize" to false,
                    "velocitySupported" to false,
                ),
            )), mapOf("requestedHz" to hz, "effectiveHz" to hz, "tuningErrorCents" to 0.0,
                "deterministic" to true, "velocitySupported" to false))
        }
        val macros = Prism.defaults(PrismVoice.ORGAN) + ("TUNE" to (case.note - Prism.rootMidi(PrismVoice.ORGAN)) / 24f)
        check(abs(Prism.frequencyFor(PrismVoice.ORGAN, macros.getValue("TUNE")).toDouble() - hz) < .001)
        val patch = PrismPatch(case.label, PrismVoice.ORGAN, macros, case.velocity)
        val probe = Prism.probe(PrismVoice.ORGAN, macros, case.velocity, patch.template, normalize = false)
        val repeated = Prism.probe(PrismVoice.ORGAN, macros, case.velocity, patch.template, normalize = false)
        check(probe.samples.contentEquals(repeated.samples)) { "${case.id}: PRISM reference was not deterministic" }
        val recipe = JsonValue.Obj(patch.toJsonValue().entries + ("renderConfiguration" to json(mapOf("normalize" to false))))
        return Render(Snip(probe.samples, 1, Dsp.RATE), macros, recipe,
            mapOf("requestedHz" to hz, "effectiveHz" to probe.effectiveHz, "tuningErrorCents" to 0.0,
                "rawPeak" to probe.raw.maxOf { abs(it).toDouble() }, "deterministic" to true))
    }

    private fun validateSnip(snip: Snip, id: String) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.frameCount > 2)
        check(snip.samples.all { it.isFinite() } && snip.peak() > 1e-6f) { "$id: invalid or silent render" }
    }

    private fun validateExport(file: File, expected: Snip): Snip {
        val bytes = file.readBytes()
        // WavWriter emits a fixed PCM fmt chunk before data; verify the actual exported format.
        check((bytes[20].toInt() and 255) == 1 && (bytes[34].toInt() and 255) == 24)
        val decoded = WavReader.read(bytes)
        validateSnip(decoded, file.name)
        check(decoded.frameCount == expected.frameCount && decoded.peak() <= .990001f)
        check(decoded.samples.indices.all { abs(decoded.samples[it] - expected.samples[it]) <= 1.3e-7f }) {
            "${file.name}: PCM roundtrip exceeded quantization tolerance"
        }
        return decoded
    }

    private fun metrics(snip: Snip): Map<String, Any> {
        val samples = snip.samples
        var sum = 0.0
        var energy = 0.0
        var differenceEnergy = 0.0
        for (i in samples.indices) {
            val sample = samples[i].toDouble()
            sum += sample
            energy += sample * sample
            if (i > 0) { val difference = sample - samples[i - 1]; differenceEnergy += difference * difference }
        }
        val rms = sqrt(energy / samples.size)
        return mapOf(
            "peak" to snip.peak().toDouble(), "rms" to rms,
            "loudestWindowRms" to Loudness.of(snip).toDouble(), "dcMean" to sum / samples.size,
            "firstDifferenceRms" to sqrt(differenceEnergy / (samples.size - 1)),
            "crestDb" to 20 * log10(snip.peak().toDouble() / rms.coerceAtLeast(1e-20)),
        )
    }

    private fun controlDescription(voice: RegistersVoice, macro: String): String = when (macro) {
        "COLOR" -> if (voice == RegistersVoice.PIPE) "Registration-inspired harmonic weighting moves from dark foundation to brighter principal color."
            else "Reed harmonic balance moves from rounded body to brighter accordion detail."
        "AIR" -> if (voice == RegistersVoice.PIPE) "Wind changes pipe speech and its opening contour; use matched playback to hear articulation."
            else "Bellows pressure changes the swell and reed excitation; use matched playback to hear articulation."
        "EDGE" -> if (voice == RegistersVoice.PIPE) "The wind-driven chiff becomes more pronounced at the pipe entrance."
            else "Pressure-dependent reed buzz becomes more pronounced."
        "SPREAD" -> "Symmetric detuning changes beating between ranks or reeds while the requested note stays fixed."
        "MOTION" -> if (voice == RegistersVoice.PIPE) "Tremulant introduces periodic movement in the sustained pipe body."
            else "Periodic vibrato moves the sustained reed body."
        else -> error("Unknown control $macro")
    }

    private fun sections() = listOf(
        section("identities", "First listen", "PIPE and MUSETTE at C3, C4 and A4, each with restrained and expressive articulation. Start with matched playback, then compare raw energy."),
        section("velocity", "Touch", "The same C4 patch at 25%, 60% and full velocity. Raw reveals dynamics; matched reveals changes in excitation and articulation."),
        section("sweeps", "One control at a time", "Low, default and high COLOR, AIR, EDGE, SPREAD and MOTION. Every other control stays at the voice defaults, with the same finite HOLD."),
        section("loops", "Held motion", "Four onset-free held cases with low or high spread and motion. Files contain three exact wraps of the engine's loop, with independent seam evidence."),
        section("references", "Existing SnipSnap organs", "TONEWHEEL FULL and PRISM ORGAN at their shared C3/C4 notes. Compare matched identity and attack against the new prototypes."),
    )

    private fun voices() = listOf(
        mapOf("id" to "PIPE", "label" to "PIPE", "description" to "Twelve harmonic partials with registration-inspired weighting, gradual speech, wind and chiff."),
        mapOf("id" to "MUSETTE", "label" to "MUSETTE", "description" to "Three beating reeds, bellows swells and pressure-dependent buzz."),
        mapOf("id" to "TONEWHEEL", "label" to "TONEWHEEL FULL", "description" to "Existing drawbar organ with scanner chorus, percussion and rotary movement."),
        mapOf("id" to "PRISM", "label" to "PRISM ORGAN", "description" to "Existing additive organ template with developing partial groups."),
    )

    private fun section(id: String, label: String, description: String) = mapOf("id" to id, "label" to label, "description" to description)
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)
    private fun fmt(value: Double) = String.format(Locale.ROOT, "%.5f", value)
    private fun sha256(file: File) = sha256(file.readBytes())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun repositoryRoot(): File = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun sourceFingerprints(repository: File): List<Map<String, String>> {
        val paths = listOf(
            "synth/src/main/kotlin/com/snipsnap/synth/Registers.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/RegistersPatch.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Tonewheel.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Prism.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Tide.kt",
            "synth/src/main/kotlin/com/snipsnap/synth/Keys.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/RegistersAuditionGenerator.kt",
            "synth/src/test/kotlin/com/snipsnap/synth/AuditionLevel.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/Loudness.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavWriter.kt",
            "audio/src/main/kotlin/com/snipsnap/audio/WavReader.kt",
            "json/src/main/kotlin/com/snipsnap/json/Json.kt",
        )
        return paths.map { path -> mapOf("path" to path, "sha256" to sha256(File(repository, path))) }
    }

    private fun git(repository: File, vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).directory(repository).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported REGISTERS manifest value ${value.javaClass}")
    }
}
