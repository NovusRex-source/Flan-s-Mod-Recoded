package com.flansmod.recoded.client.vehicle

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import net.minecraft.ChatFormatting
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/** Client side of vehicles: the seat's mounted gun, third-person camera distance, engine sounds and the vehicle HUD. */
object VehicleClient {
    /** The mounted gun of the seat [player] sits in, if any. */
    fun seatGun(player: Player): GunDefinition? {
        val vehicle = player.vehicle as? DriveableEntity ?: return null
        return Guns[vehicle.seat(vehicle.seatOf(player))?.gun]
    }

    /** The second weapon (bombs, rockets) of the seat [player] sits in, if any. */
    fun seatSecondary(player: Player): GunDefinition? {
        val vehicle = player.vehicle as? DriveableEntity ?: return null
        return Guns[vehicle.seat(vehicle.seatOf(player))?.secondary]
    }

    /** True while looking through the sight of the local player's vehicle gun (set by GunInput). */
    @JvmStatic
    var sighting = false

    /** Camera position while [sighting]: the sight of the player's seat gun, following their aim. */
    @JvmStatic
    fun sightCamera(partialTick: Float): Vec3? {
        if (!sighting) return null
        val player = Minecraft.getInstance().player ?: return null
        val vehicle = player.vehicle as? DriveableEntity ?: return null
        val seat = vehicle.seatOf(player)
        val (yaw, elevation) = vehicle.aim(seat, player, partialTick)
        return vehicle.sightPosition(seat, yaw, elevation, partialTick)
    }

    /** Third-person camera distance: at least the vehicle's `camera_distance` while riding one. */
    @JvmStatic
    fun cameraDistance(base: Float): Float {
        val entity: Entity = Minecraft.getInstance().cameraEntity ?: return base
        val vehicle = entity.vehicle as? DriveableEntity ?: return base
        return maxOf(base, vehicle.definition?.cameraDistance ?: base)
    }

    private val engines = java.util.WeakHashMap<DriveableEntity, EngineSound>()

    /** Vanilla looping sound instance per vehicle, like minecarts; started once the vehicle's definition is known. */
    private class EngineSound(private val vehicle: DriveableEntity, sound: net.minecraft.resources.Identifier) :
        AbstractTickableSoundInstance(SoundEvent.createVariableRangeEvent(sound), SoundSource.NEUTRAL, SoundInstance.createUnseededRandom()) {
        init {
            looping = true
            delay = 0
            volume = 0f
            x = vehicle.x; y = vehicle.y; z = vehicle.z
        }

        override fun canStartSilent() = true

        override fun tick() {
            if (vehicle.isRemoved) return stop()
            x = vehicle.x; y = vehicle.y; z = vehicle.z
            val def = vehicle.definition ?: return
            val speed = (vehicle.position().subtract(vehicle.xo, vehicle.yo, vehicle.zo).horizontalDistance() / def.maxSpeed).toFloat().coerceIn(0f, 1f)
            val running = vehicle.controllingPassenger != null && vehicle.hasFuel
            // Aircraft engines spool up and down with the rotor/propeller.
            volume = if (def.type.flies) vehicle.flight.power * (0.5f + 0.5f * speed) else if (running) 0.4f + 0.6f * speed else 0f
            pitch = 0.6f + 0.8f * speed
        }
    }

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            val level = mc.level ?: return@register engines.clear()
            for (entity in level.entitiesForRendering()) {
                if (entity !is DriveableEntity || entity in engines) continue
                val sound = entity.definition?.sounds?.engine ?: continue
                engines[entity] = EngineSound(entity, sound).also(mc.soundManager::play)
            }
        }

        VehicleHud.init()
    }
}
