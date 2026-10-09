package com.flansmod.recoded.client.utility

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.MapColor

/**
 * A top-down terrain image ([pixels]² pixels, [blocksPerPixel] blocks each, north up) around [centre], in vanilla map
 * colours shaded by the slope like a vanilla map, from the chunks the client has loaded (others stay dark). Uploaded
 * once as a dynamic texture under [texture]; [release] it when the screen closes. Used by the artillery map and the
 * field map.
 */
class TerrainMap(private val texture: Identifier, val centre: BlockPos, val blocksPerPixel: Int, val pixels: Int = 128) {
    fun worldX(pixel: Int) = centre.x + (pixel - pixels / 2) * blocksPerPixel
    fun worldZ(pixel: Int) = centre.z + (pixel - pixels / 2) * blocksPerPixel

    /** Screen position of world [x]/[z] on a map drawn at [mapX]/[mapY] with [size] pixels. */
    fun toScreen(x: Double, z: Double, mapX: Int, mapY: Int, size: Int): Pair<Int, Int> =
        (mapX + ((x - centre.x) / blocksPerPixel + pixels / 2) * size / pixels).toInt() to (mapY + ((z - centre.z) / blocksPerPixel + pixels / 2) * size / pixels).toInt()

    /** World block column under screen position [sx]/[sy], or null off the map. */
    fun toWorld(sx: Double, sy: Double, mapX: Int, mapY: Int, size: Int): Pair<Int, Int>? {
        val i = ((sx - mapX) * pixels / size).toInt()
        val j = ((sy - mapY) * pixels / size).toInt()
        if (sx < mapX || sy < mapY || i !in 0 until pixels || j !in 0 until pixels) return null
        return worldX(i) + blocksPerPixel / 2 to worldZ(j) + blocksPerPixel / 2
    }

    fun build(level: Level) {
        val image = NativeImage(pixels, pixels, false)
        for (i in 0 until pixels) for (j in 0 until pixels) image.setPixel(i, j, colour(level, worldX(i), worldZ(j)))
        Minecraft.getInstance().textureManager.register(texture, DynamicTexture({ "flansmod terrain map" }, image))
    }

    fun draw(g: GuiGraphicsExtractor, mapX: Int, mapY: Int, size: Int) =
        g.blit(RenderPipelines.GUI_TEXTURED, texture, mapX, mapY, 0f, 0f, size, size, pixels, pixels, pixels, pixels)

    fun release() = Minecraft.getInstance().textureManager.release(texture)

    private fun colour(level: Level, x: Int, z: Int): Int {
        if (!level.hasChunk(x shr 4, z shr 4)) return UNKNOWN
        val height = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
        val pos = BlockPos(x, height - 1, z)
        val map = level.getBlockState(pos).getMapColor(level, pos)
        if (map == MapColor.NONE) return UNKNOWN
        val north = if (level.hasChunk(x shr 4, (z - blocksPerPixel) shr 4)) level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - blocksPerPixel) else height
        val brightness = when {
            height > north -> MapColor.Brightness.HIGH
            height < north -> MapColor.Brightness.LOW
            else -> MapColor.Brightness.NORMAL
        }
        return map.calculateARGBColor(brightness)
    }

    private companion object {
        const val UNKNOWN = 0xFF181818.toInt()
    }
}
