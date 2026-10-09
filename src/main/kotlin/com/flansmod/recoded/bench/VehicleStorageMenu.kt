package com.flansmod.recoded.bench

import com.flansmod.recoded.entity.DriveableEntity
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.ItemStack

/**
 * A vehicle's cargo as a vanilla chest menu (so the client uses the vanilla chest screen): the first `size` slots of
 * [DriveableEntity.storage], valid while the vehicle exists and the player is in or next to it.
 */
object VehicleStorageMenu {
    private val TYPES: List<MenuType<ChestMenu>> = listOf(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3,
        MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6)

    fun open(player: ServerPlayer, vehicle: DriveableEntity, size: Int) {
        val rows = (size / 9).coerceIn(1, 6)
        val name = vehicle.definition?.let { Component.translatableWithFallback("vehicle.${vehicle.vehicleId!!.toLanguageKey()}", it.name) } ?: vehicle.name
        val title = Component.translatable("gui.flansmod.vehicle.storage_title", name)
        player.openMenu(SimpleMenuProvider({ id, inventory, _ -> ChestMenu(TYPES[rows - 1], id, inventory, View(vehicle, rows * 9), rows) }, title))
        vehicle.playSound(net.minecraft.sounds.SoundEvents.CHEST_OPEN, 0.6f, 1.1f)
    }

    /** The usable part of the vehicle's storage. */
    private class View(private val vehicle: DriveableEntity, private val size: Int) : Container {
        private val storage get() = vehicle.storage
        override fun getContainerSize() = size
        override fun isEmpty() = (0 until size).all { storage.getItem(it).isEmpty }
        override fun getItem(slot: Int): ItemStack = storage.getItem(slot)
        override fun removeItem(slot: Int, amount: Int): ItemStack = storage.removeItem(slot, amount)
        override fun removeItemNoUpdate(slot: Int): ItemStack = storage.removeItemNoUpdate(slot)
        override fun setItem(slot: Int, stack: ItemStack) = storage.setItem(slot, stack)
        override fun setChanged() = storage.setChanged()
        override fun stillValid(player: Player) = vehicle.isAlive && (player.vehicle == vehicle || player.distanceToSqr(vehicle) < 64.0)
        override fun clearContent() = (0 until size).forEach { storage.setItem(it, ItemStack.EMPTY) }
    }
}
