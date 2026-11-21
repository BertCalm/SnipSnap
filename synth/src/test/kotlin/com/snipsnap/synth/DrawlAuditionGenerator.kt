package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * DRAWL's first Kotlin audition: the native engine's renders under the same names as the Python
 * prototype's round-0d audition (`prototype/drawl/gen_audition.py`), so `compare_kotlin.py` can pair
 * them file for file and the owner can hear the two side by side. Writes `voices/`, `velocity/`,
 * `takes/` and `pairs/` WAVs and `recipes.json` under the folder given (testkit/drawl-audition/).
 * Nobody has heard these renders.
 */
object DrawlAuditionGenerator {

    private class Clip(val file: String, val patch: DrawlPatch)

    /** Each voice's strum, the chord and sweep the round-0 prototype page played (gen_audition.py's CHORDS). */
    private val chords = mapOf(
        DrawlVoice.CONE to (listOf(0, 7, 12, 16) to .06), DrawlVoice.BOTTLE to (listOf(0, 7, 12) to .06),
        DrawlVoice.LAP to (listOf(0, 3, 7, 12) to .06), DrawlVoice.STEEL to (listOf(0, 4, 7, 12) to .06),
        DrawlVoice.FLATTOP to (listOf(0, 4, 7, 12, 16, 19) to .09), DrawlVoice.GUT to (listOf(0, 3, 7, 12) to .06),
    )

    private fun clips(): List<Clip> {
        val out = mutableListOf<Clip>()
        for (voice in DrawlVoice.entries) {
            val v = voice.name.lowercase()
            fun add(file: String, macros: Map<String, Float> = emptyMap(), velocity: Float = Drawl.DEFAULT_VELOCITY, take: Int = 0) {
                out += Clip(file, DrawlPatch(File(file).nameWithoutExtension, voice, macros, velocity, take))
            }
            for ((tag, tune) in listOf("low" to .25f, "mid" to .5f, "high" to .75f)) add("voices/$v-$tag.wav", mapOf("TUNE" to tune))
            for ((tag, vel) in listOf("soft" to .3f, "medium" to .75f, "hard" to 1f)) add("velocity/$v-$tag.wav", velocity = vel)
            for (t in 0 until Drawl.TAKES) add("takes/$v-take$t.wav", take = t)
            for ((s, w) in listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1))
                add("pairs/$v-slide$s-waver$w.wav", mapOf("SLIDE" to s.toFloat(), "WAVER" to w.toFloat()))
            val (intervals, sweep) = chords.getValue(voice)
            for (direction in DrawlStrum.Direction.entries) {
                val strum = DrawlStrum.Articulation(intervals, sweep, direction)
                out += Clip("strum/$v-${direction.name.lowercase()}.wav", DrawlPatch("$v-${direction.name.lowercase()}", voice, strum = strum))
            }
        }
        return out
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "testkit/drawl-audition")
        root.mkdirs()
        val recipes = StringBuilder("[\n")
        val all = clips()
        for ((i, clip) in all.withIndex()) {
            val snip = clip.patch.render()
            val file = File(root, clip.file)
            file.parentFile.mkdirs()
            WavWriter.write(file, snip)
            recipes.append("  {\"file\": \"${clip.file}\", \"patch\": ${clip.patch.toJsonText()}}")
            recipes.append(if (i < all.size - 1) ",\n" else "\n")
        }
        recipes.append("]\n")
        File(root, "recipes.json").writeText(recipes.toString())
        // The kept renders' hashes, for synth/src/test/resources/drawl/ (DrawlNativePinsTest).
        val pins = StringBuilder()
        for ((id, patch) in DrawlNativePins.cases()) {
            val snip = patch.render()
            pins.append("$id ${snip.frameCount} ${DrawlNativePins.sha(snip.samples)}\n")
        }
        File(root, "native-kept.sha256").writeText(pins.toString())
        println("DRAWL audition: ${all.size} clips under $root")
    }
}
