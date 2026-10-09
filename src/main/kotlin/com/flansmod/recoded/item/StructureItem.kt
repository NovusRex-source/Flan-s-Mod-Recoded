package com.flansmod.recoded.item

import com.flansmod.recoded.gun.StructureDefinition
import com.flansmod.recoded.gun.Structures
import com.flansmod.recoded.registry.FlansComponents
import com.flansmod.recoded.registry.FlansItems
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import java.util.function.Consumer

/**
 * Structure kit ([FlansComponents.STRUCTURE] names a [StructureDefinition]): right click the ground to build the
 * structure in front of you, its front facing the way you look. Used up outside creative mode.
 */
class StructureItem(properties: Properties) : Item(properties) {
    override fun getName(stack: ItemStack): Component =
        stack.structureDefinition?.let { Component.translatableWithFallback("structure.${stack.structureId!!.toLanguageKey()}", it.name) } ?: super.getName(stack)

    override fun useOn(context: UseOnContext): InteractionResult {
        val level = context.level as? ServerLevel ?: return InteractionResult.SUCCESS
        val stack = context.itemInHand
        val id = stack.structureId ?: return InteractionResult.FAIL
        val def = Structures[id] ?: return InteractionResult.FAIL
        val facing = context.horizontalDirection
        if (!place(level, id, def, context.clickedPos, facing)) {
            context.player?.sendOverlayMessage(Component.translatable("message.flansmod.structure.missing", (def.template ?: id).toString()).withStyle(ChatFormatting.RED))
            return InteractionResult.FAIL
        }
        level.playSound(null, context.clickedPos, SoundEvents.ANVIL_PLACE, SoundSource.BLOCKS, 0.6f, 0.8f)
        if (context.player?.hasInfiniteMaterials() != true) stack.shrink(1)
        return InteractionResult.SUCCESS
    }

    override fun appendHoverText(stack: ItemStack, context: TooltipContext, display: TooltipDisplay, add: Consumer<Component>, flag: TooltipFlag) {
        val def = stack.structureDefinition ?: return
        Tooltips.category(add, "structure", Component.translatableWithFallback("structure.flansmod.category.${def.category}", def.category))
        if (def.size.size == 3) add.accept(Component.translatable("item.flansmod.structure.size", def.size[0], def.size[1], def.size[2]).withStyle(ChatFormatting.GRAY))
        add.accept(Component.translatable("item.flansmod.structure.how").withStyle(ChatFormatting.GRAY))
    }

    companion object {
        fun stackFor(id: Identifier): ItemStack = ItemStack(FlansItems.STRUCTURE).apply { set(FlansComponents.STRUCTURE, id) }

        /**
         * Builds [def] with its near edge on the block in front of [clicked] (one up, plus [StructureDefinition.yOffset]),
         * centred on it and turned so the template's north side faces [facing]. Structure voids keep what is there.
         * Returns false when the template does not exist.
         */
        fun place(level: ServerLevel, id: Identifier, def: StructureDefinition, clicked: BlockPos, facing: Direction): Boolean {
            val template = level.server.structureTemplateManager.get(def.template ?: id).orElse(null) ?: return false
            val size = template.size
            val rotation = when (facing) {
                Direction.EAST -> Rotation.CLOCKWISE_90
                Direction.SOUTH -> Rotation.CLOCKWISE_180
                Direction.WEST -> Rotation.COUNTERCLOCKWISE_90
                else -> Rotation.NONE
            }
            val pivot = BlockPos(size.x / 2, 0, size.z / 2)
            // The template's centre goes half its depth ahead of the clicked block, so its back edge starts there.
            val centre = clicked.above(1 + def.yOffset).relative(facing, size.z / 2 + 1)
            val origin = centre.subtract(pivot)
            val settings = StructurePlaceSettings().setRotation(rotation).setRotationPivot(pivot).setIgnoreEntities(true)
                .addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK)
            return template.placeInWorld(level, origin, origin, settings, level.random, Block.UPDATE_ALL)
        }
    }
}

val ItemStack.structureId: Identifier? get() = get(FlansComponents.STRUCTURE)
val ItemStack.structureDefinition: StructureDefinition? get() = if (item is StructureItem) Structures[structureId] else null
