package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import com.snipsnap.shell.CairnSingInstrumentMaker.Program
import java.io.File
import java.io.IOException
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

/**
 * The picker admits real instruments from the nine captures rendered natively
 * and checked against their pins, the same path the phone takes; no capture
 * is shipped or synthesised any other way.
 */
class CairnSingInstrumentPickerTest {
    @field:TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var temporary: Path

    private fun load(program: Program, cancelled: () -> Boolean) = CairnSingTestCache.load(program, cancelled)

    @Test fun allFiveChoiceIdsReachRealReadableShelfProgramsWithTheirBoundedRanges() {
        assertEquals(Program.entries.map { it.id }, CairnSingInstrumentPicker.choices.map { it.id })
        assertEquals(1, CairnSingInstrumentMaker.NATIVE_VOICES)
        for (choice in CairnSingInstrumentPicker.choices) {
            val destination = File(temporary.toFile(), choice.id)
            val made = CairnSingInstrumentPicker.make(choice.id, destination, ::load)
            val listed = InstrumentStore.list(destination).single()
            assertEquals(made.sidecar, listed.first)
            assertEquals(made.name, listed.second.name)
            assertTrue(listed.second.toProgram().explicitNoteOn)
            val supported = listed.second.zones.flatMap { (it.lowNote..it.highNote).toList() }
            assertEquals(if (choice.program in listOf(Program.EARLIER, Program.MOVING))
                (48..72).toList() else listOf(60), supported)
            assertTrue(listed.second.zones.all { it.loopStartFrame == 88200L })
        }
    }

    @Test fun repeatedChoicesAndOrphanDescriptorsNeverOverwriteExistingShelfFiles() {
        val destination = temporary.toFile()
        val first = CairnSingInstrumentPicker.make(Program.GROOVE_0.id, destination, ::load)
        val original = first.sidecar.readBytes()
        val second = CairnSingInstrumentPicker.make(Program.GROOVE_0.id, destination, ::load)
        assertNotEquals(first.name, second.name)
        assertTrue(first.sidecar.readBytes().contentEquals(original))
        val orphanName = CairnSingInstrumentMaker.specification(Program.GROOVE_50).name
        val orphan = InstrumentStore.sidecar(destination, orphanName).apply { writeText("keep me") }
        val third = CairnSingInstrumentPicker.make(Program.GROOVE_50.id, destination, ::load)
        assertNotEquals(orphanName, third.name)
        assertEquals("keep me", orphan.readText())
    }

    @Test fun unknownChoiceAndCancellationBeforeLoadingNeverOpenOrPublishInputs() {
        var opens = 0
        val destination = File(temporary.toFile(), "absent")
        assertFailsWith<IllegalArgumentException> {
            CairnSingInstrumentPicker.make("invented", destination, { _, _ -> opens++; error("not reached") })
        }
        assertFailsWith<CancellationException> {
            CairnSingInstrumentPicker.make(Program.MOVING.id, destination,
                { _, _ -> opens++; error("not reached") }, cancelled = { true })
        }
        assertEquals(0, opens)
        assertFalse(destination.exists())
    }

    @Test fun cancelledAdmissionRollsBackMovedFilesAndLeavesPreviousInstrumentReadable() {
        val destination = temporary.toFile()
        val kept = CairnSingInstrumentPicker.make(Program.GROOVE_0.id, destination, ::load)
        val keptBytes = kept.sidecar.readBytes()
        val cancelName = CairnSingInstrumentMaker.specification(Program.GROOVE_100).name
        assertFailsWith<CancellationException> {
            CairnSingInstrumentPicker.make(Program.GROOVE_100.id, destination, ::load,
                cancelled = { File(destination, "$cancelName.xty").exists() })
        }
        assertEquals(listOf(kept.name), InstrumentStore.list(destination).map { it.second.name })
        assertTrue(kept.sidecar.readBytes().contentEquals(keptBytes))
        assertFalse(File(destination, "$cancelName.xty").exists())
        assertFalse(InstrumentStore.sidecar(destination, cancelName).exists())
        assertTrue(destination.listFiles()!!.none { it.name.startsWith(".instrument-admission-") })
    }

    @Test fun brokenInputLoadLeavesNoEmptyInstrumentOnTheShelf() {
        val destination = File(temporary.toFile(), "never-made")
        assertFailsWith<IOException> {
            CairnSingInstrumentPicker.make(Program.MOVING.id, destination, { _, _ -> throw IOException("missing input") })
        }
        assertFalse(destination.exists())
    }
}
