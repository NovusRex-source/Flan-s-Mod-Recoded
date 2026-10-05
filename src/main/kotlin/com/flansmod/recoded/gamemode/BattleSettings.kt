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
import net.minecraft.world.item.ItemStack
import net.minecraft.world.scores.TeamColor

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
 * Teams not joined in an alliance (`A+B`) are enemies. [shop] entries are `price count item` with the item in
 * command syntax (`flansmod:gun[flansmod:gun="flansbasic:m4a1"]`); empty = a shop generated from the loaded content.
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
) {
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
            ).apply(i, ::BattlePlayer)
        }

        val ATTACHMENT: AttachmentType<BattlePlayer> = AttachmentRegistry.create(FlansMod.id("battle")) { it.persistent(CODEC).copyOnDeath() }
    }
}
