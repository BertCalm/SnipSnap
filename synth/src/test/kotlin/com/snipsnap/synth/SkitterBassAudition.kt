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

/** Six raw BASS tracks compare the R8 foundation with R9 at three bass registers. */
object SkitterBassAudition {
    private const val RATE = 44_100
    private const val QUIET = .0001
    private const val TAIL_ALLOWANCE = 882
    private const val GAP = 4_410
    private const val CEILING = .80
    private val macros = linkedMapOf("BUZZ" to .55f, "SKITTER" to .50f, "AGITATE" to .60f,
        "VENT" to .35f, "STATIC" to .15f, "HOLD" to 0f)
    private val velocities = linkedMapOf("soft" to .25f, "medium" to .60f, "hard" to 1f)
    private data class Register(val id: String, val note: String, val midi: Int)
    private val registers = listOf(Register("c1", "C1", 24), Register("c2", "C2", 36), Register("c3", "C3", 48))
    private const val BASE_COMMIT = "eab42bf300d4607e72d4122e3c5efe2ea5751857"
    private const val BASE_SOURCE_SHA = "03425e88f13033df0e00832b0a72dbc354bb0620d8cb3a4606057b52540e71de"
    private data class Source(val id: String, val velocity: Float, val render: SkitterRender, val quietEnd: Int)
    private data class Take(val register: Register, val revision: String, val fields: MutableMap<String, JsonValue>,
                            val samples: FloatArray, val cues: List<MutableMap<String, JsonValue>>) {
        val id: String get() = fields.getValue("id").str()
    }
    private data class Stats(val peak: Double, val rms: Double, val dc: Double) {
        fun json() = obj("peak" to num(peak), "rms" to num(rms), "dc" to num(dc))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val options = listOf("--source-commit=", "--baseline=", "--archive-url=")
        require(args.count { !it.startsWith("--") } <= 1 && args.count { it.startsWith("--source-commit=") } == 1 &&
            args.count { it.startsWith("--baseline=") } == 1 && options.all { option -> args.count { it.startsWith(option) } <= 1 } &&
            args.all { arg -> !arg.startsWith("--") || options.any { arg.startsWith(it) } }) {
            "supply an output directory, --source-commit=<full R9 Git SHA>, --baseline=<sealed R8 runtime>, optional --archive-url"
        }
        val sourceCommit = args.single { it.startsWith("--source-commit=") }.substringAfter('=')
        require(sourceCommit.matches(Regex("[0-9a-f]{40}"))) { "R9 source commit must be an explicit full Git SHA" }
        val root = File(args.firstOrNull { !it.startsWith("--") } ?: "../testkit/skitter-bass").canonicalFile
        val sealed = File(args.single { it.startsWith("--baseline=") }.substringAfter('=')).canonicalFile
        require(!root.toPath().startsWith(sealed.toPath()) && !sealed.toPath().startsWith(root.toPath())) { "output must be separate from the immutable R8 snapshot" }
        val sealedFile = File(sealed, "provenance.json")
        val old = Json.parse(sealedFile.readText()).obj()
        require(old.getValue("sourceCommit").str() == BASE_COMMIT && old.getValue("sourceSha256").str() == BASE_SOURCE_SHA) { "baseline must be the exact published R8 engine source" }
        require(sha(File(sealed, old.getValue("sourcePath").str()).readBytes()) == BASE_SOURCE_SHA)
        val classRoot = File(sealed, old.getValue("classesRoot").str()).canonicalFile
        require(classRoot.toPath().startsWith(sealed.toPath()))
        require(old.getValue("classes").obj().size == old.getValue("classCount").int())
        require(old.getValue("engineClassSha256") == old.getValue("classes").obj()
            .getValue("com/snipsnap/synth/Skitter.class").obj().getValue("sha256"))
        for ((name, entry) in old.getValue("classes").obj()) {
            val file = File(classRoot, name).canonicalFile
            require(file.toPath().startsWith(classRoot.toPath()) && sha(file.readBytes()) == entry.obj().getValue("sha256").str()) { "sealed R8 class changed: $name" }
        }
        val baseline = obj("sourceCommit" to str(BASE_COMMIT), "sourceSha256" to str(BASE_SOURCE_SHA),
            "sourceTree" to old.getValue("sourceTree"), "engineClassSha256" to old.getValue("engineClassSha256"),
            "classCount" to old.getValue("classCount"), "provenanceSha256" to str(sha(sealedFile.readBytes())),
            "note" to str("Immutable R8 classes and source are sealed outside the repository for independent isolated-loader equivalence checks."))
        val assets = File(root, "r9").also { it.mkdirs() }
        val archiveUrl = args.firstOrNull { it.startsWith("--archive-url=") }?.substringAfter('=')
            ?: "https://skitter-depth-r8.bertcalm.chatgpt.site"
        require(URI(archiveUrl).let { it.scheme == "https" && !it.host.isNullOrBlank() }) { "archive URL must be an absolute HTTPS URL" }
        val expectedMacros = macros.mapValues { num(it.value) }
        val sourceFile = File("src/main/kotlin/com/snipsnap/synth/Skitter.kt")
        val generatorFile = File("src/test/kotlin/com/snipsnap/synth/SkitterBassAudition.kt")
        require(sourceFile.isFile && generatorFile.isFile) { "run from synth/ to identify the actual rendered source" }
        val currentSourceSha = sha(sourceFile.readBytes())
        val source = obj("baseCommit" to str(sourceCommit), "skitterSourceSha256" to str(currentSourceSha),
            "generatorSourceSha256" to str(sha(generatorFile.readBytes())),
            "note" to str("R9 render-runtime source. Each side separately identifies its R8 or R9 source, with the current runtime and compatibility probe recorded explicitly."))
        val takes = mutableListOf<Take>()

        var compressedAudioBytes = 0L
        for (register in registers) {
            val voice = SkitterVoice.BASS
            val rendered = listOf("R8", "R9").associateWith { revision -> velocities.map { (id, velocity) ->
                val r = Skitter.renderProbe(voice, macros, midi = register.midi, velocity = velocity, seed = 0,
                    seconds = null, oversample = 4, probe = SkitterProbe(bassFoundationDisabled = revision == "R8"))
                require(r.loopStart == -1 && r.raw.isNotEmpty() && r.raw.all { it.isFinite() } && stats(r.raw).peak <= 1.0) { "$voice/$revision/$id invalid raw PCM24" }
                val last = r.raw.indexOfLast { abs(it.toDouble()) > QUIET }
                Source(id, velocity, r, (last + 1 + TAIL_ALLOWANCE).coerceIn(1, r.raw.size))
            } }
            // Durations match within each pair; each register retains its complete finite note.
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
                val id = register.id + "-" + revision.lowercase(Locale.ROOT)
                val file = File(assets, "$id-raw.wav")
                WavWriter.write(file, Snip(raw, 1, RATE), WavWriter.BitDepth.PCM_24, smpl = SmplChunk(register.midi))
                val pcm = WavReader.read(file).samples
                val compressed = compressedSize(file.readBytes()); compressedAudioBytes += compressed
                val recordPath = "r9/$id-render.json"
                File(root, recordPath).writeText(Json.write(obj("trackId" to str(id), "revision" to str(revision), "source" to source,
                    "renderSettings" to obj("voice" to str(voice.name), "macros" to JsonValue.Obj(expectedMacros), "midi" to num(register.midi),
                        "seed" to num(0), "oversample" to num(4), "seconds" to JsonValue.Null,
                        "probe" to obj("memoryFrozen" to bool(false), "agitationDisabled" to bool(false), "equalRecovery" to bool(false),
                            "noiseForcingDisabled" to bool(false), "articulationDisabled" to bool(false), "contactMemoryDisabled" to bool(false),
                            "bodyDisabled" to bool(false), "quartetDisabled" to bool(false), "depthDisabled" to bool(false),
                            "filterReturnDisabled" to bool(false), "depthExchangeDisabled" to bool(false),
                            "bassFoundationDisabled" to bool(revision == "R8"))),
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
                takes += Take(register, revision, linkedMapOf("id" to str(id), "revision" to str(revision),
                    "label" to str(if (revision == "R8") "R8 compatibility baseline" else "R9 bass candidate"),
                    "file" to str("r9/${file.name}"), "sha256" to str(sha(file.readBytes())), "frames" to num(pcm.size),
                    "seconds" to num(pcm.size.toDouble()/RATE), "stats" to stats(pcm).json(), "renderRecord" to str(recordPath),
                    "midi" to num(register.midi), "note" to str(register.note), "snapshot" to bool(false),
                    "provenance" to obj("kind" to str(if (revision == "R8") "fresh-r8-compatibility-baseline" else "fresh-r9-candidate"),
                        "sourceCommit" to str(if (revision == "R8") BASE_COMMIT else sourceCommit),
                        "sourceSha256" to str(if (revision == "R8") BASE_SOURCE_SHA else currentSourceSha),
                        "renderRuntimeCommit" to str(sourceCommit), "renderRuntimeSourceSha256" to str(currentSourceSha))), pcm, cues)
                println("$id: ${pcm.size.toDouble()/RATE}s / $compressed lossless bytes")
            }
        }
        require(takes.size == 6 && takes.map { it.id }.toSet().size == 6)
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
                val sample=take.samples.copyOfRange(cue.getValue("startFrame").int(),cue.getValue("endFrame").int())
                val expectedHz=Keys.midiHz(take.register.midi).toDouble()
                val pitch=if(expectedHz<40.0) lowPitch(sample,expectedHz) else
                    Pitch.detect(Snip(sample,1,RATE),.35f,.4f)?.let { it.hz.toDouble() to it.confidence.toDouble() }
                cue["pitchHz"]=nullable(pitch?.first);cue["pitchConfidence"]=nullable(pitch?.second)
                cue["pitchCents"]=nullable(pitch?.let {1200.0*ln(it.first/expectedHz)/ln(2.0)})
                cue["expectedHz"]=num(expectedHz)
                cue["pitchMethod"]=str(if(expectedHz<40.0)"Known-note normalized autocorrelation within expected/1.4..expected*1.4; supporting diagnostic only" else "Pitch.detect body autocorrelation")

            }
            take.fields["cues"]=arr(take.cues.map { JsonValue.Obj(it) })
        }
        val manifest=obj("schemaVersion" to num(1),"revision" to str("R9"),"defaults" to obj("register" to str("c2"),"cue" to str("medium")),
            "sampleRate" to num(RATE),"channels" to num(1),"bitDepth" to num(24),"fixedGain" to num(fixedGain),"peakCeiling" to num(CEILING),
            "source" to source,"baseline" to baseline,"archive" to obj("revision" to str("R8"),"url" to str(archiveUrl)),
            "common" to obj("voice" to str("BASS"),"seed" to num(0),"oversample" to num(4),"macros" to JsonValue.Obj(expectedMacros)),
            "cueOrder" to arr(velocities.keys.map(::str)),"crossfadeSeconds" to num(.020),
            "timing" to obj("sampleClockHz" to num(RATE),"quietThreshold" to num(QUIET),"tailAllowanceFrames" to num(TAIL_ALLOWANCE),
                "groupGapFrames" to num(GAP),"cueEndConvention" to str("exclusive"),"pairedDurations" to str("Each R8/R9 pair shares its complete finite-note cue lengths.")),
            "registers" to arr(registers.map { register ->
                obj("id" to str(register.id),"name" to str(register.note),"note" to str(register.note),"midi" to num(register.midi),
                    "role" to str("comparison"),"defaultRevision" to str("R9"),"caption" to str("BASS at ${register.note}"),
                    "expectation" to str("Proposed stronger low body and reduced low-mid formant emphasis, while preserving source and filter motion. Listening verdict pending."),
                    "tracks" to arr(listOf("R8","R9").map { revision ->
                        JsonValue.Obj(takes.single {it.register==register&&it.revision==revision}.fields) }))
            }),"compressedAudioBytes" to num(compressedAudioBytes),
            "notes" to arr(listOf(
                "This is a BASS-only R9 study at C1/MIDI24, C2/MIDI36 and C3/MIDI48. All other voices and the published R8 page are outside this focused audition.",
                "The R8 compatibility baseline uses bassFoundationDisabled=true on the current runtime. Its depthDisabled flag remains false: the R8 spectral bridge is active on both sides.",
                "The immutable R8 source commit, source SHA and compiled-class SHA are recorded separately from the current render-runtime commit/SHA for independent equivalence checks.",
                "The depthDisabled probe remains the distinct R7 compatibility path; it is not used for this R8/R9 comparison.",
                "Fixed playback gain is shared across all eighteen register/revision/velocity cues. Optional matching applies one static whole-note gain, never separate attack or body normalization.",
                "Full finite notes retain the natural gate and release. Every note starts a fresh Circuit with seed 0, velocity .25/.60/1, the same five timbral controls and HOLD 0.",
                "Only tails below -80 dBFS after a 20 ms allowance may be trimmed. No active note is shortened or faded to meet an artifact budget.",
                "The C1 pitch estimate uses known-note normalized autocorrelation because Pitch.detect starts at 40 Hz. It is a supporting diagnostic, not proof that arbitrary wrong pitches would be detected.",
                "Revision switches share one original 44100 Hz cue clock, with 20 ms linear weights summing to one. Playback remains mono and at original pitch/speed.",
                "Complete raw PCM24 WAV downloads contain no browser gain, cue cuts or crossfades. The complete R8 audition remains unchanged at the archive link."
            ).map(::str)))
        val json=Json.write(manifest);File(assets,"manifest.json").writeText(json+"\n")
        val template=javaClass.getResourceAsStream("/audition/skitter-bass.html")?.bufferedReader()?.use{it.readText()}
        if(template!=null){val marker="/* SKITTER_BASS_MANIFEST */null";require(template.indexOf(marker)>=0&&template.indexOf(marker)==template.lastIndexOf(marker))
            File(root,"index.html").writeText(template.replace(marker,json.replace("</","<\\/")))}
        println("R9 BASS: six tracks, eighteen cues; lossless audio=$compressedAudioBytes; shared gain=$fixedGain. Immutable R8 baseline=$BASE_COMMIT.")
    }

    /** C1 lies below Pitch.detect's 40 Hz floor; disclose this constrained diagnostic. */
    private fun lowPitch(samples: FloatArray, expected: Double): Pair<Double,Double>? {
        val start=(.35*RATE).toInt();val n=minOf((.4*RATE).toInt(),samples.size-start)
        if(n<=0)return null
        val values=DoubleArray(n){samples[start+it].toDouble()}
        val lo=(RATE/(expected*1.4)).toInt().coerceAtLeast(2)
        val hi=(RATE/(expected/1.4)).toInt().coerceAtMost(n/2)
        if(hi<=lo+2)return null
        val correlation=DoubleArray(hi+1)
        for(lag in lo..hi){var xy=0.0;var aa=0.0;var bb=0.0
            for(i in 0 until n-lag){xy+=values[i]*values[i+lag];aa+=values[i]*values[i];bb+=values[i+lag]*values[i+lag]}
            correlation[lag]=xy/sqrt(aa*bb).coerceAtLeast(1e-20)}
        val best=(lo+1 until hi).filter{correlation[it]>=correlation[it-1]&&correlation[it]>=correlation[it+1]}
            .maxByOrNull{correlation[it]}?:return null
        if(correlation[best]<.3)return null
        val curvature=correlation[best-1]-2*correlation[best]+correlation[best+1]
        val offset=if(abs(curvature)<1e-12)0.0 else (.5*(correlation[best-1]-correlation[best+1])/curvature).coerceIn(-.5,.5)
        return RATE/(best+offset) to correlation[best].coerceIn(0.0,1.0)
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
