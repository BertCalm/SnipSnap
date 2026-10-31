package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders the fade law's listen under testkit/fade-law-audition/ (gitignored): the DRIVE chip
 * (`driven`) at the pad sheet's default AMT 0.7 two ways, the old linear walk of the knob and the
 * new LOG walk of its gain in decibels, beside the dry pad and DRIVE at AMT 1, on a snare, a choir
 * and both MAGNET guitar pads as the pad sheet does them (on the baked audio). Both ways go through
 * the same door (the pre-amp end fade ahead of the amp, its length following the amp's lift), so
 * the only difference is the law. Every clip shares one loudness ([AuditionLevel]), so the listen
 * compares character, not level. Prints the gain and THD each way. Run via
 * `./gradlew :synth:generateFadeLawAudition`
 * (docs/superpowers/specs/2026-10-21-pedal-board-benchmark.md, finding F2).
 */
object FadeLawAuditionGenerator {

    private const val SHEET_AMT = 0.7f

    private fun mono(s: Snip): Snip =
        if (s.channels == 1) s
        else Snip(FloatArray(s.frameCount) { f -> 0.5f * (s.samples[f * 2] + s.samples[f * 2 + 1]) }, 1, s.sampleRate)

    /** The chip's render at [chain] (faded) against [full], through the same door `Treatments.apply` uses. */
    private fun drive(snip: Snip, chain: FxChain, full: FxChain): Snip = chain.process(Treatments.endFaded(snip, chain, full))

    private fun thd(out: Snip): Double {
        val rate = out.sampleRate
        val start = rate / 10
        val n = rate / 2
        fun magnitude(h: Int): Double {
            val w = 2.0 * PI * 220.0 * h / rate
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val x = out.samples[start + i].toDouble()
                re += x * cos(w * i)
                im += x * sin(w * i)
            }
            return sqrt(re * re + im * im)
        }
        var sum = 0.0
        for (h in 2..8) magnitude(h).let { sum += it * it }
        return 100.0 * sqrt(sum) / magnitude(1)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/fade-law-audition")
        root.walkTopDown().filter { it.isFile && (it.extension == "wav" || it.name == "manifest.json") }.forEach { it.delete() }
        root.mkdirs()
        val full = Treatments.chain("driven", 1f)
        val log = Treatments.fade(full, SHEET_AMT)
        val linear = Treatments.linearFade(full, SHEET_AMT)

        val entries = mutableListOf<String>()
        fun write(source: String, id: String, label: String, snip: Snip) {
            val dir = File(root, source).apply { mkdirs() }
            WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(mono(snip)), WavWriter.BitDepth.PCM_16)
            entries += """{"source":"$source","id":"$id","file":"$source/$id.wav","label":"$label"}"""
        }
        val sources = linkedMapOf(
            "SNARE" to Thump.render(ThumpVoice.SNARE),
            "CHOIR" to Vox.render(VoxVoice.CHOIR),
        )
        for (voice in MagnetVoice.values()) sources[voice.name] = Magnet.landingChain(voice).process(Magnet.render(voice))
        for ((name, dry) in sources) {
            write(name, "dry", "$name DRY", dry)
            write(name, "drive_70_linear", "$name DRIVE AMT 70, THE OLD WALK (THE KNOB, LINEARLY)", drive(dry, linear, full))
            write(name, "drive_70_log", "$name DRIVE AMT 70, THE NEW WALK (THE GAIN, IN DECIBELS)", drive(dry, log, full))
            write(name, "drive_100", "$name DRIVE AMT 100 (BOTH WALKS AGREE HERE)", drive(dry, full, full))
        }
        check(entries.size == 16) { "expected 16 clips (4 sources x 4), wrote ${entries.size}" }
        File(root, "manifest.json").writeText("{\"clips\":[\n" + entries.joinToString(",\n") + "\n]}\n")

        val rate = 44_100
        val sine = Snip(FloatArray(rate) { (0.5 * sin(2.0 * PI * 220.0 * it / rate)).toFloat() }, 1, rate)
        fun row(chain: FxChain) = chain.valve!!.getValue("DRIVE").let { d ->
            "DRIVE %.4f gain %.2f THD %.2f%%".format(java.util.Locale.ROOT, d, Valve.gainFor(d), thd(chain.process(sine)))
        }
        println("fade law, DRIVE chip at AMT 0.7: linear ${row(linear)} | log ${row(log)} | full ${row(full)}")
        for (a in (1..10).map { it / 10f }) {
            println("  AMT %.1f  log: %s   linear: %s".format(java.util.Locale.ROOT, a, row(Treatments.fade(full, a)), row(Treatments.linearFade(full, a))))
        }
        println("fade-law audition: ${entries.size} clips under ${root.absolutePath}")
    }
}
