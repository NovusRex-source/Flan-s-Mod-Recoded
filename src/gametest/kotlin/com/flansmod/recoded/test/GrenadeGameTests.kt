package com.flansmod.recoded.test

import com.flansmod.recoded.entity.GrenadeEntity
import com.flansmod.recoded.gun.GrenadeDefinition
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.registry.FlansEntities
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

class GrenadeGameTests {
    private fun GameTestHelper.throwGrenade(id: String, def: GrenadeDefinition, at: Vec3, velocity: Vec3 = Vec3.ZERO): GrenadeEntity {
        val grenadeId = Identifier.fromNamespaceAndPath("test", id)
        Grenades.replace(Grenades.all + (grenadeId to def))
        return GrenadeEntity(FlansEntities.GRENADE, level).apply {
            setItem(GrenadeItem.stackFor(grenadeId))
            absoluteVec(at).let { setPos(it.x, it.y, it.z) }
            deltaMovement = velocity
            level.addFreshEntity(this)
        }
    }

    // Explosions reach into neighbouring test areas; the extra padding keeps other tests' mobs out of range.
    @GameTest(maxTicks = 40, padding = 8)
    fun fragExplodesAfterFuseWithoutBreakingBlocks(helper: GameTestHelper) {
        helper.setBlock(BlockPos(3, 1, 3), Blocks.STONE)
        val husk = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(5, 1, 3))
        val grenade = helper.throwGrenade("frag", GrenadeDefinition("Frag", fuseTicks = 10, explosion = GrenadeDefinition.Explosion(power = 2f)), Vec3(3.5, 2.0, 3.5))

        helper.runAfterDelay(5) { helper.assertTrue(grenade.isAlive, "should not explode before the fuse") }
        helper.succeedWhen {
            helper.assertTrue(grenade.isRemoved, "grenade should be gone after detonating")
            helper.assertTrue(husk.health < husk.maxHealth, "explosion should hurt nearby mobs")
            helper.assertBlockPresent(Blocks.STONE, BlockPos(3, 1, 3))
        }
    }

    @GameTest(maxTicks = 30, padding = 8)
    fun contactGrenadeDetonatesOnImpact(helper: GameTestHelper) {
        val grenade = helper.throwGrenade("impact", GrenadeDefinition("Impact", fuseTicks = 1000, contact = true), Vec3(3.5, 4.0, 3.5), Vec3(0.0, -0.5, 0.0))
        helper.succeedWhen { helper.assertTrue(grenade.hasDetonated, "should detonate on hitting the floor long before the fuse") }
    }

    @GameTest(maxTicks = 40)
    fun grenadeBouncesInsteadOfBreakingOnImpact(helper: GameTestHelper) {
        val grenade = helper.throwGrenade("bouncy", GrenadeDefinition("Bouncy", fuseTicks = 1000), Vec3(3.5, 4.0, 3.5), Vec3(0.2, -0.5, 0.0))
        helper.runAfterDelay(30) {
            helper.assertTrue(grenade.isAlive && !grenade.hasDetonated, "grenade should survive hitting the floor")
            helper.assertTrue(grenade.deltaMovement.length() < 0.1, "grenade should come to rest, speed ${grenade.deltaMovement.length()}")
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 30)
    fun flashbangBlindsOnlyThoseWithLineOfSight(helper: GameTestHelper) {
        val seeing = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(1, 1, 1))
        val hidden = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(6, 1, 1))
        for (y in 1..3) for (z in 0..2) helper.setBlock(BlockPos(5, y, z), Blocks.STONE) // wall between grenade and the hidden husk
        helper.throwGrenade("flash", GrenadeDefinition("Flash", fuseTicks = 5, flash = GrenadeDefinition.Flash(radius = 10.0)), Vec3(3.5, 1.2, 1.5))

        helper.succeedWhen {
            helper.assertTrue(seeing.hasEffect(MobEffects.BLINDNESS), "mob in sight should be blinded")
            helper.assertFalse(hidden.hasEffect(MobEffects.BLINDNESS), "mob behind a wall must not be blinded")
        }
    }

    @GameTest(maxTicks = 5)
    fun basicPackHasGrenades(helper: GameTestHelper) {
        for (name in listOf("rocket", "40mm_he")) {
            helper.assertFalse(Grenades[Identifier.fromNamespaceAndPath("flansbasic", name)]!!.throwable, "$name is a launcher projectile")
        }
        for (name in listOf("frag", "smoke", "flashbang", "molotov")) {
            val id = Identifier.fromNamespaceAndPath("flansbasic", name)
            helper.assertTrue(Grenades[id] != null, "missing grenade $name")
            helper.assertTrue(helper.level.server.recipeManager.byKey(ResourceKey.create(Registries.RECIPE, id.withPrefix("grenade_"))).isPresent, "$name has no recipe")
        }
        helper.succeed()
    }
}
