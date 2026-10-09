package com.flansmod.recoded.client.movement

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.movement.Stance
import com.flansmod.recoded.movement.StancePayload
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec3

/**
 * Client half of the movement stances (the poses themselves are synced by the server, see [Stance]):
 * - **Prone** (Z): toggle lying down; jumping stands you up again.
 * - **Slide**: sneak while sprinting - a short slide in the crawling pose that keeps (and fades) your speed.
 * - **Climb**: jump at a wall up to 2 blocks high with room above it and you pull yourself up over the edge
 *   (vanilla's jump already handles 1 block). Player movement is client-side in vanilla, so this is too.
 */
object MovementClient {
    private val CATEGORY = KeyMapping.Category(FlansMod.id("flansmod"))
    val PRONE: KeyMapping = KeyMappingHelper.registerKeyMapping(KeyMapping("key.flansmod.prone", InputConstants.Type.KEYBOARD, InputConstants.KEY_Z, CATEGORY))

    private var jumpWasDown = false
    private var sneakWasDown = false
    private var slideDirection: Vec3? = null
    private var slideSpeed = 0.0
    /** Climbing: the height the feet must reach, the direction to step over the edge and ticks left. */
    private var climbTop = 0.0
    private var climbDirection = Vec3.ZERO
    private var climbTicks = 0

    val climbing get() = climbTicks > 0

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register(::tick)
    }

    private fun tick(mc: Minecraft) {
        val player = mc.player ?: return
        while (PRONE.consumeClick()) ClientPlayNetworking.send(StancePayload(StancePayload.PRONE))
        val jump = mc.options.keyJump.isDown
        val sneak = mc.options.keyShift.isDown
        val jumpPressed = jump && !jumpWasDown
        val sneakPressed = sneak && !sneakWasDown
        jumpWasDown = jump
        sneakWasDown = sneak
        if (mc.gui.screen() != null) return

        if (Stance.isProne(player)) {
            player.isSprinting = false
            if (jumpPressed) ClientPlayNetworking.send(StancePayload(StancePayload.PRONE))
            return
        }
        if (sneakPressed && player.isSprinting && player.onGround()) {
            ClientPlayNetworking.send(StancePayload(StancePayload.SLIDE))
            slideDirection = Vec3.directionFromRotation(0f, player.yRot)
            slideSpeed = 0.55
        }
        slide(player)
        if (jumpPressed && climbTicks == 0) startClimb(player)
        climb(player)
    }

    private fun slide(player: LocalPlayer) {
        val dir = slideDirection ?: return
        if (slideSpeed < 0.12 || !player.onGround() && slideSpeed < 0.3) {
            slideDirection = null
            return
        }
        player.deltaMovement = Vec3(dir.x * slideSpeed, player.deltaMovement.y, dir.z * slideSpeed)
        slideSpeed *= 0.88
    }

    /** A ledge 2 blocks above the feet straight ahead, with room for the player on it and above their head now. */
    private fun startClimb(player: LocalPlayer) {
        // Client ticks run after the player's movement, so on the tick jump was pressed vanilla's jump has already
        // lifted the player: accept the ground or the start of a jump.
        if (!(player.onGround() || player.deltaMovement.y > 0.2) || player.isPassenger || player.isCrouching || player.isInWater || player.abilities.flying) return
        val level = player.level()
        val facing = player.direction
        fun solid(pos: BlockPos) = !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
        // The block the player stands in (or jumped from): the first free block above the ground below.
        var feet = player.blockPosition()
        repeat(2) { if (!solid(feet.below())) feet = feet.below() }
        val front = feet.relative(facing)
        if (!solid(front.above()) || solid(front.above(2)) || solid(front.above(3))) return
        if (solid(feet.above(2)) || solid(feet.above(3))) return
        val top = front.above().y + level.getBlockState(front.above()).getCollisionShape(level, front.above()).max(net.minecraft.core.Direction.Axis.Y)
        if (top - player.y > 2.05 || top - player.y < 1.0) return
        climbTop = top
        climbDirection = Vec3(facing.stepX.toDouble(), 0.0, facing.stepZ.toDouble())
        climbTicks = 16
        player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.7f, 1.2f)
    }

    private fun climb(player: LocalPlayer) {
        if (climbTicks <= 0) return
        climbTicks--
        player.fallDistance = 0.0
        player.deltaMovement = if (player.y < climbTop + 0.05) Vec3(climbDirection.x * 0.04, 0.36, climbDirection.z * 0.04)
        else Vec3(climbDirection.x * 0.28, 0.0, climbDirection.z * 0.28).also { if (climbTicks > 3) climbTicks = 3 }
    }
}
