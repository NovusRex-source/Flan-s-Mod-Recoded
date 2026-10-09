package com.flansmod.recoded.client.vehicle

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.combat.Artillery
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.VehicleType
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/**
 * Fire control for emplacements (mortars): while the local player mans one with a round loaded, the flight of that
 * round is simulated against the terrain (projectile gravity, vanilla throwable drag) and the predicted impact is
 * marked with a red ring of particles ([Artillery.impact]). [impact] feeds the HUD's range readout. The map key opens
 * the [ArtilleryMapScreen] to lay the gun on a point. Purely visual; the server fires.
 */
object ArtilleryClient {
    /** Predicted impact point, or null when not laying an emplacement with a round in it. */
    var impact: Vec3? = null
        private set

    private var ticks = 0

    /** Opens the artillery map while manning an emplacement. */
    private val MAP: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping("key.flansmod.artillery_map", InputConstants.Type.KEYBOARD, InputConstants.KEY_N, KeyMapping.Category(FlansMod.id("flansmod"))))

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { mc -> tick(mc) }
    }

    private fun tick(mc: Minecraft) {
        val player = mc.player
        val vehicle = player?.vehicle as? DriveableEntity
        val def = vehicle?.definition
        while (MAP.consumeClick()) {
            if (vehicle != null && def?.type == VehicleType.STATIC && def.layWithKeys) mc.gui.setScreen(ArtilleryMapScreen(vehicle))
        }
        if (vehicle == null || def?.type != VehicleType.STATIC || !def.layWithKeys) return run { impact = null }
        val seat = vehicle.seatOf(player)
        val round = Artillery.round(vehicle, seat) ?: return run { impact = null }
        val level = mc.level ?: return
        val (yaw, elevation) = vehicle.aim(seat, player)
        val hit = Artillery.impact(level, vehicle, seat, yaw, elevation, round, player)
        impact = hit
        if (hit == null || ticks++ % 3 != 0) return
        // Ring of ~2 blocks (about the blast) and a column marking the centre, readable from far away. Straight into the
        // particle engine: the level's spawn helpers drop particles more than 32 blocks from the camera.
        val ring = DustParticleOptions(0xFF3020, 3f)
        for (k in 0 until 20) {
            val a = k * Math.PI * 2 / 20
            mc.particleEngine.createParticle(ring, hit.x + kotlin.math.cos(a) * 2, hit.y + 0.2, hit.z + kotlin.math.sin(a) * 2, 0.0, 0.0, 0.0)
        }
        for (h in 0 until 4) mc.particleEngine.createParticle(DustParticleOptions(0xFFE040, 3f), hit.x, hit.y + 0.4 + h * 0.6, hit.z, 0.0, 0.0, 0.0)
    }
}
