package com.flansmod.recoded.gamemode

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import java.util.Optional

/**
 * How a soldier spawned outside a battle ([SoldierEntity] without a Battle Master) behaves:
 * - [FRIENDLY]: on the players' side - fights monsters and [ENEMY] soldiers, never hurts players.
 * - [ENEMY]: attacks players (not in creative or spectator mode), [FRIENDLY] soldiers and enemy soldiers of other
 *   factions; enemy soldiers of the same faction are comrades.
 * - [NEUTRAL]: minds its own business, but shoots back at whoever attacks it.
 * - [INACTIVE]: stands still and does nothing (vanilla NoAI) - a dummy for target practice or decoration.
 */
enum class SoldierAttitude(val rgb: Int) {
    FRIENDLY(0x55FF55), ENEMY(0xFF5555), NEUTRAL(0xFFE055), INACTIVE(0xAAAAAA);

    val key get() = name.lowercase()
    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun byKey(key: String?) = entries.firstOrNull { it.key == key } ?: NEUTRAL
        val CODEC: Codec<SoldierAttitude> = Codec.STRING.xmap(::byKey, SoldierAttitude::key)
        val STREAM_CODEC: StreamCodec<ByteBuf, SoldierAttitude> = ByteBufCodecs.STRING_UTF8.map(::byKey, SoldierAttitude::key)
    }
}

/** What a soldier spawn item ([com.flansmod.recoded.item.SoldierItem]) places: a soldier of [faction] (null: any) with [attitude]. */
data class SoldierSpawn(val faction: Identifier?, val attitude: SoldierAttitude) {
    companion object {
        val CODEC: Codec<SoldierSpawn> = RecordCodecBuilder.create { i ->
            i.group(
                Identifier.CODEC.optionalFieldOf("faction").forGetter { Optional.ofNullable(it.faction) },
                SoldierAttitude.CODEC.optionalFieldOf("attitude", SoldierAttitude.NEUTRAL).forGetter(SoldierSpawn::attitude),
            ).apply(i) { faction, attitude -> SoldierSpawn(faction.orElse(null), attitude) }
        }
        val STREAM_CODEC: StreamCodec<ByteBuf, SoldierSpawn> = StreamCodec.composite(
            ByteBufCodecs.optional(Identifier.STREAM_CODEC), { Optional.ofNullable(it.faction) },
            SoldierAttitude.STREAM_CODEC, SoldierSpawn::attitude,
        ) { faction, attitude -> SoldierSpawn(faction.orElse(null), attitude) }
    }
}

/** The rules between soldiers spawned outside battles, players and monsters ([SoldierAttitude]). */
object FactionSoldiers {
    /** A soldier that belongs to no battle. */
    fun standalone(entity: Entity?) = entity is SoldierEntity && entity.masterPos == null

    /** Whether standalone [soldier] picks a fight with [target]. */
    fun hostile(soldier: SoldierEntity, target: LivingEntity): Boolean {
        if (target == soldier || !target.isAlive) return false
        val other = target as? SoldierEntity
        return when (soldier.attitude) {
            SoldierAttitude.NEUTRAL, SoldierAttitude.INACTIVE -> false
            SoldierAttitude.FRIENDLY -> (target is Enemy && target !is SoldierEntity) || (standalone(other) && other!!.attitude == SoldierAttitude.ENEMY)
            SoldierAttitude.ENEMY -> (target is Player && !target.isCreative && !target.isSpectator) ||
                (standalone(other) && (other!!.attitude == SoldierAttitude.FRIENDLY || (other.attitude == SoldierAttitude.ENEMY && other.faction != soldier.faction)))
        }
    }

    /** Whether [soldier] shoots back at [attacker]: never as a dummy, and friendly soldiers forgive players and their allies. */
    fun retaliates(soldier: SoldierEntity, attacker: LivingEntity?): Boolean = when {
        attacker == null || soldier.masterPos != null -> true
        soldier.attitude == SoldierAttitude.INACTIVE -> false
        soldier.attitude == SoldierAttitude.FRIENDLY -> attacker !is Player && !(standalone(attacker) && (attacker as SoldierEntity).attitude == SoldierAttitude.FRIENDLY)
        soldier.attitude == SoldierAttitude.ENEMY -> !(standalone(attacker) && (attacker as SoldierEntity).attitude == SoldierAttitude.ENEMY &&
            attacker.faction == soldier.faction)
        else -> true
    }

    fun init() {
        // No friendly fire between allies: friendly soldiers spare players and each other, enemy comrades each other.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { victim, source, _ ->
            val attacker = Battles.attacker(source) as? SoldierEntity ?: return@register true
            if (attacker.masterPos != null) return@register true
            when {
                victim is Player -> attacker.attitude != SoldierAttitude.FRIENDLY
                standalone(victim) -> !(attacker.attitude == (victim as SoldierEntity).attitude &&
                    (attacker.attitude == SoldierAttitude.FRIENDLY || attacker.faction == victim.faction))
                else -> true
            }
        }
    }
}
