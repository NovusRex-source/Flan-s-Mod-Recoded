package com.flansmod.recoded.gun

import kotlinx.serialization.Serializable
import net.minecraft.resources.Identifier

/**
 * A side of a conflict from `data/<namespace>/flansmod/factions/<name>.json` (e.g. the WW2 pack's Axis, Allies and
 * Soviet Union). Guns, vehicles, grenades and clothing name theirs in their `faction` field; each faction gets a
 * creative tab with its equipment and a coloured tooltip line.
 */
@Serializable
data class FactionDefinition(
    val name: String,
    /** Tooltip colour, `#RRGGBB`. */
    val color: String = "#FFFFFF",
    /** Item shown on the faction's creative tab (a gun, vehicle, clothing or grenade id). */
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
    /** Tab order among the factions of a pack. */
    val order: Int = 0,
) {
    val rgb: Int get() = color.removePrefix("#").toIntOrNull(16) ?: 0xFFFFFF
}

/** The faction of whatever definition [id] names (guns, vehicles, grenades, clothing). */
fun factionOf(id: Identifier): Identifier? = Guns[id]?.faction ?: Vehicles[id]?.faction ?: Grenades[id]?.faction ?: Clothing[id]?.faction
