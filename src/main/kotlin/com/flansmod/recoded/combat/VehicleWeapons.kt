package com.flansmod.recoded.combat

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.entity.GrenadeEntity
import com.flansmod.recoded.gun.FireMode
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.withAmmo
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.AmmoLoading
import com.flansmod.recoded.network.ShotPayload
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PlayerLookup
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3
import java.util.WeakHashMap

/**
 * Guns mounted on vehicle seats. They are ordinary gun definitions (`mounted: true`) fired with the same ballistics,
 * ammo and magazines as hand-held guns; only the muzzle, aim and the magazine storage (on the vehicle, per seat) differ.
 */
object VehicleWeapons {
    private class SeatState(var nextShotTick: Long = 0, var reloadDoneTick: Long = -1, var reloader: ServerPlayer? = null)

    private val states = WeakHashMap<DriveableEntity, HashMap<Int, SeatState>>()
    private fun state(vehicle: DriveableEntity, seat: Int) = states.getOrPut(vehicle, ::HashMap).getOrPut(seat, ::SeatState)

    fun init() {
        ServerPlayNetworking.registerGlobalReceiver(com.flansmod.recoded.network.SwitchSeatPayload.TYPE) { _, ctx ->
            val player = ctx.player()
            (player.vehicle as? DriveableEntity)?.let { vehicle ->
                if (vehicle.switchSeat(player)) player.level().playSound(null, player.x, player.y, player.z,
                    net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.6f, 1.2f)
            }
        }
        ServerTickEvents.END_SERVER_TICK.register {
            states.toList().forEach { (vehicle, seats) -> seats.forEach { (seat, state) -> tickReload(vehicle, seat, state) } }
        }
    }

    /** The mounted gun [player] operates, if their seat has one. */
    fun seatGun(player: ServerPlayer): Triple<DriveableEntity, Int, Identifier>? {
        val vehicle = player.vehicle as? DriveableEntity ?: return null
        val seat = vehicle.seatOf(player)
        val gun = vehicle.seat(seat)?.gun ?: return null
        return Triple(vehicle, seat, gun)
    }

    /** Whether [player] drives a vehicle (seat 0). */
    fun isDriver(player: ServerPlayer) = (player.vehicle as? DriveableEntity)?.seatOf(player) == 0

    /** Fires the seat's gun; returns false if [player] does not operate one (then hand-held guns work as usual). */
    fun trigger(player: ServerPlayer): Boolean {
        val (vehicle, seat, gunId) = seatGun(player) ?: return false
        val gun = Guns[gunId] ?: return true
        val state = state(vehicle, seat)
        val now = player.level().gameTime
        if (state.reloadDoneTick >= 0 || now < state.nextShotTick || gun.fireMode == FireMode.SAFE) return true
        if (!vehicle.weaponWorks(seat)) {
            state.nextShotTick = now + 20
            player.sendOverlayMessage(Component.translatable("message.flansmod.vehicle.weapon_broken"))
            return true
        }

        val magazine = vehicle.seatMagazines[seat]
        if (magazine == null || magazine.isEmpty) {
            state.nextShotTick = now + 10
            playSound(vehicle.position(), player, gun.sounds.empty)
            reload(player)
            return true
        }
        vehicle.setMagazine(seat, magazine.withRounds(magazine.rounds - 1))
        state.nextShotTick = now + gun.ticksBetweenShots

        val ammo = magazine.ammoDefinition
        val shot = gun.withAmmo(ammo)
        val (yaw, elevation) = vehicle.aim(seat, player)
        val origin = vehicle.muzzlePosition(seat, yaw, elevation)
        val directions = List(shot.pellets.coerceAtLeast(1)) { GunHandler.scatter(player, vehicle.aimDirection(yaw, elevation), shot.spread) }
        val projectile = ammo?.projectile?.let(GrenadeItem::stackFor)
        directions.forEach { dir ->
            if (projectile != null) {
                GrenadeEntity(player.level(), player, projectile).apply {
                    setPos(origin)
                    shoot(dir.x, dir.y, dir.z, shot.velocity.toFloat(), 0f)
                    player.level().addFreshEntity(this)
                }
            } else {
                Ballistics.fire(player, shot, ammo, dir, origin)
            }
        }
        val payload = ShotPayload(player.id, gunId, origin, directions)
        (PlayerLookup.tracking(vehicle) + player).toSet()
            .filter { ServerPlayNetworking.canSend(it, ShotPayload.TYPE) }
            .forEach { ServerPlayNetworking.send(it, payload) }
        playSound(origin, player, gun.sounds.shoot)
        return true
    }

    /** Starts loading the fullest fitting magazine from [player]'s inventory into their seat's gun. */
    fun reload(player: ServerPlayer): Boolean {
        val (vehicle, seat, gunId) = seatGun(player) ?: return false
        val gun = Guns[gunId] ?: return true
        val state = state(vehicle, seat)
        if (state.reloadDoneTick >= 0) return true
        val current = vehicle.seatMagazines[seat]
        val internal = GunItem.internalMagazine(gunId)
        val multiplier = if (internal != null) {
            // Built-in breech/feed: loose rounds (shells) go straight in.
            val contents = current ?: MagazineContents(internal, null, 0)
            if (contents.isFull) return true
            if (AmmoLoading.looseRounds(player, contents) == 0 && !player.hasInfiniteMaterials()) {
                if (contents.isEmpty) player.sendOverlayMessage(Component.translatable("message.flansmod.no_magazine"))
                return true
            }
            contents.definition?.reloadMultiplier ?: 1f
        } else {
            val candidate = GunHandler.bestMagazine(player, gunId)
            when {
                candidate == null && !player.hasInfiniteMaterials() -> {
                    if (current == null || current.isEmpty) player.sendOverlayMessage(Component.translatable("message.flansmod.no_magazine"))
                    return true
                }
                candidate != null && current != null && candidate.second.rounds <= current.rounds -> return true
                candidate == null && current?.isFull == true -> return true
            }
            (candidate?.second?.definition ?: current?.definition)?.reloadMultiplier ?: 1f
        }
        state.reloadDoneTick = player.level().gameTime + (gun.reloadTicks * multiplier).toInt().coerceAtLeast(1)
        state.reloader = player
        vehicle.reloadEnds = vehicle.reloadEnds + (seat to state.reloadDoneTick)
        playSound(vehicle.position(), player, gun.sounds.reload)
        return true
    }

    /** Ticks left until the seat's reload finishes, or -1 (for the HUD and tests). */
    fun isReloading(vehicle: DriveableEntity, seat: Int) = states[vehicle]?.get(seat)?.let { it.reloadDoneTick >= 0 } == true

    private fun tickReload(vehicle: DriveableEntity, seat: Int, state: SeatState) {
        if (state.reloadDoneTick < 0) return
        val player = state.reloader
        // The loader has to stay in the seat for the whole reload.
        if (player == null || player.vehicle != vehicle || vehicle.seatOf(player) != seat || vehicle.isRemoved) {
            state.reloadDoneTick = -1
            vehicle.reloadEnds = vehicle.reloadEnds - seat
            return
        }
        if (vehicle.level().gameTime < state.reloadDoneTick) return
        state.reloadDoneTick = -1
        vehicle.reloadEnds = vehicle.reloadEnds - seat
        val gunId = vehicle.seat(seat)?.gun ?: return
        val old = vehicle.seatMagazines[seat]
        val internal = GunItem.internalMagazine(gunId)
        if (internal != null) {
            val contents = old ?: MagazineContents(internal, null, 0)
            val filled = AmmoLoading.fill(player, contents)
            vehicle.setMagazine(seat, if (filled == contents && player.hasInfiniteMaterials()) MagazineContents.full(internal, contents.ammo) else filled)
            return
        }
        val candidate = GunHandler.bestMagazine(player, gunId)
        if (candidate != null) {
            val (slot, contents) = candidate
            AmmoLoading.swapMagazine(player, slot, old)
            vehicle.setMagazine(seat, contents)
        } else if (player.hasInfiniteMaterials()) {
            val type = old?.magazine ?: GunItem.acceptedMagazines(gunId).firstOrNull() ?: return
            vehicle.setMagazine(seat, MagazineContents.full(type, old?.ammo))
        }
    }

    private fun playSound(at: Vec3, player: ServerPlayer, id: Identifier?) {
        id ?: return
        player.level().playSound(null, at.x, at.y, at.z, SoundEvent.createVariableRangeEvent(id), SoundSource.PLAYERS, 1.5f, 1f)
    }
}
