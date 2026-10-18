package com.snipsnap.synth

import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.sqrt

enum class ChamberLayout { UNIFORM, OPPOSING_WALLS, ALTERNATING_WALLS, LOWER_UPPER }
enum class ChamberMaterialMode { MACRO_LAYOUT, EXPLICIT_TARGETS }
enum class ChamberMaterialPolicy { COMMON_OFFSET }
enum class ChamberPlacementMode { MACRO_PATH, EXPLICIT }

/** Stable order is also the order of the five parallel boundary responses. */
enum class ChamberMaterial(val position: Float) {
    WOOD(0f), CONCRETE(.25f), METAL(.5f), GLASS(.75f), SUBMERGED_GLASS(1f)
}

enum class ChamberFace { WEST, EAST, SOUTH, NORTH, FLOOR, CEILING }

/** Stable IDs: editing a wall never renumbers another region's modes. */
enum class ChamberRegion(val face: ChamberFace, val upper: Boolean = false) {
    WEST_LOWER(ChamberFace.WEST), WEST_UPPER(ChamberFace.WEST, true),
    EAST_LOWER(ChamberFace.EAST), EAST_UPPER(ChamberFace.EAST, true),
    SOUTH_LOWER(ChamberFace.SOUTH), SOUTH_UPPER(ChamberFace.SOUTH, true),
    NORTH_LOWER(ChamberFace.NORTH), NORTH_UPPER(ChamberFace.NORTH, true),
    FLOOR(ChamberFace.FLOOR), CEILING(ChamberFace.CEILING, true)
}

/** A normalized editor coordinate. Physical points use the same arithmetic value type. */
data class ChamberPoint(val x: Float, val y: Float, val z: Float) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) { "CHAMBER point must be finite" } }
    fun distanceTo(other: ChamberPoint): Float {
        val dx = x - other.x; val dy = y - other.y; val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    internal fun mix(other: ChamberPoint, amount: Float) = ChamberPoint(
        x + (other.x - x) * amount, y + (other.y - y) * amount, z + (other.z - z) * amount,
    )
    internal fun normalized() = x in 0f..1f && y in 0f..1f && z in 0f..1f
}

data class ChamberDimensions(val width: Float = 8f, val depth: Float = 9f, val height: Float = 4.5f) {
    init {
        require(width.isFinite() && width in 3f..15f) { "CHAMBER width must be 3..15 metres" }
        require(depth.isFinite() && depth in 3f..15f) { "CHAMBER depth must be 3..15 metres" }
        require(height.isFinite() && height in 2.5f..8f) { "CHAMBER height must be 2.5..8 metres" }
    }
    internal fun physical(point: ChamberPoint) = ChamberPoint(
        (point.x * width).coerceIn(ChamberRoom.MIN_CLEARANCE_METRES, width - ChamberRoom.MIN_CLEARANCE_METRES),
        (point.y * depth).coerceIn(ChamberRoom.MIN_CLEARANCE_METRES, depth - ChamberRoom.MIN_CLEARANCE_METRES),
        (point.z * height).coerceIn(ChamberRoom.MIN_CLEARANCE_METRES, height - ChamberRoom.MIN_CLEARANCE_METRES),
    )
}

/** Four named immutable positions, in the fixed membrane / bar / cowbell / triangle order. */
data class ChamberSources(
    val membrane: ChamberPoint = ChamberPoint(.18f, .24f, .26f),
    val bar: ChamberPoint = ChamberPoint(.38f, .36f, .30f),
    val cowbell: ChamberPoint = ChamberPoint(.64f, .28f, .34f),
    val triangle: ChamberPoint = ChamberPoint(.80f, .42f, .35f),
) {
    val points get() = listOf(membrane, bar, cowbell, triangle)
}

data class ChamberTuningRatios(
    val membrane: Float = 1f, val bar: Float = 1f, val cowbell: Float = 2f, val triangle: Float = 4f,
) {
    val values get() = listOf(membrane, bar, cowbell, triangle)
    val indices get() = 0..3
    init { require(values.all { it.isFinite() && it in .25f..8f }) { "CHAMBER tuning ratios must be .25..8" } }
    operator fun get(index: Int): Float = when (index) {
        0 -> membrane; 1 -> bar; 2 -> cowbell; 3 -> triangle; else -> throw IndexOutOfBoundsException("CHAMBER instrument $index")
    }
}

data class ChamberInstrumentEnabled(
    val membrane: Boolean = true, val bar: Boolean = true, val cowbell: Boolean = true, val triangle: Boolean = true,
) {
    val values get() = listOf(membrane, bar, cowbell, triangle)
    val indices get() = 0..3
    operator fun get(index: Int): Boolean = when (index) {
        0 -> membrane; 1 -> bar; 2 -> cowbell; 3 -> triangle; else -> throw IndexOutOfBoundsException("CHAMBER instrument $index")
    }
}

/** Continuous material positions, with independent floor and ceiling assignments. */
data class ChamberSurfaceTargets(
    val westLower: Float = 0f, val westUpper: Float = 0f,
    val eastLower: Float = .75f, val eastUpper: Float = .75f,
    val southLower: Float = 0f, val southUpper: Float = 0f,
    val northLower: Float = .75f, val northUpper: Float = .75f,
    val floor: Float = .25f, val ceiling: Float = .75f,
) {
    val positions get() = listOf(westLower, westUpper, eastLower, eastUpper,
        southLower, southUpper, northLower, northUpper, floor, ceiling)
    init { require(positions.all { it.isFinite() && it in 0f..1f }) { "CHAMBER targets must be 0..1" } }
    operator fun get(region: ChamberRegion): Float = positions[region.ordinal]
}

/**
 * Complete static room recipe. At DIVIDE=0 each boundary follows MATERIAL. In
 * EXPLICIT_TARGETS mode DIVIDE crossfades each region toward its saved target;
 * COMMON_OFFSET moves those targets by (MATERIAL-materialReference)*materialOffsetScale.
 * MACRO_LAYOUT uses the saved low/high palette and layout instead. No editor field
 * silently replaces a macro. EXPLICIT placement uses sources; MACRO_PATH linearly
 * traverses sources -> placementTarget and leaves the listener fixed.
 */
data class ChamberConfig(
    val dimensions: ChamberDimensions = ChamberDimensions(),
    val listener: ChamberPoint = ChamberPoint(.50f, .82f, .46f),
    val sources: ChamberSources = ChamberSources(),
    val placementTarget: ChamberSources = ChamberSources(
        ChamberPoint(.13f, .26f, .72f), ChamberPoint(.39f, .18f, .70f),
        ChamberPoint(.68f, .23f, .76f), ChamberPoint(.86f, .46f, .80f),
    ),
    val placementMode: ChamberPlacementMode = ChamberPlacementMode.MACRO_PATH,
    val layout: ChamberLayout = ChamberLayout.ALTERNATING_WALLS,
    val materialMode: ChamberMaterialMode = ChamberMaterialMode.EXPLICIT_TARGETS,
    val materialPolicy: ChamberMaterialPolicy = ChamberMaterialPolicy.COMMON_OFFSET,
    val materialReference: Float = .40f,
    val materialOffsetScale: Float = .50f,
    val paletteLow: Float = 0f,
    val paletteHigh: Float = .75f,
    val targets: ChamberSurfaceTargets = ChamberSurfaceTargets(),
    val splitHeight: Float = .50f,
    val boundaryBlendMetres: Float = .14f,
    val tuningRatios: ChamberTuningRatios = ChamberTuningRatios(),
    val instrumentEnabled: ChamberInstrumentEnabled = ChamberInstrumentEnabled(),
) {
    init {
        require(listener.normalized() && sources.points.all { it.normalized() } &&
            placementTarget.points.all { it.normalized() }) { "CHAMBER editor positions must be normalized 0..1" }
        require(materialReference.isFinite() && materialReference in 0f..1f)
        require(materialOffsetScale.isFinite() && materialOffsetScale in 0f..1f)
        require(paletteLow.isFinite() && paletteLow in 0f..1f && paletteHigh.isFinite() && paletteHigh in 0f..1f)
        require(splitHeight.isFinite() && splitHeight in 0.15f..0.85f) { "CHAMBER splitHeight must be .15..85" }
        require(boundaryBlendMetres.isFinite() && boundaryBlendMetres in 0.02f..0.5f)
        // Reject crossing/colliding macro paths instead of teleporting a source during rendering.
        val from = sources.points.map(dimensions::physical)
        val to = if (placementMode == ChamberPlacementMode.MACRO_PATH) placementTarget.points.map(dimensions::physical) else from
        val fixedListener = dimensions.physical(listener)
        for (a in from.indices) {
            require(minimumMovingDistance(from[a], to[a], fixedListener, fixedListener) >= ChamberRoom.MIN_POINT_DISTANCE_METRES) {
                "CHAMBER source $a passes too close to listener"
            }
            for (b in 0 until a) require(minimumMovingDistance(from[a], to[a], from[b], to[b]) >= ChamberRoom.MIN_POINT_DISTANCE_METRES) {
                "CHAMBER source paths $a and $b pass too close"
            }
        }
    }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "dimensions" to JsonValue.Obj(linkedMapOf("width" to number(dimensions.width), "depth" to number(dimensions.depth), "height" to number(dimensions.height))),
        "listener" to pointJson(listener), "sources" to sourcesJson(sources), "placementTarget" to sourcesJson(placementTarget),
        "placementMode" to JsonValue.Str(placementMode.name), "layout" to JsonValue.Str(layout.name),
        "materialMode" to JsonValue.Str(materialMode.name), "materialPolicy" to JsonValue.Str(materialPolicy.name),
        "materialReference" to number(materialReference), "materialOffsetScale" to number(materialOffsetScale),
        "paletteLow" to number(paletteLow), "paletteHigh" to number(paletteHigh),
        "targets" to JsonValue.Obj(ChamberRegion.entries.associate { it.name to number(targets[it]) }),
        "splitHeight" to number(splitHeight), "boundaryBlendMetres" to number(boundaryBlendMetres),
        "tuningRatios" to JsonValue.Arr(tuningRatios.values.map(::number)),
        "instrumentEnabled" to JsonValue.Arr(instrumentEnabled.values.map { JsonValue.Bool(it) }),
    ))

    companion object {
        fun hybrid() = ChamberConfig()
        fun aquarium() = ChamberConfig(
            dimensions = ChamberDimensions(7.5f, 10f, 5.5f),
            layout = ChamberLayout.LOWER_UPPER, materialReference = .95f,
            paletteLow = .25f, paletteHigh = 1f,
            targets = ChamberSurfaceTargets(.25f, 1f, .25f, 1f, .25f, 1f, .25f, 1f, .25f, 1f),
            splitHeight = .53f,
        )
        fun forVoice(voice: ChamberVoice): ChamberConfig = when (voice) {
            ChamberVoice.HYBRID -> hybrid()
            ChamberVoice.AQUARIUM -> aquarium()
        }

        fun fromJsonValue(value: JsonValue): ChamberConfig {
            val obj = value.obj()
            fun field(name: String) = obj[name] ?: throw JsonException("CHAMBER configuration missing $name")
            fun scalar(name: String) = field(name).num().toFloat()
            fun point(value: JsonValue): ChamberPoint {
                val a = value.arr()
                if (a.size != 3) throw JsonException("CHAMBER coordinate requires x,y,z")
                return ChamberPoint(a[0].num().toFloat(), a[1].num().toFloat(), a[2].num().toFloat())
            }
            fun sources(value: JsonValue): ChamberSources {
                val a = value.arr()
                if (a.size != 4) throw JsonException("CHAMBER requires four source positions")
                return ChamberSources(point(a[0]), point(a[1]), point(a[2]), point(a[3]))
            }
            val d = field("dimensions").obj()
            fun dim(name: String) = d[name]?.num()?.toFloat() ?: throw JsonException("CHAMBER dimensions missing $name")
            val t = field("targets").obj()
            val targets = ChamberRegion.entries.map { region ->
                t[region.name]?.num()?.toFloat() ?: throw JsonException("CHAMBER targets missing ${region.name}")
            }
            val tuning = field("tuningRatios").arr()
            val enabled = field("instrumentEnabled").arr()
            if (tuning.size != 4 || enabled.size != 4) throw JsonException("CHAMBER requires four tuning ratios and enabled flags")
            return ChamberConfig(
                dimensions = ChamberDimensions(dim("width"), dim("depth"), dim("height")), listener = point(field("listener")),
                sources = sources(field("sources")), placementTarget = sources(field("placementTarget")),
                placementMode = enumValue(field("placementMode").str()), layout = enumValue(field("layout").str()),
                materialMode = enumValue(field("materialMode").str()), materialPolicy = enumValue(field("materialPolicy").str()),
                materialReference = scalar("materialReference"), materialOffsetScale = scalar("materialOffsetScale"),
                paletteLow = scalar("paletteLow"), paletteHigh = scalar("paletteHigh"),
                targets = ChamberSurfaceTargets(targets[0], targets[1], targets[2], targets[3], targets[4], targets[5], targets[6], targets[7], targets[8], targets[9]),
                splitHeight = scalar("splitHeight"), boundaryBlendMetres = scalar("boundaryBlendMetres"),
                tuningRatios = ChamberTuningRatios(tuning[0].num().toFloat(), tuning[1].num().toFloat(), tuning[2].num().toFloat(), tuning[3].num().toFloat()),
                instrumentEnabled = ChamberInstrumentEnabled(enabled[0].bool(), enabled[1].bool(), enabled[2].bool(), enabled[3].bool()),
            )
        }

        private inline fun <reified T : Enum<T>> enumValue(value: String): T =
            enumValues<T>().firstOrNull { it.name == value } ?: throw JsonException("CHAMBER unknown ${T::class.simpleName} $value")
        private fun number(value: Float) = JsonValue.Num(value.toDouble())
        private fun pointJson(point: ChamberPoint) = JsonValue.Arr(listOf(number(point.x), number(point.y), number(point.z)))
        private fun sourcesJson(sources: ChamberSources) = JsonValue.Arr(sources.points.map(::pointJson))

        private fun minimumMovingDistance(a0: ChamberPoint, a1: ChamberPoint, b0: ChamberPoint, b1: ChamberPoint): Float {
            val dx = a0.x - b0.x; val dy = a0.y - b0.y; val dz = a0.z - b0.z
            val vx = (a1.x - a0.x) - (b1.x - b0.x)
            val vy = (a1.y - a0.y) - (b1.y - b0.y)
            val vz = (a1.z - a0.z) - (b1.z - b0.z)
            val speed2 = vx * vx + vy * vy + vz * vz
            val t = if (speed2 <= 1e-12f) 0f else (-(dx * vx + dy * vy + dz * vz) / speed2).coerceIn(0f, 1f)
            return sqrt((dx + t * vx) * (dx + t * vx) + (dy + t * vy) * (dy + t * vy) + (dz + t * vz) * (dz + t * vz))
        }
    }
}
