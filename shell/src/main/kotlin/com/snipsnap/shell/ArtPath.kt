package com.snipsnap.shell

/**
 * A path for [ArtCanvas.fillPath] and [ArtCanvas.strokePath]: moves,
 * lines, quadratic curves and closes, recorded in order so the AWT and
 * Android adapters can each replay it into their own path type. Nothing
 * here touches `java.awt`: the phone loads this class.
 */
class ArtPath {
    sealed class Segment {
        data class Move(val x: Float, val y: Float) : Segment()
        data class Line(val x: Float, val y: Float) : Segment()
        data class Quad(val cx: Float, val cy: Float, val x: Float, val y: Float) : Segment()
        data object Close : Segment()
    }

    private val list = ArrayList<Segment>()
    val segments: List<Segment> get() = list

    fun moveTo(x: Float, y: Float): ArtPath = apply { list += Segment.Move(x, y) }
    fun lineTo(x: Float, y: Float): ArtPath = apply { list += Segment.Line(x, y) }
    fun quadTo(cx: Float, cy: Float, x: Float, y: Float): ArtPath = apply { list += Segment.Quad(cx, cy, x, y) }
    fun close(): ArtPath = apply { list += Segment.Close }

    companion object {
        /** A closed polyline through the points, as [Segment.Line]s. */
        fun polygon(xs: FloatArray, ys: FloatArray): ArtPath = polyline(xs, ys).close()

        /** An open polyline through the points. */
        fun polyline(xs: FloatArray, ys: FloatArray): ArtPath {
            require(xs.size == ys.size && xs.isNotEmpty()) { "a polyline needs matching xs/ys with at least one point" }
            val p = ArtPath().moveTo(xs[0], ys[0])
            for (i in 1 until xs.size) p.lineTo(xs[i], ys[i])
            return p
        }
    }
}

/**
 * What a path or circle is painted with. Colours are packed `0xRRGGBB`
 * like everything else on [ArtCanvas]; [alpha] is 0..1 and multiplies
 * whatever is under.
 */
sealed class ArtFill {
    abstract val alpha: Float

    data class Solid(val rgb: Int, override val alpha: Float = 1f) : ArtFill()

    /** [rgb0] at ([x0],[y0]) running to [rgb1] at ([x1],[y1]), clamped beyond. */
    data class Linear(
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val rgb0: Int, val rgb1: Int,
        override val alpha: Float = 1f,
    ) : ArtFill()

    /** [inner] at the centre running to [outer] at radius [r], clamped beyond. */
    data class Radial(
        val cx: Float, val cy: Float, val r: Float,
        val inner: Int, val outer: Int,
        override val alpha: Float = 1f,
    ) : ArtFill()
}
