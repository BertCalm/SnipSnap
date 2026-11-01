package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.AtomicFile
import java.io.File

/**
 * The four choices that make a tile: which style, which palette, which
 * pad's sound draws the field ([seedSlot], null for the longest pad), and
 * which variant of the mark sits bottom-right. Same kit, same knobs,
 * same platform, same PNG bytes.
 */
data class ArtKnobs(
    val style: KitArt.Style,
    val palette: Palette,
    val seedSlot: Int?,
    val mark: Mark.Variant,
) {
    companion object {
        /** What a kit gets before anyone touches the knobs: the scheme's own palette, the longest pad, the stacked mark. */
        fun default(scheme: Scheme): ArtKnobs =
            ArtKnobs(KitArt.Style.WAVEFORM, Palettes.forScheme(scheme), seedSlot = null, mark = Mark.Variant.STACKED)
    }
}

/**
 * `art.json` beside `kit.json`: a kit's knobs, kept as a sidecar so the kit
 * format itself is untouched. Anything unreadable — missing, malformed, a
 * later version, an id this build does not know — reads as null and the
 * kit falls back to [ArtKnobs.default]; a stale sidecar never throws.
 */
object ArtSidecar {
    const val FILE = "art.json"
    private const val VERSION = 1

    fun read(kitDir: File): ArtKnobs? {
        val file = File(kitDir, FILE)
        if (!file.isFile) return null
        val obj = runCatching { Json.parse(file.readText(Charsets.UTF_8)) as? JsonValue.Obj }.getOrNull() ?: return null
        val e = obj.entries
        if ((e["version"] as? JsonValue.Num)?.value?.toInt() != VERSION) return null
        val style = (e["style"] as? JsonValue.Str)?.value?.let { KitArt.Style.byId(it) } ?: return null
        val palette = (e["palette"] as? JsonValue.Str)?.value?.let { Palettes.byId(it) } ?: return null
        val mark = (e["mark"] as? JsonValue.Str)?.value?.let { Mark.Variant.byId(it) } ?: return null
        val seed = when (val s = e["seedSlot"]) {
            null, JsonValue.Null -> null
            is JsonValue.Num -> s.value.toInt()
            else -> return null
        }
        return ArtKnobs(style, palette, seed, mark)
    }

    fun write(kitDir: File, knobs: ArtKnobs) {
        val m = linkedMapOf<String, JsonValue>(
            "version" to JsonValue.Num(VERSION.toDouble()),
            "style" to JsonValue.Str(knobs.style.id),
            "palette" to JsonValue.Str(knobs.palette.id),
            "seedSlot" to (knobs.seedSlot?.let { JsonValue.Num(it.toDouble()) } ?: JsonValue.Null),
            "mark" to JsonValue.Str(knobs.mark.id),
        )
        AtomicFile.writeText(File(kitDir, FILE), Json.write(JsonValue.Obj(m)))
    }

    /** The sidecar's knobs, or the scheme's defaults when there are none to read. */
    fun resolve(kitDir: File, scheme: Scheme): ArtKnobs = read(kitDir) ?: ArtKnobs.default(scheme)
}
