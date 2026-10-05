package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File

/** Same recipes on both revisions; render the reference before changing DSP. */
object AerostatVoicingComparisonGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args[0]).apply { mkdirs() }
        val voice = AerostatVoice.FLOAT
        val base = Aerostat.defaults(voice) + ("TUNE" to 0f)
        data class Case(val id: String, val title: String, val group: String,
                        val macros: Map<String, Float> = base,
                        val tap: AerostatTap = AerostatTap.FULL, val velocity: Float = 1f)
        val cases = listOf(
            Case("low", "C3 · full instrument", "Start here"),
            Case("middle", "C4 · full instrument", "Start here", base + ("TUNE" to 0.5f)),
            Case("high", "C5 · full instrument", "Start here", base + ("TUNE" to 1f)),
            Case("tube", "C3 · tube attack alone", "Attack and air", tap = AerostatTap.TUBE),
            Case("flow", "C3 · airflow alone", "Attack and air", tap = AerostatTap.FLOW),
            Case("quick", "C3 · Quick bank", "Attack and air", tap = AerostatTap.QUICK),
            Case("heavy", "C3 · Heavy bank", "Attack and air", tap = AerostatTap.HEAVY),
            Case("soft", "C3 · soft velocity 0.30", "Touch and motion", velocity = 0.3f),
            Case("medium", "C3 · medium velocity 0.65", "Touch and motion", velocity = 0.65f),
            Case("lift0", "C3 · Lift 0", "Touch and motion", base + ("LIFT" to 0f)),
            Case("lift1", "C3 · Lift 1", "Touch and motion", base + ("LIFT" to 1f)),
            Case("release", "C3 · long release", "Release and hold", base + ("RELEASE" to 1f)),
            Case("steam", "Drifting Steam preset", "Release and hold", AerostatPresets.all().first { it.name == "DRIFTING STEAM" }.macros),
            Case("held", "C3 · held loop (no opening strike)", "Release and hold", base + ("HOLD" to 1f)),
        )
        val json = cases.joinToString(",\n") { c ->
            val snip = Aerostat.render(voice, c.macros, c.velocity, c.tap)
            WavWriter.write(File(root, "${c.id}.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
            """{"id":"${c.id}","title":"${c.title}","group":"${c.group}","tap":"${c.tap}","velocity":${c.velocity},"macros":{${c.macros.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}}"""
        }
        File(root, "recipes.json").writeText("[$json]\n")
        println("Rendered ${cases.size} Aerostat comparison clips to $root")
    }
}
