package com.snipsnap.shell

import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.Arco
import com.snipsnap.synth.ArcoKeys
import com.snipsnap.synth.ArcoPatch
import com.snipsnap.synth.ArcoVoice
import com.snipsnap.synth.Keys
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The owner's A/B for ARCO's held keygroup ([ArcoPadMaker]): per voice, at the lowest, middle and top zone, a key held for
 * [HOLD_SECONDS] and then let go, three ways, each through the instrument player ([ArcoPadMaker.play], the preview's own path):
 *
 *  1. **held hard**: the new key's hard layer (the patch's own macros), the bow's stroke and then the loop;
 *  2. **held soft**: its soft layer (BOW and GRIP at [ArcoKeys.SOFT_SHARE] of the patch's), the same path;
 *  3. **loop only**: what ARCO's keys were until 2026-11-06, the shipped LOOP twice over with its marker at the second copy
 *     ([LoopPadMaker.renderZone], rendered at this exact note), played the same way.
 *
 * The patch is each voice's defaults. Every clip is levelled to one loudness ([AuditionLevel], the house rule for a fair A/B), so the
 * soft layer's lower touch gain is taken out and only its timbre is compared. 24-bit mono WAVs at 44.1 kHz and a `manifest.json` that
 * says what each file is. Nothing here has been heard. Run via `./gradlew :shell:generateArcoHeldAudition`; the first argument (or
 * `-ParcoHeldOutput=...`) is the folder.
 */
object ArcoHeldAuditionGenerator {

    const val HOLD_SECONDS = 8f
    private val RELEASE = ArcoPadMaker.RELEASE.default

    private class Clip(val kind: String, val audio: Snip, val fields: Map<String, JsonValue>)

    private fun num(v: Double) = JsonValue.Num(v)
    private fun str(v: String) = JsonValue.Str(v)
    private fun seconds(frames: Number) = num(frames.toDouble() / WavWriter.MPC_SAMPLE_RATE)

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "../testkit/arco-held-audition").canonicalFile.also { it.mkdirs() }
        val hold = (HOLD_SECONDS * WavWriter.MPC_SAMPLE_RATE).roundToInt()
        val manifest = ArrayList<JsonValue>()
        for (voice in ArcoVoice.entries) {
            val patch = ArcoPatch("ARCO ${voice.name} DEFAULTS", voice, Arco.defaults(voice))
            val spec = ArcoPadMaker.Spec(patch, RELEASE)
            val zones = ArcoPadMaker.zoneMidis(spec)
            for (midi in listOf(zones.first(), zones[zones.size / 2], zones.last())) {
                val clips = ArrayList<Clip>()
                for ((index, layer) in ArcoKeys.Layer.entries.withIndex().reversed()) {
                    val t0 = System.nanoTime()
                    val zone = ArcoKeys.pad(voice, patch.macros, midi, layer)
                    val ms = (System.nanoTime() - t0) / 1e6
                    val window = ArcoPadMaker.VELOCITY_WINDOWS[index]
                    val m = ArcoKeys.macrosFor(voice, patch.macros, midi, layer)
                    val start = zone.loopStartFrame.toInt()
                    val hard = layer == ArcoKeys.Layer.HARD
                    clips += Clip(
                        if (hard) "1 held hard" else "2 held soft",
                        ArcoPadMaker.play(zone, midi, window, hold, RELEASE),
                        linkedMapOf(
                            "what" to str(
                                if (hard) "the new held key, hard layer (velocity ${window.first} to ${window.last}): the patch's own BOW and GRIP"
                                else "the new held key, soft layer (velocity ${window.first} to ${window.last}): BOW and GRIP at ${ArcoKeys.SOFT_SHARE} of the patch's",
                            ),
                            "BOW" to num(m.getValue("BOW").toDouble()), "GRIP" to num(m.getValue("GRIP").toDouble()),
                            "loopStartSeconds" to seconds(start), "loopSeconds" to seconds(zone.snip.frameCount - start),
                            "seam" to num(Keys.seamError(zone.snip.samples, start)), "renderMs" to num(ms),
                        ),
                    )
                }
                val loopOnly = LoopPadMaker.renderZone(LoopPadMaker.Spec(patch, RELEASE), midi)
                clips += Clip(
                    "3 loop only",
                    ArcoPadMaker.play(loopOnly, midi, 0..127, hold, RELEASE),
                    linkedMapOf(
                        "what" to str("what ARCO's keys were until 2026-11-06: the shipped LOOP twice over, the marker at the second copy, no attack (LoopPadMaker), rendered at this exact note"),
                        "loopSeconds" to seconds(loopOnly.snip.frameCount - loopOnly.loopStartFrame),
                    ),
                )
                for (clip in clips) {
                    val file = File(out, "arco ${voice.name.lowercase(Locale.ROOT)} ${Scales.nameOf(midi)} ${clip.kind}.wav")
                    WavWriter.write(file, AuditionLevel.level(clip.audio), WavWriter.BitDepth.PCM_24)
                    manifest += JsonValue.Obj(linkedMapOf(
                        "file" to str(file.name), "voice" to str(voice.name), "note" to str(Scales.nameOf(midi)), "midi" to num(midi.toDouble()),
                        "holdSeconds" to num(HOLD_SECONDS.toDouble()), "releaseSeconds" to num(RELEASE.toDouble()),
                        "clipSeconds" to num(clip.audio.frameCount.toDouble() / clip.audio.sampleRate),
                    ) + clip.fields)
                    println("%-32s %s".format(Locale.ROOT, file.name, clip.fields.getValue("what").str()))
                }
            }
        }
        File(out, "manifest.json").writeText(Json.write(JsonValue.Obj(linkedMapOf(
            "title" to str("ARCO held keygroup A/B"),
            "patch" to str("each voice at its defaults (Arco.defaults)"),
            "level" to str("every clip levelled to one loudness (AuditionLevel), so the soft layer's lower touch gain is taken out: compare timbre, not level"),
            "player" to str("InstrumentEngine, the instrument preview's path: the key held for $HOLD_SECONDS s, then the program's RELEASE fade of $RELEASE s"),
            "heard" to str("nothing yet"),
            "clips" to JsonValue.Arr(manifest),
        ))) + "\n")
        println("wrote ${manifest.size} clips and manifest.json under ${out.absolutePath}")
    }
}
