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
import java.util.EnumSet

/**
 * A bot fighting for a team ("fill teams with bots"). It wears its team faction's clothing and carries one of its guns,
 * fires real server-side bullets ([Ballistics]) at enemy players and bots it can see and otherwise heads for the
 * mode's objective: hills to take, enemy flags to steal (and home to deliver), enemy bases, opening doors on the way. It belongs to one battle
 * session and disappears when that ends. Drops nothing.
 */
class SoldierEntity(type: EntityType<out SoldierEntity>, level: Level) : PathfinderMob(type, level) {
    var masterPos: BlockPos? = null
    var team = ""
    var session = ""
    var callsign = ""

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
        goalSelector.addGoal(2, ObjectiveGoal(this))
        goalSelector.addGoal(3, WaterAvoidingRandomStrollGoal(this, 0.8))
        goalSelector.addGoal(4, RandomLookAroundGoal(this))
        targetSelector.addGoal(1, HurtByTargetGoal(this))
        targetSelector.addGoal(2, NearestAttackableTargetGoal(this, LivingEntity::class.java, 5, true, false) { target, _ ->
            Battles.enemies(this, target) && !target.isSpectator
        })
    }

    override fun tick() {
        super.tick()
        if (!level().isClientSide() && tickCount % 20 == 0 && battle() == null) discard()
    }

    override fun removeWhenFarAway(distance: Double) = false

    /** Dead or discarded (not just unloaded): off its battle's roster. */
    override fun remove(reason: RemovalReason) {
        super.remove(reason)
        if (!level().isClientSide() && reason.shouldDestroy()) {
            (masterPos?.let { level().getBlockEntity(it) } as? BattleMasterBlockEntity)?.takeIf { it.state.bots[stringUUID] == team }?.let { BattleRules.botGone(it, stringUUID) }
        }
    }

    // ------------------------------------------------------------------------------------------- shooting

    /** How far the bot engages: the gun's bullet reach, at most 48 blocks. */
    val range: Double get() = mainHandItem.shotDefinition?.let { it.velocity * it.lifetimeTicks * 0.8 }?.coerceAtMost(48.0) ?: 0.0

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
        // Semi-automatic guns at a human pace; automatic ones in bursts of 3-5.
        val pause = if (burst >= 3 + random.nextInt(3)) 15 + random.nextInt(15).also { burst = 0 } else 0
        nextShot = level.gameTime + gun.ticksBetweenShots.coerceAtLeast(if (FireMode.AUTO in gun.availableModes) 1 else 6) + pause

        val aim = target.boundingBox.center.add(0.0, target.bbHeight * 0.1, 0.0).subtract(eyePosition).normalize()
        val spread = gun.spread * 1.5f + 1.5f
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
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        super.readAdditionalSaveData(input)
        masterPos = input.getLong("Master").map(BlockPos::of).orElse(null)
        team = input.getStringOr("Team", "")
        session = input.getStringOr("Session", "")
        callsign = input.getStringOr("Callsign", "")
    }

    companion object {
        fun createAttributes(): AttributeSupplier.Builder = createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0).add(Attributes.MOVEMENT_SPEED, 0.3).add(Attributes.FOLLOW_RANGE, 48.0)

        /**
         * A bot for [team] at [at]: random gun and clothing of the team's faction (any faction when the team has none).
         * Launchers are left out - bots fire bullets.
         */
        fun deploy(master: BattleMasterBlockEntity, level: ServerLevel, team: BattleTeam, callsign: String, at: BlockPos): SoldierEntity? {
            val soldier = FlansEntities.SOLDIER.create(level, EntitySpawnReason.EVENT) ?: return null
            soldier.masterPos = master.blockPos
            soldier.team = team.name
            soldier.session = master.state.session
            soldier.callsign = callsign
            soldier.snapTo(at.x + 0.5, at.y.toDouble(), at.z + 0.5, level.random.nextFloat() * 360f, 0f)
            soldier.customName = Component.literal(callsign).withColor(team.teamColor.rgb())
            val faction = team.faction
            val guns = Guns.all.filter { (gunId, g) ->
                !g.mounted && (faction == null || g.faction == faction) && GunItem.stackFor(gunId).let { s -> s.loadedMagazine?.ammoDefinition?.projectile == null && s.loadedMagazine != null }
            }.keys.toList()
            guns.randomOrNull()?.let { soldier.setItemSlot(EquipmentSlot.MAINHAND, GunItem.stackFor(it)) }
            Clothing.all.filter { faction == null || it.value.faction == faction }.entries.groupBy { it.value.slot }.forEach { (slot, options) ->
                val equipment = when (slot) { "head" -> EquipmentSlot.HEAD; "chest" -> EquipmentSlot.CHEST; "legs" -> EquipmentSlot.LEGS; "feet" -> EquipmentSlot.FEET; else -> null }
                    ?: return@forEach
                soldier.setItemSlot(equipment, ClothingItem.stackFor(options.random().key))
            }
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
        return target.isAlive && soldier.range > 0 && soldier.battle()?.inCountdown == false
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
        goal = objective(master)?.takeIf { it.distanceToSqr(soldier.position()) > 4.0 }
        return goal != null
    }

    override fun canContinueToUse() = !soldier.navigation.isDone && soldier.target == null

    override fun start() {
        goal?.let { soldier.navigation.moveTo(it.x, it.y, it.z, 1.0) }
    }

    override fun stop() = soldier.navigation.stop()

    private fun objective(master: BattleMasterBlockEntity): Vec3? {
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
            BattleMode.TEAM_DEATHMATCH -> posts.filter { enemy(it.team) }
        }.minByOrNull { it.blockPos.distToCenterSqr(here) } ?: return null
        return Vec3.atBottomCenterOf(target.blockPos)
    }
}
