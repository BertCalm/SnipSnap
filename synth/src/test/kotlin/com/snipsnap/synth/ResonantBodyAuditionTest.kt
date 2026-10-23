package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ResonantBodyAuditionTest {
    @TempDir lateinit var temp: File

    @Test fun `raw float WAVs preserve signed zero and headroom exactly`() {
        val samples = floatArrayOf(-0f, 0f, 1.5f, -1.25f, 0.12345678f, -0.9876543f)
        val file = File(temp, "raw.wav")
        ResonantBodyAuditionGenerator.writeFloatWav(file, Snip(samples, 2, 48_000))
        val read = WavReader.read(file)
        assertEquals(48_000, read.sampleRate)
        assertEquals(2, read.channels)
        assertContentEquals(samples.map { it.toRawBits() }, read.samples.map { it.toRawBits() })
    }

    @Test fun `audition replays exact PCM and refuses corrupted sources versions files and paths`() {
        val root = File(temp, "audition")
        ResonantBodyAuditionGenerator.generate(root)
        val manifest = Json.parse(File(root, "manifest.json").readText()).obj()
        val first = manifest.getValue("cases").arr().first().obj()
        val recipeFile = File(root, first.getValue("recipe").str())
        val originalRecipe = recipeFile.readText()
        val recipe = Json.parse(originalRecipe).obj()
        val source = File(root, recipe.getValue("source").str())
        val originalSource = source.readBytes()
        try {
            source.writeBytes(originalSource.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
            assertFailsWith<IllegalArgumentException> { ResonantBodyAuditionGenerator.verify(root) }
        } finally { source.writeBytes(originalSource) }
        for ((key, value) in listOf("schema" to JsonValue.Num(99.0), "modelVersion" to JsonValue.Num(99.0),
            "source" to JsonValue.Str("../outside.wav"))) {
            try {
                recipeFile.writeText(Json.write(JsonValue.Obj(recipe + (key to value))))
                assertFailsWith<IllegalArgumentException> { ResonantBodyAuditionGenerator.verify(root) }
            } finally { recipeFile.writeText(originalRecipe) }
        }
        val output = File(root, recipe.getValue("output").str())
        val bytes = output.readBytes()
        try {
            output.delete()
            assertFailsWith<IllegalArgumentException> { ResonantBodyAuditionGenerator.verify(root) }
        } finally { output.writeBytes(bytes) }
        ResonantBodyAuditionGenerator.verify(root)
        assertFailsWith<IllegalArgumentException> { ResonantBodyAuditionGenerator.generate(root) }
        assertContentEquals(originalSource, source.readBytes(), "refusing a nonempty destination must preserve it")
    }
}
