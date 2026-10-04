package com.flansmod.recoded.client.mixin;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Write access to the cached creative tab parameters. Setting them to null makes vanilla rebuild all
 * tab contents the next time the creative inventory opens, which picks up gun definitions that arrived
 * from the server or changed on {@code /reload}. Pure accessor: no target bytecode is changed.
 */
@Mixin(CreativeModeTabs.class)
public interface CreativeModeTabsAccessor {
	@Accessor("CACHED_PARAMETERS")
	static void flansmod$setCachedParameters(CreativeModeTab.ItemDisplayParameters parameters) {
		throw new AssertionError();
	}
}
