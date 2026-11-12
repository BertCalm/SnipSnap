package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.synth.Mirrorball.Combo
import com.snipsnap.synth.Mirrorball.Eq
import com.snipsnap.synth.Mirrorball.EqKind
import com.snipsnap.synth.Mirrorball.Press
import com.snipsnap.synth.Mirrorball.Print
import com.snipsnap.synth.Mirrorball.Ratio
import com.snipsnap.synth.Mirrorball.Reel
import com.snipsnap.synth.Mirrorball.Setting
import com.snipsnap.synth.Mirrorball.Tape
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The MIRRORBALL kits: three authored 1970s disco kits, each sixteen pads (fourteen hits, a fill and a build),
 * every pad printed through [Mirrorball]'s FET compressor → multi-head tape echo → amp.
 *
 * A rendered kit family, like [GatecrashKits], not a Patch engine: the hits come from SKIN, THUMP, ONSET,
 * TERRA as they stand, and a tambourine synthesized from BACKWASH's recordings. The three kits differ in their drums, tunings, pad
 * roles and tempo, and in how the chain is set, not only in how much of it there is:
 *
 * - [Kit.PHILLY]: a live soul-disco kit. Dead kick, towelled snare, two congas, a synthesized tambourine
 *   ([MirrorballTambourine], fitted to BACKWASH's recordings), two toms. Dry: the echo is a whisper on the snare and a gallop on the hats and tambourine.
 * - [Kit.LOFT]: a New York party kit. A clap with the snare, three hand drums, two timbales, cowbell,
 *   claves. Longer, wetter echoes on the percussion, older tape, the amp a little warmer.
 * - [Kit.MUNICH]: a machine-disco kit. Analog kick and snare, four diving synth toms, a sequencer hat that
 *   rides the dotted-eighth head, newer tape and the amp pushed.
 *
 * Every pad has three velocity zones of four takes. Where the engine can be struck at a velocity (SKIN's
 * kick, snare and toms, TERRA's hand drums, THUMP's kick, the tambourine's recorded layers) a zone is a fresh
 * strike. The other voices have no strike velocity, so their soft zones are built ([touched]): velocity moves
 * the controls a softer stick would move and takes the top off the contact. Either way the softer hit reaches
 * the compressor's fixed threshold lower, so it is squeezed less, saturates the tape less and drives the amp
 * less: it comes out more open and cleaner, as a softer hit through real hardware does. A take is another
 * strike where the engine allows one and a small move of two or three controls where it does not.
 *
 * Copied, not shared: [finish] and the zone levelling follow [GatecrashKits] line for line. Sharing them
 * would mean editing the approved GATECRASH round, so the copy is disclosed here instead.
 *
 * **The sound (round 1b):** the owner heard round 0 as "kind of basic and not very punchy or character rich",
 * picked round 0b's Punch, and asked for it "dialed back like 10-15%". Every pad is now printed (console EQ per
 * drum, then hot tape), compressed in parallel, and played through an amp driven to the edge of breakup, at
 * [INTENSITY] of the Punch settings: the EQ's decibels, the tape's heat, the crushed copy's share, the amp's gain
 * above unity and its sag are all scaled by it. Each kit then leans its own way ([voicingOf]).
 *
 * Every setting below is a shape value. Only round 0b's Punch, in the Python harness, has been heard.
 */
object MirrorballKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 3

    /** Round 0b's Punch, dialed back as the owner asked ("like 10-15%"): the middle of that, 87.5 percent. */
    const val INTENSITY = .875f

    /** The felt beater's level on every kick, against the drum's peak: round 0b's 0.55 at [INTENSITY]. */
    const val BEATER = .55f * INTENSITY

    enum class Kit(val id: String, val title: String, val bpm: Int) {
        PHILLY("mirrorball-philly", "Philly Strings", 116),
        LOFT("mirrorball-loft", "Loft Party", 122),
        MUNICH("mirrorball-munich", "Munich Machine", 128),
    }

    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = 4
    const val HITS = 14
    const val PADS = 16

    /** Every hit stays under the classifier's 1.5 s LOOP threshold; an echo is faded over its last 150 ms. */
    const val HIT_SECONDS = 1.45f
    private const val CAP_FADE_SECONDS = .15f

    /** One playable hit: what it is, how its chain is set, and how to strike it dry. */
    internal class Part(
        val name: String,
        val cls: DrumClass,
        val chain: Chain,
        val mute: Int = 0,
        val strike: (velocity: Float, take: Int) -> FloatArray,
    )

    /** A pad's own share of the chain; the kit supplies the tape, the amp and the console EQ ([eqOf]). */
    internal data class Chain(val press: Press, val reel: Reel = Reel(), val cabHz: Float = BRIGHT_CAB)

    /** How hard a kit is printed and played, at round 0b's full Punch: tape heat, crushed share, amp gain and sag. */
    data class Voicing(val hot: Float, val parallel: Float, val drive: Float, val sag: Float)

    private class Note(val beat: Double, val part: Int, val weight: Float)

    private class Phrase(val name: String, val chain: Chain, val notes: List<Note>)

    /** The speaker's roll-off for bright pads (snares, hats, percussion) and for the low ones (kicks, toms, hand drums). */
    const val BRIGHT_CAB = 8_500f
    const val WARM_CAB = 6_500f

    /** A dry hit's level against a full one, as the compressor hears it. Zones are levelled afterwards. */
    fun touch(velocity: Float): Float = .35f + .65f * velocity.coerceIn(0f, 1f)

    fun roles(kit: Kit): List<String> = parts(kit).map { it.name } + phrases(kit).map { it.name }

    fun build(kit: Kit): List<ArrangedPad> {
        val parts = parts(kit)
        val names = roles(kit)
        return List(PADS) { role ->
            // Each take is printed once: the zone's level comes from its first take's own print.
            val grid = velocities.map { v ->
                val raws = List(TAKES) { t -> raw(kit, role, v, t) }
                val gain = gains.getOrPut(Triple(kit, role, v)) { gainFor(raws[0].copyOf(), role) }
                raws.map { finish(it, gain, cap = role < HITS) }
            }
            val part = parts.getOrNull(role)
            val chain = chainOf(kit, role)
            ArrangedPad(
                grid.last().first(), part?.cls ?: DrumClass.LOOP, displayName = names[role], velocityTakes = grid,
                // Hats choke each other; the two phrases choke each other so a fill can cut a build.
                muteGroup = part?.mute ?: 2,
                source = mapOf(
                    "engine" to "MIRRORBALL", "kitVersion" to VERSION.toString(),
                    "stageVersion" to Mirrorball.VERSION.toString(), "kit" to kit.name,
                    "role" to role.toString(), "bpm" to kit.bpm.toString(),
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(),
                    "articulation" to "finite",
                    "processing" to "print>press+parallel>reel>combo", "intensity" to INTENSITY.toString(),
                    "echoHeads" to chain.reel.heads.joinToString("+").ifEmpty { "none" },
                ),
            )
        }
    }

    /**
     * How the amp's speaker is heard. [CLOSED] is round 1b: the amp alone through a guitar speaker, heard by the owner
     * as "a bit mid heavy, like listening through dollar store headphones". [OPEN] opens the speaker to 40 Hz and past
     * 13 kHz; [PARALLEL] re-amps in parallel (the direct signal and the amp at equal level); [OPEN_PARALLEL] is both.
     */
    enum class Speaker { CLOSED, OPEN, PARALLEL, OPEN_PARALLEL }

    /** The speaker the kits are printed with: the owner's pick from round 1c (2026-11-12). */
    val SPEAKER = Speaker.OPEN_PARALLEL

    /** The full setting [role] is printed with, at [intensity] of round 0b's Punch ([INTENSITY] in the kits). */
    fun settingOf(kit: Kit, role: Int, intensity: Float = INTENSITY, speaker: Speaker = SPEAKER): Setting {
        require(intensity.isFinite() && intensity in 0f..1f)
        val c = chainOf(kit, role)
        val v = voicingOf(kit)
        val eq = eqOf(kit, role).map { it.copy(db = it.db * intensity) }
        var amp = ampOf(kit).copy(drive = 1f + (v.drive - 1f) * intensity, sag = v.sag * intensity, cabHz = c.cabHz)
        if (speaker == Speaker.OPEN || speaker == Speaker.OPEN_PARALLEL)
            amp = amp.copy(lowHz = 40f, bumpDb = 1.5f, cabHz = if (c.cabHz >= BRIGHT_CAB) 15_000f else 13_000f)
        if (speaker == Speaker.PARALLEL || speaker == Speaker.OPEN_PARALLEL) amp = amp.copy(blend = .5f)
        return Setting(c.press, c.reel, tapeOf(kit), amp, Print(eq, v.hot * intensity), v.parallel * intensity)
    }

    /** One pad's hard-to-soft take as [render] makes it, but with [speaker]: the audition's comparison, not in a kit. */
    fun renderWith(kit: Kit, role: Int, velocity: Float, take: Int, speaker: Speaker): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        val dry = dryRaw(kit, role, velocity, take)
        val s = settingOf(kit, role, speaker = speaker)
        val x = Mirrorball.process(dry, s, kit.bpm, seedOf(kit, role), lengthOf(kit, role, dry, s.reel)).out
        val peak = finish(x.copyOf(), 1f, ceiling = Float.MAX_VALUE, cap = role < HITS).samples.maxOf { abs(it) }
        return finish(x, if (peak > 1e-9f) PAD_PEAK / peak else 1f, cap = role < HITS)
    }

    // Console EQ, at round 0b's full Punch. From the owner's research note: a disco kick's thump at 60 to 80 Hz and
    // its beater at 2 to 3 kHz, the box cut out between; a snare's body and a bright top; the hats' harsh top
    // rolled off a little.
    private val KICK_EQ = listOf(Eq(EqKind.PEAK, 68f, 1.1f, 6f), Eq(EqKind.PEAK, 380f, 1.2f, -6f), Eq(EqKind.PEAK, 3_000f, .9f, 11f))
    private val SNARE_EQ = listOf(Eq(EqKind.PEAK, 210f, 1f, 4f), Eq(EqKind.PEAK, 700f, 1.2f, -3f), Eq(EqKind.HIGH_SHELF, 5_500f, .7f, 6f))
    private val TOM_EQ = listOf(Eq(EqKind.PEAK, 110f, 1f, 3f), Eq(EqKind.PEAK, 420f, 1.2f, -4f), Eq(EqKind.PEAK, 3_500f, 1.2f, 7f))
    private val HAT_EQ = listOf(Eq(EqKind.HIGH_PASS, 350f, .7f), Eq(EqKind.PEAK, 11_000f, .9f, -2.5f))
    private val CLAP_EQ = listOf(Eq(EqKind.PEAK, 1_200f, .9f, 3f), Eq(EqKind.HIGH_SHELF, 6_000f, .7f, 3f))
    private val METAL_EQ = listOf(Eq(EqKind.HIGH_PASS, 400f, .7f))
    private val HAND_EQ = listOf(Eq(EqKind.PEAK, 3_200f, 1.2f, 5f))

    /**
     * The console EQ for [role], by what the drum is: its class, and for percussion its name (hand drums and
     * timbales; bells and the tambourine; shakers and the sequencer hat; sticks, rims and claves). A phrase takes its
     * first note's drum's EQ.
     */
    internal fun eqOf(kit: Kit, role: Int): List<Eq> {
        val part = if (role < HITS) parts(kit)[role] else parts(kit)[phrases(kit)[role - HITS].notes.first().part]
        return when (part.cls) {
            DrumClass.KICK -> KICK_EQ
            DrumClass.SNARE -> SNARE_EQ
            DrumClass.TOM -> TOM_EQ
            DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN -> HAT_EQ
            DrumClass.CLAP -> CLAP_EQ
            else -> when {
                Regex("Conga|Tumba|Quinto|Bongo|Timbale").containsMatchIn(part.name) -> HAND_EQ
                Regex("Tambourine|Cowbell|Ride").containsMatchIn(part.name) -> METAL_EQ
                Regex("Shaker|Sequencer Hat").containsMatchIn(part.name) -> HAT_EQ
                Regex("Stick|Rim|Claves").containsMatchIn(part.name) -> CLAP_EQ
                else -> emptyList()
            }
        }
    }

    /** Each kit's lean, at full Punch: Philly is round 0b's Punch itself, Loft a little hotter, Munich hotter still. */
    fun voicingOf(kit: Kit): Voicing = when (kit) {
        Kit.PHILLY -> Voicing(hot = .45f, parallel = .45f, drive = 2.6f, sag = .45f)
        Kit.LOFT -> Voicing(hot = .50f, parallel = .45f, drive = 3.0f, sag = .50f)
        Kit.MUNICH -> Voicing(hot = .55f, parallel = .55f, drive = 3.6f, sag = .55f)
    }

    internal fun chainOf(kit: Kit, role: Int): Chain =
        if (role < HITS) parts(kit)[role].chain else phrases(kit)[role - HITS].chain

    private fun seedOf(kit: Kit, role: Int) = Dsp.seedFor("MIRRORBALL", kit.name, role)

    /** The finished pad at one velocity and take. Pure: the same arguments give the same bytes. */
    fun render(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        return finish(raw(kit, role, velocity, take), zoneGain(kit, role, velocity), cap = role < HITS)
    }

    /** The same strike with no chain, levelled by the same pad gain: the audition's dry comparison. */
    fun dry(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        return finish(dryRaw(kit, role, velocity, take), zoneGain(kit, role, velocity), cap = role < HITS)
    }

    /**
     * Alternatives the audition plays beside a pad, none of them part of any kit: round 0b's full Punch, a lighter
     * print (75 percent), the rack's order, no echo, more echo, and on the tambourine the CC0 recording it was fitted
     * to, through the same chain.
     */
    enum class Trial { DRY, FULL_PUNCH, LIGHTER, RACK_ORDER, NO_ECHO, MORE_ECHO, RECORDED_TAMBOURINE, JINGLES_TAMBOURINE, TEXTURE_TAMBOURINE }

    /** The hard first take of [role] with one thing changed, levelled on its own to the pad peak. */
    fun trial(kit: Kit, role: Int, t: Trial): Snip {
        require(role in 0 until PADS)
        val base = settingOf(kit, role)
        val alternative = when (t) {
            Trial.RECORDED_TAMBOURINE -> recordedTambourine()
            Trial.JINGLES_TAMBOURINE -> tambourine(TambourineModel.JINGLES)
            Trial.TEXTURE_TAMBOURINE -> tambourine(TambourineModel.TEXTURE)
            else -> null
        }
        val dry = if (alternative != null) {
            require(role < HITS && parts(kit)[role].name == "Tambourine") { "only the tambourine pad has tambourine alternatives" }
            struck(Part("Tambourine", DrumClass.PERC, chainOf(kit, role), strike = alternative), 1f, 0)
        } else dryRaw(kit, role, 1f, 0)
        val x = when (t) {
            Trial.DRY -> dry
            else -> {
                val s = when (t) {
                    Trial.FULL_PUNCH -> settingOf(kit, role, 1f)
                    Trial.LIGHTER -> settingOf(kit, role, .75f)
                    Trial.NO_ECHO -> base.copy(reel = Reel())
                    Trial.MORE_ECHO -> base.copy(reel = base.reel.copy(send = min(1f, base.reel.send * 2f), repeats = min(1f, base.reel.repeats + .2f)))
                    else -> base
                }
                val order = if (t == Trial.RACK_ORDER) Mirrorball.Order.RACK else Mirrorball.Order.OWNER
                Mirrorball.process(dry, s, kit.bpm, seedOf(kit, role), lengthOf(kit, role, dry, s.reel), order).out
            }
        }
        val peak = finish(x.copyOf(), 1f, ceiling = Float.MAX_VALUE, cap = role < HITS).samples.maxOf { abs(it) }
        return finish(x, if (peak > 1e-9f) PAD_PEAK / peak else 1f, cap = role < HITS)
    }

    /** Each stage of one pad's print, for the tests that measure the gesture. */
    internal fun stages(kit: Kit, role: Int, velocity: Float, take: Int, order: Mirrorball.Order = Mirrorball.Order.OWNER): Mirrorball.Stages {
        val dry = dryRaw(kit, role, velocity, take)
        val s = settingOf(kit, role)
        return Mirrorball.process(dry, s, kit.bpm, seedOf(kit, role), lengthOf(kit, role, dry, s.reel), order)
    }

    /** How long a print may run: an echoing hit gets its full allowance, a dry-ish one only its own tail. */
    private fun lengthOf(kit: Kit, role: Int, dry: FloatArray, reel: Reel): Int =
        if (role >= HITS) dry.size + (1.2f * Dsp.RATE).toInt()
        else if (reel.on) (HIT_SECONDS * Dsp.RATE).toInt()
        else dry.size + Dsp.RATE / 10

    private fun raw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray = stages(kit, role, velocity, take).out

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
     * gain, and a zone that was also rendered quieter would be turned down twice.
     */
    private fun zoneGain(kit: Kit, role: Int, velocity: Float): Float = gains.getOrPut(Triple(kit, role, velocity)) {
        gainFor(raw(kit, role, velocity, 0), role)
    }

    /** The gain that puts this print's peak at [PAD_PEAK], measured after the output stage. */
    private fun gainFor(raw: FloatArray, role: Int): Float {
        val peak = finish(raw, 1f, ceiling = Float.MAX_VALUE, cap = role < HITS).samples.maxOf { abs(it) }
        return if (peak > 1e-9f) PAD_PEAK / peak else 1f
    }

    /** Where every zone's first take peaks. */
    const val PAD_PEAK = .92f

    private fun finish(x: FloatArray, gain: Float, ceiling: Float = .98f, cap: Boolean): Snip {
        check(x.all { it.isFinite() })
        val mean = x.average().toFloat()
        val dc = Dsp.FixedOnePole(20f)
        var peak = 0f
        for (i in x.indices) {
            val centred = x[i] - mean
            x[i] = (centred - dc.lp(centred)) * gain
            peak = max(peak, abs(x[i]))
        }
        // Safety only: another take may land a little hotter than the first. Never a boost.
        if (peak > ceiling) { val k = ceiling / peak; for (i in x.indices) x[i] *= k; peak = ceiling }
        // Stop 80 dB under the pad's peak, 20 ms later.
        var last = 0
        val floor = max(peak, 1e-6f) * 1e-4f
        for (i in x.indices) if (abs(x[i]) > floor) last = i
        var out = x.copyOf(min(x.size, max(last + (.02f * Dsp.RATE).toInt(), 2048)))
        val capFrames = (HIT_SECONDS * Dsp.RATE).toInt()
        if (cap && out.size >= capFrames) {
            // An echo still sounding at the cap leans out over 150 ms (a raised cosine), never a cut.
            out = out.copyOf(capFrames)
            val f = (CAP_FADE_SECONDS * Dsp.RATE).toInt()
            for (j in 0 until f) out[capFrames - f + j] *= (.5 * (1 + cos(PI * j / f))).toFloat()
        }
        Dsp.fadeTail(out)
        return Snip(out, 1, Dsp.RATE)
    }

    // ---------- sources ----------

    /** Takes sit at -1.5, -0.5, 0.5 and 1.5: four strikes around the authored one, none of them on it. */
    private fun jitter(take: Int): Float = take - 1.5f

    private const val TAKE_STEP = .02f

    private fun varied(m: Map<String, Float>, vary: List<String>, take: Int): Map<String, Float> {
        val out = m.toMutableMap()
        for ((i, name) in vary.withIndex()) {
            val step = jitter(take) * TAKE_STEP * if (i % 2 == 0) 1f else -1f
            out[name] = ((out[name] ?: .5f) + step).coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * SKIN's struck voices (kick, snare, tom) take velocity and take themselves: a real re-strike. A take also
     * moves TONE a small step, as a second stroke lands a little off the first: a soft kick's re-strikes alone
     * came out nearly alike (the Loft kick's soft takes measured 0.004 apart, under the test's 0.005 floor).
     */
    private fun skin(voice: SkinVoice, vararg m: Pair<String, Float>): (Float, Int) -> FloatArray =
        { v, t -> Skin.render(voice, varied(mapOf(*m), listOf("TONE"), t), v, t).samples }

    /**
     * A voice whose engine has no strike velocity. [soft] moves the named controls with velocity, and a softer
     * contact loses its top (a one-pole falling from 18 kHz to 2.5 kHz). A take moves the [vary] controls a
     * little, as a second strike lands elsewhere.
     */
    private fun touched(
        render: (Map<String, Float>, Int) -> FloatArray,
        base: Map<String, Float>,
        vary: List<String>,
        soft: (Float) -> Map<String, Float>,
    ): (Float, Int) -> FloatArray = { v, t ->
        val x = render(varied(base + soft(v), vary, t), t)
        val contact = Dsp.FixedOnePole(2_500f + 15_500f * v)
        FloatArray(x.size) { contact.lp(x[it]) }
    }

    /** SKIN's hats, shaker, ride and stick: re-seeded per take (a fresh texture), softened by [touched]. */
    private fun skinTouched(voice: SkinVoice, base: Map<String, Float>, vary: List<String> = listOf("TUNE", "DECAY"),
                            soft: (Float) -> Map<String, Float> = { emptyMap() }) =
        touched({ m, t -> Skin.render(voice, m, 1f, t, reseedUnstruck = true).samples }, base, vary, soft)

    private fun thump(voice: ThumpVoice, base: Map<String, Float>, vary: List<String> = listOf("TUNE", "DECAY"),
                      soft: (Float) -> Map<String, Float> = { emptyMap() }) =
        touched({ m, _ -> Thump.render(voice, m).samples }, base, vary, soft)

    /** Any kick with the felt beater added at the front ([Mirrorball.beater]), seeded per kit, drum and take. */
    private fun beaten(name: String, kick: (Float, Int) -> FloatArray): (Float, Int) -> FloatArray =
        { v, t -> Mirrorball.beater(kick(v, t), BEATER, Dsp.seedFor("MIRRORBALL", "BEATER", name, t), v) }

    /** THUMP's kick reads velocity itself (less pitch drop, a duller click, less drive). */
    private fun thumpKick(vararg m: Pair<String, Float>): (Float, Int) -> FloatArray =
        { v, t -> Thump.render(ThumpVoice.KICK, varied(mapOf(*m), listOf("TUNE", "CLICK"), t), v).samples }

    /** ONSET's snare ignores its velocity argument (GatecrashKits measured it), so it is touched. Model pinned. */
    private fun onset(voice: OnsetVoice, base: Map<String, Float>, soft: (Float) -> Map<String, Float>) =
        touched({ m, _ -> Onset.render(voice, m, ONSET_MODEL).samples }, base, listOf("TUNE", "SNAP", "EDGE"), soft)

    private const val ONSET_MODEL = 4

    /** TERRA's hand drums are struck at a velocity and re-struck per take: congas, tumbas, bongos. */
    private fun hand(voice: TerraVoice, vararg m: Pair<String, Float>): (Float, Int) -> FloatArray =
        { v, t -> Terra.render(voice, mapOf(*m), null, v, t).samples }

    /**
     * How the tambourine is synthesized. [JINGLES] is round 1b: the fitted jingle resonances struck by a collision
     * train ([MirrorballTambourine]). [TEXTURE] is the recordings' own rattle as band noise ([MirrorballTexture]).
     * [BLEND] is both at equal rms over their first 300 ms, the pitched shimmer over the noise of the rattle (the
     * sines and the residual of Serra and Smith's model). The owner heard round 1b's as short of "a pro sound".
     */
    enum class TambourineModel { JINGLES, TEXTURE, BLEND }

    /** The tambourine the kits print with. */
    val TAMBOURINE = TambourineModel.BLEND

    private fun tambourine(model: TambourineModel = TAMBOURINE): (Float, Int) -> FloatArray = { v, t ->
        when (model) {
            TambourineModel.JINGLES -> MirrorballTambourine.render(v, t)
            TambourineModel.TEXTURE -> MirrorballTexture.render(MirrorballTexture.Voice.TAMBOURINE, v, t)
            TambourineModel.BLEND -> {
                val j = MirrorballTambourine.render(v, t)
                val x = MirrorballTexture.render(MirrorballTexture.Voice.TAMBOURINE, v, t)
                fun rms(a: FloatArray): Double {
                    val m = min(a.size, (.3f * Dsp.RATE).toInt())
                    var e = 0.0
                    for (i in 0 until m) e += a[i].toDouble() * a[i]
                    return kotlin.math.sqrt(e / max(m, 1)).coerceAtLeast(1e-12)
                }
                val kj = .5 / rms(j)
                val kx = .5 / rms(x)
                FloatArray(max(j.size, x.size)) { (kj * j.getOrElse(it) { 0f } + kx * x.getOrElse(it) { 0f }).toFloat() }
            }
        }
    }

    /** A fitted open hat ([MirrorballCymbal]): every take re-strikes all 400 modes. */
    private fun hat(kind: MirrorballCymbal.Kind): (Float, Int) -> FloatArray = { v, t -> MirrorballCymbal.render(kind, v, t, HIT_SECONDS) }

    /** BREAKER's fitted ride, the one the owner accepted ("these are fine", 2026-11-11), dry from its source. */
    private fun ride(): (Float, Int) -> FloatArray = { v, t -> BreakerCymbal.render(BreakerCymbal.Kind.RIDE, v, t, HIT_SECONDS) }

    /**
     * A fitted noise texture ([MirrorballTexture]). The clap was recorded at two dynamics and blends them; a shaker was
     * recorded at one, so its softer zones lose top as a softer shake does (a one-pole falling from 18 to 2.5 kHz).
     */
    private fun texture(voice: MirrorballTexture.Voice, soften: Boolean): (Float, Int) -> FloatArray = { v, t ->
        val x = MirrorballTexture.render(voice, v, t)
        if (!soften) x else {
            val contact = Dsp.FixedOnePole(2_500f + 15_500f * v)
            FloatArray(x.size) { contact.lp(x[it]) }
        }
    }

    /** BACKWASH's recorded tambourine (CC0), kept only as the audition's comparison for the synthesized one. */
    private fun recordedTambourine(): (Float, Int) -> FloatArray = { v, t ->
        val tamb = BackwashVoice.TAMBOURINE
        val tune = .5f + when (t) { 2 -> .025f; 3 -> -.025f; else -> 0f }
        BackwashAcoustic.source(tamb, Backwash.defaults(tamb) + ("TUNE" to tune), v, t % 2).samples
    }

    // ---------- the three kits ----------

    internal fun parts(kit: Kit): List<Part> = when (kit) {
        Kit.PHILLY -> philly
        Kit.LOFT -> loft
        Kit.MUNICH -> munich
    }

    private fun phrases(kit: Kit): List<Phrase> = when (kit) {
        Kit.PHILLY -> phillyPhrases
        Kit.LOFT -> loftPhrases
        Kit.MUNICH -> munichPhrases
    }

    /** Each kit's tape: worn on the loft's, new on the machine kit's. */
    fun tapeOf(kit: Kit): Tape = when (kit) {
        Kit.PHILLY -> Tape(age = .45f, wow = .5f)
        Kit.LOFT -> Tape(age = .62f, wow = .65f, drive = .5f)
        Kit.MUNICH -> Tape(age = .25f, wow = .3f, drive = .35f)
    }

    /** Each kit's amp: clean, a little warmer, and pushed. The pad sets the speaker's roll-off. */
    fun ampOf(kit: Kit): Combo = when (kit) {
        Kit.PHILLY -> Combo(drive = 1.6f, sag = .35f)
        Kit.LOFT -> Combo(drive = 2.2f, sag = .45f)
        Kit.MUNICH -> Combo(drive = 3.2f, sag = .5f)
    }

    private fun named(name: String, p: Part) = Part(name, p.cls, p.chain, p.mute, p.strike)

    // Echo heads: 1 is a sixteenth, 2 a dotted eighth, 3 a quarter. Heads 1 and 2 together are the gallop.
    private val gallop = listOf(1, 2)

    // PHILLY STRINGS. The dry Philadelphia sound: the drums sit under an orchestra, so nothing washes. The kick
    // is dead and clicky and has no echo, so four on the floor stays four. The snare gets one quiet slap at a
    // sixteenth; the hats and tambourine carry the gallop.
    private val philly: List<Part> = listOf(
        // Round 0b: the kick up to about 60 Hz with a hard beater (round 0's sat under 60 Hz, a sub sine with no click).
        // The compressor's attack at its slowest (800 us), so the beater gets through before the squeeze.
        Part("Dead Kick", DrumClass.KICK, Chain(Press(12f, 0f, .85f, Ratio.EIGHT), cabHz = WARM_CAB),
            strike = beaten("philly-kick", skin(SkinVoice.KICK, "TUNE" to .42f, "DECAY" to .30f, "TONE" to .38f, "PUNCH" to .78f, "CLICK" to .85f, "DROP" to .22f))),
        Part("Towel Snare", DrumClass.SNARE, Chain(Press(14f, .55f, .80f), Reel(listOf(1), .16f, .15f)),
            strike = skin(SkinVoice.SNARE, "TUNE" to .52f, "SNAP" to .80f, "DECAY" to .32f, "PUNCH" to .80f, "RATTLE" to .65f, "SIZZLE" to .55f)),
        Part("Closed Hat", DrumClass.HAT_CLOSED, Chain(Press(10f, .60f, .60f), Reel(gallop, .26f, .30f)), mute = 1,
            strike = skinTouched(SkinVoice.HAT_CLOSED, mapOf("TUNE" to .55f, "DECAY" to .22f, "TONE" to .45f)) { v -> mapOf("PUNCH" to .35f + .3f * v) }),
        // Round 2 (the owner: these "need work to more closely model a pro sound", like real recordings): the half-open
        // hat fitted to CC0 recordings, the disco "tss".
        Part("Open Hat", DrumClass.HAT_OPEN, Chain(Press(10f, .60f, .55f), Reel(gallop, .22f, .28f)), mute = 1,
            strike = hat(MirrorballCymbal.Kind.HAT_HALF)),
        Part("Tambourine", DrumClass.PERC, Chain(Press(12f, .65f, .60f), Reel(gallop, .24f, .30f)), strike = tambourine()),
        Part("Cross Stick", DrumClass.PERC, Chain(Press(12f, .65f, .65f), Reel(listOf(1), .12f, .10f)),
            strike = skinTouched(SkinVoice.STICK, mapOf("TUNE" to .50f, "DECAY" to .35f, "BODY" to .45f)) { v -> mapOf("CLICK" to .25f + .4f * v, "PUNCH" to .4f + .3f * v) }),
        Part("Conga", DrumClass.PERC, Chain(Press(10f, .55f, .60f), Reel(listOf(2), .16f, .18f), WARM_CAB),
            strike = hand(TerraVoice.SLAP, "TUNE" to .62f, "DECAY" to .45f, "FORCE" to .9f, "POS" to .8f, "BOWL" to .55f)),
        Part("Tumba", DrumClass.PERC, Chain(Press(10f, .55f, .60f), Reel(listOf(2), .16f, .18f), WARM_CAB),
            strike = hand(TerraVoice.SLAP, "TUNE" to .30f, "DECAY" to .55f, "FORCE" to .85f, "POS" to .7f, "BOWL" to .65f)),
        // Round 0b: a stick attack on the toms (round 0's were pure sines), and a slower compressor attack to keep it.
        Part("Rack Tom", DrumClass.TOM, Chain(Press(12f, .10f, .70f, Ratio.EIGHT), Reel(listOf(2), .18f, .22f), WARM_CAB),
            strike = skin(SkinVoice.TOM, "TUNE" to .62f, "DECAY" to .40f, "TONE" to .60f, "PUNCH" to .72f, "DROP" to .20f, "CLICK" to .70f)),
        Part("Floor Tom", DrumClass.TOM, Chain(Press(12f, .10f, .70f, Ratio.EIGHT), Reel(listOf(2), .18f, .22f), WARM_CAB),
            strike = skin(SkinVoice.TOM, "TUNE" to .30f, "DECAY" to .45f, "TONE" to .60f, "PUNCH" to .72f, "DROP" to .22f, "CLICK" to .70f)),
        Part("Ride", DrumClass.PERC, Chain(Press(8f, .50f, .45f)), strike = ride()),
        Part("Cowbell", DrumClass.PERC, Chain(Press(10f, .60f, .60f), Reel(listOf(2), .14f, .15f)),
            strike = thump(ThumpVoice.COWBELL, mapOf("TUNE" to .45f, "DECAY" to .35f)) { v -> mapOf("TONE" to .35f + .3f * v) }),
        Part("Shaker", DrumClass.PERC, Chain(Press(12f, .70f, .60f), Reel(gallop, .22f, .30f)),
            strike = texture(MirrorballTexture.Voice.SHAKER, soften = true)),
        Part("Ghost Snare", DrumClass.SNARE, Chain(Press(16f, .70f, .75f), Reel(listOf(1), .10f, .10f)),
            strike = skin(SkinVoice.SNARE, "TUNE" to .62f, "SNAP" to .55f, "DECAY" to .20f, "PUNCH" to .45f, "RATTLE" to .70f, "SIZZLE" to .60f)),
    )

    private val phillyPhrases = listOf(
        // Down the toms and congas, landing on the floor tom with the open hat.
        Phrase("Tom Fill", Chain(Press(12f, .10f, .70f, Ratio.EIGHT), Reel(listOf(2), .14f, .18f), WARM_CAB), listOf(
            Note(0.0, 6, .80f), Note(0.5, 6, .70f), Note(0.75, 7, .85f), Note(1.0, 8, .88f), Note(1.5, 8, .78f),
            Note(1.75, 8, .82f), Note(2.0, 9, .90f), Note(2.5, 9, .84f), Note(2.75, 9, .80f), Note(3.0, 7, .92f),
            Note(3.5, 9, 1f), Note(3.5, 3, .8f))),
        Phrase("Snare Build", Chain(Press(14f, .55f, .80f), Reel(listOf(1), .16f, .20f)), build(part = 1)),
    )

    // LOFT PARTY. Percussion first: a clap layered with the snare, three hand drums, two metal-shelled
    // timbales, cowbell and claves. The tape is older and wetter, and the percussion rides two heads.
    private val loftPerc = Chain(Press(10f, .60f, .60f), Reel(listOf(2, 3), .26f, .38f))

    private fun timbale(name: String, tune: Float) = Part(name, DrumClass.PERC, Chain(Press(12f, .65f, .60f), Reel(listOf(2), .22f, .30f)),
        // A shallow metal shell: bright, a fast drop and a hard stick click.
        strike = skin(SkinVoice.TOM, "TUNE" to tune, "DECAY" to .30f, "TONE" to .85f, "PUNCH" to .70f, "DROP" to .10f, "CLICK" to .85f))

    private val loft: List<Part> = listOf(
        Part("Loft Kick", DrumClass.KICK, Chain(Press(12f, 0f, .70f, Ratio.EIGHT), cabHz = WARM_CAB),
            // Not Philly's dead kick: a big open boom for a sound system, lower, longer, the pitch falling more.
            strike = beaten("loft-kick", skin(SkinVoice.KICK, "TUNE" to .12f, "DECAY" to .66f, "TONE" to .55f, "PUNCH" to .55f, "CLICK" to .42f, "DROP" to .32f))),
        Part("Clap Snare", DrumClass.SNARE, Chain(Press(16f, .70f, .70f), Reel(listOf(1, 2), .20f, .22f)),
            strike = { v, t ->
                // A snare and a clap struck together: the clap is the louder half, as on the records.
                val s = Skin.render(SkinVoice.SNARE, mapOf("TUNE" to .50f, "SNAP" to .65f, "DECAY" to .30f, "PUNCH" to .60f, "RATTLE" to .55f), v, t).samples
                // Round 2: the recorded-clap texture, as on the Hand Clap pad, not THUMP's.
                val c = MirrorballTexture.render(MirrorballTexture.Voice.CLAP, v, t)
                FloatArray(max(s.size, c.size)) { .55f * s.getOrElse(it) { 0f } + .8f * c.getOrElse(it) { 0f } }
            }),
        Part("Closed Hat", DrumClass.HAT_CLOSED, Chain(Press(10f, .60f, .60f), Reel(gallop, .30f, .35f)), mute = 1,
            strike = skinTouched(SkinVoice.HAT_CLOSED, mapOf("TUNE" to .62f, "DECAY" to .18f, "TONE" to .55f)) { v -> mapOf("PUNCH" to .35f + .3f * v) }),
        // Round 2: the three-quarter-open hat (a longer sizzle than Philly's) and a recorded clap, both fitted.
        Part("Open Hat", DrumClass.HAT_OPEN, Chain(Press(10f, .60f, .55f), Reel(gallop, .26f, .32f)), mute = 1,
            strike = hat(MirrorballCymbal.Kind.HAT_34)),
        Part("Hand Clap", DrumClass.CLAP, Chain(Press(14f, .70f, .70f), Reel(listOf(1, 2), .24f, .28f)),
            strike = texture(MirrorballTexture.Voice.CLAP, soften = false)),
        Part("Quinto", DrumClass.PERC, loftPerc.copy(cabHz = WARM_CAB),
            strike = hand(TerraVoice.SLAP, "TUNE" to .80f, "DECAY" to .40f, "FORCE" to .95f, "POS" to .9f, "BOWL" to .45f)),
        Part("Conga", DrumClass.PERC, loftPerc.copy(cabHz = WARM_CAB),
            strike = hand(TerraVoice.SLAP, "TUNE" to .55f, "DECAY" to .50f, "FORCE" to .90f, "POS" to .8f, "BOWL" to .55f)),
        Part("Tumba", DrumClass.PERC, loftPerc.copy(cabHz = WARM_CAB),
            strike = hand(TerraVoice.SLAP, "TUNE" to .22f, "DECAY" to .60f, "FORCE" to .85f, "POS" to .7f, "BOWL" to .70f)),
        named("Timbale High", timbale("", .92f)),
        named("Timbale Low", timbale("", .74f)),
        Part("Cowbell", DrumClass.PERC, loftPerc,
            strike = thump(ThumpVoice.COWBELL, mapOf("TUNE" to .55f, "DECAY" to .40f)) { v -> mapOf("TONE" to .4f + .3f * v) }),
        Part("Claves", DrumClass.PERC, loftPerc,
            strike = onset(OnsetVoice.PERC_WOOD, mapOf("TUNE" to .70f, "DECAY" to .30f)) { v -> mapOf("SNAP" to .4f + .4f * v, "EDGE" to .4f + .3f * v) }),
        // Round 2: a different instrument from Philly's, a wooden shaker, fitted.
        Part("Shaker", DrumClass.PERC, Chain(Press(12f, .70f, .60f), Reel(gallop, .28f, .35f)),
            strike = texture(MirrorballTexture.Voice.SHAKER_WOOD, soften = true)),
        Part("Bongo", DrumClass.PERC, loftPerc.copy(cabHz = WARM_CAB),
            strike = hand(TerraVoice.DARBUKA, "TUNE" to .75f, "DECAY" to .35f, "FORCE" to .9f, "POS" to .85f, "SHELL" to .3f, "SNAP" to .6f)),
    )

    private val loftPhrases = listOf(
        // A timbale run in sixteenths, the high drum answering the low, landing on the cowbell.
        Phrase("Timbale Fill", Chain(Press(12f, .65f, .60f), Reel(listOf(2), .20f, .28f)), listOf(
            Note(0.0, 8, .85f), Note(0.25, 8, .65f), Note(0.5, 9, .80f), Note(0.75, 8, .70f), Note(1.0, 9, .88f),
            Note(1.25, 9, .70f), Note(1.5, 8, .90f), Note(1.75, 9, .75f), Note(2.0, 8, .80f), Note(2.25, 8, .70f),
            Note(2.5, 9, .92f), Note(2.75, 9, .82f), Note(3.0, 8, .95f), Note(3.25, 9, .88f), Note(3.5, 10, 1f))),
        // Hand claps tightening into the drop. (A conga build was tried first: sixteen hand-drum strikes cost
        // two seconds a render, which the kit's build time could not carry.)
        Phrase("Clap Build", Chain(Press(14f, .70f, .70f), Reel(listOf(1, 2), .22f, .26f)), build(part = 4)),
    )

    // MUNICH MACHINE. Machine disco: an analog kick on every beat, a sequencer hat riding the dotted-eighth
    // head, four diving synth toms. New tape (less wobble, brighter repeats) and the amp pushed.
    private fun pew(name: String, tune: Float) = Part(name, DrumClass.TOM, Chain(Press(12f, .10f, .55f), Reel(listOf(2, 3), .26f, .40f), WARM_CAB),
        // The synth tom that falls: a sine that dives a long way (SWEEP), slowly enough to hear (BEND).
        strike = thump(ThumpVoice.TOM, mapOf("TUNE" to tune, "SWEEP" to .88f, "DECAY" to .55f, "BEND" to .55f, "PUNCH" to .55f)) { v ->
            mapOf("CLICK" to .1f + .3f * v, "SWEEP" to .6f + .28f * v) })

    private val munich: List<Part> = listOf(
        Part("Machine Kick", DrumClass.KICK, Chain(Press(12f, 0f, .75f, Ratio.EIGHT), cabHz = WARM_CAB),
            // Round 2: CLICK .55 to .75. With the speaker opened, the kick's low end comes through whole, and the beater
            // band fell to 21 dB under the hit's first 15 ms.
            strike = beaten("munich-kick", thumpKick("TUNE" to .35f, "SWEEP" to .50f, "DECAY" to .35f, "CLICK" to .75f, "DRIVE" to .20f, "PUNCH" to .65f))),
        Part("Machine Snare", DrumClass.SNARE, Chain(Press(14f, .70f, .70f), Reel(listOf(1), .14f, .15f)),
            strike = onset(OnsetVoice.SNARE, mapOf("TUNE" to .60f, "DECAY" to .32f, "BLEND" to .40f, "BODY" to .50f)) { v -> mapOf("SNAP" to .45f + .3f * v, "EDGE" to .35f + .3f * v) }),
        Part("Closed Hat", DrumClass.HAT_CLOSED, Chain(Press(10f, .60f, .60f), Reel(listOf(1), .12f, .10f)), mute = 1,
            strike = thump(ThumpVoice.HAT_CLOSED, mapOf("TUNE" to .55f, "DECAY" to .22f, "METAL" to .65f)) { v -> mapOf("NOISE" to .05f + .12f * v) }),
        Part("Open Hat", DrumClass.HAT_OPEN, Chain(Press(10f, .60f, .55f), Reel(listOf(1), .10f, .10f)), mute = 1,
            strike = thump(ThumpVoice.HAT_OPEN, mapOf("TUNE" to .55f, "DECAY" to .40f, "METAL" to .65f)) { v -> mapOf("NOISE" to .05f + .12f * v) }),
        Part("Machine Clap", DrumClass.CLAP, Chain(Press(14f, .70f, .70f), Reel(listOf(2), .18f, .22f)),
            strike = thump(ThumpVoice.CLAP, mapOf("SPREAD" to .45f, "DECAY" to .38f), vary = listOf("SPREAD", "DECAY", "TONE")) { v -> mapOf("TONE" to .4f + .3f * v) }),
        pew("Pew 1", .85f), pew("Pew 2", .65f), pew("Pew 3", .45f), pew("Pew 4", .25f),
        Part("Sequencer Hat", DrumClass.PERC, Chain(Press(10f, .65f, .60f), Reel(listOf(2), .45f, .50f)),
            strike = onset(OnsetVoice.HAT_CLOSED, mapOf("TUNE" to .60f, "DECAY" to .15f)) { v -> mapOf("SNAP" to .4f + .3f * v, "EDGE" to .4f + .3f * v) }),
        Part("Zap", DrumClass.PERC, Chain(Press(10f, .60f, .55f), Reel(listOf(2, 3), .24f, .35f)),
            strike = thump(ThumpVoice.SPARK, mapOf("TUNE" to .55f, "DECAY" to .30f, "COLOR" to .6f)) { v -> mapOf("METAL" to .15f + .3f * v) }),
        Part("Rim", DrumClass.PERC, Chain(Press(12f, .65f, .65f), Reel(listOf(1), .14f, .12f)),
            strike = thump(ThumpVoice.RIM, mapOf("TUNE" to .60f, "DECAY" to .20f), vary = listOf("TUNE", "DECAY", "RING"))),
        Part("Shaker", DrumClass.PERC, Chain(Press(12f, .70f, .60f), Reel(gallop, .20f, .25f)),
            strike = skinTouched(SkinVoice.SHAKER, mapOf("TONE" to .65f, "DECAY" to .25f), vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("GRAIN" to .15f + .3f * v) }),
        Part("Long Kick", DrumClass.KICK, Chain(Press(10f, 0f, .65f, Ratio.FOUR), cabHz = WARM_CAB),
            strike = thumpKick("TUNE" to .28f, "SWEEP" to .55f, "DECAY" to .70f, "CLICK" to .35f, "DRIVE" to .15f)),
    )

    private val munichPhrases = listOf(
        // The pews falling in sixteenths, two passes down the four, landing on the clap.
        Phrase("Pew Fill", Chain(Press(12f, .55f, .55f), Reel(listOf(2, 3), .22f, .35f), WARM_CAB), listOf(
            Note(0.0, 5, .80f), Note(0.25, 6, .78f), Note(0.5, 7, .82f), Note(0.75, 8, .86f),
            Note(1.0, 5, .84f), Note(1.25, 6, .82f), Note(1.5, 7, .88f), Note(1.75, 8, .90f),
            Note(2.0, 5, .90f), Note(2.5, 6, .90f), Note(3.0, 7, .95f), Note(3.5, 8, 1f), Note(3.5, 4, .85f))),
        Phrase("Snare Build", Chain(Press(14f, .70f, .70f), Reel(listOf(1), .16f, .22f)), build(part = 1)),
    )

    /** One bar that tightens from eighths to sixteenths to thirty-seconds and grows louder as it goes. */
    private fun build(part: Int): List<Note> {
        val beats = listOf(0.0, .5, 1.0, 1.5, 2.0, 2.25, 2.5, 2.75) + List(8) { 3.0 + it * .125 }
        return beats.mapIndexed { i, b -> Note(b, part, .35f + .65f * i / beats.lastIndex) }
    }
}
