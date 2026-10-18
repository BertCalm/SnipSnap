package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the WAH section's gate clips under testkit/wah-audition/ (gitignored), for the owner's
 * gate in docs/superpowers/specs/2026-10-17-wah-section-design.md: the shipped section (not the
 * round-two prototype) on a snare, a choir and both MAGNET guitars. AUTO (the `wahed` character) at
 * the pad sheet's AMT 70 and at 100, TOUCH and ROCK at 100 beside it, and the ends of SPEED and Q
 * (AUTO at 100) on the choir and on each guitar. Each guitar twice: tapped on the baked pad (as the
 * pad sheet does it) and in front of its landing amp (a rig: the wah section added to the landing
 * chain). Every clip shares one loudness ([AuditionLevel]). Writes manifest.json. Run via
 * `./gradlew :synth:generateWahAudition`.
 */
object WahAuditionGenerator {

    private val AMOUNTS = listOf(0.7f, 1f)

    private fun pct(amount: Float) = (amount * 100).roundToInt()

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/wah-audition")
        root.mkdirs()
        root.walkTopDown().filter { it.isFile && (it.extension == "wav" || it.name == "manifest.json") }.forEach { it.delete() }
        val entries = mutableListOf<String>()
        // AuditionLevel meters a stereo clip on its mono fold and keeps both
        // channels, so the fold here is not for levelling: the listening page
        // and the phone play mono, so CHOIR is folded (averaged, never summed)
        // to what the listener would hear.
        fun mono(s: Snip): Snip =
            if (s.channels == 1) s
            else Snip(FloatArray(s.frameCount) { f -> 0.5f * (s.samples[f * 2] + s.samples[f * 2 + 1]) }, 1, s.sampleRate)
        fun write(source: String, id: String, label: String, snip: Snip) {
            check(listOf(source, id, label).none { s -> s.any { it == '"' || it == '\\' || it < ' ' } }) { "manifest field needs escaping: $source / $id / $label" }
            val dir = File(root, source).apply { mkdirs() }
            WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono(snip)), WavWriter.BitDepth.PCM_16)
            entries += """{"source":"$source","id":"$id","file":"$source/$id.wav","label":"$label"}"""
        }
        val ends = listOf(
            "speed_0" to mapOf("AUTO" to 1f, "SPEED" to 0f),
            "speed_100" to mapOf("AUTO" to 1f, "SPEED" to 1f),
            "q_0" to mapOf("AUTO" to 1f, "Q" to 0f),
            "q_100" to mapOf("AUTO" to 1f, "Q" to 1f),
        )
        val plain = linkedMapOf("SNARE" to Thump.render(ThumpVoice.SNARE), "CHOIR" to Vox.render(VoxVoice.CHOIR))
        for ((name, dry) in plain) {
            write(name, "dry", "$name DRY", dry)
            for (amount in AMOUNTS) write(name, "auto_${pct(amount)}", "$name WAH AUTO AT ${pct(amount)}", Treatments.apply("wahed", dry, amount).snip)
            write(name, "touch_100", "$name WAH TOUCH AT 100", Treatments.apply("touchwahed", dry, 1f).snip)
            write(name, "rock_100", "$name WAH ROCK AT 100", Treatments.apply("rockwahed", dry, 1f).snip)
        }
        for ((id, macros) in ends) write("CHOIR", id, "CHOIR WAH AUTO, ${id.uppercase().replace('_', ' ')}", Wah.process(plain.getValue("CHOIR"), macros))
        for (voice in MagnetVoice.values()) {
            val name = voice.name
            val dry = Magnet.render(voice)
            val landing = Magnet.landingChain(voice)
            val baked = landing.process(dry)
            write(name, "baked", "$name AS A KIT PAD (THE DRY ENGINE THROUGH ITS LANDING AMP)", baked)
            for (amount in AMOUNTS) write(name, "sheet_auto_${pct(amount)}", "$name WAH AUTO AT ${pct(amount)}, ON THE BAKED PAD (AS THE PAD SHEET DOES IT)", Treatments.apply("wahed", baked, amount).snip)
            fun rig(id: String, label: String, macros: Map<String, Float>) =
                write(name, id, "$name $label, IN FRONT OF ITS LANDING AMP (A RIG)", landing.withSection("wah", macros).process(dry))
            for (amount in AMOUNTS) rig("rig_auto_${pct(amount)}", "WAH AUTO AT ${pct(amount)}", Treatments.chain("wahed", amount).section("wah")!!)
            rig("rig_touch_100", "WAH TOUCH AT 100", mapOf("TOUCH" to 1f))
            rig("rig_rock_100", "WAH ROCK AT 100", mapOf("ROCK" to 1f))
            for ((id, macros) in ends) rig("rig_$id", "WAH AUTO, ${id.uppercase().replace('_', ' ')}", macros)
        }
        check(entries.size == 36) { "expected 36 clips (SNARE 5, CHOIR 9, two guitars 11 each), wrote ${entries.size}" }
        File(root, "manifest.json").writeText("{\"clips\":[\n" + entries.joinToString(",\n") + "\n]}\n")
        println("wah audition: ${entries.size} clips under ${root.absolutePath}")
    }
}
