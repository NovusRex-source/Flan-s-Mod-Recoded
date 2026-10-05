package com.flansmod.recoded.client.tab

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.client.mixin.CreativeModeTabsAccessor
import com.flansmod.recoded.contentpack.ContentPackSource
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.Parts
import com.flansmod.recoded.item.PartItem
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.gun.IdentifierSerializer
import com.flansmod.recoded.item.AmmoItem
import com.flansmod.recoded.item.AttachmentItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.registry.FlansItems
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.PathPackResources
import net.minecraft.server.packs.repository.PackSource
import net.minecraft.world.item.ItemStack
import java.util.Optional

/** The optional `flansmod` section of a content pack's `pack.mcmeta`. */
@Serializable
private data class PackMcmeta(val flansmod: PackTabInfo? = null)

@Serializable
private data class PackTabInfo(
    val name: String,
    /** A gun, ammo or attachment id shown as the tab icon. */
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
)

/**
 * One creative tab per content pack, like the original Flan's Mod. Packs are found at client start
 * (built-in pack + `contentpacks/`) via vanilla pack discovery; a tab lists every gun, ammo type and
 * attachment in the pack's data namespaces. Creative tabs are client-only, so servers with other packs
 * are unaffected; items from packs without a tab show up in the generic Flan's Mod tab.
 */
object PackTabs {
    private class PackTab(val namespaces: Set<String>)

    private val tabs = mutableListOf<PackTab>()

    fun init() {
        discoverPacks().forEach(::register)
        FlansItems.coveredByPackTab = { id -> tabs.any { id.namespace in it.namespaces } }
        // Definitions arrive after joining and change on /reload: make creative tabs rebuild next time.
        Content.onChanged += { CreativeModeTabsAccessor.`flansmod$setCachedParameters`(null) }
    }

    private fun register(pack: PackResources) {
        val info = pack.getRootResource("pack.mcmeta")?.get()?.use { stream ->
            runCatching { Content.JSON.decodeFromString(PackMcmeta.serializer(), stream.reader().readText()).flansmod }
                .onFailure { FlansMod.LOGGER.warn("Invalid pack.mcmeta in {}: {}", pack.packId(), it.message) }
                .getOrNull()
        } ?: return
        val namespaces = pack.getNamespaces(PackType.SERVER_DATA) - "minecraft"
        if (namespaces.isEmpty()) return

        val tab = PackTab(namespaces)
        tabs += tab
        val key = pack.packId().lowercase().replace(Regex("[^a-z0-9_./-]"), "_")
        Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB, FlansMod.id("pack/$key"),
            FabricCreativeModeTab.builder()
                .title(Component.translatableWithFallback("itemGroup.flansmod.pack.$key", info.name))
                .icon { info.icon?.let(::stackFor) ?: ItemStack(FlansItems.GUN) }
                .displayItems { _, output ->
                    fun <T : Any> ids(all: Map<Identifier, T>) = all.keys.filter { it.namespace in tab.namespaces }.sorted()
                    ids(Guns.all).forEach { output.accept(GunItem.stackFor(it)) }
                    ids(Parts.all).forEach { output.accept(PartItem.stackFor(it)) }
                    ids(Magazines.all).forEach { output.accept(MagazineItem.stackFor(it, full = true)) }
                    ids(AmmoTypes.all).forEach { output.accept(AmmoItem.stackFor(it)) }
                    ids(Attachments.all).forEach { output.accept(AttachmentItem.stackFor(it)) }
                    ids(Grenades.all).filter { Grenades[it]!!.throwable }.forEach { output.accept(GrenadeItem.stackFor(it)) }
                }
                .build(),
        )
        FlansMod.LOGGER.info("Creative tab for content pack {} ({})", info.name, namespaces.joinToString())
    }

    private fun stackFor(id: Identifier): ItemStack = when {
        AmmoTypes[id] != null -> AmmoItem.stackFor(id)
        Attachments[id] != null -> AttachmentItem.stackFor(id)
        Grenades[id] != null -> GrenadeItem.stackFor(id)
        Magazines[id] != null -> MagazineItem.stackFor(id, full = true)
        Parts[id] != null -> PartItem.stackFor(id)
        else -> GunItem.stackFor(id)
    }

    /** Built-in pack from the mod jar plus everything in `contentpacks/`, opened with vanilla pack classes. */
    private fun discoverPacks(): List<PackResources> {
        val packs = mutableListOf<PackResources>()
        FabricLoader.getInstance().getModContainer(FlansMod.MOD_ID).flatMap { it.findPath("resourcepacks/${FlansMod.BUILTIN_PACK}") }.ifPresent { path ->
            packs += PathPackResources(PackLocationInfo(FlansMod.BUILTIN_PACK, Component.literal(FlansMod.BUILTIN_PACK), PackSource.BUILT_IN, Optional.empty()), path)
        }
        ContentPackSource(PackType.SERVER_DATA).loadPacks { pack -> pack.open().forEach(packs::add) }
        return packs
    }
}
