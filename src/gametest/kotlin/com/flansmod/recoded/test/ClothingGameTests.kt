package com.flansmod.recoded.test

import com.flansmod.recoded.bench.WeaponAssemblyRecipe
import com.flansmod.recoded.item.ClothingItem
import com.flansmod.recoded.item.clothingId
import com.flansmod.recoded.registry.FlansRecipes
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput

class ClothingGameTests {
    private fun basic(path: String) = Identifier.fromNamespaceAndPath("flansbasic", path)

    @GameTest(maxTicks = 10)
    fun wearingASetGivesArmour(helper: GameTestHelper) {
        // A mob, because mock players are not ticked and vanilla applies equipment attributes on tick.
        val player = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityTypes.HUSK, net.minecraft.core.BlockPos(1, 1, 1))
        val before = player.getAttributeValue(Attributes.ARMOR)
        for ((slot, piece) in listOf(EquipmentSlot.HEAD to "army_helmet", EquipmentSlot.CHEST to "army_jacket",
            EquipmentSlot.LEGS to "army_pants", EquipmentSlot.FEET to "army_boots")) {
            val stack = ClothingItem.stackFor(basic(piece))
            helper.assertTrue(stack.get(DataComponents.EQUIPPABLE)?.slot() == slot, "$piece should be equippable on $slot")
            player.setItemSlot(slot, stack)
        }
        // Vanilla applies equipment attributes on the entity's next tick.
        helper.succeedWhen { helper.assertValueEqual(player.getAttributeValue(Attributes.ARMOR) - before, 13.0, "army set armour (2+5+4+2)") }
    }

    @GameTest(maxTicks = 5)
    fun craftedClothingIsWearableImmediately(helper: GameTestHelper) {
        val recipe = helper.level.recipeAccess().byKey(ResourceKey.create(Registries.RECIPE, basic("clothing_spec_ops_vest"))).orElseThrow()
        val stack = (recipe.value() as WeaponAssemblyRecipe).assemble(CraftingInput.EMPTY)
        helper.assertTrue(stack.clothingId == basic("spec_ops_vest"), "recipe yields the vest")
        helper.assertTrue(stack.get(DataComponents.EQUIPPABLE)?.slot() == EquipmentSlot.CHEST, "crafted vest has its equippable component")
        helper.assertTrue(stack.get(DataComponents.ATTRIBUTE_MODIFIERS)?.modifiers()?.isNotEmpty() == true, "crafted vest has armour modifiers")
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun nightVisionGogglesWhileWorn(helper: GameTestHelper) {
        val player = helper.makeMockServerPlayerInLevel()
        player.setItemSlot(EquipmentSlot.HEAD, ClothingItem.stackFor(basic("spec_ops_helmet")))
        helper.succeedWhen { helper.assertTrue(player.hasEffect(MobEffects.NIGHT_VISION), "NVG helmet grants night vision") }
    }

    @GameTest(maxTicks = 5)
    fun clothingFromCommandsGetsComponentsInInventory(helper: GameTestHelper) {
        val player = helper.makeMockServerPlayerInLevel()
        val bare = ItemStack(com.flansmod.recoded.registry.FlansItems.CLOTHING).apply { set(com.flansmod.recoded.registry.FlansComponents.CLOTHING, basic("army_boots")) }
        helper.assertFalse(bare.has(DataComponents.EQUIPPABLE), "a bare stack has no equippable yet")
        bare.inventoryTick(helper.level, player, null)
        helper.assertTrue(bare.get(DataComponents.EQUIPPABLE)?.slot() == EquipmentSlot.FEET, "inventory tick derives the components")
        helper.succeed()
    }
}
