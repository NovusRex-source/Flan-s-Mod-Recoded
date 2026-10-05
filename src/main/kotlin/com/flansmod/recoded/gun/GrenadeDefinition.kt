package com.flansmod.recoded.gun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.resources.Identifier

/**
 * A throwable from `data/<namespace>/flansmod/grenades/<name>.json`. Effects are optional and combine:
 * a grenade may explode, leave a smoke cloud and/or blind players who can see it.
 */
@Serializable
data class GrenadeDefinition(
    val name: String,
    /** Ticks until detonation; ignored when [contact] detonates first. */
    @SerialName("fuse_ticks") val fuseTicks: Int = 60,
    /** Detonate on the first impact instead of bouncing. */
    val contact: Boolean = false,
    @SerialName("throw_velocity") val throwVelocity: Float = 1.2f,
    val gravity: Double = 0.05,
    /** Fraction of speed kept when bouncing off blocks. */
    val bounciness: Double = 0.4,
    @SerialName("cooldown_ticks") val cooldownTicks: Int = 20,
    @SerialName("max_stack") val maxStack: Int = 16,
    val explosion: Explosion? = null,
    val smoke: Smoke? = null,
    val flash: Flash? = null,
    @Serializable(IdentifierSerializer::class) @SerialName("detonate_sound") val detonateSound: Identifier? = null,
    /** Item model (an `assets/<ns>/items/<name>.json` id) used for the item and the thrown entity. */
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
) {
    @Serializable
    data class Explosion(
        val power: Float = 2.5f,
        val fire: Boolean = false,
        @SerialName("break_blocks") val breakBlocks: Boolean = false,
    )

    @Serializable
    data class Smoke(val radius: Double = 4.0, @SerialName("duration_ticks") val durationTicks: Int = 300)

    /** Blinds living entities within [radius] that have line of sight to the grenade. */
    @Serializable
    data class Flash(val radius: Double = 12.0, @SerialName("duration_ticks") val durationTicks: Int = 100)
}
