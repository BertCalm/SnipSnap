package com.snipsnap.shell

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Scales
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.synth.Arco
import com.snipsnap.synth.ArcoKeys
import com.snipsnap.synth.ArcoPatch
import com.snipsnap.synth.ArcoPresets
import com.snipsnap.synth.ArcoVoice
import com.snipsnap.synth.Keys
import java.io.File
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The owner's first listen to SARANGI (R2), ARCO's third voice: the bow in a sarangi's register, D3 to D5, into ERHU's skin, then the tarab
 * (37 sympathetic strings, D3 to D6 every semitone) that WASH sets in BODY's place. Five groups, every clip levelled to one loudness
 * ([AuditionLevel], the house rule for a fair A/B), 24-bit mono WAVs at 44.1 kHz, and a `manifest.json` that says what each file is:
 *
 *  1. **presets**: the six factory presets as a pad plays them (a LOOP preset played three times end to end, so the wrap is heard twice);
 *  2. **WASH**: a three-second held note (the HOLD that bows for 3 s, the pass rule's note) at D3, D4 and D5 at WASH 0, 0.5 and 1, every other knob at
 *     its default: no tarab, the default tarab, the most tarab;
 *  3. **loop**: the default LOOP at D4 (WASH 0.5) three times end to end;
 *  4. **held**: the held key ([ArcoPadMaker], its keys shaped by [com.snipsnap.synth.HeldStart.SHORT]) at the lowest, middle and top zone (D3, D4,
 *     D5), hard and soft layers, held for [HOLD_SECONDS] and let go over the maker's default RELEASE, through the instrument player;
 *  5. **reference**: ERHU at the notes SARANGI shares with it (D4 and D5, the three-second held note; and ERHU's held D4 key, hard), beside
 *     SARANGI's own: the same string and the same skin, without the tarab and with ERHU's plain vibrato.
 *
 * Nothing here has been heard. Run via `./gradlew :shell:generateArcoSarangiAudition`; the first argument (or `-ParcoSarangiOutput=...`) is
 * the folder.
 */
object ArcoSarangiAuditionGenerator {

    const val HOLD_SECONDS = 6f
    private val RELEASE = ArcoPadMaker.RELEASE.default
    private val SARANGI = ArcoVoice.SARANGI

    private class Clip(val file: String, val group: String, val what: String, val audio: Snip, val fields: Map<String, JsonValue> = emptyMap())

    private fun num(v: Double) = JsonValue.Num(v)
    private fun str(v: String) = JsonValue.Str(v)
    private fun f(v: Double, d: Int = 2) = "%.${d}f".format(Locale.ROOT, v)

    private fun mono(samples: FloatArray) = Snip(samples, channels = 1, sampleRate = WavWriter.MPC_SAMPLE_RATE)

    private fun thrice(loop: FloatArray) = mono(loop + loop + loop)

    /** The HOLD that bows for [seconds]: [Arco.holdSeconds] turned round, from its public constants (`:synth`'s own inverse is internal to it). */
    private fun holdFor(seconds: Float): Float =
        (Arco.LOOP_THRESHOLD * ln(seconds / Arco.HOLD_MIN_SECONDS) / ln(Arco.HOLD_MAX_SECONDS / Arco.HOLD_MIN_SECONDS)).coerceIn(0f, Arco.SCRAMBLE_HOLD_CEILING)

    /** The tarab's t60 and its size against the string at [wash], from the engine's public constants (its own maps are internal to `:synth`). */
    private fun washT60(wash: Float) = Arco.WASH_T60_LOW_SECONDS + (Arco.WASH_T60_HIGH_SECONDS - Arco.WASH_T60_LOW_SECONDS) * wash
    private fun washAmount(wash: Float) = Arco.WASH_AMOUNT_HIGH * wash

    private fun macroFields(voice: ArcoVoice, macros: Map<String, Float>): Map<String, JsonValue> {
        val m = Arco.defaults(voice) + macros.filterKeys { it in Arco.defaults(voice) }
        return Arco.macrosFor(voice).associate { it.name to num(m.getValue(it.name).toDouble()) } +
            mapOf("note" to str(Scales.nameOf(Arco.midiFor(voice, m.getValue("TUNE")))))
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "../testkit/arco-sarangi-audition").canonicalFile.also { it.mkdirs() }
        val clips = ArrayList<Clip>()
        val t0 = System.nanoTime()

        // 1. presets
        for ((i, preset) in ArcoPresets.forVoice(SARANGI).withIndex()) {
            val loop = Arco.isLoop(preset.macros.getValue("HOLD"))
            val audio = if (loop) thrice(preset.render().samples) else preset.render()
            val seam = if (loop) mapOf("loopSeconds" to num(preset.render().frameCount.toDouble() / WavWriter.MPC_SAMPLE_RATE)) else emptyMap()
            clips += Clip(
                "sarangi 1 preset ${i + 1} ${preset.name.lowercase(Locale.ROOT)}.wav", "presets",
                if (loop) "the factory preset ${preset.name}, a LOOP, played three times end to end" else "the factory preset ${preset.name} as a pad plays it",
                audio, macroFields(SARANGI, preset.macros) + seam,
            )
        }

        // 2. WASH at the low, middle and top note, a three-second held note
        val held3 = holdFor(3f)
        for ((label, tune) in listOf("d3" to 0f, "d4" to 0.5f, "d5" to 1f)) for (wash in listOf(0f, 0.5f, 1f)) {
            val m = Arco.defaults(SARANGI) + mapOf("TUNE" to tune, "HOLD" to held3, "WASH" to wash)
            val what = when (wash) {
                0f -> "no tarab (WASH 0): the bow and ERHU's skin alone"
                0.5f -> "the default tarab (WASH 0.5): t60 ${f(washT60(wash).toDouble())} s, the bank ${f(washAmount(wash).toDouble())} times the string's RMS"
                else -> "the most tarab (WASH 1): t60 ${f(washT60(wash).toDouble())} s, the bank ${f(washAmount(wash).toDouble())} times the string's RMS"
            }
            clips += Clip("sarangi 2 wash $label wash ${f(wash.toDouble(), 1)}.wav", "WASH", "a three-second held note, $what", Arco.render(SARANGI, m), macroFields(SARANGI, m))
        }

        // 3. one LOOP
        val loopMacros = Arco.defaults(SARANGI) + ("HOLD" to 1f)
        val loop = Arco.render(SARANGI, loopMacros).samples
        clips += Clip(
            "sarangi 3 loop d4 defaults.wav", "loop", "the default LOOP at D4 (WASH 0.5), three times end to end: the wrap is heard twice",
            thrice(loop), macroFields(SARANGI, loopMacros) + mapOf("loopSeconds" to num(loop.size.toDouble() / WavWriter.MPC_SAMPLE_RATE)),
        )

        // 4. the held key, hard and soft, at the lowest, middle and top zone
        val hold = (HOLD_SECONDS * WavWriter.MPC_SAMPLE_RATE).roundToInt()
        val patch = ArcoPatch("SARANGI DEFAULTS", SARANGI, Arco.defaults(SARANGI))
        val zones = ArcoKeys.zoneMidis(SARANGI)
        for (midi in listOf(zones.first(), zones[zones.size / 2], zones.last())) {
            for ((index, layer) in ArcoKeys.Layer.entries.withIndex().reversed()) {
                val zone = ArcoKeys.pad(SARANGI, patch.macros, midi, layer)
                val window = ArcoPadMaker.VELOCITY_WINDOWS[index]
                val m = ArcoKeys.macrosFor(SARANGI, patch.macros, midi, layer)
                val hard = layer == ArcoKeys.Layer.HARD
                val start = zone.loopStartFrame.toInt()
                clips += Clip(
                    "sarangi 4 held ${Scales.nameOf(midi).lowercase(Locale.ROOT)} ${if (hard) "1 hard" else "2 soft"}.wav", "held",
                    "the held key, ${if (hard) "hard layer: the patch's own BOW and GRIP" else "soft layer: BOW and GRIP at ${ArcoKeys.SOFT_SHARE} of the patch's"}, " +
                        "held $HOLD_SECONDS s and let go over RELEASE $RELEASE s, through the instrument player",
                    ArcoPadMaker.play(zone, midi, window, hold, RELEASE),
                    macroFields(SARANGI, m) + mapOf(
                        "loopStartSeconds" to num(start.toDouble() / WavWriter.MPC_SAMPLE_RATE),
                        "seam" to num(Keys.seamError(zone.snip.samples, start)),
                    ),
                )
            }
        }

        // 5. ERHU beside SARANGI at the notes they share
        val erhu = ArcoVoice.ERHU
        for ((label, midi) in listOf("d4" to 62, "d5" to 74)) {
            val tune = (midi - Arco.rootMidi(erhu)) / Arco.tuneSemitones(erhu).toFloat()
            val m = Arco.defaults(erhu) + mapOf("TUNE" to tune, "HOLD" to held3)
            check(Arco.midiFor(erhu, tune) == midi)
            clips += Clip(
                "sarangi 5 reference erhu $label.wav", "reference",
                "ERHU at ${Scales.nameOf(midi)}, the three-second held note at its defaults: the same bow and skin as SARANGI's, no tarab, ERHU's plain vibrato. Compare with sarangi 2 wash $label",
                Arco.render(erhu, m), macroFields(erhu, m),
            )
        }
        val erhuPatch = ArcoPatch("ERHU DEFAULTS", erhu, Arco.defaults(erhu))
        val erhuZone = ArcoKeys.pad(erhu, erhuPatch.macros, 62, ArcoKeys.Layer.HARD)
        clips += Clip(
            "sarangi 5 reference erhu held d4 1 hard.wav", "reference",
            "ERHU's held D4 key, hard layer, the same hold and release: compare with sarangi 4 held d4 1 hard",
            ArcoPadMaker.play(erhuZone, 62, ArcoPadMaker.VELOCITY_WINDOWS.last(), hold, RELEASE),
            macroFields(erhu, ArcoKeys.macrosFor(erhu, erhuPatch.macros, 62, ArcoKeys.Layer.HARD)),
        )

        val manifest = ArrayList<JsonValue>()
        for (clip in clips) {
            val file = File(out, clip.file)
            val levelled = AuditionLevel.level(clip.audio)
            WavWriter.write(file, levelled, WavWriter.BitDepth.PCM_24)
            manifest += JsonValue.Obj(linkedMapOf(
                "file" to str(clip.file), "path" to str(file.absolutePath), "group" to str(clip.group), "what" to str(clip.what),
                "seconds" to num(clip.audio.frameCount.toDouble() / clip.audio.sampleRate),
                "loudnessBeforeLevelling" to num(Loudness.of(clip.audio).toDouble()),
            ) + clip.fields)
            println("%-44s %s".format(Locale.ROOT, clip.file, clip.what))
        }
        File(out, "manifest.json").writeText(Json.write(JsonValue.Obj(linkedMapOf(
            "title" to str("ARCO SARANGI, the first listen"),
            "voice" to str("SARANGI: D3 to D5, ERHU's skin at BODY's default, the tarab (37 strings, D3 to D6 chromatic) set by WASH in BODY's place"),
            "level" to str("every clip levelled to one loudness (AuditionLevel): compare timbre, not level"),
            "player" to str("the held keys play through InstrumentEngine, the preview's path: held $HOLD_SECONDS s, then the program's RELEASE fade of $RELEASE s"),
            "heard" to str("nothing yet"),
            "clips" to JsonValue.Arr(manifest),
        ))) + "\n")
        println("wrote ${clips.size} clips and manifest.json under ${out.absolutePath} in ${f((System.nanoTime() - t0) / 1e9, 1)} s")
    }
}
