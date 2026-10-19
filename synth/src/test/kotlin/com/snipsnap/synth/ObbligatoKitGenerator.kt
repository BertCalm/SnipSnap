package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.GrooveStore
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitPreview
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.PadNoteMap
import com.snipsnap.xpm.WavInfo
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Export the model-five duet as sixteen editable pads, MPC 3, an MPC 2 twin and a ZIP. */
object ObbligatoKitGenerator {
    const val DEMO_BPM = 120f
    const val DEMO_VELOCITY = .9f
    const val DEMO_FILE = "Obbligato Demo.wav"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1) { "Usage: ObbligatoKitGenerator [output-directory]" }
        val root = File(args.firstOrNull() ?: "../testkit").absoluteFile
        check(root.isDirectory || root.mkdirs()) { "Could not create $root" }
        val work = File(root, ".obbligato-work")
        val destination = File(root, ObbligatoKits.NAME)
        check(!work.exists() || work.deleteRecursively()) { "Could not clear $work" }
        try {
            val kit = KitAssembler.assembleArranged(
                ObbligatoKits.NAME, SynthKits.obbligato(), work,
                key = KeySpec.parse("Cminpent"), tempoBpm = DEMO_BPM,
            )
            val demo = demoClip(kit, work)
            val result = Mpc3Exporter.exportTrack(kit, work, destination,
                overwrite = true, mpc2Twin = true, clip = demo)
            // A kit's sample references are bare filenames: keep its recipes beside the
            // shared WAVs and MPC 2 program, where the app can load the same directory.
            val data = File(destination, Mpc3TrackWriter.trackDataDirName(kit.name))
            KitStore.save(kit, data)
            GrooveStore.save(data, listOf(demo))
            // KitPreview currently reads the main sample, not velocity-layer WAVs.
            // Every clip note therefore deliberately selects the main zone on hardware.
            WavWriter.write(File(destination, DEMO_FILE), KitPreview.render(kit, data, demo))
            writePadMap(kit, destination, data, demo)
            File(destination, "README.txt").writeText(readme(data.name))

            val archive = File(root, "SnipSnap-Obbligato-Kit.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                destination.walkTopDown().filter { it.isFile }
                    .sortedBy { it.relativeTo(destination).invariantSeparatorsPath }
                    .forEach { file ->
                        val path = destination.name + "/" + file.relativeTo(destination).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(path).also { it.time = 0L })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            println("Wrote ${result.program.absolutePath}, ${result.samples.size} samples, MPC 2 twin, recipes, pad map, demo and ${archive.absolutePath}")
        } finally {
            work.deleteRecursively()
        }
    }

    /** Four notes per relationship row; complete one-shot tails overlap deliberately. */
    fun demoClip(kit: Kit, kitDir: File): Mpc3Clip {
        require(kit.pads.map { it.slot }.sorted() == (1..16).toList()) { "the duet demo needs all sixteen factory pads" }
        val notes = kit.pads.sortedBy { it.slot }.map { pad ->
            val row = (pad.slot - 1) / 4
            val column = (pad.slot - 1) % 4
            val sample = WavInfo.read(File(kitDir, pad.sampleFile))
            val length = (sample.frameCount.toDouble() / sample.sampleRate *
                DEMO_BPM / 60.0 * Mpc3Clip.PULSES_PER_BEAT).roundToLong().coerceAtLeast(1L)
            Mpc3Note(
                note = Mpc3Note.noteFor(pad.slot),
                timePulses = (row * 16L + column * 3L) * Mpc3Clip.PULSES_PER_BEAT,
                velocity = DEMO_VELOCITY,
                lengthPulses = length,
            )
        }
        val lastEnd = notes.maxOf { it.timePulses + it.lengthPulses }
        val bars = ((lastEnd + Mpc3Clip.PULSES_PER_BAR - 1) / Mpc3Clip.PULSES_PER_BAR).toInt()
        return Mpc3Clip("Obbligato Row Walk", bars, notes)
    }

    private fun writePadMap(kit: Kit, destination: File, data: File, demo: Mpc3Clip) {
        fun num(value: Number) = JsonValue.Num(value.toDouble())
        val velocities = listOf(ObbligatoKits.SOFT_VELOCITY, ObbligatoKits.MID_VELOCITY, ObbligatoKits.MAIN_VELOCITY)
        val pads = kit.pads.sortedBy { it.slot }.map { pad ->
            val patch = PadRecipe.fromJsonValue(requireNotNull(pad.recipe)).patch as ObbligatoPatch
            JsonValue.Obj(linkedMapOf(
                "slot" to num(pad.slot),
                "pad" to JsonValue.Str(PadNoteMap.labelForPad(pad.slot)),
                "name" to JsonValue.Str(pad.displayName),
                "pitchMidi" to num(patch.midi),
                "triggerMidi" to num(Mpc3Note.noteFor(pad.slot)),
                "model" to num(patch.model),
                "seed" to num(patch.seed),
                "sample" to JsonValue.Str("${data.name}/${pad.sampleFile}"),
                "zones" to JsonValue.Arr(pad.velocityLayers.mapIndexed { index, layer ->
                    JsonValue.Obj(linkedMapOf(
                        "velocityStart" to num(layer.velStart), "velocityEnd" to num(layer.velEnd),
                        "nativeVelocity" to num(velocities[index]),
                        "sample" to JsonValue.Str("${data.name}/${layer.sampleFile}"),
                    ))
                }),
            ))
        }
        val value = JsonValue.Obj(linkedMapOf(
            "schemaVersion" to num(1),
            "kit" to JsonValue.Str(kit.name),
            "rendererModel" to num(ObbligatoKits.MODEL),
            "key" to JsonValue.Str("C minor pentatonic"),
            "pads" to JsonValue.Arr(pads),
            "demo" to JsonValue.Obj(linkedMapOf(
                "audio" to JsonValue.Str(DEMO_FILE),
                "clip" to JsonValue.Str(demo.name),
                "sequence" to JsonValue.Str("${data.name}/${GrooveStore.FILE_NAME}"),
                "tempoBpm" to num(DEMO_BPM),
                "midiVelocity" to num((DEMO_VELOCITY * 127).roundToInt()),
                "nativeVelocity" to num(ObbligatoKits.MAIN_VELOCITY),
                "zone" to JsonValue.Str("main only"),
            )),
        ))
        File(destination, "pad-map.json").writeText(Json.write(value) + "\n")
    }

    private fun readme(dataDirectory: String): String = """
        ${ObbligatoKits.NAME}

        Sixteen dry, finite model-five duets. Each pad starts independent air and reed
        sources; pads do not share a live relationship. HOLD is not a sustain loop.

        Pad rows (left to right: C4, Eb4, F4, G4):
        A01-A04 Harmony    A05-A08 Play    A09-A12 Fight    A13-A16 Protect
        The complete machine-readable assignments are in pad-map.json.

        MPC 3: open ${ObbligatoKits.NAME}.xtd with $dataDirectory beside it.
        MPC 2: open the .xpm inside $dataDirectory.
        SnipSnap: the same directory holds kit.json, full recipes and all sample WAVs.
        Keep each program with its sample folder when copying or extracting the ZIP.

        Three native strikes per pad: 0.3 for MIDI 1-41, 0.6 for 42-84, and the
        accepted 0.8 strike for 85-127. Soft layers are peak-matched to the main
        strike; the host's velocity curve controls playback level. The exact main
        recipe and soft-strike provenance are saved in kit.json.

        $DEMO_FILE plays all sixteen pads at 120 BPM and MIDI velocity 114, so every
        note uses the main zone. This is a main-zone performance, not a velocity demo.
        Four notes play per row, 1.5 seconds apart; 4.1-second gestures intentionally
        overlap. Rows start eight seconds apart, with a brief overlap at each boundary.
        The embedded MPC 3 clip and groove.json leave room for the final finite ending.
        The WAV uses the standard KitPreview mix and headroom limiter, without rack FX.
    """.trimIndent() + "\n"
}
