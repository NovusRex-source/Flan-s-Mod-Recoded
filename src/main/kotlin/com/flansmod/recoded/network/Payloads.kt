package com.flansmod.recoded.network

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

private fun <T : CustomPacketPayload> type(name: String) = CustomPacketPayload.Type<T>(FlansMod.id(name))

/** Client → server: trigger pulled (once per tick while held for automatic guns). */
data class ShootPayload(val aiming: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<ShootPayload>("shoot")
        val CODEC: StreamCodec<FriendlyByteBuf, ShootPayload> =
            ByteBufCodecs.BOOL.map(::ShootPayload, ShootPayload::aiming).cast()
    }
}

/** Client → server: reload key pressed. */
object ReloadPayload : CustomPacketPayload {
    val TYPE = type<ReloadPayload>("reload")
    val CODEC: StreamCodec<FriendlyByteBuf, ReloadPayload> = StreamCodec.unit(this)
    override fun type() = TYPE
}

/** Server → client: the full set of gun definitions, serialized with kotlinx.serialization. */
data class GunSyncPayload(val guns: Map<Identifier, GunDefinition>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<GunSyncPayload>("gun_sync")
        private val SERIALIZER = MapSerializer(String.serializer(), GunDefinition.serializer())

        val CODEC: StreamCodec<FriendlyByteBuf, GunSyncPayload> = ByteBufCodecs.stringUtf8(Int.MAX_VALUE).map(
            { json -> GunSyncPayload(Guns.JSON.decodeFromString(SERIALIZER, json).mapKeys { Identifier.parse(it.key) }) },
            { payload -> Guns.JSON.encodeToString(SERIALIZER, payload.guns.mapKeys { it.key.toString() }) },
        ).cast()
    }
}

object FlansNetworking {
    fun init() {
        PayloadTypeRegistry.serverboundPlay().register(ShootPayload.TYPE, ShootPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(ReloadPayload.TYPE, ReloadPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().registerLarge(GunSyncPayload.TYPE, GunSyncPayload.CODEC, 8 * 1024 * 1024)
    }
}
