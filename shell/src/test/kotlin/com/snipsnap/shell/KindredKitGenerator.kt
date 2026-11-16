package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.audio.WavWriter.MPC_SAMPLE_RATE as RATE
import com.snipsnap.synth.KindredKits
import com.snipsnap.synth.KindredKits.Kit
import java.io.File
import java.util.Locale

/**
 * Builds the four KINDRED kits, proves every grid through save, reload, the pad resolver and the MPC 3 track read
 * back from the files it wrote, and writes a listening pack under testkit/kindred-kits/ (gitignored), every clip
 * cut from the MPC 3 export through the pad resolver: the hard first take of every pad, a soft-to-hard strip of
 * all twelve strikes of every pad, every single slice (`slices/pad-NN-zZ-tT.wav`, for the groove the listening
 * page builds), and `manifest.json` with each pad's mixer level. Run via `./gradlew :shell:generateKindredKits`.
 */
object KindredKitGenerator {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/kindred-kits").absoluteFile.apply { deleteRecursively(); mkdirs() }
        val proof = StringBuilder("kit,pad,name,velocity_zones,takes_per_zone,saved_pcm,playback_cycles,mpc3_track\n")
        val manifest = StringBuilder("{\n  \"kitVersion\": ${KindredKits.VERSION},\n  \"kits\": [\n")
        for ((k, g) in Kit.entries.withIndex()) {
            val folder = File(root, g.id).apply { mkdirs() }
            val slices = File(folder, "slices").apply { mkdirs() }
            val staging = File(folder, "native")
            val began = System.nanoTime()
            val pads = KindredKits.build(g)
            val buildMs = (System.nanoTime() - began) / 1_000_000
            val kit = KitAssembler.assembleArranged("Kindred ${g.title}", pads, staging, tempoBpm = g.bpm.toFloat())
            val exported = Mpc3Exporter.exportTrack(kit, staging, File(folder, "mpc"), overwrite = true)
            val data = File(folder, "mpc/${Mpc3TrackWriter.trackDataDirName(kit.name)}")
            val saved = KitStore.load(staging)
            check(saved == kit) { "${g.title}: the reloaded kit differs from the one saved" }
            TriadKitProof.verify(saved, staging, pads)
            TriadKitProof.verify(saved, data, pads)
            TriadKitProof.verifyTrack(exported.program, saved)

            val waves = saved.pads.associate { it.slot to WavReader.read(File(data, it.sampleFile)) }
            fun play(slot: Int, velocity: Float, index: Int): FloatArray {
                val pad = saved.pads.single { it.slot == slot }
                val wav = waves.getValue(slot)
                val h = requireNotNull(PadHit.resolve(pad, velocity, index) { wav.frameCount.toLong() })
                return wav.samples.copyOfRange(h.startFrame.toInt(), h.endFrameExclusive.toInt())
            }
            fun write(dir: File, name: String, x: FloatArray) = WavWriter.write(File(dir, name), Snip(x, 1, RATE))

            manifest.append("    {\"id\": \"${g.id}\", \"title\": \"${g.title}\", \"bpm\": ${g.bpm}, \"buildMs\": $buildMs, \"pads\": [\n")
            for (pad in saved.pads) {
                val n = String.format(Locale.ROOT, "%02d", pad.slot)
                val hard = play(pad.slot, 1f, 0)
                write(folder, "pad-$n.wav", hard)
                val clips = KindredKits.velocities.flatMapIndexed { z, v -> List(KindredKits.TAKES) { t -> play(pad.slot, v, t).also { write(slices, "pad-$n-z$z-t$t.wav", it) } } }
                val stride = clips.maxOf { it.size } + RATE / 8
                val strip = FloatArray(stride * clips.size)
                clips.forEachIndexed { i, x -> x.copyInto(strip, i * stride) }
                write(folder, "touch-$n.wav", strip)
                proof.append("${g.id},${pad.slot},${pad.displayName},3,4,verified,2,verified\n")
                manifest.append("      {\"slot\": ${pad.slot}, \"name\": \"${pad.displayName}\", \"class\": \"${pad.drumClass}\", ")
                    .append("\"level\": ${String.format(Locale.ROOT, "%.4f", pad.level)}, \"mute\": ${pad.muteGroup}, ")
                    .append("\"seconds\": ${String.format(Locale.ROOT, "%.3f", hard.size / RATE.toDouble())}}")
                    .append(if (pad.slot == saved.pads.size) "\n" else ",\n")
            }
            manifest.append("    ]}").append(if (k == Kit.entries.lastIndex) "\n" else ",\n")
            File(folder, "kit-source.json").writeText(File(staging, KitStore.FILE_NAME).readText())
            println("Verified ${g.title}: ${saved.pads.size} pads, ${saved.pads.size * 12} slices, saved PCM + playback + MPC 3 track, built in $buildMs ms")
        }
        manifest.append("  ]\n}\n")
        File(root, "verification.csv").writeText(proof.toString())
        File(root, "manifest.json").writeText(manifest.toString())
    }
}
