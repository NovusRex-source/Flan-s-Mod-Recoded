package com.flansmod.recoded.item

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.VehicleDefinition
import com.flansmod.recoded.gun.Vehicles
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansItems
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.HitResult
import java.util.function.Consumer

/** The single item behind every vehicle ([FlansComponents.VEHICLE]); right click on a block places it, like a boat. */
class VehicleItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component =
        stack.vehicleDefinition?.let { Component.translatableWithFallback("vehicle.${stack.vehicleId!!.toLanguageKey()}", it.name) }
            ?: super.getName(stack)

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, add: Consumer<Component>, flag: TooltipFlag) {
        val def = stack.vehicleDefinition ?: return
        fun line(key: String, vararg args: Any) = add.accept(Component.translatable("item.flansmod.vehicle.$key", *args).withStyle(ChatFormatting.GRAY))
        line("seats", def.seats.size)
        line("health", def.health.toInt())
        if (def.needsFuel) line("fuel", stack.getOrDefault(FlansComponents.FUEL, 0) * 100 / def.fuel.capacity)
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        val id = stack.vehicleId ?: return InteractionResult.FAIL
        val def = Vehicles[id] ?: return InteractionResult.FAIL
        val hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE)
        if (hit.type != HitResult.Type.BLOCK) return InteractionResult.PASS

        val vehicle = DriveableEntity(level, id, hit.location, player.yRot).apply { fuel = stack.getOrDefault(FlansComponents.FUEL, 0) }
        if (!level.noCollision(vehicle, vehicle.boundingBox.deflate(0.05))) return InteractionResult.FAIL
        if (!level.isClientSide()) {
            level.addFreshEntity(vehicle)
            level.gameEvent(player, GameEvent.ENTITY_PLACE, hit.location)
            stack.consume(1, player)
        }
        return InteractionResult.SUCCESS
    }

    companion object {
        fun stackFor(id: Identifier?, fuel: Int = 0): ItemStack = ItemStack(FlansItems.VEHICLE).apply {
            id?.let { set(FlansComponents.VEHICLE, it) }
            if (fuel > 0) set(FlansComponents.FUEL, fuel)
        }
    }
}

val ItemStack.vehicleId: Identifier? get() = get(FlansComponents.VEHICLE)
val ItemStack.vehicleDefinition: VehicleDefinition? get() = if (item is VehicleItem) Vehicles[vehicleId] else null
