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
    /** Magazine ids this gun accepts (magazines may also list guns themselves). */
    val magazines: List<@Serializable(IdentifierSerializer::class) Identifier> = emptyList(),
    @SerialName("reload_ticks") val reloadTicks: Int = 40,
    /** Bullets per shot (shotguns); ammo may override it. Each pellet deals [damage]. */
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
    /** Movement speed multiplier while aiming down sights. */
    @SerialName("ads_move_speed") val adsMoveSpeed: Float = 0.6f,
    val recoil: Recoil = Recoil(),
    /** Built-in optic (e.g. a fixed sniper scope). A sight attachment replaces it. */
    val scope: Scope? = null,
    val sounds: Sounds = Sounds(),
    /**
     * Item display transforms in Blockbench/vanilla format, keyed by display context
     * (`firstperson_righthand`, `thirdperson_righthand`, `gui`, `ground`, `fixed`, `head`, ...)
     * plus `ads` for the first-person pose while fully aimed. Missing keys fall back to [Transform.DEFAULTS].
     */
    val display: Map<String, Transform> = emptyMap(),
    /** Visual bullet trail; `null` disables tracers for this gun. */
    val tracer: Tracer? = Tracer(),
    /** Attachment slots this gun offers, e.g. `["sight", "barrel"]`. */
    @SerialName("attachment_slots") val attachmentSlots: List<String> = emptyList(),
) {
    val ticksBetweenShots: Int get() = (1200 / rpm.coerceIn(1, 1200)).coerceAtLeast(1)

    /** Fills in default asset locations derived from the gun id, e.g. `pack:ak47` → `pack:gun/ak47`. */
    fun resolvedModel(id: Identifier) = ResolvedModel(
        geo = model.geo ?: id.withPrefix("gun/"),
        texture = model.texture ?: id.withPath("textures/gun/${id.path}.png"),
        animations = model.animations ?: id.withPrefix("gun/"),
        display = Transform.DEFAULTS + display,
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

data class ResolvedModel(val geo: Identifier, val texture: Identifier, val animations: Identifier, val display: Map<String, Transform>)

/** Same semantics as a vanilla item model `display` entry: degrees, 1/16 block units, scale factors. */
@Serializable
data class Transform(
    val rotation: List<Float> = listOf(0f, 0f, 0f),
    val translation: List<Float> = listOf(0f, 0f, 0f),
    val scale: List<Float> = listOf(1f, 1f, 1f),
) {
    companion object {
        const val ADS = "ads"

        /** Defaults for a gun modelled in Blockbench with the barrel pointing north (-Z). */
        val DEFAULTS = mapOf(
            "firstperson_righthand" to Transform(translation = listOf(6f, 2f, 0f)),
            ADS to Transform(translation = listOf(0f, 6.2f, -6f)),
            "thirdperson_righthand" to Transform(rotation = listOf(90f, 0f, 0f), scale = listOf(0.8f, 0.8f, 0.8f)),
            "gui" to Transform(rotation = listOf(0f, -90f, 0f), translation = listOf(-1.5f, -0.5f, 0f), scale = listOf(0.65f, 0.65f, 0.65f)),
            "fixed" to Transform(rotation = listOf(0f, -90f, 0f), translation = listOf(-1.5f, -0.5f, 0f), scale = listOf(0.65f, 0.65f, 0.65f)),
            "ground" to Transform(translation = listOf(0f, -2f, 0f), scale = listOf(0.5f, 0.5f, 0.5f)),
        )
    }
}

/** Client-side bullet trail. [color] is `#RRGGBB`, sizes in blocks. */
@Serializable
data class Tracer(
    val color: String = "#FFD27F",
    val width: Float = 0.04f,
    val length: Float = 3f,
) {
    val argb: Int get() = (0xFF000000 or color.removePrefix("#").toLong(16)).toInt()
}

@Serializable
data class Recoil(
    val pitch: Float = 1.5f,
    /** Random horizontal kick in ±yaw. */
    val yaw: Float = 0.5f,
    /** Fraction of the accumulated kick recovered per tick once firing stops. */
    val recovery: Float = 0.2f,
    @SerialName("ads_multiplier") val adsMultiplier: Float = 0.6f,
)

/**
 * What you see while fully aimed through an optic. [overlay] is a full-screen texture (like the spyglass)
 * that also hides the gun model; without it you look along the gun's own sights.
 */
@Serializable
data class Scope(
    @Serializable(IdentifierSerializer::class) val overlay: Identifier? = null,
    /** Grants night vision while aiming (applied by the server). */
    @SerialName("night_vision") val nightVision: Boolean = false,
    /** Living entities within [thermalRange] get the glowing outline while aiming (client only). */
    val thermal: Boolean = false,
    @SerialName("thermal_range") val thermalRange: Double = 64.0,
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
