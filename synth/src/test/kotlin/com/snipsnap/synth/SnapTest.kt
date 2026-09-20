package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnapTest {

    // ---------- photos to look at ----------

    /** Vertical stripes, eight across: a strong, periodic HORIZON line and a flat PLUMB one. */
    private fun stripes(w: Int = 320, h: Int = 240): Photo = Photo.grey(w, h) { x, _ -> if ((x * 8 / w) % 2 == 0) 0.15f else 0.85f }

    /** One sine period across the width — the closest a photo gets to a sine wave. */
    private fun sineAcross(w: Int = 320, h: Int = 240): Photo = Photo.grey(w, h) { x, _ -> 0.5f + 0.45f * sin(2.0 * PI * x / w).toFloat() }

    /** Brightness ramp left to right. */
    private fun ramp(w: Int = 320, h: Int = 240): Photo = Photo.grey(w, h) { x, _ -> x.toFloat() / (w - 1) }

    /** A single colour with a little texture so the table is not flat. */
    private fun tinted(r: Int, g: Int, b: Int, w: Int = 120, h: Int = 90): Photo = Photo.of(w, h) { x, y ->
        val wobble = ((x + y) % 7) * 6 - 18
        Photo.rgb(r + wobble, g + wobble, b + wobble)
    }

    /** Pixel-level noise: the busiest photo there is. */
    private fun noise(seed: Int, w: Int = 120, h: Int = 90): Photo {
        val random = Random(seed)
        return Photo.grey(w, h) { _, _ -> random.nextFloat() }
    }

    private fun anyPhoto(): Photo = stripes()

    // ---------- the sound ----------

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        // Diagonal, so no voice's line is flat — a sine straight across
        // would leave PLUMB one colour, which is read()'s refusal, not a
        // render's job to rescue.
        val photo = Photo.grey(320, 240) { x, y -> 0.5f + 0.45f * sin(2.0 * PI * (x / 320.0 + y / 240.0)).toFloat() }
        for (voice in SnapVoice.entries) {
            val table = Snap.table(photo, voice)
            for (macros in listOf(
                emptyMap(),
                Snap.macrosFor(voice).associate { it.name to 0f },
                Snap.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Snap.render(table, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range at $macros")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet at $macros")
                assertTrue(snip.durationSeconds < 1.5f, "$voice must stay a one-shot: ${snip.durationSeconds}s")
            }
        }
    }

    @Test
    fun `TUNE lands on the snapped note`() {
        // A sine across the photo is one sine per cycle, so the pitch
        // detector has nothing to be confused by. Two octaves of A2 root.
        val table = Snap.table(sineAcross(), SnapVoice.HORIZON)
        for (tune in listOf(0f, 0.25f, 0.5f, 1f)) {
            val expected = Snap.frequencyFor(tune)
            val snip = Snap.render(table, mapOf("TUNE" to tune, "BRIGHT" to 0.8f, "DECAY" to 0.8f, "GRIT" to 0f))
            val measured = TestPitch.estimate(snip)
            assertTrue(
                measured > expected * 0.94f && measured < expected * 1.06f,
                "TUNE $tune: expected ~${expected}Hz, measured ${measured}Hz",
            )
        }
        assertEquals(110f, Snap.frequencyFor(0f))
        assertEquals(440f, Snap.frequencyFor(1f))
    }

    @Test
    fun `render dispatches through the oversampled path, not directly at RATE`() {
        // U6 (docs/SYNTH_UPGRADE.md): the same mean-abs-diff proof every
        // other engine carries — render() must not be synthesize(RATE).
        val table = Snap.table(stripes(), SnapVoice.HORIZON)
        val actual = Snap.render(table)
        val direct = Snap.synthesize(table, emptyMap(), Dsp.RATE)
        Dsp.normalize(direct)
        Dsp.fadeTail(direct)
        var diff = 0.0
        val n = minOf(actual.samples.size, direct.size)
        for (i in 0 until n) diff += abs((actual.samples[i] - direct[i]).toDouble())
        assertTrue(diff / n > 0.0005, "render should differ from a native-rate synthesize(); avgDiff=${diff / n}")
    }

    @Test
    fun `BRIGHT opens the filter and DECAY lengthens the tail`() {
        val table = Snap.table(stripes(), SnapVoice.HORIZON)
        val dark = FeatureExtractor.extract(Snap.render(table, mapOf("BRIGHT" to 0.05f)))
        val bright = FeatureExtractor.extract(Snap.render(table, mapOf("BRIGHT" to 1f)))
        assertTrue(bright.centroidHz > dark.centroidHz * 1.5f, "BRIGHT: ${dark.centroidHz} -> ${bright.centroidHz}")

        val short = FeatureExtractor.extract(Snap.render(table, mapOf("DECAY" to 0.05f)))
        val long = FeatureExtractor.extract(Snap.render(table, mapOf("DECAY" to 1f)))
        assertTrue(long.decayMs > short.decayMs * 2f, "DECAY: ${short.decayMs}ms -> ${long.decayMs}ms")
        // At the top of DECAY the tail outlasts the classifier's 500 ms
        // TONAL line while the whole render stays under its 1.5 s LOOP
        // line. The classifier only hears TONAL where the low band
        // dominates (its bass family), so the note it is asked about is
        // the root, 110 Hz, on a smooth line — the stripes above are a
        // square wave at 220 Hz, which it files as a bright PERC, and it
        // is right to.
        assertTrue(long.decayMs > 500f, "DECAY at 1 must ring past the TONAL line: ${long.decayMs}ms")
        val rootNote = Snap.render(Snap.table(sineAcross(), SnapVoice.HORIZON), mapOf("TUNE" to 0f, "DECAY" to 1f))
        assertEquals(DrumClass.TONAL, Classifier.classify(rootNote).drumClass)
    }

    @Test
    fun `the cycle is zero-mean, seamless and full scale`() {
        val cycle = Snap.cycle(Snap.table(ramp(), SnapVoice.HORIZON))
        val mean = cycle.sum() / cycle.size
        assertTrue(abs(mean) < 0.02f, "DC left in the cycle: $mean")
        assertTrue(cycle.maxOf { abs(it) } > 0.99f, "not normalized")
        // A ramp's two ends are its extremes; after the seam blend the last
        // point sits beside the first, not a full swing away from it.
        assertTrue(abs(cycle.last() - cycle.first()) < 0.2f, "the seam still jumps: ${cycle.last()} vs ${cycle.first()}")
    }

    // ---------- the line through the photo ----------

    @Test
    fun `the table is the photo's own numbers`() {
        // A left-to-right ramp reads as a rising line HORIZON-wise...
        val across = Snap.table(ramp(), SnapVoice.HORIZON)
        assertEquals(Snap.TABLE_SIZE, across.size)
        for (i in 1 until across.size) assertTrue(across[i] >= across[i - 1], "not monotone at $i")
        assertTrue(across.first() < 8 && across.last() > 247, "ramp ends: ${across.first()}..${across.last()}")
        // ...and as one flat colour PLUMB-wise, which read() refuses in words.
        assertTrue(Snap.isFlat(Snap.table(ramp(), SnapVoice.PLUMB)))
        val refused = assertFailsWith<IllegalArgumentException> { Snap.read(ramp(), SnapVoice.PLUMB, "Ramp") }
        assertTrue(refused.message!!.contains("flat"), refused.message)
        // The HORIZON read of the same photo goes through.
        Snap.read(ramp(), SnapVoice.HORIZON, "Ramp")
    }

    @Test
    fun `ORBIT closes on itself`() {
        // A photo that is bright on the left and dark on the right: an
        // ORBIT line crosses that edge twice per turn, at twelve and six
        // o'clock, and its first and last samples — both at three o'clock,
        // neighbours on the ring — agree.
        val photo = Photo.grey(200, 200) { x, _ -> if (x < 100) 0.9f else 0.1f }
        val ring = Snap.table(photo, SnapVoice.ORBIT)
        assertTrue(abs(ring.first() - ring.last()) <= 2, "ring ends: ${ring.first()} vs ${ring.last()}")
        assertTrue(ring.max() > 200 && ring.min() < 50, "the ring never crossed the edge")
    }

    @Test
    fun `every voice on a real-shaped photo yields a playable table`() {
        val photo = Photo.of(300, 200) { x, y -> Photo.rgb((x * 255) / 299, (y * 255) / 199, 128) }
        for (voice in SnapVoice.entries) {
            val patch = Snap.read(photo, voice, "Gradient")
            assertTrue(patch.render().peak() > 0.5f, "$voice")
        }
    }

    // ---------- what the photo looks like ----------

    @Test
    fun `hue sets TUNE - red low, blue high, grey on the centre detent`() {
        val red = Snap.macrosFrom(Snap.look(tinted(200, 40, 40)))
        val blue = Snap.macrosFrom(Snap.look(tinted(40, 40, 200)))
        val grey = Snap.macrosFrom(Snap.look(tinted(120, 120, 120)))
        assertTrue(red.getValue("TUNE") < 0.1f, "red: ${red["TUNE"]}")
        assertTrue(blue.getValue("TUNE") in 0.6f..0.72f, "blue: ${blue["TUNE"]}")
        assertEquals(0.5f, grey.getValue("TUNE"), "grey has no hue to speak of")
        assertTrue(Snap.look(tinted(120, 120, 120)).saturation < Snap.GREY_SATURATION)
    }

    @Test
    fun `brightness opens BRIGHT, colour lengthens DECAY, texture raises GRIT`() {
        val dim = Snap.macrosFrom(Snap.look(tinted(40, 40, 40)))
        val lit = Snap.macrosFrom(Snap.look(tinted(220, 220, 220)))
        assertTrue(lit.getValue("BRIGHT") > dim.getValue("BRIGHT") + 0.4f, "${dim["BRIGHT"]} -> ${lit["BRIGHT"]}")

        val pale = Snap.macrosFrom(Snap.look(tinted(150, 140, 140)))
        val vivid = Snap.macrosFrom(Snap.look(tinted(230, 30, 30)))
        assertTrue(vivid.getValue("DECAY") > pale.getValue("DECAY") + 0.3f, "${pale["DECAY"]} -> ${vivid["DECAY"]}")

        val smooth = Snap.macrosFrom(Snap.look(ramp()))
        val busy = Snap.macrosFrom(Snap.look(noise(3)))
        assertTrue(busy.getValue("GRIT") > smooth.getValue("GRIT") + 0.4f, "${smooth["GRIT"]} -> ${busy["GRIT"]}")

        // And every mapped knob is a legal macro, whatever the photo.
        for (photo in listOf(ramp(), noise(9), tinted(0, 0, 0), tinted(255, 255, 255), stripes())) {
            for ((k, v) in Snap.macrosFrom(Snap.look(photo))) assertTrue(v in 0f..1f, "$k=$v")
        }
    }

    @Test
    fun `the reading's hue wraps at red`() {
        // Orange-red on both sides of 0°: the circular mean stays near
        // 0/360, not at 180 where a plain average of degrees would land.
        val photo = Photo.of(60, 60) { x, _ -> if (x % 2 == 0) Photo.rgb(255, 20, 40) else Photo.rgb(255, 40, 20) }
        val hue = Snap.look(photo).hue
        assertTrue(hue < 15f || hue > 345f, "hue $hue")
    }

    // ---------- the recipe ----------

    @Test
    fun `a SNAP patch round-trips through JSON with its table and renders the same`() {
        val patch = Snap.read(stripes(), SnapVoice.ORBIT, "Stripes")
        val back = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, back)
        assertTrue(back.render().samples.contentEquals(patch.render().samples), "same JSON must mean same sound")
        val recipe = PadRecipe(patch = patch, fx = FxChain(spring = mapOf("MIX" to 0.3f)))
        val recipeBack = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, recipeBack)
        assertTrue(recipeBack.render().samples.contentEquals(recipe.render().samples))
    }

    @Test
    fun `a table of the wrong shape is refused`() {
        assertFailsWith<IllegalArgumentException> { SnapPatch("X", SnapVoice.HORIZON, emptyMap(), IntArray(100)) }
        assertFailsWith<IllegalArgumentException> { SnapPatch("X", SnapVoice.HORIZON, emptyMap(), IntArray(Snap.TABLE_SIZE) { 300 }) }
        assertFailsWith<JsonException> {
            Patches.fromJsonText("""{"engine":"SNAP","version":1,"name":"?","voice":"ORBIT","macros":{}}""")
        }
        assertFailsWith<JsonException> {
            Patches.fromJsonText("""{"engine":"SNAP","version":1,"name":"?","voice":"SPIRAL","macros":{},"table":[]}""")
        }
    }

    @Test
    fun `scrambles are reproducible and stay in range`() {
        val table = Snap.table(anyPhoto(), SnapVoice.HORIZON)
        for (voice in SnapVoice.entries) {
            assertEquals(Snap.scramble(voice, Random(2)), Snap.scramble(voice, Random(2)))
            repeat(6) { seed ->
                val macros = Snap.scramble(voice, Random(seed))
                for ((k, v) in macros) assertTrue(v in 0f..1f, "$k=$v")
                val snip: Snip = Snap.render(table, macros)
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f })
            }
        }
    }
}
