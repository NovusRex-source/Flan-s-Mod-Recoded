package com.flansmod.recoded.test

import com.flansmod.recoded.combat.AttachmentHandler
import com.flansmod.recoded.combat.GunHandler
import com.flansmod.recoded.entity.GrenadeEntity
import com.flansmod.recoded.gun.AmmoDefinition
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.AttachmentDefinition
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.gun.GrenadeDefinition
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.MagazineDefinition
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.Scope
import com.flansmod.recoded.gun.Transform
import com.flansmod.recoded.item.AmmoItem
import com.flansmod.recoded.item.AttachmentItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.item.ammo
import com.flansmod.recoded.item.attachmentId
import com.flansmod.recoded.item.attachments
import com.flansmod.recoded.item.definition
import com.flansmod.recoded.item.gunId
import com.flansmod.recoded.item.loadedMagazine
import com.flansmod.recoded.registry.FlansDamageTypes
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3

class GunGameTests {
    private fun test(path: String) = Identifier.fromNamespaceAndPath("test", path)
    private fun basic(path: String) = Identifier.fromNamespaceAndPath("flansbasic", path)

    private val round = test("round")

    /**
     * Registers [def] as gun `test:<id>` with its own magazine `test:<id>_mag` ([capacity] rounds of `test:round`)
     * and puts it, fully loaded, into the main hand of a mock player.
     */
    private fun GameTestHelper.withGun(id: String, def: GunDefinition, mode: GameType = GameType.SURVIVAL, capacity: Int = 5): Pair<ServerPlayer, ItemStack> {
        val gunId = test(id)
        val magId = test("${id}_mag")
        AmmoTypes.replace(AmmoTypes.all + (round to AmmoDefinition("Round", caliber = "test")))
        Magazines.replace(Magazines.all + (magId to MagazineDefinition("Mag", caliber = "test", capacity = capacity)))
        Guns.replace(Guns.all + (gunId to def.copy(magazines = listOf(magId))))
        // The in-level mock is always creative; infinite materials is what guns actually check.
        val player = makeMockServerPlayerInLevel()
        player.abilities.instabuild = mode == GameType.CREATIVE
        val stack = GunItem.stackFor(gunId)
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        return player to stack
    }

    private fun magazineOf(gun: ItemStack, rounds: Int, ammo: Identifier = round) =
        MagazineItem.stackFor(MagazineContents(gun.loadedMagazine!!.magazine, ammo, rounds))

    private fun GameTestHelper.aimAt(player: ServerPlayer, from: Vec3, target: Vec3) {
        player.teleportTo(absoluteVec(from).x, absoluteVec(from).y, absoluteVec(from).z)
        player.lookAt(EntityAnchorArgument.Anchor.EYES, absoluteVec(target))
    }

    private val accurate = GunDefinition("Accurate", damage = 4f, spread = 0f, adsSpread = 0f, gravity = 0.0, velocity = 4.0, reloadTicks = 5)

    // ---------------------------------------------------------------------------------- shooting

    @GameTest(maxTicks = 40)
    fun shotDamagesTarget(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("accurate", accurate)
        val target = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(6, 1, 1))
        helper.aimAt(player, Vec3(1.5, 1.0, 1.5), Vec3(6.5, 2.0, 1.5))

        GunHandler.trigger(player)
        helper.assertValueEqual(stack.ammo, 4, "rounds left in the magazine")
        helper.succeedWhen {
            helper.assertTrue(target.lastDamageSource?.`is`(FlansDamageTypes.GUN) == true, "target should be hit by a bullet")
            helper.assertTrue(target.health < target.maxHealth, "target should be hurt")
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
        val (player, _) = helper.withGun("heavy", accurate.copy(velocity = 1.0, gravity = 0.4, drag = 1.0))
        val target = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(6, 1, 1))
        helper.aimAt(player, Vec3(1.5, 1.0, 1.5), Vec3(6.5, 2.6, 1.5))

        GunHandler.trigger(player)
        helper.runAfterDelay(30) {
            helper.assertTrue(target.lastDamageSource == null, "dropping bullet should fall short")
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 20)
    fun gunWithoutMagazineDoesNotFire(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("unloaded", accurate)
        stack.loadedMagazine = null
        val target = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(4, 1, 1))
        helper.aimAt(player, Vec3(1.5, 1.0, 1.5), Vec3(4.5, 2.0, 1.5))
        GunHandler.trigger(player)
        helper.runAfterDelay(10) {
            helper.assertTrue(target.lastDamageSource == null, "no bullet without a magazine")
            helper.assertTrue(stack.loadedMagazine == null, "survival reload without magazines must not conjure one")
            helper.succeed()
        }
    }

    // ---------------------------------------------------------------------------------- magazines

    @GameTest(maxTicks = 30)
    fun reloadSwapsMagazineAndReturnsTheOldOne(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("swap", accurate)
        stack.ammo = 2
        player.inventory.setItem(9, magazineOf(stack, 5))

        GunHandler.reload(player)
        helper.succeedWhen {
            helper.assertValueEqual(stack.ammo, 5, "full magazine inserted")
            val old = player.inventory.getItem(9).loadedMagazine
            helper.assertTrue(old?.rounds == 2, "old magazine with 2 rounds should be back in slot 9, found $old")
        }
    }

    @GameTest(maxTicks = 30)
    fun reloadPicksTheFullestMagazine(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("fullest", accurate, capacity = 10)
        stack.ammo = 0
        player.inventory.setItem(9, magazineOf(stack, 3))
        player.inventory.setItem(10, magazineOf(stack, 8))
        player.inventory.setItem(11, magazineOf(stack, 0))

        GunHandler.reload(player)
        helper.succeedWhen {
            helper.assertValueEqual(stack.ammo, 8, "fullest magazine chosen")
            helper.assertTrue(player.inventory.getItem(10).loadedMagazine?.rounds == 0, "empty old magazine goes where the new one was")
        }
    }

    @GameTest(maxTicks = 20)
    fun creativeReloadIsFree(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("creative", accurate, GameType.CREATIVE)
        stack.ammo = 0
        GunHandler.reload(player)
        helper.succeedWhen { helper.assertValueEqual(stack.ammo, 5, "creative reload refills without magazines") }
    }

    @GameTest(maxTicks = 5)
    fun unloadReturnsTheMagazine(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("unload", accurate)
        stack.ammo = 3
        GunHandler.unload(player)
        helper.assertTrue(stack.loadedMagazine == null, "gun should be empty")
        val returned = player.inventory.nonEquipmentItems.firstNotNullOfOrNull { it.takeIf { s -> s.item is MagazineItem }?.loadedMagazine }
        helper.assertTrue(returned?.rounds == 3, "magazine with 3 rounds should be in the inventory, found $returned")
        helper.succeed()
    }

    @GameTest(maxTicks = 5)
    fun magazinesLoadOnlyMatchingCaliber(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("fill", accurate, capacity = 10)
        val otherCaliber = test("other_round")
        AmmoTypes.replace(AmmoTypes.all + (otherCaliber to AmmoDefinition("Other", caliber = "other")))
        val magazine = magazineOf(stack, 0)
        player.inventory.add(AmmoItem.stackFor(otherCaliber, 20))
        player.inventory.add(AmmoItem.stackFor(round, 4))

        MagazineItem.fill(player, magazine, magazine.loadedMagazine!!)
        val contents = magazine.loadedMagazine!!
        helper.assertTrue(contents.rounds == 4 && contents.ammo == round, "magazine should take the 4 matching rounds, has $contents")
        helper.assertValueEqual(player.inventory.nonEquipmentItems.filter { it.item is AmmoItem }.sumOf { it.count }, 20, "other caliber untouched")

        MagazineItem.unload(player, magazine, contents)
        helper.assertValueEqual(magazine.loadedMagazine!!.rounds, 0, "unloaded")
        helper.assertValueEqual(player.inventory.nonEquipmentItems.filter { it.item is AmmoItem }.sumOf { it.count }, 24, "rounds returned")
        helper.succeed()
    }

    // ---------------------------------------------------------------------------------- ammo types

    @GameTest(maxTicks = 40)
    fun incendiaryArmorPiercingAmmo(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("special", accurate)
        val special = test("api")
        AmmoTypes.replace(AmmoTypes.all + (special to AmmoDefinition("API", caliber = "test", armorPiercing = true, fireSeconds = 5f)))
        stack.loadedMagazine = stack.loadedMagazine!!.copy(ammo = special)
        val target = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(6, 1, 1))
        helper.aimAt(player, Vec3(1.5, 1.0, 1.5), Vec3(6.5, 2.0, 1.5))

        GunHandler.trigger(player)
        helper.succeedWhen {
            helper.assertTrue(target.lastDamageSource?.`is`(FlansDamageTypes.GUN_AP) == true, "armour piercing damage type")
            helper.assertTrue(target.isOnFire, "incendiary round should ignite")
        }
    }

    @GameTest(maxTicks = 40)
    fun launcherAmmoFiresAProjectile(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("launcher", accurate.copy(velocity = 1.0))
        val rocket = test("rocket")
        Grenades.replace(Grenades.all + (rocket to GrenadeDefinition("Rocket", throwable = false, contact = true, gravity = 0.0)))
        val rocketAmmo = test("rocket_round")
        AmmoTypes.replace(AmmoTypes.all + (rocketAmmo to AmmoDefinition("Rocket", caliber = "test", projectile = rocket)))
        stack.loadedMagazine = stack.loadedMagazine!!.copy(ammo = rocketAmmo)
        helper.aimAt(player, Vec3(1.5, 1.0, 1.5), Vec3(6.5, 2.0, 1.5))

        GunHandler.trigger(player)
        helper.assertTrue(helper.level.getEntitiesOfClass(GrenadeEntity::class.java, player.boundingBox.inflate(4.0)).isNotEmpty(), "projectile spawned")
        helper.assertValueEqual(stack.ammo, 4, "one round used")
        helper.succeed()
    }

    // ---------------------------------------------------------------------------------- aiming, scopes, attachments

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

    @GameTest(maxTicks = 20)
    fun nightVisionScopeOnlyWhileAiming(helper: GameTestHelper) {
        val (player, _) = helper.withGun("nv", accurate.copy(scope = Scope(nightVision = true)))
        GunHandler.setAiming(player, true)
        helper.runAfterDelay(2) {
            helper.assertTrue(player.hasEffect(MobEffects.NIGHT_VISION), "night vision while aiming")
            GunHandler.setAiming(player, false)
        }
        helper.runAfterDelay(5) {
            helper.assertFalse(player.hasEffect(MobEffects.NIGHT_VISION), "night vision removed after aiming")
            helper.succeed()
        }
    }

    private fun GameTestHelper.withAttachment(player: ServerPlayer, id: String, def: AttachmentDefinition) {
        val attachmentId = test(id)
        Attachments.replace(Attachments.all + (attachmentId to def))
        player.setItemInHand(InteractionHand.OFF_HAND, AttachmentItem.stackFor(attachmentId))
    }

    private val scope = AttachmentDefinition("Scope", slot = "sight", spreadMultiplier = 0.5f, adsZoom = 4f, scope = Scope(nightVision = true))

    @GameTest(maxTicks = 5)
    fun attachmentChangesStats(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("slotted", accurate.copy(spread = 2f, attachmentSlots = listOf("sight")))
        helper.withAttachment(player, "scope", scope)

        helper.assertTrue(AttachmentHandler.install(player), "scope should install")
        helper.assertTrue(stack.attachments["sight"] == test("scope"), "installed sight")
        helper.assertTrue(player.offhandItem.isEmpty, "attachment item should be consumed")
        helper.assertValueEqual(stack.definition!!.spread, 1f, "effective spread")
        helper.assertValueEqual(stack.definition!!.adsZoom, 4f, "effective zoom")
        helper.assertTrue(stack.definition!!.scope?.nightVision == true, "scope effects come from the sight")
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
        helper.assertTrue(player.offhandItem.attachmentId == test("scope3"), "scope back in offhand")
        helper.assertValueEqual(stack.definition!!.spread, accurate.spread, "stats back to base")
        helper.succeed()
    }

    @GameTest(maxTicks = 5)
    fun redDotLowersAimPose(helper: GameTestHelper) {
        val (player, stack) = helper.withGun("ads_height", accurate.copy(attachmentSlots = listOf("sight")))
        helper.withAttachment(player, "high_sight", scope.copy(adsHeight = 2f))
        val before = stack.definition!!.resolvedModel(stack.gunId!!).display.getValue(Transform.ADS).translation[1]
        AttachmentHandler.install(player)
        val after = stack.definition!!.resolvedModel(stack.gunId!!).display.getValue(Transform.ADS).translation[1]
        helper.assertTrue(kotlin.math.abs((before - after) - 2f) < 1e-4, "aim pose should drop by 2px, was $before -> $after")
        helper.succeed()
    }

    // ---------------------------------------------------------------------------------- built-in pack

    /** The built-in Basic pack is enabled in new worlds and internally consistent. */
    @GameTest(maxTicks = 5)
    fun basicPackIsComplete(helper: GameTestHelper) {
        val guns = Guns.all.keys.filter { it.namespace == "flansbasic" }
        helper.assertTrue(guns.size >= 20, "expected the famous weapon line-up, found ${guns.size}")
        for (id in listOf("glock17", "m1911", "deagle", "mp5", "p90", "m4a1", "ak47", "scar_h", "svd", "awm", "barrett", "m870", "aa12", "m249", "rpg7")) {
            helper.assertTrue(Guns[basic(id)] != null, "missing $id")
        }
        for (id in guns) {
            val magazines = GunItem.acceptedMagazines(id)
            helper.assertTrue(magazines.isNotEmpty(), "$id accepts no magazine")
            for (mag in magazines) {
                helper.assertTrue(AmmoTypes.all.values.any(Magazines[mag]!!::accepts), "no ammo for magazine $mag")
            }
            helper.assertTrue(helper.level.server.recipeManager.byKey(ResourceKey.create(Registries.RECIPE, id)).isPresent, "$id has no recipe")
        }
        for ((id, ammo) in AmmoTypes.all) ammo.projectile?.let { helper.assertTrue(Grenades[it] != null, "$id fires unknown projectile $it") }
        helper.succeed()
    }

    @GameTest(maxTicks = 80)
    fun glockEndToEnd(helper: GameTestHelper) {
        val player = helper.makeMockServerPlayerInLevel()
        player.abilities.instabuild = false
        val gun = GunItem.stackFor(basic("glock17"), loaded = false)
        player.setItemInHand(InteractionHand.MAIN_HAND, gun)
        val magazine = MagazineItem.stackFor(basic("glock_17"))
        player.inventory.add(AmmoItem.stackFor(basic("9mm"), 20))
        player.inventory.add(ItemStack(Items.IRON_NUGGET, 5))

        MagazineItem.fill(player, magazine, magazine.loadedMagazine!!)
        helper.assertValueEqual(magazine.loadedMagazine!!.rounds, 17, "magazine filled to capacity")
        player.inventory.add(magazine)
        GunHandler.reload(player)
        helper.succeedWhen {
            helper.assertValueEqual(gun.ammo, 17, "magazine inserted")
            helper.assertValueEqual(player.inventory.nonEquipmentItems.filter { it.item is AmmoItem }.sumOf { it.count }, 3, "3 rounds left over")
            helper.assertValueEqual(player.inventory.countItem(Items.IRON_NUGGET), 5, "unrelated items untouched")
        }
    }
}
