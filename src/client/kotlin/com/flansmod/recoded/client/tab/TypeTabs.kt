package com.flansmod.recoded.client.tab

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.gun.Clothing
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.Parts
import com.flansmod.recoded.item.AmmoItem
import com.flansmod.recoded.item.AttachmentItem
import com.flansmod.recoded.item.ClothingItem
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.item.PartItem
import com.flansmod.recoded.registry.FlansItems
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * Creative tabs by item type across all content packs (in addition to one tab per pack): guns, magazines & ammo,
 * attachments, parts, explosives and clothing. Client-only, like [PackTabs].
 */
object TypeTabs {
    private class TypeTab(val key: String, val items: () -> List<ItemStack>)

    private val tabs = listOf(
        TypeTab("guns") { Guns.all.keys.sorted().map { GunItem.stackFor(it) } },
        TypeTab("ammo") {
            Magazines.all.keys.sorted().map { MagazineItem.stackFor(it, full = true) } + AmmoTypes.all.keys.sorted().map { AmmoItem.stackFor(it) }
        },
        TypeTab("attachments") { Attachments.all.keys.sorted().map(AttachmentItem::stackFor) },
        TypeTab("parts") { listOf(ItemStack(FlansItems.WEAPONS_BENCH)) + Parts.all.keys.sorted().map { PartItem.stackFor(it) } },
        TypeTab("explosives") { Grenades.all.filterValues { it.throwable }.keys.sorted().map { GrenadeItem.stackFor(it) } },
        TypeTab("clothing") { Clothing.all.keys.sorted().map(ClothingItem::stackFor) },
    )

    fun init() {
        for (tab in tabs) {
            Registry.register(
                BuiltInRegistries.CREATIVE_MODE_TAB, FlansMod.id("type/${tab.key}"),
                FabricCreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.flansmod.type.${tab.key}"))
                    .icon { tab.items().firstOrNull() ?: ItemStack(FlansItems.AMMO) }
                    .displayItems { _, output -> tab.items().forEach(output::accept) }
                    .build(),
            )
        }
    }
}
