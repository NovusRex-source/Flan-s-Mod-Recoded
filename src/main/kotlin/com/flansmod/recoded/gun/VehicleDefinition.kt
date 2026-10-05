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
    /** Footprint (square) and height; only used when there are no [parts] (otherwise the parts are the hitbox). */
    val width: Float = 2.5f,
    val height: Float = 1.6f,
    /** Hull health: the vehicle is destroyed when it reaches 0. */
    val health: Float = 100f,
    /** Fraction of bullet/melee damage absorbed by the hull; armour-piercing rounds and explosions ignore it. */
    val armor: Float = 0f,
    /**
     * Hit boxes by name. Bullets only hit the vehicle where they pass through a part; each part has its own health and
     * a [PartRole] that decides what breaks with it. Without parts the whole footprint is one hull part.
     */
    val parts: Map<String, VehiclePart> = emptyMap(),
    /** Upgrade slots (see [VehicleUpgradeDefinition]), e.g. `["engine", "armor"]`. */
    @SerialName("upgrade_slots") val upgradeSlots: List<String> = emptyList(),
    /** Right click with this item to repair the hull and every part by [Repair.amount] health. */
    val repair: Repair = Repair(),
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

    /** Parts that make it move (wheels, tracks). */
    val propulsion: List<String> get() = parts.filterValues { it.role == PartRole.PROPULSION }.keys.toList()

    /** Where the vehicle touches the ground: the footprint of its wheels/tracks, or of the whole vehicle. */
    val contactArea: Pair<Vec3, Vec3>
        get() {
            val wheels = parts.filterValues { it.role == PartRole.PROPULSION }.values
            if (wheels.isEmpty()) return bounds
            // Wheels touch the ground under their axle; tracks along their length (less the curved ends).
            fun reach(p: VehiclePart) = maxOf(0.0, (p.max.z - p.min.z) / 2 - 0.5)
            return Vec3(wheels.minOf { it.center.x }, 0.0, wheels.minOf { it.center.z - reach(it) }) to
                Vec3(wheels.maxOf { it.center.x }, 0.0, wheels.maxOf { it.center.z + reach(it) })
        }

    /** Vehicle-space bounds `[right, up, forward]` min/max of all parts, or of the square footprint. */
    val bounds: Pair<Vec3, Vec3>
        get() = if (parts.isEmpty()) Vec3(-width / 2.0, 0.0, -width / 2.0) to Vec3(width / 2.0, height.toDouble(), width / 2.0)
        else parts.values.let { ps ->
            Vec3(ps.minOf { it.min.x }, ps.minOf { it.min.y }, ps.minOf { it.min.z }) to Vec3(ps.maxOf { it.max.x }, ps.maxOf { it.max.y }, ps.maxOf { it.max.z })
        }

    /** This vehicle with all [upgrades] applied (the base definition stays untouched). */
    fun withUpgrades(upgrades: Collection<VehicleUpgradeDefinition>): VehicleDefinition = upgrades.fold(this) { v, u ->
        v.copy(
            maxSpeed = v.maxSpeed * u.speedMultiplier,
            maxReverseSpeed = v.maxReverseSpeed * u.speedMultiplier,
            acceleration = v.acceleration * u.accelerationMultiplier,
            turnSpeed = v.turnSpeed * u.turnMultiplier,
            armor = (v.armor + u.armorBonus).coerceIn(0f, 0.95f),
            health = v.health * u.healthMultiplier,
            parts = v.parts.mapValues { (_, p) -> p.copy(health = p.health * u.healthMultiplier, armor = (p.armor + u.armorBonus).coerceIn(0f, 0.95f)) },
            stepHeight = v.stepHeight + u.stepHeightBonus,
            waterSpeed = maxOf(v.waterSpeed, u.waterSpeed ?: 0.0),
            fuel = v.fuel.copy(
                capacity = (v.fuel.capacity * u.fuelCapacityMultiplier).toInt(),
                consumption = (v.fuel.consumption * u.fuelConsumptionMultiplier).toInt().coerceAtLeast(if (v.fuel.consumption > 0) 1 else 0),
            ),
        )
    }
}

/** What happens when a part is destroyed. */
@Serializable
enum class PartRole {
    /** Structure: damage goes straight to the hull health (the vehicle's [VehicleDefinition.health]). */
    @SerialName("hull") HULL,
    /** Broken engine: no throttle. */
    @SerialName("engine") ENGINE,
    /** Wheels/tracks: each broken one costs its share of top speed; all broken and the vehicle cannot move. */
    @SerialName("propulsion") PROPULSION,
    /** Gun mount of [VehiclePart.seat]: that seat cannot fire while broken. */
    @SerialName("weapon") WEAPON,
    /** Broken tank leaks fuel. */
    @SerialName("fuel_tank") FUEL_TANK,
}

/**
 * A hit box `[right0, up0, forward0, right1, up1, forward1]` in vehicle space (it turns with the hull). Non-hull parts
 * pass [coreDamage] of every hit on to the hull. [bones] are hidden while the part is broken (e.g. a shot-off wheel).
 */
@Serializable
data class VehiclePart(
    val box: List<Double>,
    val health: Float = 50f,
    val armor: Float = 0f,
    val role: PartRole = PartRole.HULL,
    /** For [PartRole.WEAPON]: the seat whose gun this part carries. */
    val seat: Int = 0,
    @SerialName("core_damage") val coreDamage: Float = 0.25f,
    val bones: List<String> = emptyList(),
) {
    val min: Vec3 get() = Vec3(minOf(box[0], box[3]), minOf(box[1], box[4]), minOf(box[2], box[5]))
    val max: Vec3 get() = Vec3(maxOf(box[0], box[3]), maxOf(box[1], box[4]), maxOf(box[2], box[5]))
    val center: Vec3 get() = min.add(max).scale(0.5)
}

@Serializable
data class Repair(
    @Serializable(IdentifierSerializer::class) val item: Identifier = Identifier.withDefaultNamespace("iron_ingot"),
    val amount: Float = 25f,
)

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
