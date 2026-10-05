package com.flansmod.recoded.gun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

/**
 * A driveable from `data/<namespace>/flansmod/vehicles/<name>.json`. Positions are in blocks in vehicle space:
 * `[right, up, forward]` relative to the centre of the vehicle's footprint at ground level. Speeds are in blocks
 * per tick, angles in degrees. The GeckoLib model is built facing north (-Z), like guns.
 */
@Serializable
data class VehicleDefinition(
    val name: String,
    val type: VehicleType = VehicleType.CAR,
    val model: ModelInfo = ModelInfo(),
    /** Hitbox footprint (square) and height. */
    val width: Float = 2.5f,
    val height: Float = 1.6f,
    val health: Float = 100f,
    /** Fraction of bullet/melee damage absorbed; armour-piercing rounds and explosions ignore it. */
    val armor: Float = 0f,
    @SerialName("step_height") val stepHeight: Float = 1f,
    @SerialName("max_speed") val maxSpeed: Double = 0.8,
    @SerialName("max_reverse_speed") val maxReverseSpeed: Double = 0.25,
    /** Speed gained per tick at full throttle. */
    val acceleration: Double = 0.02,
    /** Speed lost per tick while braking (jump key). */
    val braking: Double = 0.06,
    /** Fraction of speed lost per tick when coasting. */
    val drag: Double = 0.02,
    /** Degrees per tick at full lock. Cars need speed to turn, tanks turn on the spot. */
    @SerialName("turn_speed") val turnSpeed: Float = 4f,
    /** Speed multiplier in water; 0 stalls the engine (amphibious vehicles use 1). */
    @SerialName("water_speed") val waterSpeed: Double = 0.2,
    val fuel: Fuel = Fuel(),
    /** Seat 0 is the driver's. */
    val seats: List<Seat> = listOf(Seat()),
    /** Damage dealt to mobs that are run over, per block/tick of speed. */
    @SerialName("collision_damage") val collisionDamage: Float = 20f,
    /** Explosion when destroyed; `null` for none. */
    @SerialName("death_explosion") val deathExplosion: Float? = 3f,
    @SerialName("camera_distance") val cameraDistance: Float = 6f,
    val sounds: VehicleSounds = VehicleSounds(),
    /** Item model (an `assets/<ns>/items/<name>.json` id) for the vehicle item. */
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
) {
    fun resolvedModel(id: Identifier) = ResolvedModel(
        geo = model.geo ?: id.withPrefix("vehicle/"),
        texture = model.texture ?: id.withPath("textures/vehicle/${id.path}.png"),
        animations = model.animations ?: id.withPrefix("vehicle/"),
        display = emptyMap(),
    )

    val fuelPerTick: Int get() = fuel.consumption
    val needsFuel: Boolean get() = fuel.capacity > 0
}

@Serializable
enum class VehicleType {
    /** Wheeled: steering needs forward motion and reverses when backing up. */
    @SerialName("car") CAR,
    /** Tracked: turns on the spot. */
    @SerialName("tank") TANK,
}

/**
 * Fuel in ticks of engine time; [items] maps item ids to the fuel they add (right click the vehicle with them).
 * A capacity of 0 means the vehicle needs no fuel.
 */
@Serializable
data class Fuel(
    val capacity: Int = 24000,
    /** Fuel used per tick while the throttle is held. */
    val consumption: Int = 1,
    val items: Map<@Serializable(IdentifierSerializer::class) Identifier, Int> = DEFAULT_ITEMS,
) {
    companion object {
        val DEFAULT_ITEMS = mapOf(
            Identifier.withDefaultNamespace("coal") to 1600, Identifier.withDefaultNamespace("charcoal") to 1600,
            Identifier.withDefaultNamespace("coal_block") to 16000, Identifier.withDefaultNamespace("blaze_rod") to 2400,
            Identifier.withDefaultNamespace("lava_bucket") to 20000,
        )
    }
}

/**
 * Where a passenger sits. A seat with a [gun] lets its occupant fire that gun definition (left click, reload with the
 * reload key from magazines in their inventory). With [turret] the gun follows the occupant's view, otherwise it
 * points along the vehicle. [bones] name the model bones that follow the view (`yaw` around Y, `pitch` around X).
 */
@Serializable
data class Seat(
    val position: List<Double> = listOf(0.0, 0.5, 0.0),
    @Serializable(IdentifierSerializer::class) val gun: Identifier? = null,
    val turret: Boolean = false,
    /** Turret rotation centre (vehicle space); the muzzle is relative to it and turns with the aim. */
    val pivot: List<Double> = listOf(0.0, 1.0, 0.0),
    val muzzle: List<Double> = listOf(0.0, 0.0, 2.0),
    @SerialName("min_pitch") val minPitch: Float = -30f,
    @SerialName("max_pitch") val maxPitch: Float = 15f,
    @SerialName("yaw_bone") val yawBone: String? = null,
    @SerialName("pitch_bone") val pitchBone: String? = null,
) {
    val offset: Vec3 get() = position.vec()
}

@Serializable
data class VehicleSounds(
    /** Looping engine sound (client side), louder and higher with speed while someone drives. */
    @Serializable(IdentifierSerializer::class) val engine: Identifier? = null,
)

fun List<Double>.vec() = Vec3(getOrElse(0) { 0.0 }, getOrElse(1) { 0.0 }, getOrElse(2) { 0.0 })
