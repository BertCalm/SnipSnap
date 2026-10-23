package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Native finite SKIRL listening matrix, reproducible recipes and honest render-cost ledger. */
object SkirlAuditionGenerator {
    private val controls = listOf("TUNE", "LEAN", "BRAKE", "EDGE", "PLATE", "COLLAPSE")
    private val interactions = listOf("LEAN" to "BRAKE", "EDGE" to "COLLAPSE",
        "BRAKE" to "COLLAPSE", "EDGE" to "PLATE")
    private const val RATE = Skirl.RATE

    private data class Request(val id: String, val title: String, val section: String,
        val voice: SkirlVoice, val macros: Map<String, Float> = Skirl.defaults(voice),
        val midi: Int = 48, val velocity: Float = 1f, val inPack: Boolean = false)
    private data class Rendered(val request: Request, val probe: Skirl.Probe,
        val elapsedMillis: Double)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Supply one dedicated audition directory." }
        val output = File(args.firstOrNull() ?: "../testkit/skirl-audition").canonicalFile
        val repo = File("..").canonicalFile
        require(generateSequence(repo) { it.parentFile }.none { it == output } &&
            output != File(".").canonicalFile && output.parentFile != null) {
            "Use a dedicated audition directory."
        }
        val marker = File(output, ".skirl-audition")
        require(!output.exists() || output.isDirectory &&
            (output.listFiles().orEmpty().isEmpty() || marker.isFile)) {
            "An existing output must be an empty directory or a prior marked SKIRL audition."
        }
        output.mkdirs()
        marker.writeText("snipsnap-skirl-audition\n")
        val quick = System.getProperty("skirl.quick", "false").toBoolean()
        val benchmark = benchmarks()
        writeJson(File(output, "benchmark.json"), benchmark)
        val requests = requests(quick)
        val rendered = requests.mapIndexed { index, request ->
            val begin = System.nanoTime()
            val probe = Skirl.inspect(request.voice, request.macros, request.midi, request.velocity)
            check(probe.raw.isNotEmpty() && probe.raw.all { it.isFinite() })
            check(probe.acoustics.combinedBudgetSatisfied)
            println("SKIRL ${index + 1}/${requests.size}: ${request.id}")
            Rendered(request, probe, (System.nanoTime() - begin) / 1e6)
        }
        // One safe gain across the entire matrix preserves acoustic amplitude
        // changes. Production files use the engine's own house leveling instead.
        val commonGain = minOf(1.0, .95 / rendered.maxOf { peak(it.probe.raw) }.coerceAtLeast(1e-12))
        val clips = rendered.map { render ->
            val request = render.request
            val production = "production/${request.id}.wav"
            val raw = "common-gain/${request.id}.wav"
            val recipe = "recipes/${request.id}.json"
            val diagnostics = "diagnostics/${request.id}.json"
            wav(File(output, production), render.probe.snip.samples)
            wav(File(output, raw), scaled(render.probe.raw, commonGain))
            val patch = SkirlPatch(request.title, request.voice, request.macros, request.midi, request.velocity)
            val saved = File(output, recipe).also { it.parentFile.mkdirs(); it.writeText(patch.toJsonText()) }
            check(SkirlPatch.fromJsonText(saved.readText()) == patch)
            val metrics = metrics(render.probe, request.voice, request.macros, request.midi)
            writeJson(File(output, diagnostics), metrics + mapOf("inspectMillis" to render.elapsedMillis,
                "physicalParameters" to physical(render.probe.motion.params), "trace" to trace(render.probe.motion)))
            linkedMapOf<String, Any?>("id" to request.id, "title" to request.title,
                "section" to request.section, "voice" to request.voice.name, "midi" to request.midi,
                "velocity" to request.velocity, "macros" to request.macros, "production" to production,
                "commonGain" to raw, "recipe" to recipe, "diagnostics" to diagnostics,
                "inPack" to request.inPack, "metrics" to metrics)
        }.toMutableList()
        val referenceDirectory = System.getProperty("skirl.pythonReference")?.takeIf { it.isNotBlank() }?.let(::File)
        val comparisons = comparisons(output, referenceDirectory)
        val manifest = linkedMapOf<String, Any?>("format" to "snipsnap-skirl-audition", "version" to 1,
            "title" to "Skirl / Native Kotlin first listen", "engine" to "SKIRL", "model" to Skirl.MODEL_VERSION,
            "generatedAt" to Instant.now().toString(), "quick" to quick, "sampleRate" to RATE,
            "channels" to 1, "bitDepth" to 16, "hold" to false, "clipCount" to clips.size,
            "scope" to "Two provisional finite voices. No factory kit, app picker or HOLD loop. Owner native-audio listening is pending.",
            "gainPolicy" to mapOf("commonGain" to commonGain,
                "common" to "One safe gain for every conditioned prelevel clip; amplitude differences are retained.",
                "production" to "Actual Skirl.render house loudness level and tail fade.",
                "comparison" to "Python and Kotlin reference fixtures separately RMS matched to .1, with common pair headroom."),
            "benchmark" to "benchmark.json", "clips" to clips, "comparisons" to comparisons,
            "pythonReferenceNote" to if (comparisons.isEmpty()) "Python reference directory or WAVs unavailable; pairs omitted." else
                "Same research physical inputs. Kotlin revises both signed rim texture and note-aware contact pulse width; the heard Python source used positive texture and fixed contact width. These are acoustic-revision comparisons, not waveform-identity tests or ordinary production patches.")
        writeJson(File(output, "manifest.json"), manifest)
        File(output, "index.html").writeText(page(manifest))
        val selected = clips.filter { it["inPack"] == true }
        val pack = File(output, "listening-pack").also { it.mkdirs() }
        val packPaths = mutableListOf("manifest.json", "index.html", "benchmark.json")
        for (clip in selected) for (key in listOf("production", "commonGain", "recipe", "diagnostics")) {
            val path = clip.getValue(key) as String
            File(output, path).copyTo(File(pack, path).also { it.parentFile.mkdirs() }, overwrite = true)
            packPaths += path
        }
        // Copy only assets named by this manifest. Prior generator runs may
        // have left other research files; they must not enter this ZIP.
        for (comparison in comparisons) for (key in listOf("python", "kotlin", "montage", "originalPython", "fixture")) {
            val path = comparison.getValue(key) as String
            File(output, path).copyTo(File(pack, path).also { it.parentFile.mkdirs() }, overwrite = true)
            packPaths += path
        }
        File(output, "benchmark.json").copyTo(File(pack, "benchmark.json"), overwrite = true)
        val packManifest = manifest + mapOf("clips" to selected, "clipCount" to selected.size,
            "scope" to "Selected first-listen pack: defaults, low notes, soft contact and a rough corner for each voice. Full matrix remains in the generator output directory.")
        writeJson(File(pack, "manifest.json"), packManifest)
        File(pack, "index.html").writeText(page(packManifest))
        val archive = File(output, "skirl-listening-pack.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (path in packPaths.distinct().sorted()) {
                val file = File(pack, path)
                zip.putNextEntry(ZipEntry(path))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        println("SKIRL wrote ${clips.size} native clips, ${comparisons.size} Python pairs and measured benchmarks to $output")
        println("SKIRL selected listening ZIP: ${archive.length()} bytes (target under 25 MiB: ${archive.length() < 25L * 1024 * 1024})")
    }

    private fun requests(quick: Boolean): List<Request> = buildList {
        for (voice in SkirlVoice.entries) {
            val name = voice.name.lowercase(Locale.ROOT)
            add(Request("$name-default", "$voice / default C3", "Defaults", voice, inPack = true))
            for (midi in if (quick) listOf(36, 72) else listOf(36, 60, 72))
                add(Request("$name-note-$midi", "$voice / MIDI $midi", "Register", voice, midi = midi, inPack = midi == 36))
            for (velocity in listOf(.25f, .6f)) add(Request("$name-velocity-${(velocity * 100).toInt()}",
                "$voice / velocity $velocity", "Velocity", voice, velocity = velocity, inPack = velocity == .25f))
            val corner = mapOf("EDGE" to 1f, "PLATE" to 0f)
            add(Request("$name-rough-corner", "$voice / rough contact, short plate", "Interactions", voice,
                Skirl.defaults(voice) + corner, inPack = true))
            if (!quick) {
                for (control in controls) for (step in listOf(0f, .25f, .5f, .75f, 1f))
                    add(Request("$name-${control.lowercase(Locale.ROOT)}-${(step * 100).toInt()}",
                        "$voice / $control $step", "Five-step sweeps", voice, Skirl.defaults(voice) + (control to step)))
                for ((a, b) in interactions) for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f))
                    add(Request("$name-${a.lowercase(Locale.ROOT)}${x.toInt()}-${b.lowercase(Locale.ROOT)}${y.toInt()}",
                        "$voice / $a $x × $b $y", "Interactions", voice,
                        Skirl.defaults(voice) + mapOf(a to x, b to y)))
            }
        }
    }

    private fun benchmarks(): Map<String, Any?> {
        val results = SkirlVoice.entries.map { voice ->
            // Warm the JVM before either cache condition; "cold" below means
            // physical trace cache cold, not an unmeasured cold process start.
            repeat(3) { Skirl.render(voice) }
            SkirlDynamics.clearCache()
            val coldBegan = System.nanoTime()
            val cold = Skirl.render(voice)
            val coldMillis = (System.nanoTime() - coldBegan) / 1e6
            val warm = (0 until 7).map {
                val began = System.nanoTime()
                Skirl.render(voice)
                (System.nanoTime() - began) / 1e6
            }.sorted()
            val stage = Skirl.inspect(voice)
            linkedMapOf<String, Any?>("voice" to voice.name, "model" to Skirl.MODEL_VERSION,
                "midi" to Skirl.DEFAULT_MIDI, "velocity" to 1.0,
                "durationSeconds" to cold.samples.size.toDouble() / RATE,
                "jvmWarmupRenders" to 3, "warmRepeats" to 7,
                "coldMechanicsCacheTotalMillis" to coldMillis, "warmMedianTotalMillis" to warm[3],
                "warmMinMillis" to warm.first(), "warmMaxMillis" to warm.last(), "warmMillis" to warm,
                "warmTargetMillis" to 50, "warmTargetMet" to (warm[3] <= 50.0),
                "separateWarmInspectStageMillis" to mapOf("preparation" to stage.preparationNanos / 1e6,
                    "synthesis" to stage.synthesisNanos / 1e6, "output" to stage.outputNanos / 1e6))
        }
        return linkedMapOf("measuredBy" to "Kotlin Skirl.render in this JVM; no performance assertions",
            "javaVersion" to System.getProperty("java.version"), "os" to System.getProperty("os.name"),
            "processors" to Runtime.getRuntime().availableProcessors(), "voices" to results,
            "limitations" to "Desktop/container measurements, not Android. Cold means cleared mechanics cache after JVM warmup. Stage timings use a separate diagnostic render and include its copy overhead.")
    }

    private fun metrics(p: Skirl.Probe, voice: SkirlVoice, macros: Map<String, Float>, midi: Int): Map<String, Any?> {
        val a = p.acoustics
        val d = p.motion.diagnostics
        val target = Skirl.frequencyFor(voice, macros.getValue("TUNE"), midi).toDouble()
        fun pitch(from: Double, window: Double): Map<String, Any?> {
            val estimate = Pitch.detect(p.snip, from.toFloat(), window.toFloat())
            return mapOf("hz" to estimate?.hz, "confidence" to estimate?.confidence,
                "centsFromRequestedRoot" to estimate?.let { 1200 * ln(it.hz / target) / ln(2.0) })
        }
        val raw = p.raw
        return linkedMapOf("durationSeconds" to p.snip.samples.size.toDouble() / RATE,
            "targetHz" to target, "rollingPitch" to pitch(minOf(.15, a.rollingSeconds * .25), .25),
            "tailPitch" to pitch(a.restStartSeconds + .05, .35),
            "peak" to peak(p.snip.samples), "dc" to p.snip.samples.average(),
            "loudness" to Loudness.of(p.snip), "prelevelPeak" to peak(raw), "prelevelRms" to rms(raw),
            "classifier" to Classifier.classify(p.snip).drumClass.name,
            "declaredClass" to Skirl.drumClassFor(voice, macros).name,
            "classificationNote" to "Finite pitched intent; generic classifier files renders longer than 1.5 seconds as LOOP.",
            "rollingSeconds" to a.rollingSeconds, "terminalSeconds" to a.terminalSeconds,
            "restStartSeconds" to a.restStartSeconds, "sourceSeconds" to a.sourceSeconds,
            "failureWithdrawalSeconds" to a.failureWithdrawalSeconds,
            "rollingEvents" to a.rollingEvents.size, "terminalEvents" to a.terminalEvents.size,
            "assignedRollingEnergy" to a.assignedRollingEnergy, "measuredRollingEnergy" to a.measuredRollingEnergy,
            "assignedTerminalEnergy" to a.assignedTerminalEnergy, "incrementalTerminalEnergy" to a.incrementalTerminalEnergy,
            "remainingMechanicalEnergy" to a.remainingMechanicalEnergy,
            "prefilterEnergy" to a.prefilterEnergy, "postfilterEnergy" to a.postfilterEnergy,
            "combinedBudgetSatisfied" to a.combinedBudgetSatisfied, "restExcitationPeak" to a.restExcitationPeak,
            "fineTexture" to a.fineTexture, "signedTexture" to a.signedTexture, "seedContext" to a.seedContext,
            "contactWidthSeconds" to a.contactWidthSeconds,
            "exit" to p.motion.exit.name, "reason" to p.motion.reason.name,
            "initialEnergy" to d.initialEnergy, "finalEnergy" to d.finalEnergy,
            "physicalDurationSeconds" to d.physicalDuration, "minResistancePower" to d.minResistancePower,
            "resistanceWork" to d.resistanceWork, "relativeEnergyError" to d.relativeEnergyError,
            "maxEnergyError" to d.maxEnergyError, "minNormal" to d.minNormal,
            "maxFriction" to d.maxFriction, "maxQuaternionError" to d.maxQuaternionError,
            "maxHeightVelocityError" to d.maxHeightVelocityError,
            "startContactHz" to d.startContactHz, "endContactHz" to d.endContactHz,
            "solverEvaluations" to d.evaluations, "acceptedSteps" to d.acceptedSteps,
            "rejectedSteps" to d.rejectedSteps, "velocityProjectionUsed" to d.velocityProjectionUsed,
            "inspectStageMillis" to mapOf("preparation" to p.preparationNanos / 1e6,
                "synthesis" to p.synthesisNanos / 1e6, "output" to p.outputNanos / 1e6))
    }

    private fun comparisons(output: File, directory: File?): List<Map<String, Any?>> {
        if (directory == null || !directory.isDirectory) return emptyList()
        return SkirlVoice.entries.mapNotNull { voice ->
            val name = voice.name.lowercase(Locale.ROOT)
            val source = File(directory, if (voice == SkirlVoice.ROLL) "09_motion_fine_texture_C3.wav" else "10_late_runout_texture_C3.wav")
            if (!source.isFile) return@mapNotNull null
            val python = WavReader.read(source)
            require(python.sampleRate == RATE && python.channels == 1) { "Python comparison must be mono 44.1 kHz." }
            val angle = if (voice == SkirlVoice.ROLL) 22.85 else 8.0
            val brake = if (voice == SkirlVoice.ROLL) .4 else .2
            val terminal = if (voice == SkirlVoice.ROLL) 7.0 else 1.9
            val collapse = if (voice == SkirlVoice.ROLL) 0f else .85f
            val parameters = SkirlDynamics.Parameters(angle * PI / 180, .002 + .03 * brake.pow(1.5), terminal * PI / 180)
            val macros = Skirl.defaults(voice) + mapOf("EDGE" to .4f, "PLATE" to .5f, "COLLAPSE" to collapse)
            val native = Skirl.inspectReference(voice, parameters, macros, midi = 48, velocity = 1f)
            check(native.motion.exit == SkirlDynamics.Exit.TERMINAL && native.acoustics.combinedBudgetSatisfied) {
                "$voice Python reference fixture did not complete a valid bounded runout."
            }
            val pythonGain = .1 / rms(python.samples).coerceAtLeast(1e-12)
            val kotlinGain = .1 / rms(native.raw).coerceAtLeast(1e-12)
            val headroom = minOf(1.0, .95 / maxOf(peak(python.samples) * pythonGain, peak(native.raw) * kotlinGain).coerceAtLeast(1e-12))
            val a = scaled(python.samples, pythonGain * headroom)
            val b = scaled(native.raw, kotlinGain * headroom)
            val pythonPath = "comparisons/$name-python.wav"
            val kotlinPath = "comparisons/$name-kotlin.wav"
            val montagePath = "comparisons/$name-python-then-kotlin.wav"
            wav(File(output, pythonPath), a)
            wav(File(output, kotlinPath), b)
            wav(File(output, montagePath), a + FloatArray((.6 * RATE).toInt()) + b)
            val original = "references/$name-python-original.wav"
            source.copyTo(File(output, original).also { it.parentFile.mkdirs() }, overwrite = true)
            val fixture = "fixtures/$name-python-comparison.json"
            writeJson(File(output, fixture), mapOf("kind" to "research-physical-comparison-revised-acoustics", "productionPatch" to false,
                "voice" to voice.name, "model" to Skirl.MODEL_VERSION, "midi" to 48, "velocity" to 1.0,
                "macros" to macros, "physical" to physical(parameters), "fineTexture" to true,
                "pythonAcousticTexture" to "positive", "kotlinAcousticTexture" to "signed-rim-polarity",
                "signedTexture" to native.acoustics.signedTexture,
                "contactWidthSeconds" to native.acoustics.contactWidthSeconds,
                "pulseWidthRevision" to "Kotlin uses note-aware soft=min(.006,.75/hz), hard=min(.0007,.20/hz), exponential EDGE interpolation and velocity factor 1.6-.6*velocity. Python retains the heard fixed-width source.",
                "replay" to "Skirl.inspectReference with recorded physical parameters; ordinary SkirlPatch uses calibrated production macro maps.",
                "nativeMetrics" to metrics(native, voice, macros, 48)))
            linkedMapOf<String, Any?>("title" to "$voice / heard Python versus revised Kotlin texture",
                "python" to pythonPath, "kotlin" to kotlinPath, "montage" to montagePath,
                "originalPython" to original, "fixture" to fixture,
                "pythonRms" to rms(a), "kotlinRms" to rms(b), "headroom" to headroom,
                "note" to "Python first, 0.6 seconds silence, Kotlin second. Same research physical inputs, C3 and acoustic controls. Kotlin revises signed rim texture and note-aware contact pulse width; Python retains the heard positive texture and fixed width. This compares both acoustic revisions. Physical overrides are recorded outside production patches.")
        }
    }

    private fun trace(trace: SkirlDynamics.Trace): List<Map<String, Any?>> {
        val stride = maxOf(1, trace.times.size / 180)
        return trace.times.indices.filter { it % stride == 0 || it == trace.times.lastIndex }.map { i ->
            mapOf("seconds" to trace.times[i], "alpha" to trace.alphas[i],
                "contactPhase" to trace.contactPhases[i], "rimPhase" to trace.rimPhases[i],
                "normal" to trace.normals[i], "resistancePower" to trace.resistancePower[i],
                "mechanicalEnergy" to trace.mechanicalEnergy[i])
        }
    }

    private fun physical(p: SkirlDynamics.Parameters): Map<String, Any?> = mapOf(
        "leanAlphaRad" to p.leanAlphaRad, "muRoll" to p.muRoll, "terminalAlphaRad" to p.terminalAlphaRad,
        "timeScale" to p.timeScale, "mass" to p.mass, "radius" to p.radius, "thickness" to p.thickness,
        "gravity" to p.gravity, "staticFriction" to p.staticFriction, "epsilonOmega" to p.epsilonOmega,
        "maxSeconds" to p.maxSeconds, "descriptorRate" to p.descriptorRate, "maxContactHz" to p.maxContactHz,
        "relativeTolerance" to p.relativeTolerance, "absoluteTolerance" to p.absoluteTolerance,
        "maxStep" to p.maxStep, "maxEvaluations" to p.maxEvaluations, "model" to p.model)

    private fun peak(samples: FloatArray) = samples.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
    private fun scaled(samples: FloatArray, gain: Double) = FloatArray(samples.size) { (samples[it] * gain).toFloat() }
    private fun wav(file: File, samples: FloatArray) = WavWriter.write(file, Snip(samples, 1, RATE), WavWriter.BitDepth.PCM_16)
    private fun writeJson(file: File, value: Any?) { file.parentFile.mkdirs(); file.writeText(Json.write(json(value))) }
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

    /** Embed data so an extracted ZIP opens directly from file:// without a server. */
    private fun page(manifest: Map<String, Any?>): String {
        val embedded = Json.write(json(manifest)).replace("<", "\\u003c").replace(">", "\\u003e")
            .replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return pageTemplate.replace("__SKIRL_MANIFEST__", embedded)
    }

    private val pageTemplate = """<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Skirl / Native Kotlin first listen</title><style>body{font:16px system-ui;background:#142025;color:#f0eee6;max-width:1050px;margin:auto;padding:32px}h1{font-size:36px}p{line-height:1.55;color:#cbd3d3}article{border:1px solid #425359;border-radius:12px;padding:18px;margin:16px 0}audio{width:100%;margin:6px 0}a{color:#e6bd82}select{font:inherit;padding:8px}label{display:block;margin:8px 0}small{color:#abbcbd}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px}</style>
<h1>Skirl</h1><p>Knock → flutter → whirr → shudder → ringing stillness. Dry mono native Kotlin renders. Compare the root, increasing contact density and bounded finish. HOLD and production preset approval remain open.</p>
<p><a href="manifest.json">Manifest and metrics</a> · <a href="benchmark.json">Measured render latency</a></p><div id="pairs"></div>
<label>Section <select id="filter"><option>All</option></select></label><div id="clips"></div><p id="status">Loading local manifest…</p>
<script type="application/json" id="manifest-data">__SKIRL_MANIFEST__</script>
<script>const el=id=>document.getElementById(id);function node(tag,text){const e=document.createElement(tag);if(text!==undefined)e.textContent=text;return e}function player(parent,label,path){parent.append(node('label',label));const a=node('audio');a.controls=true;a.preload='none';a.src=path;parent.append(a)}function link(parent,label,path){const a=node('a',label);a.href=path;parent.append(a,document.createTextNode(' · '))}Promise.resolve().then(()=>JSON.parse(el('manifest-data').textContent)).then(m=>{for(const c of m.comparisons){const a=node('article');a.append(node('h2',c.title),node('p',c.note));player(a,'Python first, then Kotlin',c.montage);const g=node('div');g.className='grid';const x=node('div'),y=node('div');player(x,'Python / matched',c.python);player(y,'Kotlin / matched',c.kotlin);g.append(x,y);a.append(g);link(a,'Exact fixture',c.fixture);link(a,'Original Python WAV',c.originalPython);el('pairs').append(a)}for(const section of [...new Set(m.clips.map(c=>c.section))])el('filter').append(node('option',section));function draw(){el('clips').replaceChildren();for(const c of m.clips.filter(c=>el('filter').value==='All'||c.section===el('filter').value)){const a=node('article');a.append(node('h2',c.title),node('small',c.section+' · '+c.metrics.durationSeconds.toFixed(2)+' s'));player(a,'Production level',c.production);player(a,'Common gain / amplitude differences preserved',c.commonGain);link(a,'Saved patch',c.recipe);link(a,'Diagnostics',c.diagnostics);a.append(node('p','Judge whether the pitch stays clear, contacts merge naturally and the finish settles without an explosive hit.'));el('clips').append(a)}}el('filter').onchange=draw;draw();el('status').textContent=m.clipCount+' native cases. '+m.scope+' '+m.pythonReferenceNote}).catch(e=>el('status').textContent='Unable to read the embedded audition data: '+e.message);</script></html>"""
}
