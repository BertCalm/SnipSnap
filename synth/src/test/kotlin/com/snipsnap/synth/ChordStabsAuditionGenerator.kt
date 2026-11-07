package com.snipsnap.synth

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import java.io.File

/**
 * CHORD STABS' modal and borrowed layouts, first listen (2026-11-07), written to gitignored
 * testkit/chord-stabs-audition/. Run `./gradlew :synth:generateChordStabsAudition`.
 *
 * D dorian and G mixolydian share C major's notes with a different home, so the mode, not the key, is
 * what changes. C major's borrowed kit puts the key's own triads beside eight borrowed chords. The page
 * leads with progressions that show each mode's mark and each borrowed chord's colour, then every pad.
 * Every clip is the kit's own dry pad renders, levelled by [AuditionLevel]; no rack effects.
 */
object ChordStabsAuditionGenerator {

    private class Kit(val id: String, val title: String, val pads: List<com.snipsnap.kit.ArrangedPad>, val stabs: List<ChordStabs.Stab>)

    private class Prog(val id: String, val kit: String, val name: String, val description: String, val pads: List<Int>)

    private val kits by lazy {
        listOf(
            Kit("dorian", "D dorian", ChordStabs.modalKit(2, ChordStabs.Mode.DORIAN).map { requireNotNull(it) }, ChordStabs.modalStabs(2, ChordStabs.Mode.DORIAN)),
            Kit("mixolydian", "G mixolydian", ChordStabs.modalKit(7, ChordStabs.Mode.MIXOLYDIAN).map { requireNotNull(it) }, ChordStabs.modalStabs(7, ChordStabs.Mode.MIXOLYDIAN)),
            Kit("borrowed", "C major, borrowed", ChordStabs.borrowedKit(KeySpec.parse("C")).map { requireNotNull(it) }, ChordStabs.borrowedStabs(KeySpec.parse("C"))),
        )
    }

    /** Pad indices (A01 = 0). The names in each are checked against the kit before rendering. */
    private val progs = listOf(
        Prog("dorian_vamp", "dorian", "Dm G Dm G (i IV)", "Dorian's mark: the minor home with a major IV.", listOf(0, 3, 0, 3)),
        Prog("dorian_sevenths", "dorian", "Dm7 G7 Dm7 G7 (i7 IV7)", "The same vamp in sevenths.", listOf(8, 11, 8, 11)),
        Prog("dorian_walk", "dorian", "Dm Em F G (i ii III IV)", "Walking up the mode.", listOf(0, 1, 2, 3)),
        Prog("mixo_vamp", "mixolydian", "G F C G (I bVII IV I)", "Mixolydian's mark: the major home with a major bVII.", listOf(0, 6, 3, 7)),
        Prog("mixo_sevenths", "mixolydian", "G7 Fmaj7 G7 Fmaj7 (I7 bVIImaj7)", "The dominant home that never resolves.", listOf(8, 14, 8, 14)),
        Prog("borrowed_iv", "borrowed", "C F Fm C (I IV iv I)", "The major IV turning minor: the most used borrowed chord.", listOf(0, 3, 8, 7)),
        Prog("borrowed_bvii", "borrowed", "C A# F C (I bVII IV I)", "bVII from C minor (A# is B flat).", listOf(0, 9, 3, 7)),
        Prog("borrowed_bvi", "borrowed", "C G# A# C (I bVI bVII I)", "bVI then bVII, rising back home (G# is A flat).", listOf(0, 10, 9, 7)),
        Prog("borrowed_biii", "borrowed", "C D# F C (I bIII IV I)", "bIII from C minor (D# is E flat).", listOf(0, 11, 3, 7)),
        Prog("borrowed_bii", "borrowed", "C C# G C (I bII V I)", "The Neapolitan bII before the V (C# is D flat).", listOf(0, 13, 4, 7)),
        Prog("borrowed_ii", "borrowed", "C D F C (I II IV I)", "Major II from C lydian.", listOf(0, 14, 3, 7)),
        Prog("borrowed_v", "borrowed", "C Gm F C (I v IV I)", "Minor v from C mixolydian.", listOf(0, 12, 3, 7)),
        Prog("borrowed_maj7", "borrowed", "C G#maj7 C G#maj7 (I bVImaj7)", "bVImaj7, the lush one (G# is A flat).", listOf(0, 15, 0, 15)),
    )

    /** One chord every [SPACING] seconds, summed where tails overlap. */
    private fun sequence(snips: List<Snip>): Snip {
        val rate = snips.first().sampleRate
        val step = (SPACING * rate).toInt()
        val out = FloatArray((snips.size - 1) * step + snips.maxOf { it.samples.size })
        for ((n, s) in snips.withIndex()) for (i in s.samples.indices) out[n * step + i] += s.samples[i]
        return Snip(out, 1, rate)
    }

    private const val SPACING = 0.7f

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/chord-stabs-audition")
        require(root.mkdirs() || root.isDirectory) { "cannot create ${root.absolutePath}" }
        val byId = kits.associateBy { it.id }
        val clips = ArrayList<Map<String, Any?>>()
        for (p in progs) {
            val kit = byId.getValue(p.kit)
            val names = p.name.substringBefore(" (").split(" ")
            check(names == p.pads.map { kit.stabs[it].name }) { "${p.id}: ${p.name} does not match pads ${p.pads.map { kit.stabs[it].name }}" }
            val snip = AuditionLevel.level(sequence(p.pads.map { kit.pads[it].snip }))
            val file = "progressions/${p.id}.wav"
            WavWriter.write(File(root, file), snip, WavWriter.BitDepth.PCM_24)
            clips += linkedMapOf("id" to p.id, "section" to "PROGRESSIONS", "group" to kit.title, "name" to p.name,
                "description" to p.description, "file" to file, "seconds" to snip.samples.size.toDouble() / snip.sampleRate)
        }
        for (kit in kits) for ((i, pad) in kit.pads.withIndex()) {
            val stab = kit.stabs[i]
            val snip = AuditionLevel.level(pad.snip)
            val file = "pads/${kit.id}_${"%02d".format(i + 1)}.wav"
            WavWriter.write(File(root, file), snip, WavWriter.BitDepth.PCM_24)
            clips += linkedMapOf("id" to "${kit.id}_${"%02d".format(i + 1)}", "section" to "PADS", "group" to kit.title,
                "name" to "A${"%02d".format(i + 1)} ${stab.name}", "description" to (stab.from?.let { "borrowed from C $it" } ?: ""),
                "notes" to stab.notes.joinToString(" ") { com.snipsnap.audio.Scales.nameOf(it) }, "file" to file,
                "seconds" to snip.samples.size.toDouble() / snip.sampleRate)
        }
        File(root, "manifest.json").writeText(Json.write(value(linkedMapOf("engine" to "CHORD STABS", "revision" to "modal-borrowed-1", "clips" to clips))) + "\n")
        println("CHORD STABS audition: ${progs.size} progressions and ${clips.size - progs.size} pads in ${root.absolutePath}")
    }

    private fun value(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is String -> JsonValue.Str(v)
        is Boolean -> JsonValue.Bool(v)
        is Number -> v.toDouble().let { require(it.isFinite()) { "non-finite audition value" }; JsonValue.Num(it) }
        is Map<*, *> -> JsonValue.Obj(v.entries.associate { it.key.toString() to value(it.value) })
        is Iterable<*> -> JsonValue.Arr(v.map { value(it) })
        else -> error("cannot write ${v::class.simpleName} to the manifest")
    }
}
