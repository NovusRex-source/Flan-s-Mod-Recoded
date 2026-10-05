package com.flansmod.recoded.test

import com.flansmod.recoded.bench.WeaponAssemblyRecipe
import com.flansmod.recoded.combat.GunHandler
import com.flansmod.recoded.gun.AmmoDefinition
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.MagazineDefinition
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.item.AmmoItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.item.PartItem
import com.flansmod.recoded.item.ammoTypeId
import com.flansmod.recoded.item.loadedMagazine
import com.flansmod.recoded.registry.FlansRecipes
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.CraftingInput

/** Ammunition handling: built-in magazines, stacked magazines, and rounds crafted from casing + gunpowder + tip. */
class AmmoGameTests {
    private fun test(path: String) = Identifier.fromNamespaceAndPath("test", path)
    private fun basic(path: String) = Identifier.fromNamespaceAndPath("flansbasic", path)

    /** A gun of its own caliber (definitions are shared by all tests) and a survival player holding it. */
    private fun GameTestHelper.gun(id: String, internal: Boolean, capacity: Int = 6): Pair<ServerPlayer, ItemStack> {
        val caliber = "cal_$id"
        val round = test("${id}_round")
        val mag = test("${id}_mag")
        AmmoTypes.replace(AmmoTypes.all + (round to AmmoDefinition("Round", caliber = caliber)))
        Magazines.replace(Magazines.all + (mag to MagazineDefinition("Mag", caliber = caliber, capacity = capacity, internal = internal)))
        Guns.replace(Guns.all + (test(id) to GunDefinition(id, magazines = listOf(mag), reloadTicks = 2, velocity = 4.0, lifetimeTicks = 1)))
        val player = makeMockServerPlayerInLevel()
        player.abilities.instabuild = false
        val stack = GunItem.stackFor(test(id)).apply { loadedMagazine = MagazineContents(mag, null, 0) }
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        return player to stack
    }

    private fun ServerPlayer.count(predicate: (ItemStack) -> Boolean) = inventory.nonEquipmentItems.filter(predicate).sumOf { it.count }

    @GameTest(maxTicks = 20)
    fun builtInMagazineLoadsLooseRounds(helper: GameTestHelper) {
        val (player, gun) = helper.gun("tube", internal = true)
        player.inventory.setItem(9, AmmoItem.stackFor(test("tube_round"), 10))
        GunHandler.reload(player)
        helper.succeedWhen {
            helper.assertTrue(gun.loadedMagazine?.rounds == 6, "the tube takes 6 loose rounds, has ${gun.loadedMagazine}")
            helper.assertTrue(player.count { it.ammoTypeId == test("tube_round") } == 4, "4 rounds stay in the inventory")
            helper.assertTrue(player.count { it.item is MagazineItem } == 0, "a built-in magazine never becomes an item")
            // Unloading gives the rounds back and keeps the (empty) tube in the gun.
            player.isShiftKeyDown = true
            GunHandler.unload(player)
            helper.assertTrue(gun.loadedMagazine?.rounds == 0 && player.count { it.ammoTypeId == test("tube_round") } == 10, "unload returns the rounds")
        }
    }

    @GameTest(maxTicks = 20)
    fun reloadTakesOneMagazineFromAStack(helper: GameTestHelper) {
        val (player, gun) = helper.gun("stacked", internal = false)
        val full = MagazineItem.stackFor(MagazineContents(test("stacked_mag"), test("stacked_round"), 6)).copyWithCount(3)
        player.inventory.setItem(9, full)
        GunHandler.reload(player)
        helper.succeedWhen {
            helper.assertTrue(gun.loadedMagazine?.rounds == 6, "a full magazine went in")
            helper.assertTrue(player.inventory.getItem(9).count == 2, "two full magazines are left in the stack")
            helper.assertTrue(player.count { it.item is MagazineItem && it.loadedMagazine?.rounds == 0 } == 1, "the empty one came back separately")
        }
    }

    @GameTest
    fun fillingOneOfAStackOfMagazines(helper: GameTestHelper) {
        val (player, _) = helper.gun("fill", internal = false)
        player.setItemInHand(InteractionHand.MAIN_HAND, MagazineItem.stackFor(test("fill_mag")).copyWithCount(2))
        player.inventory.setItem(9, AmmoItem.stackFor(test("fill_round"), 20))
        player.mainHandItem.use(helper.level, player, InteractionHand.MAIN_HAND)
        helper.assertTrue(player.mainHandItem.count == 1 && player.mainHandItem.loadedMagazine?.rounds == 0, "one empty magazine stays in hand")
        helper.assertTrue(player.count { it.loadedMagazine?.rounds == 6 } == 1, "one magazine was filled")
        helper.assertTrue(player.count { it.ammoTypeId == test("fill_round") } == 14, "6 rounds were used")
        helper.succeed()
    }

    @GameTest(maxTicks = 5)
    fun roundsAreCraftedFromCasingGunpowderAndTip(helper: GameTestHelper) {
        val items = MutableList(WeaponAssemblyRecipe.WIDTH * WeaponAssemblyRecipe.HEIGHT) { ItemStack.EMPTY }
        items[0] = PartItem.stackFor(basic("casing_556"))
        items[1] = ItemStack(Items.GUNPOWDER)
        items[2] = ItemStack(Items.GUNPOWDER)
        items[3] = PartItem.stackFor(basic("bullet_ap"))
        val input = CraftingInput.ofPositioned(WeaponAssemblyRecipe.WIDTH, WeaponAssemblyRecipe.HEIGHT, items).input()
        val result = helper.level.recipeAccess().getRecipeFor(FlansRecipes.WEAPON_ASSEMBLY, input, helper.level).map { it.value().assemble(input) }.orElse(ItemStack.EMPTY)
        helper.assertTrue(result.ammoTypeId == basic("556_ap") && result.count == 12, "5.56 casing + 2 gunpowder + AP core = 12 5.56 AP, got $result")
        helper.succeed()
    }

    @GameTest(maxTicks = 5)
    fun everyCaliberHasAPlainRoundAndEveryRoundARecipe(helper: GameTestHelper) {
        val craftable = helper.level.recipeAccess().recipes.mapNotNull { (it.value() as? WeaponAssemblyRecipe)?.result?.create()?.ammoTypeId }.toSet()
        val missing = AmmoTypes.all.keys.filter { it.namespace in setOf("flansbasic", "flansvehicles", "flansww2") } - craftable
        helper.assertTrue(missing.isEmpty(), "rounds without a bench recipe: $missing")
        val calibers = Magazines.all.filterKeys { it.namespace == "flansbasic" }.values.map { it.caliber }.toSet()
        val withAmmo = AmmoTypes.all.values.map { it.caliber }.toSet()
        helper.assertTrue((calibers - withAmmo).isEmpty(), "calibers without rounds: ${calibers - withAmmo}")
        helper.succeed()
    }
}
