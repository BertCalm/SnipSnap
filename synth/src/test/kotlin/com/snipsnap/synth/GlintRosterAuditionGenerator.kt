package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos

/**
 * The GLINT roster audition (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2):
 * [GlintRosterCandidates]' sixteen candidates per voice, each rendered by the engine as a held pad on one zone
 * near the middle of the keyboard (the sound MAKE INSTRUMENT gives), five seconds long, levelled by
 * [AuditionLevel] and named `<VOICE>-01` to `<VOICE>-16`, for the owner to hear on his phone and answer with
 * the numbers to keep. A clip carries no settings, so nothing is heard through a number; `manifest.txt` beside
 * the clips says what each one is, for whoever wires the picks, and is not sent. Not a test - run with
 * `generateGlintRosterAudition`.
 */
object GlintRosterAuditionGenerator {

    /** The zone is the fifth of [Keys.glintPadMidis]' nine: an octave above the voice's lowest key. */
    private const val ZONE = 4

    /** The spec's "5 s each". [GlintPathsAuditionGenerator.heldFor] plays longer, so the clip is cut. */
    private const val SECONDS = 5f

    /** Long enough that the cut is no click, short enough to leave the pad's body alone. */
    private const val FADE_SECONDS = 0.05f

    private const val MANIFEST = "manifest.txt"
    private val CLIP_FILE = Regex("[A-Z]+-\\d\\d\\.wav")

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "../testkit/glint-roster-candidates")
        prepare(dir)
        val lines = GlintVoice.entries.flatMap { renderVoice(it, dir) }
        File(dir, MANIFEST).writeText(manifestHeader() + lines.joinToString("\n", postfix = "\n"))
        println("wrote ${lines.size} clips and $MANIFEST to ${dir.absolutePath}")
    }

    /**
     * Makes the folder and empties it of this tool's own files, so a rerun cannot leave an earlier draw's clip
     * beside the new one. Only `<VOICE>-NN.wav` and `manifest.txt` go; anything else in the folder stays.
     */
    internal fun prepare(dir: File) {
        dir.mkdirs()
        dir.listFiles { f -> f.isFile && (f.name == MANIFEST || CLIP_FILE.matches(f.name)) }?.forEach { it.delete() }
    }

    /** Writes [voice]'s sixteen clips into [dir] and returns their manifest lines, in clip order. */
    internal fun renderVoice(voice: GlintVoice, dir: File): List<String> =
        GlintRosterCandidates.forVoice(voice).map { c ->
            WavWriter.write(File(dir, clipName(voice, c.number)), clipFor(voice, c), WavWriter.BitDepth.PCM_16)
            manifestLine(voice, c)
        }

    internal fun clipName(voice: GlintVoice, number: Int): String =
        String.format(Locale.ROOT, "%s-%02d.wav", voice.name, number)

    /**
     * The candidate's held pad as the owner hears it: the first [SECONDS] of the key's onset and breaths, the
     * last [FADE_SECONDS] faded to nothing (the held render runs on, so the cut lands mid-waveform), then
     * levelled. The fade comes first; the level is one gain, so the order does not change the loudness.
     */
    internal fun clipFor(voice: GlintVoice, candidate: GlintRosterCandidates.Candidate): Snip {
        val note = Keys.glintPad(voice, candidate.macros, Keys.glintPadMidis(voice)[ZONE])
        val played = GlintPathsAuditionGenerator.heldFor(note, SECONDS)
        val samples = played.samples.copyOf((SECONDS * played.sampleRate).toInt())
        val fade = (FADE_SECONDS * played.sampleRate).toInt()
        for (i in 0 until fade) {
            val gain = 0.5 * (1.0 + cos(PI * (i + 1) / fade))
            val at = samples.size - fade + i
            samples[at] = (samples[at] * gain).toFloat()
        }
        return AuditionLevel.level(Snip(samples, channels = 1, sampleRate = played.sampleRate))
    }

    /** `SWEEP-01  PEAK=0.800 FOLLOW=0.133 ...`: the clip's name, then each varied macro to three places, `.` as the point whatever the locale. */
    internal fun manifestLine(voice: GlintVoice, candidate: GlintRosterCandidates.Candidate): String =
        clipName(voice, candidate.number).removeSuffix(".wav") + "  " +
            GlintRosterCandidates.varied(voice).joinToString(" ") {
                String.format(Locale.ROOT, "%s=%.3f", it, candidate.macros.getValue(it))
            }

    private fun manifestHeader(): String = buildString {
        appendLine("GLINT roster candidates (docs/superpowers/specs/2026-10-01-glint-depth-and-presets-design.md §6.2)")
        appendLine("seed ${GlintRosterCandidates.SEED} plus the voice's ordinal; DEPTH spans 0 to ${GlintRosterCandidates.DEPTH_MAX}; TUNE and DECAY at their defaults")
        appendLine("zone MIDI: " + GlintVoice.entries.joinToString(", ") { "${it.name} ${Keys.glintPadMidis(it)[ZONE]}" })
        appendLine()
    }
}
