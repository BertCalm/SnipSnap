package com.snipsnap.synth

/**
 * Drops every engine's static render cache. It is for tests, which share one process,
 * and for a low-memory callback. Each cache rebuilds on demand, so clearing costs only
 * the next render's preparation time. Bundled fixture data is not touched.
 */
object SynthCaches {
    fun clearAll() {
        Accord.clearCaches()
        Tidal.clearCaches()
        FlockHeld.clearCache()
        OverlapHeld.clearCache()
        Otis.clearCache()
        Ferry.clearCache()
        MeridianMotion.clearCache()
        RimeHeld.clearCache()
        RimeContour.clearCache()
        Apsis.clearPreparationCache()
        ApsisPopulation.clearPreparationCache()
        Ballast.clearSourceCache()
        Cairn.clearMotionCache()
        LatticeMotion.clearCache()
        CausticInstrument.clearGeometryCache()
        CausticCharacters.clearCache()
        Nacre.clearCoreCache()
        ObliterateBoardCache.clear()
        ScannedChainRenderCache.clear()
        OrigamiGestureRenderer.clearCache()
    }
}
