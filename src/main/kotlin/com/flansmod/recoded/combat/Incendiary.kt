package com.flansmod.recoded.combat

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.phys.Vec3

/** Fire placement for incendiary explosions and rounds, using vanilla fire blocks and placement rules. */
object Incendiary {
    /** Lights roughly a third of the free spots within [radius] (like vanilla's fiery explosions). */
    fun spreadFire(level: ServerLevel, center: Vec3, radius: Double, random: RandomSource) {
        val r = radius.toInt()
        val origin = BlockPos.containing(center)
        for (pos in BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            if (pos.distToCenterSqr(center) > radius * radius || random.nextInt(3) != 0) continue
            ignite(level, pos.immutable())
        }
    }

    /** Places fire at [pos] if it is air with something burnable or solid next to it. */
    fun ignite(level: ServerLevel, pos: BlockPos): Boolean {
        if (!level.getBlockState(pos).isAir || !BaseFireBlock.canBePlacedAt(level, pos, Direction.UP)) return false
        if (com.flansmod.recoded.gamemode.BattleWall.protects(level, pos)) return false
        return level.setBlockAndUpdate(pos, BaseFireBlock.getState(level, pos))
    }
}
