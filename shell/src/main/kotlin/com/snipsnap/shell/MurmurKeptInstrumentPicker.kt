package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.MurmurKeptSources
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException

/** A shelf door for three existing PLUCK settings, not three new voices or a new renderer. */
object MurmurKeptInstrumentPicker {
    data class Choice(val id: String, val label: String, val range: String)

    val choices: List<Choice> get() = listOf(
        Choice(MurmurKeptSources.FINITE_WIRE, "HOLLOW WIRE NOTE", "C2–C7"),
        Choice(MurmurKeptSources.HELD_WIRE, "HOLLOW WIRE HOLD", "C2–C7"),
        Choice(MurmurKeptSources.STEADY_C2, "STEADY ROOT C2", "C2–F#3"),
    )

    /** Resolve a fresh, complete kept recipe; the existing maker owns its native range/touch policy. */
    fun specification(id: String): MurmurSelectedInstrumentMaker.Spec {
        require(choices.any { it.id == id }) { "Unknown kept MURMUR instrument" }
        return MurmurSelectedInstrumentMaker.Spec(MurmurKeptSources.byId(id).factoryPatch())
    }

    data class Published(val name: String, val sidecar: File)

    @Synchronized
    fun make(id: String, destination: File, cancelled: () -> Boolean = { false },
             progress: (Int, Int) -> Unit = { _, _ -> }): Published =
        makeUsing(id, destination, cancelled, progress) { name, spec, root, stop, report ->
            MurmurSelectedInstrumentMaker.exportFresh(name, spec, root, stop, report)
            Unit
        }

    internal fun makeUsing(id: String, destination: File, cancelled: () -> Boolean,
                          progress: (Int, Int) -> Unit,
                          export: (String, MurmurSelectedInstrumentMaker.Spec, File,
                                   () -> Boolean, (Int, Int) -> Unit) -> Unit): Published {
        fun stop() {
            if (cancelled() || Thread.currentThread().isInterrupted)
                throw CancellationException("MURMUR instrument creation cancelled")
        }
        val spec = specification(id)
        stop()
        val base = Names.sanitizeStem(spec.sourcePatch().name)
            .take(OneNote.MAX_NAME_LENGTH).trimEnd(' ', '_', '.')
        val name = Names.freshStem(base) { stem ->
            listOf("$stem.xty", Mpc3TrackWriter.trackDataDirName(stem), stem + InstrumentStore.SUFFIX)
                .any { Files.exists(File(destination, it).toPath(), LinkOption.NOFOLLOW_LINKS) }
        }
        stop()
        // This is the same serial native-cell writer and descriptor-last fresh admission
        // used by SYNTH. It preserves all source metadata, bounds, touch windows and release.
        export(name, spec, destination, cancelled, progress)
        // Publication has committed. A late cancel must not turn a complete instrument
        // into a reported failure; the caller refreshes the shelf after this return.
        return Published(name, InstrumentStore.sidecar(destination, name))
    }
}
