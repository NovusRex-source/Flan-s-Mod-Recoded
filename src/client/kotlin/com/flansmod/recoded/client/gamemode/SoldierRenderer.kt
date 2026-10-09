package com.flansmod.recoded.client.gamemode

import com.flansmod.recoded.gamemode.SoldierEntity
import com.flansmod.recoded.item.GunItem
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.ArmorModelSet
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.HumanoidMobRenderer
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer
import net.minecraft.client.renderer.entity.state.HumanoidRenderState
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.HumanoidArm

/**
 * Battle bots look like players: vanilla player model with one of the default (wide) skins, picked by entity UUID,
 * their faction clothing through vanilla's armour layer (clothing are equipment assets) and the gun held like
 * players hold it (two-handed crossbow pose, see AvatarRendererMixin).
 */
class SoldierRenderer(context: EntityRendererProvider.Context) :
    HumanoidMobRenderer<SoldierEntity, SoldierRenderer.State, HumanoidModel<SoldierRenderer.State>>(context, HumanoidModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5f) {

    class State : HumanoidRenderState() {
        var skin: Identifier = SKINS.first()
    }

    init {
        addLayer(HumanoidArmorLayer(this, ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.modelSet) { HumanoidModel<State>(it) }, context.equipmentRenderer))
    }

    override fun createRenderState() = State()

    override fun extractRenderState(entity: SoldierEntity, state: State, partialTick: Float) {
        super.extractRenderState(entity, state, partialTick)
        state.skin = SKINS[Math.floorMod(entity.uuid.hashCode(), SKINS.size)]
    }

    override fun getTextureLocation(state: State): Identifier = state.skin

    override fun getArmPose(mob: SoldierEntity, arm: HumanoidArm): HumanoidModel.ArmPose =
        if (arm == mob.mainArm && mob.mainHandItem.item is GunItem) HumanoidModel.ArmPose.CROSSBOW_HOLD else super.getArmPose(mob, arm)

    companion object {
        private val SKINS = listOf("steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri")
            .map { Identifier.withDefaultNamespace("textures/entity/player/wide/$it.png") }
    }
}
