package com.flansmod.recoded.contentpack

import com.flansmod.recoded.FlansMod
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackSelectionConfig
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.FolderRepositorySource
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.server.packs.repository.RepositorySource
import net.minecraft.util.FileUtil
import net.minecraft.world.level.validation.DirectoryValidator
import java.io.IOException
import java.nio.file.Path
import java.util.Optional
import java.util.function.Consumer

/**
 * Exposes every folder/zip in `<gameDir>/contentpacks/` as a pack of [packType].
 *
 * A content pack is a regular pack (pack.mcmeta + assets/ + data/), so the same folder feeds
 * both the client resource repository and the server data repository. Discovery and file access
 * are done entirely by vanilla ([FolderRepositorySource.discoverPacks]); we only mark the packs
 * as required so they are always active and cannot be disabled in the pack screen.
 */
class ContentPackSource(private val packType: PackType) : RepositorySource {
    override fun loadPacks(consumer: Consumer<Pack>) {
        try {
            FileUtil.createDirectoriesSafe(DIRECTORY)
            FolderRepositorySource.discoverPacks(DIRECTORY, NO_SYMLINKS) { path, resources ->
                val location = PackLocationInfo(
                    "${FlansMod.MOD_ID}:contentpack/${path.fileName}",
                    Component.literal(path.fileName.toString()),
                    SOURCE,
                    Optional.empty()
                )
                Pack.readMetaAndCreate(location, resources, packType, SELECTION)?.let(consumer::accept)
                    ?: FlansMod.LOGGER.warn("Skipping content pack {}: missing or invalid pack.mcmeta", path.fileName)
            }
        } catch (e: IOException) {
            FlansMod.LOGGER.warn("Failed to list content packs in {}", DIRECTORY, e)
        }
    }

    companion object {
        val DIRECTORY: Path = FabricLoader.getInstance().gameDir.resolve("contentpacks")

        private val NO_SYMLINKS = DirectoryValidator { false }
        private val SELECTION = PackSelectionConfig(true, Pack.Position.TOP, false)
        private val SOURCE = PackSource.create({ name ->
            Component.translatable("pack.nameAndSource", name, Component.translatable("pack.source.flansmod"))
                .withStyle(ChatFormatting.GRAY)
        }, true)
    }
}
