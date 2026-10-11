package com.snipsnap.shell

import com.snipsnap.synth.Prism
import com.snipsnap.synth.PrismPatch
import com.snipsnap.synth.PrismPresets
import com.snipsnap.synth.PrismVoice
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrismUserPresetsTest {
    @Test
    fun `factory facet names remain protected after normalization`() {
        val patch = PrismPresets.forVoice(PrismVoice.KEY).first()
        assertEquals(UserPresets.Check.Factory(UserPresets.normalize(patch.name)),
            UserPresets.check("  ${patch.name.lowercase()}  ", patch.engine, patch.voiceName, emptyList()))
    }

    @Test
    fun `saved and restored user facets retain their complete additive recipe`() {
        val shelf = createTempDirectory("prism-presets").toFile()
        try {
            val patch = PrismPatch("MY FACETS", PrismVoice.KEY,
                Prism.defaults(PrismVoice.KEY) + mapOf("TUNE" to .25f, "GRAIN" to .7f), velocity = .4f,
                template = Prism.templateFor(PrismVoice.KEY).copy(attackSeconds = .02f, decaySeconds = 1.2f))
            val saved = UserPresets.save(shelf, patch, nowMillis = 12L)
            assertEquals(listOf(saved), UserPresets.read(shelf))
            assertEquals(patch, UserPresets.read(shelf).single().patch)
            val binned = requireNotNull(UserPresets.forget(shelf, patch.engine, patch.voiceName, patch.name, nowMillis = 13L))
            val restored = requireNotNull(UserPresets.unforget(shelf, binned))
            assertEquals(patch, restored.patch)
            assertContentEquals(patch.render().samples, restored.patch.render().samples)
            val line = UserPresets.rosterLine(patch)
            assertTrue(line.startsWith("p(PrismVoice.KEY, \"MY FACETS\", "))
            assertTrue("\"GRAIN\" to 0.7f" in line)
            assertTrue("velocity = 0.4f" in line, "promotion preserves native note energy")
            assertTrue("template = " in line, "promotion preserves independent partial contours")
            assertEquals("PrismPresets.kt", UserPresets.rosterFile(patch.engine))
        } finally {
            shelf.deleteRecursively()
        }
    }
}
