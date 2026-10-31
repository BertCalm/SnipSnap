package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

/** Identical scores compare the accepted renderer with the upper-TOUCH revision. */
object TrioTouchAuditionGenerator {
    private fun str(x: String) = JsonValue.Str(x)
    private fun num(x: Number) = JsonValue.Num(x.toDouble())
    private fun obj(vararg pairs: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*pairs))
    private fun sha(x: ByteArray) = MessageDigest.getInstance("SHA-256").digest(x).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun scale(x: Snip, gain: Float) = Snip(FloatArray(x.samples.size) { x.samples[it] * gain }, 1, Dsp.RATE)
    private fun distance(a: FloatArray, b: FloatArray): Double {
        var aa = 0.0; var bb = 0.0; var ab = 0.0
        for (i in a.indices) { aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i]; ab += a[i].toDouble() * b[i] }
        val gain = ab / bb
        return sqrt(a.indices.sumOf { val d = a[it] - gain * b[it]; d * d } / aa)
    }
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.single()).absoluteFile.also { it.mkdirs() }
        val clips = mutableListOf<JsonValue>()
        val files = mutableListOf<String>()
        val differences = mutableListOf<JsonValue>()
        for (drama in listOf(.5f, 1f)) {
            val rendered = mutableMapOf<Pair<Int, Float>, FloatArray>()
            for (touch in listOf(.8f, .9f, 1f)) for (model in listOf(5, 6)) {
                val id = "d${(drama * 100).toInt()}_t${(touch * 100).toInt()}_v$model"
                val patch = TrioPatch("TOUCH comparison $id", TrioVoice.ENSEMBLE,
                    Trio.defaults(TrioVoice.ENSEMBLE) + mapOf("TOUCH" to touch, "DRAMA" to drama, "SIDES" to (1f / 6f)),
                    midi = 60, velocity = 1f, config = TrioConfig(seed = 47010, model = model))
                val snip = patch.render()
                check(snip.samples.all { it.isFinite() } && snip.peak() <= .99001f)
                rendered[model to touch] = snip.samples
                val assets = linkedMapOf<String, JsonValue>()
                for ((level, gain) in listOf("raw" to .25f, "matched" to (.03f / Loudness.of(snip).coerceAtLeast(1e-9f)))) {
                    val path = "audio/${id}_$level.wav"
                    val audio = scale(snip, gain)
                    check(audio.peak() < .99f)
                    WavWriter.write(File(root, path), audio)
                    files += path
                    assets[level] = str(path)
                }
                val recipe = "recipes/$id.json"
                File(root, recipe).also { it.parentFile.mkdirs(); it.writeText(patch.toJsonText() + "\n") }
                files += recipe
                clips += obj("id" to str(id), "drama" to num(drama), "touch" to num(touch), "model" to num(model),
                    "assets" to JsonValue.Obj(assets), "recipe" to str(recipe), "peak" to num(snip.peak()),
                    "loudness" to num(Loudness.of(snip)))
            }
            for (model in listOf(5, 6)) differences += obj("drama" to num(drama), "model" to num(model),
                "normalizedDistance09to1" to num(distance(rendered.getValue(model to .9f), rendered.getValue(model to 1f))))
            check(rendered.getValue(5 to .8f).contentEquals(rendered.getValue(6 to .8f)))
        }
        val sourceFiles = listOf("Trio.kt", "TrioScore.kt", "Dsp.kt", "Strings.kt", "Modes.kt", "Keys.kt", "Thump.kt")
            .associateWith { sha(File("src/main/kotlin/com/snipsnap/synth/$it").readBytes()) } + mapOf(
                "TrioTouchAuditionGenerator.kt" to sha(File("src/test/kotlin/com/snipsnap/synth/TrioTouchAuditionGenerator.kt").readBytes()),
                "index.html" to sha(File("src/test/resources/trio-touch/index.html").readBytes()))
        val hashes = files.associateWith { sha(File(root, it).readBytes()) }
        val round = "trio-touch-" + sha(hashes.values.joinToString().toByteArray()).take(16)
        val manifest = obj("engine" to str("TRIO"), "listeningRound" to str(round), "clips" to JsonValue.Arr(clips),
            "differences" to JsonValue.Arr(differences), "sampleRate" to num(Dsp.RATE), "seconds" to num(3.5),
            "sourceHashes" to JsonValue.Obj(sourceFiles.mapValues { str(it.value) }),
            "assetHashes" to JsonValue.Obj(hashes.mapValues { str(it.value) }))
        val json = Json.write(manifest)
        File(root, "manifest.json").writeText(json + "\n")
        val template = File("src/test/resources/trio-touch/index.html").readText()
        File(root, "index.html").writeText(template.replace("__MANIFEST__", json.replace("</", "<\\/")))
        println("TRIO TOUCH: $round; 12 clips; " + Json.write(JsonValue.Arr(differences)))
    }
}
