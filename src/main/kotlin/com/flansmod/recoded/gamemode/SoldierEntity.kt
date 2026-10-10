package com.flansmod.recoded.gamemode

import com.flansmod.recoded.combat.Ballistics
import com.flansmod.recoded.combat.GunHandler
import com.flansmod.recoded.gun.Clothing
import com.flansmod.recoded.gun.FireMode
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.ClothingItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.gunId
import com.flansmod.recoded.item.loadedMagazine
import com.flansmod.recoded.item.shotDefinition
import com.flansmod.recoded.network.ShotPayload
import com.flansmod.recoded.registry.FlansEntities
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.OpenDoorGoal
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import com.flansmod.recoded.trenches.TrenchRole
import com.flansmod.recoded.trenches.TrenchRules
import com.flansmod.recoded.trenches.TrenchSoldier
import com.flansmod.recoded.trenches.TrenchUnits
import net.minecraft.resources.Identifier
import net.minecraft.world.damagesource.DamageSource
import java.util.EnumSet
import java.util.UUID

/**
 * A bot fighting for a team ("fill teams with bots", or a unit sent in by a Trenches commander). It wears its team faction's clothing and carries one of its guns,
 * fires real server-side bullets ([Ballistics]) at enemy players and bots it can see and otherwise heads for the
 * mode's objective: hills to take, enemy flags to steal (and home to deliver), enemy bases, opening doors on the way. It belongs to one battle
 * session and disappears when that ends. Drops nothing.
 *
 * Soldiers spawned outside a battle ([masterPos] null, e.g. with a soldier spawn item) wear [faction]'s gear and
 * behave by their [attitude] ([FactionSoldiers]): friendly, enemy, neutral or inactive; they stay near where they were
 * placed and never despawn.
 *
 * Trench units ([unit] set) keep to their orders instead: they walk to their place ([slot]) in the zone they were
 * ordered to ([order]) and fire at what comes into range without leaving it; their role ([TrenchRules]) adds mortar
 * rounds, grenades, engineering work or an officer's aura, and trenches give them cover.
 */
class SoldierEntity(type: EntityType<out SoldierEntity>, level: Level) : PathfinderMob(type, level) {
    var masterPos: BlockPos? = null
    var team = ""
    var session = ""
    var callsign = ""

    // Trenches: unit definition and which of its soldiers this is, role (a loader becomes a machine gunner), the zone
    // it is ordered to and its place there, squad spawn order (keeps places stable), the squad's machine gunner.
    var unit: Identifier? = null
    var member = 0
    var role: TrenchRole? = null
    var order = 0
    var slot = 0
    var serial = 0
    var partner: UUID? = null
    /** Officer aura over this soldier right now (set by [TrenchRules] every second, not saved). */
    var inspired: TrenchSoldier.Aura? = null
    /** Next mortar round or grenade throw (game time, not saved). */
    var nextSpecial = 0L

    val isTrenchUnit get() = unit != null
    val trenchSoldier: TrenchSoldier? get() = TrenchUnits[unit]?.soldiers?.getOrNull(member)

    /** Outside battles: the faction whose gear it wears (null: any) and how it behaves. */
    var faction: Identifier? = null
    var attitude: SoldierAttitude = SoldierAttitude.NEUTRAL
        set(value) {
            field = value
            isNoAi = value == SoldierAttitude.INACTIVE && masterPos == null
        }
    /** Gear handed out (soldiers placed by commands or spawn items get theirs on the first tick). */
    private var equipped = false

    private var nextShot = 0L
    private var roundsLeft = -1
    private var burst = 0

    init {
        setPersistenceRequired()
        EquipmentSlot.entries.forEach { setDropChance(it, 0f) }
        // Through doors (wooden and bunker doors - any door opened by hand), like villagers.
        (navigation as? GroundPathNavigation)?.setCanOpenDoors(true)
    }

    /** The running battle this bot fights in (null = stale bot, removed on its next tick). */
    fun battle(): BattleMasterBlockEntity? = masterPos?.let { level().getBlockEntity(it) as? BattleMasterBlockEntity }
        ?.takeIf { it.running && it.state.session == session && it.state.bots[stringUUID] == team }

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, OpenDoorGoal(this, true))
        goalSelector.addGoal(1, GunAttackGoal(this))
        goalSelector.addGoal(1, TrenchFireGoal(this))
        goalSelector.addGoal(2, ObjectiveGoal(this))
        goalSelector.addGoal(3, WaterAvoidingRandomStrollGoal(this, 0.8))
        goalSelector.addGoal(4, RandomLookAroundGoal(this))
        targetSelector.addGoal(1, object : HurtByTargetGoal(this) {
            override fun canUse() = super.canUse() && FactionSoldiers.retaliates(this@SoldierEntity, lastHurtByMob)
        })
        targetSelector.addGoal(2, NearestAttackableTargetGoal(this, LivingEntity::class.java, 5, true, false) { target, _ ->
            !target.isSpectator && if (masterPos == null) FactionSoldiers.hostile(this, target) else Battles.enemies(this, target)
        })
    }

    override fun tick() {
        super.tick()
        if (level().isClientSide()) return
        if (masterPos == null) {
            if (!equipped) equip(faction, null)
        } else if (tickCount % 20 == 0 && battle() == null) discard()
    }

    /** [gun] (else a random bullet gun of [faction], any faction when null) and a random uniform of the faction. */
    fun equip(faction: Identifier?, gun: Identifier?) {
        equipped = true
        val guns = Guns.all.filter { (gunId, g) ->
            !g.mounted && (faction == null || g.faction == faction) && GunItem.stackFor(gunId).let { s -> s.loadedMagazine?.ammoDefinition?.projectile == null && s.loadedMagazine != null }
        }.keys.toList()
        (gun ?: guns.randomOrNull())?.let { setItemSlot(EquipmentSlot.MAINHAND, GunItem.stackFor(it)) }
        Clothing.all.filter { faction == null || it.value.faction == faction }.entries.groupBy { it.value.slot }.forEach { (slot, options) ->
            val equipment = when (slot) { "head" -> EquipmentSlot.HEAD; "chest" -> EquipmentSlot.CHEST; "legs" -> EquipmentSlot.LEGS; "feet" -> EquipmentSlot.FEET; else -> null }
                ?: return@forEach
            setItemSlot(equipment, ClothingItem.stackFor(options.random().key))
        }
    }

    /** "Axis Soldier" - for death messages and the name tag when nobody named it. */
    override fun getTypeName(): Component {
        if (masterPos != null || faction == null) return super.getTypeName()
        return Component.translatable("entity.flansmod.soldier.of", com.flansmod.recoded.gun.Factions[faction]?.name ?: faction.toString())
    }

    /** Creative mode: sneak + right click with an empty hand switches a soldier's attitude (outside battles). */
    override fun mobInteract(player: net.minecraft.world.entity.player.Player, hand: net.minecraft.world.InteractionHand): net.minecraft.world.InteractionResult {
        if (masterPos == null && player.isCreative && player.isShiftKeyDown && player.getItemInHand(hand).isEmpty) {
            if (!level().isClientSide()) {
                attitude = attitude.next()
                target = null
                player.sendOverlayMessage(Component.translatable("message.flansmod.soldier.attitude", Component.translatable("soldier.flansmod.attitude.${attitude.key}")
                    .withColor(attitude.rgb)))
            }
            return net.minecraft.world.InteractionResult.SUCCESS
        }
        return super.mobInteract(player, hand)
    }

    override fun removeWhenFarAway(distance: Double) = false

    /** Trench units take less from bullets and shells in a trench, more so in a bunker ([TrenchRules.cover]). */
    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean =
        super.hurtServer(level, source, if (isTrenchUnit) amount * TrenchRules.cover(this, source) else amount)

    override fun die(source: DamageSource) {
        super.die(source)
        if (!level().isClientSide() && isTrenchUnit) TrenchRules.fallen(this)
    }

    /** Dead or discarded (not just unloaded): off its battle's roster. */
    override fun remove(reason: RemovalReason) {
        super.remove(reason)
        if (!level().isClientSide() && reason.shouldDestroy()) {
            (masterPos?.let { level().getBlockEntity(it) } as? BattleMasterBlockEntity)?.takeIf { it.state.bots[stringUUID] == team }?.let { BattleRules.botGone(it, stringUUID) }
        }
    }

    // ------------------------------------------------------------------------------------------- shooting

    /** How far the bot engages: a trench soldier's own range, else the gun's bullet reach, at most 48 blocks. */
    val range: Double get() = trenchSoldier?.range?.takeIf { it > 0 && !mainHandItem.isEmpty }
        ?: mainHandItem.shotDefinition?.let { it.velocity * it.lifetimeTicks * 0.8 }?.coerceAtMost(48.0) ?: 0.0

    /** Fires at [target] when the gun is ready: short bursts, then a pause; a reload when the magazine is spent. */
    fun fireAt(target: LivingEntity) {
        val level = level() as? ServerLevel ?: return
        val stack = mainHandItem
        val gun = stack.shotDefinition ?: return
        val magazine = stack.loadedMagazine ?: return
        if (level.gameTime < nextShot) return
        if (roundsLeft < 0) roundsLeft = magazine.capacity
        if (roundsLeft == 0) {
            roundsLeft = magazine.capacity
            nextShot = level.gameTime + gun.reloadTicks
            gun.sounds.reload?.let { playSound(it) }
            return
        }
        roundsLeft--
        burst++
        // Semi-automatic guns at a human pace; automatic ones in bursts of 3-5. Trench soldiers: their own pace, faster
        // and straighter near an officer.
        val delay = (trenchSoldier?.fireDelay ?: 1f) * (inspired?.fireDelay ?: 1f)
        val pause = if (burst >= 3 + random.nextInt(3)) 15 + random.nextInt(15).also { burst = 0 } else 0
        nextShot = level.gameTime + ((gun.ticksBetweenShots.coerceAtLeast(if (FireMode.AUTO in gun.availableModes) 1 else 6) + pause) * delay).toLong().coerceAtLeast(1)

        val aim = target.boundingBox.center.add(0.0, target.bbHeight * 0.1, 0.0).subtract(eyePosition).normalize()
        val spread = (gun.spread * 1.5f + 1.5f) * (trenchSoldier?.spread ?: 1f) * (inspired?.spread ?: 1f)
        val directions = List(gun.pellets.coerceAtLeast(1)) { GunHandler.scatter(this, aim, spread) }
        directions.forEach { Ballistics.fire(this, gun, magazine.ammoDefinition, it) }
        val payload = ShotPayload(id, stack.gunId ?: return, eyePosition, directions)
        PlayerLookup.tracking(this).filter { ServerPlayNetworking.canSend(it, ShotPayload.TYPE) }.forEach { ServerPlayNetworking.send(it, payload) }
        gun.sounds.shoot?.let { playSound(it) }
    }

    private fun playSound(id: net.minecraft.resources.Identifier) =
        level().playSound(null, x, y, z, SoundEvent.createVariableRangeEvent(id), SoundSource.HOSTILE, 1f, 1f)

    // ------------------------------------------------------------------------------------------- saving

    override fun addAdditionalSaveData(output: ValueOutput) {
        super.addAdditionalSaveData(output)
        masterPos?.let { output.putLong("Master", it.asLong()) }
        output.putString("Team", team)
        output.putString("Session", session)
        output.putString("Callsign", callsign)
        faction?.let { output.putString("Faction", it.toString()) }
        output.putString("Attitude", attitude.key)
        output.putBoolean("Equipped", equipped)
        unit?.let {
            output.putString("TrenchUnit", it.toString())
            output.putInt("TrenchMember", member)
            role?.let { r -> output.putString("TrenchRole", r.name) }
            output.putInt("TrenchOrder", order)
            output.putInt("TrenchSerial", serial)
            partner?.let { p -> output.putString("TrenchPartner", p.toString()) }
        }
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        masterPos = input.getLong("Master").map(BlockPos::of).orElse(null)
        team = input.getStringOr("Team", "")
        session = input.getStringOr("Session", "")
        callsign = input.getStringOr("Callsign", "")
        faction = input.getString("Faction").map(Identifier::tryParse).orElse(null)
        equipped = input.getBooleanOr("Equipped", false)
        attitude = SoldierAttitude.byKey(input.getStringOr("Attitude", "neutral"))
        unit = input.getString("TrenchUnit").map(Identifier::tryParse).orElse(null)
        member = input.getIntOr("TrenchMember", 0)
        role = input.getString("TrenchRole").map { runCatching { TrenchRole.valueOf(it) }.getOrNull() }.orElse(null)
        order = input.getIntOr("TrenchOrder", 0)
        serial = input.getIntOr("TrenchSerial", 0)
        partner = input.getString("TrenchPartner").map { runCatching { UUID.fromString(it) }.getOrNull() }.orElse(null)
    }

    companion object {
        fun createAttributes(): AttributeSupplier.Builder = createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.3).add(Attributes.FOLLOW_RANGE, 48.0)

        /**
         * A bot for [team] at [at]: [gun], else a random gun of the team's faction (any faction when the team has none),
         * and random clothing of the faction. Launchers are left out - bots fire bullets.
         */
        fun deploy(master: BattleMasterBlockEntity, level: ServerLevel, team: BattleTeam, callsign: String, at: BlockPos, gun: Identifier? = null): SoldierEntity? {
            val soldier = FlansEntities.SOLDIER.create(level, EntitySpawnReason.EVENT) ?: return null
            soldier.masterPos = master.blockPos
            soldier.team = team.name
            soldier.session = master.state.session
            soldier.callsign = callsign
            soldier.snapTo(at.x + 0.5, at.y.toDouble(), at.z + 0.5, level.random.nextFloat() * 360f, 0f)
            soldier.customName = Component.literal(callsign).withColor(team.teamColor.rgb())
            soldier.faction = team.faction
            soldier.equip(team.faction, gun)
            master.state = master.state.copy(bots = master.state.bots + (soldier.stringUUID to team.name))
            Battles.stats(master, "bot:$callsign", callsign, team.name, bot = true) { it }
            level.addFreshEntity(soldier)
            level.server.scoreboard.getPlayerTeam(master.scoreboardName(team.name))?.let { level.server.scoreboard.addPlayerToTeam(soldier.scoreboardName, it) }
            val countdown = (master.state.countdownEnd - level.gameTime).toInt()
            if (countdown > 0) soldier.addEffect(MobEffectInstance(MobEffects.SLOWNESS, countdown, 9, false, false))
            master.settings.respawnProtection.takeIf { it > 0 }?.let { soldier.addEffect(MobEffectInstance(MobEffects.RESISTANCE, it * 20, 4, false, false)) }
            return soldier
        }
    }
}

/** Shoot the target when it is in sight and range; otherwise move towards it. Not during the countdown. */
private class GunAttackGoal(private val soldier: SoldierEntity) : Goal() {
    init {
        flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
    }

    override fun canUse(): Boolean {
        val target = soldier.target ?: return false
        return !soldier.isTrenchUnit && target.isAlive && soldier.range > 0 && (soldier.masterPos == null || soldier.battle()?.inCountdown == false)
    }

    override fun requiresUpdateEveryTick() = true

    override fun stop() = soldier.navigation.stop()

    override fun tick() {
        val target = soldier.target ?: return
        soldier.lookControl.setLookAt(target, 60f, 60f)
        val distance = soldier.distanceTo(target)
        val sees = soldier.sensing.hasLineOfSight(target)
        when {
            !sees || distance > soldier.range -> if (soldier.navigation.isDone || soldier.tickCount % 20 == 0) soldier.navigation.moveTo(target, 1.0)
            distance < 6 -> soldier.navigation.stop()
            soldier.tickCount % 40 == 0 -> soldier.navigation.stop()
        }
        if (sees && distance <= soldier.range) soldier.fireAt(target)
    }
}

/**
 * Trench units hold their ground: they look at and shoot the target when it is in sight and range, but never go after
 * it (only [ObjectiveGoal] moves them, to where they were ordered) - so they keep firing while advancing too.
 */
private class TrenchFireGoal(private val soldier: SoldierEntity) : Goal() {
    init {
        flags = EnumSet.of(Flag.LOOK)
    }

    override fun canUse(): Boolean {
        val target = soldier.target ?: return false
        return soldier.isTrenchUnit && target.isAlive && soldier.range > 0 && soldier.battle()?.inCountdown == false
    }

    override fun requiresUpdateEveryTick() = true

    override fun tick() {
        val target = soldier.target ?: return
        soldier.lookControl.setLookAt(target, 60f, 60f)
        if (soldier.distanceTo(target) <= soldier.range && soldier.sensing.hasLineOfSight(target)) soldier.fireAt(target)
    }
}

/**
 * Head for the battle mode's objective: hills not held by the team or its allies (king of the hill, conquest), the
 * nearest enemy flag - or home while carrying one (capture the flag), the nearest enemy base (team deathmatch).
 * Back inside the border first.
 */
private class ObjectiveGoal(private val soldier: SoldierEntity) : Goal() {
    private var goal: Vec3? = null

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean {
        val master = soldier.battle()?.takeIf { !it.inCountdown } ?: return false
        goal = objective(master)?.takeIf { it.distanceToSqr(soldier.position()) > if (soldier.isTrenchUnit) 1.2 else 4.0 }
        return goal != null
    }

    // Trench units walk on under fire; their destination changes with new orders.
    override fun canContinueToUse() = !soldier.navigation.isDone && (soldier.isTrenchUnit || soldier.target == null)

    override fun start() {
        goal?.let { soldier.navigation.moveTo(it.x, it.y, it.z, 1.0) }
    }

    override fun tick() {
        if (!soldier.isTrenchUnit || soldier.tickCount % 20 != 0) return
        val master = soldier.battle() ?: return
        val now = TrenchRules.destination(master, soldier) ?: return
        if (goal?.distanceToSqr(now) ?: Double.MAX_VALUE > 2.0) {
            goal = now
            soldier.navigation.moveTo(now.x, now.y, now.z, 1.0)
        }
    }

    override fun stop() = soldier.navigation.stop()

    private fun objective(master: BattleMasterBlockEntity): Vec3? {
        if (soldier.isTrenchUnit) return TrenchRules.destination(master, soldier)
        master.borderBox()?.let { box -> if (!box.contains(soldier.position())) return box.center.with(net.minecraft.core.Direction.Axis.Y, soldier.y) }
        val settings = master.settings
        val here = soldier.position()
        fun enemy(team: String?) = team != null && !settings.friendly(team, soldier.team)
        val posts = master.state.posts.values
        val target = when (settings.mode) {
            BattleMode.KING_OF_THE_HILL, BattleMode.CONQUEST -> posts.filter {
                !it.locked && (settings.mode == BattleMode.CONQUEST || it.hill) && (it.team == null || enemy(it.team))
            }
            BattleMode.CAPTURE_THE_FLAG ->
                if (master.state.carriers.containsKey(soldier.stringUUID)) master.posts(soldier.team)
                else posts.filter { enemy(it.team) && !it.locked && !master.carried(Post.key(it.blockPos)) }
            BattleMode.TEAM_DEATHMATCH, BattleMode.TRENCHES -> posts.filter { enemy(it.team) }
        }.minByOrNull { it.blockPos.distToCenterSqr(here) } ?: return null
        return Vec3.atBottomCenterOf(target.blockPos)
    }
}
