package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.ExportFormat
import com.snipsnap.kit.ExportReadBack
import com.snipsnap.kit.Exporters
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.Severity
import com.snipsnap.kit.blocked
import com.snipsnap.xpm.WavInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenSkyIntegrationTest {
    private fun custom() = OpenSkyPatch(
        "Stored Shimmer", OpenSkyVoice.SOAR,
        mapOf("RISE" to .42f, "WIDTH" to .73f, "GLOW" to .2f, "AIR" to .66f),
        midi = 64, velocity = .78f, gateSeconds = .16f, wet = true,
        timbre = OpenSkyTimbre(
            pulseMix = .53f, pulseWidth = .29f, sineMix = .34f, octaveMix = .19f,
            centerWeightScale = 1.6f, detuneScale = .72f, toneScale = 1.18f,
            attackScale = .46f, sustainLevel = .81f, decaySeconds = .13f,
            releaseScale = .48f, bellBrightnessScale = 1.4f, bellDecayScale = 1.7f,
        ),
    )

    @Test
    fun `pad recipe dispatch regenerates the wet engine and replays a stored stereo rack`() {
        val source = custom()
        val rendered = source.render()
        val bare = PadRecipe(source)
        val restoredBare = PadRecipe.fromJsonText(bare.toJsonText())
        assertEquals(bare, restoredBare)
        assertEquals(source, assertIs<OpenSkyPatch>(restoredBare.patch))
        assertFalse(restoredBare.alias)
        assertContentEquals(rendered.samples, restoredBare.render().samples)

        val rack = FxChain(reverse = true)
        val recipe = PadRecipe(source, rack)
        val restored = PadRecipe.fromJsonText(recipe.toJsonText())
        assertEquals(recipe, restored)
        val processed = restored.render()
        assertEquals(2, processed.channels)
        assertContentEquals(rack.process(rendered).samples, processed.samples)
        assertFalse(rendered.samples.contentEquals(processed.samples), "saved rack is ignored")
        assertTrue(processed.samples.all { it.isFinite() })
    }

    @Test
    fun `native velocity layers retain custom source note gate wet state and renderer model`() {
        val source = custom()
        val original = source.copy()
        val reference = source.render()
        val soft = Velocity.atVelocity(source, .24f)
        assertContentEquals(source.copy(velocity = .24f).render().samples, soft.samples,
            "velocity dispatch dropped custom timbre or replaced the saved note recipe")
        assertFalse(soft.samples.contentEquals(reference.samples))
        assertEquals(2, soft.channels)
        assertEquals(reference.sampleRate, soft.sampleRate)
        assertEquals(reference.frameCount, soft.frameCount)
        assertEquals(original, source, "building a velocity layer edited the saved patch")
        assertTrue(Velocity.canUseAtVelocity(reference, source, null))
        assertFalse(Velocity.canUseAtVelocity(reference, source, FxChain(reverse = true)))
        val mono = Snip(FloatArray(reference.frameCount) {
            (reference.samples[it * 2] + reference.samples[it * 2 + 1]) * .5f
        }, channels = 1, sampleRate = reference.sampleRate)
        assertFalse(Velocity.canUseAtVelocity(mono, source, null), "native rerender replaced a mono pad with stereo")
        assertContentEquals(Velocity.peakMatch(reference, soft).samples,
            Velocity.layerAt(reference, source, null, .24f, Velocity.brightnessSpec(source), useAtVelocity = true).samples)
    }

    @Test
    fun `complete recipes and stereo samples survive kit storage and both MPC export generations`() {
        val root = java.nio.file.Files.createTempDirectory("opensky-export").toFile()
        try {
            val patches = OpenSkyVoice.entries.map { voice ->
                OpenSkyPresets.forVoice(voice).first().copy(
                    midi = if (voice == OpenSkyVoice.HALO) 48 else 60,
                    gateSeconds = .18f, wet = voice == OpenSkyVoice.CRYSTAL,
                )
            }
            val arranged = patches.map { patch ->
                ArrangedPad(patch.render(), DrumClass.TONAL, PadRecipe(patch).toJsonValue(), displayName = patch.name)
            }
            val sourceDirectory = File(root, "source")
            val assembled = KitAssembler.assembleArranged("OpenSky Recipe Check", arranged, sourceDirectory)
            val saved = KitStore.load(sourceDirectory)
            assertEquals(assembled, saved)
            assertFalse(Preflight.check(saved, sourceDirectory).blocked())
            assertTrue(saved.pads.all { it.muteGroup == 0 }, "pitched voices were assigned drum choke groups")
            for ((index, pad) in saved.pads.withIndex()) {
                val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                assertEquals(patches[index], assertIs<OpenSkyPatch>(recipe.patch))
                assertContentEquals(arranged[index].snip.samples, recipe.render().samples,
                    "kit.json cannot regenerate pad ${pad.slot}")
            }

            for (format in listOf(ExportFormat.PROGRAM_FOLDER, ExportFormat.MPC3_TRACK)) {
                val outcome = Exporters.export(format, saved, sourceDirectory, File(root, "card-${format.id}"))
                assertTrue(outcome.primary.isFile, "$format did not write a program")
                val findings = ExportReadBack.verify(saved, outcome)
                assertTrue(findings.none { it.severity == Severity.FAIL },
                    "$format: ${findings.filter { it.severity == Severity.FAIL }.joinToString { it.message }}")
                assertEquals(5, findings.count { it.severity == Severity.OK }, "$format did not read back every pad")
                val sampleDirectory = outcome.companion ?: outcome.primary.parentFile
                for ((index, pad) in saved.pads.withIndex()) {
                    val wav = File(sampleDirectory, pad.sampleFile)
                    assertTrue(wav.isFile, "$format missing sample ${pad.sampleFile}")
                    val info = WavInfo.read(wav)
                    assertEquals(44_100, info.sampleRate)
                    assertEquals(2, info.channels, "$format folded ${patches[index].voice} to mono")
                    assertEquals(24, info.bitsPerSample)
                    assertEquals(arranged[index].snip.frameCount.toLong(), info.frameCount)
                    assertContentEquals(WavReader.read(File(sourceDirectory, pad.sampleFile)).samples,
                        WavReader.read(wav).samples, "$format altered the stereo sample")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
