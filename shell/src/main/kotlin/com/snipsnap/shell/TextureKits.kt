package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Granular
import com.snipsnap.audio.Snip
import com.snipsnap.audio.Stretch
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.Names
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Texture kits — hits become matter. The Sculptor's two verbs share one
 * door: **sculpt** grows four seeded granular takes (cloud, scrub, swarm)
 * from a sound, **stretch** slows it into a wash or **freezes** one
 * instant of it, four takes each. Every take lands as a LOOP pad with
 * provenance and a regenerable recipe, in a kit of its own — a texture
 * is a *new* tape, not an edit of the old one, which is why the phone
 * reaches this from the KIT screen and not the pad sheet.
 *
 * The CLI (`sculpt`, `stretch`) and the phone's TEXTURE panel both come
 * through [render], so a kit grown either way is the same folder.
 */
object TextureKits {

    /** Takes per texture kit: four pads, four seeds. */
    const val TAKES = 4

    /** The longest any texture pad gets, on the phone or the terminal — a minute of one hit is plenty. */
    const val MAX_TEXTURE_SEC = 60f

    val SCULPT_MODES: List<String> = listOf("cloud", "scrub", "swarm")

    private val SCULPT_PARAMS: Map<String, Granular.Params> = mapOf(
        // Dense grains hovering just past the attack: a hit becomes weather.
        "cloud" to Granular.Params(
            sizeSec = 0.09f, density = 40f, positionStart = 0.35f,
            jitter = 0.05f, pitchSpreadSemis = 0.3f, spray = 0.6f,
        ),
        // The read position crawls the whole source: the break as a slow landscape.
        "scrub" to Granular.Params(
            sizeSec = 0.12f, density = 30f, positionStart = 0f, positionEnd = 1f,
            jitter = 0.02f, pitchSpreadSemis = 0.2f, spray = 0.5f,
        ),
        // A cloud detuned across ±7 semitones: the thickener.
        "swarm" to Granular.Params(
            sizeSec = 0.09f, density = 55f, positionStart = 0.35f,
            jitter = 0.05f, pitchSpreadSemis = 7f, spray = 0.8f,
        ),
    )

    fun sculptParams(mode: String): Granular.Params =
        SCULPT_PARAMS[mode.lowercase()]
            ?: throw IllegalArgumentException("unknown mode '$mode' - sculpt speaks ${SCULPT_MODES.joinToString(", ")}")

    /** What to grow. */
    sealed interface Spec {
        /** The verb, shouted: SCULPT / STRETCH / FREEZE. */
        val verb: String

        /** The kit-name suffix: "<Source> Sculpt". */
        val suffix: String

        data class Sculpt(val mode: String, val seconds: Float = 8f, val seed: Long = 7L) : Spec {
            override val verb get() = "SCULPT"
            override val suffix get() = "Sculpt"
        }

        /** The source slowed by [factor] — clamped so no take outruns [MAX_TEXTURE_SEC], the whole hit slowed as far as fits. */
        data class Stretch(val factor: Float = 8f, val seed: Long = 7L) : Spec {
            override val verb get() = "STRETCH"
            override val suffix get() = "Stretched"
        }

        /** One instant held for [seconds]: the loudest moment, then a quarter, half, three quarters of the way in. */
        data class Freeze(val seconds: Float = 8f, val seed: Long = 7L) : Spec {
            override val verb get() = "FREEZE"
            override val suffix get() = "Frozen"
        }
    }

    // ---- the phone's panel, as data ----

    /** The panel's two doors. */
    val KINDS: List<String> = listOf("SCULPT", "STRETCH")

    /** Each door's modes, as the chips read them. */
    fun modesFor(kind: String): List<String> = when (kind) {
        "SCULPT" -> SCULPT_MODES.map { it.uppercase() }
        "STRETCH" -> listOf("SLOW", "FREEZE")
        else -> throw IllegalArgumentException("unknown texture kind '$kind' - the panel draws: ${KINDS.joinToString(", ")}")
    }

    /** Each mode's one knob, in the phone's own bounds. */
    fun knobFor(kind: String, mode: String): Knob = when {
        kind == "SCULPT" -> Knob("LENGTH", 2f, 30f, 8f, exponential = true)
        kind == "STRETCH" && mode == "FREEZE" -> Knob("HOLD", 2f, 30f, 8f, exponential = true)
        kind == "STRETCH" -> Knob("BY", 2f, 32f, 8f, exponential = true)
        else -> throw IllegalArgumentException("unknown texture kind '$kind'")
    }

    /** What the knob's value column reads: "8 s" or "×8". */
    fun knobLabel(knob: Knob, value: Float): String =
        if (knob.label == "BY") "×${value.roundToInt()}" else "${value.roundToInt()} s"

    /** The panel's choice as a [Spec]; [fraction] is the stepper's position on [knobFor]. */
    fun spec(kind: String, mode: String, fraction: Float, seed: Long): Spec {
        val v = knobFor(kind, mode).value(fraction)
        return when {
            kind == "SCULPT" -> Spec.Sculpt(mode.lowercase().also { sculptParams(it) }, v, seed)
            mode == "FREEZE" -> Spec.Freeze(v, seed)
            else -> Spec.Stretch(v.roundToInt().toFloat(), seed)
        }
    }

    /** "<Source> Sculpt", MPC-safe. */
    fun kitName(sourceStem: String, spec: Spec): String = Names.sanitizeStem("$sourceStem ${spec.suffix}")

    /** What a take is called on its pad: "Cloud 1", "Stretch 2", "Frozen 3". */
    fun takeName(spec: Spec, index: Int): String = when (spec) {
        is Spec.Sculpt -> "${spec.mode.replaceFirstChar { it.uppercase() }} ${index + 1}"
        is Spec.Stretch -> "Stretch ${index + 1}"
        is Spec.Freeze -> "Frozen ${index + 1}"
    }

    /**
     * Grow the kit: [TAKES] takes of [spec] from [source] into [dir] as
     * [name], every pad LOOP, provenance ([sourceLabel]) and a recipe on
     * each. Same seed, same bytes. [log] hears one line per take.
     */
    fun render(name: String, dir: File, source: Snip, sourceLabel: String, spec: Spec, log: (String) -> Unit = {}): Kit {
        require(source.frameCount > 0) { "the source is empty" }
        val model = KitBuilderModel.create(name, dir)
        for (i in 0 until TAKES) {
            val take = renderTake(source, spec, i)
            val slot = i + 1
            model.assign(slot, take.snip, DrumClass.LOOP, displayName = takeName(spec, i))
            model.update(slot) {
                it.copy(source = take.provenance(sourceLabel), recipe = take.recipe)
            }
            log("  A%02d %s".format(slot, take.line))
        }
        model.save()
        return model.kit
    }

    private class Take(val snip: Snip, val provenance: (String) -> Map<String, String>, val recipe: JsonValue.Obj, val line: String)

    private fun renderTake(source: Snip, spec: Spec, i: Int): Take = when (spec) {
        is Spec.Sculpt -> {
            val seed = spec.seed + i
            Take(
                Granular.render(source, spec.seconds, seed, sculptParams(spec.mode)),
                { label -> mapOf("sculptedFrom" to label, "mode" to spec.mode, "seed" to seed.toString()) },
                obj(
                    "sculpt" to obj(
                        "mode" to JsonValue.Str(spec.mode),
                        "seed" to JsonValue.Num(seed.toDouble()),
                        "seconds" to JsonValue.Num(spec.seconds.toDouble()),
                    ),
                ),
                "${spec.mode} ${i + 1}: seed $seed",
            )
        }
        is Spec.Stretch -> {
            val seed = spec.seed + i
            // The whole hit, slowed as far as a minute allows.
            val factor = min(spec.factor, MAX_TEXTURE_SEC / source.durationSeconds).coerceAtLeast(Stretch.MIN_FACTOR)
            Take(
                Stretch.stretch(source, factor, seed),
                { label -> mapOf("stretchedFrom" to label, "mode" to "stretch", "factor" to "%.2f".format(factor), "seed" to seed.toString()) },
                obj(
                    "stretch" to obj(
                        "factor" to JsonValue.Num(factor.toDouble()),
                        "seed" to JsonValue.Num(seed.toDouble()),
                    ),
                ),
                "stretch ${i + 1}: ×%.1f, seed %d".format(factor, seed),
            )
        }
        is Spec.Freeze -> {
            val seed = spec.seed + i
            val seconds = min(spec.seconds, MAX_TEXTURE_SEC)
            val at = if (i == 0) Stretch.loudestSec(source) else source.durationSeconds * (i / 4f)
            Take(
                Stretch.freeze(source, seconds, seed, at),
                { label -> mapOf("stretchedFrom" to label, "mode" to "freeze", "at" to "%.3f".format(at), "seed" to seed.toString()) },
                obj(
                    "stretch" to obj(
                        "freeze" to JsonValue.Bool(true),
                        "at" to JsonValue.Num(at.toDouble()),
                        "seconds" to JsonValue.Num(seconds.toDouble()),
                        "seed" to JsonValue.Num(seed.toDouble()),
                    ),
                ),
                "frozen ${i + 1}: at %.2fs for %.0fs, seed %d".format(at, seconds, seed),
            )
        }
    }

    private fun obj(vararg entries: Pair<String, JsonValue>): JsonValue.Obj =
        JsonValue.Obj(linkedMapOf(*entries))
}
