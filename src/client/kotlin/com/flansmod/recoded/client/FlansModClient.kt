package com.flansmod.recoded.client

import com.flansmod.recoded.client.config.FlansConfig
import com.flansmod.recoded.client.hud.GunHud
import com.flansmod.recoded.client.input.GunInput
import com.flansmod.recoded.client.render.GunRenderer
import com.flansmod.recoded.client.render.DefinitionIconModel
import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.network.ContentSyncPayload
import com.flansmod.recoded.network.HitPayload
import com.flansmod.recoded.network.ShotPayload
import com.flansmod.recoded.client.fx.ShotEffects
import com.flansmod.recoded.client.tab.PackTabs
import com.flansmod.recoded.client.tab.FactionTabs
import com.flansmod.recoded.client.tab.TypeTabs
import net.fabricmc.api.ClientModInitializer
import com.flansmod.recoded.client.vehicle.VehicleClient
import com.flansmod.recoded.client.vehicle.VehicleRenderer
import com.flansmod.recoded.client.vehicle.VehicleMenuScreen
import com.flansmod.recoded.client.bench.WeaponsBenchScreen
import com.flansmod.recoded.client.bench.WeaponMenuScreen
import com.flansmod.recoded.client.fuel.FuelSynthesizerScreen
import com.flansmod.recoded.client.fuel.MineRenderer
import com.flansmod.recoded.client.fuel.PetrolStationScreen
import com.flansmod.recoded.client.gamemode.BattleHud
import com.flansmod.recoded.client.gamemode.BattleMasterScreen
import com.flansmod.recoded.client.gamemode.TeamFlagScreen
import com.flansmod.recoded.client.gamemode.ShopEditorScreen
import com.flansmod.recoded.client.gamemode.BattleSpawnScreen
import com.flansmod.recoded.client.gamemode.SoldierRenderer
import com.flansmod.recoded.client.gamemode.BattleMenuScreen
import com.flansmod.recoded.registry.FlansMenus
import net.minecraft.client.gui.screens.MenuScreens
import com.flansmod.recoded.registry.FlansEntities
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.renderer.entity.ThrownItemRenderer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

object FlansModClient : ClientModInitializer {
    override fun onInitializeClient() {
        FlansConfig.register()
        DefinitionIconModel.register()
        GunItem.rendererFactory = ::GunRenderer
        ClientPlayNetworking.registerGlobalReceiver(ContentSyncPayload.TYPE) { payload, _ -> Content.apply(payload) }
        ClientPlayNetworking.registerGlobalReceiver(HitPayload.TYPE) { payload, _ -> GunHud.onHit(payload) }
        ClientPlayNetworking.registerGlobalReceiver(ShotPayload.TYPE) { payload, _ -> ShotEffects.onShot(payload) }
        ShotEffects.init()
        TypeTabs.init()
        PackTabs.init()
        FactionTabs.init()
        EntityRendererRegistry.register(FlansEntities.GRENADE, ::ThrownItemRenderer)
        EntityRendererRegistry.register(FlansEntities.DRIVEABLE, ::VehicleRenderer)
        EntityRendererRegistry.register(FlansEntities.MINE, ::MineRenderer)
        VehicleClient.init()
        com.flansmod.recoded.client.vehicle.ArtilleryClient.init()
        com.flansmod.recoded.client.aircraft.AircraftClient.init()
        com.flansmod.recoded.client.utility.UtilityClient.init()
        MenuScreens.register(FlansMenus.WEAPONS_BENCH, ::WeaponsBenchScreen)
        MenuScreens.register(FlansMenus.WEAPON, ::WeaponMenuScreen)
        MenuScreens.register(FlansMenus.VEHICLE, ::VehicleMenuScreen)
        MenuScreens.register(FlansMenus.FUEL_SYNTHESIZER, ::FuelSynthesizerScreen)
        MenuScreens.register(FlansMenus.PETROL_STATION, ::PetrolStationScreen)
        MenuScreens.register(FlansMenus.BATTLE_MASTER, ::BattleMasterScreen)
        MenuScreens.register(FlansMenus.TEAM_FLAG, ::TeamFlagScreen)
        MenuScreens.register(FlansMenus.SHOP_EDITOR, ::ShopEditorScreen)
        MenuScreens.register(FlansMenus.BATTLE_SPAWN, ::BattleSpawnScreen)
        // The border wall stops the local player only while fighting (the server decides the same for everyone).
        com.flansmod.recoded.gamemode.BattleWallBlock.clientFighter = { e ->
            e == net.minecraft.client.Minecraft.getInstance().player && BattleHud.current?.let { it.inBattle && it.running && !it.waiting } == true
        }
        EntityRendererRegistry.register(FlansEntities.SOLDIER, ::SoldierRenderer)
        BattleHud.init()
        BattleMenuScreen.init()
        com.flansmod.recoded.client.movement.MovementClient.init()
        com.flansmod.recoded.client.gear.GearClient.init()
        GunInput.init()
        GunHud.init()
    }
}
