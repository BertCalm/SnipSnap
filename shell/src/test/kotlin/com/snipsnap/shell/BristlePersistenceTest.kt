package com.snipsnap.shell

import com.snipsnap.synth.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class BristlePersistenceTest {
    @Test fun `both drafts user presets and pad recipes retain the same editable source`() {
        val root = createTempDirectory("bristle-save").toFile()
        try {
            val patches = BristlePresets.all().mapIndexed { index, it ->
                it.copy(name = "CUSTOM ${it.voice}", midi = 32, velocity = .65f, take = index % 2,
                    macros = it.macros + ("TUNE" to .75f) + ("GRIT" to .81f))
            }
            val settings = mutableMapOf("engine" to "BRISTLE", "voice" to "B")
            SynthDrafts.write(settings, patches)
            assertEquals(patches.toSet(), SynthDrafts.read(settings).toSet())
            for (patch in patches) {
                UserPresets.save(root, patch, 1234)
                val saved = UserPresets.forVoice(UserPresets.read(root), "BRISTLE", patch.voiceName).single().patch
                assertEquals(patch, saved)
                val pad = PadRecipe.fromJsonText(PadRecipe(saved).toJsonText())
                assertContentEquals(patch.render().samples, pad.render().samples)
                val moved = assertNotNull(SynthRoot.of(saved)).at(30) as BristlePatch
                assertEquals(30, moved.midi)
                assertEquals(patch.velocity, moved.velocity)
                assertEquals(patch.take, moved.take)
                assertEquals(patch.macros, moved.macros)
                assertEquals(36, Bristle.rootMidi(moved))
                val line = UserPresets.rosterLine(saved)
                val call = KotlinLiteral.parse(line.removeSuffix(",")) as KotlinLiteral.Call
                assertEquals("fromJsonText", call.name)
                assertEquals("BristlePatch", (call.receiver as KotlinLiteral.Call).name)
                val promoted = BristlePatch.fromJsonText((call.positional.single() as KotlinLiteral.Str).value)
                assertEquals(saved, promoted)
                assertContentEquals(saved.render().samples, promoted.render().samples)
            }
        } finally { root.deleteRecursively() }
    }
}
