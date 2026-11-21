package com.snipsnap.synth

import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * DRAWL's first Kotlin audition: the native engine's renders under the same names as the Python
 * prototype's round-0d audition (`prototype/drawl/gen_audition.py`), so `compare_kotlin.py` can pair
 * them file for file and the owner can hear the two side by side. Writes `voices/`, `velocity/`,
 * `takes/`, `pairs/` and `strum/` WAVs and `recipes.json` under the folder given (testkit/drawl-audition/),
 * and the kept renders' hashes for `synth/src/test/resources/drawl/`.
 */
object DrawlAuditionGenerator {

    private class Clip(val file: String, val patch: DrawlPatch)

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
            for (direction in DrawlStrum.Direction.entries) {
                val strum = DrawlPresets.STRUMS.getValue(voice).copy(direction = direction)
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
        val round2 = StringBuilder()
        for ((id, patch) in DrawlNativePins.round2Cases()) {
            val snip = patch.render()
            round2.append("$id ${snip.frameCount} ${DrawlNativePins.sha(snip.samples)}\n")
        }
        File(root, "native-round2.sha256").writeText(round2.toString())
        writeKits(root)
        println("DRAWL audition: ${all.size} clips under $root")
    }

    /**
     * Round 3: the DRAWL starter's sixteen pads (`kit/01.wav` to `kit/16.wav`) and every take of DRAWL
     * TAKES (`kit-takes/NN-zZ-tT.wav`, zone 0 soft to 2 hard, take 0 to 3), with `kit.json` naming them.
     */
    private fun writeKits(root: File) {
        val patches = DrawlKits.patches()
        val json = StringBuilder("[\n")
        for ((i, pad) in DrawlKits.arranged().withIndex()) {
            val n = "%02d".format(i + 1)
            File(root, "kit").mkdirs()
            WavWriter.write(File(root, "kit/$n.wav"), pad.snip)
            json.append("  {\"pad\": ${i + 1}, \"name\": \"${pad.displayName}\", \"voice\": \"${patches[i].voice}\", " +
                "\"note\": \"${pad.source["rootNote"]}\", \"recipe\": ${patches[i].toJsonText()}}")
            json.append(if (i < patches.size - 1) ",\n" else "\n")
        }
        File(root, "kit.json").writeText(json.append("]\n").toString())
        File(root, "kit-takes").mkdirs()
        val pins = StringBuilder()
        for ((i, pad) in DrawlKits.arranged().withIndex())
            pins.append("drawl-%02d ${pad.snip.frameCount} ${DrawlNativePins.sha(pad.snip.samples)}\n".format(i + 1))
        for ((i, pad) in DrawlKits.takesArranged().withIndex()) for ((z, zone) in pad.velocityTakes.withIndex())
            for ((t, snip) in zone.withIndex()) {
                WavWriter.write(File(root, "kit-takes/%02d-z$z-t$t.wav".format(i + 1)), snip)
                pins.append("takes-%02d-z$z-t$t ${snip.frameCount} ${DrawlNativePins.sha(snip.samples)}\n".format(i + 1))
            }
        File(root, "kits.sha256").writeText(pins.toString())
    }
}
