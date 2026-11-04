package com.snipsnap.shell

/**
 * A reviewed pairing of tile colours: the ground the field sits on, the
 * ink the name is set in, and the two field tones the styles blend
 * between. Not a free colour wheel — every palette here keeps the name
 * legible on every style (KitArtTest's legibility law), which is why the
 * list is short and named.
 */
data class Palette(
    val id: String,
    val name: String,
    val ground: Int,
    val ink: Int,
    val field1: Int,
    val field2: Int,
)

/**
 * One palette per scheme, so a VENT user gets a VENT-looking tile by
 * default: ground is that scheme's LCD, ink its LCD ink, the first field
 * tone its amber, the second its accent where that differs from the
 * amber and its secondary ink where it does not.
 */
object Palettes {
    val VENT = Palette("vent", "VENT", ground = 0x0F100E, ink = 0xEEEADD, field1 = 0xE08A68, field2 = 0xB3B4A6)
    val DUSK = Palette("dusk", "DUSK", ground = 0x0E1117, ink = 0xE7E9EE, field1 = 0xF2A25C, field2 = 0xAEB4C0)
    val PHOSPHOR = Palette("phosphor", "PHOSPHOR", ground = 0x080B08, ink = 0xB6F2BE, field1 = 0x7BE38A, field2 = 0xA7B5A3)
    val OPENSKY = Palette("opensky", "OPEN SKY", ground = 0x22211A, ink = 0xF8F3E9, field1 = 0xFF8C00, field2 = 0xA74C00)
    val TIDE = Palette("tide", "TIDE", ground = 0x132022, ink = 0xEEF3F2, field1 = 0x5FD3C6, field2 = 0x00676B)
    val MEMO = Palette("memo", "MEMO", ground = 0x1C2030, ink = 0xFBF5D6, field1 = 0xFFC94A, field2 = 0x1F4FA8)
    val RISO = Palette("riso", "RISO", ground = 0x241A2A, ink = 0xFAF0EE, field1 = 0xFF7DB8, field2 = 0xB0245F)
    val SAGE = Palette("sage", "SAGE", ground = 0x18221B, ink = 0xEBEFE6, field1 = 0xA6E07F, field2 = 0x2F6B3A)

    val ALL: List<Palette> = listOf(VENT, DUSK, PHOSPHOR, OPENSKY, TIDE, MEMO, RISO, SAGE)

    fun byId(id: String): Palette? = ALL.firstOrNull { it.id == id.lowercase() }

    /** The scheme's own palette; VENT's for a scheme this list does not know. */
    fun forScheme(scheme: Scheme): Palette = byId(scheme.id.name) ?: VENT
}
