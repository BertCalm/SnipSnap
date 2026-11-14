package com.snipsnap.shell

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

/**
 * The checks that need no retained capture: an unknown choice, cancellation
 * before loading and a broken loader never open inputs or leave an instrument.
 * The backup's three shelf-admission checks read the nine shipped native Float
 * assets, which this repository does not carry, so they were left out with them.
 */
class CairnSingInstrumentPickerTest {
    @field:TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var temporary: Path

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

    @Test fun brokenInputLoadLeavesNoEmptyInstrumentOnTheShelf() {
        val destination = File(temporary.toFile(), "never-made")
        assertFailsWith<IOException> {
            CairnSingInstrumentPicker.make(Program.MOVING.id, destination, { _, _ -> throw IOException("missing input") })
        }
        assertFalse(destination.exists())
    }
}
