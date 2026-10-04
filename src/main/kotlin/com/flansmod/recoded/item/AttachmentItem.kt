package com.flansmod.recoded.item

import com.flansmod.recoded.gun.AttachmentDefinition
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansItems
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import java.util.function.Consumer

/** The single item behind every attachment; which one is stored in [FlansComponents.ATTACHMENT]. */
class AttachmentItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component =
        stack.attachmentDefinition?.let { Component.translatableWithFallback("attachment.${stack.attachmentId!!.toLanguageKey()}", it.name) }
            ?: super.getName(stack)

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, add: Consumer<Component>, flag: TooltipFlag) {
        val def = stack.attachmentDefinition ?: return
        add.accept(Component.translatable("item.flansmod.attachment.slot", Component.translatableWithFallback("attachment.flansmod.${def.slot}", def.slot)).withStyle(ChatFormatting.GRAY))
        add.accept(Component.translatable("item.flansmod.attachment.how").withStyle(ChatFormatting.DARK_GRAY))
    }

    companion object {
        fun stackFor(id: Identifier): ItemStack = ItemStack(FlansItems.ATTACHMENT).apply {
            set(FlansComponents.ATTACHMENT, id)
            Attachments[id]?.icon?.let { set(DataComponents.ITEM_MODEL, it) }
        }
    }
}

val ItemStack.attachmentId: Identifier? get() = get(FlansComponents.ATTACHMENT)
val ItemStack.attachmentDefinition: AttachmentDefinition? get() = if (item is AttachmentItem) Attachments[attachmentId] else null
