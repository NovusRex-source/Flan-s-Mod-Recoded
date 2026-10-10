package com.flansmod.recoded.trenches

import com.flansmod.recoded.FlansMod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** How hard the computer commander plays a side that has no human commander online. */
@Serializable
enum class TrenchAiLevel { @SerialName("off") OFF, @SerialName("easy") EASY, @SerialName("normal") NORMAL, @SerialName("hard") HARD }

/** Trenches settings of a battle ([com.flansmod.recoded.gamemode.BattleSettings.trenches]). */
@Serializable
data class TrenchSettings(
    /** Team funds at the start and earned every second (plus [trenchBonus] per trench held). */
    @SerialName("start_funds") val startFunds: Int = 400,
    @SerialName("funds_per_second") val fundsPerSecond: Int = 4,
    @SerialName("trench_bonus") val trenchBonus: Int = 1,
    /** Funds for every enemy soldier killed. */
    @SerialName("kill_bounty") val killBounty: Int = 15,
    /** Most soldiers a side may have in the field. */
    @SerialName("unit_cap") val unitCap: Int = 40,
    /** How long attackers must hold the enemy headquarters alone to win. */
    @SerialName("hq_capture_seconds") val hqCaptureSeconds: Int = 20,
    val ai: TrenchAiLevel = TrenchAiLevel.NORMAL,
    /** Engineering jobs: cost and seconds of work. */
    @SerialName("bunker_cost") val bunkerCost: Int = 300,
    @SerialName("bunker_seconds") val bunkerSeconds: Int = 10,
    @SerialName("wire_cost") val wireCost: Int = 120,
    @SerialName("wire_seconds") val wireSeconds: Int = 6,
    @SerialName("cut_cost") val cutCost: Int = 50,
    @SerialName("cut_seconds") val cutSeconds: Int = 4,
    /** The field the Battle Master builds: number of trench lines, blocks between them, trench width. */
    val trenches: Int = 5,
    val spacing: Int = 14,
    val width: Int = 15,
)

@Serializable
enum class TrenchJobKind { @SerialName("bunker") BUNKER, @SerialName("wire") WIRE, @SerialName("cut") CUT }

/** An engineering job ordered for [zone]; [engineer] (entity UUID) is on the way or working ([progress] ticks). */
@Serializable
data class TrenchJob(val team: String, val kind: TrenchJobKind, val zone: Int, val engineer: String? = null, val progress: Int = 0)

/** A shell on its way (barrage or mortar): lands at [x]/[y]/[z] at game time [at]. */
@Serializable
data class TrenchShell(val x: Double, val y: Double, val z: Double, val at: Long, val power: Float, val shooter: String? = null)

/** Saved Trenches state of a running battle ([com.flansmod.recoded.gamemode.BattleState.trench]). */
@Serializable
data class TrenchState(
    /** Team funds. */
    val funds: Map<String, Int> = emptyMap(),
    /** Team → UUID of its human commander (no entry or offline: the computer commands, unless off). */
    val commanders: Map<String, String> = emptyMap(),
    /** `team|unit or support id` → game time when it can be used again. */
    val ready: Map<String, Long> = emptyMap(),
    /** Trench post key → team that built a bunker there (cover, forward spawn point; the holder of the trench uses it). */
    val bunkers: Set<String> = emptySet(),
    /** Barbed wire and bunker sandbags placed during the battle (block positions), taken away when it ends. */
    val wire: List<Long> = emptyList(),
    val sandbags: List<Long> = emptyList(),
    /** Team → ticks of enemy capture progress at its headquarters. */
    val hq: Map<String, Int> = emptyMap(),
    val jobs: List<TrenchJob> = emptyList(),
    val shells: List<TrenchShell> = emptyList(),
    /** Squads sent so far (spawn order: soldiers keep their places in a trench). */
    val squads: Int = 0,
    /** Chunks of the field kept loaded while the battle runs ([net.minecraft.world.level.ChunkPos.pack]). */
    val chunks: List<Long> = emptyList(),
)

/** What the command screen and HUD show; [side] 0 = the first team (its HQ is zone 0), 1 = the second, -1 = neither. */
@Serializable
data class TrenchView(
    val side: Int,
    val teams: List<String>,
    val funds: List<Int>,
    val income: List<Int>,
    /** Commander name per side; null = computer ([ai]) or nobody. */
    val commanders: List<String?>,
    val ai: List<Boolean>,
    val canCommand: Boolean,
    val zones: List<Zone>,
    val units: List<UnitView>,
    val supports: List<SupportView>,
    val jobs: List<JobView>,
    val soldiers: List<Int>,
    val unitCap: Int,
    /** Bunker, wire, cut. */
    val jobCosts: List<Int>,
    /** Capture progress (0..1) at the first and the second team's headquarters. */
    val hq: List<Float>,
) {
    /**
     * A headquarters or trench line in lane order: owner side (-1 neutral), whether a bunker stands there, wire in
     * front of it towards the second team, soldiers per side ordered there ([units], of those still walking: [moving]),
     * the side taking it over and how far.
     */
    @Serializable
    data class Zone(val label: String, val base: Boolean, val owner: Int, val bunker: Boolean, val wireAhead: Boolean,
                    val units: List<Int>, val moving: List<Int>, val capturing: Int = -1, val progress: Float = 0f)

    @Serializable
    data class UnitView(val id: String, val name: String, val description: String, val cost: Int, val ready: Int, val icon: String?, val size: Int)

    @Serializable
    data class SupportView(val id: String, val name: String, val description: String, val cost: Int, val ready: Int)

    @Serializable
    data class JobView(val kind: TrenchJobKind, val zone: Int, val side: Int, val progress: Float, val working: Boolean)
}

/**
 * Client → server: a commander's order. [BUY] unit [id]; [ORDER] soldiers in [zone] to move [count] (> 0 forward,
 * < 0 back, ±[ALL] for all of them); [ORDER_ALL] everyone one zone forward ([count] > 0) or back; [SUPPORT] [id] on
 * [zone]; [JOB] ([id] = bunker, wire, cut) at [zone]; [TAKE_COMMAND], [RELEASE_COMMAND]; [VIEW] watch from above.
 */
data class TrenchCommandPayload(val action: Int, val id: String = "", val zone: Int = 0, val count: Int = 0) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val BUY = 0
        const val ORDER = 1
        const val ORDER_ALL = 2
        const val SUPPORT = 3
        const val JOB = 4
        const val TAKE_COMMAND = 5
        const val RELEASE_COMMAND = 6
        const val VIEW = 7
        const val ALL = 999

        val TYPE = CustomPacketPayload.Type<TrenchCommandPayload>(FlansMod.id("trench_command"))
        val CODEC: StreamCodec<FriendlyByteBuf, TrenchCommandPayload> = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TrenchCommandPayload::action, ByteBufCodecs.stringUtf8(128), TrenchCommandPayload::id,
            ByteBufCodecs.VAR_INT, TrenchCommandPayload::zone, ByteBufCodecs.VAR_INT, TrenchCommandPayload::count, ::TrenchCommandPayload,
        ).cast()
    }
}
