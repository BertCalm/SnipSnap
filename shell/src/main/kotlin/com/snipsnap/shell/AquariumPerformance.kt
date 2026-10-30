package com.snipsnap.shell

import com.snipsnap.kit.Kit
import com.snipsnap.json.JsonException
import com.snipsnap.synth.AquariumPerformanceSettings
import java.io.File

/** Live settings belong to the kit, independently of every source-backed PAD print. */
object AquariumPerformance {
    data class Loaded(val settings: AquariumPerformanceSettings, val warning: String? = null)

    fun read(kit: Kit): Loaded {
        val value = kit.aquariumPerformance ?: return Loaded(AquariumPerformanceSettings())
        return try {
            Loaded(AquariumPerformanceSettings.fromJsonValue(value))
        } catch (_: JsonException) { unreadable() }
        catch (_: IllegalArgumentException) { unreadable() }
    }

    private fun unreadable() = Loaded(AquariumPerformanceSettings(), "SAVED PERFORMANCE UNREADABLE · SAVE TO REPLACE")

    /** Caller serializes kit writes. Reopen first so unrelated pad/tempo edits are never overwritten. */
    fun save(dir: File, settings: AquariumPerformanceSettings): Kit {
        val model = KitBuilderModel.open(dir)
        model.setAquariumPerformance(settings)
        model.save()
        return model.kit
    }
}
