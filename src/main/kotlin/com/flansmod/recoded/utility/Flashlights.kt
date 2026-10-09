package com.flansmod.recoded.utility

import com.flansmod.recoded.gear.GearDefinition
import com.flansmod.recoded.gear.GearType
import com.flansmod.recoded.gear.gear
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LightBlock
import net.minecraft.world.phys.HitResult
import java.util.UUID

/**
 * Flashlight beams with vanilla's invisible light block: while a player holds a switched-on flashlight (either hand),
 * the server keeps one light block (level [GearDefinition.lightLevel]) in the air just before what the beam hits,
 * within [GearDefinition.range]; it moves with the beam and is removed when the light goes off, the player leaves or
 * the server stops. Everyone sees the lit spot (real block light, no client rendering). Only air is ever replaced, and
 * only light blocks are ever removed.
 */
object Flashlights {
    private val placed = HashMap<UUID, Pair<ResourceKey<Level>, BlockPos>>()

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { server -> server.playerList.players.forEach(::tick) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server -> clear(server, handler.player.uuid) }
        ServerLifecycleEvents.SERVER_STOPPING.register { server -> placed.keys.toList().forEach { clear(server, it) } }
    }

    /** The switched-on flashlight [player] holds, if any. */
    fun held(player: Player): GearDefinition? = listOf(player.mainHandItem, player.offhandItem)
        .firstNotNullOfOrNull { stack -> stack.gear?.takeIf { it.type == GearType.FLASHLIGHT && stack.isSwitchedOn } }

    /** Where the beam's light goes: one step back from what the beam hits, or the end of its reach. */
    fun beamTarget(player: Player, def: GearDefinition): BlockPos {
        val eye = player.eyePosition
        val look = player.lookAngle
        val hit = player.level().clip(ClipContext(eye, eye.add(look.scale(def.range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        val point = if (hit.type == HitResult.Type.MISS) hit.location else hit.location.subtract(look.scale(0.5))
        return BlockPos.containing(point)
    }

    /** The light block of [player]'s beam, if one is placed (tests). */
    fun lightOf(player: Player): BlockPos? = placed[player.uuid]?.second

    private fun tick(player: ServerPlayer) {
        val def = held(player)?.takeIf { it.lightLevel > 0 }
        val level = player.level()
        val target = def?.let { beamTarget(player, it) }
        val old = placed[player.uuid]
        if (old != null && old.first == level.dimension() && old.second == target) return
        clear(level.server, player.uuid)
        if (target == null || !level.getBlockState(target).isAir) return
        level.setBlock(target, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, def.lightLevel.coerceIn(1, 15)), Block.UPDATE_CLIENTS)
        placed[player.uuid] = level.dimension() to target
    }

    private fun clear(server: MinecraftServer, player: UUID) {
        val (dimension, pos) = placed.remove(player) ?: return
        val level: ServerLevel = server.getLevel(dimension) ?: return
        if (level.isLoaded(pos) && level.getBlockState(pos).`is`(Blocks.LIGHT)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)
    }
}
