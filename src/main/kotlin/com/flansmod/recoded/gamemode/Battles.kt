package com.flansmod.recoded.gamemode

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.fortification.Fortifications
import com.flansmod.recoded.fuel.FuelCanItem
import com.flansmod.recoded.fuel.FuelStack
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Clothing
import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.gun.FuelTypes
import com.flansmod.recoded.gun.GrenadeDefinition
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.VehicleType
import com.flansmod.recoded.gun.Vehicles
import com.flansmod.recoded.gun.factionOf
import com.flansmod.recoded.item.AmmoItem
import com.flansmod.recoded.item.ClothingItem
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.item.VehicleItem
import com.flansmod.recoded.registry.FlansItems
import com.mojang.brigadier.StringReader
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
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import java.util.UUID

/** Something the team shop sells: [stack] for [price] battle money. */
data class ShopEntry(val price: Int, val stack: ItemStack) {
    /** The shop slot's look: the item with its price as lore. */
    fun display(): ItemStack = stack.copy().apply {
        set(DataComponents.LORE, ItemLore(listOf(Component.translatable("gui.flansmod.shop.price", price).withStyle(ChatFormatting.GOLD))))
    }
}

/**
 * Battles: teams, war and money in a normal survival world. Rules:
 * - Join a team at the Battle Master; entering the running battle stores your inventory ([BattlePlayer.stash]) and puts
 *   you at your team's flag post with the start money; leaving at your flag post (or the battle ending) gives it back.
 * - Killing an enemy (not your team, not allied) earns money and a point for your team; first to the score limit, or
 *   the best team when time runs out, wins. Friends cannot hurt each other unless friendly fire is on.
 * - You respawn at your flag post with a few seconds of protection; what you carried is lost (or kept, per setting).
 */
object Battles {
    fun data(player: ServerPlayer): BattlePlayer? = player.getAttached(BattlePlayer.ATTACHMENT)
    private fun set(player: ServerPlayer, data: BattlePlayer?) {
        if (data == null) player.removeAttached(BattlePlayer.ATTACHMENT) else player.setAttached(BattlePlayer.ATTACHMENT, data)
    }

    fun master(server: MinecraftServer, pos: GlobalPos): BattleMasterBlockEntity? =
        server.getLevel(pos.dimension())?.getBlockEntity(pos.pos()) as? BattleMasterBlockEntity

    /** The player's battle, if they are a member and it is the running session they entered. */
    private fun activeBattle(player: ServerPlayer): Pair<BattlePlayer, BattleMasterBlockEntity>? {
        val data = data(player)?.takeIf { it.inBattle } ?: return null
        val master = master(player.level().server, data.master)?.takeIf { it.running && it.state.session == data.session } ?: return null
        return data to master
    }

    fun onlineFighters(master: BattleMasterBlockEntity): List<ServerPlayer> =
        online(master).filter { p -> data(p)?.let { it.inBattle && it.session == master.state.session } == true }

    private fun online(master: BattleMasterBlockEntity): List<ServerPlayer> {
        val server = master.level?.server ?: return emptyList()
        return master.state.roster.keys.mapNotNull { server.playerList.getPlayer(UUID.fromString(it)) }
    }

    private fun tell(player: ServerPlayer, key: String, vararg args: Any) = player.sendSystemMessage(Component.translatable(key, *args))
    private fun broadcast(master: BattleMasterBlockEntity, message: Component) = online(master).forEach { it.sendSystemMessage(message) }

    private fun teamName(master: BattleMasterBlockEntity, team: String): Component =
        Component.literal(team).withColor(master.settings.team(team)?.teamColor?.rgb() ?: 0xFFFFFF)

    // ------------------------------------------------------------------------------------------- membership

    fun join(player: ServerPlayer, master: BattleMasterBlockEntity, team: String) {
        val current = data(player)
        if (current?.inBattle == true) return tell(player, "message.flansmod.battle.leave_first")
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

    /** Into the running battle: the inventory stays behind, start money, off to the team's flag post. */
    fun enter(player: ServerPlayer, master: BattleMasterBlockEntity) {
        val data = data(player)?.takeIf { it.master == master.globalPos } ?: return
        if (!master.running) return tell(player, "message.flansmod.battle.not_running")
        if (data.inBattle && data.session == master.state.session) return
        val inventory = player.inventory
        val stash = (0 until inventory.containerSize).map { inventory.getItem(it).copy() }
        inventory.clearContent()
        set(player, data.copy(inBattle = true, session = master.state.session, money = master.settings.startMoney, stash = stash, loadout = emptyList()))
        player.health = player.maxHealth
        player.foodData.foodLevel = 20
        toSpawn(player, master, data.team)
        player.connection.send(ClientboundSetTitleTextPacket(Component.literal(master.settings.name).withStyle(ChatFormatting.GOLD)))
        player.connection.send(ClientboundSetSubtitleTextPacket(Component.translatable("message.flansmod.battle.entered", teamName(master, data.team))))
        syncStatus(master)
    }

    /** Out of the battle: battle items are gone, the stored inventory comes back. Membership stays. */
    fun exit(player: ServerPlayer) {
        val data = data(player)?.takeIf { it.inBattle } ?: return
        val inventory = player.inventory
        inventory.clearContent()
        data.stash.forEachIndexed { i, stack -> if (i < inventory.containerSize) inventory.setItem(i, stack) }
        set(player, data.copy(inBattle = false, session = "", money = 0, stash = emptyList(), loadout = emptyList()))
        tell(player, "message.flansmod.battle.left")
        master(player.level().server, data.master)?.let { syncStatus(it) }
    }

    /** Next to the team's flag post (or the Battle Master if the team has none). */
    private fun toSpawn(player: ServerPlayer, master: BattleMasterBlockEntity, team: String) {
        val level = master.level as? net.minecraft.server.level.ServerLevel ?: return
        val anchor = master.flag(team) ?: master.blockPos
        val spot = listOf(anchor.east(), anchor.west(), anchor.south(), anchor.north(), anchor.east(2), anchor.west(2))
            .firstOrNull { level.getBlockState(it).isAir && level.getBlockState(it.above()).isAir } ?: anchor.above()
        player.teleportTo(level, spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5, emptySet(), player.yRot, 0f, true)
    }

    // ------------------------------------------------------------------------------------------- start / end

    fun start(master: BattleMasterBlockEntity, by: ServerPlayer?) {
        if (master.running) return
        if (master.state.roster.isEmpty()) return by?.let { tell(it, "message.flansmod.battle.no_players") } ?: Unit
        val level = master.level as? net.minecraft.server.level.ServerLevel ?: return
        master.state = master.state.copy(running = true, session = UUID.randomUUID().toString(), startTick = level.gameTime,
            scores = master.settings.teams.associate { it.name to 0 })
        // Keep the battle ticking (time limit, income) wherever the fighters are.
        level.setChunkForced(master.blockPos.x shr 4, master.blockPos.z shr 4, true)
        master.syncScoreboard()
        online(master).forEach { enter(it, master) }
        broadcast(master, Component.translatable("message.flansmod.battle.started", master.settings.name).withStyle(ChatFormatting.GOLD))
    }

    /** Ends the battle; [winner] (may be null for a draw/abort) is announced. Everyone online gets their inventory back. */
    fun end(master: BattleMasterBlockEntity, winner: String?) {
        if (!master.running) return
        val fighters = onlineFighters(master)
        master.state = master.state.copy(running = false)
        (master.level as? net.minecraft.server.level.ServerLevel)?.setChunkForced(master.blockPos.x shr 4, master.blockPos.z shr 4, false)
        val title = winner?.let { Component.translatable("message.flansmod.battle.won", teamName(master, it)) } ?: Component.translatable("message.flansmod.battle.ended")
        for (p in fighters) {
            p.connection.send(ClientboundSetTitleTextPacket(title))
            p.connection.send(ClientboundSetSubtitleTextPacket(Component.literal(master.state.scores.entries.joinToString("  ") { "${it.key} ${it.value}" })))
            exit(p)
        }
        broadcast(master, title)
        syncStatus(master)
    }

    // ------------------------------------------------------------------------------------------- money and shop

    fun addMoney(player: ServerPlayer, amount: Int) {
        val data = data(player) ?: return
        set(player, data.copy(money = (data.money + amount).coerceAtLeast(0)))
    }

    fun buy(player: ServerPlayer, entry: ShopEntry) {
        val data = data(player)?.takeIf { it.inBattle } ?: return
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

    /** The shop of [team]: the configured entries, or one generated from all loaded content (filtered by the team's faction). */
    fun shop(master: BattleMasterBlockEntity, team: String): List<ShopEntry> {
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

    /** Guns (each followed by its ammunition), explosives, uniforms, vehicles, field gear and food. */
    private fun defaultShop(faction: Identifier?): List<ShopEntry> {
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
        Vehicles.all.entries.filter { ok(it.key) }.sortedBy { it.value.name }.forEach { (id, v) ->
            out += ShopEntry(when (v.type) { VehicleType.CAR -> 1500; VehicleType.TANK -> 5000; VehicleType.STATIC -> 900 }, VehicleItem.stackFor(id))
        }
        out += ShopEntry(100, FuelCanItem.stackFor(FuelStack(FuelTypes.PETROL, FuelCanItem.CAPACITY)))
        out += ShopEntry(100, FuelCanItem.stackFor(FuelStack(FuelTypes.DIESEL, FuelCanItem.CAPACITY)))
        out += ShopEntry(150, ItemStack(FlansItems.WRENCH))
        out += ShopEntry(40, ItemStack(Fortifications.SANDBAGS, 16))
        out += ShopEntry(60, ItemStack(Fortifications.BARBED_WIRE, 8))
        out += ShopEntry(20, ItemStack(Items.BREAD, 8))
        out += ShopEntry(40, ItemStack(Items.COOKED_BEEF, 8))
        out += ShopEntry(150, ItemStack(Items.GOLDEN_APPLE))
        return out
    }

    // ------------------------------------------------------------------------------------------- status sync

    /** Battle status for the HUD of every online member. */
    fun syncStatus(master: BattleMasterBlockEntity) = online(master).forEach { syncStatus(it, master) }

    fun syncStatus(player: ServerPlayer, master: BattleMasterBlockEntity?) {
        if (!ServerPlayNetworking.canSend(player, BattleStatusPayload.TYPE)) return
        val data = data(player)
        val status = if (master == null || data == null) null else BattleStatus(
            master.settings.name, master.running, master.secondsLeft(), data.team, data.inBattle, data.money,
            master.settings.teams.map { BattleStatus.Team(it.name, it.teamColor.rgb(), master.state.scores[it.name] ?: 0, master.settings.friendly(it.name, data.team)) },
            master.settings.scoreLimit,
        )
        ServerPlayNetworking.send(player, BattleStatusPayload(status))
    }

    // ------------------------------------------------------------------------------------------- events

    fun init() {
        PayloadTypeRegistry.clientboundPlay().register(BattleStatusPayload.TYPE, BattleStatusPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(BattleSettingsPayload.TYPE, BattleSettingsPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(BattleSettingsPayload.TYPE) { payload, context ->
            val player = context.player()
            val master = player.level().getBlockEntity(payload.pos) as? BattleMasterBlockEntity ?: return@registerGlobalReceiver
            if (!master.canManage(player) || master.running || !payload.pos.closerToCenterThan(player.position(), 8.0)) return@registerGlobalReceiver
            master.state = master.state.copy(settings = BattleSettings.fromJson(payload.json))
            // Members of teams that no longer exist leave.
            master.state.roster.filterValues { master.settings.team(it.team) == null }.keys
                .mapNotNull { player.level().server.playerList.getPlayer(UUID.fromString(it)) }.forEach(::quit)
            master.syncScoreboard()
            player.openMenu(master)
        }

        // Friends do not hurt each other (unless friendly fire is on).
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            val victim = entity as? ServerPlayer ?: return@register true
            val attacker = (source.entity as? ServerPlayer) ?: ((source.directEntity as? Projectile)?.owner as? ServerPlayer) ?: return@register true
            if (attacker == victim) return@register true
            val (v, master) = activeBattle(victim) ?: return@register true
            val a = activeBattle(attacker)?.first?.takeIf { it.master == v.master } ?: return@register true
            master.settings.friendlyFire || !master.settings.friendly(a.team, v.team)
        }

        // Deaths in battle: score and money for the enemy who killed, the loadout is lost (or kept for the respawn).
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, source, _ ->
            val victim = entity as? ServerPlayer ?: return@register true
            val (v, master) = activeBattle(victim) ?: return@register true
            val inventory = victim.inventory
            val loadout = if (master.settings.keepLoadout) (0 until inventory.containerSize).map { inventory.getItem(it).copy() } else emptyList()
            inventory.clearContent()
            set(victim, v.copy(loadout = loadout))
            val killer = (source.entity as? ServerPlayer) ?: ((source.directEntity as? Projectile)?.owner as? ServerPlayer)
            val k = killer?.let(::activeBattle)?.first?.takeIf { it.master == v.master }
            if (killer != null && k != null && killer != victim && !master.settings.friendly(k.team, v.team)) {
                addMoney(killer, master.settings.killReward)
                master.state = master.state.copy(scores = master.state.scores + (k.team to (master.state.scores[k.team] ?: 0) + 1))
                broadcast(master, Component.translatable("message.flansmod.battle.kill", killer.displayName.copy().withColor(
                    master.settings.team(k.team)?.teamColor?.rgb() ?: 0xFFFFFF), victim.displayName.copy().withColor(master.settings.team(v.team)?.teamColor?.rgb() ?: 0xFFFFFF)))
                if (master.settings.scoreLimit > 0 && (master.state.scores[k.team] ?: 0) >= master.settings.scoreLimit) end(master, k.team)
                else syncStatus(master)
            }
            true
        }

        // Respawn at the flag post, briefly protected; keep-loadout battles hand the gear back.
        ServerPlayerEvents.AFTER_RESPAWN.register { _, player, alive ->
            if (alive) return@register
            val (data, master) = activeBattle(player) ?: return@register
            toSpawn(player, master, data.team)
            data.loadout.forEachIndexed { i, stack -> if (i < player.inventory.containerSize) player.inventory.setItem(i, stack) }
            set(player, data.copy(loadout = emptyList()))
            if (master.settings.respawnProtection > 0) player.addEffect(MobEffectInstance(MobEffects.RESISTANCE, master.settings.respawnProtection * 20, 4))
        }

        // Back after the battle ended (or its Battle Master is gone): the inventory comes back.
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
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
) {
    @Serializable
    data class Team(val name: String, val rgb: Int, val score: Int, val friendly: Boolean)
}

/** Server → member: the battle status for the HUD; null status = not in a battle (any more). */
data class BattleStatusPayload(val status: BattleStatus?) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<BattleStatusPayload>(FlansMod.id("battle_status"))
        val CODEC: StreamCodec<FriendlyByteBuf, BattleStatusPayload> = ByteBufCodecs.stringUtf8(1 shl 16).map(
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
