package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Osteria
import com.snipsnap.synth.OsteriaPatch
import com.snipsnap.xpm.KeygroupProgram
import java.io.File
import java.util.concurrent.CancellationException

/** Finite stereo OSTERIA keys: original source envelope, 13 roots, three native touch layers. */
object OsteriaPadMaker {
    fun supports(patch: OsteriaPatch): Boolean = patch.config.model == 2 && patch.strum == null

    data class Spec(val patch: OsteriaPatch) {
        init { require(supports(patch)) { "OSTERIA instruments require the fixed-root model" } }
    }

    val zoneMidis: List<Int> get() = OsteriaKeygroupBank.roots
    val velocities: List<Float> get() = OsteriaKeygroupBank.velocities
    const val INSTRUMENT_NOTE = "Finite notes · no loop. Keeps the saved attack, length and release. C2–C6 · three touch layers · 39 samples."

    /** Serial by design; callers must also keep zones serial to bound the render working set. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis)
        return velocities.map { velocity ->
            stop(cancelled)
            val patch = spec.patch.copy(midi = midi, velocity = velocity)
            val snip = render(patch, cancelled)
            stop(cancelled)
            KeyNote(snip, 0L)
        }
    }

    /** The currently saved root/touch, without substituting a factory envelope or artificial loop. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip = render(spec.patch, cancelled)

    private fun render(patch: OsteriaPatch, cancelled: () -> Boolean): Snip {
        stop(cancelled)
        return Osteria.render(patch.voice, patch.macros, patch.midi, patch.velocity, patch.config, cancelled)
            .also { stop(cancelled) }
    }

    fun cells(spec: Spec, notes: List<List<KeyNote>>): Map<OsteriaKeygroupBank.Cell, OsteriaKeygroupBank.Note> {
        require(notes.size == zoneMidis.size) { "All 13 roots are required" }
        val recipes = OsteriaKeygroupBank.recipes(spec.patch)
        return buildMap {
            zoneMidis.forEachIndexed { index, midi ->
                require(notes[index].size == velocities.size) { "Three velocity layers are required at $midi" }
                notes[index].forEachIndexed { touch, note ->
                    require(note.loopStartFrame == 0L) { "OSTERIA source notes are finite" }
                    val cell = OsteriaKeygroupBank.Cell(midi, touch)
                    put(cell, OsteriaKeygroupBank.Note(recipes.getValue(cell), note.snip))
                }
            }
        }
    }

    fun export(name: String, spec: Spec, notes: List<List<KeyNote>>, destination: File,
               overwrite: Boolean = false): KeygroupProgram =
        OsteriaKeygroupBank.export(name, spec.patch, cells(spec, notes), destination, overwrite).program

    data class Saved(val source: OsteriaPatch, val bank: OsteriaKeygroupBank.Assembly)

    /** Reads the actual shelf package and refuses stale sources, audio, roots, layers or descriptors. */
    fun read(sidecar: File): Saved {
        require(sidecar.name.endsWith(InstrumentStore.SUFFIX))
        val name = sidecar.name.removeSuffix(InstrumentStore.SUFFIX)
        val root = sidecar.absoluteFile.parentFile
        val metadata = File(root, "${name}_[TrackData]/osteria-source-recipes.json")
        val source = OsteriaPatch.fromJsonValue(Json.parse(metadata.readText()).obj().getValue("sourcePatch")) as OsteriaPatch
        val bank = OsteriaKeygroupTransaction.validate(name, root, source)
        return Saved(source, bank)
    }

    /** Source-preserving copy; existing sampler POLY/LEAD/GLIDE choice survives without changing WAVs. */
    fun reexport(sidecar: File, name: String, destination: File, overwrite: Boolean = false): KeygroupProgram {
        val saved = read(sidecar)
        val notes = OsteriaKeygroupBank.recipes(saved.source).mapValues { (cell, recipe) ->
            val stem = "Osteria_${cell.midi}_T${cell.touch + 1}"
            require(saved.bank.recipes.getValue(stem).toJsonText() == recipe.toJsonText())
            OsteriaKeygroupBank.Note(recipe, saved.bank.samples.getValue(stem))
        }
        return OsteriaKeygroupBank.export(name, saved.source, notes, destination, overwrite,
            saved.bank.program.voiceMode).program
    }

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("OSTERIA instrument cancelled")
    }
}
