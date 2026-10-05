package com.flansmod.recoded.client.mixin;

import com.flansmod.recoded.client.input.GunInput;
import com.flansmod.recoded.item.GunItemKt;
import com.flansmod.recoded.gun.GunDefinition;
import com.flansmod.recoded.client.vehicle.VehicleClient;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Aim-down-sights zoom: divides the final FOV by the held gun's {@code ads_zoom}, blended by aim progress.
 *
 * <p>Compatibility: uses MixinExtras' {@code @ModifyReturnValue}, which chains with other mods modifying the
 * same return value (zoom mods, Sodium, Iris) instead of replacing it. Only touches the value when the
 * player holds a gun and is (partially) aiming, so it is a no-op otherwise.
 *
 * <p>Vehicle camera: vanilla only reads {@code camera_distance} from living mounts, so the third-person distance
 * passed to {@code getMaxZoom} is raised to the vehicle's {@code camera_distance} while riding one. {@code getMaxZoom}
 * still clips against blocks, and other mods changing the distance before this point are respected (we take the max).
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
	private float flansmod$adsZoom(float fov, float partialTick) {
		float progress = GunInput.INSTANCE.aimProgress(partialTick);
		if (progress <= 0f) return fov;
		var player = Minecraft.getInstance().player;
		GunDefinition gun = player == null ? null : GunItemKt.getDefinition(player.getMainHandItem());
		if (gun == null || gun.getAdsZoom() <= 1f) return fov;
		return fov / Mth.lerp(progress, 1f, gun.getAdsZoom());
	}

	@ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
	private float flansmod$vehicleCameraDistance(float distance) {
		return VehicleClient.cameraDistance(distance);
	}
}
