package com.flansmod.recoded.client.bench

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.bench.WeaponsBenchMenu
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/** Plain vanilla container screen for the Weapons Bench; layout comes from [WeaponsBenchMenu]. */
class WeaponsBenchScreen(menu: WeaponsBenchMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<WeaponsBenchMenu>(menu, inventory, title, WIDTH, HEIGHT) {

    init {
        inventoryLabelY = HEIGHT - 94
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256)
    }

    private companion object {
        const val WIDTH = 176
        const val HEIGHT = 190
        val TEXTURE = FlansMod.id("textures/gui/weapons_bench.png")
    }
}
