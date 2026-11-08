package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import kotlin.test.*

class HalobarPresetsTest {
    @Test fun `accepted factory recipes keep identity valid names and reproducible recipes`() {
        for(voice in HalobarVoice.entries) {
            val patches=HalobarPresets.forVoice(voice)
            assertEquals(patches,Presets.forVoice(HalobarPatch.ENGINE,voice.name))
            assertEquals(patches.size,patches.map { it.name }.distinct().size)
            assertEquals(patches.size,patches.map { it.macros }.distinct().size)
            for(p in patches) {
                assertEquals(voice,p.voice)
                assertTrue(p.name.isNotBlank())
                assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(p.name))
                assertEquals(p,Patches.fromJsonText(p.toJsonText()))
            }
        }
        assertFalse(HalobarPatch.ENGINE in PatchTypes.PROTOTYPES)
        assertEquals(HalobarPresets.all(),Presets.all().filterIsInstance<HalobarPatch>())
        assertEquals(12,HalobarPresets.all().size)
        assertTrue(Presets.forVoice(HalobarPatch.ENGINE,"UNKNOWN").isEmpty())
    }

    @Test fun `the native kit has sixteen editable dry rack recipes across the family`() {
        val kit=SynthKits.halobar()
        assertEquals(16,kit.size)
        val voices=mutableSetOf<HalobarVoice>()
        for(pad in kit) {
            val p=assertNotNull(pad)
            assertEquals(true,p.oneShot)
            assertFalse(p.displayName.isNullOrBlank())
            val recipe=assertNotNull(p.recipe)
            val patch=assertIs<HalobarPatch>(PadRecipe.fromJsonValue(recipe).patch)
            voices+=patch.voice
            assertNull(PadRecipe.fromJsonValue(recipe).fx)
            assertEquals(Halobar.drumClassFor(patch.voice,patch.macros),p.drumClass)
            assertTrue(Classifier.classify(p.snip).drumClass in setOf(DrumClass.PERC,DrumClass.TONAL,DrumClass.LOOP))
        }
        assertEquals(HalobarVoice.entries.toSet(),voices)
    }
}
