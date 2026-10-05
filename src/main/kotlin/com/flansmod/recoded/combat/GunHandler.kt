package com.flansmod.recoded.combat

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.entity.GrenadeEntity
import com.flansmod.recoded.gun.FireMode
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.MagazineItem
import com.flansmod.recoded.item.definition
import com.flansmod.recoded.item.gunId
import com.flansmod.recoded.item.loadedMagazine
import com.flansmod.recoded.item.shotDefinition
import com.flansmod.recoded.network.AimPayload
import com.flansmod.recoded.network.ReloadPayload
import com.flansmod.recoded.network.ShootPayload
import com.flansmod.recoded.network.ShotPayload
import com.geckolib.animatable.GeoItem
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Prediction
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Server side of firing, aiming and magazines. Clients only request actions; fire rate, rounds,
 * magazine swaps and scope effects are decided here.
 */
object GunHandler {
    private class State {
        var nextShotTick = 0L
        var burstLeft = 0
        var aiming = false
        var nightVision = false
        var reloadDoneTick = -1L
        var reloadSlot = -1
    }

    // Weak keys: a respawned or disconnected player is a new/dead object, so stale state just disappears.
    private val states = WeakHashMap<ServerPlayer, State>()
    private val ServerPlayer.gunState get() = states.getOrPut(this, ::State)

    private val ADS_SLOWDOWN = FlansMod.id("ads_slowdown")
    private const val NIGHT_VISION_TICKS = 260 // above vanilla's 200-tick flicker threshold

    fun init() {
        ServerPlayNetworking.registerGlobalReceiver(ShootPayload.TYPE) { _, ctx -> trigger(ctx.player()) }
        ServerPlayNetworking.registerGlobalReceiver(AimPayload.TYPE) { payload, ctx -> setAiming(ctx.player(), payload.aiming) }
        ServerPlayNetworking.registerGlobalReceiver(ReloadPayload.TYPE) { payload, ctx ->
            if (payload.unload) unload(ctx.player()) else reload(ctx.player())
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> states.remove(handler.player) }
        ServerTickEvents.END_SERVER_TICK.register { states.toList().forEach { (player, state) -> tick(player, state) } }
    }

    /** Trigger pulled by [player]; also the entry point for tests. */
    fun trigger(player: ServerPlayer) {
        val stack = player.mainHandItem
        val gun = stack.shotDefinition ?: return
        val state = player.gunState
        if (state.reloadDoneTick >= 0 || state.burstLeft > 0) return
        if (player.level().gameTime < state.nextShotTick) return

        val magazine = stack.loadedMagazine
        if (magazine == null || magazine.isEmpty) {
            state.nextShotTick = player.level().gameTime + 10
            playSound(player, gun.sounds.empty)
            reload(player)
            return
        }
        if (gun.fireMode == FireMode.BURST) state.burstLeft = gun.burstCount
        shoot(player, stack, gun, state)
    }

    private fun shoot(player: ServerPlayer, stack: ItemStack, gun: GunDefinition, state: State) {
        val magazine = stack.loadedMagazine ?: return
        val ammo = magazine.ammoDefinition
        stack.loadedMagazine = magazine.withRounds(magazine.rounds - 1)
        state.nextShotTick = player.level().gameTime + gun.ticksBetweenShots
        if (state.burstLeft > 0) state.burstLeft--

        val spread = if (state.aiming) gun.adsSpread else gun.spread
        val directions = List(gun.pellets.coerceAtLeast(1)) { scatter(player, player.lookAngle, spread) }
        val projectile = ammo?.projectile?.let(GrenadeItem::stackFor)
        if (projectile != null) {
            // Launchers: the round is a grenade-type projectile (rocket, 40mm) instead of a bullet.
            directions.forEach { dir ->
                GrenadeEntity(player.level(), player, projectile).apply {
                    shoot(dir.x, dir.y, dir.z, gun.velocity.toFloat(), 0f)
                    player.level().addFreshEntity(this)
                }
            }
        } else {
            directions.forEach { Ballistics.fire(player, gun, ammo, it) }
            broadcastShot(player, stack, directions)
        }
        playSound(player, gun.sounds.shoot)
        triggerAnim(player, stack, GunItem.ANIM_SHOOT)
    }

    /** Aim-down-sights state: affects spread, movement and scope effects. */
    fun setAiming(player: ServerPlayer, aiming: Boolean) {
        val gun = player.mainHandItem.definition
        val state = player.gunState
        state.aiming = aiming && gun != null
        val speed = player.getAttribute(Attributes.MOVEMENT_SPEED) ?: return
        speed.removeModifier(ADS_SLOWDOWN)
        if (state.aiming && gun!!.adsMoveSpeed < 1f) {
            speed.addTransientModifier(AttributeModifier(ADS_SLOWDOWN, gun.adsMoveSpeed - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
        }
    }

    private fun tick(player: ServerPlayer, state: State) {
        val now = player.level().gameTime
        val stack = player.mainHandItem
        val gun = stack.shotDefinition
        if (state.aiming && gun == null) setAiming(player, false)
        updateNightVision(player, state, state.aiming && gun?.scope?.nightVision == true)

        if (state.burstLeft > 0) {
            when {
                gun == null || stack.loadedMagazine?.isEmpty != false -> state.burstLeft = 0
                now >= state.nextShotTick -> shoot(player, stack, gun, state)
            }
        }

        if (state.reloadDoneTick >= 0) {
            when {
                gun == null || player.inventory.selectedSlot != state.reloadSlot -> state.reloadDoneTick = -1
                now >= state.reloadDoneTick -> {
                    state.reloadDoneTick = -1
                    finishReload(player, stack)
                }
            }
        }
    }

    /** Night-vision scopes: a hidden, ambient effect that is only removed if it is still ours. */
    private fun updateNightVision(player: ServerPlayer, state: State, wanted: Boolean) {
        if (wanted) {
            player.addEffect(MobEffectInstance(MobEffects.NIGHT_VISION, NIGHT_VISION_TICKS, 0, true, false, false))
            state.nightVision = true
        } else if (state.nightVision) {
            state.nightVision = false
            val current = player.getEffect(MobEffects.NIGHT_VISION)
            if (current != null && current.isAmbient && !current.isVisible && current.duration <= NIGHT_VISION_TICKS) {
                player.removeEffect(MobEffects.NIGHT_VISION)
            }
        }
    }

    /** Starts swapping in the fullest compatible magazine from the inventory. Entry point for tests. */
    fun reload(player: ServerPlayer) {
        val stack = player.mainHandItem
        val gun = stack.definition ?: return
        val state = player.gunState
        if (state.reloadDoneTick >= 0) return

        val current = stack.loadedMagazine
        val candidate = bestMagazine(player, stack)
        val creative = candidate == null && player.hasInfiniteMaterials()
        if (candidate == null && !creative) {
            if (current == null || current.isEmpty) {
                player.sendOverlayMessage(Component.translatable("message.flansmod.no_magazine"))
                playSound(player, gun.sounds.empty)
            }
            return
        }
        if (candidate != null && current != null && candidate.second.rounds <= current.rounds) return
        if (creative && current?.isFull == true) return

        val newMag = candidate?.second?.definition ?: current?.definition
            ?: GunItem.acceptedMagazines(stack.gunId!!).firstNotNullOfOrNull { com.flansmod.recoded.gun.Magazines[it] }
        state.reloadDoneTick = player.level().gameTime + (gun.reloadTicks * (newMag?.reloadMultiplier ?: 1f)).toInt().coerceAtLeast(1)
        state.reloadSlot = player.inventory.selectedSlot
        state.burstLeft = 0
        playSound(player, gun.sounds.reload)
        triggerAnim(player, stack, GunItem.ANIM_RELOAD)
    }

    /** Swaps magazines: the new one comes out of its inventory slot, the old one (empty or not) goes back there. */
    private fun finishReload(player: ServerPlayer, gun: ItemStack) {
        val old = gun.loadedMagazine
        val candidate = bestMagazine(player, gun)
        if (candidate != null) {
            val (slot, contents) = candidate
            player.inventory.setItem(slot, old?.let(MagazineItem::stackFor) ?: ItemStack.EMPTY)
            gun.loadedMagazine = contents
        } else if (player.hasInfiniteMaterials()) {
            val type = old?.magazine ?: GunItem.acceptedMagazines(gun.gunId!!).firstOrNull() ?: return
            gun.loadedMagazine = MagazineContents.full(type, old?.ammo)
        }
    }

    /** Sneak + reload: takes the magazine out of the gun. */
    fun unload(player: ServerPlayer) {
        val gun = player.mainHandItem
        if (gun.definition == null) return
        val magazine = gun.loadedMagazine ?: return
        player.gunState.reloadDoneTick = -1
        gun.loadedMagazine = null
        player.inventory.placeItemBackInInventory(MagazineItem.stackFor(magazine), Prediction.SERVER_ONLY)
        playSound(player, gun.definition?.sounds?.reload)
    }

    /** Inventory slot and contents of the loaded magazine with the most rounds that fits [gun]. */
    private fun bestMagazine(player: ServerPlayer, gun: ItemStack): Pair<Int, MagazineContents>? {
        val accepted = GunItem.acceptedMagazines(gun.gunId ?: return null).toSet()
        val items = player.inventory.nonEquipmentItems
        return items.indices
            .mapNotNull { slot -> items[slot].takeIf { it.item is MagazineItem }?.loadedMagazine?.let { slot to it } }
            .filter { (_, mag) -> mag.magazine in accepted && !mag.isEmpty }
            .maxByOrNull { (_, mag) -> mag.rounds }
    }

    /** Lets the shooter and everyone tracking them draw muzzle flash and tracers. */
    private fun broadcastShot(player: ServerPlayer, stack: ItemStack, directions: List<Vec3>) {
        val payload = ShotPayload(player.id, stack.gunId ?: return, player.eyePosition, directions)
        (PlayerLookup.tracking(player) + player).toSet()
            .filter { ServerPlayNetworking.canSend(it, ShotPayload.TYPE) }
            .forEach { ServerPlayNetworking.send(it, payload) }
    }

    /** Rotates [dir] by a random angle inside a cone of [degrees] half-angle. */
    private fun scatter(player: ServerPlayer, dir: Vec3, degrees: Float): Vec3 {
        if (degrees <= 0f) return dir
        val random = player.random
        val angle = Math.toRadians(degrees * random.nextDouble())
        val roll = random.nextDouble() * Math.PI * 2
        val up = if (kotlin.math.abs(dir.y) > 0.99) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val right = dir.cross(up).normalize()
        val realUp = right.cross(dir).normalize()
        val offset = right.scale(cos(roll)).add(realUp.scale(sin(roll))).scale(tan(angle))
        return dir.add(offset).normalize()
    }

    private fun playSound(player: ServerPlayer, id: Identifier?) {
        id ?: return
        player.level().playSound(null, player.x, player.y, player.z, SoundEvent.createVariableRangeEvent(id), SoundSource.PLAYERS, 1f, 1f)
    }

    private fun triggerAnim(player: ServerPlayer, stack: ItemStack, anim: String) {
        val item = stack.item as? GunItem ?: return
        item.triggerAnim(player, GeoItem.getOrAssignId(stack, player.level()), GunItem.CONTROLLER, anim)
    }
}
