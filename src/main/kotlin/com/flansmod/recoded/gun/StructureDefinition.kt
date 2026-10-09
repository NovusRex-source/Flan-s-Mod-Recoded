package com.flansmod.recoded.gun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.resources.Identifier

/**
 * A ready-made battlefield structure from `data/<namespace>/flansmod/structures/<name>.json` (bunker, trench, tower,
 * street, ...), placed with its structure kit item ([com.flansmod.recoded.item.StructureItem]). The blocks come from
 * a vanilla structure template (`data/<namespace>/structure/<path>.nbt`, e.g. saved with a structure block): its north
 * side (-Z) is the front, which faces away from whoever places it.
 */
@Serializable
data class StructureDefinition(
    val name: String,
    /** Structure template id; default: the same id as this definition. */
    @Serializable(IdentifierSerializer::class) val template: Identifier? = null,
    /** Height of the template's floor relative to the surface: -2 sinks a trench two blocks into the ground. */
    @SerialName("y_offset") val yOffset: Int = 0,
    /** Creative tab / tooltip grouping: `bunker`, `trench`, `tower`, `street`, `nest`, ... */
    val category: String = "bunker",
    /** Price in the generated battle shop. */
    val price: Int = 300,
    /** Footprint for the tooltip: width, height, depth in blocks (filled in by the generator). */
    val size: List<Int> = emptyList(),
    @Serializable(IdentifierSerializer::class) val faction: Identifier? = null,
)
