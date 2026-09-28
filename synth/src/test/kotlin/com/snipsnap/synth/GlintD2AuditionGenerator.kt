package com.snipsnap.synth

import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs

/**
 * Renders the GLINT depth pass (D2) audition set under
 * testkit/glint-d2-audition/ (gitignored): WAVs land under a clips/
 * subdirectory and the listening page (committed at
 * synth/src/test/resources/audition/glint-d2-audition.html, which references
 * its clips as `clips/<name>.wav`) is copied alongside them as index.html —
 * the same layout `GlintD1AuditionGenerator` uses. Run via
 * `./gradlew :synth:generateGlintD2Audition`.
 *
 * Six sections carry the three new voices' own questions rather than
 * repeating D1's BODY/BLOOM/velocity survey:
 *  A. the three new voices at their own defaults, cold
 *  B. the old three at their defaults too — A + B is all six voices, back
 *     to back
 *  C. CICADA's lattice — the carrier re-clocking `CICADA_SUBCYCLES` times a
 *     cycle — across BLOOM
 *  D. RATCHET's ladder — the harmonic staircase that steps only at the
 *     phase wrap, each BLOOM's rungs printed from `Glint.ratchetLadder` so
 *     they are on the record
 *  E. PLATE's coupling — the formant tied to the amplitude envelope, so a
 *     longer note holds its brightness longer in absolute time than a
 *     shorter one, at the same wall-clock instant
 *  F. the dissent — RATCHET's toy-like "bleep" across PEAK, the spec's own
 *     case that the bleep is a lineage rather than a defect
 *
 * The clip list lives in two places — this generator's `write` calls and the
 * page's `clips/<name>.wav` references — with nothing else keeping them in
 * sync, so `main` finishes by diffing the rendered set against what the page
 * references and fails loudly if either side has something the other lacks.
 *
 * See docs/superpowers/specs/2026-09-26-glint-depth-design.md and
 * `.superpowers/sdd/2026-09-27-glint-depth-d2/`.
 */
object GlintD2AuditionGenerator {

    /** Quiet on purpose: low enough that no clip needs the peak guard. */
    private const val AUDITION_LEVEL = 0.03f

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/glint-d2-audition")
        val clipsDir = File(root, "clips")
        clipsDir.mkdirs()
        var count = 0

        fun write(voice: GlintVoice, name: String, macros: Map<String, Float>) {
            val snip = Glint.render(voice, macros)
            val leveled = level(snip)
            WavWriter.write(File(clipsDir, "$name.wav"), leveled, WavWriter.BitDepth.PCM_16)
            count++
            val loudness = Loudness.of(leveled)
            var peak = 0f
            for (v in leveled.samples) peak = maxOf(peak, abs(v))
            println("$name: frames=${leveled.frameCount} loudness=$loudness peak=$peak")
        }

        // A. THE THREE NEW VOICES, COLD — each at its own real defaults, no
        // overrides.
        write(GlintVoice.CICADA, "a01_cicada_default", emptyMap())
        write(GlintVoice.RATCHET, "a02_ratchet_default", emptyMap())
        write(GlintVoice.PLATE, "a03_plate_default", emptyMap())

        // B. THE OLD THREE, SAME TREATMENT. A + B together is all six GLINT
        // voices at their defaults, back to back — a six-voice comparison.
        write(GlintVoice.REED, "b04_reed_default", emptyMap())
        write(GlintVoice.BOTTLE, "b05_bottle_default", emptyMap())
        write(GlintVoice.KAZOO, "b06_kazoo_default", emptyMap())

        // C. CICADA'S LATTICE. CICADA_SUBCYCLES re-clocks the carrier inside
        // every cycle regardless of BLOOM; BLOOM here sweeps the ratio those
        // sub-cycles carry, same as it sweeps every other lattice-free
        // voice. Everything but BLOOM stays at CICADA's own defaults.
        write(GlintVoice.CICADA, "c07_cicada_bloom0", mapOf("BLOOM" to 0f))
        write(GlintVoice.CICADA, "c08_cicada_bloom05", mapOf("BLOOM" to 0.5f))
        write(GlintVoice.CICADA, "c09_cicada_bloom1", mapOf("BLOOM" to 1f))

        // D. RATCHET'S LADDER. DECAY 0.9 gives the note room for several
        // 150ms steps. TUNE/PEAK/FOLLOW stay at RATCHET's own defaults, so
        // kBase here is the same 8.0 GlintTest's own
        // `RATCHET's steps land on integer harmonics` already measured and
        // locked down. Each BLOOM's ladder is printed from the exact
        // function the render loop reads from — `Glint.ratchetLadder` — so
        // the rungs a listener hears are the rungs on the record, not a
        // hand-typed guess.
        println("--- section D: Glint.ratchetLadder at RATCHET's own defaults, DECAY 0.9 ---")
        run {
            val defaults = Glint.defaults(GlintVoice.RATCHET)
            val tune = defaults.getValue("TUNE")
            val peak = defaults.getValue("PEAK")
            val follow = defaults.getValue("FOLLOW")
            val kBase = Glint.ratioFor(GlintVoice.RATCHET, tune, peak, follow)
            val dGroups = listOf(
                "d10_ratchet_bloom025" to 0.25f,
                "d11_ratchet_bloom05" to 0.5f,
                "d12_ratchet_bloom075" to 0.75f,
                "d13_ratchet_bloom1" to 1f,
            )
            for ((name, bloom) in dGroups) {
                val bloomAmount = Dsp.lin(bloom, 0f, Glint.BLOOM_MAX)
                val ladder = Glint.ratchetLadder(kBase, bloomAmount)
                println("$name: BLOOM=$bloom kBase=$kBase -> ladder(${ladder.size} rungs)=${ladder.toList()}")
                write(GlintVoice.RATCHET, name, mapOf("DECAY" to 0.9f, "BLOOM" to bloom))
            }
        }

        // E. PLATE'S COUPLING. k(t) = kBase * (1 + BLOOM * amp_env(t)) ties
        // the formant to the note's own loudness, not a clock — so the
        // DECAY 0.95 pair should still sound brighter than the DECAY 0.3
        // pair at the same wall-clock instant. BODY 0 keeps the second
        // formant out of the way, the same fixture GlintTest's own PLATE
        // coupling tests use (`PLATE's fall tracks the amplitude envelope,
        // not a separate curve`).
        write(GlintVoice.PLATE, "e14_plate_decay03_bloom0", mapOf("DECAY" to 0.3f, "BLOOM" to 0f, "BODY" to 0f))
        write(GlintVoice.PLATE, "e15_plate_decay03_bloom1", mapOf("DECAY" to 0.3f, "BLOOM" to 1f, "BODY" to 0f))
        write(GlintVoice.PLATE, "e16_plate_decay095_bloom0", mapOf("DECAY" to 0.95f, "BLOOM" to 0f, "BODY" to 0f))
        write(GlintVoice.PLATE, "e17_plate_decay095_bloom1", mapOf("DECAY" to 0.95f, "BLOOM" to 1f, "BODY" to 0f))

        // F. THE DISSENT. The spec records a designer's argument that
        // GLINT's toy-like "bleep" is a lineage rather than a defect, and
        // that RATCHET — stepping between fixed harmonics like an old chip
        // tune — is that position given a voice. Otherwise at RATCHET's own
        // defaults.
        write(GlintVoice.RATCHET, "f18_ratchet_peak010", mapOf("PEAK" to 0.1f))
        write(GlintVoice.RATCHET, "f19_ratchet_peak045", mapOf("PEAK" to 0.45f))
        write(GlintVoice.RATCHET, "f20_ratchet_peak090", mapOf("PEAK" to 0.9f))

        val page = GlintD2AuditionGenerator::class.java.getResourceAsStream("/audition/glint-d2-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        val pageText = page.use { it.readBytes() }
        File(root, "index.html").outputStream().use { out -> out.write(pageText) }

        // The page hardcodes its clip references and this generator
        // independently renders the clips — two copies of the same quantity
        // with nothing else keeping them in sync. Cross-check both ways
        // against what actually landed on disk under clips/ (not just what
        // this run intended to write) so a future edit to either side (a
        // renamed clip, a section added to one but not the other, a write
        // that silently failed) fails the build instead of shipping quietly.
        val referenced = Regex("""clips/([A-Za-z0-9_.-]+)\.wav""")
            .findAll(String(pageText, Charsets.UTF_8))
            .map { it.groupValues[1] }
            .toSet()
        val renderedOnDisk = (clipsDir.listFiles { f -> f.name.endsWith(".wav") } ?: emptyArray())
            .map { it.name.removeSuffix(".wav") }
            .toSet()
        val renderedNotReferenced = renderedOnDisk - referenced
        val referencedNotRendered = referenced - renderedOnDisk
        if (renderedNotReferenced.isNotEmpty() || referencedNotRendered.isNotEmpty()) {
            error(
                "GLINT D2 audition clip list mismatch between the generator and the page — " +
                    "rendered but never referenced by index.html: ${renderedNotReferenced.sorted()}; " +
                    "referenced by index.html but never rendered: ${referencedNotRendered.sorted()}",
            )
        }
        println("page references ${referenced.size} clips, all rendered")

        println("wrote $count clips under ${clipsDir.absolutePath} + index.html under ${root.absolutePath}")
    }

    /**
     * One loudness for every clip, for a fair listen: the measure is
     * [Loudness.of] — the RMS of the loudest 200 ms window, the same
     * measure `Dsp.levelTo` uses — not a whole-file RMS, so clip length
     * doesn't decide who sounds louder. A peak guard keeps the file in
     * range.
     */
    private fun level(snip: Snip): Snip {
        val out = snip.samples.copyOf()
        val loudness = Loudness.of(Snip(out, channels = 1, sampleRate = snip.sampleRate))
        var g = AUDITION_LEVEL / loudness.coerceAtLeast(1e-9f)
        var peak = 0f
        for (v in out) peak = maxOf(peak, abs(v))
        if (peak * g > 0.99f) g = 0.99f / peak
        for (i in out.indices) out[i] *= g
        return Snip(out, channels = 1, sampleRate = snip.sampleRate)
    }
}
