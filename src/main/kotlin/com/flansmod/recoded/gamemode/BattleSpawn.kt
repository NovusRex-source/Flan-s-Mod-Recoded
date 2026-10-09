package com.flansmod.recoded.gamemode

import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.gun.Factions
import com.flansmod.recoded.registry.FlansBlockEntities
import com.flansmod.recoded.registry.FlansMenus
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraft.world.scores.TeamColor

/**
 * Default spawn point: a moderator of the nearby battle (its manager, while not fighting) assigns it to a team - and
 * so to that team's faction. The team respawns here whenever it has no flag post; it can be neither claimed, captured
 * nor stolen. The pad shows the team colour ([COLOR]).
 */
class BattleSpawnBlock(properties: Properties) : BaseEntityBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(COLOR, TeamColor.WHITE))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(COLOR)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = BattleSpawnBlockEntity(pos, state)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = PAD

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (player is ServerPlayer) (level.getBlockEntity(pos) as? BattleSpawnBlockEntity)?.let(player::openMenu)
        return InteractionResult.SUCCESS
    }

    companion object {
        val COLOR: EnumProperty<TeamColor> = EnumProperty.create("color", TeamColor::class.java)
        private val PAD: VoxelShape = box(1.0, 0.0, 1.0, 15.0, 3.0, 15.0)
    }
}

class BattleSpawnBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(FlansBlockEntities.BATTLE_SPAWN, pos, state), ExtendedMenuProvider<BattleSpawnView> {
    /** Battle Master (same dimension) whose `spawns` name the team of this spawn point. */
    var master: BlockPos? = null

    fun battle(): BattleMasterBlockEntity? = master?.let { level?.getBlockEntity(it) as? BattleMasterBlockEntity }
    val team: String? get() = battle()?.state?.spawns?.get(Post.key(blockPos))

    /** Moderators: the battle's manager while not fighting in it. */
    fun canModerate(player: ServerPlayer, battle: BattleMasterBlockEntity?) =
        battle != null && battle.canManage(player) && Battles.activeBattle(player) == null

    /** Assigns this spawn point to [team] of the nearest battle (null = to nobody). */
    fun assign(player: ServerPlayer, team: String?) {
        val battle = battle() ?: BattleMasterBlockEntity.near(level ?: return, blockPos, player) ?: return
        if (!canModerate(player, battle)) return
        master = battle.blockPos
        battle.setSpawn(blockPos, team?.takeIf { battle.settings.team(it) != null })
        setChanged()
    }

    fun updateLook() {
        val level = level ?: return
        val color = battle()?.let { b -> team?.let { b.settings.team(it)?.teamColor } } ?: TeamColor.WHITE
        val state = level.getBlockState(blockPos)
        if (state.block is BattleSpawnBlock && state.getValue(BattleSpawnBlock.COLOR) != color) {
            level.setBlockAndUpdate(blockPos, state.setValue(BattleSpawnBlock.COLOR, color))
        }
    }

    override fun preRemoveSideEffects(pos: BlockPos, blockState: BlockState) {
        battle()?.setSpawn(pos, null)
        super.preRemoveSideEffects(pos, blockState)
    }

    override fun getDisplayName(): Component = Component.translatable("block.flansmod.battle_spawn")

    override fun createMenu(id: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
        BattleSpawnMenu(id, inventory, view(player as ServerPlayer), this)

    override fun getScreenOpeningData(player: ServerPlayer) = view(player)

    fun view(player: ServerPlayer): BattleSpawnView {
        val battle = battle() ?: level?.let { BattleMasterBlockEntity.near(it, blockPos, player) }
        return BattleSpawnView(
            battle?.settings?.name, team,
            battle?.settings?.teams.orEmpty().map { t ->
                BattleSpawnView.TeamView(t.name, t.teamColor.rgb(), t.faction?.let { Factions[it]?.name ?: it.toString() })
            },
            canModerate(player, battle),
        )
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        master?.let { output.putLong("Master", it.asLong()) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        master = input.getLong("Master").map(BlockPos::of).orElse(null)
    }
}

@Serializable
data class BattleSpawnView(val battle: String?, val team: String?, val teams: List<TeamView>, val canModerate: Boolean) {
    @Serializable
    data class TeamView(val name: String, val rgb: Int, val faction: String?)

    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleSpawnView> = ByteBufCodecs.stringUtf8(1 shl 16).map(
            { Content.JSON.decodeFromString(serializer(), it) }, { Content.JSON.encodeToString(serializer(), it) },
        ).cast()
    }
}

/** Buttons only: 0..15 assign to that team, [CLEAR] to nobody. */
class BattleSpawnMenu(id: Int, inventory: Inventory, val view: BattleSpawnView, private val spawn: BattleSpawnBlockEntity? = null) :
    AbstractContainerMenu(FlansMenus.BATTLE_SPAWN, id) {

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        val spawn = spawn ?: return false
        val p = player as? ServerPlayer ?: return false
        when (id) {
            in view.teams.indices -> spawn.assign(p, view.teams[id].name)
            CLEAR -> spawn.assign(p, null)
            else -> return false
        }
        p.openMenu(spawn) // fresh view
        return true
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY
    override fun stillValid(player: Player) = spawn == null || (!spawn.isRemoved && spawn.blockPos.closerToCenterThan(player.position(), 8.0))

    companion object {
        const val CLEAR = 100
    }
}
