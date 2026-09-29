package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * Renders VALVE's V1 listening clips
 * (docs/superpowers/specs/2026-09-29-magnet-valve-design.md, "Phasing and
 * gates", the V1 row) under testkit/valve-audition/ (gitignored): four
 * sources - a THUMP kick, a THUMP snare, a VELVET brass stab, a VOX choir -
 * each dry and at DRIVE 0.3 / 0.6 / 1 by CAB 0 / 0.5 / 1, TONE and SAG at
 * their defaults; then the fold-back A/B, the snare at DRIVE 1 and CAB 0.5
 * through VALVE at the snip's rate and at 4x. Every clip shares one loudness
 * ([AuditionLevel]). Writes manifest.json, the one list of clips the page is
 * built from. Run via `./gradlew :synth:generateValveAudition`.
 */
object ValveAuditionGenerator {

    private val DRIVES = listOf(0.3f, 0.6f, 1f)
    private val CABS = listOf(0f, 0.5f, 1f)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/valve-audition")
        root.mkdirs()
        val sources = linkedMapOf(
            "KICK" to Thump.render(ThumpVoice.KICK),
            "SNARE" to Thump.render(ThumpVoice.SNARE),
            "BRASS" to Velvet.render(VelvetVoice.BRASS),
            "CHOIR" to Vox.render(VoxVoice.CHOIR),
        )
        val entries = mutableListOf<String>()
        // AuditionLevel levels a mono buffer; VOX's CHOIR renders in stereo,
        // so a stereo clip is folded by averaging (never summing) first.
        fun mono(s: Snip): Snip =
            if (s.channels == 1) s
            else Snip(FloatArray(s.frameCount) { f -> 0.5f * (s.samples[f * 2] + s.samples[f * 2 + 1]) }, 1, s.sampleRate)
        fun write(source: String, id: String, label: String, snip: Snip) {
            val dir = File(root, source).apply { mkdirs() }
            WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono(snip)), WavWriter.BitDepth.PCM_16)
            entries += """{"source":"$source","id":"$id","file":"$source/$id.wav","label":"$label"}"""
        }
        for ((name, dry) in sources) {
            write(name, "dry", "$name DRY", dry)
            for (drive in DRIVES) {
                for (cab in CABS) {
                    val id = "drive_%02d_cab_%02d".format((drive * 10).toInt(), (cab * 10).toInt())
                    write(name, id, "$name DRIVE $drive CAB $cab", Valve.process(dry, mapOf("DRIVE" to drive, "CAB" to cab)))
                }
            }
        }
        val snare = sources.getValue("SNARE")
        val hot = mapOf("DRIVE" to 1f, "CAB" to 0.5f)
        write("FOLDBACK", "snare_1x", "SNARE DRIVE 1 AT THE SNIP RATE", Valve.process(snare, hot, oversample = false))
        write("FOLDBACK", "snare_4x", "SNARE DRIVE 1 AT 4X", Valve.process(snare, hot, oversample = true))
        File(root, "manifest.json").writeText("{\"clips\":[\n" + entries.joinToString(",\n") + "\n]}\n")
        println("valve audition: ${entries.size} clips under ${root.absolutePath}")
    }
}
