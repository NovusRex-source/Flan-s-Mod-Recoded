package com.flansmod.recoded.test

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.fortification.Fortifications
import com.flansmod.recoded.gamemode.BattleMasterBlockEntity
import com.flansmod.recoded.gamemode.BattleSettings
import com.flansmod.recoded.gamemode.BattleTeam
import com.flansmod.recoded.gamemode.Battles
import com.flansmod.recoded.gamemode.TeamFlagBlockEntity
import com.flansmod.recoded.gun.Fuel
import com.flansmod.recoded.gun.VehicleDefinition
import com.flansmod.recoded.gun.VehicleType
import com.flansmod.recoded.gun.Vehicles
import com.flansmod.recoded.registry.FlansBlocks
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

/** Battles (Battle Master, teams, flag posts, money, shop) and fortifications. */
class BattleGameTests {
    private fun GameTestHelper.battle(settings: BattleSettings): BattleMasterBlockEntity {
        setBlock(BlockPos(1, 1, 1), FlansBlocks.BATTLE_MASTER)
        return getBlockEntity(BlockPos(1, 1, 1), BattleMasterBlockEntity::class.java).also { it.state = it.state.copy(settings = settings) }
    }

    private fun GameTestHelper.flag(at: BlockPos, player: ServerPlayer): TeamFlagBlockEntity {
        setBlock(at, FlansBlocks.TEAM_FLAG)
        return getBlockEntity(at, TeamFlagBlockEntity::class.java).also { assertTrue(it.claim(player), "a member can claim a flag post for their team") }
    }

    @GameTest(maxTicks = 40)
    fun enteringStoresTheInventoryAndLeavingAtTheFlagGivesItBack(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(startMoney = 300, shop = listOf("100 4 minecraft:bread")))
        val player = helper.makeMockServerPlayerInLevel()
        player.inventory.setItem(0, ItemStack(Items.DIAMOND, 3))
        Battles.join(player, master, "Red")
        val flag = helper.flag(BlockPos(4, 1, 4), player)
        helper.assertTrue(master.flag("Red") == flag.blockPos, "the flag is Red's base")
        Battles.start(master, null)
        val data = Battles.data(player)!!
        helper.assertTrue(data.inBattle && data.money == 300, "in the battle with the start money: $data")
        helper.assertTrue(player.inventory.isEmpty, "the inventory stays behind")
        helper.assertTrue(player.blockPosition().distManhattan(flag.blockPos) <= 3, "you start at your flag post")

        val bread = Battles.shop(master, "Red").single()
        Battles.buy(player, bread)
        Battles.buy(player, bread)
        Battles.buy(player, bread)
        helper.assertTrue(Battles.data(player)!!.money == 0 && player.inventory.countItem(Items.BREAD) == 12, "three buys, then the money is gone")

        Battles.exit(player)
        helper.assertTrue(player.inventory.countItem(Items.DIAMOND) == 3 && player.inventory.countItem(Items.BREAD) == 0, "the old inventory is back, battle items are gone")
        helper.assertTrue(Battles.data(player)?.let { !it.inBattle && it.team == "Red" } == true, "still on the team after leaving the battle")
        Battles.end(master, null)
        Battles.quit(player)
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun enemiesScoreAndFriendsCannotHurtEachOther(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(teams = listOf(BattleTeam("Red", "red"), BattleTeam("Blue", "blue"), BattleTeam("Green", "green")),
            alliances = listOf("Red+Green"), killReward = 150, scoreLimit = 2))
        val (red, blue, green) = listOf("Red", "Blue", "Green").map { team ->
            helper.makeMockServerPlayerInLevel().also { p ->
                Battles.join(p, master, team)
                p.inventory.setItem(0, ItemStack(Items.EMERALD))
            }
        }
        Battles.start(master, null)

        // Mock players stay creative (vanilla PvP refuses all damage), so the battle rules are driven through their Fabric events.
        val allow = ServerLivingEntityEvents.ALLOW_DAMAGE.invoker()
        helper.assertTrue(!allow.allowDamage(green, helper.level.damageSources().playerAttack(red), 4f), "allies cannot hurt each other")
        helper.assertTrue(allow.allowDamage(blue, helper.level.damageSources().playerAttack(red), 4f), "enemies can")
        val death = ServerLivingEntityEvents.ALLOW_DEATH.invoker()
        death.allowDeath(blue, helper.level.damageSources().playerAttack(red), 100f)
        helper.assertTrue(blue.inventory.isEmpty, "the fallen lose what they carried")
        helper.assertTrue(master.state.scores["Red"] == 1 && Battles.data(red)!!.money == 600 + 150, "the kill scores for Red and pays: ${master.state.scores}")
        death.allowDeath(blue, helper.level.damageSources().playerAttack(green), 100f)
        helper.assertTrue(master.state.scores["Green"] == 1, "allies score for their own team")
        death.allowDeath(blue, helper.level.damageSources().playerAttack(red), 100f)
        // Score limit 2: Red won, the battle ended and every fighter got their inventory back.
        helper.assertTrue(!master.running, "reaching the score limit ends the battle")
        helper.assertTrue(listOf(red, blue, green).all { it.inventory.countItem(Items.EMERALD) == 1 && Battles.data(it)?.inBattle == false },
            "inventories come back when the battle ends")
        listOf(red, blue, green).forEach(Battles::quit)
        helper.succeed()
    }

    @GameTest(maxTicks = 160)
    fun barbedWireHurtsAndTanksFlattenIt(helper: GameTestHelper) {
        helper.setBlock(BlockPos(1, 1, 1), Fortifications.BARBED_WIRE)
        val husk = helper.spawn(EntityTypes.SHEEP, BlockPos(1, 1, 1)) // a mob that moves: block contact effects come with movement
        helper.setBlock(BlockPos(4, 1, 2), Fortifications.BARBED_WIRE)
        val id = Identifier.fromNamespaceAndPath("test", "wire_tank")
        Vehicles.replace(Vehicles.all + (id to VehicleDefinition("Tank", type = VehicleType.TANK, width = 1.5f, height = 1f, deathExplosion = null, fuel = Fuel(capacity = 0))))
        helper.level.addFreshEntity(DriveableEntity(helper.level, id, helper.absoluteVec(Vec3(4.5, 1.0, 2.5)), 0f))
        helper.succeedWhen {
            helper.assertTrue(husk.health < husk.maxHealth, "barbed wire cuts")
            helper.assertBlockNotPresent(Fortifications.BARBED_WIRE, BlockPos(4, 1, 2))
        }
    }
}
