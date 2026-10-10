package com.flansmod.recoded.client.tab

import com.flansmod.recoded.gun.AmmoDefinition
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.gun.Clothing
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.Parts
import com.flansmod.recoded.gun.VehicleUpgrades
import com.flansmod.recoded.gun.Vehicles
import com.flansmod.recoded.item.AmmoItem
import com.flansmod.recoded.item.AttachmentItem
import com.flansmod.recoded.item.ClothingItem
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.item.PartItem
import com.flansmod.recoded.item.VehicleItem
import com.flansmod.recoded.item.VehicleUpgradeItem
import com.flansmod.recoded.registry.FlansItems
import com.flansmod.recoded.fuel.FuelCanItem
import com.flansmod.recoded.fuel.FuelStack
import com.flansmod.recoded.gun.FuelTypes
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * What the creative tabs show, in a sensible order, optionally limited to some namespaces (pack tabs):
 * guns by weapon class, ammunition grouped by caliber (rounds, then magazines), attachments by slot, crafting
 * components by kind.
 */
object CreativeContent {
    /** Weapon classes in display order; unknown classes follow, then guns without one. */
    private val GUN_CLASSES = listOf("pistol", "smg", "rifle", "dmr", "sniper", "shotgun", "lmg", "launcher")
    private val SLOTS = listOf("sight", "muzzle", "underbarrel", "grip", "stock")
    private val PART_KINDS = listOf("gun", "ammo", "vehicle")
    private val CLOTHING_SLOTS = listOf("head", "chest", "legs", "feet")

    private fun <T> order(list: List<T>, value: T?) = list.indexOf(value).let { if (it < 0) list.size else it }

    fun weapons(filter: (Identifier) -> Boolean = { true }): List<ItemStack> = Guns.all
        .filter { (id, g) -> filter(id) && !g.mounted }
        .entries.sortedWith(compareBy({ order(GUN_CLASSES, it.value.category) }, { it.value.name }))
        .map { GunItem.stackFor(it.key) }

    /** Calibers ordered by the first weapon class that uses them (pistol rounds first, rockets last). */
    private fun calibers(): List<String> {
        val all = (AmmoTypes.all.values.map { it.caliber } + Magazines.all.values.map { it.caliber }).distinct()
        fun rank(caliber: String) = Guns.all.filter { (id, _) -> GunItem.acceptedMagazines(id).any { Magazines[it]?.caliber == caliber } }
            .values.minOfOrNull { if (it.mounted) GUN_CLASSES.size + 1 else order(GUN_CLASSES, it.category) } ?: (GUN_CLASSES.size + 2)
        return all.sortedWith(compareBy({ rank(it) }, { it }))
    }

    /** Plain rounds first, then by how many special effects they have. */
    private fun ammoOrder(def: AmmoDefinition) = listOf(def.armorPiercing, def.fireSeconds > 0f, def.tracer != null, def.damageMultiplier != 1f, def.pellets != null)
        .count { it }

    /** Per caliber: its rounds, then its (detachable) magazines, full. */
    fun ammunition(filter: (Identifier) -> Boolean = { true }, calibers: Collection<String>? = null): List<ItemStack> =
        calibers().filter { calibers == null || it in calibers }.flatMap { caliber ->
            AmmoTypes.all.filter { (id, a) -> filter(id) && a.caliber == caliber }.entries
                .sortedWith(compareBy({ ammoOrder(it.value) }, { it.value.name })).map { AmmoItem.stackFor(it.key) } +
                Magazines.all.filter { (id, m) -> filter(id) && m.caliber == caliber && !m.internal }.entries
                    .sortedWith(compareBy({ it.value.capacity }, { it.value.name })).map { MagazineItem.stackFor(it.key, full = true) }
        }

    fun attachments(filter: (Identifier) -> Boolean = { true }): List<ItemStack> = Attachments.all.filter { filter(it.key) }.entries
        .sortedWith(compareBy({ order(SLOTS, it.value.slot) }, { it.value.name })).map { AttachmentItem.stackFor(it.key) }

    /** Hand grenades (harmful ones first, then incendiary, smoke and flash), then anti-personnel and anti-tank mines. */
    fun explosives(filter: (Identifier) -> Boolean = { true }): List<ItemStack> = Grenades.all.filter { (id, g) -> filter(id) && g.throwable }
        .entries.sortedWith(compareBy({ explosiveOrder(it.value) }, { it.value.explosion?.power ?: 0f }, { it.value.name })).map { GrenadeItem.stackFor(it.key) }

    private fun explosiveOrder(g: com.flansmod.recoded.gun.GrenadeDefinition) = when {
        g.mine != null -> if (g.mine?.trigger == com.flansmod.recoded.gun.GrenadeDefinition.Mine.Trigger.VEHICLE) 5 else 4
        g.smoke != null || g.flash != null -> 3
        g.explosion?.fire == true -> 2
        else -> 1
    }

    /** Field equipment for vehicles (not from content packs): wrench, fuel cans, fuel synthesizer, petrol station. */
    fun vehicleTools(): List<ItemStack> = listOf(
        ItemStack(FlansItems.WRENCH), FuelCanItem.stackFor(null),
        FuelCanItem.stackFor(FuelStack(FuelTypes.PETROL, FuelCanItem.CAPACITY)), FuelCanItem.stackFor(FuelStack(FuelTypes.DIESEL, FuelCanItem.CAPACITY)),
        ItemStack(FlansItems.FUEL_SYNTHESIZER), ItemStack(FlansItems.PETROL_STATION),
    )

    /**
     * Vehicles (cars, tanks, emplacements like mortars, then planes and helicopters), their upgrades and the field gear. Their ammunition is in
     * the ammunition tab with every other round (tank shells and mortar bombs come last there).
     */
    fun vehicles(filter: (Identifier) -> Boolean = { true }, tools: Boolean = true): List<ItemStack> {
        return Vehicles.all.filter { filter(it.key) }.entries.sortedWith(compareBy({ it.value.type.ordinal }, { it.value.name }))
            .map { VehicleItem.stackFor(it.key) } +
            VehicleUpgrades.all.filter { filter(it.key) }.entries.sortedWith(compareBy({ it.value.slot }, { it.value.name })).map { VehicleUpgradeItem.stackFor(it.key) } +
            (if (tools) vehicleTools() else emptyList())
    }

    /** Uniforms by set, then gear: backpacks (by size), pouches, medical supplies, binoculars. */
    fun equipment(filter: (Identifier) -> Boolean = { true }): List<ItemStack> = Clothing.all.filter { filter(it.key) }.entries
        .sortedWith(compareBy({ it.value.asset.toString() }, { order(CLOTHING_SLOTS, it.value.slot) })).map { ClothingItem.stackFor(it.key) } +
        com.flansmod.recoded.gun.Gear.all.filter { filter(it.key) }.entries.sortedWith(compareBy({ it.value.type.ordinal }, { it.value.slots }, { it.value.name }))
            .map { com.flansmod.recoded.gear.GearItem.stackFor(it.key) }

    private val STRUCTURE_ORDER = listOf("base", "bunker", "building", "trench", "nest", "tower", "checkpoint", "street")

    /** Structure kits by category (bunkers, trenches, ..., streets), then name. */
    fun structures(filter: (Identifier) -> Boolean = { true }): List<ItemStack> = com.flansmod.recoded.gun.Structures.all.filter { filter(it.key) }.entries
        .sortedWith(compareBy({ order(STRUCTURE_ORDER, it.value.category) }, { it.value.name }))
        .map { com.flansmod.recoded.item.StructureItem.stackFor(it.key) }

    /** Soldier spawn items: every attitude for soldiers of any faction, then for each faction (in faction order). */
    fun soldiers(faction: Identifier? = null): List<ItemStack> {
        val factions = if (faction != null) listOf(faction)
            else listOf<Identifier?>(null) + com.flansmod.recoded.gun.Factions.all.entries.sortedWith(compareBy({ it.key.namespace }, { it.value.order })).map { it.key }
        return factions.flatMap { f -> com.flansmod.recoded.gamemode.SoldierAttitude.entries.map { com.flansmod.recoded.item.SoldierItem.stackFor(f, it) } }
    }

    fun crafting(filter: (Identifier) -> Boolean = { true }, withBench: Boolean = true): List<ItemStack> =
        (if (withBench) listOf(ItemStack(FlansItems.WEAPONS_BENCH)) else emptyList()) +
            Parts.all.filter { filter(it.key) }.entries.sortedWith(compareBy({ order(PART_KINDS, it.value.category) }, { it.value.name })).map { PartItem.stackFor(it.key) }

    /** Everything from one content pack, in tab order (pack tabs). */
    fun all(filter: (Identifier) -> Boolean): List<ItemStack> =
        weapons(filter) + explosives(filter) + ammunition(filter) + attachments(filter) + vehicles(filter, tools = false) +
            equipment(filter) + structures(filter) + crafting(filter, withBench = false)
}
