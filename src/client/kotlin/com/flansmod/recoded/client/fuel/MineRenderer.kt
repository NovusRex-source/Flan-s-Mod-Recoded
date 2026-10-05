package com.flansmod.recoded.client.fuel

import com.flansmod.recoded.entity.MineEntity
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.item.ItemDisplayContext

/** A mine lying on the ground: its item model in the `ground` display context, turned the way it was laid. */
class MineRenderer(context: EntityRendererProvider.Context) : EntityRenderer<MineEntity, MineRenderer.State>(context) {
    private val items = context.itemModelResolver

    class State : ThrownItemRenderState() {
        var yaw = 0f
    }

    override fun createRenderState() = State()

    override fun extractRenderState(entity: MineEntity, state: State, partialTick: Float) {
        super.extractRenderState(entity, state, partialTick)
        state.yaw = entity.yRot
        items.updateForNonLiving(state.item, entity.item, ItemDisplayContext.GROUND, entity)
    }

    override fun submit(state: State, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        poseStack.rotateDegrees(Axis.YP, 180f - state.yaw)
        poseStack.translate(0f, 0.08f, 0f)
        state.item.submit(poseStack, collector, state.lightCoords, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, state.outlineColor)
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }
}
