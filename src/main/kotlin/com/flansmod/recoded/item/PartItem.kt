package com.flansmod.recoded.item

import com.flansmod.recoded.gun.PartDefinition
import com.flansmod.recoded.gun.Parts
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansItems
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/** The single item behind every gun part; which one is stored in [FlansComponents.PART]. */
class PartItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component =
        stack.partDefinition?.let { Component.translatableWithFallback("part.${stack.partId!!.toLanguageKey()}", it.name) }
            ?: super.getName(stack)

    companion object {
        fun stackFor(id: Identifier, count: Int = 1): ItemStack = ItemStack(FlansItems.PART, count).apply {
            set(FlansComponents.PART, id)
            Parts[id]?.icon?.let { set(DataComponents.ITEM_MODEL, it) }
        }
    }
}

val ItemStack.partId: Identifier? get() = get(FlansComponents.PART)
val ItemStack.partDefinition: PartDefinition? get() = if (item is PartItem) Parts[partId] else null
