package com.flansmod.recoded.client.mixin;

import com.flansmod.recoded.client.config.FlansConfig;
import com.flansmod.recoded.client.input.GunInput;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lowers mouse sensitivity while aiming down sights (further for high-zoom scopes).
 *
 * <p>Wraps the {@code options.sensitivity().get()} read inside {@code turnPlayer} with MixinExtras'
 * {@code @ModifyExpressionValue}, so other mods hooking the same call still compose. Mouse-related mods
 * (e.g. raw input, smooth camera) run on the result unchanged. No-op unless the player is aiming.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@ModifyExpressionValue(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"))
	private Object flansmod$adsSensitivity(Object sensitivity) {
		if (!GunInput.INSTANCE.getAiming() || !(sensitivity instanceof Double value)) return sensitivity;
		var gun = GunInput.getAimedGun();
		double zoom = gun == null ? 1.0 : Math.max(1.0, gun.getAdsZoom());
		return value * FlansConfig.Companion.getGet().adsSensitivity / 100.0 * Math.min(1.0, 1.5 / zoom);
	}
}
