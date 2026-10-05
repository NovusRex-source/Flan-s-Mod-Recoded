package com.flansmod.recoded.item

import com.flansmod.recoded.entity.GrenadeEntity
import com.flansmod.recoded.gun.GrenadeDefinition
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansItems
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.ChatFormatting
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import java.util.function.Consumer

/** The single item behind every grenade; which one is stored in [FlansComponents.GRENADE]. Thrown with right click. */
class GrenadeItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component =
        stack.grenadeDefinition?.let { Component.translatableWithFallback("grenade.${stack.grenadeId!!.toLanguageKey()}", it.name) }
            ?: super.getName(stack)

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, add: Consumer<Component>, flag: TooltipFlag) {
        val def = stack.grenadeDefinition ?: return
        Tooltips.category(add, "explosive")
        Tooltips.faction(add, def.faction)
        fun effect(key: String, color: ChatFormatting, vararg args: Any) = add.accept(Component.translatable("tooltip.flansmod.grenade.$key", *args).withStyle(color))
        def.explosion?.let { effect(if (it.fire) "incendiary" else "explosion", ChatFormatting.RED, it.power) }
        def.smoke?.let { effect("smoke", ChatFormatting.GRAY) }
        def.flash?.let { effect("flash", ChatFormatting.YELLOW) }
        effect(if (def.contact) "contact" else "fuse", ChatFormatting.GRAY, "%.1f".format(def.fuseTicks / 20f))
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        val def = stack.grenadeDefinition?.takeIf { it.throwable } ?: return InteractionResult.FAIL
        level.playSound(null, player.x, player.y, player.z, SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.6f, 0.6f)
        if (level is ServerLevel) {
            Projectile.spawnProjectileFromRotation(::GrenadeEntity, level, stack, player, 0f, def.throwVelocity, 1f)
        }
        player.cooldowns.addCooldown(stack, def.cooldownTicks)
        stack.consume(1, player)
        return InteractionResult.SUCCESS
    }

    companion object {
        fun stackFor(id: Identifier, count: Int = 1): ItemStack = ItemStack(FlansItems.GRENADE, count).apply {
            set(FlansComponents.GRENADE, id)
            Grenades[id]?.let { def ->
                // Only non-default values, so crafted stacks (recipes set the same components) stack with these.
                if (def.maxStack != 16) set(DataComponents.MAX_STACK_SIZE, def.maxStack.coerceIn(1, 99))
            }
        }
    }
}

val ItemStack.grenadeId: Identifier? get() = get(FlansComponents.GRENADE)
val ItemStack.grenadeDefinition: GrenadeDefinition? get() = if (item is GrenadeItem) Grenades[grenadeId] else null
