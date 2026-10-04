package com.flansmod.recoded.combat

import com.flansmod.recoded.gun.FireMode
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.ammo
import com.flansmod.recoded.item.definition
import com.flansmod.recoded.item.gunId
import com.flansmod.recoded.network.ReloadPayload
import com.flansmod.recoded.network.ShootPayload
import com.geckolib.animatable.GeoItem
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Server side of firing and reloading. Clients only request actions; fire rate, ammo and reload
 * timing are enforced here.
 */
object GunHandler {
    private class State {
        var nextShotTick = 0L
        var burstLeft = 0
        var aiming = false
        var reloadDoneTick = -1L
        var reloadSlot = -1
    }

    // Weak keys: a respawned or disconnected player is a new/dead object, so stale state just disappears.
    private val states = WeakHashMap<ServerPlayer, State>()
    private val ServerPlayer.gunState get() = states.getOrPut(this, ::State)

    fun init() {
        ServerPlayNetworking.registerGlobalReceiver(ShootPayload.TYPE) { payload, ctx -> trigger(ctx.player(), payload.aiming) }
        ServerPlayNetworking.registerGlobalReceiver(ReloadPayload.TYPE) { _, ctx -> reload(ctx.player()) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> states.remove(handler.player) }
        ServerTickEvents.END_SERVER_TICK.register { states.toList().forEach { (player, state) -> tick(player, state) } }
    }

    /** Trigger pulled by [player]; also the entry point for tests. */
    fun trigger(player: ServerPlayer, aiming: Boolean) {
        val stack = player.mainHandItem
        val gun = stack.definition ?: return
        val state = player.gunState
        state.aiming = aiming
        if (state.reloadDoneTick >= 0 || state.burstLeft > 0) return
        if (player.level().gameTime < state.nextShotTick) return

        if (stack.ammo <= 0) {
            playSound(player, gun.sounds.empty)
            reload(player)
            return
        }
        if (gun.fireMode == FireMode.BURST) state.burstLeft = gun.burstCount
        shoot(player, stack, gun, state)
    }

    private fun shoot(player: ServerPlayer, stack: ItemStack, gun: GunDefinition, state: State) {
        stack.ammo -= 1
        state.nextShotTick = player.level().gameTime + gun.ticksBetweenShots
        if (state.burstLeft > 0) state.burstLeft--

        val spread = if (state.aiming) gun.adsSpread else gun.spread
        repeat(gun.pellets.coerceAtLeast(1)) {
            Ballistics.fire(player, gun, scatter(player, player.lookAngle, spread))
        }
        playSound(player, gun.sounds.shoot)
        triggerAnim(player, stack, GunItem.ANIM_SHOOT)
    }

    private fun tick(player: ServerPlayer, state: State) {
        val now = player.level().gameTime
        val stack = player.mainHandItem
        val gun = stack.definition

        if (state.burstLeft > 0) {
            when {
                gun == null || stack.ammo <= 0 -> state.burstLeft = 0
                now >= state.nextShotTick -> shoot(player, stack, gun, state)
            }
        }

        if (state.reloadDoneTick >= 0) {
            when {
                gun == null || player.inventory.selectedSlot != state.reloadSlot -> state.reloadDoneTick = -1
                now >= state.reloadDoneTick -> {
                    state.reloadDoneTick = -1
                    finishReload(player, stack, gun)
                }
            }
        }
    }

    /** Starts a reload if possible; also the entry point for tests. */
    fun reload(player: ServerPlayer) {
        val stack = player.mainHandItem
        val gun = stack.definition ?: return
        val state = player.gunState
        if (state.reloadDoneTick >= 0 || stack.ammo >= gun.magazine) return
        if (!player.hasInfiniteMaterials() && gun.ammo != null && countAmmo(player, gun.ammo.item) == 0) {
            playSound(player, gun.sounds.empty)
            return
        }
        state.reloadDoneTick = player.level().gameTime + gun.reloadTicks
        state.reloadSlot = player.inventory.selectedSlot
        state.burstLeft = 0
        playSound(player, gun.sounds.reload)
        triggerAnim(player, stack, GunItem.ANIM_RELOAD)
    }

    private fun finishReload(player: ServerPlayer, stack: ItemStack, gun: GunDefinition) {
        val missing = gun.magazine - stack.ammo
        val ammo = gun.ammo
        if (ammo == null || player.hasInfiniteMaterials()) {
            stack.ammo = gun.magazine
            return
        }
        val itemsWanted = Mth.positiveCeilDiv(missing, ammo.roundsPerItem.coerceAtLeast(1))
        val taken = takeAmmo(player, ammo.item, itemsWanted)
        stack.ammo += minOf(missing, taken * ammo.roundsPerItem)
    }

    private fun countAmmo(player: ServerPlayer, item: Identifier): Int =
        player.inventory.nonEquipmentItems.filter { it.isAmmo(item) }.sumOf { it.count }

    private fun takeAmmo(player: ServerPlayer, item: Identifier, wanted: Int): Int {
        var left = wanted
        for (stack in player.inventory.nonEquipmentItems) {
            if (left == 0) break
            if (!stack.isAmmo(item)) continue
            val n = minOf(left, stack.count)
            stack.shrink(n)
            left -= n
        }
        return wanted - left
    }

    private fun ItemStack.isAmmo(item: Identifier) = !isEmpty && BuiltInRegistries.ITEM.getKey(this.item) == item

    /** Rotates [dir] by a random angle inside a cone of [degrees] half-angle. */
    private fun scatter(player: ServerPlayer, dir: Vec3, degrees: Float): Vec3 {
        if (degrees <= 0f) return dir
        val random = player.random
        val angle = Math.toRadians(degrees * random.nextDouble())
        val roll = random.nextDouble() * Math.PI * 2
        val up = if (kotlin.math.abs(dir.y) > 0.99) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val right = dir.cross(up).normalize()
        val realUp = right.cross(dir).normalize()
        val offset = right.scale(cos(roll)).add(realUp.scale(sin(roll))).scale(kotlin.math.tan(angle))
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
