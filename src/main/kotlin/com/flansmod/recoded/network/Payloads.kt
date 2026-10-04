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
import net.minecraft.world.phys.Vec3

private fun <T : CustomPacketPayload> type(name: String) = CustomPacketPayload.Type<T>(FlansMod.id(name))

/** Client → server: trigger pulled (once per tick while held for automatic guns). */
object ShootPayload : CustomPacketPayload {
    val TYPE = type<ShootPayload>("shoot")
    val CODEC: StreamCodec<FriendlyByteBuf, ShootPayload> = StreamCodec.unit(this)
    override fun type() = TYPE
}

/** Client → server: started or stopped aiming down sights. */
data class AimPayload(val aiming: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<AimPayload>("aim")
        val CODEC: StreamCodec<FriendlyByteBuf, AimPayload> = ByteBufCodecs.BOOL.map(::AimPayload, AimPayload::aiming).cast()
    }
}

/** Server → client: one of your bullets hit a living target. */
data class HitPayload(val headshot: Boolean, val kill: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<HitPayload>("hit")
        val CODEC: StreamCodec<FriendlyByteBuf, HitPayload> = StreamCodec.composite(
            ByteBufCodecs.BOOL, HitPayload::headshot,
            ByteBufCodecs.BOOL, HitPayload::kill,
            ::HitPayload,
        ).cast()
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

/** Server → clients near the shooter: a shot was fired, so clients can draw muzzle flash and tracers. */
data class ShotPayload(val shooter: Int, val gun: Identifier, val origin: Vec3, val directions: List<Vec3>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<ShotPayload>("shot")
        val CODEC: StreamCodec<FriendlyByteBuf, ShotPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ShotPayload::shooter,
            Identifier.STREAM_CODEC, ShotPayload::gun,
            Vec3.STREAM_CODEC, ShotPayload::origin,
            ByteBufCodecs.collection(::ArrayList, Vec3.STREAM_CODEC, 64), { ArrayList(it.directions) },
            ::ShotPayload,
        ).cast()
    }
}

object FlansNetworking {
    fun init() {
        PayloadTypeRegistry.serverboundPlay().register(ShootPayload.TYPE, ShootPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(ReloadPayload.TYPE, ReloadPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(AimPayload.TYPE, AimPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(HitPayload.TYPE, HitPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ShotPayload.TYPE, ShotPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().registerLarge(GunSyncPayload.TYPE, GunSyncPayload.CODEC, 8 * 1024 * 1024)
    }
}
