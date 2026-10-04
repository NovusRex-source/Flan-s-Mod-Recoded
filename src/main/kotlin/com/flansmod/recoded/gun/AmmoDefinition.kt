package com.flansmod.recoded.gun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.resources.Identifier

/**
 * An ammunition type from `data/<namespace>/flansmod/ammo/<name>.json`. Guns reference it by id in
 * `ammo.item`; each item refills `ammo.rounds_per_item` rounds.
 */
@Serializable
data class AmmoDefinition(
    val name: String,
    @SerialName("max_stack") val maxStack: Int = 64,
    /** Item model (an `assets/<ns>/items/<name>.json` id) used for the ammo item. */
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
)
