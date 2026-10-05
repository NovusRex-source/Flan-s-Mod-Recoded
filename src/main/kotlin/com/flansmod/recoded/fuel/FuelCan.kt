package com.flansmod.recoded.fuel

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.FuelTypes
import com.flansmod.recoded.item.Tooltips
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansItems
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.item.component.TooltipDisplay
import java.util.function.Consumer

/** Fuel in a can, petrol station or synthesizer: [amount] units of one fuel [type]. */
data class FuelStack(val type: String, val amount: Int) {
    companion object {
        val CODEC: Codec<FuelStack> = RecordCodecBuilder.create { i ->
            i.group(Codec.STRING.fieldOf("type").forGetter(FuelStack::type), Codec.INT.fieldOf("amount").forGetter(FuelStack::amount))
                .apply(i, ::FuelStack)
        }
        val STREAM_CODEC: StreamCodec<ByteBuf, FuelStack> =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, FuelStack::type, ByteBufCodecs.VAR_INT, FuelStack::amount, ::FuelStack)

        fun name(type: String): Component = Component.translatableWithFallback("fuel.flansmod.$type", type.replaceFirstChar(Char::uppercase))
        fun colour(type: String): Int = when (type) {
            FuelTypes.PETROL -> 0xC03020
            FuelTypes.DIESEL -> 0xC8A030
            else -> 0x5080C0
        }
    }
}

/**
 * Jerry can: holds [CAPACITY] units of one fuel. Right click a vehicle to pour it into the tank (only the vehicle's own
 * fuel type), fill it at a petrol station or fuel synthesizer. Its look follows the fuel type (custom model data
 * string `petrol`/`diesel`/`empty`, selected by the item model).
 */
class FuelCanItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component = stack.fuel?.let { Component.translatable("item.flansmod.fuel_can.filled", FuelStack.name(it.type)) }
        ?: Component.translatable("item.flansmod.fuel_can.empty")

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, add: Consumer<Component>, flag: TooltipFlag) {
        Tooltips.category(add, "fuel_can")
        val fuel = stack.fuel
        add.accept(Component.translatable("tooltip.flansmod.fuel_can.contents", FuelTypes.litres(fuel?.amount ?: 0), FuelTypes.litres(CAPACITY))
            .withStyle(ChatFormatting.GRAY))
        Tooltips.hint(add, "tooltip.flansmod.fuel_can.hint")
    }

    override fun isBarVisible(stack: ItemStack) = stack.fuel != null
    override fun getBarWidth(stack: ItemStack) = ((stack.fuel?.amount ?: 0) * 13 / CAPACITY).coerceIn(0, 13)
    override fun getBarColor(stack: ItemStack) = stack.fuel?.let { FuelStack.colour(it.type) } ?: 0

    companion object {
        /** 40 litres. */
        const val CAPACITY = 8000

        fun stackFor(fuel: FuelStack?): ItemStack = ItemStack(FlansItems.FUEL_CAN).apply { this.fuel = fuel }

        /** Pours the can held in [stack] into [vehicle]; false (with a message) if the fuel does not fit. */
        fun fillVehicle(player: Player, stack: ItemStack, vehicle: DriveableEntity): Boolean {
            val def = vehicle.definition?.takeIf { it.needsFuel } ?: return false
            val can = stack.fuel
            when {
                can == null -> player.sendOverlayMessage(Component.translatable("message.flansmod.fuel_can.empty"))
                can.type != def.fuel.type -> player.sendOverlayMessage(Component.translatable("message.flansmod.fuel.wrong_type", FuelStack.name(def.fuel.type)))
                vehicle.fuel >= def.fuel.capacity -> player.sendOverlayMessage(Component.translatable("message.flansmod.fuel.full"))
                else -> {
                    if (!vehicle.level().isClientSide()) {
                        val poured = minOf(can.amount, def.fuel.capacity - vehicle.fuel)
                        vehicle.fuel += poured
                        if (!player.hasInfiniteMaterials()) stack.fuel = can.copy(amount = can.amount - poured)
                        vehicle.playSound(SoundEvents.BUCKET_EMPTY, 0.7f, 0.8f)
                    }
                    return true
                }
            }
            return false
        }
    }
}

/** The can's contents; setting null or an empty stack empties it (and updates its look). */
var ItemStack.fuel: FuelStack?
    get() = get(FlansComponents.FUEL_CAN)?.takeIf { it.amount > 0 }
    set(value) {
        val contents = value?.takeIf { it.amount > 0 }
        if (contents == null) remove(FlansComponents.FUEL_CAN) else set(FlansComponents.FUEL_CAN, contents)
        set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(listOf(), listOf(), listOf(contents?.type ?: "empty"), listOf()))
    }

/**
 * Moves fuel between a can and a tank ([tank] = current tank contents or null, [capacity] its size).
 * [intoTank]: empty the can into the tank, else fill the can from the tank. Returns the new tank contents, or the old
 * ones if nothing moved (different fuel types never mix).
 */
fun ItemStack.exchangeFuel(tank: FuelStack?, capacity: Int, intoTank: Boolean): FuelStack? {
    if (item !is FuelCanItem) return tank
    val can = fuel
    if (intoTank) {
        if (can == null || (tank != null && tank.type != can.type)) return tank
        val moved = minOf(can.amount, capacity - (tank?.amount ?: 0))
        if (moved <= 0) return tank
        fuel = can.copy(amount = can.amount - moved)
        return FuelStack(can.type, (tank?.amount ?: 0) + moved)
    }
    if (tank == null || (can != null && can.type != tank.type)) return tank
    val moved = minOf(tank.amount, FuelCanItem.CAPACITY - (can?.amount ?: 0))
    if (moved <= 0) return tank
    fuel = FuelStack(tank.type, (can?.amount ?: 0) + moved)
    return tank.copy(amount = tank.amount - moved).takeIf { it.amount > 0 }
}
