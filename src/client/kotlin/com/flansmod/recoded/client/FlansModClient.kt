package com.flansmod.recoded.client

import com.flansmod.recoded.client.config.FlansConfig
import com.flansmod.recoded.client.hud.GunHud
import com.flansmod.recoded.client.input.GunInput
import com.flansmod.recoded.client.render.GunRenderer
import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.network.ContentSyncPayload
import com.flansmod.recoded.network.HitPayload
import com.flansmod.recoded.network.ShotPayload
import com.flansmod.recoded.client.fx.ShotEffects
import com.flansmod.recoded.client.tab.PackTabs
import net.fabricmc.api.ClientModInitializer
import com.flansmod.recoded.registry.FlansEntities
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.renderer.entity.ThrownItemRenderer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

object FlansModClient : ClientModInitializer {
    override fun onInitializeClient() {
        FlansConfig.register()
        GunItem.rendererFactory = ::GunRenderer
        ClientPlayNetworking.registerGlobalReceiver(ContentSyncPayload.TYPE) { payload, _ -> Content.apply(payload) }
        ClientPlayNetworking.registerGlobalReceiver(HitPayload.TYPE) { payload, _ -> GunHud.onHit(payload) }
        ClientPlayNetworking.registerGlobalReceiver(ShotPayload.TYPE) { payload, _ -> ShotEffects.onShot(payload) }
        ShotEffects.init()
        PackTabs.init()
        EntityRendererRegistry.register(FlansEntities.GRENADE, ::ThrownItemRenderer)
        GunInput.init()
        GunHud.init()
    }
}
