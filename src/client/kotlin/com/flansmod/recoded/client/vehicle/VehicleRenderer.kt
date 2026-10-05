package com.flansmod.recoded.client.vehicle

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.client.fx.ShotEffects
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.ResolvedModel
import com.flansmod.recoded.gun.VehicleDefinition
import com.flansmod.recoded.gun.Vehicles
import com.geckolib.constant.dataticket.DataTicket
import com.geckolib.model.GeoModel
import com.geckolib.renderer.GeoEntityRenderer
import com.geckolib.renderer.base.BoneSnapshots
import com.geckolib.renderer.base.GeoRenderState
import com.geckolib.renderer.base.RenderPassInfo
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis

private val MISSING = VehicleDefinition("missing").resolvedModel(FlansMod.id("missing"))
private val MODEL: DataTicket<ResolvedModel> = DataTicket.create("flansmod_vehicle_model", ResolvedModel::class.java)

/** Procedural bone angles in radians: wheel spin, steering, and per-bone turret yaw/pitch. */
private class VehiclePose(
    val wheelSpin: Float, val steering: Float, val yaw: Map<String, Float>, val elevation: Map<String, Float>, val flash: Boolean,
    /** Installed upgrades by slot, and bones of broken parts. */
    val upgrades: Map<String, Identifier>, val brokenBones: Set<String>,
    /** Body on uneven ground: degrees nose up / left side up, blocks below the entity position. */
    val pitch: Float, val roll: Float, val sink: Float,
)
private val POSE: DataTicket<VehiclePose> = DataTicket.create("flansmod_vehicle_pose", VehiclePose::class.java)

private val GeoRenderState.vehicleModel get() = getGeckolibData(MODEL) ?: MISSING

/** One GeckoLib model for all vehicles; geo/texture/animation files come from the entity's definition. */
class VehicleGeoModel : GeoModel<DriveableEntity>() {
    private var currentAnimations: Identifier = MISSING.animations

    override fun addAdditionalStateData(animatable: DriveableEntity, relatedObject: Any?, renderState: GeoRenderState) {
        val model = animatable.vehicleId?.let { id -> Vehicles[id]?.resolvedModel(id) } ?: MISSING
        renderState.addGeckolibData(MODEL, model)
        currentAnimations = model.animations
    }

    override fun getModelResource(renderState: GeoRenderState) = renderState.vehicleModel.geo
    override fun getTextureResource(renderState: GeoRenderState) = renderState.vehicleModel.texture
    override fun getAnimationResource(animatable: DriveableEntity) = currentAnimations
}

/**
 * GeckoLib renderer for vehicles. Bones are driven procedurally:
 * `wheel*` spin with the distance travelled, `steer*` turn with the steering, each seat's `yaw_bone`/`pitch_bone`
 * follow where its occupant aims (turrets, gun mounts) and `muzzle_flash` shows briefly after a crew member fired.
 * `upgrade_<name>`/`default_<slot>` follow the installed upgrades; bones listed by a broken part are hidden.
 */
class VehicleRenderer(context: EntityRendererProvider.Context) : GeoEntityRenderer<DriveableEntity, EntityRenderState>(context, VehicleGeoModel()) {
    init {
        shadowRadius = 1.2f
    }

    /** Translucent (glass texels are see-through); culled, so from inside a solid hull you see out instead of its inner faces. */
    override fun getRenderType(renderState: EntityRenderState, texture: Identifier): RenderType = RenderTypes.entityTranslucentCull(texture)

    override fun addRenderData(animatable: DriveableEntity, relatedObject: Void?, renderState: EntityRenderState, partialTick: Float) {
        val yaw = HashMap<String, Float>()
        val pitch = HashMap<String, Float>()
        val hullYaw = animatable.getViewYRot(partialTick)
        val hullPitch = Mth.lerp(partialTick, animatable.prevBodyPitch, animatable.bodyPitch)
        val hullRoll = Mth.lerp(partialTick, animatable.prevBodyRoll, animatable.bodyRoll)
        animatable.definition?.seats?.forEachIndexed { index, seat ->
            val occupant = animatable.occupant(index) ?: return@forEachIndexed
            val (aimYaw, elevation) = animatable.aim(index, occupant, partialTick)
            val relYaw = Mth.wrapDegrees(aimYaw - hullYaw)
            // Positive Y rotation turns the model's front (-Z) to the vehicle's left, which is a smaller world yaw.
            seat.yawBone?.let { yaw[it] = -relYaw * Mth.DEG_TO_RAD }
            // The world elevation, less what the tilted hull already lifts the gun in its direction.
            val hullLift = hullPitch * Mth.cos((relYaw * Mth.DEG_TO_RAD).toDouble()).toFloat() - hullRoll * Mth.sin((relYaw * Mth.DEG_TO_RAD).toDouble()).toFloat()
            seat.pitchBone?.let { pitch[it] = (elevation - hullLift) * Mth.DEG_TO_RAD }
        }
        (renderState as GeoRenderState).addGeckolibData(POSE, VehiclePose(
            Mth.lerp(partialTick, animatable.prevWheelSpin, animatable.wheelSpin),
            -Mth.lerp(partialTick, animatable.prevSteering, animatable.steering) * MAX_STEER,
            yaw, pitch,
            flash = animatable.passengers.any { ShotEffects.recentlyFired(it.id) },
            upgrades = animatable.upgrades,
            brokenBones = animatable.definition?.parts.orEmpty().filter { (name, _) -> animatable.isBroken(name) }.values.flatMapTo(HashSet()) { it.bones },
            pitch = hullPitch, roll = hullRoll, sink = Mth.lerp(partialTick, animatable.prevBodySink, animatable.bodySink),
        ))
    }

    /** After GeckoLib's yaw: lower the body onto its wheels and tilt it (model front is -Z, its left side -X). */
    override fun applyRotations(renderPassInfo: RenderPassInfo<EntityRenderState>, poseStack: PoseStack, nativeScale: Float) {
        val pose = (renderPassInfo.renderState() as GeoRenderState).getGeckolibData(POSE)
        if (pose != null) poseStack.translate(0f, pose.sink, 0f)
        super.applyRotations(renderPassInfo, poseStack, nativeScale)
        if (pose != null) {
            poseStack.rotateDegrees(Axis.XP, pose.pitch)
            poseStack.rotateDegrees(Axis.ZP, -pose.roll)
        }
    }

    override fun adjustModelBonesForRender(renderPassInfo: RenderPassInfo<EntityRenderState>, snapshots: BoneSnapshots) {
        val pose = (renderPassInfo.renderState() as GeoRenderState).getGeckolibData(POSE) ?: return
        for (bone in renderPassInfo.model().boneLookup().get().keys) {
            when {
                bone.startsWith("wheel") -> snapshots.ifPresent(bone) { it.setRotX(-pose.wheelSpin) }
                bone.startsWith("steer") -> snapshots.ifPresent(bone) { it.setRotY(pose.steering) }
                bone == "muzzle_flash" -> snapshots.hide(bone, !pose.flash)
                // Like gun attachments: `upgrade_<name>` while installed, `default_<slot>` while the slot is empty.
                bone.startsWith("upgrade_") -> snapshots.hide(bone, pose.upgrades.values.none { it.path == bone.removePrefix("upgrade_") })
                bone.startsWith("default_") -> snapshots.hide(bone, bone.removePrefix("default_") in pose.upgrades)
            }
            if (bone in pose.brokenBones) snapshots.hide(bone, true)
            pose.yaw[bone]?.let { angle -> snapshots.ifPresent(bone) { it.setRotY(angle) } }
            pose.elevation[bone]?.let { angle -> snapshots.ifPresent(bone) { it.setRotX(angle) } }
        }
    }

    private fun BoneSnapshots.hide(bone: String, hidden: Boolean) = ifPresent(bone) { it.skipRender(hidden).skipChildrenRender(hidden) }

    private companion object {
        val MAX_STEER = 30f * Mth.DEG_TO_RAD
    }
}
