package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.audio.TempoFit
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Renders the listening gate for the 2026-11-04 gap-scan round under testkit/strike-audition/ (gitignored):
 * strike velocity on SKIN and TERRA, Robin's re-struck takes, OBBLIGATO held as keys and CHORD STABS
 * (docs/STRIKE_VELOCITY.md; docs/OBBLIGATO.md and docs/STOOP.md, their "As built" sections).
 *
 * Every clip is levelled to one loudness ([AuditionLevel]), so the gate judges tone, not volume: on the MPC
 * velocity also turns a soft hit down, and that part is the hardware's job. Each soft hit stands beside the
 * touch the same pad had before this round ("OLD"): `Velocity.soften` on TERRA, and on SKIN's kick and tom
 * the TONE macro scaled toward its floor, both rebuilt here exactly as `Velocity.atVelocity` did them.
 *
 * Sections: KICK and TOM (SKIN presets, old soft hit, then soft, medium and hard), TERRA (all sixteen kit
 * pads the same way), ROLL (eight fast hits: one take repeated, Robin's old re-pitched takes, and the new
 * re-struck takes), OBBLIGATO (keys held ten seconds: the entry, then the loop, then a release) and CHORDS
 * (every stab, three progressions, and one stab soft and hard).
 *
 * Writes manifest.json, with the gate's questions in its `gate`, and index.html (the page in
 * synth/src/test/resources/audition/strike-audition.html). Run via `./gradlew :synth:generateStrikeAudition`.
 */
object StrikeAuditionGenerator {

    private class Clip(val id: String, val label: String, val snip: Snip)

    private class Section(val id: String, val title: String, val note: String, val clips: List<Clip>)

    private const val SOFT = 0.25f
    private const val MEDIUM = 0.6f

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/strike-audition")
        root.deleteRecursively()
        root.mkdirs()

        val sections = mutableListOf<Section>()
        sections += Section(
            "KICK", "SKIN kick",
            "Each kick: the old soft hit, then the new soft, medium and hard. Hard is unchanged. FELT BOOM has no beater click, so its velocity is mostly its pitch bend.",
            listOf("TAPE SKIN", "DEEP SHELL", "FELT BOOM").flatMap { touchRow(skinPreset(SkinVoice.KICK, it)) },
        )
        sections += Section(
            "TOM", "SKIN tom",
            "Each tom: the old soft hit, then the new soft, medium and hard. BEND TOM bends hard on a hard hit and barely on a soft one.",
            listOf("LOW WOOD", "BEND TOM", "STICK SHELL").flatMap { touchRow(skinPreset(SkinVoice.TOM, it)) },
        )
        sections += Section(
            "TERRA", "TERRA kit",
            "All sixteen pads of the TERRA kit, each: the old soft hit, then the new soft, medium and hard. On the buzz pads (Cajon Low, Cajon Slap, Balafon Key) the rattle now wakes only on a firm hit.",
            terraPads().flatMap { touchRow(it) },
        )
        sections += Section(
            "ROLL", "Fast roll",
            "Eight hits, 160 ms apart, cycling four takes. SAME is one take eight times (the machine gun), OLD is Robin's re-pitched takes, NEW is Robin's re-struck takes.",
            rolls(),
        )
        sections += Section(
            "OBBLIGATO", "OBBLIGATO keys",
            "A key held for ten seconds: the duet's own entry, then the loop the keygroup holds on, then a release. HARMONY's top key holds only a 0.8 s loop; the others hold about two seconds. The last clip is the finite note the kit plays, for comparison.",
            obbligato(),
        )
        sections += Section(
            "CHORDS", "CHORD STABS",
            "C minor. Three progressions first, then every pad in order, then the tonic soft and hard.",
            chords(),
        )

        val json = StringBuilder("{\n")
        json.append(GATE)
        json.append(",\n\"sections\": [\n")
        var count = 0
        for ((si, sec) in sections.withIndex()) {
            val dir = File(root, sec.id).apply { mkdirs() }
            json.append("  {\"id\": ${q(sec.id)}, \"title\": ${q(sec.title)}, \"note\": ${q(sec.note)}, \"clips\": [\n")
            for ((ci, clip) in sec.clips.withIndex()) {
                val out = AuditionLevel.level(clip.snip)
                WavWriter.write(File(dir, "${clip.id}.wav"), out, WavWriter.BitDepth.PCM_16)
                json.append("    {\"id\": ${q(clip.id)}, \"file\": ${q("${sec.id}/${clip.id}.wav")}, \"label\": ${q(clip.label)}, ")
                json.append("\"crest\": ${"%.2f".format(java.util.Locale.ROOT, crest(out))}}")
                json.append(if (ci < sec.clips.size - 1) ",\n" else "\n")
                count++
            }
            json.append("  ]}").append(if (si < sections.size - 1) ",\n" else "\n")
        }
        json.append("]}\n")
        File(root, "manifest.json").writeText(json.toString())
        val page = StrikeAuditionGenerator::class.java.getResourceAsStream("/audition/strike-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { o -> page.use { it.copyTo(o) } }
        println("strike audition: $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    // ---------------------------------------------------------------- touch

    private fun skinPreset(voice: SkinVoice, name: String): SkinPatch =
        SkinPresets.forVoice(voice).firstOrNull { it.name == name } ?: error("SkinPresets has no $voice '$name'")

    private fun terraPads(): List<TerraPatch> =
        TerraKits.classic().map { pad -> PadRecipe.fromJsonValue(requireNotNull(requireNotNull(pad).recipe)).patch as TerraPatch }

    /** The pad's old soft hit, then its new soft, medium and hard. */
    private fun touchRow(p: Patch): List<Clip> {
        val slug = slug(p.name)
        val title = p.name.uppercase()
        return listOf(
            Clip("${slug}_old", "$title SOFT, OLD", oldTouch(p, SOFT)),
            Clip("${slug}_v25", "$title SOFT, NEW", Velocity.atVelocity(p, SOFT)),
            Clip("${slug}_v60", "$title MEDIUM, NEW", Velocity.atVelocity(p, MEDIUM)),
            Clip("${slug}_v100", "$title HARD", p.render()),
        )
    }

    /**
     * The touch [p] had before StrikeVelocity, rebuilt from `Velocity.atVelocity`'s own fallbacks as they
     * stood: TERRA took `soften`; SKIN's kick and tom scaled TONE (their brightness macro) toward
     * 0.9 / 3.2 of itself, or took `soften` when TONE was parked at 0.
     */
    internal fun oldTouch(p: Patch, v: Float): Snip = when (p) {
        is TerraPatch -> Velocity.soften(p.render(), 1f - v)
        is SkinPatch -> {
            val asked = p.macros["TONE"] ?: Skin.defaults(p.voice).getValue("TONE")
            if (asked <= 1e-6f) Velocity.soften(p.render(), 1f - v)
            else p.withMacros(p.macros + ("TONE" to asked * Dsp.lin(v, 0.9f / 3.2f, 1f))).render()
        }
        else -> error("no old touch for ${p.engine}")
    }

    // ---------------------------------------------------------------- roll

    private fun rolls(): List<Clip> {
        val sources = listOf(
            "kick" to skinPreset(SkinVoice.KICK, "TAPE SKIN"),
            "tom" to skinPreset(SkinVoice.TOM, "LOW WOOD"),
        ) + terraPads().filter { it.name in setOf("Djembe Open", "Tabla Tin") }.map { slug(it.name) to it }
        return sources.flatMap { (id, p) ->
            val ref = p.render()
            val label = p.name.uppercase()
            listOf(
                Clip("${id}_same", "$label ROLL, SAME TAKE", roll(List(4) { ref })),
                Clip("${id}_old", "$label ROLL, OLD TAKES", roll(takes(ref, p, restrike = false))),
                Clip("${id}_new", "$label ROLL, NEW TAKES", roll(takes(ref, p, restrike = true))),
            )
        }
    }

    /**
     * Four takes the way Robin makes them (take one is [ref]): its own level and start jitter, then either
     * its old micro re-pitch of [ref] or a re-struck take ([Restrike]) peak-matched to [ref]. One seed for
     * both, so the old and new chains differ only in the re-pitch against the re-strike.
     */
    private fun takes(ref: Snip, p: Patch, restrike: Boolean): List<Snip> {
        val rng = java.util.Random(7)
        return listOf(ref) + (1..3).map { t ->
            val trim = (rng.nextFloat() * 0.002f * ref.sampleRate).toInt().coerceAtMost(ref.frameCount / 4)
            val cents = (rng.nextFloat() * 2f - 1f) * 10f
            val gain = 1f + (rng.nextFloat() * 2f - 1f) * 0.08f
            val source = if (restrike) Velocity.peakMatch(ref, requireNotNull(Restrike.take(p, 1f, seed = 7, take = t))) else ref
            var s = Snip(source.samples.copyOfRange(trim, source.samples.size), 1, source.sampleRate)
            if (!restrike) s = TempoFit.repitch(s, 1000f, 1000f * Math.pow(2.0, cents / 1200.0).toFloat())
            Snip(FloatArray(s.samples.size) { (s.samples[it] * gain).coerceIn(-1f, 1f) }, 1, s.sampleRate)
        }
    }

    /** Eight hits [HIT_SPACING] apart, cycling [takes], summed where tails overlap. */
    private fun roll(takes: List<Snip>): Snip {
        val rate = takes.first().sampleRate
        val step = (HIT_SPACING * rate).toInt()
        val out = FloatArray(7 * step + takes.maxOf { it.samples.size })
        for (h in 0 until 8) {
            val s = takes[h % takes.size].samples
            for (i in s.indices) out[h * step + i] += s[i]
        }
        return Snip(out, 1, rate)
    }

    private const val HIT_SPACING = 0.16f

    // ---------------------------------------------------------------- OBBLIGATO

    private fun obbligato(): List<Clip> {
        val harmony = ObbligatoPresets.all().single { it.name == "Harmony" }.macros
        val play = ObbligatoPresets.all().single { it.name == "Play" }.macros
        val fight = ObbligatoPresets.all().single { it.name == "Fight" }.macros
        return listOf(
            Clip("harmony_c4", "HARMONY C4 HELD", held(Keys.obbligatoPad(harmony, 60))),
            Clip("harmony_f_sharp4", "HARMONY F#4 HELD", held(Keys.obbligatoPad(harmony, 66))),
            Clip("harmony_c5", "HARMONY C5 HELD (0.8 S LOOP)", held(Keys.obbligatoPad(harmony, 72))),
            Clip("play_c4", "PLAY C4 HELD", held(Keys.obbligatoPad(play, 60))),
            Clip("fight_f_sharp4", "FIGHT F#4 HELD", held(Keys.obbligatoPad(fight, 66))),
            Clip("harmony_c4_finite", "HARMONY C4, THE KIT'S FINITE NOTE", Obbligato.render(harmony, 60)),
        )
    }

    /** [note] as a held key plays it: its frames to the loop start, the loop repeated to [HELD_SECONDS], a 300 ms release. */
    private fun held(note: KeyNote): Snip {
        val s = note.snip.samples
        val start = note.loopStartFrame.toInt()
        val total = (HELD_SECONDS * note.snip.sampleRate).toInt()
        val out = FloatArray(total)
        for (i in 0 until total) out[i] = if (i < s.size) s[i] else s[start + (i - start) % (s.size - start)]
        val fade = (0.3f * note.snip.sampleRate).toInt()
        for (i in 0 until fade) out[total - fade + i] *= 1f - i.toFloat() / fade
        return Snip(out, 1, note.snip.sampleRate)
    }

    private const val HELD_SECONDS = 10f

    // ---------------------------------------------------------------- chords

    private fun chords(): List<Clip> {
        val key = KeySpec.parse("Cm")
        val pads = ChordStabs.kit(key).map { requireNotNull(it) }
        val stabs = ChordStabs.stabs(key)
        val byName = stabs.indices.associateBy { stabs[it].name }
        fun prog(vararg idx: Int) = sequence(idx.map { pads[it].snip })
        val progs = listOf(
            Clip("prog_1", "PROGRESSION Cm G# D# A# (i VI III VII)", prog(0, 5, 2, 6)),
            Clip("prog_2", "PROGRESSION Cm Fm Gm Cm (i iv v i)", prog(0, 3, 4, 7)),
            Clip("prog_3", "PROGRESSION Cm7 Fm7 A#7 D#maj7", prog(8, 11, 14, 10)),
        )
        check(byName.getValue("Cm7") == 8 && byName.getValue("A#7") == 14) { "the progressions name pads by position" }
        val each = pads.mapIndexed { i, pad -> Clip("pad_${"%02d".format(i + 1)}", "A${"%02d".format(i + 1)} ${stabs[i].name}", pad.snip) }
        val touch = listOf(
            Clip("tonic_soft", "Cm SOFT TAKE", pads[0].softVariants.first()),
            Clip("tonic_hard", "Cm HARD", pads[0].snip),
        )
        return progs + each + touch
    }

    /** [snips] one after another, [CHORD_SPACING] apart, summed where tails overlap. */
    private fun sequence(snips: List<Snip>): Snip {
        val rate = snips.first().sampleRate
        val step = (CHORD_SPACING * rate).toInt()
        val out = FloatArray((snips.size - 1) * step + snips.maxOf { it.samples.size })
        for ((n, s) in snips.withIndex()) for (i in s.samples.indices) out[n * step + i] += s.samples[i]
        return Snip(out, 1, rate)
    }

    private const val CHORD_SPACING = 0.6f

    // ---------------------------------------------------------------- the gate

    private val GATE = """
        |"gate": {
        |  "id": "strike-2026-11",
        |  "name": "Strike velocity, re-strike, OBBLIGATO keys and CHORD STABS",
        |  "task": "generateStrikeAudition",
        |  "title": "Strike Listening Gate",
        |  "heading": "SOFT HITS",
        |  "questionsTitle": "The gate: seven questions",
        |  "intro": "Four things built on 2026-11-04 that nobody has heard yet. Every clip plays at the same loudness, so you judge the sound, not the volume. Your ear is the measurement.",
        |  "questions": [
        |    {"id": "kick", "title": "A soft SKIN kick: does NEW sound like a softer hit on the same drum, and better than OLD?",
        |     "clips": ["KICK/tape_skin_old", "KICK/tape_skin_v25", "KICK/tape_skin_v60", "KICK/tape_skin_v100"],
        |     "options": ["New is better", "Old is better", "Same to me"]},
        |    {"id": "hand", "title": "Soft hand drums (Djembe Bass, Tabla Tun): NEW soft hit against OLD?",
        |     "clips": ["TERRA/djembe_bass_old", "TERRA/djembe_bass_v25", "TERRA/djembe_bass_v100", "TERRA/tabla_tun_old", "TERRA/tabla_tun_v25", "TERRA/tabla_tun_v100"],
        |     "options": ["New is better", "Old is better", "Same to me"]},
        |    {"id": "bend", "title": "A soft hit now bends less than a hard one. Does that sound natural?",
        |     "clips": ["TOM/bend_tom_v25", "TOM/bend_tom_v100", "TERRA/bayan_drag_v25", "TERRA/bayan_drag_v100"],
        |     "options": ["Natural", "Soft bends too little", "Hard bends too much"]},
        |    {"id": "buzz", "title": "Buzz pads: the rattle only wakes on a firm hit. Right amount?",
        |     "clips": ["TERRA/cajon_slap_v25", "TERRA/cajon_slap_v60", "TERRA/cajon_slap_v100", "TERRA/balafon_key_v25", "TERRA/balafon_key_v60", "TERRA/balafon_key_v100"],
        |     "options": ["Right", "Soft still too buzzy", "Soft too clean"]},
        |    {"id": "roll", "title": "A fast roll: which sounds most like one drummer?",
        |     "clips": ["ROLL/kick_same", "ROLL/kick_old", "ROLL/kick_new", "ROLL/djembe_open_same", "ROLL/djembe_open_old", "ROLL/djembe_open_new"],
        |     "options": ["New takes", "Old takes", "Can't tell"]},
        |    {"id": "keys", "title": "A held OBBLIGATO key: the duet carrying on, or a phrase on repeat?",
        |     "clips": ["OBBLIGATO/harmony_c4", "OBBLIGATO/harmony_c5", "OBBLIGATO/play_c4", "OBBLIGATO/fight_f_sharp4"],
        |     "options": ["Carrying on", "Phrase on repeat", "Depends on the key"]},
        |    {"id": "chords", "title": "CHORD STABS: would you play these progressions from the pads?",
        |     "clips": ["CHORDS/prog_1", "CHORDS/prog_2", "CHORDS/prog_3"],
        |     "options": ["Yes, as is", "Wrong voicing", "Wrong sound"]}
        |  ]
        |}""".trimMargin()

    // ---------------------------------------------------------------- helpers

    internal fun slug(name: String): String = name.lowercase().replace('#', 's').replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun crest(s: Snip): Double {
        var sum = 0.0
        var peak = 0f
        for (v in s.samples) { sum += v.toDouble() * v; peak = maxOf(peak, abs(v)) }
        val rms = sqrt(sum / s.samples.size.coerceAtLeast(1))
        return if (rms > 0) peak / rms else 0.0
    }

    private fun q(s: String): String {
        check(s.none { it == '"' || it == '\\' || it < ' ' }) { "manifest field needs escaping: $s" }
        return "\"$s\""
    }
}
