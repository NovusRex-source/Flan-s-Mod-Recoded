package com.flansmod.recoded.client.vehicle

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.withAmmo
import com.flansmod.recoded.gun.VehicleType
import com.flansmod.recoded.item.AmmoItem
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth

/**
 * Vehicle HUD (Fabric HUD API): a panel at the bottom right matching the seat and vehicle - hull state for everyone,
 * speed and fuel gauges for the driver, a hull/turret direction indicator on tracked vehicles and turret seats, and
 * the seat gun's ammunition with a reload bar for gunners. While looking through a gun sight only the gun part shows.
 */
object VehicleHud {
    private const val WIDTH = 150
    private const val BACKGROUND = 0xA0101410.toInt()
    private const val FRAME = 0xFF4A5A40.toInt()
    private const val TEXT = 0xFFE8E8D8.toInt()
    private const val DIM = 0xFF9AA090.toInt()

    fun init() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, FlansMod.id("vehicle")) { graphics, delta ->
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return@attachElementAfter
            val vehicle = player.vehicle as? DriveableEntity ?: return@attachElementAfter
            draw(graphics, mc.font, vehicle, vehicle.seatOf(player), delta.getGameTimeDeltaPartialTick(false))
        }
    }

    private fun draw(g: GuiGraphicsExtractor, font: Font, vehicle: DriveableEntity, seat: Int, partial: Float) {
        val def = vehicle.definition ?: return
        val gunId = vehicle.seat(seat)?.gun
        val gun = Guns[gunId]
        val static = def.type == VehicleType.STATIC
        val driver = seat == 0 && !static
        val turret = vehicle.seat(seat)?.turret == true
        val showIndicator = (def.type == VehicleType.TANK || turret) && !static
        val sighting = VehicleClient.sighting

        // Rows: header, hull, [speed, fuel], [gun, ammo, reload], hints.
        var rows = 2
        if (driver && !sighting) rows += if (def.needsFuel) 2 else 1
        if (gun != null) rows += 2
        if (static && gun != null) rows += 1
        rows += 1
        val height = rows * 11 + 6
        val x = g.guiWidth() - WIDTH - 6
        val y = g.guiHeight() - height - 26
        g.fill(x, y, x + WIDTH, y + height, BACKGROUND)
        g.outline(x, y, WIDTH, height, FRAME)
        var line = y + 4
        fun row() = line.also { line += 11 }

        val role = when {
            static && gun != null -> "gunner"
            driver -> "driver"
            gun != null -> "gunner"
            else -> "passenger"
        }
        val name = Component.translatableWithFallback("vehicle.${vehicle.vehicleId?.toLanguageKey()}", def.name).string
        val header = row()
        g.text(font, name, x + 4, header, TEXT)
        val roleText = Component.translatable("hud.flansmod.vehicle.role.$role").string
        g.text(font, roleText, x + WIDTH - 4 - font.width(roleText), header, DIM)

        bar(g, font, x, row(), Component.translatable("hud.flansmod.vehicle.hull").string, vehicle.health / def.health, healthColor(vehicle.health / def.health))
        if (driver && !sighting) {
            val speed = vehicle.position().subtract(vehicle.xo, vehicle.yo, vehicle.zo).horizontalDistance()
            bar(g, font, x, row(), "${(speed * 72).toInt()} km/h", (speed / def.maxSpeed).toFloat(), 0xFF6FB0E0.toInt())
            if (def.needsFuel) bar(g, font, x, row(), com.flansmod.recoded.fuel.FuelStack.name(def.fuel.type).string,
                vehicle.fuel.toFloat() / def.fuel.capacity, if (vehicle.fuel < def.fuel.capacity / 10) 0xFFE05030.toInt() else 0xFFE0B040.toInt())
        }
        if (gun != null && gunId != null) {
            g.text(font, gun.name, x + 4, row(), TEXT)
            val mag = vehicle.seatMagazines[seat]
            val ammoLine = row()
            val reloadEnd = vehicle.reloadEnds[seat]
            if (reloadEnd != null) {
                val total = gun.reloadTicks.coerceAtLeast(1)
                val left = (reloadEnd - vehicle.level().gameTime - partial).coerceAtLeast(0f)
                bar(g, font, x, ammoLine, Component.translatable("hud.flansmod.vehicle.reloading").string, 1f - left / total, 0xFFE0B040.toInt())
            } else {
                val ammo = mag?.ammo?.let { AmmoItem.displayName(it).string } ?: Component.translatable("hud.flansmod.no_magazine").string
                val count = if (mag == null) "" else "${mag.rounds}/${mag.capacity}"
                g.text(font, count, x + 4, ammoLine, if (mag == null || mag.isEmpty) 0xFFE05030.toInt() else TEXT)
                g.text(font, font.plainSubstrByWidth(ammo, WIDTH - 12 - font.width(count)), x + 8 + font.width(count), ammoLine, DIM)
            }
        }
        if (static && gun != null) {
            // Emplacements (mortars): elevation and where the shell comes down on level ground.
            val occupant = vehicle.occupant(seat)
            val elevation = occupant?.let { vehicle.aim(seat, it, partial).second } ?: 0f
            val range = estimateRange(vehicle, seat, gun, elevation)
            g.text(font, Component.translatable("hud.flansmod.vehicle.elevation", "%.0f".format(elevation), range).string, x + 4, row(), TEXT)
        }
        val hint = Component.translatable(if (gun != null) "hud.flansmod.vehicle.hint_gun" else "hud.flansmod.vehicle.hint").string
        g.text(font, font.plainSubstrByWidth(hint, WIDTH - 8), x + 4, row(), DIM)

        if (showIndicator) indicator(g, vehicle, seat, x - 36, y + height - 34, partial)
        warnings(g, font, vehicle, seat, x, y)
    }

    /**
     * Flight of the loaded round (projectile gravity, vanilla throwable drag 0.99 per tick) from the muzzle until it is
     * back at the vehicle's ground level: the range in blocks, or "?" without ammo.
     */
    private fun estimateRange(vehicle: DriveableEntity, seat: Int, gun: com.flansmod.recoded.gun.GunDefinition, elevation: Float): String {
        val ammo = vehicle.seatMagazines[seat]?.ammoDefinition ?: return "?"
        val gravity = ammo.projectile?.let { com.flansmod.recoded.gun.Grenades[it]?.gravity } ?: gun.gravity
        val speed = gun.withAmmo(ammo).velocity
        var vx = speed * kotlin.math.cos(Math.toRadians(elevation.toDouble()))
        var vy = speed * kotlin.math.sin(Math.toRadians(elevation.toDouble()))
        val muzzle = vehicle.seat(seat)?.let { it.pivot.getOrElse(1) { 1.0 } } ?: 1.0
        var x = 0.0
        var y = muzzle
        repeat(1200) {
            x += vx; y += vy
            vx *= 0.99; vy = vy * 0.99 - gravity
            if (y <= 0 && vy < 0) return "%.0f".format(x)
        }
        return "?"
    }

    /** Label left, bar right; [fraction] 0..1. */
    private fun bar(g: GuiGraphicsExtractor, font: Font, x: Int, y: Int, label: String, fraction: Float, color: Int) {
        g.text(font, label, x + 4, y, TEXT)
        val bx = x + 58
        val bw = WIDTH - 62
        g.fill(bx, y + 1, bx + bw, y + 7, 0xFF202420.toInt())
        g.fill(bx + 1, y + 2, bx + 1 + ((bw - 2) * fraction.coerceIn(0f, 1f)).toInt(), y + 6, color)
    }

    private fun healthColor(f: Float) = when {
        f > 0.6f -> 0xFF60C050.toInt()
        f > 0.3f -> 0xFFE0B040.toInt()
        else -> 0xFFE05030.toInt()
    }

    /** Top view: the hull pointing up, the turret/gun line where the seat's gun points relative to it. */
    private fun indicator(g: GuiGraphicsExtractor, vehicle: DriveableEntity, seat: Int, x: Int, y: Int, partial: Float) {
        g.fill(x, y, x + 30, y + 30, BACKGROUND)
        g.outline(x, y, 30, 30, FRAME)
        g.fill(x + 10, y + 6, x + 20, y + 25, 0xFF6A7A50.toInt())  // hull
        g.fill(x + 9, y + 6, x + 10, y + 25, 0xFF303828.toInt())   // tracks
        g.fill(x + 20, y + 6, x + 21, y + 25, 0xFF303828.toInt())
        // Your own turret, else the vehicle's first one.
        val gunSeat = seat.takeIf { vehicle.seat(it)?.turret == true }
            ?: (0 until (vehicle.definition?.seats?.size ?: 0)).firstOrNull { vehicle.seat(it)?.turret == true } ?: return
        val occupant = vehicle.occupant(gunSeat) ?: return
        val relative = Mth.wrapDegrees(vehicle.aim(gunSeat, occupant, partial).first - vehicle.getViewYRot(partial))
        g.pose().pushMatrix()
        g.pose().translate(x + 15f, y + 15f)
        g.pose().rotate(relative * Mth.DEG_TO_RAD)
        g.fill(-4, -4, 4, 4, 0xFF9AB070.toInt())  // turret
        g.fill(-1, -14, 1, -4, 0xFFE8E8D8.toInt())  // gun
        g.pose().popMatrix()
    }

    /** Broken engine / wheels / own gun, above the panel. */
    private fun warnings(g: GuiGraphicsExtractor, font: Font, vehicle: DriveableEntity, seat: Int, x: Int, y: Int) {
        val lines = buildList {
            if (seat == 0 && !vehicle.engineWorks) add(Component.translatable("hud.flansmod.vehicle.engine_broken").string)
            if (seat == 0 && vehicle.propulsion < 1f) add(Component.translatable("hud.flansmod.vehicle.propulsion", (vehicle.propulsion * 100).toInt()).string)
            if (vehicle.seat(seat)?.gun != null && !vehicle.weaponWorks(seat)) add(Component.translatable("hud.flansmod.vehicle.weapon_broken").string)
        }
        lines.forEachIndexed { i, text -> g.text(font, text, x + 4, y - 11 * (lines.size - i), 0xFFFF5040.toInt()) }
    }
}
