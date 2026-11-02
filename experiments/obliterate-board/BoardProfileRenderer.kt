package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Public board calls on the heard input files; timings describe this host JVM only. */
object BoardProfileRenderer {
    @JvmStatic fun main(args: Array<String>) {
        val repo = File(args[0]).canonicalFile
        val output = File(args[1]).canonicalFile.apply { mkdirs() }
        val assets = File(repo, "app/src/androidTest/assets/obliterate")
        val sources = Json.parse(File(assets, "sources.json").readText()).obj().getValue("sources").arr()
        val results = sources.map { row ->
            val s = row.obj(); val bytes = File(assets, s.getValue("inputFile").str()).readBytes()
            check(sha(bytes) == s.getValue("inputSha256").str())
            val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val input = Snip(FloatArray(floats.remaining()).also { floats.get(it) }, s.getValue("inputChannels").int(), 44100)
            val before = input.samples.copyOf()
            val recipe = BoardRecipe(BoardType.OBLITERATE, if (s.getValue("id").str() == "snare") .7f else .35f)
            val expected = Obliterate.render(input, recipe.obliterate!!.at(recipe.amount)).audio
            repeat(3) { ObliterateBoardCache.clear(); recipe.process(input) }
            val cold = List(7) {
                ObliterateBoardCache.clear(); val start = System.nanoTime(); val actual = recipe.process(input)
                val elapsed = (System.nanoTime() - start) / 1e6
                check(expected.samples.contentEquals(actual.samples)); elapsed
            }
            val hot = List(7) {
                val start = System.nanoTime(); val actual = recipe.process(input)
                val elapsed = (System.nanoTime() - start) / 1e6
                check(expected.samples.contentEquals(actual.samples)); elapsed
            }
            check(ObliterateBoardCache.stats().fullHits == 7L)
            val changed = recipe.copy(obliterate = recipe.obliterate!!.copy(bpm = 121.0))
            val clockStart = System.nanoTime(); changed.process(input); val clockMs = (System.nanoTime() - clockStart) / 1e6
            check(ObliterateBoardCache.stats().preHits == 1L)
            check(before.contentEquals(input.samples))
            mapOf("sourceId" to s.getValue("id").str(), "inputSha256" to s.getValue("inputSha256").str(),
                "inputFrames" to input.frameCount, "coreAmount" to recipe.amount, "settings" to recipe.toJsonText(),
                "coldBoardMs" to cold, "coldMedianMs" to cold.sorted()[3], "repeatBoardMs" to hot,
                "repeatMedianMs" to hot.sorted()[3], "clockEditMs" to clockMs,
                "clockEditReusedShimmer" to true, "sampleExactToPublicComposition" to true,
                "retainedSamples" to ObliterateBoardCache.stats().retainedSamples)
        }
        val paths = listOf("Obliterate.kt", "ObliterateCore.kt", "ObliteratePcg64.kt", "ObliterateShimmer.kt", "ObliterateBoard.kt", "ObliterateBoardCache.kt", "BoardRecipe.kt")
            .map { File(repo, "synth/src/main/kotlin/com/snipsnap/synth/$it") } + listOf(File(repo, "experiments/obliterate-board/BoardProfileRenderer.kt"))
        val result = mapOf("schema" to 1, "sourceCommit" to git(repo, "rev-parse", "HEAD"),
            "workingTreeDirty" to git(repo, "status", "--porcelain").isNotEmpty(),
            "sourceHashes" to paths.associate { it.relativeTo(repo).invariantSeparatorsPath to sha(it.readBytes()) },
            "meaning" to "Host JVM only; GC and host load uncontrolled. Repeat calls include input snapshot, validation, content hash and owned output copy. Cold calls include both real processors.",
            "javaVersion" to System.getProperty("java.version"), "availableProcessors" to Runtime.getRuntime().availableProcessors(),
            "warmups" to 3, "trials" to 7, "maximumRetainedFloatBytes" to (ObliterateBoardCache.MAX_PRE_SAMPLES + ObliterateBoardCache.MAX_OUTPUT_SAMPLES) * 4,
            "cases" to results)
        File(output, "host-profile.json").writeText(Json.write(json(result)) + "\n")
        println("Profiled ${results.size} retained listening inputs; every cached sample matches the public processors")
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun git(repo: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(repo).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText().trim(); check(p.waitFor() == 0); return text
    }
    private fun json(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null; is String -> JsonValue.Str(v); is Number -> JsonValue.Num(v.toDouble()); is Boolean -> JsonValue.Bool(v)
        is Map<*, *> -> JsonValue.Obj(v.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map(::json)); else -> error("cannot encode")
    }
}
