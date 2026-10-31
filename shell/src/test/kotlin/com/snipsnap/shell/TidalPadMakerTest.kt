package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.Scale
import com.snipsnap.audio.DrumClass
import com.snipsnap.synth.PadRecipe
import kotlin.io.path.createTempDirectory
import com.snipsnap.kit.KitPad
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Tidal
import com.snipsnap.synth.TidalPatch
import com.snipsnap.synth.TidalVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TidalPadMakerTest {
    private val patch = TidalPatch("SAVED FIELD", TidalVoice.STREAM, Tidal.defaults(TidalVoice.STREAM),
        midi = 57, velocity = .42f, seed = Long.MIN_VALUE)

    @Test
    fun `instrument recipe keeps the exact source performance and release with all keyboard zones`() {
        val spec = TidalPadMaker.Spec(patch, .7f)
        assertEquals(spec, TidalPadMaker.Spec.fromJsonText(spec.toJsonText()))
        val midis = TidalPadMaker.zoneMidis(spec)
        assertEquals((36..84 step 3).toList(), midis)
        val note = KeyNote(Snip(FloatArray(1024), 1, 44100), 512)
        val (program, samples) = TidalPadMaker.assemble("TIDAL KEYS", spec, List(midis.size) { note })
        assertEquals(17, samples.size)
        assertEquals(17, program.keygroups.size)
        assertEquals(.7f, program.volumeRelease)
        assertEquals((36..84).toList(), program.keygroups.flatMap { (it.lowNote..it.highNote).toList() })
        assertEquals(midis, program.keygroups.map { it.rootNote })
        assertTrue(program.keygroups.all { it.layers.single().loopStartFrame == 512L })
        assertFailsWith<IllegalArgumentException> { TidalPadMaker.Spec(patch, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { TidalPadMaker.assemble("BAD", spec, emptyList()) }
    }

    @Test
    fun `spread carries explicit held playback to every pitched pad and finite recipes stay one shot`() {
        val dir = createTempDirectory("tidal-spread").toFile()
        try {
            for (hold in listOf(0f, 1f)) {
                val source = patch.copy(macros = patch.macros + ("HOLD" to hold))
                val model = KitBuilderModel.create("FIELD", java.io.File(dir, "hold-$hold"))
                val plan = Spread.plan(model.kit, 57f, Spread.Options(57, Scale.MINOR, padCount = 4))
                val tone = Snip(FloatArray(4410) { kotlin.math.sin(it * .03).toFloat() * .2f }, 1, 44100)
                Spread.apply(model, tone, plan, Spread.Sound("FIELD", DrumClass.LOOP, "#88bbcc",
                    recipe = PadRecipe(source).toJsonValue()))
                assertEquals(4, model.kit.pads.size)
                assertTrue(model.kit.pads.all { it.loop == (hold == 1f) && it.oneShot == (hold == 0f) })
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `pad gating follows hold rather than the long buffer class and returning to note clears looping`() {
        val pad = KitPad(slot = 1, sampleFile = "field.wav", oneShot = true, loop = false)
        val held = TidalPad.configure(pad, patch.copy(macros = patch.macros + ("HOLD" to 1f)))
        assertFalse(held.oneShot)
        assertTrue(held.loop)
        val finite = TidalPad.configure(held, patch)
        assertTrue(finite.oneShot)
        assertFalse(finite.loop)
    }
}
