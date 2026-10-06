package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File

/** Frozen, matched recipes for the listening repair. Capture before before editing the engine. */
object TremorComparisonGenerator {
    private data class Case(val id: String, val label: String, val role: String, val patch: TremorPatch)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/tremor-comparison/candidate").also { it.mkdirs() }
        val presets = TremorPresets.all().associateBy { it.name }
        val names = listOf("BROAD DRUM", "FOUR HANDS", "SETTLING BED", "DRY CAGE", "CHARGED TAIL", "BROKEN RETURN")
        val roles = listOf("Natural hide", "Coordinated strikers", "Returning beads", "Passive wire", "Powered bloom", "Interrupted return")
        val cases = names.mapIndexed { i, name ->
            val p = presets.getValue(name)
            Case(name.lowercase().replace(' ', '_'), name, roles[i], p.copy(macros = p.macros + ("TUNE" to 0.5f)))
        }.toMutableList()
        for ((voice, knob) in listOf(TremorVoice.ROLL to "BEADS", TremorVoice.WIRE to "CAGE", TremorVoice.CHARGE to "CURRENT", TremorVoice.HIDE to "STRIKE")) {
            for (v in listOf(0f, 1f)) {
                val id = "${voice.name.lowercase()}_${knob.lowercase()}_${v.toInt()}"
                val macros = Tremor.defaults(voice) + mapOf("TUNE" to 0.5f, knob to v)
                cases += Case(id, "${voice.name} · $knob ${v.toInt()}", "Matched endpoint", TremorPatch(id, voice, macros))
            }
        }
        for (name in listOf("HELD DRUM", "HELD BLOOM")) {
            val p = presets.getValue(name)
            cases += Case(name.lowercase().replace(' ', '_'), name, "Held region", p.copy(macros = p.macros + ("TUNE" to 0.5f)))
        }
        fun str(v: String) = JsonValue.Str(v)
        fun num(v: Number) = JsonValue.Num(v.toDouble())
        val clips = cases.map { c ->
            val r = Tremor.play(c.patch.voice, c.patch.macros)
            val snip = AuditionLevel.level(r.snip)
            WavWriter.write(File(root, "${c.id}.wav"), snip, WavWriter.BitDepth.PCM_16)
            JsonValue.Obj(linkedMapOf(
                "id" to str(c.id), "title" to str(c.label), "group" to str(if (c.role == "Matched endpoint") "Controls" else if (c.role == "Held region") "Held" else "Presets"),
                "role" to str(c.role), "voice" to str(c.patch.voice.name),
                "file" to str("${c.id}.wav"), "midi" to num(Tremor.midiFor(c.patch.voice, c.patch.macros.getValue("TUNE"))),
                "macros" to JsonValue.Obj(c.patch.macros.mapValues { num(it.value) }),
                "seconds" to num(snip.frameCount / Dsp.RATE.toDouble()), "loudness" to num(Loudness.of(snip)), "peak" to num(snip.peak()),
                "rawPeak" to num(r.rawPeak), "contacts" to num(r.contactCount), "contactImpulse" to num(r.contactImpulse),
                "circuitWork" to num(r.circuitWork), "actuatorPeak" to num(r.actuatorPeak), "stringEnergy" to num(r.stringEnergy),
                "faults" to num(r.faults.count { it.kind == "LOADED" }), "railHits" to num(r.railHits),
            ))
        }
        File(root, "manifest.json").writeText(Json.write(JsonValue.Obj(linkedMapOf(
            "engine" to str("tremor"), "phase" to str("tremor_listening_r2"), "version" to str(root.name),
            "levelTarget" to num(0.03), "levelMeasure" to str("Loudest 200 ms RMS; tails and layer balance remain audible"),
            "clips" to JsonValue.Arr(clips),
        ))))
        File(root, "recipes.json").writeText(Json.write(JsonValue.Arr(clips)))
        println("Rendered ${clips.size} matched Tremor clips to $root")
    }
}
