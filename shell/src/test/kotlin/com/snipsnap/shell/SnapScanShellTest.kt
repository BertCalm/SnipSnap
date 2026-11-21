package com.snipsnap.shell

import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Photo
import com.snipsnap.synth.SnapPatch
import com.snipsnap.synth.SnapPortrait
import com.snipsnap.synth.SnapScan
import com.snipsnap.synth.SnapVoice
import java.util.Random
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** SNAP SCAN pads through the shell's SNAP paths: breeding keeps a portrait, the root names the register's octave. */
class SnapScanShellTest {

    private fun photo(shift: Int) = Photo.of(200, 150) { x, y ->
        val v = Math.round(127 + 90 * sin((x + shift) * 0.11) * if (y < 75) 1.0 else 0.4).toInt()
        Photo.rgb(v + 30, v, v - 20)
    }

    private fun pad(shift: Int) = SnapScan.read(photo(shift), SnapVoice.HORIZON, "P$shift", 1_763_740_000_000L + shift)

    @Test
    fun `a bred SNAP SCAN child keeps its first parent's portrait`() {
        val a = pad(0)
        val b = pad(40)
        val child = Breed.cross(PadRecipe(patch = a), PadRecipe(patch = b), Random(3)).patch as SnapPatch
        assertEquals(a.portrait, child.portrait)
        // and it still renders
        assertNotNull(child.render())
    }

    @Test
    fun `the root of a SNAP SCAN pad includes its register`() {
        val a = pad(0)
        val o = a.portrait!!
        val up = a.copy(portrait = SnapPortrait(o.version, o.strips, o.light, o.fromBelow, o.symmetry, o.warmth, o.grain, o.clip, minOf(o.register + 1, 2), o.seed))
        val rootA = SynthRoot.of(a)!!.midi
        val rootUp = SynthRoot.of(up)!!.midi
        assertEquals(if (o.register < 2) 12 else 0, rootUp - rootA)
    }
}
