package com.flansmod.recoded.test

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.fortification.Fortifications
import com.flansmod.recoded.gamemode.BattleMasterBlockEntity
import com.flansmod.recoded.gamemode.BattleSettings
import com.flansmod.recoded.gamemode.BattleTeam
import com.flansmod.recoded.gamemode.Battles
import com.flansmod.recoded.gamemode.BattleMode
import com.flansmod.recoded.gamemode.BattleSpawnBlock
import com.flansmod.recoded.gamemode.BattleSpawnBlockEntity
import com.flansmod.recoded.gamemode.BattleWallBlock
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import com.flansmod.recoded.gamemode.BattleRules
import com.flansmod.recoded.gamemode.Post
import com.flansmod.recoded.gamemode.ShopEditorMenu
import com.flansmod.recoded.gamemode.ShopEditorView
import com.flansmod.recoded.gamemode.ShopCategory
import com.flansmod.recoded.gamemode.SoldierEntity
import com.flansmod.recoded.gamemode.TeamFlagBlock
import com.flansmod.recoded.gamemode.TeamFlagMenu
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.Structures
import com.flansmod.recoded.item.StructureItem
import com.flansmod.recoded.item.gunId
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.minecraft.core.Direction
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.AABB
import net.minecraft.world.scores.TeamColor
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

/** Battles (Battle Master, teams, flag posts, money, shop, modes, bots, border, spectators), structure kits and fortifications. */
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
        val master = helper.battle(BattleSettings(startMoney = 300, shop = listOf("100 4 minecraft:bread"), countdownSeconds = 0))
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
            alliances = listOf("Red+Green"), killReward = 150, scoreLimit = 2, countdownSeconds = 0))
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

    private fun GameTestHelper.post(master: BattleMasterBlockEntity, at: BlockPos, team: String?, hill: Boolean = false): BlockPos {
        setBlock(at, FlansBlocks.TEAM_FLAG)
        getBlockEntity(at, TeamFlagBlockEntity::class.java).master = master.blockPos
        val abs = absolutePos(at)
        master.setPost(abs, Post(listOf(abs.x, abs.y, abs.z), team, hill, label = if (hill) "A" else team ?: ""))
        return abs
    }

    private fun GameTestHelper.fighter(master: BattleMasterBlockEntity, team: String) =
        makeMockServerPlayerInLevel().also { Battles.join(it, master, team) }

    private fun GameTestHelper.moveTo(player: ServerPlayer, x: Double, z: Double) = player.snapTo(absoluteVec(Vec3(x, 1.0, z)))

    private fun finish(master: BattleMasterBlockEntity, vararg players: ServerPlayer) {
        Battles.end(master, null)
        players.forEach(Battles::quit)
    }

    @GameTest(maxTicks = 20)
    fun fullTeamsRefuseAndSpectatorsGetTheirGameModeBack(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(teamSize = 1))
        val (a, b) = List(2) { helper.makeMockServerPlayerInLevel() }
        Battles.join(a, master, "Red")
        Battles.join(b, master, "Red")
        helper.assertTrue(Battles.data(b) == null, "a full team turns players away")
        val before = b.gameMode.gameModeForPlayer
        val place = b.blockPosition()
        Battles.spectate(b, master)
        helper.assertTrue(b.gameMode.gameModeForPlayer == GameType.SPECTATOR && master.state.watchers.containsKey(b.stringUUID), "spectating from the Battle Master")
        Battles.stopSpectating(b)
        helper.assertTrue(b.gameMode.gameModeForPlayer == before && b.blockPosition() == place && master.state.watchers.isEmpty(), "back as before")
        Battles.quit(a)
        helper.succeed()
    }

    @GameTest(maxTicks = 120)
    fun countdownProtectsAndTheBattleSetsGameModeAndSpawn(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(countdownSeconds = 2, gameMode = "adventure"))
        val red = helper.fighter(master, "Red")
        val blue = helper.fighter(master, "Blue")
        val flag = helper.flag(BlockPos(4, 1, 4), red)
        val before = red.gameMode.gameModeForPlayer
        Battles.start(master, null)
        val allow = ServerLivingEntityEvents.ALLOW_DAMAGE.invoker()
        helper.assertTrue(red.gameMode.gameModeForPlayer == GameType.ADVENTURE, "fighters play in the battle's game mode")
        helper.assertTrue(red.respawnConfig?.respawnData()?.pos()?.distManhattan(flag.blockPos)?.let { it <= 3 } == true, "the spawn point is at the flag post")
        helper.assertTrue(!allow.allowDamage(red, helper.level.damageSources().playerAttack(blue), 4f), "nobody is hurt during the countdown")
        helper.startSequence().thenWaitUntil { helper.assertTrue(!master.inCountdown, "countdown over") }.thenExecute {
            helper.assertTrue(allow.allowDamage(red, helper.level.damageSources().playerAttack(blue), 4f), "then the fight is on")
            finish(master, red, blue)
            helper.assertTrue(red.gameMode.gameModeForPlayer == before && red.respawnConfig == null, "game mode and spawn point come back")
        }.thenSucceed()
    }

    @GameTest(maxTicks = 200)
    fun holdingAHillScores(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(mode = BattleMode.KING_OF_THE_HILL, captureSeconds = 1, pointSeconds = 1, countdownSeconds = 0, scoreLimit = 0))
        val red = helper.fighter(master, "Red")
        val hill = helper.post(master, BlockPos(4, 1, 4), null, hill = true)
        Battles.start(master, null)
        helper.moveTo(red, 4.5, 5.5)
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(master.post(hill)?.team == "Red", "standing alone at the hill takes it")
            helper.assertTrue(helper.level.getBlockState(hill).getValue(TeamFlagBlock.COLOR) == TeamColor.RED, "the cloth turns red")
            helper.assertTrue((master.state.scores["Red"] ?: 0) >= 2, "a held hill scores over time: ${master.state.scores}")
        }.thenExecute { finish(master, red) }.thenSucceed()
    }

    @GameTest(maxTicks = 200)
    fun lockedPostsCannotBeTakenUnlockedBasesCanInConquest(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(mode = BattleMode.CONQUEST, captureSeconds = 1, countdownSeconds = 0, scoreLimit = 0))
        val red = helper.fighter(master, "Red")
        val blue = helper.fighter(master, "Blue")
        master.owner = red.uuid
        val flag = helper.flag(BlockPos(4, 1, 4), red)
        flag.manage(red, TeamFlagMenu.LOCK)
        helper.assertTrue(master.post(flag.blockPos)?.locked == true && !flag.canClaim(blue), "a locked post cannot be claimed")
        Battles.start(master, null)
        helper.moveTo(red, 0.5, 7.5)
        helper.moveTo(blue, 4.5, 5.5)
        helper.startSequence().thenIdle(40).thenExecute {
            helper.assertTrue(master.post(flag.blockPos)?.team == "Red", "nor captured")
            flag.manage(red, TeamFlagMenu.LOCK)
        }.thenWaitUntil {
            helper.assertTrue(master.post(flag.blockPos)?.team == "Blue", "unlocked, the enemy conquers the base")
        }.thenExecute { finish(master, red, blue) }.thenSucceed()
    }

    @GameTest(maxTicks = 200)
    fun stolenFlagsScoreAtHome(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(mode = BattleMode.CAPTURE_THE_FLAG, countdownSeconds = 0))
        val red = helper.fighter(master, "Red")
        val home = helper.post(master, BlockPos(1, 1, 5), "Red")
        val enemy = helper.post(master, BlockPos(6, 1, 5), "Blue")
        Battles.start(master, null)
        helper.moveTo(red, 6.5, 6.5)
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(master.state.carriers[red.stringUUID] == Post.key(enemy), "walking up to the enemy post steals its flag")
            helper.assertTrue(helper.level.getBlockState(enemy).getValue(TeamFlagBlock.STOLEN), "the pole stands bare")
        }.thenExecute { helper.moveTo(red, 1.5, 6.5) }.thenWaitUntil {
            helper.assertTrue(master.state.scores["Red"] == 1 && master.state.carriers.isEmpty(), "delivered home: a point for Red")
            helper.assertTrue(!helper.level.getBlockState(enemy).getValue(TeamFlagBlock.STOLEN), "the flag is back at its post")
            helper.assertTrue(master.flag("Red") == home, "home stays the spawn")
        }.thenExecute { finish(master, red) }.thenSucceed()
    }

    @GameTest(maxTicks = 120)
    fun withoutAFlagPostTheDeadSpectateUntilThereIsOne(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(countdownSeconds = 0))
        val red = helper.fighter(master, "Red")
        Battles.start(master, null)
        ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(red, red, false)
        helper.assertTrue(red.gameMode.gameModeForPlayer == GameType.SPECTATOR && Battles.data(red)?.waiting == true, "no spawn: spectating")
        helper.post(master, BlockPos(4, 1, 4), "Red")
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(Battles.data(red)?.waiting == false && red.gameMode.gameModeForPlayer == GameType.SURVIVAL, "a new flag post brings them back")
        }.thenExecute { finish(master, red) }.thenSucceed()
    }

    @GameTest(maxTicks = 120)
    fun aTeamWithNoSpawnAndNobodyStandingIsOut(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(countdownSeconds = 0))
        val red = helper.fighter(master, "Red")
        val blue = helper.fighter(master, "Blue")
        helper.post(master, BlockPos(4, 1, 4), "Red")
        Battles.start(master, null)
        ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(blue, blue, false)
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(!master.running, "Blue is out, Red wins")
            helper.assertTrue(blue.gameMode.gameModeForPlayer != GameType.SPECTATOR, "nobody stays a spectator")
        }.thenExecute { listOf(red, blue).forEach(Battles::quit) }.thenSucceed()
    }

    @GameTest(maxTicks = 80)
    fun theBorderKeepsFightersInside(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(countdownSeconds = 0))
        val red = helper.fighter(master, "Red")
        master.addBorder(helper.absolutePos(BlockPos(0, 1, 0)))
        master.addBorder(helper.absolutePos(BlockPos(5, 1, 5)))
        Battles.start(master, null)
        helper.moveTo(red, 9.5, 2.5)
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(master.borderBox()!!.contains(red.position()), "pushed back inside: ${red.position()}")
        }.thenExecute { finish(master, red) }.thenSucceed()
    }

    @GameTest(maxTicks = 20)
    fun theShopEditorPlacesCopiesAndSetsPrices(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings())
        val player = helper.makeMockServerPlayerInLevel()
        master.owner = player.uuid
        val menu = ShopEditorMenu(1, player.inventory, ShopEditorView(master.blockPos.asLong(), listOf("Red", "Blue"), 0, 0), master)
        menu.setCarried(ItemStack(Items.DIAMOND, 5))
        menu.clicked(0, 0, ContainerInput.PICKUP, player)
        helper.assertTrue(menu.carried.count == 5, "placing copies: the item stays yours")
        menu.setCarried(ItemStack.EMPTY)
        val price = Battles.shop(master, "Red")[0].price
        menu.clickMenuButton(player, ShopEditorMenu.PRICE + 5)
        menu.clickMenuButton(player, ShopEditorMenu.PRICE + 2)
        val first = Battles.shop(master, "Red")[0]
        helper.assertTrue(first.stack.`is`(Items.DIAMOND) && first.stack.count == 5 && first.price == price + 99, "sold for the set price: $first")
        helper.assertTrue(Battles.shop(master, "Blue")[0].stack.`is`(Items.DIAMOND).not(), "only Red's shop changed")
        menu.clicked(0, 0, ContainerInput.QUICK_MOVE, player)
        helper.assertTrue(Battles.shop(master, "Red")[0].stack.isEmpty, "shift-click removes it (a gap)")
        helper.succeed()
    }

    @GameTest(maxTicks = 20)
    fun theShopCatalogAddsAnythingWithoutOwningIt(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings())
        val player = helper.makeMockServerPlayerInLevel()
        master.owner = player.uuid
        val axis = Identifier.parse("flansww2:axis")
        // An edited shop that is a single gap: the catalog fills it.
        master.shops = master.shops + ("Red" to listOf(com.flansmod.recoded.gamemode.ShopEntry.EMPTY))
        val fresh = ShopEditorMenu(2, player.inventory, ShopEditorView(master.blockPos.asLong(), listOf("Red", "Blue"), 0, 0, axis.toString()), master)

        helper.assertTrue(fresh.factionOnly, "a team with a faction starts with its faction's catalog")
        fresh.clickMenuButton(player, ShopEditorMenu.CATEGORY + ShopCategory.GUNS.ordinal)
        val guns = (0 until ShopEditorMenu.CATALOG_SIZE).map { fresh.getSlot(ShopEditorMenu.CATALOG_SLOT + it).item }.filter { !it.isEmpty }
        helper.assertTrue(guns.isNotEmpty() && guns.all { it.item is com.flansmod.recoded.item.GunItem && com.flansmod.recoded.gun.factionOf(it.gunId!!) == axis },
            "the gun group shows the Axis guns: ${guns.map { it.gunId }}")
        fresh.search(player, "mp40")
        val mp40 = fresh.getSlot(ShopEditorMenu.CATALOG_SLOT).item
        helper.assertTrue(mp40.gunId?.path == "mp40", "search finds the MP 40: ${mp40.gunId}")

        fresh.clicked(ShopEditorMenu.CATALOG_SLOT, 0, ContainerInput.PICKUP, player)
        helper.assertTrue(player.inventory.isEmpty && fresh.carried.isEmpty, "nothing is taken from or given to the player")
        fresh.clickMenuButton(player, ShopEditorMenu.SET_PRICE + 1234)
        val first = Battles.shop(master, "Red").first()
        helper.assertTrue(first.stack.gunId?.path == "mp40" && first.price == 1234, "added with the exact price: $first")

        fresh.search(player, "")
        fresh.clickMenuButton(player, ShopEditorMenu.CATEGORY + ShopCategory.OTHER.ordinal)
        fresh.clicked(ShopEditorMenu.CATALOG_SLOT, 0, ContainerInput.PICKUP, player)
        helper.assertTrue(fresh.selected == 1, "the next catalog item goes into the next free slot and is selected")
        repeat(3) { fresh.clickMenuButton(player, ShopEditorMenu.COUNT + 3) }
        val second = Battles.shop(master, "Red")[1]
        helper.assertTrue(second.stack.`is`(Items.BREAD) && second.stack.count == 38, "amount raised by 30: $second")
        helper.succeed()
    }

    @GameTest(maxTicks = 20)
    fun structureKitsBuildFacingAwayFromThePlacer(helper: GameTestHelper) {
        val id = Identifier.fromNamespaceAndPath("flansbasic", "pillbox")
        val def = Structures[id] ?: throw helper.assertionException("the basic pack has a pillbox")
        helper.assertTrue(StructureItem.place(helper.level, id, def, helper.absolutePos(BlockPos(4, 0, 1)), Direction.SOUTH), "the template exists")
        helper.assertBlockPresent(Fortifications.REINFORCED_CONCRETE, BlockPos(2, 0, 2))
        helper.assertBlockPresent(Fortifications.BUNKER_DOOR, BlockPos(4, 1, 2))   // entrance at the back, towards the placer
        helper.assertBlockPresent(Fortifications.BUNKER_EMBRASURE, BlockPos(4, 2, 6)) // firing slits at the front
        helper.succeed()
    }

    @GameTest(maxTicks = 600)
    fun botsFillTheTeamsAndFight(helper: GameTestHelper) {
        // Bots carry their faction's guns: a short-range test rifle, so stray bullets stay in this test's area.
        val faction = Identifier.fromNamespaceAndPath("test", "bots")
        Guns.replace(Guns.all + (Identifier.fromNamespaceAndPath("test", "bot_rifle") to GunDefinition("Bot Rifle", faction = faction, damage = 6f,
            velocity = 2.0, lifetimeTicks = 6, magazines = listOf(Identifier.fromNamespaceAndPath("example", "rifle_mag")))))
        val master = helper.battle(BattleSettings(teams = listOf(BattleTeam("Red", "red", faction), BattleTeam("Blue", "blue", faction)),
            teamSize = 2, fillWithBots = true, countdownSeconds = 0, respawnProtection = 3, scoreLimit = 0))
        Battles.start(master, null)
        helper.assertTrue(master.running, "a battle of bots needs no players")
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(master.state.bots.values.groupingBy { it }.eachCount() == mapOf("Red" to 2, "Blue" to 2), "two bots per team: ${master.state.bots}")
            val bots = BattleRules.bots(master, helper.level)
            helper.assertTrue(bots.all { it.mainHandItem.gunId == Identifier.fromNamespaceAndPath("test", "bot_rifle") }, "armed with their faction's gun")
        }.thenExecute {
            // Face to face, four blocks apart: the fight does not depend on where they wandered.
            BattleRules.bots(master, helper.level).forEach { bot ->
                bot.snapTo(helper.absoluteVec(Vec3(if (bot.team == "Red") 2.5 else 6.5, 1.0, 3.5 + bot.callsign.length % 2)))
            }
        }.thenWaitUntil {
            // Entities in GameTest areas are not always ticked: drive the bots' AI here.
            BattleRules.bots(master, helper.level).forEach { if (!it.isRemoved) it.tick() }
            helper.assertTrue(master.state.stats.values.any { it.deaths > 0 }, "bots shoot each other")
        }.thenExecute {
            Battles.end(master, null)
            helper.assertTrue(helper.level.getEntitiesOfClass(SoldierEntity::class.java, AABB(master.blockPos).inflate(32.0)).isEmpty(), "bots leave with the battle")
        }.thenSucceed()
    }

    /** Two short-range guns of one test faction; bots must pick one of them. */
    private fun botFaction(name: String): Pair<Identifier, Set<Identifier>> {
        val faction = Identifier.fromNamespaceAndPath("test", name)
        val guns = (1..2).map { Identifier.fromNamespaceAndPath("test", "${name}_gun_$it") }
        Guns.replace(Guns.all + guns.map { it to GunDefinition("Bot Gun", faction = faction, damage = 1f, velocity = 1.0, lifetimeTicks = 2,
            magazines = listOf(Identifier.fromNamespaceAndPath("example", "rifle_mag"))) })
        return faction to guns.toSet()
    }

    @GameTest(maxTicks = 200)
    fun botsRespawnWithANewFactionLoadoutWithoutAFlagPost(helper: GameTestHelper) {
        val (faction, guns) = botFaction("respawn")
        val master = helper.battle(BattleSettings(teams = listOf(BattleTeam("Red", "red", faction), BattleTeam("Blue", "blue", faction)),
            teamSize = 1, fillWithBots = true, botRespawn = true, botRespawnSeconds = 1, countdownSeconds = 0, scoreLimit = 0))
        Battles.start(master, null)
        var first: SoldierEntity? = null
        helper.startSequence().thenWaitUntil {
            first = BattleRules.bots(master, helper.level, "Red").firstOrNull()
            helper.assertTrue(first != null, "Red gets a bot")
        }.thenExecute {
            helper.assertTrue(first!!.mainHandItem.gunId in guns, "armed with a gun of its faction")
            first!!.kill(helper.level) // no flag post, no spawn point: it can only come back at the Battle Master
            first!!.discard() // the end of the death animation (entities in test areas do not always tick)
        }.thenWaitUntil {
            val next = BattleRules.bots(master, helper.level, "Red").firstOrNull()
            helper.assertTrue(next != null && next.uuid != first!!.uuid, "the fallen bot respawns")
            helper.assertTrue(next!!.mainHandItem.gunId in guns, "with a new loadout of its faction: ${next.mainHandItem.gunId}")
            helper.assertTrue(next.blockPosition().closerThan(master.blockPos, 6.0), "at the Battle Master")
            helper.assertTrue(master.running, "a team whose bots come back is not out")
        }.thenExecute { Battles.end(master, null) }.thenSucceed()
    }

    @GameTest(maxTicks = 200)
    fun withoutBotRespawnOnlyTheFirstWaveFights(helper: GameTestHelper) {
        val (faction, _) = botFaction("no_respawn")
        val master = helper.battle(BattleSettings(teams = listOf(BattleTeam("Red", "red", faction), BattleTeam("Blue", "blue", faction)),
            teamSize = 1, fillWithBots = true, botRespawn = false, botRespawnSeconds = 0, countdownSeconds = 0, scoreLimit = 0))
        Battles.start(master, null)
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(BattleRules.bots(master, helper.level, "Red").isNotEmpty(), "Red gets its first bot")
        }.thenExecute {
            BattleRules.bots(master, helper.level, "Red").forEach { it.kill(helper.level); it.discard() }
        }.thenIdle(40).thenExecute {
            helper.assertTrue(BattleRules.bots(master, helper.level, "Red").isEmpty(), "no replacement without bot respawn")
            helper.assertFalse(master.running, "Red has nobody left and no post: Blue wins")
        }.thenSucceed()
    }

    @GameTest(maxTicks = 40)
    fun defaultSpawnPointsRespawnTeamsWithoutAFlagPost(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(countdownSeconds = 0))
        val moderator = helper.makeMockServerPlayerInLevel()
        master.owner = moderator.uuid
        val red = helper.fighter(master, "Red")
        helper.setBlock(BlockPos(5, 1, 5), FlansBlocks.BATTLE_SPAWN)
        val spawn = helper.getBlockEntity(BlockPos(5, 1, 5), BattleSpawnBlockEntity::class.java)
        spawn.assign(red, "Blue")
        helper.assertTrue(master.state.spawns.isEmpty(), "only the battle's manager assigns spawn points")
        spawn.assign(moderator, "Red")
        helper.assertTrue(master.defaultSpawn("Red") == spawn.blockPos, "the moderator made it Red's spawn")
        helper.assertTrue(helper.level.getBlockState(spawn.blockPos).getValue(BattleSpawnBlock.COLOR) == TeamColor.RED, "the pad turns red")
        Battles.join(moderator, master, "Blue")
        Battles.start(master, null)
        helper.assertTrue(!spawn.canModerate(moderator, master), "a manager fighting in the battle is no moderator")
        helper.assertTrue(red.blockPosition().distManhattan(spawn.blockPos) <= 3, "without a flag post Red starts at its spawn point")
        red.snapTo(helper.absoluteVec(Vec3(1.5, 1.0, 1.5)))
        ServerPlayerEvents.AFTER_RESPAWN.invoker().afterRespawn(red, red, false)
        helper.assertTrue(Battles.data(red)?.waiting == false && red.blockPosition().distManhattan(spawn.blockPos) <= 3, "and respawns there instead of spectating")
        finish(master, red, moderator)
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun withoutBlockDamageTheBattlefieldStaysIntact(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(countdownSeconds = 0, blockDamage = false))
        // A small border keeps the protected area inside this test (other tests blow things up nearby).
        master.addBorder(helper.absolutePos(BlockPos(0, 1, 0)))
        master.addBorder(helper.absolutePos(BlockPos(6, 1, 6)))
        val red = helper.fighter(master, "Red")
        Battles.start(master, null)
        val stone = BlockPos(3, 1, 3)
        helper.setBlock(stone, Blocks.STONE)
        val at = helper.absoluteVec(Vec3(3.5, 2.2, 3.5))
        helper.level.explode(null, at.x, at.y, at.z, 3f, true, Level.ExplosionInteraction.TNT)
        helper.assertBlockPresent(Blocks.STONE, stone)
        helper.assertTrue(BlockPos.betweenClosed(helper.absolutePos(BlockPos(0, 1, 0)), helper.absolutePos(BlockPos(6, 3, 6))).none { helper.level.getBlockState(it).`is`(Blocks.FIRE) },
            "nor does it burn")
        helper.assertTrue(!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(helper.level, red, helper.absolutePos(stone), Blocks.STONE.defaultBlockState(), null),
            "fighters cannot mine it")
        finish(master, red)
        helper.level.explode(null, at.x, at.y, at.z, 3f, false, Level.ExplosionInteraction.TNT)
        helper.assertBlockNotPresent(Blocks.STONE, stone)
        helper.succeed()
    }

    @GameTest(maxTicks = 80)
    fun theBorderWallStopsOnlyFighters(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(countdownSeconds = 0, borderWall = true))
        master.addBorder(helper.absolutePos(BlockPos(2, 1, 2)))
        master.addBorder(helper.absolutePos(BlockPos(5, 1, 5)))
        val red = helper.fighter(master, "Red")
        val outsider = helper.makeMockServerPlayerInLevel()
        Battles.start(master, null)
        helper.startSequence().thenWaitUntil {
            helper.assertBlockPresent(FlansBlocks.BATTLE_WALL, BlockPos(1, 1, 3))
            helper.assertBlockPresent(FlansBlocks.BATTLE_WALL, BlockPos(6, 3, 4))
            helper.assertBlockPresent(FlansBlocks.BATTLE_WALL, BlockPos(3, 2, 6))
        }.thenExecute {
            helper.assertTrue(BattleWallBlock.blocks(red) && !BattleWallBlock.blocks(outsider), "solid for fighters, air for everyone else")
            master.state = master.state.copy(settings = master.settings.copy(borderWall = false))
        }.thenWaitUntil {
            helper.assertBlockNotPresent(FlansBlocks.BATTLE_WALL, BlockPos(1, 1, 3))
        }.thenExecute { finish(master, red) }.thenSucceed()
    }

    @GameTest(maxTicks = 400)
    fun botsOpenDoorsOnTheirWay(helper: GameTestHelper) {
        val master = helper.battle(BattleSettings(mode = BattleMode.KING_OF_THE_HILL, captureSeconds = 60, countdownSeconds = 0, scoreLimit = 0))
        // A closed room with a bunker door to the south; the hill lies outside.
        for (x in 2..6) for (z in 1..5) for (y in 1..3) if (x == 2 || x == 6 || z == 1 || z == 5) helper.setBlock(BlockPos(x, y, z), Blocks.STONE)
        val door = Fortifications.BUNKER_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
        helper.setBlock(BlockPos(4, 1, 5), door)
        helper.setBlock(BlockPos(4, 2, 5), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))
        helper.post(master, BlockPos(4, 1, 7), null, hill = true)
        val player = helper.fighter(master, "Blue") // a battle needs a player (or bots filling the teams) to start
        Battles.start(master, null)
        val bot = SoldierEntity.deploy(master, helper.level, master.settings.teams.first(), "Door Tester", helper.absolutePos(BlockPos(4, 1, 3)))!!
        bot.removeAllEffects()
        helper.startSequence().thenWaitUntil {
            if (!bot.isRemoved) bot.tick() // entities in GameTest areas are not always ticked
            helper.assertTrue(bot.z > helper.absoluteVec(Vec3(0.0, 0.0, 5.6)).z, "the bot walked out through the door: ${helper.relativeVec(bot.position())}")
        }.thenExecute { finish(master, player) }.thenSucceed()
    }

    @GameTest(maxTicks = 160)
    fun barbedWireHurtsAndTanksFlattenIt(helper: GameTestHelper) {
        helper.setBlock(BlockPos(1, 1, 1), Fortifications.BARBED_WIRE)
        val husk = helper.spawn(EntityTypes.SHEEP, BlockPos(1, 1, 1)) // a mob that moves: block contact effects come with movement
        helper.setBlock(BlockPos(4, 1, 2), Fortifications.BARBED_WIRE)
        val id = Identifier.fromNamespaceAndPath("test", "wire_tank")
        Vehicles.replace(Vehicles.all + (id to VehicleDefinition("Tank", type = VehicleType.TANK, width = 1.5f, height = 1f, deathExplosion = null, fuel = Fuel(capacity = 0))))
        val tank = DriveableEntity(helper.level, id, helper.absoluteVec(Vec3(4.5, 1.0, 2.5)), 0f).also(helper.level::addFreshEntity)
        helper.succeedWhen {
            if (!tank.isRemoved && tank.tickCount == 0) tank.tick() // the test area's entities do not always tick
            // Block contact effects come with movement: jiggle the sheep inside the wire and tick it (it may not wander).
            if (husk.health >= husk.maxHealth && !husk.isRemoved) {
                husk.deltaMovement = Vec3(if (husk.tickCount % 2 == 0) 0.05 else -0.05, 0.0, 0.0)
                husk.tick()
            }
            helper.assertTrue(husk.health < husk.maxHealth, "barbed wire cuts")
            helper.assertBlockNotPresent(Fortifications.BARBED_WIRE, BlockPos(4, 1, 2))
        }
    }
}
