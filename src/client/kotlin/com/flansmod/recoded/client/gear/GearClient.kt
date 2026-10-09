package com.flansmod.recoded.client.gear

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gear.GearItem
import com.flansmod.recoded.gear.GearType
import com.flansmod.recoded.gear.OpenBackpackPayload
import com.flansmod.recoded.gear.gear
import com.flansmod.recoded.gear.gearId
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.attachments
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.model.player.PlayerModel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.RenderLayerParent
import net.minecraft.client.renderer.entity.layers.RenderLayer
import net.minecraft.client.renderer.entity.player.AvatarRenderer
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult

/**
 * Client side of gear and weapon accessories: the backpack key, binocular zoom, backpacks drawn on players' backs,
 * open parachutes (canopy above the player, and the local player's sink rate), and laser sight dots (for every player holding a gun with a laser, so you see enemy lasers too).
 */
object GearClient {
    private val CATEGORY = KeyMapping.Category(FlansMod.id("flansmod"))
    val BACKPACK: KeyMapping = KeyMappingHelper.registerKeyMapping(KeyMapping("key.flansmod.backpack", InputConstants.Type.KEYBOARD, InputConstants.KEY_B, CATEGORY))

    /** The backpack of a player, put on its render state by `AvatarRendererMixin`. */
    @JvmField
    val WORN: RenderStateDataKey<Identifier> = RenderStateDataKey.create { "flansmod:worn_backpack" }

    @JvmStatic
    fun worn(entity: net.minecraft.world.entity.Entity): Identifier? =
        entity.getAttached(com.flansmod.recoded.gear.GearSlots.BACK)?.gearId

    /** The canopy model of a player's open parachute, put on its render state by `AvatarRendererMixin`. */
    @JvmField
    val CANOPY: RenderStateDataKey<Identifier> = RenderStateDataKey.create { "flansmod:parachute_canopy" }

    @JvmStatic
    fun canopy(entity: net.minecraft.world.entity.Entity): Identifier? =
        (entity as? net.minecraft.world.entity.player.Player)?.let(com.flansmod.recoded.gear.Parachutes::openParachute)?.canopy

    /** Zoom while looking through binoculars (1 = none). */
    @JvmStatic
    fun binocularZoom(): Float {
        val player = Minecraft.getInstance().player ?: return 1f
        if (!player.isUsingItem) return 1f
        return player.useItem.gear?.takeIf { it.type == GearType.BINOCULARS }?.zoom ?: 1f
    }

    /** The overlay of the binoculars in use, if any. */
    fun binocularOverlay(): Identifier? {
        val player = Minecraft.getInstance().player ?: return null
        if (!player.isUsingItem) return null
        return player.useItem.gear?.takeIf { it.type == GearType.BINOCULARS }?.overlay
    }

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            while (BACKPACK.consumeClick()) if (mc.player != null) ClientPlayNetworking.send(OpenBackpackPayload)
            lasers(mc)
            parachute(mc)
        }
        // Frames under Flan's gear slots (backpack, armour plates) in the survival and creative inventory.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is net.minecraft.client.gui.screens.inventory.InventoryScreen || screen is net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen) {
                net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterBackground(screen).register { s, graphics, _, _, _ -> gearSlotFrames(s as net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>, graphics) }
            }
        }
        LivingEntityRenderLayerRegistrationCallback.EVENT.register { _, renderer, helper, _ ->
            @Suppress("UNCHECKED_CAST")
            if (renderer is AvatarRenderer<*>) {
                helper.register(BackpackLayer(renderer as RenderLayerParent<AvatarRenderState, PlayerModel>))
                helper.register(ParachuteLayer(renderer as RenderLayerParent<AvatarRenderState, PlayerModel>))
            }
        }
    }

    private val SLOT_FRAME = FlansMod.id("textures/gui/gear_slot.png")

    private fun gearSlotFrames(screen: net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>, graphics: net.minecraft.client.gui.GuiGraphicsExtractor) {
        val accessor = screen as com.flansmod.recoded.client.mixin.AbstractContainerScreenAccessor
        fun frame(x: Int, y: Int) = graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, SLOT_FRAME,
            accessor.`flansmod$leftPos`() + x - 1, accessor.`flansmod$topPos`() + y - 1, 0f, 0f, 18, 18, 18, 18)
        if (screen is net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen) {
            // Creative wraps the slots in its own class: frames at the spots CreativeInventoryScreenMixin gives them.
            if (!screen.isInventoryOpen) return
            val player = Minecraft.getInstance().player ?: return
            val plates = com.flansmod.recoded.gear.GearSlots.PlateContainer(player).capacity
            for (i in 0 until plates) frame(127 + i * 18, 6)
            frame(127, 33)
            return
        }
        for (slot in screen.menu.slots) {
            if ((slot !is com.flansmod.recoded.gear.GearSlots.BackSlot && slot !is com.flansmod.recoded.gear.GearSlots.PlateSlot) || !slot.isActive) continue
            frame(slot.x, slot.y)
        }
    }

    /**
     * The local player moves on their own client, so the open parachute's sink rate is applied here (the server keeps
     * the fall distance at zero and closes it on landing).
     */
    private fun parachute(mc: Minecraft) {
        val player = mc.player ?: return
        val chute = com.flansmod.recoded.gear.Parachutes.openParachute(player) ?: return
        val motion = player.deltaMovement
        if (motion.y < -chute.fallSpeed) player.deltaMovement = net.minecraft.world.phys.Vec3(motion.x, -chute.fallSpeed, motion.z)
        player.resetFallDistance()
    }

    /** A dot where every laser-sighted gun in view points. */
    private fun lasers(mc: Minecraft) {
        val level = mc.level ?: return
        val camera = mc.player ?: return
        for (player in level.players()) {
            val stack = player.mainHandItem
            if (stack.item !is GunItem || player.distanceToSqr(camera) > 96 * 96) continue
            val laser = stack.attachments.values.firstNotNullOfOrNull { Attachments[it]?.laser } ?: continue
            val eye = player.eyePosition
            val end = eye.add(player.lookAngle.scale(laser.range))
            val hit = level.clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            val at = if (hit.type == HitResult.Type.MISS) continue else hit.location
            mc.particleEngine.createParticle(DustParticleOptions(laser.rgb, 0.6f), at.x, at.y, at.z, 0.0, 0.0, 0.0)
        }
    }

    /** The open parachute's canopy model above the player, upright regardless of the body's pose, lines down to the shoulders. */
    class ParachuteLayer(parent: RenderLayerParent<AvatarRenderState, PlayerModel>) : RenderLayer<AvatarRenderState, PlayerModel>(parent) {
        private val item = ItemStackRenderState()

        override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: AvatarRenderState, yRot: Float, xRot: Float) {
            val model = state.getData(CANOPY) ?: return
            if (state.isInvisible) return
            val stack = net.minecraft.world.item.ItemStack(com.flansmod.recoded.gear.GearItems.GEAR).apply { set(net.minecraft.core.component.DataComponents.ITEM_MODEL, model) }
            Minecraft.getInstance().itemModelResolver.updateForTopItem(item, stack, ItemDisplayContext.FIXED, null, null, 0)
            poseStack.pushPose()
            // Model space is upside down (y down) with the origin at the neck: turn it over, lift the canopy so its
            // lines (the model's lowest 22 pixels below the centre) end at the shoulders.
            poseStack.rotateDegrees(Axis.ZP, 180f)
            poseStack.translate(0f, 22f / 16f * CANOPY_SCALE, 0f)
            poseStack.scale(CANOPY_SCALE, CANOPY_SCALE, CANOPY_SCALE)
            item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor)
            poseStack.popPose()
        }

        private companion object {
            const val CANOPY_SCALE = 1.25f
        }
    }

    /** The worn backpack's item model on the player's back (follows the body: crouching, crawling, ...). */
    class BackpackLayer(parent: RenderLayerParent<AvatarRenderState, PlayerModel>) : RenderLayer<AvatarRenderState, PlayerModel>(parent) {
        private val item = ItemStackRenderState()

        override fun submit(poseStack: PoseStack, collector: SubmitNodeCollector, light: Int, state: AvatarRenderState, yRot: Float, xRot: Float) {
            val id = state.getData(WORN) ?: return
            if (state.isInvisible) return
            Minecraft.getInstance().itemModelResolver.updateForTopItem(item, GearItem.stackFor(id), ItemDisplayContext.FIXED, null, null, 0)
            poseStack.pushPose()
            parentModel.body.translateAndRotate(poseStack)
            // Body space is upside down (y down): the model is turned over, its straps (facing -Z) against the back.
            poseStack.translate(0f, 0.38f, 0.1f)
            poseStack.rotateDegrees(Axis.ZP, 180f)
            poseStack.scale(0.62f, 0.62f, 0.62f)
            item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor)
            poseStack.popPose()
        }
    }
}
