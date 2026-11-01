package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OhmRefinementAuditionTest {
    @Test
    fun `three middle-amount comparisons share one baseline and preserve exact depth reference parameters`() {
        val clips = OhmRefinementAuditionGenerator.clips()
        assertEquals(14, clips.size)
        assertEquals(6, clips.count { !it.diagnostic })
        assertEquals(listOf("bow", "grains", "freeze"), OhmRefinementAuditionGenerator.comparisons.map { it.id })
        val byId = clips.associateBy { it.id }
        val base = byId.getValue("plucked").patch
        assertEquals(4, base.model)
        assertEquals(60, base.midi)
        assertEquals(.8f, base.velocity)
        assertEquals(731, base.seed)
        assertEquals(.9f, base.macros.getValue("MEDDLING"))
        assertEquals(1f, base.macros.getValue("COMMUNE"))
        assertEquals(0f, base.macros.getValue("HOLD"))
        for (comparison in OhmRefinementAuditionGenerator.comparisons) {
            val changed = byId.getValue(comparison.changed).patch
            assertEquals(base.copy(name = changed.name, options = changed.options), changed)
            assertEquals(changed, OhmPatch.fromJsonText(changed.toJsonText()))
        }
        assertEquals(OhmOptions(exciter = OhmExciter.BOW), byId.getValue("bowed").patch.options)
        assertEquals(OhmOptions(grains = .6f, grainSeconds = .08f), byId.getValue("grains-60").patch.options)
        assertEquals(OhmOptions(freeze = .75f), byId.getValue("freeze-75").patch.options)
        for ((id, commune) in listOf("reference-companions" to 0f, "reference-shared" to 1f)) {
            val reference = byId.getValue(id).patch
            assertEquals(2, reference.model)
            assertEquals(base.macros - "HOLD" + ("COMMUNE" to commune), reference.macros)
            assertEquals(OhmOptions(), reference.options)
            assertEquals(64, OhmRefinementAuditionGenerator.anchorHashes.getValue(id).length)
        }
        for (clip in clips.filter { it.id.endsWith("-c0") }) {
            val shared = byId.getValue(clip.id.removeSuffix("-c0")).patch
            assertTrue(clip.diagnostic)
            assertEquals(shared.copy(macros = shared.macros + ("COMMUNE" to 0f)), clip.patch)
        }
    }

    @Test
    fun `single-note JSON replay retains source options and common raw scalar`() {
        val patch = OhmRefinementAuditionGenerator.clips().single { it.id == "grains-60" }.patch.let {
            it.copy(macros = it.macros + ("LENGTH" to 0f))
        }
        val original = OhmRefinementAuditionGenerator.render(patch)
        val score = JsonValue.Obj(mapOf(
            "type" to JsonValue.Str("OHM_REFINEMENT_AUDITION"), "source" to JsonValue.Str("Ohm.renderRaw"),
            "sampleRate" to JsonValue.Num(44_100.0), "channels" to JsonValue.Num(1.0),
            "frames" to JsonValue.Num(original.frameCount.toDouble()), "rawExportGain" to JsonValue.Num(.5),
            "padRecipe" to PadRecipe(patch = patch).toJsonValue(),
        ))
        val restored = Json.parse(Json.write(score))
        assertContentEquals(FloatArray(original.samples.size) { original.samples[it] * .5f },
            OhmRefinementAuditionGenerator.replay(restored, "raw").samples)
        assertContentEquals(AuditionLevel.level(original).samples,
            OhmRefinementAuditionGenerator.replay(restored, "matched").samples)
        assertFailsWith<IllegalArgumentException> { OhmRefinementAuditionGenerator.replay(restored, "unknown") }
    }

    @Test
    fun `difference probe detects post-onset changes after a common loud attack`() {
        val baseline = FloatArray(22_050) { .01f }.also { it[0] = .9f }
        val changed = baseline.copyOf().also { for (i in 13_230 until it.size) it[i] += .005f }
        val metrics = OhmRefinementAuditionGenerator.difference(Snip(baseline, 1, 44_100), Snip(changed, 1, 44_100))
        assertEquals(.5, metrics.getValue("after300msDifferenceRmsRelativeToBaseline") as Double, .00001)
        assertTrue((metrics.getValue("differenceRmsRelativeToBaseline") as Double) < .5)
        assertTrue((metrics.getValue("maxPcm16Difference") as Int) > 100)
        assertEquals(0.0, OhmRefinementAuditionGenerator.difference(Snip(baseline, 1, 44_100), Snip(baseline, 1, 44_100))
            .getValue("differenceRmsRelativeToBaseline"))
    }
}
