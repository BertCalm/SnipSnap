package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Etch
import com.snipsnap.synth.EtchPatch
import com.snipsnap.xpm.KeygroupProgram
import java.io.File
import java.util.concurrent.CancellationException

/** Android instrument-sheet adapter for accepted finite ETCH patches. */
object EtchPadMaker {
    const val UNSUPPORTED_NOTE = "Choose a finite recipe with nonzero velocity. HOLD loops keep their sound."
    fun supports(patch: EtchPatch): Boolean = patch.velocity > 0f &&
        !Etch.isLoop(Etch.settled(patch.voice, patch.macros).getValue("HOLD"))

    data class Spec(val patch: EtchPatch) {
        init { require(supports(patch)) { "ETCH instruments need a finite, nonzero source; HOLD loops remain separate" } }
        val keys = EtchKeygroupMaker.Spec(patch)
    }

    fun zoneMidis(spec: Spec): List<Int> = EtchKeygroupMaker.roots(spec.keys)
    fun instrumentNote(spec: Spec): String =
        "Finite notes, no loop. ${Scales.nameOf(spec.keys.lowMidi)}–${Scales.nameOf(spec.keys.highMidi)} · three touch layers · ${zoneMidis(spec).size * 3} samples."

    /** Each root and touch renders serially; the caller also keeps roots serial. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec))
        val recipes = EtchKeygroupMaker.recipes(spec.keys)
        return EtchKeygroupMaker.TOUCHES.indices.map { touch ->
            stop(cancelled)
            val source = recipes.getValue(EtchKeygroupMaker.Cell(midi, touch))
            val rendered = source.render()
            stop(cancelled)
            KeyNote(rendered, loopStartFrame = 0)
        }
    }

    /** Exact current source root/touch; the finite source envelope is not replaced. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        stop(cancelled)
        return spec.patch.render().also { stop(cancelled) }
    }

    fun cells(spec: Spec, notes: List<List<KeyNote>>): Map<EtchKeygroupMaker.Cell, EtchKeygroupMaker.Note> {
        val midis = zoneMidis(spec)
        require(notes.size == midis.size)
        val recipes = EtchKeygroupMaker.recipes(spec.keys)
        return buildMap {
            for ((zone, midi) in midis.withIndex()) {
                require(notes[zone].size == EtchKeygroupMaker.TOUCHES.size)
                notes[zone].forEachIndexed { touch, note ->
                    require(note.loopStartFrame == 0L) { "ETCH finite keys cannot acquire sustain loops" }
                    val cell = EtchKeygroupMaker.Cell(midi, touch)
                    put(cell, EtchKeygroupMaker.Note(recipes.getValue(cell), note.snip))
                }
            }
        }
    }

    /** Place actual .xty/TrackData/sidecar siblings directly where InstrumentStore lists them. */
    fun export(name: String, spec: Spec, notes: List<List<KeyNote>>, destination: File,
               hooks: InstrumentPackageAdmission.Hooks = InstrumentPackageAdmission.Hooks()): KeygroupProgram {
        val sourceNotes = cells(spec, notes)
        return InstrumentPackageAdmission.publishNew(name, destination,
            build = { packet -> EtchKeygroupMaker.writeNewPackage(name, spec.keys, sourceNotes, packet) },
            validate = { packet -> EtchKeygroupMaker.validate(name, packet, spec.keys); Unit },
            hooks = hooks).program
    }

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("ETCH instrument cancelled")
    }
}
