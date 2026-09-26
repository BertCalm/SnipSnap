package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.Balance
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.Names
import com.snipsnap.mpc3.SafeXml
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.io.StringReader
import org.w3c.dom.Element
import org.xml.sax.InputSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The end-to-end test this repo never had: a real patch, through a real
 * engine, leveled, effected, written as a real WAV, assembled into a real
 * kit, and exported through the real MPC-2 writer — then read back off
 * disk, not trusted from memory, and checked against every invariant
 * docs/MPC_EXPORT.md states.
 *
 * Why this exists now: docs/SYNTH_UPGRADE.md's U4 (Stereo) is next, and it
 * is the first time any engine will return `channels = 2`.
 *
 * **Correction to an earlier reading of this test.** `XpmWriter`'s
 * per-instrument and program-level `<Mono>` (`XpmWriter.kt:114` and `:159`)
 * is not a channel count — it is the monophonic-versus-polyphonic
 * voice-allocation flag, sitting among `Pitch`, `TuneCoarse`, `TuneFine`,
 * `Polyphony` (docs/XPM_STRUCTURE.md's program-level params cluster). The
 * corpus confirms it: `Bass-TAB Deep Resonance.xpm` also carries
 * `<MonoRetrigger>` beside `<Mono>` at program level — "retrigger" is a
 * note concept, not a channel one — and its instrument declares
 * `Mono=False`/`Polyphony=0` (polyphonic bass) where the drum kit declares
 * `Mono=True`/`Polyphony=1` per pad (one voice per drum hit), independent
 * of what's in either WAV. Nothing in this codebase reads `<Mono>` back
 * on import, so it cannot desync from a sample's channel count in a way
 * that matters. [KitPad], [com.snipsnap.xpm.Pad] and
 * [com.snipsnap.xpm.DrumProgram] carry no channel field because the
 * program file doesn't need one — the MPC reads channel count from the
 * WAV's own `fmt` chunk, same as every vendor pack in `reference/golden/`.
 * The real, still-live invariant is docs/MPC_EXPORT.md's format
 * constraint — every exported WAV is mono or stereo, nothing wider — which
 * is what `every exported WAV has 1 or 2 channels` below checks.
 *
 * Six pads, not sixteen: enough engines to be a real spread (THUMP three
 * times — KICK, the just-rebuilt modal SNARE, and a WIDTH>0 SNARE — plus
 * PLUCK, VELVET and TONEWHEEL, one through a named FX treatment) without
 * paying to render and export a full kit on every `:synth:test` run.
 *
 * The WIDTH>0 SNARE pad exists because of a gap the class KDoc above
 * already names: docs/SYNTH_UPGRADE.md's U4 (Stereo) is Task 3b, and until
 * it landed no patch had ever produced a stereo `Snip` - so this suite's
 * own "every exported WAV has 1 or 2 channels" and "frame counts agree"
 * checks had a two-channel branch that had literally never executed. This
 * pad is what exercises it: `SliceEnd` is written from a WAV read back off
 * disk (`WavInfo.read(wav).frameCount`, itself `dataBytes / (channels *
 * bytesPerSample)`), so any code on the render -> level -> FX -> write path
 * that still computes frames as `samples.size` instead of `samples.size /
 * channels` for a real stereo Snip would show up here as a wrong SliceEnd,
 * not as a compile error.
 */
class ExportRegressionTest {

    private val temp: File = java.nio.file.Files.createTempDirectory("export-regression").toFile()
    private val work: File get() = File(temp, "work")
    private val destRoot: File get() = File(temp, "sd")

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    /** One arranged pad per engine, every patch a shipped preset — never an invented macro value. */
    private fun buildArrangedKit(): List<ArrangedPad?> {
        val kick = ThumpPresets.forVoice(ThumpVoice.KICK).first()
        val snare = ThumpPresets.forVoice(ThumpVoice.SNARE).first() // the rebuilt modal membrane bank
        // Same shipped preset, WIDTH pushed to 1 - the macro's own
        // documented endpoint, not an invented value: no preset sets WIDTH
        // (it defaults to 0, see MacroSpec("WIDTH", 0f) in Thump.kt), so
        // there is no shipped stereo preset to reach for, and this is the
        // one WIDTH value the macro's own contract already commits to.
        val wideSnare = snare.withMacros(snare.macros + ("WIDTH" to 1f))
        val pluck = PluckPresets.forVoice(PluckVoice.BANJO).first() // the shortest loop of the string voices: loop arithmetic changed under this
        val velvet = VelvetPresets.forVoice(VelvetVoice.BASS).first()
        val tonewheel = TonewheelPresets.forVoice(TonewheelVoice.FULL).first()

        val arranged = listOf(
            ArrangedPad(kick.render(), DrumClass.KICK, PadRecipe(kick).toJsonValue()),
            ArrangedPad(snare.render(), DrumClass.SNARE, PadRecipe(snare).toJsonValue()),
            ArrangedPad(wideSnare.render(), DrumClass.SNARE, PadRecipe(wideSnare).toJsonValue()),
            ArrangedPad(pluck.render(), DrumClass.TONAL, PadRecipe(pluck).toJsonValue()),
            // Routed through a shipped FX treatment, not a bare render - this
            // is the one pad that exercises the render -> FX half of the
            // render -> level -> FX -> write path; the other four exercise
            // render -> level -> write, FX bypassed (a null chain), which is
            // just as real a path since most pads in a kit have no treatment.
            run {
                val fx = Treatments.chain("vinyl")
                ArrangedPad(PadRecipe(velvet, fx).render(), DrumClass.TONAL, PadRecipe(velvet, fx).toJsonValue())
            },
            ArrangedPad(tonewheel.render(), DrumClass.TONAL, PadRecipe(tonewheel).toJsonValue()),
        )

        // Balance sets each pad's mixer level from measured loudness - the
        // "level" stage of render -> level -> FX -> write. Non-destructive:
        // it edits ArrangedPad.level metadata, never the audio itself, so
        // running it here is exactly what a real kit build does before
        // handing pads to KitAssembler.
        return Balance.apply(arranged)
    }

    /** Renders, balances, assembles and exports a real kit; every test below reads its output. */
    private fun exportedKit(): com.snipsnap.kit.ExportResult {
        val kit = KitAssembler.assembleArranged("Export Regression Kit", buildArrangedKit(), work)
        return KitExporter.exportProgramFolder(kit, work, destRoot, overwrite = true)
    }

    // ---------- invariant 1: format ----------

    @Test
    fun `every exported WAV is 44 point 1kHz 24-bit PCM`() {
        val result = exportedKit()
        assertTrue(result.samples.isNotEmpty(), "nothing was exported")
        for (wav in result.samples) {
            val info = WavInfo.read(wav)
            assertEquals(Dsp.RATE, info.sampleRate, "${wav.name} is not MPC-native rate")
            assertEquals(24, info.bitsPerSample, "${wav.name} is not the 24-bit depth this repo writes")
        }
    }

    // ---------- invariant 2: channel count ----------

    /**
     * The real format constraint from docs/MPC_EXPORT.md: every exported
     * WAV is mono or stereo, nothing wider. `Preflight` already fails a
     * >2-channel sample before export (`Preflight.kt:85`); this confirms
     * the invariant still holds on what actually lands on disk, past the
     * full render -> level -> FX -> write path, not just at the preflight
     * check that runs before it.
     *
     * This is not a cross-check against the program's `<Mono>` declaration
     * — that element is monophonic-versus-polyphonic voice allocation, not
     * a channel count, and nothing reads it back on import. See the class
     * KDoc above for how an earlier version of this test got that wrong.
     * Every occupied pad's samples are checked here, velocity-layer WAVs
     * included, not just one WAV per pad.
     */
    @Test
    fun `every exported WAV has 1 or 2 channels`() {
        val result = exportedKit()
        assertTrue(result.samples.isNotEmpty(), "nothing was exported")
        for (wav in result.samples) {
            val info = WavInfo.read(wav)
            assertTrue(info.channels == 1 || info.channels == 2, "${wav.name} has ${info.channels} channels")
        }
    }

    // ---------- invariant 3: frame counts agree ----------

    @Test
    fun `frame counts in the XPM agree with the WAVs on disk`() {
        val result = exportedKit()
        val instruments = parseInstruments(result.program)
        val byStem = result.samples.associateBy { it.nameWithoutExtension }

        var checked = 0
        for (inst in instruments) {
            val layer = inst.layers.firstOrNull { it.sampleName.isNotBlank() } ?: continue
            val wav = byStem.getValue(layer.sampleName)
            val info = WavInfo.read(wav)
            assertEquals(
                info.frameCount,
                layer.sliceEnd,
                "instrument ${inst.number} (${layer.sampleName}): SliceEnd disagrees with the WAV's own frame count " +
                    "- this plays the wrong length on hardware while sounding correct in the app",
            )
            checked++
        }
        assertEquals(6, checked, "expected to check all 6 occupied pads")
    }

    // ---------- invariant 4: filenames ----------

    @Test
    fun `filenames are ASCII and free of characters the MPC rejects`() {
        val result = exportedKit()
        for (wav in result.samples) {
            assertTrue(wav.name.all { it.code in 0..127 }, "${wav.name} is not pure ASCII")
            assertTrue(Names.isMpcSafe(wav.nameWithoutExtension), "${wav.name} fails the MPC-safe filename rule")
        }
        assertTrue(Names.isMpcSafe(result.program.nameWithoutExtension), "${result.program.name} fails the MPC-safe filename rule")
    }

    // ---------- invariant 5: level ----------

    @Test
    fun `every sample is inside -1 to 1 after the full render, level, FX and write path`() {
        val result = exportedKit()
        for (wav in result.samples) {
            // Decoded independently of WavWriter, via :audio's own reader -
            // this checks what got written, not what was handed to it.
            val decoded = WavReader.read(wav)
            for (s in decoded.samples) {
                assertTrue(s in -1f..1f, "${wav.name} has a sample outside [-1, 1]: $s")
                assertTrue(s.isFinite(), "${wav.name} has a non-finite sample")
            }
        }
    }

    // ---------- invariant 6: the program parses back ----------

    @Test
    fun `the program file parses back`() {
        val result = exportedKit()
        val instruments = parseInstruments(result.program)
        val occupied = instruments.count { inst -> inst.layers.any { it.sampleName.isNotBlank() } }
        assertEquals(6, occupied, "expected all 6 pads to round-trip out of the parsed program")

        val doc = SafeXml.newFactory().newDocumentBuilder()
            .parse(InputSource(StringReader(result.program.readText())))
        val programName = doc.getElementsByTagName("ProgramName").item(0)?.textContent
        assertEquals("Export Regression Kit", programName)
    }

    // ---------- shared: parse the .xpm back into the fields this test needs ----------

    private data class ParsedLayer(val sampleName: String, val sliceEnd: Long)
    private data class ParsedInstrument(val number: Int, val layers: List<ParsedLayer>)

    /**
     * Parses `.xpm` XML with the same hardened, DOCTYPE-refusing factory
     * [com.snipsnap.kit.XpnImporter] uses ([SafeXml]) - this file is our own
     * output in every test above, but a parser for a format that also
     * reads real vendor archives shouldn't have two implementations, one
     * careful and one not.
     */
    private fun parseInstruments(xpm: File): List<ParsedInstrument> {
        val cleaned = xpm.readText().removePrefix("﻿").trimStart()
        val doc = SafeXml.newFactory().newDocumentBuilder().parse(InputSource(StringReader(cleaned)))
        doc.documentElement.normalize()

        val instrumentEls = elements(doc.documentElement, "Instrument")
        return instrumentEls.map { inst ->
            val number = inst.getAttribute("number").trim().toInt()
            val layers = elements(inst, "Layer").map { layer ->
                ParsedLayer(
                    sampleName = firstText(layer, "SampleName")?.trim().orEmpty(),
                    sliceEnd = firstText(layer, "SliceEnd")?.trim()?.toLongOrNull() ?: 0L,
                )
            }
            ParsedInstrument(number, layers)
        }
    }

    private fun elements(parent: Element, tag: String): List<Element> {
        val nl = parent.getElementsByTagName(tag)
        return (0 until nl.length).mapNotNull { nl.item(it) as? Element }
    }

    private fun firstText(parent: Element, tag: String): String? =
        (parent.getElementsByTagName(tag).item(0) as? Element)?.textContent
}
