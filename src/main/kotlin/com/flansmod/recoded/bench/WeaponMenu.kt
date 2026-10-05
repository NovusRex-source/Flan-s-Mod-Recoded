package com.flansmod.recoded.bench

import com.flansmod.recoded.gun.FireMode
import com.flansmod.recoded.item.AttachmentItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.attachmentDefinition
import com.flansmod.recoded.item.attachmentId
import com.flansmod.recoded.item.attachments
import com.flansmod.recoded.item.baseDefinition
import com.flansmod.recoded.item.fireMode
import com.flansmod.recoded.item.gunId
import com.flansmod.recoded.network.OpenWeaponMenuPayload
import com.flansmod.recoded.registry.FlansMenus
import com.flansmod.recoded.combat.GunHandler
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/** What the client needs to build the weapon menu: the held gun's attachment slot names. */
data class WeaponMenuData(val slots: List<String>) {
    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, WeaponMenuData> =
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).map(::WeaponMenuData, WeaponMenuData::slots).cast()
    }
}

/**
 * Weapon menu for the gun in the main hand: one slot per attachment slot of the gun, mirrored live into the gun's
 * `flansmod:attachments` component (items in the slots *are* the installed attachments, so nothing is returned on
 * close). Menu buttons select the fire mode. The gun's own hotbar slot is locked while the menu is open.
 *
 * Slot layout: 0 until [slots].size = attachment slots, then 27 inventory + 9 hotbar slots.
 */
class WeaponMenu(id: Int, private val inventory: Inventory, data: WeaponMenuData) : AbstractContainerMenu(FlansMenus.WEAPON, id) {
    val slotNames = data.slots
    private val attachmentSlots = object : SimpleContainer(slotNames.size.coerceAtLeast(1)) {
        override fun setChanged() {
            super.setChanged()
            writeBack()
        }
    }
    private val gunSlot = inventory.selectedSlot
    private val player = inventory.player
    // Only the server writes the gun; filling the slots initially must not write back.
    private var syncing = true

    init {
        slotNames.forEachIndexed { i, name -> addSlot(AttachmentSlot(i, name, slotX(i, slotNames.size), SLOT_Y)) }
        for (row in 0 until 3) for (col in 0 until 9) addSlot(Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18))
        for (col in 0 until 9) addSlot(if (col == gunSlot) LockedSlot(inventory, col, 8 + col * 18, 142) else Slot(inventory, col, 8 + col * 18, 142))

        if (player is ServerPlayer) {
            // Server: fill the slots from the gun; later changes are mirrored back into it.
            val installed = gun.attachments
            slotNames.forEachIndexed { i, name -> installed[name]?.let { attachmentSlots.setItem(i, AttachmentItem.stackFor(it)) } }
        }
        syncing = false
    }

    private val gun: ItemStack get() = inventory.getItem(gunSlot)

    private fun writeBack() {
        if (syncing || player !is ServerPlayer) return
        gun.attachments = slotNames.indices.mapNotNull { i -> attachmentSlots.getItem(i).attachmentId?.let { slotNames[i] to it } }.toMap()
    }

    /** Buttons 0..n select fire modes by ordinal. */
    override fun clickMenuButton(player: Player, id: Int): Boolean {
        val mode = FireMode.entries.getOrNull(id) ?: return false
        (player as? ServerPlayer)?.let { GunHandler.setFireMode(it, mode) }
        return true
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        val moved = if (index < slotNames.size) moveItemStackTo(stack, slotNames.size, slots.size, true)
        else slotNames.indices.any { i -> slots[i].mayPlace(stack) && !slots[i].hasItem() && moveItemStackTo(stack, i, i + 1, false) }
        if (!moved) return ItemStack.EMPTY
        if (stack.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player) = player.inventory.selectedSlot == gunSlot && gun.item is GunItem && gun.baseDefinition != null

    private inner class AttachmentSlot(index: Int, val name: String, x: Int, y: Int) : Slot(attachmentSlots, index, x, y) {
        override fun getMaxStackSize() = 1
        override fun mayPlace(stack: ItemStack): Boolean {
            val attachment = stack.attachmentDefinition ?: return false
            val gunId = gun.gunId ?: return false
            val base = gun.baseDefinition ?: return false
            return attachment.slot == name && attachment.fits(gunId, base)
        }
    }

    private class LockedSlot(inventory: Inventory, index: Int, x: Int, y: Int) : Slot(inventory, index, x, y) {
        override fun mayPickup(player: Player) = false
        override fun mayPlace(stack: ItemStack) = false
    }

    companion object {
        const val SLOT_Y = 40

        /** Attachment slots are centred in a row above the inventory. */
        fun slotX(index: Int, count: Int) = 88 - count * 11 + index * 22 + 2

        fun init() {
            ServerPlayNetworking.registerGlobalReceiver(OpenWeaponMenuPayload.TYPE) { _, ctx -> open(ctx.player()) }
        }

        fun open(player: ServerPlayer) {
            val gun = player.mainHandItem
            val def = gun.baseDefinition ?: return
            player.openMenu(object : ExtendedMenuProvider<WeaponMenuData> {
                override fun getScreenOpeningData(player: ServerPlayer) = WeaponMenuData(def.attachmentSlots)
                override fun getDisplayName(): Component = gun.hoverName
                override fun createMenu(id: Int, inventory: Inventory, player: Player) = WeaponMenu(id, inventory, WeaponMenuData(def.attachmentSlots))
            })
        }
    }
}
