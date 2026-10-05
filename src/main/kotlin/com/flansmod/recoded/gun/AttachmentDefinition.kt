package com.flansmod.recoded.gun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.resources.Identifier

/**
 * An attachment from `data/<namespace>/flansmod/attachments/<name>.json`. It fits any gun that lists its
 * [slot] in `attachment_slots`, optionally restricted to [guns]. Multipliers default to 1 (no change).
 *
 * Visuals: the gun's GeckoLib model may contain a bone `attachment_<name>` (shown only while this
 * attachment is installed) and `default_<slot>` (hidden while anything occupies that slot, e.g. iron sights).
 */
@Serializable
data class AttachmentDefinition(
    val name: String,
    val slot: String,
    val guns: List<@Serializable(IdentifierSerializer::class) Identifier> = emptyList(),
    @SerialName("damage_multiplier") val damageMultiplier: Float = 1f,
    @SerialName("spread_multiplier") val spreadMultiplier: Float = 1f,
    @SerialName("recoil_multiplier") val recoilMultiplier: Float = 1f,
    @SerialName("velocity_multiplier") val velocityMultiplier: Double = 1.0,
    @SerialName("reload_multiplier") val reloadMultiplier: Float = 1f,
    @SerialName("ads_move_speed_multiplier") val adsMoveSpeedMultiplier: Float = 1f,
    /** Replaces the gun's `ads_zoom` (scopes). */
    @SerialName("ads_zoom") val adsZoom: Float? = null,
    /** Replaces the gun's shoot sound (suppressors). */
    @Serializable(IdentifierSerializer::class) @SerialName("shoot_sound") val shootSound: Identifier? = null,
    @SerialName("hide_tracer") val hideTracer: Boolean = false,
    /** Optic effects while aiming through this sight (overlay, night vision, thermal). */
    val scope: Scope? = null,
    /**
     * Height of this sight's line of sight (dot or lens centre) above the gun's rail, in model pixels. Guns with
     * `rail_ads` aim exactly through it; for other guns the aiming pose is just lowered by this amount.
     */
    @SerialName("ads_height") val adsHeight: Float = 0f,
    /** Item model (an `assets/<ns>/items/<name>.json` id) used for the attachment item. */
    @Serializable(IdentifierSerializer::class) val icon: Identifier? = null,
) {
    fun fits(gunId: Identifier, gun: GunDefinition) = slot in gun.attachmentSlots && (guns.isEmpty() || gunId in guns)
}

private fun GunDefinition.adsThroughSight(height: Float): Map<String, Transform> {
    val ads = display[Transform.ADS] ?: Transform.DEFAULTS.getValue(Transform.ADS)
    val t = ads.translation
    val y = railAds?.minus(height) ?: (t[1] - height)
    return display + (Transform.ADS to ads.copy(translation = listOf(t[0], y, t.getOrElse(2) { 0f })))
}

/** The gun's stats with all [attachments] applied. */
fun GunDefinition.withAttachments(attachments: Collection<AttachmentDefinition>): GunDefinition =
    attachments.fold(this) { gun, a ->
        gun.copy(
            damage = gun.damage * a.damageMultiplier,
            spread = gun.spread * a.spreadMultiplier,
            adsSpread = gun.adsSpread * a.spreadMultiplier,
            recoil = gun.recoil.copy(pitch = gun.recoil.pitch * a.recoilMultiplier, yaw = gun.recoil.yaw * a.recoilMultiplier),
            velocity = gun.velocity * a.velocityMultiplier,
            reloadTicks = (gun.reloadTicks * a.reloadMultiplier).toInt().coerceAtLeast(1),
            adsMoveSpeed = gun.adsMoveSpeed * a.adsMoveSpeedMultiplier,
            adsZoom = a.adsZoom ?: gun.adsZoom,
            sounds = a.shootSound?.let { gun.sounds.copy(shoot = it) } ?: gun.sounds,
            tracer = if (a.hideTracer) null else gun.tracer,
            scope = a.scope ?: if (a.slot == "sight") null else gun.scope,
            display = if (a.adsHeight == 0f) gun.display else gun.adsThroughSight(a.adsHeight),
        )
    }
