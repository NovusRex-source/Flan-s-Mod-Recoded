package com.flansmod.recoded.fuel

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.FuelTypes
import com.flansmod.recoded.registry.FlansBlockEntities
import com.flansmod.recoded.registry.FlansBlocks
import com.flansmod.recoded.registry.FlansMenus
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult

/** A machine block facing the player who placed it; opens its block entity's menu, ticks it on the server. */
abstract class FuelMachineBlock(properties: Properties) : BaseEntityBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    protected abstract val blockEntityType: BlockEntityType<out FuelMachineBlockEntity>

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState = defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = blockEntityType.create(pos, state)

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (!level.isClientSide()) (level.getBlockEntity(pos) as? FuelMachineBlockEntity)?.let(player::openMenu)
        return InteractionResult.SUCCESS
    }

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide()) null else createTickerHelper(type, blockEntityType) { l, p, _, be -> be.serverTick(l as ServerLevel, p) }

    companion object {
        val FACING = HorizontalDirectionalBlock.FACING
    }
}

/** Container machine with a fuel tank. The tank's level and type go to open menus through [data]. */
abstract class FuelMachineBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState, size: Int, val capacity: Int) :
    BaseContainerBlockEntity(type, pos, state) {
    private var items: NonNullList<ItemStack> = NonNullList.withSize(size, ItemStack.EMPTY)
    var tank: FuelStack? = null

    override fun getItems() = items
    override fun setItems(items: NonNullList<ItemStack>) { this.items = items }
    override fun getContainerSize() = items.size

    abstract fun serverTick(level: ServerLevel, pos: BlockPos)

    /** Fills the can in [slot] from the tank, or ([intoTank]) empties it into the tank. */
    protected fun exchange(slot: Int, intoTank: Boolean) {
        val can = getItem(slot).takeIf { !it.isEmpty } ?: return
        val after = can.exchangeFuel(tank, capacity, intoTank)
        if (after != tank) {
            tank = after
            setChanged()
        }
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        net.minecraft.world.ContainerHelper.saveAllItems(output, items)
        tank?.let { output.store("Fuel", FuelStack.CODEC, it) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items = NonNullList.withSize(containerSize, ItemStack.EMPTY)
        net.minecraft.world.ContainerHelper.loadAllItems(input, items)
        tank = input.read("Fuel", FuelStack.CODEC).orElse(null)
    }

    companion object {
        /** Fuel types the menus can show by index. */
        val TYPES = listOf(FuelTypes.PETROL, FuelTypes.DIESEL)
        fun typeIndex(type: String?) = type?.let { TYPES.indexOf(it).takeIf { i -> i >= 0 } ?: TYPES.size } ?: -1
    }
}

// ------------------------------------------------------------------------------------------------- synthesizer

class FuelSynthesizerBlock(properties: Properties) : FuelMachineBlock(properties) {
    override val blockEntityType get() = FlansBlockEntities.FUEL_SYNTHESIZER
}

/**
 * Coal-to-liquid plant: one coal (or charcoal) and a quarter bucket of water make [PER_COAL] units of petrol or diesel
 * (button in the menu) in [BATCH_TICKS]. Water comes from water buckets in the water slot or any Fabric fluid pipe
 * ([water], registered as fluid storage). Fills fuel cans in the can slot and pumps into an adjacent petrol station.
 */
class FuelSynthesizerBlockEntity(pos: BlockPos, state: BlockState) :
    FuelMachineBlockEntity(FlansBlockEntities.FUEL_SYNTHESIZER, pos, state, 3, CAPACITY) {
    var mode = FuelTypes.PETROL
    var progress = 0

    val water = object : SingleVariantStorage<FluidVariant>() {
        override fun getBlankVariant(): FluidVariant = FluidVariant.blank()
        override fun getCapacity(variant: FluidVariant) = WATER_CAPACITY
        override fun canInsert(variant: FluidVariant) = variant.isOf(Fluids.WATER)
        override fun canExtract(variant: FluidVariant) = false
        override fun onFinalCommit() = setChanged()
    }

    val data = object : ContainerData {
        override fun get(index: Int) = when (index) {
            0 -> progress
            1 -> (water.amount / WATER_PER_BATCH).toInt()
            2 -> (tank?.amount ?: 0) / 10
            3 -> typeIndex(mode)
            else -> typeIndex(tank?.type)
        }
        override fun set(index: Int, value: Int) = Unit
        override fun getCount() = 5
    }

    override fun getDefaultName(): Component = Component.translatable("container.flansmod.fuel_synthesizer")
    override fun createMenu(id: Int, inventory: Inventory): AbstractContainerMenu = FuelSynthesizerMenu(id, inventory, this, data)

    override fun canPlaceItem(slot: Int, stack: ItemStack) = when (slot) {
        COAL -> stack.`is`(Items.COAL) || stack.`is`(Items.CHARCOAL)
        WATER -> stack.`is`(Items.WATER_BUCKET)
        else -> stack.item is FuelCanItem
    }

    fun toggleMode() {
        mode = TYPES[(TYPES.indexOf(mode) + 1) % TYPES.size]
        progress = 0
        setChanged()
    }

    override fun serverTick(level: ServerLevel, pos: BlockPos) {
        // Water bucket → tank, the empty bucket stays in the slot.
        if (getItem(WATER).`is`(Items.WATER_BUCKET) && water.amount + FluidConstants.BUCKET <= WATER_CAPACITY) {
            water.variant = FluidVariant.of(Fluids.WATER)
            water.amount += FluidConstants.BUCKET
            setItem(WATER, ItemStack(Items.BUCKET))
        }
        val room = tank == null || (tank!!.type == mode && tank!!.amount + PER_COAL <= capacity)
        if (canPlaceItem(COAL, getItem(COAL)) && water.amount >= WATER_PER_BATCH && room) {
            if (++progress >= BATCH_TICKS) {
                progress = 0
                getItem(COAL).shrink(1)
                water.amount -= WATER_PER_BATCH
                tank = FuelStack(mode, (tank?.amount ?: 0) + PER_COAL)
                level.playSound(null, pos, SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.5f, 0.7f)
            }
            if (level.gameTime % 8 == 0L) level.sendParticles(ParticleTypes.SMOKE, pos.x + 0.5, pos.y + 1.1, pos.z + 0.5, 2, 0.15, 0.05, 0.15, 0.01)
            setChanged()
        } else if (progress != 0) {
            progress = 0
            setChanged()
        }
        exchange(CAN, intoTank = false)
        // Feed a neighbouring petrol station.
        for (dir in Direction.entries) {
            val station = level.getBlockEntity(pos.relative(dir)) as? PetrolStationBlockEntity ?: continue
            tank = station.accept(tank, PUSH_PER_TICK)
        }
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("Mode", mode)
        output.putInt("Progress", progress)
        output.putLong("Water", water.amount)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        mode = input.getStringOr("Mode", FuelTypes.PETROL)
        progress = input.getIntOr("Progress", 0)
        water.amount = input.getLongOr("Water", 0L)
        water.variant = if (water.amount > 0) FluidVariant.of(Fluids.WATER) else FluidVariant.blank()
    }

    companion object {
        const val COAL = 0
        const val WATER = 1
        const val CAN = 2
        const val CAPACITY = 32000
        const val PER_COAL = 1600
        const val BATCH_TICKS = 80
        const val PUSH_PER_TICK = 100
        val WATER_PER_BATCH = FluidConstants.BUCKET / 4
        val WATER_CAPACITY = FluidConstants.BUCKET * 4
    }
}

class FuelSynthesizerMenu(id: Int, inventory: Inventory, container: Container = SimpleContainer(3), val data: ContainerData = SimpleContainerData(5)) :
    FuelMachineMenu(FlansMenus.FUEL_SYNTHESIZER, id, container, 3) {
    init {
        checkContainerSize(container, 3)
        addSlot(FilteredSlot(container, FuelSynthesizerBlockEntity.COAL, 44, 22))
        addSlot(FilteredSlot(container, FuelSynthesizerBlockEntity.WATER, 44, 50))
        addSlot(FilteredSlot(container, FuelSynthesizerBlockEntity.CAN, 134, 50))
        addStandardInventorySlots(inventory, 8, 84)
        addDataSlots(data)
    }

    val progress get() = data.get(0)
    val waterBatches get() = data.get(1)
    val fuel get() = data.get(2) * 10
    val mode get() = FuelMachineBlockEntity.TYPES.getOrElse(data.get(3)) { FuelTypes.PETROL }
    val tankType get() = data.get(4).takeIf { it >= 0 }?.let { FuelMachineBlockEntity.TYPES.getOrNull(it) ?: "?" }

    /** Button 0: switch between petrol and diesel. */
    override fun clickMenuButton(player: Player, id: Int): Boolean {
        if (id != 0) return false
        (container as? FuelSynthesizerBlockEntity)?.toggleMode()
        return true
    }

}

// ------------------------------------------------------------------------------------------------- petrol station

class PetrolStationBlock(properties: Properties) : FuelMachineBlock(properties) {
    override val blockEntityType get() = FlansBlockEntities.PETROL_STATION
}

/**
 * Petrol pump with a [CAPACITY] tank of one fuel type: fills every vehicle running on that fuel within [RANGE]
 * blocks, [RATE] units per tick. Cans in the left slot are emptied into the tank, cans in the right slot filled from
 * it; a fuel synthesizer next to it pumps its production in.
 */
class PetrolStationBlockEntity(pos: BlockPos, state: BlockState) :
    FuelMachineBlockEntity(FlansBlockEntities.PETROL_STATION, pos, state, 2, CAPACITY) {
    /** Ticks left of the "pumping" state shown in the menu. */
    private var pumping = 0

    val data = object : ContainerData {
        override fun get(index: Int) = when (index) {
            0 -> (tank?.amount ?: 0) / 10
            1 -> typeIndex(tank?.type)
            else -> pumping
        }
        override fun set(index: Int, value: Int) = Unit
        override fun getCount() = 3
    }

    override fun getDefaultName(): Component = Component.translatable("container.flansmod.petrol_station")
    override fun createMenu(id: Int, inventory: Inventory): AbstractContainerMenu = PetrolStationMenu(id, inventory, this, data)
    override fun canPlaceItem(slot: Int, stack: ItemStack) = stack.item is FuelCanItem

    /** Takes up to [max] units of [offered] (same type or empty tank); returns what is left of it. */
    fun accept(offered: FuelStack?, max: Int): FuelStack? {
        if (offered == null || (tank != null && tank!!.type != offered.type)) return offered
        val moved = minOf(max, offered.amount, capacity - (tank?.amount ?: 0))
        if (moved <= 0) return offered
        tank = FuelStack(offered.type, (tank?.amount ?: 0) + moved)
        setChanged()
        return offered.copy(amount = offered.amount - moved).takeIf { it.amount > 0 }
    }

    override fun serverTick(level: ServerLevel, pos: BlockPos) {
        exchange(IN, intoTank = true)
        exchange(OUT, intoTank = false)
        if (pumping > 0) pumping--
        val fuel = tank ?: return
        if (level.gameTime % 4 != 0L) return
        val area = AABB(pos).inflate(RANGE)
        for (vehicle in level.getEntitiesOfClass(DriveableEntity::class.java, area)) {
            val def = vehicle.definition?.takeIf { it.needsFuel && it.fuel.type == fuel.type } ?: continue
            val moved = minOf(RATE * 4, tank?.amount ?: 0, def.fuel.capacity - vehicle.fuel)
            if (moved <= 0) continue
            vehicle.fuel += moved
            tank = tank!!.copy(amount = tank!!.amount - moved).takeIf { it.amount > 0 }
            if (pumping == 0) level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.5f, 0.6f)
            pumping = 20
            setChanged()
            if (tank == null) return
        }
    }

    companion object {
        const val IN = 0
        const val OUT = 1
        const val CAPACITY = 64000
        const val RATE = 40
        const val RANGE = 4.5
    }
}

class PetrolStationMenu(id: Int, inventory: Inventory, container: Container = SimpleContainer(2), val data: ContainerData = SimpleContainerData(3)) :
    FuelMachineMenu(FlansMenus.PETROL_STATION, id, container, 2) {
    init {
        checkContainerSize(container, 2)
        addSlot(FilteredSlot(container, PetrolStationBlockEntity.IN, 26, 35))
        addSlot(FilteredSlot(container, PetrolStationBlockEntity.OUT, 134, 35))
        addStandardInventorySlots(inventory, 8, 84)
        addDataSlots(data)
    }

    val fuel get() = data.get(0) * 10
    val tankType get() = data.get(1).takeIf { it >= 0 }?.let { FuelMachineBlockEntity.TYPES.getOrNull(it) ?: "?" }
    val pumping get() = data.get(2) > 0

}

/** A machine slot that only takes what its container accepts there. */
private class FilteredSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
    override fun mayPlace(stack: ItemStack) = container.canPlaceItem(containerSlot, stack)
}

/** Menus of the fuel machines: [own] machine slots, then the player inventory; shift-click moves between them. */
abstract class FuelMachineMenu(type: MenuType<*>, id: Int, protected val container: Container, private val own: Int) : AbstractContainerMenu(type, id) {
    override fun quickMoveStack(player: Player, index: Int): ItemStack {
        val slot = slots[index]
        if (!slot.hasItem()) return ItemStack.EMPTY
        val stack = slot.item
        val copy = stack.copy()
        val moved = if (index < own) moveItemStackTo(stack, own, slots.size, true)
        else (0 until own).any { slots[it].mayPlace(stack) && moveItemStackTo(stack, it, it + 1, false) }
        if (!moved) return ItemStack.EMPTY
        if (stack.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
        return copy
    }

    override fun stillValid(player: Player) = container.stillValid(player)
}
