package com.flansmod.recoded.item

import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansItems
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * A magazine. Right click fills it with matching rounds from the inventory, sneak + right click empties it.
 * The contents live in [FlansComponents.MAGAZINE], the same component a gun uses for its inserted magazine.
 */
class MagazineItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component {
        val contents = stack.loadedMagazine ?: return super.getName(stack)
        val def = contents.definition ?: return super.getName(stack)
        return Component.translatableWithFallback("magazine.${contents.magazine.toLanguageKey()}", def.name)
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, add: Consumer<Component>, flag: TooltipFlag) {
        val contents = stack.loadedMagazine ?: return
        val def = contents.definition ?: return
        add.accept(Component.translatable("item.flansmod.magazine.rounds", contents.rounds, def.capacity).withStyle(ChatFormatting.GRAY))
        add.accept(Component.translatable("item.flansmod.magazine.caliber", def.caliber).withStyle(ChatFormatting.GRAY))
        contents.ammo?.let { add.accept(Component.translatable("item.flansmod.gun.ammo_type", AmmoItem.displayName(it)).withStyle(ChatFormatting.GRAY)) }
        add.accept(Component.translatable("item.flansmod.magazine.how").withStyle(ChatFormatting.DARK_GRAY))
    }

    override fun isBarVisible(stack: ItemStack) = stack.loadedMagazine?.definition != null
    override fun getBarWidth(stack: ItemStack) = stack.loadedMagazine?.let { 13 * it.rounds / it.capacity.coerceAtLeast(1) } ?: 0
    override fun getBarColor(stack: ItemStack) = 0xE0B040

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        val contents = stack.loadedMagazine ?: return InteractionResult.FAIL
        if (level.isClientSide) return InteractionResult.SUCCESS
        val changed = if (player.isShiftKeyDown) unload(player, stack, contents) else fill(player, stack, contents)
        if (changed) level.playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_CHAIN.value(), SoundSource.PLAYERS, 0.7f, 1.4f)
        return if (changed) InteractionResult.SUCCESS else InteractionResult.FAIL
    }

    companion object {
        fun stackFor(id: Identifier, full: Boolean = false, ammo: Identifier? = null): ItemStack = ItemStack(FlansItems.MAGAZINE).apply {
            val contents = if (full) MagazineContents.full(id, ammo) else MagazineContents(id, null, 0)
            set(FlansComponents.MAGAZINE, contents ?: MagazineContents(id, null, 0))
            Magazines[id]?.icon?.let { set(DataComponents.ITEM_MODEL, it) }
        }

        fun stackFor(contents: MagazineContents): ItemStack = stackFor(contents.magazine).apply { set(FlansComponents.MAGAZINE, contents) }

        /** Loads rounds of the magazine's caliber from the inventory; keeps the ammo type already inside. */
        fun fill(player: Player, stack: ItemStack, contents: MagazineContents): Boolean {
            val def = contents.definition ?: return false
            if (contents.isFull) return false
            val inventory = player.inventory.nonEquipmentItems
            val type = contents.ammo?.takeIf { contents.rounds > 0 }
                ?: inventory.firstNotNullOfOrNull { s -> s.ammoTypeId?.takeIf { AmmoTypes[it]?.let(def::accepts) == true } }
                ?: return false
            var missing = def.capacity - contents.rounds
            for (s in inventory) {
                if (missing == 0) break
                if (s.ammoTypeId != type) continue
                val n = minOf(missing, s.count)
                s.shrink(n)
                missing -= n
            }
            val loaded = def.capacity - missing
            if (loaded == contents.rounds) return false
            stack.loadedMagazine = MagazineContents(contents.magazine, type, loaded)
            return true
        }

        /** Returns all rounds to the inventory as ammo items. */
        fun unload(player: Player, stack: ItemStack, contents: MagazineContents): Boolean {
            val ammo = contents.ammo ?: return false
            if (contents.rounds <= 0) return false
            var left = contents.rounds
            val perStack = AmmoTypes[ammo]?.maxStack?.coerceIn(1, 99) ?: 64
            while (left > 0) {
                val n = minOf(left, perStack)
                player.inventory.placeItemBackInInventory(AmmoItem.stackFor(ammo, n), Prediction.SERVER_ONLY)
                left -= n
            }
            stack.loadedMagazine = contents.withRounds(0)
            return true
        }
    }
}
