package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Revision-two evidence for SPARK and its ordered expression family. Native
 * means the finished THUMP output, before audition matching. All recipes replay
 * that output; the matched WAVs apply only [AuditionLevel]'s single gain.
 * No factory presets or accepted sound-character claims are authored here.
 */
object ThumpSparkAuditionGenerator {
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private const val PACK = "thump-spark-r2-audition.zip"
    private const val KIT_PACK = "thump-spark-r2-mpc-kit.zip"
    private const val FAMILY_NATIVE = "family-r2-native.wav"
    private const val FAMILY_MATCHED = "family-r2-matched.wav"
    private const val KIT_NAME = "SnipSnap Spark Audition"
    private data class Baseline(val metadata: Map<String, Any?>, val clips: Map<String, Map<String, JsonValue>>)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 2) { "usage: ThumpSparkAuditionGenerator [output-directory] [baseline-directory]" }
        val root = File(args.firstOrNull() ?: "../testkit/thump-spark-audition")
        check(root.mkdirs() || root.isDirectory) { "cannot create $root" }
        // Preserve the actual first-listen files before rendering anything new.
        // A saved old recipe replayed through new DSP would not be a baseline.
        val baseline = args.getOrNull(1)?.let { preserveBaseline(root, File(it)) }
        val base = ThumpPatch("SPARK START", ThumpVoice.SPARK, Thump.defaults(ThumpVoice.SPARK))
        // Warm one render before recording per-hit timings. These are local JVM
        // wall-clock observations, not measurements of a phone or a latency gate.
        base.render()
        val clips = mutableListOf<Map<String, Any?>>()
        val familyNative = mutableListOf<Snip>()
        val familyMatched = mutableListOf<Snip>()

        fun clip(id: String, title: String, section: String, patch: ThumpPatch,
                 position: Float? = null, control: String? = null): Pair<Snip, Snip> {
            val start = System.nanoTime()
            val source = patch.render()
            val renderMs = (System.nanoTime() - start) / 1_000_000.0
            validate(source, id)
            val recipe = PadRecipe(patch)
            val replay = PadRecipe.fromJsonText(recipe.toJsonText()).render()
            check(source.samples.contentEquals(replay.samples)) { "$id recipe did not replay the source" }
            val rawPath = "audio/r2-$id-native.wav"
            val matchedPath = "audio/r2-$id-matched.wav"
            val recipePath = "recipes/r2-$id.json"
            val nativeFile = WavWriter.write(File(root, rawPath), source)
            val matchedFile = WavWriter.write(File(root, matchedPath), AuditionLevel.level(source))
            File(root, recipePath).also { it.parentFile.mkdirs() }.writeText(recipe.toJsonText() + "\n")
            // Report the actual encoded downloads, with quantization included.
            val native = WavReader.read(nativeFile)
            val matched = WavReader.read(matchedFile)
            validate(native, "$id native PCM")
            validate(matched, "$id matched PCM")
            clips += linkedMapOf(
                "id" to id, "title" to title, "section" to section,
                "sweptControl" to control,
                "heldControls" to patch.macros.filterKeys { it != control },
                "expressionPosition" to position, "patch" to patch.toJsonValue(),
                "native" to rawPath, "matched" to matchedPath, "recipe" to recipePath,
                "renderMs" to renderMs, "durationSeconds" to native.durationSeconds,
                "measures" to linkedMapOf("native" to measures(native), "matched" to measures(matched)),
                "sha256" to linkedMapOf("native" to hash(nativeFile), "matched" to hash(matchedFile)),
            )
            return native to matched
        }

        ThumpFamilies.patches(base).forEachIndexed { index, patch ->
            val position = ThumpFamilies.DEFAULT_POSITIONS[index]
            val (native, matched) = clip("family-${index + 1}", "Expression ${percent(position)}",
                "Expression family", patch, position)
            familyNative += native
            familyMatched += matched
        }
        for (spec in Thump.macrosFor(ThumpVoice.SPARK)) {
            for (value in steps) {
                clip("${spec.name.lowercase(Locale.ROOT)}-${percent(value)}",
                    "${spec.name} ${percent(value)}", "${spec.name} sweep",
                    base.copy(name = "${spec.name} ${percent(value)}", macros = base.macros + (spec.name to value)),
                    control = spec.name)
            }
        }
        for ((index, tune) in listOf(.25f, .5f, .75f).withIndex()) {
            clip("register-${index + 1}", "Register ${index + 1}", "Three registers",
                base.copy(name = "SPARK NOTE ${index + 1}", macros = base.macros + ("TUNE" to tune)), control = "TUNE")
        }
        for (metal in listOf(0f, 1f)) for (color in listOf(0f, 1f)) {
            val id = "pair-metal-${percent(metal)}-color-${percent(color)}"
            clip(id, "METAL ${percent(metal)} / COLOR ${percent(color)}", "METAL and COLOR corners",
                base.copy(name = "SPARK PAIR", macros = base.macros + mapOf("METAL" to metal, "COLOR" to color)))
        }
        for (value in steps) {
            clip("color-metal000-${percent(value)}", "COLOR ${percent(value)} / METAL 000",
                "COLOR sweep with METAL 0",
                base.copy(name = "COLOR DRY ${percent(value)}",
                    macros = base.macros + mapOf("METAL" to 0f, "COLOR" to value)), control = "COLOR")
        }
        for (value in steps) {
            clip("metal-color025-${percent(value)}", "METAL ${percent(value)} / COLOR 025",
                "METAL sweep with COLOR 25",
                base.copy(name = "METAL LOW ${percent(value)}",
                    macros = base.macros + mapOf("COLOR" to .25f, "METAL" to value)), control = "METAL")
        }
        check(clips.size == 46 && clips.map { it.getValue("id") }.distinct().size == 46)
        val comparisons = baseline?.let { controlComparisons(it, clips) } ?: emptyList()
        val nativeStrip = WavWriter.write(File(root, FAMILY_NATIVE), strip(familyNative))
        val matchedStrip = WavWriter.write(File(root, FAMILY_MATCHED), strip(familyMatched))
        val kit = writeKit(root, base)
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 2, "auditionRevision" to 2, "title" to "SPARK revision 2: control checks",
            "engine" to "THUMP", "voice" to "SPARK", "controls" to base.macros,
            "sampleRate" to Dsp.RATE, "oversample" to Dsp.OVERSAMPLE,
            "rendererSourceSha256" to listOf("Thump.kt", "ThumpFamilies.kt", "Punch.kt", "Dsp.kt").associateWith { name ->
                listOf(File("src/main/kotlin/com/snipsnap/synth/$name"),
                    File("synth/src/main/kotlin/com/snipsnap/synth/$name"))
                    .firstOrNull { it.isFile }?.let(::hash)
            },
            "status" to "Kotlin render; sound design pending owner listen; phone and MPC hardware checks pending",
            "nativeDefinition" to "Finished THUMP output before audition matching, encoded as PCM24 without extra gain.",
            "matchedDefinition" to "One gain per complete clip via AuditionLevel, targeting loudest-200-ms RMS 0.03 with a 0.99 peak guard.",
            "timingDefinition" to "Single warmed local JVM wall-clock render observations. Not target-phone performance.",
            "expressionPositions" to ThumpFamilies.DEFAULT_POSITIONS,
            "withinHitExpressionMotion" to false, "effects" to emptyList<String>(),
            "familyNative" to nativeStrip.name, "familyMatched" to matchedStrip.name,
            "kitDownload" to kit.name, "download" to PACK,
            "baseline" to baseline?.metadata, "controlComparisons" to comparisons,
            "clips" to clips,
        )
        val manifestText = Json.write(json(manifest))
        File(root, "manifest.json").writeText(manifestText + "\n")
        File(root, "metrics.csv").writeText(buildString {
            append("id,render_ms,seconds,native_peak,native_dc,native_rms,native_loudest_window_rms,class\n")
            for (entry in clips) {
                @Suppress("UNCHECKED_CAST")
                val all = entry.getValue("measures") as Map<String, Map<String, Any>>
                val m = all.getValue("native")
                append(listOf(entry.getValue("id"), entry.getValue("renderMs"), entry.getValue("durationSeconds"),
                    m.getValue("peak"), m.getValue("dc"), m.getValue("rms"), m.getValue("loudestWindowRms"),
                    m.getValue("drumClass")).joinToString(",")).append('\n')
            }
        })
        File(root, "index.html").writeText(page(manifestText))
        val pack = File(root, PACK)
        // Keep the baseline's same-named download inside previous/. Excluding
        // by basename would silently break its preserved page in the new ZIP.
        bundle(root, pack, root.walkTopDown().filter { it.isFile && it.canonicalFile != pack.canonicalFile }.toList())
        println("Wrote ${clips.size} native/matched SPARK clips, expression strips, replayable recipes, measured classes, 16-pad XPM kit and listening ZIP under ${root.absolutePath}")
    }

    private fun preserveBaseline(root: File, input: File): Baseline {
        val source = input.canonicalFile
        val output = root.canonicalFile
        require(source.isDirectory && source != output &&
            !source.toPath().startsWith(output.toPath()) && !output.toPath().startsWith(source.toPath())) {
            "baseline and output must be separate directories, neither containing the other"
        }
        val manifestFile = File(source, "manifest.json")
        require(manifestFile.isFile) { "baseline needs its original manifest.json" }
        val originalText = manifestFile.readText()
        val originalHash = hash(manifestFile)
        val original = Json.parse(originalText).obj()
        require(original.getValue("engine").str() == "THUMP" && original.getValue("voice").str() == "SPARK" &&
            (original["auditionRevision"]?.int() ?: 1) == 1) { "baseline must be the SPARK revision-one audition" }
        val clips = original.getValue("clips").arr().map { it.obj() }.associateBy { it.getValue("id").str() }
        fun originalFile(path: String): File {
            val file = File(source, path).canonicalFile
            require(!File(path).isAbsolute && file.toPath().startsWith(source.toPath()) && file.isFile) {
                "baseline asset must be an existing relative file: $path"
            }
            return file
        }
        for ((id, clip) in clips) {
            for (variant in listOf("native", "matched")) {
                val file = originalFile(clip.getValue(variant).str())
                check(hash(file) == clip.getValue("sha256").obj().getValue(variant).str()) {
                    "baseline manifest does not match its $id/$variant file"
                }
            }
            originalFile(clip.getValue("recipe").str())
        }
        val entries = source.walkTopDown().toList()
        require(entries.none { Files.isSymbolicLink(it.toPath()) }) { "baseline must not contain symbolic links" }
        val files = entries.filter { it.isFile }.associate {
            it.relativeTo(source).invariantSeparatorsPath to hash(it)
        }
        val previous = File(output, "previous")
        require(previous.canonicalFile.toPath().startsWith(output.toPath())) { "previous must remain inside output" }
        check(!previous.exists() || previous.deleteRecursively()) { "cannot clear previous audition copy" }
        check(source.copyRecursively(previous, overwrite = false)) { "cannot preserve baseline audition" }
        for ((path, expected) in files) {
            check(hash(File(previous, path)) == expected) { "baseline copy changed $path" }
        }
        check(hash(manifestFile) == originalHash && File(previous, "manifest.json").readText() == originalText) {
            "the original baseline manifest must remain unchanged"
        }
        return Baseline(linkedMapOf(
            "auditionRevision" to 1, "page" to "previous/index.html", "manifest" to "previous/manifest.json",
            "manifestSha256" to originalHash, "filesSha256" to files,
            "rendererSourceSha256" to original.getValue("rendererSourceSha256"),
            "method" to "Original files copied byte-for-byte; no old patch rendered by the revised engine.",
        ), clips)
    }

    private fun controlComparisons(baseline: Baseline, clips: List<Map<String, Any?>>): List<Map<String, Any?>> =
        listOf("PUNCH", "METAL", "COLOR").flatMap { control -> steps.map { value ->
            val id = "${control.lowercase(Locale.ROOT)}-${percent(value)}"
            val previous = baseline.clips.getValue(id)
            val revised = clips.single { it["id"] == id }
            val previousMacros = previous.getValue("patch").obj().getValue("macros").obj()
            val revisedMacros = (revised.getValue("patch") as JsonValue).obj().getValue("macros").obj()
            check(previousMacros.getValue(control).num().toFloat() == value &&
                revisedMacros.getValue(control).num().toFloat() == value &&
                previousMacros.filterKeys { it != control } == revisedMacros.filterKeys { it != control }) {
                "$id comparison must hold all other controls at the same values"
            }
            linkedMapOf("id" to id, "control" to control, "value" to value,
                "heldControls" to previousMacros.filterKeys { it != control },
                "previousNative" to "previous/${previous.getValue("native").str()}",
                "previousMatched" to "previous/${previous.getValue("matched").str()}",
                "previousRecipe" to "previous/${previous.getValue("recipe").str()}",
                "previousSha256" to previous.getValue("sha256"),
                "revisedNative" to revised.getValue("native"), "revisedMatched" to revised.getValue("matched"),
                "revisedRecipe" to revised.getValue("recipe"), "revisedSha256" to revised.getValue("sha256"))
        } }

    private fun writeKit(root: File, base: ThumpPatch): File {
        val work = File(root, ".spark-kit-work")
        check(!work.exists() || work.deleteRecursively()) { "cannot clear temporary kit staging" }
        try {
            val staging = File(work, "samples")
            val pads = listOf(.15f, .4f, .65f, .9f).flatMapIndexed { row, tune ->
                val source = base.copy(name = "SPARK ROW ${row + 1}", macros = base.macros + ("TUNE" to tune))
                ThumpFamilies.render(source).mapIndexed { column, hit ->
                    ArrangedPad(hit.snip, hit.drumClass, recipe = PadRecipe(hit.patch).toJsonValue(),
                        level = 1f, oneShot = true, displayName = "SPARK ${row + 1}.${column + 1}",
                        source = mapOf("renderer" to "THUMP/SPARK", "expressionPosition" to hit.position.toString()))
                }
            }
            val kit = KitAssembler.assembleArranged(KIT_NAME, pads, staging)
            check(kit.pads.size == 16)
            val result = KitExporter.exportProgramFolder(kit, staging, work, overwrite = true)
            // KitExporter supplies the MPC2 program and portable recipe sidecar.
            // Validate all exported PCM before bundling; hardware loading remains open.
            check(result.program.isFile && result.samples.size == 16)
            for (file in result.samples) validate(WavReader.read(file), file.name)
            File(result.directory, "README.txt").writeText("""
                SPARK revision-two kit: sixteen finite, dry sample pads.

                Load "$KIT_NAME.xpm" with its WAVs and sidecar together.
                Four pitch rows (TUNE .15, .4, .65, .9), four expression columns.
                Each row keeps one settled root; columns follow the same ordered
                modulation, edge-color and decay path. No external effects.

                The sidecar preserves each SnipSnap recipe and measured class.
                This is an audition, not an approved factory preset bank.
                Owner listening and MPC hardware loading remain outstanding.
            """.trimIndent() + "\n")
            return File(root, KIT_PACK).also { archive ->
                bundle(result.directory, archive, result.directory.walkTopDown().filter { it.isFile }.toList(),
                    prefix = "$KIT_NAME/")
            }
        } finally {
            check(!work.exists() || work.deleteRecursively()) { "cannot remove temporary kit staging" }
        }
    }

    private fun strip(clips: List<Snip>): Snip {
        val gap = (Dsp.RATE * .35).toInt()
        val samples = FloatArray(clips.sumOf { it.samples.size } + gap * (clips.size - 1))
        var at = 0
        for (clip in clips) {
            clip.samples.copyInto(samples, at)
            at += clip.samples.size + gap
        }
        return Snip(samples, channels = 1, sampleRate = Dsp.RATE)
    }

    private fun validate(snip: Snip, name: String) {
        check(snip.channels == 1 && snip.sampleRate == Dsp.RATE && snip.frameCount > 0) { "$name has wrong format" }
        check(snip.samples.all { it.isFinite() && abs(it) <= 1f }) { "$name is non-finite or clipped" }
        check(snip.peak() > 1e-5f) { "$name is silent" }
    }

    private fun measures(snip: Snip): Map<String, Any> = linkedMapOf(
        "finite" to snip.samples.all { it.isFinite() }, "peak" to snip.peak(),
        "dc" to snip.samples.sumOf { it.toDouble() } / snip.samples.size,
        "rms" to sqrt(snip.samples.sumOf { it.toDouble() * it } / snip.samples.size),
        "loudestWindowRms" to Loudness.of(snip), "drumClass" to Classifier.classify(snip).drumClass.name,
    )

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun percent(value: Float) = (value * 100).toInt().toString().padStart(3, '0')

    private fun bundle(root: File, target: File, files: List<File>, prefix: String = "") {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
                zip.putNextEntry(ZipEntry(prefix + file.relativeTo(root).invariantSeparatorsPath).also { it.time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun json(value: Any?): JsonValue = when (value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Boolean -> JsonValue.Bool(value)
        is Number -> JsonValue.Num(value.toDouble().also { require(it.isFinite()) })
        is Map<*, *> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("unsupported SPARK manifest value: ${value.javaClass}")
    }

    private fun page(manifest: String): String = """
        <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>SPARK revision 2: control checks</title><style>
        :root{color-scheme:dark;font-family:system-ui,sans-serif;background:#11161a;color:#edf3f1}body{max-width:1000px;margin:auto;padding:32px 20px 80px}h1{font-size:2.5rem;margin-bottom:8px}h2{margin-top:36px}p{max-width:75ch;line-height:1.6;color:#c8d7d2}a{color:#82e4c5}article{padding:16px;background:#1d282b;border-radius:12px;margin:10px 0}.players{display:flex;gap:20px;flex-wrap:wrap}label{display:block;font-size:.85rem;color:#b7c9c3}audio{max-width:100%;height:36px}small{display:block;margin:8px 0;color:#a9bdb7}nav{display:flex;gap:16px;flex-wrap:wrap;margin:24px 0}details{margin-top:10px}pre{white-space:pre-wrap;font-size:.8rem}
        </style><body><h1>SPARK revision 2</h1>
        <p>Compare PUNCH, METAL and COLOR against the preserved first revision. Start with the matched A/B players: the old and revised sounds use the same control values in each row.</p>
        <p>Matched uses one gain per clip for comparison. Native preserves the finished instrument output. These are real Kotlin renders; the revised sound awaits your listen. Phone timing and MPC hardware loading remain unchecked.</p>
        <nav><a href="$PACK" download>Download revision 2 listening pack</a><a href="$KIT_PACK" download>Download revised 16-pad MPC2 kit</a><a href="manifest.json">Manifest</a><a href="metrics.csv">Measurements</a><a id="previous-page" hidden>Open preserved revision 1 page</a></nav>
        <div id="comparisons"></div>
        <h2>Four expression positions</h2><p>The same root, left to right, with 350 ms between hits. Increasing expression changes modulation, edge brightness and decay. No effects or continuous expression movement.</p>
        <div class="players"><label>Revision 2 matched strip<br><audio controls preload="none" src="$FAMILY_MATCHED"></audio></label><label>Revision 2 native strip<br><audio controls preload="none" src="$FAMILY_NATIVE"></audio></label></div>
        <p>The kit has four pitch rows and four expression columns. Saved recipes remain editable in SnipSnap.</p><div id="clips"></div>
        <script type="application/json" id="manifest">${manifest.replace("<", "\\u003c")}</script>
        <script>
        const manifest=JSON.parse(document.getElementById('manifest').textContent),clipRoot=document.getElementById('clips'),abRoot=document.getElementById('comparisons');
        function player(parent,title,path){const label=document.createElement('label');label.textContent=title;const audio=document.createElement('audio');audio.controls=true;audio.preload='none';audio.src=path;label.append(document.createElement('br'),audio);parent.append(label)}
        function recipes(parent,links){for(const [title,path] of links){const link=document.createElement('a');link.href=path;link.textContent=title;link.download='';parent.append(link,document.createTextNode(' '))}}
        function heldText(controls){return Object.entries(controls).map(([name,value])=>name+' '+Math.round(value*100)+'%').join(' · ')}
        if(manifest.baseline){const link=document.getElementById('previous-page');link.href=manifest.baseline.page;link.hidden=false}
        let comparisonControl='';
        for(const comparison of manifest.controlComparisons){
          if(comparisonControl!==comparison.control){comparisonControl=comparison.control;const h=document.createElement('h2');h.textContent=comparisonControl+' · revision 1 versus revision 2';abRoot.append(h)}
          const article=document.createElement('article'),title=document.createElement('strong'),held=document.createElement('small');title.textContent=comparison.control+' '+Math.round(comparison.value*100)+'%';held.textContent='Other controls held: '+heldText(comparison.heldControls);article.append(title,held);
          const matched=document.createElement('div');matched.className='players';player(matched,'Revision 1 · matched',comparison.previousMatched);player(matched,'Revision 2 · matched',comparison.revisedMatched);article.append(matched);
          const native=document.createElement('details'),summary=document.createElement('summary'),players=document.createElement('div');summary.textContent='Native comparison and editable recipes';players.className='players';player(players,'Revision 1 · native',comparison.previousNative);player(players,'Revision 2 · native',comparison.revisedNative);native.append(summary,players);recipes(native,[['Revision 1 recipe',comparison.previousRecipe],['Revision 2 recipe',comparison.revisedRecipe]]);article.append(native);abRoot.append(article);
        }
        if(!manifest.controlComparisons.length){const note=document.createElement('p');note.textContent='Previous-revision comparisons are unavailable in this pack.';abRoot.append(note)}
        let section='';
        for(const clip of manifest.clips){
          if(section!==clip.section){section=clip.section;const h=document.createElement('h2');h.textContent='Revision 2 · '+section;clipRoot.append(h)}
          const article=document.createElement('article'),title=document.createElement('strong'),held=document.createElement('small');title.textContent=clip.title;held.textContent=(clip.sweptControl?'Other controls held: ':'Settings: ')+heldText(clip.heldControls);article.append(title,held);
          const players=document.createElement('div');players.className='players';player(players,'Revision 2 · matched',clip.matched);player(players,'Revision 2 · native',clip.native);article.append(players);
          const detail=document.createElement('details'),summary=document.createElement('summary'),pre=document.createElement('pre');summary.textContent='Settings and recipe';pre.textContent=JSON.stringify(clip.patch,null,2);detail.append(summary,pre);recipes(detail,[['Download revised editable recipe',clip.recipe]]);article.append(detail);clipRoot.append(article);
        }
        document.addEventListener('play',event=>{for(const audio of document.querySelectorAll('audio'))if(audio!==event.target)audio.pause()},true);
        </script></body></html>
    """.trimIndent() + "\n"
}
