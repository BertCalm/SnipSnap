package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Cypher.Note
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tanh

/**
 * STAGGER: authored drum kits after the owner's three-part producer profile, a kit family in the [CypherKits]
 * shape (docs/superpowers/specs/2026-11-18-stagger-drum-kits-brief.md, docs/STAGGER.md). Round 1 ships one kit,
 * [Kit.CARDBOARD], and the candidates for the owner's ear; the family's other kits wait for that listen.
 *
 * The family's gesture: a muffled thud with no click → a dry crack right on top of it → a hat you can barely
 * hear, or none → the clock drags and catches up → a cartoon lands where a fill would.
 *
 * What is new on the kit side, in order of risk:
 * - [muffled]: a kick or tom clipped *before* a two-pole low-pass, so the harmonics the clipper makes under the
 *   cutoff survive and a phone still hears the drum (measured in the prototype: at 250 to 400 Hz the 150 to
 *   400 Hz share stays over one percent with the drive on and halves without it).
 * - [Cypher.staggered]: one drift shared by every part plus a bias per part, the drunk clock.
 * - [foley]: an effect sampled by a 12-bit converter at 26 kHz *and then* played slow, the sampler's order, which
 *   the rack cannot express (PITCH runs before CRUNCH there), so the converter's images come down into the band a
 *   phone plays.
 * - [glass]: a pane breaking, which no engine makes: tiny modal shards in a scattered cascade.
 * - [punch]: a comic-book punch, a rising whoosh that lands on a thud 110 ms later.
 *
 * Round 1b: the owner heard round 1 as "too much like drum machine sounds". Round 1's drums were THUMP's and SKIN's:
 * a swept sine is a drum machine by construction, and an isolated synth hit has no shell, wires, bleed or room in
 * it, where the profile's drums are chopped off records. Round 1b sources every drum from EMBER (calfskin heads
 * with sag, threshold wires, hats and ride fitted to recordings, the club's response and a warm chain; the owner
 * approved its voices by ear) and keeps the muffle, the foley print and the clock. [sliced] is the alternative in
 * SHATTER's manner: the kit played in its room and cut at a hit, so the pad carries its neighbours' bleed.
 *
 * Every pad has three velocity zones of four takes; a zone is its own strike and a take a fresh one. Pads 1 to
 * 14 are hits, pad 15 a timpani roll into the gong, pad 16 a two-bar groove, bar-exact so it loops, played as one
 * kit in one small room with a bus compressor ([LooseCrateKits.Room], reused, not copied) because the owner heard a
 * dry sum of isolated hits as "a bit drum machine like" on LOOSE CRATE.
 *
 * Chokes: hats cut hats (1), the roll and the groove cut each other (2).
 *
 * Every setting is a shape value aimed at the measured references (the brief's table), authored unheard.
 */
object StaggerKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 5

    enum class Kit(val id: String, val title: String, val bpm: Int) {
        CARDBOARD("stagger-cardboard", "Cardboard", 89),
        MATINEE("stagger-matinee", "Matinee", 84),
        STUMBLE("stagger-stumble", "Stumble", 93),
    }

    val velocities = listOf(.30f, .65f, 1f)
    const val TAKES = 4
    const val HITS = 14
    const val PADS = 16
    const val ROLL = 14
    const val GROOVE = 15
    const val GROOVE_BARS = 2

    const val HAT_GROUP = 1
    const val PHRASE_GROUP = 2

    /** Where every zone's first take peaks, as CYPHER's and LOOSE CRATE's do. */
    const val PAD_PEAK = .92f

    /** The level every pad is fed to its print at: CRUNCH and TAPE match their input's peak and then clamp. */
    const val HEADROOM = .9f

    // ---------- the laws ----------

    /** The drunk clock's numbers: a bias per limb, the drift's spread and how slowly it breathes ([Cypher.staggered]). */
    class Clock(val kickMs: Float, val snareMs: Float, val hatMs: Float, val wobbleMs: Float, val rho: Float, val seed: String) {
        internal fun play(notes: List<Note>, kick: Set<Int>, snare: Set<Int>, hat: Set<Int>): List<Note> {
            val bias = HashMap<Int, Float>()
            for (p in kick) bias[p] = kickMs
            for (p in snare) bias[p] = snareMs
            for (p in hat) bias[p] = hatMs
            return Cypher.staggered(notes, bias, wobbleMs, rho, seed)
        }
    }

    /**
     * MUFFLE: the strike through a soft clipper and then two one-poles at [cutoffHz] (12 dB per octave). The
     * clipper first, because a filter can only remove: it makes the kick's harmonics at two, three and four times
     * the root, the filter takes the click and the top, and what the filter leaves is a thud a phone can play. The
     * clipper runs at the output rate: its harmonics on a kick under 100 Hz sit far below Nyquist.
     */
    fun muffled(strike: Strike, cutoffHz: Float, drive: Float): Strike = { v, t, semis ->
        val x = strike(v, t, semis)
        val peak = KitStrikes.peakOf(x).let { if (it > 1e-9f) it else 1f }
        val norm = tanh(drive)
        val a = Dsp.FixedOnePole(cutoffHz)
        val b = Dsp.FixedOnePole(cutoffHz)
        FloatArray(x.size) { b.lp(a.lp(tanh(drive * x[it] / peak) / norm)) }
    }

    /**
     * FOLEY: the strike sampled at 12 bits and 26.04 kHz with no anti-alias filter (`Eras` sp1200), *then* played
     * [semitones] down (`Speed`): a sampler's order. The converter's images sit near 18 kHz at the house rate and
     * come down with the pitch (12 kHz at -7), which is the grain the profile calls "metallic". The source is faded
     * out at [capSeconds] before the pitch move so the pad stays under the classifier's 1.5 s loop floor.
     */
    fun foley(strike: Strike, semitones: Int, capSeconds: Float = 1f): Strike = { v, t, semis ->
        require(semitones in -12..0)
        val x = strike(v, t, semis)
        // The pitch move lengthens the pad by 2^(-semitones/12): the cap shrinks so the pad lands under 1.4 s at any
        // pitch (the Low Timpani at -9 measured 1.62 s with a flat 1 s cap, over the classifier's loop floor).
        val cap = (min(capSeconds, 1.4f / 2f.pow(-semitones / 12f)) * Dsp.RATE).toInt()
        val capped = if (x.size > cap) x.copyOf(cap).also { Dsp.fadeTailCosine(it, .25f) } else x
        val converted = Eras.process("sp1200", Snip(capped, 1, Dsp.RATE))
        Speed.process(converted, mapOf("SEMITONES" to .5f + semitones / (2f * Speed.SEMITONE_RANGE))).samples
    }

    /**
     * GLASS: a pane breaking. [shards] tiny modal objects, each three modes between 2.2 and 8.8 kHz ringing 20 to
     * 220 ms; the break is the first three inside 2 ms, the fall the rest, scattered over about 120 ms with a
     * density that thins and shards that shrink. Every shard's pitch, ring, size and time comes from the take's
     * seed, so a take is another pane. Velocity sets how many shards fall (a softer break is a smaller pane) and
     * how hard the first ones are hit. Rendered at the output rate: the resonators are linear and the excitation is
     * a seven-sample pulse, so nothing aliases.
     */
    fun glass(shards: Int = 28, seconds: Float = .7f): Strike = { v, t, _ ->
        val n = (seconds * Dsp.RATE).toInt()
        val noise = Dsp.Noise(Dsp.seedFor("STAGGER", "glass", t))
        val out = FloatArray(n)
        val count = max(6, Math.round(shards * (.5f + .5f * v)))
        for (s in 0 until count) {
            val at: Float
            if (s < 3) at = .0007f * s
            else {
                val u = (noise.next() + 1f) / 2f
                at = (.002f + .12f * (-kotlin.math.ln(max(1e-6f, 1f - u)) / 3f)).coerceAtMost(.3f)
            }
            val base = 2_200f * 2f.pow(2f * (noise.next() + 1f) / 2f)
            val t60 = .02f + .2f * ((noise.next() + 1f) / 2f).pow(2)
            var gain = (.35f + .65f * (noise.next() + 1f) / 2f) * (.6f + .4f * v)
            if (s >= 3) gain *= kotlin.math.exp(-at / .08f)
            val r2 = 1.45f + .4f * (noise.next() + 1f) / 2f
            val r3 = 2.1f + .8f * (noise.next() + 1f) / 2f
            val strike = FloatArray(n)
            val start = (at * Dsp.RATE).toInt()
            val width = 7
            for (i in 0 until width) if (start + i < n) strike[start + i] = .5f * (1f - kotlin.math.cos(2f * Math.PI.toFloat() * i / width))
            val ring = Modes.ring(strike, base, listOf(Modes.Mode(1f, 1f, t60), Modes.Mode(r2, .6f, t60 * .7f), Modes.Mode(r3, .35f, t60 * .5f)), Dsp.RATE)
            val peak = KitStrikes.peakOf(ring).let { if (it > 1e-9f) it else 1f }
            for (i in 0 until n) out[i] += gain * ring[i] / peak
        }
        out
    }

    /**
     * PUNCH: a comic-book punch. A noise whoosh rises for 130 ms through a low-pass that falls from 3 kHz to 550 Hz
     * (the swing), then a short high thud lands at [thudMs]. The pad's onset is the swing, not the hit, which is
     * the cartoon; [thud] is any strike. Every take reseeds the whoosh and re-strikes the thud.
     */
    fun punch(thud: Strike, thudMs: Float = 110f): Strike = { v, t, semis ->
        val whooshN = (.13f * Dsp.RATE).toInt()
        val noise = Dsp.Noise(Dsp.seedFor("STAGGER", "punch", t))
        val lp = Dsp.OnePole()
        val hp = Dsp.FixedOnePole(300f)
        val whoosh = FloatArray(whooshN) { i ->
            val p = i.toFloat() / whooshN
            val env = (.05f + .95f * p).pow(2)
            val raw = noise.next() * env
            val cutoff = 3_000f * (1f - p) + 550f * p
            val low = lp.lp(raw, cutoff)
            low - hp.lp(low)
        }
        val wPeak = KitStrikes.peakOf(whoosh).let { if (it > 1e-9f) it else 1f }
        val hit = thud(v, t, semis)
        val hPeak = KitStrikes.peakOf(hit).let { if (it > 1e-9f) it else 1f }
        val at = (thudMs * Dsp.RATE / 1000f).toInt()
        val out = FloatArray(max(whooshN, at + hit.size))
        for (i in whoosh.indices) out[i] += .45f * (.5f + .5f * v) * whoosh[i] / wPeak
        for (i in hit.indices) out[at + i] += hit[i] / hPeak
        out
    }

    // ---------- the print ----------

    /**
     * The kit's print, in the order the records were made: sampled into a converter (CRUNCH, 12 bits at about
     * 26 kHz, dark), the hits pressed at the mix (COMPRESSOR, a fast light press that catches the peak without
     * bringing the room forward; rounds 2b and 2c), bounced to tape (TAPE), the record's surface under the
     * phrases only (VINYL). A foley pad was already converted by its own print and skips CRUNCH, so its grain is
     * not doubled at a second rate; it is pressed like every hit. The phrases are not pressed here: their bus is
     * their press.
     */
    class Print(val name: String, val crunch: Map<String, Float>, val tape: Map<String, Float>, val grooveVinyl: Map<String, Float>?,
                val compressor: Map<String, Float>? = null) {
        fun apply(x: FloatArray, phrase: Boolean, converted: Boolean): FloatArray {
            val peak = x.maxOfOrNull { abs(it) } ?: 0f
            val k = if (peak > 1e-9f) HEADROOM / peak else 1f
            var s = Snip(FloatArray(x.size) { x[it] * k }, 1, Dsp.RATE)
            if (!converted) s = Crunch.process(s, crunch)
            // The hits are pressed; a phrase already went through its room's bus, and a second press on top brought
            // the room between the hits up 11 dB (round 2b, heard as "echoy").
            if (compressor != null && !phrase) s = Compressor.process(s, compressor)
            s = Tape.process(s, tape)
            if (phrase && grooveVinyl != null) s = Vinyl.process(s, grooveVinyl)
            return s.samples
        }
    }

    /**
     * "A bit of compression" (round 2b), then "it made it echoy" (round 2c, the owner, 2026-11-20). The sustain
     * compressor presses the body and the peak match lifts everything under it, and under an EMBER drum that is
     * the club's response: round 2b's settings (AMOUNT .40, ATTACK .25, RECOVER .45) lifted the snares' tails
     * 12 to 14 dB and a groove's quiet moments 11 dB, which is the room coming forward. Measured on the kick,
     * the snare and the groove before choosing again: a fast attack still catches the peak, and a short recovery
     * with less amount (RECOVER .20, AMOUNT .25) takes the snares' crest down 0.7 to 2.3 dB while their tails
     * rise only 4 to 6 dB and the kicks' tails stay put. The grooves take no second press: their bus is their
     * press, and the print's on top is what made the room ring between the hits. Shape.
     */
    val PRESS = mapOf("AMOUNT" to .25f, "ATTACK" to .15f, "RECOVER" to .20f)

    /**
     * CARDBOARD's print: 12 bits at 26 kHz (RATE .37 lands CRUNCH's hold at 26.0 kHz on its exponential law), the
     * converter's TONE at about 7.9 kHz (at .45, a 4.4 kHz low-pass, the groove's hat band measured 35 dB under the
     * mix against the references' 16 to 35), a worn tape, a light record under the groove. The darkness the
     * references show (eight put 95 percent of their energy under 309 to 635 Hz) is the kick's weight, not a
     * missing top: the groove's ceiling measured 360 Hz with either TONE.
     */

    val BOX = Print("box",
        mapOf("BITS" to .45f, "RATE" to .37f, "TONE" to .72f, "GRIT" to .05f),
        mapOf("WOBBLE" to .06f, "DRIVE" to .15f, "AGE" to .55f),
        mapOf("CRACKLE" to .15f, "RUMBLE" to .40f, "HISS" to .20f),
        compressor = PRESS)


    /** MATINEE's print: the same converter, a cleaner tape (the foley carries its own grain), the record under the phrases. */
    val REEL = Print("reel",
        mapOf("BITS" to .45f, "RATE" to .37f, "TONE" to .78f, "GRIT" to .05f),
        mapOf("WOBBLE" to .05f, "DRIVE" to .12f, "AGE" to .45f),
        mapOf("CRACKLE" to .20f, "RUMBLE" to .35f, "HISS" to .25f),
        compressor = PRESS)

    /** STUMBLE's print: the tape driven and worn, more record under the groove: the loosest of the three. */
    val WORN = Print("worn",
        mapOf("BITS" to .50f, "RATE" to .40f, "TONE" to .65f, "GRIT" to .08f),
        mapOf("WOBBLE" to .10f, "DRIVE" to .30f, "AGE" to .80f),
        mapOf("CRACKLE" to .25f, "RUMBLE" to .55f, "HISS" to .25f),
        compressor = PRESS)

    fun printOf(kit: Kit): Print = when (kit) { Kit.CARDBOARD -> BOX; Kit.MATINEE -> REEL; Kit.STUMBLE -> WORN }

    // ---------- the pads ----------

    /** One playable hit: its name, class, choke group, whether its own print already converted it, and how to strike it dry. */
    internal class Part(val name: String, val cls: DrumClass, val mute: Int = 0, val converted: Boolean = false, val strike: Strike)

    /** A phrase pad: notes on the kit's grid. [loop] folds the tail back onto the start and keeps the bars exact. */
    internal class Phrase(val name: String, val notes: List<Note>, val loop: Boolean)

    fun touch(velocity: Float): Float = CypherKits.touch(velocity)

    fun roles(kit: Kit): List<String> = parts(kit).map { it.name } + phrases(kit).map { it.name }

    fun muteGroup(kit: Kit, role: Int): Int = if (role < HITS) parts(kit)[role].mute else PHRASE_GROUP

    fun classOf(kit: Kit, role: Int): DrumClass = if (role < HITS) parts(kit)[role].cls else DrumClass.LOOP

    fun build(kit: Kit, renderer: (Kit, Int, Float, Int) -> Snip = ::render): List<com.snipsnap.kit.ArrangedPad> {
        val names = roles(kit)
        return List(PADS) { role ->
            val grid = velocities.map { v -> List(TAKES) { t -> renderer(kit, role, v, t) } }
            com.snipsnap.kit.ArrangedPad(
                grid.last().first(), classOf(kit, role), displayName = names[role], velocityTakes = grid,
                muteGroup = muteGroup(kit, role),
                source = mapOf(
                    "engine" to "STAGGER", "kitVersion" to VERSION.toString(), "phraseVersion" to Cypher.VERSION.toString(),
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

    /** The same pad with no kit print, levelled on its own: the audition's dry comparison (a muffle or a foley print stays, it is the drum). */
    fun dry(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        val x = dryRaw(kit, role, velocity, take)
        val peak = KitStrikes.peakOf(x)
        return if (role == GROOVE) looped(x, if (peak > 1e-9f) PAD_PEAK / peak else 1f)
        else KitStrikes.finish(x, if (peak > 1e-9f) PAD_PEAK / peak else 1f)
    }

    fun grooveFrames(kit: Kit): Int = Math.round(GROOVE_BARS * 4 * 60.0 / kit.bpm * Dsp.RATE).toInt()

    private const val TAIL_SECONDS = 1.5f
    private const val LOOP_TAIL_SECONDS = 2.5f
    private const val SEAM_SECONDS = .02f

    internal fun dryRaw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val parts = parts(kit)
        if (role < HITS) return KitStrikes.struck(parts[role].strike, velocity, take, touch(velocity))
        val phrase = phrases(kit)[role - HITS]
        return if (!phrase.loop) rolled(kit, phrase.notes, velocity, take) else grooveRaw(kit, played(kit, phrase.notes), velocity, take)
    }

    private fun rolled(kit: Kit, notes: List<Note>, velocity: Float, take: Int): FloatArray {
        val end = notes.maxOf { Cypher.startFrame(it, kit.bpm) }
        val raw = Cypher.phraseRaw(notes, kit.bpm, velocity, take, TAKES, end.toFloat() / Dsp.RATE + TAIL_SECONDS, ::touch) { strikeOf(kit, it) }
        return roomOf(kit).play(raw)
    }

    /** The groove's notes, already played through the clock and the room's dynamics, as a bar-exact loop. */
    internal fun grooveRaw(kit: Kit, notes: List<Note>, velocity: Float, take: Int): FloatArray {
        val frames = grooveFrames(kit)
        val raw = Cypher.phraseRaw(notes, kit.bpm, velocity, take, TAKES, frames.toFloat() / Dsp.RATE + LOOP_TAIL_SECONDS, ::touch) { strikeOf(kit, it) }
        val roomed = roomOf(kit).play(raw)
        val out = FloatArray(frames)
        for (i in roomed.indices) out[i % frames] += roomed[i]
        return out
    }

    /** The part number the snare wires play on in a groove: past the fourteen hits, so it never names a pad. */
    const val WIRES = 100

    internal fun strikeOf(kit: Kit, part: Int): Strike = if (part == WIRES) LooseCrateKits.wires else parts(kit)[part].strike

    /**
     * CARDBOARD's room, round 2: small and dry (the references' gaps are clean against LOOSE CRATE HUMMIN's), the bus
     * breathing, a -3 dB shelf at 1.2 kHz (the mixes are dark), the hats at their written weights (.75 and .5 against
     * the snare's 1; at a quarter of that, with the hat's own contact closing on a quiet hit, the groove's 6 to 14 kHz
     * measured 35 dB under the mix, and at .65 still 32; the references sit 16 to 35, median 23). "Whisper" is the
     * hat's short, dark timbre and its place under the kick, not silence.
     */
    fun roomOf(kit: Kit): LooseCrateKits.Room = when (kit) {
        // Round 2d: no ROOM on any groove ("it sounds like room reverb"); the bus and the dark shelf stay, and the
        // drummer's dynamics and the wires under the kicks are what keep a dry sum from reading as a machine.
        Kit.CARDBOARD -> LooseCrateKits.Room(null,
            mapOf("AMOUNT" to .40f, "ATTACK" to .80f, "RECOVER" to .45f),
            -3f, kickCurve = 2.5f, wireWeight = .08f, hatLevel = 1f, ringBarEnd = false, ride = null)
        // The cinematic kit: its foley carries its own hall; the drums are as dry as CARDBOARD's.
        Kit.MATINEE -> LooseCrateKits.Room(null,
            mapOf("AMOUNT" to .35f, "ATTACK" to .80f, "RECOVER" to .50f),
            -3f, kickCurve = 2.5f, wireWeight = .08f, hatLevel = 1f, ringBarEnd = false, ride = null)
        // The drunkest kit: the bus pressing harder, no hats to level.
        Kit.STUMBLE -> LooseCrateKits.Room(null,
            mapOf("AMOUNT" to .50f, "ATTACK" to .75f, "RECOVER" to .40f),
            -4f, kickCurve = 3f, wireWeight = .10f, hatLevel = 1f, ringBarEnd = false, ride = null)
    }

    fun clockOf(kit: Kit): Clock = when (kit) {
        // The references' backbeats sit 5 to 13 ms from the grid and show no drift; the profile says the kick drags
        // and the snare snaps early. Shape: between the two, and the page plays three widths and three rhos.
        Kit.CARDBOARD -> Clock(kickMs = 12f, snareMs = -3f, hatMs = 8f, wobbleMs = 10f, rho = .4f, seed = "cardboard")
        Kit.MATINEE -> Clock(kickMs = 10f, snareMs = -3f, hatMs = 6f, wobbleMs = 8f, rho = .4f, seed = "matinee")
        // The drunkest clock: the kick drags most, the lag breathes widest and slowest.
        Kit.STUMBLE -> Clock(kickMs = 18f, snareMs = -3f, hatMs = 10f, wobbleMs = 16f, rho = .55f, seed = "stumble")
    }

    /** A groove's notes as the drummer plays them: the clock, then the room's kick dynamics, wires and hat level. */
    internal fun played(kit: Kit, notes: List<Note>, clock: Clock = clockOf(kit)): List<Note> {
        val parts = parts(kit)
        val kicks = parts.indices.filter { parts[it].cls == DrumClass.KICK }.toSet()
        val snares = parts.indices.filter { parts[it].cls == DrumClass.SNARE }.toSet()
        val hats = parts.indices.filter { parts[it].cls == DrumClass.HAT_CLOSED || parts[it].cls == DrumClass.HAT_OPEN || parts[it].name == "Shaker" }.toSet()
        val room = roomOf(kit)
        return buildList {
            for (n in clock.play(notes, kicks, snares, hats)) {
                when (n.part) {
                    in kicks -> {
                        val w = n.weight.pow(room.kickCurve)
                        add(Note(n.beat, n.part, w, n.offsetMs, n.tuneSemis))
                        add(Note(n.beat, WIRES, (w * room.wireWeight).coerceAtLeast(.05f), n.offsetMs + 2f))
                    }
                    in hats -> add(Note(n.beat, n.part, n.weight * room.hatLevel, n.offsetMs, n.tuneSemis))
                    else -> add(n)
                }
            }
        }
    }

    private fun printed(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray {
        val x = dryRaw(kit, role, velocity, take)
        val print = printOf(kit)
        val converted = role < HITS && parts(kit)[role].converted
        if (role != GROOVE) return print.apply(x, phrase = role >= HITS, converted = converted)
        // The loop is printed twice end to end and the second pass kept, its last SEAM_SECONDS crossfaded into the
        // first pass's, so the printed loop closes on itself (LOOSE CRATE round 1's lesson: tape wobble is not periodic).
        val n = x.size
        val p = print.apply(FloatArray(2 * n) { x[it % n] }, phrase = true, converted = false)
        val fade = (SEAM_SECONDS * Dsp.RATE).toInt()
        return FloatArray(n) { j ->
            if (j < n - fade) p[n + j]
            else { val w = (j - (n - fade) + 1).toFloat() / fade; p[n + j] * (1f - w) + p[j] * w }
        }
    }

    private fun looped(x: FloatArray, gain: Float): Snip {
        val mean = x.average().toFloat()
        return Snip(FloatArray(x.size) { ((x[it] - mean) * gain).coerceIn(-.98f, .98f) }, 1, Dsp.RATE)
    }

    private val gains = ConcurrentHashMap<Triple<Kit, Int, Float>, Float>()

    private fun zoneGain(kit: Kit, role: Int, velocity: Float): Float = gains.getOrPut(Triple(kit, role, velocity)) {
        val x = printed(kit, role, velocity, 0)
        val peak = if (role == GROOVE) KitStrikes.peakOf(x.let { y -> val m = y.average().toFloat(); FloatArray(y.size) { y[it] - m } })
        else KitStrikes.finish(x, 1f, ceiling = Float.MAX_VALUE).peak()
        if (peak > 1e-9f) PAD_PEAK / peak else 1f
    }

    // ---------- the strikes ----------

    /** A SKIN hat with a dark contact: two poles at 2.5 kHz on a soft hit rising to 11.5 kHz, a short ring; a take reseeds its noise. */
    private fun hat(open: Boolean, tune: Float, decay: Float, decaySoft: Float, tone: Float): Strike = { v, t, _ ->
        val m = KitStrikes.varied(mapOf("TUNE" to tune, "DECAY" to decaySoft + (decay - decaySoft) * v, "TONE" to tone, "PUNCH" to .25f + .3f * v), listOf("TUNE", "DECAY"), t)
        val x = Skin.render(if (open) SkinVoice.HAT_OPEN else SkinVoice.HAT_CLOSED, m, 1f, t, reseedUnstruck = true).samples
        val hz = 5_000f + 9_000f * v
        val a = Dsp.FixedOnePole(hz)
        val b = Dsp.FixedOnePole(hz)
        FloatArray(x.size) { b.lp(a.lp(x[it])) }
    }

    private fun skinTouched(voice: SkinVoice, vararg m: Pair<String, Float>, vary: List<String> = listOf("TUNE", "DECAY"), soft: (Float) -> Map<String, Float> = { emptyMap() }): Strike =
        KitStrikes.skinTouched(voice, mapOf(*m), vary, soft = soft)

    /** FIRED BRONZE's dark gong, struck dry at the velocity and take, its TOUCH following the hit. */
    private fun gong(tune: Float): Strike = { v, t, _ ->
        // A take also moves the tuning and the touch a little: a soft gong's re-seeded strikes alone measured 0.0026
        // apart, under the family's 0.005 (the LOOSE CRATE doum's lesson).
        val base = FiredBronze.defaults() + mapOf("TUNE" to tune, "TOUCH" to .35f + .4f * v, "TRAIL" to .4f)
        FiredBronze.Recipe(FiredBronze.Part.KEMPUL, if (t == 0) base else KitStrikes.varied(base, listOf("TUNE", "TOUCH"), t, KitStrikes.SKIN_TAKE_STEP),
            velocity = v, stage = FiredBronze.Stage.DRY, take = t).render().samples
    }

    /** THUMP's kick as a kick usually is, click and all: the muffle's input, and the open comparison on the page. */
    internal val boxKickOpen: Strike = KitStrikes.thumpKick("TUNE" to .30f, "SWEEP" to .45f, "DECAY" to .55f, "CLICK" to .50f, "DRIVE" to .20f, "PUNCH" to .50f)

    /** The Box Kick at another cutoff, for the page (the kit's is 300 Hz, drive 3). */
    internal fun boxKickAt(cutoffHz: Float, drive: Float = 3f): Strike = muffled(boxKickOpen, cutoffHz, drive)

    internal val boxKick: Strike = boxKickAt(300f)
    internal val lowBoxKick: Strike = muffled(KitStrikes.skinStruck(SkinVoice.KICK, "TUNE" to .22f, "DECAY" to .60f, "TONE" to .30f, "PUNCH" to .45f, "CLICK" to .20f, "DROP" to .20f), 250f, 3f)
    /** The crack: SKIN's snare with its sizzle down and two poles at [crackHz], since the bare snare measured 10.8 kHz over its first 120 ms against the references' median 3.2. */
    internal val crackSnare: Strike = contact(KitStrikes.skinStruck(SkinVoice.SNARE, "TUNE" to .50f, "SNAP" to .60f, "DECAY" to .30f, "PUNCH" to .65f, "RATTLE" to .55f, "SIZZLE" to .20f), 4_500f)

    /** Two one-poles at [hz] over a strike: a drum heard through cardboard rather than a condenser. */
    private fun contact(strike: Strike, hz: Float): Strike = { v, t, semis ->
        val x = strike(v, t, semis)
        val a = Dsp.FixedOnePole(hz)
        val b = Dsp.FixedOnePole(hz)
        FloatArray(x.size) { b.lp(a.lp(x[it])) }
    }
    internal val paperSnare: Strike = contact(KitStrikes.skinStruck(SkinVoice.SNARE, "TUNE" to .78f, "SNAP" to .70f, "DECAY" to .15f, "PUNCH" to .60f, "RATTLE" to .35f, "SIZZLE" to .20f), 6_000f)
    internal val whisperHat: Strike = hat(open = false, tune = .30f, decay = .15f, decaySoft = .08f, tone = .65f)
    internal val deadTom: Strike = muffled(KitStrikes.skinStruck(SkinVoice.TOM, "TUNE" to .30f, "DECAY" to .35f, "TONE" to .30f, "DROP" to .20f), 400f, 2f)
    /** A kettle drum: SKIN's tom at the bottom of its range ringing long, then the foley print five semitones down. */
    internal val timpaniDry: Strike = KitStrikes.skinStruck(SkinVoice.TOM, "TUNE" to .10f, "DECAY" to .85f, "TONE" to .25f, "DROP" to .05f)
    internal val timpani: Strike = foley(timpaniDry, -5)
    internal val gongDry: Strike = gong(.40f)
    internal val darkGong: Strike = foley(gongDry, -5)
    internal val zapDry: Strike = KitStrikes.tines(TinesVoice.ZAP, mapOf("TUNE" to .55f, "DROP" to .70f, "BRIGHT" to .50f, "DECAY" to .40f))
    internal val zap: Strike = foley(zapDry, -7)
    internal val punchDry: Strike = punch(KitStrikes.thumpKick("TUNE" to .80f, "SWEEP" to .60f, "DECAY" to .20f, "CLICK" to .30f, "DRIVE" to .30f, "PUNCH" to .60f))
    internal val punchHit: Strike = foley(punchDry, -5)
    internal val glassDry: Strike = glass()
    /** GLASS at a pitch, for the page; it is not on CARDBOARD (the cinematic kit's pad). */
    internal fun glassAt(semitones: Int): Strike = foley(glassDry, semitones)

    /**
     * An EMBER drum struck at the velocity and take (the engine reads both itself: a softer stroke, another strike),
     * heard dry. Round 2d ("it sounds like room reverb", the owner, 2026-11-20): every EMBER hit carries the club's
     * response, 16 dB under the direct sound even at DISTANCE 0 and ringing 0.9 s, and under a snare that is a room
     * 35 dB down a quarter-second after the hit. So a kit drum is rendered at DISTANCE 0 (the close mic) and its
     * tail gated to silence past the drum's own decay (HUSH at its tap, FLOOR .5 and CLOSE .5: measured, the snare
     * keeps its first 250 ms as it was and nothing after), which is what a tightly chopped record drum is. The
     * foley keeps its distance and its tail ([dry] false): a timpani in a hall is the point of it.
     */
    private fun ember(voice: EmberVoice, vararg m: Pair<String, Float>, dry: Boolean = true): Strike = { v, t, _ ->
        val macros = if (dry) mapOf(*m) + ("DISTANCE" to 0f) else mapOf(*m)
        val x = Ember.render(voice, macros, v, t)
        if (dry) Hush.process(x, GATE).samples else x.samples
    }

    /** HUSH at its tap: the club's tail shut past the drum's decay. Shape. */
    val GATE = mapOf("FLOOR" to .5f, "CLOSE" to .5f)

    // Round 1b: the kit's drums, EMBER's, in the club at a short distance and through its warm chain.
    internal val emberKickOpen: Strike = ember(EmberVoice.KICK, "TUNE" to .35f, "DAMP" to .55f, "BUZZ" to .30f, "DISTANCE" to .30f, "WARMTH" to .50f)
    internal fun emberKickAt(cutoffHz: Float, drive: Float = 3f): Strike = muffled(emberKickOpen, cutoffHz, drive)
    internal val emberKick: Strike = emberKickAt(300f)
    internal val emberLowKick: Strike = muffled(ember(EmberVoice.KICK, "TUNE" to .15f, "DAMP" to .70f, "BUZZ" to .20f, "DISTANCE" to .30f, "WARMTH" to .55f), 250f, 3f)
    internal val emberSnare: Strike = contact(ember(EmberVoice.SNARE, "TUNE" to .50f, "DAMP" to .45f, "BUZZ" to .60f, "DISTANCE" to .30f, "WARMTH" to .55f), 5_500f)
    internal val emberPaper: Strike = contact(ember(EmberVoice.SNARE, "TUNE" to .70f, "DAMP" to .80f, "BUZZ" to .45f, "DISTANCE" to .25f, "WARMTH" to .50f), 6_000f)
    internal val emberHat: Strike = ember(EmberVoice.HAT_CLOSED, "TUNE" to .45f, "DAMP" to .75f, "DISTANCE" to .30f, "WARMTH" to .50f)
    internal val emberRim: Strike = ember(EmberVoice.RIMSHOT, "TUNE" to .55f, "DAMP" to .50f, "BUZZ" to .40f, "DISTANCE" to .30f, "WARMTH" to .50f)
    internal val emberStick: Strike = ember(EmberVoice.CROSS_STICK, "TUNE" to .55f, "DAMP" to .40f, "BUZZ" to .30f, "DISTANCE" to .30f, "WARMTH" to .50f)
    internal val emberShaker: Strike = ember(EmberVoice.SHAKER, "TUNE" to .40f, "DAMP" to .50f, "DISTANCE" to .30f, "WARMTH" to .50f)
    internal val emberDeadTom: Strike = muffled(ember(EmberVoice.TOM_FLOOR, "TUNE" to .35f, "DAMP" to .85f, "BUZZ" to .20f, "DISTANCE" to .30f, "WARMTH" to .50f), 400f, 2f)
    internal val emberTimpani: Strike = foley(ember(EmberVoice.MALLET_TOM, "TUNE" to .05f, "DAMP" to .15f, "BUZZ" to 0f, "DISTANCE" to .45f, "WARMTH" to .40f, dry = false), -5)
    internal val emberClap: Strike = ember(EmberVoice.CLAPS, "TUNE" to .50f, "CROWD" to .25f, "DISTANCE" to .35f, "WARMTH" to .50f)

    /**
     * SHATTER's manner, as an alternative on the page: the groove played in its room and cut at one of its notes, so
     * the pad is a slice of a performance with its neighbours' bleed in it. The cut starts 2 ms before the note's
     * strike and ends at the next note of any part (at most [maxSeconds]), with a 2 ms fade in and a 30 ms fade out.
     * [occurrence] picks which note of [part] (0 is its first in the loop).
     */
    internal fun sliced(kit: Kit, part: Int, occurrence: Int = 0, maxSeconds: Float = .6f): Strike = { v, t, _ ->
        val notes = played(kit, notesOf(kit))
        val starts = notes.map { Cypher.startFrame(it, kit.bpm) }
        val mine = notes.indices.filter { notes[it].part == part }.sortedBy { starts[it] }
        val i = mine[occurrence.coerceIn(0, mine.lastIndex)]
        val from = max(0, starts[i] - (.002f * Dsp.RATE).toInt())
        val next = starts.filter { it > starts[i] + 64 }.minOrNull() ?: (starts[i] + (maxSeconds * Dsp.RATE).toInt())
        val to = min(next, starts[i] + (maxSeconds * Dsp.RATE).toInt())
        val raw = grooveRaw(kit, notes, v, t)
        val out = raw.copyOfRange(from, min(to, raw.size))
        val fadeIn = (.002f * Dsp.RATE).toInt()
        for (j in 0 until min(fadeIn, out.size)) out[j] *= j.toFloat() / fadeIn
        Dsp.fadeTailCosine(out, .03f)
        out
    }

    internal fun parts(kit: Kit): List<Part> = when (kit) { Kit.CARDBOARD -> cardboard; Kit.MATINEE -> matinee; Kit.STUMBLE -> stumble }

    internal fun phrases(kit: Kit): List<Phrase> = when (kit) { Kit.CARDBOARD -> cardboardPhrases; Kit.MATINEE -> matineePhrases; Kit.STUMBLE -> stumblePhrases }

    /** A kit's groove before the clock. */
    internal fun notesOf(kit: Kit): List<Note> = when (kit) { Kit.CARDBOARD -> cardboardNotes; Kit.MATINEE -> matineeNotes; Kit.STUMBLE -> stumbleNotes }

    // CARDBOARD (89): the profile itself. The box kick and the crack snare lead; the hat is a whisper; the foley
    // sits where a kit would keep its toms and cymbals.
    private val cardboard: List<Part> = listOf(
        Part("Box Kick", DrumClass.KICK, strike = emberKick),
        Part("Crack Snare", DrumClass.SNARE, strike = emberSnare),
        Part("Whisper Hat", DrumClass.HAT_CLOSED, HAT_GROUP, strike = emberHat),
        Part("Rim", DrumClass.PERC, strike = emberRim),
        Part("Low Box Kick", DrumClass.KICK, strike = emberLowKick),
        Part("Cross Stick", DrumClass.PERC, strike = emberStick),
        Part("Paper Snare", DrumClass.SNARE, strike = emberPaper),
        Part("Shaker", DrumClass.PERC, strike = emberShaker),
        Part("Dead Tom", DrumClass.TOM, strike = emberDeadTom),
        Part("Timpani", DrumClass.TOM, converted = true, strike = emberTimpani),
        Part("Dark Gong", DrumClass.PERC, converted = true, strike = darkGong),
        Part("Zap", DrumClass.PERC, converted = true, strike = zap),
        Part("Punch", DrumClass.PERC, converted = true, strike = punchHit),
        Part("Clap", DrumClass.CLAP, strike = emberClap),
    )

    /** Round 1's drums (THUMP and SKIN), heard as "too much like drum machine sounds": kept for the page's comparison. */
    internal val round1: Map<String, Strike> = linkedMapOf(
        "Box Kick" to boxKick, "Crack Snare" to crackSnare, "Whisper Hat" to whisperHat,
        "Rim" to KitStrikes.thump(ThumpVoice.RIM, mapOf("TUNE" to .70f, "DECAY" to .25f), vary = listOf("TUNE", "DECAY", "RING")),
        "Low Box Kick" to lowBoxKick,
        "Cross Stick" to skinTouched(SkinVoice.STICK, "TUNE" to .60f, "DECAY" to .25f, "BODY" to .25f, "CLICK" to .50f) { v -> mapOf("CLICK" to .3f + .4f * v) },
        "Paper Snare" to paperSnare,
        "Shaker" to skinTouched(SkinVoice.SHAKER, "TONE" to .30f, "DECAY" to .25f, vary = listOf("TONE", "DECAY", "GRAIN")) { v -> mapOf("PUNCH" to .3f + .3f * v) },
        "Dead Tom" to deadTom, "Timpani" to timpani,
        "Clap" to KitStrikes.thump(ThumpVoice.CLAP, mapOf("SPREAD" to .25f, "DECAY" to .25f), vary = listOf("SPREAD", "DECAY", "TONE")) { v -> mapOf("TONE" to .35f + .3f * v) },
    )

    const val KICK = 0
    const val SNARE = 1
    const val HAT = 2
    const val RIM = 3
    const val LOW_KICK = 4
    const val PAPER = 6
    const val TIMPANI = 9
    const val GONG = 10
    const val ZAP = 11

    /** The groove's notes before the clock: a two-bar boom bap on the box kick and the crack snare, the hat at a whisper. */
    internal val cardboardNotes: List<Note> = buildList {
        for (bar in 0 until GROOVE_BARS) {
            val b = bar * 4.0
            val kicks = if (bar == 0) listOf(0.0 to 1f, 1.75 to .75f, 2.5 to .90f) else listOf(0.0 to 1f, 1.5 to .70f, 2.75 to .80f, 3.5 to .60f)
            for ((beat, w) in kicks) add(Note(b + beat, KICK, w))
            add(Note(b + 1.0, SNARE, .95f))
            add(Note(b + 3.0, SNARE, 1f))
            if (bar == 0) add(Note(b + 2.75, PAPER, .30f))
            if (bar == 1) add(Note(b + 3.75, RIM, .50f))
            for (eighth in 0 until 8) add(Note(b + eighth * .5, HAT, if (eighth % 2 == 0) .75f else .50f))
        }
        // A zap pickup into the loop's top.
        add(Note(7.75, ZAP, .55f))
    }

    private val cardboardPhrases = listOf(
        // A timpani roll at 1/32 over two beats, the weight rising, into the gong.
        Phrase("Timpani Roll", Cypher.ratchet(TIMPANI, 0.0, .125, 16, .30f, 1f) + Note(2.0, GONG, 1f), loop = false),
        Phrase("Cardboard Groove", cardboardNotes, loop = true),
    )

    // ---------- MATINEE (84): the cinematic kit ----------
    // Half the pads are effects. The kick and snare stand further back on a stage than CARDBOARD's; the foley is
    // where a kit would keep its toms and cymbals.
    internal val stageKick: Strike = muffled(ember(EmberVoice.KICK, "TUNE" to .40f, "DAMP" to .45f, "BUZZ" to .30f, "DISTANCE" to .50f, "WARMTH" to .45f), 350f, 3f)
    internal val stageSnare: Strike = contact(ember(EmberVoice.SNARE, "TUNE" to .45f, "DAMP" to .40f, "BUZZ" to .65f, "DISTANCE" to .55f, "WARMTH" to .50f), 5_500f)
    internal val lowTimpani: Strike = foley(ember(EmberVoice.MALLET_TOM, "TUNE" to .02f, "DAMP" to .10f, "BUZZ" to 0f, "DISTANCE" to .50f, "WARMTH" to .40f, dry = false), -9)
    /** BREAKER's fitted crash, a second long with a long fade, then nine semitones down: a gong-like crash. */
    internal val crashGong: Strike = foley(KitStrikes.cymbal(BreakerCymbal.Kind.CRASH, 1f, .4f, pitch = .8), -9)
    internal val lowGlass: Strike = glassAt(-9)
    internal val lowZap: Strike = foley(KitStrikes.thump(ThumpVoice.SPARK, mapOf("TUNE" to .40f, "DECAY" to .35f, "COLOR" to .70f)) { v -> mapOf("METAL" to .1f + .3f * v) }, -7)
    /** A tuned bar with its buzz up, seven down: an orchestral stab's bite. */
    internal val stab: Strike = foley(terraStruck(TerraVoice.TUNED_BAR, "TUNE" to .30f, "DECAY" to .45f, "FORCE" to .80f, "POS" to .30f, "BUZZ" to .60f), -7)

    /** A TERRA drum whose later takes also land a little harder or softer and ring a little longer or shorter (the stab's soft takes measured 0.0047 apart on the re-seeded strike alone). */
    private fun terraStruck(voice: TerraVoice, vararg m: Pair<String, Float>): Strike = { v, t, _ ->
        val base = mapOf(*m)
        Terra.render(voice, if (t == 0) base else KitStrikes.varied(base, listOf("FORCE", "DECAY", "POS"), t, KitStrikes.SKIN_TAKE_STEP), v, t).samples
    }

    private val matinee: List<Part> = listOf(
        Part("Stage Kick", DrumClass.KICK, strike = stageKick),
        Part("Stage Snare", DrumClass.SNARE, strike = stageSnare),
        Part("Whisper Hat", DrumClass.HAT_CLOSED, HAT_GROUP, strike = emberHat),
        Part("Rim", DrumClass.PERC, strike = emberRim),
        Part("Timpani", DrumClass.TOM, converted = true, strike = emberTimpani),
        Part("Low Timpani", DrumClass.TOM, converted = true, strike = lowTimpani),
        Part("Dark Gong", DrumClass.PERC, converted = true, strike = darkGong),
        Part("Crash Gong", DrumClass.PERC, converted = true, strike = crashGong),
        Part("Glass", DrumClass.PERC, converted = true, strike = glassAt(-5)),
        Part("Low Glass", DrumClass.PERC, converted = true, strike = lowGlass),
        Part("Zap", DrumClass.PERC, converted = true, strike = zap),
        Part("Low Zap", DrumClass.PERC, converted = true, strike = lowZap),
        Part("Punch", DrumClass.PERC, converted = true, strike = punchHit),
        Part("Stab", DrumClass.PERC, converted = true, strike = stab),
    )

    private const val M_KICK = 0
    private const val M_SNARE = 1
    private const val M_HAT = 2
    private const val M_TIMPANI = 4
    private const val M_CRASH = 7
    private const val M_GLASS = 8
    private const val M_ZAP = 10

    /** MATINEE's groove: the kick and snare under hats, a glass break on the and of four, a zap pickup into bar two. */
    internal val matineeNotes: List<Note> = buildList {
        for (bar in 0 until GROOVE_BARS) {
            val b = bar * 4.0
            val kicks = if (bar == 0) listOf(0.0 to 1f, 2.5 to .85f) else listOf(0.0 to 1f, 1.75 to .70f, 2.5 to .85f)
            for ((beat, w) in kicks) add(Note(b + beat, M_KICK, w))
            add(Note(b + 1.0, M_SNARE, .95f))
            add(Note(b + 3.0, M_SNARE, 1f))
            for (eighth in 0 until 8) add(Note(b + eighth * .5, M_HAT, if (eighth % 2 == 0) .70f else .45f))
        }
        add(Note(3.5, M_GLASS, .70f))
        add(Note(3.75, M_ZAP, .50f))
        add(Note(7.5, M_TIMPANI, .60f))
    }

    private val matineePhrases = listOf(
        // The riser: the timpani in sixteenths for two beats, then thirty-seconds for two, rising, into the crash gong.
        Phrase("Timpani Riser", Cypher.ratchet(M_TIMPANI, 0.0, .25, 8, .25f, .60f) + Cypher.ratchet(M_TIMPANI, 2.0, .125, 16, .60f, 1f) + Note(4.0, M_CRASH, 1f), loop = false),
        Phrase("Matinee Groove", matineeNotes, loop = true),
    )

    // ---------- STUMBLE (93): the drunkest clock ----------
    // A rimshot in the snare's place, no hats in the groove (the shaker whispers), the kick humming and muffled.
    internal val humKick: Strike = muffled(ember(EmberVoice.KICK, "TUNE" to .28f, "DAMP" to .35f, "BUZZ" to .35f, "DISTANCE" to .25f, "WARMTH" to .60f), 350f, 4f)
    internal val rimshot: Strike = ember(EmberVoice.RIMSHOT, "TUNE" to .70f, "DAMP" to .45f, "BUZZ" to .50f, "DISTANCE" to .25f, "WARMTH" to .55f)
    internal val ghostSnare: Strike = KitStrikes.softer(emberSnare, .40f)
    internal val openBox: Strike = ember(EmberVoice.HAT_OPEN, "TUNE" to .40f, "DAMP" to .70f, "DISTANCE" to .25f, "WARMTH" to .55f)
    internal val lowTom: Strike = muffled(ember(EmberVoice.TOM_FLOOR, "TUNE" to .20f, "DAMP" to .75f, "BUZZ" to .25f, "DISTANCE" to .25f, "WARMTH" to .55f), 400f, 2f)
    internal val knock: Strike = KitStrikes.terra(TerraVoice.SLAP, mapOf("TUNE" to .45f, "DECAY" to .20f, "FORCE" to .80f, "POS" to .80f, "BOWL" to 0f, "SMACK" to .70f))
    internal val bell: Strike = foley(KitStrikes.tines(TinesVoice.BELL, mapOf("TUNE" to .40f, "DECAY" to .40f, "RATIO" to .6f)), -7)

    private val stumble: List<Part> = listOf(
        Part("Hum Kick", DrumClass.KICK, strike = humKick),
        Part("Rimshot", DrumClass.SNARE, strike = rimshot),
        Part("Crack Snare", DrumClass.SNARE, strike = emberSnare),
        Part("Ghost Snare", DrumClass.SNARE, strike = ghostSnare),
        Part("Shaker", DrumClass.PERC, strike = emberShaker),
        Part("Open Box", DrumClass.HAT_OPEN, HAT_GROUP, strike = openBox),
        Part("Low Tom", DrumClass.TOM, strike = lowTom),
        Part("Knock", DrumClass.PERC, strike = knock),
        Part("Timpani", DrumClass.TOM, converted = true, strike = emberTimpani),
        Part("Dark Gong", DrumClass.PERC, converted = true, strike = darkGong),
        Part("Punch", DrumClass.PERC, converted = true, strike = punchHit),
        Part("Zap", DrumClass.PERC, converted = true, strike = zap),
        Part("Clap", DrumClass.CLAP, strike = emberClap),
        Part("Bell", DrumClass.PERC, converted = true, strike = bell),
    )

    private const val S_KICK = 0
    private const val S_RIM = 1
    private const val S_SNARE = 2
    private const val S_GHOST = 3
    private const val S_SHAKER = 4
    private const val S_KNOCK = 7

    /** STUMBLE's groove: no hat part at all; the shaker whispers on the eighths, the rimshot takes the backbeat. */
    internal val stumbleNotes: List<Note> = buildList {
        for (bar in 0 until GROOVE_BARS) {
            val b = bar * 4.0
            val kicks = if (bar == 0) listOf(0.0 to 1f, 1.5 to .70f, 2.75 to .85f) else listOf(0.0 to 1f, 1.75 to .65f, 2.5 to .90f, 3.25 to .55f)
            for ((beat, w) in kicks) add(Note(b + beat, S_KICK, w))
            add(Note(b + 1.0, S_RIM, .95f))
            add(Note(b + 3.0, if (bar == 1) S_SNARE else S_RIM, 1f))
            add(Note(b + 2.25, S_GHOST, .35f))
            if (bar == 1) add(Note(b + 3.75, S_KNOCK, .50f))
            for (eighth in 0 until 8) add(Note(b + eighth * .5, S_SHAKER, if (eighth % 2 == 0) .20f else .14f))
        }
    }

    private val stumblePhrases = listOf(
        // A dragging snare roll: sixteenths over two beats, every stroke later than the last by the drunkest clock, into the rimshot.
        Phrase("Dragging Roll", clockOf(Kit.STUMBLE).let { c -> Cypher.staggered(Cypher.ratchet(S_SNARE, 0.0, .25, 8, .30f, .90f), mapOf(S_SNARE to 14f), c.wobbleMs, c.rho, "stumble-roll") } + Note(2.0, S_RIM, 1f), loop = false),
        Phrase("Stumble Groove", stumbleNotes, loop = true),
    )
}
