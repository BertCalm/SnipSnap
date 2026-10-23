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

/** Eight new raw comparison tracks; accepted LEAD/HOLLOW are imported, never rerendered. */
object SkitterQuartetAudition {
    private const val RATE = 44_100
    private const val QUIET = .0001
    private const val TAIL_ALLOWANCE = 882
    private const val GAP = 4_410
    private const val CEILING = .80
    private val macros = linkedMapOf("BUZZ" to .55f, "SKITTER" to .50f, "AGITATE" to .60f,
        "VENT" to .35f, "STATIC" to .15f, "HOLD" to 0f)
    private val velocities = linkedMapOf("soft" to .25f, "medium" to .60f, "hard" to 1f)
    private val quartet = listOf(SkitterVoice.BASS, SkitterVoice.FLUTTER, SkitterVoice.CHIRP, SkitterVoice.FRAY)
    private val references = listOf(SkitterVoice.LEAD, SkitterVoice.HOLLOW)
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
        val options = listOf("--references=", "--source-commit=", "--archive-url=")
        require(args.count { !it.startsWith("--") } <= 1 && args.count { it.startsWith("--references=") } == 1 &&
            args.count { it.startsWith("--source-commit=") } == 1 &&
            options.all { option -> args.count { it.startsWith(option) } <= 1 } &&
            args.all { arg -> !arg.startsWith("--") || options.any { arg.startsWith(it) } }) {
            "supply an output directory, --references=<raw frozen R6 proof>, --source-commit=<Git SHA>, and optional --archive-url"
        }
        val referenceRoot = File(args.single { it.startsWith("--references=") }.substringAfter('=')).canonicalFile
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/skitter-quartet").canonicalFile
        require(!root.toPath().startsWith(referenceRoot.toPath()) && !referenceRoot.toPath().startsWith(root.toPath())) { "output and accepted references must be separate" }
        val assets = File(root, "r7").also { it.mkdirs() }
        val archiveUrl = args.firstOrNull { it.startsWith("--archive-url=") }?.substringAfter('=')
            ?: "https://skitter-snipsnap-audition.bertcalm.chatgpt.site"
        require(URI(archiveUrl).let { it.scheme == "https" && !it.host.isNullOrBlank() }) { "archive URL must be an absolute HTTPS URL" }
        val referenceFile = File(referenceRoot, "r6/manifest.json")
        val frozen = Json.parse(referenceFile.readText()).obj()
        require(frozen.getValue("revision").str() == "R6" && frozen.getValue("sampleRate").int() == RATE &&
            frozen.getValue("channels").int() == 1 && frozen.getValue("bitDepth").int() == 24) { "references must be the original raw R6 proof" }
        val expectedMacros = macros.mapValues { num(it.value) }
        val frozenCommon = frozen.getValue("common").obj()
        require(frozenCommon.getValue("macros").obj() == expectedMacros && frozenCommon.getValue("midi").int() == 48 &&
            frozenCommon.getValue("seed").int() == 0 && frozenCommon.getValue("oversample").int() == 4) { "accepted reference inputs differ from the controlled study" }
        val referenceSource = frozen.getValue("source").obj()
        val sourceFile = File("src/main/kotlin/com/snipsnap/synth/Skitter.kt")
        val generatorFile = File("src/test/kotlin/com/snipsnap/synth/SkitterQuartetAudition.kt")
        require(sourceFile.isFile && generatorFile.isFile) { "run from synth/ to identify the actual rendered source" }
        val sourceCommit = args.single { it.startsWith("--source-commit=") }.substringAfter('=')
        require(sourceCommit.matches(Regex("[0-9a-f]{40}"))) { "source commit must be an explicit full Git SHA" }
        val source = obj("baseCommit" to str(sourceCommit), "skitterSourceSha256" to str(sha(sourceFile.readBytes())),
            "generatorSourceSha256" to str(sha(generatorFile.readBytes())), "referenceManifestSha256" to str(sha(referenceFile.readBytes())),
            "note" to str("Base commit plus the actual local source SHA256. Accepted LEAD/HOLLOW recordings retain their own earlier source provenance."))
        val takes = mutableListOf<Take>()

        // Only the two accepted R6 recordings are imported. No calls to renderProbe for these voices.
        for (voice in references) {
            val v = frozen.getValue("voices").arr().single { it.obj().getValue("name").str() == voice.name }.obj()
            val t = v.getValue("tracks").arr().single { it.obj().getValue("revision").str() == "R6" }.obj()
            val file = File(referenceRoot, t.getValue("file").str()).canonicalFile
            require(file.toPath().startsWith(referenceRoot.toPath()) && file.extension == "wav")
            require(sha(file.readBytes()) == t.getValue("sha256").str()) { "accepted $voice reference WAV hash mismatch" }
            val snip = WavReader.read(file)
            require(snip.channels == 1 && snip.sampleRate == RATE && snip.frameCount == t.getValue("frames").int())
            val cues = velocities.map { (id, velocity) ->
                val c = t.getValue("cues").arr().single { it.obj().getValue("id").str() == id }.obj()
                require(c.getValue("velocity").num() == velocity.toDouble() && c.getValue("hold").num() == 0.0)
                LinkedHashMap(c)
            }
            takes += Take(voice, "R6", LinkedHashMap(t).also {
                it["snapshot"] = bool(true); it["label"] = str("Accepted R6 reference")
                it["provenance"] = obj("kind" to str("accepted-recording"), "sourceCommit" to referenceSource.getValue("baseCommit"),
                    "sourceSha256" to referenceSource.getValue("skitterSourceSha256"), "sourceManifestSha256" to str(sha(referenceFile.readBytes())),
                    "acceptance" to str("Owner accepted R6 LEAD and HOLLOW before the R7 quartet pass."))
            }, snip.samples, cues)
        }

        var compressedAudioBytes = 0L
        for (voice in quartet) {
            val rendered = listOf("R6", "R7").associateWith { revision -> velocities.map { (id, velocity) ->
                val r = Skitter.renderProbe(voice, macros, midi = 48, velocity = velocity, seed = 0,
                    seconds = null, oversample = 4, probe = SkitterProbe(quartetDisabled = revision == "R6"))
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
                val recordPath = "r7/$id-render.json"
                File(root, recordPath).writeText(Json.write(obj("trackId" to str(id), "revision" to str(revision), "source" to source,
                    "renderSettings" to obj("voice" to str(voice.name), "macros" to JsonValue.Obj(expectedMacros), "midi" to num(48),
                        "seed" to num(0), "oversample" to num(4), "seconds" to JsonValue.Null,
                        "probe" to obj("memoryFrozen" to bool(false), "agitationDisabled" to bool(false), "equalRecovery" to bool(false),
                            "noiseForcingDisabled" to bool(false), "articulationDisabled" to bool(false), "contactMemoryDisabled" to bool(false),
                            "bodyDisabled" to bool(false), "quartetDisabled" to bool(revision == "R6"))),
                    "events" to arr(events), "sourceNotes" to arr(notes.map { note ->
                        val r = note.render
                        obj("id" to str(note.id), "velocity" to num(note.velocity), "rawFrames" to num(r.raw.size),
                            "rawPcmSha256" to str(pcmHash(r.raw)), "quietEndFrame" to num(note.quietEnd), "rawStats" to stats(r.raw).json(),
                            "gestureCount" to num(r.diagnostics.gestureCount), "boundHits" to num(r.diagnostics.boundHits),
                            "renderMillis" to num(r.diagnostics.renderMillis),
                            "traceColumns" to arr(listOf("seconds", "energyHistory", "agitation").map(::str)),
                            "trace" to arr(r.trace.filterIndexed { index, _ -> index % 4 == 0 || index == r.trace.lastIndex }.map { t ->
                                arr(listOf(t.seconds, t.energyHistory, t.agitation).map(::num)) }))
                    }), "outputTreatment" to str("Original raw mono output; no house level matching, attack fade, output fade or per-note gain. Quiet tail removal and silence insertion only."))) + "\n")
                takes += Take(voice, revision, linkedMapOf("id" to str(id), "revision" to str(revision),
                    "label" to str(if (revision == "R6") "R6 source baseline" else "R7 body candidate"),
                    "file" to str("r7/${file.name}"), "sha256" to str(sha(file.readBytes())), "frames" to num(pcm.size),
                    "seconds" to num(pcm.size.toDouble()/RATE), "stats" to stats(pcm).json(), "renderRecord" to str(recordPath),
                    "snapshot" to bool(false), "provenance" to obj("kind" to str(if (revision == "R6") "fresh-source-baseline" else "fresh-candidate"),
                        "sourceCommit" to str(sourceCommit), "sourceSha256" to source.entries.getValue("skitterSourceSha256"))), pcm, cues)
                println("$id: ${pcm.size.toDouble()/RATE}s / $compressed lossless bytes")
            }
        }
        require(takes.size == 10 && takes.map { it.id }.toSet().size == 10)
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
                if (take.voice in quartet) {
                    val sample=take.samples.copyOfRange(cue.getValue("startFrame").int(),cue.getValue("endFrame").int())
                    val pitch=Pitch.detect(Snip(sample,1,RATE),.35f,.25f)
                    cue["pitchHz"]=nullable(pitch?.hz?.toDouble());cue["pitchConfidence"]=nullable(pitch?.confidence?.toDouble())
                    cue["pitchCents"]=nullable(pitch?.let {1200.0*ln(it.hz/Keys.midiHz(48).toDouble())/ln(2.0)})
                }
            }
            take.fields["cues"]=arr(take.cues.map { JsonValue.Obj(it) })
        }
        val manifest=obj("schemaVersion" to num(1),"revision" to str("R7"),"defaults" to obj("voice" to str("bass"),"cue" to str("hard")),
            "sampleRate" to num(RATE),"channels" to num(1),"bitDepth" to num(24),"fixedGain" to num(fixedGain),"peakCeiling" to num(CEILING),
            "source" to source,"archive" to obj("revision" to str("R6"),"url" to str(archiveUrl)),
            "referenceSource" to frozen.getValue("source"),
            "common" to obj("midi" to num(48),"note" to str("C3"),"seed" to num(0),"oversample" to num(4),"macros" to JsonValue.Obj(expectedMacros)),
            "cueOrder" to arr(velocities.keys.map(::str)),"crossfadeSeconds" to num(.020),
            "timing" to obj("sampleClockHz" to num(RATE),"quietThreshold" to num(QUIET),"tailAllowanceFrames" to num(TAIL_ALLOWANCE),
                "groupGapFrames" to num(GAP),"cueEndConvention" to str("exclusive"),"pairedDurations" to str("Each comparison pair shares its cue lengths; accepted references keep their original offsets.")),
            "voices" to arr((quartet+references).map { voice ->
                val isReference=voice in references
                obj("id" to str(voice.name.lowercase(Locale.ROOT)),"name" to str(voice.name),
                    "role" to str(if(isReference)"accepted-reference" else "comparison"),"defaultRevision" to str(if(isReference)"R6" else "R7"),
                    "acceptedRevision" to if(isReference)str("R6") else JsonValue.Null,
                    "caption" to str(caption(voice)), "expectation" to str(expectation(voice)),
                    "tracks" to arr((if(isReference)listOf("R6") else listOf("R6","R7")).map { revision ->
                        JsonValue.Obj(takes.single {it.voice==voice&&it.revision==revision}.fields) }))
            }),"compressedNewAudioBytes" to num(compressedAudioBytes),
            "notes" to arr(listOf(
                "R7 changes the four remaining voices. LEAD and HOLLOW use the exact accepted R6 recordings, without rerendering or re-encoding.",
                "R6 source baselines for BASS, FLUTTER, CHIRP and FRAY are rendered on the current runtime with quartetDisabled. Their R7 candidates use the default probe.",
                "Fixed playback gain is one shared gain across all thirty voice/revision/velocity cues. The accepted recordings may play at a different gain from their original page; downloaded bytes are unchanged.",
                "Optional matching applies one static gain to the complete selected note. Attack and body are never independently leveled.",
                "Paired versions run on one cue-relative clock, with 20ms linear crossfades whose weights sum to one. A single accepted reference has no revision switch.",
                "Full finite notes keep the natural gate and release. Every note resets Circuit, uses C3/MIDI48, seed0, velocity .25/.60/1 and HOLD0.",
                "Only tails below -80dBFS after a 20ms allowance may be trimmed. No active note is cut or faded to meet a size target.",
                "Cue indices use the original 44100Hz clock even if the browser resamples its AudioBuffer. Playback stays at its original pitch and speed.",
                "The prior full R6 site remains a separate archive. This compact site does not copy or replace its historical listening benches."
            ).map(::str)))
        val json=Json.write(manifest);File(assets,"manifest.json").writeText(json+"\n")
        val template=javaClass.getResourceAsStream("/audition/skitter-quartet.html")?.bufferedReader()?.use{it.readText()}
        if(template!=null){val marker="/* SKITTER_QUARTET_MANIFEST */null";require(template.indexOf(marker)>=0&&template.indexOf(marker)==template.lastIndexOf(marker))
            File(root,"index.html").writeText(template.replace(marker,json.replace("</","<\\/")))}
        println("R7: eight new raw tracks, two accepted references; new lossless audio=$compressedAudioBytes; shared gain=$fixedGain. Package against the frozen hosted R6 references.")
    }

    private fun caption(voice: SkitterVoice) = when (voice) {
        SkitterVoice.BASS -> "Charged weight"
        SkitterVoice.FLUTTER -> "Perforated pulse"
        SkitterVoice.CHIRP -> "Cyclic resonance"
        SkitterVoice.FRAY -> "Folded grain"
        else -> "Accepted R6 reference"
    }
    private fun expectation(voice: SkitterVoice) = when (voice) {
        SkitterVoice.BASS -> "Listen for a compact, round fundamental with a charged, asymmetric growl that shifts with touch."
        SkitterVoice.FLUTTER -> "Listen for a persistent audio-rate aperture and quick, agitated replies without a new LFO."
        SkitterVoice.CHIRP -> "Listen for a short chirped packet inside every root cycle, with sustained voiced ringing after touch."
        SkitterVoice.FRAY -> "Listen for a beating pair folding through its own curve, with dense grain that tears and relaxes."
        else -> "Owner-accepted R6 recording, retained byte for byte. This is a reference, not an R7 candidate."
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
