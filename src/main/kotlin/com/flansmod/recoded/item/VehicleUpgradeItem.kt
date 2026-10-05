package com.flansmod.recoded.item

import com.flansmod.recoded.gun.VehicleUpgradeDefinition
import com.flansmod.recoded.gun.VehicleUpgrades
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansItems
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import java.util.function.Consumer

/** The single item behind every vehicle upgrade ([FlansComponents.VEHICLE_UPGRADE]); right click a vehicle to install it. */
class VehicleUpgradeItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component =
        stack.vehicleUpgradeDefinition?.let { Component.translatableWithFallback("vehicle_upgrade.${stack.vehicleUpgradeId!!.toLanguageKey()}", it.name) }
            ?: super.getName(stack)

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, add: Consumer<Component>, flag: TooltipFlag) {
        val def = stack.vehicleUpgradeDefinition ?: return
        Tooltips.category(add, "vehicle_upgrade")
        fun line(text: Component) = add.accept(text.copy().withStyle(ChatFormatting.GRAY))
        line(Component.translatable("item.flansmod.vehicle_upgrade.slot", Component.translatableWithFallback("vehicle_upgrade.flansmod.slot.${def.slot}", def.slot)))
        fun percent(key: String, factor: Double) {
            if (factor != 1.0) line(Component.translatable("item.flansmod.vehicle_upgrade.$key", "%+d%%".format(((factor - 1) * 100).toInt())))
        }
        percent("speed", def.speedMultiplier)
        percent("acceleration", def.accelerationMultiplier)
        percent("turn", def.turnMultiplier.toDouble())
        percent("health", def.healthMultiplier.toDouble())
        percent("fuel_capacity", def.fuelCapacityMultiplier.toDouble())
        percent("fuel_consumption", def.fuelConsumptionMultiplier.toDouble())
        if (def.armorBonus != 0f) line(Component.translatable("item.flansmod.vehicle_upgrade.armor", "%+d%%".format((def.armorBonus * 100).toInt())))
        line(Component.translatable("item.flansmod.vehicle_upgrade.how"))
    }

    companion object {
        fun stackFor(id: Identifier): ItemStack = ItemStack(FlansItems.VEHICLE_UPGRADE).apply { set(FlansComponents.VEHICLE_UPGRADE, id) }
    }
}

val ItemStack.vehicleUpgradeId: Identifier? get() = get(FlansComponents.VEHICLE_UPGRADE)
val ItemStack.vehicleUpgradeDefinition: VehicleUpgradeDefinition? get() = if (item is VehicleUpgradeItem) VehicleUpgrades[vehicleUpgradeId] else null
