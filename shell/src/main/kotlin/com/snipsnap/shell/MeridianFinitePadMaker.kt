package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.MeridianPatch
import com.snipsnap.xpm.KeygroupProgram
import java.io.File
import java.util.concurrent.CancellationException

/** Instrument-sheet adapter for the complete finite MERIDIAN recipe. Held sources keep their existing maker. */
object MeridianFinitePadMaker {
    const val UNSUPPORTED_NOTE = "Finite keys need nonzero velocity and an authored root in C2–C7."

    fun supports(patch: MeridianPatch): Boolean = MeridianFiniteKeygroupMaker.supports(patch)

    data class Spec(val patch: MeridianPatch) {
        val keys = MeridianFiniteKeygroupMaker.Spec(patch)
    }

    fun zoneMidis(spec: Spec): List<Int> = MeridianFiniteKeygroupMaker.roots(spec.keys)

    fun instrumentNote(spec: Spec): String =
        "Finite notes · no loop. ${Scales.nameOf(spec.keys.lowMidi)}–${Scales.nameOf(spec.keys.highMidi)} · three touch layers · ${zoneMidis(spec).size * 3} samples."

    /** The screen also renders roots serially; cancellation is checked on either side of each native render. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec))
        val recipes = MeridianFiniteKeygroupMaker.recipes(spec.keys)
        return MeridianFiniteKeygroupMaker.TOUCHES.indices.map { touch ->
            stop(cancelled)
            val source = recipes.getValue(MeridianFiniteKeygroupMaker.Cell(midi, touch))
            val snip = source.render()
            stop(cancelled)
            KeyNote(snip, loopStartFrame = 0)
        }
    }

    /** Preview preserves the authored TUNE, native note, touch, saved renderer and finite envelope. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        stop(cancelled)
        return spec.patch.render().also { stop(cancelled) }
    }

    fun cells(spec: Spec, notes: List<List<KeyNote>>): Map<MeridianFiniteKeygroupMaker.Cell, MeridianFiniteKeygroupMaker.Note> {
        val midis = zoneMidis(spec)
        require(notes.size == midis.size)
        val recipes = MeridianFiniteKeygroupMaker.recipes(spec.keys)
        return buildMap {
            for ((zone, midi) in midis.withIndex()) {
                require(notes[zone].size == MeridianFiniteKeygroupMaker.TOUCHES.size)
                notes[zone].forEachIndexed { touch, note ->
                    require(note.loopStartFrame == 0L) { "MERIDIAN finite keys cannot acquire sustain loops" }
                    val cell = MeridianFiniteKeygroupMaker.Cell(midi, touch)
                    put(cell, MeridianFiniteKeygroupMaker.Note(recipes.getValue(cell), note.snip))
                }
            }
        }
    }

    /** Publish actual sampler/TrackData/phone siblings directly into the instrument shelf. */
    fun export(name: String, spec: Spec, notes: List<List<KeyNote>>, destination: File): KeygroupProgram =
        MeridianFiniteKeygroupMaker.exportFresh(name, spec.keys, cells(spec, notes), destination).program

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted)
            throw CancellationException("MERIDIAN finite instrument cancelled")
    }
}
