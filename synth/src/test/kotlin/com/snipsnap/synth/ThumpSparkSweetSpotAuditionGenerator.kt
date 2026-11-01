package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Editable listening candidates within the unchanged R3 voice. Each neighborhood
 * varies one control; names describe intended roles awaiting individual listening.
 * These examples do not join the factory preset bank.
 */
object ThumpSparkSweetSpotAuditionGenerator {
    private const val PACK = "spark-r3-sweet-spots-1.zip"
    private const val HOSTED = "https://snipsnap-spark-audition.bertcalm.chatgpt.site/"
    private val variants = listOf("lower", "center", "upper")
    private data class Candidate(val id: String, val name: String, val role: String, val question: String,
                                 val control: String, val values: List<Float>, val macros: Map<String, Float>)
    private data class Clip(val data: Map<String, Any?>, val native: Snip, val matched: Snip)

    private fun candidate(id: String, name: String, role: String, question: String, control: String,
                          values: List<Float>, tune: Float, decay: Float, punch: Float, metal: Float, color: Float) =
        Candidate(id, name, role, question, control, values, linkedMapOf(
            "TUNE" to tune, "DECAY" to decay, "PUNCH" to punch, "METAL" to metal, "COLOR" to color))

    private val candidates = listOf(
        candidate("r3-start", "R3 START", "The R3 default, retained as a familiar reference.",
            "Choose the contact strength you prefer around the default.", "PUNCH", listOf(.4f, .5f, .6f), .5f, .35f, .5f, .25f, .5f),
        candidate("warm-knock", "WARM KNOCK", "A lower, compact body with little metallic edge.",
            "Choose how much upper body to reveal.", "COLOR", listOf(.18f, .28f, .38f), .25f, .25f, .6f, .08f, .28f),
        candidate("soft-mallet", "SOFT MALLET", "A rounded contact with room for a longer pitched body.",
            "Choose the rise that gives this longer hit its shape.", "PUNCH", listOf(.15f, .25f, .35f), .45f, .6f, .25f, .12f, .48f),
        candidate("hollow-ring", "HOLLOW RING", "Moderate material interaction behind a darker filter setting.",
            "Choose the amount of inharmonic ring within this darker body.", "METAL", listOf(.35f, .45f, .55f), .5f, .55f, .45f, .45f, .32f),
        candidate("bright-chip", "BRIGHT CHIP", "A higher, short strike with an open harmonic body.",
            "Choose the brightness that keeps the short hit useful.", "COLOR", listOf(.62f, .72f, .82f), .8f, .12f, .78f, .25f, .72f),
        candidate("open-clang", "OPEN CLANG", "A longer, open metallic gesture with a settled tail.",
            "Choose the material intensity for the longer ring.", "METAL", listOf(.65f, .75f, .85f), .6f, .7f, .62f, .75f, .78f),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 2) { "usage: ThumpSparkSweetSpotAuditionGenerator [output-directory] [preserved-r3-directory]" }
        val root = File(args.firstOrNull() ?: "../testkit/thump-spark-sweet-spots")
        val baselineDirectory = args.getOrNull(1)?.let { File(it).canonicalFile }
        baselineDirectory?.let { source ->
            require(source.isDirectory && source != root.canonicalFile &&
                !source.toPath().startsWith(root.canonicalFile.toPath()) &&
                !root.canonicalFile.toPath().startsWith(source.toPath())) {
                "baseline and output must be separate directories, neither containing the other"
            }
        }
        check(root.mkdirs() || root.isDirectory) { "cannot create $root" }
        val files = linkedSetOf<File>()
        fun text(path: String, content: String) = File(root, path).also {
            it.parentFile.mkdirs(); it.writeText(content); files += it
        }
        fun wav(path: String, snip: Snip) = WavWriter.write(File(root, path), snip).also { files += it }
        val rendererHashes = listOf("Thump.kt", "ThumpFamilies.kt", "Punch.kt", "Dsp.kt").associateWith { name ->
            hash(listOf(File("src/main/kotlin/com/snipsnap/synth/$name"),
                File("synth/src/main/kotlin/com/snipsnap/synth/$name")).first { it.isFile })
        }
        val clips = linkedMapOf<String, Clip>()
        Thump.render(ThumpVoice.SPARK) // Warm the renderer before local observations.
        for (candidate in candidates) {
            check(candidate.values.size == 3 && candidate.values[1] == candidate.macros.getValue(candidate.control))
            check(candidate.values.zipWithNext().all { (a, b) -> a < b } && candidate.values.all { it in 0f..1f })
            for ((index, value) in candidate.values.withIndex()) {
                val variant = variants[index]
                val id = "${candidate.id}-$variant"
                val macros = candidate.macros + (candidate.control to value)
                check(macros.filterKeys { it != candidate.control } == candidate.macros.filterKeys { it != candidate.control })
                val patch = ThumpPatch("${candidate.name} ${variant.uppercase()}", ThumpVoice.SPARK, macros)
                val started = System.nanoTime()
                val source = patch.render()
                val renderMs = (System.nanoTime() - started) / 1_000_000.0
                validate(source, id)
                val recipe = PadRecipe(patch)
                val recipeText = recipe.toJsonText() + "\n"
                check(source.samples.contentEquals(PadRecipe.fromJsonText(recipeText).render().samples)) { "$id recipe replay changed the sound" }
                check(source.samples.contentEquals(patch.render().samples)) { "$id render was not deterministic" }
                val nativeFile = wav("audio/$id-native.wav", source)
                val matchedFile = wav("audio/$id-matched.wav", AuditionLevel.level(source))
                val recipeFile = text("recipes/$id.json", recipeText)
                val native = WavReader.read(nativeFile)
                val matched = WavReader.read(matchedFile)
                validate(native, "$id native PCM"); validate(matched, "$id matched PCM")
                clips[id] = Clip(linkedMapOf(
                    "id" to id, "candidateId" to candidate.id, "name" to patch.name, "variant" to variant,
                    "control" to candidate.control, "value" to value, "macros" to macros,
                    "heldControls" to macros.filterKeys { it != candidate.control }, "patch" to patch.toJsonValue(),
                    "native" to nativeFile.relativeTo(root).invariantSeparatorsPath,
                    "matched" to matchedFile.relativeTo(root).invariantSeparatorsPath,
                    "recipe" to recipeFile.relativeTo(root).invariantSeparatorsPath,
                    "frames" to native.frameCount, "durationSeconds" to native.durationSeconds, "renderMs" to renderMs,
                    "measures" to linkedMapOf("native" to measures(native), "matched" to measures(matched)),
                    "sha256" to linkedMapOf("native" to hash(nativeFile), "matched" to hash(matchedFile)),
                ), native, matched)
            }
        }
        check(clips.size == 18)
        val baseline = baselineDirectory?.let { source ->
            val baselineFile = File(source, "manifest.json")
            val original = Json.parse(baselineFile.readText()).obj()
            require(original.getValue("auditionRevision").int() == 3 && original.getValue("voice").str() == "SPARK")
            check(original.getValue("rendererSourceSha256").obj().mapValues { it.value.str() } == rendererHashes) {
                "current renderer source differs from preserved R3"
            }
            val anchor = original.getValue("clips").arr().map { it.obj() }.single { it.getValue("id").str() == "punch-050" }
            val center = clips.getValue("r3-start-center")
            val verified = linkedMapOf<String, Any?>()
            for ((kind, actual) in listOf("native" to center.native, "matched" to center.matched)) {
                val relative = anchor.getValue(kind).str()
                val file = File(source, relative).canonicalFile
                require(!File(relative).isAbsolute && file.toPath().startsWith(source.toPath()) && file.isFile)
                check(hash(file) == anchor.getValue("sha256").obj().getValue(kind).str()) { "R3 anchor hash changed" }
                val previous = WavReader.read(file)
                check(previous.sampleRate == actual.sampleRate && previous.channels == actual.channels &&
                    previous.samples.contentEquals(actual.samples)) { "R3 default $kind PCM changed" }
                verified[kind] = linkedMapOf("baselineRelativePath" to relative, "sha256" to hash(file), "exactPcmMatch" to true)
            }
            linkedMapOf("auditionRevision" to 3, "manifestSha256" to hash(baselineFile),
                "rendererSourceSha256" to rendererHashes, "anchorId" to "punch-050", "anchorMatches" to verified)
        }

        fun train(id: String, ids: List<String>, control: String? = null, held: Map<String, Float>? = null): Map<String, Any?> {
            val hits = ids.map { clips.getValue(it) }
            if (control != null) check(hits.all { it.data.getValue("heldControls") == held })
            val interval = maxOf((Dsp.RATE * .6).toInt(), hits.maxOf { it.native.frameCount } + Dsp.RATE / 10)
            val paths = linkedMapOf<String, String>()
            val hashes = linkedMapOf<String, String>()
            var frames = 0
            for (kind in listOf("native", "matched")) {
                val sources = hits.map { if (kind == "native") it.native else it.matched }
                val samples = FloatArray((sources.size - 1) * interval + sources.last().frameCount)
                sources.forEachIndexed { index, hit -> hit.samples.copyInto(samples, index * interval) }
                val file = wav("trains/$id-$kind.wav", Snip(samples, 1, Dsp.RATE))
                val encoded = WavReader.read(file)
                validate(encoded, "$id $kind train")
                frames = encoded.frameCount
                sources.forEachIndexed { index, hit ->
                    val start = index * interval
                    check(hit.samples.contentEquals(encoded.samples.copyOfRange(start, start + hit.frameCount))) { "$id changed hit $index" }
                    val end = if (index == sources.lastIndex) frames else (index + 1) * interval
                    check((start + hit.frameCount until end).all { encoded.samples[it] == 0f }) { "$id gap was not silent" }
                }
                paths[kind] = file.relativeTo(root).invariantSeparatorsPath; hashes[kind] = hash(file)
            }
            return linkedMapOf("id" to id, "control" to control, "heldControls" to held, "order" to ids,
                "native" to paths.getValue("native"), "matched" to paths.getValue("matched"), "sha256" to hashes,
                "frames" to frames, "onsetIntervalFrames" to interval, "onsetIntervalSeconds" to interval.toDouble() / Dsp.RATE,
                "construction" to "Encoded single hits copied unchanged, with silent gaps and no extra gain.",
                "hits" to ids.mapIndexed { index, clipId ->
                    linkedMapOf("clipId" to clipId, "variant" to clips.getValue(clipId).data.getValue("variant"),
                        "onsetFrame" to index * interval, "sourceFrames" to clips.getValue(clipId).native.frameCount,
                        "sourceSha256" to clips.getValue(clipId).data.getValue("sha256"))
                })
        }
        val trains = candidates.map { c -> train("${c.id}-neighbors", variants.flatMap { v -> List(2) { "${c.id}-$v" } },
            c.control, c.macros.filterKeys { it != c.control }) }
        val palette = train("six-centers", candidates.flatMap { c -> List(2) { "${c.id}-center" } })
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "auditionRevision" to 3, "setRevision" to 1, "kind" to "sweet-spot-candidates",
            "title" to "SPARK R3 sweet spots", "engine" to "THUMP", "voice" to "SPARK",
            "status" to "Proposed editable examples awaiting individual owner listening; no factory presets added.",
            "sampleRate" to Dsp.RATE, "oversample" to Dsp.OVERSAMPLE, "effects" to emptyList<String>(),
            "rendererSourceSha256" to rendererHashes, "baseline" to baseline,
            "nativeDefinition" to "Finished R3 instrument output, encoded as PCM24 without added gain.",
            "matchedDefinition" to "One gain per complete clip via AuditionLevel: loudest-200-ms RMS 0.03, peak guard 0.99.",
            "timingDefinition" to "Single warmed local JVM observations; not target-phone measurements.",
            "candidates" to candidates.map { c -> linkedMapOf("id" to c.id, "name" to c.name, "role" to c.role,
                "question" to c.question, "control" to c.control, "centerMacros" to c.macros,
                "values" to c.values, "clipIds" to variants.map { "${c.id}-$it" }, "trainId" to "${c.id}-neighbors") },
            "clips" to clips.values.map { it.data }, "trains" to trains, "palette" to palette, "download" to PACK,
            "listeningPackDefinition" to "Current examples only; its offline page and manifest omit the pack's self-download link.",
        )
        val manifestText = Json.write(json(manifest))
        text("manifest.json", manifestText + "\n")
        text("index.html", page(manifestText))
        text("metrics.csv", buildString {
            append("id,render_ms,frames,seconds,native_peak,native_dc,native_rms,native_loudest_window_rms,drum_class\n")
            for ((id, clip) in clips) {
                val m = measures(clip.native)
                append(listOf(id, clip.data.getValue("renderMs"), clip.native.frameCount, clip.native.durationSeconds,
                    m.getValue("peak"), m.getValue("dc"), m.getValue("rms"), m.getValue("loudestWindowRms"),
                    m.getValue("drumClass")).joinToString(",")).append('\n')
            }
        })
        val pack = File(root, PACK)
        val offlineManifestText = Json.write(json(LinkedHashMap(manifest).apply { put("download", null) }))
        ZipOutputStream(pack.outputStream().buffered()).use { zip ->
            for (file in files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }) {
                val path = file.relativeTo(root).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(path).also { it.time = 0L })
                if (path == "index.html") zip.write(page(offlineManifestText, offline = true).toByteArray())
                else if (path == "manifest.json") zip.write((offlineManifestText + "\n").toByteArray())
                else file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        check(pack.length() < 25L * 1024 * 1024) { "listening pack exceeds the static host's individual file limit" }
        println("Wrote 6 proposed R3 centers, 18 editable recipes, 36 single WAVs and 14 train WAVs under ${root.absolutePath}; preserved-default PCM checked=${baseline != null}")
    }

    private fun validate(snip: Snip, id: String) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.frameCount > 0) { "$id format changed" }
        check(snip.samples.all { it.isFinite() && abs(it) <= 1f } && snip.peak() > 1e-5f) { "$id unsafe or silent" }
        check(abs(snip.samples.sumOf { it.toDouble() } / snip.samples.size) < .005) { "$id DC exceeds 0.005" }
    }

    private fun measures(snip: Snip): Map<String, Any> = linkedMapOf(
        "peak" to snip.peak(), "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size,
        "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "loudestWindowRms" to Loudness.of(snip), "drumClass" to Classifier.classify(snip).drumClass.name)

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported manifest value: ${value.javaClass}")
    }

    private fun page(manifest: String, offline: Boolean = false): String {
        val back = if (offline) HOSTED else "../"
        val download = if (offline) "" else "<a href=\"$PACK\" download>Download listening pack</a>"
        return """
        <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>SPARK R3 sweet spots</title><style>
        :root{color-scheme:dark;font-family:system-ui,sans-serif;background:#11161a;color:#edf3f1}body{max-width:1050px;margin:auto;padding:30px 20px 70px}h1{font-size:2.4rem;margin-bottom:8px}p{line-height:1.6;max-width:78ch;color:#c8d7d2}a{color:#82e4c5}nav,.players{display:flex;gap:16px;flex-wrap:wrap;margin:18px 0}.players>label{min-width:0;max-width:100%;font-size:.85rem;color:#b7c9c3}audio{display:block;margin-top:6px;max-width:100%;height:38px}article,.palette{padding:20px;background:#1d282b;border-radius:14px;margin:18px 0}h2{margin:0 0 8px}small{display:block;margin:8px 0;color:#a9bdb7;line-height:1.5}details{margin:14px 0}summary{cursor:pointer}pre{white-space:pre-wrap;font-size:.8rem}fieldset{border:1px solid #506862;border-radius:8px;padding:10px 12px;margin-top:16px;display:flex;flex-wrap:wrap;gap:12px}legend{padding:0 5px;color:#c8d7d2}fieldset label{white-space:nowrap}button{background:#82e4c5;color:#10211d;border:0;border-radius:8px;padding:10px 14px;font:inherit;cursor:pointer}button:focus-visible,a:focus-visible,summary:focus-visible{outline:3px solid #fff;outline-offset:4px}.variant{border-top:1px solid #40524c;padding:10px 0}#choices-status{min-height:1.5em}
        </style><body><h1>SPARK R3 · sweet spots</h1>
        <p>Six proposed starting points inside the R3 ranges you preferred. Start with the center playlist, then compare a small neighborhood around each sound. Each local sequence plays lower twice, center twice, then upper twice; only the named control changes.</p>
        <p>Matched players keep the hits at a comparison level. Native players retain the instrument's output. These named examples await your individual listen.</p>
        <nav><a href="$back">R3 ranges and preserved revisions</a>$download<a href="manifest.json">Settings and measurements</a><button id="download-choices" type="button">Download my choices</button></nav>
        <small id="choices-status">Mark a version you prefer, or leave it undecided. Choices stay in this browser.</small>
        <section class="palette"><h2>Six centers</h2><p id="palette-order"></p><div id="palette" class="players"></div><details><summary>Native playlist</summary><div id="native-palette" class="players"></div></details></section>
        <main id="candidates"></main>
        <p>Editable recipes are included for every version. These examples add no effects and do not enter the factory preset bank. Phone timing and MPC hardware loading remain unchecked.</p>
        <script type="application/json" id="manifest">${manifest.replace("<", "\\u003c")}</script><script>
        const manifest=JSON.parse(document.getElementById('manifest').textContent),clips=new Map(manifest.clips.map(c=>[c.id,c])),trains=new Map(manifest.trains.map(t=>[t.id,t]));
        const key='snipsnap-spark-r3-sweet-spots-1',allowed=['lower','center','upper','undecided'];let choices={},canStore=true;
        try{const saved=JSON.parse(localStorage.getItem(key)||'{}');for(const c of manifest.candidates)if(allowed.includes(saved[c.id]))choices[c.id]=saved[c.id]}catch{canStore=false}
        function element(tag,text){const e=document.createElement(tag);if(text!==undefined)e.textContent=text;return e}
        function settings(macros){return Object.entries(macros).map(([name,value])=>name+' '+Math.round(value*100)+'%').join(' · ')}
        function player(parent,title,path){const label=element('label',title),audio=document.createElement('audio');audio.controls=true;audio.preload='none';audio.src=path;label.append(audio);parent.append(label)}
        function recipe(parent,clip){const a=element('a','Download editable recipe');a.href=clip.recipe;a.download='';parent.append(a)}
        function updateStatus(){const n=manifest.candidates.filter(c=>['lower','center','upper'].includes(choices[c.id])).length;document.getElementById('choices-status').textContent=n+' of 6 choices marked. '+(canStore?'Choices stay in this browser; download them to keep a file.':'Browser storage is unavailable; download your choices to keep them.')}
        document.getElementById('palette-order').textContent=manifest.candidates.map(c=>c.name).join(' → ')+' · two hits of each center.';
        player(document.getElementById('palette'),'Matched center playlist',manifest.palette.matched);player(document.getElementById('native-palette'),'Native center playlist',manifest.palette.native);
        for(const c of manifest.candidates){
          const article=element('article'),center=clips.get(c.id+'-center'),train=trains.get(c.trainId);article.append(element('h2',c.name),element('p',c.role),element('small','Center: '+settings(center.macros)));
          const primary=element('div');primary.className='players';player(primary,'Center · matched',center.matched);player(primary,c.control+' neighborhood · matched',train.matched);article.append(primary,element('p',c.question),element('small','Lower '+Math.round(c.values[0]*100)+'% → center '+Math.round(c.values[1]*100)+'% → upper '+Math.round(c.values[2]*100)+'% · two hits each · onsets every '+(train.onsetIntervalSeconds*1000).toFixed(1)+' ms.'),element('small','Other controls held: '+settings(train.heldControls)));recipe(article,center);
          const fieldset=element('fieldset');fieldset.append(element('legend','My preferred '+c.control+' setting'));
          for(const v of allowed){const label=element('label'),input=document.createElement('input');input.type='radio';input.name='choice-'+c.id;input.value=v;input.checked=(choices[c.id]||'undecided')===v;input.addEventListener('change',()=>{choices[c.id]=v;try{localStorage.setItem(key,JSON.stringify(choices))}catch{canStore=false}updateStatus()});label.append(input,document.createTextNode(' '+v[0].toUpperCase()+v.slice(1)));fieldset.append(label)}article.append(fieldset);
          const detail=element('details');detail.append(element('summary','Individual versions, native audio and complete settings'));
          const native=element('div');native.className='players';player(native,'Center · native',center.native);player(native,'Neighborhood · native',train.native);detail.append(native);
          for(const id of c.clipIds){const clip=clips.get(id),row=element('div');row.className='variant';row.append(element('strong',clip.variant.toUpperCase()+' · '+c.control+' '+Math.round(clip.value*100)+'%'),element('small',settings(clip.macros)));const players=element('div');players.className='players';player(players,'Matched',clip.matched);player(players,'Native',clip.native);row.append(players);recipe(row,clip);row.append(element('pre',JSON.stringify(clip.patch,null,2)));detail.append(row)}article.append(detail);document.getElementById('candidates').append(article);
        }
        document.getElementById('download-choices').addEventListener('click',()=>{const data={auditionRevision:3,setRevision:1,engine:manifest.engine,voice:manifest.voice,rendererSourceSha256:manifest.rendererSourceSha256,choices:manifest.candidates.map(c=>{const choice=choices[c.id]||'undecided',clip=clips.get(c.id+'-'+choice);return {candidateId:c.id,name:c.name,choice,control:c.control,value:clip?clip.value:null,macros:clip?clip.macros:null,recipe:clip?clip.recipe:null}})};const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)+'\n'],{type:'application/json'})),a=element('a');a.href=url;a.download='spark-r3-sweet-spot-choices.json';document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000)});
        document.addEventListener('play',event=>{for(const audio of document.querySelectorAll('audio'))if(audio!==event.target)audio.pause()},true);updateStatus();
        </script></body></html>
        """.trimIndent() + "\n"
    }
}
