package com.snipsnap.shell

import com.snipsnap.synth.LorentzPatch
import com.snipsnap.synth.LorentzVoice
import kotlin.test.*

class LorentzPersistenceTest {
    @Test
    fun `promoted collision literal keeps native touch model and exact sparse macros`() {
        val source = LorentzPatch("Saved $ contact", LorentzVoice.LORENTZ,
            linkedMapOf("TUNE" to 7 / 24f, "IMPACT" to .8f), velocity = .38f)
        val line = UserPresets.rosterLine(source)
        val call = KotlinLiteral.parse(line.removeSuffix(",")) as KotlinLiteral.Call
        val text = (call.positional.single() as KotlinLiteral.Str).value
        val recalled = LorentzPatch.fromJsonText(text)
        assertEquals(source, recalled)
        assertContentEquals(source.render().samples, recalled.render().samples)
        assertEquals(31f, Spread.detect(source.render(), source))
    }
}
