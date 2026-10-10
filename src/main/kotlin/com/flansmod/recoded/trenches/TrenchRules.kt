package com.flansmod.recoded.trenches

import com.flansmod.recoded.entity.GrenadeEntity
import com.flansmod.recoded.fortification.Fortifications
import com.flansmod.recoded.gamemode.BattleMasterBlockEntity
import com.flansmod.recoded.gamemode.BattleMode
import com.flansmod.recoded.gamemode.BattleRules
import com.flansmod.recoded.gamemode.Battles
import com.flansmod.recoded.gamemode.SoldierEntity
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.loadedMagazine
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.AreaEffectCloud
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Trenches mode (see [BattleMode.TRENCHES]), after the mobile game *Trenches*. Every running second: team funds come
 * in ([TrenchSettings.fundsPerSecond] plus a bonus per trench held); squads bought by a commander ([buy]) march from
 * their headquarters into the first trench and wait for orders ([order], [orderAll]); a trench line belongs to the
 * side that holds it alone for the capture time; attackers alone in the enemy headquarters for
 * [TrenchSettings.hqCaptureSeconds] win. Soldiers in a trench take less damage, in a bunker even less; mortar teams
 * shell enemy trenches in reach, assault troops throw grenades, officers inspire, a machine gun is taken over by its
 * loader. Engineers build bunkers (also forward spawn points), lay and cut barbed wire ([job]); supports are an
 * artillery barrage and poison gas ([support]). Sides without a human commander online are played by [TrenchAi].
 */
object TrenchRules {
    private val layouts = WeakHashMap<BattleMasterBlockEntity, TrenchLayout>()
    /** When each side was last warned that its headquarters is under attack (not saved). */
    private val hqWarned = WeakHashMap<BattleMasterBlockEntity, LongArray>()

    /** The lane of [master] (refreshed every second while the battle runs). */
    fun layout(master: BattleMasterBlockEntity): TrenchLayout? = layouts[master] ?: TrenchLayout.of(master)?.also { layouts[master] = it }

    fun init() {
        PayloadTypeRegistry.serverboundPlay().register(TrenchCommandPayload.TYPE, TrenchCommandPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(TrenchCommandPayload.TYPE) { payload, context -> command(context.player(), payload) }
    }

    private val BattleMasterBlockEntity.trench get() = state.trench
    private fun BattleMasterBlockEntity.updateTrench(change: (TrenchState) -> TrenchState) {
        state = state.copy(trench = change(state.trench))
    }

    // ------------------------------------------------------------------------------------------- start / end

    /**
     * A new battle: start funds, no bunkers, wire or jobs; commanders stay. The whole field stays loaded while the
     * battle runs - soldiers far from any player would stand still (and be out of reach of the rules) otherwise.
     */
    fun start(master: BattleMasterBlockEntity) {
        layouts.remove(master)
        val s = master.settings.trenches
        val chunks = fieldChunks(master)
        (master.level as? ServerLevel)?.let { level -> chunks.forEach { level.setChunkForced(ChunkPos.getX(it), ChunkPos.getZ(it), true) } }
        master.updateTrench { TrenchState(funds = master.settings.teams.take(2).associate { it.name to s.startFunds }, commanders = it.commanders, chunks = chunks) }
    }

    /** Chunks under the lane (the zones plus the width of the trench lines and a margin). */
    private fun fieldChunks(master: BattleMasterBlockEntity): List<Long> {
        val layout = TrenchLayout.of(master) ?: return emptyList()
        val reach = layout.halfWidth + 8
        val xs = layout.zones.map { it.flag.x }
        val zs = layout.zones.map { it.flag.z }
        val chunks = mutableListOf<Long>()
        for (cx in ((xs.min() - reach) shr 4)..((xs.max() + reach) shr 4)) for (cz in ((zs.min() - reach) shr 4)..((zs.max() + reach) shr 4)) {
            // Never the Battle Master's own chunk: the battle keeps (and releases) that one itself.
            if (cx != master.blockPos.x shr 4 || cz != master.blockPos.z shr 4) chunks += ChunkPos.pack(cx, cz)
        }
        return chunks.take(256)
    }

    /** The battle is over: wire comes down, bunker sandbags go back to the trench parapet. */
    fun end(master: BattleMasterBlockEntity) {
        val level = master.level as? ServerLevel ?: return
        for (pos in master.trench.wire.map(BlockPos::of)) if (level.getBlockState(pos).`is`(Fortifications.BARBED_WIRE)) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
        for (pos in master.trench.sandbags.map(BlockPos::of)) if (level.getBlockState(pos).`is`(Fortifications.SANDBAGS)) level.setBlockAndUpdate(pos, Fortifications.SANDBAG_SLAB.defaultBlockState())
        for (chunk in master.trench.chunks) level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false)
        master.updateTrench { TrenchState(commanders = it.commanders) }
        layouts.remove(master)
    }

    // ------------------------------------------------------------------------------------------- tick

    fun tick(master: BattleMasterBlockEntity, level: ServerLevel) {
        val time = level.gameTime
        if (time % 20 == 0L) TrenchLayout.of(master)?.let { layouts[master] = it } ?: layouts.remove(master)
        val layout = layouts[master] ?: TrenchLayout.of(master)?.also { layouts[master] = it } ?: return
        shells(master, level)
        if (time % 10 == 0L) {
            captures(master, level, layout)
            if (!master.running) return
        }
        if (time % 20 == 0L) {
            val units = units(master, level)
            income(master, layout)
            assignSlots(layout, units)
            auras(units)
            specials(master, level, layout, units)
            jobs(master, level, layout, units)
            for (side in 0..1) if (aiCommands(master, side, layout)) TrenchAi.tick(master, level, layout, side, units)
            master.state = master.state.copy(scores = layout.teams.associateWith { team -> held(master, layout, layout.side(team)) })
        }
        // Half a second apart from the battle's own status sync: the command screen updates twice a second.
        if (time % 20 == 10L) Battles.syncStatus(master)
    }

    /** Trench units of [master] alive in [level]. */
    fun units(master: BattleMasterBlockEntity, level: ServerLevel): List<SoldierEntity> = BattleRules.bots(master, level).filter { it.isTrenchUnit && it.isAlive }

    /** Trench lines held by [side]. */
    fun held(master: BattleMasterBlockEntity, layout: TrenchLayout, side: Int) =
        layout.zones.count { !it.base && layout.side(master.state.posts[it.key]?.team) == side }

    fun owner(master: BattleMasterBlockEntity, layout: TrenchLayout, zone: Int): Int {
        val z = layout.zones.getOrNull(zone) ?: return -1
        return if (z.base) (if (zone == 0) 0 else 1) else layout.side(master.state.posts[z.key]?.team)
    }

    private fun income(master: BattleMasterBlockEntity, layout: TrenchLayout) {
        val s = master.settings.trenches
        master.updateTrench { t ->
            t.copy(funds = layout.teams.associateWith { team -> (t.funds[team] ?: 0) + income(master, layout, layout.side(team), s) })
        }
    }

    fun income(master: BattleMasterBlockEntity, layout: TrenchLayout, side: Int, s: TrenchSettings = master.settings.trenches) =
        s.fundsPerSecond + s.trenchBonus * held(master, layout, side)

    // ------------------------------------------------------------------------------------------- captures

    /** Who is in each zone: trench lines change hands, headquarters are stormed. */
    private fun captures(master: BattleMasterBlockEntity, level: ServerLevel, layout: TrenchLayout) {
        val needed = master.settings.captureSeconds.coerceAtLeast(1) * 20
        for (zone in layout.zones) {
            val radius = if (zone.base) TrenchLayout.BASE_RADIUS + 1 else layout.halfWidth + 4.0
            val present = BattleRules.fightersNear(master, level, Vec3.atBottomCenterOf(zone.flag), radius)
                .filter { layout.zoneAt(it.first.position()) == zone.index }.map { layout.side(it.second) }.filter { it >= 0 }
            val counts = IntArray(2).also { c -> present.forEach { c[it]++ } }
            if (zone.base) {
                storm(master, level, layout, zone, counts)
                if (!master.running) return
                continue
            }
            val post = master.state.posts[zone.key] ?: continue
            val owner = layout.side(post.team)
            val capture = master.captures[zone.key]
            val alone = (0..1).singleOrNull { counts[it] > 0 && counts[1 - it] == 0 }
            if (alone == null || alone == owner) {
                // Contested, empty or held by its own side: an enemy capture fades.
                if (capture != null && (capture.ticks - if (alone == owner && alone != null) 20 else 5).also { capture.ticks = it } <= 0) master.captures.remove(zone.key)
                continue
            }
            val team = layout.team(alone)!!
            val current = capture?.takeIf { it.team == team } ?: BattleMasterBlockEntity.Capture(team, 0).also { master.captures[zone.key] = it }
            current.ticks += 10 * counts[alone].coerceAtMost(3)
            if (current.ticks < needed) continue
            master.captures.remove(zone.key)
            master.setPost(zone.flag, post.copy(team = team))
            Battles.broadcast(master, Component.translatable("message.flansmod.trenches.taken", zone.label, Battles.teamName(master, team)))
            level.playSound(null, zone.flag, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1f, 0.8f)
        }
    }

    /** Attackers alone in a headquarters build up [TrenchState.hq]; defenders push it back. Full: the attackers win. */
    private fun storm(master: BattleMasterBlockEntity, level: ServerLevel, layout: TrenchLayout, zone: TrenchLayout.Zone, counts: IntArray) {
        val side = if (zone.index == 0) 0 else 1
        val team = layout.team(side)!!
        val attackers = counts[1 - side]
        val before = master.trench.hq[team] ?: 0
        val after = when {
            attackers > 0 && counts[side] == 0 -> before + 10 * attackers.coerceAtMost(4)
            counts[side] > 0 -> before - 20
            else -> before - 5
        }.coerceAtLeast(0)
        if (after != before) master.updateTrench { it.copy(hq = it.hq + (team to after)) }
        if (attackers > 0 && after > before) {
            val warned = hqWarned.getOrPut(master) { LongArray(2) }
            if (level.gameTime - warned[side] > 200) {
                warned[side] = level.gameTime
                Battles.broadcast(master, Component.translatable("message.flansmod.trenches.hq_attacked", Battles.teamName(master, team)).withStyle(ChatFormatting.RED))
            }
        }
        if (after >= master.settings.trenches.hqCaptureSeconds.coerceAtLeast(1) * 20) Battles.end(master, layout.team(1 - side))
    }

    // ------------------------------------------------------------------------------------------- soldiers

    /** Where [soldier] should stand: its place in the zone it was ordered to (null when the lane is unknown). */
    fun destination(master: BattleMasterBlockEntity, soldier: SoldierEntity): Vec3? {
        val layout = layout(master) ?: return null
        val side = layout.side(soldier.team).takeIf { it >= 0 } ?: return null
        return layout.slot(soldier.order.coerceIn(0, layout.last), soldier.slot, side)
    }

    fun arrived(layout: TrenchLayout, soldier: SoldierEntity) = layout.zoneAt(soldier.position()) == soldier.order

    /** Places in each zone by squad order, so newcomers line up after those already there. */
    private fun assignSlots(layout: TrenchLayout, units: List<SoldierEntity>) {
        units.groupBy { layout.side(it.team) to it.order }.values.forEach { group ->
            group.sortedBy { it.serial }.forEachIndexed { i, s -> s.slot = i }
        }
    }

    /** Officers inspire allies of their team around them. */
    private fun auras(units: List<SoldierEntity>) {
        val officers = units.filter { it.role == TrenchRole.OFFICER }
        for (u in units) {
            u.inspired = officers.mapNotNull { o -> o.trenchSoldier?.aura?.takeIf { o.team == u.team && o.distanceTo(u) <= it.radius } }.minByOrNull { it.fireDelay }
        }
    }

    /** Cover against bullets and shells: in a headquarters or trench, more in a bunker its side holds. */
    fun cover(soldier: SoldierEntity, source: DamageSource): Float {
        if (source.`is`(DamageTypes.WITHER) || source.`is`(DamageTypes.MAGIC) || source.`is`(DamageTypes.INDIRECT_MAGIC) || source.`is`(DamageTypes.FALL)) return 1f
        val master = soldier.battle() ?: return 1f
        val layout = layout(master) ?: return 1f
        val zone = layout.zoneAt(soldier.position())?.let(layout.zones::get) ?: return 1f
        return when {
            zone.base -> 0.6f
            zone.key in master.trench.bunkers && owner(master, layout, zone.index) == layout.side(soldier.team) -> 0.45f
            else -> 0.75f
        }
    }

    /** A fallen machine gunner's loader takes up the gun. */
    fun fallen(soldier: SoldierEntity) {
        if (soldier.role != TrenchRole.MACHINE_GUNNER) return
        val master = soldier.battle() ?: return
        val level = soldier.level() as? ServerLevel ?: return
        val loader = units(master, level).firstOrNull { it.partner == soldier.uuid && it.isAlive } ?: return
        loader.setItemSlot(EquipmentSlot.MAINHAND, soldier.mainHandItem.copy())
        loader.role = TrenchRole.MACHINE_GUNNER
        loader.partner = null
    }

    // ------------------------------------------------------------------------------------------- mortars, grenades, shells

    private fun specials(master: BattleMasterBlockEntity, level: ServerLevel, layout: TrenchLayout, units: List<SoldierEntity>) {
        val now = level.gameTime
        for (u in units) {
            if (now < u.nextSpecial) continue
            val def = u.trenchSoldier ?: continue
            when {
                u.role == TrenchRole.MORTAR && def.mortar != null && arrived(layout, u) -> mortar(master, level, layout, u, def.mortar, units)
                def.grenades != null -> throwGrenade(level, u, def.grenades)
            }
        }
    }

    /** Enemies (soldiers and players) by the zone they are in. */
    fun enemiesByZone(master: BattleMasterBlockEntity, level: ServerLevel, layout: TrenchLayout, side: Int, units: List<SoldierEntity>): Map<Int, List<LivingEntity>> {
        val enemyTeam = layout.team(1 - side)
        val players = Battles.onlineFighters(master).filter { p -> p.level() == level && p.isAlive && !p.isSpectator && Battles.data(p)?.team == enemyTeam }
        return (units.filter { it.team == enemyTeam } + players).mapNotNull { e -> layout.zoneAt(e.position())?.let { it to e } }
            .groupBy({ it.first }, { it.second })
    }

    private fun mortar(master: BattleMasterBlockEntity, level: ServerLevel, layout: TrenchLayout, u: SoldierEntity, m: TrenchSoldier.Mortar, units: List<SoldierEntity>) {
        val side = layout.side(u.team)
        val target = enemiesByZone(master, level, layout, side, units).filter { (zone, _) ->
            val d = Vec3.atBottomCenterOf(layout.zones[zone].flag).subtract(u.position()).horizontalDistance()
            d in m.minRange..m.range
        }.maxByOrNull { it.value.size }?.value?.random() ?: return
        val r = u.random
        val aim = target.position().add((r.nextDouble() - 0.5) * 2 * m.scatter, 0.0, (r.nextDouble() - 0.5) * 2 * m.scatter)
        val flight = 20 + (aim.subtract(u.position()).horizontalDistance() / 2).toLong()
        shell(master, level, aim, level.gameTime + flight, m.power, u.stringUUID)
        u.nextSpecial = level.gameTime + (m.reloadSeconds * 20 * (u.inspired?.fireDelay ?: 1f)).toLong()
        level.playSound(null, u.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 0.5f, 1.8f)
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, u.x, u.y + 1.2, u.z, 3, 0.1, 0.2, 0.1, 0.02)
    }

    /** Assault troops lob a grenade at their target when it is close. */
    private fun throwGrenade(level: ServerLevel, u: SoldierEntity, g: TrenchSoldier.Grenades) {
        val target = u.target?.takeIf { it.isAlive && u.distanceTo(it) <= g.range && u.sensing.hasLineOfSight(it) } ?: return
        val id = g.grenade ?: grenadeFor(u) ?: return
        val stack = GrenadeItem.stackFor(id)
        val to = target.position().subtract(u.eyePosition)
        val distance = to.horizontalDistance().coerceAtLeast(1.0)
        // A 40° lob; speed for the distance (drag makes the vacuum formula fall a little short, hence 1.15).
        val speed = (sqrt(distance * (Grenades[id]?.gravity ?: 0.05) / kotlin.math.sin(Math.toRadians(80.0))) * 1.15).coerceIn(0.35, 1.6)
        val horizontal = Vec3(to.x, 0.0, to.z).normalize()
        val dir = horizontal.scale(kotlin.math.cos(Math.toRadians(40.0))).add(0.0, kotlin.math.sin(Math.toRadians(40.0)), 0.0)
        GrenadeEntity(level, u, stack).apply {
            shoot(dir.x, dir.y, dir.z, speed.toFloat(), 2f)
            level.addFreshEntity(this)
        }
        u.nextSpecial = level.gameTime + (g.cooldownSeconds * 20).toLong()
    }

    /** A thrown fragmentation grenade of the soldier's faction (else any), not smoke, flash or a mine. */
    private fun grenadeFor(u: SoldierEntity): Identifier? {
        val faction = u.battle()?.settings?.team(u.team)?.faction
        val frags = Grenades.all.filter { (_, g) -> g.throwable && g.mine == null && g.explosion != null && g.smoke == null && g.flash == null && !g.explosion.breakBlocks }
        return (frags.filter { it.value.faction == faction }.keys.minOrNull() ?: frags.keys.minOrNull())
    }

    private fun shell(master: BattleMasterBlockEntity, level: ServerLevel, at: Vec3, time: Long, power: Float, shooter: String?) {
        val y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.x.toInt(), at.z.toInt()).toDouble()
        master.updateTrench { it.copy(shells = it.shells + TrenchShell(at.x, y, at.z, time, power, shooter)) }
    }

    /** Shells that are due land: an explosion that leaves the field intact (the shooter's side is not hurt). */
    private fun shells(master: BattleMasterBlockEntity, level: ServerLevel) {
        val due = master.trench.shells.filter { it.at <= level.gameTime }
        if (due.isEmpty()) return
        master.updateTrench { it.copy(shells = it.shells - due.toSet()) }
        for (s in due) {
            val source = s.shooter?.let { level.getEntity(UUID.fromString(it)) }
            level.explode(source, s.x, s.y, s.z, s.power, false, Level.ExplosionInteraction.NONE)
        }
    }

    // ------------------------------------------------------------------------------------------- engineering

    private fun jobs(master: BattleMasterBlockEntity, level: ServerLevel, layout: TrenchLayout, units: List<SoldierEntity>) {
        val jobs = master.trench.jobs
        if (jobs.isEmpty()) return
        val s = master.settings.trenches
        val busy = jobs.mapNotNull { it.engineer }.toMutableSet()
        val result = mutableListOf<TrenchJob>()
        for (job in jobs) {
            val side = layout.side(job.team)
            // A trench lost before the work is done: the job is off.
            if (side < 0 || job.zone !in 1 until layout.last || owner(master, layout, job.zone) != side) continue
            var engineer = job.engineer?.let { id -> units.firstOrNull { it.stringUUID == id } }?.takeIf { it.order == job.zone }
            if (engineer == null) {
                job.engineer?.let(busy::remove)
                engineer = units.filter { it.team == job.team && it.role == TrenchRole.ENGINEER && it.stringUUID !in busy }
                    .minByOrNull { kotlin.math.abs(it.order - job.zone) }
                if (engineer == null) {
                    result += job.copy(engineer = null)
                    continue
                }
                busy += engineer.stringUUID
                engineer.order = job.zone
            }
            if (!arrived(layout, engineer)) {
                result += job.copy(engineer = engineer.stringUUID)
                continue
            }
            val progress = job.progress + 20
            level.sendParticles(ParticleTypes.CRIT, engineer.x, engineer.y + 0.5, engineer.z, 4, 0.3, 0.2, 0.3, 0.05)
            level.playSound(null, engineer.blockPosition(), SoundEvents.WOOD_HIT, SoundSource.HOSTILE, 0.6f, 0.9f)
            val needed = 20 * when (job.kind) { TrenchJobKind.BUNKER -> s.bunkerSeconds; TrenchJobKind.WIRE -> s.wireSeconds; TrenchJobKind.CUT -> s.cutSeconds }
            if (progress < needed) {
                result += job.copy(engineer = engineer.stringUUID, progress = progress)
                continue
            }
            busy -= engineer.stringUUID
            when (job.kind) {
                TrenchJobKind.BUNKER -> bunker(master, level, layout, job.zone, side)
                TrenchJobKind.WIRE -> layWire(master, level, layout, job.zone, side)
                TrenchJobKind.CUT -> cutWire(master, level, layout, job.zone, side)
            }
            Battles.broadcast(master, Component.translatable("message.flansmod.trenches.job_done.${job.kind.name.lowercase()}", layout.zones[job.zone].label, Battles.teamName(master, job.team)))
        }
        master.updateTrench { it.copy(jobs = result) }
    }

    /** A bunker: cover and a forward spawn point; the parapet towards the enemy becomes a sandbag wall with firing slits. */
    private fun bunker(master: BattleMasterBlockEntity, level: ServerLevel, layout: TrenchLayout, zone: Int, side: Int) {
        val z = layout.zones[zone]
        val placed = mutableListOf<Long>()
        val flag = Vec3.atBottomCenterOf(z.flag)
        for (p in -layout.halfWidth - 1..layout.halfWidth + 1) {
            if (p % 3 == 0) continue // firing slits
            for (k in 1..3) {
                val at = flag.add(layout.perp.scale(p.toDouble())).add(layout.axis.scale(k * layout.forward(side).toDouble()))
                val pos = BlockPos.containing(at.x, flag.y + 1, at.z)
                if (level.getBlockState(pos).`is`(Fortifications.SANDBAG_SLAB)) {
                    level.setBlockAndUpdate(pos, Fortifications.SANDBAGS.defaultBlockState())
                    placed += pos.asLong()
                }
            }
        }
        master.updateTrench { it.copy(bunkers = it.bunkers + z.key, sandbags = it.sandbags + placed) }
    }

    /** Barbed wire across the lane five blocks in front of the trench (a gap every four blocks). */
    private fun layWire(master: BattleMasterBlockEntity, level: ServerLevel, layout: TrenchLayout, zone: Int, side: Int) {
        val z = layout.zones[zone]
        val flag = Vec3.atBottomCenterOf(z.flag)
        val placed = mutableListOf<Long>()
        for (p in -layout.halfWidth..layout.halfWidth) {
            if (Math.floorMod(p, 4) == 2) continue
            val at = flag.add(layout.perp.scale(p.toDouble())).add(layout.axis.scale(5.0 * layout.forward(side)))
            val pos = (3 downTo -2).map { BlockPos.containing(at.x, flag.y + it, at.z) }
                .firstOrNull { level.getBlockState(it).isAir && level.getBlockState(it.below()).isFaceSturdy(level, it.below(), net.minecraft.core.Direction.UP) } ?: continue
            level.setBlockAndUpdate(pos, Fortifications.BARBED_WIRE.defaultBlockState())
            placed += pos.asLong()
        }
        master.updateTrench { it.copy(wire = it.wire + placed) }
    }

    /** Wire between [zone] and the next zone towards the enemy of [side] (anyone's wire). */
    fun wireAhead(master: BattleMasterBlockEntity, layout: TrenchLayout, zone: Int, side: Int): List<Long> {
        val next = layout.zones.getOrNull(zone + layout.forward(side)) ?: return emptyList()
        val (lo, hi) = listOf(layout.zones[zone].along, next.along).sorted()
        return master.trench.wire.filter { layout.along(Vec3.atCenterOf(BlockPos.of(it))) in lo + 1..hi - 1 }
    }

    private fun cutWire(master: BattleMasterBlockEntity, level: ServerLevel, layout: TrenchLayout, zone: Int, side: Int) {
        val cut = wireAhead(master, layout, zone, side)
        for (pos in cut.map(BlockPos::of)) if (level.getBlockState(pos).`is`(Fortifications.BARBED_WIRE)) level.destroyBlock(pos, false)
        master.updateTrench { it.copy(wire = it.wire - cut.toSet()) }
    }

    // ------------------------------------------------------------------------------------------- commands

    /** Whether [side] is played by the computer: AI on and no human commander online. */
    fun aiCommands(master: BattleMasterBlockEntity, side: Int, layout: TrenchLayout? = layout(master)): Boolean {
        if (master.settings.trenches.ai == TrenchAiLevel.OFF) return false
        val team = layout?.team(side) ?: return false
        return commander(master, team) == null
    }

    /** The online human commander of [team], if any. */
    fun commander(master: BattleMasterBlockEntity, team: String): ServerPlayer? =
        master.trench.commanders[team]?.let { id -> master.level?.server?.playerList?.getPlayer(UUID.fromString(id)) }

    /** [player] may give [team]'s orders: a member, and nobody else commands it (or they are offline). */
    fun canCommand(master: BattleMasterBlockEntity, player: ServerPlayer, team: String): Boolean =
        master.teamOf(player.uuid) == team && commander(master, team).let { it == null || it == player }

    private fun command(player: ServerPlayer, p: TrenchCommandPayload) {
        val data = Battles.data(player) ?: return
        val master = Battles.master(player.level().server, data.master) ?: return
        if (!master.running || master.settings.mode != BattleMode.TRENCHES) return
        val level = master.level as? ServerLevel ?: return
        val team = data.team
        val error: String? = when (p.action) {
            TrenchCommandPayload.VIEW -> {
                if (player.getAttached(com.flansmod.recoded.gamemode.BattleSpectator.ATTACHMENT) != null) Battles.stopSpectating(player)
                else Battles.spectate(player, master)
                null
            }
            TrenchCommandPayload.RELEASE_COMMAND -> {
                if (master.trench.commanders[team] == player.stringUUID) master.updateTrench { it.copy(commanders = it.commanders - team) }
                null
            }
            else -> if (!canCommand(master, player, team)) "message.flansmod.trenches.not_commander" else {
                if (master.trench.commanders[team] != player.stringUUID) {
                    master.updateTrench { it.copy(commanders = it.commanders + (team to player.stringUUID)) }
                    Battles.broadcast(master, Component.translatable("message.flansmod.trenches.commander", player.displayName!!, Battles.teamName(master, team)))
                }
                when (p.action) {
                    TrenchCommandPayload.BUY -> Identifier.tryParse(p.id)?.let { buy(master, level, team, it) } ?: "message.flansmod.trenches.unknown"
                    TrenchCommandPayload.ORDER -> order(master, level, team, p.zone, p.count).let { if (it == 0) "message.flansmod.trenches.nobody" else null }
                    TrenchCommandPayload.ORDER_ALL -> orderAll(master, level, team, p.count > 0).let { if (it == 0) "message.flansmod.trenches.nobody" else null }
                    TrenchCommandPayload.SUPPORT -> Identifier.tryParse(p.id)?.let { support(master, level, team, it, p.zone) } ?: "message.flansmod.trenches.unknown"
                    TrenchCommandPayload.JOB -> runCatching { TrenchJobKind.valueOf(p.id.uppercase()) }.getOrNull()?.let { job(master, level, team, it, p.zone) }
                        ?: "message.flansmod.trenches.unknown"
                    else -> null
                }
            }
        }
        error?.let { player.sendOverlayMessage(Component.translatable(it).withStyle(ChatFormatting.RED)) }
        Battles.syncStatus(player, master)
    }

    private fun funds(master: BattleMasterBlockEntity, team: String) = master.trench.funds[team] ?: 0

    private fun pay(master: BattleMasterBlockEntity, team: String, cost: Int, readyKey: String? = null, readyAt: Long = 0) =
        master.updateTrench { t -> t.copy(funds = t.funds + (team to (t.funds[team] ?: 0) - cost), ready = if (readyKey == null) t.ready else t.ready + (readyKey to readyAt)) }

    /** Seconds until [id] can be used again by [team] (0 = now). */
    fun readyIn(master: BattleMasterBlockEntity, team: String, id: Identifier): Int {
        val time = master.level?.gameTime ?: 0
        return ceil(((master.trench.ready["$team|$id"] ?: 0) - time).coerceAtLeast(0) / 20.0).toInt()
    }

    /** Sends a squad of unit [id] into the field for [team]. Returns an error message key, or null when it went. */
    fun buy(master: BattleMasterBlockEntity, level: ServerLevel, team: String, id: Identifier): String? {
        val layout = layout(master) ?: return "message.flansmod.trenches.no_field"
        val side = layout.side(team).takeIf { it >= 0 } ?: return "message.flansmod.trenches.no_side"
        val def = TrenchUnits[id]?.takeIf { it.buyable } ?: return "message.flansmod.trenches.unknown"
        val s = master.settings.trenches
        if (funds(master, team) < def.cost) return "message.flansmod.trenches.no_funds"
        if (readyIn(master, team, id) > 0) return "message.flansmod.trenches.not_ready"
        if (units(master, level).count { it.team == team } + def.soldiers.size > s.unitCap) return "message.flansmod.trenches.cap"
        val teamDef = master.settings.team(team) ?: return "message.flansmod.trenches.no_side"

        // From the most forward bunker the side holds, else from headquarters; from there into the first trench unless
        // the enemy holds it.
        val home = layout.home(side)
        val spawn = forwardSpawn(master, layout, side) ?: home
        val first = home + layout.forward(side)
        val rally = if (spawn == home && first != layout.enemyHome(side) && owner(master, layout, first) != 1 - side) first else spawn
        val squad = master.trench.squads + 1
        val members = def.soldiers.indices.map { id to it }.toMutableList()
        if (def.officer != null && TrenchUnits[def.officer] != null && level.random.nextDouble() < def.officerChance) members += def.officer to 0
        var gunner: SoldierEntity? = null
        members.forEachIndexed { n, (unitId, member) ->
            val sd = TrenchUnits[unitId]?.soldiers?.getOrNull(member) ?: return@forEachIndexed
            val callsign = "${sd.role.callsign} $squad${'a' + n}"
            val gun = gunFor(teamDef.faction, sd, level.random)
            val soldier = SoldierEntity.deploy(master, level, teamDef, callsign, Battles.spawnSpot(level, layout.zones[spawn].flag), gun)
                ?: return@forEachIndexed
            if (gun == null) soldier.setItemSlot(EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY)
            soldier.unit = unitId
            soldier.member = member
            soldier.role = sd.role
            soldier.order = rally
            soldier.serial = squad * 16 + n
            soldier.getAttribute(Attributes.MAX_HEALTH)?.baseValue = sd.health
            soldier.health = sd.health.toFloat()
            soldier.getAttribute(Attributes.MOVEMENT_SPEED)?.baseValue = 0.3 * sd.speed
            soldier.getAttribute(Attributes.FOLLOW_RANGE)?.baseValue = maxOf(soldier.range, 24.0) + 8
            if (sd.role == TrenchRole.MACHINE_GUNNER && gunner == null) gunner = soldier
            if (sd.loader) soldier.partner = gunner?.uuid
        }
        pay(master, team, def.cost, "$team|$id", level.gameTime + (def.cooldownSeconds * 20).toLong())
        master.updateTrench { it.copy(squads = squad) }
        return null
    }

    /** Where [team]'s players respawn: its most forward bunker, else its headquarters (null: not a side of the lane). */
    fun spawn(master: BattleMasterBlockEntity, team: String): BlockPos? {
        val layout = layout(master) ?: return null
        val side = layout.side(team).takeIf { it >= 0 } ?: return null
        return layout.zones[forwardSpawn(master, layout, side) ?: layout.home(side)].flag
    }

    /** The most forward trench with a bunker that [side] holds (its soldiers may start there). */
    fun forwardSpawn(master: BattleMasterBlockEntity, layout: TrenchLayout, side: Int): Int? =
        layout.zones.filter { !it.base && it.key in master.trench.bunkers && owner(master, layout, it.index) == side }
            .maxByOrNull { it.index * layout.forward(side) }?.index

    /**
     * A gun of one of [soldier]'s categories (in order) from [faction] (any faction when null) - with or without a scope
     * as asked, else regardless of the scope; any bullet gun of the faction if none fits; null for soldiers without
     * categories. [random] null: the first by id (for icons).
     */
    fun gunFor(faction: Identifier?, soldier: TrenchSoldier, random: RandomSource?): Identifier? {
        if (soldier.guns.isEmpty()) return null
        val candidates = Guns.all.filter { (id, g) ->
            !g.mounted && (faction == null || g.faction == faction) && GunItem.stackFor(id).loadedMagazine?.let { it.ammoDefinition?.projectile == null } == true
        }
        fun pick(ids: List<Identifier>) = ids.sorted().let { if (it.isEmpty()) null else if (random == null) it.first() else it[random.nextInt(it.size)] }
        val passes = if (soldier.scoped == null) listOf<Boolean?>(null) else listOf(soldier.scoped, null)
        for (scoped in passes) for (category in soldier.guns) {
            pick(candidates.filter { it.value.category == category && (scoped == null || (it.value.scope?.overlay != null) == scoped) }.keys.toList())?.let { return it }
        }
        return pick(candidates.keys.toList())
    }

    /**
     * Soldiers of [team] ordered to [zone] move on: [count] > 0 that many one zone towards the enemy, < 0 back
     * (±[TrenchCommandPayload.ALL]: all of them). Those already there go first. Returns how many move.
     */
    fun order(master: BattleMasterBlockEntity, level: ServerLevel, team: String, zone: Int, count: Int): Int {
        val layout = layout(master) ?: return 0
        val side = layout.side(team).takeIf { it >= 0 } ?: return 0
        val target = zone + if (count > 0) layout.forward(side) else -layout.forward(side)
        if (count == 0 || target !in 0..layout.last) return 0
        val movers = units(master, level).filter { it.team == team && it.order == zone }
            .sortedWith(compareBy({ !arrived(layout, it) }, { it.serial })).take(kotlin.math.abs(count))
        movers.forEach { it.order = target }
        return movers.size
    }

    /** Every soldier of [team] one zone forward (or back), up to the enemy headquarters (back to their own). */
    fun orderAll(master: BattleMasterBlockEntity, level: ServerLevel, team: String, forward: Boolean): Int {
        val layout = layout(master) ?: return 0
        val side = layout.side(team).takeIf { it >= 0 } ?: return 0
        val step = layout.forward(side) * if (forward) 1 else -1
        return units(master, level).filter { it.team == team }.count { u ->
            val target = (u.order + step).coerceIn(0, layout.last)
            (target != u.order).also { u.order = target }
        }
    }

    /** Calls support [id] down on [zone] for [team]. Returns an error message key, or null. */
    fun support(master: BattleMasterBlockEntity, level: ServerLevel, team: String, id: Identifier, zone: Int): String? {
        val layout = layout(master) ?: return "message.flansmod.trenches.no_field"
        val def = TrenchSupports[id] ?: return "message.flansmod.trenches.unknown"
        val z = layout.zones.getOrNull(zone) ?: return "message.flansmod.trenches.unknown"
        if (funds(master, team) < def.cost) return "message.flansmod.trenches.no_funds"
        if (readyIn(master, team, id) > 0) return "message.flansmod.trenches.not_ready"
        val r = level.random
        val flag = Vec3.atBottomCenterOf(z.flag)
        fun point(): Vec3 = if (z.base) flag.add((r.nextDouble() - 0.5) * 10, 0.0, (r.nextDouble() - 0.5) * 10)
            else flag.add(layout.perp.scale((r.nextDouble() * 2 - 1) * layout.halfWidth)).add(layout.axis.scale((r.nextDouble() - 0.5) * 5))
        when (def.type) {
            TrenchSupportType.BARRAGE -> {
                val spacing = def.durationSeconds * 20 / def.shells.coerceAtLeast(1)
                // The guns are far behind the lines: the first shell lands a moment after the call.
                repeat(def.shells) { i -> shell(master, level, point(), level.gameTime + 40 + (i * spacing).toLong(), def.power, null) }
            }
            TrenchSupportType.GAS -> {
                val offsets = if (z.base) listOf(0.0) else listOf(-0.6, 0.0, 0.6).map { it * layout.halfWidth }
                for (o in offsets) {
                    val at = flag.add(layout.perp.scale(o))
                    AreaEffectCloud(level, at.x, at.y + 0.2, at.z).apply {
                        radius = def.radius
                        duration = (def.durationSeconds * 20).toInt()
                        waitTime = 10
                        radiusPerTick = 0f
                        addEffect(MobEffectInstance(MobEffects.WITHER, 60, 1))
                        addEffect(MobEffectInstance(MobEffects.SLOWNESS, 60, 0))
                        setCustomParticle(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFFA8C23A.toInt()))
                        level.addFreshEntity(this)
                    }
                }
            }
        }
        pay(master, team, def.cost, "$team|$id", level.gameTime + (def.cooldownSeconds * 20).toLong())
        Battles.broadcast(master, Component.translatable("message.flansmod.trenches.support", Battles.teamName(master, team), def.name, z.label).withStyle(ChatFormatting.GOLD))
        return null
    }

    /** Orders engineering [kind] in [team]'s trench [zone]. Returns an error message key, or null. */
    fun job(master: BattleMasterBlockEntity, level: ServerLevel, team: String, kind: TrenchJobKind, zone: Int): String? {
        val layout = layout(master) ?: return "message.flansmod.trenches.no_field"
        val side = layout.side(team).takeIf { it >= 0 } ?: return "message.flansmod.trenches.no_side"
        val z = layout.zones.getOrNull(zone)?.takeIf { !it.base } ?: return "message.flansmod.trenches.trench_only"
        if (owner(master, layout, zone) != side) return "message.flansmod.trenches.not_held"
        if (master.trench.jobs.any { it.team == team && it.zone == zone && it.kind == kind }) return "message.flansmod.trenches.already"
        when (kind) {
            TrenchJobKind.BUNKER -> if (z.key in master.trench.bunkers) return "message.flansmod.trenches.already"
            TrenchJobKind.WIRE -> if (wireAhead(master, layout, zone, side).isNotEmpty()) return "message.flansmod.trenches.already"
            TrenchJobKind.CUT -> if (wireAhead(master, layout, zone, side).isEmpty()) return "message.flansmod.trenches.no_wire"
        }
        if (units(master, level).none { it.team == team && it.role == TrenchRole.ENGINEER }) return "message.flansmod.trenches.no_engineer"
        val s = master.settings.trenches
        val cost = when (kind) { TrenchJobKind.BUNKER -> s.bunkerCost; TrenchJobKind.WIRE -> s.wireCost; TrenchJobKind.CUT -> s.cutCost }
        if (funds(master, team) < cost) return "message.flansmod.trenches.no_funds"
        pay(master, team, cost)
        master.updateTrench { it.copy(jobs = it.jobs + TrenchJob(team, kind, zone)) }
        return null
    }

    /** Kill bounty for the killer's side. */
    fun killed(master: BattleMasterBlockEntity, killerTeam: String) {
        if (layout(master)?.side(killerTeam)?.takeIf { it >= 0 } == null) return
        val bounty = master.settings.trenches.killBounty
        master.updateTrench { it.copy(funds = it.funds + (killerTeam to (it.funds[killerTeam] ?: 0) + bounty)) }
    }

    /** Where a commander watching from above starts: high over their own headquarters, looking down the lane. */
    fun commanderView(master: BattleMasterBlockEntity, team: String?): Pair<Vec3, Float>? {
        val layout = layout(master) ?: return null
        val side = layout.side(team).takeIf { it >= 0 } ?: 0
        val home = Vec3.atBottomCenterOf(layout.zones[layout.home(side)].flag)
        val look = layout.axis.scale(layout.forward(side).toDouble())
        val at = home.subtract(look.scale(6.0)).add(0.0, 22.0, 0.0)
        return at to (Math.toDegrees(kotlin.math.atan2(-look.x, look.z))).toFloat()
    }

    // ------------------------------------------------------------------------------------------- status

    /** The command screen's view for a member of [myTeam] (or a spectator: [myTeam] not a side). */
    fun view(master: BattleMasterBlockEntity, level: ServerLevel, player: ServerPlayer, myTeam: String?): TrenchView? {
        val layout = layout(master) ?: return null
        val side = layout.side(myTeam)
        val units = units(master, level)
        val counts = Array(2) { IntArray(layout.zones.size) }
        val moving = Array(2) { IntArray(layout.zones.size) }
        for (u in units) {
            val s = layout.side(u.team).takeIf { it >= 0 } ?: continue
            val z = u.order.coerceIn(0, layout.last)
            counts[s][z]++
            if (!arrived(layout, u)) moving[s][z]++
        }
        for (p in Battles.onlineFighters(master)) {
            val s = layout.side(Battles.data(p)?.team).takeIf { it >= 0 } ?: continue
            if (!p.isAlive || p.isSpectator) continue
            layout.zoneAt(p.position())?.let { counts[s][it]++ }
        }
        val needed = master.settings.captureSeconds.coerceAtLeast(1) * 20f
        val zones = layout.zones.map { z ->
            val capture = master.captures[z.key]
            val ahead = if (z.index < layout.last) wireAhead(master, layout, z.index, 0).isNotEmpty() else false
            TrenchView.Zone(z.label, z.base, owner(master, layout, z.index), z.key in master.trench.bunkers, ahead,
                listOf(counts[0][z.index], counts[1][z.index]), listOf(moving[0][z.index], moving[1][z.index]),
                capture?.let { layout.side(it.team) } ?: -1, capture?.let { it.ticks / needed } ?: 0f)
        }
        val viewTeam = myTeam?.takeIf { side >= 0 } ?: layout.teams[0]
        val faction = master.settings.team(viewTeam)?.faction
        val s = master.settings.trenches
        val hqTicks = s.hqCaptureSeconds.coerceAtLeast(1) * 20f
        return TrenchView(
            side = side, teams = layout.teams,
            funds = layout.teams.map { funds(master, it) },
            income = (0..1).map { income(master, layout, it, s) },
            commanders = layout.teams.map { commander(master, it)?.scoreboardName },
            ai = (0..1).map { aiCommands(master, it, layout) },
            canCommand = side >= 0 && canCommand(master, player, myTeam!!),
            zones = zones,
            units = TrenchUnits.all.filter { it.value.buyable }.entries.sortedWith(compareBy({ it.value.order }, { it.value.cost })).map { (id, u) ->
                TrenchView.UnitView(id.toString(), u.name, u.description, u.cost, readyIn(master, viewTeam, id),
                    u.soldiers.firstNotNullOfOrNull { gunFor(faction, it, null) }?.toString(), u.soldiers.size)
            },
            supports = TrenchSupports.all.entries.sortedWith(compareBy({ it.value.order }, { it.value.cost })).map { (id, d) ->
                TrenchView.SupportView(id.toString(), d.name, d.description, d.cost, readyIn(master, viewTeam, id))
            },
            jobs = master.trench.jobs.map { j ->
                val needed = 20f * when (j.kind) { TrenchJobKind.BUNKER -> s.bunkerSeconds; TrenchJobKind.WIRE -> s.wireSeconds; TrenchJobKind.CUT -> s.cutSeconds }
                TrenchView.JobView(j.kind, j.zone, layout.side(j.team), j.progress / needed, j.engineer != null)
            },
            soldiers = layout.teams.map { t -> units.count { it.team == t } },
            unitCap = s.unitCap,
            jobCosts = listOf(s.bunkerCost, s.wireCost, s.cutCost),
            hq = layout.teams.map { (master.trench.hq[it] ?: 0) / hqTicks },
        )
    }
}
