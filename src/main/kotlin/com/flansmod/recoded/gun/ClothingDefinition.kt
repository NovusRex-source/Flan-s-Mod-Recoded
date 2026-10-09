package com.flansmod.recoded.gun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.resources.Identifier

/**
 * A piece of clothing/armour from `data/<namespace>/flansmod/clothing/<name>.json`. Rendering uses the vanilla
 * equipment asset [asset] (`assets/<ns>/equipment/<name>.json`), stats become vanilla attribute modifiers.
 */
@Serializable
data class ClothingDefinition(
    val name: String,
    /** `head`, `chest`, `legs` or `feet`. */
    val slot: String,
    @Serializable(IdentifierSerializer::class) val asset: Identifier,
    val armor: Double = 0.0,
    val toughness: Double = 0.0,
    @SerialName("knockback_resistance") val knockbackResistance: Double = 0.0,
    /** Movement speed change while worn, e.g. -0.05 for a heavy plate carrier. */
    @SerialName("speed_modifier") val speedModifier: Double = 0.0,
    /** Night-vision goggles: night vision while worn (applied by the server). */
    @SerialName("night_vision") val nightVision: Boolean = false,
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
    /** Modern armour: armour plates (gear of type `plate`) that can be inserted while it is worn (inventory slots). */
    @SerialName("plate_slots") val plateSlots: Int = 0,
    /** Side this belongs to (a [FactionDefinition] id): faction creative tab and tooltip line. */
    @Serializable(IdentifierSerializer::class) val faction: Identifier? = null,
)
