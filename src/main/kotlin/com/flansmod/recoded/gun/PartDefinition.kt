package com.flansmod.recoded.gun

import kotlinx.serialization.Serializable
import net.minecraft.resources.Identifier

/**
 * A crafting component from `data/<namespace>/flansmod/parts/<name>.json`, assembled at the Weapons Bench: gun parts
 * (barrel, receiver...), vehicle parts (engine, wheel...) or ammunition components (casing, bullet tip, warhead).
 */
@Serializable
data class PartDefinition(
    val name: String,
    /** `gun`, `vehicle` or `ammo`: groups parts in the creative tab and tooltips. */
    val category: String = "gun",
    /** Item model (an `assets/<ns>/items/<name>.json` id) used for the part item. */
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
)
