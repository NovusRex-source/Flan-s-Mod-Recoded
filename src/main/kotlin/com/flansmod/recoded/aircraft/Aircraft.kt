package com.flansmod.recoded.aircraft

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.entity.DriveableEntity
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Client → server: the aircraft this player flies hit something at [impact] blocks per tick (see [FlightModel.afterMove]). */
data class CrashPayload(val impact: Float) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<CrashPayload>(FlansMod.id("crash"))
        val CODEC: StreamCodec<FriendlyByteBuf, CrashPayload> = ByteBufCodecs.FLOAT.map(::CrashPayload, CrashPayload::impact).cast()
    }
}

/** Planes and helicopters: [FlightModel] does the flying; this registers what the server needs from the pilot's client. */
object Aircraft {
    fun init() {
        PayloadTypeRegistry.serverboundPlay().register(CrashPayload.TYPE, CrashPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(CrashPayload.TYPE) { payload, ctx ->
            val player = ctx.player()
            val vehicle = player.vehicle as? DriveableEntity ?: return@registerGlobalReceiver
            val def = vehicle.definition ?: return@registerGlobalReceiver
            // Only the pilot simulates the flight; an impact can't be faster than the aircraft can go.
            if (vehicle.controllingPassenger != player || !def.type.flies) return@registerGlobalReceiver
            FlightModel.crash(player.level(), vehicle, payload.impact.coerceIn(0f, (def.maxSpeed * 1.5 + 0.5).toFloat()))
        }
    }
}
