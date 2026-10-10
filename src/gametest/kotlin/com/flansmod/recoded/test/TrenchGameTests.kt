package com.flansmod.recoded.test

import com.flansmod.recoded.fortification.Fortifications
import com.flansmod.recoded.gamemode.BattleMasterBlockEntity
import com.flansmod.recoded.gamemode.BattleMode
import com.flansmod.recoded.gamemode.BattleSettings
import com.flansmod.recoded.gamemode.BattleTeam
import com.flansmod.recoded.gamemode.Battles
import com.flansmod.recoded.gamemode.SoldierEntity
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.gunId
import com.flansmod.recoded.registry.FlansBlocks
import com.flansmod.recoded.trenches.TrenchAiLevel
import com.flansmod.recoded.trenches.TrenchField
import com.flansmod.recoded.trenches.TrenchJobKind
import com.flansmod.recoded.trenches.TrenchLayout
import com.flansmod.recoded.trenches.TrenchRole
import com.flansmod.recoded.trenches.TrenchRules
import com.flansmod.recoded.trenches.TrenchSettings
import com.flansmod.recoded.trenches.TrenchSoldier
import com.flansmod.recoded.trenches.TrenchUnits
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.AreaEffectCloud
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Trenches mode: the field, funds and squads, orders and captures, storming a headquarters, engineers, supports,
 * mortars, the machine gun loader, cover, guns by faction and the computer commander. The field (two trench lines)
 * is built south of the Battle Master inside a padded test area; soldiers carry short-range test guns.
 */
class TrenchGameTests {
    private val faction = Identifier.fromNamespaceAndPath("test", "trench")
    private fun id(path: String) = Identifier.fromNamespaceAndPath("flanstrenches", path)

    /** Short-range guns of the test faction, one per category the trench soldiers ask for. */
    private fun guns() {
        val mag = listOf(Identifier.fromNamespaceAndPath("example", "rifle_mag"))
        Guns.replace(Guns.all + listOf("sniper", "lmg", "smg", "pistol").associate { category ->
            Identifier.fromNamespaceAndPath("test", "trench_$category") to
                GunDefinition("Trench ${category}", faction = faction, category = category, damage = 2f, velocity = 2.0, lifetimeTicks = 6, magazines = mag)
        })
    }

    private fun GameTestHelper.trenchBattle(ai: TrenchAiLevel = TrenchAiLevel.OFF, hq: Int = 20, funds: Int = 5000, lines: Int = 2): BattleMasterBlockEntity {
        guns()
        setBlock(BlockPos(8, 1, 1), FlansBlocks.BATTLE_MASTER)
        val master = getBlockEntity(BlockPos(8, 1, 1), BattleMasterBlockEntity::class.java)
        master.state = master.state.copy(settings = BattleSettings(teams = listOf(BattleTeam("Red", "red", faction), BattleTeam("Blue", "blue", faction)),
            countdownSeconds = 0, timeLimitMinutes = 0,
            trenches = TrenchSettings(ai = ai, trenches = lines, spacing = 12, width = 7, hqCaptureSeconds = hq, startFunds = funds, fundsPerSecond = 0, trenchBonus = 0)))
        assertTrue(TrenchField.build(master, level, Direction.SOUTH), "the field is built")
        return master
    }

    private fun GameTestHelper.started(master: BattleMasterBlockEntity): TrenchLayout {
        Battles.start(master, null)
        assertTrue(master.running, "a Trenches battle needs no players")
        return TrenchRules.layout(master) ?: throw assertionException("the lane is known")
    }

    /** Like assertValueEqual, for values that may be null. */
    private fun GameTestHelper.eq(actual: Any?, expected: Any?, what: String) = assertTrue(actual == expected, "$what: expected $expected, got $actual")

    private fun units(helper: GameTestHelper, master: BattleMasterBlockEntity, team: String? = null) =
        TrenchRules.units(master, helper.level).filter { team == null || it.team == team }

    /** Puts [soldiers] where they were ordered (entities in test areas do not always walk). */
    private fun arrive(layout: TrenchLayout, master: BattleMasterBlockEntity, soldiers: List<SoldierEntity>) =
        soldiers.forEach { s -> TrenchRules.destination(master, s)?.let { s.snapTo(it.x, it.y, it.z); s.navigation.stop() } }

    @GameTest(maxTicks = 20, padding = 60)
    fun theFieldHasTwoHeadquartersAndTrenchLines(helper: GameTestHelper) {
        val master = helper.trenchBattle()
        val layout = TrenchLayout.of(master) ?: throw helper.assertionException("a lane")
        helper.assertTrue(master.settings.mode == BattleMode.TRENCHES && master.state.border.size == 2, "Trenches mode with a border around the field")
        helper.eq(layout.zones.size, 4, "two headquarters and two trench lines")
        helper.assertTrue(layout.zones.first().base && layout.zones.last().base && master.state.posts[layout.zones[0].key]?.team == "Red" &&
            master.state.posts[layout.zones[3].key]?.team == "Blue", "Red's headquarters next to the Battle Master, Blue's at the far end")
        val trench = layout.zones[1]
        helper.assertTrue(master.state.posts[trench.key]?.hill == true, "trench lines are neutral posts")
        val level = helper.level
        helper.assertTrue(level.getBlockState(trench.flag.below()).`is`(net.minecraft.world.level.block.Blocks.SPRUCE_PLANKS), "duckboards in the channel")
        helper.assertTrue(level.getBlockState(trench.flag.offset(1, 0, 0)).isAir, "the channel is dug out")
        helper.assertTrue(level.getBlockState(trench.flag.offset(1, 1, -1)).`is`(Fortifications.SANDBAG_SLAB), "sandbag parapet")
        helper.assertTrue(level.getBlockState(layout.zones[0].flag.below()).`is`(Fortifications.REINFORCED_CONCRETE), "a concrete headquarters")
        helper.succeed()
    }

    @GameTest(maxTicks = 40, padding = 60)
    fun squadsCostFundsAndMarchIntoTheFirstTrench(helper: GameTestHelper) {
        val master = helper.trenchBattle(funds = 250)
        val layout = helper.started(master)
        helper.eq(master.state.trench.funds["Red"], 250, "start funds")
        helper.assertTrue(TrenchRules.buy(master, helper.level, "Red", id("rifle_squad")) == null, "a rifle squad is sent")
        val squad = units(helper, master, "Red")
        helper.assertTrue(squad.size in 3..4 && squad.all { it.order == 1 } && squad.count { it.role == TrenchRole.RIFLEMAN } == 3,
            "three riflemen (maybe with an officer), ordered into the first trench: ${squad.map { it.role to it.order }}")
        helper.assertTrue(squad.all { it.blockPosition().closerThan(layout.zones[0].flag, 6.0) }, "they start at headquarters")
        helper.assertTrue(squad.filter { it.role == TrenchRole.RIFLEMAN }.all { it.mainHandItem.gunId == Identifier.fromNamespaceAndPath("test", "trench_sniper") },
            "riflemen carry their faction's rifle")
        helper.eq(master.state.trench.funds["Red"], 150, "the squad cost 100")
        helper.eq(TrenchRules.buy(master, helper.level, "Red", id("rifle_squad")), "message.flansmod.trenches.not_ready", "a squad of the same kind needs time")
        helper.eq(TrenchRules.buy(master, helper.level, "Red", id("mortar_team")), "message.flansmod.trenches.no_funds", "too expensive")
        Battles.end(master, null)
        helper.succeed()
    }

    @GameTest(maxTicks = 200, padding = 60)
    fun holdingATrenchTakesItAndOrdersMoveSoldiersOn(helper: GameTestHelper) {
        val master = helper.trenchBattle()
        val layout = helper.started(master)
        TrenchRules.buy(master, helper.level, "Red", id("rifle_squad"))
        helper.startSequence().thenWaitUntil {
            arrive(layout, master, units(helper, master, "Red"))
            helper.eq(master.state.posts[layout.zones[1].key]?.team, "Red", "Red alone in trench 1 takes it")
            helper.eq(master.state.scores["Red"], 1, "the score is the trenches held")
        }.thenExecute {
            val moved = TrenchRules.order(master, helper.level, "Red", 1, 2)
            helper.eq(moved, 2, "two go over the top")
            helper.eq(units(helper, master, "Red").count { it.order == 2 }, 2, "ordered to trench 2")
            helper.assertTrue(TrenchRules.orderAll(master, helper.level, "Red", forward = false) > 0, "the whole line falls back")
            helper.assertTrue(units(helper, master, "Red").all { it.order <= 1 }, "nobody beyond trench 1 now")
        }.thenExecute { Battles.end(master, null) }.thenSucceed()
    }

    @GameTest(maxTicks = 200, padding = 60)
    fun stormingTheEnemyHeadquartersWins(helper: GameTestHelper) {
        val master = helper.trenchBattle(hq = 2)
        val layout = helper.started(master)
        TrenchRules.buy(master, helper.level, "Red", id("rifle_squad"))
        units(helper, master, "Red").forEach { it.order = layout.last }
        helper.startSequence().thenWaitUntil {
            if (master.running) arrive(layout, master, units(helper, master, "Red"))
            helper.assertFalse(master.running, "Red alone in Blue's headquarters wins: ${master.state.trench.hq}")
        }.thenSucceed()
    }

    @GameTest(maxTicks = 400, padding = 60)
    fun engineersBuildBunkersLayWireAndCutIt(helper: GameTestHelper) {
        val master = helper.trenchBattle()
        val layout = helper.started(master)
        val trench = layout.zones[1]
        master.setPost(trench.flag, master.state.posts[trench.key]!!.copy(team = "Red"))
        helper.eq(TrenchRules.job(master, helper.level, "Red", TrenchJobKind.BUNKER, 1), "message.flansmod.trenches.no_engineer", "engineers first")
        TrenchRules.buy(master, helper.level, "Red", id("engineers"))
        helper.assertTrue(TrenchRules.job(master, helper.level, "Red", TrenchJobKind.BUNKER, 1) == null, "a bunker is ordered")
        helper.assertTrue(TrenchRules.job(master, helper.level, "Red", TrenchJobKind.WIRE, 1) == null, "and wire in front of the trench")
        helper.startSequence().thenWaitUntil {
            arrive(layout, master, units(helper, master, "Red"))
            helper.assertTrue(master.state.trench.jobs.isEmpty(), "both jobs done: ${master.state.trench.jobs}")
        }.thenExecute {
            helper.assertTrue(trench.key in master.state.trench.bunkers && master.state.trench.sandbags.isNotEmpty(), "a bunker with a sandbag wall")
            helper.assertTrue(master.state.trench.wire.isNotEmpty() && master.state.trench.wire.all { helper.level.getBlockState(BlockPos.of(it)).`is`(Fortifications.BARBED_WIRE) },
                "barbed wire in front")
            helper.eq(TrenchRules.forwardSpawn(master, layout, 0), 1, "the bunker is a forward spawn point")
            helper.eq(master.spawn("Red"), trench.flag, "for players too")
            helper.eq(master.spawn("Blue"), layout.zones.last().flag, "Blue respawns at its headquarters")
            helper.assertTrue(TrenchRules.job(master, helper.level, "Red", TrenchJobKind.CUT, 1) == null, "now cut it again")
        }.thenWaitUntil {
            arrive(layout, master, units(helper, master, "Red"))
            helper.assertTrue(master.state.trench.wire.isEmpty(), "the wire is cut")
        }.thenExecute {
            val sandbags = master.state.trench.sandbags.map(BlockPos::of)
            Battles.end(master, null)
            helper.assertTrue(sandbags.all { helper.level.getBlockState(it).`is`(Fortifications.SANDBAG_SLAB) }, "after the battle the parapet is back")
        }.thenSucceed()
    }

    @GameTest(maxTicks = 40, padding = 60)
    fun trenchesAndBunkersGiveCover(helper: GameTestHelper) {
        val master = helper.trenchBattle()
        val layout = helper.started(master)
        TrenchRules.buy(master, helper.level, "Red", id("sniper"))
        val sniper = units(helper, master, "Red").single()
        val shot = helper.level.damageSources().generic()
        helper.eq(TrenchRules.cover(sniper, shot), 0.6f, "headquarters")
        sniper.order = 1
        arrive(layout, master, listOf(sniper))
        helper.eq(TrenchRules.cover(sniper, shot), 0.75f, "a trench")
        master.setPost(layout.zones[1].flag, master.state.posts[layout.zones[1].key]!!.copy(team = "Red"))
        master.state = master.state.copy(trench = master.state.trench.copy(bunkers = setOf(layout.zones[1].key)))
        helper.eq(TrenchRules.cover(sniper, shot), 0.45f, "a bunker its side holds")
        helper.eq(TrenchRules.cover(sniper, helper.level.damageSources().wither()), 1f, "no cover from gas")
        sniper.snapTo(Vec3.atCenterOf(layout.zones[1].flag).add(layout.axis.scale(6.0)))
        helper.eq(TrenchRules.cover(sniper, shot), 1f, "none in no man's land")
        Battles.end(master, null)
        helper.succeed()
    }

    @GameTest(maxTicks = 40, padding = 60)
    fun theLoaderTakesOverTheMachineGun(helper: GameTestHelper) {
        val master = helper.trenchBattle()
        helper.started(master)
        TrenchRules.buy(master, helper.level, "Red", id("machine_gun_team"))
        val team = units(helper, master, "Red")
        val gunner = team.single { it.role == TrenchRole.MACHINE_GUNNER }
        val loader = team.single { it.partner == gunner.uuid }
        helper.eq(gunner.mainHandItem.gunId, Identifier.fromNamespaceAndPath("test", "trench_lmg"), "the gunner has the machine gun")
        gunner.kill(helper.level)
        helper.assertTrue(loader.role == TrenchRole.MACHINE_GUNNER && loader.mainHandItem.gunId == Identifier.fromNamespaceAndPath("test", "trench_lmg"),
            "the loader picks it up")
        Battles.end(master, null)
        helper.succeed()
    }

    @GameTest(maxTicks = 200, padding = 60)
    fun mortarsAndBarragesShellEnemyTrenches(helper: GameTestHelper) {
        val master = helper.trenchBattle()
        val layout = helper.started(master)
        TrenchRules.buy(master, helper.level, "Red", id("mortar_team"))
        TrenchRules.buy(master, helper.level, "Blue", id("veteran_squad"))
        // Red's mortar in trench 1, Blue's veterans in trench 2, twelve blocks away.
        units(helper, master, "Blue").forEach { it.order = 2 }
        val mortar = units(helper, master, "Red").single { it.role == TrenchRole.MORTAR }
        helper.startSequence().thenWaitUntil {
            arrive(layout, master, units(helper, master))
            val shell = master.state.trench.shells.firstOrNull { it.shooter == mortar.stringUUID }
            helper.assertTrue(shell != null, "the mortar fires")
            helper.assertTrue(kotlin.math.abs(layout.along(Vec3(shell!!.x, shell.y, shell.z)) - layout.zones[2].along) <= 6, "at trench 2")
        }.thenExecute {
            helper.assertTrue(TrenchRules.support(master, helper.level, "Red", id("gas"), 2) == null, "gas on trench 2")
            helper.assertTrue(helper.level.getEntitiesOfClass(AreaEffectCloud::class.java, AABB(layout.zones[2].flag).inflate(8.0)).size == 3,
                "three gas clouds along the trench")
            helper.assertTrue(TrenchRules.support(master, helper.level, "Red", id("barrage"), 2) == null, "a barrage on trench 2")
            helper.eq(master.state.trench.shells.count { it.shooter == null }, 8, "eight shells on their way")
            helper.eq(TrenchRules.support(master, helper.level, "Red", id("barrage"), 2), "message.flansmod.trenches.not_ready", "then the guns reload")
        }.thenExecute { Battles.end(master, null) }.thenSucceed()
    }

    @GameTest(maxTicks = 1200, padding = 75)
    fun theComputerCommandsBothSides(helper: GameTestHelper) {
        // Three lines: each side's first trench, and a neutral one between them worth taking.
        val master = helper.trenchBattle(ai = TrenchAiLevel.HARD, funds = 1500, lines = 3)
        val layout = helper.started(master)
        var orderedOn = false
        helper.startSequence().thenWaitUntil {
            // Drive the soldiers here: entities in test areas are not always ticked.
            units(helper, master).forEach { if (!it.isRemoved) it.tick() }
            orderedOn = orderedOn || units(helper, master, "Red").any { it.order >= 2 } || units(helper, master, "Blue").any { it.order <= 2 }
            helper.assertTrue(units(helper, master, "Red").size >= 6 && units(helper, master, "Blue").size >= 6, "both sides send squads")
            helper.assertTrue(orderedOn, "and push beyond their first trench")
        }.thenExecute {
            helper.assertTrue(TrenchRules.aiCommands(master, 0, layout) && TrenchRules.aiCommands(master, 1, layout), "nobody else commands")
            Battles.end(master, null)
            helper.assertTrue(helper.level.getEntitiesOfClass(SoldierEntity::class.java, AABB(master.blockPos).inflate(80.0)).isEmpty(), "the soldiers leave with the battle")
        }.thenSucceed()
    }

    @GameTest(maxTicks = 20)
    fun soldiersCarryTheirFactionsGuns(helper: GameTestHelper) {
        fun ww2(path: String) = Identifier.fromNamespaceAndPath("flansww2", path)
        val rifleman = TrenchUnits[id("rifle_squad")]!!.soldiers.first()
        val gunner = TrenchUnits[id("machine_gun_team")]!!.soldiers.first()
        val sniper = TrenchUnits[id("sniper")]!!.soldiers.first()
        helper.eq(TrenchRules.gunFor(ww2("axis"), rifleman, null), ww2("kar98k"), "Axis riflemen: Kar98k")
        helper.eq(TrenchRules.gunFor(ww2("uk"), rifleman, null), ww2("lee_enfield"), "British riflemen: Lee-Enfield")
        helper.eq(TrenchRules.gunFor(ww2("uk"), gunner, null), ww2("bren"), "British machine gunners: Bren")
        helper.eq(TrenchRules.gunFor(ww2("axis"), sniper, null), ww2("kar98k_scoped"), "Axis snipers: the scoped Kar98k")
        helper.eq(TrenchRules.gunFor(ww2("uk"), sniper, null), ww2("lee_enfield"), "without a scoped rifle: the plain one")
        helper.eq(TrenchRules.gunFor(ww2("axis"), TrenchSoldier(guns = emptyList()), null), null, "no categories: no gun")
        helper.succeed()
    }
}
