package com.flansmod.recoded.gun

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * What is inside a magazine: its type, the loaded ammo type (null when empty) and the round count.
 * Stored on magazine items and, for the inserted magazine, on the gun. Item components need vanilla
 * codecs, so this one uses DFU/StreamCodec rather than kotlinx.serialization.
 */
data class MagazineContents(val magazine: Identifier, val ammo: Identifier?, val rounds: Int) {
    val definition get() = Magazines[magazine]
    val ammoDefinition get() = AmmoTypes[ammo]
    val capacity get() = definition?.capacity ?: 0
    val isEmpty get() = rounds <= 0 || ammo == null
    val isFull get() = rounds >= capacity

    fun withRounds(count: Int) = if (count <= 0) copy(ammo = null, rounds = 0) else copy(rounds = count)

    companion object {
        val CODEC: Codec<MagazineContents> = RecordCodecBuilder.create { i ->
            i.group(
                Identifier.CODEC.fieldOf("magazine").forGetter(MagazineContents::magazine),
                Identifier.CODEC.optionalFieldOf("ammo").forGetter { Optional.ofNullable(it.ammo) },
                Codec.INT.optionalFieldOf("rounds", 0).forGetter(MagazineContents::rounds),
            ).apply(i) { mag, ammo, rounds -> MagazineContents(mag, ammo.orElse(null), rounds) }
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, MagazineContents> = StreamCodec.composite(
            Identifier.STREAM_CODEC, MagazineContents::magazine,
            ByteBufCodecs.optional(Identifier.STREAM_CODEC), { Optional.ofNullable(it.ammo) },
            ByteBufCodecs.VAR_INT, MagazineContents::rounds,
        ) { mag, ammo, rounds -> MagazineContents(mag, ammo.orElse(null), rounds) }

        /** A full magazine of [magazine] with the first matching ammo type (creative / spawned guns). */
        fun full(magazine: Identifier, preferredAmmo: Identifier? = null): MagazineContents? {
            val def = Magazines[magazine] ?: return null
            val ammo = preferredAmmo?.takeIf { AmmoTypes[it]?.let(def::accepts) == true }
                ?: AmmoTypes.all.entries.filter { def.accepts(it.value) }.minByOrNull { it.key.toString() }?.key
                ?: return MagazineContents(magazine, null, 0)
            return MagazineContents(magazine, ammo, def.capacity)
        }
    }
}
