package com.flansmod.recoded.combat

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.entity.GrenadeEntity
import com.flansmod.recoded.gun.FireMode
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.vec
import com.flansmod.recoded.gun.withAmmo
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.AmmoLoading
import com.flansmod.recoded.network.SecondaryFirePayload
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
 *
 * A seat can have two weapons: its `gun` (attack key) and a `secondary` (secondary-weapon key), e.g. a fighter's guns
 * and its bombs. Each has its own weapon slot on the vehicle: the seat index, and seat index +
 * [DriveableEntity.SECONDARY] for the secondary (magazines, reload timers). The reload key loads both.
 */
object VehicleWeapons {
    private class SlotState(var nextShotTick: Long = 0, var reloadDoneTick: Long = -1, var reloader: ServerPlayer? = null)

    private val states = WeakHashMap<DriveableEntity, HashMap<Int, SlotState>>()
    private fun state(vehicle: DriveableEntity, slot: Int) = states.getOrPut(vehicle, ::HashMap).getOrPut(slot, ::SlotState)

    /** A weapon a crew member operates: the vehicle, the seat, the weapon slot and the gun definition id. */
    data class Weapon(val vehicle: DriveableEntity, val seat: Int, val slot: Int, val gun: Identifier) {
        val secondary get() = slot >= DriveableEntity.SECONDARY
    }

    fun init() {
        ServerPlayNetworking.registerGlobalReceiver(com.flansmod.recoded.network.SwitchSeatPayload.TYPE) { _, ctx ->
            val player = ctx.player()
            (player.vehicle as? DriveableEntity)?.let { vehicle ->
                if (vehicle.switchSeat(player)) player.level().playSound(null, player.x, player.y, player.z,
                    net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.PLAYERS, 0.6f, 1.2f)
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(SecondaryFirePayload.TYPE) { _, ctx -> trigger(ctx.player(), secondary = true) }
        ServerTickEvents.END_SERVER_TICK.register {
            states.toList().forEach { (vehicle, slots) -> slots.forEach { (slot, state) -> tickReload(vehicle, slot, state) } }
        }
    }

    /** The seat weapon [player] operates ([secondary]: the seat's second weapon), if their seat has one. */
    fun weapon(player: ServerPlayer, secondary: Boolean = false): Weapon? {
        val vehicle = player.vehicle as? DriveableEntity ?: return null
        val seat = vehicle.seatOf(player)
        val def = vehicle.seat(seat) ?: return null
        val gun = (if (secondary) def.secondary else def.gun) ?: return null
        return Weapon(vehicle, seat, if (secondary) seat + DriveableEntity.SECONDARY else seat, gun)
    }

    /** Whether [player] drives a vehicle (seat 0). */
    fun isDriver(player: ServerPlayer) = (player.vehicle as? DriveableEntity)?.seatOf(player) == 0

    /** Fires the seat's gun (or its [secondary] weapon); returns false if [player]'s seat has none. */
    fun trigger(player: ServerPlayer, secondary: Boolean = false): Boolean {
        val w = weapon(player, secondary) ?: return false
        val vehicle = w.vehicle
        val gun = Guns[w.gun] ?: return true
        val state = state(vehicle, w.slot)
        val now = player.level().gameTime
        if (state.reloadDoneTick >= 0 || now < state.nextShotTick || gun.fireMode == FireMode.SAFE) return true
        if (!vehicle.weaponWorks(w.seat)) {
            state.nextShotTick = now + 20
            player.sendOverlayMessage(Component.translatable("message.flansmod.vehicle.weapon_broken"))
            return true
        }

        val magazine = vehicle.seatMagazines[w.slot]
        if (magazine == null || magazine.isEmpty) {
            state.nextShotTick = now + 10
            playSound(vehicle.position(), player, gun.sounds.empty)
            reloadSlot(player, w)
            return true
        }
        vehicle.setMagazine(w.slot, magazine.withRounds(magazine.rounds - 1))
        state.nextShotTick = now + gun.ticksBetweenShots

        val ammo = magazine.ammoDefinition
        val shot = gun.withAmmo(ammo)
        val (yaw, elevation) = vehicle.aim(w.seat, player)
        val origin = if (w.secondary) vehicle.position().add(vehicle.toWorld(vehicle.seat(w.seat)!!.secondaryMuzzle.vec()))
        else vehicle.muzzlePosition(w.seat, yaw, elevation)
        val projectile = ammo?.projectile?.let(GrenadeItem::stackFor)
        if (gun.drop) {
            // Bombs leave the rack with the vehicle's own velocity and fall from there.
            val velocity = vehicle.flight.velocity
            if (projectile != null) GrenadeEntity(player.level(), player, projectile).apply {
                setPos(origin)
                deltaMovement = velocity
                player.level().addFreshEntity(this)
            }
            playSound(origin, player, gun.sounds.shoot)
            return true
        }
        val directions = List(shot.pellets.coerceAtLeast(1)) { GunHandler.scatter(player, vehicle.aimDirection(yaw, elevation), shot.spread) }
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
        val payload = ShotPayload(player.id, w.gun, origin, directions)
        (PlayerLookup.tracking(vehicle) + player).toSet()
            .filter { ServerPlayNetworking.canSend(it, ShotPayload.TYPE) }
            .forEach { ServerPlayNetworking.send(it, payload) }
        playSound(origin, player, gun.sounds.shoot)
        return true
    }

    /**
     * An unmanned sentry turret fires seat 0's gun along its own aim ([DriveableEntity.sentryAim]); hits are credited
     * to [owner]. An empty gun reloads from the turret's cargo instead (taking the gun's reload time).
     */
    fun fireSentry(vehicle: DriveableEntity, owner: net.minecraft.world.entity.Entity?): Boolean {
        val gunId = vehicle.seat(0)?.gun ?: return false
        val gun = Guns[gunId] ?: return false
        val level = vehicle.level() as? net.minecraft.server.level.ServerLevel ?: return false
        val state = state(vehicle, 0)
        val now = level.gameTime
        if (now < state.nextShotTick || !vehicle.weaponWorks(0)) return false
        val magazine = vehicle.seatMagazines[0]
        if (magazine == null || magazine.isEmpty) {
            if (reloadFromStorage(vehicle, gunId)) state.nextShotTick = now + gun.reloadTicks
            else state.nextShotTick = now + 40 // nothing to load: look again in a while
            return false
        }
        vehicle.setMagazine(0, magazine.withRounds(magazine.rounds - 1))
        state.nextShotTick = now + gun.ticksBetweenShots
        val ammo = magazine.ammoDefinition
        val shot = gun.withAmmo(ammo)
        val (yaw, elevation) = vehicle.aim(0, vehicle)
        val origin = vehicle.muzzlePosition(0, yaw, elevation)
        val directions = List(shot.pellets.coerceAtLeast(1)) { scatter(vehicle, vehicle.aimDirection(yaw, elevation), shot.spread) }
        val projectile = ammo?.projectile?.let(GrenadeItem::stackFor)
        directions.forEach { dir ->
            val shooter = owner as? net.minecraft.world.entity.LivingEntity
            if (projectile != null && shooter != null) GrenadeEntity(level, shooter, projectile).apply {
                setPos(origin)
                shoot(dir.x, dir.y, dir.z, shot.velocity.toFloat(), 0f)
                level.addFreshEntity(this)
            } else if (projectile == null) Ballistics.fire(vehicle, shot, ammo, dir, origin, cause = owner)
        }
        val payload = ShotPayload(vehicle.id, gunId, origin, directions)
        PlayerLookup.tracking(vehicle).filter { ServerPlayNetworking.canSend(it, ShotPayload.TYPE) }.forEach { ServerPlayNetworking.send(it, payload) }
        gun.sounds.shoot?.let { level.playSound(null, origin.x, origin.y, origin.z, SoundEvent.createVariableRangeEvent(it), SoundSource.HOSTILE, 1.5f, 1f) }
        return true
    }

    /** Loads the fullest fitting magazine (or loose rounds for a built-in feed) from the vehicle's cargo. */
    fun reloadFromStorage(vehicle: DriveableEntity, gunId: Identifier): Boolean {
        val storage = vehicle.storage
        val size = vehicle.definition?.storage ?: 0
        val old = vehicle.seatMagazines[0]
        val internal = GunItem.internalMagazine(gunId)
        if (internal != null) {
            val contents = old ?: MagazineContents(internal, null, 0)
            val def = contents.definition ?: return false
            for (i in 0 until size) {
                val stack = storage.getItem(i)
                val ammoId = stack.get(com.flansmod.recoded.registry.FlansComponents.AMMO_TYPE) ?: continue
                val ammo = com.flansmod.recoded.gun.AmmoTypes[ammoId] ?: continue
                if (!def.accepts(ammo) || (contents.ammo != null && contents.rounds > 0 && contents.ammo != ammoId)) continue
                val taken = minOf(stack.count, def.capacity - contents.rounds)
                if (taken <= 0) return false
                stack.shrink(taken)
                vehicle.setMagazine(0, MagazineContents(internal, ammoId, contents.rounds + taken))
                return true
            }
            return false
        }
        val accepted = GunItem.acceptedMagazines(gunId)
        val best = (0 until size).mapNotNull { i -> storage.getItem(i).get(com.flansmod.recoded.registry.FlansComponents.MAGAZINE)?.takeIf { it.magazine in accepted && !it.isEmpty }?.let { i to it } }
            .maxByOrNull { it.second.rounds } ?: return false
        val (slot, contents) = best
        storage.setItem(slot, old?.let { com.flansmod.recoded.item.MagazineItem.stackFor(it) } ?: net.minecraft.world.item.ItemStack.EMPTY)
        vehicle.setMagazine(0, contents)
        return true
    }

    /** [dir] turned by up to [degrees] at random (gun spread). */
    private fun scatter(entity: net.minecraft.world.entity.Entity, dir: Vec3, degrees: Float): Vec3 {
        if (degrees <= 0f) return dir
        val random = entity.random
        val angle = Math.toRadians(degrees * random.nextDouble())
        val roll = random.nextDouble() * Math.PI * 2
        val up = if (kotlin.math.abs(dir.y) > 0.99) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
        val right = dir.cross(up).normalize()
        val realUp = right.cross(dir).normalize()
        return dir.add(right.scale(kotlin.math.cos(roll)).add(realUp.scale(kotlin.math.sin(roll))).scale(kotlin.math.tan(angle))).normalize()
    }

    /** Reload key: starts loading both of [player]'s seat weapons; returns false if the seat has none. */
    fun reload(player: ServerPlayer): Boolean {
        val weapons = listOfNotNull(weapon(player), weapon(player, secondary = true))
        weapons.forEach { reloadSlot(player, it) }
        return weapons.isNotEmpty()
    }

    /** Starts loading the fullest fitting magazine (or loose rounds) from [player]'s inventory into weapon [w]. */
    private fun reloadSlot(player: ServerPlayer, w: Weapon) {
        val vehicle = w.vehicle
        val gun = Guns[w.gun] ?: return
        val state = state(vehicle, w.slot)
        if (state.reloadDoneTick >= 0) return
        val current = vehicle.seatMagazines[w.slot]
        val internal = GunItem.internalMagazine(w.gun)
        val multiplier = if (internal != null) {
            // Built-in breech/feed/bomb rack: loose rounds (shells, bombs) go straight in.
            val contents = current ?: MagazineContents(internal, null, 0)
            if (contents.isFull) return
            if (AmmoLoading.looseRounds(player, contents) == 0 && !player.hasInfiniteMaterials()) {
                if (contents.isEmpty) player.sendOverlayMessage(Component.translatable("message.flansmod.no_magazine"))
                return
            }
            contents.definition?.reloadMultiplier ?: 1f
        } else {
            val candidate = GunHandler.bestMagazine(player, w.gun)
            when {
                candidate == null && !player.hasInfiniteMaterials() -> {
                    if (current == null || current.isEmpty) player.sendOverlayMessage(Component.translatable("message.flansmod.no_magazine"))
                    return
                }
                candidate != null && current != null && candidate.second.rounds <= current.rounds -> return
                candidate == null && current?.isFull == true -> return
            }
            (candidate?.second?.definition ?: current?.definition)?.reloadMultiplier ?: 1f
        }
        state.reloadDoneTick = player.level().gameTime + (gun.reloadTicks * multiplier).toInt().coerceAtLeast(1)
        state.reloader = player
        vehicle.reloadEnds = vehicle.reloadEnds + (w.slot to state.reloadDoneTick)
        playSound(vehicle.position(), player, gun.sounds.reload)
    }

    /** Whether weapon slot [slot] of [vehicle] is reloading (for the HUD and tests). */
    fun isReloading(vehicle: DriveableEntity, slot: Int) = states[vehicle]?.get(slot)?.let { it.reloadDoneTick >= 0 } == true

    private fun tickReload(vehicle: DriveableEntity, slot: Int, state: SlotState) {
        if (state.reloadDoneTick < 0) return
        val player = state.reloader
        val seat = slot % DriveableEntity.SECONDARY
        // The loader has to stay in the seat for the whole reload.
        if (player == null || player.vehicle != vehicle || vehicle.seatOf(player) != seat || vehicle.isRemoved) {
            state.reloadDoneTick = -1
            vehicle.reloadEnds = vehicle.reloadEnds - slot
            return
        }
        if (vehicle.level().gameTime < state.reloadDoneTick) return
        state.reloadDoneTick = -1
        vehicle.reloadEnds = vehicle.reloadEnds - slot
        val gunId = vehicle.seat(seat)?.let { if (slot >= DriveableEntity.SECONDARY) it.secondary else it.gun } ?: return
        val old = vehicle.seatMagazines[slot]
        val internal = GunItem.internalMagazine(gunId)
        if (internal != null) {
            val contents = old ?: MagazineContents(internal, null, 0)
            val filled = AmmoLoading.fill(player, contents)
            vehicle.setMagazine(slot, if (filled == contents && player.hasInfiniteMaterials()) MagazineContents.full(internal, contents.ammo) else filled)
            return
        }
        val candidate = GunHandler.bestMagazine(player, gunId)
        if (candidate != null) {
            val (invSlot, contents) = candidate
            AmmoLoading.swapMagazine(player, invSlot, old)
            vehicle.setMagazine(slot, contents)
        } else if (player.hasInfiniteMaterials()) {
            val type = old?.magazine ?: GunItem.acceptedMagazines(gunId).firstOrNull() ?: return
            vehicle.setMagazine(slot, MagazineContents.full(type, old?.ammo))
        }
    }

    private fun playSound(at: Vec3, player: ServerPlayer, id: Identifier?) {
        id ?: return
        player.level().playSound(null, at.x, at.y, at.z, SoundEvent.createVariableRangeEvent(id), SoundSource.PLAYERS, 1.5f, 1f)
    }
}
