package com.flansmod.recoded.test

import com.flansmod.recoded.bench.WeaponAssemblyRecipe
import com.flansmod.recoded.bench.WeaponsBenchMenu
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.PartItem
import com.flansmod.recoded.item.gunId
import com.flansmod.recoded.registry.FlansBlocks
import com.flansmod.recoded.registry.FlansRecipes
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput

class BenchGameTests {
    private fun basic(path: String) = Identifier.fromNamespaceAndPath("flansbasic", path)
    private fun part(path: String) = PartItem.stackFor(basic(path))

    /** A 6x4 grid with [rows] (part ids, null = empty) placed at column [left], row [top]. */
    private fun grid(left: Int, top: Int, vararg rows: List<String?>): List<ItemStack> {
        val items = MutableList(WeaponAssemblyRecipe.WIDTH * WeaponAssemblyRecipe.HEIGHT) { ItemStack.EMPTY }
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, id -> if (id != null) items[(top + y) * WeaponAssemblyRecipe.WIDTH + left + x] = part(id) } }
        return items
    }

    private fun GameTestHelper.assemble(items: List<ItemStack>): ItemStack {
        val input = CraftingInput.ofPositioned(WeaponAssemblyRecipe.WIDTH, WeaponAssemblyRecipe.HEIGHT, items).input()
        return level.recipeAccess().getRecipeFor(FlansRecipes.WEAPON_ASSEMBLY, input, level).map { it.value().assemble(input) }.orElse(ItemStack.EMPTY)
    }

    private fun rifle(stock: String, grip: String, barrel: String = "barrel", receiver: String = "rifle_receiver") = arrayOf(
        listOf(stock, receiver, "gas_system", barrel),
        listOf(null, grip, "trigger_group", null),
    )

    @GameTest(maxTicks = 5)
    fun partsDecideWhichGunIsAssembled(helper: GameTestHelper) {
        helper.assertTrue(helper.assemble(grid(0, 0, *rifle("wood_stock", "wood_grip"))).gunId == basic("ak47"), "wood furniture → AK-47")
        helper.assertTrue(helper.assemble(grid(1, 2, *rifle("polymer_stock", "polymer_grip"))).gunId == basic("m4a1"), "polymer furniture anywhere on the grid → M4A1")
        helper.assertTrue(helper.assemble(grid(0, 0, *rifle("polymer_stock", "polymer_grip", barrel = "long_barrel"))).gunId == basic("m16a4"), "long barrel → M16A4")
        helper.assertTrue(helper.assemble(grid(0, 0, *rifle("polymer_stock", "polymer_grip", receiver = "battle_receiver"))).gunId == basic("scar_h"), "battle receiver → SCAR-H")
        helper.assertTrue(helper.assemble(grid(0, 0, *rifle("wood_stock", "polymer_grip"))).isEmpty, "mixed furniture is no recipe")
        val mirrored = rifle("wood_stock", "wood_grip").map { it.reversed() }.toTypedArray()
        helper.assertTrue(helper.assemble(grid(2, 0, *mirrored)).gunId == basic("ak47"), "mirrored layout also works")
        helper.succeed()
    }

    @GameTest(maxTicks = 5)
    fun everyBasicGunHasABenchRecipe(helper: GameTestHelper) {
        val craftable = helper.level.recipeAccess().recipes
            .mapNotNull { (it.value() as? WeaponAssemblyRecipe)?.result?.create()?.gunId }.toSet()
        val missing = Guns.all.keys.filter { it.namespace == "flansbasic" } - craftable
        helper.assertTrue(missing.isEmpty(), "guns without a bench recipe: $missing")
        helper.succeed()
    }

    @GameTest(maxTicks = 10)
    fun benchMenuCraftsAndConsumesParts(helper: GameTestHelper) {
        val pos = BlockPos(1, 1, 1)
        helper.setBlock(pos, FlansBlocks.WEAPONS_BENCH)
        val player = helper.makeMockServerPlayerInLevel()
        val menu = WeaponsBenchMenu(1, player.inventory, ContainerLevelAccess.create(helper.level, helper.absolutePos(pos)))
        val parts = grid(0, 0, listOf("pistol_frame", "short_barrel"), listOf("polymer_grip", "trigger_group"))
        parts.forEachIndexed { i, stack -> if (!stack.isEmpty) menu.grid.setItem(i, stack) }
        menu.slotsChanged(menu.grid)

        val result = menu.slots[0].item
        helper.assertTrue(result.gunId == basic("glock17"), "result slot should show a Glock 17, shows $result")
        menu.quickMoveStack(player, 0)
        helper.assertTrue(player.inventory.nonEquipmentItems.any { it.gunId == basic("glock17") }, "crafted gun should be in the inventory")
        helper.assertTrue((0 until menu.grid.containerSize).all { menu.grid.getItem(it).isEmpty }, "all parts used up")
        helper.assertTrue(menu.slots[0].item.isEmpty, "result cleared")
        helper.succeed()
    }
}
