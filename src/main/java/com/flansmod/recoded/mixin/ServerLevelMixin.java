package com.flansmod.recoded.mixin;

import com.flansmod.recoded.gamemode.BattleWall;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Battles with "block damage" off: every explosion inside their area (TNT, creepers, grenades, rockets, shells)
 * still hurts and knocks back, but neither breaks blocks nor starts fires.
 *
 * <p>Compatibility: only explosions inside such a running battle are touched; they are re-issued once through the same
 * method with {@code ExplosionInteraction.NONE} and no fire, so other mods' hooks on {@code explode} still see them.
 * Everything else passes through unchanged.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@Inject(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/util/random/WeightedList;Lnet/minecraft/core/Holder;)V",
		at = @At("HEAD"), cancellable = true)
	private void flansmod$keepBattlefieldBlocks(Entity source, DamageSource damageSource, ExplosionDamageCalculator calculator, double x, double y, double z,
			float radius, boolean fire, Level.ExplosionInteraction interaction, ParticleOptions small, ParticleOptions large,
			WeightedList<ExplosionParticleInfo> blockParticles, Holder<SoundEvent> sound, CallbackInfo ci) {
		if ((interaction == Level.ExplosionInteraction.NONE && !fire) || !BattleWall.INSTANCE.protects((ServerLevel) (Object) this, new Vec3(x, y, z))) return;
		ci.cancel();
		((ServerLevel) (Object) this).explode(source, damageSource, calculator, x, y, z, radius, false, Level.ExplosionInteraction.NONE, small, large, blockParticles, sound);
	}
}
