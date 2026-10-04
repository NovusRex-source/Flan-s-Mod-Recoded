package com.flansmod.recoded.mixin;

import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.PackSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to the private fields of FolderRepositorySource, used by {@link PackRepositoryMixin}
 * to tell which kind of repository is being built. Pure accessor: no bytecode of the target is changed,
 * so this cannot conflict with other mods (Sodium, Iris, ...).
 */
@Mixin(FolderRepositorySource.class)
public interface FolderRepositorySourceAccessor {
	@Accessor("packType")
	PackType flansmod$getPackType();

	@Accessor("packSource")
	PackSource flansmod$getPackSource();
}
