package com.snipsnap.synth

import com.snipsnap.audio.*
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

/** Native listening evidence and two fixed three-zone kits in MPC 3 and MPC 2 formats. */
object VelourKitGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/velour-kit").absoluteFile.apply { mkdirs() }
        val html = StringBuilder("""<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Velour · Native kits</title><style>body{max-width:960px;margin:36px auto;padding:0 20px;background:#181c20;color:#eee8de;font:17px/1.6 system-ui}h1{color:#e5b47a}article{background:#252b30;padding:16px;margin:14px 0;border-radius:10px}audio{width:100%}a{color:#a7dbca}small{color:#bec5c9}</style><h1>Velour · Native kits</h1><p>The selected C sources and both treatments now run through SnipSnap's Kotlin kit pipeline. The new hats, toms, stick, shaker and ride are candidates for your next listen.</p><p>These are fixed rendered sample kits with three touch zones. The downloadable kits contain both MPC formats. Hardware playback is unverified. Per-pad effects do not recreate the shared-bus interaction of the original audition.</p>""")
        val dry = VelourTest.fixture("dry").samples; val send = VelourTest.fixture("send").samples
        val allReports = mutableListOf<String>()
        for (t in Velour.Treatment.entries) {
            val id = t.name.lowercase(java.util.Locale.ROOT)
            val name = if (t == Velour.Treatment.VELOUR) "Velour" else "Velour Bloom"
            val start = System.nanoTime()
            val native = Velour.renderBus(dry, send, VelourTest.onsets(), t)
            val expected = VelourTest.fixture(if (t == Velour.Treatment.VELOUR) "velour" else "bloom")
            val errorDb = 20 * log10(VelourTest.relativeError(native.samples, expected.samples))
            check(errorDb < -80) { "Native parity failed: $errorDb dB" }
            // Shared gain for the native/reference pair. Presentation only, never baked into kit sources.
            val rms = sqrt(native.samples.sumOf { it.toDouble().pow(2) } / native.frameCount)
            val gain = minOf(.1 / rms, .92 / maxOf(native.peak(), expected.peak()))
            fun matched(s: Snip) = s.copy(samples = FloatArray(s.samples.size) { (s.samples[it] * gain).toFloat() })
            WavWriter.write(File(root, "$id-native.wav"), matched(native))
            WavWriter.write(File(root, "$id-reference.wav"), matched(expected))
            html.append("<h2>$name</h2><article><h3>Approved Python reference</h3><audio controls preload=none src='$id-reference.wav'></audio><h3>Native Kotlin</h3><audio controls preload=none src='$id-native.wav'></audio></article>")
            val pads = VelourKits.build(t)
            val folder = File(root, id).apply { mkdirs() }; val staging = File(folder, ".work")
            try {
                val kit = KitAssembler.assembleArranged(name, pads, staging, tempoBpm = 84f)
                val export = Mpc3Exporter.exportTrack(kit, staging, folder, overwrite = true, mpc2Twin = true)
                val data = File(folder, Mpc3TrackWriter.trackDataDirName(name))
                val findings = ExportReadBack.verify(kit, ExportOutcome(ExportFormat.MPC3_TRACK, export.program, data, export.findings)) +
                    ExportReadBack.verify(kit, ExportOutcome(ExportFormat.PROGRAM_FOLDER, File(data, "$name.xpm"), null, export.findings))
                check(!findings.blocked()) { findings.toString() }
                val saved = KitStore.load(data)
                check(saved.pads.size == 16 && saved.pads.all { it.velocityLayers.size == 3 })
                html.append("<p><a href='$id-kit.zip'>Download $name MPC kit</a></p><details><summary>Hear the sixteen exported pads</summary>")
                for (pad in saved.pads.sortedBy { it.slot }) {
                    val expectedZones = pads[pad.slot - 1].softVariants + pads[pad.slot - 1].snip
                    for ((zone, layer) in pad.velocityLayers.withIndex()) {
                        val actual = WavReader.read(File(data, layer.sampleFile))
                        val ref = expectedZones[zone]
                        check(actual.frameCount == ref.frameCount && actual.sampleRate == 44100 && actual.channels == 1)
                        check(actual.samples.indices.maxOf { abs(actual.samples[it] - ref.samples[it]) } < 2e-7f)
                    }
                    val file = File(data, pad.sampleFile)
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    html.append("<article><h3>A${pad.slot.toString().padStart(2, '0')} · ${pads[pad.slot - 1].displayName}</h3><audio controls preload=none src='$relative'></audio></article>")
                }
                html.append("</details>")
                allReports += "$name: native/reference relative RMS error $errorDb dB; export read-back passed; 16 pads, 48 PCM24 samples; render/export ${(System.nanoTime() - start) / 1e9} s (cloud JVM, not phone)."
            } finally { staging.deleteRecursively() }
            ZipOutputStream(File(root, "$id-kit.zip").outputStream().buffered()).use { zip ->
                for (f in folder.walkTopDown().filter { it.isFile }.sortedBy { it.path }) {
                    zip.putNextEntry(ZipEntry(f.relativeTo(folder).invariantSeparatorsPath).apply { time = 0L })
                    f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        }
        File(root, "verification.txt").writeText(allReports.joinToString("\n"))
        html.append("<p><a href='verification.txt'>Native parity and export checks</a></p><script>document.querySelectorAll('audio').forEach(p=>p.addEventListener('play',()=>document.querySelectorAll('audio').forEach(q=>{if(p!==q)q.pause()})))</script></html>")
        File(root, "index.html").writeText(html.toString())
        println(allReports.joinToString("\n"))
        println("Wrote $root")
    }
}
