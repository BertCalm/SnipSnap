package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.CairnSamplerDelivery
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException

/**
 * Explicit sampler-study admission, not CAIRN factory/DSP registration.
 * These five settings share one SING voice and preserve their captured range.
 */
object CairnSingInstrumentPicker {
    data class Choice(val program: CairnSingInstrumentMaker.Program, val label: String, val range: String) {
        val id: String get() = program.id
    }

    val choices: List<Choice> get() = listOf(
        Choice(CairnSingInstrumentMaker.Program.EARLIER, "EARLIER", "C3–C5"),
        Choice(CairnSingInstrumentMaker.Program.MOVING, "MOVING", "C3–C5"),
        Choice(CairnSingInstrumentMaker.Program.GROOVE_0, "GROOVE 0", "C4 ONLY"),
        Choice(CairnSingInstrumentMaker.Program.GROOVE_50, "GROOVE 50", "C4 ONLY"),
        Choice(CairnSingInstrumentMaker.Program.GROOVE_100, "GROOVE 100", "C4 ONLY"),
    )

    data class Published(val name: String, val sidecar: File)

    /**
     * Loading belongs to the caller's explicit asset provider, off the UI thread.
     * The maker validates every capture against its fixed native Float pins.
     * Admission checks cancellation before and during the descriptor-last move;
     * its existing rollback keeps cancelled studies out of the instrument list.
     */
    @Synchronized
    fun make(id: String, destination: File,
             load: (CairnSingInstrumentMaker.Program, () -> Boolean) -> Map<String, CairnSamplerDelivery.Capture>,
             cancelled: () -> Boolean = { false }): Published {
        fun stop() {
            if (cancelled() || Thread.currentThread().isInterrupted)
                throw CancellationException("CAIRN instrument creation cancelled")
        }
        val program = requireNotNull(CairnSingInstrumentMaker.Program.entries.singleOrNull { it.id == id }) {
            "Unknown CAIRN study"
        }
        stop()
        val captures = load(program, cancelled)
        stop()
        val base = Names.sanitizeStem(CairnSingInstrumentMaker.specification(program).name)
            .take(OneNote.MAX_NAME_LENGTH).trimEnd(' ', '_', '.')
        val name = Names.freshStem(base) { stem ->
            listOf("$stem.xty", Mpc3TrackWriter.trackDataDirName(stem), stem + InstrumentStore.SUFFIX)
                .any { Files.exists(File(destination, it).toPath(), LinkOption.NOFOLLOW_LINKS) }
        }
        stop()
        CairnSingInstrumentMaker.exportFresh(name, program, captures, destination,
            InstrumentPackageAdmission.Hooks(staged = { stop() }, moved = { stop() }))
        // No cancellation after publication: success already left a complete
        // instrument. The UI must refresh its shelf rather than report failure.
        return Published(name, InstrumentStore.sidecar(destination, name))
    }
}
