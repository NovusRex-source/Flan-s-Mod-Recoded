package com.flansmod.recoded.aircraft

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.VehicleDefinition
import com.flansmod.recoded.gun.VehicleType
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/** Flight state of one aircraft. Not saved: a reloaded aircraft starts with its engine idle. */
class FlightState {
    /** Planes: throttle setting 0..1 (kept while no key is held; only the simulating side knows it). */
    var throttle = 0f
    /** Planes: vertical speed from missing lift (negative = sinking). */
    var sink = 0.0
    /** Engine/rotor spool 0..1 on every side: rises while a pilot sits in a fuelled aircraft whose engine works. */
    var power = 0f
    /** Rotor/propeller angle in radians (visual). */
    var spin = 0f
    var prevSpin = 0f
    /** Movement over the last tick as seen on this side (HUD; airspeed when a new side takes over the simulation). */
    var velocity: Vec3 = Vec3.ZERO
    internal var lastPos: Vec3? = null
    /** Whether this side simulated the aircraft last tick. */
    var simulating = false
}

/**
 * Arcade flight for [DriveableEntity]s of type `plane` and `helicopter`. Like driving, it runs on whichever side owns
 * the movement (the pilot's client, else the server) and vanilla's vehicle move packet carries position, yaw and the
 * plane's pitch (`xRot`) to the server. Bank and helicopter tilt are derived from the movement on every side, so they
 * need no extra syncing; seats, gun mounts and part hit boxes follow them through [DriveableEntity.toWorld].
 *
 * - **Planes:** forward/back set the throttle, which stays where it was left. In the air the nose follows the pilot's
 *   view (turn rate `turn_speed`, pitch rate `flight.pitch_speed`); the controls need airspeed. Below
 *   `flight.lift_speed` the wings stop carrying: the plane sinks and the nose drops until a dive brings the speed back.
 *   Climbing costs speed, diving gains it. On the ground the plane taxis like a car (left/right) and lifts off by
 *   pulling the nose up (looking up) once it is fast enough. Jump brakes.
 * - **Helicopters:** forward/back fly forward and backward, left/right turn, jump climbs, the sprint key descends and
 *   with neither the helicopter hovers. Without power (no fuel, broken engine or rotor) it autorotates down.
 * - **Damage:** wings and rotors are `propulsion` parts: shooting them off costs their share of lift. Hitting the
 *   ground or a wall faster than `flight.crash_speed` damages the hull (and shakes the crew).
 */
object FlightModel {
    /** Sprint key of the local pilot, set by the client every tick: helicopters descend while it is held. */
    @JvmStatic
    @Volatile
    var descendHeld = false

    /** Set by the client: tells the server that the aircraft this client flies crashed at [impact] blocks per tick. */
    var reportCrash: (DriveableEntity, Float) -> Unit = { _, _ -> }

    /** Speed lost per tick climbing straight up (gained diving straight down). */
    private const val PATH_GRAVITY = 0.03
    private const val SPOOL = 0.02f
    private const val SPIN = 1.2f
    /** Steady descent of an unpowered helicopter (autorotation), blocks per tick. */
    private const val AUTOROTATION = 0.4
    private const val BANK_SMOOTHING = 0.25f

    /** Every tick on every side: spool the engine up or down and turn the rotor/propeller. */
    fun tickPower(v: DriveableEntity) {
        val s = v.flight
        val running = (v.controllingPassenger != null || v.autopilot != null) && v.hasFuel && v.engineWorks
        s.power = Mth.approach(s.power, if (running) 1f else 0f, SPOOL)
        s.prevSpin = s.spin
        s.spin += s.power * SPIN
    }

    /** The pilot of a flying plane steers with their view, so the plane must not turn them along with it. */
    fun steersByView(v: DriveableEntity, passenger: Entity): Boolean =
        v.definition?.type == VehicleType.PLANE && !v.onGround() && passenger == v.controllingPassenger

    /** One tick of flight; sets the aircraft's rotation and [DriveableEntity.getDeltaMovement]. */
    fun simulate(v: DriveableEntity, def: VehicleDefinition, c: DriveableEntity.Controls) {
        val s = v.flight
        if (!s.simulating) {
            // Taking over from the other side (a new pilot, or the pilot bailed out): keep flying at the current speed.
            v.speed = if (def.type == VehicleType.PLANE) s.velocity.length() else s.velocity.dot(Vec3.directionFromRotation(0f, v.yRot))
            s.sink = 0.0
            s.simulating = true
        }
        if (def.type == VehicleType.PLANE) plane(v, def, def.flight, c, s) else helicopter(v, def, def.flight, c, s)
    }

    private fun plane(v: DriveableEntity, def: VehicleDefinition, f: FlightDefinition, c: DriveableEntity.Controls, s: FlightState) {
        val pilot = v.controllingPassenger
        s.throttle = if (s.power <= 0f) 0f else (s.throttle + c.throttle * f.throttleRate).coerceIn(0f, 1f)
        val thrust = s.throttle * s.power
        // Shot-off wings (propulsion parts) cost their share of lift.
        val lift = (v.speed / f.liftSpeed).coerceIn(0.0, 1.0) * v.propulsion
        val grounded = v.onGround()
        val wanted = (if (pilot != null) -pilot.xRot else c.lift * f.maxPitch).coerceIn(-f.maxPitch, f.maxPitch)
        var pitch = -v.xRot
        if (grounded) {
            // Taxiing: nose wheel / tail wheel steering; rotate for take-off only once the wings carry.
            v.yRot -= c.steer * def.turnSpeed * 0.5f * (v.speed / (f.liftSpeed * 0.3)).coerceAtMost(1.0).toFloat()
            pitch = Mth.approach(pitch, if (lift >= 1.0) wanted.coerceAtLeast(0f) else 0f, f.pitchSpeed)
        } else {
            // In the air the nose follows the pilot's view; control surfaces need airspeed.
            val authority = lift.toFloat().coerceAtLeast(0.25f)
            val wantedYaw = pilot?.yRot ?: (v.yRot - c.steer * def.turnSpeed)
            v.yRot += Mth.wrapDegrees(wantedYaw - v.yRot).coerceIn(-def.turnSpeed * authority, def.turnSpeed * authority)
            pitch = Mth.approach(pitch, wanted, f.pitchSpeed * authority)
            // Stalling: the nose drops until the dive brings the airspeed back.
            if (lift < 1.0) pitch -= ((1 - lift) * f.pitchSpeed).toFloat()
        }
        pitch = pitch.coerceIn(-90f, 90f)
        v.xRot = -pitch

        val climb = Math.sin(Math.toRadians(pitch.toDouble()))
        var speed = (v.speed + thrust * def.acceleration - climb * PATH_GRAVITY) * (1 - def.drag)
        if (c.brake) speed = approach(speed, 0.0, def.braking * if (grounded) 1.0 else 0.3)
        if (grounded && thrust == 0f) speed *= 0.97 // rolling resistance
        // Top speed in level flight; diving goes faster.
        v.speed = speed.coerceIn(0.0, def.maxSpeed * (1 + 0.4 * (-climb).coerceAtLeast(0.0)))

        s.sink = when {
            grounded -> 0.0
            lift >= 1.0 -> s.sink * 0.8
            else -> (s.sink - v.gravity * (1 - lift)) * 0.98
        }
        var motion = Vec3.directionFromRotation(v.xRot, v.yRot).scale(v.speed).add(0.0, s.sink, 0.0)
        // Always pushed down a little on the ground, like vanilla entities (otherwise move() loses the ground every other tick).
        if (grounded && motion.y <= 0.0) motion = Vec3(motion.x, -v.gravity, motion.z)
        if (v.isInWater) {
            v.speed *= 0.8
            motion = motion.multiply(0.8, 0.5, 0.8).add(0.0, -0.02, 0.0)
        }
        v.deltaMovement = motion
    }

    private fun helicopter(v: DriveableEntity, def: VehicleDefinition, f: FlightDefinition, c: DriveableEntity.Controls, s: FlightState) {
        // Broken rotors (propulsion parts) cost their share of power.
        val power = s.power * v.propulsion
        val grounded = v.onGround()
        if (power > 0.3f) v.yRot -= c.steer * def.turnSpeed * power
        val speed = if (grounded && c.lift <= 0f) v.speed * 0.7 // skids on the ground
        else v.speed + c.throttle * def.acceleration * power
        v.speed = (speed * (1 - def.drag)).coerceIn(-def.maxReverseSpeed, def.maxSpeed)

        var vy = v.deltaMovement.y
        vy = if (power >= 0.5f) Mth.lerp(0.2, vy, c.lift * f.climbSpeed * power)
        else maxOf(vy - v.gravity * 0.5, -AUTOROTATION)
        if (grounded && vy <= 0.0) vy = -v.gravity
        if (v.isInWater) vy = vy * 0.5 - 0.01
        v.xRot = 0f
        v.deltaMovement = Vec3.directionFromRotation(0f, v.yRot).scale(v.speed).add(0.0, vy, 0.0)
    }

    /**
     * After the simulating side moved the aircraft: if it ran into something and lost more than `crash_speed`, the
     * hull takes damage (the server applies it; a pilot's client reports the impact).
     */
    fun afterMove(v: DriveableEntity, def: VehicleDefinition, intended: Vec3, from: Vec3) {
        if (!v.horizontalCollision && !v.verticalCollision) return
        val impact = intended.subtract(v.position().subtract(from)).length()
        if (v.horizontalCollision) v.speed *= 0.3
        if (impact <= def.flight.crashSpeed) return
        val level = v.level()
        if (level is ServerLevel) crash(level, v, impact.toFloat()) else reportCrash(v, impact.toFloat())
    }

    /** Damage for an impact at [impact] blocks per tick (server). */
    fun crash(level: ServerLevel, v: DriveableEntity, impact: Float) {
        val f = v.definition?.flight ?: return
        v.crash(level, ((impact - f.crashSpeed) * f.crashDamage).toFloat())
    }

    /** Every tick on every side, after moving: bank into turns, helicopters tilt with their speed. */
    fun updatePose(v: DriveableEntity, def: VehicleDefinition) {
        val s = v.flight
        val f = def.flight
        val pos = v.position()
        s.velocity = s.lastPos?.let(pos::subtract) ?: Vec3.ZERO
        s.lastPos = pos
        val turn = Mth.wrapDegrees(v.yRot - v.yRotO)
        val (pitch, roll) = if (def.type == VehicleType.PLANE) {
            -v.xRot to if (v.onGround()) 0f else (turn * f.bank).coerceIn(-f.maxBank, f.maxBank)
        } else {
            val forward = s.velocity.dot(Vec3.directionFromRotation(0f, v.yRot)) / def.maxSpeed.coerceAtLeast(0.01)
            -forward.toFloat().coerceIn(-1f, 1f) * f.tilt to (turn * f.bank * 0.5f).coerceIn(-25f, 25f)
        }
        v.setBodyPose(pitch, Mth.lerp(BANK_SMOOTHING, v.bodyRoll, roll), 0f, smooth = def.type == VehicleType.HELICOPTER)
    }

    private fun approach(value: Double, target: Double, step: Double) =
        if (value < target) minOf(value + step, target) else maxOf(value - step, target)
}
