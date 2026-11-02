package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthCachesTest {
    @Test
    fun `clearAll empties the tidal audio cache`() {
        SynthCaches.clearAll()
        Tidal.render(TidalVoice.STREAM, midi = 48, seed = 41)
        assertTrue(Tidal.audioCacheSnapshot().entries > 0, "the render should have filled the cache")
        SynthCaches.clearAll()
        val snapshot = Tidal.audioCacheSnapshot()
        assertEquals(0, snapshot.entries)
        assertEquals(0L, snapshot.bytes)
    }

    @Test
    fun `clearAll is safe on empty caches and when repeated`() {
        SynthCaches.clearAll()
        SynthCaches.clearAll()
        assertEquals(0, Tidal.audioCacheSnapshot().entries)
    }
}
