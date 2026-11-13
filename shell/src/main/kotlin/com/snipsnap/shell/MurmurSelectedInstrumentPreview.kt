package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.MurmurSelectedInstrumentSource
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.pow

/** A finite reference-player preview, including actual sampler key-up, not a natural-release claim. */
object MurmurSelectedInstrumentPreview {
    fun render(spec: MurmurSelectedInstrumentMaker.Spec, cancelled: () -> Boolean = { false }): Snip =
        renderUsing(spec, cancelled, MurmurSelectedInstrumentSource::renderCell)

    /** Offline structured-chord preview; final tones are validated before the first capture. */
    fun renderChord(spec: MurmurSelectedInstrumentMaker.Spec, finalNotes: List<Int>,
                    cancelled: () -> Boolean = { false }): Snip =
        renderChordUsing(spec, finalNotes, cancelled, MurmurSelectedInstrumentSource::renderCell)

    internal fun renderUsing(
        spec: MurmurSelectedInstrumentMaker.Spec,
        cancelled: () -> Boolean,
        renderCell: (MurmurSelectedInstrumentSource.CellRecipe) -> MurmurSelectedInstrumentSource.Capture,
    ): Snip = renderChordUsing(spec, listOf(spec.sourcePatch().midi), cancelled, renderCell)

    internal fun renderChordUsing(
        spec: MurmurSelectedInstrumentMaker.Spec,
        finalNotes: List<Int>,
        cancelled: () -> Boolean,
        renderCell: (MurmurSelectedInstrumentSource.CellRecipe) -> MurmurSelectedInstrumentSource.Capture,
    ): Snip {
        checkCancelled(cancelled)
        val source = spec.sourcePatch()
        val chord = MurmurSelectedChordPlan.fromNotes(source, finalNotes)
        // The middle absolute native touch and live .65 velocity are distinct.
        // This is a representative instrument key, not the authored .86 raw reference.
        val coveringZones = MurmurSelectedInstrumentMaker.zones(spec).filter { zone ->
            chord.notes.any { it in zone.low..zone.high }
        }
        require(chord.notes.all { note -> coveringZones.count { note in it.low..it.high } == 1 })
        val captures = coveringZones.map { zone ->
            checkCancelled(cancelled)
            val recipe = MurmurSelectedInstrumentSource.cellRecipe(source, zone.root, 1, spec.releaseSeconds)
            renderCell(recipe).also { capture ->
                checkCancelled(cancelled)
                require(capture.recipe.toJsonText() == recipe.toJsonText())
                require(capture.snip.channels == 1 && capture.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE)
                require(capture.snip.samples.all { it.isFinite() })
                require(capture.loopEndFrameExclusive == capture.snip.frameCount)
                require(if (spec.held) capture.loopStartFrame > 0 else capture.loopStartFrame == 0)
            }
        }
        val zones = coveringZones.zip(captures).map { (zone, capture) ->
            InstrumentStore.Zone(zone.low, zone.high, zone.root, "preview_" + zone.root + ".wav",
                capture.snip.frameCount.toLong(), capture.loopStartFrame.toLong())
        }
        val samples = zones.zip(captures).associate { (zone, capture) -> zone.sample to capture.snip.samples.copyOf() }
        val instrument = InstrumentStore.Instrument("MURMUR SELECTED PREVIEW", spec.releaseSeconds,
            zones, explicitNoteOn = true)
        val rate = WavWriter.MPC_SAMPLE_RATE
        val engine = InstrumentEngine(InstrumentEngine.Loaded(instrument, rate, samples), rate)
        // All tones/captures exist before any note is started. This is the
        // offline reference player, not native whole-chord atomic admission.
        for (midi in chord.notes) require(engine.noteOn(midi, .65f))
        val playedFrames = chord.notes.map { note ->
            val index = zones.indexOfFirst { note in it.lowNote..it.highNote }
            val capture = captures[index]
            val ratio = 2.0.pow((note - zones[index].rootNote) / 12.0)
            val sourceFrames = if (spec.held) capture.loopStartFrame.toDouble() +
                2.0 * (capture.loopEndFrameExclusive - capture.loopStartFrame)
            else capture.snip.frameCount.toDouble()
            sourceFrames / ratio
        }
        // Finite source remains complete in the package; this is a short key
        // press that exercises sampler release before any finite note's EOF.
        val holdFramesLong = if (spec.held) ceil(playedFrames.maxOf { it }).toLong()
            else minOf(rate / 4.0, playedFrames.minOf { it } / 2.0).toLong().coerceAtLeast(1L)
        // Extra zero tail also bounds Float release-step accumulation; it is
        // presentation padding, not a longer native or sampler envelope.
        val tailFrames = ceil(spec.releaseSeconds * rate.toDouble()).toInt() + 2048
        require(holdFramesLong > 0 && holdFramesLong + tailFrames <= rate.toLong() * 30)
        val holdFrames = holdFramesLong.toInt()
        val stereo = FloatArray((holdFrames + tailFrames) * 2)
        var written = 0
        fun fill(frames: Int) {
            var left = frames
            while (left > 0) {
                checkCancelled(cancelled)
                val count = minOf(left, 256)
                val block = FloatArray(count * 2)
                engine.render(block, count)
                block.copyInto(stereo, written * 2)
                written += count
                left -= count
            }
        }
        fill(holdFrames)
        for (midi in chord.notes) engine.noteOff(midi)
        fill(tailFrames)
        check(engine.activeNotes.isEmpty()) { "MURMUR preview did not finish its declared sampler key-up" }
        require(stereo.all { it.isFinite() } && stereo.takeLast(64).all { it == 0f })
        return Snip(stereo, 2, rate)
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted)
            throw CancellationException("MURMUR instrument preview cancelled")
    }
}
