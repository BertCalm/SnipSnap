package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Cypher.Note
import com.snipsnap.synth.Gatecrash.Mode.STONE
import com.snipsnap.synth.Gatecrash.Space
import java.util.concurrent.ConcurrentHashMap
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
    const val VERSION = 1

    enum class Kit(val id: String, val title: String, val bpm: Int) {
        TRAP("cypher-trap", "Trap", 140),
        DRILL("cypher-drill", "Drill", 142),
    }

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
                        val subMacros: Map<String, Float>? = null)

    /**
     * A phrase: notes on the kit's grid, one shared space (or dry), and the parts that choke each other inside
     * it (a sub stops the sub before it, as the pads would on the sampler).
     */
    internal class Phrase(val name: String, val space: Space?, val notes: List<Note>, val choke: Set<Int> = emptySet())

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
                    "processing" to (spaceOf(kit, role)?.mode?.name?.lowercase() ?: "dry"),
                ),
            )
        }
    }

    /** The space [role] is printed in, or null for a dry pad; its send measured once on the pad's own first take. */
    fun spaceOf(kit: Kit, role: Int): Space? {
        val authored = (if (role < HITS) parts(kit)[role].space else phrases(kit)[role - HITS].space) ?: return null
        return spaces.getOrPut(kit to role) { Gatecrash.calibrated(dryRaw(kit, role, 1f, 0), authored, seedOf(kit, role)) }
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

    /** Each stage of one pad's space, for the tests that measure it. Null for a dry pad. */
    internal fun stages(kit: Kit, role: Int, velocity: Float, take: Int): Gatecrash.Stages? {
        val space = spaceOf(kit, role) ?: return null
        return Gatecrash.process(dryRaw(kit, role, velocity, take), space, touch(velocity), seedOf(kit, role))
    }

    private fun raw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray =
        stages(kit, role, velocity, take)?.out ?: dryRaw(kit, role, velocity, take)

    /** The bar at this kit's tempo plus the tail a long sub needs. */
    fun phraseSeconds(kit: Kit): Float = (4 * 60f / kit.bpm + 1.2f)

    internal fun dryRaw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val parts = parts(kit)
        if (role < HITS) return KitStrikes.struck(parts[role].strike, velocity, take, touch(velocity))
        val phrase = phrases(kit)[role - HITS]
        return phraseRaw(kit, phrase, velocity, take)
    }

    /** [phraseRaw] for a phrase a test authors. */
    internal fun phraseRawForTest(kit: Kit, phrase: Phrase, velocity: Float, take: Int): FloatArray = phraseRaw(kit, phrase, velocity, take)

    /** A dry phrase: every note a fresh strike at its offset start, summed, with the in-phrase chokes applied as the sampler would. */
    private fun phraseRaw(kit: Kit, phrase: Phrase, velocity: Float, take: Int): FloatArray {
        val parts = parts(kit)
        val out = FloatArray((phraseSeconds(kit) * Dsp.RATE).toInt())
        val choked = phrase.notes.withIndex().filter { it.value.part in phrase.choke }.sortedBy { Cypher.startFrame(it.value, kit.bpm) }
        for ((i, note) in phrase.notes.withIndex()) {
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
    }

    internal fun phrases(kit: Kit): List<Phrase> = when (kit) {
        Kit.TRAP -> trapPhrases
        Kit.DRILL -> drillPhrases
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
        // The drill snare bar: the snare on two and the and of three, each with two sixteenth-triplet clap
        // pickups, the kick on one and the and of two's off-beat, hats in eighths. One shared room.
        Phrase("Triplet Snare", drillRoom,
            listOf(Note(0.0, 0, 1f), Note(1.75, 0, .9f), Note(1.0, 1, 1f), Note(2.5, 1, 1f),
                Note(1 - 1.0 / 3, 8, .55f), Note(1 - 1.0 / 6, 8, .70f), Note(2.5 - 1.0 / 3, 8, .55f), Note(2.5 - 1.0 / 6, 8, .70f)) +
                List(8) { Note(it * .5, 2, if (it % 2 == 0) .7f else .45f) }),
        // The sub pattern: the root on one, down a fifth on the and of two, the late slide on three, up an octave before four.
        Phrase("Slide Call", null,
            listOf(Note(0.0, 0, 1f), Note(0.0, 4, 1f), Note(1.5, 5, .95f), Note(2.0, 7, .9f), Note(3.5, 6, .9f)), choke = setOf(4, 5, 6, 7)),
    )

    /** The shortest interval between two hits of [part] in [kit]'s phrases, seconds; null if the part never repeats. */
    fun shortestPeriod(kit: Kit, part: Int): Float? {
        val starts = phrases(kit).flatMap { phrase -> phrase.notes.filter { it.part == part }.map { Cypher.startFrame(it, kit.bpm) }.sorted().zipWithNext { a, b -> b - a } }
        return starts.minOrNull()?.let { it.toFloat() / Dsp.RATE }
    }
}
