package com.flansmod.recoded.emplacement

import com.flansmod.recoded.combat.Artillery
import com.flansmod.recoded.combat.VehicleWeapons
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gamemode.Battles
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.VehicleDefinition
import com.flansmod.recoded.gun.vec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/** What a sentry turret shoots at. */
@Serializable
enum class SentryTarget {
    /** Hostile mobs (zombies, skeletons, phantoms, ...). */
    @SerialName("monsters") MONSTERS,
    /** Players and bots on an enemy team of the battle its owner fights in (never anyone outside a battle). */
    @SerialName("enemies") ENEMIES,
    /** Aircraft in flight whose pilot is such an enemy. */
    @SerialName("aircraft") AIRCRAFT,
}

/**
 * A sentry turret: the `sentry` section of an emplacement (`static`) definition. While nobody mans seat 0 the turret
 * looks for [targets] within [range], turns its gun towards them at [turnSpeed] degrees per tick (aiming ahead of
 * moving targets), and fires when it is on target and has a clear line of fire. It reloads by itself from its
 * cargo (`storage`): magazines for its gun, or loose rounds for a built-in feed. A player in seat 0 takes over.
 */
@Serializable
data class SentryDefinition(
    val range: Double = 40.0,
    val targets: List<SentryTarget> = listOf(SentryTarget.MONSTERS, SentryTarget.ENEMIES),
    @SerialName("turn_speed") val turnSpeed: Float = 8f,
    /** How often it looks for a new target, ticks. */
    @SerialName("scan_ticks") val scanTicks: Int = 10,
)

/** The sentry turret AI (server). */
object Sentries {
    private class State(var target: Entity? = null, var nextScan: Long = 0)

    private val states = WeakHashMap<DriveableEntity, State>()

    /** The current target of [vehicle]'s sentry, if any (tests, HUD). */
    fun target(vehicle: DriveableEntity): Entity? = states[vehicle]?.target

    /** One tick of an unmanned sentry turret. */
    fun tick(level: ServerLevel, vehicle: DriveableEntity, def: VehicleDefinition) {
        val sentry = def.sentry ?: return
        val seat = vehicle.seat(0) ?: return
        val gun = Guns[seat.gun] ?: return
        val state = states.getOrPut(vehicle, ::State)
        val owner = vehicle.owner?.let(level.server.playerList::getPlayer)
        val pivot = vehicle.position().add(vehicle.toWorld(seat.pivot.vec()))
        val now = level.gameTime
        if (state.target?.let { !valid(level, it, pivot, sentry, owner) } != false && now >= state.nextScan) {
            state.nextScan = now + sentry.scanTicks
            state.target = level.getEntities(vehicle, vehicle.boundingBox.inflate(sentry.range)) { valid(level, it, pivot, sentry, owner) }
                .minByOrNull { it.distanceToSqr(pivot) }
        }
        val target = state.target ?: return
        if (!target.isAlive || target.isRemoved) return run { state.target = null }

        // Aim ahead of the target by its movement over the bullet's flight time.
        val centre = target.boundingBox.center
        val motion = (target as? DriveableEntity)?.flight?.velocity ?: target.position().subtract(target.xo, target.yo, target.zo)
        val aimAt = centre.add(motion.scale(centre.distanceTo(pivot) / gun.velocity.coerceAtLeast(0.1)))
        val dir = aimAt.subtract(pivot)
        val wantYaw = Artillery.bearing(pivot, aimAt)
        val wantPitch = (Math.toDegrees(kotlin.math.atan2(dir.y, dir.horizontalDistance()))).toFloat().coerceIn(seat.minPitch, seat.maxPitch)
        val (yaw, pitch) = vehicle.sentryAim
        val newYaw = vehicle.limitYaw(seat, Mth.approachDegrees(yaw, wantYaw, sentry.turnSpeed))
        val newPitch = Mth.approach(pitch, wantPitch, sentry.turnSpeed)
        vehicle.sentryAim = newYaw to newPitch
        if (kotlin.math.abs(Mth.wrapDegrees(newYaw - wantYaw)) < 3f && kotlin.math.abs(newPitch - wantPitch) < 3f) VehicleWeapons.fireSentry(vehicle, owner)
    }

    private fun valid(level: ServerLevel, e: Entity, from: Vec3, sentry: SentryDefinition, owner: Entity?): Boolean {
        if (!e.isAlive || e.isRemoved || e.isSpectator || e.distanceToSqr(from) > sentry.range * sentry.range) return false
        val wanted = when {
            e is DriveableEntity -> SentryTarget.AIRCRAFT in sentry.targets && e.definition?.type?.flies == true && !e.onGround() &&
                owner != null && e.controllingPassenger?.let { Battles.enemies(owner, it) } == true
            e is Enemy && e is LivingEntity -> SentryTarget.MONSTERS in sentry.targets
            e is LivingEntity -> SentryTarget.ENEMIES in sentry.targets && owner != null && e.vehicle == null && Battles.enemies(owner, e)
            else -> false
        }
        if (!wanted) return false
        // Clear line of fire from the gun to the target's middle.
        val to = e.boundingBox.center
        return level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e)).type == HitResult.Type.MISS
    }
}
