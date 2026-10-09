package com.flansmod.recoded.gamemode

import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Border marker post: placed near a Battle Master (the placer's own, else the nearest within 128 blocks) it becomes a
 * corner of that battle's area. With two or more markers, fighters cannot leave the box around them (any height).
 */
class BattleBorderBlock(properties: Properties) : Block(properties) {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = POST

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        if (level !is ServerLevel) return
        val master = BattleMasterBlockEntity.near(level, pos, placer as? ServerPlayer)
        if (master == null) {
            (placer as? ServerPlayer)?.sendOverlayMessage(Component.translatable("message.flansmod.border.no_master").withStyle(ChatFormatting.RED))
            return
        }
        master.addBorder(pos)
        (placer as? ServerPlayer)?.sendOverlayMessage(Component.translatable("message.flansmod.border.linked", master.settings.name, master.state.border.size))
    }

    override fun affectNeighborsAfterRemoval(state: BlockState, level: ServerLevel, pos: BlockPos, movedByPiston: Boolean) {
        BattleMasterBlockEntity.loaded(level).filter { m -> m.state.border.any { it == listOf(pos.x, pos.y, pos.z) } }.forEach { it.removeBorder(pos) }
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston)
    }

    companion object {
        private val POST: VoxelShape = box(6.0, 0.0, 6.0, 10.0, 16.0, 10.0)
    }
}
