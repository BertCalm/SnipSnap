package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Cypher.Note
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * LOOSE CRATE: four authored drum kits after one producer's loose, dusty, sub-heavy beat-tape drums
 * (`docs/LOOSE_CRATE.md`). A rendered kit family in the [CypherKits] shape, not a Patch engine: the hits come
 * from SKIN, THUMP, TERRA, TINES and BREAKER's cymbals as they stand, and each kit prints them through its own
 * converter, tape and record ([Print]).
 *
 * The family's gesture: the kick hums under the bar → the snare lands loose, flammed or bouncing → the hats
 * drag behind → a dark, rumbling bed with the top cut off.
 *
 * - [Kit.HUMMIN] (88): the humming kick, the flam snare, dark hats that drag, heavy rumble. Its kicks, snares
 *   and hat are round 0's candidates exactly as the owner heard them.
 * - [Kit.CLOTH] (82): dry and close; a kick that drops an octave, the deep bouncing snare, tight hats, clean gaps.
 * - [Kit.HAZE] (84, a half-time feel): a washing cymbal, bells and metal, a lazy flam, the top cut hardest and
 *   the tape wobbling most.
 * - [Kit.RITUAL] (86): sparse and dub-leaning; a short sub thud, the high slap, hand drums, a sub that drops.
 *
 * Every pad has three velocity zones of four takes. A zone is its own strike (SKIN's and TERRA's drums and
 * THUMP's SUB strike at a velocity; the hats have their own contact law; the rest are [KitStrikes.touched]),
 * and a take is a fresh strike. Pads 1 to 14 are hits; pad 15 is a roll that speeds up into a landing; pad 16
 * is a four-bar groove, bar-exact so it loops.
 *
 * Chokes: hats cut hats (1), the roll and the groove cut each other (2), the hum kick and the subs cut each
 * other (3).
 *
 * Every setting outside round 0's candidates is a shape value, authored unheard.
 */
object LooseCrateKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 2

    enum class Kit(val id: String, val title: String, val bpm: Int) {
        HUMMIN("loose-crate-hummin", "Hummin", 88),
        CLOTH("loose-crate-cloth", "Cloth", 82),
        HAZE("loose-crate-haze", "Haze", 84),
        RITUAL("loose-crate-ritual", "Ritual", 86),
    }

    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = 4
    const val HITS = 14
    const val PADS = 16
    const val ROLL = 14
    const val GROOVE = 15
    const val GROOVE_BARS = 4

    const val HAT_GROUP = 1
    const val PHRASE_GROUP = 2
    const val SUB_GROUP = 3

    /** Where every zone's first take peaks, as CYPHER's do. */
    const val PAD_PEAK = .92f

    /** The level every pad is fed to its print at: CRUNCH and TAPE match their input's peak and then clamp, so a sum over 1 would be squared off. */
    const val HEADROOM = .9f

    /**
     * A kit's print, in the order the records were made: sampled into a converter (CRUNCH), bounced to tape
     * (TAPE), the record's surface under it (VINYL). [hitVinyl] is light because pads overlap and their beds
     * add; [grooveVinyl] carries the bed on the phrases. Null skips VINYL.
     */
    class Print(
        val name: String,
        val crunch: Map<String, Float>,
        val tape: Map<String, Float>,
        val hitVinyl: Map<String, Float>?,
        val grooveVinyl: Map<String, Float>?,
    ) {
        /**
         * Every pad enters the print at [HEADROOM]. Round 1 first fed a softer zone in softer, so the converter
         * would hear it softer: but CRUNCH's grain is a fixed size, so it was relatively louder on a quiet hit,
         * and the soft zone of every low or woody drum through a 12-bit print measured brighter than the hard
         * one (the thud kick's onset centroid 264, 244, 244 Hz soft to hard; CLOTH's near-clean converter
         * showed none). Fed at one level, the softness is the strike's own (duller, `LooseCrateKitsTest`). A
         * hard hit already entered at [HEADROOM], so round 0's heard sounds do not move.
         */
        fun apply(x: FloatArray, phrase: Boolean): FloatArray {
            val peak = x.maxOfOrNull { abs(it) } ?: 0f
            val k = if (peak > 1e-9f) HEADROOM / peak else 1f
            val fed = Snip(FloatArray(x.size) { x[it] * k }, 1, Dsp.RATE)
            val taped = Tape.process(Crunch.process(fed, crunch), tape)
            val vinyl = if (phrase) grooveVinyl else hitVinyl
            return (if (vinyl == null) taped else Vinyl.process(taped, vinyl)).samples
        }
    }

    /** Round 0's print, as heard: 12-bit at about 32 kHz, worn tape, a record's rumble. */
    val CRATE = Print("crate",
        mapOf("BITS" to .45f, "RATE" to .22f, "TONE" to .95f, "GRIT" to .05f),
        mapOf("WOBBLE" to .08f, "DRIVE" to .15f, "AGE" to .60f),
        mapOf("CRACKLE" to .20f, "RUMBLE" to .30f, "HISS" to .25f),
        mapOf("CRACKLE" to .20f, "RUMBLE" to .70f, "HISS" to .25f))

    /** Close and dry: near-full word size and rate, a light tape, no record under the hits (the reference's gaps are clean). */
    val CLOSE = Print("close",
        mapOf("BITS" to .20f, "RATE" to 0f, "TONE" to 1f, "GRIT" to 0f),
        mapOf("WOBBLE" to .03f, "DRIVE" to .10f, "AGE" to .35f),
        null,
        // The cloth reference's quiet frames sit 45 dB under its loudest with its top 62 dB down: the record's low
        // rumble, not a room or hiss (a closer or farther mic moved round 2's CLOTH groove by under 1 dB from 57).
        mapOf("CRACKLE" to .10f, "RUMBLE" to .80f, "HISS" to .10f))

    /** The haze: a coarser converter, the tape wobbling and darker, more surface. */
    val HAZY = Print("haze",
        mapOf("BITS" to .55f, "RATE" to .25f, "TONE" to .90f, "GRIT" to .10f),
        mapOf("WOBBLE" to .30f, "DRIVE" to .20f, "AGE" to .65f),
        mapOf("CRACKLE" to .30f, "RUMBLE" to .30f, "HISS" to .35f),
        mapOf("CRACKLE" to .35f, "RUMBLE" to .50f, "HISS" to .35f))

    /** Dub: the top darkest of the four, the rumble deep. */
    val DUB = Print("dub",
        mapOf("BITS" to .45f, "RATE" to .30f, "TONE" to .85f, "GRIT" to .05f),
        mapOf("WOBBLE" to .10f, "DRIVE" to .25f, "AGE" to .70f),
        mapOf("CRACKLE" to .15f, "RUMBLE" to .40f, "HISS" to .20f),
        mapOf("CRACKLE" to .15f, "RUMBLE" to .80f, "HISS" to .20f))

    fun printOf(kit: Kit): Print = when (kit) {
        Kit.HUMMIN -> CRATE
        Kit.CLOTH -> CLOSE
        Kit.HAZE -> HAZY
        Kit.RITUAL -> DUB
    }

    /** One playable hit: its name, class, choke group and how to strike it dry. */
    internal class Part(val name: String, val cls: DrumClass, val mute: Int = 0, val strike: Strike)

    /** A phrase pad: notes on the kit's grid. [loop] folds the tail back onto the start and keeps the bars exact. */
    internal class Phrase(val name: String, val notes: List<Note>, val loop: Boolean)

    /** A dry hit's level against a full one, as CYPHER's: the print hears a softer hit softer. */
    fun touch(velocity: Float): Float = CypherKits.touch(velocity)

    fun roles(kit: Kit): List<String> = parts(kit).map { it.name } + phrases(kit).map { it.name }

    fun muteGroup(kit: Kit, role: Int): Int = if (role < HITS) parts(kit)[role].mute else PHRASE_GROUP

    fun classOf(kit: Kit, role: Int): DrumClass = if (role < HITS) parts(kit)[role].cls else DrumClass.LOOP

    /** The kit's sixteen pads, each with its whole grid. [renderer] is [render]; a test passes its cache of the same renders. */
    fun build(kit: Kit, renderer: (Kit, Int, Float, Int) -> Snip = ::render): List<com.snipsnap.kit.ArrangedPad> {
        val names = roles(kit)
        return List(PADS) { role ->
            val grid = velocities.map { v -> List(TAKES) { t -> renderer(kit, role, v, t) } }
            com.snipsnap.kit.ArrangedPad(
                grid.last().first(), classOf(kit, role), displayName = names[role], velocityTakes = grid,
                muteGroup = muteGroup(kit, role),
                source = mapOf(
                    "engine" to "LOOSE CRATE", "kitVersion" to VERSION.toString(), "phraseVersion" to Cypher.VERSION.toString(),
                    "kit" to kit.name, "role" to role.toString(), "bpm" to kit.bpm.toString(),
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to when (role) { ROLL -> "roll"; GROOVE -> "loop"; else -> "finite" },
                    "processing" to printOf(kit).name,
                ),
            )
        }
    }

    /** The finished pad at one velocity and take. Pure: the same arguments give the same bytes. */
    fun render(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        val gain = zoneGain(kit, role, velocity)
        val x = printed(kit, role, velocity, take)
        return if (role == GROOVE) looped(x, gain) else KitStrikes.finish(x, gain)
    }

    /** The same pad with no print, levelled on its own: the audition's dry comparison. */
    fun dry(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        val x = dryRaw(kit, role, velocity, take)
        val peak = KitStrikes.peakOf(x)
        return if (role == GROOVE) looped(x, if (peak > 1e-9f) PAD_PEAK / peak else 1f)
        else KitStrikes.finish(x, if (peak > 1e-9f) PAD_PEAK / peak else 1f)
    }

    /** The groove's length in frames: [GROOVE_BARS] bars at the kit's tempo, exactly. */
    fun grooveFrames(kit: Kit): Int = Math.round(GROOVE_BARS * 4 * 60.0 / kit.bpm * Dsp.RATE).toInt()

    /** How long the groove's tail rings past its last bar before it is folded onto the first. */
    private const val TAIL_SECONDS = 1.5f

    /** The crossfade that closes the printed loop. */
    private const val SEAM_SECONDS = .02f

    internal fun dryRaw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val parts = parts(kit)
        if (role < HITS) return KitStrikes.struck(parts[role].strike, velocity, take, touch(velocity))
        val phrase = phrases(kit)[role - HITS]
        val room = roomOf(kit)
        if (!phrase.loop) {
            val end = phrase.notes.maxOf { Cypher.startFrame(it, kit.bpm) }
            val raw = Cypher.phraseRaw(phrase.notes, kit.bpm, velocity, take, TAKES, end.toFloat() / Dsp.RATE + TAIL_SECONDS, ::touch) { strikeOf(kit, it) }
            return room.play(raw)
        }
        // The loop: the bars plus every tail (the notes' and the room's), folded back onto the first bars, as the
        // kit rings on when the pad repeats.
        val frames = grooveFrames(kit)
        val raw = Cypher.phraseRaw(played(kit, phrase.notes), kit.bpm, velocity, take, TAKES, frames.toFloat() / Dsp.RATE + LOOP_TAIL_SECONDS, ::touch) { strikeOf(kit, it) }
        val roomed = room.play(raw)
        val out = FloatArray(frames)
        for (i in roomed.indices) out[i % frames] += roomed[i]
        return out
    }

    /** How long a groove rings past its bars before it is folded: the notes' tails plus the room's (at most 1 s). */
    private const val LOOP_TAIL_SECONDS = 2.5f

    // ---------- round 2: one kit in one room ----------

    /**
     * How a kit is played and heard as one kit in one room, round 2 (the owner heard round 1 as "a bit drum machine
     * like" and chose treatment A from round 2a, 2026-11-18). Measured on the references (docs/LOOSE_CRATE.md): a
     * sampled break never goes quiet the way summed dry hits do, its cymbals wash, and its kicks land 5 to 12 dB
     * apart. So the grooves are played with a drummer's kick dynamics ([kickCurve]: a note's weight w becomes
     * w^kickCurve), the snare wires answer every kick ([wireWeight] of the kick's weight, 2 ms after it), the hats sit
     * at [hatLevel] with the open hat ringing on each bar's last eighth when [ringBarEnd], and a [ride] washes on the
     * quarters at its two weights. Then the whole kit, grooves and rolls, goes through [space] (ROOM), [bus]
     * (COMPRESSOR, the mics breathing) and a [shelfDb] high shelf at [SHELF_HZ] (the references are mixed dark).
     * Single hits are not roomed: pads sequenced on a sampler would stack their rooms. Shape values.
     */
    class Room(
        val space: Map<String, Float>?,
        val bus: Map<String, Float>?,
        val shelfDb: Float,
        val kickCurve: Float,
        val wireWeight: Float,
        val hatLevel: Float,
        val ringBarEnd: Boolean,
        val ride: Int?,
        val rideWeights: Pair<Float, Float> = .10f to .06f,
    ) {
        /** The dry kit through the room, the compressor and the shelf, fed at the print's level ([HEADROOM]). */
        fun play(dry: FloatArray): FloatArray {
            if (space == null && bus == null && shelfDb == 0f) return dry
            val peak = dry.maxOfOrNull { abs(it) } ?: 0f
            var x = Snip(FloatArray(dry.size) { dry[it] * HEADROOM / max(peak, 1e-9f) }, 1, Dsp.RATE)
            if (space != null) x = com.snipsnap.synth.Room.process(x, space)
            if (bus != null) x = Compressor.process(x, bus)
            if (shelfDb == 0f) return x.samples
            val shelf = Dsp.Biquad().apply { highShelf(SHELF_HZ, shelfDb) }
            return FloatArray(x.samples.size) { shelf.process(x.samples[it]) }
        }
    }

    const val SHELF_HZ = 1_200f

    /** The part number the snare wires play on in a groove: past the fourteen hits, so it never names a pad. */
    const val WIRES = 100

    /** Only a snare's wires: SKIN's snare struck softly with its body filtered away (two poles high-passed at 1.8 kHz). */
    internal val wires: Strike = { v, t, _ ->
        val x = Skin.render(SkinVoice.SNARE, mapOf("TUNE" to 1f, "SNAP" to .30f, "DECAY" to .60f, "RATTLE" to .90f, "SIZZLE" to .80f), v, 100 + t).samples
        val a = Dsp.FixedOnePole(1_800f)
        val b = Dsp.FixedOnePole(1_800f)
        FloatArray(x.size) { val h = x[it] - a.lp(x[it]); h - b.lp(h) }
    }

    internal fun strikeOf(kit: Kit, part: Int): Strike = if (part == WIRES) wires else parts(kit)[part].strike

    /**
     * Each kit's room, against its references (quietest tenth of 10 ms frames under the loudest; the cymbal band's
     * quietest; loud against soft kicks): HUMMIN's 18 to 23 dB, 26 to 29 dB and 9 to 11 dB are a room that never
     * goes quiet; CLOTH's reference keeps clean gaps (45 dB, 62 dB, 9 dB): dynamics and wires, almost no room;
     * HAZE's three (22 to 31, 19 to 38, 8 to 12 dB) are the washiest; RITUAL's three (35 to 44, 41 to 59, 5 to 9 dB)
     * a moderate room with no ride. HUMMIN is round 2a's treatment A exactly as the owner heard it.
     */
    fun roomOf(kit: Kit): Room = when (kit) {
        Kit.HUMMIN -> Room(mapOf("SIZE" to .22f, "WALLS" to .55f, "DISTANCE" to .60f), mapOf("AMOUNT" to .45f, "ATTACK" to .80f, "RECOVER" to .45f),
            -5f, kickCurve = 3f, wireWeight = .10f, hatLevel = .7f, ringBarEnd = true, ride = 10)
        Kit.CLOTH -> Room(mapOf("SIZE" to .10f, "WALLS" to .35f, "DISTANCE" to .40f), mapOf("AMOUNT" to .30f, "ATTACK" to .85f, "RECOVER" to .55f),
            -2f, kickCurve = 3f, wireWeight = .08f, hatLevel = .85f, ringBarEnd = false, ride = null)
        // HAZE's crash already washes over bars one and three and its hats run in sixteenths: a ride on top measured
        // the quietest frames 12 dB under the loudest, against the references' 22 to 31.
        Kit.HAZE -> Room(mapOf("SIZE" to .30f, "WALLS" to .55f, "DISTANCE" to .60f), mapOf("AMOUNT" to .30f, "ATTACK" to .80f, "RECOVER" to .40f),
            -5f, kickCurve = 3f, wireWeight = .10f, hatLevel = .6f, ringBarEnd = true, ride = null)
        Kit.RITUAL -> Room(mapOf("SIZE" to .28f, "WALLS" to .50f, "DISTANCE" to .55f), mapOf("AMOUNT" to .40f, "ATTACK" to .80f, "RECOVER" to .50f),
            -4f, kickCurve = 2.5f, wireWeight = .08f, hatLevel = .8f, ringBarEnd = false, ride = null)
    }

    /**
     * A groove's notes as a drummer plays them in the room: kick weights curved, the wires under every kick, the hats
     * at the room's level (the open hat ringing on each bar's last eighth where the room says), the ride on the quarters.
     */
    internal fun played(kit: Kit, notes: List<Note>): List<Note> {
        val room = roomOf(kit)
        val parts = parts(kit)
        val kicks = parts.indices.filter { parts[it].cls == DrumClass.KICK }.toSet()
        val hat = parts.indexOfFirst { it.cls == DrumClass.HAT_CLOSED }
        val openHat = parts.indexOfFirst { it.cls == DrumClass.HAT_OPEN }
        return buildList {
            for (n in notes) {
                when {
                    n.part in kicks -> {
                        val w = n.weight.pow(room.kickCurve)
                        add(Note(n.beat, n.part, w, n.offsetMs, n.tuneSemis))
                        add(Note(n.beat, WIRES, (w * room.wireWeight).coerceAtLeast(.05f), n.offsetMs + 2f))
                    }
                    n.part == hat && room.ringBarEnd && abs(n.beat % 4.0 - 3.5) < 1e-6 ->
                        add(Note(n.beat, openHat, n.weight * .8f * room.hatLevel, n.offsetMs, n.tuneSemis))
                    n.part == hat || n.part == openHat -> add(Note(n.beat, n.part, n.weight * room.hatLevel, n.offsetMs, n.tuneSemis))
                    else -> add(n)
                }
            }
            val ride = room.ride
            if (ride != null) for (q in 0 until GROOVE_BARS * 4) add(Note(q.toDouble(), ride, if (q % 2 == 0) room.rideWeights.first else room.rideWeights.second, offsetMs = 12f))
        }
    }

    /**
     * The pad through its kit's print. The loop is printed twice end to end and the second pass kept, with its
     * last [SEAM_SECONDS] crossfaded into the first pass's: the first pass runs straight on into the second
     * pass's first frame, so the printed loop closes on itself (round 0 printed one pass and stepped 0.005 at
     * the wrap). Tape wobble and the record's noise are not periodic, which is why a seam needs closing at all.
     */
    private fun printed(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val x = dryRaw(kit, role, velocity, take)
        val print = printOf(kit)
        if (role != GROOVE) return print.apply(x, phrase = role >= HITS)
        val n = x.size
        val p = print.apply(FloatArray(2 * n) { x[it % n] }, phrase = true)
        val fade = (SEAM_SECONDS * Dsp.RATE).toInt()
        return FloatArray(n) { j ->
            if (j < n - fade) p[n + j]
            else { val w = (j - (n - fade) + 1).toFloat() / fade; p[n + j] * (1f - w) + p[j] * w }
        }
    }

    /** A loop's finish: the mean out and the zone's gain, nothing trimmed or faded, so it wraps. */
    private fun looped(x: FloatArray, gain: Float): Snip {
        val mean = x.average().toFloat()
        val out = FloatArray(x.size) { ((x[it] - mean) * gain).coerceIn(-.98f, .98f) }
        return Snip(out, 1, Dsp.RATE)
    }

    private val gains = ConcurrentHashMap<Triple<Kit, Int, Float>, Float>()

    /** One gain per velocity zone, from its first take, shared by its four: every zone peaks at [PAD_PEAK]. */
    private fun zoneGain(kit: Kit, role: Int, velocity: Float): Float = gains.getOrPut(Triple(kit, role, velocity)) {
        val x = printed(kit, role, velocity, 0)
        val peak = if (role == GROOVE) KitStrikes.peakOf(x.let { y -> val m = y.average().toFloat(); FloatArray(y.size) { y[it] - m } })
        else KitStrikes.finish(x, 1f, ceiling = Float.MAX_VALUE).peak()
        if (peak > 1e-9f) PAD_PEAK / peak else 1f
    }

    // ---------- the strikes ----------

    private fun skinSnare(vararg m: Pair<String, Float>): (Float, Int) -> FloatArray = { v, take ->
        Skin.render(SkinVoice.SNARE, mapOf(*m), v, take).samples
    }

    /** A flam: a grace stroke, the main stroke [mainMs] later, two more after it (round 0: 0, 7, 23, 40 ms). */
    private fun flam(mainMs: Float, vararg m: Pair<String, Float>): Strike =
        KitStrikes.multi(listOf(0f, mainMs, mainMs + 16f, mainMs + 33f), listOf(.50f, 1f, .60f, .70f), skinSnare(*m))

    /** A snare that bounces: six strikes 9.5 to 13 ms apart, each about 2.5 dB softer (round 0). */
    private fun bounce(vararg m: Pair<String, Float>): Strike =
        KitStrikes.multi(listOf(0f, 9.5f, 22.5f, 35f, 45f, 56f), listOf(.90f, 1f, .69f, .52f, .40f, .30f), skinSnare(*m))

    /**
     * A SKIN hat with its own contact law: two poles falling to 3 kHz on a soft hit and a shorter ring, so a
     * soft hat is a darker sound (round 0 measured the shared one-pole contact as volume only). Every take
     * re-seeds the hat's noise.
     */
    private fun hat(open: Boolean, tune: Float, decay: Float, decaySoft: Float, tone: Float = .20f): Strike = { v, t, _ ->
        val m = KitStrikes.varied(mapOf("TUNE" to tune, "DECAY" to decaySoft + (decay - decaySoft) * v, "TONE" to tone, "PUNCH" to .3f + .3f * v),
            listOf("TUNE", "DECAY"), t)
        val x = Skin.render(if (open) SkinVoice.HAT_OPEN else SkinVoice.HAT_CLOSED, m, 1f, t, reseedUnstruck = true).samples
        val hz = 3_000f + 13_000f * v * v
        val a = Dsp.FixedOnePole(hz)
        val b = Dsp.FixedOnePole(hz)
        FloatArray(x.size) { b.lp(a.lp(x[it])) }
    }

    /**
     * A TERRA drum struck at the velocity, every take a fresh strike of its own (TERRA's [Restrike] seeds) that
     * also lands a little harder or softer and rings a little longer or shorter: a soft doum's re-seeded takes
     * alone measured 0.0015 apart, under the family's 0.005.
     */
    private fun terra(voice: TerraVoice, vararg m: Pair<String, Float>): Strike = { v, t, _ ->
        val base = mapOf(*m)
        Terra.render(voice, if (t == 0) base else KitStrikes.varied(base, listOf("FORCE", "DECAY"), t, KitStrikes.SKIN_TAKE_STEP), null, v, t).samples
    }

    private fun sub(vararg m: Pair<String, Float>) = KitStrikes.sub(*m)

    private fun skinTouched(voice: SkinVoice, vararg m: Pair<String, Float>, vary: List<String> = listOf("TUNE", "DECAY"), soft: (Float) -> Map<String, Float> = { emptyMap() }): Strike =
        KitStrikes.skinTouched(voice, mapOf(*m), vary, soft = soft)

    // Round 0's candidates, exactly as heard (LooseCrateRound0Generator renders them through CRATE). What each
    // was aimed at, and what moved while aiming, is in docs/LOOSE_CRATE.md:
    // - SKIN's kick is 45 to 75 Hz over TUNE (54 Hz is .357, 50 Hz .206). THUMP's SUB is E1 to E2 (54 Hz is .39);
    //   the print lengthens a low tail by about half (550 → 773 ms to -20 dB at DECAY 1), so DECAY .85.
    // - SKIN's snare is 170 to 260 Hz over TUNE: the flam reference's 262 Hz is the top, the bounce's 196 Hz is .34.
    // - No SKIN snare reaches the slap reference's 0.5 to 2 kHz band (13 dB short); TERRA's hand slap does
    //   (its head is 160 to 400 Hz over TUNE, .99 is 396 Hz). Its BOWL is off: at .5 the bowl rang for 0.9 s.
    internal val humKnock = KitStrikes.skinStruck(SkinVoice.KICK, "TUNE" to .36f, "DECAY" to .90f, "TONE" to .30f, "PUNCH" to .40f, "CLICK" to .35f, "DROP" to .15f)
    internal val humSub = sub("TUNE" to .39f, "DECAY" to .85f, "SLIDE" to .5f, "DRIVE" to .45f, "CLICK" to .30f)
    internal val humKick = KitStrikes.layered(humKnock, .7f, humSub, 1f)
    internal val thudKick = KitStrikes.skinStruck(SkinVoice.KICK, "TUNE" to .21f, "DECAY" to .25f, "TONE" to .35f, "PUNCH" to .50f, "CLICK" to .60f, "DROP" to .55f)
    internal val flamSnare = flam(7f, "TUNE" to 1f, "SNAP" to .80f, "DECAY" to .10f, "PUNCH" to .60f, "RATTLE" to .65f, "SIZZLE" to .55f)
    internal val bounceSnare = bounce("TUNE" to .34f, "SNAP" to .80f, "DECAY" to .90f, "PUNCH" to .50f, "RATTLE" to .85f, "SIZZLE" to .80f)
    internal val slapSnare = KitStrikes.layered(
        KitStrikes.terra(TerraVoice.SLAP, mapOf("TUNE" to .99f, "DECAY" to .10f, "FORCE" to .95f, "POS" to .875f, "BOWL" to 0f, "SMACK" to .85f)), 1f,
        KitStrikes.skinStruck(SkinVoice.SNARE, "TUNE" to .62f, "SNAP" to .90f, "DECAY" to .12f, "PUNCH" to .80f, "RATTLE" to .50f, "SIZZLE" to .60f), .55f)
    internal val darkHat = hat(open = false, tune = .15f, decay = .22f, decaySoft = .12f)

    // ---------- the kits ----------

    internal fun parts(kit: Kit): List<Part> = when (kit) {
        Kit.HUMMIN -> hummin
        Kit.CLOTH -> cloth
        Kit.HAZE -> haze
        Kit.RITUAL -> ritual
    }

    internal fun phrases(kit: Kit): List<Phrase> = when (kit) {
        Kit.HUMMIN -> humminPhrases
        Kit.CLOTH -> clothPhrases
        Kit.HAZE -> hazePhrases
        Kit.RITUAL -> ritualPhrases
    }

    // HUMMIN (88): built around the humming kick. Pads 1, 2, 3, 5, 6, 7 and 14 are round 0's candidates.
    private val hummin: List<Part> = listOf(
        Part("Hum Kick", DrumClass.KICK, SUB_GROUP, humKick),
        Part("Flam Snare", DrumClass.SNARE, strike = flamSnare),
        Part("Dark Hat", DrumClass.HAT_CLOSED, HAT_GROUP, darkHat),
        Part("Dark Open Hat", DrumClass.HAT_OPEN, HAT_GROUP, hat(open = true, tune = .15f, decay = .45f, decaySoft = .30f)),
        Part("Hum Sub", DrumClass.KICK, SUB_GROUP, humSub),
        Part("Bounce Snare", DrumClass.SNARE, strike = bounceSnare),
        Part("Thud Kick", DrumClass.KICK, strike = thudKick),
        Part("Ghost Snare", DrumClass.SNARE, strike = KitStrikes.softer(bounceSnare, .40f)),
        Part("Rim", DrumClass.PERC, strike = skinTouched(SkinVoice.STICK, "TUNE" to .45f, "DECAY" to .30f, "BODY" to .40f, "CLICK" to .40f) { v -> mapOf("CLICK" to .2f + .3f * v) }),
        Part("Shaker", DrumClass.PERC, strike = skinTouched(SkinVoice.SHAKER, "TONE" to .30f, "DECAY" to .30f, vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("PUNCH" to .3f + .3f * v) }),
        Part("Dark Ride", DrumClass.PERC, strike = skinTouched(SkinVoice.RIDE, "TUNE" to .25f, "DECAY" to .55f, "TONE" to .25f) { v -> mapOf("PUNCH" to .3f + .4f * v, "SIZZLE" to .15f * v) }),
        Part("Low Tom", DrumClass.TOM, strike = KitStrikes.skinStruck(SkinVoice.TOM, "TUNE" to .20f, "DECAY" to .55f, "TONE" to .30f, "DROP" to .20f)),
        Part("Knock", DrumClass.PERC, strike = terra(TerraVoice.BOX, "TUNE" to .30f, "DECAY" to .45f, "FORCE" to .70f)),
        Part("Slap Snare", DrumClass.SNARE, strike = slapSnare),
    )

    // CLOTH (82): dry and close. The kick drops an octave from its attack; the bouncing snare leads.
    private val clothKick = KitStrikes.skinStruck(SkinVoice.KICK, "TUNE" to .46f, "DECAY" to .70f, "TONE" to .40f, "PUNCH" to .55f, "CLICK" to .55f, "DROP" to .90f)
    private val cloth: List<Part> = listOf(
        Part("Drop Kick", DrumClass.KICK, strike = clothKick),
        Part("Bounce Snare", DrumClass.SNARE, strike = bounceSnare),
        Part("Tight Hat", DrumClass.HAT_CLOSED, HAT_GROUP, hat(open = false, tune = .45f, decay = .12f, decaySoft = .05f, tone = .35f)),
        Part("Open Hat", DrumClass.HAT_OPEN, HAT_GROUP, hat(open = true, tune = .45f, decay = .35f, decaySoft = .22f, tone = .35f)),
        Part("Short Kick", DrumClass.KICK, strike = KitStrikes.skinStruck(SkinVoice.KICK, "TUNE" to .40f, "DECAY" to .30f, "TONE" to .40f, "PUNCH" to .60f, "CLICK" to .50f, "DROP" to .60f)),
        Part("Tight Flam", DrumClass.SNARE, strike = flam(5f, "TUNE" to .75f, "SNAP" to .70f, "DECAY" to .15f, "PUNCH" to .60f, "RATTLE" to .55f, "SIZZLE" to .45f)),
        Part("Cross Stick", DrumClass.PERC, strike = skinTouched(SkinVoice.STICK, "TUNE" to .60f, "DECAY" to .25f, "BODY" to .20f, "CLICK" to .55f) { v -> mapOf("CLICK" to .3f + .4f * v) }),
        Part("Ghost Snare", DrumClass.SNARE, strike = KitStrikes.softer(bounceSnare, .35f)),
        Part("Low Tom", DrumClass.TOM, strike = KitStrikes.skinStruck(SkinVoice.TOM, "TUNE" to .25f, "DECAY" to .40f, "TONE" to .40f)),
        Part("High Tom", DrumClass.TOM, strike = KitStrikes.skinStruck(SkinVoice.TOM, "TUNE" to .60f, "DECAY" to .35f, "TONE" to .45f)),
        Part("Shaker", DrumClass.PERC, strike = skinTouched(SkinVoice.SHAKER, "TONE" to .45f, "DECAY" to .25f, vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("PUNCH" to .4f + .3f * v) }),
        Part("Ride", DrumClass.PERC, strike = skinTouched(SkinVoice.RIDE, "TUNE" to .40f, "DECAY" to .45f, "TONE" to .40f) { v -> mapOf("PUNCH" to .3f + .4f * v, "SIZZLE" to .25f * v) }),
        Part("Swish", DrumClass.PERC, strike = skinTouched(SkinVoice.SHAKER, "TONE" to .35f, "DECAY" to .45f, "SWELL" to .60f, vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("PUNCH" to .2f + .3f * v) }),
        Part("Slap", DrumClass.SNARE, strike = slapSnare),
    )

    // HAZE (84, a half-time feel): a lazy flam, a washing cymbal, bells and metal. The kick is ONSET's, leaning
    // electronic: a SKIN kick here measured 0.18 from RITUAL's thud, the same drum.
    private val hazeKick = KitStrikes.onsetKick("TUNE" to .30f, "DECAY" to .40f, "BLEND" to .35f, "SNAP" to .40f, "BODY" to .70f, "EDGE" to .35f)
    private val lazyFlam = flam(14f, "TUNE" to .60f, "SNAP" to .70f, "DECAY" to .45f, "PUNCH" to .50f, "RATTLE" to .70f, "SIZZLE" to .60f)
    private val haze: List<Part> = listOf(
        Part("Haze Kick", DrumClass.KICK, strike = hazeKick),
        Part("Lazy Flam", DrumClass.SNARE, strike = lazyFlam),
        Part("Haze Hat", DrumClass.HAT_CLOSED, HAT_GROUP, hat(open = false, tune = .30f, decay = .25f, decaySoft = .14f, tone = .30f)),
        Part("Haze Open Hat", DrumClass.HAT_OPEN, HAT_GROUP, hat(open = true, tune = .30f, decay = .60f, decaySoft = .40f, tone = .30f)),
        // BREAKER's fitted crash, 1.4 s with a long fade so it stays a hit, as CYPHER DUST's.
        Part("Wash", DrumClass.PERC, strike = KitStrikes.cymbal(BreakerCymbal.Kind.CRASH, 1.4f, .5f, pitch = .85)),
        Part("Haze Ride", DrumClass.PERC, strike = KitStrikes.cymbal(BreakerCymbal.Kind.RIDE, 1.2f, .4f, pitch = .9)),
        Part("Bell", DrumClass.PERC, strike = KitStrikes.tines(TinesVoice.BELL, mapOf("TUNE" to .45f, "DECAY" to .45f, "RATIO" to .6f))),
        Part("Metal", DrumClass.PERC, strike = KitStrikes.tines(TinesVoice.CHIME, mapOf("TUNE" to .55f, "DECAY" to .35f))),
        Part("Low Sub", DrumClass.KICK, SUB_GROUP, sub("TUNE" to .20f, "DECAY" to .80f, "SLIDE" to .5f, "DRIVE" to .40f, "CLICK" to .40f)),
        Part("High Tom", DrumClass.TOM, strike = KitStrikes.skinStruck(SkinVoice.TOM, "TUNE" to .70f, "DECAY" to .45f, "TONE" to .35f, "DROP" to .30f)),
        Part("Low Tom", DrumClass.TOM, strike = KitStrikes.skinStruck(SkinVoice.TOM, "TUNE" to .30f, "DECAY" to .55f, "TONE" to .30f, "DROP" to .30f)),
        Part("Shaker", DrumClass.PERC, strike = skinTouched(SkinVoice.SHAKER, "TONE" to .35f, "DECAY" to .35f, vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("PUNCH" to .3f + .3f * v) }),
        Part("Rim", DrumClass.PERC, strike = skinTouched(SkinVoice.STICK, "TUNE" to .50f, "DECAY" to .35f, "BODY" to .30f, "CLICK" to .45f) { v -> mapOf("CLICK" to .2f + .4f * v) }),
        Part("Ghost Flam", DrumClass.SNARE, strike = KitStrikes.softer(lazyFlam, .40f)),
    )

    // RITUAL (86): sparse and dub-leaning. The short thud and the high slap lead; hand drums answer.
    private val ritual: List<Part> = listOf(
        Part("Thud Kick", DrumClass.KICK, strike = thudKick),
        Part("Slap Snare", DrumClass.SNARE, strike = slapSnare),
        Part("Ritual Hat", DrumClass.HAT_CLOSED, HAT_GROUP, hat(open = false, tune = .10f, decay = .18f, decaySoft = .10f, tone = .15f)),
        Part("Ritual Open Hat", DrumClass.HAT_OPEN, HAT_GROUP, hat(open = true, tune = .10f, decay = .50f, decaySoft = .30f, tone = .15f)),
        // A sub that drops a fourth on the off-beat.
        Part("Sub Drop", DrumClass.KICK, SUB_GROUP, sub("TUNE" to .30f, "DECAY" to .75f, "SLIDE" to .5f - 5f / 24f, "WHEN" to .40f, "DRIVE" to .50f, "CLICK" to .40f)),
        Part("Rimshot", DrumClass.PERC, strike = skinTouched(SkinVoice.STICK, "TUNE" to .65f, "DECAY" to .30f, "BODY" to .55f, "CLICK" to .60f) { v -> mapOf("CLICK" to .3f + .4f * v) }),
        Part("Low Tom", DrumClass.TOM, strike = KitStrikes.skinStruck(SkinVoice.TOM, "TUNE" to .10f, "DECAY" to .70f, "TONE" to .25f, "DROP" to .35f)),
        Part("Doum", DrumClass.PERC, strike = terra(TerraVoice.DOUM, "TUNE" to .35f, "DECAY" to .45f, "FORCE" to .45f, "AIR" to .60f)),
        Part("Hand Slap", DrumClass.PERC, strike = terra(TerraVoice.SLAP, "TUNE" to .55f, "DECAY" to .35f, "FORCE" to .90f, "POS" to .85f, "BOWL" to .30f, "SMACK" to .60f)),
        Part("Shaker", DrumClass.PERC, strike = skinTouched(SkinVoice.SHAKER, "TONE" to .25f, "DECAY" to .35f, vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("PUNCH" to .3f + .3f * v) }),
        Part("Low Bell", DrumClass.PERC, strike = KitStrikes.tines(TinesVoice.BELL, mapOf("TUNE" to .20f, "DECAY" to .40f, "RATIO" to .5f))),
        Part("Knock", DrumClass.PERC, strike = terra(TerraVoice.BOX, "TUNE" to .20f, "DECAY" to .50f, "FORCE" to .65f)),
        Part("Ghost Slap", DrumClass.SNARE, strike = KitStrikes.softer(slapSnare, .40f)),
        Part("High Thud", DrumClass.KICK, strike = KitStrikes.skinStruck(SkinVoice.KICK, "TUNE" to .45f, "DECAY" to .20f, "TONE" to .40f, "PUNCH" to .50f, "CLICK" to .55f, "DROP" to .45f)),
    )

    // ---------- the phrases ----------

    /**
     * A roll that speeds up into a landing: [count] strikes of [part] over [beats], the gaps shrinking (each
     * strike at beats × (i / count)^0.6), the weight rising from [from] to [to], then [land] on the beat after.
     */
    internal fun accelerating(part: Int, beats: Double, count: Int, from: Float, to: Float, land: Int = part): List<Note> =
        List(count) { i ->
            val p = i.toDouble() / count
            Note(beats * p.pow(0.6), part, from + (to - from) * p.toFloat())
        } + Note(beats, land, 1f)

    /** Round 0's HUMMIN groove, the same notes and the same seeded feel (parts mapped onto this kit's pads). */
    internal fun humminNotes(kick: Int, snare: Int, hat: Int, ghost: Int): List<Note> {
        val notes = buildList {
            for (bar in 0 until GROOVE_BARS) {
                val b = bar * 4.0
                val kicks = if (bar % 2 == 0) listOf(0.0 to 1f, 1.75 to .70f, 2.5 to .90f) else listOf(0.0 to 1f, .75 to .55f, 2.5 to .85f, 2.75 to .60f)
                for ((beat, w) in kicks) add(Note(b + beat, kick, w))
                add(Note(b + 1.0, snare, .95f, offsetMs = 14f))
                add(Note(b + 3.0, snare, 1f, offsetMs = 16f))
                if (bar % 2 == 1) add(Note(b + 3.75, ghost, .30f, offsetMs = 30f))
                if (bar == GROOVE_BARS - 1) add(Note(b + 3.5, ghost, .45f, offsetMs = 22f))
                for (eighth in 0 until 8) add(Note(b + eighth * .5, hat, if (eighth % 2 == 0) .75f else .50f, offsetMs = 20f))
                for (s in listOf(.75, 2.75)) add(Note(b + s, hat, .35f, offsetMs = 38f))
            }
        }
        return Cypher.humanised(notes, 10f, "loose-crate-hummin")
    }

    private val humminPhrases = listOf(
        Phrase("Flam Roll", accelerating(1, 2.0, 10, .30f, .85f, land = 0) + Note(2.0, 1, 1f), loop = false),
        // Round 0's groove used pad 6's strike (the bounce snare) for its ghosts.
        Phrase("Hummin Groove", humminNotes(kick = 0, snare = 1, hat = 2, ghost = 5), loop = true),
    )

    // CLOTH: tight and close. The backbeat 8 ms late, the hats 6 ms behind, the sixteenths between 20 ms; a 6 ms spread.
    private val clothGroove: List<Note> = Cypher.humanised(buildList {
        for (bar in 0 until GROOVE_BARS) {
            val b = bar * 4.0
            val kicks = when (bar % 4) {
                0 -> listOf(0.0 to 1f, .75 to .65f, 2.25 to .80f)
                1 -> listOf(0.0 to 1f, 2.0 to .75f, 2.5 to .85f)
                2 -> listOf(0.0 to 1f, .75 to .65f, 2.25 to .80f, 3.5 to .55f)
                else -> listOf(0.0 to 1f, 1.5 to .70f, 2.5 to .85f)
            }
            for ((beat, w) in kicks) add(Note(b + beat, 0, w))
            add(Note(b + 1.0, 1, .95f, offsetMs = 8f))
            add(Note(b + 3.0, 1, 1f, offsetMs = 8f))
            add(Note(b + 1.75, 7, .35f, offsetMs = 14f))
            if (bar % 2 == 1) add(Note(b + 3.25, 7, .30f, offsetMs = 16f))
            for (eighth in 0 until 8) {
                val open = bar == GROOVE_BARS - 1 && eighth == 7
                add(Note(b + eighth * .5, if (open) 3 else 2, if (eighth % 2 == 0) .70f else .45f, offsetMs = 6f))
            }
            for (s in listOf(1.25, 3.25)) add(Note(b + s, 2, .30f, offsetMs = 20f))
        }
        add(Note(15.5, 6, .60f))
    }, 6f, "loose-crate-cloth")

    private val clothPhrases = listOf(
        Phrase("Bounce Roll", accelerating(7, 2.0, 12, .30f, .80f, land = 1), loop = false),
        Phrase("Cloth Groove", clothGroove, loop = true),
    )

    // HAZE: half time. The kick on one, the lazy flam on three, sixteenth hats dragging 15 and 30 ms, the wash
    // opening bars one and three, the bell answering; a 12 ms spread.
    private val hazeGroove: List<Note> = Cypher.humanised(buildList {
        for (bar in 0 until GROOVE_BARS) {
            val b = bar * 4.0
            add(Note(b + 0.0, 0, 1f))
            add(Note(b + 1.75, 0, .60f))
            if (bar % 2 == 1) add(Note(b + 2.75, 0, .55f))
            add(Note(b + 2.0, 1, 1f, offsetMs = 18f))
            add(Note(b + 3.75, 13, .35f, offsetMs = 24f))
            if (bar % 2 == 0) add(Note(b + 0.0, 4, .80f))
            for (s in 0 until 16) {
                val open = s == 14 && bar % 2 == 1
                add(Note(b + s * .25, if (open) 3 else 2, if (s % 4 == 0) .70f else if (s % 2 == 0) .50f else .32f, offsetMs = if (s % 2 == 1) 30f else 15f))
            }
        }
        add(Note(7.5, 6, .60f))
        add(Note(15.5, 7, .55f))
    }, 12f, "loose-crate-haze")

    private val hazePhrases = listOf(
        Phrase("Ghost Roll", accelerating(13, 2.0, 12, .30f, .90f, land = 1), loop = false),
        Phrase("Haze Groove", hazeGroove, loop = true),
    )

    /** RITUAL's kick on three, bar by bar: a drummer's one drop never lands the same twice. */
    private val RITUAL_KICKS = floatArrayOf(1f, .80f, .95f, .75f)

    // RITUAL: one drop. Kick and slap together on three, a rimshot before it, hats on the eighths with the
    // open hat at the end of each bar, the doum and the hand slap on the off-beats, the sub dropping in bar four.
    private val ritualGroove: List<Note> = Cypher.humanised(buildList {
        for (bar in 0 until GROOVE_BARS) {
            val b = bar * 4.0
            // A drummer's one drop: the kick never lands the same twice, and a soft pickup leads into bars two and four
            // (round 2: every kick at 1 measured 2.3 dB of spread, the references' 5 to 9).
            add(Note(b + 2.0, 0, RITUAL_KICKS[bar]))
            if (bar % 2 == 1) add(Note(b + 1.75, 0, .55f, offsetMs = 8f))
            add(Note(b + 2.0, 1, 1f, offsetMs = 10f))
            add(Note(b + 1.5, 5, .55f, offsetMs = 12f))
            for (eighth in 0 until 8) add(Note(b + eighth * .5, if (eighth == 7) 3 else 2, if (eighth % 2 == 0) .60f else .40f, offsetMs = 14f))
            add(Note(b + 0.5, 7, .60f))
            add(Note(b + 3.5, 8, .55f, offsetMs = 10f))
            if (bar % 2 == 1) add(Note(b + 3.0, 12, .35f, offsetMs = 20f))
        }
        add(Note(13.0, 4, .90f))
        add(Note(7.0, 10, .55f))
    }, 14f, "loose-crate-ritual")

    private val ritualPhrases = listOf(
        Phrase("Slap Roll", accelerating(12, 2.0, 10, .30f, .80f, land = 1), loop = false),
        Phrase("Ritual Groove", ritualGroove, loop = true),
    )
}
