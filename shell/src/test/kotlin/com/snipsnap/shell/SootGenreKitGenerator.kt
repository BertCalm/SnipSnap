package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.SootGenreKits
import com.snipsnap.synth.SootGenreKits.Genre
import java.io.File
import java.util.Locale

/**
 * Builds SOOT's four sibling kits, proves every grid through save, reload, playback and MPC 3 export
 * (GATECRASH's proof, as SOOT's), and writes the listening pack: each pad's hard first take, the roll
 * and the four bars, all read back from the exported WAVs through the app's own pad resolver.
 */
object SootGenreKitGenerator {
    private const val RATE = 44100

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/soot-genre-kits").absoluteFile.apply { deleteRecursively(); mkdirs() }
        val clips = mutableListOf<String>()
        val proof = StringBuilder("kit,pad,name,class,velocity_zones,takes_per_zone,saved_pcm,exported_pcm,playback_cycles,mpc3_track,build_ms\n")
        for (g in Genre.entries) {
            val folder = File(root, g.id)
            val staging = File(folder, "native")
            val began = System.nanoTime()
            val pads = SootGenreKits.build(g)
            val buildMs = (System.nanoTime() - began) / 1_000_000
            val kit = KitAssembler.assembleArranged("Soot ${g.title}", pads, staging, tempoBpm = g.bpm.toFloat())
            val exported = Mpc3Exporter.exportTrack(kit, staging, File(folder, "mpc"), overwrite = true)
            val data = File(folder, "mpc/${Mpc3TrackWriter.trackDataDirName(kit.name)}")
            val saved = KitStore.load(staging)
            check(saved == kit) { "${g.title}: the reloaded kit differs from the one saved" }
            TriadKitProof.verify(saved, staging, pads)
            TriadKitProof.verify(saved, data, pads)
            TriadKitProof.verifyTrack(exported.program, saved)
            for (pad in saved.pads) {
                val wav = WavReader.read(File(data, pad.sampleFile))
                val h = requireNotNull(PadHit.resolve(pad, 1f, 0) { wav.frameCount.toLong() })
                val x = wav.samples.copyOfRange(h.startFrame.toInt(), h.endFrameExclusive.toInt())
                val n = String.format(Locale.ROOT, "a%02d", pad.slot)
                WavWriter.write(File(root, "listen/${g.id}/$n.wav"), Snip(x, 1, RATE), WavWriter.BitDepth.PCM_16)
                clips += "  {\"section\": \"${g.id}\", \"id\": \"$n\", \"label\": \"A${n.drop(1)} ${pad.displayName}\", \"file\": \"listen/${g.id}/$n.wav\", \"seconds\": ${String.format(Locale.ROOT, "%.3f", x.size / RATE.toDouble())}}"
                proof.append("${g.id},${pad.slot},${pad.displayName},${pad.drumClass},3,4,verified,verified,2,verified,$buildMs\n")
            }
            println("Verified SOOT ${g.title}: 16 pads, 192 slices, saved PCM + exported PCM + playback + MPC 3 track; built in $buildMs ms")
        }
        File(root, "manifest.json").writeText("{\n \"kitVersion\": ${SootGenreKits.VERSION},\n \"clips\": [\n" + clips.joinToString(",\n") + "\n ]\n}\n")
        File(root, "verification.csv").writeText(proof.toString())
    }
}
