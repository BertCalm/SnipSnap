package com.snipsnap.shell

import com.snipsnap.synth.Registers
import com.snipsnap.synth.RegistersPatch
import com.snipsnap.synth.RegistersPresets
import com.snipsnap.synth.RegistersVoice
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegistersUserPresetsTest {
    @Test
    fun `saved restored and promoted registers keep explicit pitch velocity and model`() {
        val shelf = createTempDirectory("registers-presets").toFile()
        try {
            for ((voice, model) in listOf(RegistersVoice.PIPE to 2, RegistersVoice.TRANSISTOR to 4)) {
                val patch = RegistersPatch("MY ${voice.name}", voice,
                    Registers.defaults(voice) + ("AIR" to .6f), midi = 69, velocity = .4f, model = model)
                val saved = UserPresets.save(shelf, patch, nowMillis = 12L)
                assertEquals(listOf(saved), UserPresets.forVoice(UserPresets.read(shelf), patch.engine, patch.voiceName))
                val binned = requireNotNull(UserPresets.forget(shelf, patch.engine, patch.voiceName, patch.name, nowMillis = 13L))
                val restored = requireNotNull(UserPresets.unforget(shelf, binned))
                assertEquals(patch, restored.patch)
                assertContentEquals(patch.render().samples, restored.patch.render().samples)
                val line = UserPresets.rosterLine(patch)
                assertTrue(line.startsWith("p(RegistersVoice.${voice.name}, \"MY ${voice.name}\", "))
                assertTrue("midi = 69, velocity = 0.4f, model = $model" in line)
                assertEquals("RegistersPresets.kt", UserPresets.rosterFile(patch.engine))
                val factory = RegistersPresets.forVoice(voice).first()
                assertEquals(UserPresets.Check.Factory(UserPresets.normalize(factory.name)),
                    UserPresets.check(" ${factory.name.lowercase()} ", factory.engine, factory.voiceName, emptyList()))
            }
        } finally { shelf.deleteRecursively() }
    }
}
