package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.*

class DrumlinePresetsTest {
    private val bank = DrumlinePresets.all()

    @Test fun `factory bank covers every voice with complete approved recipes and lookup`() {
        assertEquals(32, bank.size)
        assertEquals(bank.size, bank.map { it.name }.toSet().size)
        assertEquals(DrumlineVoice.entries.toSet(), bank.map { it.voice }.toSet())
        assertEquals(bank.toSet(), Presets.all().filterIsInstance<DrumlinePatch>().toSet())
        for (voice in DrumlineVoice.entries) {
            val expected = bank.filter { it.voice == voice }
            assertEquals(expected, DrumlinePresets.forVoice(voice))
            assertEquals(expected, Presets.forVoice(DrumlinePatch.ENGINE, voice.name))
        }
        assertTrue(Presets.forVoice(DrumlinePatch.ENGINE, "UNKNOWN").isEmpty())
        for (patch in bank) {
            assertEquals(Drumline.defaults(patch.voice).keys, patch.macros.keys)
            assertEquals(4, patch.model)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
            assertEquals(patch, Presets.byName(patch.engine, patch.voiceName, patch.name))
            assertEquals(patch.name.uppercase(), patch.name)
            assertTrue(patch.name.length <= 24, patch.name)
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name), patch.name)
        }
    }

    @Test fun `factory renders match all thirty-two published approved kit hits`() {
        val hashes = requireNotNull(javaClass.getResourceAsStream("/drumline-approved-model4.csv"))
            .bufferedReader().use { it.readLines() }.drop(1).map { it.split(',') }
        assertEquals(32, hashes.size)
        for ((index, patch) in bank.withIndex()) {
            val row = hashes[index]
            assertEquals(DrumlineKits.Kit.entries[index / 16].id, row[0])
            assertEquals(index % 16 + 1, row[1].toInt())
            val restored = Patches.fromJsonText(patch.toJsonText())
            val wav = ByteArrayOutputStream()
            WavWriter.write(wav, restored.render(), WavWriter.BitDepth.PCM_24)
            val actual = MessageDigest.getInstance("SHA-256").digest(wav.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals(row[2], actual, "${patch.name}: approved audio changed")
        }
    }

    @Test fun `field and street variants are distinct authored recipes`() {
        for (voice in DrumlineVoice.entries) {
            val sounds = DrumlinePresets.forVoice(voice)
            for (a in sounds.indices) for (b in a + 1 until sounds.size)
                assertTrue(PresetTestSupport.rmsDistance(sounds[a].macros, sounds[b].macros) > .02,
                    "${sounds[a].name} and ${sounds[b].name} duplicate a recipe")
        }
    }
}
