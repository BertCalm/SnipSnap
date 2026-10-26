package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ln

/** Small, dry model-2 HOLD comparison, separate from the preserved first-listen pack. */
object SkirlHoldAuditionGenerator {
    private const val RATE = Dsp.RATE

    private data class Request(val id: String, val title: String, val section: String,
        val patch: SkirlPatch, val mode: String = "patch")
    private data class Rendered(val request: Request, val probe: Skirl.Probe,
        val samples: FloatArray, val raw: FloatArray, val loopStartFrame: Int,
        val inspectMillis: Double?)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply one dedicated audition directory." }
        val output = File(args.firstOrNull() ?: "../testkit/skirl-hold-audition").canonicalFile
        val repo = File("..").canonicalFile
        require(generateSequence(repo) { it.parentFile }.none { it == output } &&
            output != File(".").canonicalFile && output.parentFile != null) {
            "Use a dedicated audition directory."
        }
        val marker = File(output, ".skirl-hold-audition")
        require(!output.exists() || output.isDirectory &&
            (output.listFiles().orEmpty().isEmpty() || marker.isFile)) {
            "An existing output must be empty or a prior marked SKIRL HOLD audition."
        }
        output.mkdirs()
        marker.writeText("snipsnap-skirl-hold-audition\n")
        val results = requests().mapIndexed { index, request ->
            val patch = request.patch
            val start = System.nanoTime()
            val probe = if (request.mode == "held-note")
                Skirl.inspectHeld(patch.voice, patch.macros, patch.midi, patch.velocity)
            else Skirl.inspect(patch.voice, patch.macros, patch.midi, patch.velocity, patch.model)
            val elapsed = (System.nanoTime() - start) / 1e6
            check(probe.raw.isNotEmpty() && probe.raw.all { it.isFinite() })
            check(probe.snip.samples.all { it.isFinite() && abs(it) <= 1f })
            check(probe.acoustics.combinedBudgetSatisfied)
            println("SKIRL HOLD ${index + 1}: ${request.id}")
            Rendered(request, probe, probe.snip.samples, probe.raw, probe.loopStartFrame, elapsed)
        }.toMutableList()
        // This is a playback demonstration, not fresh DSP evidence. Its source
        // note includes the real conditioned attack and preceding settle cycles.
        for (voice in SkirlVoice.entries) {
            val name = voice.name.lowercase(Locale.ROOT)
            val held = results.single { it.request.id == "$name-held-note" }
            val start = held.loopStartFrame
            val loop = held.samples.copyOfRange(start, held.samples.size)
            val rawLoop = held.raw.copyOfRange(start, held.raw.size)
            val production = held.samples.copyOf(held.samples.size + 7 * loop.size)
            val raw = held.raw.copyOf(held.raw.size + 7 * rawLoop.size)
            for (i in 0 until 7) {
                loop.copyInto(production, held.samples.size + i * loop.size)
                rawLoop.copyInto(raw, held.raw.size + i * rawLoop.size)
            }
            results += Rendered(held.request.copy(id = "$name-held-eight-wraps",
                title = "$voice / attack, then eight loop passes", mode = "eight-wraps"),
                held.probe, production, raw, start, null)
        }
        val commonGain = minOf(1.0, .95 / results.maxOf { peak(it.raw) }.coerceAtLeast(1e-12))
        val clips = results.map { result -> writeClip(output, result, commonGain) }
        val manifest = linkedMapOf<String, Any?>(
            "title" to "Skirl / HOLD workshop", "engine" to SkirlPatch.ENGINE,
            "model" to Skirl.MODEL_VERSION, "generatedAt" to Instant.now().toString(),
            "runtime" to mapOf("javaVersion" to System.getProperty("java.version"),
                "osName" to System.getProperty("os.name"), "osArch" to System.getProperty("os.arch")),
            "clipCount" to clips.size, "sampleRate" to RATE, "channels" to 1,
            "commonGain" to commonGain,
            "scope" to "Two prototype voices. HOLD captures existing rolling audio; it does not extend the dissipative solver. Factory presets and phone picker remain outside this pack.",
            "controls" to "HOLD 0 keeps the finite gesture. Below .99 it adds whole captured cycles and resumes the original shudder and decay. At .99 and above it returns a loop. renderHeld retains the attack and supplies a nonzero loop marker.",
            "evidence" to "Seam and cycle difference compare separately conditioned consecutive cycles, before leveling. Eight-wrap montages are listening aids, not independent seam proof. The finite source work budget does not fund the repeated acoustic capture.",
            "pitchMeasurement" to "Every clip retains both fixed .10 s and .25 s starts with .30 s windows. Loop clips also report a whole-cycle estimate; the two low-soft loops retain all twelve .30 s windows at phases 0 through .55 s. A null is a detector refusal, not a measured pitch. In the audited low-soft loops, valid root-correlation peaks in some windows fall below the detector's .85-of-global-maximum gate because its short-lag endpoint is stronger. No window is moved or omitted to conceal this limitation. Independent spectral analysis is recorded separately in the repository's HOLD evidence.",
            "level" to "Production uses engine leveling. Common-gain WAVs share one gain across all prelevel captures, preserving their relative amplitude. These are mono 44.1 kHz PCM16 exports.",
            "listening" to "Native listening approval remains open; this generator measures and exports audio but does not hear it.",
            "timing" to "inspectMillis includes probe copying and diagnostics in this JVM run. It is not a production latency benchmark and is not phone performance.",
            "clips" to clips,
        )
        writeJson(File(output, "manifest.json"), manifest)
        File(output, "index.html").writeText(page(manifest))
        val pack = File(output, "skirl-hold-listening-pack.zip")
        val paths = mutableSetOf("manifest.json", "index.html")
        for (clip in clips) for (key in listOf("production", "commonGain", "recipe", "playback", "diagnostics"))
            paths += clip.getValue(key).toString()
        val files = paths.sorted().map { File(output, it) }
        ZipOutputStream(pack.outputStream().buffered()).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.relativeTo(output).invariantSeparatorsPath).also { it.time = 0 })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        println("SKIRL HOLD: ${clips.size} clips, ${pack.length()} bytes, SHA256 ${sha256(pack)}")
    }

    private fun requests(): List<Request> = buildList {
        for (voice in SkirlVoice.entries) {
            val name = voice.name.lowercase(Locale.ROOT)
            fun addCase(suffix: String, title: String, section: String = "Default comparison",
                hold: Float = 1f, midi: Int = 48, velocity: Float = 1f,
                macros: Map<String, Float> = emptyMap(), mode: String = "patch") {
                add(Request("$name-$suffix", "$voice / $title", section,
                    SkirlPatch("$voice $title", voice, Skirl.defaults(voice) + macros + ("HOLD" to hold),
                        midi = midi, velocity = velocity), mode))
            }
            addCase("finite", "finite runout", hold = 0f)
            addCase("extended", "HOLD .5, then runout", hold = .5f)
            addCase("loop", "HOLD 1, loop only")
            addCase("low-soft-loop", "low and gentle loop", "Register and material", midi = 36, velocity = .25f)
            addCase("high-bright-loop", "high, bright plate loop", "Register and material", midi = 84,
                macros = mapOf("EDGE" to 1f, "PLATE" to 1f))
            addCase("dark-loop", "soft edge, short plate loop", "Register and material", midi = 60, velocity = .25f,
                macros = mapOf("EDGE" to 0f, "PLATE" to 0f))
            addCase("rough-extended", "rough edge, HOLD .75, then runout", "Register and material", hold = .75f,
                macros = mapOf("EDGE" to .9f, "PLATE" to .8f, "COLLAPSE" to .85f))
            addCase("held-note", "attack plus loop marker", "Attack and repeat", mode = "held-note")
        }
    }

    private fun writeClip(output: File, result: Rendered, commonGain: Double): Map<String, Any?> {
        val request = result.request
        val id = request.id
        val production = "production/$id.wav"
        val common = "common-gain/$id.wav"
        val recipe = "recipes/$id.patch.json"
        val playback = "recipes/$id.render.json"
        val diagnostics = "diagnostics/$id.json"
        val root = Skirl.midiFor(request.patch.voice, request.patch.macros.getValue("TUNE"), request.patch.midi)
        val loop = when {
            request.mode == "eight-wraps" -> null
            result.loopStartFrame > 0 -> SmplChunk.Loop(result.loopStartFrame.toLong(), result.samples.size.toLong())
            Skirl.isLoop(request.patch.macros.getValue("HOLD")) -> SmplChunk.Loop(0, result.samples.size.toLong())
            else -> null
        }
        val sampler = SmplChunk(root, loop)
        val productionFile = wav(File(output, production), result.samples, sampler)
        val commonFile = wav(File(output, common), FloatArray(result.raw.size) { (result.raw[it] * commonGain).toFloat() }, sampler)
        check(WavReader.readSmpl(productionFile.readBytes()) == sampler)
        check(WavReader.readSmpl(commonFile.readBytes()) == sampler)
        File(output, recipe).also { it.parentFile.mkdirs(); it.writeText(request.patch.toJsonText() + "\n") }
        val restored = SkirlPatch.fromJsonText(File(output, recipe).readText())
        check(restored == request.patch)
        if (request.mode == "patch") {
            check(restored.render().samples.contentEquals(result.samples)) {
                "$id saved patch did not replay the exported float audio"
            }
        } else {
            val replay = Skirl.renderHeld(restored.voice, restored.macros, restored.midi, restored.velocity)
            val expected = if (request.mode == "eight-wraps") {
                val start = replay.loopStartFrame.toInt()
                val cycle = replay.snip.samples.copyOfRange(start, replay.snip.samples.size)
                replay.snip.samples.copyOf(replay.snip.samples.size + 7 * cycle.size).also { samples ->
                    for (i in 0 until 7) cycle.copyInto(samples, replay.snip.samples.size + i * cycle.size)
                }
            } else replay.snip.samples
            check(replay.loopStartFrame == result.loopStartFrame.toLong() &&
                expected.contentEquals(result.samples)) {
                "$id saved held-note method did not replay the exported audio and marker"
            }
        }
        writeJson(File(output, playback), linkedMapOf(
            "kind" to "skirl-hold-render", "patch" to recipe,
            "method" to if (request.mode == "patch") "SkirlPatch.render" else "Skirl.renderHeld",
            "mode" to request.mode, "loopStartFrame" to result.loopStartFrame,
            "replayVerified" to true,
            "wavRootMidi" to root, "wavLoopStartFrame" to loop?.startFrame,
            "wavLoopEndFrameExclusive" to loop?.endFrameExclusive,
            "loopPasses" to if (request.mode == "eight-wraps") 8 else null,
            "instruction" to if (request.mode == "eight-wraps")
                "Render the held note, keep its actual attack, and append seven more copies of the region from loopStartFrame to the end."
                else if (request.mode == "held-note") "The saved patch renders loop-only; use renderHeld with its voice, macros, MIDI and velocity for this attack-plus-loop export."
                else "Render the saved patch with its saved model, MIDI, velocity and macros.",
        ))
        val metrics = metrics(result)
        writeJson(File(output, diagnostics), metrics)
        return linkedMapOf("id" to id, "title" to request.title, "section" to request.section,
            "voice" to request.patch.voice.name, "midi" to request.patch.midi,
            "velocity" to request.patch.velocity, "macros" to request.patch.macros,
            "replayVerified" to true,
            "mode" to request.mode, "production" to production, "commonGain" to common,
            "recipe" to recipe, "playback" to playback, "diagnostics" to diagnostics,
            "loopStartFrame" to result.loopStartFrame,
            "wavRootMidi" to root, "wavLoopStartFrame" to loop?.startFrame,
            "wavLoopEndFrameExclusive" to loop?.endFrameExclusive,
            "repeatPlayer" to (request.mode == "patch" && Skirl.isLoop(request.patch.macros.getValue("HOLD"))),
            "productionSha256" to sha256(productionFile), "commonGainSha256" to sha256(commonFile),
            "metrics" to metrics)
    }

    private fun metrics(result: Rendered): Map<String, Any?> {
        val p = result.probe
        val patch = result.request.patch
        val hold = p.hold
        val loop = if (result.loopStartFrame > 0)
            p.snip.samples.copyOfRange(result.loopStartFrame, p.snip.samples.size) else p.snip.samples
        val pitchSamples = if (Skirl.isLoop(patch.macros.getValue("HOLD"))) loop + loop + loop else loop
        val hz = Skirl.frequencyFor(patch.voice, patch.macros.getValue("TUNE"), patch.midi)
        val pitched = Snip(pitchSamples, 1, RATE)
        val pitch = Pitch.detect(pitched, .25f, .30f)
        fun pitchWindow(from: Float, length: Float): Map<String, Any?> {
            val detected = Pitch.detect(pitched, from, length)
            return linkedMapOf("fromSeconds" to from, "windowSeconds" to length,
                "status" to if (detected == null) "detector-refused" else "estimated",
                "hz" to detected?.hz,
                "cents" to detected?.let { 1200 * ln(it.hz / hz.toDouble()) / ln(2.0) },
                "confidence" to detected?.confidence)
        }
        val fixedWindows = listOf(.10f, .25f).map { pitchWindow(it, .30f) }
        val wholeCycle = if (Skirl.isLoop(patch.macros.getValue("HOLD")))
            pitchWindow(0f, loop.size.toFloat() / RATE) else null
        val phaseWindows = if (result.request.id.endsWith("-low-soft-loop"))
            (0 until 12).map { pitchWindow(it * .05f, .30f) } else null
        return linkedMapOf("durationSeconds" to result.samples.size.toDouble() / RATE,
            "peak" to peak(result.samples), "dc" to result.samples.average(),
            "loudness" to Loudness.of(Snip(result.samples, 1, RATE)),
            "floatSha256" to floatHash(result.samples), "prelevelFloatSha256" to floatHash(result.raw),
            "pitchHz" to pitch?.hz, "pitchCents" to pitch?.let { 1200 * ln(it.hz / hz.toDouble()) / ln(2.0) },
            "pitchConfidence" to pitch?.confidence,
            "pitchWindowSeconds" to .25, "pitchWindowLengthSeconds" to .30,
            "fixedPitchWindows" to fixedWindows, "wholeLoopPitch" to wholeCycle,
            "lowSoftPhasePitchWindows" to phaseWindows,
            "pitchRefusalMeaning" to "No estimate from the existing detector. Nulls are retained, not replaced with the requested note. See the manifest's pitchMeasurement explanation.",
            "inspectMillis" to result.inspectMillis, "loopStartFrame" to result.loopStartFrame,
            "captureStartSeconds" to hold?.captureStartSeconds, "cycleSeconds" to hold?.cycleSeconds,
            "overlapSeconds" to hold?.overlapSeconds, "insertedCycles" to hold?.insertedCycles,
            "insertedSeconds" to hold?.insertedSeconds, "originalRollingSeconds" to p.motion.duration,
            "originalRestStartSeconds" to p.acoustics.restStartSeconds,
            "resumedRestStartSeconds" to if (!Skirl.isLoop(patch.macros.getValue("HOLD")))
                p.acoustics.restStartSeconds + (hold?.insertedSeconds ?: 0.0) else null,
            "independentPrelevelSeamError" to hold?.seamError,
            "independentPrelevelCycleDifference" to hold?.cycleDifference,
            "sourceTraceExit" to p.motion.exit.name,
            "finiteSourceBudgetSatisfied" to p.acoustics.combinedBudgetSatisfied,
            "finiteSourceRestExcitationPeak" to p.acoustics.restExcitationPeak,
            "budgetScope" to "Original finite source only. Repeated acoustic capture assumes external replenishment.",
            "preparationMillis" to p.preparationNanos / 1e6,
            "synthesisMillis" to p.synthesisNanos / 1e6, "outputMillis" to p.outputNanos / 1e6)
    }

    private fun peak(samples: FloatArray): Double = samples.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
    private fun wav(file: File, samples: FloatArray, sampler: SmplChunk): File =
        WavWriter.write(file, Snip(samples, 1, RATE), WavWriter.BitDepth.PCM_16, smpl = sampler)
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    private fun floatHash(samples: FloatArray): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (sample in samples) {
            val bits = sample.toRawBits()
            hash.update((bits ushr 24).toByte()); hash.update((bits ushr 16).toByte())
            hash.update((bits ushr 8).toByte()); hash.update(bits.toByte())
        }
        return hash.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
    }
    private fun writeJson(file: File, value: Any?) {
        file.parentFile.mkdirs(); file.writeText(Json.write(json(value)) + "\n")
    }
    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> if (value.toDouble().isFinite()) JsonValue.Num(value.toDouble()) else JsonValue.Null
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported diagnostic type ${value.javaClass.name}")
    }

    private fun page(manifest: Map<String, Any?>): String {
        val embedded = Json.write(json(manifest)).replace("<", "\\u003c").replace(">", "\\u003e")
            .replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Skirl / HOLD workshop</title><style>body{font:16px system-ui;background:#142025;color:#f0eee6;max-width:1000px;margin:auto;padding:28px}h1{font-size:40px}p{line-height:1.6;color:#cad4d4}article{border:1px solid #425359;border-radius:12px;padding:18px;margin:16px 0}audio{width:100%;margin:8px 0}a{color:#e6bd82}select{font:inherit;padding:8px}label{display:block;margin:10px 0}small{color:#abbcbd}.links{line-height:1.8}</style>
<h1>Skirl / HOLD</h1><p>Compare the finite runout, a captured rolling plateau that releases into the original finish, and an indefinite acoustic loop. HOLD is an authored capture with replenishment, not a disk that spins forever.</p>
<p>The loop-only players repeat. The eight-wrap studies include the real attack before eight passes of the kept loop. Listen for a clear root, a natural transition into the capture, and any repeated swell or seam.</p>
<p>Some short pitch windows in the low, soft loops return no detector estimate even when longer windows find the root. The detector's relative correlation gate can reject a root peak below a stronger short-lag endpoint. All fixed-window measurements and refusals remain in the diagnostics; the waveform and detector are unchanged.</p>
<p><a href="manifest.json">Manifest and measurements</a></p><label>Voice <select id="filter"><option>Both</option><option>ROLL</option><option>WHIRR</option></select></label><div id="clips"></div><p id="status"></p>
<script type="application/json" id="data">$embedded</script><script>
const m=JSON.parse(document.getElementById('data').textContent),host=document.getElementById('clips'),filter=document.getElementById('filter');function node(t,s){const e=document.createElement(t);if(s!==undefined)e.textContent=s;return e}function player(a,label,path,repeat){a.append(node('label',label));const p=node('audio');p.controls=true;p.preload='none';p.loop=repeat;p.src=path;p.addEventListener('play',()=>document.querySelectorAll('audio').forEach(x=>{if(x!==p)x.pause()}));a.append(p)}function link(a,label,path){const l=node('a',label);l.href=path;a.append(l,document.createTextNode(' · '))}function draw(){host.querySelectorAll('audio').forEach(a=>a.pause());host.replaceChildren();for(const c of m.clips.filter(c=>filter.value==='Both'||c.voice===filter.value)){const a=node('article');a.append(node('h2',c.title),node('small',c.section+' · '+c.metrics.durationSeconds.toFixed(2)+' s · MIDI '+c.midi+' · touch '+c.velocity));player(a,'Production level',c.production,c.repeatPlayer);player(a,'Common gain',c.commonGain,c.repeatPlayer);const links=node('div');links.className='links';link(links,'Saved patch',c.recipe);link(links,'Exact playback method',c.playback);link(links,'Diagnostics',c.diagnostics);a.append(links);if(c.loopStartFrame>0)a.append(node('p','Loop marker: frame '+c.loopStartFrame+'. A normal audio player plays this WAV once; sampler sustain uses the recorded marker.'));host.append(a)}}filter.onchange=draw;draw();document.getElementById('status').textContent=m.clipCount+' clips. '+m.listening+' '+m.timing;
</script></html>"""
    }
}
