package com.snipsnap.synth

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.xpm.Keygroup
import com.snipsnap.xpm.KeygroupProgram
import com.snipsnap.xpm.VelocityLayer
import java.io.File

/**
 * The S5 instrument suite: four playable key instruments rendered from the
 * synth engines at exact MIDI pitch, multisampled every minor third across
 * two octaves, packaged as keygroup programs.
 *
 * | Instrument | Engine | Range | Velocity | Sustain |
 * |---|---|---|---|---|
 * | SnipSnap EP | TINES ratio-1 + tine ping | F2–F4 | two true renders — soft is darker | decays |
 * | SnipSnap Organ | TONEWHEEL SOUL, held | A2–A4 | none (organs aren't) | **looped** — holds forever |
 * | SnipSnap Harp | PLUCK HARP | E3–E5 | soft layer | decays |
 * | SnipSnap Music Box | TINES 3.5-ratio twins | C4–C6 | none (one dynamic, wistful) | decays |
 *
 * Each `render*` writes its WAVs into [dir] and returns the program; the
 * generator packages them dual-generation (`.xty` beside `_[TrackData]/`
 * with the MPC 2 `.xpm` twin inside, the Timeless Glow layout).
 */
object InstrumentSuite {

    private const val ZONE_STEP = 3

    fun renderEp(dir: File): KeygroupProgram = build("SnipSnap EP", dir, low = 41, count = 9) { midi, stem ->
        listOf(
            Layered("${stem}_soft", Keys.ep(midi, bright = 0.35f), 1, 63),
            Layered(stem, Keys.ep(midi, bright = 0.8f), 64, 127),
        )
    }

    fun renderOrgan(dir: File): KeygroupProgram = build("SnipSnap Organ", dir, low = Keys.ORGAN_LOW_MIDI, count = 9, release = 0.3f) { midi, stem ->
        val note = Keys.organ(midi)
        listOf(Layered(stem, note.snip, 0, 127, note.loopStartFrame))
    }

    fun renderHarp(dir: File): KeygroupProgram = build("SnipSnap Harp", dir, low = Keys.HARP_LOW_MIDI, count = 9) { midi, stem ->
        val main = Keys.harp(midi)
        listOf(
            Layered("${stem}_soft", Velocity.soften(main, 0.6f), 1, 63),
            Layered(stem, main, 64, 127),
        )
    }

    fun renderMusicBox(dir: File): KeygroupProgram = build("SnipSnap Music Box", dir, low = 60, count = 9) { midi, stem ->
        listOf(Layered(stem, Keys.musicBox(midi), 0, 127))
    }

    fun renderAll(dir: File): List<KeygroupProgram> =
        listOf(renderEp(dir), renderOrgan(dir), renderHarp(dir), renderMusicBox(dir))

    private class Layered(
        val stem: String,
        val snip: Snip,
        val velStart: Int,
        val velEnd: Int,
        val loopStartFrame: Long = 0,
    )

    private fun build(
        name: String,
        dir: File,
        low: Int,
        count: Int,
        release: Float = 0.5f,
        renderZone: (midi: Int, stem: String) -> List<Layered>,
    ): KeygroupProgram {
        dir.mkdirs()
        val prefix = name.removePrefix("SnipSnap ").replace(" ", "")
        val midis = (0 until count).map { low + it * ZONE_STEP }
        val keygroups = midis.mapIndexed { index, midi ->
            val stem = "${prefix}_${Scales.nameOf(midi)}"
            val layers = renderZone(midi, stem).map { layer ->
                WavWriter.write(File(dir, "${layer.stem}.wav"), layer.snip)
                VelocityLayer(
                    sampleName = layer.stem,
                    frameCount = layer.snip.samples.size.toLong() / layer.snip.channels,
                    velStart = layer.velStart,
                    velEnd = layer.velEnd,
                    loopStartFrame = layer.loopStartFrame,
                )
            }
            Keygroup(
                lowNote = (if (index == 0) midi - 9 else midi - 1).coerceIn(0, 127),
                highNote = (if (index == midis.lastIndex) midi + 9 else midi + 1).coerceIn(0, 127),
                rootNote = midi,
                layers = layers,
            )
        }
        return KeygroupProgram(name, keygroups, volumeRelease = release)
    }
}
