package com.flansmod.recoded.client.mixin;

import com.flansmod.recoded.client.input.GunInput;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aiming down sights keeps the view (and the gun with it) steady: walking view bob is skipped while aiming.
 *
 * <p>Compatibility: a plain cancellable HEAD inject that only fires while the local player aims a gun; outside of
 * that, vanilla (and any mod hooking bobView, e.g. camera mods) runs unchanged. Hurt shake (bobHurt) is untouched.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void flansmod$steadyWhileAiming(CameraRenderState camera, PoseStack poseStack, CallbackInfo ci) {
		if (GunInput.INSTANCE.getAiming()) ci.cancel();
	}

	/** No first-person hands while looking through a vehicle gun's sight (like the spyglass). */
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void flansmod$noHandsInVehicleSight(CallbackInfo ci) {
		if (com.flansmod.recoded.client.vehicle.VehicleClient.getSighting()) ci.cancel();
	}
}
