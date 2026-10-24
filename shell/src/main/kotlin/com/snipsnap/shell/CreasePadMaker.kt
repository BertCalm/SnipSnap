package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.kit.InstrumentStore
import com.snipsnap.kit.Names
import com.snipsnap.kit.OneNote
import com.snipsnap.synth.Crease
import com.snipsnap.synth.CreaseKeys
import com.snipsnap.synth.CreasePatch
import com.snipsnap.synth.CreaseTouchKeys
import com.snipsnap.synth.KeyNote
import com.snipsnap.synth.Keys
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File
import kotlin.math.roundToInt

/**
 * A saved model-3 CREASE sound as an attack followed by the engine's continuous
 * held gesture. The saved velocity chooses the rendered excitation and timbre;
 * key touches use the player's existing linear gain on that authored sample.
 * Optional touch layers also select softer excitation histories below the saved
 * velocity, which remains the hard-touch anchor. The default keeps one sample.
 * RELEASE belongs to the phone/MPC amplitude envelope after a key lifts.
 *
 * Zones render separately so callers can cancel and count progress before
 * [export] writes a complete package. The source patch is never rewritten.
 */
object CreasePadMaker {
    const val RELEASE_MIN_SECONDS = .1f
    const val RELEASE_MAX_SECONDS = 1.5f
    val RELEASE = Knob("RELEASE", RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS, .6f, exponential = true)

    /** Legacy finite and HOLD recipes retain their original models; this builder never upgrades them. */
    fun supports(patch: CreasePatch): Boolean = CreaseKeys.supports(patch)

    fun secondsLabel(seconds: Float): String = "%.2f s".format(java.util.Locale.ROOT, seconds)

    data class Spec(
        val patch: CreasePatch,
        val releaseSeconds: Float = RELEASE.default,
        val touchLayers: Boolean = false,
    ) {
        val layerCount: Int get() = if (touchLayers) CreaseTouchKeys.TOUCHES.size else 1

        init {
            require(supports(patch)) {
                "CREASE held instruments support model ${CreaseKeys.SUPPORTED_MODEL}; model ${patch.model} stays unchanged"
            }
            require(releaseSeconds.isFinite() && releaseSeconds in RELEASE_MIN_SECONDS..RELEASE_MAX_SECONDS) {
                "release wants $RELEASE_MIN_SECONDS..$RELEASE_MAX_SECONDS s, got $releaseSeconds"
            }
            require(!touchLayers || patch.velocity == 0f || patch.velocity >= CreaseTouchKeys.MIN_POSITIVE_ANCHOR) {
                "CREASE touch anchor velocity must be zero or at least ${CreaseTouchKeys.MIN_POSITIVE_ANCHOR}"
            }
        }
    }

    fun spec(patch: CreasePatch, releaseFraction: Float, touchLayers: Boolean = false): Spec =
        Spec(patch, RELEASE.value(releaseFraction).coerceIn(RELEASE_MIN_SECONDS, RELEASE_MAX_SECONDS), touchLayers)

    /** Minor-third roots around the saved pitch, with exact roots at clipped range boundaries. */
    fun zoneMidis(spec: Spec): List<Int> = Keys.creasePadMidis(spec.patch.midi)

    fun renderZone(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        require(midi in zoneMidis(spec)) { "CREASE zone $midi is outside this instrument's roots" }
        return Keys.creasePad(spec.patch, midi, cancelled)
    }

    /** Soft-first samples; every layer retains its own real onset and measured loop. */
    fun renderLayers(spec: Spec, midi: Int, cancelled: () -> Boolean = { false }): List<KeyNote> {
        require(midi in zoneMidis(spec)) { "CREASE zone $midi is outside this instrument's roots" }
        return if (spec.touchLayers) CreaseTouchKeys.render(spec.patch, midi, cancelled)
        else listOf(renderZone(spec, midi, cancelled))
    }

    fun assemble(name: String, spec: Spec, notes: List<KeyNote>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(!spec.touchLayers) { "CREASE touch instruments need assembleLayers; a single sample would discard their softer layers" }
        return assembleLayers(name, spec, notes.map { listOf(it) })
    }

    fun assembleLayers(name: String, spec: Spec, notes: List<List<KeyNote>>): Pair<KeygroupProgram, Map<String, Snip>> {
        require(Names.isMpcSafe(name) && name.length <= OneNote.MAX_NAME_LENGTH) {
            "instrument name must be MPC-safe and at most ${OneNote.MAX_NAME_LENGTH} characters: '$name'"
        }
        val midis = zoneMidis(spec)
        require(notes.size == midis.size) { "want ${midis.size} zones, got ${notes.size}" }
        val prefix = name.filter { !it.isWhitespace() }
        val samples = LinkedHashMap<String, Snip>()
        val keygroups = midis.mapIndexed { index, midi ->
            require(notes[index].size == spec.layerCount) {
                "CREASE zone $midi needs ${spec.layerCount} velocity layers, got ${notes[index].size}"
            }
            val layers = notes[index].mapIndexed { layerIndex, note ->
                require(note.snip.channels == 1 && note.snip.sampleRate == Crease.RATE) {
                    "CREASE zone $midi must be mono at ${Crease.RATE} Hz"
                }
                require(note.loopStartFrame > 0 && note.loopStartFrame < note.snip.frameCount - 1) {
                    "CREASE zone $midi needs an attack and a sustain loop of at least two frames"
                }
                require(note.snip.samples.all { it.isFinite() }) { "CREASE zone $midi has nonfinite audio" }
                val suffix = if (spec.touchLayers) "_v${layerIndex + 1}" else ""
                val stem = Names.sanitizeStem("${prefix}_${Scales.nameOf(midi)}$suffix")
                samples[stem] = note.snip
                val window = if (spec.touchLayers) CreaseTouchKeys.VELOCITY_WINDOWS[layerIndex] else 0..127
                VelocityLayer(stem, note.snip.frameCount.toLong(), window.first, window.last, note.loopStartFrame)
            }
            Keygroup(
                lowNote = if (index == 0) midi else (midis[index - 1] + midi) / 2 + 1,
                highNote = if (index == midis.lastIndex) midi else (midi + midis[index + 1]) / 2,
                rootNote = midi,
                layers = layers,
            )
        }
        // The WAV already contains its pressure onset. A program attack would soften it twice.
        return KeygroupProgram(name, keygroups, volumeRelease = spec.releaseSeconds) to samples
    }

    fun export(name: String, spec: Spec, notes: List<KeyNote>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assemble(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        return program
    }

    fun exportLayers(name: String, spec: Spec, notes: List<List<KeyNote>>, destRoot: File, overwrite: Boolean = false): KeygroupProgram {
        val (program, samples) = assembleLayers(name, spec, notes)
        OneNote.writePackage(name, program, samples, destRoot, overwrite)
        return program
    }

    /** Hear the saved root's arrival, another full held pass, then the selected key-up fade. */
    fun preview(spec: Spec, cancelled: () -> Boolean = { false }): Snip {
        if (spec.touchLayers) return touchPreview(spec, cancelled)
        val note = renderZone(spec, spec.patch.midi, cancelled)
        fun checkCancelled() {
            if (cancelled() || Thread.currentThread().isInterrupted)
                throw java.util.concurrent.CancellationException("CREASE preview cancelled")
        }
        checkCancelled()
        val samples = note.snip.samples
        val start = note.loopStartFrame.toInt()
        val period = samples.size - start
        val noteOff = samples.size + period
        val releaseFrames = (spec.releaseSeconds * note.snip.sampleRate).roundToInt().coerceAtLeast(2)
        val out = FloatArray(noteOff + releaseFrames)
        var at = 0
        while (at < samples.size) {
            checkCancelled()
            val end = minOf(at + 8192, samples.size)
            samples.copyInto(out, destinationOffset = at, startIndex = at, endIndex = end)
            at = end
        }
        for (i in 0 until period) {
            if (i % 8192 == 0) checkCancelled()
            out[samples.size + i] = samples[start + i]
        }
        // Key-up keeps reading the same loop. Only the player's amplitude release changes;
        // no new deformation trajectory, leveling or DSP render is introduced here.
        for (i in 0 until releaseFrames) {
            if (i % 8192 == 0) checkCancelled()
            val gain = 1f - i.toFloat() / (releaseFrames - 1)
            out[noteOff + i] = samples[start + i % period] * gain
        }
        out[out.lastIndex] = 0f
        checkCancelled()
        return Snip(out, channels = 1, sampleRate = note.snip.sampleRate)
    }

    /** Short real-player presses demonstrate each MIDI window and the chosen key-up fade. */
    private fun touchPreview(spec: Spec, cancelled: () -> Boolean): Snip {
        val notes = renderLayers(spec, spec.patch.midi, cancelled)
        val layers = notes.mapIndexed { index, note ->
            val window = CreaseTouchKeys.VELOCITY_WINDOWS[index]
            InstrumentStore.Layer("touch${index + 1}", note.snip.frameCount.toLong(), note.loopStartFrame,
                window.first, window.last)
        }
        val hard = layers.last()
        val instrument = InstrumentStore.Instrument(spec.patch.name, spec.releaseSeconds,
            listOf(InstrumentStore.Zone(spec.patch.midi, spec.patch.midi, spec.patch.midi,
                hard.sample, hard.frameCount, hard.loopStartFrame, layers)))
        val samples = layers.mapIndexed { index, layer -> layer.sample to notes[index].snip.samples }.toMap()
        val player = InstrumentEngine(InstrumentEngine.Loaded(instrument, Crease.RATE, samples), Crease.RATE)
        val heldFrames = (Crease.RATE * .9f).roundToInt()
        val tailFrames = (Crease.RATE * (spec.releaseSeconds + .12f)).roundToInt()
        val totalFrames = (heldFrames + tailFrames) * layers.size
        val out = FloatArray(totalFrames * 2)
        var frame = 0
        fun append(count: Int) {
            var left = count
            while (left > 0) {
                if (cancelled() || Thread.currentThread().isInterrupted)
                    throw java.util.concurrent.CancellationException("CREASE touch preview cancelled")
                val size = minOf(left, 512)
                val block = FloatArray(size * 2)
                player.render(block, size)
                block.copyInto(out, frame * 2)
                frame += size
                left -= size
            }
        }
        for (window in CreaseTouchKeys.VELOCITY_WINDOWS) {
            // Excitation factors are not MIDI representatives: .35 already selects
            // the middle window. Use each exported window's own midpoint instead.
            val touch = ((window.first + window.last) / 2) / 127f
            check(player.noteOn(spec.patch.midi, touch))
            append(heldFrames)
            player.noteOff(spec.patch.midi)
            append(tailFrames)
        }
        return Snip(out, channels = 2, sampleRate = Crease.RATE)
    }
}
