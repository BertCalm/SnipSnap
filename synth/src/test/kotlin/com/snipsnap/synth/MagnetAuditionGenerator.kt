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
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Renders MAGNET's R1 listening clips under testkit/magnet-audition/ (gitignored): the questions
 * the owner answers by ear before any preset is written
 * (docs/superpowers/specs/2026-09-29-magnet-valve-design.md, "Phasing and gates"): until they are
 * heard, MAGNET's numbers are shape values.
 *
 * - KIT: the sixteen pads of [SynthKits.magnet] as they land.
 * - LAND: CHUG through VALVE at the three DRIVEs the owner compared (0.71, 0.78 and 0.85: gain 13,
 *   37 and 106; the landing is now the last, picked by ear) by two CABs, each labelled by the gain
 *   it is, the one number a DRIVE means on VALVE's law.
 * - CHUGAB: a CHUG stab through its landing chain against a VELVET saw stab through the same
 *   VALVE map, the two stabs matched to each other by [Loudness.of], bare and as two bars with a
 *   snare. As played the CHUG stab rings about four times longer, so duration alone tells them
 *   apart; the section therefore repeats the comparison with a palm-muted CHUG stab: the dry render
 *   is trimmed to the saw stab's length, run through the amp, and MUTE is the lowest step from 0.60
 *   to 1.00 whose amped end level is at most 3 percent of its peak (the amp lifts a quiet tail, so
 *   the end level is measured after it); a 100 ms release follows the amp, then the saw stab's
 *   loudness. The amp lifts a quiet tail so much that the string cannot be made to end by itself in
 *   the saw stab's length, so the comparison is also made the other way: the saw stab's DECAY is
 *   raised until its dry render is as long as the as-played CHUG stab (or as long as DECAY 1.00
 *   allows, with the shortfall stated), through the same amp and loudness-matched to the CHUG stab,
 *   with no cut at all. The cut-matched pair is not symmetric (its release begins on the guitar side
 *   alone), so a fourth comparison cuts both as-played stabs at the same short length after the amp,
 *   with the same release: the longest of 0.35, 0.30, 0.25 and 0.20 s at which both stabs still ring,
 *   loudness-matched after the release. The section holds the stabs and bars, the two short stabs,
 *   and four blind pairs of bars under neutral ids (cut-matched, both-cut, decay-matched, as
 *   played); the decay-matched and as-played pairs are length-confounded controls. By id number the
 *   first bar of each pair is CHUG, saw, CHUG, saw (blind_1, blind_3, blind_5, blind_7); in the
 *   page's order (blind_1, blind_7, blind_5, blind_3) it is CHUG, saw, CHUG, saw. The manifest is
 *   the answer key: the page must not display `label` or `truth` for blind rows, and shows neither
 *   their ids nor the generator's A and B letters.
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
 * Writes `manifest.json`, `{"clips":[{"section","id","file","label","truth","order"}]}`, the one list
 * of clips the listening page is built from; `truth` is empty except on the blind clips and `order`
 * is the sequence the page presents a section's clips in (the blind bars of CHUGAB come first, in
 * the order cut-matched, both-cut, decay-matched, as played). Prints each
 * clip's peak and length and, last, the JVM cost per rendered second of a 4 s JANGLE and of VALVE
 * on it. Run via `./gradlew :synth:generateMagnetAudition`.
 */
object MagnetAuditionGenerator {

    /** Clips per section; the sum is the final count. */
    private val SECTION_COUNTS = linkedMapOf(
        "KIT" to 16, "LAND" to 6, "CHUGAB" to 18, "BLIND" to 9, "CHUG" to 14, "JANGLE" to 14,
        "PICKDEF" to 8, "PLACE" to 4, "AMPPADS" to 6,
    )

    /** A levelled file is silent below this peak: the audition level puts every real clip far above it. */
    private const val SILENT_PEAK = 0.01f

    /** The amped matched stab should end by itself: its last 5 ms peak, before the release, is at most this share of its peak (about -30 dB). */
    private const val END_LEVEL = 0.03f

    /** The release applied to the matched stab and to the symmetric pair's two cuts, after the amp, in milliseconds. */
    private const val RELEASE_MS = 100f

    /** The symmetric pair's cut lengths in seconds, longest first: the longest at which both stabs still ring is used. */
    private val SHORT_CUT_SECONDS = listOf(0.35f, 0.30f, 0.25f, 0.20f)

    /** Still ringing at a cut: the peak of the 5 ms before it is at least this share of the stab's own peak (about -25 dB). */
    private const val RINGING_LEVEL = 0.0562f

    /** Sections whose name states the question, not which clip is which: CHUG in CHUGAB is allowed in a blind row's section and directory. */
    private val QUESTION_SECTIONS = setOf("CHUGAB")

    /** Words that name a voice or an engine; none may appear in a blind clip's section, id, file name or label. */
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

    /** One MUTE candidate for the matched stab: its dry render, the amped cut and the amped cut's end level. */
    private class Muted(val mute: Float, val dry: Snip, val wet: Snip, val end: Float)

    /** One cut length for the symmetric pair, with each as-played stab's level over the 5 ms before it. */
    private class ShortCut(val seconds: Float, val frames: Int, val magnetLevel: Float, val velvetLevel: Float) {
        val ringing: Boolean get() = magnetLevel >= RINGING_LEVEL && velvetLevel >= RINGING_LEVEL
    }

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
        val picked = Magnet.pickup(voice, Magnet.string(voice, macros), f0, m.getValue("BLEND"), resonanceScale = Magnet.resonanceScale(voice, m))
        val wet = Valve.process(Snip(picked, 1, Magnet.RENDER_RATE), Magnet.LANDING_VALVE.getValue(voice), oversample = false)
        return Snip(Magnet.finish(wet.samples, Magnet.RENDER_RATE), 1, Dsp.RATE)
    }

    private fun scaled(snip: Snip, k: Float) = Snip(FloatArray(snip.samples.size) { snip.samples[it] * k }, snip.channels, snip.sampleRate)

    private fun f4(v: Float) = String.format(Locale.ROOT, "%.4f", v)

    private fun f5(v: Float) = String.format(Locale.ROOT, "%.5f", v)

    private fun f2(v: Float) = String.format(Locale.ROOT, "%.2f", v)

    /** How far under its peak a level is, as a positive number of dB with one decimal. */
    private fun dbUnder(ratio: Float) = String.format(Locale.ROOT, "%.1f", -20.0 * log10(ratio.toDouble()))

    private fun dbOf(ratio: Float) = if (ratio > 0f) String.format(Locale.ROOT, "%.1f", 20.0 * log10(ratio.toDouble())) else "-inf"

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/magnet-audition")
        root.mkdirs()
        val entries = mutableListOf<String>()
        val rows = mutableListOf<Row>()
        val paths = mutableSetOf<String>()
        val sectionCounts = linkedMapOf<String, Int>()
        val orders = mutableSetOf<String>()

        fun write(section: String, id: String, label: String, snip: Snip, truth: String = "", order: Int = (sectionCounts[section] ?: 0) + 1) {
            val file = "$section/$id.wav"
            check(paths.add(file)) { "clip written twice: $file" }
            check(orders.add("$section#$order")) { "order $order is used twice in $section" }
            check(listOf(section, id, file, label, truth).none { s -> s.any { it == '"' || it == '\\' || it.isISOControl() } }) {
                "manifest field needs escaping: $section / $id / $label / $truth"
            }
            if (section == "BLIND" || truth.isNotEmpty()) {
                val shown = listOf(label, id, file.substringAfter('/'))
                val named = VOICE_WORDS.filter { w -> (shown + (if (section in QUESTION_SECTIONS) "" else section)).any { w in it.uppercase() } } +
                    shown.mapNotNull { NOTE_NAME.find(it.uppercase())?.value }
                check(named.isEmpty()) { "blind clip $file names the sound in its section, id, file or label: $named" }
            }
            val mono = Cleanup.toMono(snip)
            check(mono.samples.all { it.isFinite() } && mono.peak() > 0f) { "$file cannot be rendered finite and non-silent" }
            val levelled = AuditionLevel.level(mono)
            val peak = levelled.peak()
            check(levelled.samples.all { it.isFinite() } && peak > SILENT_PEAK) { "$file is silent or not finite once levelled (peak $peak)" }
            WavWriter.write(File(File(root, section).apply { mkdirs() }, "$id.wav"), levelled, WavWriter.BitDepth.PCM_16)
            entries += """{"section":"$section","id":"$id","file":"$file","label":"$label","truth":"$truth","order":$order}"""
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

        // LAND: CHUG at TUNE 0.5 through the landing amp's SAG and TONE at three DRIVEs by two CABs, labelled by the gain DRIVE is (the landing is DRIVE 0.85, CAB 0.95).
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
        write("CHUGAB", "stab_magnet", "MAGNET CHUG STAB ($stabNote) THROUGH ITS LANDING AMP, AS PLAYED: IT RINGS LONGER THAN THE SAW STAB", magnetStab, order = 9)
        write("CHUGAB", "stab_velvet", "VELVET SAW STAB ($stabNote) THROUGH THE SAME AMP, AS PLAYED, MATCHED TO THE MAGNET STAB IN LOUDNESS", velvetStab, order = 10)
        write("CHUGAB", "bar_magnet", "MAGNET BAR, AS PLAYED (THE STAB RINGS LONGER): $barWords", barMagnet, order = 11)
        write("CHUGAB", "bar_velvet", "VELVET BAR, AS PLAYED: $barWords", barVelvet, order = 12)

        // CHUGAB, length-matched: a chug is a palm-muted note, so the stab's shortness comes from the engine's own MUTE. The dry
        // render is trimmed to the dry saw stab's length (no fade) and run through the amp, and the end level is measured on that
        // amped buffer: the amp's gain lifts a quiet tail, so the dry render's end level says nothing about what is heard. MUTE is
        // the lowest step from 0.60 to 1.00 whose amped end level is at most END_LEVEL (MUTE 1.00 and a stated residual if none
        // is). The release is applied after the amp, then the stab is scaled to the saw stab's loudness as heard.
        val cutFrames = velvetDry.frameCount
        val endFrames = (0.005f * chugDry.sampleRate).toInt()
        val releaseFrames = (RELEASE_MS / 1000f * chugDry.sampleRate).toInt()
        fun peakIn(buf: FloatArray, from: Int, to: Int): Float {
            var peak = 0f
            for (k in from until to) peak = maxOf(peak, abs(buf[k]))
            return peak
        }
        fun endLevel(buf: FloatArray): Float {
            val peak = peakIn(buf, 0, buf.size)
            return if (peak > 0f) peakIn(buf, buf.size - endFrames, buf.size) / peak else 0f
        }
        fun muted(mute: Float): Muted {
            val dry = Magnet.render(MagnetVoice.CHUG, Magnet.defaults(MagnetVoice.CHUG) + mapOf("TUNE" to 0.5f, "MUTE" to mute))
            check(dry.frameCount >= cutFrames) { "the CHUG render at MUTE $mute is ${dry.frameCount} frames, shorter than the saw stab's $cutFrames" }
            val wet = Valve.process(Snip(dry.samples.copyOf(cutFrames), dry.channels, dry.sampleRate), chugAmp)
            return Muted(mute, dry, wet, endLevel(wet.samples))
        }
        fun logMuted(label: String, m: Muted) = println(
            "chugab matched mute: $label MUTE ${m.mute}: dry natural ${m.dry.frameCount} frames (${f4(m.dry.durationSeconds)} s), " +
                "amped end level ${f5(m.end)} (${dbOf(m.end)} dB re peak, last 5 ms of the amped cut, before any release)",
        )
        logMuted("reference (the kit default)", muted(Magnet.defaults(MagnetVoice.CHUG).getValue("MUTE")))
        val candidates = (12..20).map { step -> muted(step / 20f).also { logMuted("candidate", it) } }
        val chosen = candidates.firstOrNull { it.end <= END_LEVEL } ?: candidates.last()
        if (chosen.end > END_LEVEL) {
            println("chugab matched mute: WARNING no MUTE up to 1.0 leaves the amped stab within $END_LEVEL (${dbOf(END_LEVEL)} dB) of its peak at the cut; using MUTE 1.0, whose residual is ${dbOf(chosen.end)} dB re peak")
        }
        logMuted("chosen", chosen)
        val releaseStart = chosen.wet.frameCount - releaseFrames
        val startLevel = peakIn(chosen.wet.samples, releaseStart - endFrames, releaseStart) / chosen.wet.peak()
        println(
            "chugab matched mute: the ${RELEASE_MS.roundToInt()} ms release begins at ${dbOf(startLevel)} dB re peak " +
                "(the last 5 ms before it); the amped end level above is the last 5 ms of the buffer, where the release is already near zero",
        )
        val released = Snip(chosen.wet.samples.copyOf().also { Dsp.fadeTail(it, ms = RELEASE_MS, rate = chosen.wet.sampleRate) }, chosen.wet.channels, chosen.wet.sampleRate)
        val matchedStab = scaled(released, Loudness.of(velvetStab) / Loudness.of(released))
        val barMatched = bar(matchedStab)
        val lengthGap = matchedStab.frameCount - velvetStab.frameCount
        println(
            "chugab matched: stab ${matchedStab.frameCount} frames (${f4(matchedStab.durationSeconds)} s) against the saw stab's ${velvetStab.frameCount} " +
                "(${f4(velvetStab.durationSeconds)} s), a gap of $lengthGap frames; Loudness.of matched ${f4(Loudness.of(matchedStab))}, " +
                "saw ${f4(Loudness.of(velvetStab))}, ratio ${f4(Loudness.of(matchedStab) / Loudness.of(velvetStab))}",
        )
        check(abs(lengthGap) <= 4) { "the matched stab is $lengthGap frames from the saw stab's length" }
        val residual = "STARTING ${dbUnder(startLevel)} DB UNDER ITS PEAK, ENDS ${dbUnder(chosen.end)} DB UNDER ITS PEAK BEFORE THE RELEASE"
        val matchedWords = "PALM-MUTED (MUTE ${chosen.mute}), CUT TO THE SAW STAB'S LENGTH, ${RELEASE_MS.roundToInt()} MS RELEASE $residual"
        val matchedTruth = "MAGNET (palm-muted, MUTE ${chosen.mute}, ${RELEASE_MS.roundToInt()} ms release starting ${dbUnder(startLevel)} dB under its peak, ends ${dbUnder(chosen.end)} dB under its peak before the release)"
        write("CHUGAB", "stab_magnet_matched", "MAGNET CHUG STAB ($stabNote) THROUGH ITS LANDING AMP, $matchedWords, MATCHED TO THE SAW STAB IN LOUDNESS", matchedStab, order = 13)
        write("CHUGAB", "bar_magnet_matched", "MAGNET BAR, THE STAB $matchedWords: $barWords", barMatched, order = 14)

        // CHUGAB, decay-matched: the comparison the other way. The amp lifts a quiet string tail, so the CHUG stab cannot be made to
        // end by itself in the saw stab's length; the saw stab's DECAY is raised instead until its dry render is as long as the
        // as-played CHUG stab's. No cut anywhere. If even DECAY 1.00 is under 90 percent of that length, DECAY is 1.00 and the shortfall
        // is stated. The saw stab goes through the same amp and is matched in loudness to the as-played CHUG stab.
        val magnetFrames = chugDry.frameCount
        val longs = (7..20).map { step ->
            val decay = step / 20f
            decay to Velvet.render(VelvetVoice.BRASS, mapOf("TUNE" to 2f / 24f, "DECAY" to decay))
        }
        for ((decay, dry) in longs) {
            println("chugab decay-matched: candidate DECAY $decay: dry ${dry.frameCount} frames (${f4(dry.durationSeconds)} s) against the as-played CHUG stab's $magnetFrames")
        }
        val tooShort = longs.last().second.frameCount < 0.9f * magnetFrames
        val (longDecay, longDry) = if (tooShort) longs.last() else longs.minBy { abs(it.second.frameCount - magnetFrames) }
        val shortfall = (100f * (1f - longDry.frameCount.toFloat() / magnetFrames)).roundToInt()
        if (tooShort) {
            println("chugab decay-matched: WARNING even DECAY 1.0 renders ${longDry.frameCount} frames, under 90 percent of the as-played CHUG stab's $magnetFrames; using DECAY 1.0, $shortfall percent shorter")
        }
        val longWet = Valve.process(longDry, chugAmp)
        val longStab = scaled(longWet, Loudness.of(magnetStab) / Loudness.of(longWet))
        val barLong = bar(longStab)
        println(
            "chugab decay-matched: chosen DECAY $longDecay: saw stab ${longDry.frameCount} frames (${f4(longDry.durationSeconds)} s) against the as-played CHUG stab's " +
                "$magnetFrames (${f4(chugDry.durationSeconds)} s), a gap of ${longDry.frameCount - magnetFrames} frames (${f4(longDry.durationSeconds - chugDry.durationSeconds)} s, $shortfall percent); " +
                "Loudness.of saw ${f4(Loudness.of(longStab))} against the as-played CHUG stab ${f4(Loudness.of(magnetStab))}, ratio ${f4(Loudness.of(longStab) / Loudness.of(magnetStab))}",
        )
        val longShort = if (tooShort) ", RINGS $shortfall PERCENT SHORTER EVEN AT DECAY 1.0" else ""
        val longWords = "WITH ITS DECAY RAISED TO RING AS LONG AS THE GUITAR STAB (DECAY $longDecay$longShort)"
        write("CHUGAB", "stab_velvet_long", "VELVET SAW STAB ($stabNote) $longWords, THROUGH THE SAME AMP, MATCHED TO THE MAGNET STAB IN LOUDNESS", longStab, order = 15)
        write("CHUGAB", "bar_velvet_long", "VELVET BAR, THE SAW STAB $longWords: $barWords", barLong, order = 16)
        val longTruth = "VELVET saw stab with DECAY $longDecay (${f4(longDry.durationSeconds)} s dry against the MAGNET stab's ${f4(chugDry.durationSeconds)} s" +
            (if (tooShort) ", $shortfall percent shorter, the DECAY ceiling" else "") + ")"
        val longBlind = if (tooShort) "THE RENDERED LENGTHS DIFFER BY $shortfall PERCENT" else "BOTH STABS RING ABOUT AS LONG"

        // CHUGAB, symmetric: the control for the cut-matched pair, whose release begins on the guitar side alone. Both as-played stabs
        // (the amped CHUG stab and the loudness-matched saw stab) are cut at the same short length, after the amp, and given the same
        // release, so the ending is on both sides and the length is equal. The cut is the longest candidate at which both stabs are still
        // ringing (the peak of the 5 ms before it at least RINGING_LEVEL of each stab's own peak); none qualifying is an error. After the
        // release the guitar cut is scaled to the saw cut's loudness, as the matched stab is.
        check(velvetStab.sampleRate == magnetStab.sampleRate) { "the two stabs are at ${magnetStab.sampleRate} and ${velvetStab.sampleRate} Hz" }
        val shortRate = magnetStab.sampleRate
        val shortCuts = SHORT_CUT_SECONDS.map { seconds ->
            val frames = (seconds * shortRate).toInt()
            check(frames > endFrames && frames <= minOf(magnetStab.frameCount, velvetStab.frameCount)) { "a cut at $seconds s does not fit both stabs" }
            ShortCut(
                seconds, frames,
                peakIn(magnetStab.samples, frames - endFrames, frames) / magnetStab.peak(),
                peakIn(velvetStab.samples, frames - endFrames, frames) / velvetStab.peak(),
            )
        }
        for (c in shortCuts) {
            println(
                "chugab short cut: candidate ${f2(c.seconds)} s (${c.frames} frames): CHUG stab ${dbOf(c.magnetLevel)} dB, saw stab ${dbOf(c.velvetLevel)} dB re each " +
                    "stab's own peak over the 5 ms before the cut; ${if (c.ringing) "both ringing (at least ${dbOf(RINGING_LEVEL)} dB)" else "not both ringing"}",
            )
        }
        val shortCut = checkNotNull(shortCuts.firstOrNull { it.ringing }) {
            "no cut of $SHORT_CUT_SECONDS s leaves both stabs within ${dbOf(RINGING_LEVEL)} dB of their own peaks"
        }
        fun cutAndReleased(stab: Snip) =
            Snip(stab.samples.copyOf(shortCut.frames).also { Dsp.fadeTail(it, ms = RELEASE_MS, rate = stab.sampleRate) }, stab.channels, stab.sampleRate)
        val velvetCut = cutAndReleased(velvetStab)
        val magnetReleased = cutAndReleased(magnetStab)
        val magnetCut = scaled(magnetReleased, Loudness.of(velvetCut) / Loudness.of(magnetReleased))
        check(magnetCut.frameCount == velvetCut.frameCount) { "the two cuts are ${magnetCut.frameCount} and ${velvetCut.frameCount} frames" }
        val cutRatio = Loudness.of(magnetCut) / Loudness.of(velvetCut)
        check(abs(cutRatio - 1f) <= 0.01f) { "the two cuts are not within 1 percent in Loudness.of: ratio $cutRatio" }
        val barMagnetCut = bar(magnetCut)
        val barVelvetCut = bar(velvetCut)
        check(barMagnetCut.frameCount == barVelvetCut.frameCount) { "the two short bars are ${barMagnetCut.frameCount} and ${barVelvetCut.frameCount} frames" }
        println(
            "chugab short: chosen ${f2(shortCut.seconds)} s, ${shortCut.frames} frames each (${magnetCut.frameCount} CHUG, ${velvetCut.frameCount} saw), ${RELEASE_MS.roundToInt()} ms release on both; " +
                "Loudness.of CHUG cut ${f4(Loudness.of(magnetCut))}, saw cut ${f4(Loudness.of(velvetCut))}, ratio ${f4(cutRatio)}; bars ${barMagnetCut.frameCount} and ${barVelvetCut.frameCount} frames",
        )
        val shortWords = "BOTH STABS CUT TO THE SAME LENGTH WITH THE SAME ${RELEASE_MS.roundToInt()} MS RELEASE"
        val shortTruth = "cut to ${f2(shortCut.seconds)} s after the amp with a ${RELEASE_MS.roundToInt()} ms release, the same on both stabs"
        write("CHUGAB", "stab_magnet_short", "MAGNET CHUG STAB ($stabNote) THROUGH ITS LANDING AMP, CUT TO ${f2(shortCut.seconds)} S WITH A ${RELEASE_MS.roundToInt()} MS RELEASE AFTER THE AMP, MATCHED TO THE SAW CUT IN LOUDNESS", magnetCut, order = 17)
        write("CHUGAB", "stab_velvet_short", "VELVET SAW STAB ($stabNote) THROUGH THE SAME AMP, CUT TO ${f2(shortCut.seconds)} S WITH THE SAME ${RELEASE_MS.roundToInt()} MS RELEASE", velvetCut, order = 18)

        // The blind pairs, under neutral ids and file names, written in the order the page presents them: cut-matched (blind_1,
        // blind_2), both-cut (blind_7, blind_8), decay-matched (blind_5, blind_6), as played (blind_3, blind_4). The first bar of each
        // pair is CHUG, saw, CHUG, saw by id number (blind_1, blind_3, blind_5, blind_7) and CHUG, saw, CHUG, saw in the order written
        // here (blind_1, blind_7, blind_5, blind_3). The decay-matched and as-played pairs are length-confounded controls.
        write("CHUGAB", "blind_1", "BAR XM A, BOTH STABS THE SAME LENGTH: $barWords", barMatched, truth = "$matchedTruth; B is VELVET", order = 1)
        write("CHUGAB", "blind_2", "BAR XM B, BOTH STABS THE SAME LENGTH: $barWords", barVelvet, truth = "VELVET; A is $matchedTruth", order = 2)
        write("CHUGAB", "blind_7", "BAR XS A, $shortWords: $barWords", barVelvetCut, truth = "VELVET saw stab $shortTruth; B is MAGNET CHUG stab, $shortTruth", order = 3)
        write("CHUGAB", "blind_8", "BAR XS B, $shortWords: $barWords", barMagnetCut, truth = "MAGNET CHUG stab $shortTruth; A is VELVET saw stab, $shortTruth", order = 4)
        write("CHUGAB", "blind_5", "BAR XL A, $longBlind: $barWords", barMagnet, truth = "MAGNET CHUG stab through its landing amp, as played; B is $longTruth", order = 5)
        write("CHUGAB", "blind_6", "BAR XL B, $longBlind: $barWords", barLong, truth = "$longTruth; A is MAGNET CHUG stab through its landing amp, as played", order = 6)
        write("CHUGAB", "blind_3", "BAR X A, AS PLAYED: $barWords", barVelvet, truth = "VELVET saw stab through the same amp; B is MAGNET", order = 7)
        write("CHUGAB", "blind_4", "BAR X B, AS PLAYED: $barWords", barMagnet, truth = "MAGNET CHUG stab through its landing amp; A is VELVET", order = 8)

        // BLIND: JANGLE as landed at the specified PICK default and at the spike's brighter one, then the specified default dry.
        val blindTunes = listOf(0f, 0.5f, 1f)
        var n = 0
        for ((pick, key) in listOf(0.6f to "spec default PICK 0.6", 0.80f to "bright default PICK 0.80")) {
            for (tune in blindTunes) {
                n++
                write("BLIND", "blind_a$n", "CLIP $n", landed(MagnetVoice.JANGLE, mapOf("TUNE" to tune, "PICK" to pick)), truth = "JANGLE ${noteOf(MagnetVoice.JANGLE, tune)} $key")
            }
        }
        for ((i, tune) in blindTunes.withIndex()) {
            write("BLIND", "blind_a${n + i + 1}", "CLIP ${n + i + 1}", Magnet.render(MagnetVoice.JANGLE, mapOf("TUNE" to tune, "PICK" to 0.6f)), truth = "JANGLE ${noteOf(MagnetVoice.JANGLE, tune)} spec default PICK 0.6 dry")
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
        check(entries.size == 95) { "expected 95 clips, wrote ${entries.size}" }
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
