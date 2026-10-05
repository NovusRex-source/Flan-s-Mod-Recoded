package com.flansmod.recoded.client.mixin;

import com.flansmod.recoded.client.hud.ScopeOverlay;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Thermal scopes: while the local player aims through one, living entities in range use vanilla's glowing
 * outline. Client-side only (no effect is applied to the entities).
 *
 * <p>Compatibility: {@code @ModifyReturnValue} only ever turns {@code false} into {@code true}, so it chains
 * with other mods (Iris, Sodium, outline mods) and never hides an outline someone else requested.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftGlowMixin {
	@ModifyReturnValue(method = "shouldEntityAppearGlowing", at = @At("RETURN"))
	private boolean flansmod$thermalScope(boolean glowing, Entity entity) {
		return glowing || ScopeOverlay.INSTANCE.highlights(entity);
	}
}
