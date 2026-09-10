package com.snipsnap.loop

import kotlin.test.Test
import kotlin.test.assertEquals

class FitReportTest {

    @Test
    fun `labels round to whole percent and say under one where it rounds away`() {
        assertEquals("TRIMMED <1% OFF THE END", FitReport(LoopFit.TRIMMED, 1_003, 1_000).label)
        assertEquals("PADDED 2% WITH SILENCE", FitReport(LoopFit.PADDED, 980, 1_000).label)
        assertEquals("SLICED AT 1 HIT · STRETCHED 25%", FitReport(LoopFit.SLICED, 750, 1_000, slices = 1).label)
        assertEquals("SLICED AT 12 HITS · SQUEEZED 8%", FitReport(LoopFit.SLICED, 1_080, 1_000, slices = 12).label)
    }

    @Test
    fun `drift is signed - positive when the snip ran long`() {
        assertEquals(0.5, FitReport(LoopFit.SLICED, 1_500, 1_000, 3).drift)
        assertEquals(-0.5, FitReport(LoopFit.SLICED, 500, 1_000, 3).drift)
    }
}
