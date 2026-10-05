package com.flansmod.recoded.entity

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.Seat
import com.flansmod.recoded.gun.VehicleDefinition
import com.flansmod.recoded.gun.VehicleType
import com.flansmod.recoded.gun.Vehicles
import com.flansmod.recoded.gun.VehicleUpgrades
import com.flansmod.recoded.gun.PartRole
import com.flansmod.recoded.item.VehicleUpgradeItem
import com.flansmod.recoded.item.vehicleUpgradeDefinition
import com.flansmod.recoded.item.vehicleUpgradeId
import com.flansmod.recoded.registry.FlansComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.AABB
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import kotlin.math.atan2
import com.flansmod.recoded.gun.vec
import com.flansmod.recoded.item.VehicleItem
import com.flansmod.recoded.registry.FlansDamageTypes
import com.flansmod.recoded.registry.FlansEntities
import com.geckolib.animatable.GeoEntity
import com.geckolib.animatable.instance.AnimatableInstanceCache
import com.geckolib.animatable.manager.AnimatableManager
import com.geckolib.util.GeckoLibUtil
import com.mojang.serialization.Codec
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricEntityDataRegistry
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializer
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.DamageTypeTags
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.InterpolationHandler
import net.minecraft.world.entity.LinearInterpolationHandler
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.MoverType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.vehicle.DismountHelper
import net.minecraft.world.entity.vehicle.VehicleEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemUtils
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.sign

/**
 * A content-pack vehicle (car, tank, ...). Built on vanilla's [VehicleEntity] like boats and minecarts: the driver's
 * client simulates movement and vanilla syncs it to the server (`ServerboundMoveVehiclePacket`), exactly as for
 * boats; without a player driver the server simulates. Fuel, damage, upgrades, seats and mounted-gun magazines are
 * decided by the server and synced with entity data. Hits are resolved per part ([raycastParts]): parts have their own
 * health and break with effects (engine, wheels/tracks, gun mounts, fuel tank).
 */
class DriveableEntity(type: EntityType<out DriveableEntity>, level: Level) : VehicleEntity(type, level), GeoEntity {
    /** Throttle (-1..1), steering (-1..1, positive = left, like [LivingEntity.xxa]) and brake. */
    data class Controls(val throttle: Float, val steer: Float, val brake: Boolean) {
        companion object {
            val NONE = Controls(0f, 0f, false)
        }
    }

    constructor(level: Level, id: Identifier, pos: Vec3, yaw: Float) : this(FlansEntities.DRIVEABLE, level) {
        vehicleId = id
        setPos(pos)
        xo = pos.x; yo = pos.y; zo = pos.z
        setYRot(yaw)
        yRotO = yaw
        boundingBox = makeBoundingBox()
    }

    var vehicleId: Identifier?
        get() = entityData.get(VEHICLE).takeIf { it.isNotEmpty() }?.let(Identifier::tryParse)
        set(value) = entityData.set(VEHICLE, value?.toString() ?: "")

    /** The vehicle as defined by its content pack, without upgrades. */
    val baseDefinition: VehicleDefinition? get() = Vehicles[vehicleId]

    private var cachedDefinition: Triple<VehicleDefinition, Map<String, Identifier>, VehicleDefinition>? = null

    /** Effective values: the base definition with all installed upgrades applied. */
    val definition: VehicleDefinition?
        get() {
            val base = baseDefinition ?: return null
            val installed = upgrades
            cachedDefinition?.let { (b, u, effective) -> if (b === base && u == installed) return effective }
            return base.withUpgrades(installed.values.mapNotNull { VehicleUpgrades[it] }).also { cachedDefinition = Triple(base, installed, it) }
        }

    /** Installed upgrades by slot. */
    var upgrades: Map<String, Identifier>
        get() = entityData.get(UPGRADES)
        set(value) = entityData.set(UPGRADES, value)

    /** Damage taken so far, by part name; [HULL] is the hull. Stored as damage so upgrades can change maximum health. */
    var damage: Map<String, Float>
        get() = entityData.get(DAMAGE)
        set(value) = entityData.set(DAMAGE, value.filterValues { it > 0f })

    /** Hull health; the vehicle is destroyed at 0. */
    var health: Float
        get() = (definition?.health ?: 0f) - (damage[HULL] ?: 0f)
        set(value) {
            damage = damage + (HULL to (definition?.health ?: 0f) - value)
        }

    fun partHealth(name: String): Float = (definition?.parts?.get(name)?.health ?: 0f) - (damage[name] ?: 0f)

    fun isBroken(name: String): Boolean = definition?.parts?.get(name)?.let { partHealth(name) <= 0f } == true

    /** Fraction of wheels/tracks still working (1 without propulsion parts). */
    val propulsion: Float
        get() = definition?.propulsion?.takeIf { it.isNotEmpty() }?.let { parts -> parts.count { !isBroken(it) }.toFloat() / parts.size } ?: 1f

    val engineWorks: Boolean
        get() = definition?.parts?.none { (name, p) -> p.role == PartRole.ENGINE && isBroken(name) } ?: true

    /** Whether the gun of [seat] can fire (its weapon part, if any, is intact). */
    fun weaponWorks(seat: Int): Boolean =
        definition?.parts?.none { (name, p) -> p.role == PartRole.WEAPON && p.seat == seat && isBroken(name) } ?: true

    var fuel: Int
        get() = entityData.get(FUEL)
        set(value) = entityData.set(FUEL, value.coerceAtLeast(0))

    /** Entity id in each seat (-1 = free). Seat 0 is the driver's. */
    private var seats: List<Int>
        get() = entityData.get(SEATS)
        set(value) = entityData.set(SEATS, value)

    /** Magazine inserted in each seat's gun, by seat index. */
    var seatMagazines: Map<Int, MagazineContents>
        get() = entityData.get(MAGAZINES)
        private set(value) = entityData.set(MAGAZINES, value)

    /** Forward speed in blocks per tick, only meaningful on the simulating side. */
    var speed = 0.0
        private set

    /** Controls used when there is no player driver (tests, future AI); null = coast. */
    var autopilot: Controls? = null

    // Client visuals: accumulated wheel rotation (radians) and steering, derived from movement on every client.
    var wheelSpin = 0f; private set
    var prevWheelSpin = 0f; private set
    var steering = 0f; private set
    var prevSteering = 0f; private set

    private var deltaYaw = 0f
    private var lastServerPos: Vec3? = null
    private var groundSpeed = 0.0
    private var destroyed = false

    private val cache = GeckoLibUtil.createInstanceCache(this)

    init {
        blocksBuilding = true
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        super.defineSynchedData(builder)
        builder.define(VEHICLE, "")
        builder.define(DAMAGE, emptyMap())
        builder.define(UPGRADES, emptyMap())
        builder.define(FUEL, 0)
        builder.define(SEATS, emptyList())
        builder.define(MAGAZINES, emptyMap())
    }

    override fun onSyncedDataUpdated(accessor: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(accessor)
        if (accessor == VEHICLE || accessor == UPGRADES) {
            refreshDimensions()
            boundingBox = makeBoundingBox()
        }
    }

    override fun getDimensions(pose: Pose): EntityDimensions =
        definition?.let { EntityDimensions.scalable(it.width, it.height) } ?: super.getDimensions(pose)

    /**
     * Vanilla bounding boxes cannot rotate, so this is the axis-aligned box around the parts turned with the hull:
     * long vehicles are long in the direction they face instead of a square footprint.
     */
    override fun makeBoundingBox(position: Vec3): AABB {
        val (min, max) = definition?.bounds ?: return super.makeBoundingBox(position)
        val corners = listOf(min.x, max.x).flatMap { x -> listOf(min.z, max.z).map { z -> flatToWorld(Vec3(x, 0.0, z)) } }
        return AABB(corners.minOf { it.x }, min.y, corners.minOf { it.z }, corners.maxOf { it.x }, max.y, corners.maxOf { it.z }).move(position)
    }

    fun makeBoundingBoxAt(position: Vec3): AABB = makeBoundingBox(position)

    override fun maxUpStep(): Float = definition?.stepHeight ?: 0.6f

    override fun getDefaultGravity() = 0.08

    override fun createInterpolationHandler(): InterpolationHandler = LinearInterpolationHandler.create(this, 3)

    // Solid like boats: players can stand on it, it blocks other vehicles, but it is too heavy to be pushed around.
    override fun canBeCollidedWith(other: Entity?) = true
    override fun canCollideWith(entity: Entity) = (entity.canBeCollidedWith(this) || entity.isPushable) && !isPassengerOfSameVehicle(entity)
    override fun isPushable() = false
    override fun isPickable() = !isRemoved

    override fun getDropItem(): Item = Items.AIR
    override fun getPickResult(): ItemStack = VehicleItem.stackFor(vehicleId)

    /** This vehicle as an item: keeps fuel, upgrades and damage. */
    fun toItem(): ItemStack = VehicleItem.stackFor(vehicleId, fuel).apply {
        if (upgrades.isNotEmpty()) set(FlansComponents.VEHICLE_UPGRADES, upgrades)
        if (damage.isNotEmpty()) set(FlansComponents.VEHICLE_DAMAGE, damage)
    }

    // ------------------------------------------------------------------------------------------------- seats

    fun seatOf(passenger: Entity): Int = seats.indexOf(passenger.id).takeIf { it >= 0 }
        ?: passengers.indexOf(passenger) // client before the seat data arrived

    fun seat(index: Int): Seat? = definition?.seats?.getOrNull(index)

    fun occupant(index: Int): Entity? =
        if (seats.isEmpty()) passengers.getOrNull(index) // client before the seat data arrived
        else seats.getOrNull(index)?.takeIf { it >= 0 }?.let { id -> passengers.firstOrNull { it.id == id } }

    override fun getControllingPassenger(): LivingEntity? = occupant(0) as? Player

    override fun canAddPassenger(passenger: Entity) = passengers.size < (definition?.seats?.size ?: 0)

    override fun addPassenger(passenger: Entity) {
        super.addPassenger(passenger)
        if (level().isClientSide()) return
        val count = definition?.seats?.size ?: return
        val current = seats.toMutableList().apply { while (size < count) add(-1) }
        // Players take the wheel if it is free; everyone else fills the remaining seats in order.
        val free = current.indices.filter { current[it] < 0 }
        val index = free.firstOrNull { it == 0 && passenger is Player } ?: free.firstOrNull { it != 0 } ?: free.firstOrNull() ?: return
        current[index] = passenger.id
        seats = current
    }

    override fun removePassenger(passenger: Entity) {
        super.removePassenger(passenger)
        if (!level().isClientSide()) seats = seats.map { if (it == passenger.id) -1 else it }
    }

    /** Vehicle space `[right, up, forward]` to a world offset, ignoring the body's tilt (bounding box, ground probes). */
    fun flatToWorld(local: Vec3, yaw: Float = yRot): Vec3 = Vec3(-local.x, local.y, local.z).yRot(-yaw * Mth.DEG_TO_RAD)

    /**
     * Vehicle space `[right, up, forward]` to a world offset from [position], following the body on uneven ground:
     * rolled ([bodyRoll], left side up), pitched ([bodyPitch], nose up), turned and lowered by [bodySink].
     * Seats, gun pivots, part hit boxes and the model all use this, on both sides.
     */
    fun toWorld(local: Vec3): Vec3 = Vec3(-local.x, local.y, local.z)
        .zRot(-bodyRoll * Mth.DEG_TO_RAD).xRot(bodyPitch * Mth.DEG_TO_RAD).yRot(-yRot * Mth.DEG_TO_RAD).add(0.0, bodySink.toDouble(), 0.0)

    // ------------------------------------------------------------------------------------------------- body on terrain

    /** Body tilt in degrees (nose up / left side up) and how far the body sits below [position] (half on a step). */
    var bodyPitch = 0f; private set
    var bodyRoll = 0f; private set
    var bodySink = 0f; private set
    var prevBodyPitch = 0f; private set
    var prevBodyRoll = 0f; private set
    var prevBodySink = 0f; private set

    /**
     * The vehicle's collision box stays level (vanilla boxes cannot tilt), but the body rests on its wheels: the ground
     * under the four corners of the wheels/tracks decides pitch, roll and height. Driving up a block the box steps up at
     * once while the rear wheels are still below, so the body sits nose-up and half a block lower until they follow.
     */
    private fun updateBodyPose(def: VehicleDefinition) {
        prevBodyPitch = bodyPitch; prevBodyRoll = bodyRoll; prevBodySink = bodySink
        val (min, max) = def.contactArea
        val reach = def.stepHeight + 0.5
        fun ground(right: Double, forward: Double): Double {
            val at = position().add(flatToWorld(Vec3(right, 0.0, forward)))
            val hit = level().clip(ClipContext(at.add(0.0, def.stepHeight + 0.1, 0.0), at.subtract(0.0, reach, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
            return if (hit.type == HitResult.Type.MISS) -reach else hit.location.y - y
        }
        val fl = ground(min.x, max.z); val fr = ground(max.x, max.z); val rl = ground(min.x, min.z); val rr = ground(max.x, min.z)
        // In the air (falling, jumping a ledge) the body hangs level on its springs.
        val airborne = !onGround() || listOf(fl, fr, rl, rr).all { it <= -reach + 0.01 }
        val (pitch, roll, sink) = if (airborne) Triple(0f, 0f, 0f) else Triple(
            Math.toDegrees(atan2((fl + fr - rl - rr) / 2, max.z - min.z)).toFloat().coerceIn(-MAX_TILT, MAX_TILT),
            Math.toDegrees(atan2((fl + rl - fr - rr) / 2, max.x - min.x)).toFloat().coerceIn(-MAX_TILT, MAX_TILT),
            ((fl + fr + rl + rr) / 4).toFloat().coerceIn(-def.stepHeight, def.stepHeight),
        )
        // Springy, not instant.
        bodyPitch = Mth.lerp(SUSPENSION, bodyPitch, pitch)
        bodyRoll = Mth.lerp(SUSPENSION, bodyRoll, roll)
        bodySink = Mth.lerp(SUSPENSION, bodySink, sink)
    }

    override fun getPassengerAttachmentPoint(passenger: Entity, dimensions: EntityDimensions, scale: Float): Vec3 =
        seat(seatOf(passenger))?.offset?.let(::toWorld) ?: super.getPassengerAttachmentPoint(passenger, dimensions, scale)

    override fun positionRider(passenger: Entity, moveFunction: MoveFunction) {
        super.positionRider(passenger, moveFunction)
        // Riders turn with the vehicle (like boats); only the side that owns the rider's rotation applies it.
        if (passenger.isLocalInstanceAuthoritative && deltaYaw != 0f) {
            passenger.yRot += deltaYaw
            passenger.yHeadRot += deltaYaw
        }
        clampRotation(passenger)
    }

    override fun onPassengerTurned(passenger: Entity) = clampRotation(passenger)

    /** Seated riders face forward and look around up to [MAX_HEAD_TURN]; gunners turn with their (turret) gun. */
    private fun clampRotation(passenger: Entity) {
        if (passenger !is LivingEntity || seat(seatOf(passenger))?.turret == true) return
        passenger.setYBodyRot(yRot)
        val relative = Mth.wrapDegrees(passenger.yRot - yRot)
        val clamped = relative.coerceIn(-MAX_HEAD_TURN, MAX_HEAD_TURN)
        if (clamped != relative) {
            passenger.yRotO += clamped - relative
            passenger.yRot += clamped - relative
        }
        passenger.yHeadRot = passenger.yRot
    }

    override fun getDismountLocationForPassenger(passenger: LivingEntity): Vec3 {
        val escape = getCollisionHorizontalEscapeVector(bbWidth * Mth.SQRT_OF_TWO.toDouble(), passenger.bbWidth.toDouble(), passenger.yRot)
        val column = BlockPos.containing(x + escape.x, y, z + escape.z)
        for (dy in listOf(0, 1, -1, 2)) {
            DismountHelper.findSafeDismountLocation(passenger.type, level(), column.above(dy), true)?.let { return it }
        }
        return super.getDismountLocationForPassenger(passenger)
    }

    // ------------------------------------------------------------------------------------------------- interaction

    override fun interact(player: Player, hand: InteractionHand, location: Vec3): InteractionResult {
        val def = definition ?: return InteractionResult.PASS
        val stack = player.getItemInHand(hand)
        val fuelValue = def.fuel.items[BuiltInRegistries.ITEM.getKey(stack.item)]
        when {
            stack.item is VehicleUpgradeItem -> return if (installUpgrade(player, hand)) InteractionResult.SUCCESS else InteractionResult.FAIL
            BuiltInRegistries.ITEM.getKey(stack.item) == def.repair.item -> return if (repair(player, hand)) InteractionResult.SUCCESS else InteractionResult.FAIL
            fuelValue != null && def.needsFuel -> {
                if (fuel + fuelValue > def.fuel.capacity) return InteractionResult.FAIL
                if (!level().isClientSide()) {
                    fuel += fuelValue
                    val remainder = stack.item.craftingRemainder?.create()
                    player.setItemInHand(hand, if (remainder != null) ItemUtils.createFilledResult(stack, player, remainder) else stack.apply { consume(1, player) })
                    playSound(SoundEvents.BUCKET_EMPTY, 0.6f, 1.2f)
                }
                return InteractionResult.SUCCESS
            }
            // Sneak + empty hand on an empty vehicle: pick it up (keeps fuel, upgrades and damage).
            player.isSecondaryUseActive && stack.isEmpty && passengers.isEmpty() -> {
                if (level() is ServerLevel) {
                    player.inventory.placeItemBackInInventory(toItem(), net.minecraft.util.Prediction.SERVER_ONLY)
                    discard()
                }
                return InteractionResult.SUCCESS
            }
            player.isSecondaryUseActive -> return InteractionResult.PASS
        }
        if (!canAddPassenger(player)) return InteractionResult.FAIL
        return if (level().isClientSide() || player.startRiding(this)) InteractionResult.SUCCESS else InteractionResult.PASS
    }

    /** Installs the held upgrade (a previous one in the same slot goes back to the player). */
    fun installUpgrade(player: Player, hand: InteractionHand): Boolean {
        val stack = player.getItemInHand(hand)
        val upgradeId = stack.vehicleUpgradeId ?: return false
        val upgrade = stack.vehicleUpgradeDefinition ?: return false
        val id = vehicleId ?: return false
        val base = baseDefinition ?: return false
        if (!upgrade.fits(id, base)) {
            player.sendOverlayMessage(Component.translatable("message.flansmod.vehicle_upgrade.incompatible", stack.hoverName))
            return false
        }
        if (upgrades[upgrade.slot] == upgradeId) return false
        if (level() is ServerLevel) {
            upgrades[upgrade.slot]?.let { player.inventory.placeItemBackInInventory(VehicleUpgradeItem.stackFor(it), net.minecraft.util.Prediction.SERVER_ONLY) }
            upgrades = upgrades + (upgrade.slot to upgradeId)
            stack.consume(1, player)
            playSound(SoundEvents.SMITHING_TABLE_USE, 0.8f, 1f)
        }
        return true
    }

    /** Repairs the hull and every part by the definition's repair amount, using one repair item. */
    fun repair(player: Player, hand: InteractionHand): Boolean {
        val def = definition ?: return false
        if (damage.isEmpty()) return false
        if (level() is ServerLevel) {
            damage = damage.mapValues { (_, d) -> d - def.repair.amount }
            player.getItemInHand(hand).consume(1, player)
            playSound(SoundEvents.ANVIL_USE, 0.5f, 1.4f)
        }
        return true
    }

    // ------------------------------------------------------------------------------------------------- movement

    override fun tick() {
        if (hurtTime > 0) hurtTime--
        super.tick()
        val def = definition
        if (def == null) {
            deltaMovement = Vec3.ZERO
            return
        }
        val yawBefore = yRot
        if (isLocalInstanceAuthoritative) {
            simulate(def, controls())
            // Turning swings the (rotated) box: refuse turns that would push it into blocks.
            val turned = makeBoundingBox()
            if (yRot != yawBefore && !level().noCollision(this, turned.deflate(0.05))) yRot = yawBefore
            boundingBox = makeBoundingBox()
            move(MoverType.SELF, deltaMovement)
            if (horizontalCollision) speed *= 0.3
        } else {
            deltaMovement = Vec3.ZERO
            boundingBox = makeBoundingBox()
        }
        deltaYaw = if (isLocalInstanceAuthoritative) Mth.wrapDegrees(yRot - yawBefore) else Mth.wrapDegrees(yRot - yRotO)
        updateBodyPose(def)
        applyEffectsFromBlocks()

        if (level().isClientSide()) tickVisuals() else tickServer(level() as ServerLevel, def)
    }

    /** The driver's input; their client sets [LivingEntity.xxa]/[LivingEntity.zza] from the movement keys. */
    private fun controls(): Controls {
        val driver = controllingPassenger as? Player ?: return autopilot ?: Controls.NONE
        return Controls(driver.zza.sign, driver.xxa.sign, driver.isJumping)
    }

    val hasFuel: Boolean
        get() = definition?.needsFuel != true || fuel > 0 || (controllingPassenger as? Player)?.hasInfiniteMaterials() == true

    /** One tick of driving physics; runs on whichever side owns the movement. */
    private fun simulate(def: VehicleDefinition, c: Controls) {
        val drive = propulsion
        val engine = c.throttle != 0f && hasFuel && engineWorks && drive > 0f
        speed = when {
            // Throttle against the motion brakes harder than the engine accelerates.
            engine -> speed + c.throttle * def.acceleration * if (sign(speed).toFloat() != c.throttle.sign && speed != 0.0) 2 else 1
            else -> speed * (1 - def.drag)
        }
        if (c.brake) speed = Mth.approach(speed.toFloat(), 0f, def.braking.toFloat()).toDouble()
        // Lost wheels/tracks cost their share of top speed.
        val factor = (if (isInWater) def.waterSpeed else 1.0) * drive
        speed = speed.coerceIn(-def.maxReverseSpeed * factor, def.maxSpeed * factor)
        if (abs(speed) < 0.002 && !engine) speed = 0.0

        val turn = when (def.type) {
            // Wheels need rolling to steer; full lock is reached at a third of top speed. Reversing flips it.
            VehicleType.CAR -> c.steer * def.turnSpeed * (abs(speed) / (def.maxSpeed / 3)).coerceAtMost(1.0).toFloat() * sign(speed).toFloat()
            VehicleType.TANK -> if (hasFuel && engineWorks) c.steer * def.turnSpeed * drive else 0f
        }
        yRot -= turn

        val forward = Vec3.directionFromRotation(0f, yRot)
        // Always pushed down a little, like vanilla entities: otherwise move() would not see the ground every other tick.
        var vy = if (onGround()) -gravity else (deltaMovement.y - gravity) * 0.98
        if (isInWater) vy = vy * 0.5 - 0.01
        deltaMovement = forward.scale(speed).add(0.0, vy, 0.0)
    }

    private fun tickVisuals() {
        smoke()
        prevWheelSpin = wheelSpin
        prevSteering = steering
        val moved = position().subtract(xo, yo, zo)
        val forward = Vec3.directionFromRotation(0f, yRot)
        wheelSpin += (moved.dot(forward) / WHEEL_RADIUS).toFloat()
        steering = Mth.lerp(0.5f, steering, (Mth.wrapDegrees(yRot - yRotO) / 4f).coerceIn(-1f, 1f) * if (moved.dot(forward) < 0) -1 else 1)
    }

    private fun tickServer(level: ServerLevel, def: VehicleDefinition) {
        val pos = position()
        groundSpeed = lastServerPos?.let { pos.subtract(it).horizontalDistance() } ?: 0.0
        lastServerPos = pos

        val driver = controllingPassenger as? ServerPlayer
        val throttle = when {
            driver != null -> driver.lastClientInput.let { it.forward() || it.backward() }
            else -> autopilot?.throttle?.let { it != 0f } == true
        }
        if (throttle && def.needsFuel && driver?.hasInfiniteMaterials() != true) fuel -= def.fuelPerTick
        // A holed fuel tank leaks.
        if (def.parts.any { (name, p) -> p.role == PartRole.FUEL_TANK && isBroken(name) }) fuel -= FUEL_LEAK
        if (groundSpeed > 0.15) runOver(level, def)
    }

    /** Mobs in the way take damage scaled by speed and are thrown aside. */
    private fun runOver(level: ServerLevel, def: VehicleDefinition) {
        val type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(FlansDamageTypes.VEHICLE)
        val source = DamageSource(type, this, controllingPassenger)
        for (target in level.getEntitiesOfClass(LivingEntity::class.java, boundingBox.inflate(0.2)) { !isPassengerOfSameVehicle(it) && it.isAlive }) {
            if (target.hurtServer(level, source, (def.collisionDamage * groundSpeed).toFloat())) {
                val away = target.position().subtract(position()).horizontal().normalize()
                target.push(away.x * groundSpeed, 0.3 * groundSpeed, away.z * groundSpeed)
            }
        }
    }

    /** Client: smoke from broken parts, and fire once the hull is badly damaged. */
    private fun smoke() {
        val def = definition ?: return
        for ((name, part) in def.parts) {
            if (!isBroken(name) || random.nextInt(4) != 0) continue
            val at = position().add(toWorld(part.center))
            level().addParticle(ParticleTypes.SMOKE, at.x, at.y + 0.3, at.z, 0.0, 0.04, 0.0)
        }
        if (health < def.health * 0.25f && random.nextInt(3) == 0) {
            val at = position().add(toWorld(def.bounds.let { (a, b) -> a.add(b).scale(0.5) }))
            level().addParticle(ParticleTypes.LARGE_SMOKE, at.x, y + bbHeight, at.z, 0.0, 0.06, 0.0)
            if (random.nextInt(3) == 0) level().addParticle(ParticleTypes.FLAME, at.x, y + bbHeight * 0.8, at.z, 0.0, 0.02, 0.0)
        }
    }

    // ------------------------------------------------------------------------------------------------- damage

    /** World position to vehicle space `[right, up, forward]` (inverse of [toWorld] + position). */
    fun toLocal(world: Vec3): Vec3 = world.subtract(position()).subtract(0.0, bodySink.toDouble(), 0.0)
        .yRot(yRot * Mth.DEG_TO_RAD).xRot(-bodyPitch * Mth.DEG_TO_RAD).zRot(bodyRoll * Mth.DEG_TO_RAD).let { Vec3(-it.x, it.y, it.z) }

    /** The first part a segment passes through, and where (world space); [HULL] for vehicles without parts. */
    fun raycastParts(from: Vec3, to: Vec3): Pair<String, Vec3>? {
        val def = definition ?: return null
        if (def.parts.isEmpty()) return boundingBox.clip(from, to).orElse(null)?.let { HULL to it }
        val a = toLocal(from)
        val b = toLocal(to)
        return def.parts.mapNotNull { (name, p) -> AABB(p.min, p.max).clip(a, b).orElse(null)?.let { name to it } }
            .minByOrNull { (_, hit) -> hit.distanceToSqr(a) }
            ?.let { (name, hit) -> name to position().add(toWorld(hit)) }
    }

    /** A shot along [from]→[to]: damages the part it passes through. False if it misses every part (gaps, open tops). */
    fun hurtAlong(level: ServerLevel, source: DamageSource, amount: Float, from: Vec3, to: Vec3): Boolean {
        val (part, _) = raycastParts(from, to) ?: return false
        return hurtPart(level, source, amount, part)
    }

    /** Untargeted damage (explosions, melee, projectiles): hits the part closest to where it came from. */
    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        val parts = definition?.parts.orEmpty()
        val from = source.sourcePosition
        val part = if (from == null || parts.isEmpty()) HULL else {
            val local = toLocal(from)
            parts.minBy { (_, p) -> p.center.distanceToSqr(local) }.key
        }
        return hurtPart(level, source, amount, part)
    }

    /**
     * Damages [part] (or the hull). Armour absorbs its share unless the round is armour piercing or an explosion; non-hull
     * parts pass [com.flansmod.recoded.gun.VehiclePart.coreDamage] of the damage on to the hull.
     */
    fun hurtPart(level: ServerLevel, source: DamageSource, amount: Float, part: String): Boolean {
        if (isRemoved || destroyed) return true
        if (isInvulnerableToBase(source)) return false
        // Your own crew's bullets and the explosions of your own shells near the vehicle do not hurt it.
        if (source.entity?.let(::isPassengerOfSameVehicle) == true || source.directEntity == this) return false
        // Creative players remove vehicles by punching them (like boats), not by shooting them.
        val creative = (source.entity as? Player)?.abilities?.instabuild == true
        if (creative && source.`is`(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK)) {
            discard()
            return true
        }
        val def = definition ?: return false
        val p = def.parts[part]
        val penetrates = source.`is`(DamageTypeTags.IS_EXPLOSION) || source.`is`(FlansDamageTypes.GUN_AP)
        val dealt = amount * if (penetrates) 1f else 1f - (p?.armor ?: def.armor).coerceIn(0f, 1f)
        hurtDir = -hurtDir
        hurtTime = 10
        markHurt()
        val wasBroken = isBroken(part)
        val hull = if (p == null || p.role == PartRole.HULL) dealt else dealt * p.coreDamage
        damage = damage.toMutableMap().apply {
            if (p != null && p.role != PartRole.HULL) merge(part, dealt, Float::plus)
            merge(HULL, hull, Float::plus)
        }
        if (!wasBroken && isBroken(part)) {
            val at = position().add(toWorld(p!!.center))
            level.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_BREAK.value(), SoundSource.NEUTRAL, 1.2f, 0.6f)
            level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 12, 0.2, 0.2, 0.2, 0.02)
        }
        if (health <= 0f) destroy(level, source)
        return true
    }

    override fun destroy(level: ServerLevel, source: DamageSource) {
        destroyed = true
        ejectPassengers()
        definition?.deathExplosion?.let { level.explode(this, x, y + bbHeight / 2, z, it, Level.ExplosionInteraction.NONE) }
        level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 1, z, 30, bbWidth / 3.0, 0.5, bbWidth / 3.0, 0.02)
        kill(level)
    }

    override fun ignoreExplosion(explosion: net.minecraft.world.level.Explosion) = explosion.directSourceEntity == this || super.ignoreExplosion(explosion)

    // ------------------------------------------------------------------------------------------------- weapons

    /** World yaw and elevation (degrees, up positive) the gun of [seatIndex] points at: turrets follow [shooter]'s view. */
    fun aim(seatIndex: Int, shooter: Entity, partialTick: Float = 1f): Pair<Float, Float> {
        val seat = seat(seatIndex) ?: return yRot to 0f
        val yaw = if (seat.turret) shooter.getViewYRot(partialTick) else getViewYRot(partialTick)
        return yaw to (-shooter.getViewXRot(partialTick)).coerceIn(seat.minPitch, seat.maxPitch)
    }

    fun aimDirection(yaw: Float, elevation: Float): Vec3 = Vec3(0.0, 0.0, 1.0).xRot(elevation * Mth.DEG_TO_RAD).yRot(-yaw * Mth.DEG_TO_RAD)

    /** Muzzle of [seatIndex]'s gun in world space: the turret pivot turns with the hull, the muzzle with the aim. */
    fun muzzlePosition(seatIndex: Int, yaw: Float, elevation: Float): Vec3 {
        val seat = seat(seatIndex) ?: return position()
        val m = seat.muzzle.vec()
        return position().add(toWorld(seat.pivot.vec())).add(Vec3(-m.x, m.y, m.z).xRot(elevation * Mth.DEG_TO_RAD).yRot(-yaw * Mth.DEG_TO_RAD))
    }

    fun setMagazine(seat: Int, contents: MagazineContents?) {
        seatMagazines = if (contents == null) seatMagazines - seat else seatMagazines + (seat to contents)
    }

    // ------------------------------------------------------------------------------------------------- saving

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.putString("Vehicle", entityData.get(VEHICLE))
        output.store("Damage", DAMAGE_CODEC, damage)
        output.store("Upgrades", UPGRADES_CODEC, upgrades)
        output.putInt("Fuel", fuel)
        output.store("Magazines", MAGAZINES_CODEC, seatMagazines.mapKeys { it.key.toString() })
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        entityData.set(VEHICLE, input.getStringOr("Vehicle", ""))
        damage = input.read("Damage", DAMAGE_CODEC).orElse(emptyMap())
        upgrades = input.read("Upgrades", UPGRADES_CODEC).orElse(emptyMap())
        fuel = input.getIntOr("Fuel", 0)
        seatMagazines = input.read("Magazines", MAGAZINES_CODEC).orElse(emptyMap())
            .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()
        refreshDimensions()
        boundingBox = makeBoundingBox()
    }

    // ------------------------------------------------------------------------------------------------- GeckoLib

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) = Unit
    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = cache

    companion object {
        const val WHEEL_RADIUS = 0.4
        /** Damage key of the hull. */
        const val HULL = "hull"
        private const val FUEL_LEAK = 5
        private const val MAX_TILT = 35f
        private const val MAX_HEAD_TURN = 120f
        private const val SUSPENSION = 0.4f

        private val SEATS_SERIALIZER: EntityDataSerializer<List<Int>> =
            EntityDataSerializer.forValueType(ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()))
        private val MAGAZINES_SERIALIZER: EntityDataSerializer<Map<Int, MagazineContents>> =
            EntityDataSerializer.forValueType(ByteBufCodecs.map(::HashMap, ByteBufCodecs.VAR_INT, MagazineContents.STREAM_CODEC))
        private val DAMAGE_SERIALIZER: EntityDataSerializer<Map<String, Float>> =
            EntityDataSerializer.forValueType(ByteBufCodecs.map(::HashMap, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.FLOAT))
        private val UPGRADES_SERIALIZER: EntityDataSerializer<Map<String, Identifier>> =
            EntityDataSerializer.forValueType(ByteBufCodecs.map(::HashMap, ByteBufCodecs.STRING_UTF8, Identifier.STREAM_CODEC))

        /** Custom entity data serializers must be registered on both sides before any vehicle is created. */
        fun registerDataSerializers() {
            FabricEntityDataRegistry.register(FlansMod.id("seats"), SEATS_SERIALIZER)
            FabricEntityDataRegistry.register(FlansMod.id("seat_magazines"), MAGAZINES_SERIALIZER)
            FabricEntityDataRegistry.register(FlansMod.id("vehicle_damage"), DAMAGE_SERIALIZER)
            FabricEntityDataRegistry.register(FlansMod.id("vehicle_upgrades"), UPGRADES_SERIALIZER)
        }

        private val VEHICLE: EntityDataAccessor<String> = SynchedEntityData.defineId(DriveableEntity::class.java, EntityDataSerializers.STRING)
        private val DAMAGE: EntityDataAccessor<Map<String, Float>> = SynchedEntityData.defineId(DriveableEntity::class.java, DAMAGE_SERIALIZER)
        private val UPGRADES: EntityDataAccessor<Map<String, Identifier>> = SynchedEntityData.defineId(DriveableEntity::class.java, UPGRADES_SERIALIZER)
        private val FUEL: EntityDataAccessor<Int> = SynchedEntityData.defineId(DriveableEntity::class.java, EntityDataSerializers.INT)
        private val SEATS: EntityDataAccessor<List<Int>> = SynchedEntityData.defineId(DriveableEntity::class.java, SEATS_SERIALIZER)
        private val MAGAZINES: EntityDataAccessor<Map<Int, MagazineContents>> = SynchedEntityData.defineId(DriveableEntity::class.java, MAGAZINES_SERIALIZER)

        private val MAGAZINES_CODEC = Codec.unboundedMap(Codec.STRING, MagazineContents.CODEC)
        private val DAMAGE_CODEC = Codec.unboundedMap(Codec.STRING, Codec.FLOAT)
        private val UPGRADES_CODEC = Codec.unboundedMap(Codec.STRING, Identifier.CODEC)
    }
}
