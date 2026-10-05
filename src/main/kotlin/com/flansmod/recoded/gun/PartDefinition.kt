package com.flansmod.recoded.gun

import kotlinx.serialization.Serializable
import net.minecraft.resources.Identifier

/** A gun part (barrel, receiver, stock, ...) from `data/<namespace>/flansmod/parts/<name>.json`, assembled at the Weapons Bench. */
@Serializable
data class PartDefinition(
    val name: String,
    /** Item model (an `assets/<ns>/items/<name>.json` id) used for the part item. */
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
)
