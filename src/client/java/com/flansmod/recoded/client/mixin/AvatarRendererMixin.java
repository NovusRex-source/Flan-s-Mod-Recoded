package com.flansmod.recoded.client.mixin;

import com.flansmod.recoded.item.GunItem;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Players holding a gun use vanilla's two-handed crossbow pose (both arms forward) in third person.
 *
 * <p>Compatibility: only the return value for gun stacks changes; animation mods (e.g. Player Animator,
 * Emotecraft) that override poses later in the pipeline still win.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	/**
	 * Copies the player's worn backpack and open parachute (synced attachments) onto the render state for Flan's
	 * backpack and parachute layers. Only adds Fabric render-state data at the end of extraction; nothing vanilla reads
	 * is changed.
	 */
	@org.spongepowered.asm.mixin.injection.Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void flansmod$wornBackpack(Avatar avatar, net.minecraft.client.renderer.entity.state.AvatarRenderState state, float partialTick,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		state.setData(com.flansmod.recoded.client.gear.GearClient.WORN, com.flansmod.recoded.client.gear.GearClient.worn(avatar));
		state.setData(com.flansmod.recoded.client.gear.GearClient.CANOPY, com.flansmod.recoded.client.gear.GearClient.canopy(avatar));
	}

	@ModifyReturnValue(method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;", at = @At("RETURN"))
	private static HumanoidModel.ArmPose flansmod$gunPose(HumanoidModel.ArmPose pose, Avatar avatar, ItemStack stack, InteractionHand hand) {
		return stack.getItem() instanceof GunItem ? HumanoidModel.ArmPose.CROSSBOW_HOLD : pose;
	}
}
