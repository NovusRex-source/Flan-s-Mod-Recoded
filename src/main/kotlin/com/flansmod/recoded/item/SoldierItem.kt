package com.flansmod.recoded.item

import com.flansmod.recoded.gamemode.SoldierAttitude
import com.flansmod.recoded.gamemode.SoldierEntity
import com.flansmod.recoded.gamemode.SoldierSpawn
import com.flansmod.recoded.gun.Factions
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansEntities
import com.flansmod.recoded.registry.FlansItems
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.CustomModelData
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.item.context.UseOnContext
import java.util.function.Consumer

/**
 * Soldier spawn item ([FlansComponents.SOLDIER]: faction and attitude), one per faction and attitude in the creative
 * tabs: right click a block to place a soldier there - in the faction's uniform with one of its guns, behaving as
 * friendly, enemy, neutral or inactive ([SoldierAttitude]). It stays within [HOME_RADIUS] blocks of that spot.
 * The icon is tinted with the faction colour and an attitude badge (custom model data colours).
 */
class SoldierItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component {
        val spawn = stack.get(FlansComponents.SOLDIER) ?: return super.getName(stack)
        val faction = spawn.faction?.let { Factions[it]?.name ?: it.toString() }
        return if (faction == null) Component.translatable("item.flansmod.soldier.any") else Component.translatable("item.flansmod.soldier.of", faction)
    }

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level as? ServerLevel ?: return InteractionResult.SUCCESS
        val spawn = context.itemInHand.get(FlansComponents.SOLDIER) ?: return InteractionResult.FAIL
        val pos = context.clickedPos.relative(context.clickedFace)
        spawn(level, pos, spawn.faction, spawn.attitude, context.horizontalDirection.opposite.toYRot()) ?: return InteractionResult.FAIL
        if (context.player?.hasInfiniteMaterials() != true) context.itemInHand.shrink(1)
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, add: Consumer<Component>, flag: TooltipFlag) {
        val spawn = stack.get(FlansComponents.SOLDIER) ?: return
        Tooltips.category(add, "soldier", Component.translatable("soldier.flansmod.attitude.${spawn.attitude.key}").withColor(spawn.attitude.rgb))
        add.accept(Component.translatable("soldier.flansmod.attitude.${spawn.attitude.key}.hint").withStyle(ChatFormatting.GRAY))
        add.accept(Component.translatable("item.flansmod.soldier.how").withStyle(ChatFormatting.DARK_GRAY))
    }

    companion object {
        const val HOME_RADIUS = 16

        fun stackFor(faction: Identifier?, attitude: SoldierAttitude): ItemStack = ItemStack(FlansItems.SOLDIER).apply {
            set(FlansComponents.SOLDIER, SoldierSpawn(faction, attitude))
            val body = faction?.let { Factions[it]?.rgb } ?: 0x8A8A70
            set(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(emptyList(), emptyList(), emptyList(), listOf(body, attitude.rgb)))
        }

        /** Places a soldier of [faction] with [attitude] at [pos] (facing [yaw]), equipped and kept near there. */
        fun spawn(level: ServerLevel, pos: BlockPos, faction: Identifier?, attitude: SoldierAttitude, yaw: Float = 0f): SoldierEntity? {
            val soldier = FlansEntities.SOLDIER.create(level, EntitySpawnReason.SPAWN_ITEM_USE) ?: return null
            soldier.snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, yaw, 0f)
            soldier.yHeadRot = yaw
            soldier.faction = faction
            soldier.attitude = attitude
            soldier.equip(faction, null)
            soldier.setHomeTo(pos, HOME_RADIUS)
            level.addFreshEntity(soldier)
            return soldier
        }
    }
}
