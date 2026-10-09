package com.flansmod.recoded.client.aircraft

import com.flansmod.recoded.aircraft.CrashPayload
import com.flansmod.recoded.aircraft.FlightModel
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.VehicleType
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Client side of planes and helicopters: the pilot's descend key (vanilla's sprint key, since sneaking dismounts),
 * crash reports to the server, and the pilot's flight instruments for [com.flansmod.recoded.client.vehicle.VehicleHud].
 */
object AircraftClient {
    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            FlightModel.descendHeld = mc.player?.vehicle is DriveableEntity && mc.options.keySprint.isDown
        }
        FlightModel.reportCrash = { _, impact ->
            if (ClientPlayNetworking.canSend(CrashPayload.TYPE)) ClientPlayNetworking.send(CrashPayload(impact))
        }
    }

    /** Height above the ground below (or the sea of the void), in blocks. */
    fun altitude(vehicle: DriveableEntity): Int {
        val ground = vehicle.level().getHeight(Heightmap.Types.MOTION_BLOCKING, vehicle.blockX, vehicle.blockZ)
        return (vehicle.y - ground).toInt().coerceAtLeast(0)
    }

    /** Vertical speed in m/s (blocks per second). */
    fun climbRate(vehicle: DriveableEntity) = vehicle.flight.velocity.y * 20

    /** A plane in the air below its lift speed. */
    fun stalling(vehicle: DriveableEntity): Boolean {
        val def = vehicle.definition ?: return false
        return def.type == VehicleType.PLANE && !vehicle.onGround() && vehicle.flight.velocity.length() < def.flight.liftSpeed * 0.95
    }
}
