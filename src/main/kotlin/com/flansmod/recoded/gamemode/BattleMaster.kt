package com.flansmod.recoded.gamemode

import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.registry.FlansBlockEntities
import com.flansmod.recoded.registry.FlansMenus
import com.mojang.serialization.Codec
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
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import java.util.Collections
import java.util.Optional
import java.util.UUID
import java.util.WeakHashMap

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

/**
 * A flag post taking part in a battle: a team base ([team] set) or, marked as [hill], an objective of king of the hill /
 * conquest that starts neutral. [locked] posts can be neither claimed, captured nor stolen. [label] names it in menus.
 */
@Serializable
data class Post(val pos: List<Int>, val team: String? = null, val hill: Boolean = false, val locked: Boolean = false, val label: String = "") {
    val blockPos get() = BlockPos(pos[0], pos[1], pos[2])

    companion object {
        fun key(pos: BlockPos) = "${pos.x},${pos.y},${pos.z}"
    }
}

/** Kills and deaths of a fighter (player UUID or bot callsign), for the in-battle scoreboard. */
@Serializable
data class FighterStats(val name: String, val team: String, val kills: Int = 0, val deaths: Int = 0, val captures: Int = 0, val bot: Boolean = false)

/** Saved battle state: settings, who is on which team (with names for the menu), scores, flag posts, border, bots. */
@Serializable
data class BattleState(
    val settings: BattleSettings = BattleSettings(),
    val owner: String? = null,
    val running: Boolean = false,
    val session: String = "",
    val startTick: Long = 0,
    /** Game time when the fighting starts (the countdown before it: no damage, nobody moves far). */
    val countdownEnd: Long = 0,
    val scores: Map<String, Int> = emptyMap(),
    /** Flag posts by [Post.key]. */
    val posts: Map<String, Post> = emptyMap(),
    /** Player UUID → (team, name). */
    val roster: Map<String, Member> = emptyMap(),
    /** Fighter stats by player UUID or `bot:<callsign>`. */
    val stats: Map<String, FighterStats> = emptyMap(),
    /** Capture the flag: carrier UUID → key of the post whose flag they carry. */
    val carriers: Map<String, String> = emptyMap(),
    /** Bot soldiers alive in this session: entity UUID → team. */
    val bots: Map<String, String> = emptyMap(),
    /** Border marker positions; with two or more, fighters stay inside the box around them. */
    val border: List<List<Int>> = emptyList(),
    /** Teams that took part when the battle started (a team without a spawn and nobody standing is out). */
    val startTeams: List<String> = emptyList(),
    /** Voluntary spectators: UUID → name. */
    val watchers: Map<String, String> = emptyMap(),
    /** Default spawn points ([BattleSpawnBlock]) by [Post.key] → team: used while the team has no flag post. */
    val spawns: Map<String, String> = emptyMap(),
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

    /** Team shops arranged in the shop editor (empty stacks are gaps); saved with the item codec, not in [state]. */
    var shops: Map<String, List<ShopEntry>> = emptyMap()
        set(value) {
            field = value
            setChanged()
        }

    /** Capture progress per post key (not saved: a reload restarts captures). */
    val captures = HashMap<String, Capture>()

    data class Capture(val team: String, var ticks: Int)

    var owner: UUID?
        get() = state.owner?.let(UUID::fromString)
        set(value) { state = state.copy(owner = value?.toString()) }

    val settings get() = state.settings
    val running get() = state.running
    val globalPos: GlobalPos get() = GlobalPos.of(level!!.dimension(), blockPos)
    val inCountdown get() = running && (level?.gameTime ?: 0) < state.countdownEnd

    // ------------------------------------------------------------------------------------------- flag posts
    fun post(pos: BlockPos): Post? = state.posts[Post.key(pos)]
    fun setPost(pos: BlockPos, post: Post?) {
        state = state.copy(posts = if (post == null) state.posts - Post.key(pos) else state.posts + (Post.key(pos) to post))
        (level?.getBlockEntity(pos) as? TeamFlagBlockEntity)?.updateLook()
    }

    fun posts(team: String) = state.posts.values.filter { it.team == team }.sortedBy { it.label }

    /** [team]'s first flag post (whose flag may be away - the post still counts), or null. */
    fun flag(team: String): BlockPos? = posts(team).firstOrNull()?.blockPos

    /** [team]'s default spawn point (a [BattleSpawnBlock] a moderator assigned to it), or null. */
    fun defaultSpawn(team: String): BlockPos? = state.spawns.entries.filter { it.value == team }.minOfOrNull { it.key }
        ?.split(",")?.map(String::toInt)?.let { BlockPos(it[0], it[1], it[2]) }

    /** Where [team] respawns: a flag post, else its default spawn point; null = nowhere (the fallen spectate). */
    fun spawn(team: String): BlockPos? = flag(team) ?: defaultSpawn(team)

    fun setSpawn(pos: BlockPos, team: String?) {
        state = state.copy(spawns = if (team == null) state.spawns - Post.key(pos) else state.spawns + (Post.key(pos) to team))
        (level?.getBlockEntity(pos) as? BattleSpawnBlockEntity)?.updateLook()
    }

    /** Next free hill letter (A, B, C, ...). */
    fun nextHillLabel(): String = ('A'..'Z').map(Char::toString).first { l -> state.posts.values.none { it.label == l } }

    fun carried(postKey: String) = state.carriers.containsValue(postKey)

    // ------------------------------------------------------------------------------------------- border
    /** The battle area (x/z box around the border markers, any height), or null with fewer than two markers. */
    fun borderBox(): AABB? {
        if (state.border.size < 2) return null
        val xs = state.border.map { it[0] }
        val zs = state.border.map { it[2] }
        return AABB(xs.min().toDouble(), -4096.0, zs.min().toDouble(), xs.max() + 1.0, 4096.0, zs.max() + 1.0)
    }

    /** The battlefield: the border box, or without one everything within [AREA_RADIUS] blocks of the Battle Master. */
    fun area(): AABB = borderBox() ?: AABB(blockPos).inflate(AREA_RADIUS, 4096.0, AREA_RADIUS)

    /** Border wall: blocks this master placed, the box they were built for, and the build in progress (not saved). */
    val wall = HashSet<Long>()
    var wallKey = ""
    internal var wallBuilding: String? = null
    internal val wallQueue = ArrayDeque<Long>()

    fun addBorder(pos: BlockPos) {
        if (state.border.none { it == listOf(pos.x, pos.y, pos.z) }) state = state.copy(border = state.border + listOf(listOf(pos.x, pos.y, pos.z)))
    }

    fun removeBorder(pos: BlockPos) {
        state = state.copy(border = state.border - listOf(listOf(pos.x, pos.y, pos.z)))
    }

    fun teamOf(player: UUID) = state.roster[player.toString()]?.team
    fun members(team: String) = state.roster.filterValues { it.team == team }

    /** Seconds left of the time limit, or -1 without one. */
    fun secondsLeft(): Int {
        val limit = settings.timeLimitMinutes * 1200L
        if (!running || limit <= 0) return -1
        return (((state.countdownEnd + limit) - (level?.gameTime ?: 0)) / 20).coerceIn(0, limit / 20).toInt()
    }

    fun canManage(player: ServerPlayer) = player.uuid == owner || player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)

    fun serverTick(level: ServerLevel) {
        LOADED += this
        BattleWall.tick(this, level)
        if (!running) return
        BattleRules.tick(this, level)
    }

    override fun setLevel(level: Level) {
        super.setLevel(level)
        LOADED += this
    }

    override fun setRemoved() {
        LOADED -= this
        super.setRemoved()
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
        (level as? ServerLevel)?.let { level ->
            state.roster.keys.mapNotNull { level.server.playerList.getPlayer(UUID.fromString(it)) }.forEach(Battles::quit)
            state.watchers.keys.mapNotNull { level.server.playerList.getPlayer(UUID.fromString(it)) }.forEach(Battles::stopSpectating)
            for (post in state.posts.values) (level.getBlockEntity(post.blockPos) as? TeamFlagBlockEntity)?.let { it.master = null; it.updateLook() }
            for (key in state.spawns.keys) {
                val (x, y, z) = key.split(",").map(String::toInt)
                (level.getBlockEntity(BlockPos(x, y, z)) as? BattleSpawnBlockEntity)?.let { it.master = null; it.updateLook() }
            }
            BattleWall.tearDown(this, level)
        }
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
            settings.teams.map { t ->
                BattleMasterView.TeamView(t.name, t.teamColor.rgb(), state.scores[t.name] ?: 0, members(t.name).values.map { it.name },
                    flag(t.name) != null, state.bots.count { it.value == t.name })
            },
            me?.team, me?.inBattle == true, canManage(player),
            spectating = player.getAttached(BattleSpectator.ATTACHMENT)?.master == globalPos,
            posts = state.posts.size, border = state.border.size,
        )
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("Battle", Content.JSON.encodeToString(BattleState.serializer(), state))
        if (shops.isNotEmpty()) output.store("Shops", SHOPS_CODEC, shops)
        if (wall.isNotEmpty()) output.store("Wall", Codec.LONG.listOf(), wall.toList())
        output.putString("WallKey", wallKey)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        state = input.getString("Battle").map { runCatching { Content.JSON.decodeFromString(BattleState.serializer(), it) }.getOrNull() }.orElse(null) ?: BattleState()
        shops = input.read("Shops", SHOPS_CODEC).orElse(emptyMap())
        wall.clear()
        input.read("Wall", Codec.LONG.listOf()).ifPresent { wall.addAll(it) }
        wallKey = input.getStringOr("WallKey", "")
    }

    companion object {
        const val AREA_RADIUS = 128.0
        private val SHOPS_CODEC: Codec<Map<String, List<ShopEntry>>> = Codec.unboundedMap(Codec.STRING, ShopEntry.CODEC.listOf())

        /** Battle Masters in loaded chunks (border markers and hill posts link to the nearest). */
        private val LOADED: MutableSet<BattleMasterBlockEntity> = Collections.newSetFromMap(WeakHashMap())

        fun loaded(level: Level): List<BattleMasterBlockEntity> = LOADED.filter { !it.isRemoved && it.level == level }

        /** The Battle Master [player] belongs to (or watches), else the nearest one within [range] blocks of [pos]. */
        fun near(level: Level, pos: BlockPos, player: ServerPlayer?, range: Double = 128.0): BattleMasterBlockEntity? {
            player?.let { p ->
                val own = Battles.data(p)?.master ?: p.getAttached(BattleSpectator.ATTACHMENT)?.master
                own?.let { Battles.master(p.level().server, it) }?.takeIf { it.level == level }?.let { return it }
            }
            return loaded(level).filter { it.blockPos.closerThan(pos, range) }.minByOrNull { it.blockPos.distSqr(pos) }
        }
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
    val spectating: Boolean = false,
    val posts: Int = 0,
    val border: Int = 0,
) {
    @Serializable
    data class TeamView(val name: String, val rgb: Int, val score: Int, val members: List<String>, val hasFlag: Boolean, val bots: Int = 0)

    companion object {
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, BattleMasterView> = ByteBufCodecs.stringUtf8(1 shl 20).map(
            { Content.JSON.decodeFromString(serializer(), it) }, { Content.JSON.encodeToString(serializer(), it) },
        ).cast()
    }
}

/** No slots: buttons only (vanilla menu buttons). 0..15 join team, [LEAVE], [ENTER], [SPECTATE], [START], [STOP], [SHOP] + team. */
class BattleMasterMenu(id: Int, inventory: Inventory, val view: BattleMasterView, private val master: BattleMasterBlockEntity? = null) :
    AbstractContainerMenu(FlansMenus.BATTLE_MASTER, id) {

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        val master = master ?: return false
        val p = player as? ServerPlayer ?: return false
        when (id) {
            in 0 until master.settings.teams.size -> Battles.join(p, master, master.settings.teams[id].name)
            LEAVE -> Battles.quit(p)
            ENTER -> Battles.enter(p, master)
            SPECTATE -> {
                if (p.getAttached(BattleSpectator.ATTACHMENT) != null) Battles.stopSpectating(p) else Battles.spectate(p, master)
                p.closeContainer()
                return true
            }
            START -> if (master.canManage(p)) Battles.start(master, p)
            STOP -> if (master.canManage(p)) Battles.end(master, master.state.scores.maxByOrNull { it.value }?.key)
            in SHOP until SHOP + master.settings.teams.size -> {
                if (master.canManage(p)) ShopEditorMenu.open(p, master, id - SHOP)
                return true
            }
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
        const val SPECTATE = 102
        const val START = 200
        const val STOP = 201
        const val SHOP = 300
    }
}
