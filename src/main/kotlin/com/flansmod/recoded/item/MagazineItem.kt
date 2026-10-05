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
        Tooltips.category(add, "magazine", Tooltips.caliber(def.caliber))
        add.accept(Component.translatable("item.flansmod.magazine.rounds", contents.rounds, def.capacity).withStyle(ChatFormatting.GRAY))
        contents.ammo?.let { add.accept(Component.translatable("item.flansmod.gun.ammo_type", AmmoItem.displayName(it)).withStyle(ChatFormatting.GRAY)) }
        Tooltips.fits(add, Tooltips.gunsForMagazine(contents.magazine))
        Tooltips.hint(add, "item.flansmod.magazine.how")
    }

    override fun isBarVisible(stack: ItemStack) = stack.loadedMagazine?.definition != null
    override fun getBarWidth(stack: ItemStack) = stack.loadedMagazine?.let { 13 * it.rounds / it.capacity.coerceAtLeast(1) } ?: 0
    override fun getBarColor(stack: ItemStack) = 0xE0B040

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        val contents = stack.loadedMagazine ?: return InteractionResult.FAIL
        if (level.isClientSide) return InteractionResult.SUCCESS
        // Magazines stack: work on one of them.
        val changed = AmmoLoading.editOne(player, stack) { one -> if (player.isShiftKeyDown) unload(player, one, contents) else fill(player, one, contents) }
        if (changed) level.playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_CHAIN.value(), SoundSource.PLAYERS, 0.7f, 1.4f)
        return if (changed) InteractionResult.SUCCESS else InteractionResult.FAIL
    }

    companion object {
        fun stackFor(id: Identifier, full: Boolean = false, ammo: Identifier? = null): ItemStack = ItemStack(FlansItems.MAGAZINE).apply {
            val contents = if (full) MagazineContents.full(id, ammo) else MagazineContents(id, null, 0)
            set(FlansComponents.MAGAZINE, contents ?: MagazineContents(id, null, 0))
        }

        fun stackFor(contents: MagazineContents): ItemStack = stackFor(contents.magazine).apply { set(FlansComponents.MAGAZINE, contents) }

        /** Loads rounds of the magazine's caliber from the inventory; keeps the ammo type already inside. */
        fun fill(player: Player, stack: ItemStack, contents: MagazineContents): Boolean {
            val filled = AmmoLoading.fill(player, contents)
            if (filled == contents) return false
            stack.loadedMagazine = filled
            return true
        }

        /** Returns all rounds to the inventory as ammo items. */
        fun unload(player: Player, stack: ItemStack, contents: MagazineContents): Boolean {
            if (contents.ammo == null || contents.rounds <= 0) return false
            stack.loadedMagazine = AmmoLoading.empty(player, contents)
            return true
        }
    }
}
