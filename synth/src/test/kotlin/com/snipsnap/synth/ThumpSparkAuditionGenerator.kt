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
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * First-listen evidence for SPARK and its ordered expression family. Native
 * means the finished THUMP output, before audition matching. All recipes replay
 * that output; the matched WAVs apply only [AuditionLevel]'s single gain.
 * No factory presets or accepted sound-character claims are authored here.
 */
object ThumpSparkAuditionGenerator {
    private val steps = listOf(0f, .25f, .5f, .75f, 1f)
    private const val PACK = "thump-spark-audition.zip"
    private const val KIT_PACK = "thump-spark-mpc-kit.zip"
    private const val KIT_NAME = "SnipSnap Spark Audition"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "usage: ThumpSparkAuditionGenerator [output-directory]" }
        val root = File(args.firstOrNull() ?: "../testkit/thump-spark-audition")
        check(root.mkdirs() || root.isDirectory) { "cannot create $root" }
        val base = ThumpPatch("SPARK START", ThumpVoice.SPARK, Thump.defaults(ThumpVoice.SPARK))
        // Warm one render before recording per-hit timings. These are local JVM
        // wall-clock observations, not measurements of a phone or a latency gate.
        base.render()
        val clips = mutableListOf<Map<String, Any?>>()
        val familyNative = mutableListOf<Snip>()
        val familyMatched = mutableListOf<Snip>()

        fun clip(id: String, title: String, section: String, patch: ThumpPatch,
                 position: Float? = null): Pair<Snip, Snip> {
            val start = System.nanoTime()
            val source = patch.render()
            val renderMs = (System.nanoTime() - start) / 1_000_000.0
            validate(source, id)
            val recipe = PadRecipe(patch)
            val replay = PadRecipe.fromJsonText(recipe.toJsonText()).render()
            check(source.samples.contentEquals(replay.samples)) { "$id recipe did not replay the source" }
            val rawPath = "audio/$id-native.wav"
            val matchedPath = "audio/$id-matched.wav"
            val recipePath = "recipes/$id.json"
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
                    base.copy(name = "${spec.name} ${percent(value)}", macros = base.macros + (spec.name to value)))
            }
        }
        for ((index, tune) in listOf(.25f, .5f, .75f).withIndex()) {
            clip("register-${index + 1}", "Register ${index + 1}", "Three registers",
                base.copy(name = "SPARK NOTE ${index + 1}", macros = base.macros + ("TUNE" to tune)))
        }
        for (metal in listOf(0f, 1f)) for (color in listOf(0f, 1f)) {
            val id = "pair-metal-${percent(metal)}-color-${percent(color)}"
            clip(id, "METAL ${percent(metal)} / COLOR ${percent(color)}", "METAL and COLOR corners",
                base.copy(name = "SPARK PAIR", macros = base.macros + mapOf("METAL" to metal, "COLOR" to color)))
        }
        val nativeStrip = WavWriter.write(File(root, "family-native.wav"), strip(familyNative))
        val matchedStrip = WavWriter.write(File(root, "family-matched.wav"), strip(familyMatched))
        val kit = writeKit(root, base)
        val manifest = linkedMapOf<String, Any?>(
            "schemaVersion" to 1, "title" to "SPARK: first listen",
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
        bundle(root, File(root, PACK), root.walkTopDown().filter { it.isFile && it.name != PACK }.toList())
        println("Wrote ${clips.size} native/matched SPARK clips, expression strips, replayable recipes, measured classes, 16-pad XPM kit and listening ZIP under ${root.absolutePath}")
    }

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
                SPARK first-listen kit: sixteen finite, dry sample pads.

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
        <title>SPARK: first listen</title><style>
        :root{color-scheme:dark;font-family:system-ui,sans-serif;background:#11161a;color:#edf3f1}body{max-width:1000px;margin:auto;padding:32px 20px 80px}h1{font-size:2.5rem;margin-bottom:8px}h2{margin-top:36px}p{max-width:75ch;line-height:1.6;color:#c8d7d2}a{color:#82e4c5}article{padding:16px;background:#1d282b;border-radius:12px;margin:10px 0}.players{display:flex;gap:20px;flex-wrap:wrap}label{display:block;font-size:.85rem;color:#b7c9c3}audio{max-width:100%;height:36px}small{display:block;margin:8px 0;color:#a9bdb7}nav{display:flex;gap:16px;flex-wrap:wrap;margin:24px 0}details{margin-top:10px}pre{white-space:pre-wrap;font-size:.8rem}
        </style><body><h1>SPARK: first listen</h1>
        <p>A dry percussion experiment with separate body and metallic edge. Start with the four expression positions, then judge whether METAL and COLOR offer useful changes at low and middle settings.</p>
        <p>Native plays the finished instrument output. Matched uses one gain per clip for comparison. These are real Kotlin renders; sound character, phone timing and MPC hardware loading await your checks.</p>
        <nav><a href="thump-spark-audition.zip" download>Download listening pack</a><a href="thump-spark-mpc-kit.zip" download>Download 16-pad MPC2 kit</a><a href="manifest.json">Manifest</a><a href="metrics.csv">Measurements</a></nav>
        <h2>Four expression positions</h2><p>The same root, left to right, with 350 ms between hits. Increasing expression changes modulation, edge brightness and decay. No effects or continuous expression movement.</p>
        <div class="players"><label>Native strip<br><audio controls preload="none" src="family-native.wav"></audio></label><label>Matched strip<br><audio controls preload="none" src="family-matched.wav"></audio></label></div>
        <p>The kit has four pitch rows and four expression columns. Saved recipes remain editable in SnipSnap.</p><div id="clips"></div>
        <script type="application/json" id="manifest">${manifest.replace("<", "\\u003c")}</script>
        <script>
        const manifest=JSON.parse(document.getElementById('manifest').textContent),root=document.getElementById('clips');let section='';
        for(const clip of manifest.clips){if(section!==clip.section){section=clip.section;const h=document.createElement('h2');h.textContent=section;root.append(h)}
        const article=document.createElement('article'),title=document.createElement('strong');title.textContent=clip.title;article.append(title);
        const players=document.createElement('div');players.className='players';for(const variant of ['native','matched']){const label=document.createElement('label');label.textContent=variant==='native'?'Native':'Matched';const audio=document.createElement('audio');audio.controls=true;audio.preload='none';audio.src=clip[variant];label.append(document.createElement('br'),audio);players.append(label)}article.append(players);
        const detail=document.createElement('details'),summary=document.createElement('summary'),pre=document.createElement('pre');summary.textContent='Settings and recipe';pre.textContent=JSON.stringify(clip.patch,null,2);const recipe=document.createElement('a');recipe.href=clip.recipe;recipe.textContent='Download editable recipe';recipe.download='';detail.append(summary,pre,recipe);article.append(detail);root.append(article)}
        document.addEventListener('play',event=>{for(const audio of document.querySelectorAll('audio'))if(audio!==event.target)audio.pause()},true);
        </script></body></html>
    """.trimIndent() + "\n"
}
