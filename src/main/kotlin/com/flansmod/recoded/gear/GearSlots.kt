package com.flansmod.recoded.gear

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.item.ClothingItem
import com.flansmod.recoded.item.clothingDefinition
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.minecraft.core.NonNullList
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.Container
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.level.gamerules.GameRules

/**
 * Extra player slots (added to vanilla's inventory menu by `InventoryMenuMixin`, next to the off-hand):
 * - the **backpack slot**: the backpack (or parachute) worn on the back (saved and synced as a player attachment, so everyone sees
 *   it; dropped on death unless keepInventory);
 * - **plate slots**: shown while the worn chest armour has `plate_slots`. The plates live on that armour item (its
 *   vanilla `container` component) and add their armour/toughness to it; hits wear them out.
 */
object GearSlots {
    val BACK: AttachmentType<ItemStack> = AttachmentRegistry.create(FlansMod.id("back")) {
        it.persistent(ItemStack.CODEC).syncWith(ItemStack.STREAM_CODEC, AttachmentSyncPredicate.all())
    }

    const val PLATE_SLOTS = 2

    fun back(player: Player): ItemStack = player.getAttachedOrElse(BACK, ItemStack.EMPTY)

    fun setBack(player: Player, stack: ItemStack) {
        if (stack.isEmpty) player.removeAttached(BACK) else player.setAttached(BACK, stack)
    }

    /** The plates in [armor] (empty stacks for free slots), [count] long. */
    fun plates(armor: ItemStack, count: Int): NonNullList<ItemStack> = NonNullList.withSize(count, ItemStack.EMPTY).also {
        armor.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(it)
    }

    /** Writes [plates] into [armor] and recomputes its attribute modifiers (base clothing + plates). */
    fun setPlates(armor: ItemStack, plates: List<ItemStack>) {
        armor.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(plates))
        ClothingItem.applyDefinition(armor)
    }

    // ------------------------------------------------------------------------------------------- slots

    /** The backpack slot (one backpack, nothing else). */
    class BackContainer(private val player: Player) : Container {
        override fun getContainerSize() = 1
        override fun isEmpty() = back(player).isEmpty
        override fun getItem(slot: Int): ItemStack = back(player)
        override fun removeItem(slot: Int, amount: Int): ItemStack = back(player).let { if (it.isEmpty) it else { setBack(player, ItemStack.EMPTY); it } }
        override fun removeItemNoUpdate(slot: Int) = removeItem(slot, 1)
        override fun setItem(slot: Int, stack: ItemStack) = setBack(player, stack)
        override fun setChanged() = setBack(player, back(player)) // re-send (e.g. after its contents changed)
        override fun stillValid(player: Player) = true
        override fun clearContent() = setBack(player, ItemStack.EMPTY)
        override fun getMaxStackSize() = 1
    }

    class BackSlot(player: Player, x: Int, y: Int) : Slot(BackContainer(player), 0, x, y) {
        override fun mayPlace(stack: ItemStack) = stack.gear?.type.let { it == GearType.BACKPACK || it == GearType.PARACHUTE }
        override fun getMaxStackSize() = 1
        override fun getNoItemIcon() = FlansMod.id("container/slot/backpack")
    }

    /** The plate slots of the worn chest armour. */
    class PlateContainer(private val player: Player) : Container {
        private val armor get() = player.getItemBySlot(EquipmentSlot.CHEST)
        val capacity get() = armor.clothingDefinition?.plateSlots?.coerceIn(0, PLATE_SLOTS) ?: 0

        override fun getContainerSize() = PLATE_SLOTS
        override fun isEmpty() = (0 until capacity).all { getItem(it).isEmpty }
        override fun getItem(slot: Int): ItemStack = if (slot < capacity) plates(armor, capacity)[slot] else ItemStack.EMPTY
        override fun removeItem(slot: Int, amount: Int): ItemStack {
            val plates = plates(armor, capacity)
            val taken = plates.getOrNull(slot)?.takeIf { !it.isEmpty } ?: return ItemStack.EMPTY
            plates[slot] = ItemStack.EMPTY
            setPlates(armor, plates)
            return taken
        }
        override fun removeItemNoUpdate(slot: Int) = removeItem(slot, 1)
        override fun setItem(slot: Int, stack: ItemStack) {
            if (slot >= capacity) return
            val plates = plates(armor, capacity)
            plates[slot] = stack
            setPlates(armor, plates)
        }
        override fun setChanged() = Unit
        override fun stillValid(player: Player) = true
        override fun clearContent() = Unit
        override fun getMaxStackSize() = 1
    }

    class PlateSlot(private val plates: PlateContainer, index: Int, x: Int, y: Int) : Slot(plates, index, x, y) {
        override fun mayPlace(stack: ItemStack) = stack.gear?.type == GearType.PLATE
        override fun getMaxStackSize() = 1
        /** Only while the worn armour has this many plate slots. */
        override fun isActive() = containerSlot < plates.capacity
        override fun getNoItemIcon() = FlansMod.id("container/slot/plate")
    }

    // ------------------------------------------------------------------------------------------- events

    fun init() {
        // Each hit by something (gunfire, blows, explosions) wears the plates of the worn armour; broken plates fall out.
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, taken, _ ->
            val player = entity as? ServerPlayer ?: return@register
            if (taken <= 0f || source.entity == null && !source.`is`(DamageTypeTags.IS_EXPLOSION)) return@register
            val armor = player.getItemBySlot(EquipmentSlot.CHEST)
            val capacity = armor.clothingDefinition?.plateSlots ?: return@register
            if (capacity <= 0) return@register
            val plates = plates(armor, capacity)
            var changed = false
            for (i in plates.indices) {
                val plate = plates[i]
                if (plate.isEmpty || !plate.isDamageableItem) continue
                plate.damageValue += 1
                changed = true
                if (plate.damageValue >= plate.maxDamage) {
                    plates[i] = ItemStack.EMPTY
                    player.level().playSound(null, player.blockPosition(), SoundEvents.SHIELD_BREAK.value(), player.soundSource, 0.8f, 1.2f)
                    player.sendOverlayMessage(Component.translatable("message.flansmod.plate_broken", plate.hoverName))
                }
            }
            if (changed) setPlates(armor, plates)
        }
        // The backpack slot drops like the rest of the inventory (and stays with keepInventory).
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, _ ->
            val player = entity as? ServerPlayer ?: return@register
            if ((player.level() as ServerLevel).gameRules.get(GameRules.KEEP_INVENTORY)) return@register
            val back = back(player)
            if (!back.isEmpty) {
                player.drop(back, true, net.minecraft.util.Prediction.SERVER_ONLY)
                setBack(player, ItemStack.EMPTY)
            }
        }
        ServerPlayerEvents.COPY_FROM.register { old, new, _ -> back(old).takeIf { !it.isEmpty }?.let { setBack(new, it) } }
    }
}
