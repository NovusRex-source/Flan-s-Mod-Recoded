package com.flansmod.recoded.gun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.resources.Identifier

/** Display name of a caliber: lang key `caliber.flansmod.<caliber>` (content packs provide it), else the raw string. */
fun caliberName(caliber: String): net.minecraft.network.chat.Component =
    net.minecraft.network.chat.Component.translatableWithFallback("caliber.flansmod.$caliber", caliber)

/**
 * A cartridge type from `data/<namespace>/flansmod/ammo/<name>.json`. It is loaded into magazines of the
 * same [caliber]; its modifiers apply to every shot fired with it.
 */
@Serializable
data class AmmoDefinition(
    val name: String,
    val caliber: String,
    @SerialName("max_stack") val maxStack: Int = 64,
    /** Item model (an `assets/<ns>/items/<name>.json` id) used for the ammo item. */
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
    @SerialName("damage_multiplier") val damageMultiplier: Float = 1f,
    @SerialName("velocity_multiplier") val velocityMultiplier: Double = 1.0,
    @SerialName("spread_multiplier") val spreadMultiplier: Float = 1f,
    /** Overrides the gun's pellet count (buckshot vs. slug). */
    val pellets: Int? = null,
    /** Sets targets on fire for this many seconds (incendiary rounds). */
    @SerialName("fire_seconds") val fireSeconds: Float = 0f,
    /** Ignores armour (uses the `flansmod:gun_ap` damage type). */
    @SerialName("armor_piercing") val armorPiercing: Boolean = false,
    /** Overrides the gun's tracer look. */
    val tracer: Tracer? = null,
    /** Fires this grenade definition as a projectile instead of a bullet (rockets, 40mm grenades). */
    @Serializable(IdentifierSerializer::class) val projectile: Identifier? = null,
)

/**
 * A magazine type from `data/<namespace>/flansmod/magazines/<name>.json`. Holds up to [capacity] rounds of
 * one ammo type of its [caliber]. A gun accepts it if either lists the other.
 */
@Serializable
data class MagazineDefinition(
    val name: String,
    val caliber: String,
    val capacity: Int,
    val guns: List<@Serializable(IdentifierSerializer::class) Identifier> = emptyList(),
    /** Multiplies the gun's reload time when this magazine is inserted (drums are slower). */
    @SerialName("reload_multiplier") val reloadMultiplier: Float = 1f,
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
    /**
     * Built into the gun (shotgun tube, launcher, tank breech): never an item. Reloading loads loose rounds straight
     * from the inventory, unloading returns them as rounds.
     */
    val internal: Boolean = false,
) {
    fun fits(magazineId: Identifier, gunId: Identifier, gun: GunDefinition) = magazineId in gun.magazines || gunId in guns
    fun accepts(ammo: AmmoDefinition) = ammo.caliber == caliber
}

/** The gun's stats for a shot fired with [ammo]. */
fun GunDefinition.withAmmo(ammo: AmmoDefinition?): GunDefinition = if (ammo == null) this else copy(
    damage = damage * ammo.damageMultiplier,
    velocity = velocity * ammo.velocityMultiplier,
    spread = spread * ammo.spreadMultiplier,
    adsSpread = adsSpread * ammo.spreadMultiplier,
    pellets = ammo.pellets ?: pellets,
    tracer = if (tracer == null) null else ammo.tracer ?: tracer,
)
