package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SKIN's sound-design round - [ThumpSoundDesignTest]'s three promises for
 * the modal engine: every new macro's default is the constant it replaced,
 * each does what its comment in `Skin.kt` says, and the classified voices
 * are still themselves at both ends of every one of them.
 */
class SkinSoundDesignTest {

    private val added = mapOf(
        SkinVoice.KICK to listOf("CLICK", "DROP"),
        SkinVoice.SNARE to listOf("RATTLE", "SIZZLE"),
        SkinVoice.HAT_CLOSED to listOf("RING"),
        SkinVoice.HAT_OPEN to listOf("RING"),
        SkinVoice.TOM to listOf("DROP", "CLICK"),
        SkinVoice.RIDE to listOf("SIZZLE"),
        SkinVoice.SHAKER to listOf("SWELL", "GRAIN"),
        SkinVoice.STICK to listOf("BODY", "CLICK"),
    )

    private fun render(voice: SkinVoice, vararg macros: Pair<String, Float>) = Skin.render(voice, macros.toMap())
    private fun features(voice: SkinVoice, vararg macros: Pair<String, Float>) = FeatureExtractor.extract(render(voice, *macros))

    // ---------- the default is the old constant, exactly ----------

    @Test
    fun `around lands on its centre to the bit at an off-centre pivot`() {
        // Hat RING's default sits at 0.8, not 0.5 - see HAT_RING_DEFAULT.
        assertEquals(0.15f, Dsp.around(0.8f, 0.55f, 0.15f, 0.1f, pivot = 0.8f))
        assertEquals(0.55f, Dsp.around(0f, 0.55f, 0.15f, 0.1f, pivot = 0.8f), 1e-6f)
        assertEquals(0.1f, Dsp.around(1f, 0.55f, 0.15f, 0.1f, pivot = 0.8f), 1e-6f)
    }

    @Test
    fun `every new macro sits at a default a missing key renders identically to`() {
        for ((voice, names) in added) {
            val bare = render(voice).samples
            val explicit = Skin.render(voice, names.associateWith { n -> Skin.defaults(voice).getValue(n) }).samples
            assertTrue(bare.contentEquals(explicit), "$voice: spelling out $names at their defaults changed the render")
        }
    }

    // ---------- each macro does what it says ----------

    @Test
    fun `every new macro changes the render at both ends of its travel`() {
        for ((voice, names) in added) for (name in names) {
            val default = Skin.defaults(voice).getValue(name)
            val mid = render(voice, name to default).samples
            for (end in floatArrayOf(0f, 1f)) {
                if (end == default) continue
                assertTrue(!render(voice, name to end).samples.contentEquals(mid), "$voice $name at $end renders the same as its default")
            }
        }
    }

    @Test
    fun `kick DROP starts the head sharp and CLICK hardens the beater`() {
        val flat = features(SkinVoice.KICK, "DROP" to 0f).centroidHz
        val dropped = features(SkinVoice.KICK, "DROP" to 1f).centroidHz
        assertTrue(dropped > flat * 1.2f, "DROP 1 centroid $dropped vs DROP 0 $flat")
        val felt = features(SkinVoice.KICK, "CLICK" to 0f).highRatio
        val plastic = features(SkinVoice.KICK, "CLICK" to 1f).highRatio
        assertTrue(plastic > felt * 3f, "CLICK 1 high ratio $plastic vs CLICK 0 $felt")
    }

    @Test
    fun `snare RATTLE lengthens the wires and SIZZLE thins them`() {
        val choked = features(SkinVoice.SNARE, "RATTLE" to 0f).decayMs
        val loose = features(SkinVoice.SNARE, "RATTLE" to 1f).decayMs
        assertTrue(loose > choked * 2f, "RATTLE 1 decay $loose vs RATTLE 0 $choked")
        // Level-matched (see wireBandPower), so SIZZLE moves the wires'
        // spectral shape rather than their loudness: a higher highpass
        // leaves a less flat spectrum.
        val dark = features(SkinVoice.SNARE, "SIZZLE" to 0f).flatness
        val thin = features(SkinVoice.SNARE, "SIZZLE" to 1f).flatness
        assertTrue(thin < dark * 0.95f, "SIZZLE 1 flatness $thin vs SIZZLE 0 $dark")
    }

    @Test
    fun `hat RING runs from a trashy band to ringing metal`() {
        for (voice in listOf(SkinVoice.HAT_CLOSED, SkinVoice.HAT_OPEN)) {
            val trash = features(voice, "RING" to 0f).flatness
            val metal = features(voice, "RING" to 1f).flatness
            // Measured 0.92 on HAT_CLOSED: the trashy end stops at k 0.55,
            // where closed hats still classify as hats (see hat()).
            assertTrue(metal < trash * 0.95f, "$voice RING 1 flatness $metal vs RING 0 $trash")
        }
    }

    @Test
    fun `tom DROP bends and CLICK adds a stick`() {
        val flat = features(SkinVoice.TOM, "DROP" to 0f).centroidHz
        val bent = features(SkinVoice.TOM, "DROP" to 1f).centroidHz
        assertTrue(bent > flat * 1.2f, "DROP 1 centroid $bent vs DROP 0 $flat")
        val bare = features(SkinVoice.TOM, "CLICK" to 0f).highRatio
        val stick = features(SkinVoice.TOM, "CLICK" to 1f).highRatio
        assertTrue(stick > bare * 5f, "CLICK 1 high ratio $stick vs CLICK 0 $bare")
    }

    @Test
    fun `ride SIZZLE brightens and outlasts the wash`() {
        val plain = features(SkinVoice.RIDE, "SIZZLE" to 0f)
        val rivets = features(SkinVoice.RIDE, "SIZZLE" to 1f)
        assertTrue(rivets.centroidHz > plain.centroidHz, "SIZZLE 1 centroid ${rivets.centroidHz} vs ${plain.centroidHz}")
        assertTrue(rivets.decayMs > plain.decayMs, "SIZZLE 1 decay ${rivets.decayMs} vs ${plain.decayMs}")
    }

    @Test
    fun `shaker SWELL moves the peak later`() {
        fun peakMs(swell: Float): Float {
            val s = render(SkinVoice.SHAKER, "SWELL" to swell)
            val i = s.samples.indices.maxBy { kotlin.math.abs(s.samples[it]) }
            return i * 1000f / s.sampleRate
        }
        val tap = peakMs(0f)
        val thrown = peakMs(1f)
        assertTrue(thrown > tap + 20f, "SWELL 1 peaks at $thrown ms vs SWELL 0 at $tap ms")
    }

    @Test
    fun `shaker GRAIN breaks the hiss into beads`() {
        // A temporal property, so the spectral features barely see it
        // (flatness moved 5%): measured instead as how much the level jumps
        // between neighbouring 1 ms windows over the first 150 ms.
        fun roughness(grain: Float): Double {
            val s = render(SkinVoice.SHAKER, "GRAIN" to grain)
            val win = s.sampleRate / 1000
            val levels = (0 until 150).map { w ->
                val from = w * win
                val sumSq = (from until from + win).sumOf { i -> s.samples[i].toDouble().let { it * it } }
                ln(sqrt(sumSq / win) + 1e-9)
            }
            return levels.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average()
        }
        val smooth = roughness(0f)
        val beads = roughness(1f)
        assertTrue(beads > smooth * 2.0, "GRAIN 1 roughness $beads vs GRAIN 0 $smooth")
    }

    @Test
    fun `stick BODY adds a shell and CLICK a wood crack`() {
        val click = features(SkinVoice.STICK, "BODY" to 0f).centroidHz
        val knock = features(SkinVoice.STICK, "BODY" to 1f).centroidHz
        assertTrue(knock < click * 0.5f, "BODY 1 centroid $knock vs BODY 0 $click")
        val pure = features(SkinVoice.STICK, "CLICK" to 0f).flatness
        val crack = features(SkinVoice.STICK, "CLICK" to 1f).flatness
        assertTrue(crack > pure * 3f, "CLICK 1 flatness $crack vs CLICK 0 $pure")
    }

    // ---------- still the instrument ----------

    @Test
    fun `classified voices still read as themselves at both ends of every new macro`() {
        // RIDE has no DrumClass of its own; HAT_OPEN is what SkinPresetsTest
        // already holds it to.
        val expected = mapOf(
            SkinVoice.KICK to DrumClass.KICK, SkinVoice.SNARE to DrumClass.SNARE,
            SkinVoice.HAT_CLOSED to DrumClass.HAT_CLOSED, SkinVoice.HAT_OPEN to DrumClass.HAT_OPEN,
            SkinVoice.TOM to DrumClass.TOM, SkinVoice.RIDE to DrumClass.HAT_OPEN,
        )
        val failures = mutableListOf<String>()
        for ((voice, cls) in expected) for (name in added.getValue(voice)) for (end in floatArrayOf(0f, 1f)) {
            val got = Classifier.classify(render(voice, name to end)).drumClass
            if (got != cls) failures += "$voice $name=$end: $got"
        }
        assertTrue(failures.isEmpty(), "new macros that leave their voice:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `the longest snare RATTLE and DECAY allow is still a one-shot`() {
        val longest = render(SkinVoice.SNARE, "DECAY" to 1f, "RATTLE" to 1f)
        assertTrue(longest.durationSeconds < 1.5f, "longest snare is ${longest.durationSeconds}s")
        assertEquals(DrumClass.SNARE, Classifier.classify(longest).drumClass)
    }
}
