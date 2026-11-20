package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import com.snipsnap.kit.ArrangedPad
import kotlin.math.pow

/**
 * The BOROUGH kits: six boom-bap drum kits (A01 to A12) and four percussion stacks (A13 to A16), any kit with any
 * stack, generated in the app by [Borough] from the recipes the owner accepted at round 2 ("I think they are all
 * ready", 2026-11-20). docs/BOROUGH.md, round 3.
 *
 * - [Kit.STOOP] STOOP DUST, 90 BPM: the reference middle, a round kick, a papery crack, swung hats. Default stack TAMBOURINE.
 * - [Kit.CELLAR] CELLAR BOOM, 90: a sub kick with a long tail, a tight dry snare. HANDS.
 * - [Kit.CRANKED] CRANKED, 90: driven and squashed, a high-knock kick, dark, the hats buried. TOPS.
 * - [Kit.WAX] WAX, 80: slow, the darkest top end, a fat low snare. BELLS.
 * - [Kit.DRY] DRY ROOM, 90: gated and clean, silence between hits, a short snare. HANDS.
 * - [Kit.RIMS] RIDE AND RIMS, 90: a rim-led snare and a ride where the hats were, the most swing. BELLS.
 *
 * Drum pads, every kit: KICK, SNARE, HAT (RIDE), OPEN HAT (RIDE BELL), KICK 2, SNARE 2, RIM, PEDAL HAT, GHOST,
 * TOM LO, TOM HI, SNARE ROLL. Stacks: HANDS, TAMBOURINE, BELLS, TOPS, four pads each. A kit fills A01 to A12 and a
 * stack A13 to A16, so a stack swaps without moving a drum ([build] takes any stack; the picker that offers the
 * choice is later work).
 *
 * Each pad is three velocity zones (0.35, 0.65, 1.0) of three takes (build.grid): a zone is a different strike (the
 * designed drums' punch, click, crack and drive grow faster than the body; EMBER's contact and threshold parts move),
 * take 0 is the recipe and takes 1 and 2 retension it (build.jitter) and draw fresh noise. Every strike is seeded by
 * its kit or stack, pad, zone and take. Level is the prototype's: each take's peak at 0.89 x v^0.8 (the SOUL ROCK law),
 * then the kit's balance, [Borough.KitRecipe.kickTrimDb] on KICK and KICK 2 and [Borough.KitRecipe.restTrimDb] on
 * every other drum pad. Stacks take no trim, as the prototype played them.
 *
 * Not here yet (docs/BOROUGH.md): the glue compressor and limiter the owner heard on the kits' grooves
 * ([Borough.KitRecipe.glueLimiterDriveDb]), which belongs to playback when a BOROUGH kit loads; and the stack picker.
 */
object BoroughKits {
    /** Bump when a pad's audio changes; saved kits carry the version they were printed with. */
    const val VERSION = 1

    enum class Stack(val id: String, val title: String) {
        HANDS("hands", "Hands"), TAMBOURINE("tamb", "Tambourine"), BELLS("bells", "Bells"), TOPS("tops", "Tops");
        internal val recipe: Borough.StackRecipe get() = Borough.recipes.stacks.single { it.id == id }
    }

    enum class Kit(val id: String, val title: String) {
        STOOP("borough-stoop", "BOROUGH STOOP DUST"),
        CELLAR("borough-cellar", "BOROUGH CELLAR BOOM"),
        CRANKED("borough-cranked", "BOROUGH CRANKED"),
        WAX("borough-wax", "BOROUGH WAX"),
        DRY("borough-dry", "BOROUGH DRY ROOM"),
        RIMS("borough-rims", "BOROUGH RIDE AND RIMS");
        internal val recipe: Borough.KitRecipe get() = Borough.recipes.kits.single { it.id == id }
        val bpm: Int get() = recipe.bpm
        /** The stack the owner heard with this kit (export_recipes.py). */
        val defaultStack: Stack get() = Stack.entries.single { it.id == recipe.defaultStack }
    }

    val velocities: List<Float> get() = Borough.recipes.zones
    val TAKES: Int get() = Borough.recipes.takes
    const val DRUM_PADS = 12
    const val STACK_PADS = 4
    const val PADS = DRUM_PADS + STACK_PADS
    /** The two kick pads (A01 KICK, A05 KICK 2): the kit's kick trim applies to these. */
    val KICK_ROLES = setOf(0, 4)
    /** One hi-hat: the closed, open and pedal hats choke one another. */
    const val HAT_GROUP = 1

    // ------------------------------------------------------------------ one strike

    private fun seed(scope: String, label: String, zone: Int, take: Int) = EmberDsp.Rng(Dsp.seedFor("BOROUGH", VERSION, scope, label, zone, take))

    private fun level(velocity: Float) = 0.89 * velocity.toDouble().pow(0.8)

    /** The kit's balance on drum pad [role]: the kick trim on the kicks, the rest trim on everything else (dB to gain). */
    fun trimOf(kit: Kit, role: Int): Double = 10.0.pow((if (role in KICK_ROLES) kit.recipe.kickTrimDb else kit.recipe.restTrimDb) / 20)

    /** Drum pad [role] (0 to 11) of [kit] in velocity zone [zone] at take [take]. Pure: the same arguments give the same bytes. */
    fun renderDrum(kit: Kit, role: Int, zone: Int, take: Int): Snip {
        require(role in 0 until DRUM_PADS && zone in velocities.indices && take in 0 until TAKES)
        val pad = kit.recipe.pads[role]
        val v = velocities[zone]
        val y = Borough.renderDrum(pad, v.toDouble(), seed(kit.id, pad.label, zone, take), jittered = take > 0)
        val g = level(v) * trimOf(kit, role)
        return Snip(FloatArray(y.size) { (y[it] * g).toFloat() }, 1, Borough.RATE)
    }

    /** Stack pad [index] (0 to 3, A13 to A16) of [stack] in zone [zone] at take [take]. The same in every kit. */
    fun renderStack(stack: Stack, index: Int, zone: Int, take: Int): Snip {
        require(index in 0 until STACK_PADS && zone in velocities.indices && take in 0 until TAKES)
        val pad = stack.recipe.pads[index]
        val v = velocities[zone]
        val y = Borough.renderStack(pad, v.toDouble(), seed(stack.id, pad.label, zone, take))
        val g = level(v)
        return Snip(FloatArray(y.size) { (y[it] * g).toFloat() }, 1, Borough.RATE)
    }

    // ------------------------------------------------------------------ pads

    fun drumName(kit: Kit, role: Int): String = kit.recipe.pads[role].label
    fun stackName(stack: Stack, index: Int): String = stack.recipe.pads[index].label

    /** A drum pad's kit role. A ride and its bell are PERC, as EMBER files them; the toms TOM; the rim and the roll PERC. */
    fun drumClassOf(kit: Kit, role: Int): DrumClass {
        val pad = kit.recipe.pads[role]
        return when (pad.kind) {
            Borough.Kind.KICK -> DrumClass.KICK
            Borough.Kind.SNARE, Borough.Kind.GHOST -> DrumClass.SNARE
            Borough.Kind.HAT -> if (pad.voice == EmberVoice.HAT_OPEN) DrumClass.HAT_OPEN else DrumClass.HAT_CLOSED
            Borough.Kind.RIDE -> DrumClass.PERC
            Borough.Kind.EMBER -> checkNotNull(pad.voice).drumClass
        }
    }

    /** A stack pad's kit role: the claps CLAP, everything else PERC (EMBER's own class for the voice). */
    fun stackClassOf(stack: Stack, index: Int): DrumClass = stack.recipe.pads[index].voice.drumClass

    /** The hats (closed, open, pedal) choke as one hi-hat; nothing else chokes. */
    fun muteGroupOf(kit: Kit, role: Int): Int = if (kit.recipe.pads[role].kind == Borough.Kind.HAT) HAT_GROUP else 0

    private fun grid(render: (zone: Int, take: Int) -> Snip): List<List<Snip>> {
        val jobs = velocities.indices.flatMap { z -> (0 until TAKES).map { t -> z to t } }
        val done = jobs.parallelStream().map { (z, t) -> render(z, t) }.toList()
        return velocities.indices.map { z -> List(TAKES) { t -> done[z * TAKES + t] } }
    }

    private fun source(scope: String, slot: Int, voice: String, extra: Map<String, String>) = mapOf(
        "engine" to "BOROUGH", "kitVersion" to VERSION.toString(), "model" to Borough.MODEL_VERSION.toString(),
        "slot" to slot.toString(), "voice" to voice, "velocities" to velocities.joinToString(","),
        "takes" to TAKES.toString(), "articulation" to "finite", "scope" to scope,
    ) + extra

    /** The twelve drum pads of [kit] (A01 to A12). The pads render in parallel. */
    fun drumPads(kit: Kit): List<ArrangedPad> = (0 until DRUM_PADS).toList().parallelStream().map { role ->
        val g = grid { z, t -> renderDrum(kit, role, z, t) }
        val pad = kit.recipe.pads[role]
        ArrangedPad(
            g.last().first(), drumClassOf(kit, role), displayName = pad.label.titled(), velocityTakes = g, muteGroup = muteGroupOf(kit, role),
            source = source(kit.id, role + 1, pad.voice?.name ?: "designed-${pad.kind.name.lowercase()}",
                mapOf("kit" to kit.name, "bpm" to kit.bpm.toString(), "trimDb" to (if (role in KICK_ROLES) kit.recipe.kickTrimDb else kit.recipe.restTrimDb).toString())),
        )
    }.toList()

    /** The four pads of [stack] (A13 to A16), the same in every kit. */
    fun stackPads(stack: Stack): List<ArrangedPad> = (0 until STACK_PADS).toList().parallelStream().map { i ->
        val g = grid { z, t -> renderStack(stack, i, z, t) }
        val pad = stack.recipe.pads[i]
        ArrangedPad(
            g.last().first(), stackClassOf(stack, i), displayName = pad.label.titled(), velocityTakes = g, muteGroup = 0,
            source = source(stack.id, DRUM_PADS + i + 1, pad.voice.name, mapOf("stack" to stack.name)),
        )
    }.toList()

    /** A sixteen-pad bank: [kit]'s drums on A01 to A12 and [stack] on A13 to A16. The stack picker calls this. */
    fun build(kit: Kit, stack: Stack = kit.defaultStack): List<ArrangedPad> = combine(drumPads(kit), stackPads(stack))

    /** Drums and a stack already built, put together: the drums never move when the stack changes. */
    fun combine(drums: List<ArrangedPad>, stack: List<ArrangedPad>): List<ArrangedPad> {
        require(drums.size == DRUM_PADS && stack.size == STACK_PADS)
        return drums + stack
    }

    private fun String.titled() = lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
