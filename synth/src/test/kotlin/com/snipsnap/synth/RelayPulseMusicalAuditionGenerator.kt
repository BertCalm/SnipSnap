package com.snipsnap.synth

import com.snipsnap.audio.Loudness
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

/**
 * The periodic pulse in actual playing: a bassline, two chord voicings and a held texture.
 * Every sounding note goes through the model-aware production renderer. Finite releases
 * are preserved, the native velocities are recorded, and one shared summing gain per
 * performance keeps raw PULSE 0 / 0.5 / 1 comparisons from acquiring different mix boosts.
 */
object RelayPulseMusicalAuditionGenerator {
    private data class Note(val beat: Double, val semitones: Int, val velocity: Float)
    private data class Gesture(
        val kind: String,
        val voice: RelayVoice,
        val preset: String,
        val title: String,
        val notes: List<Note> = emptyList(),
        val tempoBpm: Double = 90.0,
        val held: Boolean = false,
    )
    private data class Case(val gesture: Gesture, val control: Float, val patch: RelayPatch) {
        val id get() = "pulse_music_${gesture.kind}_${when (control) { 0f -> "0"; .5f -> "half"; else -> "1" }}"
    }
    private data class RenderedNote(val startFrame: Int, val note: Note, val patch: RelayPatch, val report: RelayModels.Report)
    private data class RenderedCase(val case: Case, val notes: List<RenderedNote>, val renderMs: Double) {
        val reference get() = notes.first().report
        val peakBound: Double get() {
            val boundaries = sortedMapOf<Int, Double>()
            for ((start, _, _, report) in notes) {
                boundaries[start] = boundaries.getOrDefault(start, 0.0) + report.snip.peak()
                val end = start + report.snip.frameCount
                boundaries[end] = boundaries.getOrDefault(end, 0.0) - report.snip.peak()
            }
            var active = 0.0
            var peak = 0.0
            for (delta in boundaries.values) { active += delta; peak = maxOf(peak, active) }
            return peak
        }
    }

    private val gestures = listOf(
        Gesture("bassline", RelayVoice.BASS, "ROUND TRIP", "A bassline with three touches", listOf(
            Note(0.0, 0, .35f), Note(1.0, 0, .65f), Note(2.5, 7, 1f),
            Note(3.5, 10, .65f), Note(5.0, 5, 1f), Note(6.5, 0, .35f),
        )),
        Gesture("chords", RelayVoice.KEY, "VELVET TURN", "Two C-minor / F-minor voicings", listOf(
            Note(0.0, 0, .65f), Note(0.0, 3, .35f), Note(0.0, 7, 1f),
            Note(4.0, 0, .35f), Note(4.0, 5, .65f), Note(4.0, 8, 1f),
        )),
        Gesture("held", RelayVoice.FLOW, "WARM DRIFT", "One held evolving texture", held = true),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "supply one optional output directory" }
        val root = File(args.firstOrNull() ?: "testkit/relay-pulse-musical-audition")
        check(root.isDirectory || root.mkdirs()) { "cannot create ${root.absolutePath}" }
        val sourceFiles = sourceFiles()
        val sourceHashes = sourceFiles.mapValues { sha256(it.value.readBytes()) }
        val revision = git("rev-parse", "HEAD")
        val dirty = git("status", "--porcelain")?.isNotBlank() ?: true
        fun requireUnchanged() {
            check(git("rev-parse", "HEAD") == revision && sourceFiles.all { (name, file) ->
                sha256(file.readBytes()) == sourceHashes.getValue(name)
            }) { "Relay musical audition source changed during rendering; regenerate the complete round" }
        }
        val cases = cases()
        val recipeHash = sha256(cases.joinToString("\n") {
            listOf(it.id, Json.write(it.patch.toJsonValue()), it.gesture.tempoBpm, it.gesture.notes,
                it.gesture.held).joinToString("|")
        }.toByteArray())
        val clips = ArrayList<Map<String, Any?>>(cases.size)
        val started = System.nanoTime()
        for (gesture in gestures) {
            val rendered = cases.filter { it.gesture == gesture }.map { case ->
                requireUnchanged()
                render(case)
            }
            // The same gain is used at all three depths. Its independent worst-case
            // bound sums note peaks at actual overlap times, rather than metering the sum.
            val familyPeakBound = rendered.maxOf { it.peakBound }
            val mixGain = minOf(1.0, .8 / familyPeakBound.coerceAtLeast(1e-12))
            for (performance in rendered) {
                val case = performance.case
                val report = performance.reference
                val mixed = mix(performance, mixGain)
                val repetitions = if (gesture.held) 3 else 1
                val playback = if (repetitions == 1) mixed else Snip(
                    FloatArray(mixed.samples.size * repetitions) { mixed.samples[it % mixed.samples.size] }, 1, Dsp.RATE)
                check(playback.samples.all { it.isFinite() } && playback.peak() <= .800001f) { "invalid ${case.id}" }
                val exportGain = minOf(1f, .99f / playback.peak().coerceAtLeast(1e-9f))
                val raw = Snip(FloatArray(playback.samples.size) { playback.samples[it] * exportGain }, 1, Dsp.RATE)
                val matched = AuditionLevel.level(playback)
                val rawPath = "raw/${case.id}.wav"
                val matchedPath = "matched/${case.id}.wav"
                val rawFile = WavWriter.write(File(root, rawPath), raw, WavWriter.BitDepth.PCM_24)
                val matchedFile = WavWriter.write(File(root, matchedPath), matched, WavWriter.BitDepth.PCM_24)
                val sequence = if (gesture.held) null else performance.notes.map { event(it) }
                val single = performance.notes.first()
                val midi = midi(single.patch)
                clips += linkedMapOf(
                    "id" to case.id, "group" to "musicalpulse", "voice" to gesture.voice.name,
                    "title" to "${gesture.title} · PULSE ${controlLabel(case.control)}",
                    "description" to description(gesture), "patchName" to case.patch.name,
                    "patchJson" to case.patch.toJsonValue(), "model" to case.patch.model,
                    "sources" to case.patch.phrase.sources.map { it.role.name },
                    "macros" to (Relay.defaults(case.patch.voice) + case.patch.macros),
                    "midi" to midi, "note" to noteName(midi), "velocity" to case.patch.velocity,
                    "tempoBpm" to case.patch.phrase.tempoBpm,
                    "actualTempoBpm" to case.patch.phrase.phraseBeats * 60.0 / report.phraseSeconds,
                    "requestedHz" to report.requestedHz, "actualHz" to report.actualHz,
                    "tuningErrorCents" to cents(report), "phraseSeconds" to report.phraseSeconds,
                    "gateSeconds" to report.gateSeconds, "frames" to playback.frameCount,
                    "seconds" to playback.durationSeconds,
                    "rawPath" to rawPath, "matchedPath" to matchedPath,
                    "rawSha256" to sha256(rawFile.readBytes()), "matchedSha256" to sha256(matchedFile.readBytes()),
                    "rawExportGain" to exportGain, "rawMetrics" to metrics(playback), "matchedMetrics" to metrics(matched),
                    "internalRawPeak" to performance.notes.maxOf { it.report.rawPeak },
                    "internalRawPeakScope" to "maximum individual PULSE-0 source 4x peak before the periodic gain stage and note summing",
                    "engineNormalization" to false,
                    "renderMs" to performance.renderMs, "renderMsPerAudioSecond" to performance.renderMs / mixed.durationSeconds,
                    "loop" to if (gesture.held) loop(report, mixGain, repetitions) else null,
                    "sequence" to sequence, "sequenceTempoBpm" to if (gesture.held) null else gesture.tempoBpm,
                    "weights" to if (gesture.held) weights(report) else emptyList<Any>(),
                    "accents" to emptyList<Any>(), "pulseModulation" to if (gesture.held) pulse(report) else null,
                    "weightScope" to if (gesture.held) "One actual engine loop region, repeated three times in the WAV"
                        else "Each complete note has its own source trajectory in sequence; there is no single mixed source-weight path",
                    "mixGain" to mixGain, "headroom" to mapOf(
                        "familyMaxConcurrentPeakBound" to familyPeakBound,
                        "thisVariantConcurrentPeakBound" to performance.peakBound,
                        "outputPeakBound" to mixGain * performance.peakBound,
                        "peakCeiling" to .8, "sharedAcrossControls" to listOf(0.0, .5, 1.0)),
                    "contour" to contour(playback), "renderer" to "production-model-2",
                    "pulsePerformance" to mapOf("kind" to gesture.kind, "control" to case.control,
                        "model" to case.patch.model, "familyId" to "pulse_music_${gesture.kind}",
                        "nativeVelocities" to performance.notes.map { it.patch.velocity }.distinct(),
                        "pulseClock" to if (gesture.held) "whole pulse cycles in the actual root-fitted engine phrase"
                            else "3 Hz per complete note; note-on restarts the pulse phase; no shared live clock",
                        "sharedMixGain" to mixGain),
                )
                println("Relay pulse musical: ${clips.size}/${cases.size} · ${case.id}")
            }
        }
        requireUnchanged()
        check(clips.size == 9 && clips.map { it["id"] }.distinct().size == 9)
        val wavBytes = clips.sumOf { File(root, it.getValue("rawPath") as String).length() + File(root, it.getValue("matchedPath") as String).length() }
        check(wavBytes <= 120L * 1024 * 1024) { "Relay musical audition exceeds 120 MiB: $wavBytes bytes" }
        val manifest = Json.write(json(linkedMapOf(
            "schemaVersion" to 1, "engine" to "RELAY", "auditionRevision" to 10,
            "status" to "model 2 musical check; listening pending; saved model 1 sounds retained",
            "sampleRate" to Dsp.RATE, "channels" to 1, "bitDepth" to 24,
            "clipCount" to clips.size, "listeningTarget" to .03, "listeningPeakCeiling" to .99,
            "sourceRevision" to revision, "workingTreeDirty" to dirty,
            "sourceSha256" to sourceHashes, "recipeSha256" to recipeHash,
            "compiledRendererSha256" to listOf("Relay", "RelayModels", "RelayPulse").associateWith { name ->
                RelayPulseMusicalAuditionGenerator::class.java.getResourceAsStream("/com/snipsnap/synth/$name.class")
                    ?.use { sha256(it.readBytes()) } ?: error("missing compiled $name")
            },
            "metricNotes" to "Nine actual model-2 production performances compare PULSE 0, 0.5 and 1. Finite notes retain complete natural releases and native excitation velocities 0.35, 0.65 and 1. MIDI replaces TUNE absolutely. Every performance family uses one fixed mixGain across its three controls, bounded by the highest actual concurrent sum of individual peaks across those controls. Raw files precede engine loudness normalization; rawMetrics precedes the recorded PCM export gain. Matched files use the existing loudest-200ms AuditionLevel target 0.03 and peak ceiling 0.99. Finite note-on restarts each 3Hz pulse; 90BPM aligns two cycles per beat and is not a shared live tempo bus. Complete recipes, actual note onsets, clocks, source histories and periodic pulse metadata are stored per note. Serialized source accents are inactive in model 2 and are not drawn as sounding pulses. Held playback repeats three exact copies of one real model-2 engine loop; seamError uses its actual preceding cycle and must be below 0.001. Source 4x peaks precede the added finished-sample gain and any note summing. Contour brightness is a first-difference RMS ratio, not spectral centroid. Numerical checks and listening approval are separate; this generator claims no owner listen for these new performances.",
            "groups" to listOf(mapOf("id" to "musicalpulse", "title" to "Pulse in actual playing",
                "description" to "The same bassline, chord voicings and held evolving texture at PULSE 0, 0.5 and 1. Begin with Matched to compare movement; Raw retains native touch and energy.")),
            "clips" to clips,
            "summary" to mapOf("wavCount" to clips.size * 2, "wavBytes" to wavBytes,
                "performanceFamilies" to gestures.size, "model" to RelayPatch.MODEL_VERSION,
                "heldCases" to clips.count { it["loop"] != null },
                "loopSeamsPassed" to clips.filter { it["loop"] != null }.all { (it["loop"] as Map<*, *>)["seamPass"] == true },
                "generationSeconds" to (System.nanoTime() - started) / 1e9),
        ))) + "\n"
        prune(root, clips.flatMap { listOf(it.getValue("rawPath") as String, it.getValue("matchedPath") as String) }.toSet())
        File(root, "manifest.json").writeText(manifest)
        File(root, "README.md").writeText(readme())
        File(root, "index.html").writeText(page(manifest))
        println("Wrote ${clips.size * 2} WAVs ($wavBytes bytes), manifest.json, index.html and README.md under ${root.absolutePath}")
    }

    private fun cases(): List<Case> = buildList {
        for (gesture in gestures) {
            val source = RelayPresets.all().single { it.voice == gesture.voice && it.name == gesture.preset }
            for (control in listOf(0f, .5f, 1f)) {
                add(Case(gesture, control, source.copy(model = RelayPatch.MODEL_VERSION,
                    macros = source.macros + mapOf("PULSE" to control, "HOLD" to if (gesture.held) 1f else 0f),
                    velocity = if (gesture.held) .65f else source.velocity)))
            }
        }
    }

    private fun description(gesture: Gesture): String = when (gesture.kind) {
        "bassline" -> "ROUND TRIP plays a C-minor-pentatonic bassline at 90 BPM with native excitation velocities 0.35, 0.65 and 1. Every note retains its complete natural release; the three pulse depths share one summing gain."
        "chords" -> "VELVET TURN plays C-minor and F-minor chord voicings at 90 BPM with native excitation velocities 0.35, 0.65 and 1. Complete note releases overlap, and the three pulse depths share one summing gain."
        "held" -> "WARM DRIFT sustains one evolving note at native excitation velocity 0.65. Playback repeats three exact copies of its actual model-2 engine loop region; the seam is measured against the real preceding cycle."
        else -> error("unknown Relay musical gesture ${gesture.kind}")
    }

    private fun render(case: Case): RenderedCase {
        val before = System.nanoTime()
        val baseMidi = midi(case.patch)
        val notes = if (case.gesture.held) listOf(Note(0.0, 0, case.patch.velocity)) else case.gesture.notes
        val rendered = notes.map { note ->
            val noteMidi = baseMidi + note.semitones
            check(noteMidi in Relay.rootMidi(case.patch.voice)..Relay.rootMidi(case.patch.voice) + Relay.TUNE_SEMITONES)
            val patch = case.patch.copy(velocity = note.velocity, macros = case.patch.macros +
                ("TUNE" to (noteMidi - Relay.rootMidi(case.patch.voice)).toFloat() / Relay.TUNE_SEMITONES))
            val report = RelayModels.inspect(patch.voice, patch.macros, patch.velocity, normalize = false,
                phrase = patch.phrase, model = patch.model)
            check(report.loop == case.gesture.held && report.snip.samples.all { it.isFinite() })
            RenderedNote((note.beat * 60.0 / case.gesture.tempoBpm * Dsp.RATE).roundToInt(), note, patch, report)
        }
        return RenderedCase(case, rendered, (System.nanoTime() - before) / 1e6)
    }

    private fun mix(performance: RenderedCase, gain: Double): Snip {
        val out = DoubleArray(performance.notes.maxOf { it.startFrame + it.report.snip.frameCount })
        for ((start, _, _, report) in performance.notes) for (i in report.snip.samples.indices) {
            out[start + i] += gain * report.snip.samples[i]
        }
        return Snip(FloatArray(out.size) { out[it].toFloat() }, 1, Dsp.RATE)
    }

    private fun event(event: RenderedNote): Map<String, Any?> = event.let { (start, note, patch, report) ->
        linkedMapOf(
            "beat" to note.beat, "timeSeconds" to start.toDouble() / Dsp.RATE, "startFrame" to start,
            "midi" to midi(patch), "note" to noteName(midi(patch)), "velocity" to patch.velocity,
            "frames" to report.snip.frameCount, "seconds" to report.snip.durationSeconds,
            "peak" to report.snip.peak(), "rms" to metrics(report.snip).getValue("rms"),
            "requestedHz" to report.requestedHz, "actualHz" to report.actualHz,
            "tuningErrorCents" to cents(report), "phraseSeconds" to report.phraseSeconds,
            "gateSeconds" to report.gateSeconds, "model" to patch.model,
            "internalRawPeak" to report.rawPeak,
            "internalRawPeakScope" to "actual PULSE-0 source before the finished-sample pulse stage",
            "patchJson" to patch.toJsonValue(), "weights" to weights(report),
            "accents" to emptyList<Any>(), "pulseModulation" to pulse(report),
        )
    }

    private fun pulse(report: RelayModels.Report): Map<String, Any?>? = report.pulse?.let {
        mapOf("kind" to "periodic-amplitude", "depthDb" to it.depthDb,
            "minimumGain" to it.minimumGain, "maximumGain" to 1.0,
            "rateHz" to it.rateHz, "pulseCycles" to it.cycles,
            "serializedAccentRosterActive" to false,
            "formula" to "floor=10^(-7*p/20); gain=floor+(1-floor)*(0.5+0.5*cos(2*pi*rate*t))")
    }

    private fun loop(report: RelayModels.Report, mixGain: Double, repetitions: Int): Map<String, Any> {
        val samples = report.snip.samples
        check(report.previousCycle.size == samples.size)
        val preceding = FloatArray(samples.size) { (report.previousCycle[it] * mixGain).toFloat() }
        val actual = FloatArray(samples.size) { (samples[it] * mixGain).toFloat() }
        val seam = Keys.seamError(preceding + actual, preceding.size)
        check(report.seamError < Keys.MAX_SEAM_ERROR && seam < Keys.MAX_SEAM_ERROR) { "musical held phrase does not close" }
        return mapOf("regionFrames" to samples.size, "regionSeconds" to report.snip.durationSeconds,
            "playbackRepetitions" to repetitions, "seamError" to seam,
            "nativeEngineSeamError" to report.seamError, "seamPass" to true,
            "wrapStep" to abs(actual.first() - actual.last()),
            "wrapSlopeDifference" to abs((actual[1] - actual[0]) - (actual.last() - actual[actual.lastIndex - 1])))
    }

    private fun weights(report: RelayModels.Report) = report.weights.map { mapOf("timeSeconds" to it.timeSeconds, "weights" to it.weights) }
    private fun cents(report: RelayModels.Report) = 1200.0 * ln(report.actualHz / report.requestedHz) / ln(2.0)
    private fun midi(patch: RelayPatch) = Relay.midiFor(patch.voice, (Relay.defaults(patch.voice) + patch.macros).getValue("TUNE"))
    private fun controlLabel(value: Float) = if (value == .5f) "0.5" else value.toInt().toString()
    private fun noteName(midi: Int) = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")[midi % 12] + (midi / 12 - 1)

    private fun metrics(snip: Snip): Map<String, Any> {
        val x = snip.samples
        var power = 0.0; var sum = 0.0; var difference = 0.0
        for (i in x.indices) {
            power += x[i].toDouble() * x[i]; sum += x[i]
            if (i > 0) difference = maxOf(difference, abs(x[i].toDouble() - x[i - 1]))
        }
        val tailFrames = minOf(x.size, snip.sampleRate / 20)
        var tailPower = 0.0
        for (i in x.size - tailFrames until x.size) tailPower += x[i].toDouble() * x[i]
        return mapOf("peak" to snip.peak(), "rms" to sqrt(power / x.size), "dc" to sum / x.size,
            "loudestWindowRms" to Loudness.of(snip), "last50msRms" to sqrt(tailPower / tailFrames),
            "firstSample" to x.first(), "lastSample" to x.last(), "firstDifferencePeak" to difference,
            "finite" to x.all { it.isFinite() })
    }

    private fun contour(snip: Snip): List<Map<String, Any>> = (0 until 24).map { block ->
        val x = snip.samples
        val from = block * x.size / 24
        val to = (block + 1) * x.size / 24
        var power = 0.0; var difference = 0.0
        for (i in from until to) {
            power += x[i].toDouble() * x[i]
            if (i > from) { val d = x[i].toDouble() - x[i - 1]; difference += d * d }
        }
        mapOf("timeSeconds" to (from + to).toDouble() / 2 / Dsp.RATE,
            "rms" to sqrt(power / (to - from)), "brightnessProxy" to sqrt(difference / power.coerceAtLeast(1e-20)))
    }

    private fun sourceFiles(): Map<String, File> = linkedMapOf<String, File>().apply {
        for (name in listOf("Relay.kt", "RelayModels.kt", "RelayPulse.kt", "RelayPatch.kt", "RelayPresets.kt", "RelayPhrase.kt", "Dsp.kt")) {
            put("main/$name", locate("src/main/kotlin/com/snipsnap/synth/$name"))
        }
        for (name in listOf("RelayPulseMusicalAuditionGenerator.kt", "AuditionLevel.kt")) {
            put("test/$name", locate("src/test/kotlin/com/snipsnap/synth/$name"))
        }
    }
    private fun locate(path: String): File = listOf(File(path), File("synth/$path")).firstOrNull { it.isFile }
        ?: error("run from the synth module or repository root; missing $path")
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun git(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else null
    }.getOrNull()
    private fun prune(root: File, retained: Set<String>) {
        val previous = File(root, "manifest.json")
        if (!previous.isFile) return
        val clips = runCatching { Json.parse(previous.readText()).obj().getValue("clips").arr() }.getOrDefault(emptyList())
        for (clip in clips) for (key in listOf("rawPath", "matchedPath")) {
            val path = runCatching { clip.obj().getValue(key).str() }.getOrNull() ?: continue
            if (!Regex("(raw|matched)/[a-z0-9_]+\\.wav").matches(path) || path in retained) continue
            val file = File(root, path).canonicalFile
            if (file.toPath().startsWith(root.canonicalFile.toPath()) && file.isFile) check(file.delete())
        }
    }
    private fun readme() = """
        # Relay pulse in actual playing

        Nine actual model-2 performances: one bassline, two chord voicings and a held texture,
        each at PULSE 0, 0.5 and 1. The finite examples use native excitation velocities 0.35,
        0.65 and 1, preserve every complete natural release, and share one summing gain across
        all three depths in their family. There are no effects or pitch-shifted recordings.

        Finite playback is 90 BPM, aligning two of the model's 3 Hz pulse cycles per beat.
        The clock still belongs to each complete note and restarts at note-on; this is not a
        shared live tempo bus. Per-note recipes and actual timings document what played.
        The held texture repeats three exact copies of the real fitted engine loop. Its
        seam is measured against the actual preceding cycle, not another copy of itself.

        Raw preserves native touch and energy before engine normalization. Matched uses
        the existing loudest-200ms target 0.03 and peak guard 0.99. Every WAV, source and
        recipe is fingerprinted in manifest.json. Source 4x peaks precede periodic gain
        and summing; mixed performances have per-note source trajectories rather than
        one invented mixed source-weight graph. The serialized accent score is inactive.

        These musical examples have not been heard or approved by the owner. The earlier
        approval applies to the retained model-1 bank, which this generator does not edit.
        Physical-device playability is unverified. Numerical checks are not listening claims.

        Regenerate: ./gradlew :synth:generateRelayPulseMusicalAudition
    """.trimIndent() + "\n"

    private fun page(manifest: String): String = """
        <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Relay · pulse in actual playing</title><style>
        :root{color-scheme:dark}body{font:16px/1.5 system-ui,sans-serif;background:#111616;color:#f1f4ea;margin:0;padding:clamp(1rem,5vw,4rem)}main{max-width:1100px;margin:auto}h1{font-size:clamp(2rem,7vw,4rem);line-height:1.1}.lead{max-width:760px;color:#b9c7be}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,300px),1fr));gap:1rem}article{background:#192020;border:1px solid #384440;padding:1.2rem;border-radius:6px;min-width:0}audio{width:100%}button,select{font:inherit;background:#222b2a;color:inherit;padding:.6rem;border:1px solid #788b7f;min-height:44px}a{color:#ddfb76;margin-right:1rem}h2{font-size:1.15rem}.status{color:#b9c7be;font-size:.9rem}.row{display:flex;gap:1rem;align-items:center;flex-wrap:wrap;margin-block:1.5rem}:focus-visible{outline:3px solid #ddfb76;outline-offset:3px}
        </style><main><p>SnipSnap / Relay / model 2</p><h1>Pulse in actual playing.</h1><p class="lead">The same bassline, chord voicings and held evolving texture at PULSE 0, 0.5 and 1. The source histories continue through the pulse. These new musical examples await listening; saved model-1 sounds remain separate.</p><div class="row"><label>Playback level <select id="level"><option value="matched">Matched</option><option value="raw">Raw</option></select></label><a href="manifest.json" download>Render manifest</a><a href="README.md" download>Performance notes</a></div><div id="clips" class="grid"></div><p class="status">Native note velocities and complete releases are retained. Held clips contain three actual engine loop regions. Repeat playback in this page is not an additional HOLD claim.</p></main>
        <script id="manifest" type="application/json">${manifest.replace("</", "<\\/")}</script><script>
        'use strict';const data=JSON.parse(document.getElementById('manifest').textContent),level=document.getElementById('level'),clips=document.getElementById('clips');
        const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
        const path=(c,k)=>c[k+'Path']+'?v='+c[k+'Sha256'];
        function render(){clips.innerHTML=data.clips.map(c=>'<article><h2>'+esc(c.title)+'</h2><p>'+esc(c.patchName)+' · '+Number(c.seconds).toFixed(1)+' seconds</p><audio controls preload="none" src="'+esc(path(c,level.value))+'"></audio><p><a download href="'+esc(path(c,'matched'))+'">Matched WAV</a><a download href="'+esc(path(c,'raw'))+'">Raw WAV</a></p></article>').join('');clips.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>clips.querySelectorAll('audio').forEach(b=>{if(b!==a)b.pause()})))}level.addEventListener('change',render);render();
        </script></html>
    """.trimIndent() + "\n"

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        is FloatArray -> JsonValue.Arr(value.map(::json))
        is DoubleArray -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported Relay musical manifest value ${value.javaClass}")
    }
}
