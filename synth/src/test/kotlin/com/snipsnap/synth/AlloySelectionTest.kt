package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Owner-selected defaults must not reinterpret complete or minimal model-1 saves. */
class AlloySelectionTest {
    private val retainedVoices = setOf(AlloyVoice.BASS, AlloyVoice.SOFT)

    @Test
    fun `new patches and factory anchors retain the selected voices while authored roles keep their own configurations`() {
        assertEquals(24, AlloyPresets.all().size)
        for (voice in AlloyVoice.entries) {
            val expected = if (voice in retainedVoices) Alloy.configFor(voice)
                else AlloyContrastPresets.configFor(voice)
            assertEquals(expected, Alloy.defaultConfigFor(voice), "$voice selected the wrong recipe")
            assertEquals(expected, AlloyPatch("New ${voice.name}", voice, Alloy.defaults(voice)).config)
            if (voice !in retainedVoices) {
                assertTrue(expected != Alloy.configFor(voice), "$voice still uses its original default")
            }
            val presets = AlloyPresets.forVoice(voice)
            assertEquals(4, presets.size)
            assertEquals(expected, presets.first().config, "$voice lost its selected factory anchor")
            assertEquals(4, presets.map { it.config }.distinct().size,
                "$voice replaced its authored roles with one shared configuration")
            assertTrue(presets.all { !Alloy.isLoop(it.macros.getValue("HOLD")) },
                "$voice changed the approved finite role bank into held variants")
        }
    }

    @Test
    fun `missing and null saved configurations always retain the original model one recipe`() {
        for (voice in AlloyVoice.entries) for (configField in listOf("", ",\"config\":null")) {
            val restored = AlloyPatch.fromJsonText(
                """{"engine":"ALLOY","version":1,"name":"Legacy ${voice.name}","voice":"${voice.name}","model":1,"midi":55,"velocity":0.6,"macros":{"FUSE":0.6}$configField}""",
            )
            assertEquals(Alloy.configFor(voice), restored.config, "$voice reinterpreted a legacy save")
            assertEquals(55, restored.midi)
            assertEquals(.6f, restored.velocity)
            assertEquals(mapOf("FUSE" to .6f), restored.macros)
            assertEquals(restored, AlloyPatch.fromJsonText(restored.toJsonText()),
                "$voice did not make the original fallback explicit when saving again")
        }
    }

    @Test
    fun `complete original and selected saves preserve their audio through loading and editing`() {
        for (voice in AlloyVoice.entries) {
            val configs = listOf(Alloy.configFor(voice), Alloy.defaultConfigFor(voice)).distinct()
            for ((index, config) in configs.withIndex()) {
                val source = AlloyPatch("Saved ${voice.name} $index", voice,
                    Alloy.defaults(voice) + ("LENGTH" to .20f), midi = 55, velocity = .6f, config = config)
                val restored = AlloyPatch.fromJsonText(source.toJsonText())
                assertEquals(source, restored)
                assertEquals(source.toJsonText(), restored.toJsonText())
                val sourceAudio = pcm(source)
                assertTrue(sourceAudio.any { abs(it) > 1e-6f }, "${source.name} is silent")
                assertContentEquals(sourceAudio, pcm(restored), "${source.name} changed on reload")

                val macros = source.macros + mapOf("MATERIAL" to .81f, "BODY" to .37f)
                val edited = assertIs<AlloyPatch>(Patches.edited(restored, "Edited ${voice.name}", macros))
                val expected = source.copy(name = "Edited ${voice.name}", macros = macros)
                assertEquals(expected, edited, "${source.name} lost its explicit recipe while editing")
                assertContentEquals(pcm(expected), pcm(edited), "${source.name} changed its recipe while editing")
            }
        }
    }

    @Test
    fun `new bass and soft patches retain the original audio`() {
        for (voice in retainedVoices) {
            val patch = AlloyPatch("Retained ${voice.name}", voice, Alloy.defaults(voice),
                midi = 48, velocity = .75f)
            assertContentEquals(pcm(patch.copy(config = Alloy.configFor(voice))), pcm(patch),
                "$voice changed despite retaining its original voice design")
        }
    }

    // A bounded, nonzero capture exercises recipe replay without repeating the longer
    // pitch, aliasing and settled-loop checks in AlloyPresetsTest and AlloyContrastTest.
    private fun pcm(patch: AlloyPatch) = Alloy.renderProbe(patch.voice, patch.macros,
        midi = patch.midi, velocity = patch.velocity, seconds = .35f,
        config = patch.config, taps = false).samples
}
