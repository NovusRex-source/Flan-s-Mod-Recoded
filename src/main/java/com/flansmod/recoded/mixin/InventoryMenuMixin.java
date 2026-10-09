package com.flansmod.recoded.mixin;

import com.flansmod.recoded.gear.GearSlots;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds Flan's gear slots to the player's inventory menu, after all vanilla slots (so vanilla slot indices, and mods
 * relying on them, are unchanged): two armour-plate slots and the backpack slot in the free column above the
 * off-hand slot. Vanilla's shift-click moves items out of them into the inventory. The creative inventory places
 * them through {@code CreativeInventoryScreenMixin}.
 *
 * <p>Compatibility: accessory/trinket mods that also append slots keep working; they just get higher indices.
 */
@Mixin(InventoryMenu.class)
public abstract class InventoryMenuMixin extends AbstractContainerMenu {
	protected InventoryMenuMixin(MenuType<?> type, int id) {
		super(type, id);
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void flansmod$gearSlots(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
		GearSlots.PlateContainer plates = new GearSlots.PlateContainer(owner);
		for (int i = 0; i < GearSlots.PLATE_SLOTS; i++) addSlot(new GearSlots.PlateSlot(plates, i, 77, 8 + i * 18));
		addSlot(new GearSlots.BackSlot(owner, 77, 44));
	}
}
