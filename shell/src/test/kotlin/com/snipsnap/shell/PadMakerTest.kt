package com.snipsnap.shell

import com.snipsnap.kit.PadFromAnything
import kotlin.test.Test
import kotlin.test.assertEquals

class PadMakerTest {

    @Test
    fun `the two knobs open at the builder's own defaults and read in plain units`() {
        assertEquals(PadFromAnything.DEPTH_DEFAULT, PadMaker.DEPTH.value(PadMaker.DEPTH.defaultFraction), 0.5f)
        assertEquals("×40", PadMaker.depthLabel(40f))
        assertEquals("300 ms", PadMaker.bloomLabel(0.3f))
        val spec = PadMaker.spec(PadMaker.DEPTH.defaultFraction, PadMaker.BLOOM.defaultFraction, 9L)
        assertEquals(PadFromAnything.Spec(40f, 0.3f, 9L), spec)
        assertEquals(PadFromAnything.DEPTH_MIN, PadMaker.spec(0f, 0f, 1L).depth)
        assertEquals(PadFromAnything.DEPTH_MAX, PadMaker.spec(1f, 1f, 1L).depth)
    }
}
