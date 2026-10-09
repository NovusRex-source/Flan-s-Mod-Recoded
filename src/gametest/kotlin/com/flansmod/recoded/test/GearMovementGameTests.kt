package com.flansmod.recoded.test

import com.flansmod.recoded.gear.BackpackMenu
import com.flansmod.recoded.gear.GearDefinition
import com.flansmod.recoded.gear.GearEffect
import com.flansmod.recoded.gear.GearItem
import com.flansmod.recoded.gear.GearSlots
import com.flansmod.recoded.gear.GearType
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import com.flansmod.recoded.gear.gear
import com.flansmod.recoded.gun.AttachmentDefinition
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.gun.Gear
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.attachments
import com.flansmod.recoded.movement.Stance
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** Prone, set-down bipods, backpacks and medical gear. */
class GearMovementGameTests {
    private fun test(path: String) = Identifier.fromNamespaceAndPath("test", path)

    private fun gear(id: String, def: GearDefinition): ItemStack {
        Gear.replace(Gear.all + (test(id) to def))
        return GearItem.stackFor(test(id))
    }

    @GameTest(maxTicks = 40)
    fun proneLowersThePlayerAndSetsDownTheBipod(helper: GameTestHelper) {
        Attachments.replace(Attachments.all + (test("bipod") to AttachmentDefinition("Bipod", "underbarrel",
            deploy = AttachmentDefinition.Deploy(spreadMultiplier = 0.5f, recoilMultiplier = 0.4f))))
        Guns.replace(Guns.all + (test("lmg") to GunDefinition("LMG", attachmentSlots = listOf("underbarrel"))))
        val gun = GunItem.stackFor(test("lmg")).apply { attachments = mapOf("underbarrel" to test("bipod")) }
        val player = helper.makeMockServerPlayerInLevel()
        helper.absoluteVec(net.minecraft.world.phys.Vec3(2.5, 1.0, 2.5)).let { player.snapTo(it.x, it.y, it.z, 0f, 0f) }
        player.setOnGround(true)
        helper.assertTrue(Stance.deployed(player, gun) == null && Stance.spreadMultiplier(player, gun, true) == 1f, "standing: nothing set down")
        Stance.toggleProne(player)
        helper.assertTrue(Stance.isProne(player), "Z lies down")
        helper.assertTrue(Stance.deployed(player, gun) != null, "prone sets the bipod down")
        helper.assertTrue(Stance.spreadMultiplier(player, gun, true) < 0.35f && Stance.recoilMultiplier(player, gun) < 0.3f,
            "prone + bipod: much steadier (${Stance.spreadMultiplier(player, gun, true)}, ${Stance.recoilMultiplier(player, gun)})")
        helper.runAfterDelay(2) {
            player.doTick() // mock players have no connection ticking them; the pose is chosen in the player tick
            helper.assertTrue(player.pose == Pose.SWIMMING, "prone players crawl (pose ${player.pose})")
            Stance.toggleProne(player)
            helper.assertTrue(!Stance.isProne(player), "Z again stands up (with room above)")
            helper.succeed()
        }
    }

    @GameTest
    fun backpacksStoreItemsButNotOtherBackpacks(helper: GameTestHelper) {
        val pack = gear("pack", GearDefinition("Pack", GearType.BACKPACK, slots = 18))
        val other = gear("other_pack", GearDefinition("Other", GearType.BACKPACK, slots = 9))
        val pouch = gear("pouch", GearDefinition("Pouch", GearType.POUCH, slots = 9))
        val player = helper.makeMockServerPlayerInLevel()
        player.inventory.setItem(0, pack)
        val menu = BackpackMenu(1, player.inventory, pack, pack.gear!!)
        helper.assertTrue(menu.slots[0].mayPlace(ItemStack(Items.DIRT)) && !menu.slots[0].mayPlace(other), "no backpacks inside backpacks")
        menu.slots[3].setByPlayer(ItemStack(Items.DIAMOND, 5))
        helper.assertTrue(pack.get(DataComponents.CONTAINER)?.nonEmptyItemCopyStream()?.anyMatch { it.`is`(Items.DIAMOND) && it.count == 5 } == true,
            "contents are stored on the backpack item")
        val ownSlot = menu.slots.first { it.item === pack }
        helper.assertTrue(!ownSlot.mayPickup(player), "the open backpack cannot be moved")
        val pouchMenu = BackpackMenu(2, player.inventory, pouch.also { player.inventory.setItem(1, it) }, pouch.gear!!)
        helper.assertTrue(!pouchMenu.slots[0].mayPlace(ItemStack(Items.DIRT)), "pouches only take supplies")
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun medicalGearHealsYouAndOthers(helper: GameTestHelper) {
        val kit = gear("kit", GearDefinition("Kit", GearType.MEDICAL, maxStack = 4,
            effects = listOf(GearEffect(Identifier.withDefaultNamespace("instant_health"), 1, 1))))
        val player = helper.makeMockServerPlayerInLevel()
        player.abilities.instabuild = false
        player.health = 6f
        val stack = kit.copyWithCount(3)
        stack.finishUsingItem(helper.level, player)
        helper.assertTrue(stack.count == 2, "using a kit uses it up")
        val pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, net.minecraft.core.BlockPos(2, 1, 2))
        pig.health = 3f
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        stack.interactLivingEntity(player, pig, InteractionHand.MAIN_HAND)
        helper.assertTrue(stack.count == 1, "right click treats someone else")
        // Instant health takes effect on the next entity tick.
        helper.runAfterDelay(3) {
            player.doTick()
            helper.assertTrue(player.health > 10f, "the kit healed its user (health ${player.health})")
            helper.assertTrue(pig.health > 3f, "and the treated pig (health ${pig.health})")
            helper.succeed()
        }
    }

    @GameTest
    fun theBackpackSlotTakesOnlyBackpacksAndIsWorn(helper: GameTestHelper) {
        val pack = gear("back_pack", GearDefinition("Pack", GearType.BACKPACK, slots = 9))
        val pouch = gear("back_pouch", GearDefinition("Pouch", GearType.POUCH, slots = 9))
        val player = helper.makeMockServerPlayerInLevel()
        val slot = player.inventoryMenu.slots.single { it is GearSlots.BackSlot }
        helper.assertTrue(slot.mayPlace(pack) && !slot.mayPlace(pouch) && !slot.mayPlace(ItemStack(Items.DIRT)), "only backpacks go on the back")
        slot.setByPlayer(pack)
        helper.assertTrue(GearSlots.back(player) === pack, "the backpack is worn (synced attachment)")
        helper.assertTrue(player.inventoryMenu.slots.indexOf(slot) > 45, "vanilla slot indices stay unchanged")
        helper.succeed()
    }

    @GameTest
    fun platesAddArmourToModernArmourAndWearOut(helper: GameTestHelper) {
        val vestId = test("vest")
        com.flansmod.recoded.gun.Clothing.replace(com.flansmod.recoded.gun.Clothing.all + (vestId to
            com.flansmod.recoded.gun.ClothingDefinition("Vest", "chest", Identifier.withDefaultNamespace("iron"), armor = 3.0, plateSlots = 1)))
        val plate = gear("plate", GearDefinition("Plate", GearType.PLATE, armor = 4.0, toughness = 1.0, durability = 2))
        val player = helper.makeMockServerPlayerInLevel()
        val slots = player.inventoryMenu.slots.filterIsInstance<GearSlots.PlateSlot>()
        helper.assertTrue(slots.none { it.isActive }, "no plate slots without modern armour")
        val vest = com.flansmod.recoded.item.ClothingItem.stackFor(vestId)
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, vest)
        helper.assertTrue(slots[0].isActive && !slots[1].isActive, "the vest has one plate slot")
        helper.assertTrue(slots[0].mayPlace(plate) && !slots[0].mayPlace(ItemStack(Items.IRON_INGOT)), "plate slots only take plates")
        fun armour() = player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).get(DataComponents.ATTRIBUTE_MODIFIERS)!!
            .modifiers().filter { it.attribute().`is`(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR) }.sumOf { it.modifier().amount() }
        slots[0].setByPlayer(plate)
        helper.assertTrue(armour() == 7.0, "vest 3 + plate 4 armour, got ${armour()}")
        val hit = helper.level.damageSources().mobAttack(helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, net.minecraft.core.BlockPos(3, 1, 3)))
        repeat(2) { net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.invoker().afterDamage(player, hit, 2f, 2f, false) }
        helper.assertTrue(slots[0].item.isEmpty && armour() == 3.0, "two hits break the 2-hit plate (armour ${armour()})")
        helper.succeed()
    }

    @GameTest(maxTicks = 20)
    fun flashlightLightsWhereItPointsAndOnlyWhileOn(helper: GameTestHelper) {
        // A wall three blocks ahead; the player looks at its middle.
        for (x in 0..4) for (y in 1..3) helper.setBlock(BlockPos(x, y, 5), Blocks.STONE)
        val player = helper.makeMockServerPlayerInLevel()
        player.snapTo(helper.absoluteVec(net.minecraft.world.phys.Vec3(2.5, 1.0, 1.5)))
        player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, helper.absoluteVec(net.minecraft.world.phys.Vec3(2.5, 2.5, 5.0)))
        val flashlight = gear("flashlight", GearDefinition("Flashlight", GearType.FLASHLIGHT, range = 16.0, lightLevel = 12))
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, flashlight)
        helper.runAfterDelay(2) {
            helper.assertTrue(com.flansmod.recoded.utility.Flashlights.lightOf(player) == null, "no light while it is off")
            flashlight.set(com.flansmod.recoded.utility.Utilities.ACTIVE, true)
        }
        var lit: BlockPos? = null
        helper.runAfterDelay(4) {
            lit = com.flansmod.recoded.utility.Flashlights.lightOf(player)
            helper.assertTrue(lit != null && helper.level.getBlockState(lit!!).`is`(Blocks.LIGHT), "a light block where the beam hits: $lit")
            helper.assertTrue(helper.level.getBlockState(lit!!).getValue(net.minecraft.world.level.block.LightBlock.LEVEL) == 12, "as bright as the flashlight")
            helper.assertTrue(lit!!.distSqr(BlockPos.containing(helper.absoluteVec(net.minecraft.world.phys.Vec3(2.5, 2.5, 4.5)))) <= 2, "right in front of the wall: $lit")
            flashlight.set(com.flansmod.recoded.utility.Utilities.ACTIVE, false)
        }
        helper.runAfterDelay(6) {
            helper.assertTrue(helper.level.getBlockState(lit!!).isAir, "switched off, the light is gone")
            helper.succeed()
        }
    }
}
