package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenSkyPresetsTest {
    @Test
    fun `the eight accepted R3 sources remain two complete discoverable recipes per voice`() {
        val presets = OpenSkyPresets.all()
        assertEquals(ACCEPTED.map { it.name }, presets.map { it.name })
        assertEquals(8, presets.size)
        assertEquals(OpenSkyVoice.entries.toSet(), presets.map { it.voice }.toSet())
        assertTrue(Presets.all().containsAll(presets))
        assertTrue(Presets.forVoice("OPENSKY", "NOT_A_VOICE").isEmpty())
        for (voice in OpenSkyVoice.entries) {
            val group = OpenSkyPresets.forVoice(voice)
            assertEquals(2, group.size, "$voice lost its source contrast")
            assertEquals(presets.filter { it.voice == voice }, group)
            assertEquals(group, Presets.forVoice("OPENSKY", voice.name))
            assertFalse(group[0].timbre == group[1].timbre, "$voice is only two macro variations")
        }
        for ((patch, reference) in presets.zip(ACCEPTED)) {
            assertEquals(reference.voice, patch.voice, patch.name)
            assertEquals(reference.macros, patch.macros, "${patch.name} changed accepted front-panel settings")
            assertEquals(reference.timbre, patch.timbre, "${patch.name} changed the accepted R3 dry source")
            assertEquals(60, patch.midi)
            assertEquals(1f, patch.velocity)
            assertEquals(1.5f, patch.gateSeconds)
            assertTrue(patch.wet)
            assertEquals(OpenSky.MODEL_VERSION, patch.model)
            assertEquals(patch, Presets.byName("OPENSKY", patch.voiceName, patch.name))
            assertEquals(patch, assertIs<OpenSkyPatch>(Patches.fromJsonText(patch.toJsonText())))
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name))
        }
        val entries = OpenSkyPresets.entries()
        assertEquals(presets, entries.map { it.patch })
        assertEquals(ACCEPTED.map { it.name.lowercase().replace(' ', '-') }, entries.map { it.id })
        assertTrue(entries.all { it.description.isNotBlank() }, "audition descriptions vanished during promotion")
    }

    @Test
    fun `production recipes reproduce all eight accepted dry source WAV payloads`() {
        // SHA-256 values were extracted from the accepted R3 audition WAVs before
        // promotion. Their dry families all used exportGain 1. Shorter dry clips
        // were padded for the listening player, so hash only the source frames.
        // Keeping the digests here makes this independent of regenerated fixtures
        // and of the audition generator's own preset table.
        for (reference in ACCEPTED) {
            val saved = assertIs<OpenSkyPatch>(Presets.byName("OPENSKY", reference.voice.name, reference.name))
            val dry = saved.copy(wet = false).render()
            assertEquals(2, dry.channels, reference.name)
            assertEquals(44_100, dry.sampleRate)
            assertEquals(reference.frames, dry.frameCount, "${reference.name} source duration changed")
            assertTrue(dry.samples.all { it.isFinite() && it in -1f..1f }, reference.name)
            assertTrue(dry.samples.any { it != 0f }, "${reference.name} became silent")
            assertEquals(reference.pcm16Sha256, pcm16Digest(dry), "${reference.name} no longer matches accepted R3 audio")
        }
    }

    private fun pcm16Digest(snip: com.snipsnap.audio.Snip): String {
        val wav = ByteArrayOutputStream().also { WavWriter.write(it, snip, WavWriter.BitDepth.PCM_16) }.toByteArray()
        // WavWriter's no-metadata format has a fixed 44-byte header.
        return MessageDigest.getInstance("SHA-256").digest(wav.copyOfRange(44, wav.size))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class Accepted(
        val name: String,
        val voice: OpenSkyVoice,
        val macros: Map<String, Float>,
        val timbre: OpenSkyTimbre,
        val frames: Int,
        val pcm16Sha256: String,
    )

    private companion object {
        fun macros(rise: Float, width: Float, glow: Float, air: Float) =
            mapOf("RISE" to rise, "WIDTH" to width, "GLOW" to glow, "AIR" to air)

        val ACCEPTED = listOf(
            Accepted("First Light", OpenSkyVoice.ANTHEM, macros(.25f, .2f, .25f, .45f),
                OpenSkyTimbre(pulseMix = .9f, pulseWidth = .25f, sineMix = .1f, detuneScale = .3f,
                    toneScale = .65f, attackScale = .45f, sustainLevel = .65f, decaySeconds = .15f, releaseScale = .45f),
                79049, "821e8bb374de1fe009a8969984db01e5c74c2f70c4be34576cf28b0b666f3ce9"),
            Accepted("Full Column", OpenSkyVoice.ANTHEM, macros(.85f, .95f, .7f, .85f),
                OpenSkyTimbre(octaveMix = .4f, detuneScale = 1.5f, toneScale = 1.55f,
                    attackScale = .7f, releaseScale = 1.3f),
                103414, "6883ef0b046fe8737be7033ec01d3202c541d8840b1dd6a1390e169268085f2b"),
            Accepted("Blue Hour", OpenSkyVoice.SOAR, macros(.2f, .25f, .25f, .2f),
                OpenSkyTimbre(sineMix = .8f, centerWeightScale = 2.7f, detuneScale = .25f,
                    toneScale = .4f, attackScale = 1.1f),
                94815, "53ff5276822e4f5226775c8d97016f64a8ecb01f375def3153f4a2a9f827af1b"),
            Accepted("Solar Arc", OpenSkyVoice.SOAR, macros(.8f, .65f, .5f, .9f),
                OpenSkyTimbre(pulseMix = .55f, pulseWidth = .32f, sineMix = .05f, octaveMix = .15f,
                    centerWeightScale = .75f, detuneScale = 1.2f, toneScale = 1.8f,
                    attackScale = .35f, sustainLevel = .8f, decaySeconds = .16f, releaseScale = .65f),
                84782, "fac79c5e4d06642d6fad18890154278665596d00d5b625ad4e64b40d4b64a398"),
            Accepted("Soft Horizon", OpenSkyVoice.HALO, macros(.25f, .5f, .75f, .15f),
                OpenSkyTimbre(sineMix = .8f, detuneScale = .5f, toneScale = .35f,
                    attackScale = 1.7f, releaseScale = 2f),
                207270, "563ed96e677cac5827ac83ad2c5aa1c855823f96ad239ca1498bd83f442cde39"),
            Accepted("Open Sky", OpenSkyVoice.HALO, macros(.85f, .95f, .9f, .85f),
                OpenSkyTimbre(pulseMix = .25f, pulseWidth = .42f, sineMix = .25f, octaveMix = .45f,
                    detuneScale = 1.3f, toneScale = 1.6f, attackScale = .65f, releaseScale = 1.3f),
                157878, "22d2776f5cb08c2ff681c8e90184688f4771175387a2ff32f32d6540f78f5ea0"),
            Accepted("Sun Fleck", OpenSkyVoice.CRYSTAL, macros(.2f, .25f, .2f, .35f),
                OpenSkyTimbre(centerWeightScale = .4f, attackScale = .5f, sustainLevel = .35f,
                    decaySeconds = .08f, releaseScale = .3f, bellBrightnessScale = .25f, bellDecayScale = .3f),
                74750, "ae4a835cac55cfdf2127964f41b0e7d52978315be9edad32a092a2fdf1c9123e"),
            Accepted("Skylight", OpenSkyVoice.CRYSTAL, macros(.75f, .85f, .8f, .9f),
                OpenSkyTimbre(centerWeightScale = 2.3f, releaseScale = 2.3f,
                    bellBrightnessScale = 2f, bellDecayScale = 2.8f),
                132080, "5bcad2b189baed7f9f3fddedaff3b27180666a37d57c585b32cc7906910b1f5e"),
        )
    }
}
