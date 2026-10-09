package com.flansmod.recoded.gamemode

import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.registry.FlansBlockEntities
import com.flansmod.recoded.registry.FlansMenus
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraft.world.scores.TeamColor

/**
 * Team flag post: a team's base in a battle. Claimed by a member for their team, it is where the team respawns,
 * where members enter and leave the battle (leaving gives back the inventory left behind) and where they spend battle
 * money in the team shop. The cloth shows the owner's colour ([COLOR]) and disappears while the flag is stolen
 * ([STOLEN], capture the flag). A Battle Master's manager can lock a post or make it a hill (an objective).
 */
class TeamFlagBlock(properties: Properties) : BaseEntityBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(COLOR, TeamColor.WHITE).setValue(STOLEN, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(COLOR, STOLEN)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = TeamFlagBlockEntity(pos, state)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = POLE

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (player is ServerPlayer) (level.getBlockEntity(pos) as? TeamFlagBlockEntity)?.let(player::openMenu)
        return InteractionResult.SUCCESS
    }

    companion object {
        val COLOR: EnumProperty<TeamColor> = EnumProperty.create("color", TeamColor::class.java)
        val STOLEN: BooleanProperty = BooleanProperty.create("stolen")
        private val POLE: VoxelShape = Block.box(6.0, 0.0, 6.0, 10.0, 16.0, 10.0)
    }
}

class TeamFlagBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(FlansBlockEntities.TEAM_FLAG, pos, state), ExtendedMenuProvider<TeamFlagView> {
    /** Battle Master (same dimension) this post belongs to; its [Post] there says which team owns it. */
    var master: BlockPos? = null

    fun battle(): BattleMasterBlockEntity? = master?.let { level?.getBlockEntity(it) as? BattleMasterBlockEntity }?.takeIf { it.post(blockPos) != null }
    fun post(): Post? = battle()?.post(blockPos)
    val team get() = post()?.team

    /**
     * Makes this a base of [player]'s team. Before the battle a team has one base (its previous one is released);
     * during it, a team may only set up a new base when it has none left. Locked posts and hills cannot be claimed.
     */
    fun claim(player: ServerPlayer): Boolean {
        val data = Battles.data(player) ?: return false
        val battle = Battles.master(player.level().server, data.master)?.takeIf { it.level == level } ?: return false
        if (!canClaim(player, battle)) return false
        if (!battle.running) battle.posts(data.team).forEach { battle.setPost(it.blockPos, null) }
        battle()?.takeIf { it != battle }?.setPost(blockPos, null)
        master = battle.blockPos
        battle.setPost(blockPos, Post(listOf(blockPos.x, blockPos.y, blockPos.z), data.team, label = data.team))
        setChanged()
        return true
    }

    fun canClaim(player: ServerPlayer, battle: BattleMasterBlockEntity? = Battles.data(player)?.let { Battles.master(player.level().server, it.master) }): Boolean {
        val data = Battles.data(player) ?: return false
        if (battle == null || battle.level != level) return false
        val post = battle.post(blockPos)
        if (post != null && (post.locked || post.hill || post.team == data.team)) return false
        return !battle.running || (post == null && battle.posts(data.team).isEmpty())
    }

    /** Manager actions: lock/unlock, toggle hill (neutral objective), release (no longer part of the battle). */
    fun manage(player: ServerPlayer, action: Int) {
        val battle = battle() ?: BattleMasterBlockEntity.near(level ?: return, blockPos, player) ?: return
        if (!battle.canManage(player)) return
        val post = battle.post(blockPos) ?: Post(listOf(blockPos.x, blockPos.y, blockPos.z))
        master = battle.blockPos
        when (action) {
            TeamFlagMenu.LOCK -> battle.setPost(blockPos, post.copy(locked = !post.locked))
            TeamFlagMenu.HILL -> battle.setPost(blockPos, if (post.hill) post.copy(hill = false, label = post.team ?: "")
                else post.copy(hill = true, team = null, label = battle.nextHillLabel()))
            TeamFlagMenu.RELEASE -> {
                battle.setPost(blockPos, null)
                master = null
            }
        }
        updateLook()
        setChanged()
    }

    /** Cloth colour of the owning team (white when neutral) and hidden while the flag is carried away. */
    fun updateLook() {
        val level = level ?: return
        val battle = battle()
        val color = battle?.let { b -> post()?.team?.let { b.settings.team(it)?.teamColor } } ?: TeamColor.WHITE
        val stolen = battle?.carried(Post.key(blockPos)) == true
        val state = level.getBlockState(blockPos)
        if (state.block is TeamFlagBlock && (state.getValue(TeamFlagBlock.COLOR) != color || state.getValue(TeamFlagBlock.STOLEN) != stolen)) {
            level.setBlockAndUpdate(blockPos, state.setValue(TeamFlagBlock.COLOR, color).setValue(TeamFlagBlock.STOLEN, stolen))
        }
    }

    override fun preRemoveSideEffects(pos: BlockPos, blockState: BlockState) {
        battle()?.let { b ->
            b.state = b.state.copy(carriers = b.state.carriers.filterValues { it != Post.key(pos) })
            b.setPost(pos, null)
        }
        super.preRemoveSideEffects(pos, blockState)
    }

    override fun getDisplayName(): Component = Component.translatable("container.flansmod.team_flag")

    override fun createMenu(id: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
        TeamFlagMenu(id, inventory, view(player as ServerPlayer), this, player)

    override fun getScreenOpeningData(player: ServerPlayer) = view(player)

    fun view(player: ServerPlayer): TeamFlagView {
        val battle = battle()
        val post = post()
        val team = post?.team
        val me = Battles.data(player)
        val mine = battle != null && me != null && me.master == battle.globalPos && me.team == team
        val color = battle?.settings?.team(team)?.teamColor?.rgb() ?: 0xFFFFFF
        val manager = (battle ?: level?.let { BattleMasterBlockEntity.near(it, blockPos, player) })?.canManage(player) == true
        return TeamFlagView(
            team = team, rgb = color, battle = battle?.settings?.name, running = battle?.running == true,
            myTeam = me?.team, mine = mine, inBattle = mine && me.inBattle,
            canClaim = canClaim(player),
            pages = if (mine) Battles.shop(battle, team!!).size.let { (it + PAGE - 1) / PAGE }.coerceAtLeast(1) else 0,
            label = post?.label ?: "", hill = post?.hill == true, locked = post?.locked == true, canManage = manager,
            stolen = battle?.carried(Post.key(blockPos)) == true,
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

    companion object {
        const val PAGE = 27
    }
}

@Serializable
data class TeamFlagView(
    val team: String?, val rgb: Int, val battle: String?, val running: Boolean, val myTeam: String?,
    /** The player is a member of this flag's team (shop, enter/leave). */
    val mine: Boolean, val inBattle: Boolean, val canClaim: Boolean, val pages: Int,
    val label: String = "", val hill: Boolean = false, val locked: Boolean = false, val canManage: Boolean = false, val stolen: Boolean = false,
) {
    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TeamFlagView> = ByteBufCodecs.stringUtf8(1 shl 16).map(
            { Content.JSON.decodeFromString(serializer(), it) }, { Content.JSON.encodeToString(serializer(), it) },
        ).cast()
    }
}

/**
 * Slots 0..26: the shop page (display copies with their price; clicking one buys it), then the player inventory.
 * Data: money (two 16-bit halves) and the page. Buttons: [CLAIM], [ENTER], [LEAVE], [PREV], [NEXT]; managers [LOCK], [HILL], [RELEASE].
 */
class TeamFlagMenu(
    id: Int, inventory: Inventory, val view: TeamFlagView,
    private val flag: TeamFlagBlockEntity? = null, private val player: Player = inventory.player,
    private val shop: Container = SimpleContainer(TeamFlagBlockEntity.PAGE), val data: ContainerData = SimpleContainerData(3),
) : AbstractContainerMenu(FlansMenus.TEAM_FLAG, id) {
    private var page = 0

    init {
        for (i in 0 until TeamFlagBlockEntity.PAGE) addSlot(object : Slot(shop, i, 8 + i % 9 * 18, 30 + i / 9 * 18) {
            override fun mayPickup(player: Player) = false
            override fun mayPlace(stack: ItemStack) = false
        })
        addStandardInventorySlots(inventory, 8, 104)
        addDataSlots(data)
        fillPage()
    }

    val money get() = (data.get(0) and 0xFFFF) or (data.get(1) shl 16)
    val currentPage get() = data.get(2)

    private fun entries() = flag?.battle()?.let { b -> flag.team?.let { Battles.shop(b, it) } }.orEmpty()

    private fun fillPage() {
        val p = player as? ServerPlayer ?: return
        val entries = entries().takeIf { view.mine }.orEmpty()
        for (i in 0 until TeamFlagBlockEntity.PAGE) {
            shop.setItem(i, entries.getOrNull(page * TeamFlagBlockEntity.PAGE + i)?.takeIf { !it.stack.isEmpty }?.display() ?: ItemStack.EMPTY)
        }
        val money = Battles.data(p)?.money ?: 0
        data.set(0, money and 0xFFFF)
        data.set(1, money ushr 16)
        data.set(2, page)
    }

    override fun clicked(slotId: Int, button: Int, clickType: ContainerInput, player: Player) {
        if (slotId in 0 until TeamFlagBlockEntity.PAGE) {
            // Shop slots never move items: a click buys (server side).
            val p = player as? ServerPlayer ?: return
            val entry = entries().getOrNull(page * TeamFlagBlockEntity.PAGE + slotId)?.takeIf { !it.stack.isEmpty } ?: return
            if (view.mine && Battles.data(p)?.inBattle == true) Battles.buy(p, entry)
            fillPage()
            return
        }
        super.clicked(slotId, button, clickType, player)
    }

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        val p = player as? ServerPlayer ?: return false
        val flag = flag ?: return false
        when (id) {
            CLAIM -> if (view.canClaim) flag.claim(p)
            ENTER -> flag.battle()?.takeIf { view.mine }?.let { Battles.enter(p, it) }
            LEAVE -> if (view.mine) Battles.exit(p)
            PREV -> { page = (page - 1).coerceAtLeast(0); fillPage(); return true }
            NEXT -> { page = (page + 1).coerceAtMost((view.pages - 1).coerceAtLeast(0)); fillPage(); return true }
            LOCK, HILL, RELEASE -> flag.manage(p, id)
            else -> return false
        }
        p.openMenu(flag) // fresh view
        return true
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY
    override fun stillValid(player: Player) = flag == null || (!flag.isRemoved && flag.blockPos.closerToCenterThan(player.position(), 8.0))

    companion object {
        const val CLAIM = 0
        const val ENTER = 1
        const val LEAVE = 2
        const val PREV = 3
        const val NEXT = 4
        const val LOCK = 5
        const val HILL = 6
        const val RELEASE = 7
    }
}
