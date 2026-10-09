package com.flansmod.recoded.mixin;

import com.flansmod.recoded.movement.Stance;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prone and sliding players (Flan's movement stances) are held in vanilla's crawling pose ({@link Pose#SWIMMING} on
 * land): 0.6 blocks high, crawl animation, slow movement - all vanilla behaviour, only the pose choice is forced.
 *
 * <p>Runs on both sides (the stance is a synced attachment), so client prediction and the server agree. Compatibility:
 * only cancels the vanilla pose update while a Flan's stance is active; otherwise pose mods (e.g. crawl mods) are
 * untouched.
 */
@Mixin(Player.class)
public abstract class PlayerPoseMixin {
	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void flansmod$stancePose(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (Stance.lowPose(self)) {
			self.setPose(Pose.SWIMMING);
			ci.cancel();
		}
	}
}
