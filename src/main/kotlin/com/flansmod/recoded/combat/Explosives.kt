package com.flansmod.recoded.combat

import com.flansmod.recoded.gun.GrenadeDefinition
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.phys.Vec3

/** Detonations of grenades, mines and launcher rounds: vanilla explosion plus fire and limited block damage. */
object Explosives {
    fun explode(level: ServerLevel, source: Entity, at: Vec3, e: GrenadeDefinition.Explosion) {
        level.explode(source, at.x, at.y, at.z, e.power, e.fire, if (e.breakBlocks) Level.ExplosionInteraction.TNT else Level.ExplosionInteraction.NONE)
        // Battles without block damage keep their area intact (the explosion itself: ServerLevelMixin).
        if (com.flansmod.recoded.gamemode.BattleWall.protects(level, at)) return
        // Vanilla only sets fire where the explosion destroyed blocks; incendiaries must burn either way.
        if (e.fire) Incendiary.spreadFire(level, at, e.power.toDouble() + 1.0, source.random)
        if (!e.breakBlocks) e.blockDamage?.let { damageSurroundings(level, source, at, it) }
    }

    /** Breaks weak blocks around [at] by chance, closer ones more likely; see [GrenadeDefinition.BlockDamage]. */
    fun damageSurroundings(level: ServerLevel, source: Entity, at: Vec3, damage: GrenadeDefinition.BlockDamage) {
        if (!level.gameRules.get(GameRules.TNT_EXPLODES)) return
        val r = damage.radius
        val centre = BlockPos.containing(at)
        val reach = kotlin.math.ceil(r).toInt()
        val random = source.random
        for (pos in BlockPos.betweenClosed(centre.offset(-reach, -reach, -reach), centre.offset(reach, reach, reach))) {
            val distance = Vec3.atCenterOf(pos).distanceTo(at)
            if (distance > r) continue
            val state = level.getBlockState(pos)
            if (state.isAir || state.getDestroySpeed(level, pos) < 0) continue // unbreakable
            if (state.block.explosionResistance > damage.maxResistance) continue
            if (random.nextDouble() >= damage.chance * (1 - distance / r * 0.7)) continue
            level.destroyBlock(pos.immutable(), random.nextDouble() < damage.drops, source)
        }
    }
}
