package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.synth.*
import kotlin.test.*

class TempestUserPresetsTest {
    @Test fun `model three factory voices keep their full controls through promotion`() {
        for (v in Tempest.EXPANSION_VOICES) {
            val p=TempestPresets.forVoice(v).first().copy(name="MY \"$ DRUM")
            val line=UserPresets.rosterLine(p)
            val literal=line.removePrefix("TempestPatch.fromJsonText(").removeSuffix("),").replace("\\$","$")
            val restored=TempestPatch.fromJsonText(Json.parse(literal).str())
            assertEquals(p,restored)
            assertEquals(3,restored.model)
            assertEquals(p.macros.getValue("DECAY"),restored.macros.getValue("DECAY"))
            assertContentEquals(p.render().samples,restored.render().samples)
        }
    }
    @Test fun `promotion preserves both saved models and escaped names`() {
        for (model in listOf(1, 2)) {
            val p = TempestPatch("A \"$ strike", TempestVoice.KICK, mapOf("SOURCE" to .3125f), model)
            val line = UserPresets.rosterLine(p)
            assertTrue(line.startsWith("TempestPatch.fromJsonText("))
            val literal = line.removePrefix("TempestPatch.fromJsonText(").removeSuffix("),").replace("\\$", "$")
            val restored = TempestPatch.fromJsonText(Json.parse(literal).str())
            assertEquals(p, restored)
            assertContentEquals(p.render().samples, restored.render().samples)
            assertEquals("TempestPresets.kt", UserPresets.rosterFile(p))
        }
    }
}
