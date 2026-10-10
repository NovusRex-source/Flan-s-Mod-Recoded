package com.flansmod.recoded.test

import com.flansmod.recoded.gamemode.FactionSoldiers
import com.flansmod.recoded.gamemode.SoldierAttitude
import com.flansmod.recoded.gamemode.SoldierEntity
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.SoldierItem
import com.flansmod.recoded.item.gunId
import com.flansmod.recoded.registry.FlansComponents
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.GameType

/** Soldiers spawned outside battles: faction gear, staying around, attitudes, no friendly fire, a fight, switching. */
class FactionSoldierGameTests {
    private val red = Identifier.fromNamespaceAndPath("test", "soldiers_red")
    private val blue = Identifier.fromNamespaceAndPath("test", "soldiers_blue")

    /** A short-range gun per test faction (stray bullets stay in the test area). */
    private fun guns() {
        val mag = listOf(Identifier.fromNamespaceAndPath("example", "rifle_mag"))
        Guns.replace(Guns.all + listOf(red, blue).associate { f ->
            Identifier.fromNamespaceAndPath("test", "${f.path}_gun") to GunDefinition("Soldier Gun", faction = f, damage = 4f, velocity = 2.0, lifetimeTicks = 5, magazines = mag)
        })
    }

    private fun GameTestHelper.soldier(at: BlockPos, faction: Identifier?, attitude: SoldierAttitude): SoldierEntity {
        guns()
        return SoldierItem.spawn(level, absolutePos(at), faction, attitude) ?: throw assertionException("a soldier")
    }

    @GameTest(maxTicks = 40)
    fun spawnedSoldiersWearTheirFactionsGearAndStay(helper: GameTestHelper) {
        val soldier = helper.soldier(BlockPos(2, 1, 2), red, SoldierAttitude.NEUTRAL)
        helper.assertTrue(soldier.mainHandItem.gunId == Identifier.fromNamespaceAndPath("test", "soldiers_red_gun"), "armed with its faction's gun: ${soldier.mainHandItem.gunId}")
        helper.assertTrue(soldier.homeRadius == SoldierItem.HOME_RADIUS && soldier.homePosition == helper.absolutePos(BlockPos(2, 1, 2)), "kept near where it was placed")
        val stack = SoldierItem.stackFor(red, SoldierAttitude.ENEMY)
        helper.assertTrue(stack.get(FlansComponents.SOLDIER)?.let { it.faction == red && it.attitude == SoldierAttitude.ENEMY } == true, "the item carries faction and attitude")
        helper.startSequence().thenIdle(25).thenExecute {
            repeat(25) { soldier.tick() }
            helper.assertFalse(soldier.isRemoved, "a soldier outside any battle is not discarded")
            soldier.discard()
        }.thenSucceed()
    }

    @GameTest(maxTicks = 20)
    fun attitudesDecideWhoFights(helper: GameTestHelper) {
        val friendly = helper.soldier(BlockPos(1, 1, 1), red, SoldierAttitude.FRIENDLY)
        val enemy = helper.soldier(BlockPos(3, 1, 1), red, SoldierAttitude.ENEMY)
        val otherEnemy = helper.soldier(BlockPos(5, 1, 1), blue, SoldierAttitude.ENEMY)
        val comrade = helper.soldier(BlockPos(1, 1, 3), red, SoldierAttitude.ENEMY)
        val neutral = helper.soldier(BlockPos(3, 1, 3), red, SoldierAttitude.NEUTRAL)
        val dummy = helper.soldier(BlockPos(5, 1, 3), red, SoldierAttitude.INACTIVE)
        val zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, BlockPos(6, 1, 6))
        val survivor = helper.makeMockPlayer(GameType.SURVIVAL)
        val builder = helper.makeMockPlayer(GameType.CREATIVE)
        fun check(expected: Boolean, a: SoldierEntity, b: net.minecraft.world.entity.LivingEntity, what: String) =
            helper.assertTrue(FactionSoldiers.hostile(a, b) == expected, what)
        check(true, friendly, enemy, "friendly fights enemy soldiers")
        check(true, friendly, zombie, "and monsters")
        check(false, friendly, survivor, "never players")
        check(true, enemy, survivor, "enemies attack players")
        check(false, enemy, builder, "but not in creative mode")
        check(true, enemy, friendly, "and friendly soldiers")
        check(false, enemy, comrade, "not their comrades")
        check(true, enemy, otherEnemy, "enemies of another faction fight each other")
        check(false, enemy, zombie, "monsters are none of their business")
        check(false, neutral, survivor, "neutral soldiers start nothing")
        check(false, dummy, survivor, "inactive ones neither")
        helper.assertTrue(FactionSoldiers.retaliates(neutral, survivor) && !FactionSoldiers.retaliates(friendly, survivor) && !FactionSoldiers.retaliates(dummy, survivor),
            "neutral soldiers shoot back, friendly ones forgive players, dummies never react")
        helper.assertTrue(dummy.isNoAi && !neutral.isNoAi, "inactive soldiers have no AI")
        // No friendly fire among allies.
        val before = comrade.health
        comrade.hurtServer(helper.level, helper.level.damageSources().mobAttack(enemy), 4f)
        helper.assertTrue(comrade.health == before, "comrades do not hurt each other")
        friendly.hurtServer(helper.level, helper.level.damageSources().mobAttack(enemy), 4f)
        helper.assertTrue(friendly.health < friendly.maxHealth, "enemies do hurt friendly soldiers")
        listOf(friendly, enemy, otherEnemy, comrade, neutral, dummy).forEach { it.discard() }
        helper.succeed()
    }

    @GameTest(maxTicks = 400)
    fun friendAndFoeShootEachOther(helper: GameTestHelper) {
        val friendly = helper.soldier(BlockPos(1, 1, 3), red, SoldierAttitude.FRIENDLY)
        val enemy = helper.soldier(BlockPos(6, 1, 3), blue, SoldierAttitude.ENEMY)
        helper.startSequence().thenWaitUntil {
            // Entities in test areas are not always ticked: drive them here.
            listOf(friendly, enemy).forEach { if (!it.isRemoved) it.tick() }
            helper.assertTrue(friendly.health < friendly.maxHealth || enemy.health < enemy.maxHealth, "they open fire")
        }.thenExecute { listOf(friendly, enemy).forEach { it.discard() } }.thenSucceed()
    }

    @GameTest(maxTicks = 20)
    fun creativePlayersSwitchTheAttitude(helper: GameTestHelper) {
        val soldier = helper.soldier(BlockPos(2, 1, 2), null, SoldierAttitude.NEUTRAL)
        val player = helper.makeMockServerPlayerInLevel() // creative
        player.isShiftKeyDown = true
        soldier.interact(player, InteractionHand.MAIN_HAND, soldier.position())
        helper.assertTrue(soldier.attitude == SoldierAttitude.INACTIVE && soldier.isNoAi, "neutral → inactive: ${soldier.attitude}")
        soldier.interact(player, InteractionHand.MAIN_HAND, soldier.position())
        helper.assertTrue(soldier.attitude == SoldierAttitude.FRIENDLY && !soldier.isNoAi, "inactive → friendly, active again")
        soldier.discard()
        helper.succeed()
    }
}
