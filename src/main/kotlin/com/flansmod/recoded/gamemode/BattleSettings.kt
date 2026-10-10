package com.flansmod.recoded.gamemode

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.gun.IdentifierSerializer
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.core.GlobalPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.scores.TeamColor
import java.util.Optional

/** A team of a battle: its name, vanilla team colour (`red`, `blue`, `dark_green`, ...) and optional faction (shop filter). */
@Serializable
data class BattleTeam(
    val name: String,
    val color: String = "white",
    @Serializable(IdentifierSerializer::class) val faction: Identifier? = null,
) {
    val teamColor: TeamColor get() = runCatching { TeamColor.byName(color) }.getOrNull() ?: TeamColor.WHITE

    /** Settings-screen form: `Name:color` or `Name:color:namespace:faction`. */
    fun format() = listOfNotNull(name, color, faction?.toString()).joinToString(":")

    companion object {
        fun parse(text: String): BattleTeam? {
            val parts = text.split(":", limit = 3).map(String::trim)
            if (parts[0].isEmpty()) return null
            return BattleTeam(parts[0], parts.getOrElse(1) { "white" }.lowercase(), parts.getOrNull(2)?.let(Identifier::tryParse))
        }
    }
}

/**
 * Everything the Battle Master configures (edited in its Cloth Config screen, stored as JSON on the block).
 * Teams not joined in an alliance (`A+B`) are enemies. Team shops are arranged in the shop editor (stored on the Battle
 * Master); [shop] is the older text form (`price count item` in command syntax), used for teams without an edited shop.
 * Without either, a shop is generated from the loaded content.
 */
@Serializable
data class BattleSettings(
    val name: String = "Battle",
    val teams: List<BattleTeam> = listOf(BattleTeam("Red", "red"), BattleTeam("Blue", "blue")),
    val alliances: List<String> = emptyList(),
    @SerialName("start_money") val startMoney: Int = 600,
    @SerialName("kill_reward") val killReward: Int = 200,
    @SerialName("income_per_minute") val incomePerMinute: Int = 60,
    @SerialName("score_limit") val scoreLimit: Int = 25,
    @SerialName("time_limit_minutes") val timeLimitMinutes: Int = 20,
    @SerialName("friendly_fire") val friendlyFire: Boolean = false,
    @SerialName("keep_loadout_on_death") val keepLoadout: Boolean = false,
    @SerialName("respawn_protection_seconds") val respawnProtection: Int = 5,
    val shop: List<String> = emptyList(),
    val mode: BattleMode = BattleMode.TEAM_DEATHMATCH,
    /** Game mode of fighters while in the battle: `survival` or `adventure` (no block breaking/placing). */
    @SerialName("game_mode") val gameMode: String = "survival",
    /** Most players per team (0 = no limit); also how many fighters [fillWithBots] tops each team up to. */
    @SerialName("team_size") val teamSize: Int = 0,
    @SerialName("fill_with_bots") val fillWithBots: Boolean = false,
    /**
     * Fallen bots come back after [botRespawnSeconds] with a new random loadout of their team's faction: at the team's
     * flag post or spawn point, else at the Battle Master. Off: only the first wave fills the teams.
     */
    @SerialName("bot_respawn") val botRespawn: Boolean = true,
    @SerialName("bot_respawn_seconds") val botRespawnSeconds: Int = 5,
    /** How long a team must hold a flag post alone to take it over (conquest, king of the hill). */
    @SerialName("capture_seconds") val captureSeconds: Int = 10,
    /** Conquest / king of the hill: a point per held flag every this many seconds. */
    @SerialName("point_seconds") val pointSeconds: Int = 5,
    @SerialName("countdown_seconds") val countdownSeconds: Int = 10,
    /** Off: explosions, fire and fighters leave the blocks of the battle area alone while the battle runs. */
    @SerialName("block_damage") val blockDamage: Boolean = true,
    /** Build a wall along the border markers that fighters cannot pass (others walk through). */
    @SerialName("border_wall") val borderWall: Boolean = false,
    /** Trenches mode: funds, units, computer commander, the field to build. */
    val trenches: com.flansmod.recoded.trenches.TrenchSettings = com.flansmod.recoded.trenches.TrenchSettings(),
) {
    val gameType: GameType get() = if (gameMode.equals("adventure", true)) GameType.ADVENTURE else GameType.SURVIVAL

    fun team(name: String?) = teams.firstOrNull { it.name == name }

    /** Same team or allied: no score, and no damage unless [friendlyFire]. */
    fun friendly(a: String, b: String) = a == b || alliances.any { pair ->
        pair.split("+").map(String::trim).let { a in it && b in it }
    }

    fun toJson() = Content.JSON.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): BattleSettings = runCatching { Content.JSON.decodeFromString(serializer(), json) }.getOrElse { BattleSettings() }
    }
}

/**
 * How a battle is won (every mode ends at the score limit or, with the best score, when time runs out):
 * - [TEAM_DEATHMATCH]: a point per enemy killed.
 * - [CAPTURE_THE_FLAG]: steal an enemy flag at its post (walk up to it) and carry it to your own post for a point.
 *   The post stays a respawn point while its flag is away; a carrier who dies drops it back home.
 * - [KING_OF_THE_HILL]: hill flags (posts marked as objectives) are taken by standing at them alone for the capture
 *   time; every held hill scores over time.
 * - [CONQUEST]: like king of the hill, but team bases can be conquered too (unless locked) - a team without a flag
 *   post cannot respawn, and a team with nobody left standing is out.
 * - [TRENCHES]: after the mobile game *Trenches*: the first two teams each have a headquarters (their flag post) at
 *   either end of a lane of trench lines (hill posts). Commanders - players, from above or while fighting, or the
 *   computer - spend team funds earned over time on squads and send them from trench to trench; taking the enemy
 *   headquarters wins (when time runs out: the most trenches held). See [com.flansmod.recoded.trenches.TrenchRules].
 */
@Serializable
enum class BattleMode {
    @SerialName("team_deathmatch") TEAM_DEATHMATCH,
    @SerialName("capture_the_flag") CAPTURE_THE_FLAG,
    @SerialName("king_of_the_hill") KING_OF_THE_HILL,
    @SerialName("conquest") CONQUEST,
    @SerialName("trenches") TRENCHES;

    val killsScore get() = this == TEAM_DEATHMATCH || this == CONQUEST
}

/**
 * A player's part in a battle (Fabric data attachment, saved, kept through death): the Battle Master's position,
 * their team, and - while inside a running battle ([inBattle], [session]) - their battle money, the inventory left
 * behind on entering ([stash], given back on leaving) and, with "keep loadout", what they carried when they died.
 */
data class BattlePlayer(
    val master: GlobalPos,
    val team: String,
    val session: String = "",
    val inBattle: Boolean = false,
    val money: Int = 0,
    val stash: List<ItemStack> = emptyList(),
    val loadout: List<ItemStack> = emptyList(),
    /** Game mode and respawn point before entering (given back on leaving). */
    val previousMode: String = "",
    val previousRespawn: Optional<ServerPlayer.RespawnConfig> = Optional.empty(),
    /** Dead while the team had no flag post: watching as a spectator until the team has one again. */
    val waiting: Boolean = false,
) {
    companion object {
        val CODEC: Codec<BattlePlayer> = RecordCodecBuilder.create { i ->
            i.group(
                GlobalPos.CODEC.fieldOf("master").forGetter(BattlePlayer::master),
                Codec.STRING.fieldOf("team").forGetter(BattlePlayer::team),
                Codec.STRING.optionalFieldOf("session", "").forGetter(BattlePlayer::session),
                Codec.BOOL.optionalFieldOf("in_battle", false).forGetter(BattlePlayer::inBattle),
                Codec.INT.optionalFieldOf("money", 0).forGetter(BattlePlayer::money),
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("stash", emptyList()).forGetter(BattlePlayer::stash),
                ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("loadout", emptyList()).forGetter(BattlePlayer::loadout),
                Codec.STRING.optionalFieldOf("previous_mode", "").forGetter(BattlePlayer::previousMode),
                ServerPlayer.RespawnConfig.CODEC.optionalFieldOf("previous_respawn").forGetter(BattlePlayer::previousRespawn),
                Codec.BOOL.optionalFieldOf("waiting", false).forGetter(BattlePlayer::waiting),
            ).apply(i, ::BattlePlayer)
        }

        val ATTACHMENT: AttachmentType<BattlePlayer> = AttachmentRegistry.create(FlansMod.id("battle")) { it.persistent(CODEC).copyOnDeath() }
    }
}

/** Someone watching a battle from the Battle Master as a spectator: where they were and how they played before. */
data class BattleSpectator(val master: GlobalPos, val returnTo: GlobalPos, val previousMode: String) {
    companion object {
        val CODEC: Codec<BattleSpectator> = RecordCodecBuilder.create { i ->
            i.group(
                GlobalPos.CODEC.fieldOf("master").forGetter(BattleSpectator::master),
                GlobalPos.CODEC.fieldOf("return_to").forGetter(BattleSpectator::returnTo),
                Codec.STRING.fieldOf("previous_mode").forGetter(BattleSpectator::previousMode),
            ).apply(i, ::BattleSpectator)
        }

        val ATTACHMENT: AttachmentType<BattleSpectator> = AttachmentRegistry.create(FlansMod.id("battle_spectator")) { it.persistent(CODEC).copyOnDeath() }
    }
}
