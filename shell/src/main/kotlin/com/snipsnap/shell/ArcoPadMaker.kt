package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.ArcoKeys
import com.snipsnap.synth.ArcoPatch
import com.snipsnap.synth.KeyNote
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

/**
 * ARCO, held - a saved ARCO sound as a keys instrument whose keys begin with the bow's own stroke and sustain on the bowed loop
 * ([ArcoKeys]; docs/superpowers/specs/2026-09-29-arco-bowed-string-engine-design.md, "R2: the held keygroup, as built").
 * [BellowsPadMaker]'s shape: RELEASE alone, absolute zones, a list of velocity layers per zone. It replaces the loop-only ARCO keys
 * [LoopPadMaker] offered until 2026-11-06, which had no attack: a key there started in the middle of the loop.
 *
 * Every zone is two samples, soft first: the soft one at BOW and GRIP times [ArcoKeys.SOFT_SHARE] (a slower stroke, a lighter grip, a
 * darker string), the hard one at the patch's own macros. Both are levelled on their loop to the same target, so velocity picks the
 * timbre and the player's velocity curve sets the level. TUNE does not move the zones: each zone is its key's exact pitch, whatever
 * TUNE the patch was saved at.
 *
 * Zones render one at a time through [renderZone], so a caller can show progress as each lands and cancel between them; [assemble]
 * and [export] never render. Nothing here has been heard.
 */
object ArcoPadMaker {

    /** The same range every held pad settled on ([BellowsPadMaker], [ResinPadMaker], [KeelPadMaker]). */
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    /** The MIDI velocity windows of the two layers, soft first: an even split, as the kit assembler splits two zones (1..63, 64..127). */
    val VELOCITY_WINDOWS = listOf(1..63, 64..127)

    /** How far the lowest and highest zones reach past their roots: [LoopPadMaker]'s and [ResinPadMaker]'s layout (ERHU's top zone plays up to F#6). */
    const val EDGE_REACH = 9

    data class Spec(val patch: ArcoPatch, val releaseSeconds: Float = RELEASE.default) {
        init {
            require(supports(patch)) { "ARCO ${patch.voice} has no held zones" }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
        }
    }

    /** Whether [patch]'s voice has a held zone plan; every ARCO voice has one today, so a voice added without one is not offered. */
    fun supports(patch: ArcoPatch): Boolean = ArcoKeys.zoneMidis(patch.voice).isNotEmpty()

    /** The sheet's one stepper as a spec, clamped as [BellowsPadMaker.spec] is: an exponential knob's ends can land a float's width outside its range. */
    fun spec(patch: ArcoPatch, releaseFraction: Float): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS))

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    /** CELLO's nine zones, C2 to C4 every minor third; ERHU's eight, D4 to G#5 and A5 ([ArcoKeys.zoneMidis]). The patch's TUNE plays no part. */
    fun zoneMidis(spec: Spec): List<Int> = ArcoKeys.zoneMidis(spec.patch.voice)

    /**
     * One zone's two layers, soft first, each the bow's stroke then the loop ([ArcoKeys.layers]). A zone renders in about a second of
     * a desktop JVM's time for both layers (ArcoKeysTest prints the measured cost); [cancelled] is asked before every pass of each
     * layer's retune. A zone whose loop does not close is refused by name.
     */
    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec)) { "ARCO ${spec.patch.voice} zone roots are ${zoneMidis(spec)}, got $midi" }
        return ArcoKeys.layers(spec.patch.voice, spec.patch.macros, midi, cancelled)
    }

    /**
     * The zones, in [zoneMidis] order, each a list of its layers soft first, as a keygroup program and the samples it names. Inner zones
     * reach halfway to their neighbours (ERHU's G#5 and A5 are a semitone apart), the edge zones [EDGE_REACH] semitones past their roots.
     * The WAVs carry their own stroke, so the program adds no attack of its own; RELEASE is the program's fade after a key lifts.
     */
    fun assemble(name: String, spec: Spec, notes: List<List<KeyNote>>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) {
            "instrument name must be MPC-safe and at most ${OneNote.MAX_NAME_LENGTH} characters: '$name'"
        }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} pitch zones, got ${notes.size}" }
        val prefix = name.filter { !it.isWhitespace() }
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = midis.mapIndexed { index, midi ->
            require(notes[index].size == VELOCITY_WINDOWS.size) { "ARCO zone ${Scales.nameOf(midi)} needs ${VELOCITY_WINDOWS.size} velocity layers, got ${notes[index].size}" }
            val layers = notes[index].mapIndexed { layerIndex, note ->
                require(note.snip.channels == 1 && note.snip.sampleRate == WavWriter.MPC_SAMPLE_RATE) { "ARCO samples must be mono ${WavWriter.MPC_SAMPLE_RATE} Hz" }
                require(note.loopStartFrame in 1 until note.snip.frameCount.toLong() - 1) { "ARCO zone ${Scales.nameOf(midi)} must have a stroke and a sustain loop" }
                val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}_v${layerIndex + 1}")
                samples[stem] = note.snip
                val window = VELOCITY_WINDOWS[layerIndex]
                VelocityLayer(stem, note.snip.frameCount.toLong(), window.first, window.last, note.loopStartFrame)
            }
            Keygroup(
                lowNote = (if (index == 0) midi - EDGE_REACH else (midis[index - 1] + midi) / 2 + 1).coerceIn(0, 127),
                highNote = (if (index == midis.lastIndex) midi + EDGE_REACH else (midi + midis[index + 1]) / 2).coerceIn(0, 127),
                rootNote = midi,
                layers = layers,
            )
        }
        return KeygroupProgram(name, keygroups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun export(name: String, spec: Spec, notes: List<List<KeyNote>>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        return program
    }

    /** The hard layer's index in a zone's list: the last, since the layers run soft first. */
    val HARD = VELOCITY_WINDOWS.lastIndex

    /**
     * A held key as the instrument plays it: one zone's [layer] (the middle zone and the hard layer unless asked) rendered, loaded into
     * [InstrumentEngine] in its own velocity window, pressed at the top of that window, held for [holdSeconds] (by default the stroke and
     * the loop twice, so the hold crosses the wrap), then let go and faded by the spec's RELEASE, as [BellowsPadMaker.preview] does. Only
     * the one layer renders. The release is the player's fade, never baked into the exported samples.
     */
    fun preview(
        spec: Spec, cancelled: () -> Boolean = { false },
        midi: Int = zoneMidis(spec)[zoneMidis(spec).size / 2], layer: Int = HARD, holdSeconds: Float? = null,
    ): Snip {
        require(midi in zoneMidis(spec)) { "ARCO ${spec.patch.voice} zone roots are ${zoneMidis(spec)}, got $midi" }
        require(layer in VELOCITY_WINDOWS.indices) { "ARCO has ${VELOCITY_WINDOWS.size} layers, got $layer" }
        val note = ArcoKeys.pad(spec.patch.voice, spec.patch.macros, midi, ArcoKeys.Layer.entries[layer], cancelled)
        val hold = holdSeconds?.let { (it * note.snip.sampleRate).roundToInt() }
            ?: (note.snip.frameCount + (note.snip.frameCount - note.loopStartFrame.toInt()))
        return play(note, midi, VELOCITY_WINDOWS[layer], hold, spec.releaseSeconds, cancelled)
    }

    /**
     * [note] as a one-zone, one-layer instrument rooted at [midi], its layer in [window], through [InstrumentEngine]: pressed at
     * the top of the window (so the player's own velocity rule lands in it), held for [holdFrames], released over [releaseSeconds], and
     * a 10 ms silent guard after. Mono. The audition's A/B clips play the loop-only zone through this same player.
     */
    internal fun play(note: KeyNote, midi: Int, window: IntRange, holdFrames: Int, releaseSeconds: Float, cancelled: () -> Boolean = { false }): Snip {
        val source = note.snip
        val file = "held.wav"
        val layer = InstrumentStore.Layer(file, source.frameCount.toLong(), note.loopStartFrame, window.first, window.last)
        val zone = InstrumentStore.Zone(midi, midi, midi, file, source.frameCount.toLong(), note.loopStartFrame, listOf(layer))
        val instrument = InstrumentStore.Instrument("ARCO PREVIEW", releaseSeconds, listOf(zone))
        val engine = InstrumentEngine(InstrumentEngine.Loaded(instrument, source.sampleRate, mapOf(file to source.samples)), source.sampleRate)
        check(engine.noteOn(midi, window.last / 127f)) { "the preview key did not land in its layer's window $window" }
        val releaseFrames = (releaseSeconds * source.sampleRate).roundToInt()
        val tailFrames = (source.sampleRate * .01f).roundToInt()
        val mono = FloatArray(holdFrames + releaseFrames + tailFrames)
        val block = FloatArray(256 * 2)
        var at = 0
        fun render(until: Int) {
            while (at < until) {
                if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("ARCO preview no longer wanted")
                val frames = minOf(block.size / 2, until - at)
                engine.render(block, frames)
                for (i in 0 until frames) mono[at + i] = block[i * 2]
                at += frames
            }
        }
        render(holdFrames)
        engine.noteOff(midi)
        render(mono.size)
        return Snip(mono, 1, source.sampleRate)
    }
}
