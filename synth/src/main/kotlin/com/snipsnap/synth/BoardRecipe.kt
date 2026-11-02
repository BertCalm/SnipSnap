package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** A compound board identity, kept separate from the existing fixed rack. */
enum class BoardType(val id: String) { WIDE("wide"), RUSH("rush"), OBLITERATE("obliterate") }

/**
 * A saved board and its macro intensity. No mutable parameter maps or DSP
 * state are retained: the same recipe can safely render multiple pad sources.
 *
 * The `board` field discriminates this format and versions the complete
 * mapping and processing order for each type. Version 1 keeps WIDE's accepted
 * first audition and RUSH's first mapping. Changing either sound requires
 * an explicit versioning decision.
 * OBLITERATE uses version 2 with its complete model/control state; version-1
 * WIDE/RUSH still write their original exact three-field format.
 */
data class BoardRecipe(
    val type: BoardType = BoardType.WIDE,
    val amount: Float = 1f,
    val obliterate: ObliterateBoard.Settings? = if (type == BoardType.OBLITERATE) ObliterateBoard.Settings() else null,
) {
    init {
        require(amount.isFinite() && amount in 0f..1f) { "board amount must be finite and in 0..1: $amount" }
        require((type == BoardType.OBLITERATE) == (obliterate != null)) { "OBLITERATE settings belong only to OBLITERATE" }
    }

    val isBypass: Boolean get() = amount == 0f

    fun process(snip: Snip): Snip = when (type) {
        BoardType.WIDE -> Wide.process(snip, amount)
        BoardType.RUSH -> Rush.process(snip, amount)
        BoardType.OBLITERATE -> ObliterateBoard.process(snip, amount, obliterate!!)
    }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "board" to JsonValue.Num((if (type == BoardType.OBLITERATE) OBLITERATE_VERSION else VERSION).toDouble()),
        "type" to JsonValue.Str(type.id),
        "amount" to JsonValue.Num(amount.toDouble()),
    ).also { fields -> obliterate?.let { fields["obliterate"] = it.toJsonValue() } })

    fun toJsonText(): String = Json.write(toJsonValue())

    companion object {
        const val VERSION = 1
        const val OBLITERATE_VERSION = 2

        fun fromJsonValue(value: JsonValue): BoardRecipe {
            val obj = value.obj()
            val version = obj["board"]?.int() ?: throw JsonException("missing board version")
            if (version !in setOf(VERSION, OBLITERATE_VERSION)) throw JsonException("unsupported board version $version")
            val fields = if (version == VERSION) setOf("board", "type", "amount") else setOf("board", "type", "amount", "obliterate")
            if (obj.keys != fields) throw JsonException(
                "board recipe requires exactly ${fields.joinToString()}; missing ${fields - obj.keys}, unknown ${obj.keys - fields}",
            )
            val id = obj.getValue("type").str()
            val type = BoardType.entries.firstOrNull { it.id == id }
                ?: throw JsonException("unsupported board type '$id'")
            if ((type == BoardType.OBLITERATE) != (version == OBLITERATE_VERSION))
                throw JsonException("board version $version does not support '$id'")
            // Check the JSON number before narrowing to Float, so values just
            // outside 0..1 cannot round back into the accepted interval.
            val amount = obj.getValue("amount").num()
            if (!amount.isFinite() || amount !in 0.0..1.0) throw JsonException(
                "board amount must be finite and in 0..1: $amount",
            )
            return BoardRecipe(type, amount.toFloat(), if (version == OBLITERATE_VERSION)
                ObliterateBoard.Settings.fromJsonValue(obj.getValue("obliterate")) else null)
        }

        fun fromJsonText(text: String): BoardRecipe = fromJsonValue(Json.parse(text))
    }
}
