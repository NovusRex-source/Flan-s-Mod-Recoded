package com.flansmod.recoded.item

import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.Magazines
import net.minecraft.resources.Identifier
import net.minecraft.util.Prediction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * Moving loose rounds between the inventory and magazines: magazine items (right click), internal magazines of guns
 * and vehicle guns (reload key). A magazine holds one ammo type at a time.
 */
object AmmoLoading {
    /** The ammo type [contents] would load: the type already inside, else the first fitting rounds in the inventory. */
    fun looseAmmoType(player: Player, contents: MagazineContents): Identifier? {
        val def = contents.definition ?: return null
        contents.ammo?.takeIf { contents.rounds > 0 }?.let { return it }
        return player.inventory.nonEquipmentItems.firstNotNullOfOrNull { s -> s.ammoTypeId?.takeIf { AmmoTypes[it]?.let(def::accepts) == true } }
    }

    /** Rounds of the type [contents] would load that are in the inventory. */
    fun looseRounds(player: Player, contents: MagazineContents): Int {
        val type = looseAmmoType(player, contents) ?: return 0
        return player.inventory.nonEquipmentItems.filter { it.ammoTypeId == type }.sumOf { it.count }
    }

    /** Tops [contents] up from loose rounds in the inventory (consuming them); unchanged if nothing fits. */
    fun fill(player: Player, contents: MagazineContents): MagazineContents {
        val def = contents.definition ?: return contents
        if (contents.isFull) return contents
        val type = looseAmmoType(player, contents) ?: return contents
        var missing = def.capacity - contents.rounds
        for (s in player.inventory.nonEquipmentItems) {
            if (missing == 0) break
            if (s.ammoTypeId != type) continue
            val n = minOf(missing, s.count)
            s.shrink(n)
            missing -= n
        }
        return MagazineContents(contents.magazine, type, def.capacity - missing)
    }

    /** Gives all rounds in [contents] back as ammo items; returns the emptied contents. */
    fun empty(player: Player, contents: MagazineContents): MagazineContents {
        val ammo = contents.ammo ?: return contents
        var left = contents.rounds
        val perStack = AmmoTypes[ammo]?.maxStack?.coerceIn(1, 99) ?: 64
        while (left > 0) {
            val n = minOf(left, perStack)
            player.inventory.placeItemBackInInventory(AmmoItem.stackFor(ammo, n), Prediction.SERVER_ONLY)
            left -= n
        }
        return contents.withRounds(0)
    }

    /**
     * Takes one magazine out of inventory [slot] (magazines stack) and puts [returned] - the magazine that was in the
     * gun - into the freed slot, or anywhere if the stack is not used up.
     */
    fun swapMagazine(player: Player, slot: Int, returned: MagazineContents?) {
        val stack = player.inventory.getItem(slot)
        stack.shrink(1)
        val back = returned?.takeIf { Magazines[it.magazine]?.internal != true }?.let(MagazineItem::stackFor) ?: return
        if (stack.isEmpty) player.inventory.setItem(slot, back) else player.inventory.placeItemBackInInventory(back, Prediction.SERVER_ONLY)
    }

    /** Applies [change] to one magazine of [stack]; a stack of several splits off one and returns it to the inventory. */
    fun editOne(player: Player, stack: ItemStack, change: (ItemStack) -> Boolean): Boolean {
        if (stack.count <= 1) return change(stack)
        val single = stack.copyWithCount(1)
        if (!change(single)) return false
        stack.shrink(1)
        player.inventory.placeItemBackInInventory(single, Prediction.SERVER_ONLY)
        return true
    }
}
