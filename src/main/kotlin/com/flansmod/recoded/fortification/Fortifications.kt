package com.flansmod.recoded.fortification

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.VehicleType
import net.fabricmc.fabric.api.`object`.builder.v1.block.type.BlockSetTypeBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockSetType
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Field fortifications (mod blocks, built on vanilla block classes): sandbags (block, slab, stairs) that soak up
 * explosions, reinforced concrete bunker parts (block, slab, stairs, an embrasure you can shoot through, a steel door
 * and hatch), barbed wire and Czech hedgehogs that stop vehicles.
 */
object Fortifications {
    /** Hand-openable steel (iron sounds) for bunker doors and hatches. */
    private val STEEL: BlockSetType = BlockSetTypeBuilder.copyOf(BlockSetType.IRON).openableByHand(true).register(FlansMod.id("bunker_steel"))

    private fun props(key: ResourceKey<Block>) = BlockBehaviour.Properties.of().setId(key)
    private fun sandbag(p: BlockBehaviour.Properties) = p.mapColor(MapColor.SAND).strength(1.5f, 12f).sound(SoundType.WOOL)
    private fun concrete(p: BlockBehaviour.Properties) = p.mapColor(MapColor.STONE).strength(25f, 1200f).requiresCorrectToolForDrops()

    val SANDBAGS = block("sandbags") { Block(sandbag(it)) }
    val SANDBAG_SLAB = block("sandbag_slab") { SlabBlock(sandbag(it)) }
    val SANDBAG_STAIRS = block("sandbag_stairs") { object : StairBlock(SANDBAGS.defaultBlockState(), sandbag(it)) {} }
    val REINFORCED_CONCRETE = block("reinforced_concrete") { Block(concrete(it)) }
    val REINFORCED_CONCRETE_SLAB = block("reinforced_concrete_slab") { SlabBlock(concrete(it)) }
    val REINFORCED_CONCRETE_STAIRS = block("reinforced_concrete_stairs") { object : StairBlock(REINFORCED_CONCRETE.defaultBlockState(), concrete(it)) {} }
    val BUNKER_EMBRASURE = block("bunker_embrasure") { EmbrasureBlock(concrete(it).noOcclusion()) }
    val BUNKER_DOOR = block("bunker_door") { object : DoorBlock(STEEL, it.mapColor(MapColor.METAL).strength(20f, 1200f).noOcclusion().requiresCorrectToolForDrops()) {} }
    val BUNKER_HATCH = block("bunker_hatch") { object : TrapDoorBlock(STEEL, it.mapColor(MapColor.METAL).strength(20f, 1200f).noOcclusion().requiresCorrectToolForDrops()) {} }
    val BARBED_WIRE = block("barbed_wire") { BarbedWireBlock(it.mapColor(MapColor.METAL).noCollision().strength(2f).sound(SoundType.CHAIN).noOcclusion()) }
    val CZECH_HEDGEHOG = block("czech_hedgehog") { HedgehogBlock(it.mapColor(MapColor.METAL).strength(10f, 600f).sound(SoundType.METAL).noOcclusion().requiresCorrectToolForDrops()) }

    val ALL: List<Block> get() = listOf(SANDBAGS, SANDBAG_SLAB, SANDBAG_STAIRS, REINFORCED_CONCRETE, REINFORCED_CONCRETE_SLAB, REINFORCED_CONCRETE_STAIRS,
        BUNKER_EMBRASURE, BUNKER_DOOR, BUNKER_HATCH, BARBED_WIRE, CZECH_HEDGEHOG)

    private fun <B : Block> block(name: String, create: (BlockBehaviour.Properties) -> B): B {
        val key = ResourceKey.create(Registries.BLOCK, FlansMod.id(name))
        val block = net.minecraft.core.Registry.register(BuiltInRegistries.BLOCK, key, create(props(key)))
        val itemKey = ResourceKey.create(Registries.ITEM, FlansMod.id(name))
        val item = if (block is DoorBlock) net.minecraft.world.item.DoubleHighBlockItem(block, Item.Properties().setId(itemKey).useBlockDescriptionPrefix())
        else BlockItem(block, Item.Properties().setId(itemKey).useBlockDescriptionPrefix())
        net.minecraft.core.Registry.register(BuiltInRegistries.ITEM, itemKey, item)
        return block
    }

    fun init() = Unit
}

/** Concrete wall with a firing slit at eye height: bullets, arrows and view pass through the slit. */
class EmbrasureBlock(properties: Properties) : HorizontalDirectionalBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState = defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    companion object {
        /** Solid below 10 px and above 13 px: a 3 px slit across the whole block. */
        val SHAPE: VoxelShape = Shapes.or(Block.box(0.0, 0.0, 0.0, 16.0, 10.0, 16.0), Block.box(0.0, 13.0, 0.0, 16.0, 16.0, 16.0))
    }
}

/**
 * Coils of barbed wire: entangle and cut whoever walks into them (cobweb-like slowdown plus damage), slow wheeled
 * vehicles, and are flattened by tracked ones.
 */
class BarbedWireBlock(properties: Properties) : Block(properties) {
    override fun entityInside(state: BlockState, level: Level, pos: BlockPos, entity: Entity, effects: InsideBlockEffectApplier, pastEdges: Boolean) {
        if (entity is DriveableEntity) {
            if (entity.definition?.type == VehicleType.TANK) {
                if (level is ServerLevel) level.destroyBlock(pos, false, entity)
            } else entity.makeStuckInBlock(state, Vec3(0.5, 1.0, 0.5))
            return
        }
        entity.makeStuckInBlock(state, Vec3(0.3, 0.4, 0.3))
        if (level is ServerLevel && entity is LivingEntity && entity.tickCount % 10 == 0) {
            entity.hurtServer(level, level.damageSources().cactus(), 1.5f)
        }
    }
}

/** Czech hedgehog: crossed steel beams that block vehicles (full collision) but not sight or bullets above 1 block. */
class HedgehogBlock(properties: Properties) : Block(properties) {
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = Shapes.block()
}
