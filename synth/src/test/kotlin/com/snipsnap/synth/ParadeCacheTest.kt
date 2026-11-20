package com.snipsnap.synth

import kotlin.test.*

class ParadeCacheTest {
    @Test fun `crash source cache is bounded and caller mutation cannot alter later strikes`() {
        ParadeSecondLine.clearCrashCache()
        val expected=ParadeSecondLine.freshCrash(.6,2)
        val returned=ParadeSecondLine.crashSource(.6,2)
        assertContentEquals(expected,returned)
        returned[100]=12345.0
        assertContentEquals(expected,ParadeSecondLine.crashSource(.6,2),"Cached crash changed after caller mutation")
        ParadeSecondLine.crashSource(.3,0);ParadeSecondLine.crashSource(1.0,1)
        assertEquals(2,ParadeSecondLine.crashCacheEntries())
        assertContentEquals(expected,ParadeSecondLine.crashSource(.6,2),"Eviction changed deterministic strike")
        ParadeSecondLine.clearCrashCache()
    }
}
