package com.flansmod.recoded.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to a container screen's position, to draw Flan's gear slot frames under its slots. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int flansmod$leftPos();

	@Accessor("topPos")
	int flansmod$topPos();
}
