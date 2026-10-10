package com.flansmod.recoded.trenches

import com.flansmod.recoded.gamemode.BattleMasterBlockEntity
import com.flansmod.recoded.gamemode.SoldierEntity
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import java.util.WeakHashMap
import kotlin.math.ceil

/**
 * The computer commander of a side without a human one. Every few seconds (faster on harder levels) it:
 * 1. sends a squad, keeping a mix of riflemen, machine guns, assault troops, mortars, snipers and engineers and a
 *    reserve of funds for supports (none on hard);
 * 2. brings soldiers behind the front up to it (mortars and snipers stay a trench behind), pushes on from the front
 *    trench when it clearly outnumbers what waits in the next one, storms the enemy headquarters when it is weakly held,
 *    falls back when overwhelmed;
 * 3. calls a barrage or gas on the strongest enemy trench ahead;
 * 4. has engineers build a bunker in the front trench, wire in front of it, and cut the wire in the way.
 */
object TrenchAi {
    private val next = WeakHashMap<BattleMasterBlockEntity, LongArray>()

    /** How much a soldier counts in a fight. */
    private fun power(role: TrenchRole?) = when (role) {
        TrenchRole.MACHINE_GUNNER -> 2.0
        TrenchRole.ASSAULT -> 1.5
        TrenchRole.SNIPER -> 1.2
        TrenchRole.MORTAR -> 0.5
        TrenchRole.ENGINEER -> 0.6
        else -> 1.0
    }

    /** Wanted share of each role among the side's soldiers. */
    private val MIX = mapOf(TrenchRole.RIFLEMAN to 0.42, TrenchRole.MACHINE_GUNNER to 0.2, TrenchRole.ASSAULT to 0.12, TrenchRole.MORTAR to 0.1,
        TrenchRole.SNIPER to 0.06, TrenchRole.ENGINEER to 0.06, TrenchRole.OFFICER to 0.04)

    private val SUPPORT_ROLES = setOf(TrenchRole.MORTAR, TrenchRole.SNIPER, TrenchRole.ENGINEER)

    fun tick(master: BattleMasterBlockEntity, level: ServerLevel, layout: TrenchLayout, side: Int, units: List<SoldierEntity>) {
        val ai = master.settings.trenches.ai
        val times = next.getOrPut(master) { LongArray(2) }
        if (level.gameTime < times[side]) return
        times[side] = level.gameTime + when (ai) { TrenchAiLevel.EASY -> 100; TrenchAiLevel.HARD -> 30; else -> 60 }
        val team = layout.team(side) ?: return
        val reserve = when (ai) { TrenchAiLevel.EASY -> 250; TrenchAiLevel.HARD -> 0; else -> 120 }

        repeat(if (ai == TrenchAiLevel.HARD) 2 else 1) { send(master, level, team, units, reserve) }
        val mine = TrenchRules.units(master, level).filter { it.team == team }
        move(master, level, layout, side, team, mine, units)
        support(master, level, layout, side, team, units, reserve)
        if (ai != TrenchAiLevel.EASY) engineer(master, level, layout, side, team, mine, reserve)
    }

    private fun funds(master: BattleMasterBlockEntity, team: String) = master.state.trench.funds[team] ?: 0

    /** The squad whose main role is furthest below its share, if it can be afforded and is ready. */
    private fun send(master: BattleMasterBlockEntity, level: ServerLevel, team: String, units: List<SoldierEntity>, reserve: Int) {
        val mine = units.filter { it.team == team }
        val total = mine.size.coerceAtLeast(1).toDouble()
        val share = mine.groupingBy { it.role }.eachCount()
        val funds = funds(master, team)
        // With few soldiers in the field the reserve is spent too.
        val keep = if (mine.size < 6) 0 else reserve
        val choice = TrenchUnits.all.filter { (id, u) ->
            u.buyable && funds - u.cost >= keep && TrenchRules.readyIn(master, team, id) == 0
        }.maxByOrNull { (_, u) ->
            val role = u.soldiers.firstOrNull()?.role
            (MIX[role] ?: 0.0) - (share[role] ?: 0) / total + level.random.nextDouble() * 0.08
        } ?: return
        TrenchRules.buy(master, level, team, choice.key)
    }

    private fun strength(list: List<SoldierEntity>) = list.sumOf { power(it.role) }

    private fun move(master: BattleMasterBlockEntity, level: ServerLevel, layout: TrenchLayout, side: Int, team: String,
                     mine: List<SoldierEntity>, all: List<SoldierEntity>) {
        val fw = layout.forward(side)
        val home = layout.home(side)
        val enemyHome = layout.enemyHome(side)
        val fighters = mine.filter { it.role !in SUPPORT_ROLES }
        val front = fighters.maxOfOrNull { it.order * fw }?.let { it * fw } ?: return
        val enemies = TrenchRules.enemiesByZone(master, level, layout, side, all)
        fun enemyPower(zone: Int) = enemies[zone].orEmpty().sumOf { if (it is SoldierEntity) power(it.role) else 1.5 }
        val atFront = fighters.filter { it.order == front }
        val ownFront = strength(atFront)

        // Fall back when overwhelmed.
        val threat = enemyPower(front) + enemyPower(front + fw) * 0.5
        if (front != home && ownFront < 4 && threat > ownFront * 2.5 + 2) {
            atFront.forEach { it.order = front - fw }
            return
        }

        // Push on from the front.
        val next = front + fw
        if (next in 0..layout.last) {
            val defenders = enemyPower(next)
            val arrived = atFront.count { TrenchRules.arrived(layout, it) }
            val storm = next == enemyHome
            val enough = if (storm) ownFront >= 6 && defenders <= ownFront * 0.5 else ownFront >= maxOf(4.0, defenders * 1.6 + 2)
            if (enough && arrived >= atFront.size * 0.6) {
                // Clear wire in the way first, when engineers can.
                if (!storm && TrenchRules.wireAhead(master, layout, front, side).isNotEmpty() && mine.any { it.role == TrenchRole.ENGINEER } &&
                    master.state.trench.jobs.none { it.team == team && it.kind == TrenchJobKind.CUT } && TrenchRules.owner(master, layout, front) == side) {
                    TrenchRules.job(master, level, team, TrenchJobKind.CUT, front)
                } else {
                    val goers = atFront.sortedBy { it.serial }.take(if (storm) atFront.size else ceil(atFront.size * 0.7).toInt())
                    goers.forEach { it.order = next }
                }
            }
        }

        // Bring the rest up: fighters to the front, mortars and snipers to the trench behind it.
        for (u in mine) {
            if (u.role == TrenchRole.ENGINEER && master.state.trench.jobs.any { it.engineer == u.stringUUID }) continue
            val goal = if (u.role in SUPPORT_ROLES) front - fw else front
            if (goal == u.order || !TrenchRules.arrived(layout, u)) continue
            if ((goal - u.order) * fw > 0) {
                val step = u.order + fw
                // Never walk into a trench the enemy holds and fills.
                if (TrenchRules.owner(master, layout, step) == 1 - side && enemyPower(step) > 0) continue
                u.order = step
            }
        }
    }

    /** Barrage or gas on the enemy trench ahead with the most soldiers in it, if it is worth it. */
    private fun support(master: BattleMasterBlockEntity, level: ServerLevel, layout: TrenchLayout, side: Int, team: String,
                        all: List<SoldierEntity>, reserve: Int) {
        val fw = layout.forward(side)
        val front = all.filter { it.team == team }.maxOfOrNull { it.order * fw }?.let { it * fw } ?: layout.home(side)
        val enemies = TrenchRules.enemiesByZone(master, level, layout, side, all)
        val ownZones = all.filter { it.team == team }.map { it.order }.toSet()
        val (zone, there) = (1..3).map { front + it * fw }.filter { it in 0..layout.last && it !in ownZones }
            .map { it to enemies[it].orEmpty().size }.maxByOrNull { it.second } ?: return
        if (there < 4) return
        val funds = funds(master, team)
        val options = TrenchSupports.all.entries.filter { (id, d) -> funds - d.cost >= reserve / 2 && TrenchRules.readyIn(master, team, id) == 0 }
        val pick = options.firstOrNull { it.value.type == TrenchSupportType.GAS && there >= 6 } ?: options.firstOrNull { it.value.type == TrenchSupportType.BARRAGE }
            ?: return
        TrenchRules.support(master, level, team, pick.key, zone)
    }

    /** A bunker in the front trench, then wire in front of it; buys an engineer when there is work and none. */
    private fun engineer(master: BattleMasterBlockEntity, level: ServerLevel, layout: TrenchLayout, side: Int, team: String,
                         mine: List<SoldierEntity>, reserve: Int) {
        val s = master.settings.trenches
        val held = layout.zones.filter { !it.base && TrenchRules.owner(master, layout, it.index) == side }.map { it.index }
        val front = held.maxByOrNull { it * layout.forward(side) } ?: return
        val trench = master.state.trench
        if (trench.jobs.any { it.team == team }) return
        val funds = funds(master, team)
        val work = when {
            layout.zones[front].key !in trench.bunkers && funds >= s.bunkerCost + reserve -> TrenchJobKind.BUNKER
            TrenchRules.wireAhead(master, layout, front, side).isEmpty() && funds >= s.wireCost + reserve -> TrenchJobKind.WIRE
            else -> return
        }
        if (mine.none { it.role == TrenchRole.ENGINEER }) {
            engineerUnit()?.let { TrenchRules.buy(master, level, team, it) }
            return
        }
        TrenchRules.job(master, level, team, work, front)
    }

    private fun engineerUnit(): Identifier? = TrenchUnits.all.entries.filter { it.value.buyable && it.value.soldiers.firstOrNull()?.role == TrenchRole.ENGINEER }
        .minByOrNull { it.value.cost }?.key
}
