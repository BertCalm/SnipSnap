package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Transients
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Renders FORGE's listening gate under testkit/forge-audition/ (gitignored): the questions the design
 * brief's "Cheapest proof" asks, on real pads through the real section
 * (docs/superpowers/specs/2026-10-25-forge-section-design-brief.md).
 *
 * Five sources: a THUMP kick and snare, a SKIN closed hat, a TINES bell (the metallic attack a keygroup
 * would use) and a CHOP, the loudest hit [Transients] finds in the repo's one real room recording
 * (`reference/live3 room take.wav`). Each: dry, defaults, every macro at both ends with the rest at their
 * defaults, YIELD 0.25 (where the flow switches inside each cycle, the brief's harsh zone), and CHARGE by
 * YIELD at the corners. Then three A/Bs: the coined hit at one loudness (LEVELLED), the folder at the snip
 * rate and at 4x (FOLDBACK), and FORGE before VALVE against after it (ORDER).
 *
 * Levels: unlike most auditions, the source grids are NOT loudness-matched. FORGE peak-matches, and what
 * ARREST does is raise loudness at the same peak; levelling every clip would erase the effect being judged.
 * Each source gets one gain, the one [AuditionLevel] gives its dry clip, so every clip of a source plays at
 * the peak the rack would give it. LEVELLED is the loudness-matched check that tone, not level, is what a
 * listener prefers. The manifest records each clip's loudness and crest against its dry.
 *
 * Writes manifest.json and index.html (the page in synth/src/test/resources/audition/forge-audition.html).
 * Run via `./gradlew :synth:generateForgeAudition`.
 */
object ForgeAuditionGenerator {

    private const val AUDITION_LEVEL = 0.03f

    private class Clip(val id: String, val label: String, val snip: Snip, val dry: Snip?)

    private class Section(val id: String, val title: String, val note: String, val clips: List<Clip>)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/forge-audition")
        val repo = File(args.getOrNull(1) ?: "..")
        root.mkdirs()
        val sources = linkedMapOf(
            "KICK" to Thump.render(ThumpVoice.KICK),
            "SNARE" to Thump.render(ThumpVoice.SNARE),
            "HAT" to Skin.render(SkinVoice.HAT_CLOSED),
            "METAL" to Tines.render(TinesVoice.BELL),
            "CHOP" to chop(File(repo, "reference/live3 room take.wav")),
        )

        val sections = mutableListOf<Section>()
        for ((name, dry) in sources) {
            val clips = mutableListOf(Clip("dry", "$name DRY", dry, dry), Clip("defaults", "$name FORGE DEFAULTS", Forge.process(dry), dry))
            for (spec in Forge.MACROS) {
                for (v in listOf(0f, 1f)) {
                    clips += Clip("${spec.name.lowercase()}_${v.toInt()}", "$name ${spec.name} ${v.toInt()}", Forge.process(dry, mapOf(spec.name to v)), dry)
                }
            }
            clips += Clip("yield_025", "$name YIELD 0.25 (THE HARSH ZONE)", Forge.process(dry, mapOf("YIELD" to 0.25f)), dry)
            for (c in listOf(0f, 1f)) for (y in listOf(0f, 1f)) {
                clips += Clip("charge_${c.toInt()}_yield_${y.toInt()}", "$name CHARGE ${c.toInt()} x YIELD ${y.toInt()}", Forge.process(dry, mapOf("CHARGE" to c, "YIELD" to y)), dry)
            }
            sections += Section(name, name, noteFor(name), clips)
        }

        val kick = sources.getValue("KICK")
        val snare = sources.getValue("SNARE")
        sections += Section(
            "LEVELLED", "Same loudness",
            "Dry, defaults and ARREST 1 at one loudness. If ARREST 1 still wins here, the die changes the tone, not just the level.",
            listOf(kick, snare).zip(listOf("KICK", "SNARE")).flatMap { (s, n) ->
                listOf(
                    Clip("${n.lowercase()}_dry", "$n DRY, LEVELLED", AuditionLevel.level(s), null),
                    Clip("${n.lowercase()}_defaults", "$n DEFAULTS, LEVELLED", AuditionLevel.level(Forge.process(s)), null),
                    Clip("${n.lowercase()}_arrest_1", "$n ARREST 1, LEVELLED", AuditionLevel.level(Forge.process(s, mapOf("ARREST" to 1f))), null),
                )
            },
        )
        val folder = mapOf("CHARGE" to 0f, "YIELD" to 0f, "ARREST" to 0f)
        sections += Section(
            "FOLDBACK", "Fold-back, 1x against 4x",
            "The snare at YIELD 0 with no shock and no die: the folder alone, at the snip's rate and at the 4x FORGE runs at. The 1x clip is what aliasing sounds like; if the 4x clip has any of it, the folder wants 8x.",
            listOf(
                Clip("snare_1x", "SNARE FOLDER ONLY, 1X", Forge.process(snare, folder, oversample = false), snare),
                Clip("snare_4x", "SNARE FOLDER ONLY, 4X", Forge.process(snare, folder), snare),
            ),
        )
        sections += Section(
            "ORDER", "Before or after the amp",
            "The rack puts FORGE before VALVE (the blank is formed, then the amp colours it). After is the alternative: the die coins whatever the amp did.",
            listOf(kick to "KICK", snare to "SNARE").flatMap { (s, n) ->
                listOf(
                    Clip("${n.lowercase()}_forge_valve", "$n FORGE THEN VALVE (THE RACK)", Valve.process(Forge.process(s)), s),
                    Clip("${n.lowercase()}_valve_forge", "$n VALVE THEN FORGE", Forge.process(Valve.process(s)), s),
                )
            },
        )

        val json = StringBuilder("{\"sections\": [\n")
        var count = 0
        for ((si, sec) in sections.withIndex()) {
            val dir = File(root, sec.id).apply { mkdirs() }
            json.append("  {\"id\": ${q(sec.id)}, \"title\": ${q(sec.title)}, \"note\": ${q(sec.note)}, \"clips\": [\n")
            for ((ci, clip) in sec.clips.withIndex()) {
                // Every clip plays at its own source's gain (LEVELLED's clips, with no source, are levelled already).
                val out = clip.dry?.let { scaled(clip.snip, sourceGain(it)) } ?: clip.snip
                WavWriter.write(File(dir, "${clip.id}.wav"), out, WavWriter.BitDepth.PCM_16)
                val ref = clip.dry
                val loud = ref?.let { 20 * log10(Loudness.of(clip.snip) / Loudness.of(it).coerceAtLeast(1e-9f)) }
                val crest = crest(clip.snip)
                json.append("    {\"id\": ${q(clip.id)}, \"file\": ${q("${sec.id}/${clip.id}.wav")}, \"label\": ${q(clip.label)}, ")
                json.append("\"crest\": ${"%.2f".format(crest)}")
                if (loud != null) json.append(", \"loudnessDb\": ${"%.2f".format(loud)}")
                json.append("}").append(if (ci < sec.clips.size - 1) ",\n" else "\n")
                count++
            }
            json.append("  ]}").append(if (si < sections.size - 1) ",\n" else "\n")
        }
        json.append("]}\n")
        File(root, "manifest.json").writeText(json.toString())
        val page = ForgeAuditionGenerator::class.java.getResourceAsStream("/audition/forge-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { o -> page.use { it.copyTo(o) } }
        println("forge audition: $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private fun noteFor(name: String): String = when (name) {
        "KICK" -> "A THUMP kick. CHARGE 1 adds the shock's 48 Hz weight under it; ARREST 1 is the hardest coin."
        "SNARE" -> "A THUMP snare: the clearest test of the heat burst and of the die on a bright body."
        "HAT" -> "A SKIN closed hat. A hat is all fast material, so at YIELD 0.5 nearly all of it flows; the classifier files it as a snare there. Does the ear?"
        "METAL" -> "A TINES bell, the keygroup case. CHARGE 0 against CHARGE 1 is the brief's chirp claim: the medium turns the strike into a sweep before the folder."
        else -> "The loudest hit in the repo's room recording: FORGE on a captured chop, not a synthesized drum."
    }

    /**
     * The loudest hit of the room recording, from a zero crossing, 0.45 s, mono, with a 20 ms fade out.
     * Loudest by the peak of the 20 ms after each detected onset: [Transients.detectStrongest] ranks by
     * the jump in energy, and its first pick here was a small click with a bigger hit 240 ms later.
     */
    private fun chop(file: File): Snip {
        val take = WavReader.read(file)
        val window = (0.02f * take.sampleRate).toInt()
        fun peakAfter(frame: Int): Float {
            var p = 0f
            for (f in frame until minOf(frame + window, take.frameCount)) for (ch in 0 until take.channels) p = maxOf(p, abs(take.samples[f * take.channels + ch]))
            return p
        }
        val onset = Transients.detect(take).maxByOrNull { peakAfter(it.frame) }?.frame ?: 0
        val start = Transients.zeroCrossingBefore(take, onset)
        val frames = (0.45f * take.sampleRate).toInt().coerceAtMost(take.frameCount - start)
        val out = FloatArray(frames) { f ->
            var s = 0f
            for (ch in 0 until take.channels) s += take.samples[(start + f) * take.channels + ch]
            s / take.channels
        }
        Dsp.fadeTail(out, ms = 20f, rate = take.sampleRate)
        val peak = out.maxOf { abs(it) }
        if (peak > 0f) for (i in out.indices) out[i] *= 0.9f / peak
        return Snip(out, 1, take.sampleRate)
    }

    /** The gain [AuditionLevel] would give [dry], peak-guarded the same way. */
    private fun sourceGain(dry: Snip): Float {
        var g = AUDITION_LEVEL / Loudness.of(dry).coerceAtLeast(1e-9f)
        val peak = dry.peak()
        if (peak * g > 0.99f) g = 0.99f / peak
        return g
    }

    private fun scaled(s: Snip, g: Float): Snip {
        val out = FloatArray(s.samples.size) { s.samples[it] * g }
        // A clip of the source grid shares the dry's peak (FORGE and VALVE peak-match), so this guard
        // only ever trims a sample the order A/B rounded over.
        val peak = out.maxOf { abs(it) }
        if (peak > 0.99f) for (i in out.indices) out[i] *= 0.99f / peak
        return Snip(out, s.channels, s.sampleRate)
    }

    private fun crest(s: Snip): Double {
        var sum = 0.0
        for (v in s.samples) sum += v.toDouble() * v
        val rms = sqrt(sum / s.samples.size.coerceAtLeast(1))
        return if (rms > 0) s.peak() / rms else 0.0
    }

    private fun q(s: String): String {
        check(s.none { it == '"' || it == '\\' || it < ' ' }) { "manifest field needs escaping: $s" }
        return "\"$s\""
    }
}
