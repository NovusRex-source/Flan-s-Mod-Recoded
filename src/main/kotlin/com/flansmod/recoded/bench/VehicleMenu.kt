package com.flansmod.recoded.bench

import com.flansmod.recoded.fuel.exchangeFuel

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.item.VehicleUpgradeItem
import com.flansmod.recoded.item.vehicleUpgradeDefinition
import com.flansmod.recoded.item.vehicleUpgradeId
import com.flansmod.recoded.network.OpenVehicleMenuPayload
import com.flansmod.recoded.registry.FlansMenus
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.registries.BuiltInRegistries
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

/** What the client needs to build the vehicle menu: which vehicle, and its upgrade slot names. */
data class VehicleMenuData(val vehicle: Int, val slots: List<String>) {
    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, VehicleMenuData> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, VehicleMenuData::vehicle,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), VehicleMenuData::slots,
            ::VehicleMenuData,
        )
    }
}

/**
 * Vehicle menu, opened with the weapon-menu key while riding: one slot per upgrade slot, mirrored live into the
 * vehicle's installed upgrades (like [WeaponMenu] does for gun attachments), and a fuel slot that pours any fuel item
 * into the tank. The screen also shows the state of every part, and a button opens the cargo ([VehicleStorageMenu]).
 *
 * Slot layout: 0 until [slotNames].size = upgrade slots, then the fuel slot, then 27 inventory + 9 hotbar slots.
 */
class VehicleMenu(id: Int, private val inventory: Inventory, data: VehicleMenuData) : AbstractContainerMenu(FlansMenus.VEHICLE, id) {
    val slotNames = data.slots
    val vehicle: DriveableEntity? = inventory.player.level().getEntity(data.vehicle) as? DriveableEntity
    private val player = inventory.player
    private var syncing = true

    private val upgradeSlots = object : SimpleContainer(slotNames.size.coerceAtLeast(1)) {
        override fun setChanged() {
            super.setChanged()
            writeBack()
        }
    }
    private val fuelSlot = object : SimpleContainer(1) {
        override fun setChanged() {
            super.setChanged()
            if (!syncing && player is ServerPlayer) refuel()
        }
    }

    init {
        slotNames.forEachIndexed { i, name -> addSlot(UpgradeSlot(i, name, slotX(i, slotNames.size), SLOT_Y)) }
        addSlot(object : Slot(fuelSlot, 0, FUEL_X, FUEL_Y) {
            override fun mayPlace(stack: ItemStack) = stack.item is com.flansmod.recoded.fuel.FuelCanItem ||
                vehicle?.definition?.fuel?.items?.containsKey(BuiltInRegistries.ITEM.getKey(stack.item)) == true
        })
        for (row in 0 until 3) for (col in 0 until 9) addSlot(Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18))
        for (col in 0 until 9) addSlot(Slot(inventory, col, 8 + col * 18, 142))

        if (player is ServerPlayer) {
            val installed = vehicle?.upgrades.orEmpty()
            slotNames.forEachIndexed { i, name -> installed[name]?.let { upgradeSlots.setItem(i, VehicleUpgradeItem.stackFor(it)) } }
        }
        syncing = false
    }

    private fun writeBack() {
        if (syncing || player !is ServerPlayer) return
        vehicle?.upgrades = slotNames.indices.mapNotNull { i -> upgradeSlots.getItem(i).vehicleUpgradeId?.let { slotNames[i] to it } }.toMap()
    }

    /** Pours the fuel slot into the tank as far as it fits; containers (lava bucket) stay behind empty. */
    private fun refuel() {
        val vehicle = vehicle ?: return
        val fuel = vehicle.definition?.fuel ?: return
        // Fuel cans of the vehicle's fuel type empty into the tank and stay in the slot.
        fuelSlot.getItem(0).takeIf { it.item is com.flansmod.recoded.fuel.FuelCanItem }?.let { can ->
            val tank = com.flansmod.recoded.fuel.FuelStack(fuel.type, vehicle.fuel)
            vehicle.fuel = can.exchangeFuel(tank, fuel.capacity, intoTank = true)?.amount ?: vehicle.fuel
            return
        }
        while (true) {
            val stack = fuelSlot.getItem(0)
            val value = fuel.items[BuiltInRegistries.ITEM.getKey(stack.item)] ?: return
            if (stack.isEmpty || vehicle.fuel + value > fuel.capacity) return
            vehicle.fuel += value
            val remainder = stack.item.craftingRemainder?.create()
            stack.shrink(1)
            if (remainder != null) {
                if (stack.isEmpty) fuelSlot.setItem(0, remainder) else player.inventory.placeItemBackInInventory(remainder, net.minecraft.util.Prediction.SERVER_ONLY)
                return
            }
        }
    }

    /** Button 0: open the vehicle's cargo (the screen shows the button only when there is storage). */
    override fun clickMenuButton(player: Player, id: Int): Boolean {
        if (id != STORAGE_BUTTON || player !is ServerPlayer) return false
        return vehicle?.openStorage(player) == true
    }

    override fun removed(player: Player) {
        super.removed(player)
        // The fuel slot is not part of the vehicle: leftovers go back to the player.
        if (player is ServerPlayer) clearContainer(player, fuelSlot)
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val original = stack.copy()
        val ownSlots = slotNames.size + 1
        val moved = if (index < ownSlots) moveItemStackTo(stack, ownSlots, slots.size, true)
        else (0 until ownSlots).any { i -> slots[i].mayPlace(stack) && (i == slotNames.size || !slots[i].hasItem()) && moveItemStackTo(stack, i, i + 1, false) }
        if (!moved) return ItemStack.EMPTY
        if (stack.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return original
    }

    override fun stillValid(player: Player): Boolean {
        val vehicle = vehicle ?: return false
        return vehicle.isAlive && (player.vehicle == vehicle || player.distanceToSqr(vehicle) < 64.0)
    }

    private inner class UpgradeSlot(index: Int, val name: String, x: Int, y: Int) : Slot(upgradeSlots, index, x, y) {
        override fun getMaxStackSize() = 1
        override fun mayPlace(stack: ItemStack): Boolean {
            val upgrade = stack.vehicleUpgradeDefinition ?: return false
            val id = vehicle?.vehicleId ?: return false
            val base = vehicle.baseDefinition ?: return false
            return upgrade.slot == name && upgrade.fits(id, base)
        }
    }

    companion object {
        const val STORAGE_BUTTON = 0
        const val SLOT_Y = 32
        /** The fuel slot sits right of the upgrade row, in the same line. */
        const val FUEL_X = 152
        const val FUEL_Y = SLOT_Y

        /** Upgrade slots are centred in the space left of the fuel slot. */
        fun slotX(index: Int, count: Int): Int {
            val spacing = if (count <= 1) 0 else minOf(36, 108 / (count - 1))
            return 58 - spacing * (count - 1) / 2 + index * spacing
        }

        fun init() {
            ServerPlayNetworking.registerGlobalReceiver(OpenVehicleMenuPayload.TYPE) { _, ctx ->
                (ctx.player().vehicle as? DriveableEntity)?.let { open(ctx.player(), it) }
            }
        }

        fun open(player: ServerPlayer, vehicle: DriveableEntity) {
            val def = vehicle.baseDefinition ?: return
            val data = VehicleMenuData(vehicle.id, def.upgradeSlots)
            player.openMenu(object : ExtendedMenuProvider<VehicleMenuData> {
                override fun getScreenOpeningData(player: ServerPlayer) = data
                override fun getDisplayName(): Component = Component.translatableWithFallback("vehicle.${vehicle.vehicleId!!.toLanguageKey()}", def.name)
                override fun createMenu(id: Int, inventory: Inventory, player: Player) = VehicleMenu(id, inventory, data)
            })
        }
    }
}
