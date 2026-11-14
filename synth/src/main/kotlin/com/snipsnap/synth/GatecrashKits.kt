package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.synth.Gatecrash.Mode.BURST
import com.snipsnap.synth.Gatecrash.Mode.STONE
import com.snipsnap.synth.Gatecrash.Space
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The GATECRASH kits: three authored 1980s drum kits, each sixteen pads (fourteen hits, a fill and a build).
 *
 * A rendered kit family, like [TriadKits], not a Patch engine: the hits come from SKIN, THUMP and ONSET as
 * they stand, and each pad is printed through its own [Gatecrash.Space]. The three kits differ in what the drums
 * are, how they are tuned, what each pad is for, and in the mechanism that makes the space, not only in how
 * much of it there is:
 *
 * - [Kit.STONE_ROOM]: SKIN's modal acoustic drums and six concert toms in a hard room, crushed and gated.
 * - [Kit.MACHINE_POP]: THUMP's analog voices and four diving synth toms through a nonlinear reverb burst.
 * - [Kit.ELECTRO_FUNK]: a long dry boom kick, a snappy ONSET snare and a THUMP clap in a small bright room
 *   with a very short gate, cowbell, block and zap.
 *
 * Every pad has three velocity zones of four takes. Where the engine can be struck at a velocity (SKIN's
 * kick, snare and toms, THUMP's kick) a zone is a fresh strike. The other voices have no strike velocity, so
 * their soft zones are built ([touched]): velocity moves the controls a softer stick would move and takes the
 * top off the contact. Either way the softer hit is then sent to its space at a lower level than its own
 * level fell ([Gatecrash.SEND_POWER]), so it is drier as well as duller. A take is another strike where the
 * engine allows one (SKIN's struck voices) and a small move of two or three controls where it does not.
 *
 * The fill and the build are phrases whose dry hits share one room: the gate is keyed again by every hit, so
 * the room stands through the phrase and is cut after the last one. Fourteen separately gated one-shots
 * played in the same rhythm would not do that.
 *
 * Every setting below is a shape value, authored unheard.
 */
object GatecrashKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 4

    enum class Kit(val id: String, val title: String, val bpm: Int) {
        STONE_ROOM("gatecrash-stone", "Stone Room", 96),
        MACHINE_POP("gatecrash-machine", "Machine Pop", 118),
        ELECTRO_FUNK("gatecrash-electro", "Electro Funk", 108),
    }

    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = 4
    const val HITS = 14
    const val PADS = 16

    /** One playable hit: what it is, the space it is printed in, and how to strike it dry. */
    internal class Part(
        val name: String,
        val cls: DrumClass,
        val space: Space?,
        val mute: Int = 0,
        val strike: (velocity: Float, take: Int) -> FloatArray,
    )

    /** One note of a phrase: where (beats), which part, and how hard against the phrase's own velocity. */
    private class Note(val beat: Double, val part: Int, val weight: Float)

    private class Phrase(val name: String, val space: Space, val notes: List<Note>)

    /**
     * A dry hit's level against a full one, as its space hears it: the crush threshold and the room send
     * are set against a full hit. The finished zones are then levelled to one peak ([zoneGain]).
     */
    fun touch(velocity: Float): Float = .35f + .65f * velocity.coerceIn(0f, 1f)

    fun roles(kit: Kit): List<String> = parts(kit).map { it.name } + phrases(kit).map { it.name }

    fun build(kit: Kit): List<ArrangedPad> {
        val parts = parts(kit)
        val names = roles(kit)
        return List(PADS) { role ->
            val grid = velocities.map { v -> List(TAKES) { t -> render(kit, role, v, t) } }
            val part = parts.getOrNull(role)
            ArrangedPad(
                grid.last().first(), part?.cls ?: DrumClass.LOOP, displayName = names[role], velocityTakes = grid,
                // Hats choke each other; the two phrases choke each other so a fill can cut a build.
                muteGroup = part?.mute ?: 2,
                source = mapOf(
                    "engine" to "GATECRASH", "kitVersion" to VERSION.toString(),
                    "stageVersion" to Gatecrash.VERSION.toString(), "kit" to kit.name,
                    "role" to role.toString(), "bpm" to kit.bpm.toString(),
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to "finite",
                    "processing" to spaceOf(kit, role)?.mode?.name?.lowercase().orEmpty().ifEmpty { "dry" },
                ),
            )
        }
    }

    /** The space [role] is printed in, or null for a dry pad. */
    fun spaceOf(kit: Kit, role: Int): Space? {
        val authored = (if (role < HITS) parts(kit)[role].space else phrases(kit)[role - HITS].space) ?: return null
        // The send that gives this pad its authored room energy depends on the drum, so it is measured once
        // on the pad's own full-strength first take and kept.
        return spaces.getOrPut(kit to role) { Gatecrash.calibrated(dryRaw(kit, role, 1f, 0), authored, seedOf(kit, role)) }
    }

    private val spaces = ConcurrentHashMap<Pair<Kit, Int>, Space>()

    private fun seedOf(kit: Kit, role: Int) = Dsp.seedFor(kit.name, role)

    /** The finished pad at one velocity and take. Pure: the same arguments give the same bytes. */
    fun render(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        return finish(raw(kit, role, velocity, take), zoneGain(kit, role, velocity))
    }

    /**
     * The hard first take with its room moved by [roomOffsetDb], levelled on its own. Not part of any kit:
     * the audition page uses it to let the owner hear less and more room around the authored amount, or the
     * same room with a different close time ([closeMs]) or a darker or brighter return ([toneScale]).
     */
    fun roomTrial(kit: Kit, role: Int, roomOffsetDb: Float, closeMs: Float? = null, toneScale: Float = 1f): Snip {
        require(role in 0 until PADS && roomOffsetDb.isFinite() && roomOffsetDb in -24f..12f)
        require(toneScale.isFinite() && toneScale in .25f..4f)
        val space = requireNotNull(spaceOf(kit, role)) { "a dry pad has no room to move" }
        val dry = dryRaw(kit, role, 1f, 0)
        // A different tone passes a different share of the room, so the send is measured again: the trial
        // changes the room's colour at the same room energy, not its amount by the back door.
        val retoned = Gatecrash.calibrated(dry, space.copy(closeMs = closeMs ?: space.closeMs,
            toneHz = (space.toneHz * toneScale).coerceIn(300f, 20_000f)), seedOf(kit, role))
        val moved = retoned.copy(room = retoned.room * Math.pow(10.0, roomOffsetDb / 20.0).toFloat())
        val x = Gatecrash.process(dry, moved, 1f, seedOf(kit, role)).out
        val peak = finish(x.copyOf(), 1f, ceiling = Float.MAX_VALUE).peak()
        return finish(x, if (peak > 1e-9f) PAD_PEAK / peak else 1f)
    }

    /** The same strike with no space, levelled by the same pad gain: the audition's dry comparison. */
    fun dry(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        return finish(dryRaw(kit, role, velocity, take), zoneGain(kit, role, velocity))
    }

    /** Each stage of one pad's space, for the tests that measure the gesture. Null for a dry pad. */
    internal fun stages(kit: Kit, role: Int, velocity: Float, take: Int): Gatecrash.Stages? {
        val space = spaceOf(kit, role) ?: return null
        return Gatecrash.process(dryRaw(kit, role, velocity, take), space, touch(velocity), seedOf(kit, role))
    }

    /** The gesture as numbers, for the audition's metrics table: levels in dB against a full-scale dry hit. */
    data class Gesture(
        val mode: Gatecrash.Mode,
        /** Attacks the key heard: 1 for a hit, one per note for a phrase. */
        val attacks: Int,
        /** The return 30 to 50 ms after the first attack. */
        val earlyDb: Double,
        /** The return over the last 20 ms of the hold after the last attack. */
        val lateDb: Double,
        /** The return once the space has shut. */
        val afterDb: Double,
        /** The return's highest peak from 30 ms in to the end of the hold. */
        val roomPeakDb: Double,
    )

    /** Measures one pad's space on its real render. Null for a dry pad. */
    fun gesture(kit: Kit, role: Int, velocity: Float = 1f, take: Int = 0): Gesture? {
        val space = spaceOf(kit, role) ?: return null
        val s = stages(kit, role, velocity, take) ?: return null
        fun frames(ms: Float) = (ms * .001f * Dsp.RATE).toInt()
        fun db(x: Double) = 20 * kotlin.math.log10(max(x, 1e-9))
        fun rms(from: Int, to: Int): Double {
            var sum = 0.0
            var n = 0
            for (i in max(from, 0) until min(to, s.ret.size)) { sum += s.ret[i].toDouble() * s.ret[i]; n++ }
            return if (n == 0) 0.0 else kotlin.math.sqrt(sum / n)
        }
        val first = s.attacks.first()
        val holdEnd = s.attacks.last() + frames(space.holdMs)
        // A burst is fed 42 ms of the hit, so it ends that much after its own envelope does.
        val shut = holdEnd + frames(space.closeMs) + frames(if (space.mode == BURST) 70f else 5f)
        var peak = 0f
        for (i in first + frames(30f) until min(holdEnd, s.ret.size)) peak = max(peak, abs(s.ret[i]))
        return Gesture(space.mode, s.attacks.size, db(rms(first + frames(30f), first + frames(50f))),
            db(rms(holdEnd - frames(20f), holdEnd)), db(rms(shut, shut + frames(20f))), db(peak.toDouble()))
    }

    private fun raw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray =
        stages(kit, role, velocity, take)?.out ?: dryRaw(kit, role, velocity, take)

    internal fun dryRaw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val parts = parts(kit)
        if (role < HITS) return struck(parts[role], velocity, take)
        val phrase = phrases(kit)[role - HITS]
        val beat = Dsp.RATE * 60.0 / kit.bpm
        val out = FloatArray((beat * 4).roundToInt() + Dsp.RATE / 2)
        for ((i, note) in phrase.notes.withIndex()) {
            // Each note is its own strike: the take walks so neighbouring notes are never the same audio.
            val hit = struck(parts[note.part], (velocity * note.weight).coerceIn(.05f, 1f), (take + i) % TAKES)
            val start = (note.beat * beat).roundToInt()
            for (j in 0 until min(hit.size, out.size - start)) out[start + j] += hit[j]
        }
        return out
    }

    /** A dry strike, peak at [touch]. The engines peak-normalise every render, so level is set here. */
    private fun struck(part: Part, velocity: Float, take: Int): FloatArray {
        val x = part.strike(velocity, take)
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        val k = if (peak > 1e-9f) touch(velocity) / peak else 0f
        return FloatArray(x.size) { x[it] * k }
    }

    private val gains = ConcurrentHashMap<Triple<Kit, Int, Float>, Float>()

    /**
     * One gain per velocity zone, set by the zone's first take and shared by its four takes, so every zone
     * peaks at the same level. Level is the sampler's job: the app and the MPC both turn pad velocity into
     * gain, and a zone that was also rendered quieter would be turned down twice. The zones differ in how
     * the drum was struck and how much room it got, which is what [touch] and [Gatecrash.SEND_POWER] set
     * before this gain. Takes share the gain so one strike may still land a little harder than the next.
     */
    private fun zoneGain(kit: Kit, role: Int, velocity: Float): Float = gains.getOrPut(Triple(kit, role, velocity)) {
        // Measured after the output stage: removing a kick's DC and sub-20 Hz content moves its peak.
        val peak = finish(raw(kit, role, velocity, 0), 1f, ceiling = Float.MAX_VALUE).peak()
        if (peak > 1e-9f) PAD_PEAK / peak else 1f
    }

    /** Where every zone's first take peaks. */
    const val PAD_PEAK = .92f

    private fun finish(x: FloatArray, gain: Float, ceiling: Float = .98f): Snip = KitStrikes.finish(x, gain, ceiling)

    // ---------- sources ----------

    // The strike laws live in [KitStrikes] (lifted out of here when CYPHER needed the same ones); these keep
    // this kit's call sites as they were authored and heard. A GATECRASH strike takes no pitch offset.

    /** SKIN's struck voices (kick, snare, tom) take velocity and take themselves: a real re-strike. */
    private fun skin(voice: SkinVoice, vararg m: Pair<String, Float>): (Float, Int) -> FloatArray =
        KitStrikes.skin(voice, *m).let { s -> { v, t -> s(v, t, 0f) } }

    private fun thump(voice: ThumpVoice, base: Map<String, Float>, vary: List<String> = listOf("TUNE", "DECAY"),
                      soft: (Float) -> Map<String, Float> = { emptyMap() }): (Float, Int) -> FloatArray =
        KitStrikes.thump(voice, base, vary, soft).let { s -> { v, t -> s(v, t, 0f) } }

    private fun skinTouched(voice: SkinVoice, base: Map<String, Float>, vary: List<String> = listOf("TUNE", "DECAY"),
                            soft: (Float) -> Map<String, Float> = { emptyMap() }): (Float, Int) -> FloatArray =
        KitStrikes.skinTouched(voice, base, vary, soft = soft).let { s -> { v, t -> s(v, t, 0f) } }

    /** THUMP's kick reads velocity itself (less pitch drop, a duller click, less drive). */
    private fun thumpKick(vararg m: Pair<String, Float>): (Float, Int) -> FloatArray =
        KitStrikes.thumpKick(*m).let { s -> { v, t -> s(v, t, 0f) } }

    /** ONSET's snare is touched like THUMP's voices; the model version is pinned ([KitStrikes.ONSET_MODEL]). */
    private fun onset(voice: OnsetVoice, base: Map<String, Float>, soft: (Float) -> Map<String, Float>): (Float, Int) -> FloatArray =
        KitStrikes.onset(voice, base, soft).let { s -> { v, t -> s(v, t, 0f) } }

    // ---------- the three kits ----------

    internal fun parts(kit: Kit): List<Part> = when (kit) {
        Kit.STONE_ROOM -> stoneRoom
        Kit.MACHINE_POP -> machinePop
        Kit.ELECTRO_FUNK -> electroFunk
    }

    private fun phrases(kit: Kit): List<Phrase> = when (kit) {
        Kit.STONE_ROOM -> stonePhrases
        Kit.MACHINE_POP -> machinePhrases
        Kit.ELECTRO_FUNK -> electroPhrases
    }

    // Round 2. The owner heard round 1 as harsh, loud static. Measured, its rooms carried 14 to 20 dB more
    // energy than their drums, were as bright as the drums, were clipped hard, and the dry drum was cut by
    // the gate. Now: roomDb is the room's energy against the drum's and never passes +4, every return is
    // low-passed well under the drum's own top, the clip is gentle, and the drum is left nearly whole.

    // Hats and the crash get no space at all: a held room on a cymbal is hiss.

    // Round 3. The owner, on round 2: "It seems like 80s drums round off at the end rather than cut
    // abruptly." A hardware gate has a release time. The close was 10 to 25 ms, a chop; it is now 45 to
    // 130 ms on a raised cosine, so the room leans over and falls instead of stopping.

    // Round 4. The owner, on round 3: "I think we are missing some brightness." Round 2 had low-passed every
    // return at 1.5 to 3.5 kHz and dulled the machine snares to kill round 1's static. That static was mostly
    // level (rooms 14 to 20 dB over their drums). With the level now sane the returns open to 2.2 to 6 kHz
    // and the snares and toms get some of their snap and click back.

    // STONE ROOM. One hard room, heard three ways: the kick opens it briefly, the snare holds it, and the
    // toms get the longest hold and the deepest crush, because the tom run is what this sound is known for.
    private val stoneKick = Space(STONE, size = .55f, walls = .85f, crush = .70f, holdMs = 170f, closeMs = 70f, roomDb = -6f, toneHz = 2_200f)
    private val stoneSnare = Space(STONE, size = .62f, walls = .90f, crush = .85f, holdMs = 300f, closeMs = 110f, roomDb = 4f, dryCut = .25f, toneHz = 5_500f)
    private val stoneTom = Space(STONE, size = .70f, walls = .90f, crush = .95f, holdMs = 340f, closeMs = 130f, roomDb = 4f, dryCut = .3f, toneHz = 4_000f)
    private val stoneTight = stoneSnare.copy(holdMs = 180f, closeMs = 90f, roomDb = 1f)
    private val stoneWash = Space(STONE, size = .62f, walls = .80f, crush = .60f, holdMs = 520f, closeMs = 140f, roomDb = -12f, drive = .1f, toneHz = 4_000f)

    private fun concertTom(tune: Float, decay: Float) = Part("", DrumClass.TOM, stoneTom,
        // Single-headed toms: a hard attack, a short body and a pitch that sags after the hit (DROP).
        strike = skin(SkinVoice.TOM, "TUNE" to tune, "DECAY" to decay, "TONE" to .62f, "PUNCH" to .62f, "DROP" to .32f, "CLICK" to .55f))

    private fun named(name: String, p: Part) = Part(name, p.cls, p.space, p.mute, p.strike)

    private val stoneRoom: List<Part> = listOf(
        Part("Stone Kick", DrumClass.KICK, stoneKick,
            strike = skin(SkinVoice.KICK, "TUNE" to .30f, "DECAY" to .50f, "TONE" to .45f, "PUNCH" to .65f, "CLICK" to .62f, "DROP" to .2f)),
        Part("Wall Snare", DrumClass.SNARE, stoneSnare,
            strike = skin(SkinVoice.SNARE, "TUNE" to .30f, "SNAP" to .68f, "DECAY" to .50f, "PUNCH" to .60f, "RATTLE" to .55f)),
        Part("Closed Hat", DrumClass.HAT_CLOSED, null, mute = 1,
            strike = skinTouched(SkinVoice.HAT_CLOSED, mapOf("TUNE" to .45f, "DECAY" to .30f, "TONE" to .45f)) { v -> mapOf("PUNCH" to .35f + .3f * v) }),
        Part("Open Hat", DrumClass.HAT_OPEN, null, mute = 1,
            strike = skinTouched(SkinVoice.HAT_OPEN, mapOf("TUNE" to .45f, "DECAY" to .50f, "TONE" to .45f)) { v -> mapOf("PUNCH" to .35f + .3f * v) }),
        Part("Cross Stick", DrumClass.PERC, stoneTight.copy(roomDb = 0f, toneHz = 3_500f),
            strike = skinTouched(SkinVoice.STICK, mapOf("TUNE" to .45f, "DECAY" to .40f, "BODY" to .4f)) { v -> mapOf("CLICK" to .25f + .4f * v, "PUNCH" to .4f + .3f * v) }),
        Part("Crack Snare", DrumClass.SNARE, stoneTight,
            strike = skin(SkinVoice.SNARE, "TUNE" to .62f, "SNAP" to .75f, "DECAY" to .30f, "PUNCH" to .70f, "RATTLE" to .45f)),
        named("Concert Tom 1", concertTom(.92f, .40f)),
        named("Concert Tom 2", concertTom(.76f, .44f)),
        named("Concert Tom 3", concertTom(.60f, .48f)),
        named("Concert Tom 4", concertTom(.44f, .52f)),
        named("Concert Tom 5", concertTom(.28f, .56f)),
        named("Floor Tom", concertTom(.10f, .62f)),
        Part("Dark Ride", DrumClass.PERC, stoneWash,
            strike = skinTouched(SkinVoice.RIDE, mapOf("TUNE" to .35f, "DECAY" to .60f, "TONE" to .40f)) { v -> mapOf("PUNCH" to .3f + .4f * v, "SIZZLE" to .2f * v) }),
        Part("Gated Shaker", DrumClass.PERC, stoneTight.copy(holdMs = 120f, roomDb = -6f, toneHz = 3_500f),
            strike = skinTouched(SkinVoice.SHAKER, mapOf("DECAY" to .30f, "TONE" to .5f), vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("GRAIN" to .2f + .3f * v, "PUNCH" to .4f + .3f * v) }),
    )

    private val stonePhrases = listOf(
        // A falling run over all six toms: pairs at the top, wider at the bottom so the low drums have room.
        Phrase("Tom Fill", stoneTom, listOf(
            Note(0.0, 6, .80f), Note(0.25, 6, .70f), Note(0.5, 7, .85f), Note(0.75, 7, .72f),
            Note(1.0, 8, .88f), Note(1.5, 8, .78f), Note(1.75, 9, .90f), Note(2.0, 9, .82f),
            Note(2.5, 10, .95f), Note(3.0, 10, .88f), Note(3.5, 11, 1f), Note(3.75, 11, .92f))),
        Phrase("Snare Build", stoneSnare, build(part = 1)),
    )

    // MACHINE POP. No room at all: every space here is a burst of reflections that does not decay.
    private val popKick = Space(BURST, holdMs = 110f, closeMs = 50f, roomDb = -10f, drive = .1f, toneHz = 2_200f)
    private val popSnare = Space(BURST, holdMs = 240f, closeMs = 80f, roomDb = 3f, tilt = .5f, dryCut = .2f, toneHz = 6_000f)
    private val popTom = Space(BURST, holdMs = 260f, closeMs = 90f, roomDb = -2f, tilt = .3f, toneHz = 4_500f)
    private val popClap = Space(BURST, holdMs = 200f, closeMs = 70f, roomDb = 2f, tilt = .8f, dryCut = .2f, toneHz = 5_000f)
    private val popAir = Space(BURST, holdMs = 70f, closeMs = 50f, roomDb = -8f, drive = .1f, toneHz = 4_000f)

    private fun hexTom(name: String, tune: Float) = Part(name, DrumClass.TOM, popTom,
        // The pad-kit tom: a sine that dives a long way (SWEEP) quickly (BEND), with a noisy click on top.
        strike = thump(ThumpVoice.TOM, mapOf("TUNE" to tune, "SWEEP" to .92f, "DECAY" to .50f, "BEND" to .35f, "PUNCH" to .6f)) { v ->
            mapOf("CLICK" to .2f + .45f * v, "DRIVE" to .05f + .3f * v, "SWEEP" to .62f + .3f * v) })

    private val machinePop: List<Part> = listOf(
        Part("Punch Kick", DrumClass.KICK, popKick,
            strike = thumpKick("TUNE" to .50f, "SWEEP" to .60f, "DECAY" to .30f, "CLICK" to .50f, "DRIVE" to .35f, "PUNCH" to .6f)),
        Part("Burst Snare", DrumClass.SNARE, popSnare,
            strike = thump(ThumpVoice.SNARE, mapOf("TUNE" to .45f, "DECAY" to .40f, "PUNCH" to .6f)) { v -> mapOf("SNAP" to .35f + .3f * v, "TONE" to .3f + .25f * v) }),
        Part("Closed Hat", DrumClass.HAT_CLOSED, null, mute = 1,
            strike = thump(ThumpVoice.HAT_CLOSED, mapOf("TUNE" to .5f, "DECAY" to .30f, "METAL" to .6f)) { v -> mapOf("NOISE" to .05f + .12f * v) }),
        Part("Open Hat", DrumClass.HAT_OPEN, null, mute = 1,
            strike = thump(ThumpVoice.HAT_OPEN, mapOf("TUNE" to .5f, "DECAY" to .45f, "METAL" to .6f)) { v -> mapOf("NOISE" to .05f + .12f * v) }),
        Part("Burst Clap", DrumClass.CLAP, popClap,
            strike = thump(ThumpVoice.CLAP, mapOf("SPREAD" to .45f, "DECAY" to .40f), vary = listOf("SPREAD", "DECAY", "TONE")) { v -> mapOf("TONE" to .35f + .3f * v) }),
        Part("Rim", DrumClass.PERC, popSnare.copy(holdMs = 120f, roomDb = -8f, toneHz = 3_000f),
            strike = thump(ThumpVoice.RIM, mapOf("TUNE" to .55f, "DECAY" to .25f), vary = listOf("TUNE", "DECAY", "RING"))),
        hexTom("Hex Tom 1", .85f), hexTom("Hex Tom 2", .62f), hexTom("Hex Tom 3", .40f), hexTom("Hex Tom 4", .18f),
        Part("Cowbell", DrumClass.PERC, popAir.copy(holdMs = 130f),
            strike = thump(ThumpVoice.COWBELL, mapOf("TUNE" to .5f, "DECAY" to .35f)) { v -> mapOf("TONE" to .35f + .3f * v) }),
        Part("Spark", DrumClass.PERC, popTom.copy(holdMs = 160f, roomDb = -4f),
            strike = thump(ThumpVoice.SPARK, mapOf("TUNE" to .5f, "DECAY" to .35f, "COLOR" to .5f)) { v -> mapOf("METAL" to .1f + .3f * v) }),
        Part("Tight Snare", DrumClass.SNARE, popSnare.copy(holdMs = 120f, roomDb = 0f, tilt = 0f),
            strike = thump(ThumpVoice.SNARE, mapOf("TUNE" to .70f, "DECAY" to .25f, "PUNCH" to .65f)) { v -> mapOf("SNAP" to .45f + .3f * v, "TONE" to .4f + .25f * v) }),
        Part("Machine Crash", DrumClass.PERC, null,
            strike = thump(ThumpVoice.HAT_OPEN, mapOf("TUNE" to .30f, "DECAY" to 1f, "METAL" to .75f)) { v -> mapOf("NOISE" to .3f + .3f * v) }),
    )

    private val machinePhrases = listOf(
        // Sixteenths down the four pads, ending on the lowest with the snare under it.
        Phrase("Hex Fill", popTom, listOf(
            Note(0.0, 6, .85f), Note(0.25, 6, .70f), Note(0.5, 6, .78f), Note(0.75, 7, .88f),
            Note(1.0, 7, .75f), Note(1.25, 7, .80f), Note(1.5, 8, .90f), Note(1.75, 8, .78f),
            Note(2.0, 8, .84f), Note(2.25, 9, .94f), Note(2.75, 9, .86f), Note(3.0, 9, .92f),
            Note(3.5, 9, 1f), Note(3.5, 1, .9f))),
        Phrase("Clap Build", popClap, build(part = 4)),
    )

    // ELECTRO FUNK. The kick is long, low and dry; everything else is short, and the room is small, bright
    // and shut almost at once, so the kit stays out of the way of a bass line.
    private val funkSnare = Space(STONE, size = .28f, walls = .95f, crush = .75f, holdMs = 110f, closeMs = 45f, roomDb = 1f, dryCut = .2f, toneHz = 6_000f)
    private val funkTom = funkSnare.copy(holdMs = 140f, closeMs = 60f, roomDb = -3f, dryCut = .1f, toneHz = 4_200f)
    private val funkSmall = funkSnare.copy(holdMs = 80f, roomDb = -8f, dryCut = 0f, toneHz = 3_500f)

    private fun synthTom(name: String, tune: Float) = Part(name, DrumClass.TOM, funkTom,
        strike = thump(ThumpVoice.TOM, mapOf("TUNE" to tune, "SWEEP" to .50f, "DECAY" to .40f, "PUNCH" to .55f)) { v ->
            mapOf("CLICK" to .1f + .3f * v, "DRIVE" to .1f * v) })

    private val electroFunk: List<Part> = listOf(
        Part("Boom Kick", DrumClass.KICK, null,
            strike = thumpKick("TUNE" to .25f, "SWEEP" to .45f, "DECAY" to .80f, "CLICK" to .30f, "DRIVE" to .15f)),
        Part("Snap Snare", DrumClass.SNARE, funkSnare,
            // ONSET leaning electronic: a different drum from MACHINE POP's THUMP snare, not that snare retuned.
            strike = onset(OnsetVoice.SNARE, mapOf("TUNE" to .70f, "DECAY" to .30f, "BLEND" to .30f, "BODY" to .45f)) { v -> mapOf("SNAP" to .5f + .3f * v, "EDGE" to .4f + .3f * v) }),
        Part("Closed Hat", DrumClass.HAT_CLOSED, null, mute = 1,
            strike = thump(ThumpVoice.HAT_CLOSED, mapOf("TUNE" to .55f, "DECAY" to .20f, "METAL" to .8f)) { v -> mapOf("NOISE" to .04f + .1f * v) }),
        Part("Open Hat", DrumClass.HAT_OPEN, null, mute = 1,
            strike = thump(ThumpVoice.HAT_OPEN, mapOf("TUNE" to .55f, "DECAY" to .40f, "METAL" to .8f)) { v -> mapOf("NOISE" to .04f + .1f * v) }),
        Part("Room Clap", DrumClass.CLAP, funkSnare.copy(holdMs = 130f, toneHz = 4_500f),
            strike = thump(ThumpVoice.CLAP, mapOf("SPREAD" to .65f, "DECAY" to .35f, "CLAPS" to .7f), vary = listOf("SPREAD", "DECAY", "TONE")) { v -> mapOf("TONE" to .45f + .3f * v) }),
        Part("Rim Click", DrumClass.PERC, funkSmall,
            strike = thump(ThumpVoice.RIM, mapOf("TUNE" to .70f, "DECAY" to .15f), vary = listOf("TUNE", "DECAY", "RING"))),
        Part("Cowbell", DrumClass.PERC, funkSmall.copy(roomDb = -8f),
            strike = thump(ThumpVoice.COWBELL, mapOf("TUNE" to .55f, "DECAY" to .45f)) { v -> mapOf("TONE" to .4f + .3f * v) }),
        Part("Thud Kick", DrumClass.KICK, funkSmall,
            strike = thumpKick("TUNE" to .50f, "SWEEP" to .55f, "DECAY" to .20f, "CLICK" to .60f, "DRIVE" to .45f)),
        synthTom("Low Tom", .20f), synthTom("Mid Tom", .50f), synthTom("High Tom", .80f),
        Part("Zap", DrumClass.PERC, funkSmall.copy(holdMs = 100f),
            strike = thump(ThumpVoice.SPARK, mapOf("TUNE" to .6f, "DECAY" to .30f, "COLOR" to .7f)) { v -> mapOf("METAL" to .2f + .3f * v) }),
        Part("Shaker", DrumClass.PERC, null,
            strike = skinTouched(SkinVoice.SHAKER, mapOf("DECAY" to .20f, "TONE" to .55f), vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("GRAIN" to .15f + .3f * v) }),
        Part("Block", DrumClass.PERC, funkSmall,
            strike = skinTouched(SkinVoice.STICK, mapOf("TUNE" to .70f, "DECAY" to .30f, "BODY" to .6f)) { v -> mapOf("CLICK" to .2f + .4f * v) }),
    )

    private val electroPhrases = listOf(
        // Toms falling against an off-beat cowbell, landing on two claps.
        Phrase("Tom Break", funkTom, listOf(
            Note(0.0, 10, .90f), Note(0.5, 10, .75f), Note(0.75, 9, .88f), Note(1.25, 9, .78f),
            Note(1.5, 6, .70f), Note(2.0, 8, .95f), Note(2.5, 8, .82f), Note(2.75, 6, .72f),
            Note(3.0, 4, .92f), Note(3.25, 8, .85f), Note(3.5, 4, 1f))),
        Phrase("Snare Build", funkSnare, build(part = 1)),
    )

    /** One bar that tightens from eighths to sixteenths to thirty-seconds and grows louder as it goes. */
    private fun build(part: Int): List<Note> {
        val beats = listOf(0.0, .5, 1.0, 1.5, 2.0, 2.25, 2.5, 2.75) + List(8) { 3.0 + it * .125 }
        return beats.mapIndexed { i, b -> Note(b, part, .35f + .65f * i / beats.lastIndex) }
    }
}
