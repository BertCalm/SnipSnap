package com.snipsnap.shell

import com.snipsnap.kit.Kit
import com.snipsnap.synth.AquariumPerformanceSettings

/** Routing choice is SURFACE's; sound/seed/gain/global bypass remain the kit's. */
object SurfaceAquarium {
    data class Route(val settings: AquariumPerformanceSettings, val bpm: Double, val warning: String?) {
        val wet: Boolean get() = settings.enabled && !settings.settings.isBypass
    }

    fun route(kit: Kit?, optedIn: Boolean): Route {
        if (kit == null) return Route(AquariumPerformanceSettings(), 0.0, null)
        val loaded = AquariumPerformance.read(kit)
        // An unused route must not clock an expensive hidden wet core.
        // On the next opt-in the host explicitly resets to the saved seed.
        return Route(if (optedIn) loaded.settings else AquariumPerformanceSettings(),
            kit.tempoBpm?.toDouble() ?: 0.0, loaded.warning)
    }
}
