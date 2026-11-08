package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [Restrike]: a modal-drum pad's next take is a new hit at the drum's own root, not the old take resampled. */
class RestrikeTest {

    private fun patches(): List<Patch> =
        Skin.STRUCK.flatMap { v -> SkinPresets.forVoice(v).take(4) } +
            TerraKits.classic().map { pad -> requireNotNull(pad).recipe!!.let { PadRecipe.fromJsonValue(it).patch!! } }

    @Test
    fun `take zero is the velocity render itself`() {
        for (p in patches()) {
            for (v in listOf(1f, .55f)) {
                assertContentEquals(Velocity.atVelocity(p, v).samples, assertNotNull(Restrike.take(p, v, seed = 3, take = 0)).samples, "${p.name} at $v")
            }
        }
    }

    @Test
    fun `later takes are new hits, the same every time`() {
        for (p in patches()) {
            val takes = (0..3).map { assertNotNull(Restrike.take(p, 1f, seed = 7, take = it)).samples }
            for (i in takes.indices) for (j in i + 1 until takes.size) assertFalse(takes[i].contentEquals(takes[j]), "${p.name}: take $i and take $j differ")
            assertContentEquals(takes[2], assertNotNull(Restrike.take(p, 1f, seed = 7, take = 2)).samples, "${p.name}: deterministic")
            assertFalse(takes[2].contentEquals(assertNotNull(Restrike.take(p, 1f, seed = 8, take = 2)).samples), "${p.name}: the seed matters")
            for (t in takes) assertTrue(t.all { it.isFinite() && abs(it) <= 1f }, "${p.name} finite and within 1")
        }
    }

    /**
     * The root is the drum's on every take: a take moves the velocity and,
     * on TERRA, POS, never TUNE. Read after any bend has settled, against
     * take 0, wherever both are confident, within the same 25 cents
     * StrikeVelocityTest allows: around 100 Hz the detector's reading moves
     * by up to about 15 cents with the modes' balance alone (Dholak Bass,
     * 12.6 cents between two takes at one fundamental).
     */
    @Test
    fun `every take keeps the drum's root`() {
        var checked = 0
        for (p in patches()) {
            // BOX and the hand drums are held by their modes instead (StrikeVelocityTest's bank test: the
            // same ratios over the same fundamental at every velocity, and a take keeps the drum's
            // structure seed). On their dense heads the detector's reading moves with the modes'
            // balance, which a take's softer landing shifts: the fitted DJEMBE's open tone, its partials
            // fixed at 559 and 582 Hz, read 39 cents apart between takes.
            if (p is TerraPatch && (p.voice == TerraVoice.BOX || p.voice in Terra.HAND_DRUMS)) continue
            val ref = p.render()
            if (ref.durationSeconds < 0.32f) continue
            val root = Pitch.detect(ref, fromSec = 0.22f, windowSec = 0.10f)?.takeIf { it.confidence >= .5f } ?: continue
            for (t in 1..3) {
                val take = assertNotNull(Restrike.take(p, 1f, seed = 5, take = t))
                val hz = Pitch.detect(take, fromSec = 0.22f, windowSec = 0.10f)?.takeIf { it.confidence >= .5f } ?: continue
                val cents = 1200.0 * ln(hz.hz.toDouble() / root.hz) / ln(2.0)
                assertTrue(abs(cents) <= 25.0, "${p.name} take $t: ${"%.2f".format(cents)} cents")
                checked++
            }
        }
        assertTrue(checked >= 15, "enough confident takes to mean something: $checked")
    }

    @Test
    fun `a take lands softer within the spread and a TERRA take moves its spot`() {
        for (take in 1..50) {
            val (v, pos) = Restrike.landing(.8f, seed = 11, take = take)
            assertTrue(v <= .8f && v >= .8f * (1f - Restrike.FORCE_SPREAD), "take $take velocity $v")
            assertTrue(abs(pos) <= Restrike.POSITION_SPREAD, "take $take position offset $pos")
        }
        assertEquals(.8f to 0f, Restrike.landing(.8f, seed = 11, take = 0))
    }

    @Test
    fun `only the modal drums re-strike`() {
        assertTrue(Restrike.canRestrike(SkinPatch("k", SkinVoice.KICK, Skin.defaults(SkinVoice.KICK))))
        assertTrue(Restrike.canRestrike(TerraPatch("t", TerraVoice.TUNED_BAR, Terra.defaults(TerraVoice.TUNED_BAR))))
        for (voice in SkinVoice.entries - Skin.STRUCK) {
            val p = SkinPatch("s", voice, Skin.defaults(voice))
            assertFalse(Restrike.canRestrike(p), "$voice")
            assertNull(Restrike.take(p, 1f, seed = 1, take = 1), "$voice")
        }
        val thump = ThumpPatch("k", ThumpVoice.KICK, Thump.defaults(ThumpVoice.KICK))
        assertFalse(Restrike.canRestrike(thump))
        assertNull(Restrike.take(thump, 1f, seed = 1, take = 1))
        assertFalse(Restrike.canRestrike(null))
    }
}
