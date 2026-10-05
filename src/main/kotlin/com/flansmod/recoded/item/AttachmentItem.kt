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
        Tooltips.category(add, "attachment", Component.translatableWithFallback("attachment.flansmod.${def.slot}", def.slot))
        fun percent(key: String, factor: Double, goodWhenLower: Boolean = false) {
            if (factor == 1.0) return
            val good = if (goodWhenLower) factor < 1 else factor > 1
            add.accept(Component.translatable("tooltip.flansmod.attachment.$key", "%+d%%".format(((factor - 1) * 100).toInt()))
                .withStyle(if (good) ChatFormatting.GREEN else ChatFormatting.RED))
        }
        percent("damage", def.damageMultiplier.toDouble())
        percent("spread", def.spreadMultiplier.toDouble(), goodWhenLower = true)
        percent("recoil", def.recoilMultiplier.toDouble(), goodWhenLower = true)
        percent("move", def.adsMoveSpeedMultiplier.toDouble())
        def.adsZoom?.let { add.accept(Component.translatable("tooltip.flansmod.attachment.zoom", "%.1f".format(it)).withStyle(ChatFormatting.GRAY)) }
        if (def.hideTracer) add.accept(Component.translatable("tooltip.flansmod.attachment.suppressed").withStyle(ChatFormatting.GRAY))
        def.scope?.let { if (it.nightVision) add.accept(Component.translatable("tooltip.flansmod.attachment.night_vision").withStyle(ChatFormatting.GREEN)) }
        def.scope?.let { if (it.thermal) add.accept(Component.translatable("tooltip.flansmod.attachment.thermal").withStyle(ChatFormatting.GREEN)) }
        add.accept(Component.translatable("item.flansmod.attachment.how").withStyle(ChatFormatting.DARK_GRAY))
    }

    companion object {
        fun stackFor(id: Identifier): ItemStack = ItemStack(FlansItems.ATTACHMENT).apply {
            set(FlansComponents.ATTACHMENT, id)
        }
    }
}

val ItemStack.attachmentId: Identifier? get() = get(FlansComponents.ATTACHMENT)
val ItemStack.attachmentDefinition: AttachmentDefinition? get() = if (item is AttachmentItem) Attachments[attachmentId] else null
