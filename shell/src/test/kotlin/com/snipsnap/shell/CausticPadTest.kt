package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.CausticInstrument
import com.snipsnap.synth.CausticPatch
import com.snipsnap.synth.CausticVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CausticPadTest {
    @Test fun `explicit held recipes gate and repeat the whole pure-loop sample`() {
        for (voice in CausticVoice.entries) {
            val patch = CausticPatch("Held vessel", voice,
                CausticInstrument.defaults(voice, 2) + mapOf("HOLD" to 1f, "MOTION" to .3f),
                midi = 57, velocity = .43f, model = 2)
            val original = KitPad(1, "held.wav", drumClass = DrumClass.LOOP,
                level = .6f, pan = .3f, tuneCoarse = 2, tuneFine = 7, attack = .2f, decay = .4f)
            val pad = CausticPad.configure(original, patch)
            assertEquals(original.copy(oneShot = false, loop = true, attack = 0f, decay = 1f), pad)
            val hit = assertNotNull(PadHit.resolve(pad, .75f, 0) { 8_192L })
            assertEquals(0L, hit.startFrame)
            assertEquals(8_192L, hit.endFrameExclusive)
            assertEquals(0L, hit.loopStartFrame, "ordinary pads repeat the exported pure loop from frame zero")
            assertEquals(57, patch.midi)
            assertEquals(.43f, patch.velocity)
        }
    }

    @Test fun `finite old and new recipes remain one shots even when duration files them LOOP`() {
        for (voice in CausticVoice.entries) for (model in listOf(1, 2)) {
            val patch = CausticPatch("Finite vessel", voice, CausticInstrument.defaults(voice, model), model = model)
            val original = KitPad(1, "finite.wav", drumClass = DrumClass.LOOP,
                oneShot = false, loop = true, attack = .2f, decay = .4f)
            val pad = CausticPad.configure(original, patch)
            assertTrue(pad.oneShot)
            assertFalse(pad.loop)
            assertEquals(.2f, pad.attack)
            assertEquals(.4f, pad.decay)
            val hit = assertNotNull(PadHit.resolve(pad, .75f, 0) { 8_192L })
            assertEquals(-1L, hit.loopStartFrame)
            assertEquals(original.copy(oneShot = true, loop = false), pad)
        }
    }
}
