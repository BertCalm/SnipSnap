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
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Twelve fresh raw tracks compare the exact R7 compatibility path with the R8 depth candidate. */
object SkitterDepthAudition {
    private const val RATE = 44_100
    private const val QUIET = .0001
    private const val TAIL_ALLOWANCE = 882
    private const val GAP = 4_410
    private const val CEILING = .80
    private val macros = linkedMapOf("BUZZ" to .55f, "SKITTER" to .50f, "AGITATE" to .60f,
        "VENT" to .35f, "STATIC" to .15f, "HOLD" to 0f)
    private val velocities = linkedMapOf("soft" to .25f, "medium" to .60f, "hard" to 1f)
    private val voices = SkitterVoice.entries.toList()
    private data class Source(val id: String, val velocity: Float, val render: SkitterRender, val quietEnd: Int)
    private data class Take(val voice: SkitterVoice, val revision: String, val fields: MutableMap<String, JsonValue>,
                            val samples: FloatArray, val cues: List<MutableMap<String, JsonValue>>) {
        val id: String get() = fields.getValue("id").str()
    }
    private data class Stats(val peak: Double, val rms: Double, val dc: Double) {
        fun json() = obj("peak" to num(peak), "rms" to num(rms), "dc" to num(dc))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val options = listOf("--source-commit=", "--archive-url=")
        require(args.count { !it.startsWith("--") } <= 1 && args.count { it.startsWith("--source-commit=") } == 1 &&
            options.all { option -> args.count { it.startsWith(option) } <= 1 } &&
            args.all { arg -> !arg.startsWith("--") || options.any { arg.startsWith(it) } }) {
            "supply an output directory, --source-commit=<full Git SHA>, and optional --archive-url"
        }
        val sourceCommit = args.single { it.startsWith("--source-commit=") }.substringAfter('=')
        require(sourceCommit.matches(Regex("[0-9a-f]{40}"))) { "source commit must be an explicit full Git SHA" }
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/skitter-depth").canonicalFile
        val assets = File(root, "r8").also { it.mkdirs() }
        val archiveUrl = args.firstOrNull { it.startsWith("--archive-url=") }?.substringAfter('=')
            ?: "https://skitter-voices-r7.bertcalm.chatgpt.site"
        require(URI(archiveUrl).let { it.scheme == "https" && !it.host.isNullOrBlank() }) { "archive URL must be an absolute HTTPS URL" }
        val expectedMacros = macros.mapValues { num(it.value) }
        val sourceFile = File("src/main/kotlin/com/snipsnap/synth/Skitter.kt")
        val generatorFile = File("src/test/kotlin/com/snipsnap/synth/SkitterDepthAudition.kt")
        require(sourceFile.isFile && generatorFile.isFile) { "run from synth/ to identify the actual rendered source" }
        val source = obj("baseCommit" to str(sourceCommit), "skitterSourceSha256" to str(sha(sourceFile.readBytes())),
            "generatorSourceSha256" to str(sha(generatorFile.readBytes())),
            "note" to str("Explicit source commit plus the actual local source SHA256. Both R7 compatibility baseline and R8 candidate are freshly rendered on this runtime."))
        val takes = mutableListOf<Take>()

        var compressedAudioBytes = 0L
        for (voice in voices) {
            val rendered = listOf("R7", "R8").associateWith { revision -> velocities.map { (id, velocity) ->
                val r = Skitter.renderProbe(voice, macros, midi = 48, velocity = velocity, seed = 0,
                    // Keep this archived R7/R8 comparison reproducible after the R9 readout revision.
                    seconds = null, oversample = 4, probe = SkitterProbe(depthDisabled = revision == "R7", bassFoundationDisabled = true))
                require(r.loopStart == -1 && r.raw.isNotEmpty() && r.raw.all { it.isFinite() } && stats(r.raw).peak <= 1.0) { "$voice/$revision/$id invalid raw PCM24" }
                val last = r.raw.indexOfLast { abs(it.toDouble()) > QUIET }
                Source(id, velocity, r, (last + 1 + TAIL_ALLOWANCE).coerceIn(1, r.raw.size))
            } }
            // Durations match within each pair. Different voices keep their own complete finite note lengths.
            val lengths = velocities.keys.associateWith { id -> rendered.values.maxOf { notes -> notes.single { it.id == id }.quietEnd } }
            for ((revision, notes) in rendered) {
                var cursor = GAP
                val cues = notes.map { note ->
                    val start = cursor; cursor += lengths.getValue(note.id)
                    linkedMapOf<String, JsonValue>("id" to str(note.id), "label" to str(note.id.replaceFirstChar { it.titlecase() } + " · velocity " + note.velocity),
                        "startFrame" to num(start), "endFrame" to num(cursor), "velocity" to num(note.velocity), "hold" to num(0)).also { cursor += GAP }
                }
                val raw = FloatArray(cursor)
                val events = notes.mapIndexed { index, note ->
                    val start = cues[index].getValue("startFrame").int()
                    val copied = minOf(note.render.raw.size, lengths.getValue(note.id))
                    note.render.raw.copyInto(raw, start, 0, copied)
                    obj("sourceId" to str(note.id), "onFrame" to num(start), "sourceFrames" to num(note.render.raw.size),
                        "copiedFrames" to num(copied), "velocity" to num(note.velocity),
                        "copiedPcmSha256" to str(pcmHash(note.render.raw.copyOf(copied))),
                        "removedTailPeak" to num(if (copied < note.render.raw.size) stats(note.render.raw, copied, note.render.raw.size).peak else 0.0))
                }
                val id = voice.name.lowercase(Locale.ROOT) + "-" + revision.lowercase(Locale.ROOT)
                val file = File(assets, "$id-raw.wav")
                WavWriter.write(file, Snip(raw, 1, RATE), WavWriter.BitDepth.PCM_24, smpl = SmplChunk(48))
                val pcm = WavReader.read(file).samples
                val compressed = compressedSize(file.readBytes()); compressedAudioBytes += compressed
                val recordPath = "r8/$id-render.json"
                File(root, recordPath).writeText(Json.write(obj("trackId" to str(id), "revision" to str(revision), "source" to source,
                    "renderSettings" to obj("voice" to str(voice.name), "macros" to JsonValue.Obj(expectedMacros), "midi" to num(48),
                        "seed" to num(0), "oversample" to num(4), "seconds" to JsonValue.Null,
                        "probe" to obj("memoryFrozen" to bool(false), "agitationDisabled" to bool(false), "equalRecovery" to bool(false),
                            "noiseForcingDisabled" to bool(false), "articulationDisabled" to bool(false), "contactMemoryDisabled" to bool(false),
                            "bodyDisabled" to bool(false), "quartetDisabled" to bool(false), "depthDisabled" to bool(revision == "R7"),
                            "filterReturnDisabled" to bool(false), "depthExchangeDisabled" to bool(false),
                            "bassFoundationDisabled" to bool(true))),
                    "events" to arr(events), "sourceNotes" to arr(notes.map { note ->
                        val r = note.render
                        obj("id" to str(note.id), "velocity" to num(note.velocity), "rawFrames" to num(r.raw.size),
                            "rawPcmSha256" to str(pcmHash(r.raw)), "quietEndFrame" to num(note.quietEnd), "rawStats" to stats(r.raw).json(),
                            "gestureCount" to num(r.diagnostics.gestureCount), "boundHits" to num(r.diagnostics.boundHits),
                            "depthStatePeak" to num(r.diagnostics.depthStatePeak),
                            "depthReturnPeak" to num(r.diagnostics.depthReturnPeak),
                            "depthExchangePeak" to num(r.diagnostics.depthExchangePeak),
                            "renderMillis" to num(r.diagnostics.renderMillis),
                            "traceColumns" to arr(listOf("seconds", "energyHistory", "agitation", "depth1Real", "depth1Imag",
                                "depth2Real", "depth2Imag", "depthReturn", "depthExchange").map(::str)),
                            "traceNote" to str("20 ms state snapshots, not a spectrum. The diagnostic peaks are measured at the internal sample rate."),
                            "trace" to arr(r.trace.filterIndexed { index, _ -> index % 4 == 0 || index == r.trace.lastIndex }.map { t ->
                                arr(listOf(t.seconds, t.energyHistory, t.agitation, t.depth1Real, t.depth1Imag,
                                    t.depth2Real, t.depth2Imag, t.depthReturn, t.depthExchange).map(::num)) }))
                    }), "outputTreatment" to str("Original raw mono output; no house level matching, attack fade, output fade or per-note gain. Quiet tail removal and silence insertion only."))) + "\n")
                takes += Take(voice, revision, linkedMapOf("id" to str(id), "revision" to str(revision),
                    "label" to str(if (revision == "R7") "R7 compatibility baseline" else "R8 depth candidate"),
                    "file" to str("r8/${file.name}"), "sha256" to str(sha(file.readBytes())), "frames" to num(pcm.size),
                    "seconds" to num(pcm.size.toDouble()/RATE), "stats" to stats(pcm).json(), "renderRecord" to str(recordPath),
                    "snapshot" to bool(false), "provenance" to obj("kind" to str(if (revision == "R7") "fresh-compatibility-baseline" else "fresh-depth-candidate"),
                        "sourceCommit" to str(sourceCommit), "sourceSha256" to source.entries.getValue("skitterSourceSha256"))), pcm, cues)
                println("$id: ${pcm.size.toDouble()/RATE}s / $compressed lossless bytes")
            }
        }
        require(takes.size == 12 && takes.map { it.id }.toSet().size == 12)
        val cueStats = takes.associate { take -> take.id to take.cues.associate { c -> c.getValue("id").str() to
            stats(take.samples, c.getValue("startFrame").int(), c.getValue("endFrame").int()) } }
        val fixedGain = CEILING/cueStats.values.maxOf { byCue -> byCue.values.maxOf { it.peak } }.coerceAtLeast(1e-20)
        val targets = velocities.keys.associateWith { id -> minOf(.125,cueStats.values.minOf { byCue ->
            val s=byCue.getValue(id); CEILING*s.rms/s.peak.coerceAtLeast(1e-20) }) }
        for (take in takes) {
            for (cue in take.cues) {
                val id=cue.getValue("id").str(); val s=cueStats.getValue(take.id).getValue(id)
                val gain=targets.getValue(id)/s.rms.coerceAtLeast(1e-20); require(s.peak*gain<=CEILING+1e-10)
                cue["stats"]=s.json(); cue["matchedGain"]=num(gain); cue["matchedTargetRms"]=num(targets.getValue(id))
                if (take.voice in voices) {
                    val sample=take.samples.copyOfRange(cue.getValue("startFrame").int(),cue.getValue("endFrame").int())
                    val pitch=Pitch.detect(Snip(sample,1,RATE),.35f,.25f)
                    cue["pitchHz"]=nullable(pitch?.hz?.toDouble());cue["pitchConfidence"]=nullable(pitch?.confidence?.toDouble())
                    cue["pitchCents"]=nullable(pitch?.let {1200.0*ln(it.hz/Keys.midiHz(48).toDouble())/ln(2.0)})
                }
            }
            take.fields["cues"]=arr(take.cues.map { JsonValue.Obj(it) })
        }
        val manifest=obj("schemaVersion" to num(1),"revision" to str("R8"),"defaults" to obj("voice" to str("lead"),"cue" to str("hard")),
            "sampleRate" to num(RATE),"channels" to num(1),"bitDepth" to num(24),"fixedGain" to num(fixedGain),"peakCeiling" to num(CEILING),
            "source" to source,"archive" to obj("revision" to str("R7"),"url" to str(archiveUrl)),
            "common" to obj("midi" to num(48),"note" to str("C3"),"seed" to num(0),"oversample" to num(4),"macros" to JsonValue.Obj(expectedMacros)),
            "cueOrder" to arr(velocities.keys.map(::str)),"crossfadeSeconds" to num(.020),
            "timing" to obj("sampleClockHz" to num(RATE),"quietThreshold" to num(QUIET),"tailAllowanceFrames" to num(TAIL_ALLOWANCE),
                "groupGapFrames" to num(GAP),"cueEndConvention" to str("exclusive"),"pairedDurations" to str("Each R7/R8 pair shares its complete finite-note cue lengths.")),
            "voices" to arr(voices.map { voice ->
                obj("id" to str(voice.name.lowercase(Locale.ROOT)),"name" to str(voice.name),
                    "role" to str("comparison"),"defaultRevision" to str("R8"),
                    "caption" to str(caption(voice)), "expectation" to str(expectation(voice)),
                    "tracks" to arr(listOf("R7","R8").map { revision ->
                        JsonValue.Obj(takes.single {it.voice==voice&&it.revision==revision}.fields) }))
            }),"compressedAudioBytes" to num(compressedAudioBytes),
            "notes" to arr(listOf(
                "All six voices have a fresh R7 compatibility baseline and a fresh R8 depth candidate. Neither side is labeled an accepted recording.",
                "The R7 compatibility path uses depthDisabled=true on the current runtime. The R8 candidate uses the default probe. The owner decides whether the new depth is useful.",
                "Fixed playback gain is one shared gain across all thirty-six voice/revision/velocity cues. Raw relative dynamics are retained.",
                "Optional matching applies one static gain to the complete selected note. Attack and body are never independently leveled.",
                "Paired versions run on one cue-relative clock, with 20 ms linear crossfades whose weights sum to one. No automatic switches or excerpt loops.",
                "Full finite notes keep the natural gate and release. Every note resets Circuit, uses C3 / MIDI 48, seed 0, velocity .25 / .60 / 1 and HOLD 0.",
                "Only tails below -80 dBFS after a 20 ms allowance may be trimmed. No active note is cut or faded to meet a size target.",
                "Cue indices use the original 44100 Hz clock even if the browser resamples its AudioBuffer. Playback stays at its original pitch and speed.",
                "Original raw PCM24 WAV downloads contain no browser gain, cue cut or revision crossfade. The earlier R7 site remains unchanged as a separate archive."
            ).map(::str)))
        val json=Json.write(manifest);File(assets,"manifest.json").writeText(json+"\n")
        val template=javaClass.getResourceAsStream("/audition/skitter-depth.html")?.bufferedReader()?.use{it.readText()}
        if(template!=null){val marker="/* SKITTER_DEPTH_MANIFEST */null";require(template.indexOf(marker)>=0&&template.indexOf(marker)==template.lastIndexOf(marker))
            File(root,"index.html").writeText(template.replace(marker,json.replace("</","<\\/")))}
        println("R8: twelve fresh raw tracks, thirty-six cues; lossless audio=$compressedAudioBytes; shared gain=$fixedGain. Archive remains separate.")
    }

    private fun caption(voice: SkitterVoice) = when (voice) {
        SkitterVoice.BASS -> "Loaded charge"
        SkitterVoice.LEAD -> "Formant dialogue"
        SkitterVoice.HOLLOW -> "Hollow strain"
        SkitterVoice.FLUTTER -> "Breathing aperture"
        SkitterVoice.CHIRP -> "Answering chirplets"
        SkitterVoice.FRAY -> "Grain tension"
    }
    private fun expectation(voice: SkitterVoice) = when (voice) {
        SkitterVoice.BASS -> "Proposed load-dependent second- and third-harmonic balance, with pressure carried through the note."
        SkitterVoice.LEAD -> "Proposed broad formant response and alternating upper harmonics loaded by the filter."
        SkitterVoice.HOLLOW -> "Proposed phase-modulation depth that responds to resonant load after touch."
        SkitterVoice.FLUTTER -> "Proposed perforation phase and aperture shape that respond differently as load builds and recovers."
        SkitterVoice.CHIRP -> "Proposed chirplet center and sweep that remember returning load."
        SkitterVoice.FRAY -> "Proposed fold depth and grain aperture responding to exchanged source and filter load."
    }
    private fun stats(x:FloatArray,start:Int=0,end:Int=x.size):Stats{require(end>start);var peak=0.0;var sum=0.0;var squares=0.0
        for(i in start until end){val v=x[i].toDouble();peak=maxOf(peak,abs(v));sum+=v;squares+=v*v};val n=(end-start).toDouble();return Stats(peak,sqrt(squares/n),sum/n)}
    private fun pcmHash(x:FloatArray):String{val wav=ByteArrayOutputStream().also{WavWriter.write(it,Snip(x,1,RATE),WavWriter.BitDepth.PCM_24)}.toByteArray()
        val(at,n)=dataChunk(wav);return sha(wav.copyOfRange(at,at+n))}
    private fun dataChunk(b:ByteArray):Pair<Int,Int>{var at=12;while(at+8<=b.size){val n=(0..3).fold(0){a,k->a or((b[at+4+k].toInt() and 255) shl(8*k))}
        require(n>=0&&at.toLong()+8+n<=b.size);if(String(b,at,4,Charsets.US_ASCII)=="data")return at+8 to n;at+=8+n+(n and 1)};error("missing WAV data")}
    private fun compressedSize(wav:ByteArray):Int{val b=wav.copyOf();val(at,n)=dataChunk(b);require(n%3==0);var p=0;var p2=0
        for(i in at until at+n step 3){val x=(b[i].toInt() and 255) or((b[i+1].toInt() and 255) shl 8) or((b[i+2].toInt() and 255) shl 16)
            val d=(x-2*p+p2) and 0xffffff;b[i]=d.toByte();b[i+1]=(d ushr 8).toByte();b[i+2]=(d ushr 16).toByte();p2=p;p=x}
        return ByteArrayOutputStream().also{out->GZIPOutputStream(out).use{it.write(b)}}.size()}
    private fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
    private fun obj(vararg p:Pair<String,JsonValue>)=JsonValue.Obj(linkedMapOf(*p))
    private fun arr(v:List<JsonValue>)=JsonValue.Arr(v)
    private fun str(v:String)=JsonValue.Str(v)
    private fun bool(v:Boolean)=JsonValue.Bool(v)
    private fun num(v:Number):JsonValue{require(v.toDouble().isFinite());return JsonValue.Num(v.toDouble())}
    private fun nullable(v:Double?)=v?.let(::num)?:JsonValue.Null
}
