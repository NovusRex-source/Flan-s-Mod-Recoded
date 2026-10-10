package com.flansmod.recoded.trenches

import com.flansmod.recoded.gamemode.BattleMasterBlockEntity
import com.flansmod.recoded.gamemode.Post
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * The lane of a Trenches battle, worked out from the Battle Master's flag posts: the first team's base (zone 0), the
 * hill posts as trench lines ordered from there, the second team's base (last zone). The lane runs along [axis] from
 * the first base to the second; a trench line reaches [halfWidth] blocks to either side of its flag ([perp]).
 * Works for the built field ([TrenchField]) as well as for hand-made maps with flag posts.
 */
class TrenchLayout(val teams: List<String>, val zones: List<Zone>, val axis: Vec3, val perp: Vec3, val halfWidth: Int) {
    /** A base or trench line: its flag post, how far along the lane it lies. */
    data class Zone(val index: Int, val key: String, val flag: BlockPos, val base: Boolean, val along: Double, val label: String)

    val last get() = zones.size - 1
    private val origin = Vec3.atBottomCenterOf(zones.first().flag)

    fun along(v: Vec3) = v.subtract(origin).dot(axis)
    fun across(v: Vec3) = v.subtract(origin).dot(perp)

    /** Side of [team]: 0 (first base, moves towards higher zones), 1, or -1 when not one of the two. */
    fun side(team: String?) = teams.indexOf(team)
    fun team(side: Int) = teams.getOrNull(side)

    /** +1 for side 0, -1 for side 1: the zone step towards the enemy. */
    fun forward(side: Int) = if (side == 0) 1 else -1
    fun home(side: Int) = if (side == 0) 0 else last
    fun enemyHome(side: Int) = home(1 - side)

    /** The zone [pos] is in: near a base flag, or in a trench line (a band across the lane around its flag). */
    fun zoneAt(pos: Vec3): Int? = zones.firstOrNull { z ->
        val flag = Vec3.atBottomCenterOf(z.flag)
        abs(pos.y - flag.y) < 4 && if (z.base) pos.subtract(flag).horizontalDistance() <= BASE_RADIUS
        else abs(along(pos) - z.along) <= 2.5 && abs(across(pos) - across(flag)) <= halfWidth + 1
    }?.index

    /**
     * Where soldier number [slot] stands in [zone]: along the trench, alternating left and right of the flag (beyond the
     * width: a second row behind it); around the flag in a base.
     */
    fun slot(zone: Int, slot: Int, side: Int): Vec3 {
        val z = zones[zone]
        val flag = Vec3.atBottomCenterOf(z.flag)
        if (z.base) {
            val ring = BASE_SLOTS[slot % BASE_SLOTS.size]
            return flag.add(perp.scale(ring.first.toDouble())).add(axis.scale(ring.second.toDouble() * forward(side)))
        }
        val perRow = halfWidth * 2
        val i = slot % perRow
        val offset = (i / 2 + 1) * (if (i % 2 == 0) 1 else -1)
        // A second row stands one step back, inside the two-wide channel the field builder digs.
        val row = if ((slot / perRow) % 2 == 1) 1.0 else 0.0
        return flag.add(perp.scale(offset.toDouble())).add(axis.scale(row))
    }

    companion object {
        const val BASE_RADIUS = 6.0
        private val BASE_SLOTS = listOf(1 to 0, -1 to 0, 2 to 1, -2 to 1, 3 to 0, -3 to 0, 1 to 2, -1 to 2, 2 to -1, -2 to -1, 3 to 2, -3 to 2,
            0 to 2, 4 to 1, -4 to 1, 0 to -1)

        /** The lane of [master], or null without two teams that each have a flag post. */
        fun of(master: BattleMasterBlockEntity): TrenchLayout? {
            val teams = master.settings.teams.take(2).map { it.name }
            if (teams.size < 2) return null
            // Bases are the teams' own posts (not trench lines they took: those are hills).
            fun base(team: String) = master.state.posts.values.filter { !it.hill && it.team == team }.minByOrNull { it.label }?.blockPos
            val a = base(teams[0]) ?: return null
            val b = base(teams[1]) ?: return null
            val dir = Vec3(b.x - a.x.toDouble(), 0.0, b.z - a.z.toDouble())
            if (dir.lengthSqr() < 1) return null
            val axis = dir.normalize()
            val perp = Vec3(-axis.z, 0.0, axis.x)
            val origin = Vec3.atBottomCenterOf(a)
            fun alongOf(p: BlockPos) = Vec3.atBottomCenterOf(p).subtract(origin).dot(axis)
            val lines = master.state.posts.values.filter { it.hill }.map { it to alongOf(it.blockPos) }
                .filter { it.second > 0 && it.second < alongOf(b) }.sortedBy { it.second }
            val zones = buildList {
                add(Zone(0, Post.key(a), a, true, 0.0, "HQ"))
                lines.forEachIndexed { i, (post, along) -> add(Zone(i + 1, Post.key(post.blockPos), post.blockPos, false, along, post.label.ifEmpty { "${i + 1}" })) }
                add(Zone(lines.size + 1, Post.key(b), b, true, alongOf(b), "HQ"))
            }
            return TrenchLayout(teams, zones, axis, perp, (master.settings.trenches.width / 2).coerceIn(2, 20))
        }
    }
}
