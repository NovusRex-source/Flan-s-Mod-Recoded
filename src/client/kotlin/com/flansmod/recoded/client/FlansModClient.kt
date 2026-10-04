package com.flansmod.recoded.client

import com.flansmod.recoded.client.config.FlansConfig
import com.flansmod.recoded.client.hud.GunHud
import com.flansmod.recoded.client.input.GunInput
import com.flansmod.recoded.client.render.GunRenderer
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.network.GunSyncPayload
import com.flansmod.recoded.network.HitPayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

object FlansModClient : ClientModInitializer {
    override fun onInitializeClient() {
        FlansConfig.register()
        GunItem.rendererFactory = ::GunRenderer
        ClientPlayNetworking.registerGlobalReceiver(GunSyncPayload.TYPE) { payload, _ -> Guns.replace(payload.guns) }
        ClientPlayNetworking.registerGlobalReceiver(HitPayload.TYPE) { payload, _ -> GunHud.onHit(payload) }
        GunInput.init()
        GunHud.init()
    }
}
