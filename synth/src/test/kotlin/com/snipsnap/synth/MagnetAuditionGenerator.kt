package com.snipsnap.synth

import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ArrangedPad
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Renders MAGNET's R1 listening clips under testkit/magnet-audition/ (gitignored): the questions
 * the owner answers by ear before any preset is written
 * (docs/superpowers/plans/2026-09-30-magnet-r1.md, Task 5). Nothing in MAGNET has been heard when
 * this is first run.
 *
 * - KIT: the sixteen pads of [SynthKits.magnet] as they land.
 * - LAND: CHUG through VALVE at the three landing DRIVEs by two CABs, each labelled by the gain
 *   it is, the one number a DRIVE means on VALVE's law.
 * - CHUGAB: a CHUG stab through its landing chain against a VELVET saw stab through the same
 *   VALVE map, the two stabs matched to each other by [Loudness.of], bare and as two bars with a
 *   snare, then the two bars again as a blind pair in swapped order. As played the CHUG stab rings
 *   about four times longer, so duration alone tells them apart; the section therefore repeats the
 *   comparison with the dry CHUG render cut to the saw stab's length (a 40 ms fade) before the amp
 *   and scaled to the saw stab's loudness: the stab, the bar and a second blind pair in the opposite
 *   order (the saw bar first in one pair, the CHUG bar first in the other).
 * - BLIND: JANGLE at three notes and two PICK defaults as landed, and three notes dry, with no
 *   word in a label that names the sound; the manifest's truth field carries the key.
 * - CHUG and JANGLE: each voice's default and both ends of MUTE, PICK and BLEND, dry and landed.
 * - PICKDEF: each voice at its specified PICK against the brighter PICK that reproduces the spike's
 *   exciter corner, dry and landed.
 * - PLACE: CHUG with VALVE inside the render at 176.4 kHz against the recipe's split (the dry
 *   engine, then VALVE at the pad's rate).
 * - AMPPADS: a snare and a choir dry and through each voice's landing VALVE.
 *
 * Every clip is mono and shares one loudness ([AuditionLevel]), applied once, when the file is
 * written: a stab that meets a snare is mixed unlevelled and the finished bar is what is levelled.
 * Writes `manifest.json`, `{"clips":[{"section","id","file","label","truth"}]}`, the one list of
 * clips the listening page is built from; `truth` is empty except on the blind clips. Prints each
 * clip's peak and length and, last, the JVM cost per rendered second of a 4 s JANGLE and of VALVE
 * on it. Run via `./gradlew :synth:generateMagnetAudition`.
 */
object MagnetAuditionGenerator {

    /** Clips per section, in the order they are written; the sum is the final count. */
    private val SECTION_COUNTS = linkedMapOf(
        "KIT" to 16, "LAND" to 6, "CHUGAB" to 10, "BLIND" to 9, "CHUG" to 14, "JANGLE" to 14,
        "PICKDEF" to 8, "PLACE" to 4, "AMPPADS" to 6,
    )

    /** A levelled file is silent below this peak: the audition level puts every real clip far above it. */
    private const val SILENT_PEAK = 0.01f

    /** Words that name a voice or an engine; none may appear in a blind clip's id or label. */
    private val VOICE_WORDS = listOf("JANGLE", "CHUG", "GUITAR", "HARP", "SYNTH", "STRING", "MAGNET", "VELVET", "SAW", "BRASS", "VALVE", "AMP", "PICK", "MUTE", "BLEND")
    private val NOTE_NAME = Regex("""\b[A-G]#?[0-9]\b""")

    /** One end of a macro, for the CHUG and JANGLE sections. */
    private class End(val id: String, val macro: String, val value: Float, val words: String)

    private val ENDS = listOf(
        End("mute_0", "MUTE", 0f, "an open string that rings its whole length"),
        End("mute_1", "MUTE", 1f, "a palm mute, a short dark thud"),
        End("pick_0", "PICK", 0f, "a thumb, soft and round"),
        End("pick_1", "PICK", 1f, "a wire pick, bright and clicky"),
        End("blend_0", "BLEND", 0f, "the neck pickup alone, round"),
        End("blend_1", "BLEND", 1f, "the bridge pickup alone, thin and twangy"),
    )

    private class Row(val path: String, val peak: Float, val frames: Int, val seconds: Float)

    private fun noteOf(voice: MagnetVoice, tune: Float): String =
        Scales.nameOf(Magnet.rootMidi(voice) + Magnet.semitonesFor(tune))

    private fun landed(voice: MagnetVoice, macros: Map<String, Float>): Snip =
        Magnet.landingChain(voice).process(Magnet.render(voice, macros))

    /** CHUG with VALVE run at the render rate on the string itself, before the output chain: the amp inside the render. */
    private fun ampInRender(macros: Map<String, Float>): Snip {
        val voice = MagnetVoice.CHUG
        val m = Magnet.settled(macros, voice)
        val f0 = Magnet.frequencyFor(voice, m.getValue("TUNE"))
        val picked = Magnet.pickup(voice, Magnet.string(voice, macros), f0, m.getValue("BLEND"))
        val wet = Valve.process(Snip(picked, 1, Magnet.RENDER_RATE), Magnet.LANDING_VALVE.getValue(voice), oversample = false)
        return Snip(Magnet.finish(wet.samples, Magnet.RENDER_RATE), 1, Dsp.RATE)
    }

    private fun scaled(snip: Snip, k: Float) = Snip(FloatArray(snip.samples.size) { snip.samples[it] * k }, snip.channels, snip.sampleRate)

    private fun f4(v: Float) = String.format(Locale.ROOT, "%.4f", v)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/magnet-audition")
        root.mkdirs()
        val entries = mutableListOf<String>()
        val rows = mutableListOf<Row>()
        val paths = mutableSetOf<String>()
        val sectionCounts = linkedMapOf<String, Int>()

        fun write(section: String, id: String, label: String, snip: Snip, truth: String = "") {
            val file = "$section/$id.wav"
            check(paths.add(file)) { "clip written twice: $file" }
            check(listOf(section, id, file, label, truth).none { s -> s.any { it == '"' || it == '\\' || it.isISOControl() } }) {
                "manifest field needs escaping: $section / $id / $label / $truth"
            }
            if (section == "BLIND" || truth.isNotEmpty()) {
                val named = (VOICE_WORDS.filter { w -> w in label.uppercase() || w in id.uppercase() }) +
                    listOfNotNull(NOTE_NAME.find(label.uppercase())?.value)
                check(named.isEmpty()) { "blind clip $id names the sound in its id or label: $named" }
            }
            val mono = Cleanup.toMono(snip)
            check(mono.samples.all { it.isFinite() } && mono.peak() > 0f) { "$file cannot be rendered finite and non-silent" }
            val levelled = AuditionLevel.level(mono)
            val peak = levelled.peak()
            check(levelled.samples.all { it.isFinite() } && peak > SILENT_PEAK) { "$file is silent or not finite once levelled (peak $peak)" }
            WavWriter.write(File(File(root, section).apply { mkdirs() }, "$id.wav"), levelled, WavWriter.BitDepth.PCM_16)
            entries += """{"section":"$section","id":"$id","file":"$file","label":"$label","truth":"$truth"}"""
            rows += Row(file, peak, levelled.frameCount, levelled.durationSeconds)
            sectionCounts[section] = (sectionCounts[section] ?: 0) + 1
        }

        // KIT: the pads exactly as SynthKits.magnet() lands them; each pad's recipe names its patch and note.
        SynthKits.magnet().forEachIndexed { i, pad ->
            val arranged = requireNotNull(pad) { "the magnet kit has an empty pad at ${i + 1}" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "kit pad ${i + 1} carries no recipe" })
            val patch = requireNotNull(recipe.patch) as MagnetPatch
            val tag = "A" + (i + 1).toString().padStart(2, '0')
            val note = noteOf(patch.voice, patch.macros.getValue("TUNE"))
            write("KIT", tag.lowercase() + "_" + patch.name.lowercase().replace(' ', '_'), "$tag ${patch.name.uppercase()} ($note)", arranged.snip)
        }

        // LAND: CHUG at TUNE 0.5 through the landing amp, DRIVE by CAB, labelled by the gain DRIVE is.
        val chugAmp = Magnet.LANDING_VALVE.getValue(MagnetVoice.CHUG)
        val chugDry = Magnet.render(MagnetVoice.CHUG, mapOf("TUNE" to 0.5f))
        for (drive in listOf(0.71f, 0.78f, 0.85f)) {
            for (cab in listOf(0.6f, 0.95f)) {
                val gain = Valve.gainFor(drive).roundToInt()
                val id = "chug_d${(drive * 100).roundToInt()}_c${(cab * 100).roundToInt()}"
                write("LAND", id, "GAIN $gain (DRIVE $drive), CAB $cab", Valve.process(chugDry, chugAmp + mapOf("DRIVE" to drive, "CAB" to cab)))
            }
        }

        // CHUGAB: both stabs through the same VALVE map, matched to each other, then each against one unlevelled snare.
        val magnetStab = Magnet.landingChain(MagnetVoice.CHUG).process(chugDry)
        val velvetDry = Velvet.render(VelvetVoice.BRASS, mapOf("TUNE" to 2f / 24f))
        val velvetWet = Valve.process(velvetDry, chugAmp)
        val match = Loudness.of(magnetStab) / Loudness.of(velvetWet)
        val velvetStab = scaled(velvetWet, match)
        val snare = Thump.render(ThumpVoice.SNARE)
        fun bar(stab: Snip) = Groove.render(
            listOf(ArrangedPad(stab, DrumClass.TONAL), ArrangedPad(snare, DrumClass.SNARE)),
            bpm = 92f, bars = 2, seed = 7,
        )
        val barMagnet = bar(magnetStab)
        val barVelvet = bar(velvetStab)
        val barWords = "THE STAB ON BEAT 1 OF TWO BARS, THE SNARE ON BEATS 2 AND 4"
        println(
            "chugab: stab notes ${Magnet.frequencyFor(MagnetVoice.CHUG, 0.5f)} Hz (magnet) / ${Velvet.frequencyFor(VelvetVoice.BRASS, 2f / 24f)} Hz (velvet); " +
                "Loudness.of magnet ${f4(Loudness.of(magnetStab))}, velvet through the amp ${f4(Loudness.of(velvetWet))}, scale ${f4(match)}, " +
                "velvet after the scale ${f4(Loudness.of(velvetStab))}",
        )
        val stabNote = noteOf(MagnetVoice.CHUG, 0.5f)
        write("CHUGAB", "stab_magnet", "MAGNET CHUG STAB ($stabNote) THROUGH ITS LANDING AMP, AS PLAYED: IT RINGS LONGER THAN THE SAW STAB", magnetStab)
        write("CHUGAB", "stab_velvet", "VELVET SAW STAB ($stabNote) THROUGH THE SAME AMP, AS PLAYED, MATCHED TO THE MAGNET STAB IN LOUDNESS", velvetStab)
        write("CHUGAB", "bar_magnet", "MAGNET BAR, AS PLAYED (THE STAB RINGS LONGER): $barWords", barMagnet)
        write("CHUGAB", "bar_velvet", "VELVET BAR, AS PLAYED: $barWords", barVelvet)
        write("CHUGAB", "bar_x_a", "BAR X A, AS PLAYED: $barWords", barVelvet, truth = "VELVET saw stab through the same amp; B is MAGNET")
        write("CHUGAB", "bar_x_b", "BAR X B, AS PLAYED: $barWords", barMagnet, truth = "MAGNET CHUG stab through its landing amp; A is VELVET")

        // CHUGAB, length-matched: the dry CHUG render is cut to the dry saw stab's length with a 40 ms fade before the amp, so
        // duration is not what tells the pair apart; it is then scaled to the saw stab's loudness (the saw stab is the reference).
        check(chugDry.frameCount >= velvetDry.frameCount) { "the CHUG render is shorter than the saw stab, so there is nothing to cut" }
        val cutDry = chugDry.samples.copyOf(velvetDry.frameCount).also { Dsp.fadeTail(it, ms = 40f, rate = chugDry.sampleRate) }
        val cutWet = Valve.process(Snip(cutDry, chugDry.channels, chugDry.sampleRate), chugAmp)
        val matchedStab = scaled(cutWet, Loudness.of(velvetStab) / Loudness.of(cutWet))
        val barMatched = bar(matchedStab)
        val lengthGap = matchedStab.frameCount - velvetStab.frameCount
        val loudnessRatio = Loudness.of(matchedStab) / Loudness.of(velvetStab)
        println(
            "chugab matched: stab ${matchedStab.frameCount} frames (${f4(matchedStab.durationSeconds)} s) against the saw stab's ${velvetStab.frameCount} " +
                "(${f4(velvetStab.durationSeconds)} s), a gap of $lengthGap frames; Loudness.of matched ${f4(Loudness.of(matchedStab))}, " +
                "saw ${f4(Loudness.of(velvetStab))}, ratio ${f4(loudnessRatio)}",
        )
        check(abs(lengthGap) <= 4) { "the matched stab is $lengthGap frames from the saw stab's length" }
        check(abs(loudnessRatio - 1f) <= 0.005f) { "the matched stab's loudness is ${f4(loudnessRatio)} of the saw stab's, outside 0.5 percent" }
        write("CHUGAB", "stab_magnet_matched", "MAGNET CHUG STAB ($stabNote) CUT TO THE SAW STAB'S LENGTH, THROUGH ITS LANDING AMP, MATCHED TO THE SAW STAB IN LOUDNESS", matchedStab)
        write("CHUGAB", "bar_magnet_matched", "MAGNET BAR, THE STAB CUT TO THE SAW STAB'S LENGTH: $barWords", barMatched)
        write("CHUGAB", "bar_xm_a", "BAR XM A, BOTH STABS THE SAME LENGTH: $barWords", barMatched, truth = "MAGNET (length-matched); B is VELVET")
        write("CHUGAB", "bar_xm_b", "BAR XM B, BOTH STABS THE SAME LENGTH: $barWords", barVelvet, truth = "VELVET; A is MAGNET (length-matched)")

        // BLIND: JANGLE as landed at the specified PICK default and at the spike's brighter one, then the specified default dry.
        val blindTunes = listOf(0f, 0.5f, 1f)
        var n = 0
        for ((pick, key) in listOf(0.6f to "spec default PICK 0.6", 0.80f to "bright default PICK 0.80")) {
            for (tune in blindTunes) {
                n++
                write("BLIND", "clip_$n", "CLIP $n", landed(MagnetVoice.JANGLE, mapOf("TUNE" to tune, "PICK" to pick)), truth = "JANGLE ${noteOf(MagnetVoice.JANGLE, tune)} $key")
            }
        }
        for ((i, tune) in blindTunes.withIndex()) {
            write("BLIND", "dry_${i + 1}", "CLIP ${n + i + 1}", Magnet.render(MagnetVoice.JANGLE, mapOf("TUNE" to tune, "PICK" to 0.6f)), truth = "JANGLE ${noteOf(MagnetVoice.JANGLE, tune)} spec default PICK 0.6 dry")
        }

        // CHUG and JANGLE: the default, then each macro's two ends with the rest at the defaults; dry, then landed.
        for (voice in MagnetVoice.entries) {
            val section = voice.name
            val defaults = Magnet.defaults(voice)
            val note = noteOf(voice, defaults.getValue("TUNE"))
            for (isLanded in listOf(false, true)) {
                val prefix = if (isLanded) "landed_" else ""
                val said = if (isLanded) "LANDED " else ""
                fun render(macros: Map<String, Float>) = if (isLanded) landed(voice, macros) else Magnet.render(voice, macros)
                write(section, prefix + "default", "${said}DEFAULT ($note): every macro at its default", render(emptyMap()))
                for (end in ENDS) {
                    write(section, prefix + end.id, "$said${end.macro} ${end.value.roundToInt()}: ${end.words}", render(mapOf(end.macro to end.value)))
                }
            }
        }

        // PICKDEF: the specified PICK against the one whose exciter corner is the spike's, dry then landed.
        val pickPairs = listOf(
            Triple(MagnetVoice.JANGLE, 0.6f, 0.80f),
            Triple(MagnetVoice.CHUG, 0.55f, 0.65f),
        )
        for (isLanded in listOf(false, true)) {
            for ((voice, spec, bright) in pickPairs) {
                for ((tag, pick, words) in listOf(Triple("spec", spec, "A DARKER PICK"), Triple("bright", bright, "A BRIGHTER PICK"))) {
                    val macros = mapOf("PICK" to pick)
                    val id = (if (isLanded) "landed_" else "") + voice.name.lowercase() + "_" + tag
                    val label = (if (isLanded) "LANDED " else "") + "${voice.name}, $words (PICK $pick)"
                    write("PICKDEF", id, label, if (isLanded) landed(voice, macros) else Magnet.render(voice, macros))
                }
            }
        }

        // PLACE: the amp inside the render (P1) against the split, CHUG at B2 and B3, both through the landing map.
        val placeTunes = listOf(0.5f, 1f)
        for (tune in placeTunes) {
            val note = noteOf(MagnetVoice.CHUG, tune)
            write("PLACE", "p1_${note.lowercase()}", "P1, THE AMP INSIDE THE RENDER ($note)", ampInRender(mapOf("TUNE" to tune)))
        }
        for (tune in placeTunes) {
            val note = noteOf(MagnetVoice.CHUG, tune)
            write("PLACE", "split_${note.lowercase()}", "THE SPLIT, THE AMP AFTER THE RENDER ($note)", Valve.process(Magnet.render(MagnetVoice.CHUG, mapOf("TUNE" to tune)), chugAmp))
        }

        // AMPPADS: a snare and a choir dry and through each voice's landing amp.
        val choir = Cleanup.toMono(Vox.render(VoxVoice.CHOIR))
        for ((name, source) in listOf("snare" to snare, "vox" to choir)) {
            write("AMPPADS", "${name}_dry", "${name.uppercase()} DRY", source)
            for (voice in MagnetVoice.entries) {
                write("AMPPADS", "${name}_${voice.name.lowercase()}", "${name.uppercase()} THROUGH ${voice.name}'S LANDING AMP", Valve.process(source, Magnet.LANDING_VALVE.getValue(voice)))
            }
        }

        check(sectionCounts == SECTION_COUNTS) { "section counts $sectionCounts, expected $SECTION_COUNTS" }
        check(entries.size == 87) { "expected 87 clips, wrote ${entries.size}" }
        File(root, "manifest.json").writeText("{\"clips\":[\n" + entries.joinToString(",\n") + "\n]}\n")

        println("magnet audition: ${entries.size} clips under ${root.absolutePath}")
        for (r in rows) println("  ${r.path}  peak ${f4(r.peak)}  frames ${r.frames}  (${f4(r.seconds)} s)")
        printCost()
    }

    /**
     * The phone's cost as a JVM number: milliseconds per rendered second of a 4 s JANGLE (the
     * string's budget, at MUTE 0 on the open string) and of [Valve.process] on it, the median of
     * three runs after a warm-up. A phone is slower by a factor this does not measure.
     */
    private fun printCost() {
        val macros = mapOf("TUNE" to 0f, "MUTE" to 0f)
        val amp = Magnet.LANDING_VALVE.getValue(MagnetVoice.JANGLE)
        val dry = Magnet.render(MagnetVoice.JANGLE, macros)
        Valve.process(dry, amp)
        fun medianMs(block: () -> Unit): Double {
            val runs = List(3) {
                val t0 = System.nanoTime()
                block()
                (System.nanoTime() - t0) / 1e6
            }
            return runs.sorted()[1]
        }
        val renderMs = medianMs { Magnet.render(MagnetVoice.JANGLE, macros) }
        val valveMs = medianMs { Valve.process(dry, amp) }
        val seconds = dry.durationSeconds
        println(
            "phone cost (JVM): JANGLE ${f4(seconds)} s rendered in ${f4(renderMs.toFloat())} ms = ${f4((renderMs / seconds).toFloat())} ms per rendered second; " +
                "Valve.process on it ${f4(valveMs.toFloat())} ms = ${f4((valveMs / seconds).toFloat())} ms per rendered second",
        )
    }
}
