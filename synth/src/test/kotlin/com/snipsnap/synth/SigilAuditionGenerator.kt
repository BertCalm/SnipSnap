package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Renders every SIGIL pad in Kotlin at its three zones and four takes, in the prototype's layout
 * (`<kit>/<NN>_<Pad_Name>/z<zone>_t<take>.wav`, the groove as `<kit>/groove.wav` at the hard zone and first take), so
 * `prototype/sigil/compare_kotlin.py` can measure the port against the approved prototype pad by pad and the listening
 * page can play the app's own renders. 24-bit WAVs, not levelled again. Render times go to `timings.csv` (ms, one
 * machine, four threads). Run via `./gradlew :synth:generateSigilAudition`.
 */
object SigilAuditionGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/sigil-audition/kotlin").apply { deleteRecursively(); mkdirs() }
        val pool = Executors.newFixedThreadPool(4)
        val jobs = ArrayList<Future<String>>()
        for (kit in SigilKits.Kit.entries) {
            val names = SigilKits.roles(kit)
            for (role in 0 until SigilKits.PADS) {
                for ((z, v) in SigilKits.velocities.withIndex()) for (t in 0 until SigilKits.TAKES) {
                    if (role == SigilKits.GROOVE && (z < 2 || t > 0)) continue
                    jobs += pool.submit<String> {
                        val t0 = System.nanoTime()
                        val snip = SigilKits.render(kit, role, v, t)
                        val ms = (System.nanoTime() - t0) / 1e6
                        val file = if (role == SigilKits.GROOVE) File(root, "${kit.jsonId}/groove.wav")
                        else File(root, "${kit.jsonId}/${"%02d".format(role + 1)}_${names[role].replace(' ', '_')}/z${z}_t$t.wav")
                        file.parentFile.mkdirs()
                        WavWriter.write(file, snip, WavWriter.BitDepth.PCM_24)
                        "${kit.jsonId},${role + 1},${names[role]},$z,$t,${"%.1f".format(ms)}"
                    }
                }
            }
        }
        val rows = jobs.map { it.get() }
        pool.shutdown()
        File(root, "timings.csv").writeText("kit,pad,name,zone,take,ms\n" + rows.joinToString("\n") + "\n")
        println("SIGIL audition: ${rows.size} renders under $root")
    }
}
