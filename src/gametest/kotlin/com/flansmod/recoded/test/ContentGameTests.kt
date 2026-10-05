package com.flansmod.recoded.test

import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Clothing
import com.flansmod.recoded.gun.Factions
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.Vehicles
import com.flansmod.recoded.gun.factionOf
import com.flansmod.recoded.item.GunItem
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier

/** Checks of the built-in content packs as loaded (definitions reference each other by id). */
class ContentGameTests {
    private val builtIn = setOf("flansbasic", "flansvehicles", "flansww2")
    private fun ww2(path: String) = Identifier.fromNamespaceAndPath("flansww2", path)

    @GameTest
    fun everyVehicleGunHasMagazinesAndRounds(helper: GameTestHelper) {
        val vehicles = Vehicles.all.filterKeys { it.namespace in builtIn }
        helper.assertTrue(vehicles.size >= 16, "expected the modern and WW2 vehicles, got ${vehicles.size}")
        for ((id, vehicle) in vehicles) {
            helper.assertTrue(vehicle.seats.isNotEmpty() && vehicle.parts.isNotEmpty(), "$id needs seats and hit boxes")
            for (seat in vehicle.seats) {
                val gunId = seat.gun ?: continue
                val gun = Guns[gunId]
                helper.assertTrue(gun?.mounted == true, "$id: seat gun $gunId should be a mounted gun")
                val magazines = GunItem.acceptedMagazines(gunId)
                helper.assertTrue(magazines.isNotEmpty(), "$id: $gunId has no magazine")
                for (mag in magazines) {
                    val caliber = Magazines[mag]!!.caliber
                    helper.assertTrue(AmmoTypes.all.values.any { it.caliber == caliber }, "$id: no rounds of caliber $caliber for $mag")
                }
            }
        }
        for ((id, ammo) in AmmoTypes.all.filterKeys { it.namespace in builtIn }) {
            ammo.projectile?.let { helper.assertTrue(Grenades[it] != null, "$id: projectile $it is missing") }
        }
        helper.succeed()
    }

    @GameTest
    fun ww2ContentBelongsToFactions(helper: GameTestHelper) {
        val factions = Factions.all.keys.filter { it.namespace == "flansww2" }.toSet()
        helper.assertTrue(factions == setOf(ww2("axis"), ww2("usa"), ww2("uk"), ww2("ussr")), "WW2 factions: $factions")
        helper.assertTrue(factionOf(ww2("mp40")) == ww2("axis") && factionOf(ww2("tiger1")) == ww2("axis"), "German guns and tanks are Axis")
        helper.assertTrue(factionOf(ww2("sherman")) == ww2("usa") && factionOf(ww2("cromwell")) == ww2("uk") && factionOf(ww2("t34_85")) == ww2("ussr"), "Allied tanks")
        for (faction in factions) {
            helper.assertTrue(Guns.all.any { (id, g) -> !g.mounted && factionOf(id) == faction }, "$faction has no guns")
            helper.assertTrue(Vehicles.all.keys.any { factionOf(it) == faction }, "$faction has no vehicles")
            helper.assertTrue(Clothing.all.values.count { it.faction == faction } == 4, "$faction needs a helmet, tunic, trousers and boots")
        }
        // Every WW2 gun and vehicle names its side.
        val untagged = (Guns.all.filter { (id, g) -> id.namespace == "flansww2" && !g.mounted }.keys + Vehicles.all.keys.filter { it.namespace == "flansww2" })
            .filter { factionOf(it) == null }
        helper.assertTrue(untagged.isEmpty(), "WW2 content without a faction: $untagged")
        helper.succeed()
    }
}
