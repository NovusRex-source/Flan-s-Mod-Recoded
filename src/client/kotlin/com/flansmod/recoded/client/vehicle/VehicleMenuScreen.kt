package com.flansmod.recoded.client.vehicle

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.bench.VehicleMenu
import com.flansmod.recoded.entity.DriveableEntity
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * Vehicle menu: upgrade slots (drag upgrades in/out), the fuel slot, the vehicle's effective stats and, left of the
 * panel, a health bar per part (the right side is where recipe viewers list items).
 */
class VehicleMenuScreen(menu: VehicleMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<VehicleMenu>(menu, inventory, title, 176, 166) {

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256)
        menu.slotNames.indices.forEach { i -> slotFrame(graphics, leftPos + VehicleMenu.slotX(i, menu.slotNames.size) - 1, topPos + VehicleMenu.SLOT_Y - 1) }
        slotFrame(graphics, leftPos + VehicleMenu.FUEL_X - 1, topPos + VehicleMenu.FUEL_Y - 1)
        partBars(graphics, menu.vehicle ?: return)
    }

    private fun slotFrame(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF373737.toInt())
        graphics.fill(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF.toInt())
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B.toInt())
    }

    /** Hull first, then each part: name and a bar from green (intact) to red, grey when broken. */
    private fun partBars(graphics: GuiGraphicsExtractor, vehicle: DriveableEntity) {
        val def = vehicle.definition ?: return
        val rows = listOf(DriveableEntity.HULL to (vehicle.health to def.health)) +
            def.parts.filterValues { it.role != com.flansmod.recoded.gun.PartRole.HULL }.map { (name, p) -> name to (vehicle.partHealth(name) to p.health) }
        val width = 84
        val x = leftPos - width - 6
        rows.forEachIndexed { i, (name, value) ->
            val (current, max) = value
            val y = topPos + 4 + i * 20
            val fraction = (current / max).coerceIn(0f, 1f)
            val label = Component.translatableWithFallback("vehicle_part.flansmod.$name", name.replace('_', ' '))
            graphics.text(font, label.string, x, y, if (fraction <= 0f) 0xFFFF5555.toInt() else 0xFFFFFFFF.toInt(), true)
            graphics.fill(x, y + 10, x + width, y + 14, 0xFF202020.toInt())
            val color = if (fraction <= 0f) 0xFF555555.toInt() else (0xFF000000 or ((255 * (1 - fraction)).toLong() shl 16) or ((255 * fraction).toLong() shl 8)).toInt()
            graphics.fill(x + 1, y + 11, x + 1 + ((width - 2) * fraction).toInt(), y + 13, color)
        }
    }

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        super.extractLabels(graphics, mouseX, mouseY)
        menu.slotNames.forEachIndexed { i, name ->
            val label = Component.translatableWithFallback("vehicle_upgrade.flansmod.slot.$name", name)
            val center = VehicleMenu.slotX(i, menu.slotNames.size) + 8
            graphics.text(font, label.string, center - font.width(label) / 2, VehicleMenu.SLOT_Y - 11, 0xFF404040.toInt(), false)
        }
        if (menu.slotNames.isEmpty()) {
            val text = Component.translatable("gui.flansmod.vehicle.no_slots")
            graphics.text(font, text.string, (imageWidth - font.width(text)) / 2, VehicleMenu.SLOT_Y + 4, 0xFF404040.toInt(), false)
        }
        val fuelLabel = Component.translatable("gui.flansmod.vehicle.fuel")
        graphics.text(font, fuelLabel.string, VehicleMenu.FUEL_X + 8 - font.width(fuelLabel) / 2, VehicleMenu.SLOT_Y - 11, 0xFF404040.toInt(), false)
        // Effective stats with upgrades.
        val vehicle = menu.vehicle ?: return
        val def = vehicle.definition ?: return
        val stats = listOfNotNull(
            "${(def.maxSpeed * 72).toInt()} km/h", "${(def.armor * 100).toInt()}% armour",
            if (def.needsFuel) "${vehicle.fuel * 100 / def.fuel.capacity}% fuel" else null,
        ).joinToString(" · ")
        graphics.text(font, stats, (imageWidth - font.width(stats)) / 2, 58, 0xFF404040.toInt(), false)
    }

    private companion object {
        val TEXTURE = FlansMod.id("textures/gui/weapon_menu.png")
    }
}
