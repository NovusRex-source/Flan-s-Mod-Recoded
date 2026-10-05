package com.flansmod.recoded.entity

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.Seat
import com.flansmod.recoded.gun.VehicleDefinition
import com.flansmod.recoded.gun.VehicleType
import com.flansmod.recoded.gun.Vehicles
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
 * boats; without a player driver the server simulates. Fuel, health, seats and mounted-gun magazines are decided by
 * the server and synced with entity data.
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
        definition?.let { health = it.health }
    }

    var vehicleId: Identifier?
        get() = entityData.get(VEHICLE).takeIf { it.isNotEmpty() }?.let(Identifier::tryParse)
        set(value) = entityData.set(VEHICLE, value?.toString() ?: "")

    val definition: VehicleDefinition? get() = Vehicles[vehicleId]

    var health: Float
        get() = entityData.get(HEALTH)
        set(value) = entityData.set(HEALTH, value)

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
        builder.define(HEALTH, 1f)
        builder.define(FUEL, 0)
        builder.define(SEATS, emptyList())
        builder.define(MAGAZINES, emptyMap())
    }

    override fun onSyncedDataUpdated(accessor: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(accessor)
        if (accessor == VEHICLE) refreshDimensions()
    }

    override fun getDimensions(pose: Pose): EntityDimensions =
        definition?.let { EntityDimensions.scalable(it.width, it.height) } ?: super.getDimensions(pose)

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

    // ------------------------------------------------------------------------------------------------- seats

    fun seatOf(passenger: Entity): Int = seats.indexOf(passenger.id).takeIf { it >= 0 }
        ?: passengers.indexOf(passenger) // client before the seat data arrived

    fun seat(index: Int): Seat? = definition?.seats?.getOrNull(index)

    fun occupant(index: Int): Entity? = seats.getOrNull(index)?.takeIf { it >= 0 }?.let { id -> passengers.firstOrNull { it.id == id } }

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

    /** Vehicle space `[right, up, forward]` to a world offset. */
    fun toWorld(local: Vec3, yaw: Float = yRot): Vec3 = Vec3(-local.x, local.y, local.z).yRot(-yaw * Mth.DEG_TO_RAD)

    override fun getPassengerAttachmentPoint(passenger: Entity, dimensions: EntityDimensions, scale: Float): Vec3 =
        seat(seatOf(passenger))?.offset?.let(::toWorld) ?: super.getPassengerAttachmentPoint(passenger, dimensions, scale)

    override fun positionRider(passenger: Entity, moveFunction: MoveFunction) {
        super.positionRider(passenger, moveFunction)
        // Riders turn with the vehicle (like boats); only the side that owns the rider's rotation applies it.
        if (passenger.isLocalInstanceAuthoritative && deltaYaw != 0f) {
            passenger.yRot += deltaYaw
            passenger.yHeadRot += deltaYaw
        }
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
            // Sneak + empty hand on an empty vehicle: pick it up (keeps its fuel).
            player.isSecondaryUseActive && stack.isEmpty && passengers.isEmpty() -> {
                if (level() is ServerLevel) {
                    player.inventory.placeItemBackInInventory(VehicleItem.stackFor(vehicleId, fuel), net.minecraft.util.Prediction.SERVER_ONLY)
                    discard()
                }
                return InteractionResult.SUCCESS
            }
            player.isSecondaryUseActive -> return InteractionResult.PASS
        }
        if (!canAddPassenger(player)) return InteractionResult.FAIL
        return if (level().isClientSide() || player.startRiding(this)) InteractionResult.SUCCESS else InteractionResult.PASS
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
            move(MoverType.SELF, deltaMovement)
            if (horizontalCollision) speed *= 0.3
        } else {
            deltaMovement = Vec3.ZERO
        }
        deltaYaw = if (isLocalInstanceAuthoritative) Mth.wrapDegrees(yRot - yawBefore) else Mth.wrapDegrees(yRot - yRotO)
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
        val engine = c.throttle != 0f && hasFuel
        speed = when {
            // Throttle against the motion brakes harder than the engine accelerates.
            engine -> speed + c.throttle * def.acceleration * if (sign(speed).toFloat() != c.throttle.sign && speed != 0.0) 2 else 1
            else -> speed * (1 - def.drag)
        }
        if (c.brake) speed = Mth.approach(speed.toFloat(), 0f, def.braking.toFloat()).toDouble()
        val waterFactor = if (isInWater) def.waterSpeed else 1.0
        speed = speed.coerceIn(-def.maxReverseSpeed * waterFactor, def.maxSpeed * waterFactor)
        if (abs(speed) < 0.002 && !engine) speed = 0.0

        val turn = when (def.type) {
            // Wheels need rolling to steer; full lock is reached at a third of top speed. Reversing flips it.
            VehicleType.CAR -> c.steer * def.turnSpeed * (abs(speed) / (def.maxSpeed / 3)).coerceAtMost(1.0).toFloat() * sign(speed).toFloat()
            VehicleType.TANK -> if (hasFuel) c.steer * def.turnSpeed else 0f
        }
        yRot -= turn

        val forward = Vec3.directionFromRotation(0f, yRot)
        var vy = if (onGround()) 0.0 else deltaMovement.y
        if (!onGround()) vy = (vy - gravity) * 0.98
        if (isInWater) vy = vy * 0.5 - 0.01
        deltaMovement = forward.scale(speed).add(0.0, vy, 0.0)
    }

    private fun tickVisuals() {
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

    // ------------------------------------------------------------------------------------------------- damage

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (isRemoved || destroyed) return true
        if (isInvulnerableToBase(source)) return false
        // Your own crew's bullets and the explosions of your own shells near the vehicle do not hurt it.
        if (source.entity?.let(::isPassengerOfSameVehicle) == true || source.directEntity == this) return false
        val creative = (source.entity as? Player)?.abilities?.instabuild == true
        if (creative && source.isDirect) {
            discard()
            return true
        }
        val def = definition
        val penetrates = source.`is`(DamageTypeTags.IS_EXPLOSION) || source.`is`(FlansDamageTypes.GUN_AP)
        val damage = amount * if (penetrates || def == null) 1f else 1f - def.armor.coerceIn(0f, 1f)
        hurtDir = -hurtDir
        hurtTime = 10
        markHurt()
        health -= damage
        if (health <= 0f) destroy(level, source)
        return true
    }

    override fun destroy(level: ServerLevel, source: DamageSource) {
        destroyed = true
        ejectPassengers()
        definition?.deathExplosion?.let { level.explode(this, x, y + bbHeight / 2, z, it, Level.ExplosionInteraction.NONE) }
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, x, y + 1, z, 30, bbWidth / 3.0, 0.5, bbWidth / 3.0, 0.02)
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
        output.putFloat("Health", health)
        output.putInt("Fuel", fuel)
        output.store("Magazines", MAGAZINES_CODEC, seatMagazines.mapKeys { it.key.toString() })
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        entityData.set(VEHICLE, input.getStringOr("Vehicle", ""))
        health = input.getFloatOr("Health", definition?.health ?: 1f)
        fuel = input.getIntOr("Fuel", 0)
        seatMagazines = input.read("Magazines", MAGAZINES_CODEC).orElse(emptyMap())
            .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()
        refreshDimensions()
    }

    // ------------------------------------------------------------------------------------------------- GeckoLib

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) = Unit
    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = cache

    companion object {
        const val WHEEL_RADIUS = 0.4

        private val SEATS_SERIALIZER: EntityDataSerializer<List<Int>> =
            EntityDataSerializer.forValueType(ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()))
        private val MAGAZINES_SERIALIZER: EntityDataSerializer<Map<Int, MagazineContents>> =
            EntityDataSerializer.forValueType(ByteBufCodecs.map(::HashMap, ByteBufCodecs.VAR_INT, MagazineContents.STREAM_CODEC))

        /** Custom entity data serializers must be registered on both sides before any vehicle is created. */
        fun registerDataSerializers() {
            FabricEntityDataRegistry.register(FlansMod.id("seats"), SEATS_SERIALIZER)
            FabricEntityDataRegistry.register(FlansMod.id("seat_magazines"), MAGAZINES_SERIALIZER)
        }

        private val VEHICLE: EntityDataAccessor<String> = SynchedEntityData.defineId(DriveableEntity::class.java, EntityDataSerializers.STRING)
        private val HEALTH: EntityDataAccessor<Float> = SynchedEntityData.defineId(DriveableEntity::class.java, EntityDataSerializers.FLOAT)
        private val FUEL: EntityDataAccessor<Int> = SynchedEntityData.defineId(DriveableEntity::class.java, EntityDataSerializers.INT)
        private val SEATS: EntityDataAccessor<List<Int>> = SynchedEntityData.defineId(DriveableEntity::class.java, SEATS_SERIALIZER)
        private val MAGAZINES: EntityDataAccessor<Map<Int, MagazineContents>> = SynchedEntityData.defineId(DriveableEntity::class.java, MAGAZINES_SERIALIZER)

        private val MAGAZINES_CODEC = Codec.unboundedMap(Codec.STRING, MagazineContents.CODEC)
    }
}
