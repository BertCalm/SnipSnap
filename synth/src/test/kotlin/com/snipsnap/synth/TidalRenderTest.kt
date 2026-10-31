package com.snipsnap.synth

import com.snipsnap.json.Json

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TidalRenderTest {
    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putFloat(sample)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test
    fun `production and diagnostic channels preserve frozen accepted performances`() {
        Tidal.clearCaches()
        val fixture = javaClass.getResourceAsStream("/tidal-render-references.json")!!.bufferedReader().use { it.readText() }
        val cases = Json.parse(fixture).obj().getValue("cases").arr()
        assertEquals(12, cases.size)
        for (value in cases) {
            val case = value.obj()
            val id = case.getValue("id").str()
            val patch = TidalPatch.fromJsonValue(case.getValue("patch"))
            val expected = Tidal.probe(patch.voice, patch.macros, patch.midi, patch.velocity,
                patch.seed, patch.model, Tidal.Options(normalize = true))
            val production = Tidal.renderProfiled(patch.voice, patch.macros, patch.midi,
                patch.velocity, patch.seed, patch.model)
            assertEquals(case.getValue("frames").int(), production.snip.samples.size, id)
            assertEquals(case.getValue("prelevelFloat32Sha256").str(), hash(expected.rawMix), "$id dry samples")
            assertEquals(case.getValue("normalizedFloat32Sha256").str(), hash(expected.samples), "$id diagnostic normalization")
            assertEquals(case.getValue("normalizedFloat32Sha256").str(), hash(production.snip.samples), "$id production samples")
            val expectedWorkBytes = if (patch.velocity == 0f) 0L
                else production.snip.samples.size.toLong() * Dsp.OVERSAMPLE * Float.SIZE_BYTES
            assertEquals(expectedWorkBytes, production.profile.oversampledWorkBytes,
                "$id at most one shared oversampled channel buffer")
        }
    }

    @Test
    fun `completed cache canonicalizes controls and never lends its owned samples`() {
        Tidal.clearCaches()
        val original = Tidal.render(TidalVoice.STREAM, midi = 48, seed = 41)
        val expected = original.samples.copyOf()
        original.samples.fill(0f)
        val explicit = Tidal.defaults(TidalVoice.STREAM).entries.reversed().associate { it.key to it.value }
        var checks = 0
        val repeated = Tidal.render(TidalVoice.STREAM, explicit, midi = 48, seed = 41,
            cancelled = { ++checks > 2 })
        assertEquals(2, checks, "equivalent controls should return the completed performance")
        assertTrue(expected.contentEquals(repeated.samples), "mutating the first result changed the cached sound")
        repeated.samples.fill(.9f)
        assertTrue(expected.contentEquals(Tidal.render(TidalVoice.STREAM, midi = 48, seed = 41).samples),
            "mutating a cache hit changed the next result")
        val snapshot = Tidal.audioCacheSnapshot()
        assertEquals(1, snapshot.entries)
        assertEquals(expected.size.toLong() * Float.SIZE_BYTES, snapshot.bytes)
        assertTrue(snapshot.bytes <= snapshot.maxBytes)
    }

    @Test
    fun `all audible recipe inputs separate cache entries and cancelled misses stay absent`() {
        Tidal.clearCaches()
        val base = Tidal.defaults(TidalVoice.STREAM)
        Tidal.render(TidalVoice.STREAM, base, midi = 48, seed = 41)
        fun requireMiss(voice: TidalVoice = TidalVoice.STREAM, macros: Map<String, Float> = base,
            midi: Int = 48, velocity: Float = .75f, seed: Long = 41L, model: Int = 2) {
            var checks = 0
            // A hit completes after two cancellation checks. A miss must begin fresh
            // preparation and reaches the third; cancel there without rendering it.
            assertFailsWith<CancellationException> {
                Tidal.render(voice, macros, midi, velocity, seed, model, cancelled = { ++checks > 2 })
            }
        }
        for (macro in listOf("APPROACH", "PASSAGE", "TILT", "BIND", "TAIL"))
            requireMiss(macros = base + (macro to .123f))
        requireMiss(voice = TidalVoice.PAIR)
        requireMiss(midi = 49)
        requireMiss(velocity = .6f)
        requireMiss(seed = Long.MIN_VALUE)
        requireMiss(model = 1)
        assertEquals(1, Tidal.audioCacheSnapshot().entries)
        assertFailsWith<IllegalArgumentException> { Tidal.render(TidalVoice.STREAM, base + ("HOLD" to .1f)) }
        assertFailsWith<IllegalArgumentException> { Tidal.render(TidalVoice.STREAM, midi = 35) }
        assertFailsWith<IllegalArgumentException> { Tidal.render(TidalVoice.STREAM, velocity = Float.NaN) }
    }

    @Test
    fun `cached and in progress requests both honor cancellation`() {
        Tidal.clearCaches()
        Tidal.render(TidalVoice.PAIR, midi = 60, seed = 3)
        assertFailsWith<CancellationException> {
            Tidal.render(TidalVoice.PAIR, midi = 60, seed = 3, cancelled = { true })
        }
        Tidal.clearAudioCache()
        var checks = 0
        assertFailsWith<CancellationException> {
            Tidal.render(TidalVoice.PAIR, midi = 60, seed = 3, cancelled = { ++checks > 12 })
        }
        assertEquals(0, Tidal.audioCacheSnapshot().entries)
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { Tidal.render(TidalVoice.PAIR) }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `audio cache evicts by retained bytes and owns all published buffers`() {
        val cache = TidalAudioCache<String>(32)
        val source = floatArrayOf(1f, 2f, 3f, 4f)
        cache.put("a", source)
        source.fill(0f)
        cache.put("b", FloatArray(4) { 2f })
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f), cache.get("a"))
        cache.put("c", FloatArray(4) { 3f })
        assertNull(cache.get("b"), "the least recently used buffer should leave first")
        assertNotNull(cache.get("a")).fill(7f)
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f), cache.get("a"))
        assertEquals(32L, cache.snapshot().bytes)
        cache.put("a", FloatArray(2))
        assertEquals(24L, cache.snapshot().bytes)
        cache.put("oversized", FloatArray(9))
        assertNull(cache.get("oversized"))
        assertEquals(24L, cache.snapshot().bytes)
        assertFailsWith<CancellationException> {
            cache.put("cancelled", FloatArray(2)) { throw CancellationException() }
        }
        assertNull(cache.get("cancelled"))
        cache.clear()
        assertEquals(Tidal.AudioCacheSnapshot(0, 0, 32), cache.snapshot())
    }
}
