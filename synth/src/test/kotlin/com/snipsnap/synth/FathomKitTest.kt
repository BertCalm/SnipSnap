package com.snipsnap.synth

import com.snipsnap.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FathomKitTest {

    @Test
    fun `the bass kit walks DEEP up the pentatonic then lands four GRIND and four GLASS presets, dry and filed by the engine`() {
        val kit = SynthKits.fathom()
        assertEquals(16, kit.size)
        val semitones = listOf(0, 3, 5, 7, 10, 12, 15, 17)
        for ((i, pad) in kit.withIndex()) {
            val present = requireNotNull(pad) { "pad ${i + 1} is empty" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(present.recipe) { "pad ${i + 1} carries no recipe" })
            val patch = recipe.patch as FathomPatch
            assertEquals(null, recipe.fx, "pad ${i + 1}: DRIVE is in the engine, the kit lands dry")
            assertEquals(Fathom.drumClassFor(patch.voice, patch.macros), present.drumClass, "pad ${i + 1} is filed off the engine's rule")
            val expected = when (i) { in 0..7 -> FathomVoice.DEEP; in 8..11 -> FathomVoice.GRIND; else -> FathomVoice.GLASS }
            assertEquals(expected, patch.voice, "pad ${i + 1}")
            if (i < 8) {
                // The walk is in TUNE's own semitones; FATHOM snaps TUNE to whole semitones.
                val hz = Fathom.frequencyFor(patch.voice, patch.macros.getValue("TUNE"))
                val bottom = Fathom.frequencyFor(patch.voice, 0f)
                assertEquals(semitones[i].toDouble(), 12 * kotlin.math.log2((hz / bottom).toDouble()), 1e-3, "pad ${i + 1} is off the pentatonic")
            }
            assertTrue(present.snip.samples.any { it != 0f }, "pad ${i + 1} renders silence")
        }
        assertEquals(16, kit.map { PadRecipe.fromJsonValue(it!!.recipe!!).patch!!.name }.toSet().size, "every pad is a different sound")
    }

    @Test
    fun `the shared audition writes a manifest whose every clip exists`() {
        val root = Files.createTempDirectory("fathom-audition").toFile()
        try {
            val count = RosterAudition.write(FathomAuditionGenerator.SPEC, root)
            val manifest = Json.parse(File(root, "manifest.json").readText()).obj()
            val page = manifest.getValue("page").obj()
            assertEquals("Fathom Audition", page.getValue("title").str())
            assertEquals(3, page.getValue("questions").arr().size)
            var listed = 0
            val sections = manifest.getValue("voices").arr()
            assertEquals(listOf("KIT", "DEEP", "GRIND", "GLASS"), sections.map { it.obj().getValue("id").str() })
            for (section in sections) {
                val id = section.obj().getValue("id").str()
                for (group in section.obj().getValue("groups").arr()) for (clip in group.obj().getValue("clips").arr()) {
                    val file = File(root, "$id/${clip.arr()[0].str()}.wav")
                    assertTrue(file.isFile && file.length() > 44, "manifest lists ${file.name} under $id but no audio was written")
                    listed++
                }
            }
            assertEquals(count, listed, "every clip written is listed, and every clip listed was written")
            // 16 kit pads; per voice: default, TUNE at three notes, five other knobs at two ends, twelve presets.
            assertEquals(16 + 3 * (1 + 3 + 5 * 2 + 12), count)
            assertTrue(File(root, "index.html").readText().contains("manifest.json"), "the page reads the manifest")
        } finally {
            root.deleteRecursively()
        }
    }
}
