package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.synth.DrawlPatch
import com.snipsnap.synth.KeyNote
import com.snipsnap.xpm.KeygroupProgram
import java.io.File
import java.util.concurrent.CancellationException

/** Android instrument-sheet adapter for DRAWL's finite keys ([AccordPadMaker]'s shape, six samples a root). */
object DrawlPadMaker {
    /** Every single-note DRAWL recipe makes keys; a strum is a chord on one pad, and the chord tool plays chords on keys. */
    fun supports(patch: DrawlPatch): Boolean = patch.strum == null

    data class Spec(val patch: DrawlPatch) {
        init { require(supports(patch)) { "A strummed DRAWL recipe is a chord on one pad, not keys" } }
        val keys = DrawlKeygroupMaker.Spec(patch)
    }

    fun zoneMidis(spec: Spec): List<Int> = DrawlKeygroupMaker.roots(spec.keys)
    fun instrumentNote(spec: Spec): String =
        "Finite notes, no loop. ${Scales.nameOf(spec.keys.lowMidi)}–${Scales.nameOf(spec.keys.highMidi)} · three touch layers · " +
            "two takes per touch · ${zoneMidis(spec).size * DrawlKeygroupMaker.LAYERS} samples. Let go to fade."

    /** Each root renders its three touches of two takes serially, soft first; the caller also keeps roots serial. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec))
        val recipes = DrawlKeygroupMaker.recipes(spec.keys)
        return recipes.keys.filter { it.midi == midi }.map { cell ->
            stop(cancelled)
            val rendered = DrawlKeygroupMaker.render(recipes.getValue(cell))
            stop(cancelled)
            KeyNote(rendered, loopStartFrame = 0)
        }
    }

    /** The key the saved note lands on, at medium touch: the sample that key plays. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        stop(cancelled)
        val cell = DrawlKeygroupMaker.Cell(spec.keys.authoredMidi, 1, 0)
        return DrawlKeygroupMaker.render(DrawlKeygroupMaker.recipes(spec.keys).getValue(cell)).also { stop(cancelled) }
    }

    fun cells(spec: Spec, notes: List<List<KeyNote>>): Map<DrawlKeygroupMaker.Cell, DrawlKeygroupMaker.Note> {
        val midis = zoneMidis(spec)
        require(notes.size == midis.size)
        val recipes = DrawlKeygroupMaker.recipes(spec.keys)
        return buildMap {
            for ((zone, midi) in midis.withIndex()) {
                val cells = recipes.keys.filter { it.midi == midi }
                require(notes[zone].size == cells.size)
                notes[zone].forEachIndexed { layer, note ->
                    require(note.loopStartFrame == 0L) { "DRAWL finite keys cannot acquire sustain loops" }
                    put(cells[layer], DrawlKeygroupMaker.Note(recipes.getValue(cells[layer]), note.snip))
                }
            }
        }
    }

    /** Place actual .xty/TrackData/sidecar siblings directly where InstrumentStore lists them. */
    fun export(name: String, spec: Spec, notes: List<List<KeyNote>>, destination: File,
               hooks: InstrumentPackageAdmission.Hooks = InstrumentPackageAdmission.Hooks()): KeygroupProgram {
        val sourceNotes = cells(spec, notes)
        return InstrumentPackageAdmission.publishNew(name, destination,
            build = { packet -> DrawlKeygroupMaker.writeNewPackage(name, spec.keys, sourceNotes, packet) },
            validate = { packet -> DrawlKeygroupMaker.validate(name, packet, spec.keys); Unit },
            hooks = hooks).program
    }

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("DRAWL instrument cancelled")
    }
}
