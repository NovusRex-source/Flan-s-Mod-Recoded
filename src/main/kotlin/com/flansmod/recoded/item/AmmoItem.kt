package com.flansmod.recoded.item

import com.flansmod.recoded.gun.AmmoDefinition
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansItems
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/** The single item behind every ammo type; which one is stored in [FlansComponents.AMMO_TYPE]. */
class AmmoItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component =
        stack.ammoDefinition?.let { Component.translatableWithFallback("ammo.${stack.ammoTypeId!!.toLanguageKey()}", it.name) }
            ?: super.getName(stack)

    companion object {
        fun stackFor(id: Identifier, count: Int = 1): ItemStack = ItemStack(FlansItems.AMMO, count).apply {
            set(FlansComponents.AMMO_TYPE, id)
            AmmoTypes[id]?.let { def ->
                // Only non-default values, so crafted stacks (recipes set the same components) stack with these.
                if (def.maxStack != 64) set(DataComponents.MAX_STACK_SIZE, def.maxStack.coerceIn(1, 99))
            }
        }

        /** Display name of an ammo reference: an ammo definition or a plain item. */
        fun displayName(id: Identifier): Component = AmmoTypes[id]
            ?.let { Component.translatableWithFallback("ammo.${id.toLanguageKey()}", it.name) }
            ?: Component.translatable(BuiltInRegistries.ITEM.getValue(id).descriptionId)
    }
}

val ItemStack.ammoTypeId: Identifier? get() = get(FlansComponents.AMMO_TYPE)
val ItemStack.ammoDefinition: AmmoDefinition? get() = if (item is AmmoItem) AmmoTypes[ammoTypeId] else null
