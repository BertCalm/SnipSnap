package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.BoroughKits
import com.snipsnap.synth.BoroughKits.Kit
import java.io.File
import java.util.Locale

/**
 * Generates the six BOROUGH kits with their default perc stacks, proves every pad's grid through save, reload,
 * playback and MPC 3 export, writes SFZ and DecentSampler beside them, and writes the listening pack. Every clip is
 * read back from the exported WAV through the app's own pad resolver ([PadHit.resolve]), so what a page plays is
 * what a pad plays.
 *
 * Per kit folder: `pad-NN.wav` (the hard first take), `touch-NN.wav` (soft, medium, hard, each zone's three takes
 * in order), `mpc/`, `sfz/`, `decentsampler/`, `native/` and `kit-source.json`. At the root: `verification.csv`,
 * `metrics.csv`, `manifest.json`, `README.txt`. MPC 2 is not written: its projection keeps each zone's first take
 * (the owner's format limit of 2026-11-14).
 */
object BoroughKitGenerator {
    private const val RATE = 44100

    @JvmStatic fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/borough-kits").absoluteFile.apply { deleteRecursively(); mkdirs() }
        val proof = StringBuilder("kit,pad,name,velocity_zones,takes_per_zone,saved_pcm,playback_cycles,mpc3_track\n")
        val metrics = StringBuilder("kit,pad,name,class,choke,seconds,build_ms\n")
        val manifest = StringBuilder("{\n  \"kitVersion\": ${BoroughKits.VERSION},\n  \"kits\": [\n")
        val takes = BoroughKits.TAKES
        for ((k, g) in Kit.entries.withIndex()) {
            val folder = File(root, g.id).apply { mkdirs() }
            val staging = File(folder, "native")
            val began = System.nanoTime()
            val pads = BoroughKits.build(g)
            val buildMs = (System.nanoTime() - began) / 1_000_000
            val kit = KitAssembler.assembleArranged(g.title, pads, staging, tempoBpm = g.bpm.toFloat(), glue = BoroughKits.glueOf(g))
            val exported = Mpc3Exporter.exportTrack(kit, staging, File(folder, "mpc"), overwrite = true)
            val data = File(folder, "mpc/${Mpc3TrackWriter.trackDataDirName(kit.name)}")
            com.snipsnap.kit.SfzWriter.write(kit, staging, File(folder, "sfz"), overwrite = true)
            com.snipsnap.kit.DecentSamplerWriter.write(kit, staging, File(folder, "decentsampler"), overwrite = true)
            val saved = KitStore.load(staging)
            check(saved == kit) { "${g.title}: the reloaded kit differs from the one saved" }
            check(saved.glue == BoroughKits.glueOf(g)) { "${g.title}: the saved kit lost its live glue" }
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
            fun write(name: String, x: FloatArray) = WavWriter.write(File(folder, name), Snip(x, 1, RATE))

            manifest.append("    {\"id\": \"${g.id}\", \"title\": \"${g.title}\", \"bpm\": ${g.bpm}, \"stack\": \"${g.defaultStack.id}\", \"pads\": [\n")
            for (pad in saved.pads) {
                val n = String.format(Locale.ROOT, "%02d", pad.slot)
                val hard = play(pad.slot, 1f, 0)
                write("pad-$n.wav", hard)
                val clips = BoroughKits.velocities.flatMap { v -> List(takes) { t -> play(pad.slot, v, t) } }
                val stride = clips.maxOf { it.size } + RATE / 8
                val strip = FloatArray(stride * clips.size)
                clips.forEachIndexed { i, x -> x.copyInto(strip, i * stride) }
                write("touch-$n.wav", strip)
                val seconds = String.format(Locale.ROOT, "%.3f", hard.size / RATE.toDouble())
                proof.append("${g.id},${pad.slot},${pad.displayName},3,$takes,verified,2,verified\n")
                metrics.append("${g.id},${pad.slot},${pad.displayName},${pad.drumClass},${pad.muteGroup},$seconds,$buildMs\n")
                manifest.append("      {\"slot\": ${pad.slot}, \"name\": \"${pad.displayName}\", \"class\": \"${pad.drumClass}\", \"choke\": ${pad.muteGroup}, \"seconds\": $seconds}")
                    .append(if (pad.slot == saved.pads.size) "\n" else ",\n")
            }
            manifest.append("    ]}").append(if (k == Kit.entries.lastIndex) "\n" else ",\n")
            File(folder, "kit-source.json").writeText(File(staging, KitStore.FILE_NAME).readText())
            println("Verified ${g.title}: ${BoroughKits.PADS} pads, ${BoroughKits.PADS * 3 * takes} slices, saved PCM + playback + MPC 3 track + SFZ + DecentSampler, built in $buildMs ms")
        }
        manifest.append("  ]\n}\n")
        File(root, "README.txt").writeText("BOROUGH kits, version ${BoroughKits.VERSION}: six boom-bap drum kits generated from the accepted recipes, each with its default perc stack on A13-A16; three velocity zones of three takes per pad.\n" +
            "MPC 3, SFZ and DecentSampler exports cycle takes. MPC 2 is not written: it keeps each zone's first take (the owner's format-limit decision of 2026-11-14).\n" +
            "Each kit's kit-source.json carries its live glue (\"glue\": input trim, saturation, compressor, limiter), which the app plays when the kit loads. " +
            "It is not printed into the pads, and the MPC 3, SFZ and DecentSampler exports do not apply it. Nothing here has been played on a phone or an MPC.\n")
        File(root, "verification.csv").writeText(proof.toString())
        File(root, "metrics.csv").writeText(metrics.toString())
        File(root, "manifest.json").writeText(manifest.toString())
    }
}
