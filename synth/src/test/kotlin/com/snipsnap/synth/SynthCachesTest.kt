package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SynthCachesTest {
    @Test
    fun `clearAll releases the held duet audio and source taps`() {
        SynthCaches.clearAll()
        ObbligatoHeld.render(ObbligatoPatch("SILENT HELD", Obbligato.defaults() + ("HOLD" to 1f),
            velocity = 0f, playback = ObbligatoPlayback.HELD))
        val populated = ObbligatoHeld.cacheStats()
        assertTrue(populated.entries > 0 && populated.bytes > 0, "the held capture must populate its cache")
        SynthCaches.clearAll()
        val cleared = ObbligatoHeld.cacheStats()
        assertEquals(0, cleared.entries)
        assertEquals(0L, cleared.bytes)
    }

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
