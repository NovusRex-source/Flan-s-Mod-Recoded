package com.flansmod.recoded.gear

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.Gear
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * Parachutes ([GearType.PARACHUTE]): a player falling at least [MIN_FALL] blocks with a parachute in the backpack slot
 * or the inventory opens it with the jump key (or a right click with it). While open, the server keeps the fall
 * distance at zero (no fall damage) and the client caps the sink rate ([GearDefinition.fallSpeed]); everyone sees the
 * canopy. It closes on landing, in water, when mounting something or when the parachute leaves the inventory, and can
 * be used again.
 */
object Parachutes {
    /** The open parachute's gear id; synced to everyone (canopy, the owner's sink rate), not saved. */
    val OPEN: AttachmentType<Identifier> = AttachmentRegistry.create(FlansMod.id("parachute")) {
        it.syncWith(Identifier.STREAM_CODEC, AttachmentSyncPredicate.all())
    }

    /** Blocks fallen before a parachute can open (not on every hop). */
    const val MIN_FALL = 3.0

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { server -> server.playerList.players.forEach(::tick) }
    }

    /** The parachute [player] carries: in the backpack slot, else the first one in the inventory. */
    fun carried(player: Player): ItemStack? = GearSlots.back(player).takeIf { it.gear?.type == GearType.PARACHUTE }
        ?: (0 until player.inventory.containerSize).map(player.inventory::getItem).firstOrNull { it.gear?.type == GearType.PARACHUTE }

    /** The open parachute of [player], if any (both sides). */
    fun openParachute(player: Player): GearDefinition? = player.getAttached(OPEN)?.let { Gear[it] }

    private fun mayOpen(player: Player) = !player.onGround() && !player.isPassenger && !player.abilities.flying && !player.isInWater &&
        !player.isFallFlying && player.fallDistance >= MIN_FALL

    /** Opens [player]'s parachute if they carry one and are falling; false otherwise. */
    fun open(player: ServerPlayer): Boolean {
        if (player.hasAttached(OPEN) || !mayOpen(player)) return false
        val id = carried(player)?.gearId ?: return false
        player.setAttached(OPEN, id)
        player.resetFallDistance()
        player.level().playSound(null, player.x, player.y, player.z, SoundEvents.ARMOR_EQUIP_ELYTRA.value(), SoundSource.PLAYERS, 1f, 0.8f)
        return true
    }

    private fun tick(player: ServerPlayer) {
        if (!player.hasAttached(OPEN)) {
            if (player.lastClientInput.jump()) open(player)
            return
        }
        if (player.onGround() || player.isInWater || player.isPassenger || player.abilities.flying || carried(player) == null) {
            player.removeAttached(OPEN)
            return
        }
        player.resetFallDistance()
    }
}
