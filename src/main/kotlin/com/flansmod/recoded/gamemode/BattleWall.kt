package com.flansmod.recoded.gamemode

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.registry.FlansBlocks
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.EntityCollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Border wall block, placed by the Battle Master along its border markers ("border wall" setting). Solid for fighters
 * (players inside the battle, bots, vehicles they drive), air for everyone else. Unbreakable, drops nothing.
 */
class BattleWallBlock(properties: Properties) : Block(properties) {
    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape {
        val entity = (context as? EntityCollisionContext)?.entity ?: return Shapes.empty()
        return if (blocks(entity)) Shapes.block() else Shapes.empty()
    }

    override fun isPathfindable(state: BlockState, type: PathComputationType) = false

    companion object {
        /** Client side: is this the local player inside a battle? Set by the client entrypoint. */
        var clientFighter: (Entity) -> Boolean = { false }

        private fun fighter(entity: Entity) = if (entity.level().isClientSide()) clientFighter(entity) else Battles.fighter(entity) != null

        /** Fighters, and vehicles carrying one. */
        fun blocks(entity: Entity): Boolean = fighter(entity) || (entity is DriveableEntity && entity.passengers.any(::fighter))
    }
}

/** Builds and removes the border wall of a Battle Master, and keeps battle areas without block damage intact. */
object BattleWall {
    const val HEIGHT = 4
    private const val COLUMNS_PER_TICK = 32

    private fun wanted(master: BattleMasterBlockEntity): String =
        master.borderBox()?.takeIf { master.settings.borderWall }?.let { "${it.minX.toInt()},${it.minZ.toInt()},${it.maxX.toInt()},${it.maxZ.toInt()}" } ?: ""

    /**
     * Keeps the wall in step with the setting and the markers: a changed box is torn down and rebuilt one ring of
     * columns just outside the border box, a few columns per tick. Each column fills up to [HEIGHT] blocks of air above
     * the ground (the ground nearest the markers' height), never replacing anything.
     */
    fun tick(master: BattleMasterBlockEntity, level: ServerLevel) {
        val want = wanted(master)
        if (want != master.wallKey && want != master.wallBuilding) {
            tearDown(master, level)
            master.wallKey = ""
            master.wallBuilding = want
            master.wallQueue.clear()
            if (want.isNotEmpty()) {
                val (x0, z0, x1, z1) = want.split(",").map(String::toInt)
                // The box spans blocks x0..x1-1: the wall stands on the ring around it.
                for (x in x0 - 1..x1) {
                    master.wallQueue += BlockPos.asLong(x, 0, z0 - 1)
                    master.wallQueue += BlockPos.asLong(x, 0, z1)
                }
                for (z in z0..z1 - 1) {
                    master.wallQueue += BlockPos.asLong(x0 - 1, 0, z)
                    master.wallQueue += BlockPos.asLong(x1, 0, z)
                }
            }
        }
        if (master.wallBuilding == null) return
        repeat(COLUMNS_PER_TICK) {
            val column = master.wallQueue.removeFirstOrNull()
            if (column == null) {
                master.wallKey = master.wallBuilding!!
                master.wallBuilding = null
                master.setChanged()
                return
            }
            val x = BlockPos.getX(column)
            val z = BlockPos.getZ(column)
            // The ground nearest the markers' height (not the world surface: caves, overhangs, roofs).
            val ref = master.state.border.maxOf { it[1] }
            val ground = (0..16).flatMap { listOf(ref + it, ref - it) }.firstOrNull { y ->
                level.getBlockState(BlockPos(x, y, z)).isAir && !level.getBlockState(BlockPos(x, y - 1, z)).canBeReplaced()
            } ?: ref
            for (y in ground until ground + HEIGHT) {
                val pos = BlockPos(x, y, z)
                if (!level.getBlockState(pos).isAir) continue
                level.setBlock(pos, FlansBlocks.BATTLE_WALL.defaultBlockState(), Block.UPDATE_CLIENTS)
                master.wall += pos.asLong()
            }
        }
        master.setChanged()
    }

    fun tearDown(master: BattleMasterBlockEntity, level: ServerLevel) {
        for (packed in master.wall) {
            val pos = BlockPos.of(packed)
            if (level.getBlockState(pos).`is`(FlansBlocks.BATTLE_WALL)) level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)
        }
        master.wall.clear()
        master.setChanged()
    }

    // ------------------------------------------------------------------------------------------- block damage

    /** Is [pos] inside a running battle's area whose "block damage" setting is off? */
    fun protects(level: Level, pos: Vec3): Boolean =
        BattleMasterBlockEntity.loaded(level).any { it.running && !it.settings.blockDamage && it.area().contains(pos) }

    fun protects(level: Level, pos: BlockPos) = protects(level, Vec3.atCenterOf(pos))

    fun init() {
        // Fighters do not mine the protected battlefield (explosions: ServerLevelMixin, fire and wire: their callers).
        PlayerBlockBreakEvents.BEFORE.register { level, player, pos, _, _ -> Battles.fighter(player) == null || !protects(level, pos) }
    }
}
