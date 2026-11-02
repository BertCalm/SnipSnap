package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3Clip
import com.snipsnap.mpc3.Mpc3Note
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.xpm.PadNoteMap
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToLong

/** The actual sixteen-pad, three-zone MPC export used by the musical audition. */
object RimeKitGenerator {
    const val BPM = 104f
    const val DEMO_VELOCITY = .9f
    data class Hit(val beat: Double, val slot: Int)
    val hits = listOf(Hit(0.0,1),Hit(1.0,3),Hit(2.0,4),Hit(3.0,5),
        Hit(4.0,9),Hit(5.0,9),Hit(6.0,10),Hit(7.5,9),Hit(8.0,11),
        Hit(9.0,12),Hit(10.0,13),Hit(11.5,12),Hit(12.0,14),Hit(12.5,14),Hit(13.0,15),Hit(14.0,16))
    data class Bundle(val kit: Kit, val directory: File, val data: File, val archive: File, val findings: List<Finding>)

    @JvmStatic fun main(args: Array<String>) {
        require(args.size <= 1)
        val result = generate(File(args.firstOrNull() ?: "../testkit"))
        println("RIME kit: ${result.archive.absolutePath}; 16 pads, 48 samples, MPC 3 and MPC 2")
    }

    fun generate(root: File, complete: Boolean = false): Bundle {
        root.mkdirs()
        val work = File(root, ".rime-kit-work")
        check(!work.exists() || work.deleteRecursively())
        try {
            val name = if (complete) "SnipSnap Rime Complete" else RimeKits.NAME
            val kit = KitAssembler.assembleArranged(name, if (complete) SynthKits.rimeComplete() else SynthKits.rime(), work,
                key = KeySpec.parse("Cminpent"), tempoBpm = BPM)
            val destination = File(root, name)
            val clip = Mpc3Clip("Rime Four Bars", 4, hits.map {
                Mpc3Note(Mpc3Note.noteFor(it.slot), (it.beat * Mpc3Clip.PULSES_PER_BEAT).roundToLong(), DEMO_VELOCITY)
            })
            val result = Mpc3Exporter.exportTrack(kit, work, destination, overwrite = true, clip = clip, mpc2Twin = true)
            val data = File(destination, Mpc3TrackWriter.trackDataDirName(kit.name))
            val xtd = ExportReadBack.verify(kit, ExportOutcome(ExportFormat.MPC3_TRACK, result.program, data, result.findings))
            val xpm = ExportReadBack.verify(kit, ExportOutcome(ExportFormat.PROGRAM_FOLDER, File(data,"${kit.name}.xpm"), null, result.findings))
            check(!(xtd + xpm).blocked()) { "MPC read-back failed: ${xtd + xpm}" }
            val exported = KitStore.load(data)
            check(exported.pads.size == 16 && exported.pads.all { it.velocityLayers.size == 3 })
            val patches = if (complete) RimeKits.completePatches() else RimeKits.patches()
            val velocities = listOf(RimeKits.SOFT_VELOCITY, RimeKits.MID_VELOCITY, RimeKits.MAIN_VELOCITY)
            val pads = exported.pads.sortedBy { it.slot }.map { pad ->
                val patch = patches[pad.slot-1]
                linkedMapOf("pad" to PadNoteMap.labelForPad(pad.slot), "slot" to pad.slot,
                    "name" to patch.name, "pitchMidi" to patch.midi, "triggerMidi" to Mpc3Note.noteFor(pad.slot),
                    "patch" to patch.toJsonValue(), "zones" to pad.velocityLayers.mapIndexed { index, layer ->
                        mapOf("start" to layer.velStart,"end" to layer.velEnd,"sample" to "${data.name}/${layer.sampleFile}",
                            "patch" to patch.copy(velocity = velocities[index]).toJsonValue(),
                            "normalization" to if(index<2) "peak-match-main" else "native",
                            "sha256" to sha(File(data,layer.sampleFile)))
                    })
            }
            File(destination,"pad-map.json").writeText(Json.write(json(mapOf("engine" to "RIME", "model" to if (complete) Rime.MODEL_VERSION else RimePresets.BANK_MODEL,
                "tempoBpm" to BPM,"pads" to pads,"findings" to (xtd+xpm).map { it.message }))))
            File(destination,"README.txt").writeText("""RIME musical bank — sixteen finite pads

MPC 3: load ${kit.name}.xtd beside its ${data.name}/ folder.
MPC 2: open ${data.name}/${kit.name}.xpm. Keep every WAV beside that program.
The XTD carries an original four-bar pattern; set the MPC project tempo to $BPM BPM
to match the audition. A standalone track does not set project tempo. XPM carries no pattern.

A01–A08: Round Seed, a C-minor-pentatonic bass walk from C2 to F3.
A09–A16: Firm Step, Edged Root, Soft Snow, Hollow Frost, Fine Frost,
Pebble Knock, Ringing Stone, Open Vessel.

Three velocity zones use native .3/.6/.8 strikes. The two soft samples are
peak-matched to the .8 main sample; the MPC velocity curve supplies level.
pad-map.json records each complete patch, zone, matching rule and WAV checksum.
The kit.json inside the data folder keeps the editable SnipSnap pad recipes.
All sounds are dry, finite one-shots. No seamless HOLD or rack effects are used.
MPC 3 and MPC 2 files pass the repository's read-back checks.
Hardware and phone acceptance still require owner listening and loading.
""")
            if (complete) File(destination,"README.txt").writeText("RIME COMPLETE / six voices\n\nSixteen finite pads, each with three native velocity layers, in C minor pentatonic.\nMPC 3: keep ${kit.name}.xtd beside ${data.name}/. MPC 2: load ${data.name}/${kit.name}.xpm.\nSet the MPC project tempo to $BPM BPM to match the included four-bar pattern. The track does not set project tempo.\nThe separate original RIME kit retains the accepted sixteen-pad bank.\npad-map.json records full per-zone recipes and WAV checksums; kit.json retains editable phone recipes.\nHardware and sonic acceptance remain pending.\n")
            val archive = File(root,if (complete) "SnipSnap-Rime-Complete-Kit.zip" else "SnipSnap-Rime-Kit.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                destination.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                    zip.putNextEntry(ZipEntry(destination.name+"/"+file.relativeTo(destination).invariantSeparatorsPath).also { it.time=0 })
                    file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            return Bundle(exported,destination,data,archive,xtd+xpm)
        } finally { work.deleteRecursively() }
    }

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun json(value: Any?): JsonValue = when(value) {
        null -> JsonValue.Null
        is JsonValue -> value
        is String -> JsonValue.Str(value)
        is Number -> JsonValue.Num(value.toDouble())
        is Map<*,*> -> JsonValue.Obj(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonValue.Arr(value.map(::json))
        else -> error("Unsupported kit map value $value")
    }
}
