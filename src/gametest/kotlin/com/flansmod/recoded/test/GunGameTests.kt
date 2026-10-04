package com.flansmod.recoded.test

import com.flansmod.recoded.combat.AttachmentHandler
import com.flansmod.recoded.combat.GunHandler
import com.flansmod.recoded.gun.AttachmentDefinition
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.item.AttachmentItem
import com.flansmod.recoded.item.attachmentId
import com.flansmod.recoded.item.attachments
import com.flansmod.recoded.item.definition
import com.flansmod.recoded.gun.Ammo
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.ammo
import com.flansmod.recoded.registry.FlansDamageTypes
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3

class GunGameTests {
    private fun GameTestHelper.withGun(id: String, def: GunDefinition, mode: GameType = GameType.SURVIVAL): Pair<ServerPlayer, ItemStack> {
        val gunId = Identifier.fromNamespaceAndPath("test", id)
        Guns.replace(Guns.all + (gunId to def))
        // The in-level mock is always creative; infinite materials is what guns actually check.
        val player = makeMockServerPlayerInLevel()
        player.abilities.instabuild = mode == GameType.CREATIVE
        val stack = GunItem.stackFor(gunId)
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        return player to stack
    }

    private fun GameTestHelper.aimAt(player: ServerPlayer, from: Vec3, target: Vec3) {
        player.teleportTo(absoluteVec(from).x, absoluteVec(from).y, absoluteVec(from).z)
        player.lookAt(EntityAnchorArgument.Anchor.EYES, absoluteVec(target))
    }

    private val accurate = GunDefinition("Accurate", damage = 4f, spread = 0f, adsSpread = 0f, gravity = 0.0, velocity = 4.0, magazine = 5)

    @GameTest(maxTicks = 40)
    fun shotDamagesTarget(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("accurate", accurate)
        val zombie = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(6, 1, 1))
        helper.aimAt(player, Vec3(1.5, 1.0, 1.5), zombie.eyePosition.subtract(helper.absoluteVec(Vec3.ZERO)).add(0.0, -0.6, 0.0))

        GunHandler.trigger(player)
        helper.assertValueEqual(stack.ammo, 4, "ammo after one shot")
        helper.succeedWhen {
            helper.assertTrue(zombie.lastDamageSource?.`is`(FlansDamageTypes.GUN) == true, "target should be hit by a bullet")
            helper.assertTrue(zombie.health < zombie.maxHealth, "target should be hurt")
        }
    }

    @GameTest(maxTicks = 30)
    fun fireRateIsEnforced(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("slow", accurate.copy(rpm = 60))
        repeat(5) { GunHandler.trigger(player) }
        helper.assertValueEqual(stack.ammo, 4, "only one shot per cooldown")
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun bulletDropMissesDistantTarget(helper: GameTestHelper) {
        val heavy = accurate.copy(velocity = 1.0, gravity = 0.4, drag = 1.0)
        val (player, _) = helper.withGun("heavy", heavy)
        val zombie = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(6, 1, 1))
        helper.aimAt(player, Vec3(1.5, 1.0, 1.5), Vec3(6.5, 2.6, 1.5))

        GunHandler.trigger(player)
        helper.runAfterDelay(30) {
            helper.assertTrue(zombie.lastDamageSource == null, "dropping bullet should fall short")
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 40)
    fun reloadConsumesAmmoItems(helper: GameTestHelper) {
        val def = accurate.copy(magazine = 30, reloadTicks = 5, ammo = Ammo(Identifier.withDefaultNamespace("iron_nugget"), roundsPerItem = 10))
        val (player, stack) = helper.withGun("reload", def)
        stack.ammo = 0
        player.inventory.add(ItemStack(Items.IRON_NUGGET, 2))

        GunHandler.reload(player)
        helper.succeedWhen {
            helper.assertValueEqual(stack.ammo, 20, "rounds after reload")
            helper.assertValueEqual(player.inventory.countItem(Items.IRON_NUGGET), 0, "nuggets left")
        }
    }

    @GameTest(maxTicks = 20)
    fun creativeReloadIsFree(helper: GameTestHelper) {
        val def = accurate.copy(reloadTicks = 2, ammo = Ammo(Identifier.withDefaultNamespace("iron_nugget")))
        val (player, stack) = helper.withGun("creative", def, GameType.CREATIVE)
        stack.ammo = 0
        GunHandler.reload(player)
        helper.succeedWhen { helper.assertValueEqual(stack.ammo, def.magazine, "creative reload fills magazine") }
    }

    @GameTest(maxTicks = 20)
    fun aimingSlowsMovementUntilGunIsPutAway(helper: GameTestHelper) {
        val (player, _) = helper.withGun("ads", accurate.copy(adsMoveSpeed = 0.5f))
        val speed = player.getAttribute(Attributes.MOVEMENT_SPEED)!!
        val base = speed.value

        GunHandler.setAiming(player, true)
        helper.assertTrue(kotlin.math.abs(speed.value - base * 0.5) < 1e-6, "aiming should halve speed, was ${speed.value} of $base")

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        helper.succeedWhen { helper.assertTrue(speed.value == base, "slowdown should end when the gun is put away") }
    }

    private fun GameTestHelper.withAttachment(player: ServerPlayer, id: String, def: AttachmentDefinition) {
        val attachmentId = Identifier.fromNamespaceAndPath("test", id)
        Attachments.replace(Attachments.all + (attachmentId to def))
        player.setItemInHand(InteractionHand.OFF_HAND, AttachmentItem.stackFor(attachmentId))
    }

    private val scope = AttachmentDefinition("Scope", slot = "sight", spreadMultiplier = 0.5f, adsZoom = 4f)

    @GameTest(maxTicks = 5)
    fun attachmentChangesStats(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("slotted", accurate.copy(spread = 2f, attachmentSlots = listOf("sight")))
        helper.withAttachment(player, "scope", scope)

        helper.assertTrue(AttachmentHandler.install(player), "scope should install")
        helper.assertTrue(stack.attachments["sight"] == Identifier.fromNamespaceAndPath("test", "scope"), "installed sight")
        helper.assertTrue(player.offhandItem.isEmpty, "attachment item should be consumed")
        helper.assertValueEqual(stack.definition!!.spread, 1f, "effective spread")
        helper.assertValueEqual(stack.definition!!.adsZoom, 4f, "effective zoom")
        helper.succeed()
    }

    @GameTest(maxTicks = 5)
    fun attachmentNeedsMatchingSlot(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("no_slots", accurate)
        helper.withAttachment(player, "scope2", scope)

        helper.assertFalse(AttachmentHandler.install(player), "gun without sight slot must reject a scope")
        helper.assertTrue(stack.attachments.isEmpty(), "nothing installed")
        helper.assertFalse(player.offhandItem.isEmpty, "attachment stays in hand")
        helper.succeed()
    }

    @GameTest(maxTicks = 5)
    fun removingAttachmentsReturnsThem(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("removable", accurate.copy(attachmentSlots = listOf("sight")))
        helper.withAttachment(player, "scope3", scope)
        AttachmentHandler.install(player)

        helper.assertTrue(AttachmentHandler.removeAll(player), "remove should succeed")
        helper.assertTrue(stack.attachments.isEmpty(), "gun should be bare")
        helper.assertTrue(player.offhandItem.attachmentId == Identifier.fromNamespaceAndPath("test", "scope3"), "scope back in offhand")
        helper.assertValueEqual(stack.definition!!.spread, accurate.spread, "stats back to base")
        helper.succeed()
    }

    @GameTest(maxTicks = 20)
    fun emptyGunDoesNotFire(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("empty", accurate.copy(ammo = Ammo(Identifier.withDefaultNamespace("iron_nugget"))))
        stack.ammo = 0
        val zombie = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(4, 1, 1))
        helper.aimAt(player, Vec3(1.5, 1.0, 1.5), Vec3(4.5, 2.0, 1.5))
        GunHandler.trigger(player)
        helper.runAfterDelay(10) {
            helper.assertTrue(zombie.lastDamageSource == null, "no bullet without ammo")
            helper.succeed()
        }
    }
}
