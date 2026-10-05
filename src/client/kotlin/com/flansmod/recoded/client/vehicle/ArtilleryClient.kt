package com.flansmod.recoded.client.vehicle

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.VehicleType
import com.flansmod.recoded.gun.withAmmo
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.util.Mth
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Fire control for emplacements (mortars): while the local player mans one with a round loaded, the flight of that
 * round is simulated against the terrain (projectile gravity, vanilla throwable drag) and the predicted impact is
 * marked with a red ring of particles. [impact] feeds the HUD's range readout. Purely visual; the server fires.
 */
object ArtilleryClient {
    /** Predicted impact point, or null when not laying an emplacement with a round in it. */
    var impact: Vec3? = null
        private set

    private const val MAX_TICKS = 600
    private var ticks = 0

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { mc -> tick(mc) }
    }

    private fun tick(mc: Minecraft) {
        val player = mc.player
        val vehicle = player?.vehicle as? DriveableEntity
        val def = vehicle?.definition
        if (vehicle == null || def?.type != VehicleType.STATIC) return run { impact = null }
        val seat = vehicle.seatOf(player)
        val gun = Guns[vehicle.seat(seat)?.gun]
        val ammo = vehicle.seatMagazines[seat]?.takeIf { !it.isEmpty }?.ammoDefinition
        if (gun == null || ammo == null) return run { impact = null }
        val gravity = ammo.projectile?.let { Grenades[it]?.gravity } ?: gun.gravity
        val (yaw, elevation) = vehicle.aim(seat, player)
        var pos = vehicle.muzzlePosition(seat, yaw, elevation)
        var velocity = vehicle.aimDirection(yaw, elevation).scale(gun.withAmmo(ammo).velocity)
        var hit: Vec3? = null
        val level = mc.level ?: return
        for (i in 0 until MAX_TICKS) {
            val next = pos.add(velocity)
            val clip = level.clip(ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player))
            if (clip.type != HitResult.Type.MISS) {
                hit = clip.location
                break
            }
            pos = next
            velocity = velocity.scale(0.99).subtract(0.0, gravity, 0.0)
            if (pos.y < level.minY) break
        }
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
