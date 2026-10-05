package com.flansmod.recoded.combat

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.AmmoDefinition
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.registry.FlansDamageTypes
import com.flansmod.recoded.network.HitPayload
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/**
 * Server-authoritative bullet simulation. Bullets are not entities: each one is a point that moves
 * [GunDefinition.velocity] blocks per tick, loses height through [GunDefinition.gravity] and is raycast
 * against blocks and entities along every tick's segment. Clients draw tracers from [com.flansmod.recoded.network.ShotPayload].
 */
object Ballistics {
    private class Bullet(var pos: Vec3, var velocity: Vec3, val shooter: ServerPlayer, val gun: GunDefinition, val ammo: AmmoDefinition?, var ticksLeft: Int)

    private val bullets = WeakHashMap<ServerLevel, MutableList<Bullet>>()

    fun init() {
        ServerTickEvents.END_LEVEL_TICK.register { level -> bullets[level]?.removeIf { !step(level, it) } }
    }

    /**
     * [gun] must already include ammo modifiers ([com.flansmod.recoded.gun.withAmmo]); [ammo] adds its on-hit effects.
     * [origin] defaults to the shooter's eyes; vehicle guns fire from their muzzle.
     */
    fun fire(shooter: ServerPlayer, gun: GunDefinition, ammo: AmmoDefinition?, direction: Vec3, origin: Vec3 = shooter.eyePosition) {
        bullets.getOrPut(shooter.level()) { mutableListOf() } +=
            Bullet(origin, direction.normalize().scale(gun.velocity), shooter, gun, ammo, gun.lifetimeTicks)
    }

    /** Advances one bullet by one tick. Returns false when the bullet is gone. */
    private fun step(level: ServerLevel, bullet: Bullet): Boolean {
        val start = bullet.pos
        var end = start.add(bullet.velocity)
        if (!level.isLoaded(net.minecraft.core.BlockPos.containing(end))) return false

        val blockHit = level.clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bullet.shooter))
        if (blockHit.type != HitResult.Type.MISS) end = blockHit.location

        val entityHit = ProjectileUtil.getEntityHitResult(
            level, bullet.shooter, start, end, AABB(start, end).inflate(1.0),
            // Never the shooter, their own vehicle or its crew. Vehicles count only where a part is (gaps, open tops).
            {
                it != bullet.shooter && !bullet.shooter.isPassengerOfSameVehicle(it) && it.isPickable && !it.isSpectator && it.isAlive &&
                    (it !is DriveableEntity || it.raycastParts(start, end) != null)
            }, 0.1f,
        )

        when {
            entityHit != null -> {
                hit(level, bullet, entityHit.entity, entityHit.location, start, end)
                return false
            }
            blockHit.type != HitResult.Type.MISS -> {
                val state = level.getBlockState(blockHit.blockPos)
                // Incendiary rounds set the struck surface alight.
                if ((bullet.ammo?.fireSeconds ?: 0f) > 0f) Incendiary.ignite(level, blockHit.blockPos.relative(blockHit.direction))
                level.sendParticles(BlockParticleOption(ParticleTypes.BLOCK, state), end.x, end.y, end.z, 6, 0.05, 0.05, 0.05, 0.1)
                return false
            }
        }

        bullet.pos = end
        bullet.velocity = bullet.velocity.scale(bullet.gun.drag).add(0.0, -bullet.gun.gravity, 0.0)
        return --bullet.ticksLeft > 0
    }

    private fun hit(level: ServerLevel, bullet: Bullet, target: Entity, at: Vec3, from: Vec3, to: Vec3) {
        val headshot = target is LivingEntity && at.y >= target.eyeY - 0.25
        val damage = bullet.gun.damage * if (headshot) bullet.gun.headshotMultiplier else 1f
        val typeKey = if (bullet.ammo?.armorPiercing == true) FlansDamageTypes.GUN_AP else FlansDamageTypes.GUN
        val type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(typeKey)
        bullet.ammo?.fireSeconds?.takeIf { it > 0f }?.let(target::igniteForSeconds)

        // Automatic weapons would otherwise be throttled by the 10 tick hurt cooldown.
        target.invulnerableTime = 0
        val source = DamageSource(type, bullet.shooter)
        val hurt = if (target is DriveableEntity) target.hurtAlong(level, source, damage, from, to) else target.hurtServer(level, source, damage)
        if (hurt && target is LivingEntity && ServerPlayNetworking.canSend(bullet.shooter, HitPayload.TYPE)) {
            ServerPlayNetworking.send(bullet.shooter, HitPayload(headshot, !target.isAlive))
        }
        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, at.x, at.y, at.z, if (headshot) 4 else 1, 0.1, 0.1, 0.1, 0.0)
    }
}
