package com.flansmod.recoded.item

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.Clothing
import com.flansmod.recoded.gun.ClothingDefinition
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansItems
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.EquipmentSlotGroup
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemAttributeModifiers
import net.minecraft.world.item.equipment.EquipmentAssets
import net.minecraft.world.item.equipment.Equippable

/**
 * The single item behind all clothing. Vanilla wears and renders it through the `equippable` and
 * `attribute_modifiers` components, which [applyDefinition] derives from the clothing definition. Stacks made
 * by recipes or commands only carry the clothing id; they get the components on creation or first inventory tick.
 */
class ClothingItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component =
        stack.clothingDefinition?.let { Component.translatableWithFallback("clothing.${stack.clothingId!!.toLanguageKey()}", it.name) }
            ?: super.getName(stack)

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: net.minecraft.world.item.component.TooltipDisplay,
                                 add: java.util.function.Consumer<Component>, flag: net.minecraft.world.item.TooltipFlag) {
        Tooltips.faction(add, stack.clothingDefinition?.faction)
        stack.clothingDefinition?.plateSlots?.takeIf { it > 0 }?.let { slots ->
            val used = com.flansmod.recoded.gear.GearSlots.plates(stack, slots).count { !it.isEmpty }
            add.accept(Component.translatable("tooltip.flansmod.clothing.plates", used, slots).withStyle(net.minecraft.ChatFormatting.GRAY))
        }
    }

    override fun inventoryTick(stack: ItemStack, level: ServerLevel, owner: Entity, slot: EquipmentSlot?) {
        if (!stack.has(DataComponents.EQUIPPABLE)) applyDefinition(stack)
    }

    companion object {
        private const val NIGHT_VISION_TICKS = 260

        fun stackFor(id: Identifier): ItemStack = ItemStack(FlansItems.CLOTHING).apply {
            set(FlansComponents.CLOTHING, id)
            applyDefinition(this)
        }

        /** Sets equippable + attribute modifiers from the definition (no-op for unknown clothing). */
        fun applyDefinition(stack: ItemStack) {
            val def = stack.clothingDefinition ?: return
            val slot = EquipmentSlot.byName(def.slot)
            stack.set(DataComponents.EQUIPPABLE, Equippable.builder(slot)
                .setAsset(ResourceKey.create(EquipmentAssets.ROOT_ID, def.asset))
                .setEquipSound(if (def.armor >= 5) SoundEvents.ARMOR_EQUIP_IRON else SoundEvents.ARMOR_EQUIP_LEATHER)
                .build())
            val group = EquipmentSlotGroup.bySlot(slot)
            val modifierId = FlansMod.id("clothing.${def.slot}")
            // Inserted armour plates (modern armour) add their protection to the clothing's own.
            val plates = if (def.plateSlots > 0) com.flansmod.recoded.gear.GearSlots.plates(stack, def.plateSlots).mapNotNull { com.flansmod.recoded.gun.Gear[it.get(FlansComponents.GEAR)] } else emptyList()
            val armor = def.armor + plates.sumOf { it.armor }
            val toughness = def.toughness + plates.sumOf { it.toughness }
            val speed = def.speedModifier + plates.sumOf { it.speedModifier }
            stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder().apply {
                if (armor != 0.0) add(Attributes.ARMOR, AttributeModifier(modifierId, armor, AttributeModifier.Operation.ADD_VALUE), group)
                if (toughness != 0.0) add(Attributes.ARMOR_TOUGHNESS, AttributeModifier(modifierId, toughness, AttributeModifier.Operation.ADD_VALUE), group)
                if (def.knockbackResistance != 0.0) add(Attributes.KNOCKBACK_RESISTANCE, AttributeModifier(modifierId, def.knockbackResistance, AttributeModifier.Operation.ADD_VALUE), group)
                if (speed != 0.0) add(Attributes.MOVEMENT_SPEED, AttributeModifier(modifierId, speed, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL), group)
            }.build())
        }

        /** Night-vision goggles: hidden ambient effect while the clothing is worn. */
        fun init() {
            ServerTickEvents.END_SERVER_TICK.register { server ->
                if (server.tickCount % 10 != 0) return@register
                for (player in server.playerList.players) {
                    val wearsGoggles = EquipmentSlot.entries.any { it.isArmor && player.getItemBySlot(it).clothingDefinition?.nightVision == true }
                    if (wearsGoggles) player.addEffect(MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_TICKS, 0, true, false, false))
                }
            }
        }
    }
}

val ItemStack.clothingId: Identifier? get() = get(FlansComponents.CLOTHING)
val ItemStack.clothingDefinition: ClothingDefinition? get() = if (item is ClothingItem) Clothing[clothingId] else null
