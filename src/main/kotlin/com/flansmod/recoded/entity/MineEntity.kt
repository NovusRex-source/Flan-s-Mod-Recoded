package com.flansmod.recoded.entity

import com.flansmod.recoded.combat.Incendiary
import com.flansmod.recoded.gun.GrenadeDefinition
import com.flansmod.recoded.gun.PartRole
import com.flansmod.recoded.gun.VehicleType
import com.flansmod.recoded.item.grenadeDefinition
import com.flansmod.recoded.registry.FlansEntities
import com.flansmod.recoded.registry.FlansItems
import net.minecraft.core.UUIDUtil
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ItemSupplier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * A mine lying on the ground (placed from a grenade item whose definition has a `mine` section). Arms after
 * `arm_ticks`, then goes off when its trigger passes over it: anyone for anti-personnel mines, only vehicles for
 * anti-tank mines (which also wreck the wheel/track above them). Explosions and gunfire set it off too; sneak + right
 * click with an empty hand defuses it. Rendered like a dropped item, lying flat (client `MineRenderer`).
 */
class MineEntity(type: EntityType<out MineEntity>, level: Level) : Entity(type, level), ItemSupplier {
    constructor(level: Level, stack: ItemStack, at: Vec3, yaw: Float, owner: Entity?) : this(FlansEntities.MINE, level) {
        setItem(stack.copyWithCount(1))
        setPos(at)
        yRot = yaw
        this.owner = owner?.uuid
    }

    private var owner: UUID? = null
    private var age = 0
    private var exploded = false

    private val definition: GrenadeDefinition? get() = getItem().grenadeDefinition
    val armed: Boolean get() = age >= (definition?.mine?.armTicks ?: 0)

    override fun getItem(): ItemStack = entityData.get(ITEM)
    private fun setItem(stack: ItemStack) = entityData.set(ITEM, stack)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(ITEM, ItemStack(FlansItems.GRENADE))
    }

    override fun isPickable() = !isRemoved
    override fun getDefaultGravity() = 0.04

    override fun tick() {
        super.tick()
        if (!onGround()) {
            deltaMovement = deltaMovement.add(0.0, -gravity, 0.0)
            move(MoverType.SELF, deltaMovement)
            deltaMovement = deltaMovement.scale(0.5)
        }
        val level = level() as? ServerLevel ?: return
        val def = definition ?: return discard()
        val mine = def.mine ?: return discard()
        age++
        if (age == mine.armTicks) level.playSound(null, x, y, z, SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.BLOCKS, 0.4f, 1.6f)
        if (!armed) return
        val area = AABB(x - mine.radius, y - 0.2, z - mine.radius, x + mine.radius, y + 1.0, z + mine.radius)
        // Vehicles set off every mine; people (not riding a vehicle - that one counts) only anti-personnel mines.
        val victim = level.getEntities(this, area) { e ->
            when (e) {
                is DriveableEntity -> e.definition?.type != VehicleType.STATIC
                is LivingEntity -> mine.trigger == GrenadeDefinition.Mine.Trigger.PERSONNEL && e.isAlive && !e.isSpectator && e.vehicle !is DriveableEntity
                else -> false
            }
        }.firstOrNull() ?: return
        detonate(level, def, victim as? DriveableEntity)
    }

    /** Blows up; [vehicle] (the one that drove onto it) also loses the wheel/track part above the mine. */
    fun detonate(level: ServerLevel, def: GrenadeDefinition? = definition, vehicle: DriveableEntity? = null) {
        if (exploded || def == null) return
        exploded = true
        discard()
        val source = level.damageSources().explosion(this, owner?.let(level::getEntity) as? LivingEntity)
        val mine = def.mine
        if (vehicle != null && mine != null && mine.vehicleDamage > 0f) {
            val local = vehicle.toLocal(position())
            vehicle.definition?.parts?.filterValues { it.role == PartRole.PROPULSION }?.minByOrNull { (_, p) -> p.center.distanceToSqr(local) }
                ?.let { (part, _) -> vehicle.hurtPart(level, source, mine.vehicleDamage, part) }
        }
        def.explosion?.let { e ->
            level.explode(this, x, y + 0.1, z, e.power, e.fire, if (e.breakBlocks) Level.ExplosionInteraction.TNT else Level.ExplosionInteraction.NONE)
            if (e.fire) Incendiary.spreadFire(level, position(), e.power.toDouble() + 1.0, random)
        }
    }

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (isRemoved || amount <= 0f) return false
        detonate(level)
        return true
    }

    /** Sneak + empty hand: defuse and pick it up. */
    override fun interact(player: Player, hand: InteractionHand, location: Vec3): InteractionResult {
        if (!player.isSecondaryUseActive || !player.getItemInHand(hand).isEmpty) return InteractionResult.PASS
        if (level() is ServerLevel && !isRemoved) {
            player.inventory.placeItemBackInInventory(getItem().copy(), net.minecraft.util.Prediction.SERVER_ONLY)
            playSound(SoundEvents.TRIPWIRE_CLICK_OFF, 0.5f, 1.2f)
            discard()
        }
        return InteractionResult.SUCCESS
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.store("Item", ItemStack.CODEC, getItem())
        owner?.let { output.store("Owner", UUIDUtil.CODEC, it) }
        output.putInt("Age", age)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        input.read("Item", ItemStack.CODEC).ifPresent(::setItem)
        owner = input.read("Owner", UUIDUtil.CODEC).orElse(null)
        age = input.getIntOr("Age", 0)
    }

    companion object {
        private val ITEM: EntityDataAccessor<ItemStack> = SynchedEntityData.defineId(MineEntity::class.java, EntityDataSerializers.ITEM_STACK)
    }
}
