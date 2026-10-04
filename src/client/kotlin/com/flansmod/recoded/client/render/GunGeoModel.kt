package com.flansmod.recoded.client.render

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.ResolvedModel
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.gunId
import com.geckolib.constant.dataticket.DataTicket
import com.geckolib.model.GeoModel
import com.geckolib.renderer.GeoItemRenderer
import com.geckolib.renderer.base.GeoRenderState
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * One GeckoLib model for all guns: the geo/texture/animation files are picked per stack from the
 * gun definition. GeckoLib resolves animations right after [addAdditionalStateData] within the same
 * render-state extraction, so remembering the last stack's animation file is safe (render thread only).
 */
class GunGeoModel : GeoModel<GunItem>() {
    private var currentAnimations: Identifier = MISSING.animations

    override fun addAdditionalStateData(animatable: GunItem, relatedObject: Any?, renderState: GeoRenderState) {
        val stack = when (relatedObject) {
            is ItemStack -> relatedObject
            is GeoItemRenderer.RenderData -> relatedObject.itemStack()
            else -> null
        }
        val model = stack?.gunId?.let { id -> Guns[id]?.resolvedModel(id) } ?: MISSING
        renderState.addGeckolibData(MODEL, model)
        currentAnimations = model.animations
    }

    private val GeoRenderState.model get() = getGeckolibData(MODEL) ?: MISSING

    override fun getModelResource(renderState: GeoRenderState) = renderState.model.geo
    override fun getTextureResource(renderState: GeoRenderState) = renderState.model.texture
    override fun getAnimationResource(animatable: GunItem) = currentAnimations

    companion object {
        private val MODEL: DataTicket<ResolvedModel> = DataTicket.create("flansmod_gun_model", ResolvedModel::class.java)
        private val MISSING = GunDefinition("missing").resolvedModel(FlansMod.id("missing"))
    }
}

class GunRenderer : GeoItemRenderer<GunItem>(GunGeoModel())
