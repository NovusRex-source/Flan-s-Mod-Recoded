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
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth

private val MISSING = VehicleDefinition("missing").resolvedModel(FlansMod.id("missing"))
private val MODEL: DataTicket<ResolvedModel> = DataTicket.create("flansmod_vehicle_model", ResolvedModel::class.java)

/** Procedural bone angles in radians: wheel spin, steering, and per-bone turret yaw/pitch. */
private class VehiclePose(val wheelSpin: Float, val steering: Float, val yaw: Map<String, Float>, val pitch: Map<String, Float>, val flash: Boolean)
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
 */
class VehicleRenderer(context: EntityRendererProvider.Context) : GeoEntityRenderer<DriveableEntity, EntityRenderState>(context, VehicleGeoModel()) {
    init {
        shadowRadius = 1.2f
    }

    override fun addRenderData(animatable: DriveableEntity, relatedObject: Void?, renderState: EntityRenderState, partialTick: Float) {
        val yaw = HashMap<String, Float>()
        val pitch = HashMap<String, Float>()
        val hullYaw = animatable.getViewYRot(partialTick)
        animatable.definition?.seats?.forEachIndexed { index, seat ->
            val occupant = animatable.occupant(index) ?: return@forEachIndexed
            val (aimYaw, elevation) = animatable.aim(index, occupant, partialTick)
            // Positive Y rotation turns the model's front (-Z) to the vehicle's left, which is a smaller world yaw.
            seat.yawBone?.let { yaw[it] = -Mth.wrapDegrees(aimYaw - hullYaw) * Mth.DEG_TO_RAD }
            seat.pitchBone?.let { pitch[it] = elevation * Mth.DEG_TO_RAD }
        }
        (renderState as GeoRenderState).addGeckolibData(POSE, VehiclePose(
            Mth.lerp(partialTick, animatable.prevWheelSpin, animatable.wheelSpin),
            -Mth.lerp(partialTick, animatable.prevSteering, animatable.steering) * MAX_STEER,
            yaw, pitch,
            flash = animatable.passengers.any { ShotEffects.recentlyFired(it.id) },
        ))
    }

    override fun adjustModelBonesForRender(renderPassInfo: RenderPassInfo<EntityRenderState>, snapshots: BoneSnapshots) {
        val pose = (renderPassInfo.renderState() as GeoRenderState).getGeckolibData(POSE) ?: return
        for (bone in renderPassInfo.model().boneLookup().get().keys) {
            when {
                bone.startsWith("wheel") -> snapshots.ifPresent(bone) { it.setRotX(-pose.wheelSpin) }
                bone.startsWith("steer") -> snapshots.ifPresent(bone) { it.setRotY(pose.steering) }
                bone == "muzzle_flash" -> snapshots.ifPresent(bone) { it.skipRender(!pose.flash).skipChildrenRender(!pose.flash) }
            }
            pose.yaw[bone]?.let { angle -> snapshots.ifPresent(bone) { it.setRotY(angle) } }
            pose.pitch[bone]?.let { angle -> snapshots.ifPresent(bone) { it.setRotX(angle) } }
        }
    }

    private companion object {
        val MAX_STEER = 30f * Mth.DEG_TO_RAD
    }
}
