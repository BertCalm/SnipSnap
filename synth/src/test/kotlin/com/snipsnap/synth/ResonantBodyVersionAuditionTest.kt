package com.snipsnap.synth

import com.snipsnap.audio.ResonantBody
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ResonantBodyVersionAuditionTest {
    @TempDir lateinit var temp: File

    @Test fun `version comparison replays both models and refuses corrupt or ambiguous evidence without overwriting it`() {
        // One generated pack covers the replay and refusal checks; no ignored audition or repeated benchmark is needed.
        val root = File(temp, "comparison")
        ResonantBodyVersionAuditionGenerator.generate(root)
        val manifestFile = File(root, "manifest.json")
        val originalManifest = manifestFile.readBytes()
        val manifest = Json.parse(originalManifest.toString(Charsets.UTF_8)).obj()
        val rows = manifest.getValue("cases").arr()
        assertEquals(24, rows.size)
        assertEquals(setOf(ResonantBody.MODEL_VERSION, ResonantBody.CANDIDATE_MODEL_VERSION), rows.map { it.obj().getValue("modelVersion").int() }.toSet())
        assertEquals(JsonValue.Null, manifest.getValue("originalPackCompatibility"), "a pack generated without an original pack must not claim archival compatibility")
        val first = rows.first().obj()
        val source = File(root, first.getValue("source").str())
        val matched = File(root, first.getValue("matched").str())
        val recipe = File(root, first.getValue("recipe").str())
        val originalSource = source.readBytes()
        val originalMatched = matched.readBytes()
        val originalRecipe = recipe.readBytes()

        fun refusesManifest(fields: Map<String, JsonValue>): IllegalArgumentException = try {
            manifestFile.writeText(Json.write(JsonValue.Obj(fields)))
            assertFailsWith<IllegalArgumentException> { ResonantBodyVersionAuditionGenerator.verify(root) }
        } finally { manifestFile.writeBytes(originalManifest) }

        fun refusesBytes(file: File) {
            val original = file.readBytes()
            try {
                file.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
                assertFailsWith<IllegalArgumentException> { ResonantBodyVersionAuditionGenerator.verify(root) }
            } finally { file.writeBytes(original) }
        }

        refusesBytes(source)
        refusesBytes(matched)
        refusesBytes(File(root, first.getValue("raw").str()))
        try {
            val changed = Json.parse(originalRecipe.toString(Charsets.UTF_8)).obj() + ("modelVersion" to JsonValue.Num(99.0))
            recipe.writeText(Json.write(JsonValue.Obj(changed)))
            assertFailsWith<IllegalArgumentException> { ResonantBodyVersionAuditionGenerator.verify(root) }
        } finally { recipe.writeBytes(originalRecipe) }

        refusesManifest(manifest + ("baselineVersion" to JsonValue.Num(99.0)))
        refusesManifest(manifest + ("candidateVersion" to JsonValue.Num(99.0)))
        refusesManifest(manifest - "baselineVersion")
        refusesManifest(manifest - "candidateVersion")
        refusesManifest(manifest + ("schema" to JsonValue.Num(99.0)))
        refusesManifest(manifest + ("cases" to JsonValue.Arr(emptyList())))
        refusesManifest(manifest + ("cases" to JsonValue.Arr(rows + rows.first())))
        val onlyBaseline = rows.filter { it.obj().getValue("modelVersion").int() == ResonantBody.MODEL_VERSION }
        refusesManifest(manifest + ("cases" to JsonValue.Arr(onlyBaseline)))
        val unsupported = JsonValue.Obj(first + ("modelVersion" to JsonValue.Num(99.0)))
        refusesManifest(manifest + ("cases" to JsonValue.Arr(listOf(unsupported) + rows.drop(1))))
        val sources = manifest.getValue("sources").arr()
        refusesManifest(manifest + ("sources" to JsonValue.Arr(sources + sources.first())))

        // A real file outside the output directory proves this is path confinement, not merely missing-file refusal.
        val outside = File(temp, "outside.wav").also { it.writeBytes(File(root, first.getValue("raw").str()).readBytes()) }
        val escaping = JsonValue.Obj(first + ("raw" to JsonValue.Str("../${outside.name}")))
        val error = refusesManifest(manifest + ("cases" to JsonValue.Arr(listOf(escaping) + rows.drop(1))))
        assertTrue(error.message.orEmpty().contains("escaping"))
        try {
            assertTrue(matched.delete())
            assertFailsWith<IllegalArgumentException> { ResonantBodyVersionAuditionGenerator.verify(root) }
        } finally { matched.writeBytes(originalMatched) }

        ResonantBodyVersionAuditionGenerator.verify(root)
        assertFailsWith<IllegalArgumentException> { ResonantBodyVersionAuditionGenerator.generate(root) }
        assertContentEquals(originalManifest, manifestFile.readBytes(), "a nonempty output must remain untouched")
        assertContentEquals(originalSource, source.readBytes())
        assertContentEquals(originalMatched, matched.readBytes())
        assertContentEquals(originalRecipe, recipe.readBytes())
    }
}
