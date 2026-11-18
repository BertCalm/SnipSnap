package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The KINDRED kits: four genre kits of sixteen pads, every pad struck by the one KINDRED voice. Round K2
 * (docs/KINDRED.md). The owner accepted these kits by ear in the Python harness at round 2 (2026-11-15); every
 * setting comes from [KindredRecipes], generated from that prototype's kit table.
 *
 * - A01 to A14: the kit's fourteen recipes, each a grid of three velocity zones (.3, .6, 1) of four takes, rendered
 *   by the engine at that velocity and take ([Kindred.padRaw]), so a softer zone is a softer strike (darker, less
 *   dive, less drive) and a take is a fresh strike (free-running oscillator phases, new noise, a few cents of
 *   drift). Take 0 is the reference strike the owner heard.
 * - A15, the phrase: two bars of the kit's eight-step pattern on one pad's voice. Every step re-strikes the same
 *   voice and cuts the last tail, as the reference does, with the oscillators running free across the steps.
 * - A16, the riser: the same voice re-struck faster and faster (the kit's start and end rates, its curve), until in
 *   INDUSTRIAL and AMBIENT the pattern becomes a tone. The roll-or-riser the percussion gate asks for.
 *
 * Level: every zone's first take peaks at [PAD_PEAK] and its takes share that gain (the GATECRASH law), so a soft
 * zone is a different strike, not a quieter copy. The pad's mixer level reproduces the round-2 groove balance:
 * the prototype levelled every pad to one RMS and scaled it by its mix, so a pad's level here is its mix times its
 * own crest factor, the loudest at the house default.
 */
object KindredKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 1

    enum class Kit(val id: String, val title: String, val spec: KindredRecipes.KitSpec) {
        TECHNO("kindred-techno", "Techno", spec("TECHNO")),
        INDUSTRIAL("kindred-industrial", "Industrial", spec("INDUSTRIAL")),
        ELECTRO("kindred-electro", "Electro", spec("ELECTRO")),
        AMBIENT("kindred-ambient", "Ambient", spec("AMBIENT"));

        val bpm: Int get() = spec.bpm
    }

    val velocities = listOf(.30f, .60f, 1f)
    const val TAKES = 4
    const val HITS = 14
    const val PADS = 16
    /** Where every zone's first take peaks. */
    const val PAD_PEAK = .92f
    /** The loudest pad's mixer level: the house default, -3 dB. */
    const val TOP_LEVEL = 0.707946f
    /** The phrase's length in bars. */
    const val PHRASE_BARS = 2
    /** How long the phrase's last step and the riser ring after their last strike, seconds. */
    private const val TAIL_SECONDS = .5

    fun recipes(kit: Kit): List<KindredRecipes.Recipe> = KindredRecipes.ALL.filter { it.id.startsWith("${kit.spec.name}_") }

    fun roles(kit: Kit): List<String> = recipes(kit).map { it.name } + listOf("Phrase", "Riser")

    fun drumClass(kit: Kit, role: Int): DrumClass = if (role < HITS) recipes(kit)[role].role.drumClass else DrumClass.LOOP

    fun build(kit: Kit): List<ArrangedPad> {
        val names = roles(kit)
        return List(PADS) { role ->
            val grid = velocities.map { v -> List(TAKES) { t -> render(kit, role, v, t) } }
            val cls = drumClass(kit, role)
            ArrangedPad(
                grid.last().first(), cls, level = level(kit, role), displayName = names[role], velocityTakes = grid,
                // The two hats choke each other; the phrase and the riser choke each other so the riser can cut the bars.
                muteGroup = when { cls == DrumClass.HAT_CLOSED || cls == DrumClass.HAT_OPEN -> 1; role >= HITS -> 2; else -> 0 },
                source = mapOf(
                    "engine" to "KINDRED", "kitVersion" to VERSION.toString(), "model" to Kindred.MODEL_VERSION.toString(),
                    "kit" to kit.name, "role" to role.toString(), "bpm" to kit.bpm.toString(),
                    "recipe" to (if (role < HITS) recipes(kit)[role].id else if (role == HITS) "phrase" else "riser"),
                    "velocities" to velocities.joinToString(","), "takes" to TAKES.toString(), "articulation" to "finite",
                ),
            )
        }
    }

    /** The finished pad at one velocity and take. Pure: the same arguments give the same bytes. */
    fun render(kit: Kit, role: Int, velocity: Float, take: Int): Snip {
        require(role in 0 until PADS && velocity.isFinite() && velocity in 0f..1f && take in 0 until TAKES)
        return KitStrikes.finish(raw(kit, role, velocity, take), zoneGain(kit, role, velocity))
    }

    /** The pad at 44.1 kHz before levelling: band-limited, decimated, DC-blocked. */
    internal fun raw(kit: Kit, role: Int, velocity: Float, take: Int): FloatArray = Kindred.unlevelled(
        when {
            role < HITS -> recipes(kit)[role].let { r -> Kindred.padRaw(r.voice, Kindred.settled(r.voice, r.macros), velocity, take, r.id) }
            role == HITS -> phraseRaw(kit, velocity, take)
            else -> riserRaw(kit, velocity, take)
        },
    )

    /**
     * Two bars of the kit's eight steps on the phrase pad's voice, at 4x. A step of velocity 0 is silent, as on the
     * reference. The prototype's seamless-loop render reset the oscillators at each pass; a one-shot pad does not
     * need the seam, so here they run free across the steps, the reference's own behaviour.
     */
    internal fun phraseRaw(kit: Kit, velocity: Float, take: Int): DoubleArray {
        val s = kit.spec
        val r = recipes(kit)[s.phrasePad]
        val q = Kindred.shapeFor(r.voice, r.macros, r.id)
        val fs = Kindred.INTERNAL_RATE.toDouble()
        val step = 60.0 / s.bpm / s.phraseDiv
        val passes = (PHRASE_BARS * 16 / (8 * 4 / s.phraseDiv))
        val v = velocity.coerceIn(0f, 1f).toDouble()
        val triggers = (0 until passes * 8).mapNotNull { i ->
            val (semis, w) = s.phraseSteps[i % 8]
            if (w <= 0.0) null else Kindred.Trigger((i * step * fs).toInt(), semis, v * w, s.phraseToVco2)
        }
        val n = ((passes * 8 * step + TAIL_SECONDS) * fs).toInt()
        return Kindred.strike(q, triggers, n, take, "${kit.spec.name}_PHRASE")
    }

    /**
     * The riser at 4x, the prototype's `riser` line for line: the pad's voice with its decays capped (a riser's notes
     * must not smear into one), re-struck from [KindredRecipes.KitSpec.riserStart] to `riserEnd` steps a second
     * along a curve, louder as it goes; past 100 steps a second the phrase's pitches carry into the tone.
     */
    internal fun riserRaw(kit: Kit, velocity: Float, take: Int): DoubleArray {
        val s = kit.spec
        val r = recipes(kit)[s.riserPad]
        val q0 = Kindred.shapeFor(r.voice, r.macros, r.id)
        val q = q0.copy(atau = minOf(q0.atau, .12), ftau = minOf(q0.ftau, .1))
        val fs = Kindred.INTERNAL_RATE.toDouble()
        val rng = Random(Dsp.seedFor("KINDRED", s.name, "riser", take))
        val v = velocity.coerceIn(0f, 1f).toDouble()
        val triggers = ArrayList<Kindred.Trigger>()
        var t = 0.0
        var i = 0
        while (t < s.riserSeconds - .05) {
            val frac = t / s.riserSeconds
            val rate = s.riserStart * (s.riserEnd / s.riserStart).pow(frac.pow(s.riserCurve))
            val semis = s.phraseSteps[i % 8].first * (if (s.riserEnd > 100) 0.5 else 0.0) + s.riserGlide * frac
            val jitter = if (take == 0) 1.0 else 1.0 + (rng.nextDouble() * .12 - .06)
            triggers += Kindred.Trigger((t * fs).toInt(), semis, minOf(v * (.45 + .55 * frac) * jitter, 1.0))
            t += 1.0 / rate
            i++
        }
        return Kindred.strike(q, triggers, ((s.riserSeconds + TAIL_SECONDS) * fs).toInt(), take, "${s.name}_RISER")
    }

    private val gains = ConcurrentHashMap<Triple<Kit, Int, Float>, Float>()

    /** One gain per velocity zone, set by the zone's first take and shared by its takes. */
    private fun zoneGain(kit: Kit, role: Int, velocity: Float): Float = gains.getOrPut(Triple(kit, role, velocity)) {
        val peak = peakOf(KitStrikes.finish(raw(kit, role, velocity, 0), 1f, ceiling = Float.MAX_VALUE).samples)
        if (peak > 1e-9f) PAD_PEAK / peak else 1f
    }

    private val levels = ConcurrentHashMap<Kit, List<Float>>()
    /** The prototype's groove levelling: RMS target and peak ceiling (`kindred_kits.leveled`). */
    private const val RMS_TARGET = .1f
    private const val PEAK_CEILING = .97f

    /**
     * The pad's mixer level, reproducing the round-2 groove's balance. The prototype levelled every pad to an RMS of
     * 0.1 under a 0.97 peak ceiling, then scaled it by its mix; here every pad peaks at [PAD_PEAK], so its level is
     * mix times min(0.1 x crest, 0.97) (crest: peak over RMS of the hard reference strike), scaled so the loudest
     * one-shot sits at [TOP_LEVEL]. Spiky pads (a clap's three strikes, a wood click) met the ceiling in the
     * prototype, which the min keeps. The phrase takes its voice's level and the riser its own pad's.
     */
    fun level(kit: Kit, role: Int): Float {
        val l = levels.getOrPut(kit) {
            val rs = recipes(kit)
            val raw = rs.indices.map { i ->
                val x = raw(kit, i, 1f, 0)
                var sum = 0.0
                for (s in x) sum += s.toDouble() * s
                val rms = sqrt(sum / x.size.coerceAtLeast(1)).toFloat()
                if (rms > 1e-9f) rs[i].mix * minOf(RMS_TARGET * peakOf(x) / rms, PEAK_CEILING) else 0f
            }
            val top = raw.max()
            raw.map { (it / top * TOP_LEVEL).coerceIn(.05f, TOP_LEVEL) }
        }
        return when (role) { HITS -> l[kit.spec.phrasePad]; HITS + 1 -> l[kit.spec.riserPad]; else -> l[role] }
    }

    private fun peakOf(x: FloatArray): Float { var p = 0f; for (v in x) p = max(p, abs(v)); return p }

    private fun spec(name: String) = KindredRecipes.KITS.single { it.name == name }
}
