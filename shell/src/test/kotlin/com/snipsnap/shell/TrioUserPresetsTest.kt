package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.synth.Trio
import com.snipsnap.synth.TrioConfig
import com.snipsnap.synth.TrioPatch
import com.snipsnap.synth.TrioVoice
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrioUserPresetsTest {
    @Test
    fun `saved TRIO and promoted roster constructors retain every performance choice and model`() {
        val root = createTempDirectory("trio-preset").toFile()
        try {
            for (model in listOf(Trio.LEGACY_MODEL_VERSION, Trio.MODEL_VERSION)) {
                val source = TrioPatch(
                    "SCENE \"$model\" \$X", TrioVoice.ENSEMBLE,
                    linkedMapOf("TOUCH" to .37f, "DRAMA" to .61f, "SIDES" to .32f,
                        "MOTION" to .46f, "SPACE" to .28f, "DAMP" to .59f),
                    midi = 73, velocity = .41f,
                    config = TrioConfig(seed = -29, tempoBpm = 136f, beats = 4,
                        press = .73f, tremoloHz = 17.5f, strumMs = 23f, model = model),
                )
                UserPresets.save(root, source, model.toLong())
                val back = UserPresets.read(root).single { it.name == source.name }
                assertEquals(source, back.patch)
                val line = UserPresets.rosterLine(back.patch)
                assertTrue(line.startsWith("TrioPatch.fromJsonText(\"") && line.endsWith("\"),"), line)
                val literal = line.removePrefix("TrioPatch.fromJsonText(").removeSuffix("),")
                // A Kotlin dollar escape is the only addition to JSON's string literal rules.
                val promoted = TrioPatch.fromJsonText(Json.parse(literal.replace("\\\$", "\$")).str())
                assertEquals(source, promoted, "promotion retains root, velocity, seed, gesture and historical renderer")
                assertEquals("TrioPresets.kt\n  $line\n", UserPresets.renderAll(listOf(back)))
            }
        } finally { root.deleteRecursively() }
    }
}
