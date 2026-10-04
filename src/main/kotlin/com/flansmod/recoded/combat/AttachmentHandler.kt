package com.flansmod.recoded.combat

import com.flansmod.recoded.item.AttachmentItem
import com.flansmod.recoded.item.attachmentDefinition
import com.flansmod.recoded.item.attachmentId
import com.flansmod.recoded.item.attachments
import com.flansmod.recoded.item.baseDefinition
import com.flansmod.recoded.item.gunId
import com.flansmod.recoded.network.AttachPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack

/** Installs/removes attachments: gun in the main hand, attachment in the offhand. */
object AttachmentHandler {
    fun init() {
        ServerPlayNetworking.registerGlobalReceiver(AttachPayload.TYPE) { payload, ctx ->
            if (payload.remove) removeAll(ctx.player()) else install(ctx.player())
        }
    }

    /** Installs the offhand attachment. A previously installed one in the same slot goes back to the player. */
    fun install(player: ServerPlayer): Boolean {
        val gun = player.mainHandItem
        val base = gun.baseDefinition ?: return false
        val offhand = player.offhandItem
        val attachment = offhand.attachmentDefinition ?: return false
        val attachmentId = offhand.attachmentId!!
        if (!attachment.fits(gun.gunId!!, base)) {
            player.sendOverlayMessage(Component.translatable("message.flansmod.attachment.incompatible", attachment.name))
            return false
        }

        val previous = gun.attachments[attachment.slot]
        gun.attachments = gun.attachments + (attachment.slot to attachmentId)
        offhand.shrink(1)
        previous?.let { give(player, AttachmentItem.stackFor(it)) }
        player.level().playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.8f, 1.2f)
        return true
    }

    /** Takes every attachment off the held gun and gives them back. */
    fun removeAll(player: ServerPlayer): Boolean {
        val gun = player.mainHandItem
        if (gun.baseDefinition == null || gun.attachments.isEmpty()) return false
        val removed = gun.attachments.values
        gun.attachments = emptyMap()
        removed.forEach { give(player, AttachmentItem.stackFor(it)) }
        player.level().playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.PLAYERS, 0.8f, 1.0f)
        return true
    }

    private fun give(player: ServerPlayer, stack: ItemStack) {
        if (player.offhandItem.isEmpty) player.setItemInHand(InteractionHand.OFF_HAND, stack)
        else player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY) // drops it if the inventory is full
    }
}
