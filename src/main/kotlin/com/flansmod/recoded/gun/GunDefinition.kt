package com.flansmod.recoded.gun

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.minecraft.resources.Identifier

/**
 * A gun as described by a content pack in `data/<namespace>/flansmod/guns/<name>.json`.
 * All fields except [name] have defaults, so a minimal gun is just `{ "name": "Pistol" }`.
 * Distances are in blocks, times in ticks, angles in degrees.
 */
@Serializable
data class GunDefinition(
    val name: String,
    val model: ModelInfo = ModelInfo(),
    val damage: Float = 5f,
    @SerialName("headshot_multiplier") val headshotMultiplier: Float = 1.5f,
    /** Rounds per minute. Capped at 1200 (one shot per tick). */
    val rpm: Int = 300,
    @SerialName("fire_mode") val fireMode: FireMode = FireMode.SEMI,
    @SerialName("burst_count") val burstCount: Int = 3,
    val magazine: Int = 12,
    @SerialName("reload_ticks") val reloadTicks: Int = 40,
    /** Bullets per shot (shotguns). Each pellet deals [damage]. */
    val pellets: Int = 1,
    /** Muzzle velocity in blocks per tick. */
    val velocity: Double = 15.0,
    /** Downward acceleration per tick (bullet drop). */
    val gravity: Double = 0.02,
    /** Velocity multiplier per tick. */
    val drag: Double = 0.99,
    @SerialName("lifetime_ticks") val lifetimeTicks: Int = 40,
    /** Cone half-angle when hip firing / aiming. */
    val spread: Float = 2f,
    @SerialName("ads_spread") val adsSpread: Float = 0.3f,
    /** FOV divisor while aiming down sights. */
    @SerialName("ads_zoom") val adsZoom: Float = 1.3f,
    val recoil: Recoil = Recoil(),
    val ammo: Ammo? = null,
    val sounds: Sounds = Sounds(),
) {
    val ticksBetweenShots: Int get() = (1200 / rpm.coerceIn(1, 1200)).coerceAtLeast(1)

    /** Fills in default asset locations derived from the gun id, e.g. `pack:ak47` → `pack:gun/ak47`. */
    fun resolvedModel(id: Identifier) = ResolvedModel(
        geo = model.geo ?: id.withPrefix("gun/"),
        texture = model.texture ?: id.withPath("textures/gun/${id.path}.png"),
        animations = model.animations ?: id.withPrefix("gun/"),
    )
}

@Serializable
enum class FireMode {
    @SerialName("semi") SEMI,
    @SerialName("auto") AUTO,
    @SerialName("burst") BURST,
}

/** GeckoLib asset ids. `geo`/`animations` are relative to `geckolib/models|animations/`, `texture` is a full path. */
@Serializable
data class ModelInfo(
    @Serializable(IdentifierSerializer::class) val geo: Identifier? = null,
    @Serializable(IdentifierSerializer::class) val texture: Identifier? = null,
    @Serializable(IdentifierSerializer::class) val animations: Identifier? = null,
)

data class ResolvedModel(val geo: Identifier, val texture: Identifier, val animations: Identifier)

@Serializable
data class Recoil(
    val pitch: Float = 1.5f,
    /** Random horizontal kick in ±yaw. */
    val yaw: Float = 0.5f,
    /** Fraction of the accumulated kick recovered per tick once firing stops. */
    val recovery: Float = 0.2f,
    @SerialName("ads_multiplier") val adsMultiplier: Float = 0.6f,
)

/** If set, reloading consumes [item]; each item refills [roundsPerItem] rounds. Without it, reloading is free. */
@Serializable
data class Ammo(
    @Serializable(IdentifierSerializer::class) val item: Identifier,
    @SerialName("rounds_per_item") val roundsPerItem: Int = 1,
)

@Serializable
data class Sounds(
    @Serializable(IdentifierSerializer::class) val shoot: Identifier? = null,
    @Serializable(IdentifierSerializer::class) val reload: Identifier? = null,
    @Serializable(IdentifierSerializer::class) val empty: Identifier? = null,
)

object IdentifierSerializer : KSerializer<Identifier> {
    override val descriptor = PrimitiveSerialDescriptor("flansmod.Identifier", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Identifier) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Identifier = Identifier.parse(decoder.decodeString())
}
