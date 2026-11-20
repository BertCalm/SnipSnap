package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.synth.AccordPatch
import com.snipsnap.synth.KeyNote
import com.snipsnap.xpm.KeygroupProgram
import java.io.File
import java.util.concurrent.CancellationException

/** Android instrument-sheet adapter for ACCORD's finite keys ([WickPadMaker]'s shape, six samples a root). */
object AccordPadMaker {
    /** Every saved ACCORD recipe is finite (HOLD is refused by the patch itself), so every one makes keys. */
    fun supports(patch: AccordPatch): Boolean = (patch.macros["HOLD"] ?: 0f) == 0f

    data class Spec(val patch: AccordPatch) {
        init { require(supports(patch)) { "ACCORD instruments are finite; HOLD stays zero" } }
        val keys = AccordKeygroupMaker.Spec(patch)
    }

    fun zoneMidis(spec: Spec): List<Int> = AccordKeygroupMaker.roots(spec.keys)
    fun instrumentNote(spec: Spec): String =
        "Finite notes, no loop. ${Scales.nameOf(spec.keys.lowMidi)}–${Scales.nameOf(spec.keys.highMidi)} · three touch layers · " +
            "two takes per touch · ${zoneMidis(spec).size * AccordKeygroupMaker.LAYERS} samples. Let go to fade."

    /** Each root renders its three touches of two takes serially, soft first; the caller also keeps roots serial. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec))
        val recipes = AccordKeygroupMaker.recipes(spec.keys)
        return recipes.keys.filter { it.midi == midi }.map { cell ->
            stop(cancelled)
            val rendered = AccordKeygroupMaker.render(recipes.getValue(cell), cell)
            stop(cancelled)
            KeyNote(rendered, loopStartFrame = 0)
        }
    }

    /** The key the saved note lands on, at medium touch: the sample that key plays, not the whole saved LENGTH. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        stop(cancelled)
        val cell = AccordKeygroupMaker.Cell(spec.patch.midi, 1, 0)
        return AccordKeygroupMaker.render(AccordKeygroupMaker.recipes(spec.keys).getValue(cell), cell).also { stop(cancelled) }
    }

    fun cells(spec: Spec, notes: List<List<KeyNote>>): Map<AccordKeygroupMaker.Cell, AccordKeygroupMaker.Note> {
        val midis = zoneMidis(spec)
        require(notes.size == midis.size)
        val recipes = AccordKeygroupMaker.recipes(spec.keys)
        return buildMap {
            for ((zone, midi) in midis.withIndex()) {
                val cells = recipes.keys.filter { it.midi == midi }
                require(notes[zone].size == cells.size)
                notes[zone].forEachIndexed { layer, note ->
                    require(note.loopStartFrame == 0L) { "ACCORD finite keys cannot acquire sustain loops" }
                    put(cells[layer], AccordKeygroupMaker.Note(recipes.getValue(cells[layer]), note.snip))
                }
            }
        }
    }

    /** Place actual .xty/TrackData/sidecar siblings directly where InstrumentStore lists them. */
    fun export(name: String, spec: Spec, notes: List<List<KeyNote>>, destination: File,
               hooks: InstrumentPackageAdmission.Hooks = InstrumentPackageAdmission.Hooks()): KeygroupProgram {
        val sourceNotes = cells(spec, notes)
        return InstrumentPackageAdmission.publishNew(name, destination,
            build = { packet -> AccordKeygroupMaker.writeNewPackage(name, spec.keys, sourceNotes, packet) },
            validate = { packet -> AccordKeygroupMaker.validate(name, packet, spec.keys); Unit },
            hooks = hooks).program
    }

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("ACCORD instrument cancelled")
    }
}
