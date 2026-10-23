package com.snipsnap.synth

import kotlin.test.Test

/** Exact model-2 arithmetic on the baseline's recorded JDK/architecture, not a listening claim. */
class OhmPerformanceCompatibilityTest {
    @Test
    fun `all 134 model two recipes retain every raw final and PCM16 bit`() {
        OhmPerformanceProbe.compareBundled()
    }
}
