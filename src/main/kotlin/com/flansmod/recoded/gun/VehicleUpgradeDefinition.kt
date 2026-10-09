package com.flansmod.recoded.gun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.resources.Identifier

/**
 * A vehicle upgrade from `data/<namespace>/flansmod/vehicle_upgrades/<name>.json` (engine tuning, armour kits, tyres,
 * extra fuel tanks...). It fits any vehicle offering its [slot] in `upgrade_slots`, optionally restricted to [vehicles]
 * or [types]. Multipliers default to 1 (no change). The vehicle model may contain a bone `upgrade_<name>` (shown while
 * installed) and `default_<slot>` (hidden while the slot is occupied), like gun attachments.
 */
@Serializable
data class VehicleUpgradeDefinition(
    val name: String,
    val slot: String,
    val vehicles: List<@Serializable(IdentifierSerializer::class) Identifier> = emptyList(),
    val types: List<VehicleType> = emptyList(),
    @SerialName("speed_multiplier") val speedMultiplier: Double = 1.0,
    @SerialName("acceleration_multiplier") val accelerationMultiplier: Double = 1.0,
    @SerialName("turn_multiplier") val turnMultiplier: Float = 1f,
    /** Added to the hull's and every part's armour (capped at 0.95). */
    @SerialName("armor_bonus") val armorBonus: Float = 0f,
    /** Multiplies hull and part health. */
    @SerialName("health_multiplier") val healthMultiplier: Float = 1f,
    @SerialName("fuel_capacity_multiplier") val fuelCapacityMultiplier: Float = 1f,
    @SerialName("fuel_consumption_multiplier") val fuelConsumptionMultiplier: Float = 1f,
    @SerialName("step_height_bonus") val stepHeightBonus: Float = 0f,
    /** Cargo slots added to the vehicle's storage (rounded down to whole rows of 9). */
    @SerialName("storage_bonus") val storageBonus: Int = 0,
    /** Speed factor in water (snorkel / amphibious kits); only ever raises the vehicle's own value. */
    @SerialName("water_speed") val waterSpeed: Double? = null,
    /** Item model (an `assets/<ns>/items/<name>.json` id) used for the upgrade item. */
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
) {
    fun fits(vehicleId: Identifier, vehicle: VehicleDefinition) =
        slot in vehicle.upgradeSlots && (vehicles.isEmpty() || vehicleId in vehicles) && (types.isEmpty() || vehicle.type in types)
}
