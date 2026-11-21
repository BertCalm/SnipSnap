package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.json.Json
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SNAP SCAN round 1a (docs/superpowers/specs/2026-11-21-snap-scan-design.md §10): the house probe
 * set for a portrait pad, plus the round's own: alignment, SCAN 0, the fingerprint's bounds and
 * round robin. The legacy pads' bit-for-bit guard is [SnapLegacyGoldenTest].
 */
class SnapScanTest {

    /** A picture with a bright sky, a band of stripes, and a textured, warm ground. */
    private fun scene(w: Int = 256, h: Int = 192, tint: Int = 0): Photo = Photo.of(w, h) { x, y ->
        val v = when {
            y < h / 3 -> 0.85f - 0.2f * y / (h / 3f)
            y < 2 * h / 3 -> if (((x + y / 4) * 10 / w) % 2 == 0) 0.25f else 0.7f
            else -> 0.35f + 0.2f * sin(x * 0.31 + y * 0.17).toFloat() * sin(x * 0.05).toFloat()
        }
        val c = Math.round(v * 255f)
        Photo.rgb(c + 20 + tint, c, c - 25)
    }

    private val shot = 1_763_740_000_000L

    private fun pad(photo: Photo = scene(), voice: SnapVoice = SnapVoice.HORIZON) = SnapScan.read(photo, voice, "Scene", shot)

    private fun meanAbsDiff(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        return (0 until n).sumOf { abs(a[it] - b[it]).toDouble() } / n + abs(a.size - b.size) / 1000.0
    }

    // ---------- reading ----------

    @Test
    fun `a read keeps a portrait and the photo's own knobs`() {
        val p = pad()
        val portrait = assertNotNull(p.portrait)
        assertEquals(SnapScan.STRIPS, portrait.strips.size)
        assertEquals(setOf("TUNE", "BRIGHT", "DECAY", "GRIT", "SCAN"), p.macros.keys)
        for ((k, v) in p.macros) assertTrue(v in 0f..1f, "$k = $v")
        assertTrue(p.macros.getValue("SCAN") in 0.3f..1f, "SCAN comes from the photo: ${p.macros["SCAN"]}")
        assertTrue(portrait.light > 0.5f && !portrait.fromBelow, "a bright sky reads as a strike: $portrait")
        assertTrue(portrait.register in -1..2)
    }

    @Test
    fun `the same photo at the same moment reads to the same pad`() {
        assertEquals(pad(), pad())
        assertNotEquals(pad().portrait!!.seed, SnapScan.read(scene(), SnapVoice.HORIZON, "Scene", shot + 5_000).portrait!!.seed)
    }

    @Test
    fun `a drawn line has no portrait and keeps four knobs`() {
        assertEquals(4, Snap.macrosFor(SnapVoice.DRAWN).size)
        assertEquals(5, Snap.macrosFor(SnapVoice.ORBIT).size)
        assertFailsWith<IllegalArgumentException> { SnapScan.read(scene(), SnapVoice.DRAWN, "X", shot) }
        val p = pad()
        assertFailsWith<IllegalArgumentException> { SnapPatch("X", SnapVoice.DRAWN, emptyMap(), p.table, portrait = p.portrait) }
    }

    @Test
    fun `a uniform frame is cropped before the read`() {
        val framed = Photo.of(300, 300) { x, y ->
            if (x < 30 || y < 30 || x >= 270 || y >= 270) 0 else Math.round(127 + 100 * sin(x * 0.2)).toInt().let { Photo.rgb(it, it, it) }
        }
        val cropped = SnapScan.cropBorder(framed)
        assertEquals(240, cropped.width)
        assertEquals(240, cropped.height)
        // a frame that would leave less than half is not a frame
        val dark = Photo.of(300, 300) { x, _ -> if (x < 200) 0 else Photo.rgb(200, 200, 200) }
        assertEquals(300, SnapScan.cropBorder(dark).width)
    }

    @Test
    fun `flat strips stay finite and play`() {
        val sky = Photo.of(256, 192) { x, y -> if (y < 120) Photo.rgb(120, 160, 230) else Math.round(127 + 100 * sin(x * 0.3)).toInt().let { Photo.rgb(it, it - 20, it - 40) } }
        val p = SnapScan.read(sky, SnapVoice.HORIZON, "Sky", shot)
        val s = p.render()
        assertTrue(s.samples.all { it.isFinite() })
        assertTrue(s.peak() > 0.5f)
    }

    // ---------- alignment ----------

    @Test
    fun `aligned strips never cancel in a blend, and unaligned ones do`() {
        // the same stripe pattern shifted further on every strip: the worst case for a blend
        val n = Snap.TABLE_SIZE
        val shifted = (0 until SnapScan.STRIPS).map { k ->
            FloatArray(n) { i -> sin(2.0 * PI * 3.0 * (i + k * 21) / n).toFloat() + 0.4f * sin(2.0 * PI * 7.0 * (i + k * 21) / n).toFloat() }
        }
        val raw = SnapScan.worstBlendDip(shifted)
        val aligned = SnapScan.worstBlendDip(SnapScan.align(shifted))
        assertTrue(raw < -3.0, "the unaligned case must really dip: $raw dB")
        assertTrue(aligned > -3.0, "aligned strips dipped $aligned dB")
        // and on a read photo's own stored strips
        val strips = pad().portrait!!.strips.map { Snap.cycle(it) }
        assertTrue(SnapScan.worstBlendDip(strips) > -3.0, "stored strips dip ${SnapScan.worstBlendDip(strips)} dB")
    }

    // ---------- the sound ----------

    @Test
    fun `a portrait pad renders clean, bounded audio across its knobs`() {
        val p = pad()
        for (macros in listOf(p.macros, Snap.macrosFor(p.voice).associate { it.name to 0f }, Snap.macrosFor(p.voice).associate { it.name to 1f })) {
            val s = p.copy(macros = macros).render()
            assertTrue(s.frameCount > 0)
            assertTrue(s.samples.all { it.isFinite() && it in -1f..1f }, "out of range at $macros")
            assertTrue(s.peak() > 0.5f, "too quiet at $macros")
            assertTrue(abs(s.samples.average()) < 0.01, "DC ${s.samples.average()} at $macros")
            assertTrue(s.durationSeconds < 1.5f, "must stay a one-shot: ${s.durationSeconds}s")
            val cls = Classifier.classify(s).drumClass
            assertTrue(cls == DrumClass.TONAL || cls == DrumClass.PERC, "$cls at $macros")
        }
    }

    @Test
    fun `every knob changes a portrait pad`() {
        val p = pad()
        val base = p.render().samples
        for (spec in Snap.macrosFor(p.voice)) {
            val v = p.macros.getValue(spec.name)
            val moved = p.copy(macros = p.macros + (spec.name to if (v > 0.5f) v - 0.4f else v + 0.4f)).render().samples
            assertTrue(meanAbsDiff(base, moved) > 1e-3, "${spec.name} moved nothing")
        }
    }

    @Test
    fun `every portrait reading changes the sound`() {
        val p = pad()
        val o = p.portrait!!
        val base = p.render().samples
        fun with(q: SnapPortrait) = p.copy(portrait = q).render().samples
        fun portrait(light: Float = o.light, fromBelow: Boolean = o.fromBelow, symmetry: Float = o.symmetry, warmth: Float = o.warmth,
                     grain: Float = o.grain, clip: Float = o.clip, register: Int = o.register) =
            SnapPortrait(o.version, o.strips, light, fromBelow, symmetry, warmth, grain, clip, register, o.seed)
        val flips = mapOf(
            "light" to portrait(light = 1f - o.light),
            "fromBelow" to portrait(fromBelow = !o.fromBelow),
            "symmetry" to portrait(symmetry = 1f - o.symmetry),
            "warmth" to portrait(warmth = 1f - o.warmth),
            "grain" to portrait(grain = if (o.grain > 0.5f) 0f else 1f),
            "clip" to portrait(clip = if (o.clip > 0.5f) 0f else 1f),
            "register" to portrait(register = if (o.register < 2) o.register + 1 else o.register - 1),
        )
        for ((name, q) in flips) assertTrue(meanAbsDiff(base, with(q)) > 1e-3, "$name moved nothing")
    }

    @Test
    fun `SCAN 0 plays only the whole-picture line`() {
        val p = pad().let { it.copy(macros = it.macros + ("SCAN" to 0f)) }
        val o = p.portrait!!
        val other = SnapPortrait(o.version, o.strips.reversed(), o.light, o.fromBelow, o.symmetry, o.warmth, o.grain, o.clip, o.register, o.seed)
        assertContentEquals(p.render().samples, p.copy(portrait = other).render().samples)
        val scanning = p.copy(macros = p.macros + ("SCAN" to 0.8f))
        assertTrue(meanAbsDiff(scanning.render().samples, scanning.copy(portrait = other).render().samples) > 1e-3)
    }

    @Test
    fun `the note is TUNE's, moved by the register`() {
        val smooth = Photo.of(256, 192) { x, y ->
            val v = Math.round(127 + 90 * sin(2.0 * PI * x / 256) + 10 * sin(y * 0.05)).toInt(); Photo.rgb(v, v, v)
        }
        val p = SnapScan.read(smooth, SnapVoice.HORIZON, "Smooth", shot)
        val o = p.portrait!!
        for (register in -1..2) {
            val q = SnapPortrait(o.version, o.strips, o.light, o.fromBelow, o.symmetry, o.warmth, o.grain, o.clip, register, o.seed)
            val pad = p.copy(macros = p.macros + mapOf("TUNE" to 0.25f, "GRIT" to 0f, "BRIGHT" to 0.8f, "DECAY" to 0.8f), portrait = q)
            val expected = SnapScan.frequencyFor(0.25f, q)
            assertEquals(Snap.frequencyFor(0.25f) * Math.pow(2.0, register.toDouble()).toFloat(), expected, 0.01f)
            val measured = TestPitch.estimate(pad.render())
            // within 6% (the fingerprint moves at most 3 cents); an octave error is the detector's, not the note's
            val ok = listOf(0.5f, 1f, 2f).any { m -> measured > expected * m * 0.94f && measured < expected * m * 1.06f }
            assertTrue(ok, "register $register: expected ~$expected Hz, measured $measured Hz")
        }
    }

    // ---------- fingerprint and round robin ----------

    @Test
    fun `the fingerprint stays inside its bounds`() {
        for (take in 0 until 1000) {
            val f = SnapScan.Fingerprint(12345, take)
            assertTrue(abs(f.cents) <= 3f, "take $take: ${f.cents} cents")
            assertTrue(f.lengthScale in 0.94f..1.06f, "take $take: length ${f.lengthScale}")
            assertTrue(f.scanStart in 0f..0.5f, "take $take: scan start ${f.scanStart}")
        }
        val long = pad().let { it.copy(macros = it.macros + ("DECAY" to 1f)) }
        for (take in 0 until 8) assertTrue(long.copy(take = take).render().durationSeconds < 1.5f)
    }

    @Test
    fun `takes are distinct, deterministic, and take 0 is the pad`() {
        val p = pad()
        val t0 = p.render().samples
        val t1 = p.copy(take = 1).render().samples
        assertContentEquals(t0, p.render().samples)
        assertContentEquals(t1, p.copy(take = 1).render().samples)
        assertTrue(meanAbsDiff(t0, t1) > 1e-3, "take 1 is a copy of take 0")
        // ... but a take is the same instrument, not another one: the spectrum stays close
        val c0 = FeatureExtractor.extract(p.render()).centroidHz
        val c1 = FeatureExtractor.extract(p.copy(take = 1).render()).centroidHz
        assertTrue(abs(c0 - c1) / c0 < 0.25f, "centroid moved $c0 -> $c1")
    }

    @Test
    fun `a portrait pad re-strikes and a legacy pad does not`() {
        val p = pad()
        assertTrue(Restrike.canRestrike(p))
        val legacy = SnapPatch("Old", SnapVoice.HORIZON, emptyMap(), p.table)
        assertFalse(Restrike.canRestrike(legacy))
        assertNull(Restrike.take(legacy, 1f, 7, 1))
        assertContentEquals(Velocity.atVelocity(p, 1f).samples, Restrike.take(p, 1f, 7, 0)!!.samples)
        val a = Restrike.take(p, 1f, 7, 1)!!.samples
        val b = Restrike.take(p, 1f, 7, 2)!!.samples
        assertContentEquals(a, Restrike.take(p, 1f, 7, 1)!!.samples)
        assertTrue(meanAbsDiff(a, b) > 1e-3)
    }

    @Test
    fun `velocity changes the strike, not only the level`() {
        val p = pad()
        val soft = FeatureExtractor.extract(Velocity.atVelocity(p, 0.2f))
        val hard = FeatureExtractor.extract(Velocity.atVelocity(p, 1f))
        assertTrue(hard.centroidHz > soft.centroidHz * 1.1f, "centroid soft ${soft.centroidHz} hard ${hard.centroidHz}")
    }

    // ---------- the sidecar ----------

    @Test
    fun `a portrait pad survives JSON bit for bit`() {
        val p = pad().copy(take = 3)
        val back = Patches.fromJsonText(p.toJsonText()) as SnapPatch
        assertEquals(p, back)
        assertContentEquals(p.render().samples, back.render().samples)
    }

    @Test
    fun `a legacy pad writes no portrait and no take`() {
        val legacy = SnapPatch("Old", SnapVoice.HORIZON, mapOf("GRIT" to 0.2f), pad().table)
        val obj = Json.parse(legacy.toJsonText()).obj()
        assertFalse("portrait" in obj)
        assertFalse("take" in obj)
        assertEquals(legacy, Patches.fromJsonText(legacy.toJsonText()))
    }

    @Test
    fun `a broken portrait is refused in words`() {
        val obj = LinkedHashMap(pad().toJsonValue().entries)
        val portrait = LinkedHashMap(obj.getValue("portrait").obj())
        portrait["register"] = com.snipsnap.json.JsonValue.Num(9.0)
        obj["portrait"] = com.snipsnap.json.JsonValue.Obj(portrait)
        val e = assertFailsWith<com.snipsnap.json.JsonException> { Patches.fromJsonValue(com.snipsnap.json.JsonValue.Obj(obj)) }
        assertTrue("register" in (e.message ?: ""), e.message)
    }

    @Test
    fun `a non-finite knob falls back to its default`() {
        val p = pad()
        val nan = SnapScan.render(p.table, p.macros + ("SCAN" to Float.NaN), p.portrait!!).samples
        val def = SnapScan.render(p.table, p.macros + ("SCAN" to SnapScan.SCAN_MACRO.default), p.portrait!!).samples
        assertContentEquals(def, nan)
    }

    @Test
    fun `a different photo reads to a different portrait`() {
        val a = pad()
        val b = SnapScan.read(scene(tint = 60), SnapVoice.HORIZON, "Warm", shot)
        assertNotEquals(a.portrait!!.warmth, b.portrait!!.warmth)
        assertTrue(b.portrait!!.warmth > a.portrait!!.warmth)
    }
}
