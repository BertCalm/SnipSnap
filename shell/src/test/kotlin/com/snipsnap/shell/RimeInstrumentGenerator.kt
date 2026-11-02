package com.snipsnap.shell

import com.snipsnap.kit.InstrumentStore
import com.snipsnap.synth.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Regenerable native keygroups, separate from the accepted finite drum kit. */
object RimeInstrumentGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.first()).absoluteFile.apply { mkdirs() }
        val complete = args.drop(1) == listOf("--complete")
        require(args.size == 1 || complete)
        for (voice in if (complete) RimeVoice.entries else Rime.LEGACY_VOICES) {
            val stem = "rime-${voice.name.lowercase()}-${if (complete) "model6-" else ""}instrument"
            val folder = File(root, stem).apply { mkdirs() }
            val patch = if (complete) RimePresets.forVoice(voice).single { Rime.isLoop(it.macros) }
                else RimePresets.starter(voice).copy(model = 5, macros = Rime.defaults(voice) + ("HOLD" to 1f))
            val spec = RimePadMaker.Spec(patch)
            val midis = RimePadMaker.zoneMidis(spec)
            val notes = midis.map { midi -> println("RIME instrument $voice/$midi"); RimePadMaker.renderZone(spec, midi) }
            RimePadMaker.export("Rime ${voice.name}", spec, notes, folder, overwrite = true)
            check(InstrumentStore.list(folder).single().second.zones.size == 9)
            val motion = if (Rime.movingHold(patch.macros, patch.model)) "Periodic scale motion repeats through the held body." else "UNFOLD shapes the opening once; the resolved contour then repeats."
            File(folder, "README.txt").writeText("RIME $voice held instrument\n\nNine native mono 44.1-kHz roots: ${midis.joinToString()}.\nThe contour opening develops before sustain. RELEASE is ${spec.releaseSeconds} seconds.\nMPC 3: keep the .xty and its _[TrackData] folder together. MPC 2: load the .xpm inside that folder.\nWAV sampler metadata retains each root and loop marker. rime-patch.json retains model, seed, note, velocity, gate and macros.\nPlayer velocity changes playback gain; this instrument has one authored excitation strength per root.\n$motion\nModel 6 derives the adaptation from saved UNFOLD: 60–100 repeats scale motion, lower values unfold once.\nMPC hardware loading and sonic acceptance remain unverified.\n")
            ZipOutputStream(File(root, "$stem.zip").outputStream().buffered()).use { zip ->
                folder.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        }
    }
}
