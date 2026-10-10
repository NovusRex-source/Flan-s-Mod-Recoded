package com.flansmod.recoded.gamemode

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.fortification.Fortifications
import com.flansmod.recoded.fuel.FuelCanItem
import com.flansmod.recoded.fuel.FuelStack
import com.flansmod.recoded.gear.GearSlots
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Clothing
import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.gun.FuelTypes
import com.flansmod.recoded.gun.GrenadeDefinition
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.Structures
import com.flansmod.recoded.gun.VehicleType
import com.flansmod.recoded.gun.Vehicles
import com.flansmod.recoded.gun.factionOf
import com.flansmod.recoded.item.AmmoItem
import com.flansmod.recoded.item.ClothingItem
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.item.StructureItem
import com.flansmod.recoded.item.VehicleItem
import com.flansmod.recoded.registry.FlansItems
import com.mojang.brigadier.StringReader
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.commands.arguments.item.ItemParser
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.level.GameType
import net.minecraft.world.level.storage.LevelData
import java.util.UUID

/** Something the team shop sells: [stack] for [price] battle money. An empty [stack] is a gap in the shop layout. */
data class ShopEntry(val price: Int, val stack: ItemStack) {
    /** The shop slot's look: the item with its price as lore. */
    fun display(): ItemStack = stack.copy().apply {
        set(DataComponents.LORE, ItemLore(listOf(Component.translatable("gui.flansmod.shop.price", price).withStyle(ChatFormatting.GOLD))))
    }

    companion object {
        val EMPTY = ShopEntry(0, ItemStack.EMPTY)

        val CODEC: Codec<ShopEntry> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.INT.optionalFieldOf("price", 0).forGetter(ShopEntry::price),
                ItemStack.OPTIONAL_CODEC.optionalFieldOf("item", ItemStack.EMPTY).forGetter(ShopEntry::stack),
            ).apply(i, ::ShopEntry)
        }
    }
}

/**
 * Battles: teams, war and money in a normal survival world. Rules:
 * - Join a team at the Battle Master; entering the running battle stores your inventory ([BattlePlayer.stash]), switches
 *   to the battle's game mode, moves your spawn point to the team's flag post and gives the start money; leaving at
 *   your flag post (or the battle ending) gives everything back.
 * - A countdown runs before the fighting; then the battle mode ([BattleMode]) decides how teams score. Enemies are
 *   teams not allied; friends cannot hurt each other unless friendly fire is on. Bots ([SoldierEntity]) fight too.
 * - You respawn at your team's flag post (else its default spawn point, [BattleSpawnBlock]) with a few seconds of
 *   protection; without either you watch as a spectator until your team has one again. What you carried is lost
 *   (or kept, per setting).
 */
object Battles {
    fun data(player: ServerPlayer): BattlePlayer? = player.getAttached(BattlePlayer.ATTACHMENT)
    internal fun set(player: ServerPlayer, data: BattlePlayer?) {
        if (data == null) player.removeAttached(BattlePlayer.ATTACHMENT) else player.setAttached(BattlePlayer.ATTACHMENT, data)
    }

    fun master(server: MinecraftServer, pos: GlobalPos): BattleMasterBlockEntity? =
        server.getLevel(pos.dimension())?.getBlockEntity(pos.pos()) as? BattleMasterBlockEntity

    /** The player's battle, if they are a member and it is the running session they entered. */
    fun activeBattle(player: ServerPlayer): Pair<BattlePlayer, BattleMasterBlockEntity>? {
        val data = data(player)?.takeIf { it.inBattle } ?: return null
        val master = master(player.level().server, data.master)?.takeIf { it.running && it.state.session == data.session } ?: return null
        return data to master
    }

    /** Team and battle of anyone fighting: a player inside a running battle or one of its bots. */
    fun fighter(entity: Entity?): Pair<String, BattleMasterBlockEntity>? = when (entity) {
        is ServerPlayer -> activeBattle(entity)?.let { (d, m) -> d.team to m }
        is SoldierEntity -> entity.battle()?.let { entity.team to it }
        else -> null
    }

    /** Who is behind [source]: the attacker itself or the owner of the projectile. */
    fun attacker(source: DamageSource): Entity? = source.entity ?: (source.directEntity as? Projectile)?.owner

    /** Fighters on enemy teams of the same battle. */
    fun enemies(a: Entity, b: Entity): Boolean {
        val (ta, ma) = fighter(a) ?: return false
        val (tb, mb) = fighter(b) ?: return false
        return ma == mb && !ma.settings.friendly(ta, tb)
    }

    /** Players inside the battle (alive or waiting for a spawn). */
    fun onlineFighters(master: BattleMasterBlockEntity): List<ServerPlayer> =
        online(master).filter { p -> data(p)?.let { it.inBattle && it.session == master.state.session } == true }

    /** Online members and spectators. */
    fun online(master: BattleMasterBlockEntity): List<ServerPlayer> {
        val server = master.level?.server ?: return emptyList()
        return (master.state.roster.keys + master.state.watchers.keys).mapNotNull { server.playerList.getPlayer(UUID.fromString(it)) }
    }

    internal fun tell(player: ServerPlayer, key: String, vararg args: Any) = player.sendSystemMessage(Component.translatable(key, *args))
    internal fun broadcast(master: BattleMasterBlockEntity, message: Component) = online(master).forEach { it.sendSystemMessage(message) }

    fun teamName(master: BattleMasterBlockEntity, team: String): Component =
        Component.literal(team).withColor(master.settings.team(team)?.teamColor?.rgb() ?: 0xFFFFFF)

    // ------------------------------------------------------------------------------------------- membership

    fun join(player: ServerPlayer, master: BattleMasterBlockEntity, team: String) {
        val current = data(player)
        if (current?.inBattle == true) return tell(player, "message.flansmod.battle.leave_first")
        val size = master.settings.teamSize
        if (size > 0 && master.members(team).size >= size && master.teamOf(player.uuid) != team) return tell(player, "message.flansmod.battle.team_full", size)
        if (player.getAttached(BattleSpectator.ATTACHMENT) != null) stopSpectating(player)
        if (current != null && current.master != master.globalPos) master(player.level().server, current.master)?.let { removeFromRoster(it, player) }
        set(player, BattlePlayer(master.globalPos, team))
        master.state = master.state.copy(roster = master.state.roster + (player.uuid.toString() to BattleState.Member(team, player.scoreboardName)))
        master.syncScoreboard()
        broadcast(master, Component.translatable("message.flansmod.battle.joined", player.displayName, teamName(master, team)))
        if (master.running) enter(player, master)
    }

    /** Leaves the team (outside a running battle; inside, you leave at your flag post first). */
    fun quit(player: ServerPlayer) {
        val data = data(player) ?: return
        if (data.inBattle) exit(player)
        master(player.level().server, data.master)?.let { removeFromRoster(it, player) }
        set(player, null)
        syncStatus(player, null)
    }

    private fun removeFromRoster(master: BattleMasterBlockEntity, player: ServerPlayer) {
        master.state = master.state.copy(roster = master.state.roster - player.uuid.toString())
        master.level?.server?.scoreboard?.removePlayerFromTeam(player.scoreboardName)
    }

    // ------------------------------------------------------------------------------------------- in and out

    /** Into the running battle: the inventory stays behind, start money, battle game mode and spawn, off to the flag post. */
    fun enter(player: ServerPlayer, master: BattleMasterBlockEntity) {
        val data = data(player)?.takeIf { it.master == master.globalPos } ?: return
        if (!master.running) return tell(player, "message.flansmod.battle.not_running")
        if (data.inBattle && data.session == master.state.session) return
        if (player.getAttached(BattleSpectator.ATTACHMENT) != null) stopSpectating(player)
        val inventory = player.inventory
        // The backpack slot is stashed after the inventory slots.
        val stash = (0 until inventory.containerSize).map { inventory.getItem(it).copy() } + GearSlots.back(player).copy()
        inventory.clearContent()
        GearSlots.setBack(player, ItemStack.EMPTY)
        set(player, data.copy(inBattle = true, session = master.state.session, money = master.settings.startMoney, stash = stash, loadout = emptyList(),
            previousMode = player.gameMode().serializedName, previousRespawn = java.util.Optional.ofNullable(player.respawnConfig), waiting = false))
        player.setGameMode(master.settings.gameType)
        player.health = player.maxHealth
        player.foodData.foodLevel = 20
        toSpawn(player, master, data.team)
        stats(master, player.uuid.toString(), player.scoreboardName, data.team) { it }
        // Nobody walks off during the countdown.
        val countdown = (master.state.countdownEnd - (master.level?.gameTime ?: 0)).toInt()
        if (countdown > 0) player.addEffect(MobEffectInstance(MobEffects.SLOWNESS, countdown, 9, false, false))
        player.connection.send(ClientboundSetTitleTextPacket(Component.literal(master.settings.name).withStyle(ChatFormatting.GOLD)))
        player.connection.send(ClientboundSetSubtitleTextPacket(Component.translatable("message.flansmod.battle.entered", teamName(master, data.team))))
        syncStatus(master)
    }

    /** Out of the battle: battle items are gone, the stored inventory, game mode and spawn point come back. Membership stays. */
    fun exit(player: ServerPlayer) {
        val data = data(player)?.takeIf { it.inBattle } ?: return
        // On the death screen: items given to the dead player object are gone after respawning (no keepInventory),
        // so the stash comes back on respawn instead (AFTER_RESPAWN).
        if (player.isDeadOrDying) return
        val master = master(player.level().server, data.master)
        val inventory = player.inventory
        inventory.clearContent()
        data.stash.forEachIndexed { i, stack -> if (i < inventory.containerSize) inventory.setItem(i, stack) }
        GearSlots.setBack(player, data.stash.getOrNull(inventory.containerSize) ?: ItemStack.EMPTY)
        player.setGameMode((GameType.byName(data.previousMode, GameType.SURVIVAL) ?: GameType.SURVIVAL))
        player.setRespawnPosition(data.previousRespawn.orElse(null), false)
        player.removeEffect(MobEffects.GLOWING)
        // Waiting spectators float somewhere over the battlefield: back to solid ground next to the Battle Master.
        if (data.waiting && master != null) toSpawn(player, master, null)
        set(player, data.copy(inBattle = false, session = "", money = 0, stash = emptyList(), loadout = emptyList(), previousMode = "",
            previousRespawn = java.util.Optional.empty(), waiting = false))
        tell(player, "message.flansmod.battle.left")
        master?.let { m ->
            BattleRules.dropCarried(m, player.uuid.toString())
            syncStatus(m)
        }
    }

    /**
     * Next to the team's flag post, else its default spawn point (else the Battle Master, or there when [team] is null),
     * with the player's spawn point set there.
     */
    fun toSpawn(player: ServerPlayer, master: BattleMasterBlockEntity, team: String?) {
        val level = master.level as? ServerLevel ?: return
        val spawn = team?.let(master::spawn)
        val spot = spawnSpot(level, spawn ?: master.blockPos)
        player.teleportTo(level, spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, emptySet(), player.yRot, 0f, true)
        // A temporary spawn point there (the real one comes back on leaving).
        if (spawn != null) player.setRespawnPosition(ServerPlayer.RespawnConfig(LevelData.RespawnData.of(level.dimension(), spot, player.yRot, 0f), true), false)
    }

    /** A random free spot with ground under it within three steps of [anchor] (so a team does not spawn stacked). */
    fun spawnSpot(level: ServerLevel, anchor: BlockPos): BlockPos =
        SPAWN_OFFSETS.shuffled(java.util.Random(level.random.nextLong())).map { (dx, dz) -> anchor.offset(dx, 0, dz) }
            .firstOrNull { level.getBlockState(it).isAir && level.getBlockState(it.above()).isAir && !level.getBlockState(it.below()).isAir } ?: anchor.above()

    private val SPAWN_OFFSETS = (-3..3).flatMap { dx -> (-3..3).map { dz -> dx to dz } }.filter { (dx, dz) -> (dx != 0 || dz != 0) && kotlin.math.abs(dx) + kotlin.math.abs(dz) <= 3 }

    /** Back into the fight after waiting for a spawn (the team has a flag post again). */
    fun redeploy(player: ServerPlayer, master: BattleMasterBlockEntity) {
        val data = data(player)?.takeIf { it.waiting } ?: return
        set(player, data.copy(waiting = false))
        player.setGameMode(master.settings.gameType)
        player.health = player.maxHealth
        toSpawn(player, master, data.team)
        tell(player, "message.flansmod.battle.redeployed")
    }

    /** Dead without a flag post to respawn at: watch as a spectator (back once the team has a post again). */
    private fun wait(player: ServerPlayer, master: BattleMasterBlockEntity, data: BattlePlayer) {
        set(player, data.copy(waiting = true, loadout = emptyList()))
        player.setGameMode(GameType.SPECTATOR)
        val level = master.level as? ServerLevel ?: return
        player.teleportTo(level, master.blockPos.x + 0.5, master.blockPos.y + 8.0, master.blockPos.z + 0.5, emptySet(), player.yRot, 30f, true)
        player.connection.send(ClientboundSetTitleTextPacket(Component.translatable("message.flansmod.battle.no_spawn_title").withStyle(ChatFormatting.RED)))
        player.connection.send(ClientboundSetSubtitleTextPacket(Component.translatable("message.flansmod.battle.no_spawn")))
    }

    // ------------------------------------------------------------------------------------------- spectators

    /** Watch the battle from above as a spectator (not as a member); the old game mode and place come back afterwards. */
    fun spectate(player: ServerPlayer, master: BattleMasterBlockEntity) {
        if (data(player)?.inBattle == true) return tell(player, "message.flansmod.battle.leave_first")
        if (player.getAttached(BattleSpectator.ATTACHMENT) != null) return
        val level = master.level as? ServerLevel ?: return
        player.setAttached(BattleSpectator.ATTACHMENT, BattleSpectator(master.globalPos, GlobalPos.of(player.level().dimension(), player.blockPosition()), player.gameMode().serializedName))
        master.state = master.state.copy(watchers = master.state.watchers + (player.uuid.toString() to player.scoreboardName))
        player.setGameMode(GameType.SPECTATOR)
        // Trenches: a commander's view from above their own headquarters, down the lane.
        val view = if (master.settings.mode == BattleMode.TRENCHES) com.flansmod.recoded.trenches.TrenchRules.commanderView(master, data(player)?.team) else null
        if (view != null) player.teleportTo(level, view.first.x, view.first.y, view.first.z, emptySet(), view.second, 40f, true)
        else player.teleportTo(level, master.blockPos.x + 0.5, master.blockPos.y + 12.0, master.blockPos.z + 0.5, emptySet(), player.yRot, 45f, true)
        tell(player, "message.flansmod.battle.spectating", master.settings.name)
        syncStatus(player, master)
    }

    fun stopSpectating(player: ServerPlayer) {
        val spectator = player.getAttached(BattleSpectator.ATTACHMENT) ?: return
        player.removeAttached(BattleSpectator.ATTACHMENT)
        val master = master(player.level().server, spectator.master)
        master?.let { it.state = it.state.copy(watchers = it.state.watchers - player.uuid.toString()) }
        player.setGameMode((GameType.byName(spectator.previousMode, GameType.SURVIVAL) ?: GameType.SURVIVAL))
        player.level().server.getLevel(spectator.returnTo.dimension())?.let { level ->
            val pos = spectator.returnTo.pos()
            player.teleportTo(level, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, emptySet(), player.yRot, player.xRot, true)
        }
        syncStatus(player, if (data(player) != null) master else null)
    }

    // ------------------------------------------------------------------------------------------- start / end

    fun start(master: BattleMasterBlockEntity, by: ServerPlayer?) {
        if (master.running) return
        val bots = master.settings.fillWithBots && master.settings.teamSize > 0
        val trenches = master.settings.mode == BattleMode.TRENCHES
        if (trenches && com.flansmod.recoded.trenches.TrenchLayout.of(master) == null) return by?.let { tell(it, "message.flansmod.trenches.no_field") } ?: Unit
        // Trenches can be left to the computer on both sides (and watched).
        if (master.state.roster.isEmpty() && !bots && !trenches) return by?.let { tell(it, "message.flansmod.battle.no_players") } ?: Unit
        val level = master.level as? ServerLevel ?: return
        val teams = master.settings.teams.map { it.name }.filter { t -> bots || master.members(t).isNotEmpty() }
        master.state = master.state.copy(running = true, session = UUID.randomUUID().toString(), startTick = level.gameTime,
            countdownEnd = level.gameTime + master.settings.countdownSeconds * 20L, scores = master.settings.teams.associate { it.name to 0 },
            stats = emptyMap(), carriers = emptyMap(), bots = emptyMap(), startTeams = teams)
        master.captures.clear()
        // Hills start neutral.
        master.state.posts.values.filter { it.hill && it.team != null }.forEach { master.setPost(it.blockPos, it.copy(team = null)) }
        // Keep the battle ticking (time limit, income, bots) wherever the fighters are.
        level.setChunkForced(master.blockPos.x shr 4, master.blockPos.z shr 4, true)
        master.syncScoreboard()
        if (trenches) {
            // Commanders choose: command (M) from where they are or from above, or enter at their headquarters to fight.
            com.flansmod.recoded.trenches.TrenchRules.start(master)
            online(master).filter { master.teamOf(it.uuid) != null }.forEach { tell(it, "message.flansmod.trenches.started") }
        } else online(master).filter { master.teamOf(it.uuid) != null }.forEach { enter(it, master) }
        broadcast(master, Component.translatable("message.flansmod.battle.started", master.settings.name).withStyle(ChatFormatting.GOLD))
    }

    /** Ends the battle; [winner] (may be null for a draw/abort) is announced. Everyone online gets their inventory back. */
    fun end(master: BattleMasterBlockEntity, winner: String?) {
        if (!master.running) return
        val fighters = onlineFighters(master)
        val level = master.level as? ServerLevel
        BattleRules.removeBots(master)
        if (master.settings.mode == BattleMode.TRENCHES) com.flansmod.recoded.trenches.TrenchRules.end(master)
        val carried = master.state.carriers.values
        master.state = master.state.copy(running = false, carriers = emptyMap())
        carried.forEach { key -> master.state.posts[key]?.let { (level?.getBlockEntity(it.blockPos) as? TeamFlagBlockEntity)?.updateLook() } }
        level?.setChunkForced(master.blockPos.x shr 4, master.blockPos.z shr 4, false)
        val title = winner?.let { Component.translatable("message.flansmod.battle.won", teamName(master, it)) } ?: Component.translatable("message.flansmod.battle.ended")
        for (p in fighters) {
            p.connection.send(ClientboundSetTitleTextPacket(title))
            p.connection.send(ClientboundSetSubtitleTextPacket(Component.literal(master.state.scores.entries.joinToString("  ") { "${it.key} ${it.value}" })))
            exit(p)
        }
        broadcast(master, title)
        syncStatus(master)
    }

    /** [points] for [team]; reaching the score limit wins the battle. */
    fun score(master: BattleMasterBlockEntity, team: String, points: Int = 1) {
        master.state = master.state.copy(scores = master.state.scores + (team to (master.state.scores[team] ?: 0) + points))
        if (master.settings.scoreLimit > 0 && (master.state.scores[team] ?: 0) >= master.settings.scoreLimit) end(master, team)
        else syncStatus(master)
    }

    internal fun stats(master: BattleMasterBlockEntity, key: String, name: String, team: String, bot: Boolean = false, change: (FighterStats) -> FighterStats) {
        val old = master.state.stats[key] ?: FighterStats(name, team, bot = bot)
        master.state = master.state.copy(stats = master.state.stats + (key to change(old.copy(team = team))))
    }

    internal fun statsKey(entity: Entity) = if (entity is SoldierEntity) "bot:${entity.callsign}" else entity.stringUUID
    internal fun statsName(entity: Entity) = if (entity is SoldierEntity) entity.callsign else entity.scoreboardName

    // ------------------------------------------------------------------------------------------- money and shop

    fun addMoney(player: ServerPlayer, amount: Int) {
        val data = data(player) ?: return
        set(player, data.copy(money = (data.money + amount).coerceAtLeast(0)))
    }

    fun buy(player: ServerPlayer, entry: ShopEntry) {
        val data = data(player)?.takeIf { it.inBattle } ?: return
        if (entry.stack.isEmpty) return
        if (data.money < entry.price) {
            player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1f)
            return tell(player, "message.flansmod.shop.too_expensive", entry.price - data.money)
        }
        set(player, data.copy(money = data.money - entry.price))
        player.inventory.placeItemBackInInventory(entry.stack.copy(), net.minecraft.util.Prediction.SERVER_ONLY)
        player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.PLAYERS, 0.5f, 1.2f)
    }

    private val shopCache = HashMap<Pair<String, String>, List<ShopEntry>>()

    init {
        Content.onChanged += { shopCache.clear() }
    }

    /**
     * The shop of [team], in slot order (empty stacks are gaps): arranged in the shop editor, else the settings' text
     * entries, else generated from all loaded content (filtered by the team's faction).
     */
    fun shop(master: BattleMasterBlockEntity, team: String): List<ShopEntry> {
        master.shops[team]?.takeIf { it.isNotEmpty() }?.let { return it }
        val settings = master.settings
        return shopCache.getOrPut(settings.toJson() to team) {
            if (settings.shop.isNotEmpty()) parseShop(master, settings.shop) else defaultShop(settings.team(team)?.faction)
        }
    }

    private fun parseShop(master: BattleMasterBlockEntity, lines: List<String>): List<ShopEntry> {
        val registries = master.level?.registryAccess() ?: return emptyList()
        return lines.mapNotNull { line ->
            val parts = line.trim().split(" ", limit = 3)
            val price = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val count = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            runCatching { ShopEntry(price, ItemParser(registries).parse(StringReader(parts[2])).createItemStack(count)) }
                .onFailure { FlansMod.LOGGER.warn("Invalid shop entry '{}': {}", line, it.message) }.getOrNull()
        }
    }

    private val GUN_PRICES = mapOf("pistol" to 100, "smg" to 250, "shotgun" to 300, "rifle" to 400, "dmr" to 450, "lmg" to 600, "sniper" to 650, "launcher" to 800)
    private val CLOTHING_PRICES = mapOf("head" to 80, "chest" to 150, "legs" to 120, "feet" to 60)

    /** A sensible price for [stack]: what the generated shop asks for it, else 100. */
    fun suggestedPrice(stack: ItemStack): Int =
        (defaultShop(null) + defaultShop(factionOf(stack))).firstOrNull { ItemStack.isSameItemSameComponents(it.stack, stack) }?.price ?: 100

    internal fun factionOf(stack: ItemStack): Identifier? = listOf(
        com.flansmod.recoded.registry.FlansComponents.GUN, com.flansmod.recoded.registry.FlansComponents.CLOTHING,
        com.flansmod.recoded.registry.FlansComponents.VEHICLE, com.flansmod.recoded.registry.FlansComponents.GRENADE, com.flansmod.recoded.registry.FlansComponents.GEAR,
    ).firstNotNullOfOrNull { stack.get(it) }?.let(::factionOf)

    /** Guns (each followed by its ammunition), explosives, uniforms, vehicles, field gear, structures and food. */
    fun defaultShop(faction: Identifier?): List<ShopEntry> = shopCache.getOrPut("default" to faction.toString()) {
        fun ok(id: Identifier) = faction == null || factionOf(id) == faction
        val out = mutableListOf<ShopEntry>()
        val ammoSeen = HashSet<Identifier>()
        val guns = Guns.all.filter { (id, g) -> !g.mounted && ok(id) }.entries.sortedWith(compareBy({ GUN_PRICES[it.value.category] ?: 400 }, { it.value.name }))
        for ((id, gun) in guns) {
            out += ShopEntry(GUN_PRICES[gun.category] ?: 400, GunItem.stackFor(id))
            for (mag in GunItem.acceptedMagazines(id)) {
                val def = Magazines[mag] ?: continue
                if (!def.internal) {
                    if (ammoSeen.add(mag)) out += ShopEntry(if (def.capacity <= 20) 25 else 40, MagazineItem.stackFor(mag, full = true))
                } else AmmoTypes.all.entries.filter { it.value.caliber == def.caliber }.minByOrNull { it.key.path.length }?.let { (ammo, a) ->
                    if (ammoSeen.add(ammo)) out += ShopEntry(if (a.projectile != null) 120 else 30, AmmoItem.stackFor(ammo).copyWithCount(if (a.projectile != null) 2 else 16))
                }
            }
        }
        for ((id, g) in Grenades.all.entries.sortedBy { it.value.name }) {
            if (!g.throwable || !ok(id)) continue
            val price = when (g.mine?.trigger) { null -> 75; GrenadeDefinition.Mine.Trigger.VEHICLE -> 200; else -> 100 }
            out += ShopEntry(price, GrenadeItem.stackFor(id))
        }
        Clothing.all.entries.filter { ok(it.key) && (faction == null || it.value.faction == faction) }.sortedBy { it.value.asset.toString() }
            .forEach { (id, c) -> out += ShopEntry(CLOTHING_PRICES[c.slot] ?: 100, ClothingItem.stackFor(id)) }
        com.flansmod.recoded.gun.Gear.all.entries.filter { ok(it.key) }.sortedBy { it.value.type.ordinal }.forEach { (id, g) ->
            val price = when (g.type) {
                com.flansmod.recoded.gear.GearType.BACKPACK -> 50 + g.slots * 8
                com.flansmod.recoded.gear.GearType.POUCH -> 80
                com.flansmod.recoded.gear.GearType.MEDICAL -> 20 + g.effects.size * 25
                com.flansmod.recoded.gear.GearType.BINOCULARS -> 100
                com.flansmod.recoded.gear.GearType.PLATE -> (g.armor * 40 + g.toughness * 30).toInt()
                com.flansmod.recoded.gear.GearType.PARACHUTE -> 150
                com.flansmod.recoded.gear.GearType.MAP, com.flansmod.recoded.gear.GearType.COMPASS -> 40
                com.flansmod.recoded.gear.GearType.FLASHLIGHT -> 60
            }
            out += ShopEntry(price, com.flansmod.recoded.gear.GearItem.stackFor(id).copyWithCount(if (g.maxStack >= 4) 2 else 1))
        }
        Vehicles.all.entries.filter { ok(it.key) }.sortedBy { it.value.name }.forEach { (id, v) ->
            out += ShopEntry(when (v.type) { VehicleType.CAR -> 1500; VehicleType.TANK -> 5000; VehicleType.STATIC -> 900; VehicleType.HELICOPTER -> 4500; VehicleType.PLANE -> 4000 }, VehicleItem.stackFor(id))
        }
        Structures.all.entries.sortedBy { it.value.name }.forEach { (id, s) -> out += ShopEntry(s.price, StructureItem.stackFor(id)) }
        out += ShopEntry(100, FuelCanItem.stackFor(FuelStack(FuelTypes.PETROL, FuelCanItem.CAPACITY)))
        out += ShopEntry(100, FuelCanItem.stackFor(FuelStack(FuelTypes.DIESEL, FuelCanItem.CAPACITY)))
        out += ShopEntry(150, ItemStack(FlansItems.WRENCH))
        out += ShopEntry(40, ItemStack(Fortifications.SANDBAGS, 16))
        out += ShopEntry(60, ItemStack(Fortifications.BARBED_WIRE, 8))
        out += ShopEntry(20, ItemStack(Items.BREAD, 8))
        out += ShopEntry(40, ItemStack(Items.COOKED_BEEF, 8))
        out += ShopEntry(150, ItemStack(Items.GOLDEN_APPLE))
        out
    }

    // ------------------------------------------------------------------------------------------- status sync

    /** Battle status for the HUD and battle menu of every online member and spectator. */
    fun syncStatus(master: BattleMasterBlockEntity) = online(master).forEach { syncStatus(it, master) }

    fun syncStatus(player: ServerPlayer, master: BattleMasterBlockEntity?) {
        if (!ServerPlayNetworking.canSend(player, BattleStatusPayload.TYPE)) return
        val data = data(player)?.takeIf { master != null && it.master == master.globalPos }
        val watching = master != null && player.getAttached(BattleSpectator.ATTACHMENT)?.master == master.globalPos
        val status = if (master == null || (data == null && !watching)) null else {
            val time = master.level?.gameTime ?: 0
            val myTeam = data?.team ?: ""
            BattleStatus(
                master.settings.name, master.running, master.secondsLeft(), myTeam, data?.inBattle == true, data?.money ?: 0,
                master.settings.teams.map {
                    BattleStatus.Team(it.name, it.teamColor.rgb(), master.state.scores[it.name] ?: 0, master.settings.friendly(it.name, myTeam),
                        master.spawn(it.name) != null)
                },
                master.settings.scoreLimit, master.settings.mode,
                countdown = if (master.inCountdown) ((master.state.countdownEnd - time + 19) / 20).toInt() else 0,
                posts = master.state.posts.entries.sortedBy { it.value.label }.map { (key, post) ->
                    val capture = master.captures[key]
                    BattleStatus.PostView(post.label, post.pos, post.team, post.hill, post.locked, master.carried(key),
                        capture?.team, capture?.let { it.ticks / (master.settings.captureSeconds * 20f).coerceAtLeast(1f) } ?: 0f)
                },
                fighters = master.state.stats.values.sortedWith(compareBy({ it.team }, { -it.kills }, { it.deaths })).map {
                    BattleStatus.Row(it.name, it.team, it.kills, it.deaths, it.captures, it.bot)
                },
                border = master.borderBox()?.let { listOf(it.minX.toInt(), it.minZ.toInt(), it.maxX.toInt(), it.maxZ.toInt()) },
                waiting = data?.waiting == true, spectating = watching,
                carrying = master.state.carriers[player.uuid.toString()]?.let { master.state.posts[it]?.label },
                nearFlag = data?.inBattle == true && master.posts(myTeam).any { it.blockPos.closerToCenterThan(player.position(), 6.0) },
                trench = if (master.running && master.settings.mode == BattleMode.TRENCHES) (master.level as? ServerLevel)?.let {
                    com.flansmod.recoded.trenches.TrenchRules.view(master, it, player, data?.team)
                } else null,
            )
        }
        ServerPlayNetworking.send(player, BattleStatusPayload(status))
    }

    // ------------------------------------------------------------------------------------------- events

    fun init() {
        // Attachment types register when first touched.
        BattlePlayer.ATTACHMENT
        BattleSpectator.ATTACHMENT
        PayloadTypeRegistry.clientboundPlay().register(BattleStatusPayload.TYPE, BattleStatusPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(BattleSettingsPayload.TYPE, BattleSettingsPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(BattleActionPayload.TYPE, BattleActionPayload.CODEC)
        ShopEditorMenu.init()
        com.flansmod.recoded.trenches.TrenchRules.init()
        ServerPlayNetworking.registerGlobalReceiver(BattleSettingsPayload.TYPE) { payload, context ->
            val player = context.player()
            val master = player.level().getBlockEntity(payload.pos) as? BattleMasterBlockEntity ?: return@registerGlobalReceiver
            if (!master.canManage(player) || master.running || !payload.pos.closerToCenterThan(player.position(), 8.0)) return@registerGlobalReceiver
            master.state = master.state.copy(settings = BattleSettings.fromJson(payload.json))
            // Members of teams that no longer exist leave; posts of such teams become free.
            master.state.roster.filterValues { master.settings.team(it.team) == null }.keys
                .mapNotNull { player.level().server.playerList.getPlayer(UUID.fromString(it)) }.forEach(::quit)
            master.state.posts.values.filter { it.team != null && master.settings.team(it.team) == null }.forEach { master.setPost(it.blockPos, it.copy(team = null)) }
            master.shops = master.shops.filterKeys { master.settings.team(it) != null }
            master.syncScoreboard()
            player.openMenu(master)
        }
        ServerPlayNetworking.registerGlobalReceiver(BattleActionPayload.TYPE) { payload, context ->
            val player = context.player()
            when (payload.action) {
                BattleActionPayload.LEAVE -> activeBattle(player)?.let { (d, m) ->
                    // Leaving (and getting the inventory back) only at your own flag post - or while waiting for a spawn.
                    if (d.waiting || m.posts(d.team).any { it.blockPos.closerToCenterThan(player.position(), 6.0) }) exit(player)
                    else tell(player, "message.flansmod.battle.leave_at_flag")
                }
                BattleActionPayload.STOP_SPECTATING -> stopSpectating(player)
                BattleActionPayload.QUIT_TEAM -> if (data(player)?.inBattle == false) quit(player)
            }
        }

        // Friends do not hurt each other (unless friendly fire is on); nobody is hurt during the countdown.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            val (victimTeam, master) = fighter(entity) ?: return@register true
            if (master.inCountdown) return@register false
            val attacker = attacker(source)
            if (attacker == null || attacker == entity) return@register true
            val (attackerTeam, m) = fighter(attacker) ?: return@register true
            m != master || master.settings.friendlyFire || !master.settings.friendly(attackerTeam, victimTeam)
        }

        // Deaths in battle: kill/death stats, score and money for the enemy who killed, the loadout is lost (or kept).
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, source, _ ->
            val (victimTeam, master) = fighter(entity) ?: return@register true
            if (entity is ServerPlayer) {
                val v = data(entity)!!
                val inventory = entity.inventory
                val loadout = if (master.settings.keepLoadout) (0 until inventory.containerSize).map { inventory.getItem(it).copy() } +
                    GearSlots.back(entity).copy() else emptyList()
                inventory.clearContent()
                GearSlots.setBack(entity, ItemStack.EMPTY)
                set(entity, v.copy(loadout = loadout))
            }
            BattleRules.dropCarried(master, entity.uuid.toString())
            stats(master, statsKey(entity), statsName(entity), victimTeam, entity is SoldierEntity) { it.copy(deaths = it.deaths + 1) }
            val killer = attacker(source)
            val (killerTeam, km) = fighter(killer) ?: (null to null)
            if (killer != null && killerTeam != null && km == master && killer != entity && !master.settings.friendly(killerTeam, victimTeam)) {
                stats(master, statsKey(killer), statsName(killer), killerTeam, killer is SoldierEntity) { it.copy(kills = it.kills + 1) }
                if (killer is ServerPlayer) addMoney(killer, master.settings.killReward)
                if (master.settings.mode == BattleMode.TRENCHES) com.flansmod.recoded.trenches.TrenchRules.killed(master, killerTeam)
                broadcast(master, Component.translatable("message.flansmod.battle.kill",
                    killer.displayName!!.copy().withColor(master.settings.team(killerTeam)?.teamColor?.rgb() ?: 0xFFFFFF),
                    entity.displayName!!.copy().withColor(master.settings.team(victimTeam)?.teamColor?.rgb() ?: 0xFFFFFF)))
                if (master.settings.mode.killsScore) score(master, killerTeam) else syncStatus(master)
            }
            true
        }

        // Respawn at a flag post, briefly protected (keep-loadout battles hand the gear back) - or wait as a spectator.
        ServerPlayerEvents.AFTER_RESPAWN.register { _, player, alive ->
            if (alive) return@register
            val (data, master) = activeBattle(player) ?: return@register exit(player) // the battle ended while dead
            if (master.spawn(data.team) == null) return@register wait(player, master, data)
            toSpawn(player, master, data.team)
            data.loadout.forEachIndexed { i, stack -> if (i < player.inventory.containerSize) player.inventory.setItem(i, stack) }
            data.loadout.getOrNull(player.inventory.containerSize)?.let { GearSlots.setBack(player, it) }
            set(player, data.copy(loadout = emptyList()))
            if (master.settings.respawnProtection > 0) player.addEffect(MobEffectInstance(MobEffects.RESISTANCE, master.settings.respawnProtection * 20, 4))
        }

        // Back after the battle ended (or its Battle Master is gone): inventory, game mode and spawn come back.
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            player.getAttached(BattleSpectator.ATTACHMENT)?.let { s -> if (master(player.level().server, s.master) == null) stopSpectating(player) }
            val data = data(player) ?: return@register
            val master = master(player.level().server, data.master)
            if (master == null) {
                if (data.inBattle) exit(player)
                set(player, null)
                return@register
            }
            if (data.inBattle && (!master.running || master.state.session != data.session)) exit(player)
            syncStatus(player, master)
        }
    }
}

@Serializable
data class BattleStatus(
    val name: String, val running: Boolean, val secondsLeft: Int, val team: String, val inBattle: Boolean, val money: Int,
    val teams: List<Team>, val scoreLimit: Int,
    val mode: BattleMode = BattleMode.TEAM_DEATHMATCH,
    /** Seconds until the fighting starts (0 = fighting). */
    val countdown: Int = 0,
    val posts: List<PostView> = emptyList(),
    val fighters: List<Row> = emptyList(),
    /** Battle area: min x, min z, max x, max z. */
    val border: List<Int>? = null,
    /** Dead without a flag post: spectating until the team has one again. */
    val waiting: Boolean = false,
    /** Watching from the Battle Master. */
    val spectating: Boolean = false,
    /** Label of the enemy post whose flag you carry. */
    val carrying: String? = null,
    /** Close enough to your own flag post to leave the battle. */
    val nearFlag: Boolean = false,
    /** Trenches mode: the lane, funds, units and orders for the command screen. */
    val trench: com.flansmod.recoded.trenches.TrenchView? = null,
) {
    @Serializable
    data class Team(val name: String, val rgb: Int, val score: Int, val friendly: Boolean, val hasSpawn: Boolean = true)

    @Serializable
    data class PostView(val label: String, val pos: List<Int>, val team: String?, val hill: Boolean, val locked: Boolean, val stolen: Boolean,
                        val capturingTeam: String?, val progress: Float)

    @Serializable
    data class Row(val name: String, val team: String, val kills: Int, val deaths: Int, val captures: Int, val bot: Boolean)
}

/** Server → member/spectator: the battle status for the HUD and the battle menu; null status = not in a battle (any more). */
data class BattleStatusPayload(val status: BattleStatus?) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BattleStatusPayload>(FlansMod.id("battle_status"))
        val CODEC: StreamCodec<FriendlyByteBuf, BattleStatusPayload> = ByteBufCodecs.stringUtf8(1 shl 18).map(
            { BattleStatusPayload(if (it.isEmpty()) null else Content.JSON.decodeFromString(BattleStatus.serializer(), it)) },
            { it.status?.let { s -> Content.JSON.encodeToString(BattleStatus.serializer(), s) } ?: "" },
        ).cast()
    }
}

/** Client → server: new settings for the Battle Master at [pos] (from its Cloth Config screen). */
data class BattleSettingsPayload(val pos: BlockPos, val json: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BattleSettingsPayload>(FlansMod.id("battle_settings"))
        val CODEC: StreamCodec<FriendlyByteBuf, BattleSettingsPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BattleSettingsPayload::pos, ByteBufCodecs.stringUtf8(1 shl 16), BattleSettingsPayload::json, ::BattleSettingsPayload,
        ).cast()
    }
}

/** Client → server: a button of the in-battle menu ([LEAVE], [STOP_SPECTATING], [QUIT_TEAM]). */
data class BattleActionPayload(val action: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val LEAVE = 0
        const val STOP_SPECTATING = 1
        const val QUIT_TEAM = 2
        val TYPE = CustomPacketPayload.Type<BattleActionPayload>(FlansMod.id("battle_action"))
        val CODEC: StreamCodec<FriendlyByteBuf, BattleActionPayload> = ByteBufCodecs.VAR_INT.map(::BattleActionPayload, BattleActionPayload::action).cast()
    }
}
