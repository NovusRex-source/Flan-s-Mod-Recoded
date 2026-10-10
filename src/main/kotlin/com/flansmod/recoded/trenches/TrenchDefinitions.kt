package com.flansmod.recoded.trenches

import com.flansmod.recoded.gun.DefinitionRegistry
import com.flansmod.recoded.gun.IdentifierSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.resources.Identifier

/** What a trench soldier does besides shooting; [callsign] names them in the field (e.g. "Gunner 7a"). */
@Serializable
enum class TrenchRole(val callsign: String) {
    /** Rifle infantry. */
    @SerialName("rifleman") RIFLEMAN("Rifleman"),
    /** Machine gun: lots of fire at short range; a [TrenchSoldier.loader] of the squad takes the gun over when they fall. */
    @SerialName("machine_gunner") MACHINE_GUNNER("Gunner"),
    /** Long range, slow and precise. */
    @SerialName("sniper") SNIPER("Sniper"),
    /** Lobs shells ([TrenchSoldier.mortar]) at enemy-held trenches in reach. */
    @SerialName("mortar") MORTAR("Mortar"),
    /** Carries out the commander's engineering jobs: bunkers, barbed wire, cutting wire. */
    @SerialName("engineer") ENGINEER("Sapper"),
    /** Close-range troops throwing grenades ([TrenchSoldier.grenades]) into the enemy trench. */
    @SerialName("assault") ASSAULT("Raider"),
    /** Inspires allies around them ([TrenchSoldier.aura]). */
    @SerialName("officer") OFFICER("Officer"),
}

/** One soldier of a [TrenchUnitDefinition]. */
@Serializable
data class TrenchSoldier(
    val role: TrenchRole = TrenchRole.RIFLEMAN,
    /**
     * Gun categories (`rifle`, `sniper`, `dmr`, `smg`, `lmg`, `pistol`, `shotgun`, ...) in order of preference; the gun is
     * picked from the team's faction (any faction for teams without one). Launchers are never used.
     */
    val guns: List<String> = listOf("sniper", "dmr", "rifle"),
    /** true: only guns with a scope overlay, false: only without one, null: either. */
    val scoped: Boolean? = null,
    val health: Double = 20.0,
    /** Walking speed multiplier. */
    val speed: Double = 1.0,
    /** Bullet spread multiplier (lower is more accurate). */
    val spread: Float = 1f,
    /** How far they engage, in blocks (0: the gun's reach, at most 48). */
    val range: Double = 0.0,
    /** Multiplier of the pause between shots. */
    @SerialName("fire_delay") val fireDelay: Float = 1f,
    /** Takes over the squad machine gunner's gun when the gunner falls. */
    val loader: Boolean = false,
    val mortar: Mortar? = null,
    val grenades: Grenades? = null,
    val aura: Aura? = null,
) {
    /** Mortar shells: explosion [power], seconds between rounds, [range] (and no closer than [minRange]), [scatter] in blocks. */
    @Serializable
    data class Mortar(
        val power: Float = 2.5f,
        @SerialName("reload_seconds") val reloadSeconds: Float = 9f,
        val range: Double = 70.0,
        @SerialName("min_range") val minRange: Double = 12.0,
        val scatter: Double = 3.0,
    )

    /** Grenade throwing: the grenade (default: a fragmentation grenade of the faction), throw range, seconds between throws. */
    @Serializable
    data class Grenades(
        @Serializable(IdentifierSerializer::class) val grenade: Identifier? = null,
        val range: Double = 14.0,
        @SerialName("cooldown_seconds") val cooldownSeconds: Float = 10f,
    )

    /** Allies within [radius] shoot faster ([fireDelay]) and straighter ([spread]). */
    @Serializable
    data class Aura(val radius: Double = 10.0, @SerialName("fire_delay") val fireDelay: Float = 0.7f, val spread: Float = 0.7f)
}

/**
 * A unit the commander of a Trenches battle can send in, from `data/<namespace>/flansmod/trench_units/<name>.json`: a
 * squad of [soldiers] for [cost] team funds, available again after [cooldownSeconds]. With [officerChance] an officer
 * (the first soldier of unit [officer]) joins the squad, as in the original game where officers turn up at random.
 */
@Serializable
data class TrenchUnitDefinition(
    val name: String,
    val description: String = "",
    val cost: Int = 100,
    @SerialName("cooldown_seconds") val cooldownSeconds: Float = 5f,
    val soldiers: List<TrenchSoldier> = listOf(TrenchSoldier()),
    @SerialName("officer_chance") val officerChance: Double = 0.0,
    @Serializable(IdentifierSerializer::class) val officer: Identifier? = null,
    /** Position in the command screen. */
    val order: Int = 0,
    /** false: not sold on its own (e.g. the officer that joins other squads). */
    val buyable: Boolean = true,
)

@Serializable
enum class TrenchSupportType {
    /** [TrenchSupportDefinition.shells] high-explosive shells over the target trench. */
    @SerialName("barrage") BARRAGE,
    /** Poison gas clouds lingering in the target trench - they hurt whoever is in them, friend or foe. */
    @SerialName("gas") GAS,
}

/** Off-map support the commander calls on a trench, from `data/<namespace>/flansmod/trench_supports/<name>.json`. */
@Serializable
data class TrenchSupportDefinition(
    val name: String,
    val description: String = "",
    val type: TrenchSupportType = TrenchSupportType.BARRAGE,
    val cost: Int = 400,
    @SerialName("cooldown_seconds") val cooldownSeconds: Float = 45f,
    /** Barrage: number of shells and their explosion power, spread over [durationSeconds]. */
    val shells: Int = 8,
    val power: Float = 2.5f,
    /** Barrage: how long the shells keep falling; gas: how long the clouds linger. */
    @SerialName("duration_seconds") val durationSeconds: Float = 4f,
    /** Gas: radius of each cloud. */
    val radius: Float = 4f,
    val order: Int = 0,
)

object TrenchUnits : DefinitionRegistry<TrenchUnitDefinition>("trench_units", TrenchUnitDefinition.serializer())
object TrenchSupports : DefinitionRegistry<TrenchSupportDefinition>("trench_supports", TrenchSupportDefinition.serializer())
