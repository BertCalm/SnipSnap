package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders the BORE audition (docs/superpowers/specs/2026-09-28-bore-woodwind-engine-design.md,
 * the S19 gate) under testkit/bore-audition/ (gitignored): the sixteen-pad kit as it lands on
 * the MPC, then for each voice its default, BREATH/LIP/CHIFF/HOLD at both ends with the rest at
 * their defaults, all eight of its own presets, and the landing - the same note dry, with the
 * TAPE recipe every one-shot pad lands with, and with twice the wow. The LOOP step is a clip of
 * its own per voice, played to its own length. Clips share one loudness ([AuditionLevel]).
 * Writes `manifest.json`, which the page builds itself from, so the clip list lives here and
 * nowhere else, then copies the listening page from the test resources. Run via
 * `./gradlew :synth:generateBoreAudition`, then publish the folder as the listening artifact.
 *
 * The first section is the reed's loop (round 1.5, after the kit card's note "Solo loop sounds like a cheap keyboard"):
 * the SOLO LOOP and the PAD REED with the bell and nothing after (the first rounds' chain, built with this round's
 * pitch match: their timbre, not their wrap), with the rasp and two thirds of the
 * voicing (the one-shot's bite at C3 with the default knobs) and the whole of it (shipped), and the loops of the defaults at three notes,
 * with the one-shot of the same note beside them. Each clip's description carries the bite and the seam measured on
 * that very render.
 *
 * The second section is the reed's attack (round 1.4, after the voicing: "let's fix the attack speed next"): the same
 * notes with the tongue's seed off (the swell, 0.44 s to 80% at C3), light, as shipped and strong, then CHIFF's
 * range and a soft breath at C3, then the two presets a fast attack could have tipped over. Every description
 * carries the onset measured on that very note, so the page says what the clip is, not what it was meant to be.
 *
 * The third section is the reed's voicing (round 1.3, after the last round's answer: a prototype filter
 * on the rasped reed, measured against a real tenor, with the deeper cut and the brighter lift both kept):
 * the same SAX phrase and three presets with no voicing (what the last round merged), a milder one, the
 * shipped one and a stronger one, so its strength is a choice made by ear. The clips are one-shots; a LOOP
 * carries the rasp and the voicing too since round 1.5 ([Bore.RASP_DRIVE], [Bore.VOICE_LOW_DB]).
 *
 * Nothing in BORE has been heard by anyone when this is first run: it is the gate that decides
 * whether the measured engine is a woodwind. The page says so.
 */
object BoreAuditionGenerator {

    private class Knob(val name: String, val low: String, val high: String)

    /** The knobs at both ends. TUNE is a note, not a knob to audition. HOLD's top end is a LOOP, so it has its own clip below and this one stops short of it. */
    private val KNOBS = listOf(
        Knob("BREATH", "the softest note that speaks: a quiet breath, dull, little noise", "the hardest breath the note takes: brighter, more air in it"),
        Knob("LIP", "FLUTE: a dark, round jet; SAX: a loose, open, buzzy reed", "FLUTE: a bright, open jet; SAX: a tight, pure, pinched reed"),
        Knob("CHIFF", "a slow swell, no tongue", "a hard tongue: fast attack, a breath burst, a key thump underneath"),
        Knob("HOLD", "the shortest breath: 0.3 s of note", "the longest one-shot: 4 s, with its vibrato"),
    )

    private val BODIES = mapOf(
        BoreVoice.FLUTE to "an air jet across a mouth hole, in a pipe: the fundamental leads at every breath, the note follows the pitch's own bore",
        BoreVoice.SAX to "a single reed on a cone: the full harmonic series, quick to speak (a tongue's seed starts it), buzzier the looser the lip",
    )

    private class Clip(val id: String, val name: String, val desc: String)
    private class Group(val label: String, val key: Boolean, val clips: List<Clip>)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/bore-audition")
        root.mkdirs()
        var count = 0
        val sections = StringBuilder()

        val phrase = listOf(7f / 24, 0.5f, 19f / 24)
        // The reed's loop: what it was, what it is, and the one-shot it should be a held version of.
        val loopDir = File(root, "LOOP")
        fun loopClip(id: String, name: String, what: String, macros: Map<String, Float>, carry: Boolean, share: Float): Clip {
            val m = Bore.settled(macros + mapOf("HOLD" to 1f), BoreVoice.SAX)
            val r = Bore.renderLoopMeasured(BoreVoice.SAX, m, carry = carry, voiceShare = share)
            val snip = Snip(r.loop, channels = 1, sampleRate = Dsp.RATE)
            WavWriter.write(File(loopDir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
            count++
            val bite = BoreMeasure.biteDb(snip, Bore.frequencyFor(BoreVoice.SAX, m.getValue("TUNE")), fromSec = 0.5f)
            return Clip(id, name, "%s: bite %+.1f dB, seam %.0e".format(java.util.Locale.ROOT, what, bite, r.seam))
        }
        fun shotClip(id: String, name: String, macros: Map<String, Float>): Clip {
            val m = Bore.settled(macros + mapOf("HOLD" to 0.5f), BoreVoice.SAX)
            val snip = Bore.render(BoreVoice.SAX, m)
            WavWriter.write(File(loopDir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
            count++
            val bite = BoreMeasure.biteDb(snip, Bore.frequencyFor(BoreVoice.SAX, m.getValue("TUNE")), fromSec = 0.5f)
            return Clip(id, name, "the one-shot of this note, for reference: bite %+.1f dB".format(java.util.Locale.ROOT, bite))
        }
        val matched = 0.67f
        fun threeWays(tag: String, macros: Map<String, Float>) = listOf(
            loopClip("${tag}_0_before", "BELL ONLY", "the first rounds' chain, the bell and nothing after (built with this round's pitch match, so it is their timbre, not their wrap)", macros, carry = false, share = Bore.LOOP_VOICE_SHARE),
            loopClip("${tag}_1_matched", "TWO-THIRDS", "the rasp and %.2f of the voicing: the one-shot's bite at C3 with the default knobs, under it elsewhere".format(java.util.Locale.ROOT, matched), macros, carry = true, share = matched),
            loopClip("${tag}_2_full", "SHIPPED", "the rasp and the voicing whole", macros, carry = true, share = Bore.LOOP_VOICE_SHARE),
        )
        val solo = BorePresets.forVoice(BoreVoice.SAX).first { it.name == "SOLO LOOP" }.macros
        val padReed = BorePresets.forVoice(BoreVoice.SAX).first { it.name == "PAD REED" }.macros
        val soloClips = threeWays("solo", solo) + shotClip("solo_3_oneshot", "ONE-SHOT", solo)
        val padClips = threeWays("pad", padReed) + shotClip("pad_3_oneshot", "ONE-SHOT", padReed)
        val notes = listOf("C3" to 0f, "C4" to 0.5f, "C5" to 1f)
        val noteClips = notes.flatMap { (name, tune) ->
            val m = Bore.defaults(BoreVoice.SAX) + mapOf("TUNE" to tune)
            listOf(
                loopClip("note_${name.lowercase()}_0_before", "$name BELL ONLY", "the default knobs' loop with the bell and nothing after (this round's pitch match)", m, carry = false, share = Bore.LOOP_VOICE_SHARE),
                loopClip("note_${name.lowercase()}_1_full", "$name SHIPPED", "the default knobs' loop now", m, carry = true, share = Bore.LOOP_VOICE_SHARE),
                shotClip("note_${name.lowercase()}_2_oneshot", "$name ONE-SHOT", m),
            )
        }
        sections.append(
            sectionJson(
                id = "LOOP", display = "THE SAX'S LOOP", body = "held notes: the kit card's SOLO LOOP sounded like a cheap keyboard, so loops now carry the rasp and the voicing",
                readout = listOf(
                    "SOLO LOOP " + DOT + " PAD REED " + DOT + " THE DEFAULTS AT C3, C4, C5",
                    "REPEAT ON: A LOOP IS TWO SECONDS AND MUST NOT CLICK AT THE WRAP",
                ),
                groups = listOf(
                    Group("SOLO LOOP (THE ONE YOU HEARD AS A CHEAP KEYBOARD)", key = true, clips = soloClips),
                    Group("PAD REED", key = true, clips = padClips),
                    Group("THE DEFAULT KNOBS AT THREE NOTES", key = false, clips = noteClips),
                ),
            ),
        )
        sections.append(",\n")

        // The reed's attack: the tongue's seed at four strengths on the same notes, and what CHIFF and BREATH do to it.
        val attackDir = File(root, "ATTACK")
        val attackSteps = listOf<Triple<String, Float?, Pair<String, String>>>(
            Triple("0_none", 0f, "NO SEED" to "the swell, the attack before this round"),
            Triple("1_light", 0.1f, "LIGHT" to "the tongue's seed at 0.1"),
            Triple("2_shipped", null, "SHIPPED" to "the shipped seed: the tongue's, scaled by CHIFF, BREATH and the reed's level"),
            Triple("3_strong", 0.4f, "STRONG" to "the seed at 0.4, near its cap of 0.5 (not shipped)"),
        )
        fun writeAttack(id: String, snip: Snip) {
            WavWriter.write(File(attackDir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
            count++
        }
        val saxBase = Bore.defaults(BoreVoice.SAX) + mapOf("HOLD" to 0.3f)
        // The default TUNE is the middle of the range (C4); the C3 groups say so by setting it.
        val c3Base = saxBase + mapOf("TUNE" to 0f)
        check(renderSeeded(saxBase, null).samples.contentEquals(Bore.render(BoreVoice.SAX, saxBase).samples)) {
            "the audition's shipped-seed clip is not what Bore.render makes"
        }
        fun onsetText(macros: Map<String, Float>, seed: Float?) = "%.2f s to 80%%".format(java.util.Locale.ROOT, seededOnset(macros, seed))
        val attackPhrase = attackSteps.map { (key, seed, label) ->
            val gap = FloatArray((0.15f * Dsp.RATE).toInt())
            val samples = phrase.flatMap { t -> renderSeeded(saxBase + mapOf("TUNE" to t), seed).samples.toList() + gap.toList() }.toFloatArray()
            writeAttack("phrase_$key", Snip(samples, channels = 1, sampleRate = Dsp.RATE))
            Clip("phrase_$key", label.first, label.second + ": " + phrase.joinToString(" ") { t -> "%.2f".format(java.util.Locale.ROOT, seededOnset(saxBase + mapOf("TUNE" to t), seed)) } + " s at G3 C4 G4")
        }
        val c3Seeds = attackSteps.map { (key, seed, label) ->
            writeAttack("c3_$key", renderSeeded(c3Base, seed))
            Clip("c3_$key", label.first, label.second + ": " + onsetText(c3Base, seed))
        }
        val chiffClips = listOf(0f, 0.4f, 1f).map { chiff ->
            val m = c3Base + mapOf("CHIFF" to chiff)
            val id = "chiff_" + fmt(chiff).trimStart('.')
            writeAttack(id, renderSeeded(m, null))
            Clip(id, "CHIFF " + fmt(chiff), (if (chiff == 0f) "no tongue: the swell stays" else if (chiff >= 1f) "a hard tongue: a stab, with its breath burst and key thump" else "the default") + ": " + onsetText(m, null))
        }
        val breathClips = listOf(0f, 0.6f, 1f).map { breath ->
            val m = c3Base + mapOf("BREATH" to breath)
            val id = "breath_" + fmt(breath).trimStart('.')
            writeAttack(id, renderSeeded(m, null))
            Clip(id, "BREATH " + fmt(breath), (if (breath == 0f) "the softest note: it speaks slowly, as a soft note does" else if (breath >= 1f) "the hardest breath" else "the default") + ": " + onsetText(m, null))
        }
        fun attackPreset(presetName: String, tag: String) = listOf(attackSteps.first(), attackSteps[2]).map { (key, seed, label) ->
            val preset = BorePresets.forVoice(BoreVoice.SAX).first { it.name == presetName }
            writeAttack("${tag}_$key", renderSeeded(preset.macros, seed))
            Clip("${tag}_$key", label.first, macroLine(BoreVoice.SAX, preset.macros) + " " + DOT + " " + onsetText(preset.macros, seed))
        }
        // Sharper listening: the notes held 1.2 s after 0.3 s of silence (a device that swallows the first moments of a
        // short clip, and a 0.3 s note that is mostly its own attack, both hide a start), and the seed alone: CHIFF 0 and
        // no pop, so there is no tongue noise at all, only the tone beginning slowly or at once.
        val longClips = listOf(0f to "C3", 0.5f to "C4").flatMap { (tune, note) ->
            val m = saxBase + mapOf("TUNE" to tune)
            listOf(attackSteps.first(), attackSteps[2]).map { (key, seed, label) ->
                val id = "long_${note.lowercase()}_$key"
                writeAttack(id, renderSeeded(m, seed, gateSeconds = 1.2f, leadInSeconds = 0.3f))
                Clip(id, "$note ${label.first}", "held 1.2 s after 0.3 s of silence: " + onsetText(m, seed))
            }
        }
        val aloneBase = c3Base + mapOf("CHIFF" to 0f)
        val aloneClips = listOf(0f to "NO SEED", 0.2f to "SEED 0.2").map { (seed, name) ->
            val id = if (seed == 0f) "alone_none" else "alone_seed"
            writeAttack(id, renderSeeded(aloneBase, seed, gateSeconds = 1.2f, pop = 0f, leadInSeconds = 0.3f))
            Clip(id, name, "C3, CHIFF 0, no pop, held 1.2 s after 0.3 s of silence: %.2f s to 80%%".format(java.util.Locale.ROOT, seededOnset(aloneBase, seed, pop = 0f)))
        }
        val c3Shipped = seededOnset(c3Base, null)
        val c3None = seededOnset(c3Base, 0f)
        sections.append(
            sectionJson(
                id = "ATTACK", display = "THE SAX'S ATTACK", body = "how fast a note speaks: a real tenor reaches 80% of its level in 0.05-0.12 s, ours took 0.44 s at C3",
                readout = listOf(
                    "C3 " + DOT + " DEFAULT KNOBS " + DOT + " NO SEED %.2f S, SHIPPED %.2f S".format(java.util.Locale.ROOT, c3None, c3Shipped),
                    "REAL TENOR 0.05-0.12 S (A VERY SOFT NOTE 0.49 S)",
                ),
                groups = listOf(
                    Group("LONG NOTES, WITH A QUIET LEAD-IN: ONLY THE START DIFFERS", key = true, clips = longClips),
                    Group("THE SEED ALONE: NO POP, NO CHIFF", key = true, clips = aloneClips),
                    Group("THE SAME PHRASE, FOUR SEEDS", key = true, clips = attackPhrase),
                    Group("C3 ALONE, WHERE IT WAS SLOWEST", key = true, clips = c3Seeds),
                    Group("CHIFF AT C3, THE SHIPPED SEED", key = false, clips = chiffClips),
                    Group("A SOFT BREATH STAYS SLOW", key = false, clips = breathClips),
                    Group("HIGH STAB (A HARD TONGUE ON A BRIGHT REED)", key = false, clips = attackPreset("HIGH STAB", "high_stab")),
                    Group("LOW HONK", key = false, clips = attackPreset("LOW HONK", "low_honk")),
                ),
            ),
        )
        sections.append(",\n")

        // The reed's voicing, four strengths of the same thing.
        val voiceDir = File(root, "VOICE")
        val voiceSteps = listOf(
            Triple("0_merged", Bore.Voicing.NONE, "MERGED" to "the last round's reed: the bell and the rasp, no voicing"),
            Triple("1_milder", Bore.Voicing(-18f, 12f), "MILDER" to "the voicing at -18 dB under 200 Hz and +12 dB over 3 kHz"),
            Triple("2_shipped", Bore.voicingFor(BoreVoice.SAX), "SHIPPED" to "the shipped default: -26 dB under 200 Hz, +18 dB over 3 kHz, opening with the note's loudness"),
            Triple("3_stronger", Bore.Voicing(-32f, 24f), "STRONGER" to "-32 dB under 200 Hz and +24 dB over 3 kHz (not shipped: a step past it)"),
        )
        fun writeVoice(id: String, snip: Snip) {
            WavWriter.write(File(voiceDir, "$id.wav"), AuditionLevel.level(snip), WavWriter.BitDepth.PCM_16)
            count++
        }
        val saxDefaults = Bore.defaults(BoreVoice.SAX)
        check(renderVoiced(saxDefaults, Bore.voicingFor(BoreVoice.SAX)).samples.contentEquals(Bore.render(BoreVoice.SAX, saxDefaults).samples)) {
            "the audition's shipped-strength clip is not what Bore.render makes"
        }
        val phraseClips = voiceSteps.map { (key, voicing, label) ->
            val gap = FloatArray((0.15f * Dsp.RATE).toInt())
            val samples = phrase.flatMap { t ->
                (renderVoiced(saxDefaults + mapOf("TUNE" to t, "HOLD" to 0.3f), voicing).samples.toList() + gap.toList())
            }.toFloatArray()
            writeVoice("phrase_$key", Snip(samples, channels = 1, sampleRate = Dsp.RATE))
            Clip("phrase_$key", label.first, label.second)
        }
        fun presetClips(presetName: String, tag: String) = voiceSteps.filter { it.first != "3_stronger" }.map { (key, voicing, label) ->
            val preset = BorePresets.forVoice(BoreVoice.SAX).first { it.name == presetName }
            writeVoice("${tag}_$key", renderVoiced(preset.macros, voicing))
            Clip("${tag}_$key", label.first, macroLine(BoreVoice.SAX, preset.macros))
        }
        sections.append(
            sectionJson(
                id = "VOICE", display = "THE SAX'S VOICING", body = "measured against a real tenor sax: the fundamental weaker, the highs stronger, opening with the note",
                readout = listOf("G3, C4, G4 " + DOT + " DEFAULT KNOBS", "REAL TENOR AT C3: 50-250 HZ 16 DB UNDER THE WHOLE SOUND; OURS BEFORE: 1"),
                groups = listOf(
                    Group("THE SAME PHRASE, FOUR STRENGTHS", key = true, clips = phraseClips),
                    Group("BITE PRESET (A LOOSE LIP, A HARD BREATH)", key = true, clips = presetClips("BITE", "bite_preset")),
                    Group("GROWL", key = false, clips = presetClips("GROWL", "growl")),
                    Group("HIGH STAB", key = false, clips = presetClips("HIGH STAB", "high_stab")),
                ),
            ),
        )
        sections.append(",\n")

        // The kit, as it lands: SynthKits.bore() is the one list of what is on which pad,
        // and each pad's own recipe names its patch.
        val kitDir = File(root, "KIT")
        val kitClips = SynthKits.bore().mapIndexed { i, pad ->
            val arranged = requireNotNull(pad) { "the bore kit has an empty pad at ${i + 1}" }
            val recipe = PadRecipe.fromJsonValue(requireNotNull(arranged.recipe) { "kit pad ${i + 1} carries no recipe" })
            val patch = requireNotNull(recipe.patch) as BorePatch
            val tag = "A%02d".format(i + 1)
            val id = tag.lowercase() + "_" + patch.name.lowercase().replace(' ', '_')
            WavWriter.write(File(kitDir, "$id.wav"), AuditionLevel.level(arranged.snip), WavWriter.BitDepth.PCM_16)
            count++
            Clip(id, "$tag ${patch.name.uppercase()}", patch.voice.name + " " + DOT + " " + macroLine(patch.voice, patch.macros))
        }
        sections.append(
            sectionJson(
                id = "KIT", display = "THE BORE KIT", body = "sixteen pads as the MPC gets them",
                readout = listOf("A01-A04 FLUTE, A TRIAD AND ITS OCTAVE", "A05-A08 SAX, THE SAME", "A09-A16 EIGHT PRESETS, FOUR PER VOICE"),
                groups = listOf(
                    Group("FLUTE, C MAJOR TO THE OCTAVE", key = true, clips = kitClips.subList(0, 4)),
                    Group("SAX, THE SAME", key = true, clips = kitClips.subList(4, 8)),
                    Group("FLUTE PRESETS", key = true, clips = kitClips.subList(8, 12)),
                    Group("SAX PRESETS", key = true, clips = kitClips.subList(12, 16)),
                ),
            ),
        )

        for (voice in BoreVoice.entries) {
            val dir = File(root, voice.name)
            val defaults = Bore.defaults(voice)
            fun write(id: String, macros: Map<String, Float>) {
                WavWriter.write(File(dir, "$id.wav"), AuditionLevel.level(Bore.render(voice, macros)), WavWriter.BitDepth.PCM_16)
                count++
            }

            write("default", emptyMap())
            val groups = mutableListOf(
                Group("THE DEFAULT", key = true, clips = listOf(Clip("default", "DEFAULT", macroLine(voice, emptyMap())))),
            )
            for (knob in KNOBS) {
                val lo = knob.name.lowercase() + "_0"
                val hi = knob.name.lowercase() + "_1"
                write(lo, mapOf(knob.name to 0f))
                // HOLD 1 is the LOOP step; the top of the one-shot travel is just under it.
                write(hi, mapOf(knob.name to if (knob.name == "HOLD") Bore.SCRAMBLE_HOLD_CEILING else 1f))
                groups += Group(
                    knob.name + " " + DOT + " DEFAULT " + fmt(defaults.getValue(knob.name)), key = false,
                    clips = listOf(Clip(lo, "${knob.name} 0", knob.low), Clip(hi, "${knob.name} 1", knob.high)),
                )
            }

            val presets = BorePresets.forVoice(voice).map { preset ->
                val id = "preset_" + preset.name.lowercase().replace(' ', '_')
                write(id, preset.macros)
                Clip(id, preset.name, macroLine(voice, preset.macros))
            }
            groups += Group("${voice.name}'S OWN PRESETS", key = true, clips = presets)

            // The LOOP step, alone: a seamless two seconds, played once here (hold a pad to hear it wrap).
            write("loop", mapOf("HOLD" to 1f))
            groups += Group(
                "THE LOOP STEP", key = false,
                clips = listOf(Clip("loop", "HOLD 1 (LOOP)", "a seamless whole-period loop: dry, no vibrato, no breath noise; turn the page's REPEAT on to hear the wrap (on a drum pad it plays once through)")),
            )

            // The landing: the same note dry, landed with the recipe's TAPE, and with twice the wow
            // - the numbers Bore.LANDING_TAPE leaves to this gate.
            val landing = mutableListOf<Clip>()
            val patch = BorePatch("Landing", voice, defaults)
            val landed = mapOf(
                "dry" to Triple("DRY", "the engine alone, no tape", null),
                "landed" to Triple("LANDED, WOBBLE .10", "the recipe every one-shot pad lands with", Bore.LANDING_TAPE),
                "wow" to Triple("LANDED, WOBBLE .20", "the same tape with twice the wow", Bore.LANDING_TAPE + ("WOBBLE" to 0.2f)),
            )
            for ((id, spec) in landed) {
                val (name, desc, tape) = spec
                val fx = tape?.let { FxChain().withSection("tape", it) }
                WavWriter.write(File(dir, "landing_$id.wav"), AuditionLevel.level(PadRecipe(patch, fx).render()), WavWriter.BitDepth.PCM_16)
                count++
                landing += Clip("landing_$id", name, desc)
            }
            groups += Group("THE LANDING: DRY, TAPE, MORE TAPE", key = true, clips = landing)

            if (sections.isNotEmpty()) sections.append(",\n")
            sections.append(
                sectionJson(
                    id = voice.name, display = voice.name, body = BODIES.getValue(voice),
                    readout = listOf(
                        "ROOT " + (if (voice == BoreVoice.FLUTE) "C4" else "C3") + " " + DOT + " 24 SEMITONES OF TRAVEL",
                        "BREATH " + fmt(defaults.getValue("BREATH")) + DOT + "LIP " + fmt(defaults.getValue("LIP")),
                    ),
                    groups = groups,
                ),
            )
        }

        File(root, "manifest.json").writeText("{\"voices\": [\n$sections\n]}\n")
        val page = BoreAuditionGenerator::class.java.getResourceAsStream("/audition/bore-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + manifest.json + index.html under ${root.absolutePath}")
    }

    /**
     * A one-shot SAX note with the bell and the rasp as shipped and [voicing] on the output: [Bore.Voicing.NONE]
     * is the last round's reed, [Bore.voicingFor] is [Bore.render] exactly (checked above). One-shots only - a
     * LOOP renders through [Bore.renderLoop], which since round 1.5 has the rasp and the voicing as well.
     */
    private fun renderVoiced(macros: Map<String, Float>, voicing: Bore.Voicing): Snip {
        val m = Bore.settled(macros, BoreVoice.SAX)
        require(!Bore.isLoop(m.getValue("HOLD"))) { "renderVoiced is for one-shots" }
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val hz = Bore.tunedHz(BoreVoice.SAX, Bore.frequencyFor(BoreVoice.SAX, m.getValue("TUNE")))
        val bell = Bore.biteBoostDb(BoreVoice.SAX, m.getValue("LIP"))
        val rasp = Bore.raspAmount(BoreVoice.SAX, m.getValue("LIP"), m.getValue("BREATH"))
        return Snip(Bore.finish(Bore.blow(BoreVoice.SAX, hz, m, rate), rate, bell, rasp, voicing), channels = 1, sampleRate = Dsp.RATE)
    }

    /**
     * A one-shot SAX note as [Bore.render] makes it, with the tongue's [seed] overridden: null is the shipped one
     * ([Bore.blow] computes it), 0 is none - the swell this round replaced.
     */
    private fun renderSeeded(macros: Map<String, Float>, seed: Float?, gateSeconds: Float? = null, pop: Float? = null, leadInSeconds: Float = 0f): Snip {
        val m = Bore.settled(macros, BoreVoice.SAX)
        require(!Bore.isLoop(m.getValue("HOLD"))) { "renderSeeded is for one-shots" }
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val hz = Bore.tunedHz(BoreVoice.SAX, Bore.frequencyFor(BoreVoice.SAX, m.getValue("TUNE")))
        val bell = Bore.biteBoostDb(BoreVoice.SAX, m.getValue("LIP"))
        val rasp = Bore.raspAmount(BoreVoice.SAX, m.getValue("LIP"), m.getValue("BREATH"))
        val note = Bore.finish(Bore.blow(BoreVoice.SAX, hz, m, rate, gateSeconds = gateSeconds, pop = pop, seed = seed), rate, bell, rasp, Bore.voicingFor(BoreVoice.SAX))
        return Snip(FloatArray((leadInSeconds * Dsp.RATE).toInt()) + note, channels = 1, sampleRate = Dsp.RATE)
    }

    /** Seconds to 80% of the raw note's steady level, read the way BoreTest reads it (the blown wave, 1.4 s of gate). */
    private fun seededOnset(macros: Map<String, Float>, seed: Float?, pop: Float? = null): Float {
        val m = Bore.settled(macros, BoreVoice.SAX)
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val hz = Bore.tunedHz(BoreVoice.SAX, Bore.frequencyFor(BoreVoice.SAX, m.getValue("TUNE")))
        return BoreMeasure.onsetSeconds(Bore.blow(BoreVoice.SAX, hz, m, rate, gateSeconds = 1.4f, pop = pop, seed = seed), 1.0f, 1.4f)
    }

    private const val DOT = "\u00b7"

    /** The knobs that differ from the voice's defaults, the way the app's sliders read them. */
    private fun macroLine(voice: BoreVoice, macros: Map<String, Float>): String {
        val d = Bore.defaults(voice)
        val moved = Bore.macrosFor(voice).map { it.name }.filter { (macros[it] ?: d.getValue(it)) != d.getValue(it) }
        return if (moved.isEmpty()) "every knob at its default" else moved.joinToString(" ") { "$it ${fmt(macros.getValue(it))}" }
    }

    /** A macro value the way the app's sliders read it: `0`, `.35`, `1`. */
    private fun fmt(v: Float) = when {
        v <= 0f -> "0"
        v >= 1f -> "1"
        else -> "." + (v * 100).roundToInt().toString().padStart(2, '0')
    }

    private fun sectionJson(id: String, display: String, body: String, readout: List<String>, groups: List<Group>): String {
        val g = groups.joinToString(",") { grp ->
            val c = grp.clips.joinToString(",") { "[${q(it.id)},${q(it.name)},${q(it.desc)}]" }
            "{\"label\":${q(grp.label)},\"key\":${grp.key},\"clips\":[$c]}"
        }
        val r = readout.joinToString(",") { q(it) }
        return "{\"id\":${q(id)},\"display\":${q(display)},\"body\":${q(body)},\"readout\":[$r],\"groups\":[$g]}"
    }

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
