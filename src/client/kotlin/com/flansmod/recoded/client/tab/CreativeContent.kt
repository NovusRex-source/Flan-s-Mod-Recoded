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

    fun explosives(filter: (Identifier) -> Boolean = { true }): List<ItemStack> = Grenades.all.filter { (id, g) -> filter(id) && g.throwable }
        .entries.sortedBy { it.value.name }.map { GrenadeItem.stackFor(it.key) }

    /** Field equipment for vehicles (not from content packs): wrench, fuel cans, fuel synthesizer, petrol station. */
    fun vehicleTools(): List<ItemStack> = listOf(
        ItemStack(FlansItems.WRENCH), FuelCanItem.stackFor(null),
        FuelCanItem.stackFor(FuelStack(FuelTypes.PETROL, FuelCanItem.CAPACITY)), FuelCanItem.stackFor(FuelStack(FuelTypes.DIESEL, FuelCanItem.CAPACITY)),
        ItemStack(FlansItems.FUEL_SYNTHESIZER), ItemStack(FlansItems.PETROL_STATION),
    )

    /** Vehicles (driveable, then emplacements like mortars), their upgrades, the tools and the ammunition their guns use. */
    fun vehicles(filter: (Identifier) -> Boolean = { true }, tools: Boolean = true): List<ItemStack> {
        val mountedCalibers = Guns.all.filterValues { it.mounted }.keys.flatMap { GunItem.acceptedMagazines(it) }.mapNotNull { Magazines[it]?.caliber }.toSet()
        return Vehicles.all.filter { filter(it.key) }.entries.sortedWith(compareBy({ it.value.type == com.flansmod.recoded.gun.VehicleType.STATIC }, { it.value.name }))
            .map { VehicleItem.stackFor(it.key) } + (if (tools) vehicleTools() else emptyList()) +
            VehicleUpgrades.all.filter { filter(it.key) }.entries.sortedWith(compareBy({ it.value.slot }, { it.value.name })).map { VehicleUpgradeItem.stackFor(it.key) } +
            ammunition(filter, mountedCalibers)
    }

    fun equipment(filter: (Identifier) -> Boolean = { true }): List<ItemStack> = Clothing.all.filter { filter(it.key) }.entries
        .sortedWith(compareBy({ it.value.asset.toString() }, { order(CLOTHING_SLOTS, it.value.slot) })).map { ClothingItem.stackFor(it.key) }

    fun crafting(filter: (Identifier) -> Boolean = { true }, withBench: Boolean = true): List<ItemStack> =
        (if (withBench) listOf(ItemStack(FlansItems.WEAPONS_BENCH)) else emptyList()) +
            Parts.all.filter { filter(it.key) }.entries.sortedWith(compareBy({ order(PART_KINDS, it.value.category) }, { it.value.name })).map { PartItem.stackFor(it.key) }

    /** Everything from one content pack, in tab order (pack tabs). */
    fun all(filter: (Identifier) -> Boolean): List<ItemStack> =
        weapons(filter) + ammunition(filter) + attachments(filter) + explosives(filter) + vehicles(filter, tools = false).filter { it.item !is AmmoItem && it.item !is MagazineItem } +
            equipment(filter) + crafting(filter, withBench = false)
}
