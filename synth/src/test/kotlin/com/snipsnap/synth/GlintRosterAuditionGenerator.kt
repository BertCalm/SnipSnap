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
 * near the middle of the keyboard (the sound MAKE INSTRUMENT gives), five seconds long (longer where STEP's
 * climb needs it, see [clipFor]), levelled by
 * [AuditionLevel] and named `<VOICE>-01` to `<VOICE>-16`, for the owner to hear on his phone and answer with
 * the numbers to keep. A clip carries no settings, so nothing is heard through a number; `manifest.txt` beside
 * the clips says what each one is, for whoever wires the picks, and is not sent. Not a test - run with
 * `generateGlintRosterAudition`.
 */
object GlintRosterAuditionGenerator {

    /** The zone is the fifth of [Keys.glintPadMidis]' nine: an octave above the voice's lowest key. */
    private const val ZONE = 4

    /**
     * The spec's "5 s each", the least a clip runs. [GlintPathsAuditionGenerator.heldFor] plays longer, so the
     * clip is cut, and only a long onset (STEP's climb) makes it longer still: see [clipFor].
     */
    private const val SECONDS = 5f

    /** Long enough that the cut is no click, short enough to leave the pad's body alone. */
    private const val FADE_SECONDS = 0.05f

    private const val MANIFEST = "manifest.txt"

    /** This tool's own clip names, `<VOICE>-NN.wav` for the four voices and nothing that merely looks like one. */
    private val CLIP_FILE = Regex("(${GlintVoice.entries.joinToString("|") { it.name }})-\\d\\d\\.wav")

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
     * beside the new one. Only `<VOICE>-NN.wav` and `manifest.txt` go; anything else in the folder stays. A file
     * that cannot be removed stops the run, naming it: a stale clip left silently is what this exists to prevent.
     * [remove] is the deletion, a parameter so a test can make it fail.
     */
    internal fun prepare(dir: File, remove: (File) -> Boolean = { it.delete() }) {
        dir.mkdirs()
        dir.listFiles { f -> f.isFile && (f.name == MANIFEST || CLIP_FILE.matches(f.name)) }?.forEach {
            check(remove(it)) { "could not remove ${it.name} from ${dir.absolutePath}; a stale clip would sit beside the new set" }
        }
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
     * The MIDI note every clip of [voice] is rendered at. It is TUNE 0.5, the pitch GLINT's key tracking pivots
     * on ([Glint.referenceHz]), so FOLLOW has nothing to do here and cannot be heard: that is why the spread
     * leaves it at its default.
     */
    internal fun zoneMidi(voice: GlintVoice): Int = Keys.glintPadMidis(voice)[ZONE]

    /**
     * The candidate's held pad as the owner hears it: the first [SECONDS] of the key's onset and breaths, or, when
     * the onset and one whole breath need longer, up to the end of that breath, which is the note's loop marker.
     * STEP climbs a ladder of harmonics before it holds, so a long climb would otherwise leave the owner little
     * of the held sound to judge BLOOM's breathing by. The last [FADE_SECONDS] are faded to nothing (the held
     * render runs on, so the cut lands mid-waveform), then the clip is levelled. The fade comes first so that
     * the level is measured on the clip as it will be heard.
     */
    internal fun clipFor(voice: GlintVoice, candidate: GlintRosterCandidates.Candidate): Snip {
        val note = Keys.glintPad(voice, candidate.macros, zoneMidi(voice))
        val played = GlintPathsAuditionGenerator.heldFor(note, SECONDS)
        val frames = maxOf((SECONDS * played.sampleRate).toInt(), note.loopStartFrame.toInt())
        val samples = played.samples.copyOf(frames)
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
        appendLine("seed ${GlintRosterCandidates.SEED} plus the voice's ordinal; DEPTH spans 0 to ${GlintRosterCandidates.DEPTH_MAX}; TUNE, DECAY and FOLLOW (VOWEL has none) at their defaults")
        appendLine("zone MIDI: " + GlintVoice.entries.joinToString(", ") { "${it.name} ${zoneMidi(it)}" })
        appendLine()
    }
}
