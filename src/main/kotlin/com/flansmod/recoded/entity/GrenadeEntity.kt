package com.flansmod.recoded.entity

import com.flansmod.recoded.gun.GrenadeDefinition
import com.flansmod.recoded.item.grenadeDefinition
import com.flansmod.recoded.registry.FlansEntities
import com.flansmod.recoded.registry.FlansItems
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * A thrown grenade. Built on vanilla's throwable projectile (rendered by vanilla's ThrownItemRenderer from
 * the synced item stack); this class only adds bouncing, the fuse and the detonation effects.
 * After detonating, smoke grenades stay as an invisible emitter until the cloud has dissipated.
 */
class GrenadeEntity : ThrowableItemProjectile {
    constructor(type: EntityType<out GrenadeEntity>, level: Level) : super(type, level)
    constructor(level: Level, owner: LivingEntity, stack: ItemStack) : super(FlansEntities.GRENADE, owner, level, stack)

    private var fuse = -1
    private var smokeTicksLeft = 0
    private var detonated = false

    private val definition: GrenadeDefinition? get() = item.grenadeDefinition

    override fun getDefaultItem(): Item = FlansItems.GRENADE

    override fun getDefaultGravity(): Double = if (detonated) 0.0 else definition?.gravity ?: 0.05

    override fun tick() {
        super.tick()
        val level = level() as? ServerLevel ?: return
        val def = definition ?: return discard()

        if (detonated) {
            emitSmoke(level, def)
            return
        }
        if (fuse < 0) fuse = def.fuseTicks
        if (--fuse <= 0) detonate(level, def)
    }

    override fun onHitBlock(hit: BlockHitResult) {
        super.onHitBlock(hit)
        if (detonated) return
        val def = definition ?: return
        if (def.contact) return detonateIfServer(def)

        // Reflect the velocity on the face that was hit and lose some energy.
        val normal = Vec3.atLowerCornerOf(hit.direction.unitVec3i)
        val v = deltaMovement
        val reflected = v.subtract(normal.scale(2 * v.dot(normal))).scale(def.bounciness)
        deltaMovement = if (reflected.lengthSqr() < 0.002) Vec3.ZERO else reflected
        if (reflected.lengthSqr() > 0.01) playSound(SoundEvents.STONE_HIT, 0.3f, 1.6f)
    }

    override fun onHitEntity(hit: EntityHitResult) {
        super.onHitEntity(hit)
        val def = definition ?: return
        if (def.contact) detonateIfServer(def) else deltaMovement = deltaMovement.scale(-0.2)
    }

    // Default ThrowableItemProjectile behaviour discards on any hit; grenades only end by detonating.
    override fun onHit(result: HitResult) {
        if (detonated) return
        when (result) {
            is BlockHitResult -> onHitBlock(result)
            is EntityHitResult -> onHitEntity(result)
        }
    }

    private fun detonateIfServer(def: GrenadeDefinition) {
        (level() as? ServerLevel)?.let { detonate(it, def) }
    }

    private fun detonate(level: ServerLevel, def: GrenadeDefinition) {
        if (detonated) return
        detonated = true
        deltaMovement = Vec3.ZERO
        def.detonateSound?.let { level.playSound(null, x, y, z, SoundEvent.createVariableRangeEvent(it), SoundSource.PLAYERS, 1f, 1f) }

        def.explosion?.let { e ->
            level.explode(this, x, y, z, e.power, e.fire, if (e.breakBlocks) Level.ExplosionInteraction.TNT else Level.ExplosionInteraction.NONE)
        }
        def.flash?.let { flash(level, it) }

        smokeTicksLeft = def.smoke?.durationTicks ?: 0
        if (smokeTicksLeft <= 0) discard()
    }

    private fun flash(level: ServerLevel, flash: GrenadeDefinition.Flash) {
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, -1), x, y + 0.2, z, 1, 0.0, 0.0, 0.0, 0.0)
        level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 2f, 1.8f)
        val here = position().add(0.0, 0.2, 0.0)
        for (target in level.getEntitiesOfClass(LivingEntity::class.java, boundingBox.inflate(flash.radius))) {
            if (target.distanceToSqr(here) > flash.radius * flash.radius) continue
            val clear = level.clip(ClipContext(target.eyePosition, here, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, target)).type == HitResult.Type.MISS
            if (!clear) continue
            // Shorter blindness for those far away.
            val strength = 1 - target.distanceToSqr(here) / (flash.radius * flash.radius)
            target.addEffect(MobEffectInstance(MobEffects.BLINDNESS, (flash.durationTicks * strength).toInt().coerceAtLeast(20)), getOwner())
            target.addEffect(MobEffectInstance(MobEffects.NAUSEA, (flash.durationTicks * strength).toInt().coerceAtLeast(20)), getOwner())
        }
    }

    private fun emitSmoke(level: ServerLevel, def: GrenadeDefinition) {
        val smoke = def.smoke ?: return discard()
        val spread = smoke.radius * minOf(1.0, (smoke.durationTicks - smokeTicksLeft + 20) / 60.0) / 2
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y + 0.5, z, 4 + smoke.radius.toInt() * 2, spread, spread / 2, spread, 0.01)
        if (--smokeTicksLeft <= 0) discard()
    }

    /** True once the grenade went off (used by tests). */
    val hasDetonated get() = detonated

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        output.putInt("Fuse", fuse)
        output.putInt("SmokeTicks", smokeTicksLeft)
        output.putBoolean("Detonated", detonated)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        fuse = input.getIntOr("Fuse", -1)
        smokeTicksLeft = input.getIntOr("SmokeTicks", 0)
        detonated = input.getBooleanOr("Detonated", false)
    }
}
