package com.flansmod.recoded.client.fuel

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.fuel.FuelStack
import com.flansmod.recoded.fuel.FuelSynthesizerBlockEntity
import com.flansmod.recoded.fuel.FuelSynthesizerMenu
import com.flansmod.recoded.fuel.PetrolStationBlockEntity
import com.flansmod.recoded.fuel.PetrolStationMenu
import com.flansmod.recoded.gun.FuelTypes
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu

/** Shared look: vanilla panel texture (slots drawn in it), vertical tank gauges drawn on top. */
abstract class FuelMachineScreen<M : AbstractContainerMenu>(menu: M, inventory: Inventory, title: Component, private val texture: String) :
    AbstractContainerScreen<M>(menu, inventory, title, 176, 166) {

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        graphics.blit(RenderPipelines.GUI_TEXTURED, FlansMod.id("textures/gui/$texture.png"), leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256)
    }

    /** A gauge: frame at ([x], [y]) relative to the panel, filled from the bottom by [fraction]. */
    protected fun gauge(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, fraction: Float, color: Int) {
        val gx = leftPos + x
        val gy = topPos + y
        graphics.fill(gx, gy, gx + w, gy + h, 0xFF2A2A2A.toInt())
        val filled = ((h - 2) * fraction.coerceIn(0f, 1f)).toInt()
        graphics.fill(gx + 1, gy + h - 1 - filled, gx + w - 1, gy + h - 1, color)
    }

    protected fun fuelColor(type: String?) = 0xFF000000.toInt() or FuelStack.colour(type ?: FuelTypes.PETROL)

    protected fun litres(units: Int) = Component.translatable("gui.flansmod.fuel.litres", FuelTypes.litres(units))
}

class FuelSynthesizerScreen(menu: FuelSynthesizerMenu, inventory: Inventory, title: Component) :
    FuelMachineScreen<FuelSynthesizerMenu>(menu, inventory, title, "fuel_synthesizer") {

    private lateinit var modeButton: Button

    override fun init() {
        super.init()
        modeButton = addRenderableWidget(Button.builder(modeText()) { minecraft.gameMode?.handleInventoryButtonClick(menu.containerId, 0) }
            .bounds(leftPos + 66, topPos + 58, 54, 16).build())
    }

    private fun modeText() = FuelStack.name(menu.mode)

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        modeButton.message = modeText()
        // Water (left of the slots), progress arrow, fuel tank.
        gauge(graphics, 22, 18, 12, 52, menu.waterBatches / 16f, 0xFF3060D0.toInt())
        val progress = menu.progress * 40 / FuelSynthesizerBlockEntity.BATCH_TICKS
        graphics.fill(leftPos + 70, topPos + 33, leftPos + 110, topPos + 37, 0xFF555555.toInt())
        graphics.fill(leftPos + 70, topPos + 33, leftPos + 70 + progress, topPos + 37, 0xFFE0E0E0.toInt())
        gauge(graphics, 156, 18, 12, 52, menu.fuel.toFloat() / FuelSynthesizerBlockEntity.CAPACITY, fuelColor(menu.tankType))
    }

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        super.extractLabels(graphics, mouseX, mouseY)
        val tank = menu.tankType?.let { FuelStack.name(it).copy().append(" ").append(litres(menu.fuel)) } ?: Component.translatable("gui.flansmod.fuel.empty")
        graphics.text(font, tank, 66, 20, 0xFF404040.toInt(), false)
    }
}

class PetrolStationScreen(menu: PetrolStationMenu, inventory: Inventory, title: Component) :
    FuelMachineScreen<PetrolStationMenu>(menu, inventory, title, "petrol_station") {

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        gauge(graphics, 74, 18, 28, 56, menu.fuel.toFloat() / PetrolStationBlockEntity.CAPACITY, fuelColor(menu.tankType))
    }

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        super.extractLabels(graphics, mouseX, mouseY)
        val tank = menu.tankType?.let { FuelStack.name(it).copy().append(" ").append(litres(menu.fuel)) } ?: Component.translatable("gui.flansmod.fuel.empty")
        // Tank contents right of the title, "pumping" under the out slot.
        graphics.text(font, tank, imageWidth - 8 - font.width(tank), titleLabelY, 0xFF404040.toInt(), false)
        if (menu.pumping) graphics.text(font, Component.translatable("gui.flansmod.fuel.pumping"), 120, 58, 0xFF2E7D32.toInt(), false)
        graphics.text(font, Component.translatable("gui.flansmod.fuel.in"), 22, 24, 0xFF404040.toInt(), false)
        graphics.text(font, Component.translatable("gui.flansmod.fuel.out"), 128, 24, 0xFF404040.toInt(), false)
    }
}
