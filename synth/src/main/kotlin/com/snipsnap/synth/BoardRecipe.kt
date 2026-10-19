package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/** A compound board identity, kept separate from the existing fixed rack. */
enum class BoardType(val id: String) { WIDE("wide") }

/**
 * A saved board and its macro intensity. No mutable parameter maps or DSP
 * state are retained: the same recipe can safely render multiple pad sources.
 *
 * The `board` field discriminates this format and versions the complete
 * mapping and processing order. Version 1 stores WIDE's accepted first
 * audition. Changing that sound requires an explicit versioning decision.
 */
data class BoardRecipe(
    val type: BoardType = BoardType.WIDE,
    val amount: Float = 1f,
) {
    init {
        require(amount.isFinite() && amount in 0f..1f) { "board amount must be finite and in 0..1: $amount" }
    }

    val isBypass: Boolean get() = amount == 0f

    fun process(snip: Snip): Snip = when (type) {
        BoardType.WIDE -> Wide.process(snip, amount)
    }

    fun toJsonValue(): JsonValue.Obj = JsonValue.Obj(linkedMapOf(
        "board" to JsonValue.Num(VERSION.toDouble()),
        "type" to JsonValue.Str(type.id),
        "amount" to JsonValue.Num(amount.toDouble()),
    ))

    fun toJsonText(): String = Json.write(toJsonValue())

    companion object {
        const val VERSION = 1

        fun fromJsonValue(value: JsonValue): BoardRecipe {
            val obj = value.obj()
            val fields = setOf("board", "type", "amount")
            if (obj.keys != fields) throw JsonException(
                "board recipe requires exactly board, type and amount; missing ${fields - obj.keys}, unknown ${obj.keys - fields}",
            )
            val version = obj.getValue("board").int()
            if (version != VERSION) throw JsonException("unsupported board version $version")
            val id = obj.getValue("type").str()
            val type = BoardType.entries.firstOrNull { it.id == id }
                ?: throw JsonException("unsupported board type '$id'")
            // Check the JSON number before narrowing to Float, so values just
            // outside 0..1 cannot round back into the accepted interval.
            val amount = obj.getValue("amount").num()
            if (!amount.isFinite() || amount !in 0.0..1.0) throw JsonException(
                "board amount must be finite and in 0..1: $amount",
            )
            return BoardRecipe(type, amount.toFloat())
        }

        fun fromJsonText(text: String): BoardRecipe = fromJsonValue(Json.parse(text))
    }
}
