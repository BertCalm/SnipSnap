package com.snipsnap.shell

import com.snipsnap.synth.*
import java.nio.file.Files
import kotlin.test.*

class SecondlineKitTest {
    @Test fun `current model preserves every hard reference and both legacy soft stick recipes`() {
        for(voice in ParadeSecondLine.Voice.entries) {
            val old=SecondlinePatch("Reference",voice,emptyMap(),2).render(1f)
            val current=SecondlinePatch("Reference",voice,emptyMap(),4).render(1f)
            assertContentEquals(old.samples,current.samples,voice.name)
        }
        val file=Files.createTempFile("legacy-stick", ".wav").toFile()
        try {
            for(model in listOf(1,2)) {
                val patch=SecondlinePatch("Soft stick",ParadeSecondLine.Voice.STICK_CLICK,emptyMap(),model)
                val replay=SecondlinePatch.fromJsonText(patch.toJsonText()).render(.3f,2)
                com.snipsnap.audio.WavWriter.write(file,replay,com.snipsnap.audio.WavWriter.BitDepth.PCM_16)
                val hash=java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                assertEquals("b45892f433d2114681a95848d206e3363e345a3a62f45e1c03810a3397f23310",hash)
            }
        } finally { file.delete() }
    }

    @Test fun `promotion keeps exact models and controls for all provisional voices`() {
        assertEquals("ParadeSecondLine.Voice",UserPresets.voiceEnum("SECONDLINE"))
        assertEquals("SecondlinePresets.kt",UserPresets.rosterFile("SECONDLINE"))
        for(p in SecondlinePresets.all()) {
            val original=p.copy(macros=p.macros+("DAMP" to .73123455f))
            val line=UserPresets.rosterLine(original)
            val call=KotlinLiteral.parse(line.removeSuffix(",")) as KotlinLiteral.Call
            assertEquals("fromJsonText",call.name)
            assertEquals("SecondlinePatch",(call.receiver as KotlinLiteral.Call).name)
            val restored=SecondlinePatch.fromJsonText((call.positional.single() as KotlinLiteral.Str).value)
            assertEquals(original,restored)
            assertEquals(original.macros.mapValues { it.value.toRawBits() },restored.macros.mapValues { it.value.toRawBits() })
        }
    }

    @Test fun `both native Second Line kits retain editable recipes and playback grids through export`() {
        for(kit in SecondlineKits.Kit.entries) {
            val dir=Files.createTempDirectory("secondline-kit").toFile()
            try { assertEquals(16,SecondlineKitProof.build(kit,dir).pads.size) } finally { dir.deleteRecursively() }
        }
    }
    @Test fun `triangle variation replaces only optional metal colors`() {
        val base=SecondlineKits.patches(SecondlineKits.Kit.CLASSIC)
        val triangle=SecondlineKits.patches(SecondlineKits.Kit.TRIANGLE)
        assertEquals(listOf(13,14),base.indices.filter { base[it]!=triangle[it] })
        assertEquals(ParadeSecondLine.Voice.entries.toSet(),SecondlinePresets.all().map { it.voice }.toSet())
        assertTrue((base+triangle).all { it.model==4 })
        val old=SecondlinePatch("Legacy triangle",ParadeSecondLine.Voice.TRIANGLE,emptyMap(),1)
        assertEquals(old,SecondlinePatch.fromJsonText(old.toJsonText()))
        assertEquals(old,com.snipsnap.synth.Patches.fromJsonText(old.toJsonText()))
    }
}
