package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Cypher.Note
import com.snipsnap.synth.Gatecrash.Mode.STONE
import com.snipsnap.synth.Gatecrash.Space
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * The CYPHER kits: authored hip-hop drum kits, each sixteen pads (fourteen hits, then two phrases).
 *
 * A rendered kit family in the [GatecrashKits] shape, not a Patch engine: the hits come from THUMP (its new
 * SUB voice included), SKIN and RIVET as they stand, and a pad is printed dry or through a [Gatecrash] space.
 * The family's gesture: a genre's kick and snare, the space or converter its era printed them through, the
 * hats' grid (straight or rolled), and the phrase that proves the kit is that genre.
 *
 * Round 1 ships the two kits that need the sliding sub:
 * - [Kit.TRAP]: a short punch over subs that hold and slide, a piercing snare, hats on a hard grid that roll
 *   in triplets and sixty-fourths, a bell and a zap. Dry throughout: precision is the print.
 * - [Kit.DRILL]: subs that land then slide down on the off-beat, a snare and clap in a dark big room on the
 *   triplet grid, hats ticking under, metal hits.
 *
 * Every pad has three velocity zones of four takes. Where the engine strikes at a velocity (THUMP's KICK and
 * SUB, RIVET) a zone is its own strike; the other voices are [KitStrikes.touched]. A take is the engine's own
 * take where it has one (SUB deals five dimensions out, [Thump.SUB_TAKES]) and a small move of two or three
 * controls where it does not. A pad's class is authored here: the classifier reads a long sub as TONAL, and
 * the kit says KICK, because that is the pad it belongs on.
 *
 * Chokes: hats cut hats (group 1), the two phrases cut each other (group 2), and every sub cuts every other
 * sub (group 3), which is how sliding subs are played.
 *
 * The hats obey the ratchet rule measured in the prototype: a hat whose decay to -60 dB is at most 1.5 times
 * the shortest period in its kit's roll keeps a 35 dB dip between hits; past four times it is a buzz. TRAP's
 * roll reaches 1/64 at 140 BPM (26.8 ms), so its closed hat sits at DECAY 0 (40 ms); DRILL's reaches 1/32
 * triplets (35 ms), so its hat may ring 54 ms. `CypherTest` measures it on the render.
 *
 * Every setting below is a shape value, authored unheard.
 */
object CypherKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 3

    enum class Kit(val id: String, val title: String, val bpm: Int) {
        TRAP("cypher-trap", "Trap", 140),
        DRILL("cypher-drill", "Drill", 142),
        BACKROOM("cypher-backroom", "Backroom", 90),
        LOWRIDER("cypher-lowrider", "Lowrider", 93),
        DUST("cypher-dust", "Dust", 86),
        FLINT("cypher-flint", "Flint", 120),
    }

    /** A print that is not a space: a rack, a tape, a vinyl bed, applied to the finished dry (or spaced) pad. */
    internal class Print(val name: String, val apply: (Snip) -> Snip)

    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = 4
    const val HITS = 14
    const val PADS = 16

    /** Mute groups: hats, phrases, subs. */
    const val HAT_GROUP = 1
    const val PHRASE_GROUP = 2
    const val SUB_GROUP = 3

    /** One playable hit: what it is, the space it is printed in, its choke group, and how to strike it dry. */
    internal class Part(val name: String, val cls: DrumClass, val space: Space?, val mute: Int = 0, val strike: Strike,
                        /** A sub's authored controls, kept so the audition can play it at another DRIVE. */
                        val subMacros: Map<String, Float>? = null,
                        /** A rack, tape or vinyl print after the space (or instead of one). */
                        val print: Print? = null,
                        /** The hard first take is an accepted render at its own level ([KitStrikes.struck]'s `exact`). */
                        val exact: Boolean = false)

    /**
     * A phrase: notes on the kit's grid, one shared space (or dry), the parts that choke each other inside
     * it (a sub stops the sub before it, as the pads would on the sampler), and which parts the space prints
     * ([printed]; null is every part). Round 1's drill phrase sent its kick and hats through the room too:
     * every hat re-opened the gate, the room never closed, and the owner heard it as messy.
     */
    internal class Phrase(val name: String, val space: Space?, val notes: List<Note>, val choke: Set<Int> = emptySet(),
                          val printed: Set<Int>? = null, val print: Print? = null)

    /** The rack, tape or vinyl print on [role], or null. */
    internal fun printOf(kit: Kit, role: Int): Print? =
        if (role < HITS) parts(kit)[role].print else phrases(kit)[role - HITS].print

    /** A dry hit's level against a full one, as its space hears it. The finished zones are then levelled ([zoneGain]). */
    fun touch(velocity: Float): Float = .35f + .65f * velocity.coerceIn(0f, 1f)

    fun roles(kit: Kit): List<String> = parts(kit).map { it.name } + phrases(kit).map { it.name }

    /** The choke group of pad [role] in [kit]. */
    fun muteGroup(kit: Kit, role: Int): Int = if (role < HITS) parts(kit)[role].mute else PHRASE_GROUP

    fun build(kit: Kit): List<com.snipsnap.kit.ArrangedPad> {
        val parts = parts(kit)
        val names = roles(kit)
        return List(PADS) { role ->
            val grid = velocities.map { v -> List(TAKES) { t -> render(kit, role, v, t) } }
            val part = parts.getOrNull(role)
            com.snipsnap.kit.ArrangedPad(
                grid.last().first(), part?.cls ?: DrumClass.LOOP, displayName = names[role], velocityTakes = grid,
                muteGroup = muteGroup(kit, role),
                source = mapOf(
                    "engine" to "CYPHER", "kitVersion" to VERSION.toString(),
                    "stageVersion" to Gatecrash.VERSION.toString(), "phraseVersion" to Cypher.VERSION.toString(),
                    "kit" to kit.name, "role" to role.toString(), "bpm" to kit.bpm.toString(),
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to "finite",
                    "processing" to listOfNotNull(spaceOf(kit, role)?.mode?.name?.lowercase(), printOf(kit, role)?.name).joinToString("+").ifEmpty { "dry" },
                ),
            )
        }
    }

    /**
     * The space [role] is printed in, or null for a dry pad; its send measured once on the pad's own first
     * take (on a phrase, on the notes the space prints, since the others never reach it).
     */
    fun spaceOf(kit: Kit, role: Int): Space? {
        val authored = (if (role < HITS) parts(kit)[role].space else phrases(kit)[role - HITS].space) ?: return null
        return spaces.getOrPut(kit to role) { Gatecrash.calibrated(printedRaw(kit, role, 1f, 0), authored, seedOf(kit, role)) }
    }

    private val spaces = ConcurrentHashMap<Pair<Kit, Int>, Space>()

    private fun seedOf(kit: Kit, role: Int) = Dsp.seedFor("CYPHER", kit.name, role)

    /** The finished pad at one velocity and take. Pure: the same arguments give the same bytes. */
    fun render(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        return KitStrikes.finish(raw(kit, role, velocity, take), zoneGain(kit, role, velocity))
    }

    /** The same strike with no space, levelled by the same pad gain: the audition's dry comparison. */
    fun dry(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        return KitStrikes.finish(dryRaw(kit, role, velocity, take), zoneGain(kit, role, velocity))
    }

    /**
     * Each stage of one pad's space, for the tests that measure it. Null for a dry pad. On a phrase, `out`
     * is the printed notes with their room plus the unprinted notes dry; `ret` is the room alone.
     */
    internal fun stages(kit: Kit, role: Int, velocity: Float, take: Int): Gatecrash.Stages? {
        val space = spaceOf(kit, role) ?: return null
        val printed = Gatecrash.process(printedRaw(kit, role, velocity, take), space, touch(velocity), seedOf(kit, role))
        if (role < HITS || phrases(kit)[role - HITS].printed == null) return printed
        val dry = unprintedRaw(kit, role, velocity, take)
        val out = FloatArray(max(printed.out.size, dry.size)) { i ->
            (if (i < printed.out.size) printed.out[i] else 0f) + (if (i < dry.size) dry[i] else 0f)
        }
        return Gatecrash.Stages(out, printed.ret, printed.gate, printed.attacks)
    }

    private fun raw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val x = stages(kit, role, velocity, take)?.out ?: dryRaw(kit, role, velocity, take)
        val print = printOf(kit, role) ?: return x
        return print.apply(Snip(x, 1, Dsp.RATE)).samples
    }

    /** The phrase's bars at this kit's tempo plus the tail a long sub or room needs. */
    fun phraseSeconds(kit: Kit): Float = (bars(kit) * 4 * 60f / kit.bpm + 1.2f)

    /** Two-bar phrases on the two slow kits whose feel needs a second bar to show; one bar elsewhere. */
    fun bars(kit: Kit): Int = if (kit == Kit.BACKROOM || kit == Kit.LOWRIDER) 2 else 1

    /** The whole pad dry: a hit, or every note of a phrase. */
    internal fun dryRaw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val parts = parts(kit)
        if (role < HITS) return KitStrikes.struck(parts[role].strike, velocity, take, touch(velocity), exact = parts[role].exact)
        return phraseRaw(kit, phrases(kit)[role - HITS], velocity, take)
    }

    /** One part of a phrase alone, dry: for the probe that checks every written note is an onset of its part. */
    internal fun partRaw(kit: Kit, role: Int, part: Int, velocity: Float, take: Int): FloatArray =
        phraseRaw(kit, phrases(kit)[role - HITS], velocity, take, only = setOf(part))

    /** What the pad's space hears: the hit, or only the notes a phrase prints. */
    internal fun printedRaw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        if (role < HITS) return dryRaw(kit, role, velocity, take)
        val phrase = phrases(kit)[role - HITS]
        return phraseRaw(kit, phrase, velocity, take, only = phrase.printed)
    }

    /** The notes a phrase keeps dry beside its space; empty when the space prints every part. */
    private fun unprintedRaw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val phrase = phrases(kit)[role - HITS]
        val printed = phrase.printed ?: return FloatArray(0)
        return phraseRaw(kit, phrase, velocity, take, only = phrase.notes.map { it.part }.toSet() - printed)
    }

    /** [phraseRaw] for a phrase a test authors. */
    internal fun phraseRawForTest(kit: Kit, phrase: Phrase, velocity: Float, take: Int): FloatArray = phraseRaw(kit, phrase, velocity, take)

    /**
     * A dry phrase: every note a fresh strike at its offset start, summed, with the in-phrase chokes applied
     * as the sampler would. [only] limits it to notes of those parts (the take still walks over every note,
     * so a part's strikes are the same whether or not the other parts are summed).
     */
    private fun phraseRaw(kit: Kit, phrase: Phrase, velocity: Float, take: Int, only: Set<Int>? = null): FloatArray {
        val parts = parts(kit)
        val out = FloatArray((phraseSeconds(kit) * Dsp.RATE).toInt())
        val choked = phrase.notes.withIndex().filter { it.value.part in phrase.choke }.sortedBy { Cypher.startFrame(it.value, kit.bpm) }
        for ((i, note) in phrase.notes.withIndex()) {
            if (only != null && note.part !in only) continue
            val v = (velocity * note.weight).coerceIn(.05f, 1f)
            val hit = KitStrikes.struck(parts[note.part].strike, v, (take + i) % TAKES, touch(v), note.tuneSemis)
            val start = Cypher.startFrame(note, kit.bpm)
            // A choked part ends where the next choked note starts, over a 5 ms fade, as a pad would on the sampler.
            val next = if (note.part in phrase.choke) choked.firstOrNull { it.index != i && Cypher.startFrame(it.value, kit.bpm) > start }
                ?.let { Cypher.startFrame(it.value, kit.bpm) } else null
            val end = min(out.size, min(start + hit.size, next ?: Int.MAX_VALUE))
            val fade = (.005f * Dsp.RATE).toInt()
            for (j in start until end) {
                val gain = if (next != null && j > end - fade) (end - j).toFloat() / fade else 1f
                out[j] += hit[j - start] * gain
            }
        }
        return out
    }

    private val gains = ConcurrentHashMap<Triple<Kit, Int, Float>, Float>()

    /**
     * One gain per velocity zone, set by the zone's first take and shared by its four takes, so every zone
     * peaks at the same level: the sampler turns velocity into gain, and a zone that was also rendered quieter
     * would be turned down twice. The zones differ in how the drum was struck and how much room it got.
     */
    private fun zoneGain(kit: Kit, role: Int, velocity: Float): Float = gains.getOrPut(Triple(kit, role, velocity)) {
        val peak = KitStrikes.finish(raw(kit, role, velocity, 0), 1f, ceiling = Float.MAX_VALUE).peak()
        if (peak > 1e-9f) PAD_PEAK / peak else 1f
    }

    /** Where every zone's first take peaks. */
    const val PAD_PEAK = .92f

    // ---------- the kits ----------

    internal fun parts(kit: Kit): List<Part> = when (kit) {
        Kit.TRAP -> trap
        Kit.DRILL -> drill
        Kit.BACKROOM -> backroom
        Kit.LOWRIDER -> lowrider
        Kit.DUST -> dust
        Kit.FLINT -> flint
    }

    internal fun phrases(kit: Kit): List<Phrase> = when (kit) {
        Kit.TRAP -> trapPhrases
        Kit.DRILL -> drillPhrases
        Kit.BACKROOM -> backroomPhrases
        Kit.LOWRIDER -> lowriderPhrases
        Kit.DUST -> dustPhrases
        Kit.FLINT -> flintPhrases
    }

    private fun sub(name: String, vararg m: Pair<String, Float>) =
        Part(name, DrumClass.KICK, null, SUB_GROUP, KitStrikes.sub(*m), subMacros = mapOf(*m))

    /**
     * Not part of any kit: a sub pad's hard first take at another DRIVE, levelled on its own, so the owner
     * can hear the phone-band trade (a clean sub is weight a phone does not play) around the authored amount.
     */
    fun subTrial(kit: Kit, role: Int, drive: Float): Snip {
        require(role in 0 until HITS && drive.isFinite() && drive in 0f..1f)
        val macros = requireNotNull(parts(kit)[role].subMacros) { "role $role of ${kit.name} is not a sub" }
        val x = KitStrikes.struck(KitStrikes.sub(*(macros + ("DRIVE" to drive)).toList().toTypedArray()), 1f, 0, touch(1f))
        val peak = KitStrikes.finish(x.copyOf(), 1f, ceiling = Float.MAX_VALUE).peak()
        return KitStrikes.finish(x, if (peak > 1e-9f) PAD_PEAK / peak else 1f)
    }

    /** The DRIVE a sub pad was authored at, for the audition's labels. */
    fun subDrive(kit: Kit, role: Int): Float? = parts(kit).getOrNull(role)?.subMacros?.get("DRIVE")

    /** The slide intervals the kits use, on SLIDE's -12..+12 semitone scale centred at .5. */
    private const val FOURTH_DOWN = .5f - 5f / 24f
    private const val FIFTH_DOWN = .5f - 7f / 24f
    private const val OCTAVE_UP = 1f

    // TRAP. Dry: precision is the print. The subs are four pads so a line can be played, and they choke each other.
    private val trap: List<Part> = listOf(
        Part("Punch", DrumClass.KICK, null,
            strike = KitStrikes.thumpKick("TUNE" to .55f, "SWEEP" to .70f, "DECAY" to .25f, "CLICK" to .60f, "DRIVE" to .45f, "PUNCH" to .6f)),
        Part("Pierce Snare", DrumClass.SNARE, null,
            strike = KitStrikes.thump(ThumpVoice.SNARE, mapOf("TUNE" to .72f, "DECAY" to .30f, "PUNCH" to .6f)) { v -> mapOf("SNAP" to .55f + .3f * v, "TONE" to .45f + .25f * v) }),
        // DECAY 0 is a 40 ms hat: the roll on A15 reaches 1/64 at 140 BPM (26.8 ms between hits). The take
        // moves TUNE and METAL, never DECAY, so no take breaks the ratchet rule.
        Part("Tick", DrumClass.HAT_CLOSED, null, HAT_GROUP,
            strike = KitStrikes.thump(ThumpVoice.HAT_CLOSED, mapOf("TUNE" to .60f, "DECAY" to 0f, "METAL" to .55f), vary = listOf("TUNE", "METAL")) { v -> mapOf("NOISE" to .05f + .12f * v) }),
        Part("Open Tick", DrumClass.HAT_OPEN, null, HAT_GROUP,
            strike = KitStrikes.thump(ThumpVoice.HAT_OPEN, mapOf("TUNE" to .60f, "DECAY" to .50f, "METAL" to .55f)) { v -> mapOf("NOISE" to .05f + .12f * v) }),
        sub("Sub Root", "TUNE" to .50f, "DECAY" to .70f, "SLIDE" to .5f, "DRIVE" to .45f, "CLICK" to .60f),
        // The low root's harmonics start lower too, so it is driven harder to reach the phone band.
        sub("Sub Low", "TUNE" to .08f, "DECAY" to .70f, "SLIDE" to .5f, "DRIVE" to .62f, "CLICK" to .60f),
        sub("Slide Down", "TUNE" to .50f, "DECAY" to .70f, "SLIDE" to FOURTH_DOWN, "WHEN" to .45f, "DRIVE" to .45f, "CLICK" to .60f),
        sub("Slide Up", "TUNE" to .50f, "DECAY" to .70f, "SLIDE" to OCTAVE_UP, "WHEN" to .35f, "DRIVE" to .45f, "CLICK" to .60f),
        Part("Clap", DrumClass.CLAP, null,
            strike = KitStrikes.thump(ThumpVoice.CLAP, mapOf("SPREAD" to .30f, "DECAY" to .30f), vary = listOf("SPREAD", "DECAY", "TONE")) { v -> mapOf("TONE" to .40f + .3f * v) }),
        Part("Rim", DrumClass.PERC, null,
            strike = KitStrikes.thump(ThumpVoice.RIM, mapOf("TUNE" to .80f, "DECAY" to .20f), vary = listOf("TUNE", "DECAY", "RING"))),
        Part("Bell", DrumClass.PERC, null,
            strike = KitStrikes.thump(ThumpVoice.COWBELL, mapOf("TUNE" to .40f, "DECAY" to .30f)) { v -> mapOf("TONE" to .35f + .3f * v) }),
        Part("Zap", DrumClass.PERC, null,
            strike = KitStrikes.thump(ThumpVoice.SPARK, mapOf("TUNE" to .70f, "DECAY" to .25f, "COLOR" to .80f)) { v -> mapOf("METAL" to .1f + .3f * v) }),
        // The brief's wide shaker pair, as two tunings in mono.
        Part("Shaker Left", DrumClass.PERC, null,
            strike = KitStrikes.skinTouched(SkinVoice.SHAKER, mapOf("TONE" to .80f, "DECAY" to .15f, "GRAIN" to .40f), vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("PUNCH" to .4f + .3f * v) }),
        Part("Shaker Right", DrumClass.PERC, null,
            strike = KitStrikes.skinTouched(SkinVoice.SHAKER, mapOf("TONE" to .60f, "DECAY" to .18f, "GRAIN" to .60f), vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("PUNCH" to .4f + .3f * v) }),
    )

    private val trapPhrases = listOf(
        // The signature: one bar of the closed hat tightening from eighths to sixteenths to 1/32 triplets to
        // 1/64, growing louder, and rising four semitones across the last beat.
        Phrase("Hat Roll", null,
            Cypher.ratchet(2, 0.0, .5, 2, .50f, .60f) + Cypher.ratchet(2, 1.0, .25, 4, .60f, .70f) +
                Cypher.ratchet(2, 2.0, 1.0 / 12, 12, .70f, .85f) + Cypher.ratchet(2, 3.0, 1.0 / 16, 16, .85f, 1f, riseSemis = 4f)),
        // A sub line: the root on one, a slide down on the and of two, a slide up on the and of three, the low root on four.
        Phrase("Slide Line", null, listOf(Note(0.0, 4, 1f), Note(1.5, 6, .95f), Note(2.5, 7, .90f), Note(3.0, 5, 1f)), choke = setOf(4, 5, 6, 7)),
    )

    // DRILL. Its subs sit a major third above TRAP's (TUNE .62, about 63 Hz): a fifth down from there is
    // 42 Hz, which a phone still hints at and the pitch probe can still read.
    // The snare, clap and metal hits are printed in one dark big room: a large hard room, half crushed,
    // open 140 ms, closing over 60 ms, 3 dB under the drum in energy and low-passed at 3 kHz.
    private val drillRoom = Space(STONE, size = .75f, walls = .70f, crush = .50f, holdMs = 140f, closeMs = 60f, roomDb = -3f, toneHz = 3_000f)
    private val drillMetalRoom = drillRoom.copy(roomDb = -6f)

    private val drill: List<Part> = listOf(
        Part("Drill Kick", DrumClass.KICK, null,
            strike = KitStrikes.thumpKick("TUNE" to .45f, "SWEEP" to .60f, "DECAY" to .28f, "CLICK" to .50f, "DRIVE" to .40f, "PUNCH" to .5f)),
        // Not THUMP's snare: every THUMP snare shares one wire-noise stream, so two of them read as one drum
        // in different clothes. ONSET's snare blends an electronic body with a physical one; the room is its edge.
        Part("Room Snare", DrumClass.SNARE, drillRoom,
            strike = KitStrikes.onset(OnsetVoice.SNARE, mapOf("TUNE" to .50f, "DECAY" to .40f, "BLEND" to .60f, "BODY" to .60f)) { v -> mapOf("SNAP" to .40f + .3f * v, "EDGE" to .30f + .3f * v) }),
        // DECAY .12 is a 47 ms hat; the drill roll reaches 1/32 triplets at 142 BPM (35 ms between hits).
        Part("Tick", DrumClass.HAT_CLOSED, null, HAT_GROUP,
            strike = KitStrikes.thump(ThumpVoice.HAT_CLOSED, mapOf("TUNE" to .60f, "DECAY" to .12f, "METAL" to .60f), vary = listOf("TUNE", "METAL")) { v -> mapOf("NOISE" to .05f + .12f * v) }),
        Part("Open Tick", DrumClass.HAT_OPEN, null, HAT_GROUP,
            strike = KitStrikes.thump(ThumpVoice.HAT_OPEN, mapOf("TUNE" to .60f, "DECAY" to .45f, "METAL" to .60f)) { v -> mapOf("NOISE" to .05f + .12f * v) }),
        sub("Sub", "TUNE" to .62f, "DECAY" to .80f, "SLIDE" to .5f, "DRIVE" to .40f, "CLICK" to .50f),
        sub("Slide Down", "TUNE" to .62f, "DECAY" to .80f, "SLIDE" to FIFTH_DOWN, "WHEN" to .60f, "DRIVE" to .40f, "CLICK" to .50f),
        sub("Slide Up", "TUNE" to .62f, "DECAY" to .80f, "SLIDE" to OCTAVE_UP, "WHEN" to .35f, "DRIVE" to .40f, "CLICK" to .50f),
        // The slide that lands on the next beat.
        sub("Slide Late", "TUNE" to .62f, "DECAY" to .90f, "SLIDE" to FOURTH_DOWN, "WHEN" to .75f, "DRIVE" to .40f, "CLICK" to .50f),
        Part("Room Clap", DrumClass.CLAP, drillRoom,
            strike = KitStrikes.thump(ThumpVoice.CLAP, mapOf("SPREAD" to .40f, "DECAY" to .35f), vary = listOf("SPREAD", "DECAY", "TONE")) { v -> mapOf("TONE" to .35f + .3f * v) }),
        Part("Rim", DrumClass.PERC, null,
            strike = KitStrikes.thump(ThumpVoice.RIM, mapOf("TUNE" to .90f, "DECAY" to .20f), vary = listOf("TUNE", "DECAY", "RING"))),
        Part("Metal Hit", DrumClass.PERC, drillMetalRoom,
            strike = KitStrikes.rivet(RivetVoice.METAL, mapOf("METAL" to .70f, "DRIVE" to .60f, "SIZE" to .50f, "WALLS" to .70f))),
        Part("Metal Low", DrumClass.PERC, drillMetalRoom,
            strike = KitStrikes.rivet(RivetVoice.METAL, mapOf("TUNE" to .30f, "METAL" to .80f, "DRIVE" to .70f, "SIZE" to .50f, "WALLS" to .70f))),
        Part("Grit Snare", DrumClass.SNARE, null,
            strike = KitStrikes.rivet(RivetVoice.SNARE, mapOf("STRIKE" to .70f, "METAL" to .50f, "DRIVE" to .60f), vary = listOf("TUNE", "STRIKE"))),
        Part("Shaker", DrumClass.PERC, null,
            strike = KitStrikes.skinTouched(SkinVoice.SHAKER, mapOf("TONE" to .70f, "DECAY" to .15f), vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("PUNCH" to .4f + .3f * v) }),
    )

    private val drillPhrases = listOf(
        // The drill snare bar, round 1b. Round 1 put the snare on two and the and of three with two
        // sixteenth-triplet claps before each, and sent the kick and hats through the room too; the owner
        // heard it as messy. Now: the snare on three and the and of four (the drill backbeat that leans into
        // the next bar), one eighth-triplet clap pickup before each, the kick on one and the and of two,
        // hats in straight eighths. Only the snare and the clap go to the room; the kick and hats stay dry,
        // as they are on their own pads, so the gate is keyed by the snare and not by every hat.
        Phrase("Drill Snare", drillRoom,
            listOf(Note(0.0, 0, 1f), Note(1.5, 0, .85f), Note(2.0, 1, 1f), Note(3.5, 1, .95f),
                Note(2 - 1.0 / 3, 8, .55f), Note(3.5 - 1.0 / 3, 8, .50f)) +
                List(8) { Note(it * .5, 2, if (it % 2 == 0) .7f else .45f) },
            printed = setOf(1, 8)),
        // The sub pattern: the root on one, down a fifth on the and of two, the late slide on three, up an octave before four.
        Phrase("Slide Call", null,
            listOf(Note(0.0, 0, 1f), Note(0.0, 4, 1f), Note(1.5, 5, .95f), Note(2.0, 7, .9f), Note(3.5, 6, .9f)), choke = setOf(4, 5, 6, 7)),
    )

    // ---------- BACKROOM (boom bap, 90) ----------
    // The accepted Backroom six are the kit's core: their strikes are the accepted SKIN patches and their
    // print is the accepted rack (CRUNCH → TAPE → ROOM, the snare's SPIKE), so A01 to A06's hard first take is
    // the sound the owner accepted on 2026-11-07, levelled to the kit's zone peak. The ten new pads get the
    // same rack. The study's six are read from BoomBapKits so one table feeds both.

    private fun backroomRack(id: String) = Print("backroom", { BoomBapKits.colorationFor(id).process(it) })

    private val backroomStudy = BoomBapKits.parts().associateBy { it.id }

    /** An accepted Backroom part: SKIN's struck voices re-strike; the others are touched, with the hard first take exact. */
    private fun accepted(id: String, name: String, cls: DrumClass, mute: Int = 0): Part {
        val study = backroomStudy.getValue(id)
        val voice = study.patch.voice
        // A struck voice re-strikes with a drummer's force spread on the later takes (as skinStruck does);
        // the hard first take is the accepted render itself, at its own level into its rack (exact).
        val strike: Strike = if (voice in Skin.STRUCK) { v, t, _ ->
            val force = if (t == 0) v else Restrike.landing(v, Dsp.seedFor("CYPHER", "backroom", id), t).first
            val patch = if (t == 0) study.patch else study.patch.copy(macros = KitStrikes.varied(study.patch.macros, KitStrikes.skinTakeVary(voice), t, KitStrikes.SKIN_TAKE_STEP))
            patch.render(velocity = force, take = t).samples
        } else when (voice) {
            SkinVoice.HAT_CLOSED, SkinVoice.HAT_OPEN -> KitStrikes.skinTouched(voice, study.patch.macros, exactFirst = true) { v -> mapOf("PUNCH" to .35f + .3f * v) }
            SkinVoice.STICK -> KitStrikes.skinTouched(voice, study.patch.macros, exactFirst = true) { v -> mapOf("CLICK" to (study.patch.macros["CLICK"] ?: .2f) * (.5f + .5f * v)) }
            else -> KitStrikes.skinTouched(voice, study.patch.macros, vary = listOf("TONE", "DECAY", "GRAIN"), exactFirst = true) { v -> mapOf("PUNCH" to .4f + .3f * v) }
        }
        return Part(name, cls, null, mute, strike, print = backroomRack(id), exact = true)
    }

    private val backroom: List<Part> = listOf(
        accepted("kick", "Pushed Knock", DrumClass.KICK),
        accepted("snare", "Snap Paper", DrumClass.SNARE),
        accepted("closed", "Pushed Tip", DrumClass.HAT_CLOSED, HAT_GROUP),
        accepted("open", "Pushed Edge", DrumClass.HAT_OPEN, HAT_GROUP),
        accepted("stick", "Pushed Stick", DrumClass.PERC),
        accepted("shaker", "Pushed Seeds", DrumClass.PERC),
        // The deeper second kick a chopped break gives you.
        Part("Low Knock", DrumClass.KICK, null, strike = KitStrikes.skinStruck(SkinVoice.KICK, "TUNE" to .22f, "DECAY" to .40f, "CLICK" to .25f, "PUNCH" to .60f), print = backroomRack("kick")),
        // The clap and rim layers the brief names under the snare.
        Part("Clap Layer", DrumClass.CLAP, null, strike = KitStrikes.thump(ThumpVoice.CLAP, mapOf("SPREAD" to .35f, "DECAY" to .35f), vary = listOf("SPREAD", "DECAY", "TONE")) { v -> mapOf("TONE" to .35f + .3f * v) }, print = backroomRack("snare")),
        Part("Rim Layer", DrumClass.PERC, null, strike = KitStrikes.thump(ThumpVoice.RIM, mapOf("TUNE" to .55f, "DECAY" to .20f), vary = listOf("TUNE", "DECAY", "RING")), print = backroomRack("snare")),
        // The ghost the study carries as a zone, promoted to a pad for finger drummers.
        Part("Ghost Snare", DrumClass.SNARE, null, strike = KitStrikes.softer({ v, t, _ -> backroomStudy.getValue("snare").patch.render(velocity = v, take = t).samples }, .35f), print = backroomRack("snare")),
        Part("Rack Tom", DrumClass.TOM, null, strike = KitStrikes.skinStruck(SkinVoice.TOM, "TUNE" to .55f, "DECAY" to .45f, "TONE" to .45f), print = backroomRack("kick")),
        Part("Floor Tom", DrumClass.TOM, null, strike = KitStrikes.skinStruck(SkinVoice.TOM, "TUNE" to .20f, "DECAY" to .55f, "TONE" to .40f), print = backroomRack("kick")),
        Part("Dark Ride", DrumClass.PERC, null, strike = KitStrikes.skinTouched(SkinVoice.RIDE, mapOf("TUNE" to .35f, "DECAY" to .50f, "TONE" to .35f)) { v -> mapOf("PUNCH" to .3f + .4f * v, "SIZZLE" to .2f * v) }, print = backroomRack("kick")),
        // The chopped-vibe stab a boom bap kit always has one of.
        Part("Bell Hit", DrumClass.PERC, null, strike = KitStrikes.tines(TinesVoice.BELL, mapOf("TUNE" to .35f, "DECAY" to .30f, "RATIO" to .7f)), print = backroomRack("kick")),
    )

    /** Boom bap's swing: every off-beat sixteenth late by this share of a sixteenth (a sampler's "58 percent"). Shape. */
    const val BACKROOM_SWING = .14f

    private val backroomPhrases = listOf(
        // The head nod written down: one bar of sixteenths on the closed hat, accents on the eighths, two open
        // hats, every off-beat sixteenth late by the swing.
        Phrase("Swung Hats", null,
            Cypher.swung(List(16) { Note(it * .25, if (it == 7 || it == 15) 3 else 2, if (it % 4 == 0) .85f else if (it % 2 == 0) .6f else .4f) }, BACKROOM_SWING, Kit.BACKROOM.bpm),
            print = backroomRack("kick")),
        // The kit playing itself: two bars of kick, snare and hats, swung, the snare 6 ms behind the kick, a rim pickup.
        Phrase("Break Drop", null,
            Cypher.swung(listOf(0.0, 4.0).flatMap { o ->
                listOf(Note(o + 0.0, 0, 1f), Note(o + 1.0, 1, 1f, offsetMs = 6f), Note(o + 2.5, 0, .85f), Note(o + 3.0, 1, 1f, offsetMs = 6f), Note(o + 3.75, 0, .6f)) +
                    List(8) { Note(o + it * .5, if (it == 7) 3 else 2, if (it % 2 == 0) .7f else .45f) }
            } + listOf(Note(3.5, 8, .5f), Note(7.25, 1, .55f), Note(7.5, 4, .6f), Note(7.75, 1, .7f)), BACKROOM_SWING, Kit.BACKROOM.bpm),
            print = backroomRack("kick")),
    )

    // ---------- LOWRIDER (G-funk, 93) ----------
    // A long room that is a room: the GATECRASH stage with no crush and a hold longer than the room's own
    // decay, so it rings out and the gate only tidies the last of the tail. Energy-measured like every space.
    private val longRoom = Space(STONE, size = .75f, walls = .80f, crush = 0f, holdMs = 900f, closeMs = 300f, roomDb = 2f, drive = .1f, toneHz = 6_000f)
    private val smallRoom = Space(STONE, size = .30f, walls = .60f, crush = .2f, holdMs = 120f, closeMs = 60f, roomDb = -6f, toneHz = 4_000f)

    // Deep and fat: tuned well under BACKROOM's accepted snare (also SKIN's), with a long wire tail; the two measured as one drum at .45.
    private val lowriderSnare = KitStrikes.skinStruck(SkinVoice.SNARE, "TUNE" to .22f, "SNAP" to .55f, "DECAY" to .70f, "RATTLE" to .80f, "SIZZLE" to .35f, "PUNCH" to .60f)
    private val lowriderClap = KitStrikes.thump(ThumpVoice.CLAP, mapOf("SPREAD" to .60f, "DECAY" to .50f, "ROOM" to .70f), vary = listOf("SPREAD", "DECAY", "TONE")) { v -> mapOf("TONE" to .35f + .3f * v) }

    private val lowrider: List<Part> = listOf(
        // The acoustic-electronic hybrid: a synth sine that dives slowly and roundly, under an acoustic knock.
        Part("Hybrid Thump", DrumClass.KICK, null, strike = KitStrikes.layered(
            KitStrikes.thumpKick("TUNE" to .30f, "SWEEP" to .35f, "DECAY" to .60f, "BEND" to .30f, "CLICK" to .20f, "PUNCH" to .5f), .7f,
            KitStrikes.skinStruck(SkinVoice.KICK, "TUNE" to .35f, "DECAY" to .30f, "CLICK" to .60f, "TONE" to .50f), .5f)),
        Part("Fat Snare", DrumClass.SNARE, longRoom, strike = lowriderSnare),
        Part("Closed Hat", DrumClass.HAT_CLOSED, null, HAT_GROUP,
            strike = KitStrikes.thump(ThumpVoice.HAT_CLOSED, mapOf("TUNE" to .55f, "DECAY" to .30f, "METAL" to .40f)) { v -> mapOf("NOISE" to .05f + .12f * v) }),
        Part("Open Hat", DrumClass.HAT_OPEN, null, HAT_GROUP,
            strike = KitStrikes.thump(ThumpVoice.HAT_OPEN, mapOf("TUNE" to .55f, "DECAY" to .60f, "METAL" to .40f)) { v -> mapOf("NOISE" to .05f + .12f * v) }),
        Part("Big Clap", DrumClass.CLAP, longRoom.copy(roomDb = 3f), strike = lowriderClap),
        Part("Snare and Clap", DrumClass.SNARE, longRoom, strike = KitStrikes.layered(lowriderSnare, .8f, lowriderClap, .6f)),
        Part("Rim", DrumClass.PERC, smallRoom, strike = KitStrikes.thump(ThumpVoice.RIM, mapOf("TUNE" to .50f, "DECAY" to .25f), vary = listOf("TUNE", "DECAY", "RING"))),
        Part("Bell", DrumClass.PERC, null, strike = KitStrikes.thump(ThumpVoice.COWBELL, mapOf("TUNE" to .50f, "DECAY" to .40f)) { v -> mapOf("TONE" to .35f + .3f * v) }),
        Part("High Tom", DrumClass.TOM, longRoom.copy(roomDb = 0f), strike = KitStrikes.skinStruck(SkinVoice.TOM, "TUNE" to .70f, "DECAY" to .50f, "TONE" to .50f)),
        Part("Low Tom", DrumClass.TOM, longRoom.copy(roomDb = 0f), strike = KitStrikes.skinStruck(SkinVoice.TOM, "TUNE" to .30f, "DECAY" to .60f, "TONE" to .45f)),
        Part("Shaker", DrumClass.PERC, null, strike = KitStrikes.skinTouched(SkinVoice.SHAKER, mapOf("TONE" to .50f, "DECAY" to .30f), vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("PUNCH" to .4f + .3f * v) }),
        // A tuned open hat standing in for a tambourine (BREAKER's is a recording and stays BREAKER's). Disclosed.
        Part("Jingle", DrumClass.PERC, null, strike = KitStrikes.skinTouched(SkinVoice.HAT_OPEN, mapOf("TUNE" to .80f, "DECAY" to .30f, "TONE" to .70f, "RING" to .90f)) { v -> mapOf("PUNCH" to .35f + .3f * v) }),
        // The P-funk synth bass note under the kick: no slide.
        sub("Sub Note", "TUNE" to .25f, "DECAY" to .60f, "SLIDE" to .5f, "DRIVE" to .25f, "CLICK" to .20f),
        Part("Snap", DrumClass.PERC, smallRoom, strike = KitStrikes.skinTouched(SkinVoice.STICK, mapOf("TUNE" to .60f, "DECAY" to .30f, "BODY" to .30f, "CLICK" to .40f)) { v -> mapOf("CLICK" to .25f + .4f * v) }),
    )

    private val lowriderPhrases = listOf(
        // Behind the beat: the snare and clap 18 ms late, the hats 8 ms late, the kick on time. Two bars; the
        // snare notes alone go to the long room.
        Phrase("Laid Back", longRoom,
            Cypher.behind(Cypher.behind(listOf(0.0, 4.0).flatMap { o ->
                listOf(Note(o + 0.0, 0, 1f), Note(o + 2.5, 0, .85f), Note(o + 1.0, 5, 1f), Note(o + 3.0, 5, 1f)) +
                    List(8) { Note(o + it * .5, 2, if (it % 2 == 0) .65f else .4f) } + List(16) { Note(o + it * .25, 10, if (it % 4 == 2) .5f else .3f) }
            } + listOf(Note(7.5, 7, .6f)), setOf(5), 18f), setOf(2), 8f),
            printed = setOf(5)),
        // A bar of snare sixteenths growing into the big clap on the next downbeat, in the room.
        Phrase("Big Fill", longRoom, Cypher.ratchet(1, 0.0, .25, 16, .40f, 1f) + listOf(Note(4.0, 4, 1f)), printed = setOf(1, 4)),
    )

    // ---------- DUST (underground cinematic boom bap, 86) ----------
    // No compressor anywhere: the print is the TAPE section driven hard; VINYL only under the phrases.
    private val tape = Print("tape", { Tape.process(it, mapOf("WOBBLE" to .05f, "DRIVE" to .70f, "AGE" to .75f)) })
    private val tapeLight = Print("tape", { Tape.process(it, mapOf("WOBBLE" to .05f, "DRIVE" to .40f, "AGE" to .60f)) })
    private val tapeVinyl = Print("tape+vinyl", { Vinyl.process(Tape.process(it, mapOf("WOBBLE" to .05f, "DRIVE" to .70f, "AGE" to .75f)), mapOf("CRACKLE" to .50f, "RUMBLE" to .30f, "HISS" to .40f)) })

    private val woodCrack = KitStrikes.skinStruck(SkinVoice.SNARE, "TUNE" to .85f, "SNAP" to .80f, "DECAY" to .20f, "RATTLE" to .30f, "SIZZLE" to .20f, "PUNCH" to .30f)

    private val dust: List<Part> = listOf(
        Part("Tape Thud", DrumClass.KICK, null, strike = KitStrikes.skinStruck(SkinVoice.KICK, "TUNE" to .30f, "DECAY" to .35f, "TONE" to .35f, "PUNCH" to .30f, "CLICK" to .40f), print = tape),
        Part("Wood Crack", DrumClass.SNARE, null, strike = woodCrack, print = tape),
        Part("Dry Tip", DrumClass.HAT_CLOSED, null, HAT_GROUP, strike = KitStrikes.skinTouched(SkinVoice.HAT_CLOSED, mapOf("TUNE" to .50f, "DECAY" to .15f, "TONE" to .35f)) { v -> mapOf("PUNCH" to .35f + .3f * v) }, print = tapeLight),
        Part("Dry Edge", DrumClass.HAT_OPEN, null, HAT_GROUP, strike = KitStrikes.skinTouched(SkinVoice.HAT_OPEN, mapOf("TUNE" to .50f, "DECAY" to .35f, "TONE" to .35f)) { v -> mapOf("PUNCH" to .35f + .3f * v) }, print = tapeLight),
        Part("Rim Click", DrumClass.PERC, null, strike = KitStrikes.skinTouched(SkinVoice.STICK, mapOf("TUNE" to .70f, "DECAY" to .25f, "BODY" to .15f, "CLICK" to .60f)) { v -> mapOf("CLICK" to .3f + .4f * v) }, print = tape),
        // The wooden snare the brief describes, as two woods: the crack over a slapped box.
        Part("Box Snare", DrumClass.SNARE, null, strike = KitStrikes.layered(woodCrack, .8f, KitStrikes.terra(TerraVoice.BOX, mapOf("TUNE" to .55f, "DECAY" to .35f, "FORCE" to .90f)), .5f), print = tape),
        Part("Second Thud", DrumClass.KICK, null, strike = KitStrikes.skinStruck(SkinVoice.KICK, "TUNE" to .40f, "DECAY" to .25f, "TONE" to .30f, "PUNCH" to .20f), print = tape),
        // BREAKER's fitted crash, 1.4 s with a long fade so it stays a hit and not a loop.
        Part("Crash", DrumClass.PERC, null, strike = KitStrikes.cymbal(BreakerCymbal.Kind.CRASH, 1.4f, .5f), print = tape),
        Part("Dark Ride", DrumClass.PERC, null, strike = KitStrikes.skinTouched(SkinVoice.RIDE, mapOf("TUNE" to .30f, "DECAY" to .50f, "TONE" to .30f)) { v -> mapOf("PUNCH" to .3f + .4f * v, "SIZZLE" to .2f * v) }, print = tape),
        Part("Low Tom", DrumClass.TOM, null, strike = KitStrikes.skinStruck(SkinVoice.TOM, "TUNE" to .25f, "DECAY" to .50f, "TONE" to .35f), print = tape),
        Part("Shaker", DrumClass.PERC, null, strike = KitStrikes.skinTouched(SkinVoice.SHAKER, mapOf("TONE" to .40f, "DECAY" to .30f), vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("PUNCH" to .4f + .3f * v) }, print = tapeLight),
        Part("Soft Snare", DrumClass.SNARE, null, strike = KitStrikes.softer(woodCrack, .4f), print = tape),
        // The lone stab the drumless stretch plays over.
        Part("Bell Stab", DrumClass.PERC, null, strike = KitStrikes.tines(TinesVoice.BELL, mapOf("TUNE" to .30f, "DECAY" to .30f, "RATIO" to .7f)), print = tape),
        Part("Knock", DrumClass.PERC, null, strike = KitStrikes.terra(TerraVoice.BOX, mapOf("TUNE" to .30f, "DECAY" to .45f, "FORCE" to .70f)), print = tape),
    )

    /** The unquantized break's spread, either side of the grid, milliseconds. Shape. */
    const val DUST_SPREAD_MS = 22f

    private val dustBreak = Cypher.humanised(
        listOf(Note(0.0, 0, 1f), Note(1.0, 1, .95f), Note(1.75, 0, .7f), Note(2.5, 0, .85f), Note(3.0, 1, 1f), Note(3.75, 4, .55f)) +
            List(8) { Note(it * .5, if (it == 7) 3 else 2, if (it % 2 == 0) .7f else .5f) },
        DUST_SPREAD_MS, "dust-break")

    private val dustPhrases = listOf(
        // One bar, every note off the grid by its own fixed amount, on a bed of crackle.
        Phrase("Dusty Break", null, dustBreak, print = tapeVinyl),
        // The crash on the one, then the same break.
        Phrase("Crash Break", null, listOf(Note(0.0, 7, 1f)) + dustBreak, print = tapeVinyl),
    )

    // ---------- FLINT (Michigan scat, 120) ----------
    private val flint: List<Part> = listOf(
        // ONSET's kick, not THUMP's: DRILL's and TRAP's are THUMP kicks, and a third at these settings measured as the same drum.
        Part("Fast Kick", DrumClass.KICK, null, strike = KitStrikes.onsetKick("TUNE" to .60f, "DECAY" to .25f, "BLEND" to .70f, "SNAP" to .80f, "EDGE" to .60f)),
        // A very high, sizzling SKIN snare: not THUMP's (TRAP's) and not ONSET's (DRILL's).
        Part("High Snare", DrumClass.SNARE, null, strike = KitStrikes.skinStruck(SkinVoice.SNARE, "TUNE" to .95f, "SNAP" to .90f, "DECAY" to .18f, "RATTLE" to .35f, "SIZZLE" to .70f, "PUNCH" to .70f)),
        // DECAY .1 is a 46 ms hat; the scatter's 1/32 pickup at 120 BPM is 62 ms.
        Part("Short Hat", DrumClass.HAT_CLOSED, null, HAT_GROUP, strike = KitStrikes.thump(ThumpVoice.HAT_CLOSED, mapOf("TUNE" to .65f, "DECAY" to .10f, "METAL" to .50f), vary = listOf("TUNE", "METAL")) { v -> mapOf("NOISE" to .05f + .12f * v) }),
        Part("Open Hat", DrumClass.HAT_OPEN, null, HAT_GROUP, strike = KitStrikes.thump(ThumpVoice.HAT_OPEN, mapOf("TUNE" to .65f, "DECAY" to .40f, "METAL" to .50f)) { v -> mapOf("NOISE" to .05f + .12f * v) }),
        sub("Early Slide", "TUNE" to .45f, "DECAY" to .50f, "SLIDE" to FOURTH_DOWN, "WHEN" to .20f, "DRIVE" to .50f, "CLICK" to .50f),
        sub("Sub Root", "TUNE" to .45f, "DECAY" to .50f, "SLIDE" to .5f, "DRIVE" to .50f, "CLICK" to .50f),
        Part("High Rim", DrumClass.PERC, null, strike = KitStrikes.thump(ThumpVoice.RIM, mapOf("TUNE" to .90f, "DECAY" to .18f), vary = listOf("TUNE", "DECAY", "RING"))),
        Part("Higher Rim", DrumClass.PERC, null, strike = KitStrikes.thump(ThumpVoice.RIM, mapOf("TUNE" to 1f, "DECAY" to .15f), vary = listOf("DECAY", "RING", "BODY"))),
        Part("Wood Click", DrumClass.PERC, null, strike = KitStrikes.skinTouched(SkinVoice.STICK, mapOf("TUNE" to .90f, "DECAY" to .15f, "BODY" to 0f, "CLICK" to .80f)) { v -> mapOf("CLICK" to .4f + .4f * v) }),
        Part("Box Click", DrumClass.PERC, null, strike = KitStrikes.terra(TerraVoice.BOX, mapOf("TUNE" to .80f, "DECAY" to .30f, "FORCE" to .90f))),
        Part("Block", DrumClass.PERC, null, strike = KitStrikes.tines(TinesVoice.BLOCK, mapOf("TUNE" to .60f, "DECAY" to .30f))),
        Part("Off Bell", DrumClass.PERC, null, strike = KitStrikes.thump(ThumpVoice.COWBELL, mapOf("TUNE" to .70f, "DECAY" to .25f)) { v -> mapOf("TONE" to .35f + .3f * v) }),
        Part("Clap", DrumClass.CLAP, null, strike = KitStrikes.thump(ThumpVoice.CLAP, mapOf("SPREAD" to .20f, "DECAY" to .25f), vary = listOf("SPREAD", "DECAY", "TONE")) { v -> mapOf("TONE" to .40f + .3f * v) }),
        Part("Zap", DrumClass.PERC, null, strike = KitStrikes.thump(ThumpVoice.SPARK, mapOf("TUNE" to .80f, "DECAY" to .20f, "COLOR" to .90f)) { v -> mapOf("METAL" to .1f + .3f * v) }),
    )

    /** The clicks' scatter off the grid, either side, milliseconds. Shape. */
    const val FLINT_SCATTER_MS = 6f

    private val flintPhrases = listOf(
        // The four click pads on an off-balance sixteenth grid with a 1/32 pickup, each click a few ms off.
        Phrase("Click Scatter", null,
            Cypher.humanised(listOf(Note(.5, 6, .9f), Note(.75, 8, .6f), Note(1.5, 7, .8f), Note(1.75, 9, .7f), Note(2.125, 8, .5f), Note(2.25, 6, 1f),
                Note(2.5, 10, .6f), Note(3.25, 7, .85f), Note(3.75, 9, .65f)), FLINT_SCATTER_MS, "flint-scatter") +
                listOf(Note(0.0, 0, 1f), Note(2.0, 1, 1f), Note(3.0, 0, .8f))),
        // The root on one, the early slide on the and of one and on three, the root on the and of four.
        Phrase("Slide Stack", null, listOf(Note(0.0, 5, 1f), Note(0.5, 4, .9f), Note(2.0, 4, .95f), Note(3.5, 5, .9f)), choke = setOf(4, 5)),
    )

    /** The shortest interval between two hits of [part] in [kit]'s phrases, seconds; null if the part never repeats. */
    fun shortestPeriod(kit: Kit, part: Int): Float? {
        val starts = phrases(kit).flatMap { phrase -> phrase.notes.filter { it.part == part }.map { Cypher.startFrame(it, kit.bpm) }.sorted().zipWithNext { a, b -> b - a } }
        return starts.minOrNull()?.let { it.toFloat() / Dsp.RATE }
    }
}
