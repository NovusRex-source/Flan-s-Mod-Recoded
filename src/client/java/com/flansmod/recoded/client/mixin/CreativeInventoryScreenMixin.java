package com.flansmod.recoded.client.mixin;

import com.flansmod.recoded.gear.GearSlots;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * The creative inventory tab lays out the survival inventory's slots by index; slots appended after the vanilla ones
 * would land on the hotbar. Flan's gear slots (backpack, armour plates) get their own spot right of the armour slots.
 *
 * <p>Compatibility: only changes the position arguments for Flan's own slot classes; every other slot (vanilla or
 * other mods) keeps the position vanilla computed.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeInventoryScreenMixin {
	@ModifyArgs(method = "selectTab", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen$SlotWrapper;<init>(Lnet/minecraft/world/inventory/Slot;III)V"))
	private void flansmod$placeGearSlots(Args args) {
		Slot slot = args.get(0);
		if (slot instanceof GearSlots.BackSlot) {
			args.set(2, 127);
			args.set(3, 33);
		} else if (slot instanceof GearSlots.PlateSlot) {
			args.set(2, 127 + slot.getContainerSlot() * 18);
			args.set(3, 6);
		}
	}
}
