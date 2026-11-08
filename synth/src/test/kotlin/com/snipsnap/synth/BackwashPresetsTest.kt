package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.*

class BackwashPresetsTest {
    @Test fun `factory registration preserves heard defaults and valid complete names`() {
        val bank = BackwashPresets.all()
        assertEquals(36, bank.size)
        assertEquals(36, bank.map { it.name }.toSet().size)
        assertTrue(Presets.all().containsAll(bank))
        assertTrue(Presets.forVoice("BACKWASH", "BAD").isEmpty())
        for (voice in BackwashVoice.entries) {
            val presets = BackwashPresets.forVoice(voice)
            assertEquals(presets, Presets.forVoice("BACKWASH",voice.name))
            assertEquals(Backwash.defaults(voice), presets.first().macros)
            for (patch in presets) {
                assertEquals(Backwash.defaults(voice).keys,patch.macros.keys)
                assertTrue(patch.macros.values.all { it.isFinite() && it in 0f..1f })
                assertTrue(patch.name.length<=18 && !PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name))
                assertEquals(patch,Presets.byName("BACKWASH",voice.name,patch.name))
                assertNull(Presets.landingFor("BACKWASH",voice.name,patch.macros))
            }
        }
    }
    @Test fun `factory patches reproduce all 48 delivered kit strikes exactly`() {
        val evidence = Json.parse(requireNotNull(javaClass.getResource("/backwash/accepted-kit.json")).readText()).arr()
        assertEquals(48,evidence.size)
        for (entry in evidence) {
            val item=entry.obj()
            assertEquals(3,item.getValue("model").int())
            val old=PadRecipe.fromJsonValue(item.getValue("recipe")).patch as BackwashPatch
            val factory=Presets.byName(old.engine,old.voiceName,old.name) as BackwashPatch
            assertEquals(old,factory)
            val snip=factory.render(item.getValue("velocity").num().toFloat())
            val bytes=ByteArrayOutputStream().also { WavWriter.write(it,snip,WavWriter.BitDepth.PCM_24) }.toByteArray()
            val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            assertEquals(item.getValue("wavSha256").str(),sha,"${old.name} ${item.getValue("velocity")}")
        }
    }
}
