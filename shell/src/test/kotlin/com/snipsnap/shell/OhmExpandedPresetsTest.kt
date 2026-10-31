package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.synth.Ohm
import com.snipsnap.synth.OhmCapture
import com.snipsnap.synth.OhmExciter
import com.snipsnap.synth.OhmGesture
import com.snipsnap.synth.OhmOptions
import com.snipsnap.synth.OhmPatch
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OhmExpandedPresetsTest {
    @Test
    fun `captured held presets retain their source and release through shelf and promotion`() {
        val root = createTempDirectory("ohm-source-preset").toFile()
        try {
            for (model in 3..4) {
                val patch = OhmPatch("MY FAMILY", Ohm.defaults(model) + ("HOLD" to 1f), midi = 59,
                    velocity = .4f, seed = -17, gesture = OhmGesture.GATHER, model = model,
                    options = OhmOptions(OhmExciter.CAPTURED, elder = .3f, freeze = .7f,
                        capture = OhmCapture(FloatArray(128) { if (it % 17 == 0) .5f else -.02f }, "MY \$HIT")),
                    releaseSeconds = 1.2f)
                val shelf = File(root, "model$model")
                UserPresets.save(shelf, patch, nowMillis = 100)
                val restored = UserPresets.read(shelf).single().patch
                assertEquals(patch, restored)
                val line = UserPresets.rosterLine(restored)
                assertTrue(line.startsWith("OhmPatch.fromJsonText("), "model $model must bypass the legacy p helper")
                val jsonLiteral = line.removePrefix("OhmPatch.fromJsonText(").removeSuffix("),").replace("\\$", "$")
                assertEquals(patch, OhmPatch.fromJsonText(Json.parse(jsonLiteral).str()))
                assertTrue(line.contains("\\$"), "source labels cannot become Kotlin interpolation")
            }
        } finally { root.deleteRecursively() }
    }
}
