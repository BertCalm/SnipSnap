package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.SplayPatch
import com.snipsnap.xpm.KeygroupProgram
import java.io.File
import java.util.concurrent.CancellationException

/** Instrument-sheet adapter for the saved finite SPLAY performance; it never substitutes a held envelope. */
object SplayFinitePadMaker {
    const val UNSUPPORTED_NOTE = "FINITE SPLAY KEYS NEED VELOCITY ABOVE ZERO. HOLD REMAINS UNAVAILABLE."

    fun supports(patch: SplayPatch): Boolean = SplayFiniteKeygroupMaker.supports(patch)

    data class Spec(val patch: SplayPatch) {
        val keys = SplayFiniteKeygroupMaker.Spec(patch)
    }

    fun zoneMidis(spec: Spec): List<Int> = SplayFiniteKeygroupMaker.roots(spec.keys)

    fun instrumentNote(spec: Spec): String =
        "FINITE NOTES · NO LOOP. ${Scales.nameOf(spec.keys.lowMidi)}–${Scales.nameOf(spec.keys.highMidi)} · THREE GAIN-ONLY VELOCITY LEVELS · ${zoneMidis(spec).size * SplayFiniteKeygroupMaker.TOUCHES.size} SAMPLES."

    /** Serial roots keep the mechanical histories bounded; cancellation is checked around every Kotlin engine render. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec))
        val recipes = SplayFiniteKeygroupMaker.recipes(spec.keys)
        return SplayFiniteKeygroupMaker.TOUCHES.indices.map { touch ->
            stop(cancelled)
            val source = recipes.getValue(SplayFiniteKeygroupMaker.Cell(midi, touch))
            val snip = source.render()
            stop(cancelled)
            KeyNote(snip, loopStartFrame = 0)
        }
    }

    /** Preview uses the complete saved model, root, velocity and seed with engine leveling. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        stop(cancelled)
        return spec.patch.render().also { stop(cancelled) }
    }

    fun cells(spec: Spec, notes: List<List<KeyNote>>): Map<SplayFiniteKeygroupMaker.Cell, SplayFiniteKeygroupMaker.Note> {
        val midis = zoneMidis(spec)
        require(notes.size == midis.size)
        val recipes = SplayFiniteKeygroupMaker.recipes(spec.keys)
        return buildMap {
            for ((zone, midi) in midis.withIndex()) {
                require(notes[zone].size == SplayFiniteKeygroupMaker.TOUCHES.size)
                notes[zone].forEachIndexed { touch, note ->
                    require(note.loopStartFrame == 0L) { "SPLAY finite keys cannot acquire sustain loops" }
                    val cell = SplayFiniteKeygroupMaker.Cell(midi, touch)
                    put(cell, SplayFiniteKeygroupMaker.Note(recipes.getValue(cell), note.snip))
                }
            }
        }
    }

    /** The established admission transaction makes the XTY, TrackData and phone sidecar visible together. */
    fun export(name: String, spec: Spec, notes: List<List<KeyNote>>, destination: File): KeygroupProgram =
        SplayFiniteKeygroupMaker.exportFresh(name, spec.keys, cells(spec, notes), destination).program

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted)
            throw CancellationException("SPLAY finite instrument cancelled")
    }
}
