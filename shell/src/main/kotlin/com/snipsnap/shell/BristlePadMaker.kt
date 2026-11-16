package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.Bristle
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.BristleHeld
import com.snipsnap.synth.BristlePatch
import com.snipsnap.xpm.KeygroupProgram
import java.io.File
import java.util.concurrent.CancellationException

/** Instrument-sheet adapter; sustain is an explicit building choice beside the saved finite source. */
object BristlePadMaker {
    const val UNSUPPORTED_NOTE = "Finite keys need nonzero velocity and an authored root in C1–C3."

    fun supports(patch: BristlePatch): Boolean = BristleKeygroupMaker.supports(patch)

    val RELEASE = Knob("RELEASE", .01f, 1.5f, .2f, exponential = true)

    data class Spec(val patch: BristlePatch, val held: Boolean = false,
                    val releaseSeconds: Float = if (held) RELEASE.default else .045f, val takes: Int = 1) {
        val keys = BristleKeygroupMaker.Spec(patch, releaseSeconds = releaseSeconds, held = held, takes = takes)
    }

    fun spec(patch: BristlePatch, held: Boolean, releaseFraction: Float, takes: Int = 1): Spec =
        Spec(patch, held, if (held) RELEASE.value(releaseFraction).coerceIn(RELEASE.lo, RELEASE.hi) else .045f, takes)

    fun zoneMidis(spec: Spec): List<Int> = BristleKeygroupMaker.roots(spec.keys)

    fun instrumentNote(spec: Spec): String =
        "${if (spec.held) "Hold a key to sustain; release to fade." else "Finite notes · no loop."} ${Scales.nameOf(spec.keys.lowMidi)}–${Scales.nameOf(spec.keys.highMidi)} · three touch layers · ${zoneMidis(spec).size * 3 * spec.takes} samples." +
            if (spec.takes > 1) " Two takes per touch. MPC cycling uses separate touch programs." else ""

    /** The screen also renders roots serially; cancellation is checked on either side of each native render. */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec))
        val recipes = BristleKeygroupMaker.recipes(spec.keys)
        return BristleKeygroupMaker.TOUCHES.indices.flatMap { touch -> (0 until spec.takes).map { take ->
            stop(cancelled)
            val source = recipes.getValue(BristleKeygroupMaker.Cell(midi, touch, take))
            val note = BristleKeygroupMaker.prepareNote(source, BristleKeygroupMaker.TOUCHES[touch], spec.held, cancelled)
            stop(cancelled)
            note
        } }
    }

    /** Finite preview is exact; held preview plays three seconds and the chosen sampler release. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        stop(cancelled)
        if (spec.takes > 1) return previewCycle(spec, cancelled)
        val note = previewNote(spec, cancelled)
        if (!spec.held) return note.snip
        // The sheet must audition the RELEASE knob, not run an endless loop
        // whose Stop button uses the preview player's unrelated safety fade.
        val midi = Bristle.rootMidi(spec.patch)
        val zone = InstrumentStore.Zone(midi, midi, midi, "preview", note.snip.frameCount.toLong(), note.loopStartFrame)
        val instrument = InstrumentStore.Instrument("Bristle preview", spec.releaseSeconds, listOf(zone), explicitNoteOn = true)
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, note.snip.sampleRate, listOf(note.snip.samples)), note.snip.sampleRate)
        check(player.noteOn(midi))
        fun render(frames: Int): FloatArray {
            val out = FloatArray(frames * 2)
            val block = FloatArray(512)
            var offset = 0
            while (offset < frames) {
                stop(cancelled)
                val count = minOf(256, frames - offset)
                player.render(block, count)
                block.copyInto(out, offset * 2, 0, count * 2)
                offset += count
            }
            return out
        }
        val held = render(note.snip.sampleRate * 3)
        player.noteOff(midi)
        val tail = render(((spec.releaseSeconds + .05f) * note.snip.sampleRate).toInt())
        stop(cancelled)
        return Snip(held + tail, 2, note.snip.sampleRate)
    }

    /** Two genuine attacks at each touch, selected by the same cursor as the keyboard. */
    private fun previewCycle(spec: Spec, cancelled: () -> Boolean): Snip {
        val midi = Bristle.rootMidi(spec.patch)
        val notes = renderZone(spec, midi, cancelled)
        val layers = notes.mapIndexed { index, note ->
            val window = BristleKeygroupMaker.VELOCITY_WINDOWS[index / spec.takes]
            InstrumentStore.Layer("take$index", note.snip.frameCount.toLong(), note.loopStartFrame, window.first, window.last)
        }
        val last = layers.last()
        val zone = InstrumentStore.Zone(midi, midi, midi, last.sample, last.frameCount, last.loopStartFrame, layers, cycleLayers = true)
        val instrument = InstrumentStore.Instrument("Bristle variations", spec.releaseSeconds, listOf(zone), explicitNoteOn = true)
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, WavWriter.MPC_SAMPLE_RATE,
            notes.mapIndexed { index, note -> "take$index" to note.snip.samples }.toMap()), WavWriter.MPC_SAMPLE_RATE)
        val hold = (WavWriter.MPC_SAMPLE_RATE * .4).toInt()
        val tail = ((spec.releaseSeconds + .05f) * WavWriter.MPC_SAMPLE_RATE).toInt()
        val out = FloatArray((hold + tail) * 6 * 2)
        var offset = 0
        for (v in BristleKeygroupMaker.TOUCHES) repeat(2) {
            stop(cancelled)
            check(player.noteOn(midi, v))
            val a = FloatArray(hold * 2); player.render(a, hold); a.copyInto(out, offset); offset += a.size
            player.noteOff(midi)
            val b = FloatArray(tail * 2); player.render(b, tail); b.copyInto(out, offset); offset += b.size
        }
        stop(cancelled)
        return Snip(out, 2, WavWriter.MPC_SAMPLE_RATE)
    }

    fun previewNote(spec: Spec, cancelled: () -> Boolean = { false }): KeyNote {
        stop(cancelled)
        return (if (spec.held) BristleHeld.render(spec.patch, cancelled).note else KeyNote(spec.patch.render()))
            .also { stop(cancelled) }
    }

    fun cells(spec: Spec, notes: List<List<KeyNote>>): Map<BristleKeygroupMaker.Cell, BristleKeygroupMaker.Note> {
        val midis = zoneMidis(spec)
        require(notes.size == midis.size)
        val recipes = BristleKeygroupMaker.recipes(spec.keys)
        return buildMap {
            for ((zone, midi) in midis.withIndex()) {
                require(notes[zone].size == BristleKeygroupMaker.TOUCHES.size * spec.takes)
                notes[zone].forEachIndexed { index, note ->
                    require(note.loopStartFrame == if (spec.held) BristleHeld.LOOP_START_FRAME.toLong() else 0L) { "BRISTLE playback mode differs" }
                    val cell = BristleKeygroupMaker.Cell(midi, index / spec.takes, index % spec.takes)
                    put(cell, BristleKeygroupMaker.Note(recipes.getValue(cell), note.snip, note.loopStartFrame))
                }
            }
        }
    }

    /** Publish actual sampler/TrackData/phone siblings directly into the instrument shelf. */
    fun export(name: String, spec: Spec, notes: List<List<KeyNote>>, destination: File): KeygroupProgram =
        BristleKeygroupMaker.exportFresh(name, spec.keys, cells(spec, notes), destination).program

    private fun stop(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted)
            throw CancellationException("BRISTLE finite instrument cancelled")
    }
}
