package com.snipsnap.audio

/**
 * One dimension of a [Similar.vector], named for what it measures rather
 * than its index — [index] is that vector's own order, so a change there
 * must move here too.
 */
enum class Axis(internal val index: Int) {
    CENTROID(0),
    ROLLOFF(1),
    FLATNESS(2),
    ZERO_CROSSING(3),
    LOW(4),
    MID(5),
    HIGH(6),
    DURATION(7),
    DECAY(8),
}

/**
 * A [GrainField.Projector] fixed to named features instead of fit from a
 * sample's own grains, for a map — a photo field's — with no grains to fit
 * a [GrainField.PcaProjector] from. [x] reads straight off the vector;
 * `y` is left at the map's own centre (0.5), since the one feature DUET
 * wants there for this projector — loudness — is not part of
 * [Similar.vector] at all (deliberately: "a quiet snare is still a
 * snare"). The caller reading a live mic level maps that onto y itself,
 * the smaller change next to threading level through this interface.
 */
class AxesProjector(private val x: Axis = Axis.CENTROID) : GrainField.Projector {
    override fun project(vector: FloatArray): Pair<Float, Float> =
        vector[x.index].coerceIn(0f, 1f) to 0.5f
}
