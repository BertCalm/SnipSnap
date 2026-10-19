package com.snipsnap.synth

/** Four listening fixtures differ only in BOND, exposing the relationship's actual contribution. */
object ObbligatoPresets {
    fun all(): List<ObbligatoPatch> = listOf(
        "Harmony" to ObbligatoRelationship.HARMONY,
        "Play" to ObbligatoRelationship.PLAY,
        "Fight" to ObbligatoRelationship.FIGHT,
        "Protect" to ObbligatoRelationship.PROTECT,
    ).map { (name, relationship) ->
        ObbligatoPatch(name, Obbligato.defaults() + ("BOND" to relationship.bond))
    }
}
