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

/**
 * Adds two R6 performance WAVs to a frozen R5 proof. R4/R5 are read and hash-checked,
 * never rendered again. The host retains their original files and cue positions.
 */
object SkitterCharacterR6Audition {
    private const val RATE = 44_100
    private const val QUIET = .0001
    private const val TAIL_ALLOWANCE = 882
    private const val GAP = 4_410
    private const val CEILING = .80
    private val macros = linkedMapOf("BUZZ" to .55f, "SKITTER" to .50f, "AGITATE" to .60f,
        "VENT" to .35f, "STATIC" to .15f, "HOLD" to 0f)
    private val velocities = linkedMapOf("soft" to .25f, "medium" to .60f, "hard" to 1f)
    private val voices = listOf(SkitterVoice.LEAD, SkitterVoice.HOLLOW)
    private data class Source(val id: String, val velocity: Float, val render: SkitterRender, val quietEnd: Int)
    private data class Take(val voice: SkitterVoice, val revision: String, val fields: MutableMap<String, JsonValue>,
                            val samples: FloatArray, val cues: List<MutableMap<String, JsonValue>>)
    private data class Stats(val peak: Double, val rms: Double, val dc: Double) {
        fun json() = obj("peak" to num(peak), "rms" to num(rms), "dc" to num(dc))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.count { !it.startsWith("--") } <= 1 && args.count { it.startsWith("--baseline=") } == 1 &&
            args.count { it.startsWith("--source-commit=") } <= 1 &&
            args.all { !it.startsWith("--") || it.startsWith("--baseline=") || it.startsWith("--source-commit=") }) {
            "supply one output directory, --baseline=<raw frozen R5 proof>, optional --source-commit=<source base>"
        }
        val baseline = File(args.single { it.startsWith("--baseline=") }.substringAfter('=')).canonicalFile
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/skitter-character-r6").canonicalFile
        require(!root.toPath().startsWith(baseline.toPath()) && !baseline.toPath().startsWith(root.toPath())) { "output must be separate from the frozen baseline" }
        val assets = File(root, "r6").also { it.mkdirs() }
        val baselineFile = File(baseline, "r5/manifest.json")
        val frozen = Json.parse(baselineFile.readText()).obj()
        require(frozen.getValue("revision").str() == "R5" && frozen.getValue("sampleRate").int() == RATE &&
            frozen.getValue("channels").int() == 1 && frozen.getValue("bitDepth").int() == 24) { "baseline must be the original raw R5 proof" }
        val expectedMacros = macros.mapValues { num(it.value) }
        val baselineCommon = frozen.getValue("common").obj()
        require(baselineCommon.getValue("macros").obj() == expectedMacros && baselineCommon.getValue("midi").int() == 48 &&
            baselineCommon.getValue("seed").int() == 0 && baselineCommon.getValue("oversample").int() == 4) { "baseline inputs differ from the controlled R6 proof" }
        val sourceFile = File("src/main/kotlin/com/snipsnap/synth/Skitter.kt")
        val generatorFile = File("src/test/kotlin/com/snipsnap/synth/SkitterCharacterR6Audition.kt")
        require(sourceFile.isFile && generatorFile.isFile) { "run from the synth project directory to record actual source hashes" }
        val source = obj("baseCommit" to str(args.firstOrNull { it.startsWith("--source-commit=") }?.substringAfter('=') ?: "b8ae2b958057080ad2d06238d4a2e1bf4c618e22"),
            "skitterSourceSha256" to str(sha(sourceFile.readBytes())), "generatorSourceSha256" to str(sha(generatorFile.readBytes())),
            "baselineManifestSha256" to str(sha(baselineFile.readBytes())),
            "note" to str("Source base plus actual rendered source SHA256. R4/R5 audio is imported from the frozen R5 artifact, not rerendered."))
        val takes = mutableListOf<Take>()

        // Keep the immutable baseline's exact WAV bytes, metadata, and absolute cue positions.
        for (voice in frozen.getValue("voices").arr()) {
            val v = voice.obj()
            val kind = SkitterVoice.valueOf(v.getValue("name").str())
            require(kind in voices)
            for (track in v.getValue("tracks").arr()) {
                val t = track.obj()
                val revision = t.getValue("revision").str()
                require(revision == "R4" || revision == "R5")
                val path = File(baseline, t.getValue("file").str()).canonicalFile
                require(path.toPath().startsWith(baseline.toPath()) && path.extension == "wav") { "baseline must supply ordinary raw WAV files within its directory" }
                require(sha(path.readBytes()) == t.getValue("sha256").str()) { "frozen baseline WAV hash mismatch: $path" }
                val snip = WavReader.read(path)
                require(snip.channels == 1 && snip.sampleRate == RATE && snip.frameCount == t.getValue("frames").int())
                val cues = velocities.map { (id, velocity) ->
                    val c = t.getValue("cues").arr().single { it.obj().getValue("id").str() == id }.obj()
                    require(c.getValue("velocity").num() == velocity.toDouble() && c.getValue("hold").num() == 0.0)
                    LinkedHashMap(c)
                }
                takes += Take(kind, revision, LinkedHashMap(t).also {
                    it["snapshot"] = bool(true)
                    it["recordingSource"] = str("Frozen R5 proof recording; original PCM24 bytes and original cue positions")
                    it["label"] = str(if (revision == "R4") "R4 source · frozen R5 proof" else "R5 articulation · frozen proof")
                }, snip.samples, cues)
            }
        }
        require(takes.size == 4 && takes.map { it.voice to it.revision }.toSet().size == 4) { "expected one frozen R4/R5 take for each of LEAD and HOLLOW" }

        // Full finite notes: no seconds override, no powered-tail cut, no attack or output fade.
        val rendered = voices.associateWith { voice -> velocities.map { (id, velocity) ->
            val r = Skitter.renderProbe(voice, macros, midi = 48, velocity = velocity, seed = 0, seconds = null, oversample = 4)
            require(r.loopStart == -1 && r.raw.isNotEmpty() && r.raw.all { it.isFinite() } && stats(r.raw).peak <= 1.0) { "$voice/$id invalid raw PCM24" }
            val last = r.raw.indexOfLast { abs(it.toDouble()) > QUIET }
            Source(id, velocity, r, (last + 1 + TAIL_ALLOWANCE).coerceIn(1, r.raw.size))
        } }
        // R6 notes share durations. Baseline bounds stay untouched, even if R6 needs a longer quiet tail.
        val durations = velocities.keys.associateWith { id -> maxOf(
            takes.maxOf { take -> take.cues.single { it.getValue("id").str() == id }.let { it.getValue("endFrame").int() - it.getValue("startFrame").int() } },
            rendered.values.maxOf { list -> list.single { it.id == id }.quietEnd }) }
        var compressedAudioBytes = 0L
        for (voice in voices) {
            val notes = rendered.getValue(voice)
            var cursor = GAP
            val cues = notes.map { note ->
                val start = cursor
                cursor += durations.getValue(note.id)
                linkedMapOf<String, JsonValue>("id" to str(note.id), "label" to str(note.id.replaceFirstChar { it.titlecase() } + " · velocity " + note.velocity),
                    "startFrame" to num(start), "endFrame" to num(cursor), "velocity" to num(note.velocity), "hold" to num(0)).also { cursor += GAP }
            }
            val raw = FloatArray(cursor)
            val events = notes.mapIndexed { index, note ->
                val start = cues[index].getValue("startFrame").int()
                val copied = minOf(note.render.raw.size, durations.getValue(note.id))
                note.render.raw.copyInto(raw, start, 0, copied)
                obj("sourceId" to str(note.id), "onFrame" to num(start), "copiedFrames" to num(copied),
                    "sourceFrames" to num(note.render.raw.size), "velocity" to num(note.velocity),
                    "copiedPcmSha256" to str(pcmHash(note.render.raw.copyOf(copied))),
                    "removedTailPeak" to num(if (copied < note.render.raw.size) stats(note.render.raw, copied, note.render.raw.size).peak else 0.0))
            }
            val id = voice.name.lowercase(Locale.ROOT) + "-r6"
            val file = File(assets, "$id-raw.wav")
            WavWriter.write(file, Snip(raw, 1, RATE), WavWriter.BitDepth.PCM_24, smpl = SmplChunk(48))
            val pcm = WavReader.read(file).samples
            val compressed = compressedSize(file.readBytes())
            compressedAudioBytes += compressed
            val recordPath = "r6/$id-render.json"
            val record = obj("trackId" to str(id), "revision" to str("R6"), "source" to source,
                "renderSettings" to obj("voice" to str(voice.name), "macros" to JsonValue.Obj(expectedMacros), "midi" to num(48),
                    "seed" to num(0), "oversample" to num(4), "seconds" to JsonValue.Null,
                    "probe" to obj("memoryFrozen" to bool(false), "agitationDisabled" to bool(false), "equalRecovery" to bool(false),
                        "noiseForcingDisabled" to bool(false), "articulationDisabled" to bool(false), "contactMemoryDisabled" to bool(false), "bodyDisabled" to bool(false))),
                "events" to arr(events),
                "sourceNotes" to arr(notes.map { note ->
                    val r = note.render
                    obj("id" to str(note.id), "velocity" to num(note.velocity), "rawFrames" to num(r.raw.size),
                        "rawPcmSha256" to str(pcmHash(r.raw)), "quietEndFrame" to num(note.quietEnd), "rawStats" to stats(r.raw).json(),
                        "gestureCount" to num(r.diagnostics.gestureCount), "boundHits" to num(r.diagnostics.boundHits),
                        "renderMillis" to num(r.diagnostics.renderMillis),
                        "traceColumns" to arr(listOf("seconds", "energyHistory", "agitation", "contact").map(::str)),
                        "trace" to arr(r.trace.filterIndexed { index, _ -> index % 4 == 0 || index == r.trace.lastIndex }.map { t ->
                            arr(listOf(t.seconds, t.energyHistory, t.agitation, t.contact).map(::num)) }))
                }), "outputTreatment" to str("Original raw output. No house level matching, attack fade, output fade or per-note gain; quiet tail removal and silent padding only."))
            File(root, recordPath).writeText(Json.write(record) + "\n")
            takes += Take(voice, "R6", linkedMapOf("id" to str(id), "revision" to str("R6"), "label" to str("R6 body candidate"),
                "file" to str("r6/${file.name}"), "sha256" to str(sha(file.readBytes())), "frames" to num(pcm.size),
                "seconds" to num(pcm.size.toDouble()/RATE), "stats" to stats(pcm).json(), "renderRecord" to str(recordPath), "snapshot" to bool(false)), pcm, cues)
            println("$id: ${pcm.size} frames / ${pcm.size.toDouble()/RATE}s / $compressed lossless bytes")
        }

        // One shared fixed gain across all eighteen voice/revision/velocity cues. Optional matching
        // uses each complete note's RMS; changing playback position never recomputes that gain.
        val cueStats = takes.associate { take -> (take.voice to take.revision) to take.cues.associate { c -> c.getValue("id").str() to
            stats(take.samples, c.getValue("startFrame").int(), c.getValue("endFrame").int()) } }
        val fixedGain = CEILING / cueStats.values.maxOf { byCue -> byCue.values.maxOf { it.peak } }.coerceAtLeast(1e-20)
        val targets = velocities.keys.associateWith { id -> minOf(.125, cueStats.values.minOf { byCue ->
            val s = byCue.getValue(id); CEILING*s.rms/s.peak.coerceAtLeast(1e-20) }) }
        for (take in takes) {
            for (cue in take.cues) {
                val id = cue.getValue("id").str(); val s = cueStats.getValue(take.voice to take.revision).getValue(id)
                val gain = targets.getValue(id)/s.rms.coerceAtLeast(1e-20)
                require(s.peak*gain <= CEILING+1e-10)
                cue["stats"] = s.json(); cue["matchedGain"] = num(gain); cue["matchedTargetRms"] = num(targets.getValue(id))
                if (take.revision == "R6") {
                    val samples = take.samples.copyOfRange(cue.getValue("startFrame").int(), cue.getValue("endFrame").int())
                    val pitch = Pitch.detect(Snip(samples, 1, RATE), .35f, .25f)
                    cue["pitchHz"] = nullable(pitch?.hz?.toDouble()); cue["pitchConfidence"] = nullable(pitch?.confidence?.toDouble())
                    cue["pitchCents"] = nullable(pitch?.let { 1200.0*ln(it.hz/Keys.midiHz(48).toDouble())/ln(2.0) })
                }
            }
            take.fields["cues"] = arr(take.cues.map { JsonValue.Obj(it) })
        }
        val manifest = obj("schemaVersion" to num(1), "revision" to str("R6"), "sampleRate" to num(RATE),
            "channels" to num(1), "bitDepth" to num(24), "fixedGain" to num(fixedGain), "peakCeiling" to num(CEILING),
            "source" to source, "baselineSource" to frozen.getValue("source"),
            "common" to obj("midi" to num(48), "note" to str("C3"), "seed" to num(0), "oversample" to num(4), "macros" to JsonValue.Obj(expectedMacros)),
            "cueOrder" to arr(velocities.keys.map(::str)), "crossfadeSeconds" to num(.020),
            "timing" to obj("sampleClockHz" to num(RATE), "quietThreshold" to num(QUIET), "tailAllowanceFrames" to num(TAIL_ALLOWANCE),
                "groupGapFrames" to num(GAP), "cueEndConvention" to str("exclusive")),
            "voices" to arr(voices.map { voice -> obj("id" to str(voice.name.lowercase(Locale.ROOT)), "name" to str(voice.name),
                "caption" to str(if (voice == SkitterVoice.LEAD) "Formant bite" else "Hollow reed"),
                "expectation" to str(if (voice == SkitterVoice.LEAD) "Proposed root-locked formant body: compare the held color as well as the attack." else "Proposed protected carrier and octave-driven phase modulation: compare the hollow body after contact."),
                "tracks" to arr(listOf("R4", "R5", "R6").map { revision -> JsonValue.Obj(takes.single { it.voice == voice && it.revision == revision }.fields) })) }),
            "compressedAudioBytes" to num(compressedAudioBytes),
            "notes" to arr(listOf(
                "R6 is a proposed body change. No owner listening acceptance is claimed.",
                "R4 and R5 use the exact frozen R5 proof WAVs and their original cue offsets. The earlier published R4 full archive and R5 proof remain accessible.",
                "Default playback is a complete hard finite note. Restart touch returns to its beginning; revision switching preserves cue-relative time.",
                "All three revision sources run on one clock. Revision switches use a 20 ms linear crossfade; weights sum to one. Shorter quiet-ended notes become silence, not a cut to the longer note.",
                "Fixed gain is shared across all eighteen voice/revision/velocity cues. Optional matching applies one static complete-note gain, never a separate gain for attack or body.",
                "Source notes start from a fresh Circuit with MIDI48, velocity .25/.60/1, seed0, the same five timbral controls and HOLD0. Natural gates and releases are retained.",
                "Original raw PCM24 downloads have no browser gain or transition applied. Cue indices use the original 44100 Hz clock regardless of browser resampling.",
                "Quiet trimming is allowed only after the last sample above -80 dBFS plus20ms. No active tail is shortened for the archive budget."
            ).map(::str)))
        val json = Json.write(manifest)
        File(assets, "manifest.json").writeText(json+"\n")
        val template = javaClass.getResourceAsStream("/audition/skitter-character-r6.html")?.bufferedReader()?.use { it.readText() }
        if (template != null) {
            val marker = "/* SKITTER_R6_MANIFEST */null"
            require(template.indexOf(marker) >= 0 && template.indexOf(marker) == template.lastIndexOf(marker))
            File(root, "index.html").writeText(template.replace(marker, json.replace("</", "<\\/")))
        }
        val nonAudio = root.walkTopDown().filter { it.isFile && it.extension != "wav" }.sumOf { it.length() }
        require(compressedAudioBytes+nonAudio+65_536 <= 2_000_000) { "R6 proof exceeds its 2 MB added-byte target; keep active audio intact and review padding/trace size" }
        println("Two new WAVs only: compressed=$compressedAudioBytes, other=$nonAudio, shared gain=$fixedGain. Verify assembled tar <=256MiB.")
    }

    private fun stats(x: FloatArray, start: Int = 0, end: Int = x.size): Stats {
        require(end > start); var peak=0.0; var sum=0.0; var squares=0.0
        for (i in start until end) { val v=x[i].toDouble(); peak=maxOf(peak,abs(v)); sum+=v; squares+=v*v }
        val n=(end-start).toDouble(); return Stats(peak,sqrt(squares/n),sum/n)
    }
    private fun pcmHash(x: FloatArray): String {
        val wav=ByteArrayOutputStream().also { WavWriter.write(it,Snip(x,1,RATE),WavWriter.BitDepth.PCM_24) }.toByteArray()
        val (at,n)=dataChunk(wav); return sha(wav.copyOfRange(at,at+n))
    }
    private fun dataChunk(b: ByteArray): Pair<Int,Int> {
        var at=12
        while(at+8<=b.size) { val n=(0..3).fold(0) { a,k -> a or ((b[at+4+k].toInt() and 255) shl (8*k)) }; require(n>=0&&at.toLong()+8+n<=b.size)
            if(String(b,at,4,Charsets.US_ASCII)=="data") return at+8 to n; at+=8+n+(n and 1) }
        error("missing WAV data")
    }
    private fun compressedSize(wav: ByteArray): Int {
        val b=wav.copyOf(); val (at,n)=dataChunk(b); require(n%3==0); var p=0;var p2=0
        for(i in at until at+n step 3) { val x=(b[i].toInt() and 255) or ((b[i+1].toInt() and 255) shl 8) or ((b[i+2].toInt() and 255) shl 16)
            val d=(x-2*p+p2) and 0xffffff;b[i]=d.toByte();b[i+1]=(d ushr 8).toByte();b[i+2]=(d ushr 16).toByte();p2=p;p=x }
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(b) } }.size()
    }
    private fun sha(b: ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun obj(vararg p: Pair<String,JsonValue>)=JsonValue.Obj(linkedMapOf(*p))
    private fun arr(v: List<JsonValue>)=JsonValue.Arr(v)
    private fun str(v: String)=JsonValue.Str(v)
    private fun bool(v: Boolean)=JsonValue.Bool(v)
    private fun num(v: Number): JsonValue { require(v.toDouble().isFinite());return JsonValue.Num(v.toDouble()) }
    private fun nullable(v: Double?)=v?.let(::num)?:JsonValue.Null
}
