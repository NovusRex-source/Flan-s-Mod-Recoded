package com.flansmod.recoded.client.tab

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.gun.FactionDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.factionOf
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.item.AmmoItem
import com.flansmod.recoded.item.grenadeId
import com.flansmod.recoded.item.gunId
import com.flansmod.recoded.item.vehicleId
import com.flansmod.recoded.registry.FlansItems
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.FileToIdConverter
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.world.item.ItemStack

/**
 * One creative tab per faction (`data/<ns>/flansmod/factions/<name>.json` of the installed content packs): its guns with
 * their magazines and rounds, grenades, vehicles (with their shells) and uniforms. Creative tabs must exist before
 * joining a world, so factions are discovered from the packs at client start like [PackTabs]; the contents come from
 * the synced definitions.
 */
object FactionTabs {
    private val files = FileToIdConverter.json("${FlansMod.MOD_ID}/factions")

    fun init() {
        val factions = PackTabs.discoverPacks().flatMap { pack ->
            pack.getNamespaces(PackType.SERVER_DATA).flatMap { ns ->
                buildList {
                    pack.listResources(PackType.SERVER_DATA, ns, "${FlansMod.MOD_ID}/factions") { file, stream ->
                        runCatching { stream.get().use { Content.JSON.decodeFromString(FactionDefinition.serializer(), it.reader().readText()) } }
                            .onSuccess { add(files.fileToId(file) to it) }
                            .onFailure { FlansMod.LOGGER.warn("Invalid faction {}: {}", file, it.message) }
                    }
                }
            }.also { pack.close() }
        }
        factions.sortedWith(compareBy({ it.first.namespace }, { it.second.order })).forEachIndexed { index, (id, faction) -> register(index, id, faction) }
    }

    private fun register(index: Int, id: Identifier, faction: FactionDefinition) {
        Registry.register(
            // Fabric orders modded tabs by id: after the type tabs, in the factions' order.
            BuiltInRegistries.CREATIVE_MODE_TAB, FlansMod.id("type/8_faction_${index}_${id.namespace}_${id.path}"),
            FabricCreativeModeTab.builder()
                .title(Component.translatableWithFallback("faction.${id.toLanguageKey()}", faction.name))
                .icon { items(id).firstOrNull { faction.icon != null && it.definitionId() == faction.icon } ?: items(id).firstOrNull() ?: ItemStack(FlansItems.GUN) }
                .displayItems { _, output -> items(id).forEach(output::accept) }
                .build(),
        )
    }

    /** Weapons with their ammunition (only magazines that fit them, incl. vehicle guns), explosives, vehicles, uniforms. */
    fun items(faction: Identifier): List<ItemStack> {
        val mine = { id: Identifier -> factionOf(id) == faction }
        // Hand guns of the faction plus its vehicles' guns (for tank shells and belt boxes).
        val guns = Guns.all.keys.filter(mine) + com.flansmod.recoded.gun.Vehicles.all.filterKeys(mine).values.flatMap { v -> v.seats.mapNotNull { it.gun } }
        val magazines = guns.flatMap { GunItem.acceptedMagazines(it) }.toSet()
        val calibers = magazines.mapNotNull { Magazines[it]?.caliber }.toSet()
        val ammo = CreativeContent.ammunition(calibers = calibers).filter { it.item !is MagazineItem || it.magazineId() in magazines }
        return CreativeContent.weapons(mine) + ammo.filter { it.item is AmmoItem || it.item is MagazineItem } + CreativeContent.explosives(mine) +
            CreativeContent.vehicles(mine, tools = false).filter { it.vehicleUpgrade() == null } +
            CreativeContent.equipment(mine)
    }

    private fun ItemStack.magazineId() = get(com.flansmod.recoded.registry.FlansComponents.MAGAZINE)?.magazine
    private fun ItemStack.vehicleUpgrade() = get(com.flansmod.recoded.registry.FlansComponents.VEHICLE_UPGRADE)
    private fun ItemStack.definitionId(): Identifier? = gunId ?: vehicleId ?: grenadeId ?: get(com.flansmod.recoded.registry.FlansComponents.CLOTHING)
}
