package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sound-design round: twelve macros that each opened up a constant a
 * THUMP voice used to hardcode (BEND, HOLD, RATTLE, NOISE, CLAPS, ROOM,
 * TOM's CLICK and DRIVE, RATIO, TONE, RING, BODY).
 *
 * Three promises, one section each: a new macro's default is the old
 * constant to the bit (so no preset that predates it moved), each one
 * audibly does what its comment in `Thump.kt` says across its travel, and
 * the classified voices still read as themselves at both ends of it.
 */
class ThumpSoundDesignTest {

    private val added = mapOf(
        ThumpVoice.KICK to listOf("BEND", "HOLD"),
        ThumpVoice.SNARE to listOf("RATTLE"),
        ThumpVoice.HAT_CLOSED to listOf("NOISE"),
        ThumpVoice.HAT_OPEN to listOf("NOISE"),
        ThumpVoice.CLAP to listOf("CLAPS", "ROOM"),
        ThumpVoice.TOM to listOf("BEND", "CLICK", "DRIVE"),
        ThumpVoice.COWBELL to listOf("RATIO", "TONE", "RING"),
        ThumpVoice.RIM to listOf("RING", "BODY"),
    )

    private fun render(voice: ThumpVoice, vararg macros: Pair<String, Float>) = Thump.render(voice, macros.toMap())
    private fun features(voice: ThumpVoice, vararg macros: Pair<String, Float>) = FeatureExtractor.extract(render(voice, *macros))

    // ---------- the default is the old constant, exactly ----------

    @Test
    fun `around lands on its centre to the bit at 0 point 5`() {
        // The constants the new macros replaced. A plain expMap would only
        // get near these; around() has to hit them, or presets drift.
        for ((lo, center, hi) in listOf(
            Triple(18f, 90f, 400f), Triple(1f, 3f, 7f), Triple(8f, 30f, 120f),
            Triple(1.1f / 1.48f, 1f, 2.6f / 1.48f), Triple(0.85f, 1.2f, 3f),
            Triple(0.08f, 0.6f, 1.4f), Triple(0.006f, 0.12f, 0.35f),
        )) {
            assertEquals(center, Dsp.around(0.5f, lo, center, hi), "around(0.5, $lo, $center, $hi)")
            assertEquals(lo, Dsp.around(0f, lo, center, hi), 1e-5f * lo)
            assertEquals(hi, Dsp.around(1f, lo, center, hi), 1e-5f * hi)
        }
    }

    @Test
    fun `every new macro sits at a default a missing key renders identically to`() {
        for ((voice, names) in added) {
            val bare = render(voice).samples
            val explicit = Thump.render(voice, names.associateWith { n -> Thump.defaults(voice).getValue(n) }).samples
            assertTrue(bare.contentEquals(explicit), "$voice: spelling out $names at their defaults changed the render")
        }
    }

    // ---------- each macro does what it says ----------

    @Test
    fun `every new macro changes the render at both ends of its travel`() {
        for ((voice, names) in added) for (name in names) {
            val mid = render(voice, name to Thump.defaults(voice).getValue(name)).samples
            for (end in floatArrayOf(0f, 1f)) {
                if (end == Thump.defaults(voice).getValue(name)) continue
                val moved = render(voice, name to end).samples
                assertTrue(!moved.contentEquals(mid), "$voice $name at $end renders the same as its default")
            }
        }
    }

    @Test
    fun `kick BEND slow keeps the pitch up longer, fast drops it at once`() {
        val slow = features(ThumpVoice.KICK, "BEND" to 0f).centroidHz
        val fast = features(ThumpVoice.KICK, "BEND" to 1f).centroidHz
        assertTrue(slow > fast * 1.4f, "BEND 0 centroid $slow vs BEND 1 $fast")
    }

    @Test
    fun `kick HOLD lengthens the body`() {
        val dry = features(ThumpVoice.KICK, "HOLD" to 0f).decayMs
        val held = features(ThumpVoice.KICK, "HOLD" to 1f).decayMs
        assertTrue(held > dry * 2f, "HOLD 1 decay $held vs HOLD 0 $dry")
    }

    @Test
    fun `snare RATTLE lengthens the wires`() {
        val choked = features(ThumpVoice.SNARE, "RATTLE" to 0f).decayMs
        val loose = features(ThumpVoice.SNARE, "RATTLE" to 1f).decayMs
        assertTrue(loose > choked * 3f, "RATTLE 1 decay $loose vs RATTLE 0 $choked")
    }

    @Test
    fun `hat NOISE brightens`() {
        for (voice in listOf(ThumpVoice.HAT_CLOSED, ThumpVoice.HAT_OPEN)) {
            val pure = features(voice, "NOISE" to 0f).centroidHz
            val washed = features(voice, "NOISE" to 1f).centroidHz
            assertTrue(washed > pure * 1.1f, "$voice NOISE 1 centroid $washed vs NOISE 0 $pure")
        }
    }

    @Test
    fun `clap CLAPS adds impacts and ROOM adds tail`() {
        val few = render(ThumpVoice.CLAP, "CLAPS" to 0f).frameCount
        val many = render(ThumpVoice.CLAP, "CLAPS" to 1f).frameCount
        assertTrue(many > few, "CLAPS 1 is $many frames vs CLAPS 0 $few - more impacts, later tail start")
        val dry = features(ThumpVoice.CLAP, "ROOM" to 0f).decayMs
        val hall = features(ThumpVoice.CLAP, "ROOM" to 1f).decayMs
        assertTrue(hall > dry * 2f, "ROOM 1 decay $hall vs ROOM 0 $dry")
    }

    @Test
    fun `tom CLICK adds a stick and DRIVE adds grit`() {
        // Centroid can't see a 4 ms click past a whole tom's sine body
        // (power-weighted, measured: identical to the Hz), so this reads
        // the high band, where the stick actually lives.
        val bare = features(ThumpVoice.TOM, "CLICK" to 0f).highRatio
        val stick = features(ThumpVoice.TOM, "CLICK" to 1f).highRatio
        assertTrue(stick > bare * 5f, "CLICK 1 high ratio $stick vs CLICK 0 $bare")
        val clean = features(ThumpVoice.TOM, "DRIVE" to 0f).centroidHz
        val driven = features(ThumpVoice.TOM, "DRIVE" to 1f).centroidHz
        assertTrue(driven > clean, "DRIVE 1 centroid $driven vs DRIVE 0 $clean")
    }

    @Test
    fun `cowbell TONE brightens at every step`() {
        // Monotone on purpose: TONE is the name Velocity's brightness scan
        // matches, so a softer strike scales it down and has to sound darker.
        var last = 0f
        for (step in 0..4) {
            val c = features(ThumpVoice.COWBELL, "TONE" to step / 4f).centroidHz
            assertTrue(c > last, "TONE ${step / 4f} centroid $c did not rise past $last")
            last = c
        }
    }

    @Test
    fun `rim BODY adds a low head under the tick and RING lengthens it`() {
        val side = features(ThumpVoice.RIM, "BODY" to 0f).centroidHz
        val shot = features(ThumpVoice.RIM, "BODY" to 1f).centroidHz
        assertTrue(shot < side * 0.5f, "BODY 1 centroid $shot vs BODY 0 $side")
        val dry = features(ThumpVoice.RIM, "RING" to 0f).decayMs
        val clave = features(ThumpVoice.RIM, "RING" to 1f).decayMs
        assertTrue(clave > dry, "RING 1 decay $clave vs RING 0 $dry")
    }

    // ---------- still the instrument ----------

    @Test
    fun `classified voices still read as themselves at both ends of every new macro`() {
        val expected = mapOf(
            ThumpVoice.KICK to DrumClass.KICK, ThumpVoice.SNARE to DrumClass.SNARE,
            ThumpVoice.HAT_CLOSED to DrumClass.HAT_CLOSED, ThumpVoice.HAT_OPEN to DrumClass.HAT_OPEN,
            ThumpVoice.CLAP to DrumClass.CLAP, ThumpVoice.TOM to DrumClass.TOM,
        )
        val failures = mutableListOf<String>()
        for ((voice, cls) in expected) for (name in added.getValue(voice)) for (end in floatArrayOf(0f, 1f)) {
            val got = Classifier.classify(render(voice, name to end)).drumClass
            if (got != cls) failures += "$voice $name=$end: $got"
        }
        assertTrue(failures.isEmpty(), "new macros that leave their voice:\n${failures.joinToString("\n")}")
    }

    @Test
    fun `HOLD and DECAY share a budget, so the longest kicks still read as kicks`() {
        // Without the shared budget, DECAY 0.9-1 with HOLD past ~0.5 held a
        // low sine long enough to classify as TONAL (measured, see kick()).
        for (decay in floatArrayOf(0.8f, 0.9f, 0.95f, 1f)) {
            val longest = render(ThumpVoice.KICK, "DECAY" to decay, "HOLD" to 1f)
            assertTrue(longest.durationSeconds < 1.5f, "DECAY $decay HOLD 1 kick is ${longest.durationSeconds}s")
            assertEquals(DrumClass.KICK, Classifier.classify(longest).drumClass, "DECAY $decay HOLD 1")
        }
        // ...and the budget never swallows HOLD whole.
        val unheld = render(ThumpVoice.KICK, "DECAY" to 1f, "HOLD" to 0f).frameCount
        val held = render(ThumpVoice.KICK, "DECAY" to 1f, "HOLD" to 1f).frameCount
        assertTrue(held > unheld, "HOLD does nothing at DECAY 1")
    }
}
