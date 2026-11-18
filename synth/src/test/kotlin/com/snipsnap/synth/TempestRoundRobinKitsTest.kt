package com.snipsnap.synth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.test.*

class TempestRoundRobinKitsTest {
    private fun sha(x: FloatArray): String {
        val b = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it) }
    }

    /** Float-sample hashes of every accepted TEMPEST render, taken on the base before takes existed: (kind, name) to (frames, sha). */
    private val pins: Map<Pair<String, String>, Pair<Int, String>> by lazy {
        javaClass.getResourceAsStream("/tempest/accepted-v1.sha256")!!.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .associate { line -> line.split(' ').let { (k, n, f, s) -> (k to n) to (f.toInt() to s) } }
    }

    private fun pinned(kind: String, name: String, x: FloatArray, why: String) {
        val (frames, s) = pins.getValue(kind to name)
        assertEquals(frames, x.size, why)
        assertEquals(s, sha(x), why)
    }

    private val patches = TempestPresets.fullKitPatches()
    private val built by lazy { TempestRoundRobinKits.build() }
    private fun takes(i: Int) = listOf(built[i].snip) + built[i].takes

    @Test fun `every accepted TEMPEST render still hashes as it did before takes existed`() {
        assertEquals(22 + 16 + 48 + 16 + 10, pins.size)
        for (p in TempestPresets.all()) {
            val name = "${p.voice.name}/${p.name.replace(' ', '_')}"
            pinned("preset", name, p.render().samples, "preset $name")
            pinned("preset", name, Tempest.render(p.voice, p.macros, p.model, take = 0).samples, "preset $name, take 0")
        }
        for ((i, pad) in SynthKits.tempest().withIndex()) pinned("kit", "tempest/A%02d".format(i + 1), pad!!.snip.samples, "TEMPEST A${i + 1}")
        for (k in TempestGenreKits.Kit.entries) for ((i, pad) in TempestGenreKits.build(k).withIndex())
            pinned("kit", "${k.id}/A%02d".format(i + 1), pad!!.snip.samples, "${k.id} A${i + 1}")
        for ((i, pad) in SynthKits.tempestTuned().withIndex()) pinned("kit", "tempest-tuned/A%02d".format(i + 1), pad!!.snip.samples, "TEMPEST TUNED A${i + 1}")
        for (v in TempestVoice.entries) for (model in 1..Tempest.MODEL_VERSION) if (Tempest.supports(v, model))
            pinned("render", "${v.name}/model$model", Tempest.render(v, emptyMap(), model, take = 0).samples, "$v model $model")
    }

    @Test fun `take zero of every pad is the accepted TEMPEST pad, byte for byte`() {
        val accepted = SynthKits.tempest()
        assertEquals(16, built.size)
        for ((i, pad) in built.withIndex()) {
            val patch = patches[i]
            assertEquals(patch.name, pad.displayName)
            assertEquals(accepted[i]!!.drumClass, pad.drumClass)
            assertEquals(accepted[i]!!.muteGroup, pad.muteGroup)
            assertEquals(if (patch.voice == TempestVoice.HAT) 1 else 0, pad.muteGroup, "${patch.name}: hats choke in group 1")
            assertTrue(pad.oneShot)
            assertNull(pad.recipe, "a single-hit recipe cannot regenerate the takes")
            assertEquals(patch, TempestPatch.fromJsonText(pad.source.getValue("recipe")))
            assertTrue(pad.softVariants.isEmpty() && pad.velocityTakes.isEmpty(), "TEMPEST has one touch")
            assertEquals(TempestRoundRobinKits.TAKES - 1, pad.takes.size)
            assertContentEquals(accepted[i]!!.snip.samples, pad.snip.samples, "${patch.name} take 0")
            pinned("kit", "tempest/A%02d".format(i + 1), pad.snip.samples, "${patch.name} take 0")
        }
    }

    @Test fun `takes replay exactly, share the pad's length and stay bounded`() {
        assertEquals(0, TempestRoundRobinKits.STRIKES.first())
        assertEquals(TempestRoundRobinKits.TAKES, TempestRoundRobinKits.STRIKES.toSet().size)
        for ((i, patch) in patches.withIndex()) for ((take, snip) in takes(i).withIndex()) {
            assertContentEquals(TempestRoundRobinKits.render(patch, take).samples, snip.samples, "${patch.name} take $take replays")
            assertEquals(Tempest.OUTPUT_FRAMES, snip.frameCount)
            assertTrue(snip.samples.all { it.isFinite() && abs(it) <= 1f }, "${patch.name} take $take bounded")
        }
    }

    @Test fun `takes are refused where they do not exist`() {
        assertFailsWith<IllegalArgumentException> { Tempest.render(TempestVoice.KICK, emptyMap(), 1, take = 1) }
        assertFailsWith<IllegalArgumentException> { Tempest.render(TempestVoice.HAT, emptyMap(), 3, take = -1) }
        assertFailsWith<IllegalArgumentException> { TempestRoundRobinKits.render(patches[0], TempestRoundRobinKits.TAKES) }
        // Model 1 keeps its first-build audio and never takes the take path.
        assertContentEquals(TempestLegacy.render(TempestVoice.KICK, emptyMap()).samples, Tempest.render(TempestVoice.KICK, emptyMap(), 1, take = 0).samples)
    }

    /**
     * Whether the takes vary where they should, by a phase-blind measure (frame-by-frame band
     * levels, [TempestRoundRobinMeasure.texture]): the noise voices and the free-running metal and
     * hat partials move, while a kick or a tom changes only its faint noise and stays almost the
     * same hit. No take is a copy of another (a phase-sensitive residual with gain removed).
     * Measured against take 0: kicks and toms 0.00 to 0.01 dB; metal 0.36 to 0.83, hats 0.91 to
     * 1.62, snares 1.90 to 2.26 and claps 2.11 to 2.29 dB.
     */
    @Test fun `noise voices vary by a phase-blind measure while kicks and toms stay almost identical`() {
        val m = TempestRoundRobinMeasure
        for ((i, patch) in patches.withIndex()) {
            val row = takes(i).map { it.samples }
            for (a in row.indices) for (b in a + 1 until row.size) {
                assertFalse(row[a].contentEquals(row[b]), "${patch.name} takes $a/$b are a copy")
                assertTrue(m.residual(row[a], row[b]) > 1e-12, "${patch.name} takes $a/$b differ only in gain")
                val tex = m.texture(row[a], row[b])
                when (patch.voice) {
                    TempestVoice.KICK, TempestVoice.TOM ->
                        assertTrue(tex < STEADY_TEXTURE, "${patch.name} takes $a/$b: a tuned body should barely move, $tex dB")
                    else -> assertTrue(tex > VARIED_TEXTURE, "${patch.name} takes $a/$b should vary, $tex dB")
                }
            }
        }
    }

    /**
     * The closeness screen the takes were chosen by, after EROSION's: every later take keeps the
     * accepted onset (within a quarter millisecond), stays within 2 dB of the accepted hit's spectral
     * shape over the bands that carry it ([TempestRoundRobinMeasure.audibleDistance]), and keeps its
     * 90%-energy time within 0.7 to 1.4 times the accepted one. Measured on the kit's takes: onsets
     * within 0.16 ms, shape 0.0 to 1.74 dB (snares and claps highest), energy time 0.955 to 1.016 times.
     */
    @Test fun `takes keep the accepted onset spectral shape and energy arrival`() {
        val m = TempestRoundRobinMeasure
        for ((i, patch) in patches.withIndex()) {
            val accepted = takes(i)[0].samples
            for ((take, snip) in takes(i).withIndex().drop(1)) {
                val x = snip.samples
                assertEquals(m.onsetMs(accepted), m.onsetMs(x), ONSET_MS, "${patch.name} take $take onset")
                val d = m.audibleDistance(accepted, x)
                assertTrue(d < 2.0, "${patch.name} take $take shape $d dB from its own hit")
                val ratio = m.t90Ms(x) / m.t90Ms(accepted)
                assertTrue(ratio in .7..1.4, "${patch.name} take $take energy time ratio $ratio")
            }
        }
    }

    private companion object {
        const val ONSET_MS = .25
        /** Kick and tom takes measured at most 0.01 dB apart. */
        const val STEADY_TEXTURE = .1
        /** Every other pair measured at least 0.3 dB apart. */
        const val VARIED_TEXTURE = .25
    }
}
