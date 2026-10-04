package com.flansmod.recoded.client.render

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.client.input.GunInput
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.ResolvedModel
import com.flansmod.recoded.gun.Transform
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.gunId
import com.geckolib.constant.DataTickets
import com.geckolib.constant.dataticket.DataTicket
import com.geckolib.model.GeoModel
import com.geckolib.renderer.GeoItemRenderer
import com.geckolib.renderer.base.GeoRenderState
import com.geckolib.renderer.base.RenderPassInfo
import net.minecraft.client.resources.model.cuboid.ItemTransform
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Vector3f

private val MODEL: DataTicket<ResolvedModel> = DataTicket.create("flansmod_gun_model", ResolvedModel::class.java)
private val AIM: DataTicket<Float> = DataTicket.create("flansmod_aim", Float::class.javaObjectType)
private val MISSING = GunDefinition("missing").resolvedModel(FlansMod.id("missing"))

private val GeoRenderState.gunModel get() = getGeckolibData(MODEL) ?: MISSING

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

    override fun getModelResource(renderState: GeoRenderState) = renderState.gunModel.geo
    override fun getTextureResource(renderState: GeoRenderState) = renderState.gunModel.texture
    override fun getAnimationResource(animatable: GunItem) = currentAnimations
}

/**
 * Applies the gun's display transforms. The item's base model has none, so these replace vanilla's
 * per-context transforms with identical semantics (rotation around the item centre, mirrored for the
 * left hand). In first person the pose blends towards the `ads` transform while aiming.
 */
class GunRenderer : GeoItemRenderer<GunItem>(GunGeoModel()) {
    override fun addRenderData(animatable: GunItem, relatedObject: RenderData?, renderState: GeoRenderState, partialTick: Float) {
        renderState.addGeckolibData(AIM, GunInput.aimProgress(partialTick))
    }

    override fun adjustRenderPose(renderPassInfo: RenderPassInfo<GeoRenderState>) {
        val state = renderPassInfo.renderState()
        val context = state.getGeckolibData(DataTickets.ITEM_RENDER_PERSPECTIVE) ?: ItemDisplayContext.NONE
        val display = state.gunModel.display
        var transform = display.forContext(context)
        if (context.firstPerson()) {
            val aim = state.getGeckolibData(AIM) ?: 0f
            if (aim > 0f) transform = transform.lerp(display[Transform.ADS] ?: transform, aim)
        }

        renderPassInfo.poseStack().apply {
            translate(0.5f, 0.5f, 0.5f)
            transform.toVanilla().apply(context.leftHand(), last())
            translate(-0.5f, -0.5f, -0.5f)
        }
        super.adjustRenderPose(renderPassInfo)
    }

    private fun Map<String, Transform>.forContext(context: ItemDisplayContext): Transform =
        get(context.serializedName)
            ?: when (context) { // vanilla falls back to the mirrored right-hand transform
                ItemDisplayContext.FIRST_PERSON_LEFT_HAND -> get(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND.serializedName)
                ItemDisplayContext.THIRD_PERSON_LEFT_HAND -> get(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND.serializedName)
                else -> null
            }
            ?: Transform()

    private fun Transform.lerp(other: Transform, t: Float) = Transform(
        rotation.lerp(other.rotation, t), translation.lerp(other.translation, t), scale.lerp(other.scale, t),
    )

    private fun List<Float>.lerp(other: List<Float>, t: Float) = indices.map { Mth.lerp(t, this[it], other.getOrElse(it) { this[it] }) }

    private fun List<Float>.vec(divisor: Float = 1f) = Vector3f(getOrElse(0) { 0f } / divisor, getOrElse(1) { 0f } / divisor, getOrElse(2) { 0f } / divisor)

    private fun Transform.toVanilla() = ItemTransform(rotation.vec(), translation.vec(16f), scale.vec())
}
