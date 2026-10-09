package com.flansmod.recoded.movement

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.AttachmentDefinition
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.item.attachments
import com.mojang.serialization.Codec
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * Movement stances: **prone** (toggle, Z) and the **sprint slide** (sneak while sprinting) both put the player into
 * vanilla's crawling pose (0.6 blocks high: smaller target, slow movement), forced by `PlayerPoseMixin`. Both are
 * synced data attachments, so every client poses the player the same way. Prone and crouching steady the aim, and
 * set down a bipod or tripod ([AttachmentDefinition.deploy]). Climbing 2-block walls is client movement (`Mantle`).
 */
object Stance {
    val PRONE: AttachmentType<Boolean> = AttachmentRegistry.create(FlansMod.id("prone")) {
        it.syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.all())
    }

    /** Game time until which the player slides. */
    val SLIDE: AttachmentType<Long> = AttachmentRegistry.create(FlansMod.id("slide")) {
        it.syncWith(ByteBufCodecs.VAR_LONG, AttachmentSyncPredicate.all())
    }

    const val SLIDE_TICKS = 14

    fun isProne(player: Player) = player.getAttachedOrElse(PRONE, false)
    fun isSliding(player: Player) = player.getAttachedOrElse(SLIDE, 0L) > player.level().gameTime

    /** Forced crawling pose (read by the pose mixin on both sides). */
    @JvmStatic
    fun lowPose(player: Player) = !player.isSpectator && !player.isPassenger && (isProne(player) || isSliding(player))

    /** The deploy settings of [stack]'s bipod/tripod if the gun is set down right now. */
    fun deployed(player: Player, stack: ItemStack): AttachmentDefinition.Deploy? {
        val deploy = stack.attachments.values.firstNotNullOfOrNull { Attachments[it]?.deploy } ?: return null
        return deploy.takeIf { isProne(player) || (!it.proneOnly && player.isCrouching) }
    }

    private fun laser(stack: ItemStack) = stack.attachments.values.firstNotNullOfOrNull { Attachments[it]?.laser }

    /** Spread factor from stance, a set-down bipod/tripod and (hip fire) a laser. */
    fun spreadMultiplier(player: Player, stack: ItemStack, aiming: Boolean): Float {
        var m = when {
            isProne(player) -> 0.6f
            player.isCrouching -> 0.85f
            else -> 1f
        }
        deployed(player, stack)?.let { m *= it.spreadMultiplier }
        if (!aiming) laser(stack)?.let { m *= it.hipSpreadMultiplier }
        return m
    }

    fun recoilMultiplier(player: Player, stack: ItemStack): Float {
        var m = when {
            isProne(player) -> 0.7f
            player.isCrouching -> 0.9f
            else -> 1f
        }
        deployed(player, stack)?.let { m *= it.recoilMultiplier }
        return m
    }

    // ------------------------------------------------------------------------------------------- server

    private fun canGoDown(player: ServerPlayer) = player.onGround() && !player.isPassenger && !player.isInWater && !player.isFallFlying && !player.isSpectator

    fun toggleProne(player: ServerPlayer) {
        if (isProne(player)) {
            // Standing up needs room for a standing player.
            if (player.level().noCollision(player, player.getDimensions(Pose.STANDING).makeBoundingBox(player.position()).deflate(1.0E-7))) {
                player.removeAttached(PRONE)
            }
        } else if (canGoDown(player)) {
            player.setAttached(PRONE, true)
            player.isSprinting = false
            player.level().playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), player.soundSource, 0.6f, 0.8f)
        }
    }

    fun slide(player: ServerPlayer) {
        if (!player.isSprinting || !canGoDown(player) || isProne(player)) return
        player.setAttached(SLIDE, player.level().gameTime + SLIDE_TICKS)
        player.level().playSound(null, player.blockPosition(), SoundEvents.GRAVEL_STEP, player.soundSource, 0.8f, 0.7f)
    }

    fun init() {
        PayloadTypeRegistry.serverboundPlay().register(StancePayload.TYPE, StancePayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(StancePayload.TYPE) { payload, ctx ->
            when (payload.action) {
                StancePayload.PRONE -> toggleProne(ctx.player())
                StancePayload.SLIDE -> slide(ctx.player())
            }
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            for (player in server.playerList.players) {
                if (!isProne(player)) continue
                if (player.isPassenger || player.isInWater || player.isFallFlying || player.isSpectator) player.removeAttached(PRONE)
                else if (player.isSprinting) player.isSprinting = false
            }
        }
    }
}

/** Client → server: [PRONE] toggles lying down, [SLIDE] starts a sprint slide. */
data class StancePayload(val action: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val PRONE = 0
        const val SLIDE = 1
        val TYPE = CustomPacketPayload.Type<StancePayload>(FlansMod.id("stance"))
        val CODEC: StreamCodec<FriendlyByteBuf, StancePayload> = ByteBufCodecs.VAR_INT.map(::StancePayload, StancePayload::action).cast()
    }
}
