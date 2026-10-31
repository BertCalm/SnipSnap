package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.log10

/**
 * Renders FORGE's DEPTH gate under testkit/forge-depth-audition/ (gitignored): the two things the brief
 * left unheard after DEPTH shipped (docs/superpowers/specs/2026-10-25-forge-section-design-brief.md,
 * "DEPTH"). First, YIELD 0.25's harsh middle, which the owner kept at the first gate and the TIGHT medium
 * took off the kick: at the default CHARGE, DEPTH 1 (the AS BUILT medium) gives it back exactly and
 * DEPTH 0.75 gives about half. Second, DEPTH's in-between values, interpolated but never heard.
 *
 * Four sources, through the real section ([Forge.process], the shipped [Forge.Medium.forDepth]): the
 * THUMP kick and snare at YIELD 0.25 against DEPTH, with YIELD 0 and the defaults for reference, and the
 * TINES bell and the room recording's loudest hit at CHARGE 1 across DEPTH 0, 0.25, 0.5, 0.75 and 1.
 *
 * Levels as the first gate's: one gain per source, the dry clip's [AuditionLevel] gain. Writes
 * manifest.json, with the gate's own questions in `gate`, and index.html (the first gate's page, which
 * reads them). Run via `./gradlew :synth:generateForgeDepthAudition`.
 */
object ForgeDepthAuditionGenerator {

    private val DEPTHS = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

    private class Clip(val id: String, val label: String, val snip: Snip)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/forge-depth-audition")
        val repo = File(args.getOrNull(1) ?: "..")
        root.mkdirs()
        val sources = linkedMapOf(
            "KICK" to Thump.render(ThumpVoice.KICK),
            "SNARE" to Thump.render(ThumpVoice.SNARE),
            "BELL" to Tines.render(TinesVoice.BELL),
            "CHOP" to ForgeAuditionGenerator.chop(File(repo, "reference/live3 room take.wav")),
        )

        val json = StringBuilder("{\n")
        json.append(gateJson()).append(",\n\"sections\": [\n")
        var count = 0
        for ((si, entry) in sources.entries.withIndex()) {
            val (name, dry) = entry
            val clips = mutableListOf(Clip("dry", "$name DRY", dry))
            if (name == "KICK" || name == "SNARE") {
                clips += Clip("defaults", "$name FORGE DEFAULTS", Forge.process(dry))
                clips += Clip("yield_0", "$name YIELD 0", Forge.process(dry, mapOf("YIELD" to 0f)))
                for (d in listOf(0f, 0.75f, 1f)) {
                    clips += Clip("yield_025_${tag(d)}", "$name YIELD 0.25 AT DEPTH ${word(d)}", Forge.process(dry, mapOf("YIELD" to 0.25f, "DEPTH" to d)))
                }
            } else {
                for (d in DEPTHS) {
                    clips += Clip("charge_1_${tag(d)}", "$name CHARGE 1 AT DEPTH ${word(d)}", Forge.process(dry, mapOf("CHARGE" to 1f, "DEPTH" to d)))
                }
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
        val page = ForgeDepthAuditionGenerator::class.java.getResourceAsStream("/audition/forge-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { o -> page.use { it.copyTo(o) } }
        println("forge depth audition: $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    /** depth_0, depth_025, depth_05, depth_075, depth_1: a clip id. */
    private fun tag(d: Float): String = "depth_" + word(d).replace(".", "")

    private fun word(d: Float): String = if (d == d.toInt().toFloat()) d.toInt().toString() else d.toString()

    private fun noteFor(name: String): String = when (name) {
        "KICK" -> "YIELD 0.25 is the harsh middle you kept at the first gate. The TIGHT medium, DEPTH 0, smoothed it on this kick; DEPTH 1 is the medium you heard it on, and DEPTH 0.75 sits between. DEPTH 1 plays about 1.7 dB louder than DEPTH 0 here, and louder can pass for better: check the dB beside each clip."
        "SNARE" -> "The same YIELD 0.25 on the snare, where the harsh middle never went away: it is there at DEPTH 0 too."
        "BELL" -> "CHARGE 1 across DEPTH. 0 is TIGHT, 0.5 is SHORT (your pick on the bell), 1 is AS BUILT; 0.25 and 0.75 are in between and new."
        else -> "The room recording's loudest hit at CHARGE 1 across DEPTH. 1 is AS BUILT, your pick on the chop; 0.25 and 0.75 are in between and new."
    }

    private fun gateJson(): String {
        val kick = listOf("yield_0", "yield_025_depth_0", "yield_025_depth_075", "yield_025_depth_1", "defaults").map { "\"KICK/$it\"" }
        val snare = listOf("yield_0", "yield_025_depth_0", "yield_025_depth_1", "defaults").map { "\"SNARE/$it\"" }
        fun sweep(s: String) = DEPTHS.map { "\"$s/charge_1_${tag(it)}\"" }
        val questions = listOf(
            """{"id": "kick_grit", "title": "On the kick, YIELD 0.25: is the grit you kept at the first gate back at DEPTH 1?", """ +
                """"clips": [${kick.joinToString(", ")}], "options": ["Yes, DEPTH 1 is enough", "Bring it back at DEPTH 0 too", "I don't miss it"]}""",
            """{"id": "snare_grit", "title": "On the snare, YIELD 0.25 is still harsh at DEPTH 0. Keep it?", """ +
                """"clips": [${snare.joinToString(", ")}], "options": ["Keep it", "Smooth it", "Don't mind"]}""",
            """{"id": "bell_sweep", "title": "On the bell, does DEPTH move smoothly from TIGHT to AS BUILT?", """ +
                """"clips": [${sweep("BELL").joinToString(", ")}], "options": ["Smooth", "It jumps somewhere", "An in-between one sounds wrong"]}""",
            """{"id": "chop_sweep", "title": "On the chop, does DEPTH move smoothly from TIGHT to AS BUILT?", """ +
                """"clips": [${sweep("CHOP").joinToString(", ")}], "options": ["Smooth", "It jumps somewhere", "An in-between one sounds wrong"]}""",
        )
        return """"gate": {"id": "depth", "name": "FORGE depth gate", "title": "Forge Depth Gate", "heading": "FORGE · DEPTH", """ +
            """"intro": ${q("DEPTH shipped with only its three end points heard. Two things are left for your ears: YIELD 0.25's harsh middle, which the TIGHT medium smoothed on the kick and DEPTH 1 gives back, and DEPTH's in-between values. Nothing in FORGE changes until you answer.")}, """ +
            """"questionsTitle": "The gate: four questions", "task": "generateForgeDepthAudition", "questions": [${questions.joinToString(", ")}]}"""
    }

    private fun q(s: String): String = ForgeAuditionGenerator.q(s)
}
