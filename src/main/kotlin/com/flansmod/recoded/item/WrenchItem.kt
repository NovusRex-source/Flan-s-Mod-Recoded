package com.flansmod.recoded.item

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.PartRole
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import java.util.function.Consumer

/**
 * Field repairs: right click a vehicle part (the one you look at - wheel, track, engine, gun mount or the hull) to
 * mend [REPAIR] health of it, restoring broken parts. Costs durability, not materials; big repairs still need the
 * vehicle's repair item.
 */
class WrenchItem(properties: Properties) : Item(properties) {
    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, add: Consumer<Component>, flag: TooltipFlag) {
        Tooltips.category(add, "tool")
        Tooltips.hint(add, "tooltip.flansmod.wrench.hint")
    }

    companion object {
        const val REPAIR = 15f
        const val COOLDOWN = 8

        /** Repairs the part of [vehicle] that [player] looks at. */
        fun repair(player: Player, hand: InteractionHand, vehicle: DriveableEntity): Boolean {
            val def = vehicle.definition ?: return false
            val stack = player.getItemInHand(hand)
            if (player.cooldowns.isOnCooldown(stack)) return false
            val eye = player.eyePosition
            val looked = vehicle.raycastParts(eye, eye.add(player.lookAngle.scale(player.entityInteractionRange() + 2)))?.first
            // Hull parts share the hull's health; look at nothing damaged → mend the most damaged part instead.
            fun key(part: String) = if (def.parts[part]?.role == PartRole.HULL || part == DriveableEntity.HULL) DriveableEntity.HULL else part
            val target = looked?.let(::key)?.takeIf { (vehicle.damage[it] ?: 0f) > 0f }
                ?: vehicle.damage.maxByOrNull { it.value }?.key
            if (target == null) {
                player.sendOverlayMessage(Component.translatable("message.flansmod.wrench.intact"))
                return false
            }
            if (!vehicle.level().isClientSide()) {
                vehicle.damage = vehicle.damage + (target to (vehicle.damage[target] ?: 0f) - REPAIR)
                stack.hurtAndBreak(1, player, if (hand == InteractionHand.MAIN_HAND) EquipmentSlot.MAINHAND else EquipmentSlot.OFFHAND)
                vehicle.playSound(SoundEvents.ANVIL_USE, 0.4f, 1.6f)
                val max = if (target == DriveableEntity.HULL) def.health else def.parts[target]?.health ?: 0f
                val now = max - (vehicle.damage[target] ?: 0f)
                player.sendOverlayMessage(Component.translatable("message.flansmod.wrench.repaired",
                    Component.translatableWithFallback("vehicle_part.flansmod.$target", target.replace('_', ' ')), now.toInt(), max.toInt()).withStyle(ChatFormatting.GREEN))
            }
            player.cooldowns.addCooldown(stack, COOLDOWN)
            return true
        }
    }
}
