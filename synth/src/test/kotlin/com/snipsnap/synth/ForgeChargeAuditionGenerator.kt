package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.log10

/**
 * Renders FORGE's CHARGE tuning gate under testkit/forge-charge-audition/ (gitignored). At the first
 * gate the owner heard CHARGE's folded sweep as "mixed": thicker on one source, a laser zap on another
 * (docs/superpowers/specs/2026-10-25-forge-section-design-brief.md, "Heard"). The likeliest zap is the
 * medium itself: at CHARGE 1 its 24 all-passes from 300 Hz stretch a click into a 14 ms rising sweep,
 * most of it the low end arriving late.
 *
 * Four media, through the real section ([Forge.process] with a candidate [Forge.Medium]):
 * AS BUILT (24 sections, 300 Hz to 6 kHz), SHORT (12, the same band: half the sweep), HIGH (24, from
 * 1.2 to 8 kHz: no low-end delay) and TIGHT (12, 1.2 to 8 kHz). On four sources (the TINES bell and
 * the THUMP kick the first gate asked about, the THUMP snare, the room recording's loudest hit), each
 * candidate at CHARGE 1 with the rest at their defaults, and as the FORGE chip at full (`forged`:
 * every default, ARREST 1), plus the dry pad and CHARGE 0 for reference.
 *
 * Levels as the first gate's: one gain per source, the dry clip's [AuditionLevel] gain, so every clip
 * keeps the peak the rack gives it. Writes manifest.json, with the gate's own questions in `gate`, and
 * index.html (the first gate's page, which reads them). Run via `./gradlew :synth:generateForgeChargeAudition`.
 */
object ForgeChargeAuditionGenerator {

    internal val CANDIDATES: List<Pair<String, Forge.Medium>> = listOf(
        "AS_BUILT" to Forge.Medium.SHIPPED,
        "SHORT" to Forge.Medium(12, 300f, 6_000f, 0.7f),
        "HIGH" to Forge.Medium(24, 1_200f, 8_000f, 0.7f),
        "TIGHT" to Forge.Medium(12, 1_200f, 8_000f, 0.7f),
    )

    private val CHIP = mapOf("CHARGE" to 0.5f, "STANDOFF" to 0.5f, "YIELD" to 0.5f, "ARREST" to 1f)

    private class Clip(val id: String, val label: String, val snip: Snip)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/forge-charge-audition")
        val repo = File(args.getOrNull(1) ?: "..")
        root.mkdirs()
        val sources = linkedMapOf(
            "BELL" to Tines.render(TinesVoice.BELL),
            "KICK" to Thump.render(ThumpVoice.KICK),
            "SNARE" to Thump.render(ThumpVoice.SNARE),
            "CHOP" to ForgeAuditionGenerator.chop(File(repo, "reference/live3 room take.wav")),
        )

        val json = StringBuilder("{\n")
        json.append(gateJson(sources.keys.toList())).append(",\n\"sections\": [\n")
        var count = 0
        for ((si, entry) in sources.entries.withIndex()) {
            val (name, dry) = entry
            val clips = mutableListOf(
                Clip("dry", "$name DRY", dry),
                Clip("charge_0", "$name CHARGE 0 (NO MEDIUM)", Forge.process(dry, mapOf("CHARGE" to 0f))),
            )
            for ((cand, medium) in CANDIDATES) {
                val word = cand.replace('_', ' ')
                clips += Clip("${cand.lowercase()}_charge_1", "$name $word CHARGE 1", Forge.process(dry, mapOf("CHARGE" to 1f), true, medium))
                clips += Clip("${cand.lowercase()}_chip", "$name $word CHIP", Forge.process(dry, CHIP, true, medium))
            }
            val gain = ForgeAuditionGenerator.sourceGain(dry)
            val dir = File(root, name).apply { mkdirs() }
            json.append("  {\"id\": ${q(name)}, \"title\": ${q(name)}, \"note\": ${q(noteFor(name))}, \"clips\": [\n")
            for ((ci, clip) in clips.withIndex()) {
                WavWriter.write(File(dir, "${clip.id}.wav"), ForgeAuditionGenerator.scaled(clip.snip, gain), WavWriter.BitDepth.PCM_16)
                val loud = 20 * log10(Loudness.of(clip.snip) / Loudness.of(dry).coerceAtLeast(1e-9f))
                json.append("    {\"id\": ${q(clip.id)}, \"file\": ${q("$name/${clip.id}.wav")}, \"label\": ${q(clip.label)}, ")
                json.append("\"crest\": ${"%.2f".format(ForgeAuditionGenerator.crest(clip.snip))}, \"loudnessDb\": ${"%.2f".format(loud)}}")
                json.append(if (ci < clips.size - 1) ",\n" else "\n")
                count++
            }
            json.append("  ]}").append(if (si < sources.size - 1) ",\n" else "\n")
        }
        json.append("]}\n")
        File(root, "manifest.json").writeText(json.toString())
        val page = ForgeChargeAuditionGenerator::class.java.getResourceAsStream("/audition/forge-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { o -> page.use { it.copyTo(o) } }
        println("forge charge audition: $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    private fun noteFor(name: String): String = when (name) {
        "BELL" -> "The TINES bell from the first gate's chirp question. HIGH keeps its attack exactly as bright as AS BUILT with a third of the sweep."
        "KICK" -> "The THUMP kick from the first gate's chirp question. Every candidate tames the bright sweep on its attack; AS BUILT has the most."
        "SNARE" -> "A THUMP snare, to hear the candidates on a bright body."
        else -> "The loudest hit in the room recording: the candidates on a captured chop."
    }

    private fun gateJson(sources: List<String>): String {
        val pretty = mapOf("BELL" to "the bell", "KICK" to "the kick", "SNARE" to "the snare", "CHOP" to "the chop")
        val questions = mutableListOf(
            """{"id": "zap", "title": "At the first gate, which one did CHARGE turn into a laser zap?", """ +
                """"clips": ["BELL/as_built_charge_1", "KICK/as_built_charge_1"], "options": ["The bell", "The kick", "Both", "Neither"]}""",
        )
        for (s in sources) {
            val clips = CANDIDATES.map { "\"$s/${it.first.lowercase()}_charge_1\"" } + CANDIDATES.map { "\"$s/${it.first.lowercase()}_chip\"" }
            questions += """{"id": "${s.lowercase()}", "title": "On ${pretty.getValue(s)}, which medium do you like best, at CHARGE 1 and on the chip?", """ +
                """"clips": [${clips.joinToString(", ")}], "options": ["As built", "Short", "High", "Tight"]}"""
        }
        return """"gate": {"id": "charge", "name": "FORGE charge gate", "title": "Forge Charge Gate", "heading": "FORGE · CHARGE", """ +
            """"intro": ${q("At the first gate CHARGE's sweep read as mixed: thicker on one source, a laser zap on another. These are four shapes of the medium, the all-pass stage CHARGE drives, through the real section. AS BUILT is what you heard; SHORT halves its sweep; HIGH keeps it out of the low end; TIGHT does both. Each plays at CHARGE 1 and as the FORGE chip. Nothing here has been heard yet.")}, """ +
            """"questionsTitle": "The gate: five questions", "task": "generateForgeChargeAudition", "questions": [${questions.joinToString(", ")}]}"""
    }

    private fun q(s: String): String = ForgeAuditionGenerator.q(s)
}
