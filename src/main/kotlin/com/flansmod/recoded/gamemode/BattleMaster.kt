package com.flansmod.recoded.gamemode

import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.registry.FlansBlockEntities
import com.flansmod.recoded.registry.FlansMenus
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.scores.TeamColor
import java.util.Optional
import java.util.UUID

/** The Battle Master: configure a battle, join a team, start and stop it. Its placer (or an operator) manages it. */
class BattleMasterBlock(properties: Properties) : BaseEntityBlock(properties) {
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = BattleMasterBlockEntity(pos, state)

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        (level.getBlockEntity(pos) as? BattleMasterBlockEntity)?.owner = placer?.uuid
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (player is ServerPlayer) (level.getBlockEntity(pos) as? BattleMasterBlockEntity)?.let(player::openMenu)
        return InteractionResult.SUCCESS
    }

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide()) null else createTickerHelper(type, FlansBlockEntities.BATTLE_MASTER) { l, _, _, be -> be.serverTick(l as ServerLevel) }
}

/** Saved battle state: settings, who is on which team (with names for the menu), scores and each team's flag post. */
@Serializable
data class BattleState(
    val settings: BattleSettings = BattleSettings(),
    val owner: String? = null,
    val running: Boolean = false,
    val session: String = "",
    val startTick: Long = 0,
    val scores: Map<String, Int> = emptyMap(),
    val flags: Map<String, List<Int>> = emptyMap(),
    /** Player UUID → (team, name). */
    val roster: Map<String, Member> = emptyMap(),
) {
    @Serializable
    data class Member(val team: String, val name: String)
}

class BattleMasterBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(FlansBlockEntities.BATTLE_MASTER, pos, state),
    ExtendedMenuProvider<BattleMasterView> {
    var state = BattleState()
        set(value) {
            field = value
            setChanged()
        }

    var owner: UUID?
        get() = state.owner?.let(UUID::fromString)
        set(value) { state = state.copy(owner = value?.toString()) }

    val settings get() = state.settings
    val running get() = state.running
    val globalPos: GlobalPos get() = GlobalPos.of(level!!.dimension(), blockPos)

    fun flag(team: String): BlockPos? = state.flags[team]?.let { BlockPos(it[0], it[1], it[2]) }
    fun setFlag(team: String, pos: BlockPos?) {
        state = state.copy(flags = if (pos == null) state.flags - team else state.flags + (team to listOf(pos.x, pos.y, pos.z)))
    }

    fun teamOf(player: UUID) = state.roster[player.toString()]?.team
    fun members(team: String) = state.roster.filterValues { it.team == team }

    /** Seconds left of the time limit, or -1 without one. */
    fun secondsLeft(): Int {
        val limit = settings.timeLimitMinutes * 1200L
        if (!running || limit <= 0) return -1
        return (((state.startTick + limit) - (level?.gameTime ?: 0)) / 20).coerceAtLeast(0).toInt()
    }

    fun canManage(player: ServerPlayer) = player.uuid == owner || player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)

    fun serverTick(level: ServerLevel) {
        if (!running) return
        val time = level.gameTime
        if (time % 20 == 0L) Battles.syncStatus(this)
        if (settings.incomePerMinute > 0 && (time - state.startTick) % 1200 == 1199L) {
            Battles.onlineFighters(this).forEach { Battles.addMoney(it, settings.incomePerMinute) }
        }
        if (secondsLeft() == 0) Battles.end(this, state.scores.maxByOrNull { it.value }?.key)
    }

    // ------------------------------------------------------------------------------------------- scoreboard teams
    /** Vanilla scoreboard team of [team] (coloured names, built-in friendly fire rule within the team). */
    fun scoreboardName(team: String) = "flans${blockPos.asLong().toString(36).takeLast(8)}_${settings.teams.indexOfFirst { it.name == team }}"

    fun syncScoreboard() {
        val scoreboard = (level as? ServerLevel)?.server?.scoreboard ?: return
        for (i in settings.teams.size until 16) scoreboard.getPlayerTeam("flans${blockPos.asLong().toString(36).takeLast(8)}_$i")?.let(scoreboard::removePlayerTeam)
        for (team in settings.teams) {
            val id = scoreboardName(team.name)
            val sb = scoreboard.getPlayerTeam(id) ?: scoreboard.addPlayerTeam(id)
            sb.setDisplayName(Component.literal(team.name))
            sb.setColor(Optional.of(team.teamColor))
            sb.setAllowFriendlyFire(settings.friendlyFire)
        }
        for ((_, member) in state.roster) scoreboard.getPlayerTeam(scoreboardName(member.team))?.let { scoreboard.addPlayerToTeam(member.name, it) }
    }

    fun removeScoreboard() {
        val scoreboard = (level as? ServerLevel)?.server?.scoreboard ?: return
        for (i in 0 until 16) scoreboard.getPlayerTeam("flans${blockPos.asLong().toString(36).takeLast(8)}_$i")?.let(scoreboard::removePlayerTeam)
    }

    override fun preRemoveSideEffects(pos: BlockPos, blockState: BlockState) {
        if (running) Battles.end(this, null)
        (level as? ServerLevel)?.let { level -> state.roster.keys.mapNotNull { level.server.playerList.getPlayer(UUID.fromString(it)) }.forEach(Battles::quit) }
        removeScoreboard()
        super.preRemoveSideEffects(pos, blockState)
    }

    // ------------------------------------------------------------------------------------------- menu
    override fun getDisplayName(): Component = Component.literal(settings.name)

    override fun createMenu(id: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
        BattleMasterMenu(id, inventory, view(player as ServerPlayer), this)

    override fun getScreenOpeningData(player: ServerPlayer) = view(player)

    fun view(player: ServerPlayer): BattleMasterView {
        val me = Battles.data(player)?.takeIf { it.master == globalPos }
        return BattleMasterView(
            blockPos.asLong(), settings, running, secondsLeft(),
            settings.teams.map { t -> BattleMasterView.TeamView(t.name, t.teamColor.rgb(), state.scores[t.name] ?: 0, members(t.name).values.map { it.name }, flag(t.name) != null) },
            me?.team, me?.inBattle == true, canManage(player),
        )
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("Battle", Content.JSON.encodeToString(BattleState.serializer(), state))
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        state = input.getString("Battle").map { runCatching { Content.JSON.decodeFromString(BattleState.serializer(), it) }.getOrNull() }.orElse(null) ?: BattleState()
    }
}

/** What the Battle Master menu shows, sent when it opens (the menu is reopened after every action). */
@Serializable
data class BattleMasterView(
    /** Block position ([BlockPos.asLong]) - where settings are sent. */
    val pos: Long,
    val settings: BattleSettings,
    val running: Boolean,
    val secondsLeft: Int,
    val teams: List<TeamView>,
    val myTeam: String?,
    val inBattle: Boolean,
    val canManage: Boolean,
) {
    @Serializable
    data class TeamView(val name: String, val rgb: Int, val score: Int, val members: List<String>, val hasFlag: Boolean)

    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleMasterView> = ByteBufCodecs.stringUtf8(1 shl 20).map(
            { Content.JSON.decodeFromString(serializer(), it) }, { Content.JSON.encodeToString(serializer(), it) },
        ).cast()
    }
}

/** No slots: buttons only (vanilla menu buttons). 0..15 join team, [LEAVE], [ENTER], [START], [STOP]. */
class BattleMasterMenu(id: Int, inventory: Inventory, val view: BattleMasterView, private val master: BattleMasterBlockEntity? = null) :
    AbstractContainerMenu(FlansMenus.BATTLE_MASTER, id) {

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        val master = master ?: return false
        val p = player as? ServerPlayer ?: return false
        when (id) {
            in 0 until master.settings.teams.size -> Battles.join(p, master, master.settings.teams[id].name)
            LEAVE -> Battles.quit(p)
            ENTER -> Battles.enter(p, master)
            START -> if (master.canManage(p)) Battles.start(master, p)
            STOP -> if (master.canManage(p)) Battles.end(master, master.state.scores.maxByOrNull { it.value }?.key)
            else -> return false
        }
        p.openMenu(master) // fresh view
        return true
    }

    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY
    override fun stillValid(player: Player) = master == null || (!master.isRemoved && master.blockPos.closerToCenterThan(player.position(), 8.0))

    companion object {
        const val LEAVE = 100
        const val ENTER = 101
        const val START = 200
        const val STOP = 201
    }
}
