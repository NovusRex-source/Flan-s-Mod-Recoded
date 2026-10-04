package com.flansmod.recoded.mixin;

import com.flansmod.recoded.contentpack.ContentPackSource;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Adds {@link ContentPackSource} to the two pack repositories that matter:
 * <ul>
 *   <li>the client resource pack repository (has a CLIENT_RESOURCES folder source = resourcepacks/)</li>
 *   <li>the world data pack repository (has a SERVER_DATA folder source with PackSource.WORLD = world/datapacks/)</li>
 * </ul>
 * Other repositories (e.g. the vanilla-only "trusted" repository or server-sent packs) are left untouched.
 *
 * <p>Side effects / compatibility:
 * <ul>
 *   <li>Only injects at RETURN of the constructor and only adds to {@code sources}; vanilla logic is not altered.</li>
 *   <li>{@code sources} is an ImmutableSet in vanilla. Fabric API's own PackRepositoryMixin already replaces it
 *       with a LinkedHashSet; we do the same if it hasn't happened yet, so mixin apply order does not matter.
 *       Any other mod doing the same copy keeps working, since it only ever sees a superset.</li>
 *   <li>Renderer mods (Sodium, Iris) never touch PackRepository, so there is no overlap with them.</li>
 * </ul>
 */
@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {
	@Shadow @Final @Mutable
	private Set<RepositorySource> sources;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void flansmod$addContentPacks(RepositorySource[] repositorySources, CallbackInfo ci) {
		PackType type = flansmod$detectType();
		if (type == null) return;

		if (!(sources instanceof LinkedHashSet)) {
			sources = new LinkedHashSet<>(sources);
		}
		sources.add(new ContentPackSource(type));
	}

	private PackType flansmod$detectType() {
		for (RepositorySource source : sources) {
			if (!(source instanceof FolderRepositorySource)) continue;
			FolderRepositorySourceAccessor folder = (FolderRepositorySourceAccessor) source;
			PackType type = folder.flansmod$getPackType();

			if (type == PackType.CLIENT_RESOURCES) return type;
			if (type == PackType.SERVER_DATA && folder.flansmod$getPackSource() == PackSource.WORLD) return type;
		}
		return null;
	}
}
