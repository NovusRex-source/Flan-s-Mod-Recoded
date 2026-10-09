package com.flansmod.recoded.client.fx

import com.flansmod.recoded.client.input.GunInput
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.shotDefinition
import com.flansmod.recoded.item.gunId
import com.flansmod.recoded.network.ShotPayload
import net.minecraft.world.entity.LivingEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Purely visual: replays each bullet with the same velocity/gravity/drag as the server to draw a tracer,
 * and shows a muzzle flash. Hits and damage are never decided here.
 */
object ShotEffects {
    private class Tracer(val gun: GunDefinition, val muzzle: Vec3, var pos: Vec3, var velocity: Vec3, var ticksLeft: Int) {
        var prevPos = pos
        var travelled = 0.0
        var stopped = false
    }

    private class Flash(val pos: Vec3, var ticksLeft: Int = 1)

    private val tracers = mutableListOf<Tracer>()
    private val flashes = mutableListOf<Flash>()

    /** Number of shots received from the server (used by tests). */
    var shotsSeen = 0
        private set

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { client -> client.level?.let(::tick) ?: clear() }
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            if (tracers.isEmpty() && flashes.isEmpty()) return@register
            val camera = context.levelState().cameraRenderState.pos
            val partial = Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false)
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.lightning()) { pose, buffer ->
                tracers.forEach { draw(it, camera, partial, pose, buffer) }
                flashes.forEach { flash(it, camera, pose, buffer) }
            }
        }
    }

    private val lastShot = HashMap<Int, Long>()

    /** True for a few frames after [entityId] fired (drives the muzzle-flash bone). */
    fun recentlyFired(entityId: Int) = lastShot[entityId]?.let { System.nanoTime() - it < FLASH_NANOS } == true

    private const val FLASH_NANOS = 60_000_000L

    fun onShot(shot: ShotPayload) {
        shotsSeen++
        lastShot[shot.shooter] = System.nanoTime()
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        // Prefer the shooter's held gun so attachments (e.g. suppressors hiding tracers) apply.
        val shooter = level.getEntity(shot.shooter) as? LivingEntity
        val gun = shooter?.mainHandItem?.takeIf { it.gunId == shot.gun }?.shotDefinition ?: Guns[shot.gun] ?: return
        val muzzle = muzzlePosition(mc, level, shot)

        flashes += Flash(muzzle)
        level.addParticle(ParticleTypes.SMOKE, muzzle.x, muzzle.y, muzzle.z, 0.0, 0.02, 0.0)
        if (gun.tracer == null) return
        // The server's bullet flies from the shooter's eyes; the visible one leaves the muzzle and meets that path where
        // the bullet lands (or at the end of its range), so it never seems to come out of the camera.
        shot.directions.forEach { dir ->
            val path = dir.normalize()
            val end = shot.origin.add(path.scale(gun.velocity * gun.lifetimeTicks))
            val hit = level.clip(ClipContext(shot.origin, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()))
            val target = if (hit.type == HitResult.Type.MISS) end else hit.location
            val fromMuzzle = target.subtract(muzzle).normalize()
            tracers += Tracer(gun, muzzle, muzzle, fromMuzzle.scale(gun.velocity), gun.lifetimeTicks)
        }
    }

    /**
     * Where the flash appears: in front of the shooter's eyes, offset towards the gun on the right.
     * For the local player the offset follows the aim-down-sights blend so the flash stays at the barrel.
     */
    private fun muzzlePosition(mc: Minecraft, level: ClientLevel, shot: ShotPayload): Vec3 {
        val shooter = level.getEntity(shot.shooter)
        // Mounted guns and sentry turrets (the vehicle is the shooter): the server already sent the muzzle position.
        if (shooter is com.flansmod.recoded.entity.DriveableEntity) return shot.origin
        if (shooter is net.minecraft.world.entity.player.Player && com.flansmod.recoded.client.vehicle.VehicleClient.seatGun(shooter) != null) return shot.origin
        val look = shooter?.lookAngle ?: shot.directions.first().normalize()
        val right = look.cross(Vec3(0.0, 1.0, 0.0)).normalize()
        val up = right.cross(look).normalize()
        // Own shots in first person: the view-model muzzle, moving to the screen centre while aiming. Everyone else (and
        // yourself in third person): the gun held at the right shoulder.
        if (shooter == mc.player && mc.options.cameraType.isFirstPerson) {
            val hip = 1f - GunInput.aimProgress
            return shot.origin.add(look.scale(1.2)).add(right.scale(0.25 * hip)).add(up.scale(-0.2 * hip))
        }
        return shot.origin.add(look.scale(1.1)).add(right.scale(0.3)).add(up.scale(-0.3))
    }

    private fun tick(level: ClientLevel) {
        flashes.removeIf { --it.ticksLeft < 0 }
        tracers.removeIf { t ->
            t.prevPos = t.pos
            if (t.stopped) return@removeIf true
            var next = t.pos.add(t.velocity)
            val hit = level.clip(ClipContext(t.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()))
            if (hit.type != HitResult.Type.MISS) {
                next = hit.location
                t.stopped = true
            }
            t.travelled += next.distanceTo(t.pos)
            t.pos = next
            t.velocity = t.velocity.scale(t.gun.drag).add(0.0, -t.gun.gravity, 0.0)
            --t.ticksLeft <= 0
        }
    }

    private fun clear() {
        tracers.clear()
        flashes.clear()
    }

    private fun draw(t: Tracer, camera: Vec3, partial: Float, pose: PoseStack.Pose, buffer: VertexConsumer) {
        val style = t.gun.tracer ?: return
        val head = t.prevPos.lerp(t.pos, partial.toDouble())
        val dir = t.pos.subtract(t.prevPos).let { if (it.lengthSqr() < 1e-6) t.velocity else it }.normalize()
        // Starts at the muzzle and stretches out until it reaches its full length.
        val travelled = t.travelled - t.pos.distanceTo(t.prevPos) * (1 - partial)
        val tail = if (travelled < style.length) t.muzzle else head.subtract(dir.scale(style.length.toDouble()))
        quad(tail, head, camera, style.width, style.argb and 0x00FFFFFF or (0x40 shl 24), style.argb, pose, buffer)
    }

    private fun flash(f: Flash, camera: Vec3, pose: PoseStack.Pose, buffer: VertexConsumer) {
        val size = 0.04 + 0.03 * f.ticksLeft
        val color = 0xE0FFE8A0.toInt()
        quad(f.pos.add(-size, 0.0, 0.0), f.pos.add(size, 0.0, 0.0), camera, size.toFloat(), color, color, pose, buffer)
        quad(f.pos.add(0.0, -size, 0.0), f.pos.add(0.0, size, 0.0), camera, size.toFloat(), color, color, pose, buffer)
    }

    /** A camera-facing ribbon from [a] to [b], drawn with both windings so it is never culled. */
    private fun quad(a: Vec3, b: Vec3, camera: Vec3, width: Float, colorA: Int, colorB: Int, pose: PoseStack.Pose, buffer: VertexConsumer) {
        val axis = b.subtract(a)
        val side = axis.cross(b.subtract(camera)).normalize().scale(width / 2.0)
        if (side.lengthSqr() == 0.0) return
        val corners = listOf(a.add(side) to colorA, b.add(side) to colorB, b.subtract(side) to colorB, a.subtract(side) to colorA)
        for (order in listOf(corners, corners.reversed())) for ((p, c) in order) {
            buffer.addVertex(pose, (p.x - camera.x).toFloat(), (p.y - camera.y).toFloat(), (p.z - camera.z).toFloat()).setColor(c)
        }
    }

}
