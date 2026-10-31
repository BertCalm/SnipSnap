package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Four unlevelled performance snapshots, independent of the full SKITTER audition generator. */
object SkitterArticulationAudition {
    private const val RATE = 44_100
    private const val QUIET = .0001 // -80 dBFS; no powered note is shortened to meet the site budget.
    private const val TAIL_ALLOWANCE = 882 // 20 ms after the last sample above QUIET.
    private const val GAP = 4_410 // 100 ms between cue groups.
    private const val REPEAT_GAP = 2_646 // 60 ms after each complete, quiet-ended retrigger.
    private const val CEILING = .85
    private val common = linkedMapOf("BUZZ" to .55f, "SKITTER" to .50f, "AGITATE" to .60f,
        "VENT" to .35f, "STATIC" to .15f, "HOLD" to 0f)
    private val kinds = listOf(
        Kind("soft", "Soft · velocity .25", .25f, 0f),
        Kind("medium", "Medium · velocity .60", .60f, 0f),
        Kind("hard", "Hard · velocity 1.00", 1f, 0f),
        Kind("sustain", "Long finite sustain · HOLD .75", .60f, .75f),
    )
    private data class Kind(val id: String, val label: String, val velocity: Float, val hold: Float)
    private data class Source(val kind: Kind, val report: SkitterRender, val pcm: ByteArray, val quietEnd: Int)
    private data class Event(val on: Int, val source: Source, val copiedFrames: Int)
    private data class Cue(val id: String, val label: String, val start: Int, val end: Int,
                           val velocity: Float?, val hold: Float?, val events: List<Event>)
    private data class Stats(val peak: Double, val rms: Double, val dc: Double) {
        fun json() = obj("peak" to num(peak), "rms" to num(rms), "dc" to num(dc))
    }
    private data class Track(val voice: SkitterVoice, val revision: String, val sources: Map<String, Source>,
                             val events: List<Event>, val cues: List<Cue>, val raw: FloatArray,
                             val file: File, val pcm: FloatArray, val stats: Stats, val compressedBytes: Int) {
        val id: String get() = voice.name.lowercase(Locale.ROOT) + "-" + revision.lowercase(Locale.ROOT)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 &&
            args.count { it.startsWith("--source-commit=") } <= 1 &&
            args.all { !it.startsWith("--") || it.startsWith("--source-commit=") }) {
            "supply one output directory and optional --source-commit=<base commit>"
        }
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/skitter-articulation").also { it.mkdirs() }
        val assets = File(root, "r5").also { it.mkdirs() }
        val sourceCommit = args.firstOrNull { it.startsWith("--source-commit=") }?.substringAfter('=') ?: "b429ec2c"
        require(sourceCommit.isNotBlank()) { "--source-commit must identify the source base" }
        val sourceFile = File("src/main/kotlin/com/snipsnap/synth/Skitter.kt")
        val generatorFile = File("src/test/kotlin/com/snipsnap/synth/SkitterArticulationAudition.kt")
        require(sourceFile.isFile && generatorFile.isFile) { "run from the synth project directory so source hashes identify the rendered code" }
        val sourceJson = obj("baseCommit" to str(sourceCommit), "skitterSourceSha256" to str(sha(sourceFile.readBytes())),
            "generatorSourceSha256" to str(sha(generatorFile.readBytes())),
            "note" to str("The commit identifies the source base; the SHA256 identifies the actual local source rendered on this runtime."))

        // Render each distinct note once. Retriggers deliberately reset Circuit and reuse the same seed.
        val inputs = linkedMapOf<Pair<SkitterVoice, String>, Map<String, Source>>()
        for (voice in listOf(SkitterVoice.LEAD, SkitterVoice.HOLLOW)) for (revision in listOf("R4", "R5")) {
            inputs[voice to revision] = kinds.associate { kind ->
                val macros = common + ("HOLD" to kind.hold)
                val report = Skitter.renderProbe(voice, macros, midi = 48, velocity = kind.velocity,
                    seed = 0, seconds = null, oversample = 4,
                    probe = SkitterProbe(articulationDisabled = revision == "R4", bodyDisabled = true))
                require(report.loopStart == -1) { "this proof requires finite notes, not periodic HOLD loops" }
                require(report.raw.isNotEmpty() && report.raw.all { it.isFinite() } && stats(report.raw).peak <= 1.0) {
                    "$voice/$revision/${kind.id}: raw PCM24 would be invalid or clip"
                }
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, Snip(report.raw, 1, RATE), WavWriter.BitDepth.PCM_24) }.toByteArray()
                val lastActive = report.raw.indexOfLast { abs(it.toDouble()) > QUIET }
                val quietEnd = (lastActive + 1 + TAIL_ALLOWANCE).coerceIn(1, report.raw.size)
                println("$voice $revision ${kind.id}: ${report.raw.size} frames; quiet tail ends at $quietEnd")
                kind.id to Source(kind, report, dataBytes(encoded), quietEnd)
            }
        }
        // All four takes share cue boundaries. Only a common quiet tail can be removed.
        val lengths = kinds.associate { kind -> kind.id to inputs.values.maxOf { it.getValue(kind.id).quietEnd } }
        val tracks = inputs.map { (key, sources) ->
            val (voice, revision) = key
            val events = mutableListOf<Event>()
            val cues = mutableListOf<Cue>()
            var position = GAP
            fun event(id: String): Event {
                val source = sources.getValue(id)
                return Event(position, source, minOf(lengths.getValue(id), source.report.raw.size)).also {
                    events += it
                    position += lengths.getValue(id)
                }
            }
            for (kind in kinds.take(3)) {
                val start = position
                val e = event(kind.id)
                cues += Cue(kind.id, kind.label, start, position, kind.velocity, kind.hold, listOf(e))
                position += GAP
            }
            val repeatStart = position
            val repeats = (0..2).map { index -> event("medium").also { if (index < 2) position += REPEAT_GAP } }
            cues += Cue("repeats", "Three medium retriggers · reset between notes", repeatStart, position, .60f, 0f, repeats)
            position += GAP
            val sustainStart = position
            val sustain = event("sustain")
            cues += Cue("sustain", kinds.last().label, sustainStart, position, .60f, .75f, listOf(sustain))
            position += GAP
            cues += Cue("take", "Complete performance", 0, position, null, null, events.toList())
            val raw = FloatArray(position)
            for (e in events) e.source.report.raw.copyInto(raw, e.on, 0, e.copiedFrames)
            val id = voice.name.lowercase(Locale.ROOT) + "-" + revision.lowercase(Locale.ROOT)
            val file = File(assets, "$id-raw.wav")
            WavWriter.write(file, Snip(raw, 1, RATE), WavWriter.BitDepth.PCM_24, smpl = SmplChunk(48))
            val pcm = WavReader.read(file).samples
            Track(voice, revision, sources, events, cues, raw, file, pcm, stats(pcm), compressedSize(file.readBytes()))
        }
        require(tracks.map { it.pcm.size }.distinct().size == 1) { "paired takes must have identical frame counts" }
        val fixedGain = CEILING / tracks.maxOf { it.stats.peak }.coerceAtLeast(1e-20)
        val targets = tracks.first().cues.associate { cue ->
            cue.id to minOf(.125, tracks.minOf { track ->
                val c = track.cues.first { it.id == cue.id }
                val s = stats(track.pcm, c.start, c.end)
                CEILING * s.rms / s.peak.coerceAtLeast(1e-20)
            })
        }
        fun eventJson(e: Event) = obj("onFrame" to num(e.on), "sourceId" to str(e.source.kind.id),
            "velocity" to num(e.source.kind.velocity), "hold" to num(e.source.kind.hold),
            "sourceFrames" to num(e.source.report.raw.size), "copiedFrames" to num(e.copiedFrames),
            "copiedPcmSha256" to str(sha(e.source.pcm.copyOfRange(0, e.copiedFrames * 3))))
        fun cueJson(track: Track, cue: Cue): JsonValue {
            val samples = track.pcm.copyOfRange(cue.start, cue.end)
            val s = stats(samples)
            val gain = targets.getValue(cue.id) / s.rms.coerceAtLeast(1e-20)
            require((s.peak * gain).isFinite() && s.peak * gain <= CEILING + 1e-10) { "matched cue exceeds the shared peak budget" }
            val pitch = Pitch.detect(Snip(samples, 1, RATE), .35f, .25f)
            val cents = pitch?.let { 1200.0 * ln(it.hz / Keys.midiHz(48).toDouble()) / ln(2.0) }
            return obj("id" to str(cue.id), "label" to str(cue.label), "startFrame" to num(cue.start),
                "endFrame" to num(cue.end), "velocity" to nullable(cue.velocity?.toDouble()), "hold" to nullable(cue.hold?.toDouble()),
                "stats" to s.json(), "matchedGain" to num(gain), "matchedTargetRms" to num(targets.getValue(cue.id)),
                "pitchHz" to nullable(pitch?.hz?.toDouble()), "pitchCents" to nullable(cents),
                "pitchConfidence" to nullable(pitch?.confidence?.toDouble()), "noteEvents" to arr(cue.events.map(::eventJson)))
        }
        fun trackJson(track: Track): JsonValue {
            val recordPath = "r5/${track.id}-render.json"
            val probe = obj("memoryFrozen" to bool(false), "agitationDisabled" to bool(false),
                "equalRecovery" to bool(false), "noiseForcingDisabled" to bool(false),
                "articulationDisabled" to bool(track.revision == "R4"), "contactMemoryDisabled" to bool(false),
                "bodyDisabled" to bool(true))
            val record = obj("trackId" to str(track.id), "revision" to str(track.revision), "source" to sourceJson,
                "renderSettings" to obj("voice" to str(track.voice.name), "midi" to num(48), "seed" to num(0),
                    "oversample" to num(4), "seconds" to JsonValue.Null, "probe" to probe),
                "sourceNotes" to arr(track.sources.values.map { source ->
                    val r = source.report; val d = r.diagnostics
                    val copied = minOf(lengths.getValue(source.kind.id), r.raw.size)
                    obj("id" to str(source.kind.id), "velocity" to num(source.kind.velocity),
                        "macros" to JsonValue.Obj((common + ("HOLD" to source.kind.hold)).mapValues { num(it.value) }),
                        "rawFrames" to num(r.raw.size), "copiedFrames" to num(copied), "quietEndFrame" to num(source.quietEnd),
                        "rawStats" to stats(r.raw).json(), "rawPcmSha256" to str(sha(source.pcm)),
                        "copiedPcmSha256" to str(sha(source.pcm.copyOfRange(0, copied * 3))),
                        "removedTailPeak" to num(if (copied < r.raw.size) stats(r.raw, copied, r.raw.size).peak else 0.0),
                        "gestureCount" to num(d.gestureCount), "boundHits" to num(d.boundHits),
                        "historyPeak" to num(d.historyPeak), "agitationPeak" to num(d.agitationPeak),
                        "renderMillis" to num(d.renderMillis),
                        "traceColumns" to arr(listOf("seconds", "energyHistory", "agitation", "contact", "oscillatorBGain").map(::str)),
                        // The 20 ms trace explains events; audio and the separate focused tests establish the result.
                        "trace" to arr(r.trace.filterIndexed { index, _ -> index % 4 == 0 || index == r.trace.lastIndex }.map { t ->
                            arr(listOf(t.seconds, t.energyHistory, t.agitation, t.contact, t.oscillatorBGain).map(::num))
                        }))
                }), "events" to arr(track.events.map(::eventJson)),
                "outputTreatment" to str("Unlevelled raw output, no fade, no per-note gain, no overlap. Only quiet tail removal and silence insertion; PCM24 through WavWriter, no true-peak option."))
            File(root, recordPath).writeText(Json.write(record) + "\n")
            return obj("id" to str(track.id), "revision" to str(track.revision),
                "label" to str(if (track.revision == "R4") "R4 source on current runtime" else "R5 articulation candidate"),
                "file" to str("r5/${track.file.name}"), "sha256" to str(sha(track.file.readBytes())),
                "frames" to num(track.pcm.size), "seconds" to num(track.pcm.size.toDouble() / RATE),
                "stats" to track.stats.json(), "renderRecord" to str(recordPath), "cues" to arr(track.cues.map { cueJson(track, it) }))
        }
        val manifest = obj("schemaVersion" to num(1), "revision" to str("R5"), "sampleRate" to num(RATE),
            "channels" to num(1), "bitDepth" to num(24), "fixedGain" to num(fixedGain), "peakCeiling" to num(CEILING),
            "source" to sourceJson, "common" to obj("midi" to num(48), "note" to str("C3"), "seed" to num(0),
                "oversample" to num(4), "macros" to JsonValue.Obj(common.mapValues { num(it.value) }), "sustainHold" to num(.75)),
            "timing" to obj("quietThreshold" to num(QUIET), "tailAllowanceFrames" to num(TAIL_ALLOWANCE),
                "groupGapFrames" to num(GAP), "repeatGapFrames" to num(REPEAT_GAP),
                "cueEndConvention" to str("exclusive"), "cueTimeBaseHz" to num(RATE),
                "notePolicy" to str("Every note starts a fresh Circuit with seed 0. Three medium retriggers reuse that same raw render with quiet tails and no overlap. Memory is exercised within each note, not shared between retriggers.")),
            "cueOrder" to arr(listOf("soft", "medium", "hard", "repeats", "sustain", "take").map(::str)),
            "voices" to arr(listOf(SkitterVoice.LEAD, SkitterVoice.HOLLOW).map { voice ->
                obj("id" to str(voice.name.lowercase(Locale.ROOT)), "name" to str(voice.name),
                    "expectation" to str(if (voice == SkitterVoice.LEAD) "Listen for a quick tear at contact, then recovery into the body." else "Listen for the upper voice to bloom after the touch, then settle into the hollow body."),
                    "tracks" to arr(tracks.filter { it.voice == voice }.map(::trackJson)))
            }),
            "compressedAudioBytes" to num(tracks.sumOf { it.compressedBytes }),
            "notes" to arr(listOf(
                "Proposed articulation, not an owner listening verdict. Pitch and mono compatibility remain requirements.",
                "R4 here is the original source path on the current runtime, selected with articulationDisabled. The published R4 archive is retained separately without regenerating its recordings.",
                "Fixed gain is one shared gain across all four raw tracks and all velocity cues, set by the loudest raw sample. It preserves their relative level response.",
                "Cue-matched mode applies one static gain to the complete selected cue or take. It hides level response and never separately levels repeated notes.",
                "The long sustain is finite HOLD .75, with the engine's natural gate and release. It is not a periodic HOLD loop.",
                "All cue indices use the original 44100 Hz clock; convert indices to seconds even if the browser resamples its decoded AudioBuffer.",
                "Downloads contain the original unlevelled mono PCM24 WAV, unaffected by browser gain or cue selection. Source-note PCM hashes allow each copied event to be checked.",
                "Pitch estimates are supporting diagnostics at the known C3 root; the owner judges articulation and usefulness by listening."
            ).map(::str)))
        val json = Json.write(manifest)
        File(assets, "manifest.json").writeText(json + "\n")
        val template = checkNotNull(javaClass.getResourceAsStream("/audition/skitter-articulation.html")) { "missing articulation page template" }
            .bufferedReader().use { it.readText() }
        require(template.countMarker("/* SKITTER_ARTICULATION_MANIFEST */null") == 1) { "template needs one manifest marker" }
        File(root, "index.html").writeText(template.replace("/* SKITTER_ARTICULATION_MANIFEST */null", json.replace("</", "<\\/")))
        // Account for ordinary JSON/HTML and losslessly compressed PCM, with slack for tar headers.
        val nonAudioBytes = root.walkTopDown().filter { it.isFile && it.extension != "wav" }.sumOf { it.length() }
        val estimatedAddedBytes = tracks.sumOf { it.compressedBytes.toLong() } + nonAudioBytes + 65_536
        require(estimatedAddedBytes <= 8_000_000) {
            "proof transport estimate $estimatedAddedBytes exceeds the 8 MB allowance; preserve audible tails and reduce trace/padding before publication"
        }
        println("R5 proof: four raw tracks; ${tracks.first().pcm.size.toDouble() / RATE}s each; fixed gain=$fixedGain")
        println("Lossless audio=${tracks.sumOf { it.compressedBytes }} bytes; nonaudio=$nonAudioBytes; added estimate=$estimatedAddedBytes. Final assembled tar must still be checked against 256 MiB.")
    }

    private fun stats(samples: FloatArray, start: Int = 0, end: Int = samples.size): Stats {
        require(end > start)
        var peak = 0.0; var sum = 0.0; var squares = 0.0
        for (i in start until end) { val x = samples[i].toDouble(); peak = maxOf(peak, abs(x)); sum += x; squares += x*x }
        val n = (end - start).toDouble()
        return Stats(peak, sqrt(squares/n), sum/n)
    }
    private fun chunk(bytes: ByteArray, tag: String): Pair<Int, Int> {
        var at = 12
        while (at + 8 <= bytes.size) {
            val length = le32(bytes, at + 4)
            require(length >= 0 && at.toLong() + 8 + length <= bytes.size) { "invalid WAV chunk" }
            if (String(bytes, at, 4, Charsets.US_ASCII) == tag) return at + 8 to length
            at += 8 + length + (length and 1)
        }
        error("missing WAV $tag chunk")
    }
    private fun dataBytes(bytes: ByteArray): ByteArray { val (at, n) = chunk(bytes, "data"); return bytes.copyOfRange(at, at+n) }
    private fun le32(bytes: ByteArray, i: Int): Int = (0..3).fold(0) { n, k -> n or ((bytes[i+k].toInt() and 255) shl (8*k)) }
    private fun compressedSize(wav: ByteArray): Int {
        val bytes = wav.copyOf(); val (at, n) = chunk(bytes, "data")
        require(n % 3 == 0)
        var previous = 0; var previous2 = 0
        for (i in at until at+n step 3) {
            val x = (bytes[i].toInt() and 255) or ((bytes[i+1].toInt() and 255) shl 8) or ((bytes[i+2].toInt() and 255) shl 16)
            val d = (x - 2*previous + previous2) and 0xffffff
            bytes[i] = d.toByte(); bytes[i+1] = (d ushr 8).toByte(); bytes[i+2] = (d ushr 16).toByte()
            previous2 = previous; previous = x
        }
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.size()
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun String.countMarker(marker: String) = windowed(marker.length).count { it == marker }
    private fun obj(vararg pairs: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*pairs))
    private fun arr(values: List<JsonValue>) = JsonValue.Arr(values)
    private fun str(value: String) = JsonValue.Str(value)
    private fun bool(value: Boolean) = JsonValue.Bool(value)
    private fun num(value: Number): JsonValue { require(value.toDouble().isFinite()); return JsonValue.Num(value.toDouble()) }
    private fun nullable(value: Double?) = value?.let(::num) ?: JsonValue.Null
}
