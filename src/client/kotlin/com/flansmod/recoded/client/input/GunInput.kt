package com.flansmod.recoded.client.input

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.client.config.FlansConfig
import com.flansmod.recoded.client.vehicle.VehicleClient
import com.flansmod.recoded.gun.FireMode
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.item.ammo
import com.flansmod.recoded.item.definition
import com.flansmod.recoded.item.shotDefinition
import com.flansmod.recoded.network.AimPayload
import com.flansmod.recoded.network.AttachPayload
import com.flansmod.recoded.network.FireModePayload
import com.flansmod.recoded.network.OpenWeaponMenuPayload
import com.flansmod.recoded.item.fireMode
import com.flansmod.recoded.network.ReloadPayload
import com.flansmod.recoded.network.ShootPayload
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.util.Mth

/**
 * Client half of gunplay: turns mouse input into [ShootPayload]/[ReloadPayload], tracks aim-down-sights
 * and applies procedural camera recoil. The server re-checks fire rate and ammo, so the local cooldown
 * only exists to keep recoil in sync with real shots and to avoid packet spam.
 */
object GunInput {
    private val CATEGORY = KeyMapping.Category(FlansMod.id("flansmod"))
    private val RELOAD = KeyMappingHelper.registerKeyMapping(
        KeyMapping("key.flansmod.reload", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, CATEGORY)
    )

    private val FIRE_MODE = KeyMappingHelper.registerKeyMapping(
        KeyMapping("key.flansmod.fire_mode", InputConstants.Type.KEYBOARD, InputConstants.KEY_K, CATEGORY)
    )
    private val WEAPON_MENU = KeyMappingHelper.registerKeyMapping(
        KeyMapping("key.flansmod.weapon_menu", InputConstants.Type.KEYBOARD, InputConstants.KEY_U, CATEGORY)
    )
    private val ATTACH = KeyMappingHelper.registerKeyMapping(
        KeyMapping("key.flansmod.attach", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, CATEGORY)
    )

    /** True while aiming down sights. Read by the FOV/sensitivity mixins and the HUD. */
    var aiming = false
        private set

    /** The gun being aimed with: the vehicle seat's gun or the held one (zoom, sensitivity, scope overlay). */
    @JvmStatic
    var aimedGun: GunDefinition? = null
        private set

    /** Drivers keep their hands on the wheel: no hand-held guns, only the driver seat's own gun. */
    private fun isDriver(player: LocalPlayer) = (player.vehicle as? com.flansmod.recoded.entity.DriveableEntity)?.seatOf(player) == 0

    /** 0..1 progress of the ADS transition, smoothed per tick. */
    var aimProgress = 0f
        private set
    private var prevAimProgress = 0f
    fun aimProgress(partialTick: Float) = Mth.lerp(partialTick, prevAimProgress, aimProgress)

    private var cooldown = 0
    private var burstLeft = 0
    private var useWasDown = false
    private var wasAiming = false

    // Recoil: kick still to be applied, and kick already applied that will be recovered.
    private var pendingPitch = 0f
    private var pendingYaw = 0f
    private var recoverPitch = 0f
    private var ticksSinceShot = 0

    fun init() {
        ClientPreAttackCallback.EVENT.register { client, player, clicks -> onAttack(client, player, clicks) }
        ClientTickEvents.END_CLIENT_TICK.register(::tick)
    }

    /** Fires every tick while the attack key is held; returning true cancels mining/attacking. */
    private fun onAttack(client: Minecraft, player: LocalPlayer, clicks: Int): Boolean {
        VehicleClient.seatGun(player)?.let { return onAttackMounted(client, player, it, clicks) }
        val gun = player.mainHandItem.shotDefinition ?: return false
        if (isDriver(player)) return true
        if (client.gui.screen() != null || player.isSpectator) return true
        val mode = player.mainHandItem.fireMode
        val wantsShot = when (mode) {
            FireMode.AUTO -> true
            FireMode.SEMI, FireMode.BURST, FireMode.SAFE -> clicks > 0
        }
        if (wantsShot && cooldown <= 0 && burstLeft <= 0) trigger(player, gun)
        return true
    }

    /** A vehicle seat's gun: fires in its definition's mode; ammo and reloads live on the vehicle (server side). */
    private fun onAttackMounted(client: Minecraft, player: LocalPlayer, gun: GunDefinition, clicks: Int): Boolean {
        if (client.gui.screen() != null || player.isSpectator) return true
        if ((gun.fireMode == FireMode.AUTO || clicks > 0) && cooldown <= 0) {
            ClientPlayNetworking.send(ShootPayload)
            val vehicle = player.vehicle as? com.flansmod.recoded.entity.DriveableEntity
            if (vehicle?.seatMagazines?.get(vehicle.seatOf(player))?.isEmpty == false) {
                burstLeft = 1
                localShot(player, gun)
            } else {
                cooldown = 10
            }
        }
        return true
    }

    private fun trigger(player: LocalPlayer, gun: GunDefinition) {
        ClientPlayNetworking.send(ShootPayload)
        if (player.mainHandItem.ammo <= 0 || player.mainHandItem.fireMode == FireMode.SAFE) {
            cooldown = 10
            return
        }
        burstLeft = if (player.mainHandItem.fireMode == FireMode.BURST) gun.burstCount else 1
        localShot(player, gun)
    }

    /** Predicts a shot locally: recoil plus cooldown. */
    private fun localShot(player: LocalPlayer, gun: GunDefinition) {
        burstLeft--
        cooldown = gun.ticksBetweenShots
        ticksSinceShot = 0
        val strength = FlansConfig.get.recoilMultiplier / 100f * if (aiming) gun.recoil.adsMultiplier else 1f
        pendingPitch += gun.recoil.pitch * strength
        pendingYaw += (player.random.nextFloat() * 2f - 1f) * gun.recoil.yaw * strength
    }

    private fun tick(client: Minecraft) {
        val player = client.player ?: return
        val mounted = VehicleClient.seatGun(player)
        val gun = if (mounted == null && !isDriver(player)) player.mainHandItem.shotDefinition else null
        if (cooldown > 0) cooldown--
        ticksSinceShot++

        // Burst follow-up shots are fired by the server; mirror them for recoil.
        if (gun != null && burstLeft > 0 && cooldown <= 0 && player.mainHandItem.ammo > 0) localShot(player, gun)
        if (gun == null) burstLeft = 0

        updateAim(client, gun, mounted ?: player.mainHandItem.definition?.takeIf { gun != null })
        if (aiming && gun != null) {
            // No hand sway while aiming: the gun stays locked to the view.
            player.xBob = player.xRot; player.xBobO = player.xRot
            player.yBob = player.yRot; player.yBobO = player.yRot
        }
        while (RELOAD.consumeClick()) {
            if (mounted != null) ClientPlayNetworking.send(ReloadPayload(unload = false))
            else if (gun != null) ClientPlayNetworking.send(ReloadPayload(unload = player.isShiftKeyDown))
        }
        while (FIRE_MODE.consumeClick()) if (gun != null) ClientPlayNetworking.send(FireModePayload)
        while (WEAPON_MENU.consumeClick()) {
            // Riding a vehicle: its menu (upgrades, fuel, parts) instead of the gun's.
            if (player.vehicle is com.flansmod.recoded.entity.DriveableEntity) ClientPlayNetworking.send(com.flansmod.recoded.network.OpenVehicleMenuPayload)
            else if (gun != null) ClientPlayNetworking.send(OpenWeaponMenuPayload)
        }
        while (ATTACH.consumeClick()) if (gun != null) ClientPlayNetworking.send(AttachPayload(remove = player.isShiftKeyDown))
        applyRecoil(player, gun ?: mounted)
    }

    private fun updateAim(client: Minecraft, gun: GunDefinition?, aimable: GunDefinition?) {
        val useDown = client.options.keyUse.isDown && client.gui.screen() == null
        aimedGun = aimable
        aiming = when {
            aimable == null -> false
            FlansConfig.get.toggleAim -> if (useDown && !useWasDown) !aiming else aiming
            else -> useDown
        }
        useWasDown = useDown
        if (aiming != wasAiming) {
            wasAiming = aiming
            // The server only cares about hand-held guns (spread, slowdown, night vision).
            ClientPlayNetworking.send(AimPayload(aiming && gun != null))
        }
        prevAimProgress = aimProgress
        aimProgress = Mth.approach(aimProgress, if (aiming) 1f else 0f, 0.25f)
    }

    private fun applyRecoil(player: LocalPlayer, gun: GunDefinition?) {
        // Kick: apply most of the pending recoil this tick so it feels instant but not jarring.
        if (pendingPitch != 0f || pendingYaw != 0f) {
            val pitch = pendingPitch * 0.6f
            val yaw = pendingYaw * 0.6f
            player.xRot = Mth.clamp(player.xRot - pitch, -90f, 90f)
            player.yRot += yaw
            recoverPitch += pitch
            pendingPitch -= pitch
            pendingYaw -= yaw
            if (kotlin.math.abs(pendingPitch) < 0.01f) pendingPitch = 0f
            if (kotlin.math.abs(pendingYaw) < 0.01f) pendingYaw = 0f
        }
        // Recovery: drift back down once the player stops firing.
        if (ticksSinceShot > 2 && recoverPitch > 0f) {
            val back = recoverPitch * (gun?.recoil?.recovery ?: 0.3f)
            player.xRot = Mth.clamp(player.xRot + back, -90f, 90f)
            recoverPitch -= back
            if (recoverPitch < 0.01f) recoverPitch = 0f
        }
    }
}
