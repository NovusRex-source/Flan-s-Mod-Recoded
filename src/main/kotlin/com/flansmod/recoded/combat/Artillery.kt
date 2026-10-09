package com.flansmod.recoded.combat

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.withAmmo
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Fire control for emplacements: where the round loaded in a seat comes down for a given laying (simulated like the
 * flying projectile: its gravity, vanilla throwable drag, against the terrain), and the laying that puts it on a target.
 * Used by the impact marker and the artillery map on the gunner's client, and by tests.
 */
object Artillery {
    private const val MAX_TICKS = 600
    /** Throwable projectiles (grenade entities) keep this much of their speed per tick. */
    private const val DRAG = 0.99

    /** The loaded round's muzzle speed and gravity. */
    data class Round(val speed: Double, val gravity: Double)

    /** The round loaded in [seat]'s gun, or null when empty. */
    fun round(vehicle: DriveableEntity, seat: Int): Round? {
        val gun = Guns[vehicle.seat(seat)?.gun] ?: return null
        val ammo = vehicle.seatMagazines[seat]?.takeIf { !it.isEmpty }?.ammoDefinition ?: return null
        return Round(gun.withAmmo(ammo).velocity, ammo.projectile?.let { Grenades[it]?.gravity } ?: gun.gravity)
    }

    /** Where [round] fired from [seat] with this laying lands, or null if it flies beyond loaded terrain. */
    fun impact(level: Level, vehicle: DriveableEntity, seat: Int, yaw: Float, elevation: Float, round: Round, shooter: Entity? = null): Vec3? {
        var pos = vehicle.muzzlePosition(seat, yaw, elevation)
        var velocity = vehicle.aimDirection(yaw, elevation).scale(round.speed)
        repeat(MAX_TICKS) {
            val next = pos.add(velocity)
            val clip = level.clip(ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, shooter ?: vehicle))
            if (clip.type != HitResult.Type.MISS) return clip.location
            pos = next
            velocity = velocity.scale(DRAG).subtract(0.0, round.gravity, 0.0)
            if (pos.y < level.minY) return null
        }
        return null
    }

    /** World yaw (vanilla convention) from [from] towards [to]. */
    fun bearing(from: Vec3, to: Vec3): Float = (Mth.atan2(-(to.x - from.x), to.z - from.z) * Mth.RAD_TO_DEG).toFloat()

    /** The longest horizontal reach of [round] from [seat] (at 45°, or the closest elevation the mount allows). */
    fun maxRange(level: Level, vehicle: DriveableEntity, seat: Int, round: Round): Double {
        val s = vehicle.seat(seat) ?: return 0.0
        val yaw = vehicle.yRot
        return impact(level, vehicle, seat, yaw, 45f.coerceIn(s.minPitch, s.maxPitch), round)?.let { it.subtract(vehicle.position()).horizontalDistance() } ?: 0.0
    }

    /**
     * The laying (yaw, elevation) that drops [round] on [target] (x/z; the terrain decides the height), or null when
     * the target is out of the mount's range. Guns that can fire below 45° take the flatter trajectory (howitzers,
     * rocket launchers); mortars, which cannot, the high angle.
     */
    fun solve(level: Level, vehicle: DriveableEntity, seat: Int, target: Vec3, round: Round): Pair<Float, Float>? {
        val s = vehicle.seat(seat) ?: return null
        val origin = vehicle.position()
        val want = target.subtract(origin).horizontalDistance()
        val yaw = bearing(origin, target)
        fun reach(elevation: Float) = impact(level, vehicle, seat, yaw, elevation, round)?.subtract(origin)?.horizontalDistance() ?: Double.MAX_VALUE
        // Below 45° the reach grows as the barrel rises, above it shrinks: search each branch the mount has.
        fun branch(high: Boolean): Float? {
            var lo = if (high) maxOf(45f, s.minPitch) else s.minPitch
            var hi = if (high) s.maxPitch else minOf(45f, s.maxPitch)
            if (lo >= hi) return null
            val near = reach(if (high) hi else lo)
            val far = reach(if (high) lo else hi)
            if (want > far + 1.0 || want < near - 1.0) return null
            repeat(24) {
                val mid = (lo + hi) / 2
                // High branch: more elevation = shorter; low branch: more elevation = longer.
                if ((reach(mid) > want) == high) lo = mid else hi = mid
            }
            return (lo + hi) / 2
        }
        val elevation = (if (s.minPitch < 45f) branch(false) else null) ?: branch(true) ?: return null
        return yaw to elevation
    }
}
