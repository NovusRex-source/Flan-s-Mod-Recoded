package com.flansmod.recoded.network

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.AmmoDefinition
import com.flansmod.recoded.gun.AttachmentDefinition
import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.gun.GrenadeDefinition
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.MagazineDefinition
import com.flansmod.recoded.gun.PartDefinition
import com.flansmod.recoded.gun.IdentifierSerializer
import kotlinx.serialization.Serializable
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

/** Client → server: reload key pressed; [unload] (sneaking) takes the magazine out instead. */
data class ReloadPayload(val unload: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<ReloadPayload>("reload")
        val CODEC: StreamCodec<FriendlyByteBuf, ReloadPayload> = ByteBufCodecs.BOOL.map(::ReloadPayload, ReloadPayload::unload).cast()
    }
}

/** Server → client: all content-pack definitions, serialized with kotlinx.serialization. */
@Serializable
data class ContentSyncPayload(
    val guns: Map<@Serializable(IdentifierSerializer::class) Identifier, GunDefinition>,
    val attachments: Map<@Serializable(IdentifierSerializer::class) Identifier, AttachmentDefinition>,
    val ammo: Map<@Serializable(IdentifierSerializer::class) Identifier, AmmoDefinition>,
    val grenades: Map<@Serializable(IdentifierSerializer::class) Identifier, GrenadeDefinition>,
    val magazines: Map<@Serializable(IdentifierSerializer::class) Identifier, MagazineDefinition>,
    val parts: Map<@Serializable(IdentifierSerializer::class) Identifier, PartDefinition>,
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<ContentSyncPayload>("content_sync")
        val CODEC: StreamCodec<FriendlyByteBuf, ContentSyncPayload> = ByteBufCodecs.stringUtf8(Int.MAX_VALUE).map(
            { Content.JSON.decodeFromString(serializer(), it) },
            { Content.JSON.encodeToString(serializer(), it) },
        ).cast()
    }
}

/** Client → server: switch to the next fire mode of the held gun. */
object FireModePayload : CustomPacketPayload {
    val TYPE = type<FireModePayload>("fire_mode")
    val CODEC: StreamCodec<FriendlyByteBuf, FireModePayload> = StreamCodec.unit(this)
    override fun type() = TYPE
}

/** Client → server: open the weapon menu for the held gun. */
object OpenWeaponMenuPayload : CustomPacketPayload {
    val TYPE = type<OpenWeaponMenuPayload>("open_weapon_menu")
    val CODEC: StreamCodec<FriendlyByteBuf, OpenWeaponMenuPayload> = StreamCodec.unit(this)
    override fun type() = TYPE
}

/** Client → server: install the offhand attachment, or ([remove]) take all attachments off. */
data class AttachPayload(val remove: Boolean) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = type<AttachPayload>("attach")
        val CODEC: StreamCodec<FriendlyByteBuf, AttachPayload> = ByteBufCodecs.BOOL.map(::AttachPayload, AttachPayload::remove).cast()
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
        PayloadTypeRegistry.serverboundPlay().register(AttachPayload.TYPE, AttachPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(FireModePayload.TYPE, FireModePayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(OpenWeaponMenuPayload.TYPE, OpenWeaponMenuPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().registerLarge(ContentSyncPayload.TYPE, ContentSyncPayload.CODEC, 8 * 1024 * 1024)
    }
}
