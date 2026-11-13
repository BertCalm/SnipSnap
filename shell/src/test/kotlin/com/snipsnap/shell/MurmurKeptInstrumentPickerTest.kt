package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.MurmurKeptSources
import com.snipsnap.synth.MurmurVoice
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Picker/admission-boundary tests only; no native synthesis or saved-bank requalification. */
class MurmurKeptInstrumentPickerTest {
    @field:TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var temporary: Path

    @Test fun everyStableChoiceOwnsTheCompleteKeptSourceAndItsExistingBounds() {
        assertEquals(listOf(MurmurKeptSources.FINITE_WIRE, MurmurKeptSources.HELD_WIRE,
            MurmurKeptSources.STEADY_C2), MurmurKeptInstrumentPicker.choices.map { it.id })
        for (choice in MurmurKeptInstrumentPicker.choices) {
            val source = MurmurKeptSources.byId(choice.id)
            val spec = MurmurKeptInstrumentPicker.specification(choice.id)
            assertEquals(source.factoryPatch().toJsonText(), spec.sourcePatchJson)
            assertEquals(source.supportedMidi, spec.supportedMidi)
            assertEquals(MurmurVoice.PLUCK, spec.sourcePatch().voice)
            assertEquals(choice.id == MurmurKeptSources.HELD_WIRE, spec.held)
            val callerEdit = spec.sourcePatch().copy(macros = spec.sourcePatch().macros + ("MOUTH" to .99f))
            assertNotEquals(callerEdit.toJsonText(), spec.sourcePatchJson)
            assertEquals(source.factoryPatch().toJsonText(),
                MurmurKeptInstrumentPicker.specification(choice.id).sourcePatchJson)
        }
    }

    @Test fun unknownAndAlreadyCancelledChoicesNeverCreateOrExportAnything() {
        val absent = File(temporary.toFile(), "not-created")
        var exports = 0
        val export: (String, MurmurSelectedInstrumentMaker.Spec, File, () -> Boolean, (Int, Int) -> Unit) -> Unit =
            { _, _, _, _, _ -> exports++ }
        assertFailsWith<IllegalArgumentException> {
            MurmurKeptInstrumentPicker.makeUsing("invented", absent, { false }, { _, _ -> }, export)
        }
        assertFailsWith<CancellationException> {
            MurmurKeptInstrumentPicker.makeUsing(MurmurKeptSources.HELD_WIRE, absent,
                { true }, { _, _ -> }, export)
        }
        assertEquals(0, exports)
        assertFalse(absent.exists())
    }

    @Test fun orphanSidecarsAndDataNamesAreKeptAndLateCancelDoesNotUnpublishSuccess() {
        val root = temporary.toFile()
        val id = MurmurKeptSources.STEADY_C2
        val base = MurmurKeptInstrumentPicker.specification(id).sourcePatch().name
        val orphan = InstrumentStore.sidecar(root, base).apply { writeText("keep this sidecar") }
        val occupiedData = File(root, Mpc3TrackWriter.trackDataDirName("$base 2")).apply { mkdir() }
        var cancelled = false
        var sourceJson: String? = null
        val progress = mutableListOf<Pair<Int, Int>>()
        val made = MurmurKeptInstrumentPicker.makeUsing(id, root, { cancelled },
            { done, total -> progress += done to total }) { name, spec, destination, stop, report ->
            // Synthetic boundary only: the real public path delegates to the qualified native maker.
            assertFalse(stop())
            sourceJson = spec.sourcePatchJson
            InstrumentStore.sidecar(destination, name).writeText("published test boundary")
            report(1, 1)
            cancelled = true // The exporter has committed before returning.
        }
        assertEquals("keep this sidecar", orphan.readText())
        assertTrue(occupiedData.isDirectory)
        assertNotEquals(base, made.name)
        assertNotEquals("$base 2", made.name)
        assertEquals("published test boundary", made.sidecar.readText())
        assertEquals(MurmurKeptSources.byId(id).factoryPatch().toJsonText(), sourceJson)
        assertEquals(listOf(1 to 1), progress)
    }
}
