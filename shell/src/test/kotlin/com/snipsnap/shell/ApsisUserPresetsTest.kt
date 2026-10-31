package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.synth.ApsisConfig
import com.snipsnap.synth.ApsisPatch
import com.snipsnap.synth.ApsisPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApsisUserPresetsTest {
    @Test
    fun `promotion retains the complete orbital recipe as an escaped Kotlin constructor`() {
        for (model in listOf(1, 2, 3)) {
            val patch = ApsisPresets.all()[1].copy(name = "A \"$ passage", midi = 48,
                velocity = .3f, config = ApsisConfig(modelVersion = model, seedContext = Int.MIN_VALUE))
            val line = UserPresets.rosterLine(patch)
            assertTrue(line.startsWith("ApsisPatch.fromJsonText("))
            val literal = line.removePrefix("ApsisPatch.fromJsonText(").removeSuffix("),").replace("\\$", "$")
            assertEquals(patch, ApsisPatch.fromJsonText(Json.parse(literal).str()))
            assertEquals("ApsisPresets.kt", UserPresets.rosterFile(patch))
        }
    }
}
