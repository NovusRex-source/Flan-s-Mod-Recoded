package com.flansmod.recoded.client.tab

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.registry.FlansItems
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.resources.Identifier
import com.flansmod.recoded.item.ammoTypeId
import com.flansmod.recoded.item.attachmentId
import com.flansmod.recoded.item.grenadeId
import com.flansmod.recoded.item.gunId
import com.flansmod.recoded.item.vehicleId

/**
 * The creative tabs, by item type across all content packs: weapons, ammunition, attachments, explosives, vehicles,
 * equipment and crafting components ([CreativeContent] decides order and grouping). Client-only: the contents come
 * from synced definitions. Optional per-pack tabs: [PackTabs].
 */
object TypeTabs {
    /** [icon]: preferred icon (a well-known item of the basic packs), else the tab's first item. */
    private class TypeTab(val key: String, val icon: String?, val items: () -> List<ItemStack>)

    /**
     * By what players look for: guns and everything thrown or laid (grenades, mines) under Weapons; every round,
     * shell and magazine under Ammunition (by caliber, hand-held first); Vehicles with emplacements, upgrades and
     * field gear; Uniforms; Fortifications with the battle blocks; crafting parts last. Faction tabs follow.
     */
    private val tabs = listOf(
        TypeTab("weapons", "flansbasic:m4a1") { CreativeContent.weapons() + CreativeContent.explosives() },
        TypeTab("ammo", "flansbasic:556") { CreativeContent.ammunition() },
        TypeTab("attachments", "flansbasic:acog") { CreativeContent.attachments() },
        TypeTab("vehicles", "flansvehicles:m1_abrams") { CreativeContent.vehicles() },
        TypeTab("equipment", "flansbasic:army_helmet") { CreativeContent.equipment() },
        TypeTab("fortifications", null) {
            com.flansmod.recoded.fortification.Fortifications.ALL.map { ItemStack(it) } + CreativeContent.structures() +
                listOf(com.flansmod.recoded.registry.FlansItems.TEAM_FLAG, com.flansmod.recoded.registry.FlansItems.BATTLE_SPAWN, com.flansmod.recoded.registry.FlansItems.BATTLE_BORDER,
                    com.flansmod.recoded.registry.FlansItems.BATTLE_MASTER).map(::ItemStack)
        },
        TypeTab("crafting", null) { CreativeContent.crafting() },
    )

    /** Registry id of a tab; Fabric orders modded tabs by id, so the index keeps them in this order. */
    fun id(key: String) = FlansMod.id("type/${tabs.indexOfFirst { it.key == key }}_$key")

    private fun icon(tab: TypeTab): ItemStack {
        val items = tab.items()
        val preferred = tab.icon?.let(Identifier::parse)
        return items.firstOrNull { preferred != null && it.definitionId() == preferred } ?: items.firstOrNull() ?: ItemStack(FlansItems.WEAPONS_BENCH)
    }

    /** The definition id behind a Flan's item stack, whatever its type. */
    private fun ItemStack.definitionId(): Identifier? = gunId ?: ammoTypeId ?: attachmentId ?: grenadeId ?: vehicleId ?: get(com.flansmod.recoded.registry.FlansComponents.CLOTHING)

    fun init() {
        for (tab in tabs) {
            Registry.register(
                BuiltInRegistries.CREATIVE_MODE_TAB, id(tab.key),
                FabricCreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.flansmod.type.${tab.key}"))
                    .icon { icon(tab) }
                    .displayItems { _, output -> tab.items().forEach(output::accept) }
                    .build(),
            )
        }
    }
}
