package com.flansmod.recoded.gamemode

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * What a running battle does every tick: the start countdown, taking flag posts (king of the hill, conquest), stealing
 * and delivering flags (capture the flag), points over time, the border, bots, redeploying players who waited for a
 * spawn, eliminated teams, income and the time limit.
 */
object BattleRules {
    /** Fighters within this many blocks of a post take it. */
    const val CAPTURE_RADIUS = 4.0
    /** Walking this close to an enemy flag post steals its flag; to your own delivers it. */
    const val FLAG_REACH = 1.8

    /** When each team may deploy its next bot (not saved). */
    private val nextBot = HashMap<Pair<BattleMasterBlockEntity, String>, Long>()
    /** When each bot entity was last found (by UUID; not saved). */
    private val lastSeen = HashMap<Pair<BattleMasterBlockEntity, String>, Long>()
    private const val LOST_TICKS = 1200L

    fun tick(master: BattleMasterBlockEntity, level: ServerLevel) {
        val time = level.gameTime
        val settings = master.settings
        if (master.inCountdown) {
            val left = master.state.countdownEnd - time
            if (left % 20 == 0L) title(master, Component.literal((left / 20).toString()).withStyle(ChatFormatting.GOLD), null, SoundEvents.NOTE_BLOCK_HAT.value())
            if (time % 20 == 0L) {
                maintainBots(master, level)
                Battles.syncStatus(master)
            }
            return
        }
        if (time == master.state.countdownEnd) {
            title(master, Component.translatable("message.flansmod.battle.fight").withStyle(ChatFormatting.RED),
                Component.translatable("gui.flansmod.battle.mode.${settings.mode.name.lowercase()}"), SoundEvents.RAID_HORN.value())
        }
        val elapsed = time - master.state.countdownEnd

        when (settings.mode) {
            BattleMode.KING_OF_THE_HILL, BattleMode.CONQUEST -> captures(master, level)
            BattleMode.CAPTURE_THE_FLAG -> if (time % 5 == 0L) flags(master, level)
            BattleMode.TEAM_DEATHMATCH -> Unit
            BattleMode.TRENCHES -> {
                com.flansmod.recoded.trenches.TrenchRules.tick(master, level)
                if (!master.running) return
            }
        }
        if (time % 10 == 0L) border(master, level)
        if (time % 20 == 0L) {
            maintainBots(master, level)
            Battles.onlineFighters(master).filter { p -> Battles.data(p)?.waiting == true && master.spawn(Battles.data(p)!!.team) != null }
                .forEach { Battles.redeploy(it, master) }
            if (eliminations(master, level)) return
        }
        if (!master.running) return

        // Held posts score over time.
        val pointTicks = settings.pointSeconds.coerceAtLeast(1) * 20L
        if (elapsed > 0 && elapsed % pointTicks == 0L && (settings.mode == BattleMode.KING_OF_THE_HILL || settings.mode == BattleMode.CONQUEST)) {
            val held = master.state.posts.values.filter { it.team != null && (settings.mode == BattleMode.CONQUEST || it.hill) }.groupingBy { it.team!! }.eachCount()
            for ((team, count) in held) {
                Battles.score(master, team, count)
                if (!master.running) return
            }
        }
        if (settings.incomePerMinute > 0 && elapsed > 0 && elapsed % 1200 == 0L) {
            Battles.onlineFighters(master).forEach { Battles.addMoney(it, settings.incomePerMinute) }
        }
        if (time % 20 == 0L) Battles.syncStatus(master)
        if (master.secondsLeft() == 0) Battles.end(master, master.state.scores.maxByOrNull { it.value }?.key)
    }

    private fun title(master: BattleMasterBlockEntity, title: Component, subtitle: Component?, sound: net.minecraft.sounds.SoundEvent) {
        for (p in Battles.onlineFighters(master)) {
            p.connection.send(ClientboundSetTitleTextPacket(title))
            subtitle?.let { p.connection.send(ClientboundSetSubtitleTextPacket(it)) }
            p.level().playSound(null, p.blockPosition(), sound, SoundSource.PLAYERS, 1f, 1f)
        }
    }

    // ------------------------------------------------------------------------------------------- fighters

    /** Living fighters (players not waiting for a spawn, bots) of [master] in [level] within [radius] of [center]. */
    fun fightersNear(master: BattleMasterBlockEntity, level: ServerLevel, center: Vec3, radius: Double): List<Pair<LivingEntity, String>> {
        val players = Battles.onlineFighters(master).filter { p ->
            p.level() == level && p.isAlive && !p.isSpectator && Battles.data(p)?.waiting == false && p.position().closerThan(center, radius)
        }.map { it to Battles.data(it)!!.team }
        val bots = level.getEntitiesOfClass(SoldierEntity::class.java, AABB.ofSize(center, radius * 2, radius * 2, radius * 2)) {
            it.isAlive && it.position().closerThan(center, radius) && it.battle() == master
        }.map { it to it.team }
        return players + bots
    }

    fun bots(master: BattleMasterBlockEntity, level: ServerLevel, team: String? = null): List<SoldierEntity> =
        master.state.bots.filter { team == null || it.value == team }.keys.mapNotNull { level.getEntity(UUID.fromString(it)) as? SoldierEntity }

    // ------------------------------------------------------------------------------------------- king of the hill, conquest

    /** A team alone at a post (allies together count as one side) takes it over after the capture time. */
    private fun captures(master: BattleMasterBlockEntity, level: ServerLevel) {
        val settings = master.settings
        val needed = settings.captureSeconds.coerceAtLeast(1) * 20
        for ((key, post) in master.state.posts) {
            val capturable = !post.locked && (settings.mode == BattleMode.CONQUEST || post.hill)
            if (!capturable) {
                master.captures.remove(key)
                continue
            }
            val present = fightersNear(master, level, Vec3.atBottomCenterOf(post.blockPos), CAPTURE_RADIUS)
            val teams = present.map { it.second }.toSet()
            val capture = master.captures[key]
            val contested = teams.any { a -> teams.any { b -> !settings.friendly(a, b) } }
            if (teams.isEmpty() || contested) {
                if (teams.isEmpty() && capture != null && --capture.ticks <= 0) master.captures.remove(key)
                continue
            }
            val team = present.groupingBy { it.second }.eachCount().maxBy { it.value }.key
            if (post.team != null && settings.friendly(post.team, team)) {
                // Defenders push an enemy capture back.
                if (capture != null && (capture.ticks - 2).also { capture.ticks = it } <= 0) master.captures.remove(key)
                continue
            }
            val current = capture?.takeIf { it.team == team } ?: BattleMasterBlockEntity.Capture(team, 0).also { master.captures[key] = it }
            current.ticks += present.count { it.second == team }.coerceAtMost(3) // more people capture faster
            if (current.ticks < needed) continue
            master.captures.remove(key)
            master.setPost(post.blockPos, post.copy(team = team))
            present.filter { it.second == team }.forEach { (e, t) -> Battles.stats(master, Battles.statsKey(e), Battles.statsName(e), t, e is SoldierEntity) { it.copy(captures = it.captures + 1) } }
            Battles.broadcast(master, Component.translatable("message.flansmod.battle.captured", Battles.teamName(master, team), post.label.ifEmpty { key }))
            level.playSound(null, post.blockPos, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1f, 0.8f)
        }
    }

    // ------------------------------------------------------------------------------------------- capture the flag

    /** Enemies walking up to a post steal its flag; carrying it to your own post (whose flag is home) scores. */
    private fun flags(master: BattleMasterBlockEntity, level: ServerLevel) {
        val settings = master.settings
        // Carriers who are gone (offline, dead, out of the battle) drop the flag back home.
        for ((carrier, _) in master.state.carriers) {
            val entity = carrierEntity(level, carrier)
            if (entity == null || !entity.isAlive || Battles.fighter(entity)?.second != master || (entity is ServerPlayer && Battles.data(entity)?.waiting == true)) {
                dropCarried(master, carrier)
            }
        }
        for ((key, post) in master.state.posts) {
            val owner = post.team ?: continue
            if (post.locked || master.carried(key)) continue
            for ((entity, team) in fightersNear(master, level, Vec3.atBottomCenterOf(post.blockPos), FLAG_REACH)) {
                if (settings.friendly(team, owner) || master.state.carriers.containsKey(entity.stringUUID)) continue
                master.state = master.state.copy(carriers = master.state.carriers + (entity.stringUUID to key))
                (level.getBlockEntity(post.blockPos) as? TeamFlagBlockEntity)?.updateLook()
                Battles.broadcast(master, Component.translatable("message.flansmod.battle.flag_stolen", entity.displayName!!, Battles.teamName(master, owner)))
                level.playSound(null, post.blockPos, SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 1f, 1f)
                break
            }
        }
        for ((carrier, key) in master.state.carriers) {
            val entity = carrierEntity(level, carrier) ?: continue
            entity.addEffect(MobEffectInstance(MobEffects.GLOWING, 20, 0, false, false))
            val team = Battles.fighter(entity)?.first ?: continue
            val home = master.posts(team).firstOrNull { !master.carried(Post.key(it.blockPos)) && Vec3.atBottomCenterOf(it.blockPos).closerThan(entity.position(), FLAG_REACH + 0.5) }
                ?: continue
            dropCarried(master, carrier, announce = false)
            Battles.stats(master, Battles.statsKey(entity), Battles.statsName(entity), team, entity is SoldierEntity) { it.copy(captures = it.captures + 1) }
            Battles.broadcast(master, Component.translatable("message.flansmod.battle.flag_captured", entity.displayName!!,
                Battles.teamName(master, master.state.posts[key]?.team ?: "?")))
            level.playSound(null, home.blockPos, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1.2f)
            Battles.score(master, team)
            if (!master.running) return
        }
    }

    private fun carrierEntity(level: ServerLevel, uuid: String): LivingEntity? =
        UUID.fromString(uuid).let { level.server.playerList.getPlayer(it) ?: level.getEntity(it) } as? LivingEntity

    /** The flag [carrier] holds goes back to its post. */
    fun dropCarried(master: BattleMasterBlockEntity, carrier: String, announce: Boolean = true) {
        val key = master.state.carriers[carrier] ?: return
        master.state = master.state.copy(carriers = master.state.carriers - carrier)
        val post = master.state.posts[key] ?: return
        (master.level?.getBlockEntity(post.blockPos) as? TeamFlagBlockEntity)?.updateLook()
        if (announce) Battles.broadcast(master, Component.translatable("message.flansmod.battle.flag_returned", Battles.teamName(master, post.team ?: "?")))
    }

    // ------------------------------------------------------------------------------------------- border

    /** Fighters (and their vehicles) outside the border markers are put back inside. */
    private fun border(master: BattleMasterBlockEntity, level: ServerLevel) {
        val box = master.borderBox() ?: return
        val outside = Battles.onlineFighters(master).filter { it.level() == level && !it.isSpectator && !box.contains(it.position()) } +
            bots(master, level).filter { !box.contains(it.position()) }
        for (entity in outside) {
            val root = entity.rootVehicle
            val inside = Vec3(root.x.coerceIn(box.minX + 0.6, box.maxX - 0.6), root.y, root.z.coerceIn(box.minZ + 0.6, box.maxZ - 0.6))
            val target = if (level.noCollision(root, root.boundingBox.move(inside.subtract(root.position())))) inside
                else Vec3(inside.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, inside.x.toInt(), inside.z.toInt()).toDouble(), inside.z)
            push(root, target, level)
            (entity as? ServerPlayer)?.sendOverlayMessage(Component.translatable("message.flansmod.battle.border").withStyle(ChatFormatting.RED))
        }
    }

    private fun push(root: Entity, to: Vec3, level: ServerLevel) {
        if (root is ServerPlayer) {
            root.teleportTo(level, to.x, to.y, to.z, emptySet(), root.yRot, root.xRot, false)
            return
        }
        root.snapTo(to.x, to.y, to.z, root.yRot, root.xRot)
        root.deltaMovement = Vec3.ZERO
        // Vehicles are simulated by their driver's client: tell it about the move.
        (root.controllingPassenger as? ServerPlayer)?.connection?.send(ClientboundMoveVehiclePacket.fromEntity(root))
    }

    // ------------------------------------------------------------------------------------------- bots

    /**
     * With "fill with bots", every team is topped up to the team size: one bot per second at its flag post or spawn point
     * (at the start also at the Battle Master). With "bots respawn" a fallen bot comes back after the respawn delay -
     * at the Battle Master if the team has no post - with a new random loadout ([SoldierEntity.deploy]); without it only
     * the first wave is deployed. Players joining replace bots.
     */
    private fun maintainBots(master: BattleMasterBlockEntity, level: ServerLevel) {
        val settings = master.settings
        // Bots leave the roster when they die or are removed (botGone). One not found for a minute is lost too - but a
        // bot in a loaded, not entity-ticking chunk is invisible to getEntity without being gone, hence the grace time.
        for (id in master.state.bots.keys) {
            val key = master to id
            if (level.getEntity(UUID.fromString(id)) != null) lastSeen[key] = level.gameTime
            else if (level.gameTime - lastSeen.getOrPut(key) { level.gameTime } > LOST_TICKS) botGone(master, id)
        }
        // Trenches: soldiers come only when a commander sends them.
        if (!settings.fillWithBots || settings.teamSize <= 0 || settings.mode == BattleMode.TRENCHES) return
        for (team in settings.teams) {
            val players = Battles.onlineFighters(master).count { Battles.data(it)?.team == team.name }
            val count = master.state.bots.count { it.value == team.name }
            val wanted = (settings.teamSize - players).coerceAtLeast(0)
            if (count > wanted) {
                bots(master, level, team.name).firstOrNull()?.let { bot ->
                    master.state = master.state.copy(bots = master.state.bots - bot.stringUUID)
                    bot.discard()
                }
                continue
            }
            if (count == wanted || level.gameTime < (nextBot[master to team.name] ?: 0)) continue
            val mine = bots(master, level, team.name)
            // The first wave may start at the Battle Master; replacements for fallen bots only come with "bots respawn".
            val firstWave = master.state.stats.values.none { it.bot && it.team == team.name && it.deaths > 0 }
            if (!firstWave && !settings.botRespawn) continue
            val anchor = master.spawn(team.name) ?: master.blockPos.takeIf { firstWave || master.inCountdown || settings.botRespawn } ?: continue
            val used = mine.map { it.callsign }.toSet()
            val callsign = (1..64).map { "${team.name} ${botName(it)}" }.first { it !in used }
            SoldierEntity.deploy(master, level, team, callsign, Battles.spawnSpot(level, anchor))
        }
    }

    private val NAMES = listOf("Able", "Baker", "Charlie", "Dog", "Easy", "Fox", "George", "How", "Item", "Jig", "King", "Love", "Mike", "Nan", "Oboe", "Peter")
    private fun botName(i: Int) = NAMES.getOrNull(i - 1) ?: "#$i"

    /** A bot of [master] died or was removed: off the roster, its team deploys the next one a little later. */
    fun botGone(master: BattleMasterBlockEntity, id: String) {
        val team = master.state.bots[id] ?: return
        master.state = master.state.copy(bots = master.state.bots - id)
        nextBot[master to team] = (master.level?.gameTime ?: 0) + master.settings.botRespawnSeconds.coerceAtLeast(0) * 20L
        lastSeen.remove(master to id)
    }

    fun removeBots(master: BattleMasterBlockEntity) {
        val level = master.level as? ServerLevel ?: return
        bots(master, level).forEach { it.discard() }
        master.state = master.state.copy(bots = emptyMap())
        nextBot.keys.removeIf { it.first == master }
        lastSeen.keys.removeIf { it.first == master }
    }

    // ------------------------------------------------------------------------------------------- elimination

    /**
     * A team without a flag post and nobody standing (players waiting for a spawn, offline or gone; no bots, and none
     * coming back because bots respawn) is out.
     * When only one side (a team and its allies) is left, it wins. Returns true when the battle ended.
     */
    private fun eliminations(master: BattleMasterBlockEntity, level: ServerLevel): Boolean {
        val teams = master.state.startTeams
        if (teams.size < 2) return false
        val settings = master.settings
        val botsReturn = settings.fillWithBots && settings.botRespawn && settings.teamSize > 0
        val alive = teams.filter { team ->
            master.spawn(team) != null || master.state.bots.containsValue(team) || botsReturn ||
                Battles.onlineFighters(master).any { p -> Battles.data(p)?.let { it.team == team && !it.waiting } == true && p.isAlive }
        }
        if (alive.isNotEmpty() && !alive.all { a -> alive.all { b -> settings.friendly(a, b) } }) return false
        Battles.end(master, alive.maxByOrNull { master.state.scores[it] ?: 0 })
        return true
    }
}
