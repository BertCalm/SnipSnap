package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Filament
import com.snipsnap.synth.FilamentConfig
import com.snipsnap.synth.FilamentPatch
import com.snipsnap.synth.FilamentPresets
import com.snipsnap.synth.FilamentProfile
import com.snipsnap.synth.FilamentVoice
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FilamentPreviewCacheTest {
    private val patch = FilamentPresets.all().first()
    private fun audio(value: Float = .25f) = Snip(floatArrayOf(value, -value), 1, 44_100)

    @Test
    fun `every saved sound field separates previews while names and equivalent defaults share audio`() {
        val cache = FilamentPreviewCache()
        var renders = 0
        fun preview(patch: FilamentPatch) = cache.getOrRender(patch) { renders++; audio() }
        preview(patch)
        preview(patch.copy(name = "Another label"))
        assertEquals(1, renders)
        val changes = listOf(
            patch.copy(macros = patch.macros + ("BEND" to .91f)),
            patch.copy(midi = 72), patch.copy(velocity = .43f),
            patch.copy(config = FilamentConfig(seed = Int.MIN_VALUE)),
            patch.copy(config = FilamentConfig(profile = FilamentProfile.LATE)),
            patch.copy(config = FilamentConfig(perfectRetention = true)),
            patch.copy(model = Filament.ORIGINAL_MODEL), patch.copy(model = Filament.CHARACTER_MODEL),
            patch.copy(voice = FilamentVoice.PULSE),
        )
        changes.forEach(::preview)
        assertEquals(1 + changes.size, renders)
        val partial = FilamentPatch("Partial", FilamentVoice.THREAD, emptyMap())
        preview(partial)
        preview(partial.copy(macros = Filament.defaults(partial.voice)))
        assertEquals(2 + changes.size, renders)
    }

    @Test
    fun `callers cannot mutate the cached samples on either a miss or a hit`() {
        val cache = FilamentPreviewCache()
        val miss = cache.getOrRender(patch) { audio() }
        miss.samples[0] = 99f
        val hit = cache.getOrRender(patch) { error("cached") }
        assertContentEquals(floatArrayOf(.25f, -.25f), hit.samples)
        hit.samples[0] = 88f
        assertContentEquals(floatArrayOf(.25f, -.25f), cache.getOrRender(patch) { error("cached") }.samples)
    }

    @Test
    fun `sample budget evicts the least recently used preview and skips an oversized render`() {
        val cache = FilamentPreviewCache(maximumBytes = 16)
        var renders = 0
        fun preview(midi: Int) = cache.getOrRender(patch.copy(midi = midi)) { renders++; audio() }
        preview(48); preview(60); preview(48); preview(72)
        assertEquals(3, renders)
        preview(48)
        assertEquals(3, renders)
        preview(60)
        assertEquals(4, renders)
        assertEquals(16L, cache.cachedBytes)
        val large = patch.copy(midi = 84)
        repeat(2) { cache.getOrRender(large) { renders++; Snip(FloatArray(5), 1, 44_100) } }
        assertEquals(6, renders)
        assertEquals(16L, cache.cachedBytes)
        cache.clear()
        assertEquals(0L, cache.cachedBytes)

        val entryBounded = FilamentPreviewCache()
        repeat(9) { midi -> entryBounded.getOrRender(patch.copy(midi = 48 + midi)) { audio() } }
        assertEquals(8L * 8, entryBounded.cachedBytes)
        var oldestRendered = false
        entryBounded.getOrRender(patch.copy(midi = 48)) { oldestRendered = true; audio() }
        assertEquals(true, oldestRendered)
    }

    @Test
    fun `a superseded render is discarded and even cache hits obey cancellation`() {
        val cache = FilamentPreviewCache()
        var cancelled = false
        assertFailsWith<CancellationException> {
            cache.getOrRender(patch, { cancelled }) { cancelled = true; audio() }
        }
        assertEquals(0L, cache.cachedBytes)
        cancelled = false
        cache.getOrRender(patch) { audio() }
        assertFailsWith<CancellationException> { cache.getOrRender(patch, { true }) { error("cached") } }
    }
}
