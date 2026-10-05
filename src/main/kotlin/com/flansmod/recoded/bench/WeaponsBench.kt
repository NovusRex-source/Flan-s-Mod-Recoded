package com.flansmod.recoded.bench

import com.flansmod.recoded.registry.FlansBlocks
import com.flansmod.recoded.registry.FlansMenus
import com.flansmod.recoded.registry.FlansRecipes
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.inventory.ResultContainer
import net.minecraft.world.inventory.Slot
import net.minecraft.world.inventory.TransientCraftingContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/** The Weapons Bench: a block that opens a 6x4 assembly grid for [WeaponAssemblyRecipe]s. */
class WeaponsBenchBlock(properties: Properties) : Block(properties) {
    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (!level.isClientSide) player.openMenu(state.getMenuProvider(level, pos))
        return InteractionResult.SUCCESS
    }

    override fun getMenuProvider(state: BlockState, level: Level, pos: BlockPos): MenuProvider =
        SimpleMenuProvider({ id, inventory, _ -> WeaponsBenchMenu(id, inventory, ContainerLevelAccess.create(level, pos)) }, TITLE)

    companion object {
        val TITLE: Component = Component.translatable("container.flansmod.weapons_bench")
    }
}

/**
 * Slot layout: 0 = result, 1..24 = the 6x4 grid, 25..60 = player inventory and hotbar.
 * The result is computed on the server whenever the grid changes; vanilla menu sync sends it to the client.
 */
class WeaponsBenchMenu(id: Int, inventory: Inventory, private val access: ContainerLevelAccess = ContainerLevelAccess.NULL) :
    AbstractContainerMenu(FlansMenus.WEAPONS_BENCH, id) {

    val grid = TransientCraftingContainer(this, WeaponAssemblyRecipe.WIDTH, WeaponAssemblyRecipe.HEIGHT)
    private val result = ResultContainer()
    private val player = inventory.player

    init {
        addSlot(ResultSlot(150, 45))
        for (row in 0 until WeaponAssemblyRecipe.HEIGHT) for (col in 0 until WeaponAssemblyRecipe.WIDTH) {
            addSlot(Slot(grid, col + row * WeaponAssemblyRecipe.WIDTH, 8 + col * 18, 18 + row * 18))
        }
        addStandardInventorySlots(inventory, 8, 108)
    }

    override fun slotsChanged(container: Container) {
        if (container == grid) access.execute { level, _ -> updateResult(level) }
    }

    private fun updateResult(level: Level) {
        val serverLevel = level as? ServerLevel ?: return
        val input = grid.asPositionedCraftInput().input()
        val stack = serverLevel.recipeAccess().getRecipeFor(FlansRecipes.WEAPON_ASSEMBLY, input, serverLevel)
            .map { it.value().assemble(input) }.orElse(ItemStack.EMPTY)
        result.setItem(0, stack)
        broadcastChanges()
    }

    /** Taking the result uses up one of every item on the grid (recipes match exactly, so all of them are ingredients). */
    private inner class ResultSlot(x: Int, y: Int) : Slot(result, 0, x, y) {
        override fun mayPlace(stack: ItemStack) = false

        override fun onTake(player: Player, stack: ItemStack) {
            stack.onCraftedBy(player, stack.count)
            for (i in 0 until grid.containerSize) if (!grid.getItem(i).isEmpty) grid.removeItem(i, 1)
            slotsChanged(grid)
            super.onTake(player, stack)
        }
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        val moved = when (index) {
            RESULT -> moveItemStackTo(stack, INVENTORY_START, INVENTORY_END, true).also { if (it) slot.onQuickCraft(stack, original) }
            in GRID -> moveItemStackTo(stack, INVENTORY_START, INVENTORY_END, false)
            else -> moveItemStackTo(stack, GRID.first, GRID.last + 1, false)
        }
        if (!moved) return ItemStack.EMPTY
        if (stack.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        if (stack.count == original.count) return ItemStack.EMPTY
        slot.onTake(player, stack)
        return original
    }

    override fun removed(player: Player) {
        super.removed(player)
        access.execute { _, _ -> clearContainer(player, grid) }
    }

    override fun stillValid(player: Player) = stillValid(access, player, FlansBlocks.WEAPONS_BENCH)

    private companion object {
        const val RESULT = 0
        val GRID = 1..24
        const val INVENTORY_START = 25
        const val INVENTORY_END = 61
    }
}
