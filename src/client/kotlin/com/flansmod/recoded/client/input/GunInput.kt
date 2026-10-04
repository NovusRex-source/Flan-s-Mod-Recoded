package com.flansmod.recoded.client.input

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.client.config.FlansConfig
import com.flansmod.recoded.gun.FireMode
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.item.ammo
import com.flansmod.recoded.item.definition
import com.flansmod.recoded.network.AimPayload
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

    /** True while aiming down sights. Read by the FOV/sensitivity mixins and the HUD. */
    var aiming = false
        private set

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
        val gun = player.mainHandItem.definition ?: return false
        if (client.gui.screen() != null || player.isSpectator) return true
        val wantsShot = when (gun.fireMode) {
            FireMode.AUTO -> true
            FireMode.SEMI, FireMode.BURST -> clicks > 0
        }
        if (wantsShot && cooldown <= 0 && burstLeft <= 0) trigger(player, gun)
        return true
    }

    private fun trigger(player: LocalPlayer, gun: GunDefinition) {
        ClientPlayNetworking.send(ShootPayload)
        if (player.mainHandItem.ammo <= 0) {
            cooldown = 10
            return
        }
        burstLeft = if (gun.fireMode == FireMode.BURST) gun.burstCount else 1
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
        val gun = player.mainHandItem.definition
        if (cooldown > 0) cooldown--
        ticksSinceShot++

        // Burst follow-up shots are fired by the server; mirror them for recoil.
        if (gun != null && burstLeft > 0 && cooldown <= 0 && player.mainHandItem.ammo > 0) localShot(player, gun)
        if (gun == null) burstLeft = 0

        updateAim(client, gun)
        while (RELOAD.consumeClick()) if (gun != null) ClientPlayNetworking.send(ReloadPayload)
        applyRecoil(player, gun)
    }

    private fun updateAim(client: Minecraft, gun: GunDefinition?) {
        val useDown = client.options.keyUse.isDown && client.gui.screen() == null
        aiming = when {
            gun == null -> false
            FlansConfig.get.toggleAim -> if (useDown && !useWasDown) !aiming else aiming
            else -> useDown
        }
        useWasDown = useDown
        if (aiming != wasAiming) {
            wasAiming = aiming
            ClientPlayNetworking.send(AimPayload(aiming))
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
