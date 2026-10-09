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
    /** False for launcher projectiles (rockets, 40mm): no hand throwing, not listed as an item. */
    val throwable: Boolean = true,
    /** Smoke trail while flying (rockets). */
    val trail: Boolean = false,
    val explosion: Explosion? = null,
    val smoke: Smoke? = null,
    val flash: Flash? = null,
    @Serializable(IdentifierSerializer::class) @SerialName("detonate_sound") val detonateSound: Identifier? = null,
    /** Item model (an `assets/<ns>/items/<name>.json` id) used for the item and the thrown entity. */
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
    /** A mine: placed on the ground with right click instead of thrown, goes off when its [Mine.trigger] passes over it. */
    val mine: Mine? = null,
    /** Side this belongs to (a [FactionDefinition] id): faction creative tab and tooltip line. */
    @Serializable(IdentifierSerializer::class) val faction: Identifier? = null,
) {
    /**
     * [breakBlocks]: a full vanilla (TNT-like) explosion. Otherwise the vanilla explosion only hurts and pushes, and
     * [blockDamage] does the limited, grenade-type-specific damage to the surroundings.
     */
    @Serializable
    data class Explosion(
        val power: Float = 2.5f,
        val fire: Boolean = false,
        @SerialName("break_blocks") val breakBlocks: Boolean = false,
        @SerialName("block_damage") val blockDamage: BlockDamage? = null,
    )

    /**
     * Light damage to the surroundings: within [radius] blocks, blocks whose blast resistance is at most
     * [maxResistance] break with [chance] (falling off to 0 at the edge). Typical: fragmentation 0.5 (glass, leaves,
     * plants, some soil), concussion/HE 1.5 (also sand, gravel, wood planks are 3), demolition 6 (stone).
     * Off when the `tnt_explodes` gamerule is false.
     */
    @Serializable
    data class BlockDamage(
        val radius: Double = 2.0,
        @SerialName("max_resistance") val maxResistance: Float = 0.5f,
        val chance: Double = 0.7,
        /** Chance a broken block drops itself. */
        val drops: Double = 0.3,
    )

    @Serializable
    data class Smoke(val radius: Double = 4.0, @SerialName("duration_ticks") val durationTicks: Int = 300)

    /**
     * [trigger] `personnel`: any living entity or vehicle steps on it; `vehicle`: only vehicles (anti-tank mines).
     * [vehicleDamage] goes straight into the vehicle part above the mine (tracks, wheels) on top of the explosion.
     */
    @Serializable
    data class Mine(
        val trigger: Trigger = Trigger.PERSONNEL,
        @SerialName("arm_ticks") val armTicks: Int = 40,
        val radius: Double = 0.7,
        @SerialName("vehicle_damage") val vehicleDamage: Float = 0f,
    ) {
        @Serializable
        enum class Trigger { @SerialName("personnel") PERSONNEL, @SerialName("vehicle") VEHICLE }
    }

    /** Blinds living entities within [radius] that have line of sight to the grenade. */
    @Serializable
    data class Flash(val radius: Double = 12.0, @SerialName("duration_ticks") val durationTicks: Int = 100)
}
